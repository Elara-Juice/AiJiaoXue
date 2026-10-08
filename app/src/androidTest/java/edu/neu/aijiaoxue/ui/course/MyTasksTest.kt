package edu.neu.aijiaoxue.ui.course

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.DemoData
import edu.neu.aijiaoxue.data.Session
import edu.neu.aijiaoxue.data.entity.SupervisionTask
import edu.neu.aijiaoxue.data.model.SupervisionType
import edu.neu.aijiaoxue.data.model.TaskStatus
import edu.neu.aijiaoxue.navigation.Routes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** US07：只列出本人任务；按状态给出“继续采集 / 继续评价”入口，跳转到对应任务。 */
@RunWith(AndroidJUnit4::class)
class MyTasksTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = AppDatabase.get(context)
    private val created = mutableListOf<Long>()
    private val navigated = mutableListOf<String>()

    @Before
    fun setUp() = runBlocking<Unit> {
        DemoData.seedIfEmpty(db)
        Session.signIn(context, db.userDao().getByAccount("sup01")!!)
    }

    @After
    fun tearDown() = runBlocking<Unit> {
        created.forEach {
            db.openHelper.writableDatabase.execSQL("DELETE FROM supervision_tasks WHERE id = ?", arrayOf(it))
        }
        Session.signOut(context)
    }

    /** 建一个测试任务并推进到指定状态。日期用 2099 年，避免和演示数据冲突。 */
    private suspend fun createTask(account: String, date: String, status: TaskStatus): Long {
        val sup = db.userDao().getByAccount(account)!!
        val course = db.courseDao().observeRows("").first().first()
        val id = db.taskDao().create(
            SupervisionTask(
                code = "",
                courseId = course.id,
                supervisorId = sup.id,
                supervisionDate = date,
                period = "1-2",
                type = SupervisionType.REGULAR,
            ),
        )!!
        created += id
        val now = System.currentTimeMillis()
        if (status != TaskStatus.NOT_STARTED) db.taskDao().startClass(id, now)
        if (status == TaskStatus.PENDING_EVALUATION) db.taskDao().endClass(id, now)
        return id
    }

    private fun code(taskId: Long) = runBlocking { db.taskDao().getById(taskId)!!.code }

    private fun show() {
        rule.setContent { MyTasksScreen(onBack = {}, onNavigate = { navigated += it }) }
    }

    private fun waitFor(text: String) = rule.waitUntil(10_000) {
        rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    /** 列表较长时目标卡片可能在屏幕外。 */
    private fun scrollTo(taskId: Long) {
        rule.onNode(hasScrollAction()).performScrollToNode(hasTestTag(taskCardTag(taskId)))
    }

    /** 只在指定任务的卡片里找，避免演示数据或其他测试任务里的同名按钮干扰。 */
    private fun inCard(taskId: Long, text: String) =
        rule.onNode(hasText(text) and hasAnyAncestor(hasTestTag(taskCardTag(taskId))))

    @Test
    fun continueButtons_routeByStatus() = runBlocking<Unit> {
        val inProgress = createTask("sup01", "2099-02-01", TaskStatus.IN_PROGRESS)
        val pending = createTask("sup01", "2099-02-02", TaskStatus.PENDING_EVALUATION)
        show()
        waitFor(code(inProgress))

        scrollTo(inProgress)
        inCard(inProgress, "继续采集").performClick()
        assertEquals(Routes.capture(inProgress), navigated.last())

        scrollTo(pending)
        inCard(pending, "继续评价").performClick()
        assertEquals(Routes.evaluation(pending), navigated.last())
        // 待评价仍可回采集页补充材料
        inCard(pending, "查看材料").performClick()
        assertEquals(Routes.capture(pending), navigated.last())
    }

    @Test
    fun onlyOwnTasksAreListed() = runBlocking<Unit> {
        val mine = createTask("sup01", "2099-02-03", TaskStatus.IN_PROGRESS)
        val others = createTask("sup02", "2099-02-04", TaskStatus.IN_PROGRESS)
        show()
        waitFor(code(mine))
        // 硬性规则 6：别人的任务不在查询结果里
        rule.onNodeWithText(code(others), substring = true).assertDoesNotExist()
    }

    @Test
    fun overdueNotStartedTask_isMarked() = runBlocking<Unit> {
        // 不用演示数据 DD20260928-001：手动测试点过“开始采集”后它就不再是待开始
        val overdue = createTask("sup01", "2000-01-01", TaskStatus.NOT_STARTED)
        val future = createTask("sup01", "2099-02-05", TaskStatus.NOT_STARTED)
        show()
        waitFor(code(overdue))
        scrollTo(overdue)
        inCard(overdue, "待开始 · 已逾期").assertExists()
        inCard(overdue, "开始采集").assertExists()
        scrollTo(future)
        inCard(future, "待开始").assertExists()
        inCard(future, "待开始 · 已逾期").assertDoesNotExist()
    }
}
