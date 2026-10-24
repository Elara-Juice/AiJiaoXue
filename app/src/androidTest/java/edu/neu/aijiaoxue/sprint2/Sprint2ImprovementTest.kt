package edu.neu.aijiaoxue.sprint2

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import edu.neu.aijiaoxue.Sprint1Fixture
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.entity.*
import edu.neu.aijiaoxue.data.model.*
import edu.neu.aijiaoxue.data.repository.AiCorrectionRepository
import edu.neu.aijiaoxue.data.repository.ReviewedAnalysisProvider
import edu.neu.aijiaoxue.ui.improve.ImprovementRepository
import edu.neu.aijiaoxue.ui.improve.ImprovementSuggestionGenerator
import edu.neu.aijiaoxue.ui.improve.RuleBasedImprovementSuggestionGenerator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 固定课堂结论只用于接入验证；这些测试不证明真实模型分析或建议生成效果。 */
@RunWith(AndroidJUnit4::class)
class Sprint2ImprovementTest {
    private lateinit var db: AppDatabase
    private lateinit var f: Sprint1Fixture
    private val reviewedText = "参与式学习环节缺少小组讨论，需增加学生解释思路的机会。"

    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        f = Sprint1Fixture(db)
        f.seed()
    }
    @After fun close() { db.close() }

    private suspend fun denied(action: suspend () -> Unit) {
        val error = runCatching { action() }.exceptionOrNull()
        assertTrue("Expected a business denial, got $error", error is IllegalArgumentException || error is IllegalStateException)
    }
    private suspend fun analysis(taskId: Long, type: String = "BOPPPS", content: String = "原始分析：课堂互动不足", verified: Boolean = false) =
        db.aiAnalysisDao().insertAnalysis(AiAnalysisResult(taskId = taskId, type = type, content = content, confidence = 0.75f, verified = verified))

    private suspend fun reviewedTask(courseId: Long = f.course, supervisorId: Long = f.supervisor): Long {
        val task = f.task(courseId, supervisorId)
        val analysis = analysis(task, type = "INTERACTION")
        AiCorrectionRepository(db).saveCorrection(analysis, supervisorId, reviewedText)
        f.evaluations.save(supervisorId, task, f.draft(task), submit = true)
        return task
    }

    @Test fun correctedFalseIsValidAndPublishedTextIsTheReviewedVersion() = runBlocking {
        val task = f.task()
        val analysis = analysis(task)
        val correctionId = AiCorrectionRepository(db).saveCorrection(analysis, f.supervisor, reviewedText)
        val correction = db.aiCorrectionDao().observeCorrections(analysis).first().single()
        assertFalse(correction.accepted)
        assertEquals(f.supervisor, correction.reviewerId)
        val reference = ReviewedAnalysisProvider(db).forSupervisor(f.supervisor, task).single()
        assertEquals(reviewedText, reference.effectiveContent)
        assertEquals(correctionId, reference.correctionId)
        f.evaluations.save(f.supervisor, task, f.draft(task), submit = true)
        val record = f.improvement.load(f.teacher, task)
        assertTrue(record.reviewPublished)
        assertEquals(reviewedText, record.reviewedAnalyses.single().effectiveContent)
        assertEquals(reference.versionKey, record.reviewedAnalyses.single().versionKey)
        assertEquals(reviewedText, f.evaluations.load(f.supervisor, task).reviewedAnalyses.single().effectiveContent)
    }

    @Test fun unreviewedAndLegacyAnonymousCorrectionsAreNotPublished() = runBlocking {
        val task = f.task()
        analysis(task, verified = false)
        val legacy = analysis(task, content = "旧版没有实际审核人", verified = true)
        db.aiCorrectionDao().insertCorrection(AiCorrection(analysisId = legacy, originalContent = "旧版没有实际审核人",
            correctedContent = "旧修正不能由任务负责人倒填审核人", accepted = false))
        assertTrue(ReviewedAnalysisProvider(db).forSupervisor(f.supervisor, task).isEmpty())
        f.evaluations.save(f.supervisor, task, f.draft(task), submit = true)
        f.focus(task)
        val record = f.improvement.refreshSuggestions(f.teacher, task)
        assertTrue(record.reviewPublished)
        assertTrue(record.reviewedAnalyses.isEmpty())
        assertTrue(record.suggestions.isEmpty())
        assertEquals("每节课增加一次讨论", record.measures.single().content)
    }

    @Test fun laterReviewCannotBackfillAnAlreadyPublishedEmptyVersion() = runBlocking {
        val task = f.task()
        val analysis = analysis(task)
        f.evaluations.save(f.supervisor, task, f.draft(task), submit = true)
        AiCorrectionRepository(db).saveCorrection(analysis, f.supervisor, reviewedText)
        f.focus(task)
        val record = f.improvement.refreshSuggestions(f.teacher, task)
        assertTrue(record.reviewPublished)
        assertTrue(record.reviewedAnalyses.isEmpty())
        assertTrue(record.suggestions.isEmpty())
    }

    @Test fun laterCorrectionAndReanalysisDoNotChangePublishedSnapshotOrConfirmedMeasure() = runBlocking {
        val task = reviewedTask()
        f.focus(task)
        val candidates = f.improvement.refreshSuggestions(f.teacher, task)
        val suggestion = candidates.suggestions.first()
        val frozen = candidates.reviewedAnalyses.single()
        f.improvement.adoptSuggestion(f.teacher, task, suggestion.id)
        var record = f.improvement.load(f.teacher, task)
        f.improvement.saveMeasures(f.teacher, task, record.measures.map { it.copy(dueDate = "2026-11-01") }, confirm = true)
        AiCorrectionRepository(db).saveCorrection(frozen.analysisId, f.supervisor, "新修正：与已发布版本不同")
        db.aiCorrectionDao().deleteByAnalysis(frozen.analysisId)
        db.aiEvidenceDao().deleteByAnalysis(frozen.analysisId)
        db.aiAnalysisDao().deleteByTask(task)
        analysis(task, content = "重新分析得到新结论")
        record = f.improvement.load(f.teacher, task)
        assertEquals(frozen, record.reviewedAnalyses.single())
        assertEquals(suggestion, record.suggestions.single { it.id == suggestion.id })
        assertEquals(suggestion.content, record.measures.single { it.suggestionId == suggestion.id }.content)
        assertEquals(PlanStatus.CONFIRMED, record.plan!!.status)
        assertEquals(frozen.effectiveContent, f.evaluations.load(f.supervisor, task, history = true).reviewedAnalyses.single().effectiveContent)
    }

    @Test fun teacherAccessRequiresOwnPublishedFeedbackAndTaskScopedSuggestionIds() = runBlocking {
        val pending = f.task()
        denied { f.improvement.load(f.teacher, pending) }
        denied { f.improvement.refreshSuggestions(f.teacher, pending) }
        val task = reviewedTask()
        f.focus(task)
        val first = f.improvement.refreshSuggestions(f.teacher, task).suggestions.first()
        denied { f.improvement.load(f.otherTeacher, task) }
        denied { f.improvement.refreshSuggestions(f.otherTeacher, task) }
        denied { f.improvement.adoptSuggestion(f.otherTeacher, task, first.id) }
        denied { f.improvement.dismissSuggestion(f.otherTeacher, task, first.id) }
        denied { f.improvement.load(f.supervisor, task) }
        val ownSecondTask = reviewedTask()
        f.focus(ownSecondTask)
        denied { f.improvement.adoptSuggestion(f.teacher, ownSecondTask, first.id) }
        denied { f.improvement.dismissSuggestion(f.teacher, ownSecondTask, first.id) }
        assertTrue(f.improvement.load(f.teacher, task).measures.none { it.suggestionId != null })
    }

    @Test fun perItemSuggestionsAdoptIdempotentlyAndCannotCrossItemLinks() = runBlocking {
        val task = reviewedTask()
        f.focus(task, count = 2)
        val initial = f.improvement.refreshSuggestions(f.teacher, task)
        assertEquals(initial.items.map { it.id }.toSet(), initial.suggestions.map { it.itemId }.toSet())
        val first = initial.suggestions.first()
        repeat(3) { f.improvement.adoptSuggestion(f.teacher, task, first.id) }
        val after = f.improvement.refreshSuggestions(f.teacher, task)
        assertEquals(initial.suggestions.map { it.id }, after.suggestions.map { it.id })
        val adopted = after.measures.single { it.suggestionId == first.id }
        assertEquals(first.itemId, adopted.itemId)
        assertEquals(first.content, adopted.content)
        assertEquals(MeasureSource.ADOPTED, adopted.source)
        val otherItem = after.items.single { it.id != first.itemId }
        denied { f.improvement.saveMeasures(f.teacher, task, after.measures.map {
            if (it.id == adopted.id) it.copy(itemId = otherItem.id) else it
        }) }
        assertEquals(first.itemId, f.improvement.load(f.teacher, task).measures.single { it.suggestionId == first.id }.itemId)
    }

    @Test fun editingSuggestionUsesItsOwnOriginalAndRepeatAdoptionPreservesHumanEdit() = runBlocking {
        val task = reviewedTask()
        f.focus(task)
        val candidate = f.improvement.refreshSuggestions(f.teacher, task).suggestions.first()
        var record = f.improvement.adoptSuggestion(f.teacher, task, candidate.id, "教师按班级情况改为两分钟同伴解释")
        assertEquals(MeasureSource.MODIFIED, record.measures.single { it.suggestionId == candidate.id }.source)
        record = f.improvement.adoptSuggestion(f.teacher, task, candidate.id)
        assertEquals("教师按班级情况改为两分钟同伴解释", record.measures.single { it.suggestionId == candidate.id }.content)
        // 恢复该条原文，必须判为采纳；不能与整段督导suggestions比较。
        f.improvement.saveMeasures(f.teacher, task, record.measures.map {
            if (it.suggestionId == candidate.id) it.copy(content = candidate.content) else it
        })
        val restored = f.improvement.load(f.teacher, task).measures.single { it.suggestionId == candidate.id }
        assertEquals(MeasureSource.ADOPTED, restored.source)
        assertEquals(candidate.content, restored.content)
    }

    @Test fun dismissalSurvivesRefreshAndExplicitRestoreDoesNotCreateMeasure() = runBlocking {
        val task = reviewedTask()
        f.focus(task)
        val candidate = f.improvement.refreshSuggestions(f.teacher, task).suggestions.first()
        f.improvement.dismissSuggestion(f.teacher, task, candidate.id)
        val refreshed = f.improvement.refreshSuggestions(f.teacher, task)
        assertNotNull(refreshed.suggestions.single { it.id == candidate.id }.dismissedAt)
        assertTrue(refreshed.measures.none { it.suggestionId == candidate.id })
        denied { f.improvement.adoptSuggestion(f.teacher, task, candidate.id) }
        val restored = f.improvement.dismissSuggestion(f.teacher, task, candidate.id, dismissed = false)
        assertNull(restored.suggestions.single { it.id == candidate.id }.dismissedAt)
        assertTrue(restored.measures.none { it.suggestionId == candidate.id })
        f.improvement.adoptSuggestion(f.teacher, task, candidate.id)
        denied { f.improvement.dismissSuggestion(f.teacher, task, candidate.id) }
    }

    @Test fun confirmationLocksSuggestionsAndMeasuresButOwnedTodoStatusStillWorks() = runBlocking {
        val task = reviewedTask()
        f.focus(task)
        val candidate = f.improvement.refreshSuggestions(f.teacher, task).suggestions.first()
        val adopted = f.improvement.adoptSuggestion(f.teacher, task, candidate.id)
        denied { f.improvement.saveMeasures(f.teacher, task, adopted.measures, confirm = true) }
        f.improvement.saveMeasures(f.teacher, task, adopted.measures.map { it.copy(dueDate = "2026-11-01") }, confirm = true)
        val confirmed = f.improvement.load(f.teacher, task)
        val measure = confirmed.measures.single { it.suggestionId == candidate.id }
        denied { f.improvement.adoptSuggestion(f.teacher, task, candidate.id) }
        denied { f.improvement.dismissSuggestion(f.teacher, task, candidate.id) }
        denied { f.improvement.saveMeasures(f.teacher, task, confirmed.measures) }
        denied { f.improvement.updateStatus(f.otherTeacher, measure.id, MeasureStatus.DONE) }
        f.improvement.updateStatus(f.teacher, measure.id, MeasureStatus.DONE)
        assertEquals(MeasureStatus.DONE, f.improvement.todos(f.teacher, false, MeasureStatus.DONE).first().single { it.measureId == measure.id }.status)
        assertTrue(f.improvement.todos(f.otherTeacher, false, null).first().isEmpty())
    }

    @Test fun removingFocusDropsOnlyItsCandidatesAndRejectsLateAdoption() = runBlocking {
        val task = reviewedTask()
        f.focus(task, count = 2)
        val initial = f.improvement.refreshSuggestions(f.teacher, task)
        val removed = initial.items.first()
        val stale = initial.suggestions.first { it.itemId == removed.id }
        f.improvement.saveFocus(f.teacher, task, initial.items.filter { it.id != removed.id }, allowRemoval = true)
        denied { f.improvement.adoptSuggestion(f.teacher, task, stale.id) }
        val after = f.improvement.refreshSuggestions(f.teacher, task)
        assertTrue(after.suggestions.none { it.itemId == removed.id })
        assertEquals(initial.suggestions.filter { it.itemId != removed.id }.map { it.id }, after.suggestions.map { it.id })
    }

    @Test fun noAiKeepsOriginalManualEvaluationFocusPlanAndTodoChain() = runBlocking {
        val task = f.task()
        val draft = f.draft(task, suggestions = null)
        denied { f.evaluations.save(f.supervisor, task, draft.copy(evaluation = draft.evaluation.copy(contentScore = null)), submit = true) }
        f.evaluations.save(f.supervisor, task, draft, submit = true)
        val focus = f.focus(task)
        assertEquals(81, focus.evaluation.totalScore)
        assertTrue(f.improvement.refreshSuggestions(f.teacher, task).suggestions.isEmpty())
        val ownMeasure = ImprovementMeasure(planId = focus.plan!!.id, itemId = focus.items.single().id,
            content = "教师自主安排课堂练习", source = MeasureSource.ADDED, dueDate = "2026-11-01")
        f.improvement.saveMeasures(f.teacher, task, listOf(ownMeasure), confirm = true)
        val saved = f.improvement.load(f.teacher, task)
        assertEquals(MeasureSource.ADDED, saved.measures.single().source)
        assertNull(saved.measures.single().suggestionId)
        assertEquals(1, f.improvement.todos(f.teacher, true, null).first().size)
        denied { f.evaluations.save(f.supervisor, task, draft) }
    }

    @Test fun slowGenerationCannotWriteAfterPlanConfirmation() = runBlocking {
        val task = reviewedTask()
        val focused = f.focus(task)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val delayed = ImprovementRepository(db, object : ImprovementSuggestionGenerator {
            override suspend fun generate(item: ImprovementItem, dimension: EvalDimension?, reviews: List<ReviewedAnalysisSnapshot>): List<ImprovementSuggestion> {
                entered.complete(Unit)
                release.await()
                return RuleBasedImprovementSuggestionGenerator().generate(item, dimension, reviews)
            }
        })
        val request = async { runCatching { delayed.refreshSuggestions(f.teacher, task) } }
        entered.await()
        f.improvement.saveMeasures(f.teacher, task, listOf(ImprovementMeasure(planId = focused.plan!!.id,
            itemId = focused.items.single().id, content = "教师已决定的措施", source = MeasureSource.ADDED,
            dueDate = "2026-11-01")), confirm = true)
        release.complete(Unit)
        assertTrue(request.await().exceptionOrNull() is IllegalArgumentException)
        val after = f.improvement.load(f.teacher, task)
        assertTrue(after.suggestions.isEmpty())
        assertEquals("教师已决定的措施", after.measures.single().content)
        assertEquals(PlanStatus.CONFIRMED, after.plan!!.status)
    }

    @Test fun slowGenerationCannotWriteAfterFocusChanges() = runBlocking {
        val task = reviewedTask()
        val focused = f.focus(task, count = 2)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val delayed = ImprovementRepository(db, object : ImprovementSuggestionGenerator {
            override suspend fun generate(item: ImprovementItem, dimension: EvalDimension?, reviews: List<ReviewedAnalysisSnapshot>): List<ImprovementSuggestion> {
                entered.complete(Unit)
                release.await()
                return RuleBasedImprovementSuggestionGenerator().generate(item, dimension, reviews)
            }
        })
        val request = async { runCatching { delayed.refreshSuggestions(f.teacher, task) } }
        entered.await()
        f.improvement.saveFocus(f.teacher, task, focused.items.take(1), allowRemoval = true)
        release.complete(Unit)
        assertTrue(request.await().exceptionOrNull() is IllegalArgumentException)
        assertTrue(f.improvement.load(f.teacher, task).suggestions.isEmpty())
    }

    @Test fun suggestionFailureLeavesManualPlanningAvailable() = runBlocking {
        val task = reviewedTask()
        val focused = f.focus(task)
        val failing = ImprovementRepository(db, object : ImprovementSuggestionGenerator {
            override suspend fun generate(item: ImprovementItem, dimension: EvalDimension?, reviews: List<ReviewedAnalysisSnapshot>): List<ImprovementSuggestion> =
                throw IllegalStateException("测试上游暂不可用")
        })
        denied { failing.refreshSuggestions(f.teacher, task) }
        f.improvement.saveMeasures(f.teacher, task, listOf(ImprovementMeasure(planId = focused.plan!!.id,
            itemId = focused.items.single().id, content = "教师自行设计分组讨论", source = MeasureSource.ADDED,
            dueDate = "2026-11-01")), confirm = true)
        assertEquals(PlanStatus.CONFIRMED, f.improvement.load(f.teacher, task).plan!!.status)
    }

    @Test fun sourceAndAdoptionSurviveDatabaseCloseAndReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "yzj-s2-persistence-${System.nanoTime()}.db"
        var disk = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        try {
            val fixture = Sprint1Fixture(disk)
            fixture.seed()
            val task = fixture.task()
            val analysis = disk.aiAnalysisDao().insertAnalysis(AiAnalysisResult(taskId = task, type = "INTERACTION",
                content = "磁盘测试原结论", confidence = 0.8f))
            AiCorrectionRepository(disk).saveCorrection(analysis, fixture.supervisor, reviewedText)
            fixture.evaluations.save(fixture.supervisor, task, fixture.draft(task), submit = true)
            fixture.focus(task)
            val candidate = fixture.improvement.refreshSuggestions(fixture.teacher, task).suggestions.single()
            val adopted = fixture.improvement.adoptSuggestion(fixture.teacher, task, candidate.id)
            fixture.improvement.saveMeasures(fixture.teacher, task, adopted.measures.map { it.copy(dueDate = "2026-11-01") }, confirm = true)
            val teacher = fixture.teacher
            val before = fixture.improvement.load(teacher, task)
            disk.close()
            disk = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            val after = ImprovementRepository(disk).load(teacher, task)
            assertEquals(before.plan, after.plan)
            assertEquals(before.measures, after.measures)
            assertEquals(before.suggestions, after.suggestions)
            assertEquals(before.reviewedAnalyses, after.reviewedAnalyses)
            assertEquals(candidate.content, after.measures.single().content)
        } finally {
            disk.close()
            context.deleteDatabase(name)
        }
    }
}
