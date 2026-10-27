package edu.neu.aijiaoxue.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import edu.neu.aijiaoxue.Sprint1Fixture
import edu.neu.aijiaoxue.data.ai.*
import edu.neu.aijiaoxue.data.entity.*
import edu.neu.aijiaoxue.data.model.*
import edu.neu.aijiaoxue.data.repository.TranscriptRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnalysisPipelineTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: AppDatabase
    private lateinit var fixture: Sprint1Fixture
    @Before fun setup() = runBlocking { db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build(); fixture = Sprint1Fixture(db); fixture.seed() }
    @After fun close() { db.close() }
    private suspend fun withTranscript(): Long {
        val task = fixture.task()
        val material = db.captureDao().insertMaterial(Material(taskId = task, type = MaterialType.AUDIO, filePath = "tasks/$task/audio/fixture.m4a", durationMs = 20000, capturedAt = 0, offsetMs = 0))
        TranscriptRepository(db).saveGenerated(fixture.supervisor, task, material,
            """{"schemaVersion":1,"segments":[{"segmentId":"s1","text":"然后我们。然后我们。然后我们。二叉树。二叉树。二叉树。","mediaStartMs":0,"mediaEndMs":10000,"speakerRole":"UNKNOWN"}]}""",
            "fixture-hash", "fixture", "fixture", "fixture-v1")
        return task
    }
    @Test fun localExpressionPipelineHasRealReferencesAndPreservesPreviousBatch() = runBlocking {
        val task = withTranscript()
        val repo = TranscriptRepository(db)
        repo.saveTermExclusions(fixture.supervisor, task, listOf("二叉树"), emptyList())
        val pipeline = CloudAnalysisPipeline(db, context)
        val job = pipeline.start(fixture.supervisor, task, AnalysisOperation.EXPRESSION_STATS)
        assertEquals(JobState.SUCCEEDED, db.analysisSourceDao().getJob(job)!!.state)
        val first = db.aiAnalysisDao().getByTask(task).single()
        assertEquals(SourceMode.DETERMINISTIC_RULE, first.sourceMode)
        assertFalse(first.payloadJson!!.contains("二叉树"))
        assertTrue(first.payloadJson!!.contains("然后我们"))
        val refs = db.aiEvidenceDao().getByAnalysis(first.id)
        assertTrue(refs.isNotEmpty()); assertEquals("s1", JSONObject(refs.first().sourceRefJson!!).getString("itemKey"))
        pipeline.start(fixture.supervisor, task, AnalysisOperation.EXPRESSION_STATS)
        assertNotEquals(first.id, db.aiAnalysisDao().getByTask(task).single().id)
        assertNotNull(db.aiAnalysisDao().getAnalysisById(first.id))
        assertFalse(db.aiAnalysisDao().getAnalysisById(first.id)!!.isCurrent)
    }
    @Test fun differentOperationDoesNotDeleteExistingAnalysis() = runBlocking {
        val task = withTranscript(); val pipeline = CloudAnalysisPipeline(db, context)
        pipeline.start(fixture.supervisor, task, AnalysisOperation.EXPRESSION_STATS)
        pipeline.start(fixture.supervisor, task, AnalysisOperation.INTERACTION_STATS)
        val rows = db.aiAnalysisDao().getByTask(task)
        assertEquals(2, rows.size)
        val interaction = rows.single { it.operation == AnalysisOperation.INTERACTION_STATS }
        assertEquals(ResultState.INSUFFICIENT_INPUT, interaction.resultState)
        assertTrue(interaction.content.contains("无法据此判断"))
    }
    @Test fun changedTermsInvalidateOnlyRelevantManifest() = runBlocking {
        val task = withTranscript(); val inputs = AnalysisInputs(db, context)
        val expression = inputs.build(fixture.supervisor, task, AnalysisOperation.EXPRESSION_STATS)
        val content = inputs.build(fixture.supervisor, task, AnalysisOperation.CONTENT)
        TranscriptRepository(db).saveTermExclusions(fixture.supervisor, task, listOf("二叉树"), emptyList())
        assertFalse(ManifestEvidence.isCurrent(db, task, expression))
        assertTrue(ManifestEvidence.isCurrent(db, task, content))
        assertEquals(AnalysisInputs.fingerprint(content), AnalysisInputs.fingerprint(inputs.build(fixture.supervisor, task, AnalysisOperation.CONTENT)))
    }
    @Test fun changedTranscriptInvalidatesAnalysisAndUnauthorizedCallerCannotStart() = runBlocking {
        val task = withTranscript(); val inputs = AnalysisInputs(db, context)
        val manifest = inputs.build(fixture.supervisor, task, AnalysisOperation.CONTENT)
        val version = db.analysisSourceDao().transcripts(task).single()
        TranscriptRepository(db).correctSegment(fixture.supervisor, task, version.id, "s1", "修订后的原句", SpeakerRole.TEACHER)
        assertFalse(ManifestEvidence.isCurrent(db, task, manifest))
        assertNotNull(runCatching { CloudAnalysisPipeline(db, context).start(fixture.otherSupervisor, task, AnalysisOperation.EXPRESSION_STATS) }.exceptionOrNull())
    }
    @Test fun unknownReferencesAndInventedQuotesAreRejected() = runBlocking {
        val task = withTranscript(); val manifest = AnalysisInputs(db, context).build(fixture.supervisor, task, AnalysisOperation.CONTENT)
        val inputId = manifest.getJSONArray("inputs").getJSONObject(0).getString("inputId")
        val valid = JSONObject("""{"activities":[{"sourceRefs":[{"inputId":"$inputId","itemKey":"s1","quote":"然后我们"}]}]}""")
        assertEquals(1, ManifestEvidence.resolve(valid, manifest, task).size)
        val invented = JSONObject(valid.toString().replace("然后我们", "并未出现的转写"))
        assertNotNull(runCatching { ManifestEvidence.resolve(invented, manifest, task) }.exceptionOrNull())
        val cross = JSONObject(valid.toString().replace(inputId, "tr-cross-task"))
        assertNotNull(runCatching { ManifestEvidence.resolve(cross, manifest, task) }.exceptionOrNull())
    }
    @Test fun fingerprintIgnoresTransportIdentityButIncludesSelectedInput() {
        val a = JSONObject("""{"schemaVersion":1,"operation":"CONTENT","caseId":"one","inputRevision":1,"inputs":[{"inputId":"tr-1","kind":"TRANSCRIPT","revision":1,"assetId":null,"payload":{"text":"A"}}]}""")
        val b = JSONObject(a.toString()).put("caseId", "two").put("inputRevision", 100)
        b.getJSONArray("inputs").getJSONObject(0).put("assetId", "upload-id")
        assertEquals(AnalysisInputs.fingerprint(a), AnalysisInputs.fingerprint(b))
        b.getJSONArray("inputs").getJSONObject(0).getJSONObject("payload").put("text", "B")
        assertNotEquals(AnalysisInputs.fingerprint(a), AnalysisInputs.fingerprint(b))
    }
}
