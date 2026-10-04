package edu.neu.aijiaoxue.data.dao

import edu.neu.aijiaoxue.data.model.EvalGrade
import edu.neu.aijiaoxue.data.model.InteractionType
import edu.neu.aijiaoxue.data.model.MeasureSource
import edu.neu.aijiaoxue.data.model.MeasureStatus
import edu.neu.aijiaoxue.data.model.Priority
import edu.neu.aijiaoxue.data.model.SupervisionType
import edu.neu.aijiaoxue.data.model.TaskStatus

/*
 * 联表查询的结果行，只读，供列表页直接显示，避免界面逐条再查课程名、教师名。
 * 字段名必须与 SQL 里的列别名一致。
 */

/** 课程列表行（US01 检索、US02 详情、US05 安排）。 */
data class CourseRow(
    val id: Long,
    val code: String,
    val name: String,
    val teacherId: Long,
    val teacherName: String,
    val className: String?,
    val schedule: String?,
    val location: String?,
    /** 仅统计已完成的督导任务（US02 业务规则 2）。 */
    val completedCount: Int,
    val lastSupervisionDate: String?,
    /** 存在未完成的督导安排，检索结果显示“待督导”（US05）。 */
    val hasOpenArrangement: Boolean,
    val isKeyFocus: Boolean,
)

/** 任务列表行（我的督导任务、US06 任务进度）。 */
data class TaskRow(
    val id: Long,
    val code: String,
    val courseId: Long,
    val courseName: String,
    val teacherName: String,
    val supervisorId: Long,
    val supervisorName: String,
    val supervisionDate: String,
    val period: String,
    val type: SupervisionType,
    val status: TaskStatus,
)

/** 历史督导记录行（US32）。 */
data class HistoryRow(
    val taskId: Long,
    val code: String,
    val courseName: String,
    val teacherName: String,
    val supervisorName: String,
    val supervisionDate: String,
    val period: String,
    val totalScore: Int?,
    val grade: EvalGrade?,
)

/**
 * US32 历史详情页顶部的“督导人 + 这堂课”信息。管理人员能看到全部督导的记录，
 * 所以必须显示是哪位督导、哪门课的哪一次课（课程、班级、地点、日期节次、章节、实际上课时段）。
 */
data class HistoryDetailRow(
    val taskId: Long,
    val code: String,
    val type: SupervisionType,
    val supervisorId: Long,
    val supervisorName: String,
    val supervisorAccount: String,
    val courseId: Long,
    val courseCode: String,
    val courseName: String,
    val teacherName: String,
    val className: String?,
    val schedule: String?,
    val location: String?,
    val supervisionDate: String,
    val period: String,
    val chapter: String?,
    /** 采集开始、结束时间（epoch 毫秒）；预置的历史任务没有采集过，为 null。 */
    val classStartAt: Long?,
    val classEndAt: Long?,
    val totalScore: Int?,
    val grade: EvalGrade?,
    val submittedAt: Long?,
)

/** 教师督导反馈列表行（US33），teacherReadAt 为 null 显示红点。 */
data class FeedbackRow(
    val evaluationId: Long,
    val taskId: Long,
    val courseName: String,
    val supervisorName: String,
    val supervisionDate: String,
    val totalScore: Int?,
    val grade: EvalGrade?,
    val submittedAt: Long?,
    val teacherReadAt: Long?,
)

/** 改进待办行（US42），带原问题、课程和督导日期用于追溯。 */
data class TodoRow(
    val measureId: Long,
    val planId: Long,
    val taskId: Long,
    val content: String,
    val source: MeasureSource,
    val dueDate: String,
    val status: MeasureStatus,
    val itemDescription: String,
    val priority: Priority,
    val courseName: String,
    val supervisionDate: String,
)

/** US06 状态统计。 */
data class StatusCount(
    val status: TaskStatus,
    val count: Int,
)

/** US12 各类型互动事件计数。 */
data class EventTypeCount(
    val type: InteractionType,
    val count: Int,
)
