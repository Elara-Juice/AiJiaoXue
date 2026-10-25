package edu.neu.aijiaoxue.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import edu.neu.aijiaoxue.data.model.SuggestionOrigin

/** US27/38：提交评价时发布一次，空集合也保留发布记录，禁止后来悄悄回填。 */
@Entity(tableName = "review_publications", indices = [Index(value = ["evaluationId"], unique = true), Index("taskId")])
data class ReviewPublication(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val evaluationId: Long,
    val taskId: Long,
    val supervisorId: Long,
    val publishedAt: Long,
)

/** 不向易被重分析删除的 ai_* 表建立级联外键；原始ID仅作历史标识。 */
@Entity(
    tableName = "reviewed_analysis_snapshots",
    foreignKeys = [ForeignKey(entity = ReviewPublication::class, parentColumns = ["id"],
        childColumns = ["publicationId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("publicationId")],
)
data class ReviewedAnalysisSnapshot(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val publicationId: Long,
    val analysisId: Long,
    val correctionId: Long,
    val reviewerId: Long,
    val type: String,
    val originalContent: String,
    val effectiveContent: String,
    val evidenceSummary: String,
    val reviewedAt: Long,
    val versionKey: String,
)

/** 建议正文和依据不覆盖更新；只有用户的放弃状态可变。 */
@Entity(
    tableName = "improvement_suggestions",
    foreignKeys = [ForeignKey(entity = ImprovementItem::class, parentColumns = ["id"],
        childColumns = ["itemId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("planId"), Index("itemId"),
        Index(value = ["itemId", "reviewVersionKey", "generatorVersion", "problemSnapshot"], unique = true)],
)
data class ImprovementSuggestion(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val planId: Long,
    val itemId: Long,
    val sourceSnapshotId: Long,
    val content: String,
    val problemSnapshot: String,
    val bopppsStage: String,
    val rationale: String,
    val generatorMode: SuggestionOrigin,
    val generatorVersion: String,
    val reviewVersionKey: String,
    val createdAt: Long,
    val dismissedAt: Long? = null,
)
