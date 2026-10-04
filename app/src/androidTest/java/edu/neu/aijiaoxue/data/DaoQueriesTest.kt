package edu.neu.aijiaoxue.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import edu.neu.aijiaoxue.data.entity.Arrangement
import edu.neu.aijiaoxue.data.entity.Course
import edu.neu.aijiaoxue.data.entity.Evaluation
import edu.neu.aijiaoxue.data.entity.EvaluationIssue
import edu.neu.aijiaoxue.data.entity.ImprovementItem
import edu.neu.aijiaoxue.data.entity.ImprovementMeasure
import edu.neu.aijiaoxue.data.entity.ImprovementPlan
import edu.neu.aijiaoxue.data.entity.InteractionEvent
import edu.neu.aijiaoxue.data.entity.Material
import edu.neu.aijiaoxue.data.entity.SupervisionTask
import edu.neu.aijiaoxue.data.entity.User
import edu.neu.aijiaoxue.data.model.ArrangementStatus
import edu.neu.aijiaoxue.data.model.EvalGrade
import edu.neu.aijiaoxue.data.model.EvalStatus
import edu.neu.aijiaoxue.data.model.InteractionType
import edu.neu.aijiaoxue.data.model.ItemSource
import edu.neu.aijiaoxue.data.model.MeasureSource
import edu.neu.aijiaoxue.data.model.MaterialType
import edu.neu.aijiaoxue.data.model.MeasureStatus
import edu.neu.aijiaoxue.data.model.Priority
import edu.neu.aijiaoxue.data.model.Role
import edu.neu.aijiaoxue.data.model.Semester
import edu.neu.aijiaoxue.data.model.SupervisionType
import edu.neu.aijiaoxue.data.model.TaskStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 联表查询与权限查询的运行时校验，使用内存数据库。 */
@RunWith(AndroidJUnit4::class)
class DaoQueriesTest {
    private lateinit var db: AppDatabase
    private var teacherA = 0L
    private var teacherB = 0L
    private var supervisor = 0L
    private var courseA = 0L
    private var courseB = 0L

