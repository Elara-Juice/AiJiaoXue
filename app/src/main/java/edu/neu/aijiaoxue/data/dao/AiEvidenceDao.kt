package edu.neu.aijiaoxue.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import edu.neu.aijiaoxue.data.entity.AiEvidence
import kotlinx.coroutines.flow.Flow

@Dao
interface AiEvidenceDao {
    @Insert
    suspend fun insertEvidence(evidence: AiEvidence)

    @Query("SELECT * FROM ai_evidences WHERE analysisId = :analysisId ORDER BY startMs, id")
    fun observeEvidence(analysisId: Long): Flow<List<AiEvidence>>

    @Query("SELECT * FROM ai_evidences WHERE analysisId = :analysisId ORDER BY startMs, id")
    suspend fun getByAnalysis(analysisId: Long): List<AiEvidence>

    @Query("""SELECT e.* FROM ai_evidences e JOIN ai_analysis_results a ON a.id = e.analysisId
        JOIN supervision_tasks t ON t.id = a.taskId JOIN users u ON u.id = :actorId
        WHERE e.analysisId = :analysisId AND e.taskId = a.taskId
        AND t.supervisorId = u.id AND u.role = 'SUPERVISOR' ORDER BY e.startMs, e.id""")
    fun observeForActor(analysisId: Long, actorId: Long): Flow<List<AiEvidence>>

    @Query("""SELECT e.* FROM ai_evidences e JOIN ai_analysis_results a ON a.id = e.analysisId
        JOIN supervision_tasks t ON t.id = a.taskId JOIN users u ON u.id = :actorId
        WHERE a.taskId = :taskId AND e.taskId = a.taskId
        AND t.supervisorId = u.id AND u.role = 'SUPERVISOR' ORDER BY e.startMs, e.id""")
    fun observeByTaskForActor(taskId: Long, actorId: Long): Flow<List<AiEvidence>>

    @Query("DELETE FROM ai_evidences WHERE analysisId = :analysisId")
    suspend fun deleteByAnalysis(analysisId: Long)
}
