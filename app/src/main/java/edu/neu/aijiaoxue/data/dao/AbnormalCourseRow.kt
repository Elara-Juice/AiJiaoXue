package edu.neu.aijiaoxue.data.dao

import edu.neu.aijiaoxue.data.model.TaskStatus

/** Draft scores are never selected; null evaluationId means no formally published evaluation. */
data class AbnormalCourseRow(
    val taskId: Long, val courseId: Long, val evaluationId: Long?,
    val courseName: String, val teacherId: Long, val teacherName: String,
    val supervisionDate: String, val period: String, val totalScore: Int?, val taskStatus: TaskStatus,
)
