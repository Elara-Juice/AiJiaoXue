package edu.neu.aijiaoxue.ui.evaluation

import androidx.room.withTransaction
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.dao.HistoryRow
import edu.neu.aijiaoxue.data.entity.*
import edu.neu.aijiaoxue.data.model.*
import edu.neu.aijiaoxue.data.repository.ReviewedAnalysisProvider
import edu.neu.aijiaoxue.data.repository.ReviewRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 进程内串行化读写；离开页面后的最后一笔保存完成后，新页面才读取草稿。 */
internal object DraftTransactions {
    val mutex = Mutex()
}

data class EvaluationDraft(val evaluation: Evaluation, val issues: List<EvaluationIssue> = emptyList())

data class ClassroomRecord(
    val task: SupervisionTask,
    val course: Course,
    val teacherName: String,
    val supervisorName: String,
    val draft: EvaluationDraft,
    val materials: List<Material>,
    val events: List<InteractionEvent>,
    val engagements: List<EngagementRecord>,
    val reviewedAnalyses: List<ReviewedAnalysisSnapshot> = emptyList(),
    val publication: ReviewPublication? = null,
    val publishedFacts: List<PublishedFactSnapshot> = emptyList(),
    val publishedEvidence: List<PublishedEvidenceSnapshot> = emptyList(),
)

class EvaluationRepository(private val db: AppDatabase) {
    private val evaluations = db.evaluationDao()

    suspend fun load(actorId: Long, taskId: Long, history: Boolean = false): ClassroomRecord =
        DraftTransactions.mutex.withLock {
            db.withTransaction {
                val task = if (history) db.taskDao().getHistoryTaskForActor(taskId, actorId)
                    else db.taskDao().getEvaluationTaskForActor(taskId, actorId)
                requireNotNull(task) { "记录不存在或无权限访问" }
                require(history || task.status in setOf(TaskStatus.PENDING_EVALUATION, TaskStatus.COMPLETED)) {
                    "请先结束课堂采集，再填写评价"
                }
                val course = requireNotNull(db.courseDao().getById(task.courseId))
                val evaluation = evaluations.getByTask(taskId) ?: Evaluation(taskId = taskId)
                val publication = if (evaluation.status == EvalStatus.SUBMITTED) db.improvementSuggestionDao().publicationForEvaluation(evaluation.id) else null
                val reviewed = if (evaluation.status == EvalStatus.SUBMITTED) {
                    db.improvementSuggestionDao().publicationForEvaluation(evaluation.id)?.let {
                        db.improvementSuggestionDao().snapshots(it.id)
                    }.orEmpty()
                } else ReviewedAnalysisProvider(db).forSupervisor(actorId, taskId)
                ClassroomRecord(
                    task, course, db.userDao().getById(course.teacherId)?.name.orEmpty(),
                    db.userDao().getById(task.supervisorId)?.name.orEmpty(),
                    EvaluationDraft(evaluation, if (evaluation.id == 0L) emptyList() else evaluations.getIssues(evaluation.id)),
                    db.captureDao().getMaterials(taskId), db.captureDao().observeEvents(taskId).first(),
                    db.captureDao().observeEngagements(taskId).first(),
                    reviewed,
                    publication,
                    publication?.let { db.analysisSourceDao().publishedFacts(it.id) }.orEmpty(),
                    publication?.let { db.analysisSourceDao().publishedEvidence(it.id) }.orEmpty(),
                )
            }
        }

    fun history(actorId: Long, keyword: String, from: String, to: String): Flow<List<HistoryRow>> = flow {
        require(from.isEmpty() || validDate(from)) { "开始日期无效" }
        require(to.isEmpty() || validDate(to)) { "结束日期无效" }
        require(from.isEmpty() || to.isEmpty() || from <= to) { "开始日期不能晚于结束日期" }
        val user = requireNotNull(db.userDao().getById(actorId)) { "请重新登录" }
        val source = when (user.role) {
            Role.ADMIN -> db.taskDao().observeHistoryAll(keyword.trim(), from, to)
            Role.SUPERVISOR -> db.taskDao().observeHistoryForSupervisor(actorId, keyword.trim(), from, to)
            else -> error("无权限访问历史记录")
        }
        emitAll(source)
    }

