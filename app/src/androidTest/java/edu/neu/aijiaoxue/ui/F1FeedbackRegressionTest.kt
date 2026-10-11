package edu.neu.aijiaoxue.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.DemoData
import edu.neu.aijiaoxue.data.Session
import android.app.Application
import edu.neu.aijiaoxue.data.dao.CourseRow
import edu.neu.aijiaoxue.data.entity.SupervisionTask
import edu.neu.aijiaoxue.data.model.SupervisionType
import edu.neu.aijiaoxue.data.model.TaskStatus
import edu.neu.aijiaoxue.ui.arrange.ArrangementScreen
import edu.neu.aijiaoxue.ui.arrange.TaskProgressScreen
import edu.neu.aijiaoxue.ui.course.CourseDetailScreen
import edu.neu.aijiaoxue.ui.course.CourseSearchScreen
import edu.neu.aijiaoxue.ui.course.TaskCreateScreen
import edu.neu.aijiaoxue.ui.course.TaskCreateViewModel
import edu.neu.aijiaoxue.ui.feedback.FeedbackDetailScreen
import edu.neu.aijiaoxue.ui.feedback.FeedbackListScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 10/7 回归：F1（US01、02、04、05、06）和 US33，用例按需求说明各故事的验收标准编写。
 * 跑在模拟器的真实数据库上，测试新建的任务、安排在 tearDown 里删除，改过的已读状态恢复原值。
 */
