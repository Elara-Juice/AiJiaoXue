package edu.neu.aijiaoxue.data.repository

import android.content.Context
import androidx.room.withTransaction
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.ai.*
import edu.neu.aijiaoxue.data.entity.*
import edu.neu.aijiaoxue.data.model.*
import edu.neu.aijiaoxue.data.remote.AnalysisSettings
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class AnalysisSelection(val operations: Set<AnalysisOperation> = setOf(AnalysisOperation.CONTENT, AnalysisOperation.BOPPPS))
data class AnalysisPreparationItem(val operation: AnalysisOperation, val available: Boolean, val reason: String?, val local: Boolean)
data class AnalysisPlanPreview(val materialCount: Int, val reusableMaterialCount: Int, val items: List<AnalysisPreparationItem>)
data class AnalysisRunSnapshot(val run: AnalysisRun, val steps: List<AnalysisRunStep>)

class AnalysisRunRepository(private val db: AppDatabase, private val context: Context) {
    suspend fun preview(actorId: Long, taskId: Long) = AnalysisPlanBuilder(db, context).preview(actorId, taskId)
    suspend fun latest(actorId: Long, taskId: Long): AnalysisRunSnapshot? {
        requireNotNull(db.analysisSourceDao().ownedTask(actorId, taskId)) { "无权查看本课堂分析" }
        return db.analysisRunDao().runs(taskId).firstOrNull()?.let { AnalysisRunSnapshot(it, db.analysisRunDao().steps(it.id)) }
    }
    suspend fun snapshot(actorId: Long, runId: Long): AnalysisRunSnapshot {
        val run = requireNotNull(db.analysisRunDao().run(runId)) { "分析计划不存在" }
        requireNotNull(db.analysisSourceDao().ownedTask(actorId, run.taskId)) { "无权查看本课堂分析" }
        require(run.createdBy == actorId)
        return AnalysisRunSnapshot(run, db.analysisRunDao().steps(runId))
    }
    suspend fun createRun(actorId: Long, taskId: Long, selection: AnalysisSelection, requestKey: String): Long {
        UUID.fromString(requestKey)
        require(selection.operations.all { it in AnalysisPlanBuilder.SEMANTIC_OPERATIONS }) { "推荐计划包含不支持的能力" }
        return db.withTransaction {
            val task = requireNotNull(db.analysisSourceDao().ownedTask(actorId, taskId)) { "无权分析此课堂" }
            require(task.status in setOf(TaskStatus.PENDING_EVALUATION, TaskStatus.COMPLETED)) { "请先结束课堂采集" }
            db.analysisRunDao().byRequest(requestKey)?.let { require(it.taskId == taskId && it.createdBy == actorId); return@withTransaction it.id }
            db.analysisRunDao().runs(taskId).firstOrNull { it.state !in TERMINAL }?.let { return@withTransaction it.id }
            val accepted = AnalysisRunInputs.capture(db, context, taskId)
            val now = System.currentTimeMillis()
            val id = db.analysisRunDao().insertRun(AnalysisRun(requestKey = requestKey, taskId = taskId, createdBy = actorId,
                selectedOperationsJson = JSONArray(selection.operations.map { it.name }.sorted()).toString(),
                materialSelectionJson = accepted.getJSONArray("materials").toString(), acceptedInputsJson = accepted.toString(),
                proxyBaseUrl = AnalysisSettings(context).get().baseUrl, createdAt = now, updatedAt = now))
            db.analysisRunDao().insertSteps(buildSteps(id, db.captureDao().getMaterials(taskId), selection.operations, now))
            id
        }
    }
    companion object {
        val TERMINAL = setOf(AnalysisRunState.COMPLETED, AnalysisRunState.PARTIAL, AnalysisRunState.CANCELLED)
        fun buildSteps(runId: Long, materials: List<Material>, operations: Set<AnalysisOperation>, now: Long, revision: Int = 1): List<AnalysisRunStep> {
            val materialSteps = materials.sortedBy { it.id }.map { material ->
                val operation = if (material.type == MaterialType.AUDIO) AnalysisOperation.TRANSCRIBE else AnalysisOperation.IMAGE_TEXT
                AnalysisRunStep(runId = runId, stepKey = "r$revision-material-${material.id}", operation = operation, materialId = material.id, createdAt = now, updatedAt = now)
            }
            return materialSteps + operations.sortedBy { it.ordinal }.map { operation ->
                AnalysisRunStep(runId = runId, stepKey = "r$revision-${operation.name}", operation = operation,
                    state = AnalysisStepState.BLOCKED, blockReason = "材料处理完成后，请核对转写再继续分析",
                    dependsOnJson = JSONArray(materialSteps.map { it.stepKey }).toString(), createdAt = now, updatedAt = now)
            }
        }
    }
}
