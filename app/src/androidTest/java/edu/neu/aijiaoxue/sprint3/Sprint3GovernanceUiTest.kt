package edu.neu.aijiaoxue.sprint3

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import edu.neu.aijiaoxue.Sprint1Fixture
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.entity.Evaluation
import edu.neu.aijiaoxue.data.model.EvalStatus
import edu.neu.aijiaoxue.data.model.TaskStatus
import edu.neu.aijiaoxue.data.repository.GovernanceFilter
import edu.neu.aijiaoxue.data.repository.GovernanceRepository
import edu.neu.aijiaoxue.ui.governance.GovernanceScreen
import edu.neu.aijiaoxue.ui.governance.GovernanceViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Sprint3GovernanceUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var db: AppDatabase
    private lateinit var fixture: Sprint1Fixture
    private var taskId = 0L
    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        fixture = Sprint1Fixture(db)
        fixture.seed()
        taskId = fixture.task(date = "2026-10-20")
        db.evaluationDao().upsert(Evaluation(taskId = taskId, totalScore = 80,
            overallOpinion = "正式意见", status = EvalStatus.SUBMITTED, submittedAt = 1000))
        db.taskDao().updateStatus(taskId, TaskStatus.COMPLETED)
    }
    @After fun close() = db.close()

    @Test fun pendingFiltersAreLabelledAndClassroomSourceCanBeOpened() {
        val model = GovernanceViewModel(GovernanceRepository(db), fixture.admin)
        var openedTask: Long? = null
        compose.setContent { GovernanceScreen(model, {}, { openedTask = it }) }
        compose.waitUntil(10_000) { !model.uiState.value.loading && model.uiState.value.dashboard != null }
        compose.onNodeWithTag("governanceList").performScrollToNode(hasText("仅有 1 次有效督导，至少两次才能形成趋势。"))
        compose.onNodeWithText("仅有 1 次有效督导，至少两次才能形成趋势。").assertIsDisplayed()
        val source = hasText("查看课堂来源", substring = true)
        compose.onNodeWithTag("governanceList").performScrollToNode(source)
        compose.onNode(source).performClick()
        assertEquals(taskId, openedTask)
        compose.runOnIdle { model.setFilter(GovernanceFilter(fromDate = "2026-10-21")) }
        val pending = "筛选条件已修改，请点击应用；当前结果仍按上次已应用的条件显示。"
        compose.onNodeWithTag("governanceList").performScrollToNode(hasText(pending))
        compose.onNodeWithText(pending).assertIsDisplayed()
        compose.onNodeWithTag("governanceList").performScrollToNode(hasText("应用筛选 / 刷新"))
        compose.onNodeWithText("应用筛选 / 刷新").performClick()
        compose.waitUntil(10_000) { !model.uiState.value.loading && model.uiState.value.dashboard?.filter?.fromDate == "2026-10-21" }
        compose.onNodeWithTag("governanceList").performScrollToNode(hasText("当前范围没有已提交的有效督导记录。"))
        compose.onNodeWithText("当前范围没有已提交的有效督导记录。").assertIsDisplayed()
        compose.onNodeWithText(pending).assertDoesNotExist()
    }
}
