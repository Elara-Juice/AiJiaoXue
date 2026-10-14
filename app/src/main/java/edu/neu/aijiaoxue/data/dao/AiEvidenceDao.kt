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

    /** US26：分析页一次取出本任务全部证据，按分析结果分组展示。 */
    @Query("SELECT * FROM ai_evidences WHERE taskId = :taskId ORDER BY startMs, id")
    fun observeByTask(taskId: Long): Flow<List<AiEvidence>>

    @Query("DELETE FROM ai_evidences WHERE analysisId = :analysisId")
    suspend fun deleteByAnalysis(analysisId: Long)
}
