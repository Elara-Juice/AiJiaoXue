package edu.neu.aijiaoxue.data.repository


import edu.neu.aijiaoxue.data.ai.AIAnalyzer
import edu.neu.aijiaoxue.data.entity.AiAnalysisResult
import edu.neu.aijiaoxue.data.entity.InteractionEvent
import edu.neu.aijiaoxue.data.entity.Material
import edu.neu.aijiaoxue.data.dao.AiAnalysisDao



/**
 * Sprint2 AI分析业务仓库
 *
 * 负责：
 *
 * AI分析
 * ↓
 * 数据转换
 * ↓
 * 数据保存
 *
 */
class AIAnalysisRepository(

    private val analyzer: AIAnalyzer,

    private val dao: AiAnalysisDao

){



    suspend fun analyzeAndSave(
        taskId: Long,
        materials: List<Material>,
        events: List<InteractionEvent>
    ): List<AiAnalysisResult>{


        val results =
            analyzer.analyze(
                taskId,
                materials,
                events
            )



        val savedResults =
            results.map { result ->


                val entity =
                    AiAnalysisResult(

                        taskId = taskId,

                        type = result.type,

                        content = result.content,

                        confidence = result.confidence,

                        startMs = result.startMs,

                        endMs = result.endMs

                    )


                val id =
                    dao.insertAnalysis(
                        entity
                    )


                entity.copy(
                    id = id
                )

            }


        return savedResults


    }



    fun observeResults(

        taskId: Long

    ) =
        dao.observeAnalysis(taskId)



    suspend fun clearResults(

        taskId: Long

    ){

        dao.deleteByTask(taskId)

    }


}