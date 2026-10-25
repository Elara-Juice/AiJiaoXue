package edu.neu.aijiaoxue.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import edu.neu.aijiaoxue.data.entity.AiAnalysisResult
import edu.neu.aijiaoxue.data.entity.AiEvidence
import kotlinx.coroutines.flow.Flow

@Dao
interface AiAnalysisDao {
    @Insert
    suspend fun insertAnalysis(result: AiAnalysisResult): Long

    @Insert
    suspend fun insertEvidences(evidences: List<AiEvidence>)

    @Query("SELECT * FROM ai_analysis_results WHERE taskId = :taskId ORDER BY createdAt ASC, id ASC")
    fun observeAnalysis(taskId: Long): Flow<List<AiAnalysisResult>>

    /** 证据随分析结果级联删除。 */
    @Query("DELETE FROM ai_analysis_results WHERE taskId = :taskId")
    suspend fun deleteByTask(taskId: Long)

    /** 重新分析时整体替换上一轮结果和证据，避免同一任务出现两套结论。 */
    @Transaction
    suspend fun replaceForTask(taskId: Long, results: List<Pair<AiAnalysisResult, List<AiEvidence>>>): List<AiAnalysisResult> {
        deleteByTask(taskId)
        return results.map { (result, evidences) ->
            val id = insertAnalysis(result)
            insertEvidences(evidences.map { it.copy(analysisId = id) })
            result.copy(id = id)
        }
    }

    @Query("SELECT * FROM ai_analysis_results WHERE id = :id LIMIT 1")
    suspend fun getAnalysisById(id: Long): AiAnalysisResult?

    /** US27：只有该任务的督导本人能查看和审核分析结果。 */
    @Query("""SELECT a.* FROM ai_analysis_results a
        JOIN supervision_tasks t ON t.id = a.taskId
        JOIN users u ON u.id = :actorId
        WHERE a.id = :id AND t.supervisorId = u.id AND u.role = 'SUPERVISOR'""")
    suspend fun getAnalysisForActor(id: Long, actorId: Long): AiAnalysisResult?

    @Query("UPDATE ai_analysis_results SET verified = 1 WHERE id = :id")
    suspend fun markVerified(id: Long)
}
