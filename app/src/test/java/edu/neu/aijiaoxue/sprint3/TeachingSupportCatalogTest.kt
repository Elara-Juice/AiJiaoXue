package edu.neu.aijiaoxue.sprint3

import edu.neu.aijiaoxue.data.model.EvalDimension
import edu.neu.aijiaoxue.ui.growth.TeachingSupportCatalog
import java.net.URI
import org.junit.Assert.*
import org.junit.Test

class TeachingSupportCatalogTest {
    @Test fun blankGenericAndPositiveDescriptionsDoNotCreateDiagnoses() {
        for (problem in listOf("", " \n ", "课堂情况", "需要优化教学", "互动", "学生参与良好", "学生发言不少")) {
            for (dimension in listOf(null, EvalDimension.INTERACTION, EvalDimension.ENGAGEMENT)) {
                assertTrue("不应从 '$problem' 生成诊断", TeachingSupportCatalog.strategies(dimension, problem).isEmpty())
            }
        }
    }

    @Test fun concreteInteractionProblemHasActionableOptionsAndTeacherChoice() {
        val problem = "提问后只有少数学生回答"
        val results = TeachingSupportCatalog.strategies(EvalDimension.INTERACTION, problem)
        assertEquals(setOf("think-pair-share", "peer-instruction"), results.map { it.key }.toSet())
        results.forEach {
            assertTrue(it.rationale.contains(problem))
            assertTrue(it.rationale.contains("本地规则"))
            assertTrue(it.rationale.contains("教师"))
            assertTrue(it.rationale.contains("放弃"))
            assertTrue(it.content.contains("观察记录"))
            assertTrue(it.resourceKeys.isNotEmpty())
        }
    }

    @Test fun engagementAndPracticeIssuesGetRelevantActivities() {
        val engagement = TeachingSupportCatalog.strategies(EvalDimension.ENGAGEMENT, "后排学生参与率下降")
        assertEquals(setOf("minute-paper", "predict-observe-explain"), engagement.map { it.key }.toSet())
        assertTrue(engagement.all { it.content.contains("到场人数") })
        val practice = TeachingSupportCatalog.strategies(EvalDimension.ENGAGEMENT, "实践机会不足")
        assertEquals("predict-observe-explain", practice.single().key)
        assertTrue(practice.single().content.contains("预测"))
        assertTrue(practice.single().content.contains("解释"))
    }

    @Test fun shallowAnswersNeedReasoningAndMissingDiscussionIsStillAProblem() {
        val reasoning = TeachingSupportCatalog.strategies(EvalDimension.INTERACTION, "学生只回答答案，回答缺少理由")
        assertEquals("peer-instruction", reasoning.single().key)
        assertTrue(TeachingSupportCatalog.strategies(EvalDimension.INTERACTION, "没有讨论活动").isNotEmpty())
    }

    @Test fun negatedUncertainAndResolvedProblemsDoNotCreateCandidates() {
        for (problem in listOf(
            "未发现互动不足", "未发现学生参与率下降", "没有出现参与率下降", "无明显参与不足", "不是学生走神",
            "是否存在互动不足", "学生参与下降，证据不足", "可能存在互动不足", "互动不足已解决",
            "无数据，互动不足",
        )) {
            assertTrue(problem, TeachingSupportCatalog.strategies(null, problem).isEmpty())
        }
    }

    @Test fun nullDimensionRequiresExplicitIssueAndNegationStaysWithinItsClause() {
        val inferred = TeachingSupportCatalog.strategies(null, "未发现互动不足；后排学生参与率下降")
        assertEquals(setOf("minute-paper", "predict-observe-explain"), inferred.map { it.key }.toSet())
        assertEquals(inferred.map { it.key }, TeachingSupportCatalog.strategies(null, "未发现互动不足\n后排学生参与率下降").map { it.key })
        assertTrue(TeachingSupportCatalog.strategies(null, "其他安排").isEmpty())
        assertTrue(TeachingSupportCatalog.strategies(EvalDimension.CONTENT, "互动不足").isEmpty())
        assertTrue(TeachingSupportCatalog.strategies(EvalDimension.DESIGN, "学生参与不足").isEmpty())
        assertTrue(TeachingSupportCatalog.strategies(EvalDimension.INTERACTION, "学生参与不足").isEmpty())
    }

    @Test fun repeatedRequestsHaveStableKeysAndAllReferencesResolveOffline() {
        val problem = "互动不足；学生参与下降"
        val first = TeachingSupportCatalog.strategies(null, problem)
        assertEquals(first, TeachingSupportCatalog.strategies(null, problem))
        assertEquals(first.size, first.map { it.key }.distinct().size)
        assertEquals(4, first.size)
        val resources = TeachingSupportCatalog.resources.associateBy { it.key }
        first.forEach { strategy ->
            strategy.resourceKeys.forEach { key ->
                val resource = requireNotNull(resources[key])
                assertTrue(resource.summary.isNotBlank())
                assertTrue(resource.steps.isNotBlank())
                assertTrue(resource.summary.contains("教学参考"))
                assertTrue(resource.sourceName.isNotBlank())
                val uri = URI(resource.sourceUrl)
                assertEquals("https", uri.scheme)
                assertTrue(uri.host in setOf("teaching.cornell.edu", "teaching.berkeley.edu", "ctl.duke.edu", "itali.uq.edu.au"))
            }
        }
        assertEquals(4, resources.size)
    }

    @Test fun whitespaceDoesNotHideAConcreteConcern() {
        val compact = TeachingSupportCatalog.strategies(EvalDimension.ENGAGEMENT, "学生参与率下降")
        val spaced = TeachingSupportCatalog.strategies(EvalDimension.ENGAGEMENT, "  学生参与率　下降  ")
        assertEquals(compact.map { it.key }, spaced.map { it.key })
    }
}
