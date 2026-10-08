package edu.neu.aijiaoxue

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.entity.*
import edu.neu.aijiaoxue.data.model.*
import edu.neu.aijiaoxue.ui.evaluation.EvaluationRepository
import edu.neu.aijiaoxue.ui.improve.ImprovementRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Sprint1RepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var f: Sprint1Fixture
    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        f = Sprint1Fixture(db); f.seed()
    }
    @After fun tearDown() { db.close() }
    private suspend fun denied(action: suspend () -> Unit) {
        val failure = runCatching { action() }.exceptionOrNull()
        assertTrue("Expected a business denial, got $failure", failure is IllegalArgumentException || failure is IllegalStateException)
    }

    @Test fun firstDraftRejectsOtherSupervisorAndTeacher() = runBlocking {
        val task = f.task()
        denied { f.evaluations.save(f.otherSupervisor, task, f.draft(task)) }
        denied { f.evaluations.save(f.teacher, task, f.draft(task)) }
        denied { f.evaluations.load(f.otherSupervisor, task) }
        assertNull(db.evaluationDao().getByTask(task))
    }
    @Test fun scoringAndIssuesPersistAndDoNotDuplicateDuringRapidEdits() = runBlocking {
        val task = f.task()
        repeat(12) { index -> f.evaluations.save(f.supervisor, task, f.draft(task).let {
            it.copy(evaluation = it.evaluation.copy(overallOpinion = "最新编辑 $index"))
        }) }
        val read = f.evaluations.load(f.supervisor, task)
        assertEquals(81, read.draft.evaluation.totalScore)
        assertEquals(EvalGrade.GOOD, read.draft.evaluation.grade)
        assertEquals("最新编辑 11", read.draft.evaluation.overallOpinion)
        assertEquals(2, read.draft.issues.size)
        assertEquals("概念准确", read.draft.evaluation.contentComment)
    }
    @Test fun invalidScoreAndMissingRequirementsDoNotPartiallySubmit() = runBlocking {
        val task = f.task()
        val valid = f.draft(task)
        for (score in listOf(0, 6)) denied { f.evaluations.save(f.supervisor, task, valid.copy(evaluation = valid.evaluation.copy(contentScore = score))) }
        denied { f.evaluations.save(f.supervisor, task, valid.copy(issues = emptyList()), submit = true) }
        denied { f.evaluations.save(f.supervisor, task, valid.copy(evaluation = valid.evaluation.copy(overallOpinion = " ")), submit = true) }
        denied { f.evaluations.save(f.supervisor, task, valid.copy(evaluation = valid.evaluation.copy(designScore = null)), submit = true) }
        assertEquals(TaskStatus.PENDING_EVALUATION, db.taskDao().getById(task)!!.status)
        assertNull(db.evaluationDao().getByTask(task))
    }
    @Test fun submissionCompletesArrangementAndLateDraftCannotOverwrite() = runBlocking {
        val arrangement = db.arrangementDao().insert(Arrangement(courseId = f.course, createdBy = f.admin, isKeyFocus = true, status = ArrangementStatus.CLAIMED))
        val task = f.task(arrangementId = arrangement)
        val draft = f.draft(task)
        f.evaluations.save(f.supervisor, task, draft, submit = true)
        denied { f.evaluations.save(f.supervisor, task, draft.copy(evaluation = draft.evaluation.copy(overallOpinion = "迟到的草稿"))) }
        denied { f.evaluations.save(f.supervisor, task, draft, submit = true) }
        assertEquals(TaskStatus.COMPLETED, db.taskDao().getById(task)!!.status)
        assertEquals(ArrangementStatus.DONE, db.arrangementDao().observeAll().first().single { it.id == arrangement }.status)
        assertEquals(EvalStatus.SUBMITTED, db.evaluationDao().getByTask(task)!!.status)
        assertEquals(draft.evaluation.overallOpinion, db.evaluationDao().getByTask(task)!!.overallOpinion)
    }
    @Test fun taskStateAndSpoofedTaskIdAreRejected() = runBlocking {
        val task = f.task()
        denied { f.evaluations.save(f.supervisor, task, f.draft(task + 100)) }
        db.taskDao().updateStatus(task, TaskStatus.IN_PROGRESS)
        denied { f.evaluations.load(f.supervisor, task) }
        denied { f.evaluations.save(f.supervisor, task, f.draft(task)) }
    }
    @Test fun historyFiltersDateBoundariesKeywordAndActorScope() = runBlocking {
        val earlier = f.submitted(date = "2026-10-01")
        val later = f.submitted(date = "2026-10-07")
        val other = f.submitted(f.otherCourse, f.otherSupervisor)
        val own = f.evaluations.history(f.supervisor, "测试教师一", "2026-10-01", "2026-10-07").first()
        assertEquals(listOf(later, earlier), own.map { it.taskId })
        assertEquals(listOf(later), f.evaluations.history(f.supervisor, "数据结构", "2026-10-07", "2026-10-07").first().map { it.taskId })
        assertEquals(3, f.evaluations.history(f.admin, "", "", "").first().size)
        assertTrue(f.evaluations.history(f.supervisor, "不存在", "", "").first().isEmpty())
        denied { f.evaluations.history(f.teacher, "", "", "").first() }
        denied { f.evaluations.history(f.supervisor, "", "2026-10-07", "2026-10-01").first() }
        denied { f.evaluations.load(f.supervisor, other, history = true) }
        denied { f.evaluations.load(f.teacher, later, history = true) }
        assertEquals(other, f.evaluations.load(f.admin, other, history = true).task.id)
    }
    @Test fun historyIncludesMaterialsEventsAndEngagementWithoutMutatingThem() = runBlocking {
        val task = f.task()
        db.captureDao().insertMaterial(Material(taskId = task, type = MaterialType.PPT, filePath = "tasks/$task/photo/missing.jpg", capturedAt = 100, offsetMs = 10))
        db.captureDao().insertEvent(InteractionEvent(taskId = task, type = InteractionType.TEACHER_QUESTION, occurredAt = 110, offsetMs = 20, note = "提问"))
        db.captureDao().insertEngagement(EngagementRecord(taskId = task, type = EngagementType.LISTENING, presentCount = 40, engagedCount = 30, recordedAt = 120, offsetMs = 30))
        f.evaluations.save(f.supervisor, task, f.draft(task), submit = true)
        val history = f.evaluations.load(f.supervisor, task, history = true)
        assertEquals(1, history.materials.size); assertEquals("提问", history.events.single().note)
        assertEquals(30, history.engagements.single().engagedCount)
        assertEquals(2, history.draft.issues.size)
    }
    @Test fun focusRequiresOwnSubmittedFeedbackAndRealIssueIds() = runBlocking {
        val pending = f.task()
        denied { f.improvement.load(f.teacher, pending) }
        val task = f.submitted()
        denied { f.improvement.load(f.otherTeacher, task) }
        denied { f.improvement.load(f.supervisor, task) }
        denied { f.improvement.saveFocus(f.teacher, task, emptyList()) }
        denied { f.improvement.saveFocus(f.teacher, task, listOf(ImprovementItem(planId = 0, issueId = Long.MAX_VALUE,
            description = "伪造问题", source = ItemSource.SUPERVISOR, priority = Priority.HIGH))) }
        assertNull(db.improvementDao().getPlanByTask(task))
    }
    @Test fun moreThanThreeFocusesAndTeacherAddedItemsAreAllowed() = runBlocking {
        val task = f.submitted()
        val items = (1..4).map { ImprovementItem(planId = 0, source = ItemSource.TEACHER, description = "自选事项 $it", priority = Priority.LOW) }
        f.improvement.saveFocus(f.teacher, task, items)
        val record = f.improvement.load(f.teacher, task)
        assertEquals(4, record.items.size); assertTrue(record.items.all { it.source == ItemSource.TEACHER && it.priority == Priority.LOW })
    }
    @Test fun changingFocusPreservesRetainedIdsAndNeedsConsentBeforeDeletingMeasures() = runBlocking {
        val task = f.submitted()
        val record = f.focus(task, count = 2)
        val keep = record.items.first().copy(priority = Priority.LOW)
        denied { f.improvement.saveFocus(f.teacher, task, listOf(keep)) }
        f.improvement.saveFocus(f.teacher, task, listOf(keep), allowRemoval = true)
        val after = f.improvement.load(f.teacher, task)
        assertEquals(listOf(keep), after.items)
        assertEquals(record.measures.filter { it.itemId == keep.id }.map { it.id }, after.measures.map { it.id })
    }
    @Test fun discardedReferenceIsNotRecreatedAndNoSuggestionCanUseAddedMeasure() = runBlocking {
        val task = f.submitted()
        val initial = f.focus(task)
        f.improvement.saveMeasures(f.teacher, task, emptyList())
        assertTrue(f.improvement.load(f.teacher, task).measures.isEmpty())
        f.improvement.saveFocus(f.teacher, task, initial.items)
        assertTrue(f.improvement.load(f.teacher, task).measures.isEmpty())
        val noAdvice = f.submitted(suggestions = null)
        val record = f.focus(noAdvice)
        assertTrue(record.measures.isEmpty())
        f.improvement.saveMeasures(f.teacher, noAdvice, listOf(ImprovementMeasure(planId = record.plan!!.id,
            itemId = record.items.single().id, content = "自主设计措施", source = MeasureSource.ADDED, dueDate = "2026-10-20")), confirm = true)
        assertEquals(PlanStatus.CONFIRMED, f.improvement.load(f.teacher, noAdvice).plan!!.status)
    }
    @Test fun measuresValidateContentsDatesAndForeignItemIds() = runBlocking {
        val task = f.submitted(); val record = f.focus(task)
        val reference = record.measures.single()
        denied { f.improvement.saveMeasures(f.teacher, task, emptyList(), confirm = true) }
        denied { f.improvement.saveMeasures(f.teacher, task, listOf(reference), confirm = true) }
        denied { f.improvement.saveMeasures(f.teacher, task, listOf(reference.copy(content = " ", dueDate = "2026-10-01")), confirm = true) }
        denied { f.improvement.saveMeasures(f.teacher, task, listOf(reference.copy(dueDate = "2026-02-30"))) }
        val other = f.focus(f.submitted(f.otherCourse, f.otherSupervisor), f.otherTeacher)
        denied { f.improvement.saveMeasures(f.teacher, task, listOf(other.measures.single())) }
        denied { f.improvement.saveFocus(f.teacher, task, other.items, allowRemoval = true) }
        assertEquals(PlanStatus.DRAFT, f.improvement.load(f.teacher, task).plan!!.status)
    }
    @Test fun confirmationLocksContentButAllowsOwnedStatusUpdateAndTrace() = runBlocking {
        val task = f.submitted(); val draft = f.focus(task)
        val measures = draft.measures.map { it.copy(content = "改写后的讨论措施", dueDate = "2026-10-01", source = MeasureSource.MODIFIED) }
        f.improvement.saveMeasures(f.teacher, task, measures, confirm = true)
        val record = f.improvement.load(f.teacher, task)
        val measure = record.measures.single()
        denied { f.improvement.saveMeasures(f.teacher, task, record.measures, confirm = true) }
        denied { f.improvement.saveMeasures(f.teacher, task, record.measures.map { it.copy(content = "绕过锁定") }) }
        denied { f.improvement.saveFocus(f.teacher, task, record.items) }
        denied { f.improvement.updateStatus(f.otherTeacher, measure.id, MeasureStatus.DONE) }
        f.improvement.updateStatus(f.teacher, measure.id, MeasureStatus.IN_PROGRESS)
        val todo = f.improvement.todos(f.teacher, true, null).first().single()
        assertEquals(task, todo.taskId); assertEquals(record.plan!!.id, todo.planId)
        assertEquals(record.items.single().description, todo.itemDescription)
        assertEquals(MeasureSource.MODIFIED, todo.source); assertEquals(MeasureStatus.IN_PROGRESS, todo.status)
        assertTrue(f.improvement.todos(f.otherTeacher, false, null).first().isEmpty())
    }
    @Test fun todosFilterByStatusSortByDateAndRespectOverdueBoundaries() = runBlocking {
        val task = f.submitted(); val record = f.focus(task)
        val ref = record.measures.single()
        val measures = listOf(ref.copy(dueDate = "2026-10-07"), ref.copy(id = 0, dueDate = "2026-10-06", source = MeasureSource.ADDED),
            ref.copy(id = 0, dueDate = "2026-10-08", source = MeasureSource.ADDED))
        f.improvement.saveMeasures(f.teacher, task, measures, confirm = true)
        val sorted = f.improvement.todos(f.teacher, true, null).first()
        assertEquals(listOf("2026-10-06", "2026-10-07", "2026-10-08"), sorted.map { it.dueDate })
        assertTrue(isMeasureOverdue(sorted[0].status, sorted[0].dueDate, "2026-10-07"))
        assertFalse(isMeasureOverdue(sorted[1].status, sorted[1].dueDate, "2026-10-07"))
        f.improvement.updateStatus(f.teacher, sorted[0].measureId, MeasureStatus.DONE)
        assertEquals(2, f.improvement.todos(f.teacher, true, null).first().size)
        val done = f.improvement.todos(f.teacher, false, MeasureStatus.DONE).first().single()
        assertFalse(isMeasureOverdue(done.status, done.dueDate, "2026-10-07"))
    }
    @Test fun draftsSurviveDatabaseCloseAndReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "yzj-persistence-${System.nanoTime()}.db"
        var disk = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        try {
            val fixture = Sprint1Fixture(disk); fixture.seed()
            val task = fixture.task(); fixture.evaluations.save(fixture.supervisor, task, fixture.draft(task))
            val submitted = fixture.submitted(); fixture.confirmed(submitted)
            val actor = fixture.supervisor; val teacher = fixture.teacher
            disk.close(); disk = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            assertEquals("概念准确", EvaluationRepository(disk).load(actor, task).draft.evaluation.contentComment)
            assertEquals(PlanStatus.CONFIRMED, ImprovementRepository(disk).load(teacher, submitted).plan!!.status)
            assertEquals(1, ImprovementRepository(disk).todos(teacher, true, null).first().size)
        } finally { disk.close(); context.deleteDatabase(name) }
    }
}
