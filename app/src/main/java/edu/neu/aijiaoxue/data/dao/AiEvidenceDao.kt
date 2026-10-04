package edu.neu.aijiaoxue.data.dao


import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import edu.neu.aijiaoxue.data.entity.AiEvidence
import kotlinx.coroutines.flow.Flow


@Dao
interface AiEvidenceDao {


    /**
     * 保存AI证据
     */
    @Insert
    suspend fun insertEvidence(
        evidence: AiEvidence
    )


    /**
     * 查询某个AI分析的证据
     */
    @Query(
        """
        SELECT *
        FROM ai_evidences
        WHERE analysisId = :analysisId
        """
    )
    fun observeEvidence(
        analysisId: Long
    ): Flow<List<AiEvidence>>



    /**
     * 删除某个分析的证据
     */
    @Query(
        """
        DELETE FROM ai_evidences
        WHERE analysisId = :analysisId
        """
    )
    suspend fun deleteByAnalysis(
        analysisId: Long
    )

}