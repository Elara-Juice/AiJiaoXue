package edu.neu.aijiaoxue.data.repository

import androidx.room.withTransaction
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.dao.*
import edu.neu.aijiaoxue.data.entity.MetricSnapshot
import edu.neu.aijiaoxue.data.model.*
import java.time.LocalDate

data class GovernanceFilter(
    val teacherId: Long? = null, val courseId: Long? = null,
    val fromDate: String = "", val toDate: String = "",
)

data class MetricTrendPair(
    val beforeTaskId: Long, val afterTaskId: Long, val metricKey: MetricKey,
    val before: MetricSnapshot?, val after: MetricSnapshot?,
    val outcome: MetricRules.CompareOutcome?, val note: String?,
)

data class TeacherCourseTrend(
    val teacherId: Long, val teacherName: String, val courseId: Long, val courseName: String,
    val classrooms: List<GovernanceTaskRow>, val comparisons: List<MetricTrendPair>,
) {
    val hasComparableTrend: Boolean get() = classrooms.size >= 2 &&
        comparisons.any { it.outcome is MetricRules.CompareOutcome.Comparable }
}

data class GovernanceSummary(
    val classroomCount: Int, val teacherCount: Int, val courseCount: Int,
    val issuesByDimension: Map<EvalDimension?, Int>,
    val measuresByStatus: Map<MeasureStatus, Int>,
    val executedMeasureCount: Int, val executionRecordCount: Int,
    val latestVerifications: List<GovernanceVerificationRow>,
) {
    val verificationCounts: Map<VerificationVerdict, Int> get() = latestVerifications.groupingBy { it.verdict }.eachCount()
}

data class GovernanceDashboard(
    val role: Role, val filter: GovernanceFilter,
    /** 只包含当前读者可见的正式课堂课程，用于教师/课程筛选。 */
    val courseOptions: List<GovernanceTaskRow>,
    val trends: List<TeacherCourseTrend>,
    /** 整体治理只向管理员提供；教师仅有本人课程趋势。 */
    val summary: GovernanceSummary?,
)

data class GovernanceTaskDetail(
    val classroom: GovernanceTaskRow, val snapshots: List<MetricSnapshot>,
    val issues: List<GovernanceIssueRow>, val measures: List<GovernanceMeasureRow>,
    val executions: List<GovernanceExecutionRow>, val verifications: List<GovernanceVerificationRow>,
)

/** 无数据库写入：治理读取已提交结果，不在打开报表时补造历史指标或改善结论。 */
class GovernanceRepository(private val db: AppDatabase) {
    private val dao = db.governanceDao()

    suspend fun load(actorId: Long, filter: GovernanceFilter = GovernanceFilter()): GovernanceDashboard =
        db.withTransaction {
            val role = authorizedRole(actorId)
            validateFilter(filter)
            require(role != Role.TEACHER || filter.teacherId == null || filter.teacherId == actorId) { "只能查看本人课程趋势" }
            val options = dao.formalTasks(actorId, null, null, "", "").distinctBy { it.courseId }
            require(filter.courseId == null || options.any { it.courseId == filter.courseId &&
                (filter.teacherId == null || it.teacherId == filter.teacherId) }) { "课程尚无正式结果或无权限访问" }
            val tasks = dao.formalTasks(actorId, filter.teacherId, filter.courseId, filter.fromDate, filter.toDate)
            val ids = tasks.map { it.taskId }
            val snapshots = if (ids.isEmpty()) emptyList() else dao.snapshots(actorId, ids)
            val summary = if (role == Role.ADMIN) {
                val issues = if (ids.isEmpty()) emptyList() else dao.issues(actorId, ids)
                val measures = if (ids.isEmpty()) emptyList() else dao.measures(actorId, ids)
                val executions = if (ids.isEmpty()) emptyList() else dao.executions(actorId, ids)
                val verifications = if (ids.isEmpty()) emptyList() else dao.verifications(actorId, ids)
                GovernanceSummary(
                    tasks.size, tasks.map { it.teacherId }.distinct().size, tasks.map { it.courseId }.distinct().size,
                    issues.distinctBy { it.issueId }.groupingBy { it.dimension }.eachCount(),
                    measures.distinctBy { it.measureId }.groupingBy { it.status }.eachCount(),
                    executions.map { it.measureId }.distinct().size, executions.distinctBy { it.executionId }.size,
                    GovernanceCalculations.latestVerifications(verifications),
                )
            } else null
            GovernanceDashboard(role, filter, options, GovernanceCalculations.trends(tasks, snapshots), summary)
        }

