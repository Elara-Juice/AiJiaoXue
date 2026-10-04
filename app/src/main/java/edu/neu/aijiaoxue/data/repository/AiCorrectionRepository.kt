package edu.neu.aijiaoxue.data.repository

import androidx.room.withTransaction
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.entity.AiAnalysisResult
import edu.neu.aijiaoxue.data.entity.AiCorrection
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
        val corrected = correctedContent.trim().ifEmpty { analysis.content }
        val id = db.aiCorrectionDao().insertCorrection(AiCorrection(
            analysisId = analysisId, originalContent = analysis.content, correctedContent = corrected,
            accepted = corrected == analysis.content, reviewerId = actorId,
        ))
        check(db.aiAnalysisDao().markVerified(analysisId) == 1) { "分析结果已改变，请重新打开" }
        id
    }

    fun observeCorrections(analysisId: Long, actorId: Long) = flow {
        loadAnalysis(analysisId, actorId)
        emitAll(db.aiCorrectionDao().observeForActor(analysisId, actorId))
    }
}
