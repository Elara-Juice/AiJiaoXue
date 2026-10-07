package edu.neu.aijiaoxue

import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.entity.*
import edu.neu.aijiaoxue.data.model.*
import edu.neu.aijiaoxue.ui.evaluation.*
import edu.neu.aijiaoxue.ui.improve.*

/** 仅在测试源集生成数据；不污染DemoData或正式入口。 */
class Sprint1Fixture(val db: AppDatabase) {
    val evaluations = EvaluationRepository(db)
    val improvement = ImprovementRepository(db)
    var admin = 0L; var supervisor = 0L; var otherSupervisor = 0L
    var teacher = 0L; var otherTeacher = 0L; var course = 0L; var otherCourse = 0L
    private var sequence = 0

    suspend fun seed() {
        val hash = edu.neu.aijiaoxue.data.PasswordHasher.hash("123456")
        val users = db.userDao().insertAll(listOf(
            User(account = "admin", passwordHash = hash, name = "测试管理员", role = Role.ADMIN),
            User(account = "sup1", passwordHash = hash, name = "测试督导一", role = Role.SUPERVISOR),
            User(account = "sup2", passwordHash = hash, name = "测试督导二", role = Role.SUPERVISOR),
            User(account = "tea1", passwordHash = hash, name = "测试教师一", role = Role.TEACHER),
            User(account = "tea2", passwordHash = hash, name = "测试教师二", role = Role.TEACHER),
        ))
        admin = users[0]; supervisor = users[1]; otherSupervisor = users[2]; teacher = users[3]; otherTeacher = users[4]
        val courses = db.courseDao().insertAll(listOf(
            Course(code = "TEST-01", name = "测试数据结构", teacherId = teacher, className = "测试班", schedule = "周三", location = "测试教室"),
            Course(code = "TEST-02", name = "测试操作系统", teacherId = otherTeacher, className = null, schedule = null, location = null),
        ))
        course = courses[0]; otherCourse = courses[1]
    }
    suspend fun task(courseId: Long = course, supervisorId: Long = supervisor, date: String = "2026-10-07", arrangementId: Long? = null): Long {
        sequence++
        return db.taskDao().insert(SupervisionTask(code = "YZJ-TEST-$sequence", courseId = courseId, supervisorId = supervisorId,
            arrangementId = arrangementId, supervisionDate = date, period = sequence.toString(), chapter = "测试章节",
            type = SupervisionType.REGULAR, status = TaskStatus.PENDING_EVALUATION))
    }
    fun draft(taskId: Long, suggestions: String? = "每节课增加一次讨论") = EvaluationDraft(
        Evaluation(taskId = taskId, contentScore = 5, designScore = 4, interactionScore = 3, engagementScore = 4,
            contentComment = "概念准确", strengths = "讲解清楚", suggestions = suggestions, overallOpinion = "总体良好，增加学生互动"),
        listOf(EvaluationIssue(evaluationId = 0, description = "互动较少", dimension = EvalDimension.INTERACTION),
            EvaluationIssue(evaluationId = 0, description = "参与不均", dimension = EvalDimension.ENGAGEMENT)),
    )
    suspend fun submitted(courseId: Long = course, supervisorId: Long = supervisor, suggestions: String? = "每节课增加一次讨论", date: String = "2026-10-07"): Long {
        val taskId = task(courseId, supervisorId, date)
        evaluations.save(supervisorId, taskId, draft(taskId, suggestions), submit = true)
        return taskId
    }
    suspend fun focus(taskId: Long, actorId: Long = teacher, count: Int = 1): ImprovementRecord {
        val record = improvement.load(actorId, taskId)
        improvement.saveFocus(actorId, taskId, record.issues.take(count).map {
            ImprovementItem(planId = 0, issueId = it.id, source = ItemSource.SUPERVISOR, description = it.description, priority = Priority.HIGH)
        })
        return improvement.load(actorId, taskId)
    }
    suspend fun confirmed(taskId: Long, actorId: Long = teacher): ImprovementRecord {
        val record = focus(taskId, actorId)
        val measures = record.measures.ifEmpty { listOf(ImprovementMeasure(planId = record.plan!!.id, itemId = record.items.first().id,
            content = "教师新增", source = MeasureSource.ADDED, dueDate = "")) }.map { it.copy(dueDate = "2026-10-01") }
        improvement.saveMeasures(actorId, taskId, measures, confirm = true)
        return improvement.load(actorId, taskId)
    }
}
