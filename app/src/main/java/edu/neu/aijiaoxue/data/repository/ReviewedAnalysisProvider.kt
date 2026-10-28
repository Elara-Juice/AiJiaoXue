package edu.neu.aijiaoxue.data.repository

import androidx.room.withTransaction
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.entity.AiAnalysisResult
import edu.neu.aijiaoxue.data.entity.AiCorrection
import edu.neu.aijiaoxue.data.entity.ReviewPublication
import edu.neu.aijiaoxue.data.entity.ReviewedAnalysisSnapshot
import edu.neu.aijiaoxue.data.entity.PublishedFactSnapshot
import edu.neu.aijiaoxue.data.entity.PublishedEvidenceSnapshot
import edu.neu.aijiaoxue.data.ai.AnalysisJson
import edu.neu.aijiaoxue.data.ai.ManifestEvidence
import edu.neu.aijiaoxue.data.model.EvalStatus
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.first

/** US27/38：只消费能够核验审核人的结论；教师只能读取随正式评价发布的快照。 */
class ReviewedAnalysisProvider(private val db: AppDatabase) {
    suspend fun forSupervisor(actorId: Long, taskId: Long): List<ReviewedAnalysisSnapshot> = db.withTransaction {
        requireNotNull(db.taskDao().getEvaluationTaskForActor(taskId, actorId)) { "无权限查看本次审核结果" }
        currentReviewed(actorId, taskId)
    }

    private suspend fun currentReviewed(actorId: Long, taskId: Long): List<ReviewedAnalysisSnapshot> =
        db.aiAnalysisDao().getByTask(taskId).mapNotNull { analysis ->
            // 不能仅信任 verified；旧版记录没有审核人，必须重新审核。
            val correction = db.aiCorrectionDao().getByAnalysis(analysis.id).firstOrNull()
            if (correction == null) return@mapNotNull null
            if (analysis.schemaVersion == 1) {
                val job = analysis.runId?.toLongOrNull()?.let { db.analysisSourceDao().getJob(it) } ?: return@mapNotNull null
                if (job.taskId != taskId || job.operation != analysis.operation || job.inputFingerprint != analysis.inputFingerprint) return@mapNotNull null
                val manifest = JSONObject(job.inputManifestJson)
                if (!ManifestEvidence.isCurrent(db, taskId, manifest)) return@mapNotNull null
                val original = analysis.payloadJson?.let(::JSONObject) ?: return@mapNotNull null
                val effective = correction.correctedPayloadJson?.let(::JSONObject) ?: return@mapNotNull null
                if (correction.originalPayloadHash != AnalysisJson.hashJson(original)) return@mapNotNull null
                if (runCatching { StructuredPayloadValidation.validate(analysis.operation, original, effective, manifest, taskId) }.isFailure) return@mapNotNull null
                val evidence = ManifestEvidence.resolve(effective, manifest, taskId)
                return@mapNotNull createReviewedAnalysisSnapshot(analysis, correction, actorId, evidence.joinToString("\n") { it.description }.ifBlank { "发布时没有结构化来源引用" })?.copy(schemaVersion = 1, originalPayloadJson = analysis.payloadJson, effectivePayloadJson = correction.correctedPayloadJson, inputFingerprint = analysis.inputFingerprint, sourceMode = analysis.sourceMode, producerJson = analysis.producerJson, resultState = analysis.resultState)
            }
            val evidence = db.aiEvidenceDao().getByAnalysis(analysis.id).filter { it.taskId == taskId }
            val materials = db.captureDao().getMaterials(taskId).associateBy { it.id }
            val events = db.captureDao().observeEvents(taskId).first().associateBy { it.id }
            val summary = if (evidence.isEmpty()) "该审核结论未关联课堂证据，需结合人工记录判断。"
                else evidence.joinToString("\n") { row ->
                    val sourceState = when {
                        row.materialId == null && row.eventId == null -> "文字或时间提示，未绑定原始课堂证据："
                        (row.materialId != null && !materials.containsKey(row.materialId)) ||
                            (row.eventId != null && !events.containsKey(row.eventId)) -> "原引用已失效或不属于本任务，仅留存文字："
                        else -> "已关联本任务课堂记录："
                    }
                    val range = row.startMs?.let { start ->
                        "（课堂偏移 ${start / 1000} 秒" + (row.endMs?.let { "至 ${it / 1000} 秒" } ?: "") + "）"
                    }.orEmpty()
                    sourceState + row.description.take(500) + range
                }
            createReviewedAnalysisSnapshot(analysis, correction, actorId, summary)
        }

