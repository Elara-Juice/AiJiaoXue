package edu.neu.aijiaoxue.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import edu.neu.aijiaoxue.data.entity.AiCorrection
import kotlinx.coroutines.flow.Flow

/** US27：AI 结果人工审核。 */
@Dao
interface AiCorrectionDao {
    @Insert
    suspend fun insertCorrection(correction: AiCorrection): Long

    @Query("SELECT * FROM ai_corrections WHERE analysisId = :analysisId ORDER BY createdAt DESC, id DESC")
    fun observeCorrections(analysisId: Long): Flow<List<AiCorrection>>

    @Query("DELETE FROM ai_corrections WHERE analysisId = :analysisId")
    suspend fun deleteByAnalysis(analysisId: Long)
}
