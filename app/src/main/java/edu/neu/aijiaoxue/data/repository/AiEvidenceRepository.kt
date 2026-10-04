package edu.neu.aijiaoxue.data.repository


import edu.neu.aijiaoxue.data.dao.AiEvidenceDao
import edu.neu.aijiaoxue.data.entity.AiEvidence
import edu.neu.aijiaoxue.data.entity.AiAnalysisResult



/**
 * Sprint2 US26
 *
 * AI结果与课堂证据关联
 */
class AiEvidenceRepository(

    private val dao: AiEvidenceDao

){



    suspend fun createEvidence(

        analysis: AiAnalysisResult

    ){


        val evidence = AiEvidence(

            analysisId = analysis.id,

            taskId = analysis.taskId,

            startMs = analysis.startMs,

            endMs = analysis.endMs,

            description =
                "AI分析依据：${analysis.content}"

        )


        dao.insertEvidence(
            evidence
        )

    }




    fun observeEvidence(

        analysisId: Long

    ) =
        dao.observeEvidence(
            analysisId
        )



    suspend fun deleteEvidence(

        analysisId: Long

    ){

        dao.deleteByAnalysis(
            analysisId
        )

    }


}