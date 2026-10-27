package edu.neu.aijiaoxue.ui.analysis

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.ai.CloudAnalysisPipeline
import edu.neu.aijiaoxue.data.entity.*
import edu.neu.aijiaoxue.data.model.*
import edu.neu.aijiaoxue.data.remote.AnalysisSettings
import edu.neu.aijiaoxue.data.repository.EvidenceSourceResolver
import edu.neu.aijiaoxue.data.repository.ResolvedEvidenceSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

data class CloudAnalysisState(
    val loading: Boolean = true, val busy: Boolean = false, val task: SupervisionTask? = null,
    val materials: List<Material> = emptyList(), val jobs: List<AnalysisJob> = emptyList(),
    val results: List<AiAnalysisResult> = emptyList(), val evidence: Map<Long, List<AiEvidence>> = emptyMap(),
    val source: ResolvedEvidenceSource? = null, val error: String? = null,
    val transcriptCount: Int = 0, val imageTextCount: Int = 0,
)

class CloudAnalysisViewModel(private val db: AppDatabase, context: Context, private val actorId: Long, private val taskId: Long) : ViewModel() {
    private val appContext = context.applicationContext
    private val pipeline = CloudAnalysisPipeline(db, appContext)
    private val mutex = Mutex()
    private val state = MutableStateFlow(CloudAnalysisState())
    val uiState = state.asStateFlow()
    val settings = AnalysisSettings(appContext)
    init { reload() }
    fun reload() { viewModelScope.launch { safely { load() } } }
    private suspend fun load() {
        val task = requireNotNull(db.analysisSourceDao().ownedTask(actorId, taskId)) { "无权访问此课堂" }
        val results = db.aiAnalysisDao().getHistoryForActor(taskId, actorId)
        val evidence = results.associate { it.id to db.aiEvidenceDao().getByAnalysis(it.id) }
        state.update { it.copy(loading = false, task = task, materials = db.captureDao().getMaterials(taskId),
            results = results, evidence = evidence, jobs = db.analysisSourceDao().jobsForTask(taskId),
            transcriptCount = db.analysisSourceDao().transcripts(taskId).size, imageTextCount = db.analysisSourceDao().imageTexts(taskId).size) }
    }
    fun start(operation: AnalysisOperation, materialId: Long? = null) = work {
        state.update { it.copy(error = null) }; pipeline.start(actorId, taskId, operation, materialId)
    }
    fun refresh() = work { refreshPending() }
    suspend fun poll() {
        if (!mutex.tryLock()) return
        try { safely { refreshPending(); load() } } finally { mutex.unlock() }
    }
    private suspend fun refreshPending() {
        requireNotNull(db.analysisSourceDao().ownedTask(actorId, taskId))
        db.analysisSourceDao().jobsForTask(taskId).filter { it.state in CloudAnalysisPipeline.ACTIVE }.forEach { pipeline.refresh(actorId, it.id) }
    }
    fun cancel(jobId: Long) { viewModelScope.launch { safely { pipeline.cancel(actorId, jobId); load() } } }
    fun retry(job: AnalysisJob) {
        val json = org.json.JSONObject(job.inputManifestJson)
        start(job.operation, if (json.isNull("materialId")) null else json.getLong("materialId"))
    }
    fun openEvidence(id: Long) { viewModelScope.launch { safely {
        val source = EvidenceSourceResolver(appContext, db).openCurrent(actorId, taskId, id)
        state.update { it.copy(source = source) }
    } } }
    fun closeSource() { state.update { it.copy(source = null) } }
    fun saveSettings(url: String, token: String): String? = try { settings.save(url, token); null } catch (e: Exception) { e.message }
    private fun work(block: suspend () -> Unit) { viewModelScope.launch {
        if (!mutex.tryLock()) return@launch
        state.update { it.copy(busy = true) }
        try { safely { block() }; safely { load() } } finally { state.update { it.copy(busy = false) }; mutex.unlock() }
    } }
    private suspend fun safely(block: suspend () -> Unit) {
        try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { state.update { it.copy(loading = false, error = e.message ?: "分析操作失败") } }
    }
}
