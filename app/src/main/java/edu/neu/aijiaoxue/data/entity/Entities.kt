package edu.neu.aijiaoxue.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import edu.neu.aijiaoxue.data.model.ArrangementStatus
import edu.neu.aijiaoxue.data.model.EngagementType
import edu.neu.aijiaoxue.data.model.EvalDimension
import edu.neu.aijiaoxue.data.model.EvalGrade
import edu.neu.aijiaoxue.data.model.EvalStatus
import edu.neu.aijiaoxue.data.model.InteractionType
import edu.neu.aijiaoxue.data.model.ItemSource
import edu.neu.aijiaoxue.data.model.MaterialType
import edu.neu.aijiaoxue.data.model.MeasureSource
import edu.neu.aijiaoxue.data.model.MeasureStatus
import edu.neu.aijiaoxue.data.model.PlanStatus
import edu.neu.aijiaoxue.data.model.Priority
import edu.neu.aijiaoxue.data.model.Role
import edu.neu.aijiaoxue.data.model.SupervisionType
import edu.neu.aijiaoxue.data.model.TaskStatus

/*
 * 命名约定见 docs/开发规范.md：
 * 表名 snake_case 复数；字段 camelCase；主键一律 id: Long 自增；外键 xxxId；
 * 时间点 xxxAt（epoch 毫秒）；日期 xxxDate（"yyyy-MM-dd"）；可空字段表示“未填写”。
 */

@Entity(tableName = "users", indices = [Index(value = ["account"], unique = true)])
data class User(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val account: String,
    val passwordHash: String,
    val name: String,
    val role: Role,
)

@Entity(
    tableName = "courses",
    foreignKeys = [ForeignKey(entity = User::class, parentColumns = ["id"], childColumns = ["teacherId"])],
    indices = [Index("teacherId"), Index(value = ["code"], unique = true)],
)
data class Course(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 课程编号，如 "FT2026-01"。 */
    val code: String,
    val name: String,
    val teacherId: Long,
    val className: String?,
    /** 上课时间的展示文本，如 "周三 3-4 节"。 */
    val schedule: String?,
    val location: String?,
)

@Entity(
    tableName = "arrangements",
    foreignKeys = [ForeignKey(entity = Course::class, parentColumns = ["id"], childColumns = ["courseId"])],
    indices = [Index("courseId")],
)
data class Arrangement(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val courseId: Long,
    val isKeyFocus: Boolean = false,
    val note: String? = null,
    val createdBy: Long,
    val status: ArrangementStatus = ArrangementStatus.PENDING,
    val createdAt: Long = System.currentTimeMillis(),
)

