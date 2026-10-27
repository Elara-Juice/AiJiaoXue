package edu.neu.aijiaoxue

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.core.content.IntentCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.DemoData
import edu.neu.aijiaoxue.data.FileStore
import edu.neu.aijiaoxue.data.Session
import edu.neu.aijiaoxue.data.model.ArrangementStatus
import edu.neu.aijiaoxue.data.model.DateFormats
import edu.neu.aijiaoxue.data.model.EngagementType
import edu.neu.aijiaoxue.data.model.EvalGrade
import edu.neu.aijiaoxue.data.model.EvalStatus
import edu.neu.aijiaoxue.data.model.InteractionType
import edu.neu.aijiaoxue.data.model.MaterialType
import edu.neu.aijiaoxue.data.model.MeasureSource
import edu.neu.aijiaoxue.data.model.MeasureStatus
import edu.neu.aijiaoxue.data.model.PlanStatus
import edu.neu.aijiaoxue.data.model.Priority
import edu.neu.aijiaoxue.data.model.TaskStatus
import edu.neu.aijiaoxue.ui.capture.AudioRecorder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 需求说明表 6-1 端到端验收场景（主链 11 步），同一门课程 A 连续走完，回归时直接跑。
 *
 * - 启动真实 MainActivity，全部经由页面操作。
 * - 课程 A 由测试自建（端到端演示课程A，任课教师张晓明），不动 docs/演示数据.md 里的数据结构课程，
 *   也不清空模拟器上已采集的数据；测试结束（含失败）删除本测试产生的全部记录和文件。
 * - 第 5 步无法在测试进程内真正杀进程：关闭 Activity（ViewModel 随之销毁）后重新启动，
 *   录音按设计由前台服务继续，异常退出后的录音补写由 CaptureRecordingTest 覆盖。
 */
