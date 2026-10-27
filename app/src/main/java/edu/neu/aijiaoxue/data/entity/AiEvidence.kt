package edu.neu.aijiaoxue.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * US26：AI 结论对应的课堂证据。
 * 材料或事件被删除（US11 删除重采、US12 撤销）时只断开关联，证据说明保留。
 */
@Entity(
    tableName = "ai_evidences",
    foreignKeys = [
        ForeignKey(
            entity = AiAnalysisResult::class, parentColumns = ["id"], childColumns = ["analysisId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SupervisionTask::class, parentColumns = ["id"], childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = Material::class, parentColumns = ["id"], childColumns = ["materialId"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = InteractionEvent::class, parentColumns = ["id"], childColumns = ["eventId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("analysisId"), Index("taskId"), Index("materialId"), Index("eventId")],
)
data class AiEvidence(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val analysisId: Long,
    val taskId: Long,
    val materialId: Long? = null,
    val eventId: Long? = null,
    val startMs: Long? = null,
    val endMs: Long? = null,
    val description: String,
    val sourceRefJson: String? = null,
)