/** 督导任务。所有模块之间只通过 taskId 交接。 */
@Entity(
    tableName = "supervision_tasks",
    foreignKeys = [
        ForeignKey(entity = Course::class, parentColumns = ["id"], childColumns = ["courseId"]),
        ForeignKey(entity = User::class, parentColumns = ["id"], childColumns = ["supervisorId"]),
    ],
    indices = [
        Index("courseId"),
        Index("supervisorId"),
        Index(value = ["code"], unique = true),
        // US04：同一督导对同一课程同一日期同一节次不能重复创建
        Index(value = ["supervisorId", "courseId", "supervisionDate", "period"], unique = true),
    ],
)
data class SupervisionTask(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 展示用任务编号，见 TaskCode，如 "DD20261003-001"。 */
    val code: String,
    val courseId: Long,
    val supervisorId: Long,
    val arrangementId: Long? = null,
    val supervisionDate: String,
    /** 节次，格式 "起-止"，如 "3-4"；单节写 "3"。 */
    val period: String,
    val chapter: String? = null,
    val type: SupervisionType,
    val status: TaskStatus = TaskStatus.NOT_STARTED,
    /** 首次进入采集页的时间，所有 offsetMs 以此为 0 点。 */
    val classStartAt: Long? = null,
    val classEndAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "materials",
    foreignKeys = [ForeignKey(
        entity = SupervisionTask::class, parentColumns = ["id"], childColumns = ["taskId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("taskId")],
)
data class Material(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    val type: MaterialType,
    /** 相对 filesDir 的路径，见 FileStore，如 "tasks/12/audio/AUD_20261003_101530.m4a"。 */
    val filePath: String,
    /** 拍摄时间或录音开始时间；相册导入则为导入时间。 */
    val capturedAt: Long,
    val offsetMs: Long,
    /** 仅音频：录音结束时间与有效时长（不含暂停）。 */
    val endedAt: Long? = null,
    val durationMs: Long? = null,
    val isImported: Boolean = false,
    val note: String? = null,
)

@Entity(
    tableName = "interaction_events",
    foreignKeys = [ForeignKey(
        entity = SupervisionTask::class, parentColumns = ["id"], childColumns = ["taskId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("taskId")],
)
data class InteractionEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    val type: InteractionType,
    val occurredAt: Long,
    val offsetMs: Long,
    val note: String? = null,
)

@Entity(
    tableName = "engagement_records",
    foreignKeys = [ForeignKey(
        entity = SupervisionTask::class, parentColumns = ["id"], childColumns = ["taskId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("taskId")],
)
data class EngagementRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    val type: EngagementType,
    val presentCount: Int? = null,
    /** 参与人数与参与比例二选一（US13）。 */
    val engagedCount: Int? = null,
    val engagedPercent: Int? = null,
    val description: String? = null,
    val recordedAt: Long,
    val offsetMs: Long,
)

/** 督导评价，与任务 1:1。四个维度固定为列，与 EvalDimension 一一对应。 */
@Entity(
    tableName = "evaluations",
    foreignKeys = [ForeignKey(entity = SupervisionTask::class, parentColumns = ["id"], childColumns = ["taskId"])],
    indices = [Index(value = ["taskId"], unique = true)],
)
data class Evaluation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    val contentScore: Int? = null,
    val contentComment: String? = null,
    val designScore: Int? = null,
    val designComment: String? = null,
    val interactionScore: Int? = null,
    val interactionComment: String? = null,
    val engagementScore: Int? = null,
    val engagementComment: String? = null,
    val totalScore: Int? = null,
    val grade: EvalGrade? = null,
    val strengths: String? = null,
    val suggestions: String? = null,
    val overallOpinion: String? = null,
    val status: EvalStatus = EvalStatus.DRAFT,
    val submittedAt: Long? = null,
    /** 教师首次查看时间，null 表示未读（US33 红点）。 */
    val teacherReadAt: Long? = null,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    fun scoreOf(dim: EvalDimension): Int? = when (dim) {
        EvalDimension.CONTENT -> contentScore
        EvalDimension.DESIGN -> designScore
        EvalDimension.INTERACTION -> interactionScore
        EvalDimension.ENGAGEMENT -> engagementScore
    }

    fun scores(): Map<EvalDimension, Int?> = EvalDimension.entries.associateWith { scoreOf(it) }
}

/** 综合意见中的“主要问题”条目（US30），是改进事项的来源。 */
@Entity(
    tableName = "evaluation_issues",
    foreignKeys = [ForeignKey(
        entity = Evaluation::class, parentColumns = ["id"], childColumns = ["evaluationId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("evaluationId")],
)
data class EvaluationIssue(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val evaluationId: Long,
    val description: String,
    val dimension: EvalDimension? = null,
    val sortOrder: Int = 0,
)

@Entity(tableName = "improvement_plans", indices = [Index("teacherId"), Index(value = ["taskId"], unique = true)])
data class ImprovementPlan(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val teacherId: Long,
    val taskId: Long,
    val status: PlanStatus = PlanStatus.DRAFT,
    val confirmedAt: Long? = null,
)

/** 改进事项（US36）。issueId 为空表示教师自选。 */
@Entity(
    tableName = "improvement_items",
    foreignKeys = [ForeignKey(
        entity = ImprovementPlan::class, parentColumns = ["id"], childColumns = ["planId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("planId"), Index("issueId")],
)
data class ImprovementItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val planId: Long,
    val issueId: Long? = null,
    val source: ItemSource,
    val description: String,
    val priority: Priority,
)

/** 改进措施，即改进待办（US41、US42）。 */
@Entity(
    tableName = "improvement_measures",
    foreignKeys = [ForeignKey(
        entity = ImprovementItem::class, parentColumns = ["id"], childColumns = ["itemId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("itemId"), Index("planId")],
)
data class ImprovementMeasure(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val planId: Long,
    val itemId: Long,
    val content: String,
    val source: MeasureSource,
    val dueDate: String,
    val status: MeasureStatus = MeasureStatus.NOT_STARTED,
    val updatedAt: Long = System.currentTimeMillis(),
)
