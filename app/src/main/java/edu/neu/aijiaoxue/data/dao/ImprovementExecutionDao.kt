package edu.neu.aijiaoxue.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import edu.neu.aijiaoxue.data.entity.ImprovementEvidence
import edu.neu.aijiaoxue.data.entity.ImprovementExecution
import edu.neu.aijiaoxue.data.entity.ImprovementMeasure
import edu.neu.aijiaoxue.data.entity.SupervisionTask

private const val EXECUTION_SCOPE = """
    JOIN improvement_items i ON i.id = m.itemId AND i.planId = m.planId
    JOIN improvement_plans p ON p.id = m.planId
    JOIN supervision_tasks t ON t.id = p.taskId
    JOIN courses c ON c.id = t.courseId AND c.teacherId = p.teacherId
    JOIN evaluations e ON e.taskId = t.id
    JOIN users u ON u.id = :actorId
    WHERE p.status = 'CONFIRMED' AND t.status = 'COMPLETED' AND e.status = 'SUBMITTED'
"""

private const val EXECUTION_READERS = """
    AND (u.role = 'ADMIN' OR (u.role = 'TEACHER' AND u.id = p.teacherId)
         OR (u.role = 'SUPERVISOR' AND u.id = t.supervisorId))
"""

/** 所有公开业务读取在 SQL 中限制角色、归属和正式提交状态。 */
@Dao
interface ImprovementExecutionDao {
    @Query("""SELECT DISTINCT t.* FROM improvement_measures m """ + EXECUTION_SCOPE + EXECUTION_READERS + " AND t.id = :taskId")
    suspend fun taskForReader(actorId: Long, taskId: Long): SupervisionTask?

    @Query("""SELECT m.* FROM improvement_measures m """ + EXECUTION_SCOPE + EXECUTION_READERS +
        " AND t.id = :taskId ORDER BY m.itemId, m.id")
    suspend fun measuresForReader(actorId: Long, taskId: Long): List<ImprovementMeasure>

    @Query("""SELECT m.* FROM improvement_measures m """ + EXECUTION_SCOPE +
        " AND u.role = 'TEACHER' AND u.id = p.teacherId AND t.id = :taskId AND m.id = :measureId")
    suspend fun measureForWriter(actorId: Long, taskId: Long, measureId: Long): ImprovementMeasure?

    @Query("""SELECT x.* FROM improvement_executions x
        JOIN improvement_measures m ON m.id = x.measureId """ + EXECUTION_SCOPE + EXECUTION_READERS +
        " AND t.id = :taskId ORDER BY x.implementedAt DESC, x.id DESC")
    suspend fun executionsForReader(actorId: Long, taskId: Long): List<ImprovementExecution>

    @Query("""SELECT x.* FROM improvement_executions x
        JOIN improvement_measures m ON m.id = x.measureId """ + EXECUTION_SCOPE +
        " AND u.role = 'TEACHER' AND u.id = p.teacherId AND x.id = :executionId")
    suspend fun executionForWriter(actorId: Long, executionId: Long): ImprovementExecution?

    @Query("""SELECT f.* FROM improvement_evidences f
        JOIN improvement_executions x ON x.id = f.executionId
        JOIN improvement_measures m ON m.id = x.measureId """ + EXECUTION_SCOPE + EXECUTION_READERS +
        " AND x.id = :executionId ORDER BY f.createdAt, f.id")
    suspend fun evidenceForReader(actorId: Long, executionId: Long): List<ImprovementEvidence>

    @Query("""SELECT f.* FROM improvement_evidences f
        JOIN improvement_executions x ON x.id = f.executionId
        JOIN improvement_measures m ON m.id = x.measureId """ + EXECUTION_SCOPE + EXECUTION_READERS +
        " AND f.id = :evidenceId")
    suspend fun singleEvidenceForReader(actorId: Long, evidenceId: Long): ImprovementEvidence?

    // 以下原始关联/幂等查询只在 Repository 已授权的同一事务内使用。
    @Query("""SELECT p.taskId FROM improvement_executions x
        JOIN improvement_measures m ON m.id = x.measureId JOIN improvement_plans p ON p.id = m.planId
        WHERE x.id = :executionId""")
    suspend fun taskIdForExecution(executionId: Long): Long?

    @Query("SELECT * FROM improvement_executions WHERE measureId = :measureId AND requestKey = :requestKey")
    suspend fun executionForRequest(measureId: Long, requestKey: String): ImprovementExecution?

    @Query("SELECT * FROM improvement_evidences WHERE executionId = :executionId AND requestKey = :requestKey")
    suspend fun evidenceForRequest(executionId: Long, requestKey: String): ImprovementEvidence?

    @Insert
    suspend fun insertExecution(execution: ImprovementExecution): Long

    @Insert
    suspend fun insertEvidence(evidence: ImprovementEvidence): Long
}
