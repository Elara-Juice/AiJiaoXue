package edu.neu.aijiaoxue.data.ai

import edu.neu.aijiaoxue.data.entity.EngagementRecord
import edu.neu.aijiaoxue.data.entity.InteractionEvent
import edu.neu.aijiaoxue.data.entity.Material

/** AI 课堂分析接口。Sprint 2 用 [MockAIAnalyzer]，Sprint 3 可替换为真实服务。 */
interface AIAnalyzer {
    suspend fun analyze(
        taskId: Long,
        materials: List<Material>,
        events: List<InteractionEvent>,
        engagements: List<EngagementRecord>,
    ): List<AIResult>
}
