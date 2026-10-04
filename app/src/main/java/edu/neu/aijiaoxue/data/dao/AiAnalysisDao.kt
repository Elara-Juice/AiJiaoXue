package edu.neu.aijiaoxue.data.dao


import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import edu.neu.aijiaoxue.data.entity.AiAnalysisResult
import kotlinx.coroutines.flow.Flow


@Dao
interface AiAnalysisDao {


    /**
     * 保存AI分析结果
     */
    @Insert
    suspend fun insertAnalysis(
        result: AiAnalysisResult
    ): Long


    /**
     * 查询某次督导任务的AI结果
     */
    @Query(
        """
        SELECT * 
        FROM ai_analysis_results
        WHERE taskId = :taskId
        ORDER BY createdAt ASC
        """
    )
    fun observeAnalysis(
        taskId: Long
    ): Flow<List<AiAnalysisResult>>


    /**
     * 删除某次任务AI结果
     */
    @Query(
        """
        DELETE FROM ai_analysis_results
        WHERE taskId = :taskId
        """
    )
    suspend fun deleteByTask(
        taskId: Long
    )

    /**
     * US27
     *
     * 查询单条AI分析结果
     */
    @Query(
        """
    SELECT *
    FROM ai_analysis_results
    WHERE id = :id
    LIMIT 1
    """
    )
    suspend fun getAnalysisById(

        id: Long

    ): AiAnalysisResult?

}