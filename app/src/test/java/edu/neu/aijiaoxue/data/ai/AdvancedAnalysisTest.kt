package edu.neu.aijiaoxue.data.ai

import edu.neu.aijiaoxue.data.entity.InteractionEvent
import edu.neu.aijiaoxue.data.entity.Material
import edu.neu.aijiaoxue.data.entity.AiAnalysisResult
import edu.neu.aijiaoxue.data.entity.AiCorrection
import edu.neu.aijiaoxue.data.model.AiAnalysisType
import edu.neu.aijiaoxue.data.model.InteractionType
import edu.neu.aijiaoxue.data.model.MaterialType
import edu.neu.aijiaoxue.data.repository.createReviewedAnalysisSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedAnalysisTest {
    private val analyzer = MockAIAnalyzer()

    @Test
    fun us18FindsRepeatedExpressionInRecordedNotes() = runBlocking {
        val result = analyze(
            materials = listOf(material(id = 11, offsetMs = 1_200, note = "我们现在开始，今天我们现在开始。")),
        ).single { it.type == AiAnalysisType.VERBAL_REPETITION }

        assertTrue(result.content.contains("我们现在开始"))
        assertTrue(result.evidences.isNotEmpty())
    }

    @Test
    fun us18ReportsInsufficientInputWithoutInventingConclusion() = runBlocking {
        val result = analyze(
            materials = listOf(material(id = 11, offsetMs = 1_200, note = "   ")),
            events = listOf(event(id = 21, offsetMs = 2_000, note = null)),
        ).single { it.type == AiAnalysisType.VERBAL_REPETITION }

        assertTrue(result.content.contains("输入不足/无法分析"))
        assertTrue(result.evidences.isEmpty())
    }

    @Test
    fun us18EvidenceTracesRepeatedTextToSourceAndClassPosition() = runBlocking {
        val result = analyze(
            materials = listOf(material(id = 11, offsetMs = 1_200, note = "今天我们继续，今天我们继续。")),
            events = listOf(event(id = 21, offsetMs = 2_400, note = "今天我们继续。")),
        ).single { it.type == AiAnalysisType.VERBAL_REPETITION }

        assertTrue(result.evidences.any { it.materialId == 11L && it.startMs == 1_200L })
        assertTrue(result.evidences.any { it.eventId == 21L && it.startMs == 2_400L })
        assertEquals(1_200L, result.startMs)
        assertEquals(2_400L, result.endMs)
    }

    @Test
    fun us21BuildsTransitionIntervalsFromMaterialsAndEvents() = runBlocking {
        val result = analyze(
            materials = listOf(material(id = 31, offsetMs = 5_000, note = null)),
            events = listOf(event(id = 42, offsetMs = 15_000, note = null)),
        ).single { it.type == AiAnalysisType.TEACHING_TRANSITION }

        assertTrue(result.content.contains("方法："))
        assertTrue(result.content.contains("记录类型切换线索（材料→互动事件）"))
        assertTrue(result.content.contains("依据：材料（PPT）"))
        assertTrue(result.content.contains("互动事件（教师提问）"))
        assertTrue(result.content.contains("5000–15000ms"))
        assertEquals(5_000L, result.startMs)
        assertEquals(15_000L, result.endMs)
        assertTrue(result.evidences.any { it.materialId == 31L && it.startMs == 5_000L })
        assertTrue(result.evidences.any { it.eventId == 42L && it.startMs == 15_000L })
    }

    @Test
    fun us21ReportsInsufficientInputWhenOnlyOneTimePointExists() = runBlocking {
        val result = analyze(
            materials = listOf(material(id = 31, offsetMs = 5_000, note = null)),
        ).single { it.type == AiAnalysisType.TEACHING_TRANSITION }

        assertTrue(result.content.contains("输入不足/无法分析"))
        assertTrue(result.content.contains("两个不同时间点"))
        assertEquals(5_000L, result.startMs)
        assertEquals(5_000L, result.endMs)
        assertTrue(result.evidences.any { it.materialId == 31L })
    }

    @Test
    fun us21DoesNotCreateAnIntervalWithoutTimedSources() = runBlocking {
        val result = analyze().single { it.type == AiAnalysisType.TEACHING_TRANSITION }

        assertTrue(result.content.contains("输入不足/无法分析"))
        assertTrue(result.evidences.isEmpty())
        assertFalse(result.content.contains("发现 "))
    }

    @Test
    fun reviewedUs18AndUs21ResultsRetainTypeContentAndEvidenceSummary() = runBlocking {
        val analyzed = analyze(
            materials = listOf(material(id = 51, offsetMs = 1_000, note = "今天我们开始，今天我们开始。")),
            events = listOf(event(id = 52, offsetMs = 2_000, note = "继续讨论")),
        )
        listOf(AiAnalysisType.VERBAL_REPETITION, AiAnalysisType.TEACHING_TRANSITION).forEachIndexed { index, type ->
            val result = analyzed.single { it.type == type }
            val persisted = AiAnalysisResult(
                id = 100 + index.toLong(), taskId = 1, type = result.type, content = result.content,
                confidence = result.confidence, startMs = result.startMs, endMs = result.endMs, verified = true,
            )
            val evidenceSummary = result.evidences.joinToString("\n") { it.description }
            val correction = AiCorrection(
                id = 200 + index.toLong(), analysisId = persisted.id, reviewerId = 7,
                originalContent = persisted.content, correctedContent = " 人工核准：${persisted.content} ",
                accepted = false, createdAt = 300 + index.toLong(),
            )

            val snapshot = createReviewedAnalysisSnapshot(
                persisted, correction, actorId = 7, evidenceSummary = evidenceSummary,
            )

            assertEquals(type.name, snapshot?.type)
            assertEquals(persisted.content, snapshot?.originalContent)
            assertEquals("人工核准：${persisted.content}", snapshot?.effectiveContent)
            assertEquals(evidenceSummary, snapshot?.evidenceSummary)
            assertEquals("analysis-${persisted.id}/review-${correction.id}", snapshot?.versionKey)
        }
    }

    @Test
    fun unreviewedOrMismatchedCorrectionCannotBecomeFormalSnapshot() = runBlocking {
        val result = analyze(
            materials = listOf(material(id = 61, offsetMs = 1_000, note = "今天我们开始，今天我们开始。")),
        ).single { it.type == AiAnalysisType.VERBAL_REPETITION }
        val analysis = AiAnalysisResult(
            id = 101, taskId = 1, type = result.type, content = result.content,
            confidence = result.confidence, verified = false,
        )
        val correction = AiCorrection(
            id = 201, analysisId = analysis.id, reviewerId = 7,
            originalContent = analysis.content, correctedContent = analysis.content, accepted = true,
        )

        assertEquals(null, createReviewedAnalysisSnapshot(analysis, correction, 7, "真实证据摘要"))
        assertEquals(null, createReviewedAnalysisSnapshot(analysis.copy(verified = true),
            correction.copy(reviewerId = 8), 7, "真实证据摘要"))
        assertEquals(null, createReviewedAnalysisSnapshot(analysis.copy(verified = true),
            correction.copy(originalContent = "已过期的原始版本"), 7, "真实证据摘要"))
        assertEquals(null, createReviewedAnalysisSnapshot(analysis.copy(verified = true),
            correction.copy(correctedContent = "   "), 7, "真实证据摘要"))
    }

    private suspend fun analyze(
        materials: List<Material> = emptyList(),
        events: List<InteractionEvent> = emptyList(),
    ) = analyzer.analyze(taskId = 1, materials = materials, events = events, engagements = emptyList())

    private fun material(id: Long, offsetMs: Long, note: String?) = Material(
        id = id,
        taskId = 1,
        type = MaterialType.PPT,
        filePath = "tasks/1/material-$id",
        capturedAt = offsetMs,
        offsetMs = offsetMs,
        note = note,
    )

    private fun event(id: Long, offsetMs: Long, note: String?) = InteractionEvent(
        id = id,
        taskId = 1,
        type = InteractionType.TEACHER_QUESTION,
        occurredAt = offsetMs,
        offsetMs = offsetMs,
        note = note,
    )
}