    suspend fun sourceDetail(actorId: Long, taskId: Long): GovernanceTaskDetail = db.withTransaction {
        authorizedRole(actorId)
        val task = requireNotNull(dao.formalTask(actorId, taskId)) { "课堂尚无正式结果或无权限访问" }
        val ids = listOf(taskId)
        GovernanceTaskDetail(task, dao.snapshots(actorId, ids), dao.issues(actorId, ids),
            dao.measures(actorId, ids), dao.executions(actorId, ids),
            GovernanceCalculations.latestVerifications(dao.verifications(actorId, ids)))
    }

    private suspend fun authorizedRole(actorId: Long): Role {
        val role = db.userDao().getById(actorId)?.role
        require(role == Role.ADMIN || role == Role.TEACHER) { "仅教学管理人员或任课教师可查看趋势" }
        return requireNotNull(role)
    }

    private fun validateFilter(filter: GovernanceFilter) {
        fun valid(value: String) = value.isEmpty() || runCatching {
            LocalDate.parse(value, DateFormats.DATE).format(DateFormats.DATE) == value
        }.getOrDefault(false)
        require(valid(filter.fromDate) && valid(filter.toDate)) { "日期格式应为 yyyy-MM-dd" }
        require(filter.fromDate.isEmpty() || filter.toDate.isEmpty() || filter.fromDate <= filter.toDate) { "开始日期不能晚于结束日期" }
    }
}

/** US47/48 的纯计算；可比性统一调用 MetricRules，不在页面另算指标。 */
object GovernanceCalculations {
    fun latestVerifications(rows: List<GovernanceVerificationRow>): List<GovernanceVerificationRow> = rows
        .sortedWith(compareByDescending<GovernanceVerificationRow> { it.verifiedAt }.thenByDescending { it.verificationId })
        // SQLite 可空唯一列允许重复整堂关联；按真实业务关系去重，避免重复点击扩大改善数量。
        .distinctBy { Triple(it.originTaskId, it.originIssueId, it.followUpTaskId) }

    fun trends(tasks: List<GovernanceTaskRow>, snapshots: List<MetricSnapshot>): List<TeacherCourseTrend> = tasks
        .distinctBy { it.taskId }.groupBy { it.teacherId to it.courseId }.values.map { group ->
            val ordered = group.sortedWith(compareBy<GovernanceTaskRow> { it.supervisionDate }
                .thenBy { it.period.substringBefore('-').trim().toIntOrNull() ?: Int.MAX_VALUE }.thenBy { it.taskId })
            val first = ordered.first()
            val pairs = ordered.zipWithNext().flatMap { (before, after) ->
                MetricKey.entries.map { key -> compare(before.taskId, after.taskId, key, snapshots) }
            }
            TeacherCourseTrend(first.teacherId, first.teacherName, first.courseId, first.courseName, ordered, pairs)
        }.sortedWith(compareBy({ it.teacherName }, { it.courseName }, { it.courseId }))

    private fun compare(beforeId: Long, afterId: Long, key: MetricKey, snapshots: List<MetricSnapshot>): MetricTrendPair {
        val before = snapshots.filter { it.taskId == beforeId && it.metricKey == key }
        val after = snapshots.filter { it.taskId == afterId && it.metricKey == key }
        val order = compareBy<MetricSnapshot> { it.createdAt }.thenBy { it.id }
        val common = after.filter { a -> before.any { it.methodVersion == a.methodVersion } }.maxWithOrNull(order)
        val b = if (common == null) before.maxWithOrNull(order) else before.first { it.methodVersion == common.methodVersion }
        val a = common ?: after.maxWithOrNull(order)
        if (b == null || a == null) return MetricTrendPair(beforeId, afterId, key, b, a, null, "缺少正式指标快照，不补算历史值")
        if (b.value?.isFinite() == false || a.value?.isFinite() == false) {
            return MetricTrendPair(beforeId, afterId, key, b, a, null, "指标值无效，不能比较")
        }
        val outcome = MetricRules.compare(b.view(), a.view())
        return MetricTrendPair(beforeId, afterId, key, b, a, outcome,
            (outcome as? MetricRules.CompareOutcome.NotComparable)?.reason?.message)
    }

    private fun MetricSnapshot.view() = MetricSnapshotView(metricKey, value, unit, denominator, sampling,
        methodVersion, scopeStartAt, scopeEndAt)
}
