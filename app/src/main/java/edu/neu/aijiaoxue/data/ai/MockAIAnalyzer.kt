package edu.neu.aijiaoxue.data.ai

import edu.neu.aijiaoxue.data.entity.EngagementRecord
import edu.neu.aijiaoxue.data.entity.InteractionEvent
import edu.neu.aijiaoxue.data.entity.Material
import edu.neu.aijiaoxue.data.model.AiAnalysisType
import edu.neu.aijiaoxue.data.model.EngagementType
import edu.neu.aijiaoxue.data.model.MaterialType

/**
 * Sprint 2 模拟分析器：按已采集的材料、互动事件和参与状态用规则生成结论，
 * 不联网、不调用 AI 服务（AGENTS.md 硬性规则 10）。证据都指向真实记录，便于追溯。
 */
class MockAIAnalyzer : AIAnalyzer {
    override suspend fun analyze(
        taskId: Long,
        materials: List<Material>,
        events: List<InteractionEvent>,
        engagements: List<EngagementRecord>,
    ): List<AIResult> {
        val classEnd = (materials.map { it.offsetMs + (it.durationMs ?: 0) } +
            events.map { it.offsetMs } + engagements.map { it.offsetMs }).maxOrNull() ?: 0L
        return listOf(phases(materials, classEnd), interaction(events, classEnd), engagement(engagements, classEnd))
    }

    /** US19：按 PPT、板书拍照的时间点粗分教学环节。 */
    private fun phases(materials: List<Material>, classEnd: Long): AIResult {
        val slides = materials.filter { it.type == MaterialType.PPT || it.type == MaterialType.BLACKBOARD }
        val content = if (slides.isEmpty()) "未采集到 PPT 或板书照片，无法识别教学环节"
        else "根据 ${slides.size} 张 PPT/板书照片，课堂包含导入、讲授、练习、总结等环节"
        return AIResult(
            type = AiAnalysisType.BOPPPS,
            content = content,
            confidence = if (slides.isEmpty()) 0.3f else (0.6f + 0.05f * slides.size).coerceAtMost(0.9f),
            startMs = 0L,
            endMs = classEnd,
            evidences = slides.map {
                AIEvidenceRef("${it.type.label}照片", materialId = it.id, startMs = it.offsetMs, endMs = it.offsetMs)
            },
        )
    }

    /** US23：按互动标注统计频次。 */
    private fun interaction(events: List<InteractionEvent>, classEnd: Long): AIResult {
        val counts = events.groupingBy { it.type }.eachCount()
        val content = if (events.isEmpty()) "未标注互动事件，课堂以讲授为主"
        else "共标注互动 ${events.size} 次：" + counts.entries.joinToString("、") { "${it.key.label} ${it.value} 次" }
        return AIResult(
            type = AiAnalysisType.INTERACTION,
            content = content,
            confidence = if (events.isEmpty()) 0.5f else 0.85f,
            startMs = events.minOfOrNull { it.offsetMs } ?: 0L,
            endMs = events.maxOfOrNull { it.offsetMs } ?: classEnd,
            evidences = events.map {
                AIEvidenceRef(it.type.label + (it.note?.let { n -> "：$n" } ?: ""), eventId = it.id,
                    startMs = it.offsetMs, endMs = it.offsetMs)
            },
        )
    }

    /** US13 记录的参与状态汇总；参与状态没有独立外键，证据按时间点记录。 */
    private fun engagement(records: List<EngagementRecord>, classEnd: Long): AIResult {
        val distracted = records.count { it.type == EngagementType.DISTRACTED }
        val content = when {
            records.isEmpty() -> "未记录学生参与状态"
            distracted * 2 > records.size -> "低头/走神记录较多（$distracted/${records.size}），建议关注课堂节奏"
            else -> "学生参与状态整体良好（${records.size} 条记录中走神 $distracted 条）"
        }
        return AIResult(
            type = AiAnalysisType.ENGAGEMENT,
            content = content,
            confidence = if (records.isEmpty()) 0.3f else 0.8f,
            startMs = records.minOfOrNull { it.offsetMs } ?: 0L,
            endMs = records.maxOfOrNull { it.offsetMs } ?: classEnd,
            evidences = records.map {
                AIEvidenceRef(it.type.label + (it.description?.let { d -> "：$d" } ?: ""),
                    startMs = it.offsetMs, endMs = it.offsetMs)
            },
        )
    }
}
