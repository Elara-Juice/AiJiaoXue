package edu.neu.aijiaoxue.data.repository

import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.ai.AIAnalyzer
import edu.neu.aijiaoxue.data.entity.AiAnalysisResult
import edu.neu.aijiaoxue.data.entity.AiEvidence
import edu.neu.aijiaoxue.data.entity.SupervisionTask
import edu.neu.aijiaoxue.data.model.TaskStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

class AIAnalysisRepository(private val db: AppDatabase, private val analyzer: AIAnalyzer) {
    /** 权限放在查询层：只有任务的督导本人能分析（AGENTS.md 硬性规则 6）。 */
    suspend fun loadTask(taskId: Long, actorId: Long): SupervisionTask =
        db.taskDao().getEvaluationTaskForActor(taskId, actorId) ?: throw IllegalStateException("无权限访问该任务")

    /** 课堂采集结束后才能分析，进行中的课堂数据还不完整。 */
    fun canAnalyze(task: SupervisionTask) =
        task.status == TaskStatus.PENDING_EVALUATION || task.status == TaskStatus.COMPLETED

    suspend fun analyzeAndSave(taskId: Long, actorId: Long): List<AiAnalysisResult> {
        val task = loadTask(taskId, actorId)
        check(canAnalyze(task)) { "请先结束课堂采集再进行 AI 分析" }
        val capture = db.captureDao()
        val results = analyzer.analyze(
            taskId,
            capture.getMaterials(taskId),
            capture.observeEvents(taskId).first(),
            capture.observeEngagements(taskId).first(),
        )
        return db.aiAnalysisDao().replaceForTask(taskId, results.map { result ->
            AiAnalysisResult(
                taskId = taskId, type = result.type, content = result.content, confidence = result.confidence,
                startMs = result.startMs, endMs = result.endMs,
            ) to result.evidences.map {
                AiEvidence(
                    analysisId = 0, taskId = taskId, materialId = it.materialId, eventId = it.eventId,
                    startMs = it.startMs, endMs = it.endMs, description = it.description,
                )
            }
        })
    }

    fun observeResults(taskId: Long): Flow<List<AiAnalysisResult>> = db.aiAnalysisDao().observeAnalysis(taskId)

    suspend fun clearResults(taskId: Long) = db.aiAnalysisDao().deleteByTask(taskId)
}
