package edu.neu.aijiaoxue.data.ai

import android.content.Context
import androidx.room.withTransaction
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.analysis.ExpressionSegment
import edu.neu.aijiaoxue.data.analysis.ExpressionStatistics
import edu.neu.aijiaoxue.data.entity.*
import edu.neu.aijiaoxue.data.model.*
import edu.neu.aijiaoxue.data.remote.AnalysisApiClient
import edu.neu.aijiaoxue.data.remote.AnalysisSettings
import edu.neu.aijiaoxue.data.repository.TranscriptRepository
import edu.neu.aijiaoxue.data.repository.StructuredPayloadValidation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** US15/16/17/19/20/22：网络在Room事务外；晚到结果必须再次验证输入和取消状态。 */
class CloudAnalysisPipeline(private val db: AppDatabase, private val context: Context) {
    private val dao get() = db.analysisSourceDao()
    private fun api() = AnalysisApiClient(AnalysisSettings(context).get())

    suspend fun start(actorId: Long, taskId: Long, operation: AnalysisOperation, materialId: Long? = null): Long {
        require(operation !in setOf(AnalysisOperation.LEGACY, AnalysisOperation.PARTICIPATION))
        val manifest = AnalysisInputs(db, context).build(actorId, taskId, operation, materialId)
        val fingerprint = AnalysisInputs.fingerprint(manifest)
        manifest.put("inputFingerprint", fingerprint)
        val now = System.currentTimeMillis()
        val superseded = mutableListOf<String>()
        val job = db.withTransaction {
            // 同一能力和材料的新请求替代旧请求；另一段录音/另一张图片不受影响。
            dao.jobsForTask(taskId).filter { it.operation == operation && it.state in ACTIVE && it.inputFingerprint != fingerprint }.forEach { previous ->
                val old = JSONObject(previous.inputManifestJson)
                val oldMaterial = if (old.isNull("materialId")) null else old.getLong("materialId")
                if (oldMaterial == materialId) {
                    dao.updateJob(previous.copy(state = JobState.CANCELLED, stage = "已由新输入请求替代", updatedAt = now))
                    previous.serverJobId?.let(superseded::add)
                }
            }
            dao.jobsForTask(taskId).firstOrNull { it.operation == operation && it.inputFingerprint == fingerprint && it.state in ACTIVE }
                ?: AnalysisJob(clientRequestId = UUID.randomUUID().toString(), taskId = taskId, operation = operation,
                    inputFingerprint = fingerprint, inputManifestJson = manifest.toString(), createdAt = now, updatedAt = now)
                    .let { it.copy(id = dao.insertJob(it)) }
        }
        superseded.forEach { id -> runCatching { api().request("POST", "/v1/analysis-jobs/$id/cancel", JSONObject()) } }
        if (job.serverJobId != null) return job.id
        try {
            if (operation in LOCAL_OPERATIONS) {
                val result = if (operation == AnalysisOperation.EXPRESSION_STATS) expressionResult(manifest) else interactionResult(manifest)
                applyResult(actorId, job, result, JobState.SUCCEEDED)
                return job.id
            }
            submit(actorId, job)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { recordError(job.id, error) }
        return job.id
    }

    /** 进程重启/请求超时重用同一幂等键；成功响应丢失也不会重复产生云任务。 */
    private suspend fun submit(actorId: Long, requested: AnalysisJob) = submissionLocks.getOrPut(requested.id) { Mutex() }.withLock {
            val job = requireNotNull(dao.getJob(requested.id))
            if (job.state !in ACTIVE || job.serverJobId != null) return@withLock
            val task = requireNotNull(dao.ownedTask(actorId, job.taskId))
            if (task.analysisCaseId == null) dao.setCaseId(task.id, UUID.randomUUID().toString())
            val caseId = requireNotNull(dao.ownedTask(actorId, job.taskId)?.analysisCaseId)
            val manifest = JSONObject(job.inputManifestJson)
            val materialId = if (manifest.isNull("materialId")) null else manifest.getLong("materialId")
            if (materialId != null) {
                val material = requireNotNull(db.captureDao().getMaterial(materialId))
                require(material.taskId == job.taskId)
                val item = manifest.getJSONArray("inputs").getJSONObject(0)
                if (item.isNull("assetId")) {
                  val upload = api().upload(AnalysisInputs.safeFile(context, material), JSONObject().put("caseId", caseId)
                    .put("inputId", item.getString("inputId")).put("kind", item.getString("kind"))
                    .put("sha256", item.getJSONObject("sourceRef").getString("contentHash")))
                  item.put("assetId", upload.getString("assetId"))
                }
            }
            val proceed = db.withTransaction {
                val latest = requireNotNull(dao.getJob(job.id))
                if (latest.state !in ACTIVE) false else {
                    dao.updateJob(latest.copy(inputManifestJson = manifest.toString(), updatedAt = System.currentTimeMillis())); true
                }
            }
            if (!proceed) return@withLock
            val response = api().request("POST", "/v1/analysis-jobs", AnalysisInputs.request(manifest, caseId), job.clientRequestId)
            val serverId = response.optString("jobId").takeIf { it.matches(Regex("[A-Za-z0-9_.:-]{1,160}")) }
                ?: throw java.io.IOException("提交响应缺少任务编号，保留原幂等请求以便恢复")
            val cancelled = db.withTransaction {
                val latest = requireNotNull(dao.getJob(job.id))
                dao.updateJob(latest.copy(serverJobId = serverId, errorCode = null, updatedAt = System.currentTimeMillis()))
                latest.state == JobState.CANCELLED
            }
            if (cancelled) runCatching { api().request("POST", "/v1/analysis-jobs/$serverId/cancel", JSONObject()) }
    }

    private suspend fun recordError(id: Long, error: Exception) = db.withTransaction {
        val job = requireNotNull(dao.getJob(id))
        if (job.state in ACTIVE) dao.updateJob(job.copy(state = if (error is java.io.IOException) job.state else JobState.FAILED,
            stage = if (error is java.io.IOException) "连接中断，可刷新继续" else "处理失败，原结果保留",
            errorCode = error.message?.take(300), updatedAt = System.currentTimeMillis()))
    }

    suspend fun refresh(actorId: Long, localJobId: Long): JobState {
        val job = requireNotNull(dao.getJob(localJobId))
        requireNotNull(dao.ownedTask(actorId, job.taskId)) { "无权查看分析任务" }
        if (job.state !in ACTIVE) return job.state
        if (job.serverJobId == null) {
            if (job.operation in LOCAL_OPERATIONS) start(actorId, job.taskId, job.operation)
            else try { submit(actorId, job) } catch (e: CancellationException) { throw e } catch (e: Exception) { recordError(job.id, e) }
            return requireNotNull(dao.getJob(job.id)).state
        }
        val response = api().request("GET", "/v1/analysis-jobs/${job.serverJobId}")
        val state = JobState.valueOf(response.getString("state"))
        if (state in setOf(JobState.SUCCEEDED, JobState.PARTIAL)) {
            require(response.getString("inputFingerprint") == job.inputFingerprint) { "分析服务返回了其他输入的结果" }
            val manifest = JSONObject(job.inputManifestJson)
            val materialId = if (manifest.isNull("materialId")) null else manifest.getLong("materialId")
            val current = try { AnalysisInputs(db, context).build(actorId, job.taskId, job.operation, materialId) }
                catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            if (current == null || AnalysisInputs.fingerprint(current) != job.inputFingerprint) {
                db.withTransaction {
                    val latest = requireNotNull(dao.getJob(job.id))
                    if (latest.state in ACTIVE) dao.updateJob(latest.copy(state = JobState.STALE_INPUT, stage = "输入已改变，请重新分析", updatedAt = System.currentTimeMillis()))
                }
                return requireNotNull(dao.getJob(job.id)).state
            }
            try { applyResult(actorId, job, response.getJSONObject("result"), state) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { recordError(job.id, e); return requireNotNull(dao.getJob(job.id)).state }
            response.optString("resultHash").takeIf { it.matches(Regex("[a-f0-9]{64}")) }?.let {
                runCatching { api().request("POST", "/v1/analysis-jobs/${job.serverJobId}/ack", JSONObject().put("resultHash", it)) }
            }
        } else db.withTransaction {
            val latest = requireNotNull(dao.getJob(job.id))
            if (latest.state in ACTIVE) dao.updateJob(latest.copy(state = state,
                stage = response.optString("stage"), errorCode = response.optJSONObject("error")?.optString("message"), updatedAt = System.currentTimeMillis()))
        }
        return requireNotNull(dao.getJob(job.id)).state
    }

    suspend fun cancel(actorId: Long, id: Long) {
        val job = requireNotNull(dao.getJob(id)); requireNotNull(dao.ownedTask(actorId, job.taskId))
        db.withTransaction {
            val latest = requireNotNull(dao.getJob(id))
            if (latest.state !in ACTIVE) return@withTransaction
            dao.updateJob(latest.copy(state = JobState.CANCELLED, updatedAt = System.currentTimeMillis()))
        }
        if (dao.getJob(id)?.state != JobState.CANCELLED) return
        job.serverJobId?.let { runCatching { api().request("POST", "/v1/analysis-jobs/$it/cancel", JSONObject()) } }
    }

    private suspend fun applyResult(actorId: Long, job: AnalysisJob, result: JSONObject, state: JobState) = db.withTransaction {
        requireNotNull(dao.ownedTask(actorId, job.taskId)) { "分析权限已改变" }
        val latest = requireNotNull(dao.getJob(job.id))
        if (latest.state !in ACTIVE) return@withTransaction
        val manifest = JSONObject(job.inputManifestJson)
        require(ManifestEvidence.isCurrent(db, job.taskId, manifest)) { "来源版本已改变" }
        val payload = result.getJSONObject("payload")
        val resultState = ResultState.valueOf(result.getString("resultState"))
        val producer = result.optJSONObject("producer") ?: JSONObject().put("provider", "rule").put("model", "expression_exact_v1")
        val materialId = if (manifest.isNull("materialId")) null else manifest.getLong("materialId")
        when (job.operation) {
            AnalysisOperation.TRANSCRIBE -> {
                val input = manifest.getJSONArray("inputs").getJSONObject(0)
                TranscriptRepository(db).saveGenerated(actorId, job.taskId, requireNotNull(materialId), payload.toString(),
                    input.getJSONObject("sourceRef").getString("contentHash"), producer.optString("provider", "bailian"), producer.optString("model"), "bailian_timestamp_v1", resultState)
            }
            AnalysisOperation.IMAGE_TEXT -> {
                val input = manifest.getJSONArray("inputs").getJSONObject(0)
                dao.deactivateImageTexts(requireNotNull(materialId))
                dao.insertImageText(ImageTextVersion(taskId = job.taskId, materialId = materialId, revision = dao.imageTextRevision(materialId) + 1,
                    fileHash = input.getJSONObject("sourceRef").getString("contentHash"), payloadJson = payload.toString(), contentHash = AnalysisJson.hash(payload.toString()),
                    provider = producer.optString("provider", "deepseek"), model = producer.optString("model"), state = resultState, createdAt = System.currentTimeMillis()))
                dao.bumpInputRevision(job.taskId)
            }
            else -> {
                StructuredPayloadValidation.validate(job.operation, payload, payload, manifest, job.taskId)
                val references = ManifestEvidence.resolve(payload, manifest, job.taskId)
                require(resultState != ResultState.SUPPORTED || references.isNotEmpty()) { "有结论的分析必须关联真实来源" }
                val limitations = result.optJSONArray("limitations")?.let { (0 until it.length()).joinToString("；") { index -> it.getString(index) } }.orEmpty()
                val analysis = AiAnalysisResult(taskId = job.taskId, type = AnalysisJson.analysisType(job.operation), content = AnalysisJson.summary(payload) + if (limitations.isNotBlank()) "\n限制：$limitations" else "", confidence = 0f,
                    operation = job.operation, schemaVersion = 1, payloadJson = payload.toString(), inputFingerprint = job.inputFingerprint, runId = job.id.toString(),
                    resultState = resultState, sourceMode = if (job.operation in LOCAL_OPERATIONS) SourceMode.DETERMINISTIC_RULE else SourceMode.CLOUD_MODEL, producerJson = producer.toString())
                dao.ownedTask(actorId, job.taskId) ?: error("无权保存")
                db.aiAnalysisDao().activateBatch(job.taskId, job.operation, listOf(analysis to references))
            }
        }
        dao.updateJob(latest.copy(state = state, resultJson = result.toString(), stage = "已保存，待人工复核", errorCode = null, updatedAt = System.currentTimeMillis()))
    }

    private suspend fun expressionResult(manifest: JSONObject): JSONObject {
        val segments = mutableListOf<ExpressionSegment>(); val sourceBySegment = mutableMapOf<String, Pair<String, JSONObject>>()
        AnalysisJson.objects(manifest.getJSONArray("inputs")).filter { it.getString("kind") == "TRANSCRIPT" }.forEach { input ->
            AnalysisJson.objects(input.getJSONObject("payload").optJSONArray("segments")).forEach { segment ->
                val id = "${input.getString("inputId")}:${segment.getString("segmentId")}"
                segments += ExpressionSegment(id, segment.getString("text"), segment.optString("speakerRole") == "TEACHER")
                sourceBySegment[id] = input.getString("inputId") to segment
            }
        }
        val exclusions = manifest.optJSONObject("termExclusions")
        val terms = setOf("professionalTerms", "ignoredExpressions").flatMap { key -> exclusions?.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty() }.toSet()
        val teacherOnly = segments.any { it.teacher }
        val counts = ExpressionStatistics.calculate(segments, terms, teacherOnly)
        val rows = JSONArray(counts.map { count -> JSONObject().put("expression", count.expression).put("count", count.count)
            .put("occurrences", JSONArray(count.occurrences.map { hit ->
                val (inputId, segment) = sourceBySegment.getValue(hit.segmentId)
                JSONObject().put("inputId", inputId).put("segmentId", segment.getString("segmentId")).put("charStart", hit.charStart).put("charEnd", hit.charEnd)
            }))
            .put("evidenceRefs", JSONArray(count.occurrences.map { hit ->
                val (inputId, segment) = sourceBySegment.getValue(hit.segmentId)
                JSONObject().put("inputId", inputId).put("itemKey", segment.getString("segmentId"))
                    .put("quote", segment.getString("text").substring(hit.charStart, hit.charEnd))
            })) })
        return JSONObject().put("resultState", if (rows.length() > 0) "SUPPORTED" else "INSUFFICIENT_INPUT")
            .put("payload", JSONObject().put("scope", if (teacherOnly) "人工标注的教师句段" else "课堂全部转写（尚未标注教师角色）")
                .put("expressions", rows).put("note", if (segments.isEmpty()) "没有有效转写" else if (counts.isEmpty()) "未检出达到阈值的重复表达候选" else "候选需要人工判断；次数不等于语言问题"))
    }
    private fun interactionResult(manifest: JSONObject): JSONObject {
        val inputs = AnalysisJson.objects(manifest.getJSONArray("inputs"))
        val events = inputs.filter { it.getString("kind") == "INTERACTION_EVENT" }
        val context = inputs.firstOrNull { it.getString("kind") == "CONTEXT" }?.getJSONObject("payload")?.optJSONObject("context")
        val minutes = context?.optLong("classDurationMs", 0)?.div(60000.0) ?: 0.0
        val rows = JSONArray(events.groupBy { it.getJSONObject("payload").getJSONArray("events").getJSONObject(0).getString("type") }.map { (type, group) ->
            JSONObject().put("label", runCatching { InteractionType.valueOf(type).label }.getOrDefault(type)).put("count", group.size)
                .put("evidenceRefs", JSONArray(group.map { JSONObject().put("inputId", it.getString("inputId")).put("itemKey", it.getJSONObject("payload").getJSONArray("events").getJSONObject(0).getString("id")) }))
        })
        return JSONObject().put("resultState", if (events.isEmpty()) "INSUFFICIENT_INPUT" else "SUPPORTED")
            .put("producer", JSONObject().put("provider", "rule").put("model", "interaction_event_count_v1"))
            .put("payload", JSONObject().put("scope", "人工记录的互动事件").put("activities", rows)
                .put("每分钟事件数", if (minutes > 0) events.size / minutes else JSONObject.NULL)
                .put("note", if (events.isEmpty()) "未记录互动事件，无法据此判断课堂互动情况" else "仅统计已记录事件，不推断未记录活动或教学质量"))
    }
    companion object {
        private val submissionLocks = ConcurrentHashMap<Long, Mutex>()
        val ACTIVE = setOf(JobState.QUEUED, JobState.RUNNING, JobState.CANCEL_REQUESTED)
        val LOCAL_OPERATIONS = setOf(AnalysisOperation.EXPRESSION_STATS, AnalysisOperation.INTERACTION_STATS)
    }
}

/** 字段形状之外再校验引用与引文；结果只能引用此次请求已经授权的输入。 */
object ManifestEvidence {
    fun findItem(value: Any?, key: String): JSONObject? = when (value) {
        is JSONObject -> if (value.optString("id") == key || value.optString("segmentId") == key) value else value.keys().asSequence().mapNotNull { findItem(value.opt(it), key) }.firstOrNull()
        is JSONArray -> (0 until value.length()).firstNotNullOfOrNull { findItem(value.opt(it), key) }
        else -> null
    }
    fun resolve(payload: JSONObject, manifest: JSONObject, taskId: Long): List<AiEvidence> {
        val inputs = AnalysisJson.objects(manifest.getJSONArray("inputs")).associateBy { it.getString("inputId") }
        return AnalysisJson.refs(payload).distinctBy { AnalysisJson.canonical(it) }.map { reference ->
            val input = requireNotNull(inputs[reference.getString("inputId")]) { "结果引用了未知输入" }
            val source = JSONObject(input.getJSONObject("sourceRef").toString())
            val itemKey = reference.optString("itemKey").takeIf { it.isNotBlank() && it != "null" }
            val item = if (itemKey == null) input.getJSONObject("payload") else requireNotNull(findItem(input.getJSONObject("payload"), itemKey)) { "来源片段不存在" }
            val quote = reference.optString("quote").takeIf { it.isNotBlank() && it != "null" }
            if (quote != null) require(containsExactText(item, quote)) { "引文不属于来源正文" }
            source.put("itemKey", itemKey ?: JSONObject.NULL).put("quote", quote ?: JSONObject.NULL)
            item.optJSONArray("classRanges")?.let { source.put("classRanges", it) }
            item.optString("mappingQuality").takeIf { it.isNotBlank() }?.let { source.put("mappingQuality", it) }
            if (item.has("mediaStartMs") && item.has("mediaEndMs")) source.put("mediaRange", JSONObject().put("startMs", item.getLong("mediaStartMs")).put("endMs", item.getLong("mediaEndMs")))
            if (item.has("startMs") && item.has("endMs")) source.put("classRanges", JSONArray().put(JSONObject().put("startMs", item.getLong("startMs")).put("endMs", item.getLong("endMs"))))
            val kind = source.getString("kind")
            AiEvidence(analysisId = 0, taskId = taskId, materialId = if (kind == "MATERIAL") source.getLong("rowId") else null,
                eventId = if (kind == "INTERACTION_EVENT") source.getLong("rowId") else null, description = quote ?: AnalysisJson.summary(item).take(500), sourceRefJson = source.toString())
        }
    }
    private fun containsExactText(value: Any?, quote: String): Boolean = when (value) {
        is String -> quote.filterNot(Char::isWhitespace).let { normalized -> normalized.isNotBlank() && value.filterNot(Char::isWhitespace).contains(normalized) }
        is JSONObject -> value.keys().asSequence().filter { it in setOf("text", "label", "name", "description", "note", "notes", "statement", "reason") }
            .any { value.opt(it) is String && containsExactText(value.opt(it), quote) }
        is JSONArray -> (0 until value.length()).any { containsExactText(value.opt(it), quote) }
        else -> false
    }
    suspend fun isCurrent(db: AppDatabase, taskId: Long, manifest: JSONObject, depth: Int = 0): Boolean {
        if (depth >= 8) return false
        val dao = db.analysisSourceDao()
        val inputs = AnalysisJson.objects(manifest.getJSONArray("inputs"))
        val operation = AnalysisOperation.valueOf(manifest.getString("operation"))
        val task = db.taskDao().getById(taskId) ?: return false
        fun ids(kind: String) = inputs.filter { it.optJSONObject("sourceRef")?.optString("kind") == kind }.map { it.getJSONObject("sourceRef").getLong("rowId") }.toSet()
        if (operation !in setOf(AnalysisOperation.TRANSCRIBE, AnalysisOperation.IMAGE_TEXT)) {
            if (ids("TRANSCRIPT_SEGMENT") != dao.transcripts(taskId).filter { it.state in setOf(ResultState.SUPPORTED, ResultState.PARTIAL) }.map { it.id }.toSet()) return false
            if (operation in setOf(AnalysisOperation.BOPPPS, AnalysisOperation.GOAL_ACTIVITY, AnalysisOperation.TRANSITION, AnalysisOperation.MATERIAL_SUPPORT, AnalysisOperation.INTERACTION_STATS)) {
                if (!(operation == AnalysisOperation.TRANSITION && inputs.any { it.getString("kind") == "BOPPPS" }) && ids("MANUAL_PHASE") != setOfNotNull(dao.currentPhases(taskId)?.id)) return false
                if (ids("MANUAL_FACT") != dao.facts(taskId).map { it.id }.toSet()) return false
                if (ids("INTERACTION_EVENT") != db.captureDao().observeEvents(taskId).first().map { it.id }.toSet()) return false
            }
            if (operation == AnalysisOperation.EXPRESSION_STATS && AnalysisJson.canonical(manifest.optJSONObject("termExclusions") ?: JSONObject()) != AnalysisJson.canonical(dao.termExclusions(task.courseId)?.payloadJson?.let(::JSONObject) ?: JSONObject())) return false
            if (operation in setOf(AnalysisOperation.SYLLABUS_ALIGNMENT, AnalysisOperation.GOAL_ACTIVITY, AnalysisOperation.MATERIAL_SUPPORT)) {
                if (ids("COURSE_REFERENCE") != setOfNotNull(task.courseReferenceVersionId)) return false
                val selection = task.referenceSelectionJson?.let(::JSONObject)
                val course = inputs.firstOrNull { it.getString("kind") == "COURSE_REFERENCE" }?.getJSONObject("payload")
                for (key in listOf("selectedKnowledgePointIds", "selectedObjectiveIds")) {
                    if (AnalysisJson.canonical(selection?.optJSONArray(key)) != AnalysisJson.canonical(course?.optJSONArray(key))) return false
                }
            }
            if (operation == AnalysisOperation.MATERIAL_SUPPORT && ids("IMAGE_TEXT") != dao.imageTexts(taskId).map { it.id }.toSet()) return false
            val context = inputs.firstOrNull { it.getString("kind") == "CONTEXT" }?.getJSONObject("payload")?.optJSONObject("context")
            if (context != null) {
                val audio = db.captureDao().getMaterials(taskId).filter { it.type == MaterialType.AUDIO }
                val transcripts = dao.transcripts(taskId)
                val missing = audio.filter { m -> transcripts.none { it.materialId == m.id && it.state == ResultState.SUPPORTED } }.map { "material-${it.id}" }
                val duration = task.classStartAt?.let { start -> task.classEndAt?.minus(start) }
                if (AnalysisJson.canonical(context.opt("classDurationMs")) != AnalysisJson.canonical(duration)) return false
                if (AnalysisJson.canonical(context.optJSONArray("missingAudioIds")) != AnalysisJson.canonical(JSONArray(missing))) return false
            }
        }
        for (input in inputs) {
            val ref = input.optJSONObject("sourceRef") ?: continue
            val id = ref.getLong("rowId"); val hash = ref.getString("contentHash")
            val valid = when (ref.getString("kind")) {
                "MATERIAL" -> db.captureDao().getMaterial(id)?.taskId == taskId
                "TRANSCRIPT_SEGMENT" -> dao.transcript(id)?.let { it.taskId == taskId && it.isCurrent && it.contentHash == hash } == true
                "IMAGE_TEXT" -> dao.imageText(id)?.let { it.taskId == taskId && it.isCurrent && it.contentHash == hash } == true
                "MANUAL_PHASE" -> dao.phases(id)?.let { it.taskId == taskId && it.isCurrent && it.contentHash == hash } == true
                "COURSE_REFERENCE" -> db.taskDao().getById(taskId)?.courseReferenceVersionId == id && dao.reference(id)?.contentHash == hash
                "INTERACTION_EVENT" -> dao.event(taskId, id)?.let { AnalysisJson.hashJson(AnalysisInputs.eventJson(it)) == hash } == true
                "MANUAL_FACT" -> dao.fact(id)?.let { it.taskId == taskId && it.isCurrent && AnalysisJson.hash(it.description) == hash } == true
                "REVIEWED_ANALYSIS" -> db.aiAnalysisDao().getAnalysisById(id)?.let { a ->
                    val c = db.aiCorrectionDao().getByAnalysis(id).firstOrNull()
                    val original = a.payloadJson?.let(::JSONObject)
                    val parent = a.runId?.toLongOrNull()?.let { dao.getJob(it) }?.inputManifestJson?.let(::JSONObject)
                    a.taskId == taskId && a.isCurrent && a.verified && c?.reviewerId == task.supervisorId && original != null &&
                        c.originalPayloadHash == AnalysisJson.hashJson(original) && AnalysisJson.hashJson(JSONObject(c.correctedPayloadJson ?: a.payloadJson)) == hash &&
                        parent != null && isCurrent(db, taskId, parent, depth + 1)
                } == true
                else -> false
            }
            if (!valid) return false
        }
        return true
    }
}
