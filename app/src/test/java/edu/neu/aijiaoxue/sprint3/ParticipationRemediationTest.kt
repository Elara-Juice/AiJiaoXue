package edu.neu.aijiaoxue.sprint3

import edu.neu.aijiaoxue.data.analysis.*
import edu.neu.aijiaoxue.data.dao.AbnormalCourseRow
import edu.neu.aijiaoxue.data.entity.EngagementRecord
import edu.neu.aijiaoxue.data.entity.InteractionEvent
import edu.neu.aijiaoxue.data.model.*
import edu.neu.aijiaoxue.data.repository.CourseAttentionRules
import org.junit.Assert.*
import org.junit.Test

class ParticipationRemediationTest {
    private fun input(count: Int = 24, events: Boolean = true) = ParticipationInput(1000, 1000 + 1_200_000,
        listOf(EngagementRecord(id = 5, taskId = 1, type = EngagementType.DISCUSSING, presentCount = 30,
            engagedCount = count, recordedAt = 61_000, offsetMs = 60_000)),
        if (events) listOf(InteractionEvent(id = 7, taskId = 1, type = InteractionType.GROUP_DISCUSSION,
            occurredAt = 62_000, offsetMs = 61_000)) else emptyList(), emptyList(), null, emptyList(), emptyList())

    @Test fun twoNormalSourcesProduceLimitedFindingWithActualIds() {
        val result = ParticipationAnalysis.analyze(input())
        assertNull(result.insufficientNote)
        val finding = result.findings.single()
        assertTrue(finding.title.contains("正常对照"))
        assertTrue(finding.detail.contains("不代表全课堂"))
        assertTrue(finding.basis.contains("记录 5"))
        assertTrue(finding.basis.contains("7（61000ms）"))
        assertEquals(setOf(DataSourceKind.ENGAGEMENT_RECORD, DataSourceKind.INTERACTION_EVENT), finding.sources.map { it.kind }.toSet())
        assertEquals(60_000L, finding.startOffsetMs)
        assertEquals(61_000L, finding.endOffsetMs)
    }
    @Test fun conflictingSamplesStaySeparateAndInvalidPercentCannotBecomeNormal() {
        val low = ParticipationAnalysis.analyze(input(count = 3))
        assertTrue(low.findings.single().title.contains("差异"))
        assertTrue(low.anomalies.any { it.type == AnomalyType.LOW_ENGAGEMENT })
        val invalid = ParticipationAnalysis.analyze(input(count = 99))
        assertTrue(invalid.findings.isEmpty())
        assertNotNull(invalid.insufficientNote)
        assertTrue(ParticipationAnalysis.analyze(input(events = false)).findings.isEmpty())
    }

    private fun anomaly(from: Long, to: Long) = AnomalyInterval(AnomalyType.DISTRACTION_RUN,
        from, to, null, null, "测试异常", "两条采样记录", null, null)
    @Test fun intervalMapsAllOverlappingStagesAndNeverFillsGapWithNearestStage() {
        val stages = listOf(PhaseSegment("讲授", 0, 100), PhaseSegment("讨论", 100, 200),
            PhaseSegment("复合活动", 50, 150), PhaseSegment("总结", 300, 400))
        val mapped = ParticipationAnalysis.locatePhases(listOf(anomaly(75, 175)), stages).single()
        assertEquals(setOf("讲授", "讨论", "复合活动"), mapped.phaseLabel!!.split("；").toSet())
        assertTrue(mapped.phaseBasis!!.contains("75ms—100ms"))
        assertTrue(mapped.phaseBasis!!.contains("100ms—175ms"))
        assertNull(ParticipationAnalysis.locatePhases(listOf(anomaly(220, 250)), stages).single().phaseLabel)
    }
    @Test fun pointAtBoundaryBelongsToFollowingStageAndAbsentStagesClearStaleMapping() {
        val stages = listOf(PhaseSegment("讲授", 0, 100), PhaseSegment("讨论", 100, 200))
        assertEquals("讨论", ParticipationAnalysis.locatePhases(listOf(anomaly(100, 100)), stages).single().phaseLabel)
        val old = anomaly(100, 100).copy(phaseLabel = "旧标签", phaseBasis = "旧依据")
        assertNull(ParticipationAnalysis.locatePhases(listOf(old), emptyList()).single().phaseLabel)
    }

    @Test fun attentionThresholdSeparates59From60MissingAndUnpublished() {
        fun row(score: Int?, evaluationId: Long? = 1, status: TaskStatus = TaskStatus.COMPLETED) =
            AbnormalCourseRow(1, 1, evaluationId, "课程", 2, "教师", "2026-10-20", "1", score, status)
        assertEquals(CourseAttentionState.ATTENTION, CourseAttentionRules.classify(row(59)).state)
        assertEquals(CourseAttentionState.NORMAL, CourseAttentionRules.classify(row(60)).state)
        assertEquals(CourseAttentionState.UNDETERMINED, CourseAttentionRules.classify(row(null)).state)
        assertEquals(CourseAttentionState.UNDETERMINED, CourseAttentionRules.classify(row(101)).state)
        assertEquals(CourseAttentionState.UNPUBLISHED, CourseAttentionRules.classify(row(20, null)).state)
        assertEquals(CourseAttentionState.UNPUBLISHED, CourseAttentionRules.classify(row(20, 1, TaskStatus.PENDING_EVALUATION)).state)
    }
}
