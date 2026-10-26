package edu.neu.aijiaoxue.sprint3

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import edu.neu.aijiaoxue.Sprint1Fixture
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.entity.EngagementRecord
import edu.neu.aijiaoxue.data.entity.Evaluation
import edu.neu.aijiaoxue.data.entity.EvaluationIssue
import edu.neu.aijiaoxue.data.entity.InteractionEvent
import edu.neu.aijiaoxue.data.entity.SupervisionTask
import edu.neu.aijiaoxue.data.model.EngagementType
import edu.neu.aijiaoxue.data.model.EvalDimension
import edu.neu.aijiaoxue.data.model.InteractionType
import edu.neu.aijiaoxue.data.model.MetricKey
import edu.neu.aijiaoxue.data.model.MetricRules
import edu.neu.aijiaoxue.data.model.SupervisionType
import edu.neu.aijiaoxue.data.model.TaskStatus
import edu.neu.aijiaoxue.data.model.VerificationVerdict
import edu.neu.aijiaoxue.data.repository.ReviewRepository
import edu.neu.aijiaoxue.ui.evaluation.EvaluationDraft
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Sprint 3（李奕萱）US24/US25/US45/US46 的运行时校验：
 * 关联授权、指标快照只追加、缺测不补零、口径不同不强行比较、核验必须有依据。
 */
@RunWith(AndroidJUnit4::class)
class Sprint3ReviewTest {
    private lateinit var db: AppDatabase
    private lateinit var fixture: Sprint1Fixture
    private lateinit var repo: ReviewRepository
    private val start = 1_800_000_000_000L

