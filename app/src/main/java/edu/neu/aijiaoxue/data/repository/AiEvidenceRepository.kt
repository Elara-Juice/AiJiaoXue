package edu.neu.aijiaoxue.data.repository

import edu.neu.aijiaoxue.data.dao.AiEvidenceDao
import edu.neu.aijiaoxue.data.entity.AiEvidence
import kotlinx.coroutines.flow.Flow

/** US26：证据在 AIAnalysisRepository.analyzeAndSave 中随分析结果一起写入，这里只负责查询。 */
class AiEvidenceRepository(private val dao: AiEvidenceDao) {
    fun observeEvidence(analysisId: Long): Flow<List<AiEvidence>> = dao.observeEvidence(analysisId)

    fun observeByTask(taskId: Long): Flow<List<AiEvidence>> = dao.observeByTask(taskId)
}
