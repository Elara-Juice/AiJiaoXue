package edu.neu.aijiaoxue.sprint3

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import edu.neu.aijiaoxue.Sprint1Fixture
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.entity.*
import edu.neu.aijiaoxue.data.model.*
import edu.neu.aijiaoxue.data.repository.*
import edu.neu.aijiaoxue.ui.evaluation.EvaluationDraft
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemediationV9RepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var fixture: Sprint1Fixture
    private lateinit var reviews: ReviewRepository
    private var sequence = 0
    private data class Lesson(val task: Long, val evaluation: Long, val issue: Long?)

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        fixture = Sprint1Fixture(db).also { it.seed() }
        reviews = ReviewRepository(db)
    }
    @After fun close() = db.close()

    private suspend fun lesson(date: String, period: String = "1-2", followUp: Boolean = false,
                               noIssues: Boolean = false, design: Int = 4): Lesson {
        val task = db.taskDao().insert(SupervisionTask(code = "V9-${++sequence}", courseId = fixture.course,
            supervisorId = fixture.supervisor, supervisionDate = date, period = period,
            type = if (followUp) SupervisionType.FOLLOW_UP else SupervisionType.REGULAR,
            status = TaskStatus.PENDING_EVALUATION, classStartAt = 1_800_000_000_000,
            classEndAt = 1_800_002_700_000))
        val evaluation = Evaluation(taskId = task, contentScore = 4, designScore = design, interactionScore = 4,
            engagementScore = 4, overallOpinion = "本次正式课堂观察", issueDeclaration =
                if (noIssues) IssueDeclaration.NO_ISSUES_CONFIRMED else IssueDeclaration.HAS_ISSUES,
            issueDeclarationNote = if (noIssues) "完整观察本次课堂，未发现需要列出的主要问题" else null)
        val issues = if (noIssues) emptyList() else listOf(EvaluationIssue(evaluationId = 0,
            description = if (followUp) "教师语言表达还可更清晰" else "教学活动安排不够合理", dimension =
                if (followUp) EvalDimension.CONTENT else EvalDimension.DESIGN))
        fixture.evaluations.save(fixture.supervisor, task, EvaluationDraft(evaluation, issues), submit = true)
        val saved = db.evaluationDao().getByTask(task)!!
        return Lesson(task, saved.id, db.evaluationDao().getIssues(saved.id).firstOrNull()?.id)
    }
    private suspend fun link(before: Lesson, after: Lesson) = reviews.createLink(fixture.supervisor, after.task, before.task, before.issue)
    private suspend fun pair(before: Lesson, after: Lesson, key: MetricKey, role: VerificationEvidenceRole = VerificationEvidenceRole.PRIMARY) =
        VerificationEvidenceRequest.MetricPair(
            db.metricSnapshotDao().getForTask(fixture.supervisor, before.task).single { it.metricKey == key }.id,
            db.metricSnapshotDao().getForTask(fixture.supervisor, after.task).single { it.metricKey == key }.id,
            role, "该指标对应原问题中的教学活动设计")
    private fun observing() = VerificationDraft(VerificationVerdict.CONTINUE_OBSERVING,
        IssuePresence.UNDETERMINED, ImprovementExtent.UNDETERMINED, "缺少足够的原问题证据，继续观察")
    private fun observation(after: Lesson) = VerificationEvidenceRequest.FollowUpObservation(after.evaluation,
        "新增加两次练习和反馈，环节间衔接仍需要调整", role = VerificationEvidenceRole.PRIMARY,
        relationExplanation = "直接观察原教学活动安排问题中的改善点与残余问题")

    @Test fun zeroIssuesPublishesExplicitDeclarationAndDesignSnapshotAndThenLocks() = runBlocking {
        val after = lesson("2026-10-21", followUp = true, noIssues = true, design = 5)
        val saved = db.evaluationDao().getByTask(after.task)!!
        assertEquals(EvalStatus.SUBMITTED, saved.status)
        assertEquals(IssueDeclaration.NO_ISSUES_CONFIRMED, saved.issueDeclaration)
        assertEquals(fixture.supervisor, saved.issueDeclaredBy)
        assertEquals(saved.submittedAt, saved.issueDeclaredAt)
        assertEquals(TaskStatus.COMPLETED, db.taskDao().getById(after.task)!!.status)
        assertTrue(db.evaluationDao().getIssues(saved.id).isEmpty())
        assertNotNull(db.improvementSuggestionDao().publicationForEvaluation(saved.id))
        val design = db.metricSnapshotDao().getForTask(fixture.supervisor, after.task).single { it.metricKey == MetricKey.EVALUATION_DESIGN }
        assertEquals(5.0, design.value!!, 0.0)
        assertEquals("manual_design_score_v1", design.methodVersion)
        assertTrue(runCatching { fixture.evaluations.save(fixture.supervisor, after.task, EvaluationDraft(saved.copy(issueDeclarationNote = "改写"))) }.isFailure)
    }

    @Test fun unknownAndConflictingDeclarationsAreRejectedByRepositoryAndDao() = runBlocking {
        val task = fixture.task()
        val draft = fixture.draft(task)
        assertTrue(runCatching { fixture.evaluations.save(fixture.supervisor, task,
            draft.copy(evaluation = draft.evaluation.copy(issueDeclaration = IssueDeclaration.UNASSESSED)), true) }.isFailure)
        assertNull(db.evaluationDao().getByTask(task))
        assertTrue(runCatching { fixture.evaluations.save(fixture.supervisor, task,
            draft.copy(evaluation = draft.evaluation.copy(issueDeclaration = IssueDeclaration.NO_ISSUES_CONFIRMED,
                issueDeclarationNote = "仍有问题但声称无问题")), true) }.isFailure)
        val id = db.evaluationDao().saveDraft(draft.evaluation.copy(issueDeclaration = IssueDeclaration.UNASSESSED,
            issueDeclaredBy = fixture.supervisor, issueDeclaredAt = 1000))!!
        db.evaluationDao().upsertIssue(EvaluationIssue(evaluationId = id, description = "已有问题"))
        assertFalse(db.evaluationDao().submit(id, task, 81, EvalGrade.GOOD, 1000))
        assertEquals(TaskStatus.PENDING_EVALUATION, db.taskDao().getById(task)!!.status)
    }

    @Test fun exactSelectedMetricIdsSurviveReopenAndUnrelatedTotalCannotProveImprovement() = runBlocking {
        val before = lesson("2026-10-20")
        val after = lesson("2026-10-21", followUp = true)
        val link = link(before, after)
        val design = pair(before, after, MetricKey.EVALUATION_DESIGN)
        val receipt = reviews.saveVerification(fixture.supervisor, link, VerificationDraft(VerificationVerdict.NOT_IMPROVED,
            IssuePresence.PRESENT, ImprovementExtent.NONE, "原教学活动设计问题仍然存在", listOf(design)))
        val detail = reviews.linkDetail(fixture.teacher, link)!!
        val saved = detail.verifications.single()
        assertEquals(design.beforeId, saved.beforeSnapshotId)
        assertEquals(design.afterId, saved.afterSnapshotId)
        assertEquals(receipt.id, detail.evidence.single().verificationId)
        assertEquals(MetricKey.EVALUATION_DESIGN, detail.evidence.single().metricKey)
        assertTrue(detail.verificationWarnings.isEmpty())
        assertTrue(runCatching { reviews.saveVerification(fixture.supervisor, link, VerificationDraft(
            VerificationVerdict.IMPROVED, IssuePresence.NOT_OBSERVED, ImprovementExtent.PARTIAL,
            "总分提高", listOf(pair(before, after, MetricKey.EVALUATION_TOTAL)))) }.isFailure)
        assertEquals(1, db.issueVerificationDao().countForLink(link))
    }

    @Test fun partialImprovementKeepsAllEvidenceAndGovernanceUsesLatestEligibleRecord() = runBlocking {
        val before = lesson("2026-10-20", design = 3)
        val after = lesson("2026-10-21", followUp = true, design = 4)
        val link = link(before, after)
        val receipt = reviews.saveVerification(fixture.supervisor, link, VerificationDraft(VerificationVerdict.IMPROVED,
            IssuePresence.PRESENT, ImprovementExtent.PARTIAL, "增加练习反馈但衔接仍不足",
            listOf(observation(after), pair(before, after, MetricKey.EVALUATION_TOTAL, VerificationEvidenceRole.AUXILIARY)),
            "原问题仍部分存在，记录已改善部分而非全部解决"))
        val detail = reviews.linkDetail(fixture.supervisor, link)!!
        assertEquals(2, detail.evidence.size)
        assertNull(detail.verifications.single().beforeSnapshotId)
        assertNull(detail.verifications.single().afterSnapshotId)
        assertEquals(receipt.id, detail.evidence.first().verificationId)
        assertTrue(detail.verificationWarnings.isEmpty())
        val summary = GovernanceRepository(db).load(fixture.admin).summary!!
        assertEquals(1, summary.verificationCounts[VerificationVerdict.IMPROVED])
        assertEquals(1.0, summary.improvementRate!!, 0.0)
    }

    @Test fun omissionIsNotDisappearanceButExplicitZeroIssueSourceCanBeSelected() = runBlocking {
        val before = lesson("2026-10-20")
        val otherIssues = lesson("2026-10-21", followUp = true)
        val link = link(before, otherIssues)
        val resolved = VerificationDraft(VerificationVerdict.IMPROVED, IssuePresence.NOT_OBSERVED,
            ImprovementExtent.RESOLVED, "逐问题确认本次未再出现教学活动安排问题")
        assertTrue(runCatching { reviews.saveVerification(fixture.supervisor, link, resolved) }.isFailure)
        val zero = lesson("2026-10-22", followUp = true, noIssues = true)
        val zeroLink = link(before, zero)
        reviews.saveVerification(fixture.supervisor, zeroLink, resolved.copy(evidence = listOf(
            VerificationEvidenceRequest.NoIssuesDeclaration(zero.evaluation, VerificationEvidenceRole.PRIMARY,
                "核对原教学活动安排问题，在完整本次复评中未再观察到"))))
        assertTrue(reviews.linkDetail(fixture.teacher, zeroLink)!!.verificationWarnings.isEmpty())
    }

    @Test fun chronologyIsCheckedAtCandidateCreationAndAgainAtSave() = runBlocking {
        val before = lesson("2026-10-20", "3-4")
        val reversed = lesson("2026-10-20", "1-2", followUp = true)
        val overlap = lesson("2026-10-20", "4-5", followUp = true)
        assertTrue(runCatching { link(before, reversed) }.isFailure)
        assertTrue(runCatching { link(before, overlap) }.isFailure)
        assertTrue(reviews.originCandidates(fixture.supervisor, reversed.task).none { it.id == before.task })
        val valid = lesson("2026-10-20", "5-6", followUp = true)
        val link = link(before, valid)
        // Period 2 is reversed relative to 3–4 and does not collide with the separately seeded 1–2 task.
        db.openHelper.writableDatabase.execSQL("UPDATE supervision_tasks SET period='2' WHERE id=?", arrayOf(valid.task))
        assertTrue(runCatching { reviews.saveVerification(fixture.supervisor, link, observing()) }.isFailure)
        assertEquals(0, db.issueVerificationDao().countForLink(link))
        assertTrue(reviews.compare(fixture.supervisor, before.task, valid.task).comparisons.isEmpty())
    }

    @Test fun sourceLossKeepsFrozenEvidenceAndDoesNotResurrectEarlierImprovement() = runBlocking {
        val before = lesson("2026-10-20")
        val after = lesson("2026-10-21", followUp = true)
        val link = link(before, after)
        reviews.saveVerification(fixture.supervisor, link, observing())
        val design = pair(before, after, MetricKey.EVALUATION_DESIGN)
        val latest = reviews.saveVerification(fixture.supervisor, link, VerificationDraft(VerificationVerdict.NOT_IMPROVED,
            IssuePresence.PRESENT, ImprovementExtent.NONE, "设计指标不变，原问题仍存在", listOf(design)))
        val frozen = reviews.linkDetail(fixture.supervisor, link)!!.evidence.single().snapshotJson
        db.openHelper.writableDatabase.execSQL("DELETE FROM metric_snapshots WHERE id=?", arrayOf(design.afterId))
        val detail = reviews.linkDetail(fixture.teacher, link)!!
        assertEquals(frozen, detail.evidence.single().snapshotJson)
        assertNotNull(detail.verificationWarnings[latest.id])
        val summary = GovernanceRepository(db).load(fixture.admin).summary!!
        assertEquals(latest.id, summary.latestVerifications.single().verificationId)
        assertEquals(1, summary.pendingVerifications.size)
        assertTrue(summary.verificationCounts.isEmpty())
    }

    @Test fun evidenceFailureRollsBackAndUnauthorizedOrOldApiCannotBypass() = runBlocking {
        val before = lesson("2026-10-20")
        val after = lesson("2026-10-21", followUp = true)
        val link = link(before, after)
        assertTrue(runCatching { reviews.saveVerification(fixture.teacher, link, observing()) }.isFailure)
        assertTrue(runCatching { reviews.saveVerification(fixture.supervisor, link, VerificationVerdict.IMPROVED, "总分提高") }.isFailure)
        val wrongPair = pair(before, after, MetricKey.EVALUATION_DESIGN).let { it.copy(beforeId = it.afterId, afterId = it.beforeId) }
        assertTrue(runCatching { reviews.saveVerification(fixture.supervisor, link,
            observing().copy(evidence = listOf(wrongPair))) }.isFailure)
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_second_evidence BEFORE INSERT ON issue_verification_evidence
            WHEN NEW.ordinal=1 BEGIN SELECT RAISE(ABORT,'test atomicity'); END""")
        assertTrue(runCatching { reviews.saveVerification(fixture.supervisor, link,
            observing().copy(evidence = listOf(observation(after), pair(before, after, MetricKey.EVALUATION_TOTAL, VerificationEvidenceRole.AUXILIARY)))) }.isFailure)
        assertEquals(0, db.issueVerificationDao().countForLink(link))
        assertTrue(db.issueVerificationDao().evidenceForLink(fixture.supervisor, link).isEmpty())
    }
}
