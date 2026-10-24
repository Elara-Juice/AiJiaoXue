package edu.neu.aijiaoxue.data.entity


import androidx.room.Entity
import androidx.room.PrimaryKey



/**
 * Sprint2 US27
 *
 * AI结果人工修正记录
 *
 * 用于：
 * 1. 保存督导对AI结果的审核
 * 2. 为Sprint3提供AI准确性分析数据
 */
@Entity(
    tableName = "ai_corrections"
)
data class AiCorrection(


    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,


    /**
     * 对应AI分析结果
     */
    val analysisId: Long,


    /**
     * 原AI结论
     */
    val originalContent: String,


    /**
     * 人工修正后的内容
     */
    val correctedContent: String,


    /**
     * 是否确认AI结果正确
     */
    val accepted: Boolean,


    /**
     * 修改时间
     */
    val createdAt: Long =
        System.currentTimeMillis(),

    /** 旧版本没有记录审核人，迁移时保持 null；必须重新审核后才能作为正式依据。 */
    val reviewerId: Long? = null,


)