@RunWith(AndroidJUnit4::class)
class EndToEndTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val db = AppDatabase.get(context)
    private val sql get() = db.openHelper.writableDatabase
    private var scenario: ActivityScenario<MainActivity>? = null
    private var courseId = 0L

    /** 系统相机替身：往 TakePicture 给的 Uri 写一张 JPEG 并返回成功，不真正启动相机。 */
    private val camera = object : Instrumentation.ActivityMonitor() {
        override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
            if (intent.action != MediaStore.ACTION_IMAGE_CAPTURE) return null
            val uri = IntentCompat.getParcelableExtra(intent, MediaStore.EXTRA_OUTPUT, Uri::class.java) ?: return null
            val bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).drawColor(Color.rgb(30, 90, 160))
            context.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            bitmap.recycle()
            return Instrumentation.ActivityResult(Activity.RESULT_OK, null)
        }
    }

    @Before
    fun setUp() = runBlocking<Unit> {
        instrumentation.uiAutomation.apply {
            grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        DemoData.seedIfEmpty(db)
        removeLeftovers()
        courseId = db.courseDao().insertAll(
            listOf(
                edu.neu.aijiaoxue.data.entity.Course(
                    code = COURSE_CODE, name = COURSE_NAME, teacherId = userId("tea01"),
                    className = "软件2401班", schedule = "周四 9-10 节", location = "一号楼 A101",
                ),
            ),
        ).single()
        instrumentation.addMonitor(camera)
        Session.signOut(context)
        launchApp()
    }

    @After
    fun tearDown() = runBlocking<Unit> {
        instrumentation.removeMonitor(camera)
        if (AudioRecorder.state.value.status != AudioRecorder.Status.IDLE) AudioRecorder.stop()
        scenario?.close()
        removeLeftovers()
        Session.signOut(context)
    }

    @Test
    fun mainChain_11Steps() {
        // ---------- 1. 管理人员筛选未督导课程，将课程 A 纳入督导安排并标记重点关注 ----------
        login("admin")
        openHomeEntry("督导安排")
        waitText("仅看本学期未督导课程")
        rule.onAllNodes(isToggleable())[0].performClick()
        val rowTitle = "$COURSE_NAME · $COURSE_CODE"
        scrollAny(hasText(rowTitle))
        // 课程编号 E2E-A 排在 FT2026-xx 之前，列表里第一张卡片就是课程 A
        rule.onAllNodesWithText("安排此课程")[0].performClick()
        rule.onAllNodes(isToggleable())[1].performClick() // 重点关注
        rule.onNode(hasSetTextAction()).performTextReplacement("端到端验收")
        rule.onNodeWithText("纳入安排").performClick()
        waitText("已纳入待督导安排")
        val arrangement = awaitDb { db.arrangementDao().getOpenByCourse(courseId) }
        assertTrue(arrangement.isKeyFocus)
        assertEquals(ArrangementStatus.PENDING, arrangement.status)
        scrollAny(hasText("待督导 · 重点关注"))

        // ---------- 2. 督导检索课程 A，查看详情 ----------
        goHome(ADMIN_HOME)
        relogin("sup01")
        openHomeEntry("课程检索")
        rule.onNode(hasSetTextAction()).performTextReplacement(COURSE_NAME)
        waitText("待督导 · 重点关注")
        // 检索框里也有课程名，只点结果卡片
        rule.onNode(hasText(COURSE_NAME) and !hasSetTextAction()).performClick()
        waitText("课程编号：$COURSE_CODE")
        rule.onNodeWithText("任课教师：张晓明").assertExists()
        rule.onNodeWithText("上课班级：软件2401班").assertExists()
        rule.onNodeWithText("上课时间：周四 9-10 节").assertExists()
        rule.onNodeWithText("上课地点：一号楼 A101").assertExists()
        rule.onNodeWithText("已纳入待督导安排").assertExists()

        // ---------- 3. 创建督导任务：唯一任务、待开始、进度页同步可见 ----------
        rule.onNodeWithText("创建督导任务").performClick()
        // 日期默认今天（选择器）；节次下拉选第 9 节，结束节自动带出第 10 节
        waitText("督导日期：${DateFormats.today()}", substring = true)
        rule.onNodeWithText("开始节次", substring = true).performClick()
        rule.onNode(hasText("第 9 节") and hasAnyAncestor(isPopup())).performScrollTo().performClick()
        waitText("结束节次：第 10 节", substring = true)
        rule.onNode(hasSetTextAction() and hasText("教学章节", substring = true)).performTextReplacement("第1章 绪论")
        closeSoftKeyboard()
        rule.onNodeWithText("创建任务").performClick()
        waitText("任务创建成功，已加入督导任务")
        val task = awaitDb { tasks().singleOrNull() }
        val taskId = task.id
        assertEquals(TaskStatus.NOT_STARTED, task.status)
        assertEquals(DateFormats.today(), task.supervisionDate)
        assertEquals("9-10", task.period)
        assertTrue(task.code.startsWith("DD${DateFormats.compact(task.supervisionDate)}-"))
        assertEquals(arrangement.id, task.arrangementId)
        assertEquals(ArrangementStatus.CLAIMED, arrangementStatus(arrangement.id))
        // 任务进度页（US06）用的同一条查询
        val progress = runBlocking { db.taskDao().observeRowsFiltered(courseId, null, null).first() }
        assertEquals(listOf(task.code), progress.map { it.code })

        // ---------- 4. 开始录音，拍 2 张 PPT，标注 3 次教师提问、2 次学生回答，记录 1 次参与状态 ----------
        // 王博田缺陷 D02：从创建成功页直接进入采集
        waitText("任务编号：${task.code}", substring = true)
        rule.onNodeWithText("开始采集").performClick()
        clickIn(CAPTURE, hasText("开始录音"))
        rule.waitUntil(TIMEOUT) {
            AudioRecorder.state.value.let { it.status == AudioRecorder.Status.RECORDING && it.taskId == taskId }
        }
        repeat(2) { takePhoto(taskId, expected = it + 1) }
        mark(taskId, InteractionType.TEACHER_QUESTION, 3)
        mark(taskId, InteractionType.STUDENT_ANSWER, 2)
        val classStart = runBlocking { db.taskDao().getById(taskId)!!.classStartAt!! }
        recordEngagement()
        val captured = runBlocking { db.taskDao().getById(taskId)!! }
        assertEquals(TaskStatus.IN_PROGRESS, captured.status)
        val materials = materials(taskId)
        val audio = materials.single { it.type == MaterialType.AUDIO }
        assertNull("录音中，尚未写结束时间", audio.endedAt)
        val photos = materials.filter { it.type != MaterialType.AUDIO }
        assertEquals(listOf(MaterialType.PPT, MaterialType.PPT), photos.map { it.type })
        (materials).forEach { assertTrue(it.capturedAt >= classStart && it.offsetMs >= 0) }
        val events = events(taskId)
        assertEquals(3, events.count { it.type == InteractionType.TEACHER_QUESTION })
        assertEquals(2, events.count { it.type == InteractionType.STUDENT_ANSWER })
        events.forEach { assertTrue(it.occurredAt >= classStart && it.offsetMs >= 0) }
        val engagement = awaitDb { engagements(taskId).singleOrNull() }
        assertEquals(EngagementType.LISTENING, engagement.type)
        assertEquals(40, engagement.presentCount)
        assertEquals(35, engagement.engagedCount)
        assertTrue(engagement.recordedAt >= classStart && engagement.offsetMs >= 0)

        // ---------- 5. 关闭应用后重新进入该任务：已采集内容全部恢复，可继续采集 ----------
        scenario!!.close()
        launchApp()
        waitText(SUPERVISOR_HOME)
        openHomeEntry("我的督导任务")
        scrollAny(hasTestTag("task-$taskId"))
        rule.onNode(hasText("继续采集") and hasAnyAncestor(hasTestTag("task-$taskId"))).performClick()
        waitTag(CAPTURE)
        scrollTo(CAPTURE, hasContentDescription("标注教师提问，已标注 3 次"))
        scrollTo(CAPTURE, hasContentDescription("标注学生回答，已标注 2 次"))
        scrollTo(CAPTURE, hasText("PPT 2", substring = true))
        scrollTo(CAPTURE, hasText("录音 1", substring = true))
        assertEquals(AudioRecorder.Status.RECORDING, AudioRecorder.state.value.status)
        assertEquals(taskId, AudioRecorder.state.value.taskId)
        assertEquals(materials.map { it.id }.toSet(), materials(taskId).map { it.id }.toSet())
        assertEquals(events.map { it.id }.toSet(), events(taskId).map { it.id }.toSet())
        assertEquals(1, engagements(taskId).size)

        // ---------- 6. 删除 1 张模糊照片并重新拍摄 ----------
        val blurry = photos.first()
        // 先取消一次：取消确认不做任何修改（US11 异常处理）
        clickIn(CAPTURE, hasContentDescription("删除PPT 1"))
        clickDialog("取消")
        assertTrue(materials(taskId).any { it.id == blurry.id })
        clickIn(CAPTURE, hasContentDescription("删除PPT 1"))
        clickDialog("删除")
        rule.waitUntil(TIMEOUT) { rule.onAllNodesWithText("PPT 2", substring = true).fetchSemanticsNodes().isEmpty() }
        takePhoto(taskId, expected = 2)
        val retaken = materials(taskId).filter { it.type != MaterialType.AUDIO }
        assertFalse(retaken.any { it.id == blurry.id })
        assertFalse(FileStore.exists(context, blurry.filePath))
        retaken.forEach { assertTrue(FileStore.exists(context, it.filePath)) }
        assertTrue(retaken.all { it.filePath.startsWith("tasks/$taskId/photo/") })

        // ---------- 7. 结束采集，四维评分和综合意见（2 条主要问题），提交 ----------
        clickIn(CAPTURE, hasText("结束课堂采集") and hasClickActionMatcher())
        clickDialog("结束并去评价")
        awaitDb { db.taskDao().getById(taskId)!!.takeIf { it.status == TaskStatus.PENDING_EVALUATION } }
        // 正在录音时结束采集，先结束录音并保存
        val savedAudio = awaitDb { db.captureDao().getMaterial(audio.id)?.takeIf { it.endedAt != null } }
        assertTrue(savedAudio.durationMs!! > 0)
        assertEquals(AudioRecorder.Status.IDLE, AudioRecorder.state.value.status)

        waitTag(EVALUATION)
        listOf(5, 4, 3, 4).zip(listOf("CONTENT", "DESIGN", "INTERACTION", "ENGAGEMENT")).forEach { (score, dim) ->
            clickIn(EVALUATION, hasTestTag("score-$dim-$score"))
        }
        clickIn(EVALUATION, hasText("添加问题"))
        fill(EVALUATION, "问题 1", ISSUE_1)
        clickIn(EVALUATION, hasText("添加问题"))
        fill(EVALUATION, "问题 2", ISSUE_2)
        fill(EVALUATION, "主要优点（选填）", "绪论部分案例引入自然")
        fill(EVALUATION, "改进建议（选填）", SUGGESTION)
        fill(EVALUATION, "总体意见（必填）", OPINION)
        clickIn(EVALUATION, hasText("提交评价"))
        clickDialog("确认提交")
        val evaluation = awaitDb { db.evaluationDao().getByTask(taskId)?.takeIf { it.status == EvalStatus.SUBMITTED } }
        scrollTo(EVALUATION, hasText("评价已提交，内容已锁定"))
        assertEquals(81, evaluation.totalScore)
        assertEquals(EvalGrade.GOOD, evaluation.grade)
        assertEquals(OPINION, evaluation.overallOpinion)
        val issues = runBlocking { db.evaluationDao().getIssues(evaluation.id) }
        assertEquals(listOf(ISSUE_1, ISSUE_2), issues.map { it.description })
        assertEquals(TaskStatus.COMPLETED, runBlocking { db.taskDao().getById(taskId)!!.status })
        assertEquals(ArrangementStatus.DONE, arrangementStatus(arrangement.id))
        clickIn(EVALUATION, hasText("查看历史归档"))
        waitText("完整督导报告 · 只读")

        // ---------- 8. 督导按教师姓名检索历史记录并打开 ----------
        goHome(SUPERVISOR_HOME)
        openHomeEntry("历史记录")
        waitText("历史督导记录")
        rule.onNode(hasSetTextAction() and hasText("课程名称或教师姓名")).performTextReplacement("张晓明")
        closeSoftKeyboard()
        rule.onNodeWithText("检索").performClick()
        clickAny(hasText(COURSE_NAME))
        waitText("完整督导报告 · 只读")
        waitText("81 分 · 良好 · 已归档")
        scrollAny(hasText("材料 3 份 · 互动 5 次 · 参与记录 1 条"))
        scrollAny(hasText(OPINION, substring = true))
        rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().let { assertTrue("历史记录只读", it.isEmpty()) }

        // ---------- 9. 课程 A 的任课教师查看反馈；其他教师不可见 ----------
        goHome(SUPERVISOR_HOME)
        relogin("tea02")
        openHomeEntry("督导反馈")
        waitText("软件工程")
        rule.onNodeWithText(COURSE_NAME).assertDoesNotExist()
        assertNull(runBlocking { db.evaluationDao().getSubmittedForTeacher(taskId, userId("tea02")) })

        goHome(TEACHER_HOME)
        relogin("tea01")
        openHomeEntry("督导反馈")
        clickAny(hasText(COURSE_NAME) and hasText("未读"))
        waitText("综合得分：81 · 良好")
        scrollAny(hasText(OPINION))
        scrollAny(hasText("1. $ISSUE_1"))
        scrollAny(hasText("2. $ISSUE_2"))
        awaitDb { db.evaluationDao().getByTask(taskId)?.teacherReadAt }

        // ---------- 10. 选 1 条问题为高优先级，确认改进计划（采纳 1 条建议、新增 1 条措施） ----------
        clickAny(hasText("确定改进重点") and hasClickActionMatcher())
        waitTag(FOCUS)
        val issue = issues.first()
        clickIn(FOCUS, hasTestTag("focus-select-${issue.id}"))
        rule.onAllNodesWithText("高优先级")[0].performClick()
        clickIn(FOCUS, hasText("保存重点并制定计划"))
        waitTag(PLAN)
        clickIn(PLAN, hasText("采纳原建议"))
        chooseDate()
        clickIn(PLAN, hasText("新增措施"))
        scrollTo(PLAN, newMeasureField)
        rule.onNode(newMeasureField).performTextReplacement(ADDED_MEASURE)
        closeSoftKeyboard()
        chooseDate()
        // 草稿自动保存后再确认
        awaitDb {
            planMeasures(taskId).takeIf { list ->
                list.size == 2 && list.all { it.dueDate.isNotBlank() } && list.any { it.content == ADDED_MEASURE }
            }
        }
        clickIn(PLAN, hasText("确认计划"))
        clickDialog("确认并生成待办")
        val plan = awaitDb { db.improvementDao().getPlanByTask(taskId)?.takeIf { it.status == PlanStatus.CONFIRMED } }
        scrollTo(PLAN, hasText("计划已确认，内容和日期已锁定"))
        val item = runBlocking { db.improvementDao().getItems(plan.id) }.single()
        assertEquals(issue.id, item.issueId)
        assertEquals(Priority.HIGH, item.priority)
        val measures = planMeasures(taskId)
        assertEquals(setOf(MeasureSource.ADOPTED, MeasureSource.ADDED), measures.map { it.source }.toSet())
        assertEquals(SUGGESTION, measures.single { it.source == MeasureSource.ADOPTED }.content)

        // ---------- 11. 改进待办中把 1 条措施改为“进行中”，可追溯到原问题 ----------
        clickIn(PLAN, hasText("查看改进待办"))
        waitTag(TODO)
        val added = measures.single { it.source == MeasureSource.ADDED }
        clickIn(TODO, hasTestTag("todo-${added.id}-IN_PROGRESS"))
        awaitDb { planMeasures(taskId).single { it.id == added.id }.takeIf { it.status == MeasureStatus.IN_PROGRESS } }
        scrollTo(TODO, hasText("计划完成：${added.dueDate} · ${MeasureStatus.IN_PROGRESS.label}"))
        scrollTo(TODO, hasText("原问题：$ISSUE_1"))
        assertEquals(
            MeasureStatus.NOT_STARTED,
            planMeasures(taskId).single { it.source == MeasureSource.ADOPTED }.status,
        )
    }

    // ---------------- 页面操作 ----------------

    private fun launchApp() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    private fun waitText(text: String, substring: Boolean = false) = rule.waitUntil(TIMEOUT) {
        rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
    }

    private fun waitTag(tag: String) = rule.waitUntil(TIMEOUT) {
        rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    }

    private fun login(account: String) {
        waitText("登录")
        rule.onNode(hasSetTextAction() and hasText("账号")).performTextReplacement(account)
        rule.onNode(hasSetTextAction() and hasText("密码")).performTextReplacement(DemoData.DEFAULT_PASSWORD)
        closeSoftKeyboard()
        rule.onNode(hasText("登录") and !hasSetTextAction()).performClick()
        rule.waitUntil(TIMEOUT) { Session.currentUser?.account == account }
    }

    /** 从角色首页进入“我的”退出，再登录另一个账号。 */
    private fun relogin(account: String) {
        rule.onNodeWithText("我的").performClick()
        waitText("退出登录")
        rule.onNodeWithText("退出登录").performClick()
        clickDialog("退出")
        login(account)
    }

    private fun openHomeEntry(title: String) {
        waitText(title)
        rule.onNodeWithText(title).performClick()
    }

    /** 按返回键回到角色首页；homeHint 是首页卡片上的说明文字，其他页面不会出现。 */
    private fun goHome(homeHint: String) {
        repeat(8) {
            rule.waitForIdle()
            if (rule.onAllNodesWithText(homeHint).fetchSemanticsNodes().isNotEmpty()) return
            androidx.test.espresso.Espresso.pressBackUnconditionally()
        }
        waitText(homeHint)
    }

    /** 在带 tag 的列表里滚到目标再点击（LazyColumn 不组合屏幕外的项）。 */
    private fun clickIn(listTag: String, matcher: SemanticsMatcher) {
        scrollTo(listTag, matcher)
        rule.onNode(matcher).performClick()
    }

    private fun scrollTo(listTag: String, matcher: SemanticsMatcher) {
        rule.waitUntil(TIMEOUT) {
            runCatching { rule.onNodeWithTag(listTag).performScrollToNode(matcher) }.isSuccess
        }
    }

    private fun fill(listTag: String, label: String, value: String) {
        val matcher = hasSetTextAction() and hasText(label)
        scrollTo(listTag, matcher)
        rule.onNode(matcher).performTextReplacement(value)
        closeSoftKeyboard()
    }

    /** 页面没有 tag 时，逐个可滚动容器尝试滚到目标。 */
    private fun scrollAny(matcher: SemanticsMatcher) {
        rule.waitUntil(TIMEOUT) {
            rule.onAllNodes(hasScrollAction()).fetchSemanticsNodes().indices.forEach { i ->
                runCatching { rule.onAllNodes(hasScrollAction())[i].performScrollToNode(matcher) }
            }
            rule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
        }
        runCatching { rule.onAllNodes(matcher)[0].performScrollTo() }
    }

    private fun clickAny(matcher: SemanticsMatcher) {
        scrollAny(matcher)
        rule.onAllNodes(matcher)[0].performClick()
    }

    private fun clickDialog(text: String) {
        val matcher = hasText(text) and hasAnyAncestor(isDialog())
        rule.waitUntil(TIMEOUT) { rule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(matcher).performClick()
    }

    /** US13：记录 1 次参与状态（抬头听讲，实到 40 人、参与 35 人）。 */
    private fun recordEngagement() {
        clickIn(CAPTURE, hasText("记录参与状态") and hasClickActionMatcher())
        waitText("状态类型")
        rule.onNode(hasSetTextAction() and hasText("实到人数（选填）")).performTextReplacement("40")
        rule.onNode(hasSetTextAction() and hasText("参与人数（选填）")).performTextReplacement("35")
        rule.onNode(hasSetTextAction() and hasText("文字描述（选填）")).performTextReplacement("前排抬头听讲，后排有低头")
        closeSoftKeyboard()
        clickDialog("保存")
        rule.waitUntil(TIMEOUT) { rule.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
    }

    /** 点“拍照”，相机替身写入照片；类型对话框按默认 PPT 保存。 */
    private fun takePhoto(taskId: Long, expected: Int) {
        clickIn(CAPTURE, hasText("拍照"))
        waitText("照片已保存，选择材料类型")
        clickDialog("保存")
        rule.waitUntil(TIMEOUT) { rule.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
        awaitDb { materials(taskId).filter { it.type != MaterialType.AUDIO }.takeIf { it.size == expected } }
    }

    private fun mark(taskId: Long, type: InteractionType, times: Int) {
        val before = events(taskId).count { it.type == type }
        repeat(times) { n ->
            clickIn(CAPTURE, hasContentDescription("标注${type.label}，已标注", substring = true))
            awaitDb { events(taskId).takeIf { list -> list.count { it.type == type } == before + n + 1 } }
            Unit
        }
    }

    /** 与 Sprint1UiTest 相同：Material 3 日期格子的语义文本是完整日期。 */
    private fun chooseDate() {
        clickIn(PLAN, hasText("计划完成日期：请选择"))
        rule.onNode(hasText("15日", substring = true) and hasClickActionMatcher(), useUnmergedTree = true).performClick()
        rule.onNodeWithText("确定日期").performClick()
        rule.waitUntil(TIMEOUT) { rule.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
    }

    /** 新增措施的 id 为负数（未入库前），tag 形如 measure-content--1。 */
    private val newMeasureField = SemanticsMatcher("新增措施输入框") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("measure-content--") == true
    }

    private fun hasClickActionMatcher() = androidx.compose.ui.test.hasClickAction()

    // ---------------- 数据读取与清理 ----------------

    private fun userId(account: String) = runBlocking { db.userDao().getByAccount(account)!!.id }

    private fun tasks() = runBlocking {
        db.taskDao().observeRowsFiltered(courseId, null, null).first().map { db.taskDao().getById(it.id)!! }
    }

    private fun materials(taskId: Long) = runBlocking { db.captureDao().getMaterials(taskId) }
    private fun events(taskId: Long) = runBlocking { db.captureDao().observeEvents(taskId).first() }
    private fun engagements(taskId: Long) = runBlocking { db.captureDao().observeEngagements(taskId).first() }

    private fun planMeasures(taskId: Long) = runBlocking {
        val plan = db.improvementDao().getPlanByTask(taskId) ?: return@runBlocking emptyList()
        db.improvementDao().getItems(plan.id).flatMap { db.improvementDao().getMeasuresByItem(it.id) }
    }

    private fun arrangementStatus(id: Long): ArrangementStatus =
        sql.query("SELECT status FROM arrangements WHERE id = ?", arrayOf(id))
            .use { it.moveToFirst(); ArrangementStatus.valueOf(it.getString(0)) }

    /** 轮询数据库直到 block 返回非空，界面写库是异步的。 */
    private fun <T : Any> awaitDb(block: suspend () -> T?): T {
        var value: T? = null
        rule.waitUntil(TIMEOUT) {
            value = runBlocking { block() }
            value != null
        }
        return value!!
    }

    /** 删除课程 A 及其下全部任务、材料文件、评价、改进计划和安排。上次中途失败的残留也一并清掉。 */
    private fun removeLeftovers() {
        val ids = sql.query("SELECT id FROM courses WHERE code = ?", arrayOf(COURSE_CODE))
            .use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }
        for (course in ids) {
            val taskIds = sql.query("SELECT id FROM supervision_tasks WHERE courseId = ?", arrayOf(course))
                .use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }
            for (task in taskIds) {
                File(context.filesDir, "tasks/$task").deleteRecursively()
                sql.execSQL("DELETE FROM improvement_plans WHERE taskId = ?", arrayOf(task))
                sql.execSQL("DELETE FROM evaluations WHERE taskId = ?", arrayOf(task))
                sql.execSQL("DELETE FROM supervision_tasks WHERE id = ?", arrayOf(task))
            }
            sql.execSQL("DELETE FROM arrangements WHERE courseId = ?", arrayOf(course))
            sql.execSQL("DELETE FROM courses WHERE id = ?", arrayOf(course))
        }
    }

    private companion object {
        const val TIMEOUT = 15_000L
        const val COURSE_CODE = "E2E-A"
        const val COURSE_NAME = "端到端演示课程A"
        // 各角色首页卡片上的说明文字，用来判断是否回到了首页
        const val ADMIN_HOME = "查看本学期未督导课程，发布督导安排"
        const val SUPERVISOR_HOME = "继续未完成的采集或评价"
        const val TEACHER_HOME = "查看本人课程的督导评价，制定改进计划"
        const val CAPTURE = "capture-page"
        const val EVALUATION = "evaluation-list"
        const val FOCUS = "focus-list"
        const val PLAN = "plan-list"
        const val TODO = "todo-list"
        const val ISSUE_1 = "提问以封闭式问题为主"
        const val ISSUE_2 = "小组讨论时后排参与不足"
        const val SUGGESTION = "每节课设计开放式提问"
        const val OPINION = "整体良好，建议加强课堂互动"
        const val ADDED_MEASURE = "增加一次小组讨论"
    }
}
