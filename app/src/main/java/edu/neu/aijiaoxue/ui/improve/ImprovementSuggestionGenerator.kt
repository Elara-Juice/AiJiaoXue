package edu.neu.aijiaoxue.ui.improve

import edu.neu.aijiaoxue.data.entity.*
import edu.neu.aijiaoxue.data.model.*

/** 上游真实生成服务接入点；只能接收已发布的审核快照，不接收原始课堂材料。 */
interface ImprovementSuggestionGenerator {
    suspend fun generate(item: ImprovementItem, dimension: EvalDimension?,
        reviews: List<ReviewedAnalysisSnapshot>): List<ImprovementSuggestion>
}

/**
 * 真实AI生成服务尚未接入。此实现公开标注为规则辅助，按正式问题维度及审核结论选取
 * 教学策略，不声称识别了课堂缺失环节、自动转写或验证了建议效果。
 */
class RuleBasedImprovementSuggestionGenerator : ImprovementSuggestionGenerator {
    override suspend fun generate(item: ImprovementItem, dimension: EvalDimension?,
        reviews: List<ReviewedAnalysisSnapshot>): List<ImprovementSuggestion> {
        val effectiveDimension = dimension ?: inferDimension(item.description) ?: return emptyList()
        val types = when (effectiveDimension) {
            EvalDimension.CONTENT, EvalDimension.DESIGN -> setOf("BOPPPS")
            EvalDimension.INTERACTION -> setOf("INTERACTION", "BOPPPS")
            EvalDimension.ENGAGEMENT -> setOf("ENGAGEMENT", "INTERACTION")
        }
        val relevant = reviews.filter { it.type in types }
        if (relevant.isEmpty()) return emptyList()
        // 每个事项给出一条可执行候选；优先专门维度，不把所有审核内容复制给每个问题。
        val review = relevant.firstOrNull { it.type == types.first() } ?: relevant.first()
        val (stage, action) = strategy(effectiveDimension, item.description + " " + review.effectiveContent)
        return listOf(ImprovementSuggestion(
            planId = item.planId, itemId = item.id, sourceSnapshotId = review.id,
            content = "针对“${item.description.trim()}”，$action",
            problemSnapshot = item.description, bopppsStage = stage,
            rationale = "正式问题：${item.description}\n已审核参考：${review.effectiveContent}\n" +
                "按${effectiveDimension.label}与相关教学环节匹配规则策略；请结合实际课堂判断适用性。",
            generatorMode = SuggestionOrigin.RULE_BASED, generatorVersion = VERSION,
            reviewVersionKey = review.versionKey, createdAt = System.currentTimeMillis(),
        ))
    }

    private fun inferDimension(text: String): EvalDimension? = when {
        listOf("参与", "走神", "专注").any { it in text } -> EvalDimension.ENGAGEMENT
        listOf("互动", "提问", "讨论", "反馈").any { it in text } -> EvalDimension.INTERACTION
        listOf("导入", "目标", "前测", "后测", "总结", "设计", "环节").any { it in text } -> EvalDimension.DESIGN
        listOf("内容", "知识", "概念", "重点", "难点").any { it in text } -> EvalDimension.CONTENT
        else -> null
    }

    private fun strategy(dimension: EvalDimension, text: String): Pair<String, String> = when {
        dimension == EvalDimension.INTERACTION -> "参与式学习（P）" to
            "在下一次相关课堂设置分层提问，先独立思考再同伴讨论，轮换邀请学生说明理由，并记录反馈用于课后调整。"
        dimension == EvalDimension.ENGAGEMENT -> "参与式学习（P）" to
            "将相关知识点安排为短时小组任务，明确每位成员的角色与产出，抽查不同小组的理解并记录需要再讲解的内容。"
        "前测" in text -> "前测（P）" to
            "课前设计两道对应先备知识的诊断题，根据回答调整讲解起点，并保留答题摘要供课后检查。"
        "后测" in text || "检验" in text -> "后测（P）" to
            "围绕本课目标设计一道迁移应用题，在课堂结束前收集回答，针对常见错误安排后续补充练习。"
        "总结" in text -> "总结（S）" to
            "预留课堂结束前的回顾时间，请学生用概念关系图归纳关键知识，再由教师补充遗漏并布置一项巩固练习。"
        "导入" in text -> "导入（B）" to
            "用与课程目标相关的真实情境提出问题，邀请学生说明已有认识，并明确该情境与本课知识点的联系。"
        dimension == EvalDimension.DESIGN -> "目标（O）" to
            "将本课目标改写为可观察的学习产出，逐项对应课堂活动和检查方式，并在开课与结束时分别说明和回顾。"
        else -> "目标（O）与参与式学习（P）" to
            "围绕关键概念设计一个正例与一个易混淆反例，让学生解释差异，再用一道应用题检查理解并据此补充讲解。"
    }

    companion object { const val VERSION = "local-teaching-rules-v1" }
}
