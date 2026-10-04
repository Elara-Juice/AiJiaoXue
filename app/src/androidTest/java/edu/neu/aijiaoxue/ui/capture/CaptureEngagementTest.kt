package edu.neu.aijiaoxue.ui.capture

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.DemoData
import edu.neu.aijiaoxue.data.FileStore
import edu.neu.aijiaoxue.data.Session
import edu.neu.aijiaoxue.data.entity.Material
import edu.neu.aijiaoxue.data.entity.SupervisionTask
import edu.neu.aijiaoxue.data.model.EngagementType
import edu.neu.aijiaoxue.data.model.MaterialType
import edu.neu.aijiaoxue.data.model.SupervisionType
import edu.neu.aijiaoxue.data.model.TaskStatus
import edu.neu.aijiaoxue.navigation.Routes
import edu.neu.aijiaoxue.ui.engagement.EngagementInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * US13：参与状态立即入库，自动记录时间并关联当前任务；越界不写库；采集结束后不能新增。
 * US11：删除先删文件再删记录；评价提交（已完成）后不能删；正在录制的那段不能删。
 */
@RunWith(AndroidJUnit4::class)
class CaptureEngagementTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = context.applicationContext as Application
    private val db = AppDatabase.get(context)
    private var taskId = 0L
    private lateinit var vm: CaptureViewModel

    @Before
    fun setUp() = runBlocking<Unit> {
        DemoData.seedIfEmpty(db)
        val sup = db.userDao().getByAccount("sup01")!!
        Session.signIn(context, sup)
        val course = db.courseDao().observeRows("").first().first()
        taskId = db.taskDao().create(
            SupervisionTask(
                code = "",
                courseId = course.id,
                supervisorId = sup.id,
                supervisionDate = "2099-01-04",
                period = "1-2",
                type = SupervisionType.REGULAR,
            ),
        )!!
        vm = withContext(Dispatchers.Main) {
            CaptureViewModel(app, SavedStateHandle(mapOf(Routes.ARG_TASK_ID to taskId)))
        }
        withTimeout(5_000) { vm.uiState.first { !it.isLoading && it.canCapture } }
    }

    @After
    fun tearDown() = runBlocking<Unit> {
        FileStore.taskDir(context, taskId).deleteRecursively()
        db.openHelper.writableDatabase.execSQL("DELETE FROM supervision_tasks WHERE id = ?", arrayOf(taskId))
        Session.signOut(context)
    }

    private suspend fun awaitState(predicate: (CaptureUiState) -> Boolean): CaptureUiState =
        withTimeout(5_000) { vm.uiState.first(predicate) }

    private suspend fun addPhoto(): Material {
        val (file, path) = FileStore.newPhotoFile(context, taskId)
        file.writeBytes(byteArrayOf(1, 2, 3))
        val start = db.taskDao().getById(taskId)!!.classStartAt!!
        val now = System.currentTimeMillis()
        val id = db.captureDao().insertMaterial(
            Material(taskId = taskId, type = MaterialType.PPT, filePath = path, capturedAt = now, offsetMs = now - start),
        )
        return db.captureDao().getMaterial(id)!!
    }

    @Test
    fun recordEngagement_savesWithTimeAndTask() = runBlocking<Unit> {
        val before = System.currentTimeMillis()
        withContext(Dispatchers.Main) {
            vm.recordEngagement(EngagementInput(EngagementType.DISCUSSING, 40, null, 80, "分组讨论"))
        }
        val record = awaitState { it.engagements.size == 1 }.engagements.single()
        assertEquals(taskId, record.taskId)
        assertEquals(EngagementType.DISCUSSING, record.type)
        assertEquals(40, record.presentCount)
        assertNull(record.engagedCount)
        assertEquals(80, record.engagedPercent)
        assertEquals("分组讨论", record.description)
        assertTrue(record.recordedAt >= before)
        val start = db.taskDao().getById(taskId)!!.classStartAt!!
        assertEquals(record.recordedAt - start, record.offsetMs)
    }

    @Test
    fun recordEngagement_outOfRangeIsNotSaved() = runBlocking<Unit> {
        withContext(Dispatchers.Main) {
            vm.recordEngagement(EngagementInput(EngagementType.LISTENING, 30, 35, null, null))
            vm.recordEngagement(EngagementInput(EngagementType.LISTENING, null, null, 120, null))
            vm.recordEngagement(EngagementInput(EngagementType.LISTENING, 30, 20, 50, null))
        }
        delay(500)
        assertTrue(db.captureDao().observeEngagements(taskId).first().isEmpty())
    }

    @Test
    fun recordEngagement_blockedAfterClassEnded() = runBlocking<Unit> {
        db.taskDao().endClass(taskId, System.currentTimeMillis())
        awaitState { it.status == TaskStatus.PENDING_EVALUATION }
        withContext(Dispatchers.Main) {
            vm.recordEngagement(EngagementInput(EngagementType.OTHER, null, null, null, "结束后"))
        }
        delay(500)
        assertTrue(db.captureDao().observeEngagements(taskId).first().isEmpty())
    }

    @Test
    fun deleteMaterial_removesFileThenRecord() = runBlocking<Unit> {
        val photo = addPhoto()
        val item = awaitState { s -> s.materials.any { it.material.id == photo.id } }
            .materials.single { it.material.id == photo.id }
        assertTrue(FileStore.exists(context, photo.filePath))
        withContext(Dispatchers.Main) { vm.deleteMaterial(item) }
        awaitState { s -> s.materials.none { it.material.id == photo.id } }
        assertNull(db.captureDao().getMaterial(photo.id))
        assertFalse(FileStore.exists(context, photo.filePath))
    }

    /** 需求 3.3：“待评价”下仍可补充材料，所以也可以删掉拍坏的再补。 */
    @Test
    fun deleteMaterial_allowedWhilePendingEvaluation() = runBlocking<Unit> {
        val photo = addPhoto()
        db.taskDao().endClass(taskId, System.currentTimeMillis())
        val state = awaitState { s -> s.status == TaskStatus.PENDING_EVALUATION && s.materials.isNotEmpty() }
        assertTrue(state.canDeleteMaterials)
        withContext(Dispatchers.Main) { vm.deleteMaterial(state.materials.single()) }
        withTimeout(5_000) { while (db.captureDao().getMaterial(photo.id) != null) delay(50) }
        assertFalse(FileStore.exists(context, photo.filePath))
    }

    @Test
    fun deleteMaterial_blockedAfterEvaluationSubmitted() = runBlocking<Unit> {
        val photo = addPhoto()
        val item = awaitState { it.materials.isNotEmpty() }.materials.single()
        db.taskDao().endClass(taskId, System.currentTimeMillis())
        db.taskDao().updateStatus(taskId, TaskStatus.COMPLETED)
        awaitState { it.status == TaskStatus.COMPLETED && !it.canDeleteMaterials }
        withContext(Dispatchers.Main) { vm.deleteMaterial(item) }
        delay(500)
        assertNotNull(db.captureDao().getMaterial(photo.id))
        assertTrue(FileStore.exists(context, photo.filePath))
    }
}