    @Before
    fun setUp() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val ids = db.userDao().insertAll(
            listOf(
                User(account = "t1", passwordHash = "x", name = "张老师", role = Role.TEACHER),
                User(account = "t2", passwordHash = "x", name = "李老师", role = Role.TEACHER),
                User(account = "s1", passwordHash = "x", name = "王督导", role = Role.SUPERVISOR),
            )
        )
        teacherA = ids[0]; teacherB = ids[1]; supervisor = ids[2]
        val courses = db.courseDao().insertAll(
            listOf(
                Course(code = "C01", name = "数据结构", teacherId = teacherA, className = null, schedule = null, location = null),
                Course(code = "C02", name = "操作系统", teacherId = teacherB, className = null, schedule = null, location = null),
            )
        )
        courseA = courses[0]; courseB = courses[1]
    }

    @After
    fun tearDown() = db.close()

    private suspend fun task(courseId: Long, date: String, status: TaskStatus) = db.taskDao().insert(
        SupervisionTask(
            code = "DD-$courseId-$date", courseId = courseId, supervisorId = supervisor,
            supervisionDate = date, period = "3-4", type = SupervisionType.REGULAR, status = status,
        )
    )

    @Test
    fun courseRows_searchByTeacherName_andCountsCompletedOnly() = runBlocking {
        task(courseA, "2026-09-10", TaskStatus.COMPLETED)
        task(courseA, "2026-09-20", TaskStatus.COMPLETED)
        task(courseA, "2026-10-03", TaskStatus.NOT_STARTED)
        db.arrangementDao().insert(Arrangement(courseId = courseA, isKeyFocus = true, createdBy = supervisor))

        val rows = db.courseDao().observeRows("张").first()
        assertEquals(1, rows.size)
        val row = rows[0]
        assertEquals("张老师", row.teacherName)
        assertEquals(2, row.completedCount)
        assertEquals("2026-09-20", row.lastSupervisionDate)
        assertTrue(row.hasOpenArrangement)
        assertTrue(row.isKeyFocus)

        val other = db.courseDao().observeRow(courseB).first()!!
        assertEquals(0, other.completedCount)
        assertNull(other.lastSupervisionDate)
        assertFalse(other.hasOpenArrangement)

        assertEquals(2, db.courseDao().observeRows("").first().size)
    }

    @Test
    fun uncoveredRows_excludesCoursesCompletedThisSemester() = runBlocking {
        task(courseA, "2026-09-10", TaskStatus.COMPLETED)
        task(courseB, "2026-03-01", TaskStatus.COMPLETED)
        val uncovered = db.courseDao().observeUncoveredRows(Semester.START_DATE).first()
        assertEquals(listOf(courseB), uncovered.map { it.id })
    }

    @Test
    fun taskRows_filterAndCounts() = runBlocking {
        task(courseA, "2026-10-01", TaskStatus.NOT_STARTED)
        task(courseB, "2026-10-02", TaskStatus.COMPLETED)

        val all = db.taskDao().observeRowsFiltered(null, null, null).first()
        assertEquals(2, all.size)
        assertEquals("2026-10-02", all[0].supervisionDate)
        assertEquals("王督导", all[0].supervisorName)

        val notStarted = db.taskDao().observeRowsFiltered(null, null, TaskStatus.NOT_STARTED).first()
        assertEquals(listOf(courseA), notStarted.map { it.courseId })
        assertEquals(1, db.taskDao().observeRowsFiltered(courseB, supervisor, null).first().size)

        val counts = db.taskDao().observeStatusCounts().first().associate { it.status to it.count }
        assertEquals(mapOf(TaskStatus.NOT_STARTED to 1, TaskStatus.COMPLETED to 1), counts)
    }

    @Test
    fun history_filtersByKeywordAndDateRange() = runBlocking {
        task(courseA, "2026-09-10", TaskStatus.COMPLETED)
        task(courseB, "2026-09-25", TaskStatus.COMPLETED)
        task(courseB, "2026-10-01", TaskStatus.PENDING_EVALUATION)

        val dao = db.taskDao()
        assertEquals(2, dao.observeHistoryForSupervisor(supervisor, "", "", "").first().size)
        assertEquals(listOf("操作系统"), dao.observeHistoryAll("李", "", "").first().map { it.courseName })
        assertEquals(1, dao.observeHistoryAll("", "2026-09-20", "2026-09-30").first().size)
        assertEquals(0, dao.observeHistoryForSupervisor(teacherA, "", "", "").first().size)
    }

    @Test
    fun feedback_onlyOwnSubmitted() = runBlocking {
        val t1 = task(courseA, "2026-10-01", TaskStatus.COMPLETED)
        val t2 = task(courseA, "2026-10-02", TaskStatus.PENDING_EVALUATION)
        val dao = db.evaluationDao()
        dao.upsert(Evaluation(taskId = t1, status = EvalStatus.SUBMITTED, submittedAt = 1L))
        dao.upsert(Evaluation(taskId = t2, status = EvalStatus.DRAFT))

        assertEquals(listOf(t1), dao.observeFeedbackRows(teacherA).first().map { it.taskId })
        assertEquals(1, dao.observeUnreadCount(teacherA).first())
        assertTrue(dao.observeFeedbackRows(teacherB).first().isEmpty())
        assertNull(dao.getSubmittedForTeacher(t1, teacherB))
        assertNull(dao.getSubmittedForTeacher(t2, teacherA))

        val e = dao.getSubmittedForTeacher(t1, teacherA)!!
        dao.markRead(e.id, 2L)
        assertEquals(0, dao.observeUnreadCount(teacherA).first())
    }

    @Test
    fun todos_traceBackToItemAndCourse_andConfirmOnlyOnce() = runBlocking {
        val t = task(courseA, "2026-10-01", TaskStatus.COMPLETED)
        val evalId = db.evaluationDao().upsert(Evaluation(taskId = t, status = EvalStatus.SUBMITTED))
        val issueId = db.evaluationDao().upsertIssue(EvaluationIssue(evaluationId = evalId, description = "提问偏少"))
        val dao = db.improvementDao()
        val planId = dao.upsertPlan(ImprovementPlan(teacherId = teacherA, taskId = t))
        val itemId = dao.upsertItem(
            ImprovementItem(planId = planId, issueId = issueId, source = ItemSource.SUPERVISOR, description = "提问偏少", priority = Priority.HIGH)
        )
        dao.upsertMeasure(ImprovementMeasure(planId = planId, itemId = itemId, content = "每节至少 5 次提问", source = MeasureSource.ADOPTED, dueDate = "2026-10-20"))
        val doneId = dao.upsertMeasure(ImprovementMeasure(planId = planId, itemId = itemId, content = "小组讨论", source = MeasureSource.ADDED, dueDate = "2026-10-10"))

        // 草稿计划不进待办
        assertTrue(dao.observeTodoRows(teacherA, null).first().isEmpty())
        assertEquals(1, dao.confirmPlan(planId, 1L))
        assertEquals(0, dao.confirmPlan(planId, 2L))

        val all = dao.observeTodoRows(teacherA, null).first()
        assertEquals(listOf("2026-10-10", "2026-10-20"), all.map { it.dueDate })
        assertEquals("提问偏少", all[0].itemDescription)
        assertEquals("数据结构", all[0].courseName)
        assertEquals(t, all[0].taskId)

        dao.updateMeasureStatus(doneId, MeasureStatus.DONE, 3L)
        assertEquals(1, dao.observeOpenTodoRows(teacherA).first().size)
        assertEquals(1, dao.observeTodoRows(teacherA, MeasureStatus.DONE).first().size)
        assertTrue(dao.observeTodoRows(teacherB, null).first().isEmpty())
        assertNull(dao.observePlanForTeacher(t, teacherB).first())
    }

    private fun newTask(courseId: Long, period: String = "3-4") = SupervisionTask(
        code = "", courseId = courseId, supervisorId = supervisor,
        supervisionDate = "2026-10-03", period = period, type = SupervisionType.REGULAR,
    )

    @Test
    fun createTask_generatesCode_rejectsDuplicate_claimsArrangement() = runBlocking {
        val arrId = db.arrangementDao().insert(Arrangement(courseId = courseA, createdBy = supervisor))
        val dao = db.taskDao()
        val id1 = dao.create(newTask(courseA))!!
        val id2 = dao.create(newTask(courseA, period = "5-6"))!!
        assertNull(dao.create(newTask(courseA)))

        val t1 = dao.getById(id1)!!
        assertEquals("DD20261003-001", t1.code)
        assertEquals("DD20261003-002", dao.getById(id2)!!.code)
        assertEquals(arrId, t1.arrangementId)
        assertEquals(TaskStatus.NOT_STARTED, t1.status)
        assertEquals(ArrangementStatus.CLAIMED, db.arrangementDao().getOpenByCourse(courseA)!!.status)
        // 安排已被认领，第二个任务不再关联
        assertNull(dao.getById(id2)!!.arrangementId)
    }

    @Test
    fun captureLifecycle_andEventCounts() = runBlocking {
        val taskId = db.taskDao().create(newTask(courseA))!!
        val tasks = db.taskDao()
        assertEquals(0, tasks.endClass(taskId, 1L))
        assertEquals(1, tasks.startClass(taskId, 100L))
        assertEquals(0, tasks.startClass(taskId, 200L))
        assertEquals(100L, tasks.getById(taskId)!!.classStartAt)

        val cap = db.captureDao()
        val e1 = cap.insertEvent(InteractionEvent(taskId = taskId, type = InteractionType.TEACHER_QUESTION, occurredAt = 1, offsetMs = 1))
        cap.insertEvent(InteractionEvent(taskId = taskId, type = InteractionType.TEACHER_QUESTION, occurredAt = 2, offsetMs = 2))
        cap.insertEvent(InteractionEvent(taskId = taskId, type = InteractionType.STUDENT_ANSWER, occurredAt = 3, offsetMs = 3))
        assertEquals(1, cap.deleteEventById(e1))
        val counts = cap.observeEventCounts(taskId).first().associate { it.type to it.count }
        assertEquals(mapOf(InteractionType.TEACHER_QUESTION to 1, InteractionType.STUDENT_ANSWER to 1), counts)

        cap.insertMaterial(Material(taskId = taskId, type = MaterialType.AUDIO, filePath = "a.m4a", capturedAt = 1, offsetMs = 0))
        cap.insertMaterial(Material(taskId = taskId, type = MaterialType.AUDIO, filePath = "b.m4a", capturedAt = 2, offsetMs = 0, endedAt = 9, durationMs = 7))
        assertEquals(listOf("a.m4a"), cap.getUnfinishedAudios(taskId).map { it.filePath })

        assertEquals(1, tasks.endClass(taskId, 300L))
        assertEquals(TaskStatus.PENDING_EVALUATION, tasks.getById(taskId)!!.status)
        assertEquals(true, cap.isMaterialEditable(taskId))
    }

    @Test
    fun evaluation_draftSubmitLocksAndCompletesTask() = runBlocking {
        db.arrangementDao().insert(Arrangement(courseId = courseA, createdBy = supervisor))
        val taskId = db.taskDao().create(newTask(courseA))!!
        db.taskDao().startClass(taskId, 1L)
        db.taskDao().endClass(taskId, 2L)
        val dao = db.evaluationDao()

        val evalId = dao.saveDraft(Evaluation(taskId = taskId, contentScore = 4))!!
        // 再次保存不新建记录
        assertEquals(evalId, dao.saveDraft(Evaluation(taskId = taskId, contentScore = 5, designScore = 4)))
        assertEquals(5, dao.getByTask(taskId)!!.contentScore)

        // 未评完、无总体意见、无问题条目：不能提交
        assertFalse(dao.submit(evalId, taskId, 80, EvalGrade.GOOD, 10L))
        dao.saveDraft(
            dao.getByTask(taskId)!!.copy(interactionScore = 4, engagementScore = 4, overallOpinion = "整体良好")
        )
        assertFalse(dao.submit(evalId, taskId, 80, EvalGrade.GOOD, 10L))
        dao.upsertIssue(EvaluationIssue(evaluationId = evalId, description = "提问偏少"))

        assertTrue(dao.submit(evalId, taskId, 84, EvalGrade.GOOD, 10L))
        val e = dao.getByTask(taskId)!!
        assertEquals(EvalStatus.SUBMITTED, e.status)
        assertEquals(84, e.totalScore)
        assertEquals(TaskStatus.COMPLETED, db.taskDao().getById(taskId)!!.status)
        assertNull(db.arrangementDao().getOpenByCourse(courseA))
        assertEquals(false, db.captureDao().isMaterialEditable(taskId))

        // 提交后锁定
        assertNull(dao.saveDraft(e.copy(contentScore = 1)))
        assertFalse(dao.submit(evalId, taskId, 84, EvalGrade.GOOD, 11L))
        assertEquals(5, dao.getByTask(taskId)!!.contentScore)
        assertNull(dao.getForSupervisor(taskId, teacherA))
    }

    @Test
    fun improvement_focusThenConfirm() = runBlocking {
        val t = task(courseA, "2026-10-01", TaskStatus.COMPLETED)
        val draftTask = task(courseA, "2026-10-02", TaskStatus.PENDING_EVALUATION)
        val evalId = db.evaluationDao().upsert(Evaluation(taskId = t, status = EvalStatus.SUBMITTED))
        db.evaluationDao().upsert(Evaluation(taskId = draftTask))
        val issueId = db.evaluationDao().upsertIssue(EvaluationIssue(evaluationId = evalId, description = "提问偏少"))
        val dao = db.improvementDao()

        fun item(desc: String, issue: Long? = null) = ImprovementItem(
            planId = 0, issueId = issue, description = desc, priority = Priority.HIGH,
            source = if (issue == null) ItemSource.TEACHER else ItemSource.SUPERVISOR,
        )
        // 权限：别人的课程、未提交的评价、空选择
        assertNull(dao.saveFocus(teacherB, t, listOf(item("x"))))
        assertNull(dao.saveFocus(teacherA, draftTask, listOf(item("x"))))
        assertNull(dao.saveFocus(teacherA, t, emptyList()))

        val planId = dao.saveFocus(teacherA, t, listOf(item("提问偏少", issueId), item("板书不清")))!!
        // 重新调整：只保留第一项
        val kept = dao.getItems(planId).first { it.issueId == issueId }
        assertEquals(planId, dao.saveFocus(teacherA, t, listOf(kept)))
        assertEquals(listOf(kept.id), dao.getItems(planId).map { it.id })

        assertFalse(dao.confirmPlanChecked(planId, 1L))
        assertEquals(1, dao.countItemsWithoutMeasure(planId))
        val mId = dao.upsertMeasure(ImprovementMeasure(planId = planId, itemId = kept.id, content = " ", source = MeasureSource.ADDED, dueDate = "2026-10-20"))
        assertEquals(1, dao.countInvalidMeasures(planId))
        assertFalse(dao.confirmPlanChecked(planId, 1L))
        dao.upsertMeasure(ImprovementMeasure(id = mId, planId = planId, itemId = kept.id, content = "每节 5 次提问", source = MeasureSource.ADDED, dueDate = "2026-10-20"))

        assertTrue(dao.confirmPlanChecked(planId, 1L))
        assertEquals(false, dao.isPlanEditable(planId))
        assertNull(dao.saveFocus(teacherA, t, listOf(kept)))

        assertEquals(0, dao.updateMeasureStatusForTeacher(mId, teacherB, MeasureStatus.IN_PROGRESS, 2L))
        assertEquals(1, dao.updateMeasureStatusForTeacher(mId, teacherA, MeasureStatus.IN_PROGRESS, 2L))
        assertEquals(MeasureStatus.IN_PROGRESS, dao.observeOpenTodoRows(teacherA).first().single().status)
    }
}
