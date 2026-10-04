package edu.neu.aijiaoxue.data.ai


/**
 * AI课堂分析结果
 *
 * Sprint2核心数据对象
 *
 * 后续Sprint3：
 * 用于历史趋势分析、教学改进验证
 */
data class AIResult(

    // 分析类型
    val type: String,


    // AI分析内容
    val content: String,


    // 可信度 0-1
    val confidence: Float,


    // 对应课堂时间范围(ms)
    val startMs: Long? = null,

    val endMs: Long? = null

)