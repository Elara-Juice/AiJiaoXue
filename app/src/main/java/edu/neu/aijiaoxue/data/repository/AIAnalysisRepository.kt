package edu.neu.aijiaoxue.data.repository

import androidx.room.withTransaction
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.ai.AIAnalyzer
import edu.neu.aijiaoxue.data.entity.AiAnalysisResult
import edu.neu.aijiaoxue.data.entity.SupervisionTask
import edu.neu.aijiaoxue.data.model.TaskStatus
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow

/** 分析器仍由团队注入；本模块负责授权、输入一致性与原子持久化。 */
class AIAnalysisRepository(private val db: AppDatabase, private val analyzer: AIAnalyzer) {
    suspend fun loadTask(taskId: Long, actorId: Long): SupervisionTask =
        requireNotNull(db.taskDao().getEvaluationTaskForActor(taskId, actorId)) { "任务不存在或无权限访问" }

    fun canAnalyze(task: SupervisionTask) =
        task.status == TaskStatus.PENDING_EVALUATION || task.status == TaskStatus.COMPLETED

    suspend fun analyzeAndSave(taskId: Long, actorId: Long): List<AiAnalysisResult> {
        // 慢分析不占用写事务；返回后重新验证输入与权限，防止写入过期结果。
        val input = db.withTransaction {
            val task = loadTask(taskId, actorId)
            require(canAnalyze(task)) { "请先结束课堂采集" }
            Triple(db.captureDao().getMaterials(taskId), db.captureDao().observeEvents(taskId).first(),
                db.aiAnalysisDao().getByTask(taskId))
        }
        val results = analyzer.analyze(taskId, input.first, input.second)
        require(results.all { it.content.isNotBlank() && it.confidence.isFinite() && it.confidence in 0f..1f }) {
            "分析返回了无效结果，原结果已保留"
        }
        require(results.all { (it.startMs == null || it.startMs >= 0) &&
            (it.endMs == null || it.endMs >= 0) &&
            (it.startMs == null || it.endMs == null || it.endMs >= it.startMs) }) { "分析时间范围无效" }
        return db.withTransaction {
            require(canAnalyze(loadTask(taskId, actorId))) { "任务状态已改变，请重新分析" }
            require(db.captureDao().getMaterials(taskId) == input.first &&
                db.captureDao().observeEvents(taskId).first() == input.second &&
                db.aiAnalysisDao().getByTask(taskId) == input.third) { "课堂材料或分析结果已改变，请重试" }
            removeCurrentResults(taskId)
            results.map { result ->
                val entity = AiAnalysisResult(taskId = taskId, type = result.type, content = result.content,
                    confidence = result.confidence, startMs = result.startMs, endMs = result.endMs)
                entity.copy(id = db.aiAnalysisDao().insertAnalysis(entity))
            }
            // AIResult没有证据引用，不能把结论复制一遍伪装成证据。
        }
    }

    fun observeResults(taskId: Long, actorId: Long) = flow {
        db.withTransaction { loadTask(taskId, actorId) }
        emitAll(db.aiAnalysisDao().observeForActor(taskId, actorId))
    }

    suspend fun clearResults(taskId: Long, actorId: Long) = db.withTransaction {
        loadTask(taskId, actorId)
        removeCurrentResults(taskId)
    }

    private suspend fun removeCurrentResults(taskId: Long) {
        // main没有AI表外键；显式删除保证v2来源数据也不会留下孤立记录。
        // 已发布依据和已采用建议由独立快照保存，不在这里删除。
        db.aiAnalysisDao().getByTask(taskId).forEach {
            db.aiCorrectionDao().deleteByAnalysis(it.id)
            db.aiEvidenceDao().deleteByAnalysis(it.id)
        }
        db.aiAnalysisDao().deleteByTask(taskId)
    }
}
