package edu.neu.aijiaoxue.data.repository

import androidx.room.withTransaction
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.ai.AIAnalyzer
import edu.neu.aijiaoxue.data.ai.AIResult
import edu.neu.aijiaoxue.data.entity.AiAnalysisResult
import edu.neu.aijiaoxue.data.entity.AiEvidence
import edu.neu.aijiaoxue.data.entity.EngagementRecord
import edu.neu.aijiaoxue.data.entity.InteractionEvent
import edu.neu.aijiaoxue.data.entity.Material
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

    private data class AnalysisInput(
        val task: SupervisionTask,
        val materials: List<Material>,
        val events: List<InteractionEvent>,
        val engagements: List<EngagementRecord>,
        val previousResults: List<AiAnalysisResult>,
        val latestCorrectionIds: Map<Long, Long?>,
    )

    /** 必须由调用方的 Room 事务包裹，确保所有输入来自同一时点。 */
    private suspend fun readInput(taskId: Long, actorId: Long): AnalysisInput {
        val task = loadTask(taskId, actorId)
        require(canAnalyze(task)) { "请先结束课堂采集" }
        val capture = db.captureDao()
        val previous = db.aiAnalysisDao().getByTask(taskId)
        return AnalysisInput(
            task = task,
            materials = capture.getMaterials(taskId).sortedWith(compareBy({ it.capturedAt }, { it.id })),
            events = capture.observeEvents(taskId).first().sortedWith(compareBy({ it.occurredAt }, { it.id })),
            engagements = capture.observeEngagements(taskId).first().sortedWith(compareBy({ it.recordedAt }, { it.id })),
            previousResults = previous,
            // verified 已经为 true 时再次审核不会改变分析行，必须单独跟踪审核版本。
            latestCorrectionIds = previous.associate { it.id to db.aiCorrectionDao().getByAnalysis(it.id).firstOrNull()?.id },
        )
    }

    suspend fun analyzeAndSave(taskId: Long, actorId: Long): List<AiAnalysisResult> {
        // 慢分析不占用写事务；返回后重新验证输入、审核版本与权限。
        val input = db.withTransaction { readInput(taskId, actorId) }
        val results = analyzer.analyze(taskId, input.materials, input.events, input.engagements)
        validateResults(results, input)
        return db.withTransaction {
            require(readInput(taskId, actorId) == input) { "课堂记录、审核或分析结果已改变，请重新分析" }
            db.aiAnalysisDao().replaceForTask(taskId, results.map { result ->
                val entity = AiAnalysisResult(
                    taskId = taskId, type = result.type, content = result.content,
                    confidence = result.confidence, startMs = result.startMs, endMs = result.endMs,
                )
                entity to result.evidences.map { evidence ->
                    AiEvidence(
                        analysisId = 0, taskId = taskId, materialId = evidence.materialId,
                        eventId = evidence.eventId, startMs = evidence.startMs, endMs = evidence.endMs,
                        description = evidence.description,
                    )
                }
            })
        }
    }

    private fun validateResults(results: List<AIResult>, input: AnalysisInput) {
        // 清空属于独立的明确操作，分析器无结果不能被解释为删除已有审核。
        require(results.isNotEmpty()) { "分析未返回有效结果，原分析、审核与证据已保留，请重试" }
        require(results.all { it.content.isNotBlank() && it.confidence.isFinite() && it.confidence in 0f..1f }) {
            "分析返回了无效结果，原结果已保留"
        }
        require(results.all { validRange(it.startMs, it.endMs) }) { "分析时间范围无效" }
        val materialIds = input.materials.map { it.id }.toSet()
        val eventIds = input.events.map { it.id }.toSet()
        results.flatMap { it.evidences }.forEach { evidence ->
            require(evidence.description.isNotBlank() && validRange(evidence.startMs, evidence.endMs)) {
                "分析返回了无效证据，原结果已保留"
            }
            // 外键只能证明记录存在；此处还须证明引用来自本次任务的分析输入。
            require((evidence.materialId == null || evidence.materialId in materialIds) &&
                (evidence.eventId == null || evidence.eventId in eventIds)) { "证据引用不属于本次任务，原结果已保留" }
            // 两种 ID 均为空时仅保留文字/时间提示，不能伪造原始材料或事件引用。
        }
    }

    private fun validRange(start: Long?, end: Long?) =
        (start == null || start >= 0) && (end == null || end >= 0) &&
            (start == null || end == null || end >= start)

    fun observeResults(taskId: Long, actorId: Long) = flow {
        db.withTransaction { loadTask(taskId, actorId) }
        emitAll(db.aiAnalysisDao().observeForActor(taskId, actorId))
    }

    suspend fun clearResults(taskId: Long, actorId: Long) = db.withTransaction {
        loadTask(taskId, actorId)
        // v5 外键级联删除当前审核及证据；已发布依据和已采用建议保存在独立快照中。
        db.aiAnalysisDao().deleteByTask(taskId)
    }
}
