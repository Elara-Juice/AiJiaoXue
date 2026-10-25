package edu.neu.aijiaoxue.ui.improve

import androidx.room.withTransaction
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.entity.ImprovementEvidence
import edu.neu.aijiaoxue.data.entity.ImprovementExecution
import edu.neu.aijiaoxue.data.model.MeasureStatus
import edu.neu.aijiaoxue.ui.evaluation.DraftTransactions
import kotlinx.coroutines.sync.withLock

data class ExecutionInput(
    val implementationNote: String,
    val actualAdjustment: String,
    val implementedAt: Long,
    val requestKey: String,
    val status: MeasureStatus? = null,
)

data class ExecutionEntry(val execution: ImprovementExecution, val evidence: List<ImprovementEvidence>)
data class ExecutionContext(val record: ImprovementRecord, val executions: List<ExecutionEntry>)
data class ExecutionAccess(val execution: ImprovementExecution, val taskId: Long, val measureId: Long)
data class EvidenceAccess(val evidence: ImprovementEvidence, val taskId: Long)

/** US43/44：教师写入实际做法，已确认计划和已发布来源始终只读。 */
class ImprovementExecutionRepository(private val db: AppDatabase) {
    private val dao = db.improvementExecutionDao()

    /**
     * 教师仅看本人；管理员及原任务督导可按任务读取实施摘要。
     * FOLLOW_UP 类型不构成授权，新督导须等待真实复评关联接入，不能读取任意旧任务。
     */
    suspend fun load(actorId: Long, taskId: Long): ExecutionContext = DraftTransactions.mutex.withLock {
        db.withTransaction {
            val task = requireNotNull(dao.taskForReader(actorId, taskId)) { "改进记录不存在、尚未确认或无权限访问" }
            val plan = requireNotNull(db.improvementDao().getPlanByTask(taskId))
            val evaluation = requireNotNull(db.evaluationDao().getByTask(taskId))
            val items = db.improvementDao().getItems(plan.id)
            val suggestions = db.improvementSuggestionDao()
            val publication = suggestions.publicationForEvaluation(evaluation.id)
            // 不调用再次获取 DraftTransactions.mutex 的旧 Repository，避免非重入锁死锁。
            val record = ImprovementRecord(
                task = task,
                course = requireNotNull(db.courseDao().getById(task.courseId)),
                evaluation = evaluation,
                issues = db.evaluationDao().getIssues(evaluation.id),
                plan = plan,
                items = items,
                // 旧表仅对 itemId 建外键，仍须校验冗余 planId，不能加载串到其他计划的行。
                measures = dao.measuresForReader(actorId, taskId),
                suggestions = suggestions.suggestions(plan.id),
                reviewedAnalyses = publication?.let { suggestions.snapshots(it.id) }.orEmpty(),
                reviewPublished = publication != null,
            )
            ExecutionContext(record, dao.executionsForReader(actorId, taskId).map {
                ExecutionEntry(it, dao.evidenceForReader(actorId, it.id))
            })
        }
    }

    /** 请求键只执行一次；重试不会再次改变已被后续操作更新的措施状态。 */
    suspend fun append(actorId: Long, taskId: Long, measureId: Long, input: ExecutionInput): Long =
        DraftTransactions.mutex.withLock {
            db.withTransaction {
                requireNotNull(dao.measureForWriter(actorId, taskId, measureId)) { "措施不存在、尚未确认或无权限修改" }
                val now = System.currentTimeMillis()
                val note = input.implementationNote.trim()
                val adjustment = input.actualAdjustment.trim()
                require(note.isNotEmpty() && note.length <= MAX_TEXT_LENGTH) { "请填写1至5000字的实施说明" }
                require(adjustment.isNotEmpty() && adjustment.length <= MAX_TEXT_LENGTH) { "请填写1至5000字的实际调整" }
                require(input.implementedAt in 1..(now + CLOCK_TOLERANCE_MS)) { "实施时间无效或晚于当前时间" }
                validateRequestKey(input.requestKey)
                val previous = dao.executionForRequest(measureId, input.requestKey)
                if (previous != null) {
                    require(previous.recordedBy == actorId && previous.implementationNote == note &&
                        previous.actualAdjustment == adjustment && previous.implementedAt == input.implementedAt) {
                        "同一保存请求内容已改变，请重新发起保存"
                    }
                    return@withTransaction previous.id
                }
                val id = dao.insertExecution(ImprovementExecution(
                    measureId = measureId,
                    implementationNote = note,
                    actualAdjustment = adjustment,
                    implementedAt = input.implementedAt,
                    recordedBy = actorId,
                    createdAt = now,
                    requestKey = input.requestKey,
                ))
                input.status?.let { status ->
                    check(db.improvementDao().updateMeasureStatusForTeacher(measureId, actorId, status, now) == 1) {
                        "状态更新失败，实施记录未保存"
                    }
                }
                id
            }
        }

