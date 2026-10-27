package edu.neu.aijiaoxue.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import edu.neu.aijiaoxue.Sprint1Fixture
import edu.neu.aijiaoxue.data.entity.*
import edu.neu.aijiaoxue.data.model.*
import edu.neu.aijiaoxue.data.repository.*
import edu.neu.aijiaoxue.data.ai.AnalysisJson
import edu.neu.aijiaoxue.data.ai.SourceRef
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnalysisSourceRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var fixture: Sprint1Fixture
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var seq = 0

    @Before fun setup() = runBlocking { db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build(); fixture = Sprint1Fixture(db); fixture.seed() }
    @After fun close() { db.close() }
    private suspend fun task(): Long { seq++; return db.taskDao().insert(SupervisionTask(code = "SOURCE-$seq", courseId = fixture.course, supervisorId = fixture.supervisor, supervisionDate = "2026-10-20", period = "$seq", type = SupervisionType.REGULAR, status = TaskStatus.PENDING_EVALUATION, classStartAt = 100000, classEndAt = 200000)) }
    private val courseJson = """{"schemaVersion":1,"knowledgePoints":[{"id":"KP1","text":"树的遍历","chapter":"第三章","required":true}],"objectives":[{"id":"GO1","text":"手动执行遍历","chapter":"第三章","knowledgePointIds":["KP1"]}]}"""

    @Test fun adminCreatesVersionAndSupervisorBindsOnlySelectedCourseScope() = runBlocking {
        val repo = CourseReferenceRepository(db); val task = task()
        val version = repo.createVersion(fixture.admin, fixture.course, CourseReferenceDraft("第三章", "正式大纲", courseJson))
        repo.bindToTask(fixture.supervisor, task, version, listOf("KP1"), emptyList())
        assertEquals(version, db.taskDao().getById(task)!!.courseReferenceVersionId)
        assertTrue(db.taskDao().getById(task)!!.referenceSelectionJson!!.contains("selectedKnowledgePointIds"))
        assertNotNull(runCatching { repo.createVersion(fixture.supervisor, fixture.course, CourseReferenceDraft("章", "依据", courseJson)) }.exceptionOrNull())
        assertNotNull(runCatching { repo.listVersions(fixture.otherTeacher, fixture.course) }.exceptionOrNull())
        assertNotNull(runCatching { repo.bindToTask(fixture.otherSupervisor, task, version, listOf("KP1"), emptyList()) }.exceptionOrNull())
    }

    @Test fun courseVersionRejectsUnknownGoalReference() {
        assertNotNull(runCatching { CourseReferenceRepository.validatePayload(courseJson.replace("[\"KP1\"]", "[\"missing\"]")) }.exceptionOrNull())
    }

    @Test fun unconfirmedPhaseDraftKeepsPreviousConfirmedInput() = runBlocking {
        val task = task(); val repo = TeachingPhaseRepository(db)
        val confirmed = repo.appendVersion(fixture.supervisor, task, listOf(PhaseSegment("p1", ActivityKind.LECTURE, "讲授", 0, 50000)), true)
        val draft = repo.appendVersion(fixture.supervisor, task, listOf(PhaseSegment("p1", ActivityKind.DISCUSSION, "讨论", 0, 60000)), false)
        assertEquals(confirmed, db.analysisSourceDao().currentPhases(task)!!.id)
        assertEquals(draft, db.analysisSourceDao().latestPhases(task)!!.id)
        assertNotNull(runCatching { repo.appendVersion(fixture.otherSupervisor, task, listOf(PhaseSegment("p1", ActivityKind.LECTURE, "越权", 0, 50000)), true) }.exceptionOrNull())
    }

    @Test fun manualFactRetryAndCorrectionPreserveOriginalRecord() = runBlocking {
        val task = task(); val repo = ManualFactRepository(db)
        val draft = ManualFactDraft("教师引导学生举例", classAtMs = 10000)
        val id = repo.add(fixture.supervisor, task, draft, "request-one")
        assertEquals(id, repo.add(fixture.supervisor, task, draft, "request-one"))
        val corrected = repo.add(fixture.supervisor, task, draft.copy(description = "教师引导两位学生举例", supersedesFactId = id), "request-two")
        assertEquals("教师引导学生举例", db.analysisSourceDao().fact(id)!!.description)
        assertFalse(db.analysisSourceDao().fact(id)!!.isCurrent)
        assertEquals(listOf(corrected), db.analysisSourceDao().facts(task).map { it.id })
        assertNotNull(runCatching { repo.add(fixture.supervisor, task, draft.copy(supersedesFactId = id), "request-three") }.exceptionOrNull())
    }

    @Test fun manualFactRejectsOtherTaskSourceAndPostSubmissionWrite() = runBlocking {
        val task = task(); val other = task()
        val event = db.captureDao().insertEvent(InteractionEvent(taskId = other, type = InteractionType.STUDENT_ANSWER, occurredAt = 110000, offsetMs = 10000))
        val source = JSONObject().put("kind", "INTERACTION_EVENT").put("taskId", task).put("rowId", event).toString()
        val repo = ManualFactRepository(db)
        assertNotNull(runCatching { repo.add(fixture.supervisor, task, ManualFactDraft("跨任务来源", sourceRefJson = source), "bad") }.exceptionOrNull())
        fixture.evaluations.save(fixture.supervisor, task, fixture.draft(task), submit = true)
        assertNotNull(runCatching { repo.add(fixture.supervisor, task, ManualFactDraft("提交后补录", classAtMs = 10000), "late") }.exceptionOrNull())
    }

    @Test fun transcriptCorrectionPreservesAcousticRangeAndAddsNewRoleVersion() = runBlocking {
        val task = task(); val material = db.captureDao().insertMaterial(Material(taskId = task, type = MaterialType.AUDIO, filePath = "tasks/$task/audio/test.m4a", capturedAt = 100000, offsetMs = 0, endedAt = 120000, durationMs = 20000))
        db.analysisSourceDao().insertAudioSpans(listOf(AudioTimeSpan(materialId = material, ordinal = 0, mediaStartMs = 0, mediaEndMs = 20000, classStartMs = 1000, classEndMs = 21000, mappingQuality = TimeMappingQuality.EXACT)))
        val json = """{"schemaVersion":1,"segments":[{"segmentId":"s1","mediaStartMs":1000,"mediaEndMs":5000,"text":"请思考","speakerRole":"TEACHER","words":[{"text":"请","startMs":1000,"endMs":1500}]}]}"""
        val repo = TranscriptRepository(db)
        val original = repo.saveGenerated(fixture.supervisor, task, material, json, "hash", "provider", "model", "v1")
        val oldJson = JSONObject(db.analysisSourceDao().transcript(original)!!.payloadJson).getJSONArray("segments").getJSONObject(0)
        assertEquals("UNKNOWN", oldJson.getString("speakerRole"))
        val corrected = repo.correctSegment(fixture.supervisor, task, original, "s1", "请大家思考", SpeakerRole.TEACHER)
        val newJson = JSONObject(db.analysisSourceDao().transcript(corrected)!!.payloadJson).getJSONArray("segments").getJSONObject(0)
        assertEquals(1000L, newJson.getLong("mediaStartMs")); assertEquals(5000L, newJson.getLong("mediaEndMs"))
        assertEquals("TEACHER", newJson.getString("speakerRole")); assertFalse(newJson.has("words"))
        assertFalse(db.analysisSourceDao().transcript(original)!!.isCurrent)
        assertNotNull(runCatching { repo.correctSegment(fixture.supervisor, task, original, "s1", "旧版本覆盖", SpeakerRole.UNKNOWN) }.exceptionOrNull())
    }

    @Test fun formalEvidenceReturnsOnlySummaryToOwnTeacherAndDeniesOtherTeacher() = runBlocking {
        val task = fixture.submitted()
        val evaluation = db.evaluationDao().getByTask(task)!!
        val publication = db.improvementSuggestionDao().publicationForEvaluation(evaluation.id)!!
        val fact = db.analysisSourceDao().insertPublishedFact(PublishedFactSnapshot(publicationId = publication.id, sourceFactId = 42, description = "冻结事实", recordedBy = fixture.supervisor, recordedAt = 1))
        val evidence = db.analysisSourceDao().insertPublishedEvidence(PublishedEvidenceSnapshot(publicationId = publication.id, factSnapshotId = fact, ordinal = 0, taskId = task, sourceRefJson = null, excerpt = "允许公开的摘要", sourceHash = null, availabilityAtPublish = "SUMMARY_ONLY", capturedAt = 1))
        val resolver = EvidenceSourceResolver(context, db)
        assertTrue(resolver.openPublished(fixture.teacher, task, evidence) is ResolvedEvidenceSource.SummaryOnly)
        assertNotNull(runCatching { resolver.openPublished(fixture.otherTeacher, task, evidence) }.exceptionOrNull())
        assertNotNull(runCatching { resolver.openPublished(fixture.otherSupervisor, task, evidence) }.exceptionOrNull())
    }

    private suspend fun structuredAnalysis(taskId: Long): AiAnalysisResult {
        val material = db.captureDao().insertMaterial(Material(taskId = taskId, type = MaterialType.AUDIO, filePath = "tasks/$taskId/audio/evidence.m4a", capturedAt = 100000, offsetMs = 0, endedAt = 120000, durationMs = 20000))
        val transcriptJson = """{"schemaVersion":1,"segments":[{"segmentId":"s1","mediaStartMs":1000,"mediaEndMs":5000,"text":"教师介绍树的前序遍历","speakerRole":"UNKNOWN"}]}"""
        val versionId = TranscriptRepository(db).saveGenerated(fixture.supervisor, taskId, material, transcriptJson, "hash", "provider", "model", "v1")
        val version = db.analysisSourceDao().transcript(versionId)!!
        val manifest = JSONObject().put("operation", "CONTENT").put("inputs", JSONArray().put(JSONObject().put("inputId", "tr-1").put("kind", "TRANSCRIPT").put("payload", JSONObject(version.payloadJson)).put("sourceRef", SourceRef(EvidenceKind.TRANSCRIPT_SEGMENT, taskId, version.id, version.revision, null, version.contentHash).json())))
        val fingerprint = AnalysisJson.hashJson(manifest)
        val job = db.analysisSourceDao().insertJob(AnalysisJob(clientRequestId = "test-$taskId", taskId = taskId, operation = AnalysisOperation.CONTENT, inputFingerprint = fingerprint, inputManifestJson = manifest.toString(), state = JobState.SUCCEEDED, createdAt = 1, updatedAt = 1))
        val payload = """{"topics":[{"id":"topic-1","name":"树的遍历","keywords":["前序"],"evidenceRefs":[{"inputId":"tr-1","itemKey":"s1","quote":"前序遍历"}]}]}"""
        val analysis = AiAnalysisResult(taskId = taskId, type = AiAnalysisType.CONTENT, content = "树的遍历", confidence = 0f, operation = AnalysisOperation.CONTENT, schemaVersion = 1, payloadJson = payload, inputFingerprint = fingerprint, runId = job.toString(), sourceMode = SourceMode.CLOUD_MODEL)
        return analysis.copy(id = db.aiAnalysisDao().insertAnalysis(analysis))
    }

    @Test fun structuredReviewRejectsSpoofedOwnerHashAndQuote() = runBlocking {
        val task = task(); val analysis = structuredAnalysis(task); val repo = AiCorrectionRepository(db)
        val payload = JSONObject(analysis.payloadJson!!); val hash = AnalysisJson.hashJson(payload)
        assertNotNull(runCatching { repo.saveStructuredCorrection(analysis.id, fixture.otherSupervisor, analysis.inputFingerprint!!, hash, payload.toString()) }.exceptionOrNull())
        assertNotNull(runCatching { repo.saveStructuredCorrection(analysis.id, fixture.supervisor, analysis.inputFingerprint!!, "forged", payload.toString()) }.exceptionOrNull())
        payload.getJSONArray("topics").getJSONObject(0).getJSONArray("evidenceRefs").getJSONObject(0).put("quote", "原记录中从未出现的内容")
        assertNotNull(runCatching { repo.saveStructuredCorrection(analysis.id, fixture.supervisor, analysis.inputFingerprint!!, hash, payload.toString()) }.exceptionOrNull())
        assertFalse(db.aiAnalysisDao().getAnalysisById(analysis.id)!!.verified)
    }

    @Test fun structuredReviewFreezesEffectivePayloadFactsAndSourceManifest() = runBlocking {
        val task = task(); val analysis = structuredAnalysis(task); val repo = AiCorrectionRepository(db)
        val payload = JSONObject(analysis.payloadJson!!); val hash = AnalysisJson.hashJson(payload)
        payload.getJSONArray("topics").getJSONObject(0).put("name", "二叉树前序遍历")
        val correctionId = repo.saveStructuredCorrection(analysis.id, fixture.supervisor, analysis.inputFingerprint!!, hash, payload.toString())
        val savedCorrection = db.aiCorrectionDao().getByAnalysis(analysis.id).first()
        assertFalse(savedCorrection.accepted)
        assertEquals(AnalysisJson.summary(JSONObject(savedCorrection.correctedPayloadJson!!)), savedCorrection.correctedContent)
        ManualFactRepository(db).add(fixture.supervisor, task, ManualFactDraft("学生补充了一个例子", classAtMs = 30000), "fact-before-publication")
        fixture.evaluations.save(fixture.supervisor, task, fixture.draft(task), submit = true)
        val evaluation = db.evaluationDao().getByTask(task)!!
        val publication = db.improvementSuggestionDao().publicationForEvaluation(evaluation.id)!!
        val frozen = db.improvementSuggestionDao().snapshots(publication.id).single()
        assertEquals(correctionId, frozen.correctionId)
        assertEquals(savedCorrection.correctedContent, frozen.effectiveContent)
        assertEquals(AnalysisJson.summary(JSONObject(frozen.effectivePayloadJson!!)), frozen.effectiveContent)
        assertTrue(frozen.effectivePayloadJson!!.contains("二叉树前序遍历"))
        assertTrue(JSONObject(publication.contextSnapshotJson!!).getJSONObject("inputManifests").has(analysis.id.toString()))
        assertEquals(1, db.analysisSourceDao().publishedFacts(publication.id).size)
        assertEquals(1, db.analysisSourceDao().publishedEvidence(publication.id).size)
        db.aiAnalysisDao().deactivate(task, AnalysisOperation.CONTENT)
        assertEquals(frozen.effectivePayloadJson, db.improvementSuggestionDao().snapshots(publication.id).single().effectivePayloadJson)
    }

    @Test fun changedTranscriptCannotBePromotedByOldStructuredReview() = runBlocking {
        val task = task(); val analysis = structuredAnalysis(task)
        val transcript = db.analysisSourceDao().transcripts(task).single()
        TranscriptRepository(db).correctSegment(fixture.supervisor, task, transcript.id, "s1", "教师改讲了中序遍历", SpeakerRole.TEACHER)
        assertNotNull(runCatching { AiCorrectionRepository(db).saveStructuredCorrection(analysis.id, fixture.supervisor, analysis.inputFingerprint!!, AnalysisJson.hashJson(JSONObject(analysis.payloadJson!!)), analysis.payloadJson) }.exceptionOrNull())
        assertTrue(ReviewedAnalysisProvider(db).forSupervisor(fixture.supervisor, task).isEmpty())
    }

    @Test fun bopppsManualTimeBoundaryMustStayInsideReferencedIntervals() {
        val input = JSONObject().put("inputId", "phase-input").put("kind", "MANUAL_PHASE")
            .put("sourceRef", SourceRef(EvidenceKind.MANUAL_PHASE, 1, 1, 1, null, "hash").json())
            .put("payload", JSONObject().put("segments", JSONArray().put(JSONObject().put("id", "phase-1").put("label", "讨论").put("startMs", 0).put("endMs", 5000))))
        val manifest = JSONObject().put("inputs", JSONArray().put(input))
        val segment = JSONObject().put("id", "b1").put("stage", "PARTICIPATORY_LEARNING").put("label", "讨论")
            .put("evidenceRefs", JSONArray().put(JSONObject().put("inputId", "phase-input").put("itemKey", "phase-1").put("quote", "讨论")))
        val coverage = JSONArray(StructuredPayloadValidation.stages.map { stage -> JSONObject().put("stage", stage).put("status", if (stage == "PARTICIPATORY_LEARNING") "OBSERVED" else "INSUFFICIENT_INPUT").put("segmentIds", if (stage == "PARTICIPATORY_LEARNING") JSONArray().put("b1") else JSONArray()).put("reason", "按真实记录判断") })
        val original = JSONObject().put("segments", JSONArray().put(segment)).put("coverage", coverage)
        val edited = JSONObject(original.toString())
        edited.getJSONArray("segments").getJSONObject(0).put("reviewedRanges", JSONArray().put(JSONObject().put("startMs", 1000).put("endMs", 4000)))
        StructuredPayloadValidation.validate(AnalysisOperation.BOPPPS, original, edited, manifest, 1)
        edited.getJSONArray("segments").getJSONObject(0).getJSONArray("reviewedRanges").getJSONObject(0).put("endMs", 6000)
        assertNotNull(runCatching { StructuredPayloadValidation.validate(AnalysisOperation.BOPPPS, original, edited, manifest, 1) }.exceptionOrNull())
    }

    @Test fun transcriptRejectsFractionalWordAndSentenceTimestamps() {
        val valid = """{"schemaVersion":1,"segments":[{"segmentId":"s1","mediaStartMs":0,"mediaEndMs":1000,"text":"你好","words":[{"text":"你","startMs":0,"endMs":500}]}]}"""
        TranscriptRepository.validateAndMap(valid, 1000, emptyList(), manual = false)
        val fractionalWord = JSONObject(valid)
        fractionalWord.getJSONArray("segments").getJSONObject(0).getJSONArray("words").getJSONObject(0).put("startMs", 0.5)
        assertNotNull(runCatching { TranscriptRepository.validateAndMap(fractionalWord.toString(), 1000, emptyList(), manual = false) }.exceptionOrNull())
        val fractionalSentence = JSONObject(valid)
        fractionalSentence.getJSONArray("segments").getJSONObject(0).put("mediaEndMs", 999.5)
        assertNotNull(runCatching { TranscriptRepository.validateAndMap(fractionalSentence.toString(), 1000, emptyList(), manual = false) }.exceptionOrNull())
    }
}
