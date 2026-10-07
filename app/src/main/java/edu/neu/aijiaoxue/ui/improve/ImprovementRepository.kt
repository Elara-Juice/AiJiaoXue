package edu.neu.aijiaoxue.ui.improve

import androidx.room.withTransaction
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.entity.*
import edu.neu.aijiaoxue.data.model.*
import edu.neu.aijiaoxue.ui.evaluation.DraftTransactions
import edu.neu.aijiaoxue.ui.evaluation.validDate
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll

data class ImprovementRecord(
    val task: SupervisionTask,
    val course: Course,
    val evaluation: Evaluation,
    val issues: List<EvaluationIssue>,
    val plan: ImprovementPlan?,
    val items: List<ImprovementItem>,
    val measures: List<ImprovementMeasure>,
)

class ImprovementRepository(private val db: AppDatabase) {
    private val dao = db.improvementDao()

    private suspend fun read(actorId: Long, taskId: Long): ImprovementRecord {
        val task = requireNotNull(db.taskDao().getImprovementTaskForActor(taskId, actorId)) {
            "反馈不存在、尚未提交或无权限访问"
        }
        val evaluation = requireNotNull(db.evaluationDao().getSubmittedForTeacher(taskId, actorId))
        val plan = dao.getPlanByTask(taskId)
        require(plan == null || plan.teacherId == actorId) { "无权限访问此计划" }
        val items = plan?.let { dao.getItems(it.id) }.orEmpty()
        return ImprovementRecord(
            task, requireNotNull(db.courseDao().getById(task.courseId)), evaluation,
            db.evaluationDao().getIssues(evaluation.id), plan, items,
            items.flatMap { dao.getMeasuresByItem(it.id) },
        )
    }

    suspend fun load(actorId: Long, taskId: Long): ImprovementRecord = DraftTransactions.mutex.withLock {
        db.withTransaction { read(actorId, taskId) }
    }

    suspend fun saveFocus(actorId: Long, taskId: Long, items: List<ImprovementItem>, allowRemoval: Boolean = false) =
        DraftTransactions.mutex.withLock {
            db.withTransaction {
                val record = read(actorId, taskId)
                require(record.plan?.status != PlanStatus.CONFIRMED) { "计划已确认，不能修改改进重点" }
                require(items.isNotEmpty()) { "请至少选择一项改进重点" }
                require(items.all { it.description.isNotBlank() }) { "请填写教师自选事项" }
                val ownedIssues = record.issues.associateBy { it.id }
                require(items.mapNotNull { it.issueId }.distinct().size == items.count { it.issueId != null }) { "不能重复选择同一问题" }
                require(items.filter { it.id > 0 }.map { it.id }.distinct().size == items.count { it.id > 0 }) { "不能重复保存同一事项" }
                items.forEach { item ->
                    require(item.id <= 0 || record.items.any { it.id == item.id && it.issueId == item.issueId }) { "事项不属于此计划" }
                    require(item.issueId == null || ownedIssues.containsKey(item.issueId)) { "问题不属于本次反馈" }
                }
                val removed = record.items.filter { old -> items.none { it.id == old.id } }
                require(allowRemoval || record.measures.none { m -> removed.any { it.id == m.itemId } }) {
                    "移除事项会同时删除已填写的措施，请确认后重试"
                }
                val planId = record.plan?.id ?: dao.upsertPlan(ImprovementPlan(teacherId = actorId, taskId = taskId))
                val keep = items.map { item ->
                    val normalized = item.copy(
                        id = item.id.coerceAtLeast(0), planId = planId,
                        source = if (item.issueId == null) ItemSource.TEACHER else ItemSource.SUPERVISOR,
                        description = item.issueId?.let { ownedIssues.getValue(it).description } ?: item.description.trim(),
                    )
                    val inserted = dao.upsertItem(normalized)
                    val itemId = if (inserted == -1L) normalized.id else inserted
                    // US41：仅首次创建事项时预填，重开页面或放弃后不重复生成。
                    if (item.id <= 0 && !record.evaluation.suggestions.isNullOrBlank()) {
                        dao.upsertMeasure(ImprovementMeasure(
                            planId = planId, itemId = itemId, content = record.evaluation.suggestions.trim(),
                            source = MeasureSource.ADOPTED, dueDate = "",
                        ))
                    }
                    itemId
                }
                dao.deleteItemsExcept(planId, keep)
                planId
            }
        }

    suspend fun saveMeasures(actorId: Long, taskId: Long, measures: List<ImprovementMeasure>, confirm: Boolean = false) =
        DraftTransactions.mutex.withLock {
            db.withTransaction {
                val record = read(actorId, taskId)
                val plan = requireNotNull(record.plan) { "请先确定改进重点" }
                require(plan.status == PlanStatus.DRAFT) { "计划已确认，内容和日期不可修改" }
                require(measures.filter { it.id > 0 }.map { it.id }.distinct().size == measures.count { it.id > 0 }) { "措施重复" }
                measures.forEach { measure ->
                    require(record.items.any { it.id == measure.itemId }) { "措施不属于此计划的事项" }
                    require(measure.id <= 0 || record.measures.any { it.id == measure.id && it.itemId == measure.itemId }) { "措施不属于此事项" }
                    require(measure.dueDate.isEmpty() || validDate(measure.dueDate)) { "计划完成日期无效" }
                }
                if (confirm) require(planError(record.items, measures) == null) { planError(record.items, measures).orEmpty() }
                record.measures.filter { old -> measures.none { it.id == old.id } }.forEach { dao.deleteMeasure(it) }
                measures.forEach { measure ->
                    val source = if (measure.source == MeasureSource.ADOPTED &&
                        measure.content.trim() != record.evaluation.suggestions?.trim()) MeasureSource.MODIFIED else measure.source
                    dao.upsertMeasure(measure.copy(
                        id = measure.id.coerceAtLeast(0), planId = plan.id, source = source,
                        status = MeasureStatus.NOT_STARTED, updatedAt = System.currentTimeMillis(),
                    ))
                }
                if (confirm) check(dao.confirmPlanChecked(plan.id, System.currentTimeMillis())) { "确认失败，请重新打开计划" }
            }
        }

    fun todos(actorId: Long, onlyOpen: Boolean, status: MeasureStatus?) = flow {
        require(db.userDao().getById(actorId)?.role == Role.TEACHER) { "无权限访问教师待办" }
        emitAll(dao.observeAuthorizedTodos(actorId, onlyOpen, status))
    }

    suspend fun updateStatus(actorId: Long, measureId: Long, status: MeasureStatus) = DraftTransactions.mutex.withLock {
        db.withTransaction {
            requireNotNull(dao.getMeasureForActor(measureId, actorId)) { "待办不存在或无权限修改" }
            check(dao.updateMeasureStatusForTeacher(measureId, actorId, status, System.currentTimeMillis()) == 1) { "状态更新失败" }
        }
    }
}

fun planError(items: List<ImprovementItem>, measures: List<ImprovementMeasure>): String? = when {
    items.isEmpty() -> "请先确定改进重点"
    items.any { item -> measures.none { it.itemId == item.id } } -> "每个改进事项至少保留一条措施"
    measures.any { it.content.isBlank() } -> "请填写措施内容或删除空白措施"
    measures.any { it.dueDate.isBlank() || !validDate(it.dueDate) } -> "请为每条措施选择有效的完成日期"
    else -> null
}
