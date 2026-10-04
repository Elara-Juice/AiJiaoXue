package edu.neu.aijiaoxue.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import edu.neu.aijiaoxue.data.model.EvalGrade
import edu.neu.aijiaoxue.data.model.Role
import edu.neu.aijiaoxue.data.model.Semester
import edu.neu.aijiaoxue.data.model.TaskStatus
import edu.neu.aijiaoxue.data.model.isTaskOverdue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 演示数据与 docs/演示数据.md 一致，且能支撑文档里写的演示用途。 */
@RunWith(AndroidJUnit4::class)
class DemoDataTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        DemoData.seedIfEmpty(db)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun userId(account: String) = db.userDao().getByAccount(account)!!.id

    @Test
    fun seedsOnlyOnce() = runBlocking {
        DemoData.seedIfEmpty(db)
        assertEquals(6, db.userDao().count())
        assertEquals(5, db.courseDao().observeRows("").first().size)
    }

    @Test
    fun usersAndPasswords() = runBlocking {
        val expected = mapOf(
            "admin" to ("刘建华" to Role.ADMIN),
            "sup01" to ("王志强" to Role.SUPERVISOR),
            "sup02" to ("赵敏" to Role.SUPERVISOR),
            "tea01" to ("张晓明" to Role.TEACHER),
            "tea02" to ("李静" to Role.TEACHER),
            "tea03" to ("孙伟" to Role.TEACHER),
        )
        for ((account, nameRole) in expected) {
            val u = db.userDao().getByAccount(account)!!
            assertEquals(nameRole, u.name to u.role)
            assertTrue(PasswordHasher.verify("123456", u.passwordHash))
            assertTrue(u.passwordHash != "123456")
        }
    }

    @Test
    fun coursesMatchDoc() = runBlocking {
        val rows = db.courseDao().observeRows("").first().associateBy { it.code }
        assertEquals(listOf("FT2026-01", "FT2026-02", "FT2026-03", "FT2026-04", "FT2026-05"), rows.keys.toList())
        with(rows.getValue("FT2026-01")) {
            assertEquals("数据结构", name); assertEquals("张晓明", teacherName)
            assertEquals("软件2401班", className); assertEquals("周一 1-2 节", schedule); assertEquals("一号楼 A201", location)
            assertEquals(0, completedCount)
        }
        with(rows.getValue("FT2026-02")) {
            assertEquals("操作系统", name); assertEquals("软件2402班", className); assertEquals("一号楼 A305", location)
            assertEquals(1, completedCount); assertEquals("2026-09-16", lastSupervisionDate)
        }
        with(rows.getValue("FT2026-03")) {
            assertEquals("软件工程", name); assertEquals("李静", teacherName); assertEquals("软件2404班", className)
        }
        with(rows.getValue("FT2026-04")) {
            assertEquals("计算机网络", name); assertEquals("软件2403班", className); assertEquals("周四 1-2 节", schedule)
        }
        with(rows.getValue("FT2026-05")) {
            assertEquals("高等数学", name); assertEquals("孙伟", teacherName); assertEquals("软件2402班", className)
            assertNull(location)
            assertTrue(hasOpenArrangement); assertTrue(isKeyFocus)
        }
        // 张晓明、李静各 2 门课
        assertEquals(2, db.courseDao().observeByTeacher(userId("tea01")).first().size)
        assertEquals(2, db.courseDao().observeByTeacher(userId("tea02")).first().size)
    }

    @Test
    fun uncoveredThisSemester() = runBlocking {
        val codes = db.courseDao().observeUncoveredRows(Semester.START_DATE).first().map { it.code }
        assertEquals(listOf("FT2026-01", "FT2026-03", "FT2026-04", "FT2026-05"), codes)
    }

    @Test
    fun tasksAndOverdue() = runBlocking {
        val tasks = db.taskDao().observeRowsFiltered(null, null, null).first().associateBy { it.code }
        assertEquals(setOf("DD20260916-001", "DD20260610-001", "DD20260928-001"), tasks.keys)
        assertEquals(TaskStatus.COMPLETED, tasks.getValue("DD20260916-001").status)
        assertEquals("王志强", tasks.getValue("DD20260916-001").supervisorName)
        assertEquals("赵敏", tasks.getValue("DD20260610-001").supervisorName)
        val late = tasks.getValue("DD20260928-001")
        assertTrue(isTaskOverdue(late.status, late.supervisionDate, today = "2026-10-03"))
        // 新建任务时编号不会和预置任务冲突
        assertEquals(1, db.taskDao().countByCodePrefix("DD20260916-"))
    }

    @Test
    fun evaluationsAndFeedback() = runBlocking {
        val feedback = db.evaluationDao().observeFeedbackRows(userId("tea01")).first()
        assertEquals(1, feedback.size)
        assertEquals(81, feedback[0].totalScore)
        assertEquals(EvalGrade.GOOD, feedback[0].grade)
        assertNull(feedback[0].teacherReadAt)
        assertEquals(1, db.evaluationDao().observeUnreadCount(userId("tea01")).first())
        assertTrue(db.evaluationDao().observeFeedbackRows(userId("tea03")).first().isEmpty())

        val se = db.evaluationDao().observeFeedbackRows(userId("tea02")).first().single()
        assertEquals(95, se.totalScore)
        assertEquals(EvalGrade.EXCELLENT, se.grade)

        val osIssues = db.evaluationDao().getIssues(feedback[0].evaluationId).map { it.description }
        assertEquals(listOf("提问以封闭式问题为主，缺少启发性提问", "后排学生低头较多，参与度不足"), osIssues)
        assertEquals(1, db.evaluationDao().countIssues(se.evaluationId))

        // 王志强的历史记录只有操作系统一条
        val history = db.taskDao().observeHistoryForSupervisor(userId("sup01"), "", "", "").first()
        assertEquals(listOf("操作系统"), history.map { it.courseName })
        assertTrue(db.improvementDao().canPlan(feedback[0].taskId, userId("tea01")))
    }
}
