package edu.neu.aijiaoxue.sprint2

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import edu.neu.aijiaoxue.Sprint1Fixture
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.ai.AIAnalyzer
import edu.neu.aijiaoxue.data.ai.AIEvidenceRef
import edu.neu.aijiaoxue.data.ai.AIResult
import edu.neu.aijiaoxue.data.entity.AiCorrection
import edu.neu.aijiaoxue.data.entity.AiEvidence
import edu.neu.aijiaoxue.data.entity.EngagementRecord
import edu.neu.aijiaoxue.data.entity.InteractionEvent
import edu.neu.aijiaoxue.data.entity.Material
import edu.neu.aijiaoxue.data.model.AiAnalysisType
import edu.neu.aijiaoxue.data.model.EngagementType
import edu.neu.aijiaoxue.data.model.InteractionType
import edu.neu.aijiaoxue.data.model.MaterialType
import edu.neu.aijiaoxue.data.model.TaskStatus
import edu.neu.aijiaoxue.data.repository.AIAnalysisRepository
import edu.neu.aijiaoxue.data.repository.AiCorrectionRepository
import edu.neu.aijiaoxue.data.repository.AiEvidenceRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiUpstreamSecurityTest {
    private lateinit var db: AppDatabase
    private lateinit var fixture: Sprint1Fixture
    private var taskId = 0L

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        fixture = Sprint1Fixture(db)
        fixture.seed()
        taskId = fixture.task()
    }

    @After fun close() = db.close()

    private fun analysis(
        evidences: List<AIEvidenceRef> = emptyList(),
        beforeResult: suspend () -> Unit = {},
    ) = AIAnalysisRepository(db, object : AIAnalyzer {
        override suspend fun analyze(taskId: Long, materials: List<Material>, events: List<InteractionEvent>,
            engagements: List<EngagementRecord>): List<AIResult> {
            beforeResult()
            return listOf(AIResult(AiAnalysisType.BOPPPS, "固定测试结论", 0.8f, 0, 1000, evidences))
        }
    })

    private suspend fun denied(action: suspend () -> Unit) {
        val failure = runCatching { action() }.exceptionOrNull()
        assertTrue("应拒绝操作，实际为 $failure", failure is IllegalArgumentException || failure is IllegalStateException)
    }

    @Test fun allRepositoriesRejectOtherActorsWithoutChangingRecords() = runBlocking {
        val repository = analysis()
        val target = repository.analyzeAndSave(taskId, fixture.supervisor).single()
        val review = AiCorrectionRepository(db)
        val evidence = AiEvidenceRepository(db)
        for (actor in listOf(fixture.otherSupervisor, fixture.teacher, fixture.admin)) {
            denied { repository.loadTask(taskId, actor) }
            denied { repository.analyzeAndSave(taskId, actor) }
            denied { repository.observeResults(taskId, actor).first() }
            denied { repository.clearResults(taskId, actor) }
            denied { review.loadAnalysis(target.id, actor) }
            denied { review.saveCorrection(target.id, actor, "伪造修正") }
            denied { review.observeCorrections(target.id, actor).first() }
            denied { evidence.observeEvidence(target.id, actor).first() }
            denied { evidence.observeByTask(taskId, actor).first() }
        }
        assertEquals(listOf(target), db.aiAnalysisDao().getByTask(taskId))
        assertTrue(db.aiCorrectionDao().getByAnalysis(target.id).isEmpty())
    }

    @Test fun actualActorAndDerivedAcceptedAreSavedWithVerified() = runBlocking {
        val target = analysis().analyzeAndSave(taskId, fixture.supervisor).single()
        val review = AiCorrectionRepository(db)
        review.saveCorrection(target.id, fixture.supervisor, "   ")
        review.saveCorrection(target.id, fixture.supervisor, "  经核对导入不足  ")
        val records = db.aiCorrectionDao().getByAnalysis(target.id)
        assertEquals(listOf(false, true), records.map { it.accepted })
        assertEquals("经核对导入不足", records.first().correctedContent)
        assertTrue(records.all { it.reviewerId == fixture.supervisor && it.originalContent == target.content })
        assertTrue(db.aiAnalysisDao().getAnalysisById(target.id)!!.verified)
    }

    @Test fun failedVerifiedWriteRollsBackCorrectionInsert() = runBlocking {
        val target = analysis().analyzeAndSave(taskId, fixture.supervisor).single()
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_verified BEFORE UPDATE OF verified
            ON ai_analysis_results BEGIN SELECT RAISE(ABORT, 'test rollback'); END""")
        val failure = runCatching { AiCorrectionRepository(db).saveCorrection(target.id, fixture.supervisor, "修正") }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(db.aiCorrectionDao().getByAnalysis(target.id).isEmpty())
        assertFalse(db.aiAnalysisDao().getAnalysisById(target.id)!!.verified)
    }

    @Test fun reanalysisReplacesOldReviewAndDoesNotFabricateEvidence() = runBlocking {
        val repository = analysis()
        val old = repository.analyzeAndSave(taskId, fixture.supervisor).single()
        AiCorrectionRepository(db).saveCorrection(old.id, fixture.supervisor, "修正")
        db.aiEvidenceDao().insertEvidence(AiEvidence(analysisId = old.id, taskId = taskId, description = "历史说明"))
        val replacement = repository.analyzeAndSave(taskId, fixture.supervisor).single()
        assertNotEquals(old.id, replacement.id)
        assertEquals(listOf(replacement), db.aiAnalysisDao().getByTask(taskId))
        assertTrue(db.aiCorrectionDao().getByAnalysis(old.id).isEmpty())
        assertTrue(db.aiEvidenceDao().getByAnalysis(old.id).isEmpty())
        assertTrue(db.aiEvidenceDao().getByAnalysis(replacement.id).isEmpty())
    }

    @Test fun changedCaptureDuringAnalysisKeepsPreviousResults() = runBlocking {
        val previous = analysis().analyzeAndSave(taskId, fixture.supervisor).single()
        val delayed = analysis {
            db.captureDao().insertEvent(InteractionEvent(taskId = taskId, type = InteractionType.STUDENT_ANSWER,
                occurredAt = 100, offsetMs = 100))
        }
        denied { delayed.analyzeAndSave(taskId, fixture.supervisor) }
        assertEquals(listOf(previous), db.aiAnalysisDao().getByTask(taskId))
    }

    @Test fun legacyReviewStaysUnknownAndInProgressCannotAnalyze() = runBlocking {
        val target = analysis().analyzeAndSave(taskId, fixture.supervisor).single()
        db.aiCorrectionDao().insertCorrection(AiCorrection(analysisId = target.id,
            originalContent = target.content, correctedContent = "旧修正", accepted = false))
        assertNull(AiCorrectionRepository(db).observeCorrections(target.id, fixture.supervisor).first().single().reviewerId)
        db.taskDao().updateStatus(taskId, TaskStatus.IN_PROGRESS)
        denied { analysis().analyzeAndSave(taskId, fixture.supervisor) }
        assertEquals(listOf(target), db.aiAnalysisDao().getByTask(taskId))
    }

    @Test fun anotherReviewDuringSlowAnalysisKeepsTheAlreadyVerifiedResultAndNewestReview() = runBlocking {
        val original = analysis().analyzeAndSave(taskId, fixture.supervisor).single()
        val review = AiCorrectionRepository(db)
        review.saveCorrection(original.id, fixture.supervisor, "第一次人工修正")
        val verified = db.aiAnalysisDao().getAnalysisById(original.id)!!
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val slow = analysis { entered.complete(Unit); release.await() }
        val pending = async { runCatching { slow.analyzeAndSave(taskId, fixture.supervisor) } }
        entered.await()
        val newestId = review.saveCorrection(original.id, fixture.supervisor, "第二次人工补充")
        // verified 在这次审核前后均为 true，不能仅比较分析行判断版本没有变化。
        assertEquals(verified, db.aiAnalysisDao().getAnalysisById(original.id))
        release.complete(Unit)
        assertTrue(pending.await().exceptionOrNull() is IllegalArgumentException)
        assertEquals(listOf(verified), db.aiAnalysisDao().getByTask(taskId))
        assertEquals(newestId, db.aiCorrectionDao().getByAnalysis(original.id).first().id)
        assertEquals("第二次人工补充", db.aiCorrectionDao().getByAnalysis(original.id).first().correctedContent)
    }

    @Test fun changedEngagementDuringAnalysisKeepsPreviousResults() = runBlocking {
        val previous = analysis().analyzeAndSave(taskId, fixture.supervisor).single()
        val engagement = EngagementRecord(taskId = taskId, type = EngagementType.LISTENING,
            recordedAt = 100, offsetMs = 100)
        val engagementId = db.captureDao().insertEngagement(engagement)
        val delayed = AIAnalysisRepository(db, object : AIAnalyzer {
            override suspend fun analyze(taskId: Long, materials: List<Material>, events: List<InteractionEvent>,
                engagements: List<EngagementRecord>): List<AIResult> {
                assertEquals(listOf(engagement.copy(id = engagementId)), engagements)
                db.captureDao().insertEngagement(engagement.copy(type = EngagementType.DISCUSSING, recordedAt = 200))
                return listOf(AIResult(AiAnalysisType.ENGAGEMENT, "迟到的参与分析", 0.8f))
            }
        })
        denied { delayed.analyzeAndSave(taskId, fixture.supervisor) }
        assertEquals(listOf(previous), db.aiAnalysisDao().getByTask(taskId))
        assertEquals(2, db.captureDao().observeEngagements(taskId).first().size)
    }

    @Test fun evidenceFromAnotherTaskCannotReplaceResultsOrBecomeVisible() = runBlocking {
        val previous = analysis().analyzeAndSave(taskId, fixture.supervisor).single()
        val foreignTask = fixture.task(supervisorId = fixture.otherSupervisor)
        val foreignMaterial = db.captureDao().insertMaterial(Material(taskId = foreignTask,
            type = MaterialType.PPT, filePath = "tasks/$foreignTask/photo/foreign.jpg", capturedAt = 0, offsetMs = 0))
        val foreignEvent = db.captureDao().insertEvent(InteractionEvent(taskId = foreignTask,
            type = InteractionType.TEACHER_QUESTION, occurredAt = 0, offsetMs = 0))
        // 两种引用分别验证：外键都有效，但任务归属不同。
        for (reference in listOf(
            AIEvidenceRef("他人任务材料", materialId = foreignMaterial),
            AIEvidenceRef("他人任务事件", eventId = foreignEvent),
        )) {
            denied { analysis(evidences = listOf(reference)).analyzeAndSave(taskId, fixture.supervisor) }
            assertEquals(listOf(previous), db.aiAnalysisDao().getByTask(taskId))
            assertTrue(AiEvidenceRepository(db).observeByTask(taskId, fixture.supervisor).first().isEmpty())
        }
        assertNotNull(db.captureDao().getMaterial(foreignMaterial))
        assertEquals(foreignEvent, db.captureDao().observeEvents(foreignTask).first().single().id)
    }

    @Test fun failedEvidenceInsertRollsBackReplacementAndPreservesReview() = runBlocking {
        val previous = analysis().analyzeAndSave(taskId, fixture.supervisor).single()
        val reviewId = AiCorrectionRepository(db).saveCorrection(previous.id, fixture.supervisor, "人工审核保留")
        val verified = db.aiAnalysisDao().getAnalysisById(previous.id)!!
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_evidence BEFORE INSERT
            ON ai_evidences BEGIN SELECT RAISE(ABORT, 'test evidence rollback'); END""")
        val failure = runCatching {
            analysis(evidences = listOf(AIEvidenceRef("只有时间提示", startMs = 0, endMs = 1000)))
                .analyzeAndSave(taskId, fixture.supervisor)
        }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals(listOf(verified), db.aiAnalysisDao().getByTask(taskId))
        assertEquals(reviewId, db.aiCorrectionDao().getByAnalysis(previous.id).single().id)
        assertTrue(db.aiEvidenceDao().getByAnalysis(previous.id).isEmpty())
    }

    @Test fun emptyOrFailedAnalysisKeepsExistingResultReviewAndEvidence() = runBlocking {
        val initial = analysis(evidences = listOf(AIEvidenceRef("保留的课堂时间提示", startMs = 0, endMs = 1000)))
            .analyzeAndSave(taskId, fixture.supervisor).single()
        AiCorrectionRepository(db).saveCorrection(initial.id, fixture.supervisor, "已核对的人工修正")
        val previous = db.aiAnalysisDao().getByTask(taskId)
        val reviews = db.aiCorrectionDao().getByAnalysis(initial.id)
        val evidence = db.aiEvidenceDao().getByAnalysis(initial.id)
        assertEquals(1, evidence.size)
        for (throwsFailure in listOf(false, true)) {
            val failing = AIAnalysisRepository(db, object : AIAnalyzer {
                override suspend fun analyze(taskId: Long, materials: List<Material>, events: List<InteractionEvent>,
                    engagements: List<EngagementRecord>): List<AIResult> {
                    if (throwsFailure) error("测试分析服务失败")
                    return emptyList()
                }
            })
            val failure = runCatching { failing.analyzeAndSave(taskId, fixture.supervisor) }.exceptionOrNull()
            if (throwsFailure) {
                assertTrue(failure is IllegalStateException)
                assertEquals("测试分析服务失败", failure?.message)
            } else {
                assertTrue(failure is IllegalArgumentException)
                assertTrue(failure?.message.orEmpty().contains("原分析、审核与证据已保留"))
            }
            assertEquals(previous, db.aiAnalysisDao().getByTask(taskId))
            assertEquals(reviews, db.aiCorrectionDao().getByAnalysis(initial.id))
            assertEquals(evidence, db.aiEvidenceDao().getByAnalysis(initial.id))
        }
    }
}
