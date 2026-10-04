package edu.neu.aijiaoxue.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import edu.neu.aijiaoxue.data.entity.AiCorrection
import kotlinx.coroutines.flow.Flow

@Dao
interface AiCorrectionDao {
    @Insert
    suspend fun insertCorrection(correction: AiCorrection): Long

    @Query("SELECT * FROM ai_corrections WHERE analysisId = :analysisId ORDER BY createdAt DESC, id DESC")
    fun observeCorrections(analysisId: Long): Flow<List<AiCorrection>>

    @Query("SELECT * FROM ai_corrections WHERE analysisId = :analysisId ORDER BY createdAt DESC, id DESC")
    suspend fun getByAnalysis(analysisId: Long): List<AiCorrection>

    @Query("""SELECT c.* FROM ai_corrections c JOIN ai_analysis_results a ON a.id = c.analysisId
        JOIN supervision_tasks t ON t.id = a.taskId JOIN users u ON u.id = :actorId
        WHERE c.analysisId = :analysisId AND t.supervisorId = u.id AND u.role = 'SUPERVISOR'
        ORDER BY c.createdAt DESC, c.id DESC""")
    fun observeForActor(analysisId: Long, actorId: Long): Flow<List<AiCorrection>>

    @Query("DELETE FROM ai_corrections WHERE analysisId = :analysisId")
    suspend fun deleteByAnalysis(analysisId: Long)
}
