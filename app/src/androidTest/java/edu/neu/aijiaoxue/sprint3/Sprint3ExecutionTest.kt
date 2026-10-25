package edu.neu.aijiaoxue.sprint3

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import edu.neu.aijiaoxue.Sprint1Fixture
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.entity.ImprovementMeasure
import edu.neu.aijiaoxue.data.model.MeasureStatus
import edu.neu.aijiaoxue.data.model.SupervisionType
import edu.neu.aijiaoxue.ui.improve.ExecutionInput
import edu.neu.aijiaoxue.ui.improve.ImprovementExecutionRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** US43/44: exercise production authorization and transactions, not a duplicate model. */
@RunWith(AndroidJUnit4::class)
class Sprint3ExecutionTest {
    private lateinit var db: AppDatabase
    private lateinit var fixture: Sprint1Fixture
    private lateinit var repo: ImprovementExecutionRepository
    private var taskId = 0L
    private lateinit var measure: ImprovementMeasure

    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java).build()
        fixture = Sprint1Fixture(db).also { it.seed() }
        repo = ImprovementExecutionRepository(db)
        taskId = fixture.submitted()
        measure = fixture.confirmed(taskId).measures.single()
    }

    @After fun close() { db.close() }

    private fun input(key: String = "execution-1", text: String = "实际增加同伴讨论与随机汇报") =
        ExecutionInput(implementationNote = "本节课分组讨论后汇报", actualAdjustment = text,
            implementedAt = System.currentTimeMillis() - 1000, requestKey = key, status = MeasureStatus.IN_PROGRESS)

    private suspend fun denied(block: suspend () -> Unit) {
        val error = runCatching { block() }.exceptionOrNull()
        assertTrue("Expected a business denial, got $error",
            error is IllegalArgumentException || error is IllegalStateException)
    }

    @Test fun appendPreservesConfirmedPlanAndKeepsActualPracticeSeparate() = runBlocking {
        val before = fixture.improvement.load(fixture.teacher, taskId)
        val id = repo.append(fixture.teacher, taskId, measure.id, input())
        val context = repo.load(fixture.teacher, taskId)
        val actual = context.executions.single().execution
        assertEquals(id, actual.id)
        assertEquals(measure.id, actual.measureId)
        assertEquals(fixture.teacher, actual.recordedBy)
        assertEquals("实际增加同伴讨论与随机汇报", actual.actualAdjustment)
        assertEquals(before.plan, context.record.plan)
        assertEquals(measure.content, context.record.measures.single().content)
        assertEquals(measure.suggestionId, context.record.measures.single().suggestionId)
        assertEquals(before.reviewedAnalyses, context.record.reviewedAnalyses)
        assertEquals(MeasureStatus.IN_PROGRESS, context.record.measures.single().status)
        assertEquals(taskId, repo.requireExecution(fixture.teacher, id).taskId)
    }

    @Test fun concurrentIdenticalRetryCreatesOneRecordAndDifferentRequestAppends() = runBlocking {
        val same = input()
        val ids = List(4) { async { repo.append(fixture.teacher, taskId, measure.id, same) } }.awaitAll()
        assertEquals(1, ids.toSet().size)
        val next = repo.append(fixture.teacher, taskId, measure.id, input("execution-2", "补充小组互评"))
        assertNotEquals(ids.first(), next)
        assertEquals(2, repo.load(fixture.teacher, taskId).executions.size)
    }

    @Test fun requestKeyCannotSilentlyReplaceEarlierPractice() = runBlocking {
        val same = input()
        repo.append(fixture.teacher, taskId, measure.id, same)
        denied { repo.append(fixture.teacher, taskId, measure.id, same.copy(actualAdjustment = "不同的实际做法")) }
        assertEquals(same.actualAdjustment, repo.load(fixture.teacher, taskId).executions.single().execution.actualAdjustment)
    }

    @Test fun otherTeacherAndRolesCannotWriteOrReadTeacherContextWithoutAuthorization() = runBlocking {
        for (actor in listOf(fixture.otherTeacher, fixture.otherSupervisor, 999999L)) {
            denied { repo.load(actor, taskId) }
            denied { repo.append(actor, taskId, measure.id, input()) }
        }
        for (actor in listOf(fixture.admin, fixture.supervisor)) {
            denied { repo.append(actor, taskId, measure.id, input()) }
        }
        assertTrue(repo.load(fixture.teacher, taskId).executions.isEmpty())
    }

    @Test fun authorizedReviewersReadButCannotMutateTheImplementation() = runBlocking {
        val id = repo.append(fixture.teacher, taskId, measure.id, input())
        for (actor in listOf(fixture.admin, fixture.supervisor)) {
            assertEquals(id, repo.load(actor, taskId).executions.single().execution.id)
            denied { repo.requireExecution(actor, id) }
        }
    }

    @Test fun followUpTypeAloneDoesNotGrantAccessToOriginalTeacherData() = runBlocking {
        val later = fixture.task(supervisorId = fixture.otherSupervisor)
        db.openHelper.writableDatabase.execSQL("UPDATE supervision_tasks SET type = ? WHERE id = ?",
            arrayOf<Any>(SupervisionType.FOLLOW_UP.name, later))
        repo.append(fixture.teacher, taskId, measure.id, input())
        denied { repo.load(fixture.otherSupervisor, taskId) }
    }

    @Test fun cannotAttachARecordToAnotherTaskOrInventedMeasure() = runBlocking {
        val second = fixture.submitted()
        val secondMeasure = fixture.confirmed(second).measures.single()
        denied { repo.append(fixture.teacher, taskId, secondMeasure.id, input()) }
        denied { repo.append(fixture.teacher, taskId, Long.MAX_VALUE, input()) }
        assertTrue(repo.load(fixture.teacher, taskId).executions.isEmpty())
        assertTrue(repo.load(fixture.teacher, second).executions.isEmpty())
    }

    @Test fun draftPlanAndUnpublishedEvaluationAreRejected() = runBlocking {
        val draftTask = fixture.submitted()
        val draft = fixture.focus(draftTask)
        denied { repo.append(fixture.teacher, draftTask, draft.measures.single().id, input()) }
        db.openHelper.writableDatabase.execSQL("UPDATE evaluations SET status = 'DRAFT' WHERE taskId = ?", arrayOf(taskId))
        denied { repo.load(fixture.teacher, taskId) }
        denied { repo.append(fixture.teacher, taskId, measure.id, input()) }
    }

    @Test fun invalidInputDoesNotPartiallyChangeProgress() = runBlocking {
        val invalid = listOf(input().copy(actualAdjustment = "  "), input().copy(implementedAt = 0),
            input().copy(implementedAt = System.currentTimeMillis() + 86_400_000L),
            input().copy(requestKey = ""), input().copy(actualAdjustment = "字".repeat(5001)))
        for (item in invalid) denied { repo.append(fixture.teacher, taskId, measure.id, item) }
        assertTrue(repo.load(fixture.teacher, taskId).executions.isEmpty())
        assertEquals(measure.status, fixture.improvement.load(fixture.teacher, taskId).measures.single().status)
    }

    @Test fun changingTodoStatusDoesNotInventExecutionOrImprovementJudgment() = runBlocking {
        fixture.improvement.updateStatus(fixture.teacher, measure.id, MeasureStatus.DONE)
        val context = repo.load(fixture.teacher, taskId)
        assertEquals(MeasureStatus.DONE, context.record.measures.single().status)
        assertTrue(context.executions.isEmpty())
        val before = fixture.improvement.load(fixture.teacher, taskId).evaluation
        repo.append(fixture.teacher, taskId, measure.id, input().copy(status = null))
        val after = repo.load(fixture.teacher, taskId)
        assertEquals(MeasureStatus.DONE, after.record.measures.single().status)
        assertEquals(before, after.record.evaluation)
    }

    @Test fun retryAfterLaterProgressChangeDoesNotReplayOldStatus() = runBlocking {
        val request = input()
        val id = repo.append(fixture.teacher, taskId, measure.id, request)
        fixture.improvement.updateStatus(fixture.teacher, measure.id, MeasureStatus.DONE)
        assertEquals(id, repo.append(fixture.teacher, taskId, measure.id, request))
        val context = repo.load(fixture.teacher, taskId)
        assertEquals(1, context.executions.size)
        assertEquals(MeasureStatus.DONE, context.record.measures.single().status)
    }

    @Test fun referencedHistoryPreventsDestructiveParentDeletion() = runBlocking {
        val id = repo.append(fixture.teacher, taskId, measure.id, input())
        assertTrue(runCatching { db.openHelper.writableDatabase.execSQL(
            "DELETE FROM improvement_measures WHERE id = ?", arrayOf(measure.id)) }.isFailure)
        assertTrue(runCatching { db.openHelper.writableDatabase.execSQL(
            "DELETE FROM improvement_plans WHERE id = ?", arrayOf(measure.planId)) }.isFailure)
        assertEquals(id, repo.load(fixture.teacher, taskId).executions.single().execution.id)
    }

    @Test fun fileDatabaseReopenPreservesImplementationAndOriginalPlan() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "s3-reopen-${System.nanoTime()}.db"
        var disk = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        try {
            val original = Sprint1Fixture(disk).also { it.seed() }
            val task = original.submitted()
            val plan = original.confirmed(task)
            val id = ImprovementExecutionRepository(disk).append(original.teacher, task, plan.measures.single().id, input())
            disk.close()
            disk = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            val reopened = ImprovementExecutionRepository(disk).load(original.teacher, task)
            assertEquals(id, reopened.executions.single().execution.id)
            assertEquals("实际增加同伴讨论与随机汇报", reopened.executions.single().execution.actualAdjustment)
            assertEquals(plan.plan, reopened.record.plan)
            assertEquals(plan.measures.single().content, reopened.record.measures.single().content)
        } finally {
            disk.close()
            context.deleteDatabase(name)
        }
    }
}
