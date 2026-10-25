package edu.neu.aijiaoxue.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** US43：教师实施证据与原课堂采集材料分开存储。 */
@Entity(
    tableName = "improvement_evidences",
    foreignKeys = [ForeignKey(
        entity = ImprovementExecution::class, parentColumns = ["id"], childColumns = ["executionId"],
    )],
    indices = [Index(value = ["executionId", "requestKey"], unique = true)],
)
data class ImprovementEvidence(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val executionId: Long,
    val relativePath: String,
    val displayName: String,
    val mimeType: String,
    val byteSize: Long,
    val description: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val requestKey: String,
)
