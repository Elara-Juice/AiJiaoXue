package edu.neu.aijiaoxue.sprint2

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import edu.neu.aijiaoxue.Sprint1Fixture
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.ai.AIAnalyzer
import edu.neu.aijiaoxue.data.ai.AIResult
import edu.neu.aijiaoxue.data.entity.AiCorrection
import edu.neu.aijiaoxue.data.entity.AiEvidence
import edu.neu.aijiaoxue.data.entity.InteractionEvent
import edu.neu.aijiaoxue.data.entity.Material
import edu.neu.aijiaoxue.data.model.InteractionType
import edu.neu.aijiaoxue.data.model.TaskStatus
import edu.neu.aijiaoxue.data.repository.AIAnalysisRepository
import edu.neu.aijiaoxue.data.repository.AiCorrectionRepository
import edu.neu.aijiaoxue.data.repository.AiEvidenceRepository
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

    private fun analysis(beforeResult: suspend () -> Unit = {}) = AIAnalysisRepository(db, object : AIAnalyzer {
        override suspend fun analyze(taskId: Long, materials: List<Material>, events: List<InteractionEvent>): List<AIResult> {
            beforeResult()
            return listOf(AIResult("BOPPPS", "固定测试结论", 0.8f, 0, 1000))
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
}
