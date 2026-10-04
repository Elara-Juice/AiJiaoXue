package edu.neu.aijiaoxue.data.dao


import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import edu.neu.aijiaoxue.data.entity.AiCorrection
import kotlinx.coroutines.flow.Flow



/**
 * Sprint2 US27
 *
 * AI结果人工审核 DAO
 */
@Dao
interface AiCorrectionDao {


    /**
     * 保存人工审核结果
     */
    @Insert
    suspend fun insertCorrection(

        correction: AiCorrection

    ): Long



    /**
     * 查询某个AI分析的审核记录
     */
    @Query(
        """
        SELECT *
        FROM ai_corrections
        WHERE analysisId = :analysisId
        ORDER BY createdAt DESC
        """
    )
    fun observeCorrections(

        analysisId: Long

    ): Flow<List<AiCorrection>>



    /**
     * 删除某个分析的审核记录
     */
    @Query(
        """
        DELETE FROM ai_corrections
        WHERE analysisId = :analysisId
        """
    )
    suspend fun deleteByAnalysis(

        analysisId: Long

    )


}