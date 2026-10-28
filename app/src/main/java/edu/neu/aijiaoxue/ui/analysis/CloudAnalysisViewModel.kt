package edu.neu.aijiaoxue.ui.analysis

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.ai.CloudAnalysisPipeline
import edu.neu.aijiaoxue.data.ai.AnalysisRunCoordinator
import edu.neu.aijiaoxue.data.entity.*
import edu.neu.aijiaoxue.data.model.*
import edu.neu.aijiaoxue.data.remote.AnalysisApiClient
import edu.neu.aijiaoxue.data.remote.AnalysisSettings
import edu.neu.aijiaoxue.data.repository.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

data class CloudAnalysisState(
    val loading: Boolean = true, val busy: Boolean = false, val refreshing: Boolean = false,
    val task: SupervisionTask? = null, val courseName: String = "课堂分析",
    val materials: List<Material> = emptyList(), val jobs: List<AnalysisJob> = emptyList(),
    val results: List<AiAnalysisResult> = emptyList(), val evidence: Map<Long, List<AiEvidence>> = emptyMap(),
    val presentations: Map<Long, AnalysisPresentation> = emptyMap(),
    val source: ResolvedEvidenceSource? = null, val error: String? = null, val message: String? = null,
    val transcriptCount: Int = 0, val imageTextCount: Int = 0,
    val transcribedMaterialIds: Set<Long> = emptySet(), val recognizedMaterialIds: Set<Long> = emptySet(),
    val configured: Boolean = false, val connectionMessage: String? = null,
    val plan: AnalysisPlanPreview? = null, val run: AnalysisRunSnapshot? = null,
)

/** UI operations are queued; only background polling may be skipped. */
class CloudAnalysisViewModel(private val db: AppDatabase, context: Context, private val actorId: Long, private val taskId: Long) : ViewModel() {
    private val appContext = context.applicationContext
    private val pipeline = CloudAnalysisPipeline(db, appContext)
    private val runs = AnalysisRunRepository(db, appContext)
    private val coordinator = AnalysisRunCoordinator(db, appContext)
    private val mutex = Mutex()
    private val pending = mutableSetOf<String>()
    private var foreground = false
    private var pollIndex = 0
    private var restored = false
    private val state = MutableStateFlow(CloudAnalysisState())
    val uiState = state.asStateFlow()
    val settings = AnalysisSettings(appContext)

    init { reload() }
    fun reload() = work("reload", "正在读取课堂资料") { load() }
    private suspend fun load() {
        val task = requireNotNull(db.analysisSourceDao().ownedTask(actorId, taskId)) { "无权访问此课堂" }
        if (!restored) {
            runs.latest(actorId, taskId)?.let { coordinator.pauseRun(actorId, it.run.id) }
            restored = true
        }
        val results = db.aiAnalysisDao().getHistoryForActor(taskId, actorId)
        val evidence = results.associate { it.id to db.aiEvidenceDao().getByAnalysis(it.id) }
        val transcripts = db.analysisSourceDao().transcripts(taskId).filter { it.state in setOf(ResultState.SUPPORTED, ResultState.PARTIAL) }
        val images = db.analysisSourceDao().imageTexts(taskId).filter { it.state in setOf(ResultState.SUPPORTED, ResultState.PARTIAL) }
        val materials = db.captureDao().getMaterials(taskId)
        val jobs = db.analysisSourceDao().jobsForTask(taskId)
        val courseName = db.courseDao().getById(task.courseId)?.name ?: "课堂分析"
        val plan = if (task.status in setOf(TaskStatus.PENDING_EVALUATION, TaskStatus.COMPLETED)) runs.preview(actorId, taskId) else null
        val run = runs.latest(actorId, taskId)
        val presentations = withContext(Dispatchers.Default) { results.associate { it.id to AnalysisPresentationMapper.map(it) } }
        val configuration = settings.get()
        state.update { it.copy(loading = false, task = task, courseName = courseName, materials = materials,
            results = results, presentations = presentations, evidence = evidence, jobs = jobs,
            transcriptCount = transcripts.size, imageTextCount = images.size,
            transcribedMaterialIds = transcripts.map { t -> t.materialId }.toSet(), recognizedMaterialIds = images.map { i -> i.materialId }.toSet(),
            plan = plan, run = run, configured = configuration.baseUrl.isNotBlank() && configuration.token.isNotBlank()) }
    }

