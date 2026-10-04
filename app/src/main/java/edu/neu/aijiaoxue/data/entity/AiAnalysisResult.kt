package edu.neu.aijiaoxue.data.entity


import androidx.room.Entity
import androidx.room.PrimaryKey


/**
 * Sprint2 AI分析结果实体
 *
 * 用于保存AI分析后的正式结果。
 *
 * 与 data.ai.AIResult 区别：
 *
 * AIResult:
 * AI服务运行过程中的临时对象
 *
 * AiAnalysisResult:
 * Room数据库持久化对象
 *
 * Sprint3:
 * 用于历史趋势分析和教学改进验证
 */
@Entity(
    tableName = "ai_analysis_results"
)
data class AiAnalysisResult(


    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,


    /**
     * 所属督导任务
     */
    val taskId: Long,


    /**
     * 分析类型
     *
     * BOPPPS
     * INTERACTION
     * ENGAGEMENT
     */
    val type: String,


    /**
     * AI分析文本
     */
    val content: String,


    /**
     * AI可信度 0-1
     */
    val confidence: Float,


    /**
     * 对应课堂时间范围
     */
    val startMs: Long? = null,


    val endMs: Long? = null,


    /**
     * 是否人工审核
     *
     * US27
     */
    val verified: Boolean = false,


    /**
     * 创建时间
     */
    val createdAt: Long =
        System.currentTimeMillis()

)