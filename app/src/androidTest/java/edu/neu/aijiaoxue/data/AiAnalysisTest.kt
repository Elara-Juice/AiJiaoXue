package edu.neu.aijiaoxue.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import edu.neu.aijiaoxue.data.ai.MockAIAnalyzer
import edu.neu.aijiaoxue.data.entity.AiAnalysisResult
import edu.neu.aijiaoxue.data.entity.Course
import edu.neu.aijiaoxue.data.entity.EngagementRecord
import edu.neu.aijiaoxue.data.entity.Evaluation
import edu.neu.aijiaoxue.data.entity.EvaluationIssue
import edu.neu.aijiaoxue.data.entity.InteractionEvent
import edu.neu.aijiaoxue.data.entity.Material
import edu.neu.aijiaoxue.data.entity.SupervisionTask
import edu.neu.aijiaoxue.data.entity.User
import edu.neu.aijiaoxue.data.model.AiAnalysisType
import edu.neu.aijiaoxue.data.model.EngagementType
import edu.neu.aijiaoxue.data.model.EvalDimension
import edu.neu.aijiaoxue.data.model.InteractionType
import edu.neu.aijiaoxue.data.model.MaterialType
import edu.neu.aijiaoxue.data.model.Role
import edu.neu.aijiaoxue.data.model.SupervisionType
import edu.neu.aijiaoxue.data.model.TaskStatus
import edu.neu.aijiaoxue.data.repository.AIAnalysisRepository
import edu.neu.aijiaoxue.data.repository.AiCorrectionRepository
import edu.neu.aijiaoxue.data.repository.ReviewedAnalysisProvider
import edu.neu.aijiaoxue.ui.evaluation.EvaluationDraft
import edu.neu.aijiaoxue.ui.evaluation.EvaluationRepository
import edu.neu.aijiaoxue.ui.improve.ImprovementRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Sprint 2/3 主链：真实采集引用 → 五类规则分析 → 人工审核 → 正式评价快照。 */
@RunWith(AndroidJUnit4::class)
class AiAnalysisTest {
    private lateinit var db: AppDatabase
    private lateinit var analysis: AIAnalysisRepository
    private lateinit var review: AiCorrectionRepository
    private var supervisor = 0L
    private var otherSupervisor = 0L
    private var teacher = 0L
    private var task = 0L
    private var photo = 0L
    private var question = 0L
    private var answer = 0L
    private val expectedTypes = setOf(AiAnalysisType.BOPPPS, AiAnalysisType.INTERACTION,
        AiAnalysisType.ENGAGEMENT, AiAnalysisType.VERBAL_REPETITION, AiAnalysisType.TEACHING_TRANSITION)

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
        teacher = ids[0]; supervisor = ids[1]; otherSupervisor = ids[2]
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
            Material(taskId = task, type = MaterialType.PPT, filePath = "tasks/$task/photo/a.jpg", capturedAt = 0,
                offsetMs = 60_000, note = "我们现在开始，我们现在开始。")
        )
        question = capture.insertEvent(
            InteractionEvent(taskId = task, type = InteractionType.TEACHER_QUESTION, occurredAt = 0,
                offsetMs = 120_000, note = "我们现在开始。")
        )
        answer = capture.insertEvent(InteractionEvent(taskId = task, type = InteractionType.STUDENT_ANSWER, occurredAt = 0, offsetMs = 130_000))
        capture.insertEngagement(EngagementRecord(taskId = task, type = EngagementType.LISTENING, recordedAt = 0, offsetMs = 200_000))
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun analyze_savesResultsWithEvidenceFromRealCaptureRecords() = runBlocking<Unit> {
        val results = analysis.analyzeAndSave(task, supervisor)
        assertEvidenceFromCurrentCapture(results)
        assertEquals(results, analysis.observeResults(task, supervisor).first())
    }

    @Test
    fun reanalyze_replacesPreviousResults() = runBlocking<Unit> {
        val previous = analysis.analyzeAndSave(task, supervisor)
        val previouslyReviewed = previous.single { it.type == AiAnalysisType.VERBAL_REPETITION }
        review.saveCorrection(previouslyReviewed.id, supervisor, "人工检查过的旧版本")
        val replacements = analysis.analyzeAndSave(task, supervisor)
        assertEvidenceFromCurrentCapture(replacements)
        assertEquals(replacements, analysis.observeResults(task, supervisor).first())
        assertTrue(previous.map { it.id }.toSet().intersect(replacements.map { it.id }.toSet()).isEmpty())
        previous.forEach {
            assertEquals(null, db.aiAnalysisDao().getAnalysisById(it.id))
            assertTrue(db.aiEvidenceDao().getByAnalysis(it.id).isEmpty())
            assertTrue(db.aiCorrectionDao().getByAnalysis(it.id).isEmpty())
        }
        assertTrue(replacements.none { it.verified })
    }

    @Test
    fun newTypes_publishOnlyAfterReviewAndRemainImmutableAfterReanalysis() = runBlocking<Unit> {
        val results = analysis.analyzeAndSave(task, supervisor)
        val provider = ReviewedAnalysisProvider(db)
        assertTrue(provider.forSupervisor(supervisor, task).isEmpty())
        val advanced = results.filter { it.type in setOf(AiAnalysisType.VERBAL_REPETITION, AiAnalysisType.TEACHING_TRANSITION) }
        val corrections = advanced.associate { result ->
            result.id to review.saveCorrection(result.id, supervisor, "人工核准：${result.content}")
        }
        val reviewed = provider.forSupervisor(supervisor, task)
        assertEquals(advanced.map { it.type.name }.toSet(), reviewed.map { it.type }.toSet())
        reviewed.forEach { snapshot ->
            val source = advanced.single { it.id == snapshot.analysisId }
            assertEquals(source.content, snapshot.originalContent)
            assertEquals("人工核准：${source.content}", snapshot.effectiveContent)
            assertEquals(corrections.getValue(source.id), snapshot.correctionId)
            assertEquals(supervisor, snapshot.reviewerId)
            assertTrue(snapshot.evidenceSummary.contains("已关联本任务课堂记录："))
            db.aiEvidenceDao().getByAnalysis(source.id).forEach {
                assertTrue(snapshot.evidenceSummary.contains(it.description))
            }
        }
        EvaluationRepository(db).save(supervisor, task, EvaluationDraft(
            Evaluation(taskId = task, issueDeclaration = edu.neu.aijiaoxue.data.model.IssueDeclaration.HAS_ISSUES,
                contentScore = 4, designScore = 4, interactionScore = 4, engagementScore = 4,
                overallOpinion = "根据人工审核的证据关注语言表达和教学组织"),
            listOf(EvaluationIssue(evaluationId = 0, description = "需进一步观察课堂环节安排", dimension = EvalDimension.DESIGN)),
        ), submit = true)
        val teacherRecord = ImprovementRepository(db).load(teacher, task)
        val published = teacherRecord.reviewedAnalyses
        assertTrue(teacherRecord.reviewPublished)
        assertEquals(advanced.map { it.type.name }.toSet(), published.map { it.type }.toSet())
        published.forEach { snapshot ->
            assertTrue(snapshot.id > 0 && snapshot.publicationId > 0)
            val before = reviewed.single { it.analysisId == snapshot.analysisId }
            assertEquals(before.copy(id = snapshot.id, publicationId = snapshot.publicationId), snapshot)
        }

        // 重分析会级联删除当前分析/证据/审核，但不能改变已随正式评价发布给教师的依据。
        analysis.analyzeAndSave(task, supervisor)
        assertTrue(provider.forSupervisor(supervisor, task).isEmpty())
        assertEquals(published, ImprovementRepository(db).load(teacher, task).reviewedAnalyses)
        analysis.clearResults(task, supervisor)
        assertTrue(db.aiAnalysisDao().getByTask(task).isEmpty())
        assertEquals(published, ImprovementRepository(db).load(teacher, task).reviewedAnalyses)
    }

    @Test
    fun review_savesCorrectionAndMarksVerified() = runBlocking<Unit> {
        val target = analysis.analyzeAndSave(task, supervisor).first()
        review.saveCorrection(target.id, supervisor, "  ")
        review.saveCorrection(target.id, supervisor, "导入环节偏短")

        val records = review.observeCorrections(target.id, supervisor).first()
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
        assertTrue(analysis.observeResults(task, supervisor).first().isEmpty())
    }

    @Test
    fun deletingMaterial_keepsEvidenceButDropsLink() = runBlocking<Unit> {
        analysis.analyzeAndSave(task, supervisor)
        db.captureDao().deleteMaterial(db.captureDao().getMaterial(photo)!!)
        val evidence = db.aiEvidenceDao().observeByTask(task).first().first { it.description.startsWith("PPT") }
        assertEquals(null, evidence.materialId)
    }

    private suspend fun assertEvidenceFromCurrentCapture(results: List<AiAnalysisResult>) {
        assertEquals(expectedTypes, results.map { it.type }.toSet())
        assertEquals(expectedTypes.size, results.size)
        val materials = db.captureDao().getMaterials(task).associateBy { it.id }
        val events = db.captureDao().observeEvents(task).first().associateBy { it.id }
        val engagements = db.captureDao().observeEngagements(task).first()
        val evidenceByAnalysis = db.aiEvidenceDao().observeByTask(task).first().groupBy { it.analysisId }
        assertEquals(results.map { it.id }.toSet(), evidenceByAnalysis.keys)
        results.forEach { result ->
            val evidence = evidenceByAnalysis.getValue(result.id)
            assertTrue("${result.type} 应有本次输入的证据", evidence.isNotEmpty())
            evidence.forEach { row ->
                assertEquals(task, row.taskId)
                assertEquals(result.id, row.analysisId)
                assertTrue(row.description.isNotBlank())
                row.materialId?.let { id ->
                    val source = materials.getValue(id)
                    assertEquals(source.offsetMs, row.startMs)
                    assertEquals(source.offsetMs, row.endMs)
                }
                row.eventId?.let { id ->
                    val source = events.getValue(id)
                    assertEquals(source.offsetMs, row.startMs)
                    assertEquals(source.offsetMs, row.endMs)
                }
            }
            when (result.type) {
                AiAnalysisType.BOPPPS -> {
                    assertEquals(setOf(photo), evidence.mapNotNull { it.materialId }.toSet())
                    assertTrue(evidence.all { it.eventId == null })
                }
                AiAnalysisType.INTERACTION -> {
                    assertTrue(result.content.contains("2 次"))
                    assertEquals(setOf(question, answer), evidence.mapNotNull { it.eventId }.toSet())
                    assertTrue(evidence.all { it.materialId == null })
                }
                AiAnalysisType.ENGAGEMENT -> {
                    // 现有参与状态证据只存时间提示，不能伪装为材料或互动事件外键。
                    assertTrue(evidence.all { it.materialId == null && it.eventId == null })
                    assertEquals(engagements.map { it.offsetMs }.toSet(), evidence.mapNotNull { it.startMs }.toSet())
                    evidence.forEach { row ->
                        assertTrue(engagements.any { it.offsetMs == row.startMs && row.description.startsWith(it.type.label) })
                    }
                }
                AiAnalysisType.VERBAL_REPETITION -> {
                    assertTrue(result.content.contains("我们现在开始"))
                    assertEquals(setOf(photo), evidence.mapNotNull { it.materialId }.toSet())
                    assertEquals(setOf(question), evidence.mapNotNull { it.eventId }.toSet())
                    evidence.forEach { row ->
                        val sourceText = row.materialId?.let { materials.getValue(it).note }
                            ?: row.eventId?.let { events.getValue(it).note }
                        val location = Regex("中的“(.+?)”（文本字符位置 (\\d+)–(\\d+)）").find(row.description)
                        assertTrue("重复表达证据应标出真实文本位置", sourceText != null && location != null)
                        val match = requireNotNull(location)
                        assertEquals(match.groupValues[1], requireNotNull(sourceText).substring(
                            match.groupValues[2].toInt() - 1, match.groupValues[3].toInt()))
                    }
                }
                AiAnalysisType.TEACHING_TRANSITION -> {
                    assertEquals(setOf(photo), evidence.mapNotNull { it.materialId }.toSet())
                    assertEquals(setOf(question, answer), evidence.mapNotNull { it.eventId }.toSet())
                    assertTrue(evidence.all { (it.materialId != null) xor (it.eventId != null) })
                    assertEquals(60_000L, result.startMs)
                    assertEquals(130_000L, result.endMs)
                }
                AiAnalysisType.CONTENT, AiAnalysisType.SYLLABUS_ALIGNMENT,
                AiAnalysisType.GOAL_ACTIVITY, AiAnalysisType.MATERIAL_SUPPORT ->
                    throw AssertionError("旧 Mock 不应生成新类型 ${result.type}")
            }
        }
    }

    private suspend fun expectDenied(block: suspend () -> Unit) {
        val failure = runCatching { block() }.exceptionOrNull()
        assertTrue("应当拒绝，实际为 $failure", failure is IllegalArgumentException || failure is IllegalStateException)
    }
}