    /** 文件复制之前调用，必须具备教师写权限；管理员/督导只读不允许导入附件。 */
    suspend fun requireExecution(actorId: Long, executionId: Long): ExecutionAccess =
        DraftTransactions.mutex.withLock { db.withTransaction { executionForWriter(actorId, executionId) } }

    private suspend fun executionForWriter(actorId: Long, executionId: Long): ExecutionAccess {
        val execution = requireNotNull(dao.executionForWriter(actorId, executionId)) { "实施记录不存在或无权限修改" }
        return ExecutionAccess(execution, requireNotNull(dao.taskIdForExecution(executionId)), execution.measureId)
    }

    /**
     * Store 负责文件真实签名、复制结果与字节内容；此处复核授权和持久化边界。
     * 同键重试保留原附件，返回原 ID；Store 比较新旧文件内容后清理本次临时文件。
     */
    suspend fun addEvidence(actorId: Long, executionId: Long, evidence: ImprovementEvidence): Long =
        DraftTransactions.mutex.withLock {
            db.withTransaction {
                val access = executionForWriter(actorId, executionId)
                require(evidence.id == 0L && evidence.executionId == executionId) { "证据与实施记录不匹配" }
                validateRequestKey(evidence.requestKey)
                validateEvidence(evidence, access.taskId)
                val previous = dao.evidenceForRequest(executionId, evidence.requestKey)
                if (previous != null) {
                    require(previous.mimeType == evidence.mimeType && previous.byteSize == evidence.byteSize &&
                        previous.description == evidence.description.trim()) { "同一导入请求内容已改变，请重新选择文件" }
                    return@withTransaction previous.id
                }
                dao.insertEvidence(evidence.copy(
                    displayName = evidence.displayName.trim(),
                    description = evidence.description.trim(),
                    createdAt = System.currentTimeMillis(),
                ))
            }
        }

    /** 仅返回授权后的元数据；Store 再校验真实文件路径和存在性。 */
    suspend fun requireEvidence(actorId: Long, evidenceId: Long): EvidenceAccess =
        DraftTransactions.mutex.withLock {
            db.withTransaction {
                val evidence = requireNotNull(dao.singleEvidenceForReader(actorId, evidenceId)) { "证据不存在或无权限访问" }
                val taskId = requireNotNull(dao.taskIdForExecution(evidence.executionId))
                validateEvidence(evidence, taskId)
                EvidenceAccess(evidence, taskId)
            }
        }

    private fun validateRequestKey(requestKey: String) {
        require(requestKey.isNotBlank() && requestKey.length <= 128 && requestKey == requestKey.trim() &&
            requestKey.none { it.isISOControl() }) { "保存请求标识无效，请重新操作" }
    }

    private fun validateEvidence(evidence: ImprovementEvidence, taskId: Long) {
        require(evidence.mimeType in SUPPORTED_MIME_TYPES) { "仅支持JPEG、PNG图片和PDF资料" }
        require(evidence.byteSize in 1..MAX_EVIDENCE_BYTES) { "证据为空或超过10MiB限制" }
        require(evidence.displayName.trim().isNotEmpty() && evidence.displayName.length <= 255 &&
            evidence.displayName.none { it.isISOControl() }) { "文件显示名称无效" }
        require(evidence.description.length <= MAX_TEXT_LENGTH) { "证据说明不能超过5000字" }
        val prefix = "tasks/$taskId/improvement/${evidence.executionId}/"
        require(evidence.relativePath.startsWith(prefix)) { "证据路径不属于本次实施记录" }
        val fileName = evidence.relativePath.removePrefix(prefix)
        require(SAFE_FILE_NAME.matches(fileName) && fileName != "." && fileName != "..") { "证据文件路径无效" }
    }

    companion object {
        const val MAX_TEXT_LENGTH = 5_000
        const val MAX_EVIDENCE_BYTES = 10L * 1024 * 1024
        private const val CLOCK_TOLERANCE_MS = 60_000L
        private val SUPPORTED_MIME_TYPES = setOf("image/jpeg", "image/png", "application/pdf")
        private val SAFE_FILE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    }
}
