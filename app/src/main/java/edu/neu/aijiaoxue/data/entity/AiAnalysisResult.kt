package edu.neu.aijiaoxue.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import edu.neu.aijiaoxue.data.model.AiAnalysisType

/**
 * Sprint 2 AI 分析结果，Room 持久化对象；data.ai.AIResult 是分析器返回的临时对象。
 * 删除督导任务时级联删除，避免留下找不到任务的分析结果。
 */
@Entity(
    tableName = "ai_analysis_results",
    foreignKeys = [ForeignKey(
        entity = SupervisionTask::class, parentColumns = ["id"], childColumns = ["taskId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("taskId")],
)
data class AiAnalysisResult(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    val type: AiAnalysisType,
    val content: String,
    /** 可信度 0–1。 */
    val confidence: Float,
    /** 对应课堂时间范围，相对 classStartAt 的毫秒数。 */
    val startMs: Long? = null,
    val endMs: Long? = null,
    /** US27：督导已审核（确认或修正）。 */
    val verified: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)
