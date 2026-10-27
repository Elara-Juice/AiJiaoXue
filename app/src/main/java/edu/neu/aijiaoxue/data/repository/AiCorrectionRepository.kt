package edu.neu.aijiaoxue.data.repository

import androidx.room.withTransaction
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.entity.AiAnalysisResult
import edu.neu.aijiaoxue.data.entity.AiCorrection
import edu.neu.aijiaoxue.data.ai.AnalysisJson
import edu.neu.aijiaoxue.data.ai.ManifestEvidence
import org.json.JSONObject
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/** US27：审核人必须来自本次实际登录操作，不从任务负责人倒填历史记录。 */
class AiCorrectionRepository(private val db: AppDatabase) {
    suspend fun loadAnalysis(analysisId: Long, actorId: Long): AiAnalysisResult = db.withTransaction {
        authorizedAnalysis(analysisId, actorId)
    }

    private suspend fun authorizedAnalysis(analysisId: Long, actorId: Long): AiAnalysisResult =
        requireNotNull(db.aiAnalysisDao().getAnalysisForActor(analysisId, actorId)) { "分析不存在或无权限审核" }

    /** 原文、accepted均由可信数据库内容推导，不接受页面伪造审核人或原文。 */
    suspend fun saveCorrection(analysisId: Long, actorId: Long, correctedContent: String): Long = db.withTransaction {
        val analysis = authorizedAnalysis(analysisId, actorId)
        require(analysis.schemaVersion == 0) { "结构化分析须使用字段审核表单" }
        require(analysis.isCurrent) { "此分析已被新版本替代，请重新分析" }
        val corrected = correctedContent.trim().ifEmpty { analysis.content }
        val id = db.aiCorrectionDao().insertCorrection(AiCorrection(
            analysisId = analysisId, originalContent = analysis.content, correctedContent = corrected,
            accepted = corrected == analysis.content, reviewerId = actorId,
        ))
        check(db.aiAnalysisDao().markVerified(analysisId) == 1) { "分析结果已改变，请重新打开" }
        id
    }

    suspend fun loadManifest(analysisId: Long, actorId: Long): String? = db.withTransaction {
        val analysis = authorizedAnalysis(analysisId, actorId)
        analysis.runId?.toLongOrNull()?.let { db.analysisSourceDao().getJob(it) }?.takeIf { it.taskId == analysis.taskId && it.operation == analysis.operation }?.inputManifestJson
    }

    suspend fun saveStructuredCorrection(analysisId: Long, actorId: Long, expectedInputFingerprint: String, expectedPayloadHash: String, correctedPayloadJson: String): Long = db.withTransaction {
        val analysis = authorizedAnalysis(analysisId, actorId)
        require(analysis.schemaVersion == 1 && analysis.isCurrent) { "分析已过期，请重新分析" }
        val original = JSONObject(requireNotNull(analysis.payloadJson))
        val originalHash = AnalysisJson.hashJson(original)
        require(analysis.inputFingerprint == expectedInputFingerprint && originalHash == expectedPayloadHash) { "分析内容已变化，请重新打开" }
        val job = requireNotNull(analysis.runId?.toLongOrNull()?.let { db.analysisSourceDao().getJob(it) }) { "分析原始输入记录缺失" }
        require(job.taskId == analysis.taskId && job.operation == analysis.operation && job.inputFingerprint == analysis.inputFingerprint) { "分析任务与来源不匹配" }
        val manifest = JSONObject(job.inputManifestJson)
        require(ManifestEvidence.isCurrent(db, analysis.taskId, manifest)) { "来源已变化，请使用最新资料重新分析" }
        val edited = JSONObject(correctedPayloadJson)
        StructuredPayloadValidation.validate(analysis.operation, original, edited, manifest, analysis.taskId)
        val payload = AnalysisJson.canonical(edited)
        // Reopening and publishing render the stored canonical payload; persist that same field order here.
        val id = db.aiCorrectionDao().insertCorrection(AiCorrection(analysisId = analysisId, reviewerId = actorId, originalContent = analysis.content, correctedContent = AnalysisJson.summary(JSONObject(payload)), accepted = AnalysisJson.hashJson(edited) == originalHash, originalPayloadHash = originalHash, correctedPayloadJson = payload))
        check(db.aiAnalysisDao().markVerified(analysisId) == 1)
        id
    }

    fun observeCorrections(analysisId: Long, actorId: Long) = flow {
        loadAnalysis(analysisId, actorId)
        emitAll(db.aiCorrectionDao().observeForActor(analysisId, actorId))
    }
}
