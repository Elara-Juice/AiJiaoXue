package edu.neu.aijiaoxue.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** US43/44：实际实施另存历史，不能覆盖已确认的计划正文。 */
@Entity(
    tableName = "improvement_executions",
    foreignKeys = [
        ForeignKey(entity = ImprovementMeasure::class, parentColumns = ["id"], childColumns = ["measureId"]),
        ForeignKey(entity = User::class, parentColumns = ["id"], childColumns = ["recordedBy"]),
    ],
    indices = [Index(value = ["measureId", "requestKey"], unique = true), Index("recordedBy")],
)
data class ImprovementExecution(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val measureId: Long,
    val implementationNote: String,
    val actualAdjustment: String,
    val implementedAt: Long,
    val recordedBy: Long,
    val createdAt: Long = System.currentTimeMillis(),
    val requestKey: String,
)
