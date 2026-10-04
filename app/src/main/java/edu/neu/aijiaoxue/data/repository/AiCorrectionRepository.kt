package edu.neu.aijiaoxue.data.repository


import edu.neu.aijiaoxue.data.dao.AiCorrectionDao
import edu.neu.aijiaoxue.data.entity.AiCorrection



/**
 * Sprint2 US27
 *
 * AI结果人工审核业务层
 */
class AiCorrectionRepository(

    private val dao: AiCorrectionDao

){



    /**
     * 保存人工审核结果
     */
    suspend fun saveCorrection(

        analysisId: Long,

        originalContent: String,

        correctedContent: String,

        accepted: Boolean

    ){


        dao.insertCorrection(

            AiCorrection(

                analysisId = analysisId,

                originalContent = originalContent,

                correctedContent = correctedContent,

                accepted = accepted

            )

        )


    }



    /**
     * 查询某个AI结果的审核记录
     */
    fun observeCorrections(

        analysisId: Long

    ) =

        dao.observeCorrections(
            analysisId
        )



    /**
     * 删除审核记录
     */
    suspend fun deleteCorrections(

        analysisId: Long

    ){

        dao.deleteByAnalysis(
            analysisId
        )

    }


}