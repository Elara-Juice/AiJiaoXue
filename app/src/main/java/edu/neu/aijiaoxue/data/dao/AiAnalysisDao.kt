package edu.neu.aijiaoxue.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import edu.neu.aijiaoxue.data.entity.AiAnalysisResult
import edu.neu.aijiaoxue.data.entity.AiEvidence
import edu.neu.aijiaoxue.data.model.AnalysisOperation
import kotlinx.coroutines.flow.Flow

@Dao
interface AiAnalysisDao {
    @Query("UPDATE ai_analysis_results SET isCurrent = 0 WHERE taskId = :taskId AND operation = :operation")
    suspend fun deactivate(taskId: Long, operation: AnalysisOperation)

    /** US26/31：只取消旧批次激活，不删除旧审核链和历史来源。 */
    @Transaction
    suspend fun activateBatch(taskId: Long, operation: AnalysisOperation, rows: List<Pair<AiAnalysisResult, List<AiEvidence>>>): List<Long> {
        require(rows.all { it.first.taskId == taskId && it.first.operation == operation && it.second.all { e -> e.taskId == taskId } })
        deactivate(taskId, operation)
        return rows.map { (result, evidence) ->
            val id = insertAnalysis(result.copy(id = 0, isCurrent = true))
            insertEvidences(evidence.map { it.copy(id = 0, analysisId = id) })
            id
        }
    }
    @Insert
    suspend fun insertAnalysis(result: AiAnalysisResult): Long

    @Insert
    suspend fun insertEvidences(evidences: List<AiEvidence>)

    /** 调用者在同一事务内完成授权和输入版本检查；外键级联清理旧审核及证据。 */
    @Transaction
    suspend fun replaceForTask(
        taskId: Long,
        results: List<Pair<AiAnalysisResult, List<AiEvidence>>>,
    ): List<AiAnalysisResult> {
        require(results.all { (result, evidence) ->
            result.taskId == taskId && evidence.all { it.taskId == taskId }
        }) { "分析及证据必须属于同一任务" }
        deleteByTask(taskId)
        return results.map { (result, evidence) ->
            val id = insertAnalysis(result)
            insertEvidences(evidence.map { it.copy(analysisId = id) })
            result.copy(id = id)
        }
    }

    @Query("SELECT * FROM ai_analysis_results WHERE taskId = :taskId AND isCurrent = 1 ORDER BY createdAt, id")
    fun observeAnalysis(taskId: Long): Flow<List<AiAnalysisResult>>

    @Query("SELECT * FROM ai_analysis_results WHERE taskId = :taskId AND isCurrent = 1 ORDER BY createdAt, id")
    suspend fun getByTask(taskId: Long): List<AiAnalysisResult>

    @Query("""SELECT a.* FROM ai_analysis_results a JOIN supervision_tasks t ON t.id=a.taskId JOIN users u ON u.id=:actorId
        WHERE a.taskId=:taskId AND t.supervisorId=u.id AND u.role='SUPERVISOR' ORDER BY a.createdAt DESC,a.id DESC""")
    suspend fun getHistoryForActor(taskId: Long, actorId: Long): List<AiAnalysisResult>

    @Query("SELECT * FROM ai_analysis_results WHERE id = :id LIMIT 1")
    suspend fun getAnalysisById(id: Long): AiAnalysisResult?

    /** US27：路由角色检查不能代替任务归属校验。 */
    @Query("""SELECT a.* FROM ai_analysis_results a
        JOIN supervision_tasks t ON t.id = a.taskId JOIN users u ON u.id = :actorId
        WHERE a.id = :id AND t.supervisorId = u.id AND u.role = 'SUPERVISOR'""")
    suspend fun getAnalysisForActor(id: Long, actorId: Long): AiAnalysisResult?

    @Query("""SELECT a.* FROM ai_analysis_results a
        JOIN supervision_tasks t ON t.id = a.taskId JOIN users u ON u.id = :actorId
        WHERE a.taskId = :taskId AND t.supervisorId = u.id AND u.role = 'SUPERVISOR' AND a.isCurrent = 1
        ORDER BY a.createdAt, a.id""")
    fun observeForActor(taskId: Long, actorId: Long): Flow<List<AiAnalysisResult>>

    @Query("UPDATE ai_analysis_results SET verified = 1 WHERE id = :id")
    suspend fun markVerified(id: Long): Int

    @Query("DELETE FROM ai_analysis_results WHERE taskId = :taskId")
    suspend fun deleteByTask(taskId: Long)
}
