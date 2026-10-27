package edu.neu.aijiaoxue.ui.input

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.ai.AnalysisInputs
import edu.neu.aijiaoxue.data.ai.AnalysisJson
import edu.neu.aijiaoxue.data.entity.*
import edu.neu.aijiaoxue.data.model.*
import edu.neu.aijiaoxue.data.repository.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class InputSourceOption(val key: String, val label: String, val sourceRefJson: String)
data class InputWorkspaceUiState(
    val loading: Boolean = true, val busy: Boolean = false, val error: String? = null, val message: String? = null,
    val role: Role? = null, val task: SupervisionTask? = null, val course: Course? = null,
    val editable: Boolean = false, val references: List<CourseReferenceVersion> = emptyList(),
    val phases: List<PhaseSegment> = emptyList(), val phaseStatus: PhaseStatus? = null,
    val transcripts: List<TranscriptVersion> = emptyList(), val facts: List<ManualClassroomFact> = emptyList(),
    val sources: List<InputSourceOption> = emptyList(), val professionalTerms: String = "", val ignoredExpressions: String = "",
    val resolvedSource: ResolvedEvidenceSource? = null,
)

/** taskId=0 + courseId 用于课程要求页面；普通课堂入口只需传 taskId。 */
class InputWorkspaceViewModel(app: Application, private val actorId: Long, private val taskId: Long = 0, private val courseId: Long? = null) : AndroidViewModel(app) {
    private val db = AppDatabase.get(app)
    private val _uiState = MutableStateFlow(InputWorkspaceUiState())
    val uiState = _uiState.asStateFlow()
    init { refresh() }

    fun refresh() { viewModelScope.launch { runCatching { load() }.onFailure { _uiState.update { s -> s.copy(loading = false, error = it.message ?: "无法读取课堂资料") } } } }

    private suspend fun load() {
        val actor = requireNotNull(db.userDao().getById(actorId)) { "请重新登录" }
        val task = if (taskId > 0) requireNotNull(db.analysisSourceDao().ownedTask(actorId, taskId)) { "无权编辑此课堂输入" } else null
        val course = requireNotNull(db.courseDao().getById(task?.courseId ?: requireNotNull(courseId))) { "课程不存在" }
        val refs = CourseReferenceRepository(db).listVersions(actorId, course.id)
        val latestPhase = task?.let { db.analysisSourceDao().latestPhases(it.id) }
        val transcripts = task?.let { TranscriptRepository(db).list(actorId, it.id) }.orEmpty().sortedBy { firstClassMs(it) ?: Long.MAX_VALUE }
        val facts = task?.let { db.analysisSourceDao().facts(it.id) }.orEmpty()
        val sources = mutableListOf<InputSourceOption>()
        if (task != null) {
            db.captureDao().getMaterials(task.id).forEach { m ->
                val hash = runCatching { EvidenceSourceResolver.fileHash(EvidenceSourceResolver.resolveMaterialFile(getApplication(), task.id, m)) }.getOrDefault("")
                sources += InputSourceOption("m${m.id}", "${m.type.label} #${m.id} · ${m.offsetMs / 1000} 秒", ref("MATERIAL", task.id, m.id, hash = hash, quote = m.note.orEmpty()).put("classAtMs", m.offsetMs).toString())
            }
            db.captureDao().observeEvents(task.id).first().forEach { e -> sources += InputSourceOption("e${e.id}", "互动 ${e.type.label} · ${e.offsetMs / 1000} 秒", ref("INTERACTION_EVENT", task.id, e.id, hash = AnalysisJson.hashJson(AnalysisInputs.eventJson(e)), quote = e.note.orEmpty()).put("classAtMs", e.offsetMs).toString()) }
            db.captureDao().observeEngagements(task.id).first().forEach { e -> sources += InputSourceOption("p${e.id}", "参与 ${e.type.label} · ${e.offsetMs / 1000} 秒", ref("ENGAGEMENT_RECORD", task.id, e.id, quote = e.description.orEmpty()).put("classAtMs", e.offsetMs).toString()) }
            transcripts.forEach { t -> val a = JSONObject(t.payloadJson).getJSONArray("segments"); for (i in 0 until a.length()) {
                val s = a.getJSONObject(i)
                sources += InputSourceOption("t${t.id}:${s.getString("segmentId")}", "转写：${s.getString("text").take(32)}", ref("TRANSCRIPT_SEGMENT", task.id, t.id, t.revision, t.contentHash, s.getString("segmentId"), s.getString("text")).toString())
            } }
        }
        val terms = db.analysisSourceDao().termExclusions(course.id)?.payloadJson?.let(::JSONObject)
        fun termsText(key: String) = terms?.optJSONArray(key)?.let { a -> (0 until a.length()).joinToString("\n") { a.getString(it) } }.orEmpty()
        val editable = task != null && db.evaluationDao().getByTask(task.id)?.status != EvalStatus.SUBMITTED
        _uiState.update { it.copy(loading = false, error = null, role = actor.role, task = task, course = course, editable = editable, references = refs, phases = latestPhase?.let { p -> TeachingPhaseRepository.decode(p.payloadJson) }.orEmpty(), phaseStatus = latestPhase?.status, transcripts = transcripts, facts = facts, sources = sources, professionalTerms = termsText("professionalTerms"), ignoredExpressions = termsText("ignoredExpressions")) }
    }

