package edu.neu.aijiaoxue.ui.capture

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
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
import edu.neu.aijiaoxue.navigation.Routes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * US09：拍照后立即入库并弹出类型选择；取消拍照不留空文件；类型和备注可保存；相册导入记录导入时间。
 * 系统相机界面无法在测试里操作，这里直接调用 ViewModel，并模拟相机往目标文件写入照片。
 */
@RunWith(AndroidJUnit4::class)
class CapturePhotoTest {
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
                supervisionDate = "2099-01-02",
                period = "1-2",
                type = SupervisionType.REGULAR,
            ),
        )!!
        vm = withContext(Dispatchers.Main) {
            CaptureViewModel(app, SavedStateHandle(mapOf(Routes.ARG_TASK_ID to taskId)))
        }
        // 等进入采集页的校验和开始计时完成
        withTimeout(5_000) { vm.uiState.first { !it.isLoading && it.canCapture } }
    }

    @After
    fun tearDown() = runBlocking<Unit> {
        FileStore.taskDir(context, taskId).deleteRecursively()
        db.openHelper.writableDatabase.execSQL("DELETE FROM supervision_tasks WHERE id = ?", arrayOf(taskId))
        Session.signOut(context)
    }

    private fun writeJpeg(file: File, width: Int = 400, height: Int = 300) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
    }

    /** FileProvider Uri 形如 content://…fileprovider/tasks/{taskId}/photo/IMG_xxx.jpg。 */
    private fun targetOf(uri: Uri): File =
        File(File(FileStore.taskDir(context, taskId), FileStore.PHOTO_DIR), uri.lastPathSegment!!)

    private suspend fun photos(): List<Material> =
        db.captureDao().getMaterials(taskId).filter { it.type != MaterialType.AUDIO }

    private suspend fun waitForPhotos(count: Int): List<Material> = withTimeout(10_000) {
        while (photos().size != count) kotlinx.coroutines.delay(50)
        photos()
    }

    @Test
    fun takePhoto_savesMaterialAndOpensTypeDialog() = runBlocking<Unit> {
        val uri = withContext(Dispatchers.Main) { vm.preparePhoto() }!!
        assertEquals("${context.packageName}.fileprovider", uri.authority)
        // 模拟系统相机往 Uri 对应的文件写入照片
        val target = targetOf(uri)
        assertTrue(target.name.startsWith("IMG_") && target.name.endsWith(".jpg"))
        writeJpeg(target)

        withContext(Dispatchers.Main) { vm.onPhotoTaken(true) }
        val photo = waitForPhotos(1).single()
        assertEquals(taskId, photo.taskId)
        assertEquals("tasks/$taskId/photo/${target.name}", photo.filePath)
        assertFalse(photo.isImported)
        assertTrue(photo.offsetMs >= 0)
        // 拍完立即弹出类型选择
        assertEquals(photo.id, vm.editingPhotoId.value)
        assertTrue(vm.editingPhotoIsNew.value)

        withContext(Dispatchers.Main) { vm.savePhoto(photo, MaterialType.BLACKBOARD, "  第3章板书  ") }
        val saved = withTimeout(5_000) {
            var m = db.captureDao().getMaterial(photo.id)!!
            while (m.type != MaterialType.BLACKBOARD) {
                kotlinx.coroutines.delay(50)
                m = db.captureDao().getMaterial(photo.id)!!
            }
            m
        }
        assertEquals("第3章板书", saved.note)
        assertEquals(0L, vm.editingPhotoId.value)

        // 保存后可再次打开（验收③）：文件能解码
        assertTrue(PhotoFiles.decode(FileStore.resolve(context, saved.filePath), 320) != null)
    }

    @Test
    fun cancelCamera_deletesEmptyFile_andAllowsRetry() = runBlocking<Unit> {
        val first = withContext(Dispatchers.Main) { vm.preparePhoto() }!!
        // 相机被取消时可能已经建了空文件
        targetOf(first).createNewFile()
        withContext(Dispatchers.Main) { vm.onPhotoTaken(false) }
        kotlinx.coroutines.delay(500)
        assertTrue(photos().isEmpty())
        val dir = File(FileStore.taskDir(context, taskId), FileStore.PHOTO_DIR)
        assertTrue(dir.listFiles().isNullOrEmpty())

        // 重试可以正常拍
        val retry = withContext(Dispatchers.Main) { vm.preparePhoto() }!!
        writeJpeg(targetOf(retry))
        withContext(Dispatchers.Main) { vm.onPhotoTaken(true) }
        waitForPhotos(1)
    }

    @Test
    fun importFromGallery_copiesAsJpeg_andMarksImported() = runBlocking<Unit> {
        // 模拟相册里的一张竖向大图
        val source = File(context.cacheDir, "gallery_test.jpg")
        writeJpeg(source, width = 3000, height = 4000)
        val before = System.currentTimeMillis()

        withContext(Dispatchers.Main) { vm.importPhoto(Uri.fromFile(source)) }
        val photo = waitForPhotos(1).single()
        source.delete()

        assertTrue(photo.isImported)
        // 业务规则 2：导入图片记录导入时间，而不是原图拍摄时间
        assertTrue(photo.capturedAt >= before)
        val file = FileStore.resolve(context, photo.filePath)
        assertTrue(file.exists())
        // 长边压到 2560 以内
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
        assertTrue(maxOf(bounds.outWidth, bounds.outHeight) <= 2560)
        assertEquals(photo.id, vm.editingPhotoId.value)
    }

    @Test
    fun importUnreadableFile_showsNoMaterial() = runBlocking<Unit> {
        val bad = File(context.cacheDir, "not_an_image.jpg").apply { writeText("not an image") }
        withContext(Dispatchers.Main) { vm.importPhoto(Uri.fromFile(bad)) }
        kotlinx.coroutines.delay(800)
        bad.delete()
        assertTrue(photos().isEmpty())
        val dir = File(FileStore.taskDir(context, taskId), FileStore.PHOTO_DIR)
        assertTrue(dir.listFiles().isNullOrEmpty())
        assertNull(db.captureDao().getUnfinishedAudios(taskId).firstOrNull())
    }
}
