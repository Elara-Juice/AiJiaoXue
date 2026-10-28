package edu.neu.aijiaoxue.data.ai

import android.content.Context
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.FileStore
import edu.neu.aijiaoxue.data.entity.*
import edu.neu.aijiaoxue.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** 请求中的sourceRef只存本机，移除后再发往代理。供应商永远不能选择Room主键。 */
class AnalysisInputs(private val db: AppDatabase, private val context: Context) {
    suspend fun build(actorId: Long, taskId: Long, operation: AnalysisOperation, materialId: Long? = null): JSONObject = withContext(Dispatchers.IO) {
        val dao = db.analysisSourceDao()
        val task = requireNotNull(dao.ownedTask(actorId, taskId)) { "无权分析此课堂" }
        require(task.status in setOf(TaskStatus.PENDING_EVALUATION, TaskStatus.COMPLETED)) { "请先结束课堂采集" }
        val inputs = JSONArray()
        fun add(id: String, kind: String, revision: Int, payload: JSONObject, ref: SourceRef?) {
            inputs.put(JSONObject().put("inputId", id).put("kind", kind).put("revision", revision)
                .put("assetId", JSONObject.NULL).put("payload", payload).apply { ref?.let { put("sourceRef", it.json()) } })
        }
        val materials = db.captureDao().getMaterials(taskId)
        if (operation in setOf(AnalysisOperation.TRANSCRIBE, AnalysisOperation.IMAGE_TEXT)) {
            val material = requireNotNull(materials.find { it.id == materialId }) { "请选择本课堂的材料" }
            require((operation == AnalysisOperation.TRANSCRIBE) == (material.type == MaterialType.AUDIO)) { "材料类型不匹配" }
            val file = safeFile(context, material)
            require(file.isFile && file.length() > 0) { "原始文件缺失或为空" }
            require(file.length() <= if (operation == AnalysisOperation.TRANSCRIBE) 262144000 else 10485760) { "材料超过上传限制" }
            if (operation == AnalysisOperation.TRANSCRIBE) require(material.durationMs != null && material.durationMs in 1..7200000) { "录音尚未完成或超过2小时" }
            val hash = hashFile(file)
            add("material-${material.id}", if (material.type == MaterialType.AUDIO) "AUDIO" else "IMAGE", 1, JSONObject(),
                SourceRef(EvidenceKind.MATERIAL, taskId, material.id, 1, null, hash, classAtMs = edu.neu.aijiaoxue.data.repository.MaterialTimeResolver.classroomOffsetMs(material)))
        } else {
            dao.transcripts(taskId).filter { it.state in setOf(ResultState.SUPPORTED, ResultState.PARTIAL) }.forEach {
                add("tr-${it.id}", "TRANSCRIPT", it.revision, JSONObject(it.payloadJson), SourceRef(EvidenceKind.TRANSCRIPT_SEGMENT, taskId, it.id, it.revision, null, it.contentHash))
            }
            if (operation in setOf(AnalysisOperation.SYLLABUS_ALIGNMENT, AnalysisOperation.GOAL_ACTIVITY, AnalysisOperation.MATERIAL_SUPPORT)) {
                task.courseReferenceVersionId?.let { dao.reference(it) }?.let { reference ->
                    require(reference.courseId == task.courseId)
                    val payload = JSONObject(reference.payloadJson)
                    val selection = task.referenceSelectionJson?.let(::JSONObject)
                    for ((key, selectedKey) in listOf("knowledgePoints" to "selectedKnowledgePointIds", "objectives" to "selectedObjectiveIds")) {
                        val selected = selection?.optJSONArray(selectedKey)
                        if (selected != null) {
                            val keys = (0 until selected.length()).map { selected.getString(it) }.toSet()
                            payload.put(key, JSONArray(AnalysisJson.objects(payload.optJSONArray(key)).filter { it.getString("id") in keys }))
                            payload.put(selectedKey, selected)
                        }
                    }
                    add("course-${reference.id}", "COURSE_REFERENCE", reference.revision, payload, SourceRef(EvidenceKind.COURSE_REFERENCE, null, reference.id, reference.revision, null, reference.contentHash))
                }
            }
            if (operation in setOf(AnalysisOperation.BOPPPS, AnalysisOperation.GOAL_ACTIVITY, AnalysisOperation.TRANSITION, AnalysisOperation.MATERIAL_SUPPORT, AnalysisOperation.INTERACTION_STATS)) {
                dao.currentPhases(taskId)?.let { add("phases-${it.id}", "MANUAL_PHASE", it.revision, JSONObject(it.payloadJson), SourceRef(EvidenceKind.MANUAL_PHASE, taskId, it.id, it.revision, null, it.contentHash)) }
                db.captureDao().observeEvents(taskId).first().forEach { event ->
                    val row = eventJson(event)
                    add("event-${event.id}", "INTERACTION_EVENT", 1, JSONObject().put("events", JSONArray().put(row)), SourceRef(EvidenceKind.INTERACTION_EVENT, taskId, event.id, 1, null, AnalysisJson.hashJson(row), classAtMs = event.offsetMs))
                }
                dao.facts(taskId).forEach { fact ->
                    val row = JSONObject().put("id", "fact-${fact.id}").put("text", fact.description).put("classAtMs", fact.classAtMs ?: JSONObject.NULL)
                    add("fact-${fact.id}", "MANUAL_FACT", 1, JSONObject().put("facts", JSONArray().put(row)), SourceRef(EvidenceKind.MANUAL_FACT, taskId, fact.id, 1, null, AnalysisJson.hash(fact.description), classAtMs = fact.classAtMs))
                }
            }
            if (operation == AnalysisOperation.MATERIAL_SUPPORT) dao.imageTexts(taskId).forEach {
                add("image-${it.id}", "IMAGE_TEXT", it.revision, JSONObject(it.payloadJson), SourceRef(EvidenceKind.IMAGE_TEXT, taskId, it.id, it.revision, null, it.contentHash))
            }
            if (operation in setOf(AnalysisOperation.SYLLABUS_ALIGNMENT, AnalysisOperation.TRANSITION, AnalysisOperation.MATERIAL_SUPPORT)) {
                db.aiAnalysisDao().getByTask(taskId).filter { it.verified && it.payloadJson != null && it.operation in setOf(AnalysisOperation.CONTENT, AnalysisOperation.BOPPPS) }.forEach { result ->
                    val correction = db.aiCorrectionDao().getByAnalysis(result.id).firstOrNull()
                    if (correction?.reviewerId == actorId && correction.originalPayloadHash == AnalysisJson.hashJson(JSONObject(result.payloadJson!!))) {
                        val payload = JSONObject(correction.correctedPayloadJson ?: result.payloadJson)
                        val effectiveHash = AnalysisJson.hashJson(payload)
                        if (result.operation == AnalysisOperation.BOPPPS && operation == AnalysisOperation.TRANSITION) {
                            val prior = result.runId?.toLongOrNull()?.let { dao.getJob(it) }?.inputManifestJson?.let(::JSONObject)
                            val timed = JSONArray()
                            if (prior != null) AnalysisJson.objects(payload.optJSONArray("segments")).forEach { segment ->
                                val refs = ManifestEvidence.resolve(segment, prior, taskId)
                                val sourceRanges = refs.flatMap { evidence ->
                                    val ref = JSONObject(evidence.sourceRefJson!!)
                                    if (ref.optString("kind") == "TRANSCRIPT_SEGMENT" && ref.optString("mappingQuality") != "EXACT") emptyList()
                                    else AnalysisJson.objects(ref.optJSONArray("classRanges"))
                                }.distinctBy { AnalysisJson.canonical(it) }.sortedBy { it.getLong("startMs") }
                                val overrides = segment.optJSONArray("reviewedRanges")?.let(AnalysisJson::objects)
                                val ranges = if (overrides == null) sourceRanges else sourceRanges.flatMap { source -> overrides.mapNotNull { chosen ->
                                    val start = maxOf(source.getLong("startMs"), chosen.getLong("startMs"))
                                    val end = minOf(source.getLong("endMs"), chosen.getLong("endMs"))
                                    if (end <= start) null else JSONObject().put("startMs", start).put("endMs", end)
                                } }.distinctBy { AnalysisJson.canonical(it) }.sortedBy { it.getLong("startMs") }
                                ranges.forEachIndexed { i, range -> timed.put(JSONObject(segment.toString()).put("id", "${segment.getString("id")}_range_$i")
                                    .put("startMs", range.getLong("startMs")).put("endMs", range.getLong("endMs"))) }
                            }
                            if (timed.length() > 0) {
                                payload.put("segments", timed)
                                for (index in inputs.length() - 1 downTo 0) if (inputs.getJSONObject(index).getString("kind") == "MANUAL_PHASE") inputs.remove(index)
                            } else return@forEach
                        }
                        add("reviewed-${result.id}", result.operation.name, 1, payload, SourceRef(EvidenceKind.REVIEWED_ANALYSIS, taskId, result.id, 1, null, effectiveHash))
                    }
                }
            }
            val transcripts = dao.transcripts(taskId)
            val audio = materials.filter { it.type == MaterialType.AUDIO }
            val missing = audio.filter { m -> transcripts.none { it.materialId == m.id && it.state == ResultState.SUPPORTED } }.map { "material-${it.id}" }
            val spans = JSONArray()
            audio.forEach { material -> dao.audioSpans(material.id).filter { it.mappingQuality != TimeMappingQuality.UNKNOWN }.forEach {
                spans.put(JSONObject().put("classStartMs", it.classStartMs).put("classEndMs", it.classEndMs).put("mappingQuality", it.mappingQuality.name))
            } }
            val duration = task.classStartAt?.let { start -> task.classEndAt?.minus(start) }
            add("context", "CONTEXT", 1, JSONObject().put("context", JSONObject().put("classDurationMs", duration ?: JSONObject.NULL)
                .put("allAudioTranscribed", audio.isNotEmpty() && missing.isEmpty()).put("missingAudioIds", JSONArray(missing)).put("recordingSpans", spans)), null)
        }
        JSONObject().put("schemaVersion", 1).put("operation", operation.name).put("methodVersion", "${operation.name.lowercase()}_v1")
            .put("caseId", "task-$taskId").put("inputs", inputs).put("materialId", materialId ?: JSONObject.NULL)
            .put("inputRevision", if (operation in setOf(AnalysisOperation.TRANSCRIBE, AnalysisOperation.IMAGE_TEXT)) JSONObject.NULL else task.inputRevision)
            .apply { if (operation == AnalysisOperation.EXPRESSION_STATS) put("termExclusions", dao.termExclusions(task.courseId)?.payloadJson?.let(::JSONObject) ?: JSONObject()) }
    }

