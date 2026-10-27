package edu.neu.aijiaoxue.data.repository

import androidx.room.withTransaction
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.dao.AbnormalCourseRow
import edu.neu.aijiaoxue.data.dao.GovernanceIssueRow
import edu.neu.aijiaoxue.data.model.*

data class CourseAttentionRecord(
    val classroom: AbnormalCourseRow,
    val state: CourseAttentionState,
    val ruleCode: String,
    val reason: String,
    val issues: List<GovernanceIssueRow> = emptyList(),
)

data class CourseAttentionGroup(val courseId: Long, val courseName: String, val teacherName: String,
                                val classrooms: List<CourseAttentionRecord>)
data class AbnormalCourseResult(
    val filter: GovernanceFilter,
    val options: List<AbnormalCourseRow>,
    val groups: List<CourseAttentionGroup>,
    val normalCount: Int,
    val undetermined: List<CourseAttentionRecord>,
    val unpublished: List<CourseAttentionRecord>,
)

object CourseAttentionRules {
    const val RULE_CODE = "FORMAL_TOTAL_LT60_V1"
    fun classify(row: AbnormalCourseRow): CourseAttentionRecord = when {
        row.evaluationId == null || row.taskStatus != TaskStatus.COMPLETED -> CourseAttentionRecord(row,
            CourseAttentionState.UNPUBLISHED, "NO_FORMAL_EVALUATION", "尚无已完成课堂的正式评价；草稿分数不参与判断")
        row.totalScore == null || row.totalScore !in 0..100 -> CourseAttentionRecord(row,
            CourseAttentionState.UNDETERMINED, "INVALID_FORMAL_TOTAL", "正式总分缺失或不在0至100分范围，无法判定")
        EvalRules.gradeOf(row.totalScore) == EvalGrade.NEEDS_IMPROVEMENT -> CourseAttentionRecord(row,
            CourseAttentionState.ATTENTION, RULE_CODE, "已提交正式评价总分 ${row.totalScore} 分，低于60分关注阈值；需人工核查，不等同真实教学质量差")
        else -> CourseAttentionRecord(row, CourseAttentionState.NORMAL, RULE_CODE, "本次正式总分未触发低于60分的关注阈值")
    }
}

class AbnormalCourseRepository(private val db: AppDatabase) {
    suspend fun load(actorId: Long, filter: GovernanceFilter = GovernanceFilter()): AbnormalCourseResult = db.withTransaction {
        require(db.userDao().getById(actorId)?.role == Role.ADMIN) { "仅教学管理人员可查看关注课程清单" }
        require(filter.fromDate.isEmpty() || ReviewChronologyV1.date(filter.fromDate) != null) { "开始日期无效" }
        require(filter.toDate.isEmpty() || ReviewChronologyV1.date(filter.toDate) != null) { "结束日期无效" }
        require(filter.fromDate.isEmpty() || filter.toDate.isEmpty() || filter.fromDate <= filter.toDate) { "开始日期不能晚于结束日期" }
        val dao = db.governanceDao()
        val options = dao.abnormalCandidates(actorId, null, null, "", "").distinctBy { it.courseId }
        val rows = dao.abnormalCandidates(actorId, filter.teacherId, filter.courseId, filter.fromDate, filter.toDate)
        val formalIds = rows.filter { it.evaluationId != null && it.taskStatus == TaskStatus.COMPLETED }.map { it.taskId }
        val issues = if (formalIds.isEmpty()) emptyMap() else dao.issues(actorId, formalIds).groupBy { it.taskId }
        val classified = rows.map { CourseAttentionRules.classify(it).copy(issues = issues[it.taskId].orEmpty()) }
        val groups = classified.filter { it.state == CourseAttentionState.ATTENTION }.groupBy { it.classroom.courseId }.map { (id, records) ->
            val source = records.first().classroom
            CourseAttentionGroup(id, source.courseName, source.teacherName, records)
        }.sortedBy { it.courseName }
        AbnormalCourseResult(filter, options, groups, classified.count { it.state == CourseAttentionState.NORMAL },
            classified.filter { it.state == CourseAttentionState.UNDETERMINED }, classified.filter { it.state == CourseAttentionState.UNPUBLISHED })
    }
}
