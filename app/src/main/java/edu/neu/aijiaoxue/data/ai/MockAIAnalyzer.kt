package edu.neu.aijiaoxue.data.ai


import edu.neu.aijiaoxue.data.entity.InteractionEvent
import edu.neu.aijiaoxue.data.entity.Material



/**
 * Sprint2阶段模拟AI分析器
 *
 * 当前：
 * 使用规则模拟AI结果
 *
 * 后续Sprint：
 * 替换真实AI服务即可
 */
class MockAIAnalyzer :  AIAnalyzer {


    override suspend fun analyze(

        taskId: Long,

        materials: List<Material>,

        events: List<InteractionEvent>

    ): List<AIResult> {


        val results = mutableListOf<AIResult>()



        /*
         * US19:
         * 自动识别BOPPPS教学环节
         */
        results.add(

            AIResult(

                type = "BOPPPS",

                content =
                    "检测到课堂包含导入、讲授、互动、总结等教学环节",

                confidence = 0.86f,

                startMs = 0L,

                endMs = 600000L

            )

        )



        /*
         * US23:
         * 量化师生互动频次
         */
        results.add(

            AIResult(

                type = "INTERACTION",

                content =
                    "检测到课堂互动事件${events.size}次，教师提问与学生反馈较为集中",

                confidence = 0.82f,

                startMs = 120000L,

                endMs = 300000L

            )

        )



        /*
         * Sprint3铺垫：
         * 学生参与趋势分析
         */
        results.add(

            AIResult(

                type = "ENGAGEMENT",

                content =
                    "学生课堂参与状态整体良好，可继续关注互动深度",

                confidence = 0.80f,

                startMs = 300000L,

                endMs = 500000L

            )

        )


        return results

    }


}