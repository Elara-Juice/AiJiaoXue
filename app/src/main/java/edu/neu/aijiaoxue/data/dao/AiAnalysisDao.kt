package edu.neu.aijiaoxue.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import edu.neu.aijiaoxue.data.entity.AiAnalysisResult
import kotlinx.coroutines.flow.Flow

@Dao
interface AiAnalysisDao {
    @Insert
    suspend fun insertAnalysis(result: AiAnalysisResult): Long

    @Query("SELECT * FROM ai_analysis_results WHERE taskId = :taskId ORDER BY createdAt, id")
    fun observeAnalysis(taskId: Long): Flow<List<AiAnalysisResult>>

    @Query("SELECT * FROM ai_analysis_results WHERE taskId = :taskId ORDER BY createdAt, id")
    suspend fun getByTask(taskId: Long): List<AiAnalysisResult>

    @Query("SELECT * FROM ai_analysis_results WHERE id = :id LIMIT 1")
    suspend fun getAnalysisById(id: Long): AiAnalysisResult?

    /** US27：路由角色检查不能代替任务归属校验。 */
    @Query("""SELECT a.* FROM ai_analysis_results a
        JOIN supervision_tasks t ON t.id = a.taskId JOIN users u ON u.id = :actorId
        WHERE a.id = :id AND t.supervisorId = u.id AND u.role = 'SUPERVISOR'""")
    suspend fun getAnalysisForActor(id: Long, actorId: Long): AiAnalysisResult?

    @Query("""SELECT a.* FROM ai_analysis_results a
        JOIN supervision_tasks t ON t.id = a.taskId JOIN users u ON u.id = :actorId
        WHERE a.taskId = :taskId AND t.supervisorId = u.id AND u.role = 'SUPERVISOR'
        ORDER BY a.createdAt, a.id""")
    fun observeForActor(taskId: Long, actorId: Long): Flow<List<AiAnalysisResult>>

    @Query("UPDATE ai_analysis_results SET verified = 1 WHERE id = :id")
    suspend fun markVerified(id: Long): Int

    @Query("DELETE FROM ai_analysis_results WHERE taskId = :taskId")
    suspend fun deleteByTask(taskId: Long)
}