    @Before
    fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java).build()
        fixture = Sprint1Fixture(db).also { it.seed() }
        repo = ReviewRepository(db)
    }

    @After
    fun close() {
        db.close()
    }

    @Test
    fun linkIsVisibleToTeacherAndFollowUpSupervisorButNotOtherTeacher() = runBlocking {
        val origin = classroom(fixture.supervisor, "2026-10-07", "1", SupervisionType.REGULAR, EngagementMode.COUNTS)
        val followUp = classroom(fixture.otherSupervisor, "2026-10-20", "1", SupervisionType.FOLLOW_UP, EngagementMode.COUNTS)
        val linkId = repo.createLink(fixture.otherSupervisor, followUp.taskId, origin.taskId, origin.issueId)

        // 复评督导通过关联读取原课堂的正式结果
        val detail = repo.linkDetail(fixture.otherSupervisor, linkId)
        assertNotNull(detail)
        assertEquals("互动较少", detail!!.link.originIssueSnapshot)
        // 本课教师可以看自己的复评关联
        assertNotNull(repo.linkDetail(fixture.teacher, linkId))
        // 其他教师看不到
        assertNull(repo.linkDetail(fixture.otherTeacher, linkId))
        // 原课堂督导也可以看到这条关联
        assertNotNull(repo.linkDetail(fixture.supervisor, linkId))
    }

    @Test
    fun crossSupervisorCannotReadRawCaptureOfOriginClassroom() = runBlocking {
        val origin = classroom(fixture.supervisor, "2026-10-07", "1", SupervisionType.REGULAR, EngagementMode.COUNTS)
        val followUp = classroom(fixture.otherSupervisor, "2026-10-20", "1", SupervisionType.FOLLOW_UP, EngagementMode.COUNTS)
        repo.createLink(fixture.otherSupervisor, followUp.taskId, origin.taskId, origin.issueId)

        val denied = runCatching { repo.participationInsight(fixture.otherSupervisor, origin.taskId) }.exceptionOrNull()
        assertNotNull("跨督导不能读取原课堂原始采集数据", denied)
        // 本堂督导本人可以
        assertNotNull(repo.participationInsight(fixture.supervisor, origin.taskId))
    }

    @Test
    fun snapshotsAreAppendOnlyAndMissingDataStaysMissing() = runBlocking {
        val origin = classroom(fixture.supervisor, "2026-10-07", "1", SupervisionType.REGULAR, EngagementMode.COUNTS)
        // EvaluationRepository.submit already captures the first immutable snapshot.
        assertEquals(3, db.metricSnapshotDao().getForTask(fixture.supervisor, origin.taskId).size)
        val first = db.metricSnapshotDao().getForTask(fixture.supervisor, origin.taskId)
            .first { it.metricKey == MetricKey.ENGAGEMENT_RATE }
        // 参与率 = (18+24+18) / (30+30+30)
        assertEquals(200.0 / 3.0, first.value!!, 0.01)
        assertEquals(MetricRules.DENOMINATOR_PRESENT, first.denominator)

        // 追加新的参与记录后重新计算，历史快照不能被覆盖
        db.captureDao().insertEngagement(EngagementRecord(taskId = origin.taskId, type = EngagementType.LISTENING,
            presentCount = 30, engagedCount = 30, recordedAt = start + 10_000, offsetMs = 10_000))
        assertEquals(0, repo.captureSnapshots(fixture.supervisor, origin.taskId))
        val again = db.metricSnapshotDao().getForTask(fixture.supervisor, origin.taskId)
            .first { it.metricKey == MetricKey.ENGAGEMENT_RATE }
        assertEquals(first.value, again.value)
        assertEquals(first.id, again.id)

        // 没有参与记录时是缺测，不是 0
        val empty = classroom(fixture.supervisor, "2026-10-08", "2", SupervisionType.REGULAR, EngagementMode.NONE)
        repo.captureSnapshots(fixture.supervisor, empty.taskId)
        val missing = db.metricSnapshotDao().getForTask(fixture.supervisor, empty.taskId)
            .first { it.metricKey == MetricKey.ENGAGEMENT_RATE }
        assertNull("缺测不得补零", missing.value)
        assertEquals(0, missing.sampleCount)
    }

    @Test
    fun comparisonOnlyWhenUnitsAndDenominatorsMatch() = runBlocking {
        val origin = classroom(fixture.supervisor, "2026-10-07", "1", SupervisionType.REGULAR, EngagementMode.COUNTS,
            scores = listOf(5, 4, 3, 4))
        val followUp = classroom(fixture.otherSupervisor, "2026-10-20", "1", SupervisionType.FOLLOW_UP, EngagementMode.COUNTS,
            scores = listOf(5, 4, 5, 5))
        repo.createLink(fixture.otherSupervisor, followUp.taskId, origin.taskId, origin.issueId)

        val result = repo.compare(fixture.otherSupervisor, origin.taskId, followUp.taskId)
        val rate = result.comparisons.first { it.metricKey == MetricKey.ENGAGEMENT_RATE }
        assertTrue("同口径应可比较", rate.outcome is MetricRules.CompareOutcome.Comparable)
        val total = result.comparisons.first { it.metricKey == MetricKey.EVALUATION_TOTAL }
        val outcome = total.outcome as MetricRules.CompareOutcome.Comparable
        assertEquals(95 - 81, outcome.delta.toInt())
    }

    @Test
    fun differentDenominatorIsNotComparedAndMissingSecondClassIsReported() = runBlocking {
        val origin = classroom(fixture.supervisor, "2026-10-07", "1", SupervisionType.REGULAR, EngagementMode.COUNTS)
        val followUp = classroom(fixture.otherSupervisor, "2026-10-20", "1", SupervisionType.FOLLOW_UP, EngagementMode.PERCENT)
        repo.createLink(fixture.otherSupervisor, followUp.taskId, origin.taskId, origin.issueId)

        val result = repo.compare(fixture.otherSupervisor, origin.taskId, followUp.taskId)
        val rate = result.comparisons.first { it.metricKey == MetricKey.ENGAGEMENT_RATE }
        assertEquals(MetricRules.IncomparableReason.DENOMINATOR_MISMATCH,
            (rate.outcome as MetricRules.CompareOutcome.NotComparable).reason)

        // 复评结果没有发布时不进入正式比较
        val unpublished = classroom(fixture.otherSupervisor, "2026-10-21", "2", SupervisionType.FOLLOW_UP,
            EngagementMode.COUNTS, submit = false)
        val pending = repo.compare(fixture.otherSupervisor, origin.taskId, unpublished.taskId)
        assertNotNull(pending.note)
        assertTrue(pending.note!!.contains("尚未发布"))
        assertTrue(pending.comparisons.isEmpty())
    }

    @Test
    fun verificationNeedsEvidenceAndKeepsContinueObserving() = runBlocking {
        val origin = classroom(fixture.supervisor, "2026-10-07", "1", SupervisionType.REGULAR, EngagementMode.COUNTS,
            scores = listOf(5, 4, 3, 4))
        val followUp = classroom(fixture.otherSupervisor, "2026-10-20", "1", SupervisionType.FOLLOW_UP, EngagementMode.COUNTS,
            scores = listOf(5, 4, 5, 5))
        val linkId = repo.createLink(fixture.otherSupervisor, followUp.taskId, origin.taskId, origin.issueId)

        // 有可比指标且评价总分提高，可以判定“问题已改善”
        val receipt = repo.saveVerification(fixture.otherSupervisor, linkId, VerificationVerdict.IMPROVED, "复评评价总分高于原课堂")
        assertEquals(VerificationVerdict.IMPROVED, receipt.verdict)

        // 没有第二次有效课堂时只能保留“继续观察”
        // 评价分数取满分，避免复评课堂较高的评价分被当作改善证据；该场景只验证无正向证据时拒绝“已改善”。
        val lonely = classroom(fixture.supervisor, "2026-10-08", "3", SupervisionType.REGULAR, EngagementMode.NONE,
            scores = listOf(5, 5, 5, 5))
        val lonelyLink = repo.createLink(fixture.otherSupervisor, followUp.taskId, lonely.taskId, null)
        val observing = repo.saveVerification(fixture.otherSupervisor, lonelyLink, VerificationVerdict.CONTINUE_OBSERVING, "缺少第二次有效课堂")
        assertEquals(VerificationVerdict.CONTINUE_OBSERVING, observing.verdict)

        // 证据不足时不允许直接判“问题已改善”
        val denied = runCatching {
            repo.saveVerification(fixture.otherSupervisor, lonelyLink, VerificationVerdict.IMPROVED, "感觉改善了")
        }.exceptionOrNull()
        assertNotNull("证据不足时必须拒绝改善结论", denied)
    }

    @Test
    fun teacherCannotSaveVerification() = runBlocking {
        val origin = classroom(fixture.supervisor, "2026-10-07", "1", SupervisionType.REGULAR, EngagementMode.COUNTS)
        val followUp = classroom(fixture.otherSupervisor, "2026-10-20", "1", SupervisionType.FOLLOW_UP, EngagementMode.COUNTS)
        val linkId = repo.createLink(fixture.otherSupervisor, followUp.taskId, origin.taskId, origin.issueId)
        val denied = runCatching {
            repo.saveVerification(fixture.teacher, linkId, VerificationVerdict.IMPROVED, "教师自行判定")
        }.exceptionOrNull()
        assertNotNull("教师不能保存核验结论", denied)
    }

    @Test
    fun linkRequiresSameCourseAndFollowUpType() = runBlocking {
        val origin = classroom(fixture.supervisor, "2026-10-07", "1", SupervisionType.REGULAR, EngagementMode.COUNTS)
        val regular = classroom(fixture.otherSupervisor, "2026-10-20", "1", SupervisionType.REGULAR, EngagementMode.COUNTS)
        val notFollowUp = runCatching {
            repo.createLink(fixture.otherSupervisor, regular.taskId, origin.taskId, null)
        }.exceptionOrNull()
        assertNotNull("非跟踪复评课堂不能建立复评关联", notFollowUp)

        val otherCourse = classroom(fixture.otherSupervisor, "2026-10-20", "2", SupervisionType.FOLLOW_UP,
            EngagementMode.COUNTS, courseId = fixture.otherCourse)
        val notSameCourse = runCatching {
            repo.createLink(fixture.otherSupervisor, otherCourse.taskId, origin.taskId, null)
        }.exceptionOrNull()
        assertNotNull("跨课程不能建立复评关联", notSameCourse)
    }

    private enum class EngagementMode { COUNTS, PERCENT, NONE }

    private data class Classroom(val taskId: Long, val issueId: Long?)

    private suspend fun classroom(
        supervisorId: Long,
        date: String,
        period: String,
        type: SupervisionType,
        mode: EngagementMode,
        courseId: Long = fixture.course,
        scores: List<Int> = listOf(5, 4, 3, 4),
        issues: List<String> = listOf("互动较少", "参与不均"),
        submit: Boolean = true,
    ): Classroom {
        val taskId = db.taskDao().insert(SupervisionTask(code = "LYX-$date-$period", courseId = courseId,
            supervisorId = supervisorId, supervisionDate = date, period = period, chapter = "测试章节",
            type = type, status = TaskStatus.PENDING_EVALUATION))
        db.taskDao().markClassStart(taskId, start)
        db.taskDao().markClassEnd(taskId, start + 45 * 60 * 1000L)
        when (mode) {
            EngagementMode.COUNTS -> {
                db.captureDao().insertEngagement(EngagementRecord(taskId = taskId, type = EngagementType.LISTENING,
                    presentCount = 30, engagedCount = 18, recordedAt = start + 60_000, offsetMs = 60_000))
                db.captureDao().insertEngagement(EngagementRecord(taskId = taskId, type = EngagementType.DISCUSSING,
                    presentCount = 30, engagedCount = 24, recordedAt = start + 600_000, offsetMs = 600_000))
                db.captureDao().insertEngagement(EngagementRecord(taskId = taskId, type = EngagementType.DISTRACTED,
                    presentCount = 30, engagedCount = 18, recordedAt = start + 1_200_000, offsetMs = 1_200_000))
            }
            EngagementMode.PERCENT -> {
                db.captureDao().insertEngagement(EngagementRecord(taskId = taskId, type = EngagementType.LISTENING,
                    engagedPercent = 60, recordedAt = start + 60_000, offsetMs = 60_000))
            }
            EngagementMode.NONE -> Unit
        }
        repeat(6) { index ->
            db.captureDao().insertEvent(InteractionEvent(taskId = taskId, type = InteractionType.TEACHER_QUESTION,
                occurredAt = start + (index + 1) * 300_000L, offsetMs = (index + 1) * 300_000L))
        }
        if (submit) {
            val draft = EvaluationDraft(
                Evaluation(taskId = taskId, contentScore = scores[0], designScore = scores[1],
                    interactionScore = scores[2], engagementScore = scores[3],
                    strengths = "讲解清楚", suggestions = "增加互动", overallOpinion = "总体良好"),
                issues.mapIndexed { index, text ->
                    EvaluationIssue(evaluationId = 0, description = text,
                        dimension = EvalDimension.INTERACTION, sortOrder = index)
                },
            )
            fixture.evaluations.save(supervisorId, taskId, draft, submit = true)
            val evaluation = db.evaluationDao().getByTask(taskId)!!
            val first = db.evaluationDao().observeIssues(evaluation.id).first().firstOrNull()
            return Classroom(taskId, first?.id)
        }
        return Classroom(taskId, null)
    }
}
