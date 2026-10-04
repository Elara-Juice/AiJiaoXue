package edu.neu.aijiaoxue.data.entity


import androidx.room.Entity
import androidx.room.PrimaryKey


/**
 * Sprint2 AI证据关联实体
 *
 * US26:
 * AI结论追溯课堂证据
 *
 * Sprint3:
 * 用于问题定位和改进验证
 */
@Entity(
    tableName = "ai_evidences"
)
data class AiEvidence(


    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,


    /**
     * 对应AI分析结果
     */
    val analysisId: Long,


    /**
     * 当前督导任务
     */
    val taskId: Long,


    /**
     * 关联材料
     *
     * PPT、图片、音频等
     */
    val materialId: Long? = null,


    /**
     * 关联课堂事件
     */
    val eventId: Long? = null,


    /**
     * 时间片段
     */
    val startMs: Long? = null,


    val endMs: Long? = null,


    /**
     * 证据说明
     */
    val description: String

)