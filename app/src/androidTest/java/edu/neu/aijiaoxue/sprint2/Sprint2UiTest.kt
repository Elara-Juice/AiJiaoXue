package edu.neu.aijiaoxue.sprint2

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import edu.neu.aijiaoxue.Sprint1Fixture
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.entity.*
import edu.neu.aijiaoxue.data.model.*
import edu.neu.aijiaoxue.data.repository.AiCorrectionRepository
import edu.neu.aijiaoxue.ui.evaluation.*
import edu.neu.aijiaoxue.ui.improve.*
import edu.neu.aijiaoxue.ui.theme.AiJiaoXueTheme
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the production Compose screens and repositories; analysis text is a fixed test fixture. */
@RunWith(AndroidJUnit4::class)
class Sprint2UiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var db: AppDatabase
    private lateinit var f: Sprint1Fixture
    private val stores = mutableListOf<ViewModelStore>()
    private val originalAnalysis = "原始课堂分析：互动机会较少"
    private val reviewedAnalysis = "参与式学习缺少讨论，需要安排学生解释思路。"

    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        f = Sprint1Fixture(db)
        f.seed()
    }

    @After fun close() {
        rule.runOnIdle { stores.forEach { it.clear() } }
        db.close()
    }

    private inline fun <reified T : ViewModel> model(crossinline create: () -> T): T {
        val store = ViewModelStore().also { stores += it }
        return ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <VM : ViewModel> create(modelClass: Class<VM>): VM = create() as VM
        })[T::class.java]
    }

    private suspend fun reviewedTask(submit: Boolean): Pair<Long, Long> {
        val task = f.task()
        val analysis = db.aiAnalysisDao().insertAnalysis(AiAnalysisResult(
            taskId = task, type = AiAnalysisType.INTERACTION, content = originalAnalysis, confidence = 0.8f,
        ))
        AiCorrectionRepository(db).saveCorrection(analysis, f.supervisor, reviewedAnalysis)
        if (submit) {
            f.evaluations.save(f.supervisor, task, f.draft(task), submit = true)
            f.focus(task)
        }
        return task to analysis
    }

    private fun scroll(list: String, matcher: SemanticsMatcher) {
        rule.onNodeWithTag(list).performScrollToNode(matcher)
    }

    private fun click(list: String, text: String) {
        scroll(list, hasText(text))
        rule.onNodeWithText(text).performClick()
    }

    private fun clickTag(list: String, tag: String) {
        scroll(list, hasTestTag(tag))
        rule.onNodeWithTag(tag).performClick()
    }

    private fun chooseDate() {
        click("plan-list", "计划完成日期：请选择")
        rule.onNode(hasText("15日", substring = true) and hasClickAction(), useUnmergedTree = true).performClick()
        rule.onNodeWithText("确定日期").performClick()
    }

    @Test fun reviewedReferenceDoesNotScoreAndRefreshPreservesManualDraft() {
        val (task, analysis) = runBlocking { reviewedTask(submit = false) }
        lateinit var vm: EvaluationViewModel
        var analysisOpened = false
        rule.setContent { AiJiaoXueTheme {
            vm = remember { model { EvaluationViewModel(f.evaluations, f.supervisor, task) } }
            EvaluationScreen(vm, {}, {}, onAnalysis = { analysisOpened = true })
        } }
        rule.waitUntil(20_000) { !vm.uiState.value.loading && vm.uiState.value.record?.reviewedAnalyses?.size == 1 }
        scroll("evaluation-list", hasText("审核后有效内容：$reviewedAnalysis"))
        rule.onNodeWithText("审核后有效内容：$reviewedAnalysis").assertIsDisplayed()
        assertTrue(EvalDimension.entries.all { vm.uiState.value.draft.evaluation.scoreOf(it) == null })
        assertTrue(vm.uiState.value.draft.issues.isEmpty())
        clickTag("evaluation-list", "evaluation-open-analysis")
        assertTrue(analysisOpened)

        clickTag("evaluation-list", "score-CONTENT-5")
        val opinion = hasSetTextAction() and hasText("总体意见（必填）")
        scroll("evaluation-list", opinion)
        rule.onNode(opinion).performTextReplacement("我的人工判断应在刷新参考后保留")
        closeSoftKeyboard()
        click("evaluation-list", "提交评价")
        rule.waitUntil { vm.uiState.value.invalidIndex == 4 }
        val validationAttempt = vm.uiState.value.validationAttempt
        val changedReference = "新审核内容：先安排独立思考，再组织同伴讨论。"
        runBlocking { AiCorrectionRepository(db).saveCorrection(analysis, f.supervisor, changedReference) }
        rule.runOnIdle { vm.refreshReviewedReference() }
        rule.waitUntil(20_000) { vm.uiState.value.record?.reviewedAnalyses?.singleOrNull()?.effectiveContent == changedReference }
        assertEquals(5, vm.uiState.value.draft.evaluation.contentScore)
        assertNull(vm.uiState.value.draft.evaluation.designScore)
        assertEquals("我的人工判断应在刷新参考后保留", vm.uiState.value.draft.evaluation.overallOpinion)
        assertEquals(validationAttempt, vm.uiState.value.validationAttempt)
        assertEquals(4, vm.uiState.value.invalidIndex)
        rule.waitUntil(20_000) { vm.uiState.value.saved }
    }

    @Test fun suggestionCanBeDismissedRestoredEditedConfirmedAndTracedInTodo() {
        val task = runBlocking { reviewedTask(submit = true).first }
        var page by mutableStateOf("plan")
        var planBackCount = 0
        lateinit var plan: ImprovePlanViewModel
        lateinit var todo: ImproveTodoViewModel
        rule.setContent { AiJiaoXueTheme {
            // Keep the same plan VM, as navigation does when the todo page is pushed.
            plan = remember { model { ImprovePlanViewModel(f.improvement, f.teacher, task) } }
            if (page == "plan") {
                ImprovePlanScreen(plan, { planBackCount++ }, {}, { page = "todo" })
            } else {
                todo = remember { model { ImproveTodoViewModel(f.improvement, f.teacher) } }
                ImproveTodoScreen(todo, { page = "plan" }, {}, {})
            }
        } }
        rule.waitUntil(20_000) { !plan.uiState.value.loading && !plan.uiState.value.busy && plan.uiState.value.record?.suggestions?.size == 1 }
        val suggestion = plan.uiState.value.record!!.suggestions.single()
        assertEquals(SuggestionOrigin.RULE_BASED, suggestion.generatorMode)
        clickTag("plan-list", "suggestion-dismiss-${suggestion.id}")
        rule.waitUntil(20_000) { !plan.uiState.value.busy && plan.uiState.value.record!!.suggestions.single().dismissedAt != null }
        assertTrue(plan.uiState.value.measures.isEmpty())
        clickTag("plan-list", "suggestion-restore-${suggestion.id}")
        rule.waitUntil(20_000) { !plan.uiState.value.busy && plan.uiState.value.record!!.suggestions.single().dismissedAt == null }
        clickTag("plan-list", "suggestion-edit-${suggestion.id}")
        val edited = "每组先独立列出两种思路，再轮流解释给同伴。"
        rule.onNodeWithTag("suggestion-edit-content").performTextReplacement(edited)
        closeSoftKeyboard()
        rule.onNodeWithText("采用修改内容").performClick()
        rule.waitUntil(20_000) { !plan.uiState.value.busy && plan.uiState.value.measures.singleOrNull()?.content == edited }
        assertEquals(MeasureSource.MODIFIED, plan.uiState.value.measures.single().source)
        assertEquals(suggestion.content, plan.uiState.value.record!!.suggestions.single().content)
        chooseDate()
        rule.waitUntil(20_000) { plan.uiState.value.saved }
        click("plan-list", "确认计划")
        rule.onNodeWithText("确认并生成待办").performClick()
        rule.waitUntil(20_000) { plan.uiState.value.readOnly }
        val confirmed = plan.uiState.value.measures.single()
        scroll("plan-list", hasText("计划完成日期：${confirmed.dueDate}"))
        rule.onNodeWithText("计划完成日期：${confirmed.dueDate}").assertIsNotEnabled()
        rule.onAllNodesWithTag("suggestion-edit-${suggestion.id}").assertCountEquals(0)
        click("plan-list", "查看改进待办")
        // Navigation updates composition asynchronously; wait for the screen that creates todo.
        rule.waitUntil(20_000) { rule.onAllNodesWithTag("todo-list").fetchSemanticsNodes().isNotEmpty() }
        rule.waitUntil(20_000) { !todo.uiState.value.loading && todo.uiState.value.rows.size == 1 }
        val row = todo.uiState.value.rows.single()
        assertEquals(edited, row.content)
        assertEquals(suggestion.id, row.suggestionId)
        assertEquals(suggestion.content, row.suggestionContent)
        assertEquals(suggestion.reviewVersionKey, row.reviewVersionKey)
        assertEquals(SuggestionOrigin.RULE_BASED, row.suggestionOrigin)
        scroll("todo-list", hasText("原建议快照：${suggestion.content}"))
        rule.onNodeWithText("原建议快照：${suggestion.content}").assertIsDisplayed()
        clickTag("todo-list", "todo-${row.measureId}-IN_PROGRESS")
        rule.waitUntil(20_000) { todo.uiState.value.rows.single().status == MeasureStatus.IN_PROGRESS }
        assertEquals(suggestion.reviewVersionKey, todo.uiState.value.rows.single().reviewVersionKey)
        rule.onNodeWithText("返回").performClick()
        rule.waitUntil { !plan.uiState.value.leaving }
        rule.onNodeWithText("返回").performClick()
        assertEquals(1, planBackCount)
    }

    @Test fun failedSuggestionGenerationStillAllowsManualPlanAndConfirmation() {
        val task = runBlocking { reviewedTask(submit = true).first }
        val repository = ImprovementRepository(db, object : ImprovementSuggestionGenerator {
            override suspend fun generate(item: ImprovementItem, dimension: EvalDimension?,
                reviews: List<ReviewedAnalysisSnapshot>): List<ImprovementSuggestion> = error("生成服务测试失败")
        })
        lateinit var vm: ImprovePlanViewModel
        rule.setContent { AiJiaoXueTheme {
            vm = remember { model { ImprovePlanViewModel(repository, f.teacher, task) } }
            ImprovePlanScreen(vm, {}, {}, {})
        } }
        rule.waitUntil(20_000) { !vm.uiState.value.loading && !vm.uiState.value.busy && vm.uiState.value.error != null }
        assertNotNull(vm.uiState.value.record)
        assertTrue(vm.uiState.value.error!!.contains("可继续人工新增或编辑措施"))
        click("plan-list", "新增措施")
        val id = vm.uiState.value.measures.single().id
        scroll("plan-list", hasTestTag("measure-content-$id"))
        rule.onNodeWithTag("measure-content-$id").performTextReplacement("人工安排课堂讨论并记录反馈")
        closeSoftKeyboard()
        chooseDate()
        rule.waitUntil(20_000) { vm.uiState.value.saved }
        click("plan-list", "确认计划")
        rule.onNodeWithText("确认并生成待办").performClick()
        rule.waitUntil(20_000) { vm.uiState.value.readOnly }
        val saved = runBlocking { f.improvement.load(f.teacher, task) }
        assertEquals(PlanStatus.CONFIRMED, saved.plan!!.status)
        assertEquals("人工安排课堂讨论并记录反馈", saved.measures.single().content)
        assertEquals(MeasureSource.ADDED, saved.measures.single().source)
        assertNull(saved.measures.single().suggestionId)
    }

    @Test fun leavingDrainsRapidEditsBeforeNewViewModelAdoptsSuggestion() {
        val task = runBlocking { reviewedTask(submit = true).first }
        var generation by mutableIntStateOf(0)
        var navigations = 0
        lateinit var current: ImprovePlanViewModel
        rule.setContent { AiJiaoXueTheme { key(generation) {
            current = remember { model { ImprovePlanViewModel(f.improvement, f.teacher, task) } }
            ImprovePlanScreen(current, onBack = {
                navigations++
                stores.first().clear()
                generation++
            }, onFocus = {}, onTodos = {})
        } } }
        rule.waitUntil(20_000) { !current.uiState.value.loading && !current.uiState.value.busy }
        val previous = current
        val item = previous.uiState.value.record!!.items.single()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val blocker = CoroutineScope(Dispatchers.IO).launch {
            db.withTransaction { entered.complete(Unit); release.await() }
        }
        runBlocking { entered.await() }
        try {
            rule.runOnIdle {
                previous.add(item.id)
                val id = previous.uiState.value.measures.single().id
                repeat(25) { previous.content(id, "快速输入 $it") }
            }
            rule.onNodeWithText("返回").performClick()
            rule.onNodeWithText("返回").performClick()
            assertEquals(0, navigations)
            assertTrue(previous.uiState.value.leaving)
        } finally {
            release.complete(Unit)
            runBlocking { blocker.join() }
        }
        rule.waitUntil(20_000) { current !== previous && !current.uiState.value.loading && !current.uiState.value.busy }
        assertEquals(1, navigations)
        assertEquals("快速输入 24", current.uiState.value.measures.single().content)
        val suggestion = current.uiState.value.record!!.suggestions.single()
        clickTag("plan-list", "suggestion-adopt-${suggestion.id}")
        rule.waitUntil(20_000) { !current.uiState.value.busy && current.uiState.value.measures.size == 2 }
        val persisted = runBlocking { f.improvement.load(f.teacher, task) }
        assertEquals(2, persisted.measures.size)
        assertEquals("快速输入 24", persisted.measures.single { it.suggestionId == null }.content)
        assertEquals(suggestion.id, persisted.measures.single { it.suggestionId != null }.suggestionId)
    }
}
