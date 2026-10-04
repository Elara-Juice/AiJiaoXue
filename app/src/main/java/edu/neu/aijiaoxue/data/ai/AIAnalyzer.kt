package edu.neu.aijiaoxue.data.ai


import edu.neu.aijiaoxue.data.entity.Material
import edu.neu.aijiaoxue.data.entity.InteractionEvent


/**
 * AI课堂分析接口
 *
 * Sprint2:
 * 使用Mock实现
 *
 * Sprint3:
 * 可替换真实AI服务
 */
interface AIAnalyzer {


    suspend fun analyze(

        taskId: Long,

        materials: List<Material>,

        events: List<InteractionEvent>

    ): List<AIResult>


}