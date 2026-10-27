package edu.neu.aijiaoxue.sprint3

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import edu.neu.aijiaoxue.Sprint1Fixture
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.entity.*
import edu.neu.aijiaoxue.data.model.*
import edu.neu.aijiaoxue.data.repository.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Sprint3GovernanceTest {
    private lateinit var db: AppDatabase
    private lateinit var fixture: Sprint1Fixture
    private lateinit var repository: GovernanceRepository
    private data class Classroom(val task: Long, val evaluation: Long, val issue: Long, val teacher: Long)

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        fixture = Sprint1Fixture(db)
        fixture.seed()
        repository = GovernanceRepository(db)
    }
    @After fun close() = db.close()

    private suspend fun formal(course: Long = fixture.course, date: String = "2026-10-20", score: Int = 80): Classroom {
        val task = fixture.task(courseId = course, date = date)
        val evaluation = db.evaluationDao().upsert(Evaluation(taskId = task, totalScore = score,
            overallOpinion = "已提交正式意见", strengths = "明确任务后分组讨论", status = EvalStatus.SUBMITTED, submittedAt = 1000))
        val issue = db.evaluationDao().upsertIssue(EvaluationIssue(evaluationId = evaluation,
            description = "学生互动不足", dimension = EvalDimension.INTERACTION))
        db.taskDao().updateStatus(task, TaskStatus.COMPLETED)
        return Classroom(task, evaluation, issue, db.courseDao().getById(course)!!.teacherId)
    }

    private suspend fun snapshot(classroom: Classroom, value: Double?) = db.metricSnapshotDao().insert(MetricSnapshot(
        taskId = classroom.task, metricKey = MetricKey.EVALUATION_TOTAL, value = value,
        unit = MetricRules.UNIT_SCORE, denominator = "百分制总分", denominatorValue = 100.0,
        sampling = MetricRules.SAMPLING_EVALUATION, methodVersion = MetricRules.METHOD_VERSION,
        sampleCount = if (value == null) 0 else 1, scopeStartAt = null, scopeEndAt = null,
    ))

    private suspend fun denied(action: suspend () -> Unit) {
        val failure = runCatching { action() }.exceptionOrNull()
        assertTrue("预期拒绝，实际 $failure", failure is IllegalArgumentException || failure is IllegalStateException)
    }

    @Test fun teacherAndSupervisorCannotReadOtherTeachersOrGlobalGovernance() = runBlocking {
        val own = formal()
        val other = formal(fixture.otherCourse)
        snapshot(own, 80.0); snapshot(other, 95.0)
        val teacher = repository.load(fixture.teacher)
        assertNull(teacher.summary)
        assertEquals(listOf(fixture.course), teacher.trends.map { it.courseId })
        assertTrue(teacher.courseOptions.all { it.teacherId == fixture.teacher })
        denied { repository.load(fixture.teacher, GovernanceFilter(teacherId = fixture.otherTeacher)) }
        denied { repository.sourceDetail(fixture.teacher, other.task) }
        denied { repository.load(fixture.supervisor) }
        denied { repository.sourceDetail(fixture.supervisor, own.task) }
        assertTrue(db.governanceDao().formalTasks(fixture.supervisor, null, null, "", "").isEmpty())
        assertTrue(db.governanceDao().snapshots(fixture.teacher, listOf(other.task)).isEmpty())
        val admin = repository.load(fixture.admin)
        assertEquals(2, admin.summary!!.teacherCount)
        assertEquals(2, admin.summary!!.courseCount)
    }

    @Test fun filtersFormalDataByDatesAndDoesNotPromoteDraftSnapshots() = runBlocking {
        val before = formal(date = "2026-10-20")
        val after = formal(date = "2026-10-22")
        snapshot(before, 70.0); snapshot(after, 80.0)
        val draft = fixture.task(date = "2026-10-21")
        db.evaluationDao().upsert(Evaluation(taskId = draft, totalScore = 100))
        snapshot(Classroom(draft, 0, 0, fixture.teacher), 100.0)
        val all = repository.load(fixture.admin)
        assertEquals(2, all.summary!!.classroomCount)
        assertTrue(all.trends.single().hasComparableTrend)
        val filtered = repository.load(fixture.admin, GovernanceFilter(courseId = fixture.course, fromDate = "2026-10-21", toDate = "2026-10-22"))
        assertEquals(listOf(after.task), filtered.trends.single().classrooms.map { it.taskId })
        assertFalse(filtered.trends.single().hasComparableTrend)
        denied { repository.sourceDetail(fixture.admin, draft) }
        denied { repository.load(fixture.admin, GovernanceFilter(fromDate = "2026-10-23", toDate = "2026-10-20")) }
        denied { repository.load(fixture.admin, GovernanceFilter(fromDate = "2026-02-30")) }
    }

    @Test fun missingSnapshotStaysMissingAndSourceDetailPreservesOfficialValues() = runBlocking {
        val before = formal(score = 70)
        val after = formal(date = "2026-10-22", score = 90)
        snapshot(before, 70.0)
        val trend = repository.load(fixture.teacher).trends.single()
        assertFalse(trend.hasComparableTrend)
        val pair = trend.comparisons.single { it.metricKey == MetricKey.EVALUATION_TOTAL }
        assertNull(pair.after)
        assertNull(pair.outcome)
        val detail = repository.sourceDetail(fixture.teacher, after.task)
        assertEquals(90, detail.classroom.totalScore)
        assertEquals("学生互动不足", detail.issues.single().description)
        assertTrue(detail.snapshots.isEmpty())
        assertTrue(db.metricSnapshotDao().getForTask(fixture.admin, after.task).isEmpty())
    }

    @Test fun multipleExecutionsCountOncePerMeasureAndDraftPlanDoesNotInflateGovernance() = runBlocking {
        val classroom = formal()
        val plan = db.improvementDao().upsertPlan(ImprovementPlan(teacherId = fixture.teacher, taskId = classroom.task,
            status = PlanStatus.CONFIRMED, confirmedAt = 1000))
        val item = db.improvementDao().upsertItem(ImprovementItem(planId = plan, issueId = classroom.issue,
            source = ItemSource.SUPERVISOR, description = "互动改进", priority = Priority.HIGH))
        val measure = db.improvementDao().upsertMeasure(ImprovementMeasure(planId = plan, itemId = item,
            content = "先思考再小组交流", source = MeasureSource.ADDED, dueDate = "2026-10-30", status = MeasureStatus.DONE))
        for (number in 1..2) db.improvementExecutionDao().insertExecution(ImprovementExecution(
            measureId = measure, implementationNote = "实施说明$number", actualAdjustment = "调整$number",
            implementedAt = number * 1000L, recordedBy = fixture.teacher, requestKey = "execution-$number"))
        val draftClass = formal(date = "2026-10-22")
        val draftPlan = db.improvementDao().upsertPlan(ImprovementPlan(teacherId = fixture.teacher, taskId = draftClass.task))
        val draftItem = db.improvementDao().upsertItem(ImprovementItem(planId = draftPlan, source = ItemSource.TEACHER,
            description = "未确认重点", priority = Priority.HIGH))
        db.improvementDao().upsertMeasure(ImprovementMeasure(planId = draftPlan, itemId = draftItem,
            content = "未确认措施", source = MeasureSource.ADDED, dueDate = "2026-10-30"))
        val summary = repository.load(fixture.admin).summary!!
        assertEquals(2, summary.issuesByDimension[EvalDimension.INTERACTION])
        assertEquals(1, summary.measuresByStatus.values.sum())
        assertEquals(1, summary.executedMeasureCount)
        assertEquals(2, summary.executionRecordCount)
        assertTrue(summary.latestVerifications.isEmpty())
        val detail = repository.sourceDetail(fixture.teacher, classroom.task)
        assertEquals(listOf(measure), detail.measures.map { it.measureId })
        assertEquals(setOf("调整1", "调整2"), detail.executions.map { it.actualAdjustment }.toSet())
    }

    @Test fun duplicateWholeClassLinksUseLatestAndUnpublishedOrLegacyRecordsRemainPending() = runBlocking {
        val origin = formal()
        val followUp = formal(date = "2026-10-22")
        suspend fun link(followUpId: Long) = db.reviewLinkDao().insert(ReviewLink(originTaskId = origin.task,
            followUpTaskId = followUpId, teacherId = fixture.teacher, originIssueSnapshot = "整堂复评", createdBy = fixture.supervisor))
        val first = link(followUp.task)
        val duplicate = link(followUp.task)
        assertNotEquals(first, duplicate)
        db.issueVerificationDao().insert(IssueVerification(linkId = first, verdict = VerificationVerdict.IMPROVED,
            basis = "首次观察", verifierId = fixture.supervisor, verifiedAt = 2000))
        val latestId = db.issueVerificationDao().insert(IssueVerification(linkId = duplicate, verdict = VerificationVerdict.NOT_IMPROVED,
            basis = "同一时刻的新核验，仍需调整", verifierId = fixture.supervisor, verifiedAt = 2000))
        val unpublished = fixture.task(date = "2026-10-23")
        db.issueVerificationDao().insert(IssueVerification(linkId = link(unpublished), verdict = VerificationVerdict.CONTINUE_OBSERVING,
            basis = "尚待正式评价", verifierId = fixture.supervisor, verifiedAt = 3000))
        val summary = repository.load(fixture.admin).summary!!
        assertEquals(latestId, summary.latestVerifications.single { it.followUpTaskId == followUp.task }.verificationId)
        assertEquals(2, summary.pendingVerifications.size)
        assertTrue(summary.verificationCounts.isEmpty())
        assertNull(summary.verificationCounts[VerificationVerdict.IMPROVED])
        assertEquals(setOf(followUp.task, unpublished), repository.sourceDetail(fixture.teacher, origin.task).verifications.map { it.followUpTaskId }.toSet())
    }
}