    suspend fun save(actorId: Long, taskId: Long, draft: EvaluationDraft, submit: Boolean = false) =
        DraftTransactions.mutex.withLock {
            db.withTransaction {
                val task = requireNotNull(db.taskDao().getEvaluationTaskForActor(taskId, actorId)) { "无权限修改此评价" }
                require(task.status == TaskStatus.PENDING_EVALUATION) { "任务不是待评价状态，评价不可修改" }
                require(draft.evaluation.taskId == taskId) { "评价与任务不匹配" }
                val existing = evaluations.getByTask(taskId)
                require(existing?.status != EvalStatus.SUBMITTED) { "评价已提交，不可修改" }
                val total = EvalRules.totalScore(draft.evaluation.scores())
                if (submit) require(evaluationError(draft) == null) { evaluationError(draft).orEmpty() }
                val submittedAt = System.currentTimeMillis()
                val clean = draft.evaluation.copy(
                    id = existing?.id ?: 0, totalScore = total, grade = total?.let(EvalRules::gradeOf),
                    status = EvalStatus.DRAFT, submittedAt = null, teacherReadAt = null,
                    issueDeclaredBy = if (submit) actorId else null,
                    issueDeclaredAt = if (submit) submittedAt else null,
                )
                val id = requireNotNull(evaluations.saveDraft(clean)) { "评价已提交" }
                // 草稿尚无教师引用，整批更新问题可避免快速输入造成新问题重复插入。
                evaluations.getIssues(id).forEach { evaluations.deleteIssue(it) }
                draft.issues.forEachIndexed { index, issue ->
                    evaluations.upsertIssue(issue.copy(id = 0, evaluationId = id, sortOrder = index))
                }
                if (submit) {
                    check(evaluations.submit(id, taskId, requireNotNull(total), EvalRules.gradeOf(total), submittedAt)) {
                        "提交失败，请重新打开评价"
                    }
                    // US38：正式评价与其审核依据同时发布，失败则整笔提交回滚。
                    ReviewedAnalysisProvider(db).publish(actorId, taskId, id)
                    // TD-07: freeze this task's formal metric snapshots as part of evaluation submission.
                    ReviewRepository(db).captureSnapshots(actorId, taskId)
                }
            }
        }
}

fun evaluationError(draft: EvaluationDraft): String? {
    val missing = EvalDimension.entries.filter { draft.evaluation.scoreOf(it) == null }
    return when {
        missing.isNotEmpty() -> "请完成评分：${missing.joinToString("、") { it.label }}"
        draft.evaluation.issueDeclaration == IssueDeclaration.UNASSESSED -> "请确认已记录主要问题，或明确确认本次无主要问题"
        draft.evaluation.issueDeclaration == IssueDeclaration.HAS_ISSUES && draft.issues.isEmpty() -> "请填写主要问题，或选择确认本次无主要问题"
        draft.evaluation.issueDeclaration == IssueDeclaration.NO_ISSUES_CONFIRMED && draft.issues.isNotEmpty() -> "零问题声明不能同时保留问题条目"
        draft.evaluation.issueDeclaration == IssueDeclaration.NO_ISSUES_CONFIRMED && draft.evaluation.issueDeclarationNote.isNullOrBlank() -> "请填写本次无主要问题的确认说明"
        draft.issues.any { it.description.isBlank() } -> "请填写或删除空白问题条目"
        draft.evaluation.scores().values.any { it != null && it !in 1..5 } -> "各维度评分应为1至5分"
        draft.evaluation.overallOpinion.isNullOrBlank() -> "请填写总体意见"
        else -> null
    }
}

fun validDate(value: String): Boolean = runCatching {
    java.time.LocalDate.parse(value, DateFormats.DATE).format(DateFormats.DATE) == value
}.getOrDefault(false)
