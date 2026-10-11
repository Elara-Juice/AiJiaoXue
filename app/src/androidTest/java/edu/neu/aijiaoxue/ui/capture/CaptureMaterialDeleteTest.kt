package edu.neu.aijiaoxue.ui.capture

import android.Manifest
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.DemoData
import edu.neu.aijiaoxue.data.FileStore
import edu.neu.aijiaoxue.data.Session
import edu.neu.aijiaoxue.data.entity.Material
import edu.neu.aijiaoxue.data.entity.SupervisionTask
import edu.neu.aijiaoxue.data.model.MaterialType
import edu.neu.aijiaoxue.data.model.SupervisionType
import edu.neu.aijiaoxue.data.model.TaskStatus
import edu.neu.aijiaoxue.navigation.Routes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * US11 页面操作：取消确认不做任何修改；正在录制的那段、评价提交后的材料都不显示“删除”；
 * 文件缺失的材料可以删掉再重拍。删文件、删记录和状态校验见 [CaptureEngagementTest]。
 */
@RunWith(AndroidJUnit4::class)
class CaptureMaterialDeleteTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = AppDatabase.get(context)
    private var taskId = 0L

    @Before
    fun setUp() = runBlocking {
        InstrumentationRegistry.getInstrumentation().uiAutomation.apply {
            grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        DemoData.seedIfEmpty(db)
        val sup = db.userDao().getByAccount("sup01")!!
        Session.signIn(context, sup)
        val course = db.courseDao().observeRows("").first().first()
        taskId = db.taskDao().create(
            SupervisionTask(
                code = "",
                courseId = course.id,
                supervisorId = sup.id,
                supervisionDate = "2099-01-05",
                period = "1-2",
                type = SupervisionType.REGULAR,
            ),
        )!!
    }

    @After
    fun tearDown() = runBlocking {
        if (AudioRecorder.state.value.status != AudioRecorder.Status.IDLE) AudioRecorder.stop()
        FileStore.taskDir(context, taskId).deleteRecursively()
        db.openHelper.writableDatabase.execSQL("DELETE FROM supervision_tasks WHERE id = ?", arrayOf(taskId))
        Session.signOut(context)
    }

    private fun showCapture() {
        rule.setContent {
            val nav = rememberNavController()
            NavHost(nav, startDestination = Routes.CAPTURE) {
                composable(
                    Routes.CAPTURE,
                    listOf(navArgument(Routes.ARG_TASK_ID) { type = NavType.LongType; defaultValue = taskId }),
                ) { CaptureScreen(onBack = {}, onClassEnded = {}) }
            }
        }
    }

    private fun waitFor(text: String, timeout: Long = 10_000) =
        rule.waitUntil(timeout) { rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    /** 材料列表在互动记录下面，LazyColumn 不会组合屏幕外的项，先滚过去。 */
    private fun scrollPageTo(text: String) {
        rule.onNodeWithTag(CAPTURE_PAGE_TAG).performScrollToNode(hasText(text, substring = true))
    }

    private fun deleteButtonCount(title: String) =
        rule.onAllNodes(hasContentDescription("删除$title")).fetchSemanticsNodes().size

    private fun clickDialog(text: String) {
        val matcher = hasText(text) and hasAnyAncestor(isDialog())
        rule.waitUntil(5_000) { rule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(matcher).performClick()
    }

    /** 课堂已开始后插入一张照片；writeFile = false 模拟文件被清理或丢失。 */
    private suspend fun addPhoto(writeFile: Boolean = true): Material {
        val (file, path) = FileStore.newPhotoFile(context, taskId)
        if (writeFile) file.writeBytes(byteArrayOf(1, 2, 3))
        val start = db.taskDao().getById(taskId)!!.classStartAt ?: System.currentTimeMillis()
        val now = System.currentTimeMillis()
        val id = db.captureDao().insertMaterial(
            Material(taskId = taskId, type = MaterialType.PPT, filePath = path, capturedAt = now, offsetMs = now - start),
        )
        return db.captureDao().getMaterial(id)!!
    }

    /** US11 异常处理：取消确认则不做任何修改。 */
    @Test
    fun cancelDelete_keepsFileAndRecord() = runBlocking<Unit> {
        showCapture()
        waitFor("开始录音")
        val photo = addPhoto()
        scrollPageTo("PPT 1")
        rule.waitUntil(5_000) { deleteButtonCount("PPT 1") == 1 }

        rule.onNode(hasContentDescription("删除PPT 1")).performClick()
        waitFor("不可恢复")
        clickDialog("取消")
        rule.waitUntil(5_000) { rule.onAllNodesWithText("不可恢复", substring = true).fetchSemanticsNodes().isEmpty() }

        assertNotNull(db.captureDao().getMaterial(photo.id))
        assertTrue(FileStore.exists(context, photo.filePath))
        rule.onNodeWithText("PPT 1").assertExists()
    }

    /** 正在录制的那段还没写完，不能删；结束录音后出现“删除”。 */
    @Test
    fun activeRecording_hasNoDeleteButtonUntilStopped() = runBlocking<Unit> {
        showCapture()
        waitFor("开始录音")
        rule.onNodeWithText("开始录音").performClick()
        waitFor("录音中")
        rule.waitUntil(5_000) { runBlocking { db.captureDao().getMaterials(taskId).size == 1 } }
        scrollPageTo("录音 1")
        waitFor("录音 1")
        assertTrue(deleteButtonCount("录音 1") == 0)

        rule.onNodeWithTag(CAPTURE_PAGE_TAG).performScrollToNode(hasText("结束录音"))
        rule.onNodeWithText("结束录音").performClick()
        waitFor("录音已保存")
        scrollPageTo("录音 1")
        rule.waitUntil(5_000) { deleteButtonCount("录音 1") == 1 }
    }

    /** US07 异常处理“允许重新采集”：文件缺失的材料也能删掉。 */
    @Test
    fun missingFileMaterial_canBeDeleted() = runBlocking<Unit> {
        showCapture()
        waitFor("开始录音")
        val photo = addPhoto(writeFile = false)
        scrollPageTo("PPT 1")
        waitFor("文件缺失")

        rule.onNode(hasContentDescription("删除PPT 1")).performClick()
        clickDialog("删除")
        rule.waitUntil(5_000) { runBlocking { db.captureDao().getMaterial(photo.id) == null } }
        waitFor("可重新采集")
        assertFalse(FileStore.exists(context, photo.filePath))
    }

    /** US11 业务规则 2：评价提交（任务“已完成”）后不显示“删除”，材料保留。 */
    @Test
    fun completedTask_showsNoDeleteButton() = runBlocking<Unit> {
        db.taskDao().startClass(taskId, System.currentTimeMillis())
        val photo = addPhoto()
        db.taskDao().endClass(taskId, System.currentTimeMillis())
        db.taskDao().updateStatus(taskId, TaskStatus.COMPLETED)

        showCapture()
        scrollPageTo("PPT 1")
        waitFor("PPT 1")
        assertTrue(deleteButtonCount("PPT 1") == 0)
        assertNotNull(db.captureDao().getMaterial(photo.id))
        assertTrue(FileStore.exists(context, photo.filePath))
        assertNull(rule.onAllNodesWithText("不可恢复", substring = true).fetchSemanticsNodes().firstOrNull())
    }
}