    fun start(operation: AnalysisOperation, materialId: Long? = null) = work("start-$operation-$materialId", "分析请求已接收") {
        pipeline.start(actorId, taskId, operation, materialId)
    }
    fun startRecommended() = work("recommended", "推荐分析已接收") {
        val id = runs.createRun(actorId, taskId, AnalysisSelection(), UUID.randomUUID().toString())
        coordinator.resumeRun(actorId, id)
        if (foreground) coordinator.tick(actorId, id, true)
    }
    fun resumeRun() = work("resume", "正在继续本轮分析") {
        state.value.run?.run?.id?.let { coordinator.resumeRun(actorId, it) }
    }
    fun continueAvailable() = work("available", "正在处理已成功的材料") {
        state.value.run?.run?.id?.let { coordinator.continueWithAvailable(actorId, it) }
    }
    fun reconfirmRun() = work("reconfirm", "正在核对最新材料") {
        state.value.run?.run?.id?.let { coordinator.reconfirmRun(actorId, it) }
    }
    fun cancelRun() { viewModelScope.launch { safely {
        state.value.run?.run?.id?.let { coordinator.cancelRun(actorId, it) }; load()
    } } }

    fun setForeground(active: Boolean) {
        foreground = active
        if (!active) viewModelScope.launch { safely { runs.latest(actorId, taskId)?.let { coordinator.pauseRun(actorId, it.run.id) }; load() } }
    }
    fun refresh() = work("refresh", "刷新请求已接收") {
        val run = runs.latest(actorId, taskId)
        if (run != null) coordinator.tick(actorId, run.run.id, false)
        db.analysisSourceDao().jobsForTask(taskId).filter { it.state in CloudAnalysisPipeline.ACTIVE && it.id !in run?.steps.orEmpty().mapNotNull { step -> step.jobId } }
            .forEach { pipeline.refresh(actorId, it.id) }
    }
    suspend fun poll() {
        if (!foreground || pending.isNotEmpty() || !mutex.tryLock()) return
        state.update { it.copy(refreshing = true) }
        try { safely {
            val run = runs.latest(actorId, taskId)
            if (run != null) coordinator.tick(actorId, run.run.id, foreground)
            run {
                val jobs = db.analysisSourceDao().jobsForTask(taskId).filter { it.state in CloudAnalysisPipeline.ACTIVE && it.id !in run?.steps.orEmpty().mapNotNull { step -> step.jobId } }
                if (jobs.isNotEmpty()) pipeline.refreshExistingJob(actorId, jobs[pollIndex++ % jobs.size].id)
            }
            load()
        } } finally { state.update { it.copy(refreshing = false) }; mutex.unlock() }
    }

    fun cancel(jobId: Long) { viewModelScope.launch { safely { pipeline.cancel(actorId, jobId); load() } } }
    fun retry(job: AnalysisJob) {
        val json = JSONObject(job.inputManifestJson)
        start(job.operation, if (json.isNull("materialId")) null else json.getLong("materialId"))
    }
    fun openEvidence(id: Long) { viewModelScope.launch { safely {
        val source = EvidenceSourceResolver(appContext, db).openCurrent(actorId, taskId, id)
        state.update { it.copy(source = source) }
    } } }
    fun closeSource() { state.update { it.copy(source = null) } }
    fun saveSettings(url: String, token: String): String? = try {
        settings.save(url, token)
        state.update { it.copy(configured = true, connectionMessage = null) }; null
    } catch (e: Exception) { e.message }
    fun checkConnection() = work("connection", "正在检查代理连接") {
        val json = AnalysisApiClient(settings.get()).request("GET", "/v1/capabilities")
        val diagnostic = json.optJSONObject("diagnostics")
        val available = listOf("semantic" to "文本", "vision" to "图片", "asr" to "转写")
            .filter { json.optBoolean(it.first) }.joinToString("、") { it.second }.ifBlank { "尚未配置云能力" }
        val decode = when {
            diagnostic == null -> "解码环境未检查"
            diagnostic.optBoolean("ffmpegAvailable") && diagnostic.optBoolean("ffprobeAvailable") -> "音频解码工具已就绪"
            else -> "M4A/MP3解码工具待检查"
        }
        state.update { it.copy(connectionMessage = "代理连接与设备鉴权通过。配置：$available。$decode。云服务与公网音频下载仍需真实素材验证。") }
    }
    fun clearNotice() { state.update { it.copy(error = null, message = null) } }

    private fun work(key: String, message: String, block: suspend () -> Unit) {
        if (!pending.add(key)) { state.update { it.copy(message = "此操作已在处理中") }; return }
        state.update { it.copy(busy = true, error = null, message = message) }
        viewModelScope.launch {
            try { mutex.withLock { safely { block() }; safely { load() } } }
            finally { pending.remove(key); state.update { it.copy(busy = pending.isNotEmpty(), message = if (it.error == null) null else it.message) } }
        }
    }
    private suspend fun safely(block: suspend () -> Unit) {
        try { block() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { state.update { it.copy(loading = false, error = e.message ?: "分析操作失败，请重试") } }
    }
}
