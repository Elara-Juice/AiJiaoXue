package edu.neu.aijiaoxue

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.entity.Material
import edu.neu.aijiaoxue.data.model.*
import edu.neu.aijiaoxue.ui.evaluation.*
import edu.neu.aijiaoxue.ui.improve.*
import edu.neu.aijiaoxue.ui.theme.AiJiaoXueTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import android.os.ParcelFileDescriptor
import java.io.File

@RunWith(AndroidJUnit4::class)
class Sprint1UiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var db: AppDatabase
    private lateinit var f: Sprint1Fixture
    private val stores = mutableListOf<ViewModelStore>()
    private val mediaFiles = mutableListOf<File>()
    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        f = Sprint1Fixture(db); f.seed()
    }
    @After fun close() {
        rule.runOnIdle { stores.forEach { it.clear() } }
        db.close()
        mediaFiles.forEach { it.delete() }
    }
    private inline fun <reified T : ViewModel> model(crossinline create: () -> T): T {
        val store = ViewModelStore().also { stores += it }
        return ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <VM : ViewModel> create(modelClass: Class<VM>): VM = create() as VM
        })[T::class.java]
    }
    private fun waitText(text: String) = rule.waitUntil(20_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun scroll(list: String, matcher: SemanticsMatcher) { rule.onNodeWithTag(list).performScrollToNode(matcher) }
    private fun click(list: String, text: String) { scroll(list, hasText(text)); rule.onNodeWithText(text).performClick() }
    private fun fill(list: String, label: String, value: String) {
        val matcher = hasSetTextAction() and hasText(label)
        scroll(list, matcher); rule.onNode(matcher).performTextReplacement(value); closeSoftKeyboard()
    }
    private fun screenshot(name: String) {
        rule.waitForIdle()
        val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /sdcard/Download/yzj-$name.png")
        ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
    }
    private fun closePhoto() {
        rule.onNodeWithText("关闭图片").performClick()
        rule.waitForIdle()
        rule.waitUntil(20_000) { rule.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
    }
    private fun awaitPhoto() {
        try {
            rule.waitUntil(20_000) {
                rule.waitForIdle()
                rule.onAllNodesWithContentDescription("课堂材料图片", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (failure: Throwable) {
            screenshot("photo-failure")
            rule.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().indices.forEach {
                android.util.Log.i("YZJ_UI", rule.onAllNodes(isRoot(), useUnmergedTree = true)[it].printToString())
            }
            throw failure
        }
    }

    @Test fun evaluationValidatesCancelsThenSubmitsLatestEditAndLocks() {
        val task = runBlocking { f.task() }
        lateinit var vm: EvaluationViewModel
        rule.setContent { AiJiaoXueTheme {
            vm = remember { model { EvaluationViewModel(f.evaluations, f.supervisor, task) } }
            EvaluationScreen(vm, {}, {})
        } }
        rule.waitUntil(20_000) { !vm.uiState.value.loading }
        click("evaluation-list", "提交评价")
        rule.waitUntil { vm.uiState.value.invalidIndex == 3 }
        val scores = listOf(5, 4, 3, 4)
        EvalDimension.entries.forEachIndexed { index, dimension ->
            val tag = "score-${dimension.name}-${scores[index]}"
            scroll("evaluation-list", hasTestTag(tag)); rule.onNodeWithTag(tag).performClick()
        }
        click("evaluation-list", "添加问题")
        fill("evaluation-list", "问题 1", "增加课堂互动")
        fill("evaluation-list", "总体意见（必填）", "最后一次编辑必须被提交")
        click("evaluation-list", "提交评价")
        rule.onNodeWithText("继续编辑").performClick()
        assertEquals(TaskStatus.PENDING_EVALUATION, runBlocking { db.taskDao().getById(task)!!.status })
        click("evaluation-list", "提交评价")
        rule.onNodeWithText("确认提交").performClick()
        rule.waitUntil(20_000) { vm.uiState.value.readOnly }
        scroll("evaluation-list", hasText("评价已提交，内容已锁定"))
        rule.onNodeWithText("评价已提交，内容已锁定").assertIsDisplayed()
        screenshot("evaluation-submitted")
        val saved = runBlocking { f.evaluations.load(f.supervisor, task) }
        assertEquals(81, saved.draft.evaluation.totalScore)
        assertEquals("最后一次编辑必须被提交", saved.draft.evaluation.overallOpinion)
        scroll("evaluation-list", hasTestTag("score-CONTENT-5"))
        rule.onNodeWithTag("score-CONTENT-5").assertIsNotEnabled()
    }

    @Test fun fastTypingSurvivesViewModelDestructionAndImmediateReopen() {
        val task = runBlocking { f.task() }
        lateinit var vm: EvaluationViewModel
        var generation by mutableIntStateOf(0)
        rule.setContent { AiJiaoXueTheme { key(generation) {
            vm = remember { model { EvaluationViewModel(f.evaluations, f.supervisor, task) } }
            EvaluationScreen(vm, {}, {})
        } } }
        rule.waitUntil(20_000) { !vm.uiState.value.loading }
        rule.runOnIdle {
            repeat(25) { index -> vm.opinion { it.copy(overallOpinion = "连续输入 $index") } }
            stores.last().clear()
            generation++
        }
        rule.waitUntil(20_000) { !vm.uiState.value.loading && vm.uiState.value.draft.evaluation.overallOpinion == "连续输入 24" }
        assertEquals("连续输入 24", runBlocking { f.evaluations.load(f.supervisor, task).draft.evaluation.overallOpinion })
    }

    @Test fun focusPlanAndTodoFlowUsesDatePickerTracksSourcesAndNavigates() {
        val task = runBlocking { f.submitted() }
        val issue = runBlocking { f.improvement.load(f.teacher, task).issues.first() }
        var page by mutableStateOf("focus")
        lateinit var focus: ImproveFocusViewModel
        lateinit var plan: ImprovePlanViewModel
        lateinit var todo: ImproveTodoViewModel
        var tracedPlan = 0L; var tracedFeedback = 0L
        rule.setContent { AiJiaoXueTheme {
            when (page) {
                "focus" -> {
                    focus = remember { model { ImproveFocusViewModel(f.improvement, f.teacher, task) } }
                    ImproveFocusScreen(focus, {}, { page = "plan" })
                }
                "plan" -> {
                    plan = remember { model { ImprovePlanViewModel(f.improvement, f.teacher, task) } }
                    ImprovePlanScreen(plan, {}, { page = "focus" }, { page = "todo" })
                }
                else -> {
                    todo = remember { model { ImproveTodoViewModel(f.improvement, f.teacher) } }
                    ImproveTodoScreen(todo, {}, { tracedPlan = it }, { tracedFeedback = it })
                }
            }
        } }
        rule.waitUntil(20_000) { !focus.uiState.value.loading }
        click("focus-list", "保存重点并制定计划")
        rule.waitUntil { focus.uiState.value.error == "请至少选择一项改进重点" }
        scroll("focus-list", hasTestTag("focus-select-${issue.id}"))
        rule.onNodeWithTag("focus-select-${issue.id}").performClick()
        rule.onAllNodesWithText("高优先级")[0].performClick()
        click("focus-list", "保存重点并制定计划")
        waitText("个人改进计划")
        rule.waitUntil(20_000) { !plan.uiState.value.loading }
        click("plan-list", "确认计划")
        rule.waitUntil { plan.uiState.value.error != null }
        fill("plan-list", "措施内容（必填）", "调整后的建议")
        click("plan-list", "采纳原建议")
        chooseDate()
        click("plan-list", "新增措施")
        val added = plan.uiState.value.measures.first { it.id < 0 }.id
        scroll("plan-list", hasTestTag("measure-content-$added"))
        rule.onNodeWithTag("measure-content-$added").performTextReplacement("教师新增课堂练习")
        closeSoftKeyboard(); chooseDate()
        rule.waitUntil(20_000) { plan.uiState.value.saved }
        screenshot("plan-draft")
        click("plan-list", "确认计划")
        rule.onNodeWithText("确认并生成待办").performClick()
        rule.waitUntil(20_000) { plan.uiState.value.readOnly }
        click("plan-list", "查看改进待办")
        waitText("改进待办")
        rule.waitUntil(20_000) { !todo.uiState.value.loading && todo.uiState.value.rows.size == 2 }
        val row = todo.uiState.value.rows.first()
        val tag = "todo-${row.measureId}-IN_PROGRESS"
        scroll("todo-list", hasTestTag(tag)); rule.onNodeWithTag(tag).performClick()
        rule.waitUntil(20_000) { todo.uiState.value.rows.first { it.measureId == row.measureId }.status == MeasureStatus.IN_PROGRESS }
        screenshot("todo")
        scroll("todo-list", hasText("查看原计划")); rule.onAllNodesWithText("查看原计划")[0].performClick()
        rule.onAllNodesWithText("查看督导反馈")[0].performClick()
        assertEquals(task, tracedPlan); assertEquals(task, tracedFeedback)
        val saved = runBlocking { f.improvement.load(f.teacher, task) }
        assertEquals(setOf(MeasureSource.ADOPTED, MeasureSource.ADDED), saved.measures.map { it.source }.toSet())
        assertEquals(Priority.HIGH, saved.items.single().priority)
    }
    private fun chooseDate() {
        scroll("plan-list", hasText("计划完成日期：请选择"))
        rule.onNodeWithText("计划完成日期：请选择").performClick()
        rule.waitForIdle()
        screenshot("date-picker")
        // Material 3 的日期按钮语义文本是完整日期，视觉上的“15”不是独立文本节点。
        rule.onNode(hasText("15日", substring = true) and hasClickAction(), useUnmergedTree = true).performClick()
        rule.onNodeWithText("确定日期").performClick()
    }

    @Test fun focusOverThreeWarnsWithoutBlockingAndRemovalCanBeCancelled() {
        val task = runBlocking { f.submitted() }
        lateinit var vm: ImproveFocusViewModel
        var navigated = false
        rule.setContent { AiJiaoXueTheme {
            vm = remember { model { ImproveFocusViewModel(f.improvement, f.teacher, task) } }
            ImproveFocusScreen(vm, {}, { navigated = true })
        } }
        rule.waitUntil(20_000) { !vm.uiState.value.loading }
        rule.runOnIdle {
            repeat(4) { index ->
                vm.addCustom(); val id = vm.uiState.value.choices.last().item.id
                vm.change(id) { it.copy(item = it.item.copy(description = "教师自选重点 $index")) }
            }
        }
        scroll("focus-list", hasText("已超过三项，建议聚焦重点；仍可保存。"))
        rule.onNodeWithText("已超过三项，建议聚焦重点；仍可保存。").assertIsDisplayed()
        screenshot("focus")
        click("focus-list", "保存重点并制定计划")
        rule.waitUntil(20_000) { navigated }
        rule.runOnIdle { vm.reload() }
        rule.waitUntil(20_000) { !vm.uiState.value.loading }
        rule.runOnIdle { vm.change(vm.uiState.value.choices.last().item.id) { it.copy(selected = false) } }
        click("focus-list", "保存重点并制定计划")
        rule.onNodeWithText("继续调整").performClick()
        assertEquals(4, runBlocking { f.improvement.load(f.teacher, task).items.size })
    }

    @Test fun historySearchShowsEmptyStateAndMissingMaterialsAreReadable() {
        val task = runBlocking { f.submitted() }
        runBlocking { db.captureDao().insertMaterial(Material(taskId = task, type = MaterialType.PPT,
            filePath = "tasks/$task/photo/not-there.jpg", capturedAt = 0, offsetMs = 0)) }
        var detailId by mutableLongStateOf(0)
        lateinit var history: HistoryViewModel
        rule.setContent { AiJiaoXueTheme {
            if (detailId == 0L) {
                history = remember { model { HistoryViewModel(f.evaluations, f.supervisor) } }
                HistoryScreen(history, {}, { detailId = it })
            } else {
                val detail = remember { model { HistoryDetailViewModel(f.evaluations, f.supervisor, detailId) } }
                HistoryDetailScreen(detail, {})
            }
        } }
        waitText("测试数据结构")
        rule.onNode(hasSetTextAction() and hasText("课程名称或教师姓名")).performTextReplacement("不存在的课程")
        closeSoftKeyboard(); rule.onNodeWithText("检索").performClick(); waitText("没有符合条件的历史记录")
        rule.onNodeWithText("重置条件").performClick(); waitText("测试数据结构")
        rule.onNodeWithText("测试数据结构").performClick()
        waitText("历史详情 · 只读"); waitText("81 分 · 良好 · 已归档")
        screenshot("history-detail")
        rule.onNodeWithText("展开课堂记录").performScrollTo().performClick()
        rule.onNodeWithText("文件缺失或不可访问").performScrollTo().assertIsDisplayed()
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }

    @Test fun classroomPhotoAndAacAudioOpenAndCorruptPhotoReportsError() {
        val task = runBlocking { f.submitted() }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking { mediaFiles += Sprint1MediaFixture.create(context, f, task) }
        val record = runBlocking { f.evaluations.load(f.supervisor, task, history = true) }
        rule.setContent { AiJiaoXueTheme {
            androidx.compose.foundation.lazy.LazyColumn {
                item { ClassroomReference(record, initiallyExpanded = true) }
            }
        } }
        rule.onNodeWithText("查看图片 1").performClick()
        rule.waitForIdle()
        awaitPhoto()
        screenshot("classroom-photo")
        closePhoto()
        rule.onNodeWithText("播放音频 2").performScrollTo().performClick()
        waitText("停止播放"); screenshot("classroom-audio")
        rule.onNodeWithText("停止播放").performClick()
        rule.onNodeWithText("查看图片 3").performScrollTo().performClick()
        rule.waitForIdle()
        awaitPhoto()
        screenshot("classroom-blackboard")
        closePhoto()
        mediaFiles.first().writeText("invalid image fixture")
        rule.onNodeWithText("查看图片 1").performScrollTo().performClick()
        rule.waitForIdle()
        screenshot("corrupt-photo")
        rule.waitUntil(20_000) { rule.onAllNodesWithText("图片无法显示：文件缺失或损坏", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        closePhoto()
        assertNull(classroomFile(context, task, "../outside.jpg"))
    }

}
