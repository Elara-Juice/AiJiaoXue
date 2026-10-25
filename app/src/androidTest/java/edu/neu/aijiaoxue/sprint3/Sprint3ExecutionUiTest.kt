package edu.neu.aijiaoxue.sprint3

import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import edu.neu.aijiaoxue.Sprint1Fixture
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.model.DateFormats
import edu.neu.aijiaoxue.ui.improve.*
import edu.neu.aijiaoxue.ui.theme.AiJiaoXueTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** US43/44：通过正式页面操作和真实 Room 验证保存、原计划只读及草稿保留。 */
@RunWith(AndroidJUnit4::class)
class Sprint3ExecutionUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var db: AppDatabase
    private lateinit var fixture: Sprint1Fixture
    private lateinit var repository: ImprovementExecutionRepository
    private lateinit var vm: ImproveExecutionViewModel
    private val stores = mutableListOf<ViewModelStore>()

    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java).build()
        fixture = Sprint1Fixture(db).also { it.seed() }
        repository = ImprovementExecutionRepository(db)
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

    private suspend fun confirmedTask(twoMeasures: Boolean = false): Long {
        val taskId = fixture.submitted()
        if (twoMeasures) {
            val record = fixture.focus(taskId, count = 2)
            fixture.improvement.saveMeasures(fixture.teacher, taskId,
                record.measures.map { it.copy(dueDate = DateFormats.today()) }, confirm = true)
        } else fixture.confirmed(taskId)
        return taskId
    }

    private fun show(taskId: Long) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        rule.setContent { AiJiaoXueTheme {
            vm = remember { model {
                ImproveExecutionViewModel(repository, ImprovementEvidenceStore(context, repository), fixture.teacher, taskId)
            } }
            ImproveExecutionScreen(vm, {})
        } }
        rule.waitUntil(20_000) { !vm.uiState.value.loading && !vm.uiState.value.busy && vm.uiState.value.context != null }
    }

    private fun scrollTo(matcher: SemanticsMatcher) {
        rule.onNodeWithTag("execution-list").performScrollToNode(matcher)
    }

    private fun replace(tag: String, text: String) {
        scrollTo(hasTestTag(tag))
        rule.onNodeWithTag(tag).performTextReplacement(text)
        closeSoftKeyboard()
    }

    private fun click(tag: String) {
        scrollTo(hasTestTag(tag))
        rule.onNodeWithTag(tag).performClick()
    }

    @Test fun savingActualPracticeDisplaysHistoryWithoutRewritingTheConfirmedPlan() {
        val taskId = runBlocking { confirmedTask() }
        val before = runBlocking { fixture.improvement.load(fixture.teacher, taskId) }
        show(taskId)
        replace("execution-note", "本次先安排两分钟独立思考，再开展讨论")
        replace("execution-adjustment", "由直接提问调整为先思考后同伴互讲")
        replace("execution-date", DateFormats.today())
        click("execution-save")
        rule.waitUntil(20_000) { !vm.uiState.value.busy && vm.uiState.value.context?.executions?.size == 1 }
        scrollTo(hasText("实际调整：由直接提问调整为先思考后同伴互讲"))
        rule.onNodeWithText("实际调整：由直接提问调整为先思考后同伴互讲").assertIsDisplayed()
        val firstDraft = vm.uiState.value.drafts.getValue(before.measures.single().id)
        assertEquals("", firstDraft.implementationNote)
        assertEquals("", firstDraft.actualAdjustment)

        replace("execution-note", "第二次实施时增加每组汇报")
        replace("execution-adjustment", "讨论后让每组解释关键步骤")
        click("execution-save")
        rule.waitUntil(20_000) { !vm.uiState.value.busy && vm.uiState.value.context?.executions?.size == 2 }
        val persisted = runBlocking { repository.load(fixture.teacher, taskId) }
        assertEquals(2, persisted.executions.size)
        assertEquals(before.plan, persisted.record.plan)
        assertEquals(before.items, persisted.record.items)
        assertEquals(before.measures, persisted.record.measures)
        assertEquals(before.suggestions, persisted.record.suggestions)
        assertEquals(before.reviewedAnalyses, persisted.record.reviewedAnalyses)
        assertTrue(persisted.executions.any { it.execution.actualAdjustment == "由直接提问调整为先思考后同伴互讲" })
    }

    @Test fun blankAndInvalidDateValidationKeepTheDraftAndDoNotInsertHistory() {
        val taskId = runBlocking { confirmedTask() }
        show(taskId)
        val measureId = vm.uiState.value.selectedMeasureId!!
        replace("execution-note", "不能因校验失败丢失的实施说明")
        click("execution-save")
        rule.waitUntil { vm.uiState.value.error == "请填写实际调整内容" }
        assertEquals("不能因校验失败丢失的实施说明", vm.uiState.value.drafts.getValue(measureId).implementationNote)
        replace("execution-adjustment", "保留这条尚未保存的具体调整")
        replace("execution-date", "2026-02-30")
        val requestKey = vm.uiState.value.drafts.getValue(measureId).requestKey
        click("execution-save")
        rule.waitUntil { vm.uiState.value.error?.contains("有效的实施日期") == true }
        val draft = vm.uiState.value.drafts.getValue(measureId)
        assertEquals("2026-02-30", draft.implementationDate)
        assertEquals("保留这条尚未保存的具体调整", draft.actualAdjustment)
        assertEquals(requestKey, draft.requestKey)
        assertTrue(runBlocking { repository.load(fixture.teacher, taskId) }.executions.isEmpty())
        scrollTo(hasTestTag("execution-adjustment"))
        rule.onNodeWithTag("execution-adjustment").assertTextContains("保留这条尚未保存的具体调整")
    }

    @Test fun switchingMeasuresAndReloadingPreserveEachUnsavedDraft() {
        val taskId = runBlocking { confirmedTask(twoMeasures = true) }
        show(taskId)
        val measures = vm.uiState.value.context!!.record.measures
        val first = measures[0].id
        val second = measures[1].id
        replace("execution-note", "第一条措施的实施草稿")
        replace("execution-adjustment", "第一条措施的实际调整草稿")
        val requestKey = vm.uiState.value.drafts.getValue(first).requestKey
        click("execution-select-$second")
        replace("execution-note", "第二条措施的独立草稿")
        click("execution-select-$first")
        scrollTo(hasTestTag("execution-note"))
        rule.onNodeWithTag("execution-note").assertTextContains("第一条措施的实施草稿")
        click("execution-refresh")
        rule.waitUntil(20_000) { !vm.uiState.value.loading && !vm.uiState.value.busy }
        assertEquals("第一条措施的实际调整草稿", vm.uiState.value.drafts.getValue(first).actualAdjustment)
        assertEquals("第二条措施的独立草稿", vm.uiState.value.drafts.getValue(second).implementationNote)
        assertEquals(requestKey, vm.uiState.value.drafts.getValue(first).requestKey)
        assertTrue(runBlocking { repository.load(fixture.teacher, taskId) }.executions.isEmpty())
    }

    @Test fun revokedOwnershipOnReloadClearsPreviouslyAuthorizedContentAndPendingDrafts() {
        val taskId = runBlocking { confirmedTask() }
        val executionId = runBlocking {
            val measure = fixture.improvement.load(fixture.teacher, taskId).measures.single()
            repository.append(fixture.teacher, taskId, measure.id,
                ExecutionInput("撤销权限后不能继续展示的实施说明", "撤销权限后不能继续展示的实际做法",
                    System.currentTimeMillis() - 1000, "ui-revoked-ownership"))
        }
        show(taskId)
        rule.runOnIdle {
            vm.setImplementationNote("尚未提交的私有草稿")
            vm.setActualAdjustment("尚未提交的实际调整")
            assertTrue(vm.prepareEvidence(executionId, "尚未导入的私有证据说明"))
        }
        assertNotNull(vm.uiState.value.context)
        assertNotNull(vm.uiState.value.pendingEvidence)
        assertTrue(vm.uiState.value.drafts.isNotEmpty())
        db.openHelper.writableDatabase.execSQL("UPDATE courses SET teacherId = ? WHERE id = ?",
            arrayOf(fixture.otherTeacher, fixture.course))
        click("execution-refresh")
        rule.waitUntil(20_000) { !vm.uiState.value.loading && !vm.uiState.value.busy && vm.uiState.value.error != null }
        val denied = vm.uiState.value
        assertNull(denied.context)
        assertNull(denied.selectedMeasureId)
        assertNull(denied.pendingEvidence)
        assertTrue(denied.drafts.isEmpty())
        rule.onAllNodesWithTag("execution-list").assertCountEquals(0)
        rule.onAllNodesWithText("撤销权限后不能继续展示的实际做法", substring = true).assertCountEquals(0)
        rule.onNodeWithText(denied.error!!).assertIsDisplayed()
    }
}
