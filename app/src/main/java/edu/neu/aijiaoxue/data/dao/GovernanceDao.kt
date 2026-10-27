package edu.neu.aijiaoxue.data.dao

import androidx.room.Dao
import androidx.room.Query
import edu.neu.aijiaoxue.data.entity.MetricSnapshot
import edu.neu.aijiaoxue.data.model.EvalDimension
import edu.neu.aijiaoxue.data.model.MeasureStatus
import edu.neu.aijiaoxue.data.model.VerificationVerdict

private const val FORMAL_FROM = """
    FROM evaluations e JOIN supervision_tasks t ON t.id = e.taskId
    JOIN courses c ON c.id = t.courseId JOIN users tu ON tu.id = c.teacherId
    JOIN users su ON su.id = t.supervisorId JOIN users u ON u.id = :actorId
"""
private const val FORMAL_SCOPE = """
    WHERE e.status = 'SUBMITTED' AND t.status = 'COMPLETED'
    AND (u.role = 'ADMIN' OR (u.role = 'TEACHER' AND c.teacherId = u.id))
"""
private const val TASK_COLUMNS = """
    SELECT t.id AS taskId, t.code AS taskCode, t.supervisionDate, t.period, t.chapter,
        c.id AS courseId, c.name AS courseName, c.code AS courseCode,
        c.teacherId, tu.name AS teacherName, su.name AS supervisorName,
        e.id AS evaluationId, e.totalScore, e.strengths, e.overallOpinion, e.submittedAt
"""
private const val CONFIRMED_MEASURES = """
    JOIN improvement_plans p ON p.taskId = t.id AND p.teacherId = c.teacherId AND p.status = 'CONFIRMED'
    JOIN improvement_items i ON i.planId = p.id
    JOIN improvement_measures m ON m.itemId = i.id AND m.planId = p.id
"""

data class GovernanceTaskRow(
    val taskId: Long, val taskCode: String, val supervisionDate: String, val period: String,
    val chapter: String?, val courseId: Long, val courseName: String, val courseCode: String,
    val teacherId: Long, val teacherName: String, val supervisorName: String,
    val evaluationId: Long, val totalScore: Int?, val strengths: String?,
    val overallOpinion: String?, val submittedAt: Long?,
)

data class GovernanceIssueRow(
    val issueId: Long, val taskId: Long, val description: String, val dimension: EvalDimension?,
)

data class GovernanceMeasureRow(
    val measureId: Long, val taskId: Long, val itemId: Long, val itemDescription: String,
    val content: String, val status: MeasureStatus,
)

data class GovernanceExecutionRow(
    val executionId: Long, val measureId: Long, val taskId: Long,
    val implementationNote: String, val actualAdjustment: String, val implementedAt: Long,
)

data class GovernanceVerificationRow(
    val verificationId: Long, val linkId: Long, val originTaskId: Long, val originIssueId: Long?,
    val originIssueSnapshot: String, val followUpTaskId: Long, val followUpCode: String,
    val verdict: VerificationVerdict, val basis: String, val verifierName: String, val verifiedAt: Long,
    val contractVersion: Int = 1,
    val ineligibilityReason: String? = null,
)

/** US47/48：每个查询都限制正式数据和读者身份，页面不直接读取无授权的原始 DAO。 */
@Dao
interface GovernanceDao {
    /** US34: admin-only source query. Preserve missing formal scores and separately expose absence of a formal report. */
    @Query("""SELECT t.id AS taskId,c.id AS courseId,e.id AS evaluationId,c.name AS courseName,
        c.teacherId,tu.name AS teacherName,t.supervisionDate,t.period,e.totalScore,t.status AS taskStatus
        FROM supervision_tasks t JOIN courses c ON c.id=t.courseId JOIN users tu ON tu.id=c.teacherId
        JOIN users u ON u.id=:actorId AND u.role='ADMIN'
        LEFT JOIN evaluations e ON e.taskId=t.id AND e.status='SUBMITTED'
        WHERE (:teacherId IS NULL OR c.teacherId=:teacherId) AND (:courseId IS NULL OR c.id=:courseId)
        AND (:fromDate='' OR t.supervisionDate>=:fromDate) AND (:toDate='' OR t.supervisionDate<=:toDate)
        ORDER BY t.supervisionDate DESC,t.id DESC""")
    suspend fun abnormalCandidates(actorId: Long, teacherId: Long?, courseId: Long?, fromDate: String, toDate: String): List<AbnormalCourseRow>

