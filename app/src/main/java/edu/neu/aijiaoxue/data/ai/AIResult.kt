package edu.neu.aijiaoxue.data.ai

import edu.neu.aijiaoxue.data.model.AiAnalysisType

/** 分析器返回的临时结果，由 AIAnalysisRepository 转成 AiAnalysisResult 入库。 */
data class AIResult(
    val type: AiAnalysisType,
    val content: String,
    /** 可信度 0–1。 */
    val confidence: Float,
    /** 对应课堂时间范围，相对 classStartAt 的毫秒数。 */
    val startMs: Long? = null,
    val endMs: Long? = null,
    /** US26：支撑该结论的课堂证据。 */
    val evidences: List<AIEvidenceRef> = emptyList(),
)

/** 指向一条课堂材料或互动事件；两者都为空时表示时间片段证据。 */
data class AIEvidenceRef(
    val description: String,
    val materialId: Long? = null,
    val eventId: Long? = null,
    val startMs: Long? = null,
    val endMs: Long? = null,
)
