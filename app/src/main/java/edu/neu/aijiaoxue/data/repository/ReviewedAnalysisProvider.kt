package edu.neu.aijiaoxue.data.repository

import androidx.room.withTransaction
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.entity.ReviewPublication
import edu.neu.aijiaoxue.data.entity.ReviewedAnalysisSnapshot
import edu.neu.aijiaoxue.data.model.EvalStatus
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
            if (!analysis.verified || correction?.reviewerId != actorId ||
                correction.originalContent != analysis.content || correction.correctedContent.isBlank()) return@mapNotNull null
            val evidence = db.aiEvidenceDao().observeEvidence(analysis.id).first().filter { it.taskId == taskId }
            val materials = db.captureDao().getMaterials(taskId).associateBy { it.id }
            val events = db.captureDao().observeEvents(taskId).first().associateBy { it.id }
            val summary = if (evidence.isEmpty()) "该审核结论未关联课堂证据，需结合人工记录判断。"
                else evidence.joinToString("\n") { row ->
                    val sourceState = when {
                        row.materialId == null && row.eventId == null -> "历史文字说明，未绑定原始课堂证据："
                        (row.materialId != null && !materials.containsKey(row.materialId)) ||
                            (row.eventId != null && !events.containsKey(row.eventId)) -> "原引用已失效或不属于本任务，仅留存文字："
                        else -> "已关联本任务课堂记录："
                    }
                    val range = row.startMs?.let { start ->
                        "（课堂偏移 ${start / 1000} 秒" + (row.endMs?.let { "至 ${it / 1000} 秒" } ?: "") + "）"
                    }.orEmpty()
                    sourceState + row.description.take(500) + range
                }
            ReviewedAnalysisSnapshot(
                publicationId = 0, analysisId = analysis.id, correctionId = correction.id,
                reviewerId = actorId, type = analysis.type, originalContent = analysis.content,
                // accepted=false 是已修正的有效结论，不是“拒绝审核”。
                effectiveContent = correction.correctedContent.trim(), evidenceSummary = summary,
                reviewedAt = correction.createdAt, versionKey = "analysis-${analysis.id}/review-${correction.id}",
            )
        }

    /** 由评价提交事务调用；提交前后与审核写入同受 Room 事务串行保护。 */
    internal suspend fun publish(actorId: Long, taskId: Long, evaluationId: Long) {
        requireNotNull(db.taskDao().getEvaluationTaskForActor(taskId, actorId)) { "无权限发布审核依据" }
        val evaluation = requireNotNull(db.evaluationDao().getByTask(taskId))
        require(evaluation.id == evaluationId && evaluation.status == EvalStatus.SUBMITTED) { "请先提交本次评价" }
        val dao = db.improvementSuggestionDao()
        check(dao.publicationForEvaluation(evaluationId) == null) { "本次评价已发布审核依据" }
        val rows = currentReviewed(actorId, taskId)
        val publicationId = dao.insertPublication(ReviewPublication(
            evaluationId = evaluationId, taskId = taskId, supervisorId = actorId, publishedAt = System.currentTimeMillis(),
        ))
        rows.forEach { dao.insertSnapshot(it.copy(publicationId = publicationId)) }
    }
}
