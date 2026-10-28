package edu.neu.aijiaoxue.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import edu.neu.aijiaoxue.Sprint1Fixture
import edu.neu.aijiaoxue.data.ai.*
import edu.neu.aijiaoxue.data.entity.*
import edu.neu.aijiaoxue.data.model.*
import edu.neu.aijiaoxue.data.repository.*
import edu.neu.aijiaoxue.ui.input.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Uses an isolated database and local operations; never calls a cloud provider. */
@RunWith(AndroidJUnit4::class)
class AnalysisWorkflowTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: AppDatabase
    private lateinit var fixture: Sprint1Fixture
    private val files = mutableListOf<File>()
    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        fixture = Sprint1Fixture(db); fixture.seed()
    }
    @After fun close() { db.close(); files.forEach { it.delete() } }

    private suspend fun input(): Pair<Long, Long> {
        val task = fixture.task()
        val (file, path) = FileStore.importedAudioFile(context, task, UUID.randomUUID().toString(), "wav")
        file.writeText("unique fixture media"); files += file
        val material = db.captureDao().insertMaterial(Material(taskId = task, type = MaterialType.AUDIO,
            filePath = path, durationMs = 20000, capturedAt = 0, offsetMs = 0, isImported = true))
        val version = TranscriptRepository(db).saveGenerated(fixture.supervisor, task, material,
            """{"schemaVersion":1,"segments":[{"segmentId":"s1","text":"然后我们。然后我们。然后我们。","mediaStartMs":0,"mediaEndMs":10000},{"segmentId":"s2","text":"第二句","mediaStartMs":10000,"mediaEndMs":20000}]}""",
            AnalysisInputs.hashFile(file), "fixture", "fixture", "fixture")
        return task to version
    }

    @Test fun batchCorrectionIsAtomicAndKeepsUnknownClassTime() = runBlocking {
        val (task, original) = input(); val repo = TranscriptRepository(db)
        val next = repo.correctBatch(fixture.supervisor, task, original, listOf(TranscriptEdit("s1", "第一句已改", SpeakerRole.TEACHER), TranscriptEdit("s2", "第二句已改", SpeakerRole.STUDENT)))
        val version = db.analysisSourceDao().transcript(next)!!
        assertEquals(2, version.revision)
        val segments = JSONObject(version.payloadJson).getJSONArray("segments")
        assertEquals("第一句已改", segments.getJSONObject(0).getString("text"))
        assertEquals("第二句已改", segments.getJSONObject(1).getString("text"))
        assertEquals("UNKNOWN", segments.getJSONObject(0).getString("mappingQuality"))
        assertEquals(0, segments.getJSONObject(0).getJSONArray("classRanges").length())
        assertTrue(runCatching { repo.correctBatch(fixture.supervisor, task, original, listOf(TranscriptEdit("s1", "旧页", SpeakerRole.UNKNOWN))) }.exceptionOrNull() is InputVersionConflictException)
        assertTrue(runCatching { repo.correctBatch(fixture.supervisor, task, next, listOf(TranscriptEdit("s1", "不应写入", SpeakerRole.UNKNOWN), TranscriptEdit("missing", "无效", SpeakerRole.UNKNOWN))) }.isFailure)
        assertEquals(next, db.analysisSourceDao().transcripts(task).single().id)
    }

    @Test fun planReusesTranscriptPausesAndCompletesOnlyAfterExplicitReview() = runBlocking {
        val (task, _) = input(); val repo = AnalysisRunRepository(db, context)
        val coordinator = AnalysisRunCoordinator(db, context)
        val request = UUID.randomUUID().toString()
        val run = repo.createRun(fixture.supervisor, task, AnalysisSelection(setOf(AnalysisOperation.EXPRESSION_STATS)), request)
        assertEquals(run, repo.createRun(fixture.supervisor, task, AnalysisSelection(), request))
        coordinator.resumeRun(fixture.supervisor, run)
        coordinator.pauseRun(fixture.supervisor, run)
        coordinator.tick(fixture.supervisor, run)
        assertTrue(db.analysisSourceDao().jobsForTask(task).isEmpty())
        coordinator.resumeRun(fixture.supervisor, run)
        coordinator.tick(fixture.supervisor, run)
        assertEquals(AnalysisStepState.REUSED, repo.snapshot(fixture.supervisor, run).steps.first().state)
        coordinator.tick(fixture.supervisor, run)
        assertEquals(AnalysisRunState.WAITING_REVIEW, repo.snapshot(fixture.supervisor, run).run.state)
        coordinator.resumeRun(fixture.supervisor, run)
        coordinator.tick(fixture.supervisor, run)
        coordinator.tick(fixture.supervisor, run)
        assertEquals(AnalysisRunState.COMPLETED, repo.snapshot(fixture.supervisor, run).run.state)
        assertEquals(SourceMode.DETERMINISTIC_RULE, db.aiAnalysisDao().getByTask(task).single().sourceMode)
        assertNotNull(repo.snapshot(fixture.supervisor, run).steps.last().jobId)
    }

    @Test fun changedInputBlocksAutomaticSubmissionAndCancellationIsDurable() = runBlocking {
        val (task, version) = input(); val repo = AnalysisRunRepository(db, context)
        val coordinator = AnalysisRunCoordinator(db, context)
        val run = repo.createRun(fixture.supervisor, task, AnalysisSelection(), UUID.randomUUID().toString())
        coordinator.resumeRun(fixture.supervisor, run)
        TranscriptRepository(db).correctSegment(fixture.supervisor, task, version, "s1", "新版本", SpeakerRole.UNKNOWN)
        coordinator.tick(fixture.supervisor, run)
        assertEquals(AnalysisRunState.WAITING_DECISION, repo.snapshot(fixture.supervisor, run).run.state)
        assertTrue(db.analysisSourceDao().jobsForTask(task).isEmpty())
        assertTrue(runCatching { repo.snapshot(fixture.otherSupervisor, run) }.isFailure)
        coordinator.cancelRun(fixture.supervisor, run)
        assertEquals(AnalysisRunState.CANCELLED, repo.snapshot(fixture.supervisor, run).run.state)
        coordinator.tick(fixture.supervisor, run)
        assertTrue(db.analysisSourceDao().jobsForTask(task).isEmpty())
    }

    @Test fun restoredDraftRetainsUnsavedEditsAndSaveDoesNotClearNewerTyping() {
        val submitted = TranscriptEditorDraft(10, 20, mapOf("s1" to TranscriptEdit("s1", "已提交", SpeakerRole.TEACHER)), 1)
        val current = submitted.copy(changes = submitted.changes + ("s1" to TranscriptEdit("s1", "保存中继续输入", SpeakerRole.TEACHER)), editRevision = 2)
        val reconciled = reconcileTranscriptSave(current, submitted, 21)
        assertEquals(21L, reconciled.baseVersionId)
        assertEquals("保存中继续输入", reconciled.changes.getValue("s1").text)
        val state = InputEditorState(tab = InputWorkspaceTab.TRANSCRIPT, transcripts = mapOf(10L to reconciled), fact = FactEditorDraft(description = "未保存事实"))
        assertEquals(state, InputDraftStore.decode(InputDraftStore.encode(state)))
    }
}
