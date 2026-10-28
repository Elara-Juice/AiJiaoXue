package edu.neu.aijiaoxue.data.dao

import androidx.room.Dao
import androidx.room.Query
import edu.neu.aijiaoxue.data.model.*
import kotlinx.coroutines.flow.Flow

data class SupervisorTaskRow(val id: Long, val courseId: Long, val code: String, val courseName: String,
    val teacherName: String, val supervisionDate: String, val period: String, val chapter: String?,
    val status: TaskStatus, val type: SupervisionType, val materialCount: Int, val resultCount: Int,
    val pendingReviewCount: Int, val hasDraft: Boolean, val activeJobCount: Int)

data class SupervisorIssueRow(val id: Long, val taskId: Long, val courseId: Long, val courseName: String,
    val teacherName: String, val supervisionDate: String, val description: String, val dimension: EvalDimension?,
    val teacherReadAt: Long?, val planConfirmed: Boolean, val measureCount: Int, val doneCount: Int,
    val executionCount: Int, val dueDate: String?, val changeRevision: Int)

private const val TASKS = """SELECT t.id,t.courseId,t.code,c.name AS courseName,u.name AS teacherName,
    t.supervisionDate,t.period,t.chapter,t.status,t.type,
    (SELECT COUNT(*) FROM materials m WHERE m.taskId=t.id) AS materialCount,
    (SELECT COUNT(*) FROM ai_analysis_results a WHERE a.taskId=t.id AND a.isCurrent=1) AS resultCount,
    (SELECT COUNT(*) FROM ai_analysis_results a WHERE a.taskId=t.id AND a.isCurrent=1 AND a.verified=0) AS pendingReviewCount,
    EXISTS(SELECT 1 FROM evaluations e WHERE e.taskId=t.id AND e.status='DRAFT') AS hasDraft,
    (SELECT COUNT(*) FROM analysis_jobs j WHERE j.taskId=t.id AND j.state IN ('QUEUED','RUNNING')) AS activeJobCount
    FROM supervision_tasks t JOIN courses c ON c.id=t.courseId JOIN users u ON u.id=c.teacherId
    JOIN users actor ON actor.id=:actorId AND actor.role='SUPERVISOR'
    WHERE t.supervisorId=:actorId """

private const val ISSUES = """SELECT i.id,t.id AS taskId,t.courseId,c.name AS courseName,u.name AS teacherName,
    t.supervisionDate,i.description,i.dimension,e.teacherReadAt,
    EXISTS(SELECT 1 FROM improvement_plans p WHERE p.taskId=t.id AND p.teacherId=c.teacherId AND p.status='CONFIRMED') AS planConfirmed,
    (SELECT COUNT(*) FROM improvement_measures m JOIN improvement_items item ON item.id=m.itemId AND item.planId=m.planId
        JOIN improvement_plans p ON p.id=item.planId WHERE item.issueId=i.id AND p.taskId=t.id AND p.teacherId=c.teacherId AND p.status='CONFIRMED') AS measureCount,
    (SELECT COUNT(*) FROM improvement_measures m JOIN improvement_items item ON item.id=m.itemId AND item.planId=m.planId
        JOIN improvement_plans p ON p.id=item.planId WHERE item.issueId=i.id AND p.taskId=t.id AND p.teacherId=c.teacherId AND p.status='CONFIRMED' AND m.status='DONE') AS doneCount,
    (SELECT COUNT(*) FROM improvement_executions x JOIN improvement_measures m ON m.id=x.measureId
        JOIN improvement_items item ON item.id=m.itemId AND item.planId=m.planId JOIN improvement_plans p ON p.id=item.planId
        WHERE item.issueId=i.id AND p.taskId=t.id AND p.teacherId=c.teacherId AND p.status='CONFIRMED') AS executionCount,
    (SELECT MIN(m.dueDate) FROM improvement_measures m JOIN improvement_items item ON item.id=m.itemId AND item.planId=m.planId
        JOIN improvement_plans p ON p.id=item.planId WHERE item.issueId=i.id AND p.taskId=t.id AND p.teacherId=c.teacherId AND p.status='CONFIRMED' AND m.status!='DONE') AS dueDate,
    (SELECT COUNT(*) FROM review_links l LEFT JOIN issue_verifications v ON v.linkId=l.id
        LEFT JOIN issue_verification_evidence ve ON ve.verificationId=v.id WHERE l.originIssueId=i.id) AS changeRevision
    FROM evaluation_issues i JOIN evaluations e ON e.id=i.evaluationId AND e.status='SUBMITTED'
    JOIN supervision_tasks t ON t.id=e.taskId AND t.status='COMPLETED'
    JOIN courses c ON c.id=t.courseId JOIN users u ON u.id=c.teacherId
    JOIN users actor ON actor.id=:actorId AND actor.role='SUPERVISOR'
    WHERE t.supervisorId=:actorId """

@Dao
interface SupervisorPortalDao {
    @Query(TASKS + " ORDER BY t.supervisionDate DESC,t.id DESC")
    fun observeTasks(actorId: Long): Flow<List<SupervisorTaskRow>>
    @Query(TASKS + " AND t.id=:taskId")
    suspend fun task(actorId: Long, taskId: Long): SupervisorTaskRow?
    @Query(ISSUES + " ORDER BY t.supervisionDate DESC,i.sortOrder,i.id")
    fun observeIssues(actorId: Long): Flow<List<SupervisorIssueRow>>
    @Query(ISSUES + " AND i.id=:issueId")
    suspend fun issue(actorId: Long, issueId: Long): SupervisorIssueRow?
}
