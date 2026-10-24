package edu.neu.aijiaoxue.data.repository

import androidx.room.withTransaction
import edu.neu.aijiaoxue.data.AppDatabase
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/** 仅查看该督导本人任务的关联证据，不再将分析结论复制成“证据”。 */
class AiEvidenceRepository(private val db: AppDatabase) {
    fun observeEvidence(analysisId: Long, actorId: Long) = flow {
        db.withTransaction {
            requireNotNull(db.aiAnalysisDao().getAnalysisForActor(analysisId, actorId)) { "证据不存在或无权限访问" }
        }
        emitAll(db.aiEvidenceDao().observeForActor(analysisId, actorId))
    }

    fun observeByTask(taskId: Long, actorId: Long) = flow {
        db.withTransaction {
            requireNotNull(db.taskDao().getEvaluationTaskForActor(taskId, actorId)) { "任务不存在或无权限访问" }
        }
        emitAll(db.aiEvidenceDao().observeByTaskForActor(taskId, actorId))
    }
}
