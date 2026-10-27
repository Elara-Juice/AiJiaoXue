package edu.neu.aijiaoxue.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** US27：督导对 AI 结论的审核记录，同时为 Sprint 3 的 AI 准确性分析留数据。 */
@Entity(
    tableName = "ai_corrections",
    foreignKeys = [
        ForeignKey(
            entity = AiAnalysisResult::class, parentColumns = ["id"], childColumns = ["analysisId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(entity = User::class, parentColumns = ["id"], childColumns = ["reviewerId"]),
    ],
    indices = [Index("analysisId"), Index("reviewerId")],
)
data class AiCorrection(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val analysisId: Long,
    /** 历史版本没有记录审核人时保持 null；不能倒填任务负责人作为审核事实。 */
    val reviewerId: Long? = null,
    val originalContent: String,
    val correctedContent: String,
    /** 督导确认 AI 结论无需修改。 */
    val accepted: Boolean,
    val createdAt: Long = System.currentTimeMillis(),
    val originalPayloadHash: String? = null,
    val correctedPayloadJson: String? = null,
)
