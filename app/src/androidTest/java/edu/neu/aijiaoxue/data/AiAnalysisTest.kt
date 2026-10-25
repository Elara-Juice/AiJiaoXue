package edu.neu.aijiaoxue.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import edu.neu.aijiaoxue.data.ai.MockAIAnalyzer
import edu.neu.aijiaoxue.data.entity.Course
import edu.neu.aijiaoxue.data.entity.EngagementRecord
import edu.neu.aijiaoxue.data.entity.InteractionEvent
import edu.neu.aijiaoxue.data.entity.Material
import edu.neu.aijiaoxue.data.entity.SupervisionTask
import edu.neu.aijiaoxue.data.entity.User
import edu.neu.aijiaoxue.data.model.AiAnalysisType
import edu.neu.aijiaoxue.data.model.EngagementType
import edu.neu.aijiaoxue.data.model.InteractionType
import edu.neu.aijiaoxue.data.model.MaterialType
import edu.neu.aijiaoxue.data.model.Role
import edu.neu.aijiaoxue.data.model.SupervisionType
import edu.neu.aijiaoxue.data.model.TaskStatus
import edu.neu.aijiaoxue.data.repository.AIAnalysisRepository
import edu.neu.aijiaoxue.data.repository.AiCorrectionRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Sprint 2 主链：课堂采集 → AI 分析 → 证据追溯 → 人工审核 → 审核记录保存。 */
@RunWith(AndroidJUnit4::class)
class AiAnalysisTest {
    private lateinit var db: AppDatabase
    private lateinit var analysis: AIAnalysisRepository
    private lateinit var review: AiCorrectionRepository
    private var supervisor = 0L
    private var otherSupervisor = 0L
    private var task = 0L
    private var photo = 0L
    private var question = 0L

    @Before
    fun setUp() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        analysis = AIAnalysisRepository(db, MockAIAnalyzer())
        review = AiCorrectionRepository(db)
        val ids = db.userDao().insertAll(
            listOf(
                User(account = "t1", passwordHash = "x", name = "张老师", role = Role.TEACHER),
                User(account = "s1", passwordHash = "x", name = "王督导", role = Role.SUPERVISOR),
                User(account = "s2", passwordHash = "x", name = "赵督导", role = Role.SUPERVISOR),
            )
        )
        supervisor = ids[1]; otherSupervisor = ids[2]
        val course = db.courseDao().insertAll(
            listOf(Course(code = "C01", name = "数据结构", teacherId = ids[0], className = null, schedule = null, location = null))
        )[0]
        task = db.taskDao().insert(
            SupervisionTask(
                code = "DD-AI-1", courseId = course, supervisorId = supervisor, supervisionDate = "2026-10-08",
                period = "3-4", type = SupervisionType.REGULAR, status = TaskStatus.PENDING_EVALUATION,
            )
        )
        val capture = db.captureDao()
        photo = capture.insertMaterial(
            Material(taskId = task, type = MaterialType.PPT, filePath = "tasks/$task/photo/a.jpg", capturedAt = 0, offsetMs = 60_000)
        )
        question = capture.insertEvent(
            InteractionEvent(taskId = task, type = InteractionType.TEACHER_QUESTION, occurredAt = 0, offsetMs = 120_000)
        )
        capture.insertEvent(InteractionEvent(taskId = task, type = InteractionType.STUDENT_ANSWER, occurredAt = 0, offsetMs = 130_000))
        capture.insertEngagement(EngagementRecord(taskId = task, type = EngagementType.LISTENING, recordedAt = 0, offsetMs = 200_000))
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun analyze_savesResultsWithEvidenceFromRealCaptureRecords() = runBlocking<Unit> {
        val results = analysis.analyzeAndSave(task, supervisor)
        assertEquals(AiAnalysisType.entries.toList(), results.map { it.type })

        val evidences = db.aiEvidenceDao().observeByTask(task).first().groupBy { it.analysisId }
        val phase = results.first { it.type == AiAnalysisType.BOPPPS }
        assertEquals(listOf(photo), evidences[phase.id]!!.map { it.materialId })
        val interaction = results.first { it.type == AiAnalysisType.INTERACTION }
        assertTrue(interaction.content.contains("2 次"))
        assertEquals(question, evidences[interaction.id]!!.first().eventId)
    }

    @Test
    fun reanalyze_replacesPreviousResults() = runBlocking<Unit> {
        analysis.analyzeAndSave(task, supervisor)
        analysis.analyzeAndSave(task, supervisor)
        assertEquals(3, analysis.observeResults(task).first().size)
        assertEquals(4, db.aiEvidenceDao().observeByTask(task).first().size)
    }

    @Test
    fun review_savesCorrectionAndMarksVerified() = runBlocking<Unit> {
        val target = analysis.analyzeAndSave(task, supervisor).first()
        review.saveCorrection(target.id, supervisor, "  ")
        review.saveCorrection(target.id, supervisor, "导入环节偏短")

        val records = review.observeCorrections(target.id).first()
        assertEquals(listOf("导入环节偏短", target.content), records.map { it.correctedContent })
        assertEquals(listOf(false, true), records.map { it.accepted })
        assertTrue(records.all { it.reviewerId == supervisor })
        assertTrue(db.aiAnalysisDao().getAnalysisById(target.id)!!.verified)
    }

    @Test
    fun otherSupervisor_cannotAnalyzeOrReview() = runBlocking<Unit> {
        expectDenied { analysis.analyzeAndSave(task, otherSupervisor) }
        val target = analysis.analyzeAndSave(task, supervisor).first()
        expectDenied { review.saveCorrection(target.id, otherSupervisor, "x") }
        assertFalse(db.aiAnalysisDao().getAnalysisById(target.id)!!.verified)
    }

    @Test
    fun inProgressTask_cannotBeAnalyzed() = runBlocking<Unit> {
        db.taskDao().updateStatus(task, TaskStatus.IN_PROGRESS)
        expectDenied { analysis.analyzeAndSave(task, supervisor) }
        assertTrue(analysis.observeResults(task).first().isEmpty())
    }

    @Test
    fun deletingMaterial_keepsEvidenceButDropsLink() = runBlocking<Unit> {
        analysis.analyzeAndSave(task, supervisor)
        db.captureDao().deleteMaterial(db.captureDao().getMaterial(photo)!!)
        val evidence = db.aiEvidenceDao().observeByTask(task).first().first { it.description.startsWith("PPT") }
        assertEquals(null, evidence.materialId)
    }

    private suspend fun expectDenied(block: suspend () -> Unit) {
        try {
            block()
            fail("应当拒绝")
        } catch (_: IllegalStateException) {
        }
    }
}
