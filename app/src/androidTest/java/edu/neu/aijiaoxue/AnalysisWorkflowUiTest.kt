package edu.neu.aijiaoxue

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.ai.CloudAnalysisPipeline
import edu.neu.aijiaoxue.data.entity.InteractionEvent
import edu.neu.aijiaoxue.data.model.*
import edu.neu.aijiaoxue.ui.analysis.*
import edu.neu.aijiaoxue.ui.theme.AiJiaoXueTheme
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

/** Real Compose screen backed by isolated test data. No cloud inference and no demo DB reset. */
@RunWith(AndroidJUnit4::class)
class AnalysisWorkflowUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: AppDatabase
    private val store = ViewModelStore()
    @After fun close() { rule.runOnIdle { store.clear() }; db.close() }

    @Test fun preparationProgressAndResultsKeepEvaluationReachable() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val f = Sprint1Fixture(db)
        val task = runBlocking {
            f.seed(); val id = f.task()
            db.captureDao().insertEvent(InteractionEvent(taskId = id, type = InteractionType.TEACHER_QUESTION, occurredAt = 10000, offsetMs = 1000))
            CloudAnalysisPipeline(db, context).start(f.supervisor, id, AnalysisOperation.INTERACTION_STATS)
            id
        }
        val vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = CloudAnalysisViewModel(db, context, f.supervisor, task) as T
        })[CloudAnalysisViewModel::class.java]
        var evaluated = false
        rule.setContent { AiJiaoXueTheme { CloudAnalysisScreen(vm, {}, {}, { evaluated = true }, {}, {}) } }
        rule.waitUntil(20000) { !vm.uiState.value.loading }
        rule.onNodeWithTag("analysis-start-recommended").assertIsDisplayed().assertIsNotEnabled()
        screenshot("analysis-preparation")
        rule.onNodeWithTag("analysis-section-PROGRESS").performClick()
        rule.onNodeWithTag("analysis-enter-evaluation").assertIsDisplayed()
        screenshot("analysis-progress")
        rule.onNodeWithTag("analysis-section-RESULTS").performClick()
        rule.onNodeWithText("分析结果与依据").assertIsDisplayed()
        rule.onNodeWithText("本地规则统计", substring = true).assertExists()
        screenshot("analysis-results")
        rule.onNodeWithTag("analysis-enter-evaluation").performClick()
        rule.runOnIdle { assertTrue(evaluated) }
    }

    private fun screenshot(name: String) {
        rule.waitForIdle()
        File(context.cacheDir, "$name.png").outputStream().use {
            rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