    /** 由评价提交事务调用；提交前后与审核写入同受 Room 事务串行保护。 */
    internal suspend fun publish(actorId: Long, taskId: Long, evaluationId: Long) {
        requireNotNull(db.taskDao().getEvaluationTaskForActor(taskId, actorId)) { "无权限发布审核依据" }
        val evaluation = requireNotNull(db.evaluationDao().getByTask(taskId))
        require(evaluation.id == evaluationId && evaluation.status == EvalStatus.SUBMITTED) { "请先提交本次评价" }
        val dao = db.improvementSuggestionDao()
        check(dao.publicationForEvaluation(evaluationId) == null) { "本次评价已发布审核依据" }
        val rows = currentReviewed(actorId, taskId)
        val sources = db.analysisSourceDao()
        val task = requireNotNull(db.taskDao().getById(taskId))
        val course = requireNotNull(db.courseDao().getById(task.courseId))
        // 先取得候选上下文再创建 publication，避免按发布状态选源时读到空快照。
        val phases = sources.currentPhases(taskId)
        val reference = task.courseReferenceVersionId?.let { sources.reference(it) }
        val manifests = JSONObject()
        rows.filter { it.schemaVersion == 1 }.forEach { snapshot ->
            val analysis = requireNotNull(db.aiAnalysisDao().getAnalysisById(snapshot.analysisId))
            val job = requireNotNull(analysis.runId?.toLongOrNull()?.let { sources.getJob(it) })
            manifests.put(snapshot.analysisId.toString(), JSONObject(job.inputManifestJson))
        }
        val context = JSONObject().put("schemaVersion", 1).put("courseName", course.name).put("courseCode", course.code)
            .put("teacherName", db.userDao().getById(course.teacherId)?.name.orEmpty()).put("supervisorName", db.userDao().getById(task.supervisorId)?.name.orEmpty())
            .put("taskCode", task.code).put("supervisionDate", task.supervisionDate).put("period", task.period).put("chapter", task.chapter ?: JSONObject.NULL)
            .put("className", course.className ?: JSONObject.NULL).put("schedule", course.schedule ?: JSONObject.NULL).put("location", course.location ?: JSONObject.NULL)
            .put("classStartAt", task.classStartAt ?: JSONObject.NULL).put("classEndAt", task.classEndAt ?: JSONObject.NULL)
            .put("manualPhases", phases?.payloadJson?.let(::JSONObject) ?: JSONObject.NULL).put("manualPhaseVersionId", phases?.id ?: JSONObject.NULL)
            .put("courseReference", reference?.payloadJson?.let(::JSONObject) ?: JSONObject.NULL).put("courseReferenceVersionId", reference?.id ?: JSONObject.NULL)
            .put("courseReferenceSource", reference?.sourceDescription ?: JSONObject.NULL).put("referenceSelection", task.referenceSelectionJson?.let(::JSONObject) ?: JSONObject.NULL)
            .put("inputManifests", manifests)
            .put("materialSummaries", JSONArray(db.captureDao().getMaterials(taskId).map { m -> JSONObject().put("id", m.id).put("type", m.type.label).put("note", m.note ?: JSONObject.NULL).put("capturedAt", m.capturedAt).put("isImported", m.isImported).put("offsetMs", MaterialTimeResolver.classroomOffsetMs(m) ?: JSONObject.NULL).put("timeMappingQuality", if (MaterialTimeResolver.classroomOffsetMs(m) == null) "UNKNOWN" else "EXACT").put("durationMs", m.durationMs ?: JSONObject.NULL) }))
        val now = System.currentTimeMillis()
        val publicationId = dao.insertPublication(ReviewPublication(
            evaluationId = evaluationId, taskId = taskId, supervisorId = actorId, publishedAt = now, contextSnapshotJson = context.toString(),
        ))
        rows.forEach { row ->
            val snapshotId = dao.insertSnapshot(row.copy(publicationId = publicationId))
            val evidence = if (row.schemaVersion == 1) ManifestEvidence.resolve(JSONObject(requireNotNull(row.effectivePayloadJson)), manifests.getJSONObject(row.analysisId.toString()), taskId) else db.aiEvidenceDao().getByAnalysis(row.analysisId).filter { it.taskId == taskId }
            evidence.forEachIndexed { index, item ->
                val ref = item.sourceRefJson ?: item.materialId?.let { JSONObject().put("kind", "MATERIAL").put("taskId", taskId).put("rowId", it).toString() }
                    ?: item.eventId?.let { JSONObject().put("kind", "INTERACTION_EVENT").put("taskId", taskId).put("rowId", it).toString() }
                sources.insertPublishedEvidence(PublishedEvidenceSnapshot(publicationId = publicationId, analysisSnapshotId = snapshotId, ordinal = index, taskId = taskId, sourceRefJson = ref, excerpt = item.description, sourceHash = ref?.let(::JSONObject)?.optString("contentHash")?.takeIf(String::isNotBlank), availabilityAtPublish = if (ref == null) "SUMMARY_ONLY" else "AVAILABLE", capturedAt = now))
            }
        }
        sources.facts(taskId).forEach { fact ->
            val id = sources.insertPublishedFact(PublishedFactSnapshot(publicationId = publicationId, sourceFactId = fact.id, description = fact.description, sourceRefJson = fact.sourceRefJson, classAtMs = fact.classAtMs, classStartMs = fact.classStartMs, classEndMs = fact.classEndMs, recordedBy = fact.recordedBy, recordedAt = fact.recordedAt))
            if (fact.sourceRefJson != null) sources.insertPublishedEvidence(PublishedEvidenceSnapshot(publicationId = publicationId, factSnapshotId = id, ordinal = 0, taskId = taskId, sourceRefJson = fact.sourceRefJson, excerpt = fact.description, sourceHash = JSONObject(fact.sourceRefJson).optString("contentHash").takeIf(String::isNotBlank), availabilityAtPublish = "AVAILABLE", capturedAt = now))
        }
    }
}

/** Pure boundary validation so reviewed results cannot publish stale or unauthored corrections. */
internal fun createReviewedAnalysisSnapshot(
    analysis: AiAnalysisResult,
    correction: AiCorrection,
    actorId: Long,
    evidenceSummary: String,
): ReviewedAnalysisSnapshot? {
    if (!analysis.isCurrent || !analysis.verified || correction.reviewerId != actorId ||
        correction.originalContent != analysis.content || correction.correctedContent.isBlank()) return null
    return ReviewedAnalysisSnapshot(
        publicationId = 0,
        analysisId = analysis.id,
        correctionId = correction.id,
        reviewerId = actorId,
        type = analysis.type.name,
        originalContent = analysis.content,
        // accepted=false 是已修正的有效结论，不是“拒绝审核”。
        effectiveContent = correction.correctedContent.trim(),
        evidenceSummary = evidenceSummary,
        reviewedAt = correction.createdAt,
        versionKey = "analysis-${analysis.id}/review-${correction.id}",
    )
}
