package edu.neu.aijiaoxue.data.repository

import androidx.room.withTransaction
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.dao.*
import edu.neu.aijiaoxue.data.entity.*
import edu.neu.aijiaoxue.data.model.*
import edu.neu.aijiaoxue.ui.improve.ExecutionEntry
import kotlinx.coroutines.flow.*
import java.time.LocalDate

data class TrackedIssue(val row: SupervisorIssueRow, val stage: TrackingStage, val latestReview: ReviewLinkDetail?)
data class SupervisorIssueDetail(val tracked: TrackedIssue, val suggestions: String?, val measures: List<ImprovementMeasure>,
    val executions: List<ExecutionEntry>, val reviews: List<ReviewLinkDetail>)
data class SupervisorTaskDetail(val row: SupervisorTaskRow, val evaluation: Evaluation?, val reference: CourseReferenceVersion?,
    val origins: List<ReviewLink>, val history: List<SupervisorTaskRow>, val referenceSelectionJson: String?)

/** All entry queries restrict the supervisor at SQL level. Teacher draft plans remain private. */
class SupervisorPortalRepository(private val db: AppDatabase) {
    private val dao = db.supervisorPortalDao()
    fun tasks(actorId: Long) = dao.observeTasks(actorId)
    fun issues(actorId: Long): Flow<List<TrackedIssue>> = dao.observeIssues(actorId).map { rows ->
        rows.map { tracked(actorId, it) }
    }
    private suspend fun tracked(actorId: Long, row: SupervisorIssueRow): TrackedIssue {
        val latest = db.reviewLinkDao().getByOriginTask(actorId, row.taskId)
            .filter { it.originIssueId == row.id }.sortedWith(compareByDescending<ReviewLink> { it.createdAt }.thenByDescending { it.id }).firstOrNull()
            ?.let { ReviewRepository(db).linkDetail(actorId, it.id) }
        val verdict = latest?.verifications?.firstOrNull()?.takeIf { it.id !in latest.verificationWarnings }
        val stage = when {
            verdict?.verdict == VerificationVerdict.IMPROVED && verdict.presence == IssuePresence.NOT_OBSERVED &&
                verdict.extent == ImprovementExtent.RESOLVED -> TrackingStage.CLOSED
            latest != null -> TrackingStage.AWAITING_REVIEW
            row.measureCount == 0 -> TrackingStage.AWAITING_PLAN
            row.doneCount == row.measureCount -> TrackingStage.AWAITING_REVIEW
            else -> TrackingStage.IMPLEMENTING
        }
        return TrackedIssue(row, stage, latest)
    }
    suspend fun task(actorId: Long, taskId: Long): SupervisorTaskDetail {
        val row = requireNotNull(dao.task(actorId, taskId)) { "任务不存在或无权访问" }
        val task = requireNotNull(db.taskDao().getEvaluationTaskForActor(taskId, actorId))
        return SupervisorTaskDetail(row, db.evaluationDao().getForSupervisor(taskId, actorId),
            task.courseReferenceVersionId?.let { db.analysisSourceDao().reference(it) },
            db.reviewLinkDao().getByFollowUpTask(actorId, taskId),
            tasks(actorId).first().filter { it.courseId == row.courseId && it.id != taskId && it.status == TaskStatus.COMPLETED }, task.referenceSelectionJson)
    }
    suspend fun issue(actorId: Long, issueId: Long): SupervisorIssueDetail {
        val row = requireNotNull(dao.issue(actorId, issueId)) { "正式问题不存在或无权访问" }
        val plan = db.improvementDao().getPlanByTask(row.taskId)?.takeIf { it.status == PlanStatus.CONFIRMED }
        val itemIds = plan?.let { db.improvementDao().getItems(it.id).filter { item -> item.issueId == issueId }.map { item -> item.id } }.orEmpty()
        val measures = db.improvementExecutionDao().measuresForReader(actorId, row.taskId).filter { it.itemId in itemIds }
        val executions = db.improvementExecutionDao().executionsForReader(actorId, row.taskId).filter { e -> measures.any { it.id == e.measureId } }
            .map { ExecutionEntry(it, db.improvementExecutionDao().evidenceForReader(actorId, it.id)) }
        val reviews = db.reviewLinkDao().getByOriginTask(actorId, row.taskId).filter { it.originIssueId == issueId }
            .mapNotNull { ReviewRepository(db).linkDetail(actorId, it.id) }.sortedByDescending { it.link.createdAt }
        return SupervisorIssueDetail(tracked(actorId, row), db.evaluationDao().getForSupervisor(row.taskId, actorId)?.suggestions, measures, executions, reviews)
    }
    /** US45/46: creating the classroom and linking the original issue is one transaction. */
    suspend fun createFollowUp(actorId: Long, issueId: Long, date: String, start: Int, end: Int, chapter: String): Long = db.withTransaction {
        val row = requireNotNull(dao.issue(actorId, issueId)) { "无权为此问题创建复评" }
        require(runCatching { LocalDate.parse(date).toString() == date }.getOrDefault(false)) { "日期格式应为 YYYY-MM-DD" }
        require(start in 1..12 && end in start..12) { "请选择有效的起止节次" }
        val id = requireNotNull(db.taskDao().create(SupervisionTask(code = "", courseId = row.courseId, supervisorId = actorId,
            supervisionDate = date, period = if (start == end) "$start" else "$start-$end", chapter = chapter.trim().ifBlank { null }, type = SupervisionType.FOLLOW_UP))) { "此课程同日同节次已有任务，请使用其他时间" }
        ReviewRepository(db).createLink(actorId, id, row.taskId, issueId)
        id
    }
}
