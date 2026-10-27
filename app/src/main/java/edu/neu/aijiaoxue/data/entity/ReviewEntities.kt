package edu.neu.aijiaoxue.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import edu.neu.aijiaoxue.data.model.MetricKey
import edu.neu.aijiaoxue.data.model.VerificationVerdict
import androidx.room.ColumnInfo
import edu.neu.aijiaoxue.data.model.*

/*
 * Sprint 3（李奕萱）：复评关联、正式指标快照与问题改善核验记录。
 *
 * 约定：
 * - 指标快照只追加不更新：同一 (taskId, metricKey, methodVersion) 唯一，
 *   口径变化时写新的 methodVersion，旧快照原样保留，历史正式结果不被重分析覆盖。
 * - 复评关联保存原问题快照文本，原问题条目被删也不影响历史追溯。
 */

/**
 * US45：正式指标快照。value 为 null 表示缺测，按缺测处理，不能按 0 参与比较。
 * 单位、分母、采样方式、方法版本共同决定两次课堂的指标是否可比。
 */
@Entity(
    tableName = "metric_snapshots",
    foreignKeys = [ForeignKey(
        entity = SupervisionTask::class, parentColumns = ["id"], childColumns = ["taskId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [
        Index("taskId"),
        Index(value = ["taskId", "metricKey", "methodVersion"], unique = true),
    ],
)
data class MetricSnapshot(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    val metricKey: MetricKey,
    /** null 表示该指标缺测，不得补零。 */
    val value: Double?,
    val unit: String,
    /** 分母口径说明，例如“实到人数合计”；口径不同不能直接比较。 */
    val denominator: String,
    val denominatorValue: Double?,
    /** 采样方式说明，例如“课堂全时段互动事件计数”。 */
    val sampling: String,
    /** 口径版本：口径变更时新增版本，旧快照保留。 */
    val methodVersion: String,
    /** 实际参与统计的样本数，0 表示无有效样本。 */
    val sampleCount: Int,
    /** 统计区间的起止时间（epoch 毫秒），缺测时为空。 */
    val scopeStartAt: Long?,
    val scopeEndAt: Long?,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * US45/46：原课堂（原问题）与后续复评任务的稳定关联。
 * originIssueSnapshot 保存原问题原文，来源条目删除后仍可追溯。
 */
@Entity(
    tableName = "review_links",
    foreignKeys = [
        ForeignKey(entity = SupervisionTask::class, parentColumns = ["id"], childColumns = ["originTaskId"],
            onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = SupervisionTask::class, parentColumns = ["id"], childColumns = ["followUpTaskId"],
            onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index("originTaskId"),
        Index("followUpTaskId"),
        Index("teacherId"),
        Index(value = ["originTaskId", "originIssueId", "followUpTaskId"], unique = true),
    ],
)
data class ReviewLink(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 原课堂任务。 */
    val originTaskId: Long,
    /** 原评价问题条目，可为空表示整堂复评。 */
    val originIssueId: Long? = null,
    /** 后续复评任务。 */
    val followUpTaskId: Long,
    /** 课程任课教师，用于教师侧授权读取。 */
    val teacherId: Long,
    /** 原问题原文快照。 */
    val originIssueSnapshot: String,
    /** 原问题所属维度，null 表示未指定。 */
    val originDimension: String? = null,
    val createdBy: Long,
    val createdAt: Long = System.currentTimeMillis(),
    val originDimensionKey: EvalDimension? = null,
    @ColumnInfo(defaultValue = "'UNKNOWN'")
    val chronologyStatus: ReviewChronologyStatus = ReviewChronologyStatus.UNKNOWN,
    @ColumnInfo(defaultValue = "0") val chronologyRuleVersion: Int = 0,
)

/**
 * US46：重点问题改善核验记录。判断、依据、核验人、时间四项齐全才算一次核验。
 * 依据的前后指标快照只作证据引用，删除快照不改变核验结论。
 */
@Entity(
    tableName = "issue_verifications",
    foreignKeys = [
        ForeignKey(entity = ReviewLink::class, parentColumns = ["id"], childColumns = ["linkId"],
            onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = User::class, parentColumns = ["id"], childColumns = ["verifierId"]),
        ForeignKey(entity = MetricSnapshot::class, parentColumns = ["id"], childColumns = ["beforeSnapshotId"],
            onDelete = ForeignKey.SET_NULL),
        ForeignKey(entity = MetricSnapshot::class, parentColumns = ["id"], childColumns = ["afterSnapshotId"],
            onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index("linkId"), Index("verifierId")],
)
data class IssueVerification(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val linkId: Long,
    /** 改善/未改善/继续观察。 */
    val verdict: VerificationVerdict,
    /** 判断依据，不能为空。 */
    val basis: String,
    /** 核验人。 */
    val verifierId: Long,
    /** 核验时间。 */
    val verifiedAt: Long,
    /** 核验所依据的前次指标快照。 */
    val beforeSnapshotId: Long? = null,
    /** 核验所依据的后次指标快照。 */
    val afterSnapshotId: Long? = null,
    @ColumnInfo(defaultValue = "1") val contractVersion: Int = 1,
    @ColumnInfo(defaultValue = "'UNDETERMINED'") val presence: IssuePresence = IssuePresence.UNDETERMINED,
    @ColumnInfo(defaultValue = "'UNDETERMINED'") val extent: ImprovementExtent = ImprovementExtent.UNDETERMINED,
    val conflictExplanation: String? = null,
    val contextSnapshotJson: String? = null,
)

/** 核验时冻结所选来源，不在回看时重新挑选指标或把空列表解释成问题消失。 */
@Entity(
    tableName = "issue_verification_evidence",
    foreignKeys = [
        ForeignKey(entity = IssueVerification::class, parentColumns = ["id"], childColumns = ["verificationId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = SupervisionTask::class, parentColumns = ["id"], childColumns = ["sourceTaskId"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(entity = Evaluation::class, parentColumns = ["id"], childColumns = ["sourceEvaluationId"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(entity = EvaluationIssue::class, parentColumns = ["id"], childColumns = ["sourceIssueId"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(entity = MetricSnapshot::class, parentColumns = ["id"], childColumns = ["beforeSnapshotId"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(entity = MetricSnapshot::class, parentColumns = ["id"], childColumns = ["afterSnapshotId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index(value = ["verificationId", "ordinal"], unique = true), Index("sourceTaskId"),
        Index("sourceEvaluationId"), Index("sourceIssueId"), Index("beforeSnapshotId"), Index("afterSnapshotId")],
)
data class IssueVerificationEvidence(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val verificationId: Long,
    val ordinal: Int,
    val kind: VerificationEvidenceKind,
    val role: VerificationEvidenceRole,
    val sourceTaskId: Long? = null,
    val sourceEvaluationId: Long? = null,
    val sourceIssueId: Long? = null,
    val beforeSnapshotId: Long? = null,
    val afterSnapshotId: Long? = null,
    val metricKey: MetricKey? = null,
    val relationExplanation: String,
    val factText: String? = null,
    /** Epoch milliseconds; both null means the whole dated follow-up lesson. */
    val scopeStartAt: Long? = null,
    val scopeEndAt: Long? = null,
    val snapshotJson: String,
    val createdAt: Long,
)
