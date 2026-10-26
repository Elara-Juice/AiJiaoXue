package edu.neu.aijiaoxue.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** US37：保留正式反馈原文，教师说明单独保存，不改写督导评价。 */
@Entity(tableName = "teaching_highlights", foreignKeys = [
    ForeignKey(entity = SupervisionTask::class, parentColumns = ["id"], childColumns = ["taskId"], onDelete = ForeignKey.CASCADE),
], indices = [Index(value = ["taskId"], unique = true), Index("teacherId")])
data class TeachingHighlight(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    val teacherId: Long,
    val sourceText: String,
    val practice: String,
    val updatedAt: Long,
)

/** US39/40：草稿措施与规则/资源/亮点来源的关联；确认后的措施不可更改。 */
@Entity(tableName = "teaching_adoptions", foreignKeys = [
    ForeignKey(entity = ImprovementMeasure::class, parentColumns = ["id"], childColumns = ["measureId"], onDelete = ForeignKey.CASCADE),
], indices = [Index(value = ["measureId"], unique = true), Index(value = ["itemId", "sourceKey"], unique = true)])
data class TeachingAdoption(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val measureId: Long,
    val itemId: Long,
    val sourceKey: String,
    val sourceTitle: String,
    val sourceReference: String,
    val problemSnapshot: String,
    val originalContent: String,
    val adoptedAt: Long,
)
