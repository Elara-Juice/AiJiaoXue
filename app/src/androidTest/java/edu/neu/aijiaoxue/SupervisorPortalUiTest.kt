package edu.neu.aijiaoxue

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.*
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.model.*
import edu.neu.aijiaoxue.data.repository.SupervisorPortalRepository
import edu.neu.aijiaoxue.navigation.*
import edu.neu.aijiaoxue.ui.supervisor.*
import edu.neu.aijiaoxue.ui.theme.AiJiaoXueTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SupervisorPortalUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: AppDatabase
    private lateinit var f: Sprint1Fixture
    private lateinit var portal: SupervisorPortalViewModel
    private val store = ViewModelStore()
    private var task = 0L
    private var issue = 0L

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        f = Sprint1Fixture(db)
        runBlocking {
            f.seed(); task = f.submitted()
            issue = SupervisorPortalRepository(db).issues(f.supervisor).first().first().row.id
        }
        portal = model("portal") { SupervisorPortalViewModel(SupervisorPortalRepository(db), f.supervisor) }
    }
    @After fun close() { rule.runOnIdle { store.clear() }; db.close() }

    private inline fun <reified T : ViewModel> model(key: String, crossinline create: () -> T): T =
        ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <V : ViewModel> create(modelClass: Class<V>): V = create() as V
        })[key, T::class.java]

    @Test fun fourEntriesKeepStateAndTaskWorkspaceReachesItsIssues() {
        rule.setContent { AiJiaoXueTheme {
            val nav = rememberNavController()
            SupervisorNavigationFrame(nav, f.supervisor, db) {
                NavHost(nav, Routes.HOME_SUPERVISOR) {
                    composable(Routes.HOME_SUPERVISOR) { SupervisorPortalScreen(portal, SupervisorTab.WORKBENCH, nav::navigate) }
                    composable(Routes.MY_TASKS) { SupervisorPortalScreen(portal, SupervisorTab.TASKS, nav::navigate) }
                    composable(Routes.SUPERVISOR_TRACKING) { SupervisorPortalScreen(portal, SupervisorTab.TRACKING, nav::navigate) }
                    composable(Routes.SUPERVISOR_ME) { SupervisorProfileScreen(nav::navigate) }
                    composable(Routes.TASK_DETAIL, listOf(navArgument(Routes.ARG_TASK_ID) { type = NavType.LongType })) {
                        SupervisorTaskScreen(model("task") { SupervisorTaskViewModel(SupervisorPortalRepository(db), f.supervisor, task) }, { nav.popBackStack() }, nav::navigate)
                    }
                    composable(Routes.TASK_TRACKING, listOf(navArgument(Routes.ARG_TASK_ID) { type = NavType.LongType })) {
                        SupervisorPortalScreen(portal, SupervisorTab.TRACKING, nav::navigate, task) { nav.popBackStack() }
                    }
                    composable(Routes.ISSUE_DETAIL, listOf(navArgument(Routes.ARG_ISSUE_ID) { type = NavType.LongType })) {
                        SupervisorIssueScreen(model("issue") { SupervisorIssueViewModel(db, f.supervisor, issue) }, { nav.popBackStack() }, nav::navigate)
                    }
                }
            }
        } }
        rule.waitUntil(20000) { !portal.uiState.value.loading }
        rule.onNodeWithTag("nav-WORKBENCH").assertIsSelected()
        screenshot("supervisor-workbench")
        rule.onNodeWithTag("nav-TASKS").performClick()
        rule.onNodeWithTag("portal-search").performTextReplacement("不存在的课程")
        rule.onNodeWithText("没有符合条件的任务").assertIsDisplayed()
        rule.onNodeWithTag("portal-search").performTextClearance()
        rule.onNodeWithTag("nav-PROFILE").performClick()
        rule.onNodeWithText("分析服务设置").assertIsDisplayed()
        rule.onNodeWithTag("nav-TRACKING").performClick()
        rule.onNodeWithTag("tracked-issue-$issue").assertIsDisplayed()
        screenshot("supervisor-tracking")
        rule.onNodeWithTag("nav-TASKS").performClick()
        rule.onNodeWithText("课堂档案").performClick()
        rule.waitUntil(10000) { rule.onAllNodesWithTag("task-tab-IMPROVEMENT").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("nav-TASKS").assertDoesNotExist()
        rule.onNodeWithTag("task-tab-IMPROVEMENT").performScrollTo().performClick()
        rule.onNodeWithTag("tracked-issue-$issue").performClick()
        rule.waitUntil(10000) { rule.onAllNodesWithTag("issue-next").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("创建复评任务").assertIsDisplayed()
        screenshot("supervisor-issue")
    }

    @Test fun followUpFormCreatesBoundTaskAndRejectsAnEarlierDate() {
        val vm = model("followup") { SupervisorIssueViewModel(db, f.supervisor, issue) }
        var created: Long? = null
        rule.setContent { AiJiaoXueTheme { FollowUpCreateScreen(vm, {}, { created = it }) } }
        rule.waitUntil(10000) { !vm.uiState.value.loading }
        rule.onNodeWithTag("followup-date").performTextReplacement("2026-10-06")
        rule.onNodeWithTag("followup-create").performScrollTo().performClick()
        rule.waitUntil(10000) { vm.uiState.value.error != null }
        assertNull(created)
        rule.onNodeWithTag("followup-date").performScrollTo().performTextReplacement("2026-10-20")
        rule.onNodeWithTag("followup-create").performScrollTo().performClick()
        rule.waitUntil(10000) { created != null }
        runBlocking {
            val links = db.reviewLinkDao().getByFollowUpTask(f.supervisor, created!!)
            assertEquals(issue, links.single().originIssueId)
            assertEquals(task, links.single().originTaskId)
        }
    }

    private fun screenshot(name: String) {
        rule.waitForIdle()
        File(context.cacheDir, "$name.png").outputStream().use {
            rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
