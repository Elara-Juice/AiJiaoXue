package edu.neu.aijiaoxue.sprint3

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import edu.neu.aijiaoxue.Sprint1Fixture
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.ai.AnalysisJson
import edu.neu.aijiaoxue.data.ai.AnalysisInputs
import edu.neu.aijiaoxue.data.entity.*
import edu.neu.aijiaoxue.data.model.*
import edu.neu.aijiaoxue.data.repository.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EffectivePhaseFlowTest {
    private lateinit var db: AppDatabase
    private lateinit var fixture: Sprint1Fixture
    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        fixture = Sprint1Fixture(db).also { it.seed() }
    }
    @After fun close() = db.close()
    private suspend fun task(): Long {
        val id = fixture.task()
        db.taskDao().markClassStart(id, 1000)
        db.taskDao().markClassEnd(id, 301_000)
        return id
    }

    @Test fun manualCorrectionPublishesAndHistoricalReadingNeverUsesLaterCandidate() = runBlocking {
        val task = task()
        val phases = TeachingPhaseRepository(db)
        phases.appendVersion(fixture.supervisor, task, listOf(PhaseSegment("p1", ActivityKind.LECTURE, "讲授", 0, 100_000)), true)
        val corrected = phases.appendVersion(fixture.supervisor, task, listOf(PhaseSegment("p1", ActivityKind.DISCUSSION, "讨论", 20_000, 120_000)), true)
        val provider = EffectivePhaseProvider(db)
        assertEquals("讨论", provider.load(fixture.supervisor, task).single().label)
        fixture.evaluations.save(fixture.supervisor, task, fixture.draft(task), true)
        val published = provider.load(fixture.supervisor, task).single()
        assertEquals("讨论", published.label)
        assertEquals(20_000L, published.startOffsetMs)
        assertEquals("manual-phase-$corrected", published.versionKey)
        // Simulate a later independent candidate; immutable publication must still be used.
        db.analysisSourceDao().deactivatePhases(task)
        val payload = TeachingPhaseRepository.encode(listOf(PhaseSegment("p2", ActivityKind.LECTURE, "后来候选", 0, 200_000)))
        db.analysisSourceDao().insertPhases(TeachingPhaseVersion(taskId = task, revision = 3, payloadJson = payload,
            contentHash = InputSourceRules.hash(payload), status = PhaseStatus.CONFIRMED, confirmedBy = fixture.supervisor, confirmedAt = 5000, isCurrent = true))
        assertEquals(published, provider.load(fixture.supervisor, task).single())
        assertTrue(runCatching { provider.load(fixture.teacher, task) }.isFailure)
    }

    @Test fun reviewedBopppsUsesCorrectedPayloadAndRealReferencedRanges() = runBlocking {
        val task = task()
        val manualId = TeachingPhaseRepository(db).appendVersion(fixture.supervisor, task,
            listOf(PhaseSegment("p1", ActivityKind.DISCUSSION, "真实讨论来源", 40_000, 90_000)), true)
        val manifest = AnalysisInputs(db, InstrumentationRegistry.getInstrumentation().targetContext)
            .build(fixture.supervisor, task, AnalysisOperation.BOPPPS)
        val fingerprint = AnalysisInputs.fingerprint(manifest)
        manifest.put("inputFingerprint", fingerprint)
        val phaseInputId = AnalysisJson.objects(manifest.getJSONArray("inputs"))
            .single { it.optJSONObject("sourceRef")?.optLong("rowId") == manualId && it.getString("kind") == "MANUAL_PHASE" }
            .getString("inputId")
        val job = db.analysisSourceDao().insertJob(AnalysisJob(clientRequestId = "phase-test", taskId = task,
            operation = AnalysisOperation.BOPPPS, inputFingerprint = fingerprint, inputManifestJson = manifest.toString(),
            state = JobState.SUCCEEDED, createdAt = 1000, updatedAt = 1000))
        fun payload(label: String, stage: String) = JSONObject().put("segments", JSONArray().put(JSONObject()
            .put("id", "s1").put("stage", stage).put("label", label)
            .put("evidenceRefs", JSONArray().put(JSONObject().put("inputId", phaseInputId).put("itemKey", "p1").put("quote", "真实讨论来源")))))
        val original = payload("原讲授标签", "BRIDGE_IN")
        val corrected = payload("人工确认讨论", "PARTICIPATORY_LEARNING")
        corrected.getJSONArray("segments").getJSONObject(0).put("reviewedRanges",
            JSONArray().put(JSONObject().put("startMs", 50_000).put("endMs", 80_000)))
        val analysis = db.aiAnalysisDao().insertAnalysis(AiAnalysisResult(taskId = task, type = AiAnalysisType.BOPPPS,
            content = "原文", confidence = 1f, verified = true, operation = AnalysisOperation.BOPPPS,
            schemaVersion = 1, payloadJson = original.toString(), runId = job.toString(), inputFingerprint = fingerprint, sourceMode = SourceMode.CLOUD_MODEL))
        val correction = db.aiCorrectionDao().insertCorrection(AiCorrection(analysisId = analysis, reviewerId = fixture.supervisor,
            originalContent = "原文", correctedContent = "人工修正", accepted = false,
            originalPayloadHash = AnalysisJson.hashJson(original), correctedPayloadJson = corrected.toString()))
        val stage = EffectivePhaseProvider(db).load(fixture.supervisor, task).single()
        assertTrue(stage.label.contains("人工确认讨论"))
        assertFalse(stage.label.contains("原讲授标签"))
        assertEquals(50_000L, stage.startOffsetMs)
        assertEquals(80_000L, stage.endOffsetMs)
        assertEquals(PhaseLayer.BOPPPS, stage.layer)
        assertTrue(stage.evidenceRefs.single().contains("classRanges"))

        // The frozen effective payload retains the reviewed interval, independent of later candidates.
        val evaluation = db.evaluationDao().upsert(Evaluation(taskId = task, status = EvalStatus.SUBMITTED))
        db.taskDao().updateStatus(task, TaskStatus.COMPLETED)
        val publication = db.improvementSuggestionDao().insertPublication(ReviewPublication(
            evaluationId = evaluation, taskId = task, supervisorId = fixture.supervisor, publishedAt = 2000,
            contextSnapshotJson = JSONObject().put("inputManifests", JSONObject().put(analysis.toString(), manifest)).toString()))
        db.improvementSuggestionDao().insertSnapshot(ReviewedAnalysisSnapshot(publicationId = publication,
            analysisId = analysis, correctionId = correction, reviewerId = fixture.supervisor, type = AiAnalysisType.BOPPPS.name,
            originalContent = "原文", effectiveContent = "人工修正", evidenceSummary = "真实讨论来源", reviewedAt = 1000,
            versionKey = requireNotNull(stage.versionKey), schemaVersion = 1, originalPayloadJson = original.toString(),
            effectivePayloadJson = corrected.toString(), sourceMode = SourceMode.CLOUD_MODEL))
        corrected.getJSONArray("segments").getJSONObject(0).put("reviewedRanges",
            JSONArray().put(JSONObject().put("startMs", 60_000).put("endMs", 70_000)))
        db.aiCorrectionDao().insertCorrection(AiCorrection(analysisId = analysis, reviewerId = fixture.supervisor,
            originalContent = "原文", correctedContent = "后来候选", accepted = false,
            originalPayloadHash = AnalysisJson.hashJson(original), correctedPayloadJson = corrected.toString(), createdAt = Long.MAX_VALUE))
        assertEquals(stage, EffectivePhaseProvider(db).load(fixture.supervisor, task).single())
        assertEquals(60_000L, EffectivePhaseProvider(db).load(fixture.supervisor, task,
            PhaseLayer.BOPPPS, PhaseReadMode.CURRENT_CANDIDATE).single().startOffsetMs)
    }

    @Test fun legacyTextAndMissingPublishedContextNeverBecomeFullLessonStages() = runBlocking {
        val task = task()
        db.aiAnalysisDao().insertAnalysis(AiAnalysisResult(taskId = task, type = AiAnalysisType.BOPPPS,
            content = "无法确认完整教学环节结构", confidence = 0.2f, verified = true, startMs = 0, endMs = 300_000))
        assertTrue(EffectivePhaseProvider(db).load(fixture.supervisor, task).isEmpty())
        val evaluation = db.evaluationDao().upsert(Evaluation(taskId = task, status = EvalStatus.SUBMITTED))
        db.taskDao().updateStatus(task, TaskStatus.COMPLETED)
        db.improvementSuggestionDao().insertPublication(ReviewPublication(evaluationId = evaluation, taskId = task,
            supervisorId = fixture.supervisor, publishedAt = 2000))
        assertTrue(EffectivePhaseProvider(db).load(fixture.supervisor, task).isEmpty())
    }
}
