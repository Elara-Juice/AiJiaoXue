package edu.neu.aijiaoxue.sprint3

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import edu.neu.aijiaoxue.Sprint1Fixture
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.FileStore
import edu.neu.aijiaoxue.ui.improve.ExecutionInput
import edu.neu.aijiaoxue.ui.improve.ImprovementEvidenceStore
import edu.neu.aijiaoxue.ui.improve.ImprovementExecutionRepository
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 使用独立内存库和测试专属目录，不触碰正常课堂材料或演示数据。 */
@RunWith(AndroidJUnit4::class)
class Sprint3EvidenceTest {
    private lateinit var db: AppDatabase
    private lateinit var fixture: Sprint1Fixture
    private lateinit var repository: ImprovementExecutionRepository
    private lateinit var store: ImprovementEvidenceStore
    private lateinit var context: Context
    private lateinit var root: File
    private var taskId = 0L
    private var executionId = 0L

    @Before fun setup() = runBlocking {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        root = Files.createTempDirectory(target.cacheDir.toPath(), "s3-evidence-").toFile()
        context = object : ContextWrapper(target) {
            override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
            override fun getApplicationContext(): Context = this
        }
        db = Room.inMemoryDatabaseBuilder(target, AppDatabase::class.java).build()
        fixture = Sprint1Fixture(db)
        fixture.seed()
        taskId = fixture.submitted()
        val record = fixture.confirmed(taskId)
        repository = ImprovementExecutionRepository(db)
        executionId = repository.append(
            fixture.teacher, taskId, record.measures.first().id,
            ExecutionInput("本轮增加讨论", "将讲授后的练习调整为两人互评", System.currentTimeMillis(), "execution"),
        )
        store = ImprovementEvidenceStore(context, repository)
    }

    @After fun close() {
        if (::db.isInitialized) db.close()
        if (::root.isInitialized) root.deleteRecursively()
    }

    @Test fun actualJpegPngAndPdfAreCopiedAndCanBeReopenedByAnotherStoreInstance() = runBlocking {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val jpeg = File(root, "课堂记录.jpg").also { file ->
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
        }
        val png = File(root, "课堂记录.png").also { file ->
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        }
        bitmap.recycle()
        val pdf = pdf("教学资料.pdf", "A")
        val cases = listOf(jpeg to "image/jpeg", png to "image/png", pdf to "application/pdf")
        cases.forEachIndexed { index, (source, expectedType) ->
            val id = store.importEvidence(fixture.teacher, executionId, Uri.fromFile(source), "教学实施证据", "type-$index")
            val reopened = ImprovementEvidenceStore(context, repository).openEvidence(fixture.teacher, id)
            assertEquals(expectedType, reopened.mimeType)
            assertArrayEquals(source.readBytes(), reopened.file.readBytes())
            assertTrue(reopened.file.canonicalPath.startsWith(context.filesDir.canonicalPath + File.separator))
            assertFalse(reopened.file.name.contains("课堂记录"))
            assertTrue(source.delete())
            assertTrue(store.openEvidence(fixture.teacher, id).file.isFile)
        }
        assertEquals(3, repository.load(fixture.teacher, taskId).executions.single().evidence.size)
        assertFalse(evidenceDirectory().listFiles().orEmpty().any { it.extension == "tmp" })
    }

    @Test fun concurrentRetryKeepsOneRecordAndOneFileAndConflictingRetryPreservesTheFirst() = runBlocking {
        val source = pdf("first.pdf", "A")
        val first = async(Dispatchers.IO) { store.importEvidence(fixture.teacher, executionId, Uri.fromFile(source), "说明", "same-request") }
        val retry = async(Dispatchers.IO) { store.importEvidence(fixture.teacher, executionId, Uri.fromFile(source), "说明", "same-request") }
        val id = first.await()
        assertEquals(id, retry.await())
        val original = store.openEvidence(fixture.teacher, id)
        val originalBytes = original.file.readBytes()
        assertEquals(1, evidenceDirectory().listFiles().orEmpty().size)
        val changed = pdf("changed.pdf", "B")
        assertEquals(source.length(), changed.length())
        val failure = runCatching {
            store.importEvidence(fixture.teacher, executionId, Uri.fromFile(changed), "说明", "same-request")
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertArrayEquals(originalBytes, store.openEvidence(fixture.teacher, id).file.readBytes())
        assertEquals(1, evidenceDirectory().listFiles().orEmpty().size)
        assertEquals(1, repository.load(fixture.teacher, taskId).executions.single().evidence.size)
    }

    @Test fun declaredExtensionDoesNotBypassSignatureOrSizeChecksAndFailureLeavesNoMetadataOrFiles() = runBlocking {
        val impostor = File(root, "fake.pdf").apply { writeText("This is not a PDF file") }
        assertTrue(runCatching {
            store.importEvidence(fixture.teacher, executionId, Uri.fromFile(impostor), "", "invalid-type")
        }.exceptionOrNull() is IOException)
        val oversized = File(root, "too-large.pdf")
        RandomAccessFile(oversized, "rw").use {
            it.write("%PDF-1.4".toByteArray())
            it.setLength(ImprovementEvidenceStore.MAX_EVIDENCE_BYTES + 1)
        }
        assertTrue(runCatching {
            store.importEvidence(fixture.teacher, executionId, Uri.fromFile(oversized), "", "invalid-size")
        }.exceptionOrNull() is IOException)
        assertTrue(repository.load(fixture.teacher, taskId).executions.single().evidence.isEmpty())
        assertTrue(evidenceDirectory().listFiles().orEmpty().isEmpty())
    }

    @Test fun wrongTeacherCannotImportOrOpenAndAuthorizedMissingFilesAreReportedClearly() = runBlocking {
        val source = pdf("source.pdf", "A")
        val unauthorized = runCatching {
            store.importEvidence(fixture.otherTeacher, executionId, Uri.fromFile(source), "", "forbidden")
        }.exceptionOrNull()
        assertNotNull(unauthorized)
        assertFalse(evidenceDirectory().exists())
        val id = store.importEvidence(fixture.teacher, executionId, Uri.fromFile(source), "", "owner")
        assertNotNull(runCatching { store.openEvidence(fixture.otherTeacher, id) }.exceptionOrNull())
        val opened = store.openEvidence(fixture.teacher, id)
        assertTrue(opened.file.delete())
        val missing = runCatching { store.openEvidence(fixture.teacher, id) }.exceptionOrNull()
        assertTrue(missing is IOException)
        assertTrue(missing!!.message.orEmpty().contains("缺失"))
    }

    @Test fun canonicalResolverRejectsTraversalAndAnotherExecutionDirectory() = runBlocking {
        val invalidPaths = listOf(
            "tasks/$taskId/improvement/$executionId/../../outside.pdf",
            "tasks/$taskId/improvement/${executionId + 1}/outside.pdf",
            "tasks/$taskId/improvement/$executionId/..\\outside.pdf",
            File(root, "outside.pdf").absolutePath,
        )
        invalidPaths.forEach { path ->
            assertTrue(runCatching {
                FileStore.resolveImprovementEvidence(context, taskId, executionId, path)
            }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    private fun evidenceDirectory() = File(context.filesDir, "tasks/$taskId/improvement/$executionId")

    private fun pdf(name: String, marker: String): File = File(root, name).apply {
        writeText("%PDF-1.4\n1 0 obj\n<< /Type /Catalog /Marker ($marker) >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n%%EOF\n")
    }
}
