package edu.neu.aijiaoxue.data.ai

import android.content.Context
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.model.*
import edu.neu.aijiaoxue.data.repository.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class AnalysisPlanBuilder(private val db: AppDatabase, private val context: Context) {
    suspend fun preview(actorId: Long, taskId: Long): AnalysisPlanPreview = withContext(Dispatchers.IO) {
        val task = requireNotNull(db.analysisSourceDao().ownedTask(actorId, taskId)) { "无权分析此课堂" }
        val materials = db.captureDao().getMaterials(taskId)
        var reusable = 0
        for (m in materials) {
            val file = AnalysisInputs.safeFile(context, m)
            if (!file.isFile) continue
            val hash = AnalysisInputs.hashFile(file)
            if (m.type == MaterialType.AUDIO && db.analysisSourceDao().transcripts(taskId).any { it.materialId == m.id && it.fileHash == hash && it.state in USABLE } ||
                m.type != MaterialType.AUDIO && db.analysisSourceDao().imageTexts(taskId).any { it.materialId == m.id && it.fileHash == hash && it.state in USABLE }) reusable++
        }
        val hasAudio = materials.any { it.type == MaterialType.AUDIO }
        val hasImage = materials.any { it.type != MaterialType.AUDIO }
        val selection = task.referenceSelectionJson?.let(::JSONObject)
        val hasPoints = (selection?.optJSONArray("selectedKnowledgePointIds")?.length() ?: 0) > 0
        val hasGoals = (selection?.optJSONArray("selectedObjectiveIds")?.length() ?: 0) > 0
        val events = db.captureDao().observeEvents(taskId).first().isNotEmpty()
        AnalysisPlanPreview(materials.size, reusable, SEMANTIC_OPERATIONS.map { operation ->
            val reason = when {
                operation == AnalysisOperation.INTERACTION_STATS && !events -> "尚无实际互动事件"
                operation != AnalysisOperation.INTERACTION_STATS && !hasAudio -> "需要真实音频转写"
                operation == AnalysisOperation.SYLLABUS_ALIGNMENT && !hasPoints -> "请绑定本课堂知识点范围"
                operation == AnalysisOperation.GOAL_ACTIVITY && !hasGoals -> "请绑定本课堂教学目标"
                operation == AnalysisOperation.MATERIAL_SUPPORT && !hasImage -> "需要课堂图片及识别文字"
                else -> null
            }
            AnalysisPreparationItem(operation, reason == null, reason, operation in CloudAnalysisPipeline.LOCAL_OPERATIONS)
        })
    }
    companion object {
        val USABLE = setOf(ResultState.SUPPORTED, ResultState.PARTIAL)
        val SEMANTIC_OPERATIONS = listOf(AnalysisOperation.CONTENT, AnalysisOperation.BOPPPS, AnalysisOperation.SYLLABUS_ALIGNMENT,
            AnalysisOperation.GOAL_ACTIVITY, AnalysisOperation.TRANSITION, AnalysisOperation.MATERIAL_SUPPORT,
            AnalysisOperation.EXPRESSION_STATS, AnalysisOperation.INTERACTION_STATS)
    }
}
