package edu.neu.aijiaoxue.sprint3

import edu.neu.aijiaoxue.data.analysis.ParticipationAnalysis
import edu.neu.aijiaoxue.data.analysis.ParticipationInput
import edu.neu.aijiaoxue.data.analysis.PhaseSegment
import edu.neu.aijiaoxue.data.entity.Evaluation
import edu.neu.aijiaoxue.data.entity.EvaluationIssue
import edu.neu.aijiaoxue.data.entity.EngagementRecord
import edu.neu.aijiaoxue.data.entity.InteractionEvent
import edu.neu.aijiaoxue.data.entity.Material
import edu.neu.aijiaoxue.data.model.AnomalyType
import edu.neu.aijiaoxue.data.model.DataSourceKind
import edu.neu.aijiaoxue.data.model.EngagementType
import edu.neu.aijiaoxue.data.model.EvalStatus
import edu.neu.aijiaoxue.data.model.InteractionType
import edu.neu.aijiaoxue.data.model.MaterialType
import edu.neu.aijiaoxue.data.model.VerificationRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * US24/US25：多源参与结论与参与异常区间。
 * 只有真实输入才产生结论，缺少已审核的环节结论时不编造环节。
 */
class ParticipationAnalysisTest {
    private fun engagement(id: Long, type: EngagementType, offset: Long, engaged: Int? = null, present: Int? = null) =
        EngagementRecord(id = id, taskId = 1L, type = type, presentCount = present, engagedCount = engaged,
            recordedAt = 1_000L + offset, offsetMs = offset)

    private fun event(id: Long, offset: Long) =
        InteractionEvent(id = id, taskId = 1L, type = InteractionType.TEACHER_QUESTION,
            occurredAt = 1_000L + offset, offsetMs = offset)

    private fun material(id: Long, offset: Long) =
        Material(id = id, taskId = 1L, type = MaterialType.PPT, filePath = "tasks/1/photo/$id.jpg",
            capturedAt = 1_000L + offset, offsetMs = offset)

    private val evaluation = Evaluation(taskId = 1L, contentScore = 5, designScore = 4, interactionScore = 3,
        engagementScore = 4, totalScore = 81, status = EvalStatus.SUBMITTED, submittedAt = 2_000L)

    @Test
    fun singleSourceDoesNotProduceMultiSourceConclusion() {
        val input = ParticipationInput(0L, 45 * 60 * 1000L,
            engagements = listOf(engagement(1, EngagementType.LISTENING, 60_000, 18, 30)),
            events = emptyList(), materials = emptyList(), evaluation = null,
            evaluationIssues = emptyList(), phases = emptyList())
        val insight = ParticipationAnalysis.analyze(input)
        assertNotNull(insight.insufficientNote)
        assertTrue(insight.findings.isEmpty())
        assertEquals(listOf(DataSourceKind.ENGAGEMENT_RECORD), insight.availableSourceKinds)
    }

    @Test
    fun twoSourcesProduceFindingWithSourceAndTimeRange() {
        val input = ParticipationInput(0L, 45 * 60 * 1000L,
            engagements = listOf(engagement(1, EngagementType.LISTENING, 60_000, 18, 30)),
            events = emptyList(), materials = emptyList(), evaluation = evaluation,
            evaluationIssues = emptyList(), phases = emptyList())
        val insight = ParticipationAnalysis.analyze(input)
        assertNull(insight.insufficientNote)
        val finding = insight.findings.first { it.title.contains("学生参与") }
        assertTrue(finding.sources.map { it.kind }.containsAll(
            listOf(DataSourceKind.ENGAGEMENT_RECORD, DataSourceKind.EVALUATION)))
        assertEquals(60_000L, finding.startOffsetMs)
        assertTrue(finding.basis.contains("参与状态记录 1 条"))
    }

    @Test
    fun consecutiveDistractionIsDetectedAsInterval() {
        val input = ParticipationInput(0L, 45 * 60 * 1000L,
            engagements = listOf(
                engagement(1, EngagementType.DISTRACTED, 100_000),
                engagement(2, EngagementType.DISTRACTED, 200_000),
                engagement(3, EngagementType.LISTENING, 300_000),
            ),
            events = emptyList(), materials = emptyList(), evaluation = null,
            evaluationIssues = emptyList(), phases = emptyList())
        val anomaly = ParticipationAnalysis.detectAnomalies(input)
            .single { it.type == AnomalyType.DISTRACTION_RUN }
        assertEquals(100_000L, anomaly.startOffsetMs)
        assertEquals(200_000L, anomaly.endOffsetMs)
    }

    @Test
    fun longSilenceBetweenEventsIsDetected() {
        val input = ParticipationInput(0L, 45 * 60 * 1000L,
            engagements = emptyList(),
            events = listOf(event(1, 60_000), event(2, 12 * 60 * 1000L)),
            materials = emptyList(), evaluation = null,
            evaluationIssues = emptyList(), phases = emptyList())
        val anomaly = ParticipationAnalysis.detectAnomalies(input)
            .single { it.type == AnomalyType.NO_INTERACTION }
        assertEquals(60_000L, anomaly.startOffsetMs)
    }

    @Test
    fun phaseIsLocatedOnlyWithReviewedPhaseSegment() {
        val input = ParticipationInput(0L, 45 * 60 * 1000L,
            engagements = listOf(
                engagement(1, EngagementType.DISTRACTED, 100_000),
                engagement(2, EngagementType.DISTRACTED, 200_000),
            ),
            events = emptyList(), materials = emptyList(), evaluation = null,
            evaluationIssues = emptyList(), phases = emptyList())
        val withoutPhase = ParticipationAnalysis.locatePhases(ParticipationAnalysis.detectAnomalies(input), emptyList())
        assertNull(withoutPhase.single().phaseLabel)

        val withPhase = ParticipationAnalysis.locatePhases(
            ParticipationAnalysis.detectAnomalies(input),
            listOf(PhaseSegment("讲授", 0L, 5 * 60 * 1000L)),
        )
        assertEquals("讲授", withPhase.single().phaseLabel)
    }

    @Test
    fun materialCountIsNotUsedAsContentAnalysis() {
        val input = ParticipationInput(0L, 45 * 60 * 1000L,
            engagements = listOf(engagement(1, EngagementType.LISTENING, 60_000, 18, 30)),
            events = emptyList(), materials = listOf(material(1, 60_000), material(2, 70_000)),
            evaluation = null, evaluationIssues = emptyList(), phases = emptyList())
        val finding = ParticipationAnalysis.analyze(input).findings
            .single { it.title.contains("采集材料") }
        assertTrue(finding.detail.contains("不代表 PPT 内容"))
    }

    @Test
    fun similarIssueIsDetectedByTextOverlap() {
        assertTrue(VerificationRules.isSimilarIssue("课堂上师生互动较少", "本节课师生互动偏少"))
        assertTrue(!VerificationRules.isSimilarIssue("课堂上师生互动较少", "板书书写不够工整"))
    }
}
