package edu.neu.aijiaoxue.sprint3

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import edu.neu.aijiaoxue.Sprint1Fixture
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.model.MeasureSource
import edu.neu.aijiaoxue.data.model.PlanStatus
import edu.neu.aijiaoxue.ui.evaluation.DraftTransactions
import edu.neu.aijiaoxue.ui.growth.TeachingSupportRepository
import edu.neu.aijiaoxue.ui.growth.TeachingSupportScreen
import edu.neu.aijiaoxue.ui.growth.TeachingSupportViewModel
import edu.neu.aijiaoxue.ui.improve.ImprovementRecord
import edu.neu.aijiaoxue.ui.theme.AiJiaoXueTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 真实 Room、Repository、ViewModel 与 Compose 页面；只在测试数据库中构造课堂。 */
@RunWith(AndroidJUnit4::class)
class TeachingSupportUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var db: AppDatabase
    private lateinit var f: Sprint1Fixture
    private lateinit var repository: TeachingSupportRepository
    private val stores = mutableListOf<ViewModelStore>()

    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java,
        ).build()
        f = Sprint1Fixture(db)
        f.seed()
        repository = TeachingSupportRepository(db)
    }

    @After fun close() {
        rule.runOnIdle { stores.forEach { it.clear() } }
        db.close()
    }

    private fun model(): TeachingSupportViewModel {
        val store = ViewModelStore().also { stores += it }
        return ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                TeachingSupportViewModel(repository, f.teacher) as T
        })[TeachingSupportViewModel::class.java]
    }

    private fun select(taskId: Long, vm: TeachingSupportViewModel) {
        rule.waitUntil(20_000) { !vm.state.value.busy && vm.state.value.record != null }
        val row = vm.state.value.record!!.feedbacks.single { it.taskId == taskId }
        rule.onNodeWithText("${row.supervisionDate} ${row.courseName} · 任务${row.taskId}")
            .performScrollTo().performClick()
        rule.waitUntil(20_000) {
            !vm.state.value.busy && vm.state.value.record?.selected?.task?.id == taskId
        }
    }

    @Test fun formalFeedbackHighlightAndEditedStrategySurviveRefreshAndNewViewModel() {
        val (task, unpublished, otherTask) = runBlocking {
            val own = f.submitted(suggestions = null)
            f.focus(own)
            val draft = f.task()
            f.evaluations.save(f.supervisor, draft, f.draft(draft), submit = false)
            val other = f.submitted(courseId = f.otherCourse)
            Triple(own, draft, other)
        }
        var generation by mutableIntStateOf(0)
        var feedbackOpened: Long? = null
        lateinit var vm: TeachingSupportViewModel
        rule.setContent { AiJiaoXueTheme { key(generation) {
            vm = remember { model() }
            TeachingSupportScreen(vm, {}, {}, {}, { feedbackOpened = it })
        } } }
        rule.waitUntil(20_000) { !vm.state.value.busy && vm.state.value.record != null }
        assertEquals(listOf(task), vm.state.value.record!!.feedbacks.map { it.taskId })
        assertFalse(vm.state.value.record!!.feedbacks.any { it.taskId == unpublished || it.taskId == otherTask })
        select(task, vm)

        val practice = "先用学生熟悉的例子说明概念，再让学生讲出判断依据。"
        val practiceField = hasSetTextAction() and hasText("可复用做法说明")
        rule.onNode(practiceField).performScrollTo().performTextReplacement(practice)
        closeSoftKeyboard()
        rule.onNodeWithText("标记并保存亮点").performScrollTo().performClick()
        rule.waitUntil(20_000) {
            !vm.state.value.busy && vm.state.value.record?.highlights?.singleOrNull()?.practice == practice
        }
        assertNull(vm.state.value.error)
        assertEquals("讲解清楚", vm.state.value.record!!.highlights.single().sourceText)
        rule.onNodeWithText("查看正式反馈来源").performScrollTo().performClick()
        assertEquals(task, feedbackOpened)

        val item = vm.state.value.record!!.selected!!.items.single()
        val choice = vm.choices(item.id).first()
        assertTrue(choice.key.startsWith("strategy:"))
        val edited = "每组先独立写出两种解法，再轮流解释；记录尚未参与同学的发言。"
        rule.onAllNodes(hasSetTextAction() and hasText("调整后加入计划")).onFirst()
            .performScrollTo().performTextReplacement(edited)
        closeSoftKeyboard()
        rule.onAllNodesWithText("采用此做法").onFirst().performScrollTo().performClick()
        rule.waitUntil(20_000) { !vm.state.value.busy && vm.state.value.record?.adoptions?.size == 1 }
        assertNull(vm.state.value.error)
        val adopted = vm.state.value.record!!.adoptions.single()
        assertEquals(choice.key, adopted.sourceKey)
        assertEquals(choice.content, adopted.originalContent)
        assertEquals(choice.reference, adopted.sourceReference)
        assertEquals(item.description, adopted.problemSnapshot)
        rule.onNodeWithText("当前措施：$edited").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("候选原文：${choice.content}").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("已加入计划").performScrollTo().assertIsNotEnabled()

        rule.onNodeWithText("刷新").performScrollTo().performClick()
        rule.waitUntil(20_000) { !vm.state.value.busy && vm.state.value.record?.adoptions?.size == 1 }
        val previous = vm
        rule.runOnIdle { generation++ }
        rule.waitUntil(20_000) {
            vm !== previous && !vm.state.value.busy && vm.state.value.record != null
        }
        select(task, vm)
        rule.onNode(practiceField).assertTextContains(practice)
        rule.onNodeWithText("当前措施：$edited").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("正式亮点原文：讲解清楚").performScrollTo().assertIsDisplayed()
        val persisted = runBlocking { repository.load(f.teacher, task) }
        assertEquals(1, persisted.adoptions.size)
        assertEquals(adopted, persisted.adoptions.single())
        val persistedPlan = requireNotNull(persisted.selected)
        val measure = persistedPlan.measures.single { it.id == adopted.measureId }
        assertEquals(edited, measure.content)
        assertEquals(MeasureSource.ADDED, measure.source)
        assertEquals("", measure.dueDate)
        assertEquals(PlanStatus.DRAFT, persistedPlan.plan!!.status)
        assertEquals(practice, persisted.highlights.single().practice)
    }

    @Test fun confirmedPlanDisplaysFrozenSourcesAndDisablesFurtherAdoption() {
        val task = runBlocking {
            val id = f.submitted(suggestions = null)
            f.focus(id)
            val record = repository.load(f.teacher, id)
            val item = record.selected!!.items.single()
            val choice = repository.choices(record, item).first()
            repository.adopt(f.teacher, id, item.id, choice.key, "确认前选择的互动措施")
            val plan = f.improvement.load(f.teacher, id)
            f.improvement.saveMeasures(f.teacher, id,
                plan.measures.map { it.copy(dueDate = "2026-11-01") }, confirm = true)
            id
        }
        lateinit var vm: TeachingSupportViewModel
        rule.setContent { AiJiaoXueTheme {
            vm = remember { model() }
            TeachingSupportScreen(vm, {}, {}, {}, {})
        } }
        select(task, vm)
        val before = runBlocking { repository.load(f.teacher, task) }
        assertEquals(PlanStatus.CONFIRMED, before.selected!!.plan!!.status)
        rule.onNodeWithText("计划已确认，保留原文与来源；本页仅供查阅。")
            .performScrollTo().assertIsDisplayed()
        val inputs = rule.onAllNodesWithText("调整后加入计划")
        assertTrue(inputs.fetchSemanticsNodes().isNotEmpty())
        inputs.fetchSemanticsNodes().indices.forEach { inputs[it].assertIsNotEnabled() }
        val adoptButtons = rule.onAllNodesWithText("采用此做法")
        assertTrue(adoptButtons.fetchSemanticsNodes().isNotEmpty())
        adoptButtons.fetchSemanticsNodes().indices.forEach { adoptButtons[it].assertIsNotEnabled() }
        rule.onNodeWithText("已加入计划").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("当前措施：确认前选择的互动措施").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("候选原文：${before.adoptions.single().originalContent}")
            .performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("刷新").performScrollTo().performClick()
        rule.waitUntil(20_000) { !vm.state.value.busy }
        val after = runBlocking { repository.load(f.teacher, task) }
        assertEquals(before.adoptions, after.adoptions)
        assertEquals(before.selected!!.measures, after.selected!!.measures)
    }

    @Test fun pendingAdoptionBlocksPlanNavigationUntilNewMeasureIsPersisted() {
        val task = runBlocking {
            f.submitted(suggestions = null).also { f.focus(it) }
        }
        var navigationCount = 0
        val openedPlan = CompletableDeferred<ImprovementRecord>()
        lateinit var vm: TeachingSupportViewModel
        rule.setContent { AiJiaoXueTheme {
            vm = remember { model() }
            val scope = rememberCoroutineScope()
            TeachingSupportScreen(vm, {}, {}, onPlan = { taskId ->
                navigationCount++
                scope.launch { openedPlan.complete(f.improvement.load(f.teacher, taskId)) }
            }, onFeedback = {})
        } }
        select(task, vm)
        val item = vm.state.value.record!!.selected!!.items.single()
        val choice = vm.choices(item.id).first()
        assertTrue(vm.state.value.record!!.selected!!.measures.isEmpty())

        // 暂停真实 Repository 的写入；页面仍可绘制并处理导航尝试。
        runBlocking { DraftTransactions.mutex.lock() }
        try {
            rule.onAllNodesWithText("采用此做法").onFirst().performScrollTo().performClick()
            rule.waitUntil(20_000) { vm.state.value.busy }
            rule.onNodeWithText("打开改进计划").performScrollTo().assertIsNotEnabled()
                .performTouchInput { click() }
            rule.runOnIdle { assertEquals(0, navigationCount) }
            assertFalse(openedPlan.isCompleted)
            assertTrue(vm.state.value.record!!.adoptions.isEmpty())
        } finally {
            DraftTransactions.mutex.unlock()
        }
        rule.waitUntil(20_000) { !vm.state.value.busy && vm.state.value.record?.adoptions?.size == 1 }
        assertNull(vm.state.value.error)
        val adoption = vm.state.value.record!!.adoptions.single()
        rule.onNodeWithText("打开改进计划").performScrollTo().assertIsEnabled().performClick()
        rule.waitUntil(20_000) { openedPlan.isCompleted }
        assertEquals(1, navigationCount)
        val loaded = runBlocking { openedPlan.await() }
        assertEquals(task, loaded.task.id)
        assertEquals(choice.content, loaded.measures.single { it.id == adoption.measureId }.content)
        assertEquals(choice.key, adoption.sourceKey)
    }

    @Test fun resumedPageReloadsPlanConfirmedWhileAwayAndDisablesAdoption() {
        val task = runBlocking {
            f.submitted().also { f.focus(it) }
        }
        lateinit var vm: TeachingSupportViewModel
        rule.setContent { AiJiaoXueTheme {
            vm = remember { model() }
            TeachingSupportScreen(vm, {}, {}, {}, {})
        } }
        select(task, vm)
        assertEquals(PlanStatus.DRAFT, vm.state.value.record!!.selected!!.plan!!.status)
        rule.onAllNodesWithText("采用此做法").onFirst().performScrollTo().assertIsEnabled()

        rule.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        try {
            runBlocking {
                val plan = f.improvement.load(f.teacher, task)
                f.improvement.saveMeasures(f.teacher, task,
                    plan.measures.map { it.copy(dueDate = "2026-11-01") }, confirm = true)
            }
            // 离页期间保留的是旧页面快照；恢复事件必须重新读取正式计划状态。
            assertEquals(PlanStatus.DRAFT, vm.state.value.record!!.selected!!.plan!!.status)
        } finally {
            rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        }
        rule.waitUntil(20_000) {
            !vm.state.value.busy && vm.state.value.record?.selected?.plan?.status == PlanStatus.CONFIRMED
        }
        assertNull(vm.state.value.error)
        rule.onNodeWithText("计划已确认，保留原文与来源；本页仅供查阅。")
            .performScrollTo().assertIsDisplayed()
        val adoptButtons = rule.onAllNodesWithText("采用此做法")
        assertTrue(adoptButtons.fetchSemanticsNodes().isNotEmpty())
        adoptButtons.fetchSemanticsNodes().indices.forEach { adoptButtons[it].assertIsNotEnabled() }
        assertTrue(vm.state.value.record!!.adoptions.isEmpty())
    }
}
