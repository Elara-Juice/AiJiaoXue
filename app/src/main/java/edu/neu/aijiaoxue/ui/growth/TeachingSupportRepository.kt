package edu.neu.aijiaoxue.ui.growth

import androidx.room.withTransaction
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.dao.TeachingFeedbackRow
import edu.neu.aijiaoxue.data.entity.*
import edu.neu.aijiaoxue.data.model.*
import edu.neu.aijiaoxue.ui.evaluation.DraftTransactions
import edu.neu.aijiaoxue.ui.improve.ImprovementRecord
import edu.neu.aijiaoxue.ui.improve.ImprovementRepository
import kotlinx.coroutines.sync.withLock

data class TeachingSupportRecord(
    val feedbacks: List<TeachingFeedbackRow>,
    val highlights: List<TeachingHighlight>,
    val selected: ImprovementRecord?,
    val adoptions: List<TeachingAdoption>,
)

data class TeachingChoice(val key: String, val title: String, val content: String, val reason: String, val reference: String)

class TeachingSupportRepository(private val db: AppDatabase) {
    private val dao = db.teachingSupportDao()

    suspend fun load(actorId: Long, taskId: Long? = null): TeachingSupportRecord {
        require(db.userDao().getById(actorId)?.role == Role.TEACHER) { "仅任课教师可以管理本人的教学做法" }
        val feedbacks = dao.feedbacks(actorId)
        if (taskId != null) require(feedbacks.any { it.taskId == taskId }) { "反馈尚未发布或无权访问" }
        return TeachingSupportRecord(feedbacks, dao.highlights(actorId),
            taskId?.let { ImprovementRepository(db).load(actorId, it) },
            taskId?.let { dao.adoptions(actorId, it) }.orEmpty())
    }

    suspend fun saveHighlight(actorId: Long, taskId: Long, practice: String) = DraftTransactions.mutex.withLock {
        db.withTransaction {
            val row = requireNotNull(dao.feedbacks(actorId).find { it.taskId == taskId }) { "反馈尚未发布或无权访问" }
            val source = requireNotNull(row.strengths?.trim()?.takeIf { it.isNotEmpty() }) { "本次正式反馈未填写教学亮点" }
            val text = practice.trim()
            require(text.isNotEmpty() && text.length <= 1000) { "请填写1—1000字的可复用做法" }
            val existing = dao.highlights(actorId).find { it.taskId == taskId }
            dao.saveHighlight(TeachingHighlight(existing?.id ?: 0, taskId, actorId,
                existing?.sourceText ?: source, text, System.currentTimeMillis()))
        }
    }

    /** 只从本人的正式问题、资源目录和已保存亮点提供候选；不是自动诊断。 */
    fun choices(record: TeachingSupportRecord, item: ImprovementItem): List<TeachingChoice> {
        val selected = record.selected ?: return emptyList()
        if (selected.items.none { it.id == item.id }) return emptyList()
        val dimension = selected.issues.find { it.id == item.issueId }?.dimension
        val strategies = TeachingSupportCatalog.strategies(dimension, item.description)
        val resourceKeys = strategies.flatMap { it.resourceKeys }.toSet()
        return strategies.map { TeachingChoice("strategy:${it.key}", it.title, it.content, it.rationale,
            "本地规则辅助策略；问题：${item.description}；规则版本 teaching-support-v1") } +
            TeachingSupportCatalog.resources.filter { it.key in resourceKeys }.map {
                TeachingChoice("resource:${it.key}", it.title, it.steps, "匹配当前互动／参与问题：${it.summary}",
                    "${it.sourceName} ${it.sourceUrl}")
            } + record.highlights.map {
                TeachingChoice("highlight:${it.id}", "复用本人教学亮点", it.practice,
                    "由本人从正式反馈标记，使用前请核对适用课堂", "原课堂任务 ${it.taskId}；亮点原文：${it.sourceText}")
            }
    }

    /** US39：二次校验归属、问题、来源和草稿锁定；重复采用不会覆盖教师编辑。 */
    suspend fun adopt(actorId: Long, taskId: Long, itemId: Long, key: String, edited: String): Long {
        val preview = load(actorId, taskId)
        return DraftTransactions.mutex.withLock { db.withTransaction {
            require(dao.feedbacks(actorId).any { it.taskId == taskId }) { "反馈尚未发布或无权访问" }
            val plan = requireNotNull(db.improvementDao().getPlanByTask(taskId)) { "请先确定改进重点" }
            require(plan.teacherId == actorId && plan.status == PlanStatus.DRAFT) { "计划已确认，不能加入或修改措施" }
            val item = requireNotNull(db.improvementDao().getItems(plan.id).find { it.id == itemId }) { "改进事项不存在" }
            require(preview.selected?.items?.contains(item) == true) { "改进重点已变化，请刷新页面" }
            val fresh = preview.copy(highlights = dao.highlights(actorId))
            val choice = requireNotNull(choices(fresh, item).find { it.key == key }) { "来源不存在或不适用于此问题" }
            val text = edited.trim()
            require(text.isNotEmpty() && text.length <= 2000) { "请填写1—2000字的实施策略" }
            val previous = dao.adoptions(actorId, taskId).find { it.itemId == itemId && it.sourceKey == key }
            if (previous != null) return@withTransaction previous.measureId
            // 教师自主选用，措施按教师新增处理；候选原文和出处另表冻结。
            val measureId = db.improvementDao().upsertMeasure(ImprovementMeasure(planId = plan.id, itemId = itemId,
                content = text, source = MeasureSource.ADDED, dueDate = ""))
            dao.insertAdoption(TeachingAdoption(measureId = measureId, itemId = itemId, sourceKey = key,
                sourceTitle = choice.title, sourceReference = choice.reference, problemSnapshot = item.description,
                originalContent = choice.content, adoptedAt = System.currentTimeMillis()))
            measureId
        } }
    }
}