    companion object {
        fun fingerprint(manifest: JSONObject) = AnalysisJson.hashJson(JSONObject(manifest.toString()).apply {
            remove("inputFingerprint"); remove("caseId"); remove("inputRevision")
            AnalysisJson.objects(optJSONArray("inputs")).forEach { it.put("assetId", JSONObject.NULL) }
        })
        fun request(manifest: JSONObject, caseId: String): JSONObject = JSONObject(manifest.toString()).apply {
            remove("materialId"); remove("inputRevision"); remove("termExclusions"); put("caseId", caseId)
            AnalysisJson.objects(getJSONArray("inputs")).forEach { input ->
                input.remove("sourceRef")
                // 语义模型只需要句级文本和真实时间，完整词级时戳保留本机可追溯版本。
                if (input.getString("kind") in setOf("TRANSCRIPT", "TRANSCRIPT_SEGMENT")) AnalysisJson.objects(input.getJSONObject("payload").optJSONArray("segments")).forEach { it.remove("words") }
            }
        }
        fun safeFile(context: Context, material: Material): java.io.File {
            val root = FileStore.taskDir(context, material.taskId).canonicalFile
            val file = FileStore.resolve(context, material.filePath).canonicalFile
            require(file.path.startsWith(root.path + java.io.File.separator)) { "材料路径超出课堂目录" }
            return file
        }
        fun hashFile(file: java.io.File): String {
            val hash = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { stream -> val bytes = ByteArray(65536); while (true) { val n = stream.read(bytes); if (n < 0) break; hash.update(bytes, 0, n) } }
            return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        fun eventJson(event: InteractionEvent) = JSONObject().put("id", "event-${event.id}").put("type", event.type.name)
            .put("text", event.note ?: event.type.label).put("atMs", event.offsetMs)
    }
}
