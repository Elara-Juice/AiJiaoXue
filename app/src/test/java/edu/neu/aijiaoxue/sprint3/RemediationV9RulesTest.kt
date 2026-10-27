package edu.neu.aijiaoxue.sprint3

import edu.neu.aijiaoxue.data.dao.GovernanceTaskRow
import edu.neu.aijiaoxue.data.dao.GovernanceVerificationRow
import edu.neu.aijiaoxue.data.entity.Evaluation
import edu.neu.aijiaoxue.data.entity.EvaluationIssue
import edu.neu.aijiaoxue.data.entity.MetricSnapshot
import edu.neu.aijiaoxue.data.model.*
import edu.neu.aijiaoxue.data.repository.GovernanceCalculations
import edu.neu.aijiaoxue.data.repository.GovernanceSummary
import edu.neu.aijiaoxue.ui.evaluation.EvaluationDraft
import edu.neu.aijiaoxue.ui.evaluation.evaluationError
import org.junit.Assert.*
import org.junit.Test

class RemediationV9RulesTest {
    private fun chronology(date: String = "2026-10-20", before: String = "1-2", afterDate: String = date,
                           after: String = "3-4", afterId: Long = 2) =
        ReviewChronologyV1.validate(1, date, before, afterId, afterDate, after)

    @Test fun sameDayMustUseNonOverlappingNumericPeriods() {
        assertEquals(ReviewChronologyStatus.VALID, chronology(before = "9-10", after = "11-12"))
        assertEquals(ReviewChronologyStatus.REVERSED, chronology(before = "5-6", after = "3-4"))
        assertEquals(ReviewChronologyStatus.OVERLAP, chronology(before = "3-4", after = "4-5"))
        assertEquals(ReviewChronologyStatus.UNKNOWN, chronology(after = ""))
        assertEquals(ReviewChronologyStatus.UNKNOWN, chronology(after = "13"))
        assertEquals(ReviewChronologyStatus.UNKNOWN, chronology(after = "4-3"))
    }
    @Test fun invalidDatesAndSameTasksCannotBeOrdered() {
        assertEquals(ReviewChronologyStatus.SAME_TASK, chronology(afterId = 1))
        assertEquals(ReviewChronologyStatus.UNKNOWN, chronology(date = "2026-02-30"))
        assertEquals(ReviewChronologyStatus.UNKNOWN, chronology(date = "2026-1-2"))
        assertEquals(ReviewChronologyStatus.REVERSED, chronology(afterDate = "2026-10-19"))
        assertEquals(ReviewChronologyStatus.VALID, chronology(before = "", afterDate = "2026-10-21", after = ""))
    }

    private val evaluation = Evaluation(taskId = 1, contentScore = 4, designScore = 4,
        interactionScore = 4, engagementScore = 4, overallOpinion = "已完成本次课堂观察")

    @Test fun explicitZeroIssuesIsDifferentFromUnfilledOrOmittedList() {
        assertNotNull(evaluationError(EvaluationDraft(evaluation)))
        assertNotNull(evaluationError(EvaluationDraft(evaluation.copy(issueDeclaration = IssueDeclaration.HAS_ISSUES))))
        val confirmed = evaluation.copy(issueDeclaration = IssueDeclaration.NO_ISSUES_CONFIRMED,
            issueDeclarationNote = "完整观察本次课堂，未发现需列出的主要问题")
        assertNull(evaluationError(EvaluationDraft(confirmed)))
        assertNotNull(evaluationError(EvaluationDraft(confirmed.copy(issueDeclarationNote = " "))))
        assertNotNull(evaluationError(EvaluationDraft(confirmed, listOf(EvaluationIssue(evaluationId = 0, description = "仍有问题")))))
    }

    private fun draft(verdict: VerificationVerdict = VerificationVerdict.IMPROVED,
                      presence: IssuePresence = IssuePresence.PRESENT, extent: ImprovementExtent = ImprovementExtent.PARTIAL,
                      conflict: String? = null) = VerificationDraft(verdict, presence, extent,
        "小组讨论的参与面扩大，但主动提问仍少", conflictExplanation = conflict)
    private fun metric(key: MetricKey, delta: Double, role: VerificationEvidenceRole = VerificationEvidenceRole.PRIMARY) =
        VerificationEvidenceFact(VerificationEvidenceKind.METRIC_PAIR, role, key, delta)
    private val observation = VerificationEvidenceFact(VerificationEvidenceKind.FOLLOW_UP_OBSERVATION, VerificationEvidenceRole.PRIMARY)
    private val noIssues = VerificationEvidenceFact(VerificationEvidenceKind.NO_ISSUES_DECLARATION, VerificationEvidenceRole.PRIMARY)

