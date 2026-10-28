package edu.neu.aijiaoxue.ui.input

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.InputDraftStore
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
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class InputSourceOption(val key: String, val label: String, val sourceRefJson: String)
data class InputWorkspaceUiState(
    val loading: Boolean = true, val busy: Boolean = false, val error: String? = null, val message: String? = null,
    val role: Role? = null, val task: SupervisionTask? = null, val course: Course? = null,
    val editable: Boolean = false, val references: List<CourseReferenceVersion> = emptyList(),
    val phases: List<PhaseSegment> = emptyList(), val phaseStatus: PhaseStatus? = null,
    val phaseVersionId: Long? = null,
    val transcripts: List<TranscriptVersion> = emptyList(), val facts: List<ManualClassroomFact> = emptyList(),
    val sources: List<InputSourceOption> = emptyList(), val professionalTerms: String = "", val ignoredExpressions: String = "",
    val resolvedSource: ResolvedEvidenceSource? = null,
    val editor: InputEditorState = InputEditorState(),
    val phaseMutation: MutationState = MutationState(), val factMutation: MutationState = MutationState(),
    val transcriptMutations: Map<Long, MutationState> = emptyMap(), val draftWarning: String? = null,
)

/** taskId=0 + courseId 用于课程要求页面；普通课堂入口只需传 taskId。 */
class InputWorkspaceViewModel(app: Application, private val actorId: Long, private val taskId: Long = 0, private val courseId: Long? = null) : AndroidViewModel(app) {
    private val db = AppDatabase.get(app)
    private val drafts = InputDraftStore(app, actorId, taskId, courseId)
    private val _uiState = MutableStateFlow(InputWorkspaceUiState())
    val uiState = _uiState.asStateFlow()
    init { viewModelScope.launch {
        try {
            load()
            // 先验证身份和课堂归属，再恢复本账号的私有编辑补丁。
            try { drafts.read()?.let { restored -> _uiState.update { it.copy(editor = restored) } } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { _uiState.update { it.copy(draftWarning = e.message ?: "本地草稿读取失败，原文件已保留") } }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { _uiState.update { it.copy(loading = false, error = e.message ?: "无法读取课堂资料") } }
    } }

    fun refresh() { viewModelScope.launch { try { load() } catch (e: CancellationException) { throw e } catch (e: Exception) { _uiState.update { it.copy(loading = false, error = e.message ?: "无法读取课堂资料") } } } }

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
                val at = MaterialTimeResolver.classroomOffsetMs(m)
                sources += InputSourceOption("m${m.id}", "${m.type.label} #${m.id} · ${at?.let { "课堂 ${inputTime(it)}" } ?: "课堂时间未知"}", ref("MATERIAL", task.id, m.id, hash = hash, quote = m.note.orEmpty()).put("classAtMs", at ?: JSONObject.NULL).toString())
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
        _uiState.update { it.copy(loading = false, role = actor.role, task = task, course = course, editable = editable, references = refs, phases = latestPhase?.let { p -> TeachingPhaseRepository.decode(p.payloadJson) }.orEmpty(), phaseStatus = latestPhase?.status, phaseVersionId = latestPhase?.id, transcripts = transcripts, facts = facts, sources = sources, professionalTerms = termsText("professionalTerms"), ignoredExpressions = termsText("ignoredExpressions")) }
    }

    private fun mutate(message: String, action: suspend () -> Unit) {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(busy = true, error = null, message = null) }
        viewModelScope.launch {
            try { action(); _uiState.update { it.copy(message = message) }; refreshAfterSave() }
            catch (e: CancellationException) { throw e }
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

    private suspend fun refreshAfterSave() {
        try { load() } catch (e: CancellationException) { throw e }
        catch (e: Exception) { _uiState.update { it.copy(error = "已保存，列表刷新失败，请点击刷新；无需重复保存") } }
    }

    private fun edit(transform: (InputEditorState) -> InputEditorState) {
        _uiState.update { it.copy(editor = transform(it.editor)) }
        val snapshot = _uiState.value.editor
        viewModelScope.launch {
            try { drafts.write(snapshot) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { _uiState.update { it.copy(draftWarning = "本次输入仍在页面中，但私有草稿写入失败：${e.message ?: "存储不可用"}") } }
        }
    }

    fun selectTab(tab: InputWorkspaceTab) = edit { it.copy(tab = tab) }
    fun selectMaterial(id: Long) = edit { it.copy(selectedMaterialId = id) }
    fun setTranscriptPage(materialId: Long, page: Int) = edit { it.copy(pages = it.pages + (materialId to page.coerceAtLeast(0))) }
    fun setScroll(tab: InputWorkspaceTab, offset: Int) = edit { it.copy(scrollOffsets = it.scrollOffsets + (tab to offset.coerceAtLeast(0))) }
    fun beginPhase(segment: PhaseSegment? = null) = edit { it.copy(phase = PhaseEditorDraft(true, segment?.id, segment?.label.orEmpty(), segment?.startMs?.let(::inputTime).orEmpty(), segment?.endMs?.let(::inputTime).orEmpty(), segment?.kind ?: ActivityKind.LECTURE, segment?.note.orEmpty(), _uiState.value.phaseVersionId, it.phase.editRevision + 1)) }
    fun changePhase(transform: (PhaseEditorDraft) -> PhaseEditorDraft) = edit { it.copy(phase = transform(it.phase).copy(editRevision = it.phase.editRevision + 1)) }
    fun hidePhase() = edit { it.copy(phase = it.phase.copy(visible = false)) }
    fun resumePhase() = edit { it.copy(phase = it.phase.copy(visible = true)) }
    fun rebasePhase() = edit { it.copy(phase = it.phase.copy(baseVersionId = _uiState.value.phaseVersionId, editRevision = it.phase.editRevision + 1)) }
    fun changeFact(transform: (FactEditorDraft) -> FactEditorDraft) = edit { it.copy(fact = transform(it.fact).copy(editRevision = it.fact.editRevision + 1, requestKey = UUID.randomUUID().toString())) }
    fun beginFact(fact: ManualClassroomFact) = edit { it.copy(fact = FactEditorDraft(fact.description, fact.sourceRefJson, fact.classAtMs?.let(::inputTime).orEmpty(), fact.classStartMs?.let(::inputTime).orEmpty(), fact.classEndMs?.let(::inputTime).orEmpty(), fact.id, it.fact.editRevision + 1)) }
    fun clearFact() = edit { it.copy(fact = FactEditorDraft(editRevision = it.fact.editRevision + 1)) }

    fun savePhaseDraft() {
        val state = _uiState.value; val draft = state.editor.phase
        draftMutation(draft.editRevision, { s, m -> s.copy(phaseMutation = m) }, {
            val segment = PhaseSegment(draft.editingId ?: "phase-${UUID.randomUUID()}", draft.kind, draft.label, requireNotNull(parseInputTime(draft.start)) { "请填写开始时间" }, requireNotNull(parseInputTime(draft.end)) { "请填写结束时间" }, draft.note)
            TeachingPhaseRepository(db).appendVersion(actorId, taskId, (state.phases.filterNot { it.id == draft.editingId } + segment).sortedBy { it.startMs }, false, draft.baseVersionId)
        }) { id -> edit { current -> current.copy(phase = if (current.phase.editRevision == draft.editRevision) PhaseEditorDraft(baseVersionId = id, editRevision = draft.editRevision + 1) else current.phase.copy(baseVersionId = id)) } }
    }

    fun savePhaseList(segments: List<PhaseSegment>, confirm: Boolean) {
        val state = _uiState.value
        draftMutation(state.editor.phase.editRevision, { s, m -> s.copy(phaseMutation = m) }, { TeachingPhaseRepository(db).appendVersion(actorId, taskId, segments, confirm, state.phaseVersionId) }) { id ->
            edit { it.copy(phase = it.phase.copy(baseVersionId = id)) }
        }
    }

    fun saveFactDraft() {
        val draft = _uiState.value.editor.fact
        draftMutation(draft.editRevision, { s, m -> s.copy(factMutation = m) }, {
            ManualFactRepository(db).add(actorId, taskId, ManualFactDraft(draft.description, draft.sourceJson, parseInputTime(draft.at), parseInputTime(draft.start), parseInputTime(draft.end), draft.supersedesId), draft.requestKey)
        }) { edit { current -> if (current.fact.editRevision == draft.editRevision) current.copy(fact = FactEditorDraft(editRevision = draft.editRevision + 1)) else current } }
    }

    fun editTranscript(version: TranscriptVersion, segmentId: String, text: String, role: SpeakerRole) = edit { state ->
        val draft = state.transcripts[version.materialId] ?: TranscriptEditorDraft(version.materialId, version.id)
        state.copy(transcripts = state.transcripts + (version.materialId to draft.copy(changes = draft.changes + (segmentId to TranscriptEdit(segmentId, text, role)), editRevision = draft.editRevision + 1)))
    }

    fun saveTranscriptDraft(materialId: Long) {
        val submitted = _uiState.value.editor.transcripts[materialId] ?: return
        draftMutation(submitted.editRevision, { s, m -> s.copy(transcriptMutations = s.transcriptMutations + (materialId to m)) }, {
            TranscriptRepository(db).correctBatch(actorId, taskId, submitted.baseVersionId, submitted.changes.values.toList())
        }) { id -> edit { state ->
            val current = state.transcripts[materialId] ?: submitted
            state.copy(transcripts = state.transcripts + (materialId to reconcileTranscriptSave(current, submitted, id)))
        } }
    }

    /** 仅在用户已看到冲突和新正文后显式选择。不存在的句段仍保留以便复制，仓储会拒绝。 */
    fun rebaseTranscript(materialId: Long) = edit { state ->
        val current = _uiState.value.transcripts.firstOrNull { it.materialId == materialId } ?: return@edit state
        val draft = state.transcripts[materialId] ?: return@edit state
        state.copy(transcripts = state.transcripts + (materialId to draft.copy(baseVersionId = current.id, editRevision = draft.editRevision + 1)))
    }

    private fun draftMutation(revision: Long, update: (InputWorkspaceUiState, MutationState) -> InputWorkspaceUiState, action: suspend () -> Long, saved: (Long) -> Unit) {
        if (_uiState.value.busy) return
        val pending = MutationState(UUID.randomUUID().toString(), revision, UiMutationStatus.SAVING)
        _uiState.update { update(it.copy(busy = true, error = null, message = null), pending) }
        viewModelScope.launch {
            try {
                val id = action()
                saved(id)
                _uiState.update { update(it.copy(message = "修改已保存"), pending.copy(status = UiMutationStatus.SAVED, savedVersionId = id)) }
                refreshAfterSave()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                _uiState.update { update(it, pending.copy(status = if (e is InputVersionConflictException) UiMutationStatus.CONFLICT else UiMutationStatus.FAILED, error = e.message ?: "保存失败，输入已保留")) }
                if (e is InputVersionConflictException) { try { load() } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { } }
            } finally { _uiState.update { it.copy(busy = false) } }
        }
    }

    companion object {
        /** 只依据保存的课堂映射排序；没有映射的录音不能用主键推断授课先后。 */
        fun firstClassMs(version: TranscriptVersion): Long? = runCatching {
            AnalysisJson.objects(JSONObject(version.payloadJson).optJSONArray("segments")).flatMap { AnalysisJson.objects(it.optJSONArray("classRanges")) }
                .mapNotNull { range -> range.optLong("startMs", -1).takeIf { it >= 0 && range.optLong("endMs", -1) > it } }.minOrNull()
        }.getOrNull()
        fun ref(kind: String, taskId: Long, rowId: Long, revision: Int = 0, hash: String = "", itemKey: String? = null, quote: String = ""): JSONObject = JSONObject().put("kind", kind).put("taskId", taskId).put("rowId", rowId).put("revision", revision).put("contentHash", hash).put("itemKey", itemKey ?: JSONObject.NULL).put("quote", quote).put("classRanges", JSONArray())
    }
}