    private fun mutate(message: String, action: suspend () -> Unit) {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(busy = true, error = null, message = null) }
        viewModelScope.launch {
            try { action(); load(); _uiState.update { it.copy(message = message) } }
            catch (e: Exception) { _uiState.update { it.copy(error = e.message ?: "操作失败") } }
            finally { _uiState.update { it.copy(busy = false) } }
        }
    }

    fun createReference(chapter: String, source: String, points: String, objectives: String) = mutate("课程要求已保存为新版本") {
        val pointRows = points.lines().map(String::trim).filter(String::isNotEmpty)
        val objectiveRows = objectives.lines().map(String::trim).filter(String::isNotEmpty)
        val payload = JSONObject().put("schemaVersion", 1).put("knowledgePoints", JSONArray().apply {
            pointRows.forEachIndexed { i, text -> put(JSONObject().put("id", "KP${i + 1}").put("chapter", chapter).put("text", text).put("required", true)) }
        }).put("objectives", JSONArray().apply {
            objectiveRows.forEachIndexed { i, line ->
                val pieces = line.split('|', limit = 2)
                put(JSONObject().put("id", "GO${i + 1}").put("chapter", chapter).put("text", pieces[0].trim()).put("knowledgePointIds", JSONArray(pieces.getOrNull(1)?.split(',')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty())))
            }
        }).toString()
        CourseReferenceRepository(db).createVersion(actorId, requireNotNull(_uiState.value.course).id, CourseReferenceDraft(chapter, source, payload))
    }

    fun bindReference(versionId: Long, pointIds: List<String>, objectiveIds: List<String>) = mutate("本次课堂教学范围已绑定") { CourseReferenceRepository(db).bindToTask(actorId, taskId, versionId, pointIds, objectiveIds) }
    fun savePhases(segments: List<PhaseSegment>, confirm: Boolean) = mutate(if (confirm) "教学环节已确认，下游分析使用此版本" else "环节草稿已保存，确认后供分析使用") { TeachingPhaseRepository(db).appendVersion(actorId, taskId, segments, confirm) }
    fun addFact(description: String, sourceJson: String?, atSeconds: String, startSeconds: String, endSeconds: String, supersedesId: Long?) = mutate("人工事实已独立保存") {
        fun seconds(text: String): Long? = text.trim().takeIf(String::isNotEmpty)?.let { value -> val seconds = requireNotNull(value.toLongOrNull()) { "时间请输入整数秒" }; require(seconds in 0..Long.MAX_VALUE / 1000) { "时间范围无效" }; seconds * 1000 }
        ManualFactRepository(db).add(actorId, taskId, ManualFactDraft(description, sourceJson, seconds(atSeconds), seconds(startSeconds), seconds(endSeconds), supersedesId), UUID.randomUUID().toString())
    }
    fun correctTranscript(versionId: Long, segmentId: String, text: String, role: SpeakerRole) = mutate("转写校订已保存为新版本") { TranscriptRepository(db).correctSegment(actorId, taskId, versionId, segmentId, text, role) }
    fun saveTerms(terms: String, ignored: String) = mutate("术语排除已保存为新版本") { TranscriptRepository(db).saveTermExclusions(actorId, taskId, terms.lines(), ignored.lines()) }
    fun openSource(json: String) = mutate("已核对来源权限") { val source = EvidenceSourceResolver(getApplication(), db).openSource(actorId, taskId, json); _uiState.update { it.copy(resolvedSource = source) } }
    fun closeSource() { _uiState.update { it.copy(resolvedSource = null) } }

    companion object {
        /** 只依据保存的课堂映射排序；没有映射的录音不能用主键推断授课先后。 */
        fun firstClassMs(version: TranscriptVersion): Long? = runCatching {
            AnalysisJson.objects(JSONObject(version.payloadJson).optJSONArray("segments")).flatMap { AnalysisJson.objects(it.optJSONArray("classRanges")) }
                .mapNotNull { range -> range.optLong("startMs", -1).takeIf { it >= 0 && range.optLong("endMs", -1) > it } }.minOrNull()
        }.getOrNull()
        fun ref(kind: String, taskId: Long, rowId: Long, revision: Int = 0, hash: String = "", itemKey: String? = null, quote: String = ""): JSONObject = JSONObject().put("kind", kind).put("taskId", taskId).put("rowId", rowId).put("revision", revision).put("contentHash", hash).put("itemKey", itemKey ?: JSONObject.NULL).put("quote", quote).put("classRanges", JSONArray())
    }
}