    @Test fun unrelatedGrowthAndTotalCannotProveOriginalIssueImproved() {
        assertNotNull(VerificationRulesV2.error(draft(), EvalDimension.INTERACTION, listOf(metric(MetricKey.EVALUATION_TOTAL, 20.0))))
        assertNotNull(VerificationRulesV2.error(draft(), EvalDimension.INTERACTION, listOf(metric(MetricKey.ENGAGEMENT_RATE, 20.0))))
        assertNotNull(VerificationRulesV2.error(draft(), EvalDimension.INTERACTION,
            listOf(metric(MetricKey.EVALUATION_TOTAL, 20.0, VerificationEvidenceRole.AUXILIARY))))
    }
    @Test fun remainingIssueCanImprovePartlyWithActualObservationAndExplanation() {
        assertNotNull(VerificationRulesV2.error(draft(), EvalDimension.INTERACTION, listOf(observation)))
        assertNull(VerificationRulesV2.error(draft(conflict = "数量未变但分组讨论覆盖更多学生"), EvalDimension.INTERACTION, listOf(observation)))
        assertNotNull(VerificationRulesV2.error(draft(conflict = "文字解释"), EvalDimension.INTERACTION,
            listOf(metric(MetricKey.INTERACTION_DENSITY, 1.0))))
    }
    @Test fun contradictoryDirectionNeedsFactsAndCanOnlySupportPartialImprovement() {
        val facts = listOf(metric(MetricKey.INTERACTION_DENSITY, -1.0), observation)
        assertNotNull(VerificationRulesV2.error(draft(), EvalDimension.INTERACTION, facts))
        assertNull(VerificationRulesV2.error(draft(conflict = "次数减少但讨论参与面扩大"), EvalDimension.INTERACTION, facts))
    }
    @Test fun absenceRequiresExplicitObservationNotOnlyIncreasingMetric() {
        val resolved = draft(presence = IssuePresence.NOT_OBSERVED, extent = ImprovementExtent.RESOLVED)
        assertNotNull(VerificationRulesV2.error(resolved, EvalDimension.INTERACTION, listOf(metric(MetricKey.INTERACTION_DENSITY, 1.0))))
        assertNull(VerificationRulesV2.error(resolved, EvalDimension.INTERACTION, listOf(noIssues)))
        assertNull(VerificationRulesV2.error(resolved, EvalDimension.INTERACTION, listOf(observation), similarIssuePresent = true))
    }
    @Test fun designMetricIsRelevantOnlyToDesignAndUsesOriginalFivePointScale() {
        val spec = MetricRules.spec(MetricKey.EVALUATION_DESIGN)
        assertEquals("manual_design_score_v1", spec.methodVersion)
        assertFalse(spec.durationSensitive)
        assertEquals(MetricKey.EVALUATION_DESIGN, VerificationRulesV2.primaryMetric(EvalDimension.DESIGN))
        assertNull(VerificationRulesV2.primaryMetric(EvalDimension.CONTENT))
        assertNotNull(VerificationRulesV2.error(draft(), EvalDimension.INTERACTION, listOf(metric(MetricKey.EVALUATION_DESIGN, 1.0))))
    }
    @Test fun noEvidenceMeansContinueObserving() {
        assertNotNull(VerificationRulesV2.error(draft(), EvalDimension.INTERACTION, emptyList()))
        assertNull(VerificationRulesV2.error(draft(VerificationVerdict.CONTINUE_OBSERVING,
            IssuePresence.UNDETERMINED, ImprovementExtent.UNDETERMINED), EvalDimension.INTERACTION, emptyList()))
    }
    @Test fun incompatibleMetricKeysAndNonFiniteNumbersNeverProduceDeltas() {
        fun value(key: MetricKey, number: Double) = MetricSnapshotView(key, number, "score", "single_evaluation",
            "submitted_design_dimension", "v1", null, null)
        assertTrue(MetricRules.compare(value(MetricKey.EVALUATION_DESIGN, Double.NaN),
            value(MetricKey.EVALUATION_DESIGN, 5.0)) is MetricRules.CompareOutcome.NotComparable)
        assertTrue(MetricRules.compare(value(MetricKey.EVALUATION_DESIGN, 4.0),
            value(MetricKey.EVALUATION_TOTAL, 5.0)) is MetricRules.CompareOutcome.NotComparable)
    }

    @Test fun governanceNeverInventsSameDayOrderOrFallsBackToOldImprovement() {
        fun task(id: Long) = GovernanceTaskRow(id, "T$id", "2026-10-20", "1-2", null, 1, "课", "C", 1,
            "教师", "督导", id, 80, null, "意见", 1000)
        fun snapshot(id: Long, value: Double) = MetricSnapshot(id, id, MetricKey.EVALUATION_TOTAL, value, "分", "百分制总分",
            100.0, MetricRules.SAMPLING_EVALUATION, "v1", 1, null, null, 1000)
        val pair = GovernanceCalculations.trends(listOf(task(1), task(2)), listOf(snapshot(1, 70.0), snapshot(2, 90.0)))
            .single().comparisons.single { it.metricKey == MetricKey.EVALUATION_TOTAL }
        assertNull(pair.outcome)
        assertEquals(ReviewChronologyStatus.OVERLAP.label, pair.note)
        val older = GovernanceVerificationRow(1, 1, 1, 10, "问题", 2, "T2", VerificationVerdict.IMPROVED, "依据", "督导", 1000, 2)
        val latest = older.copy(verificationId = 2, verifiedAt = 2000, ineligibilityReason = "证据失效")
        val summary = GovernanceSummary(2, 1, 1, emptyMap(), emptyMap(), 0, 0,
            GovernanceCalculations.latestVerifications(listOf(older, latest)))
        assertEquals(2L, summary.latestVerifications.single().verificationId)
        assertEquals(1, summary.pendingVerifications.size)
        assertTrue(summary.verificationCounts.isEmpty())
        assertNull(summary.improvementRate)
    }
}