    @Query(TASK_COLUMNS + FORMAL_FROM + FORMAL_SCOPE + """
        AND (:teacherId IS NULL OR c.teacherId = :teacherId)
        AND (:courseId IS NULL OR c.id = :courseId)
        AND (:fromDate = '' OR t.supervisionDate >= :fromDate)
        AND (:toDate = '' OR t.supervisionDate <= :toDate)
        ORDER BY t.supervisionDate, t.id""")
    suspend fun formalTasks(actorId: Long, teacherId: Long?, courseId: Long?, fromDate: String, toDate: String): List<GovernanceTaskRow>

    @Query(TASK_COLUMNS + FORMAL_FROM + FORMAL_SCOPE + " AND t.id = :taskId")
    suspend fun formalTask(actorId: Long, taskId: Long): GovernanceTaskRow?

    @Query("SELECT s.* " + FORMAL_FROM + " JOIN metric_snapshots s ON s.taskId = t.id " +
        FORMAL_SCOPE + " AND t.id IN (:taskIds) ORDER BY s.taskId, s.metricKey, s.createdAt, s.id")
    suspend fun snapshots(actorId: Long, taskIds: List<Long>): List<MetricSnapshot>

    @Query("SELECT i.id AS issueId, t.id AS taskId, i.description, i.dimension " + FORMAL_FROM +
        " JOIN evaluation_issues i ON i.evaluationId = e.id " + FORMAL_SCOPE +
        " AND t.id IN (:taskIds) ORDER BY t.id, i.sortOrder, i.id")
    suspend fun issues(actorId: Long, taskIds: List<Long>): List<GovernanceIssueRow>

    @Query("""SELECT m.id AS measureId, t.id AS taskId, i.id AS itemId,
        i.description AS itemDescription, m.content, m.status """ + FORMAL_FROM + CONFIRMED_MEASURES +
        FORMAL_SCOPE + " AND t.id IN (:taskIds) ORDER BY t.id, i.id, m.id")
    suspend fun measures(actorId: Long, taskIds: List<Long>): List<GovernanceMeasureRow>

    @Query("""SELECT x.id AS executionId, m.id AS measureId, t.id AS taskId,
        x.implementationNote, x.actualAdjustment, x.implementedAt """ + FORMAL_FROM + CONFIRMED_MEASURES +
        " JOIN improvement_executions x ON x.measureId = m.id AND x.recordedBy = c.teacherId " +
        FORMAL_SCOPE + " AND t.id IN (:taskIds) ORDER BY x.implementedAt DESC, x.id DESC")
    suspend fun executions(actorId: Long, taskIds: List<Long>): List<GovernanceExecutionRow>

    /** 先取所有授权核验，再判断最新一条的有效性，不能提前过滤失效来源而回退旧结论。 */
    @Query("""SELECT v.id AS verificationId, l.id AS linkId, l.originTaskId, l.originIssueId,
        l.originIssueSnapshot, l.followUpTaskId, ft.code AS followUpCode,
        v.verdict, v.basis, vu.name AS verifierName, v.verifiedAt,
        v.contractVersion, NULL AS ineligibilityReason """ + FORMAL_FROM + """
        JOIN review_links l ON l.originTaskId = t.id AND l.teacherId = c.teacherId
        JOIN supervision_tasks ft ON ft.id = l.followUpTaskId
        JOIN issue_verifications v ON v.linkId = l.id
        JOIN users vu ON vu.id = v.verifierId
        """ + FORMAL_SCOPE + " AND t.id IN (:taskIds) ORDER BY v.verifiedAt DESC, v.id DESC")
    suspend fun verifications(actorId: Long, taskIds: List<Long>): List<GovernanceVerificationRow>
}