@RunWith(AndroidJUnit4::class)
class F1FeedbackRegressionTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = AppDatabase.get(context)
    private val sql get() = db.openHelper.writableDatabase
    private val cleanup = mutableListOf<() -> Unit>()

    @Before
    fun setUp() = runBlocking<Unit> { DemoData.seedIfEmpty(db) }

    @After
    fun tearDown() {
        cleanup.asReversed().forEach { it() }
        Session.signOut(context)
    }

    private fun signIn(account: String) = runBlocking {
        Session.signIn(context, db.userDao().getByAccount(account)!!)
    }

    private fun course(code: String): CourseRow =
        runBlocking { db.courseDao().observeRows("").first().first { it.code == code } }

    private fun waitFor(text: String, substring: Boolean = false) = rule.waitUntil(10_000) {
        rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
    }

    private fun search(keyword: String) =
        rule.onNode(hasSetTextAction()).performTextReplacement(keyword)

    // ---------- US01 ----------

    @Test
    fun us01_searchByTeacherAndCourseName_trimsSpaces_showsEmptyHint() {
        signIn("sup01")
        rule.setContent { CourseSearchScreen(onBack = {}, onCourseClick = {}) }

        search("张晓明")
        waitFor("数据结构")
        rule.onNodeWithText("操作系统").assertExists()
        rule.onNodeWithText("软件工程").assertDoesNotExist()

        search("  软件工程 ")
        waitFor("软件工程")
        rule.onNodeWithText("数据结构").assertDoesNotExist()

        search("FT2026-05")
        waitFor("高等数学")
        // US05 验收④：已纳入安排的课程在检索结果里显示“待督导”
        rule.onNodeWithText("待督导", substring = true).assertExists()

        search("不存在的课程xyz")
        waitFor("未找到相关课程，请更换关键词")
    }

    // ---------- US02 ----------

    @Test
    fun us02_detailShowsCoreInfo_missingFieldShowsUnfilled() {
        signIn("sup01")
        val math = course("FT2026-05")
        rule.setContent { CourseDetailScreen(math.id, onBack = {}, onCreateTask = {}) }
        waitFor("高等数学")
        rule.onNodeWithText("课程编号：FT2026-05").assertExists()
        rule.onNodeWithText("任课教师：孙伟").assertExists()
        rule.onNodeWithText("上课班级：软件2402班").assertExists()
        rule.onNodeWithText("上课时间：周五 3-4 节").assertExists()
        rule.onNodeWithText("上课地点：未填写").assertExists()
        // D04：没有已完成的督导时显示“暂无”
        assertNull(math.lastSupervisionDate)
        rule.onNodeWithText("最近督导日期：暂无").assertExists()
        rule.onNodeWithText("已纳入待督导安排").assertExists()
        rule.onNodeWithText("创建督导任务").assertExists()
    }

    @Test
    fun us02_historyCountsCompletedOnly_andCreateEntry() {
        signIn("sup01")
        val os = course("FT2026-02")
        var createdFor: Long? = null
        rule.setContent { CourseDetailScreen(os.id, onBack = {}, onCreateTask = { createdFor = it }) }
        waitFor("操作系统")
        rule.onNodeWithText("历史督导次数：${os.completedCount}").assertExists()
        rule.onNodeWithText("最近督导日期：${os.lastSupervisionDate}").assertExists()
        assertTrue(os.completedCount >= 1)
        rule.onNodeWithText("创建督导任务").performClick()
        assertEquals(os.id, createdFor)
    }

    @Test
    fun us02_adminHasNoCreateTaskButton() {
        signIn("admin")
        val os = course("FT2026-02")
        rule.setContent { CourseDetailScreen(os.id, onBack = {}, onCreateTask = {}) }
        waitFor("操作系统")
        rule.onNodeWithText("创建督导任务").assertDoesNotExist()
    }

    // ---------- US04 ----------

    @Test
    fun us04_requiredFields_createUnique_rejectDuplicate() {
        signIn("sup01")
        val sup = Session.requireUser()
        val target = course("FT2026-03")
        val date = "2099-01-01"
        val pendingBefore = runBlocking { db.taskDao().getPendingArrangement(target.id) }
        cleanup += {
            sql.execSQL(
                "DELETE FROM supervision_tasks WHERE supervisorId = ? AND courseId = ? AND supervisionDate = ?",
                arrayOf(sup.id, target.id, date),
            )
            // create() 会认领待督导安排，测试后恢复
            pendingBefore?.let { sql.execSQL("UPDATE arrangements SET status = 'PENDING' WHERE id = ?", arrayOf(it.id)) }
        }

        // D03：日期用选择器、节次用下拉。2099 年在日历里翻不到，日期直接交给 ViewModel（与选择器回调同一入口）
        val vm = TaskCreateViewModel(context.applicationContext as Application, target.id)
        var startedTaskId: Long? = null
        var openedMyTasks = false
        rule.setContent {
            TaskCreateScreen(
                target.id, onBack = {}, onStartCapture = { startedTaskId = it }, onMyTasks = { openedMyTasks = true },
                viewModel = vm,
            )
        }
        fun pickPeriod(label: String, n: Int) {
            rule.onNodeWithText("$label：", substring = true).performClick()
            rule.onNode(hasText("第 $n 节") and hasAnyAncestor(isPopup())).performScrollTo().performClick()
        }

        // 日期默认今天，点开是日期选择器，不是文本框
        rule.onNodeWithText("督导日期：", substring = true).performClick()
        waitFor("确定日期")
        rule.onNodeWithText("取消").performClick()
        assertEquals(0, rule.onAllNodes(hasSetTextAction() and hasText("督导日期", substring = true)).fetchSemanticsNodes().size)
        vm.onDateChange(date)
        waitFor("督导日期：$date", substring = true)

        // 未选节次：提示在节次下方，具体到哪一项
        rule.onNodeWithText("创建任务").performClick()
        waitFor("请选择开始节次")
        assertEquals(0, countTasks(sup.id, target.id, date))

        // 选第 5 节，结束节自动带出第 6 节；再把结束节改成第 3 节
        pickPeriod("开始节次", 5)
        waitFor("结束节次：第 6 节", substring = true)
        pickPeriod("结束节次", 3)
        rule.onNodeWithText("创建任务").performClick()
        waitFor("结束节次不能早于开始节次")
        assertEquals(0, countTasks(sup.id, target.id, date))

        // 日期为空（选择器本身不会产生，只在 ViewModel 里兜底）
        vm.onDateChange("")
        rule.onNodeWithText("创建任务").performClick()
        waitFor("请选择督导日期")
        vm.onDateChange(date)

        pickPeriod("结束节次", 6)
        rule.onNodeWithText("创建任务").performClick()
        waitFor("任务创建成功，已加入督导任务")
        val task = runBlocking { db.taskDao().observeRowsFiltered(target.id, sup.id, null).first() }
            .single { it.supervisionDate == date }
        assertEquals("DD20990101-001", task.code)
        assertEquals("5-6", task.period)
        assertEquals(TaskStatus.NOT_STARTED, task.status)
        // D02：成功页显示任务编号，可直接开始采集或进入我的督导任务
        waitFor("任务编号：DD20990101-001", substring = true)
        rule.onNodeWithText("返回课程").assertExists()
        rule.onNodeWithText("查看我的督导任务").performClick()
        assertTrue(openedMyTasks)
        rule.onNodeWithText("开始采集").performClick()
        assertEquals(task.id, startedTaskId)

        // 同一督导、课程、日期、节次再建一次（新的 ViewModel，相当于重新进入创建页）
        val again = TaskCreateViewModel(context.applicationContext as Application, target.id)
        again.onDateChange(date)
        again.onPeriodStartChange(5)
        again.onPeriodEndChange(6)
        again.create()
        val deadline = System.currentTimeMillis() + 5_000
        while (again.uiState.value.error == null && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertEquals("任务已存在", again.uiState.value.error)
        assertEquals(1, countTasks(sup.id, target.id, date))
    }

    private fun countTasks(supId: Long, courseId: Long, date: String) =
        sql.query(
            "SELECT COUNT(*) FROM supervision_tasks WHERE supervisorId = ? AND courseId = ? AND supervisionDate = ?",
            arrayOf(supId, courseId, date),
        ).use { it.moveToFirst(); it.getInt(0) }

    // ---------- US05 ----------

    @Test
    fun us05_uncoveredFilter_addArrangementWithKeyFocus_rejectDuplicate() {
        signIn("admin")
        val ds = course("FT2026-01")
        // 前提：数据结构没有未完成安排（演示数据如此；被演示改过时先跳过，不误删用户数据）
        org.junit.Assume.assumeTrue(!ds.hasOpenArrangement)
        cleanup += { sql.execSQL("DELETE FROM arrangements WHERE courseId = ?", arrayOf(ds.id)) }

        val vm = edu.neu.aijiaoxue.ui.arrange.ArrangementViewModel(context.applicationContext as android.app.Application)
        rule.setContent { ArrangementScreen(onBack = {}, onCourseClick = {}, viewModel = vm) }
        waitFor("数据结构", substring = true)
        // D04：数据结构没有督导记录，显示“暂无”
        assertNull(ds.lastSupervisionDate)
        waitFor("最近督导：暂无")

        // 筛选本学期未督导：本学期已完成督导的操作系统不出现
        rule.onAllNodes(isToggleable())[0].performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("操作系统", substring = true).fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithText("数据结构", substring = true).assertExists()

        rule.onAllNodesWithText("安排此课程")[0].performClick()
        rule.onAllNodes(isToggleable())[1].performClick() // 重点关注
        rule.onNode(hasSetTextAction()).performTextReplacement("回归测试")
        rule.onNodeWithText("纳入安排").performClick()
        waitFor("已纳入待督导安排")
        // D05：成功写在 message，error 为空
        assertEquals("已纳入待督导安排", vm.uiState.value.message)
        assertNull(vm.uiState.value.error)

        val arrangement = runBlocking { db.arrangementDao().getOpenByCourse(ds.id) }
        assertNotNull(arrangement)
        assertTrue(arrangement!!.isKeyFocus)
        assertEquals("回归测试", arrangement.note)
        assertTrue(course("FT2026-01").hasOpenArrangement)
        // 已纳入的课程不再给“安排此课程”按钮，界面上无法重复纳入
        assertEquals(1, openArrangements(ds.id))
    }

    @Test
    fun us05_duplicateArrangement_isRejectedByViewModel() {
        signIn("admin")
        val math = course("FT2026-05")
        org.junit.Assume.assumeTrue(math.hasOpenArrangement)
        val before = openArrangements(math.id)
        val vm = edu.neu.aijiaoxue.ui.arrange.ArrangementViewModel(context.applicationContext as android.app.Application)
        vm.selectCourse(math)
        vm.addArrangement()
        val deadline = System.currentTimeMillis() + 5_000
        while (vm.uiState.value.error == null && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertEquals("该课程已在待督导安排中", vm.uiState.value.error)
        assertNull(vm.uiState.value.message)
        assertEquals(before, openArrangements(math.id))
    }

    private fun openArrangements(courseId: Long) =
        sql.query("SELECT COUNT(*) FROM arrangements WHERE courseId = ? AND status != 'DONE'", arrayOf(courseId))
            .use { it.moveToFirst(); it.getInt(0) }

    // ---------- US06 ----------

    @Test
    fun us06_listCountsOverdue_andFilterByStatus() {
        signIn("admin")
        // 自建一条逾期任务，不依赖演示数据当前状态（演示中计算机网络那条可能已被推进）
        val net = course("FT2026-04")
        val sup02 = runBlocking { db.userDao().getByAccount("sup02")!! }
        val lateId = runBlocking {
            db.taskDao().create(
                SupervisionTask(
                    code = "", courseId = net.id, supervisorId = sup02.id,
                    supervisionDate = "2000-01-03", period = "9-10", type = SupervisionType.SPECIAL,
                ),
            )!!
        }
        cleanup += { sql.execSQL("DELETE FROM supervision_tasks WHERE id = ?", arrayOf(lateId)) }
        val counts = runBlocking { db.taskDao().observeStatusCounts().first() }.associate { it.status to it.count }

        rule.setContent { TaskProgressScreen(onBack = {}) }
        waitFor("状态：全部状态")
        rule.waitUntil(10_000) { rule.onAllNodes(hasScrollAction()).fetchSemanticsNodes().isNotEmpty() }

        // 各状态数量与数据库一致
        TaskStatus.entries.forEach { status ->
            rule.onNodeWithText("${status.label} ${counts[status] ?: 0}", substring = true).assertExists()
        }
        // 超过督导日期仍待开始 → 已逾期
        val lateRow = "计算机网络 · 2000-01-03 9-10"
        rule.onNode(hasScrollAction()).performScrollToNode(hasText(lateRow))
        rule.onAllNodesWithText("待开始 · 已逾期").fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }

        // 按督导筛选赵敏：王志强的操作系统消失，自建任务仍在
        rule.onNodeWithText("督导：全部督导").performClick()
        rule.onNode(hasText("赵敏") and hasAnyAncestor(isPopup())).performClick()
        waitFor("督导：赵敏")
        rule.waitUntil(5_000) { rule.onAllNodesWithText("操作系统 · 2026-09-16 3-4").fetchSemanticsNodes().isEmpty() }
        rule.onNode(hasScrollAction()).performScrollToNode(hasText(lateRow))

        // 再按状态筛选“已完成”：逾期任务消失
        rule.onNodeWithText("状态：全部状态").performClick()
        rule.onNode(hasText("已完成") and hasAnyAncestor(isPopup())).performClick()
        waitFor("状态：已完成")
        rule.waitUntil(5_000) { rule.onAllNodesWithText(lateRow).fetchSemanticsNodes().isEmpty() }

        // 回到全部状态、全部督导，点自建任务看基本信息
        rule.onNodeWithText("状态：已完成").performClick()
        rule.onNode(hasText("全部状态") and hasAnyAncestor(isPopup())).performClick()
        rule.onNodeWithText("督导：赵敏").performClick()
        rule.onNode(hasText("全部督导") and hasAnyAncestor(isPopup())).performClick()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText(lateRow))
        rule.onNodeWithText(lateRow).performClick()
        waitFor("任务信息")
        rule.onNodeWithText("状态：待开始（已逾期）", substring = true).assertExists()
    }

    // ---------- US33 ----------

    @Test
    fun us33_teacherSeesOwnSubmittedFeedback_unreadThenRead() {
        signIn("tea01")
        val teacherId = Session.requireUser().id
        val row = runBlocking { db.evaluationDao().observeFeedbackRows(teacherId).first() }
            .first { it.courseName == "操作系统" && it.supervisionDate == "2026-09-16" }
        restoreReadAt(row.evaluationId, row.teacherReadAt)
        sql.execSQL("UPDATE evaluations SET teacherReadAt = NULL WHERE id = ?", arrayOf(row.evaluationId))

        var opened: Long? = null
        rule.setContent { FeedbackListScreen(onBack = {}, onFeedbackClick = { opened = it }) }
        waitFor("操作系统")
        rule.onNodeWithText("未读").assertExists()
        // 只能看本人课程：李静的软件工程不出现
        rule.onNodeWithText("软件工程").assertDoesNotExist()
        rule.onNodeWithText("操作系统").performClick()
        assertEquals(row.taskId, opened)
    }

    @Test
    fun us33_detailShowsStructuredEvaluation_andMarksRead() {
        signIn("tea01")
        val teacherId = Session.requireUser().id
        val row = runBlocking { db.evaluationDao().observeFeedbackRows(teacherId).first() }
            .first { it.courseName == "操作系统" && it.supervisionDate == "2026-09-16" }
        restoreReadAt(row.evaluationId, row.teacherReadAt)
        sql.execSQL("UPDATE evaluations SET teacherReadAt = NULL WHERE id = ?", arrayOf(row.evaluationId))

        var focusOpened = false
        rule.setContent { FeedbackDetailScreen(row.taskId, onBack = {}, onImproveFocus = { focusOpened = true }) }
        waitFor("综合得分：81 · 良好")
        rule.onNodeWithText("督导：王志强 · 2026-09-16").assertExists()
        rule.onNodeWithText("教学内容：5 分", substring = true).assertExists()
        rule.onNodeWithText("课堂互动：3 分", substring = true).assertExists()
        rule.onNodeWithText("概念讲解清晰，调度算法动画演示直观").assertExists()
        rule.onNodeWithText("增加开放式提问；结合小组讨论调动后排学生").assertExists()
        rule.onNodeWithText("整体教学效果良好，建议在课堂互动方面重点改进").assertExists()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("1. 提问以封闭式问题为主，缺少启发性提问"))
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("2. 后排学生低头较多，参与度不足"))

        rule.waitUntil(5_000) { readAt(row.evaluationId) != null }

        // D01：反馈详情底部有“确定改进重点”，进入 US36
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("确定改进重点"))
        rule.onNodeWithText("确定改进重点").performClick()
        assertTrue(focusOpened)
    }

    @Test
    fun us33_otherTeacherCannotOpenFeedback() {
        val os = runBlocking {
            db.evaluationDao().observeFeedbackRows(db.userDao().getByAccount("tea01")!!.id).first()
        }.first { it.courseName == "操作系统" && it.supervisionDate == "2026-09-16" }
        restoreReadAt(os.evaluationId, os.teacherReadAt)

        signIn("tea02")
        rule.setContent { FeedbackDetailScreen(os.taskId, onBack = {}) }
        waitFor("无权查看此反馈或评价尚未提交")
        rule.onNodeWithText("综合得分", substring = true).assertDoesNotExist()
        // 越权打开不能把别人的反馈标成已读
        assertEquals(os.teacherReadAt, readAt(os.evaluationId))
    }

    private fun readAt(evaluationId: Long): Long? =
        sql.query("SELECT teacherReadAt FROM evaluations WHERE id = ?", arrayOf(evaluationId))
            .use { it.moveToFirst(); if (it.isNull(0)) null else it.getLong(0) }

    private fun restoreReadAt(evaluationId: Long, original: Long?) {
        cleanup += { sql.execSQL("UPDATE evaluations SET teacherReadAt = ? WHERE id = ?", arrayOf(original, evaluationId)) }
    }

}
