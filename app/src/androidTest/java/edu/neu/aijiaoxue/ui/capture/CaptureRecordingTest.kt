package edu.neu.aijiaoxue.ui.capture

import android.Manifest
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** US08：开始、暂停、继续、结束录音；音频关联当前任务并记录时长。US07：异常退出的录音片段补写或清理。 */
@RunWith(AndroidJUnit4::class)
class CaptureRecordingTest {
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
        // 用不会和演示数据冲突的日期建一个测试专用任务，测试后删除
        taskId = db.taskDao().create(
            SupervisionTask(
                code = "",
                courseId = course.id,
                supervisorId = sup.id,
                supervisionDate = "2099-01-01",
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
                ) { CaptureScreen(onBack = {}) }
            }
        }
    }

    private fun waitFor(text: String, timeout: Long = 10_000) =
        rule.waitUntil(timeout) { rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun record_pause_resume_stop_savesAudioForTask() = runBlocking {
        showCapture()
        waitFor("开始录音")

        // 规范第 4 节：首次进入采集页即开始课堂计时，状态变为“进行中”
        val started = db.taskDao().getById(taskId)!!
        assertEquals(TaskStatus.IN_PROGRESS, started.status)
        assertNotNull(started.classStartAt)

        rule.onNodeWithText("开始录音").performClick()
        waitFor("录音中")
        // NF-03：开始录音时就已写库
        rule.waitUntil(5_000) { runBlocking { db.captureDao().getMaterials(taskId).size == 1 } }
        Thread.sleep(2_000)

        rule.onNodeWithText("暂停").performClick()
        waitFor("已暂停")
        Thread.sleep(2_000)

        rule.onNodeWithText("继续").performClick()
        waitFor("录音中")
        Thread.sleep(1_500)

        rule.onNodeWithText("结束录音").performClick()
        waitFor("录音已保存")
        waitFor("录音 1")
        rule.onNodeWithText("播放").assertExists()

        val audio = db.captureDao().getMaterials(taskId).single()
        assertEquals(MaterialType.AUDIO, audio.type)
        assertEquals(taskId, audio.taskId)
        assertTrue(audio.filePath.startsWith("tasks/$taskId/audio/AUD_") && audio.filePath.endsWith(".m4a"))
        assertTrue(FileStore.exists(context, audio.filePath))
        assertNotNull(audio.endedAt)
        // 有效时长约 3.5 秒，不含 2 秒暂停
        val duration = audio.durationMs!!
        assertTrue("durationMs=$duration", duration in 2_500..5_000)
        assertTrue(audio.offsetMs >= 0)

        // 一个任务允许多段音频（US08 业务规则 2）
        rule.onNodeWithText("开始录音").performClick()
        waitFor("录音中")
        Thread.sleep(1_500)
        rule.onNodeWithText("结束录音").performClick()
        waitFor("录音 2")
        assertEquals(2, db.captureDao().getMaterials(taskId).size)
        assertEquals(TaskStatus.IN_PROGRESS, db.taskDao().getById(taskId)!!.status)
    }

    @Test
    fun enter_cleansUpUnfinishedAudioWithoutFile() = runBlocking {
        // 模拟录音刚开始就被杀：有记录、endedAt 为空、文件不存在
        db.taskDao().startClass(taskId, System.currentTimeMillis())
        db.captureDao().insertMaterial(
            Material(
                taskId = taskId,
                type = MaterialType.AUDIO,
                filePath = "tasks/$taskId/audio/AUD_20990101_000000.m4a",
                capturedAt = System.currentTimeMillis(),
                offsetMs = 0,
            ),
        )
        showCapture()
        waitFor("暂无材料")
        assertTrue(db.captureDao().getUnfinishedAudios(taskId).isEmpty())
        assertTrue(db.captureDao().getMaterials(taskId).isEmpty())
    }

    @Test
    fun otherSupervisor_isBlocked() = runBlocking {
        Session.signIn(context, db.userDao().getByAccount("sup02")!!)
        showCapture()
        waitFor("无权限访问该任务")
        rule.onNodeWithText("开始录音").assertDoesNotExist()
        // 越权进入不能改变任务状态
        assertEquals(TaskStatus.NOT_STARTED, db.taskDao().getById(taskId)!!.status)
        assertNull(db.taskDao().getById(taskId)!!.classStartAt)
    }
}
