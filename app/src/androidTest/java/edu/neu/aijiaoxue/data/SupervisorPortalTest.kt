package edu.neu.aijiaoxue.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import edu.neu.aijiaoxue.Sprint1Fixture
import edu.neu.aijiaoxue.data.entity.*
import edu.neu.aijiaoxue.data.model.*
import edu.neu.aijiaoxue.data.repository.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Independent database: protects the real emulator's classroom data and credentials. */
@RunWith(AndroidJUnit4::class)
class SupervisorPortalTest {
    private lateinit var db: AppDatabase
    private lateinit var f: Sprint1Fixture
    private lateinit var repo: SupervisorPortalRepository

    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        f = Sprint1Fixture(db).also { it.seed() }
        repo = SupervisorPortalRepository(db)
    }
    @After fun close() { db.close() }

    @Test fun onlyOwnedPublishedIssuesAndConfirmedTeacherPlansAreVisible() = runBlocking {
        val task = f.submitted()
        f.submitted(supervisorId = f.otherSupervisor)
        val draftTask = f.task()
        f.evaluations.save(f.supervisor, draftTask, f.draft(draftTask), submit = false)
        val rows = repo.issues(f.supervisor).first()
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.row.taskId == task })
        assertTrue(repo.issues(f.teacher).first().isEmpty())
        assertTrue(repo.tasks(f.teacher).first().isEmpty())
        val issueId = rows.first().row.id
        assertTrue(runCatching { repo.issue(f.otherSupervisor, issueId) }.isFailure)
        assertTrue(runCatching { repo.task(f.otherSupervisor, task) }.isFailure)
        f.focus(task)
        assertTrue(repo.issue(f.supervisor, issueId).measures.isEmpty())
        assertEquals(TrackingStage.AWAITING_PLAN, repo.issue(f.supervisor, issueId).tracked.stage)
        val draftPlan = f.improvement.load(f.teacher, task)
        f.improvement.saveMeasures(f.teacher, task, draftPlan.measures.map { it.copy(dueDate = "2026-11-01") }, confirm = true)
        val plan = f.improvement.load(f.teacher, task)
        assertTrue(repo.issue(f.supervisor, issueId).measures.isNotEmpty())
        assertEquals(TrackingStage.IMPLEMENTING, repo.issue(f.supervisor, issueId).tracked.stage)
        plan.measures.forEach { f.improvement.updateStatus(f.teacher, it.id, MeasureStatus.DONE) }
        assertEquals(TrackingStage.AWAITING_REVIEW, repo.issue(f.supervisor, issueId).tracked.stage)
    }

    @Test fun followUpCreationBindsOriginalIssueAndRollsBackInvalidChronology() = runBlocking {
        val origin = f.submitted(date = "2026-10-07")
        val issue = repo.issues(f.supervisor).first().first().row.id
        val initialCount = repo.tasks(f.supervisor).first().size
        assertTrue(runCatching { repo.createFollowUp(f.supervisor, issue, "2026-10-06", 1, 2, "复评") }.isFailure)
        assertEquals(initialCount, repo.tasks(f.supervisor).first().size)
        assertTrue(runCatching { repo.createFollowUp(f.otherSupervisor, issue, "2026-10-20", 1, 2, "复评") }.isFailure)
        val follow = repo.createFollowUp(f.supervisor, issue, "2026-10-20", 1, 2, "复评章节")
        val task = db.taskDao().getById(follow)!!
        assertEquals(f.course, task.courseId)
        assertEquals(f.supervisor, task.supervisorId)
        assertEquals(SupervisionType.FOLLOW_UP, task.type)
        assertEquals(TaskStatus.NOT_STARTED, task.status)
        val link = db.reviewLinkDao().getByFollowUpTask(f.supervisor, follow).single()
        assertEquals(origin, link.originTaskId)
        assertEquals(issue, link.originIssueId)
        assertEquals(f.teacher, link.teacherId)
        assertTrue(runCatching { repo.createFollowUp(f.supervisor, issue, "2026-10-20", 1, 2, "重复") }.isFailure)
        assertEquals(initialCount + 1, repo.tasks(f.supervisor).first().size)
    }

    @Test fun partialImprovementCannotCloseIssueAndInvalidEvidenceReopensIt() = runBlocking {
        f.submitted()
        val issue = repo.issues(f.supervisor).first().first().row.id
        val follow = repo.createFollowUp(f.supervisor, issue, "2026-10-20", 1, 2, "复评")
        // Simulate a completed capture in this isolated fixture before publishing its evaluation.
        db.taskDao().updateStatus(follow, TaskStatus.PENDING_EVALUATION)
        f.evaluations.save(f.supervisor, follow, f.draft(follow).copy(issues = listOf(
            EvaluationIssue(evaluationId = 0, description = "板书需要规范", dimension = EvalDimension.DESIGN)
        )), submit = true)
        val link = db.reviewLinkDao().getByFollowUpTask(f.supervisor, follow).single()
        val evaluationId = db.evaluationDao().getByTask(follow)!!.id
        val reviews = ReviewRepository(db)
        val evidence = listOf(VerificationEvidenceRequest.FollowUpObservation(evaluationId,
            "已增加两轮讨论并记录参与情况，复查原课堂互动问题", role = VerificationEvidenceRole.PRIMARY,
            relationExplanation = "直接观察原互动问题的改善情况"))
        reviews.saveVerification(f.supervisor, link.id, VerificationDraft(VerificationVerdict.IMPROVED,
            IssuePresence.PRESENT, ImprovementExtent.PARTIAL, "讨论增加但原问题仍部分存在", evidence,
            "讨论机会已增加，但主动提问仍然不足，因此仅部分改善"))
        assertEquals(TrackingStage.AWAITING_REVIEW, repo.issue(f.supervisor, issue).tracked.stage)
        reviews.saveVerification(f.supervisor, link.id, VerificationDraft(VerificationVerdict.IMPROVED,
            IssuePresence.NOT_OBSERVED, ImprovementExtent.RESOLVED, "本次复评未再观察到原互动问题", evidence))
        assertEquals(TrackingStage.CLOSED, repo.issue(f.supervisor, issue).tracked.stage)
        // Corrupted historical source must not continue displaying a reliable closure.
        db.taskDao().updateStatus(follow, TaskStatus.PENDING_EVALUATION)
        assertEquals(TrackingStage.AWAITING_REVIEW, repo.issue(f.supervisor, issue).tracked.stage)
    }
}
