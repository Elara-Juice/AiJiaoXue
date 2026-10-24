package edu.neu.aijiaoxue.sprint2

import edu.neu.aijiaoxue.data.entity.ImprovementItem
import edu.neu.aijiaoxue.data.entity.ReviewedAnalysisSnapshot
import edu.neu.aijiaoxue.data.model.*
import edu.neu.aijiaoxue.ui.improve.RuleBasedImprovementSuggestionGenerator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ImprovementSuggestionGeneratorTest {
    private val generator = RuleBasedImprovementSuggestionGenerator()
    private val item = ImprovementItem(id = 7, planId = 3, issueId = 2, source = ItemSource.SUPERVISOR,
        description = "后排学生参与不均", priority = Priority.HIGH)
    private fun review(id: Long, type: String, text: String) = ReviewedAnalysisSnapshot(
        id = id, publicationId = 10, analysisId = id + 100, correctionId = id + 200, reviewerId = 5,
        type = type, originalContent = "待审原文", effectiveContent = text, evidenceSummary = "人工核验的摘要",
        reviewedAt = 1234, versionKey = "review-$id")

    @Test fun engagementAdviceUsesRelevantReviewedSourceAndClearlyDeclaresRules() = runBlocking {
        val specific = review(2, "ENGAGEMENT", "后排学生在练习环节参与偏少")
        val advice = generator.generate(item, EvalDimension.ENGAGEMENT,
            listOf(review(1, "BOPPPS", "无关的前测分析"), specific)).single()
        assertEquals(item.id, advice.itemId)
        assertEquals(specific.id, advice.sourceSnapshotId)
        assertEquals(specific.versionKey, advice.reviewVersionKey)
        assertEquals(SuggestionOrigin.RULE_BASED, advice.generatorMode)
        assertTrue(advice.generatorVersion.isNotBlank())
        assertTrue(advice.content.contains(item.description))
        assertTrue(advice.rationale.contains(specific.effectiveContent))
        assertFalse(advice.rationale.contains("无关的前测分析"))
    }

    @Test fun missingRelevantEvidenceAndUnclassifiedCustomItemProduceNoInventedAdvice() = runBlocking {
        assertTrue(generator.generate(item, EvalDimension.ENGAGEMENT, emptyList()).isEmpty())
        assertTrue(generator.generate(item, EvalDimension.ENGAGEMENT,
            listOf(review(1, "BOPPPS", "仅分析了目标说明"))).isEmpty())
        assertTrue(generator.generate(item.copy(issueId = null, source = ItemSource.TEACHER,
            description = "教师其他安排"), null, listOf(review(2, "INTERACTION", "互动分析"))).isEmpty())
    }

    @Test fun designAdviceKeepsProblemAndPublishedReviewRatherThanRawOriginal() = runBlocking {
        val problem = item.copy(description = "前测与先备知识不匹配")
        val reviewed = review(3, "BOPPPS", "前测题需覆盖学生先备知识")
        val advice = generator.generate(problem, EvalDimension.DESIGN, listOf(reviewed)).single()
        assertEquals("前测（P）", advice.bopppsStage)
        assertTrue(advice.content.contains(problem.description))
        assertTrue(advice.rationale.contains(reviewed.effectiveContent))
        assertFalse(advice.rationale.contains(reviewed.originalContent))
    }
}
