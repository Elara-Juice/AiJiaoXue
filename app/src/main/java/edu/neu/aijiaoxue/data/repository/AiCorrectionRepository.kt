package edu.neu.aijiaoxue.data.repository

import androidx.room.withTransaction
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.entity.AiAnalysisResult
import edu.neu.aijiaoxue.data.entity.AiCorrection
import kotlinx.coroutines.flow.Flow

/** US27：AI 结果人工审核。 */
class AiCorrectionRepository(private val db: AppDatabase) {
    suspend fun loadAnalysis(analysisId: Long, actorId: Long): AiAnalysisResult =
        db.aiAnalysisDao().getAnalysisForActor(analysisId, actorId) ?: throw IllegalStateException("无权限访问该分析结果")

    /** 审核记录与“已审核”标记同时写入；修正内容为空时视为确认原结论。 */
    suspend fun saveCorrection(analysisId: Long, actorId: Long, correctedContent: String) {
        val analysis = loadAnalysis(analysisId, actorId)
        val corrected = correctedContent.trim().ifEmpty { analysis.content }
        db.withTransaction {
            db.aiCorrectionDao().insertCorrection(
                AiCorrection(
                    analysisId = analysisId, reviewerId = actorId, originalContent = analysis.content,
                    correctedContent = corrected, accepted = corrected == analysis.content,
                )
            )
            db.aiAnalysisDao().markVerified(analysisId)
        }
    }

    fun observeCorrections(analysisId: Long): Flow<List<AiCorrection>> =
        db.aiCorrectionDao().observeCorrections(analysisId)
}
