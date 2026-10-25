package edu.neu.aijiaoxue.ui.analysis

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import edu.neu.aijiaoxue.data.entity.AiAnalysisResult
import edu.neu.aijiaoxue.data.entity.AiEvidence
import edu.neu.aijiaoxue.data.entity.SupervisionTask
import edu.neu.aijiaoxue.data.repository.AIAnalysisRepository
import edu.neu.aijiaoxue.data.repository.AiEvidenceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AnalysisUiState(
    val loading: Boolean = true,
    val task: SupervisionTask? = null,
    val canAnalyze: Boolean = false,
    val analyzing: Boolean = false,
    val results: List<AiAnalysisResult> = emptyList(),
    /** US26：按 analysisId 分组的证据。 */
    val evidences: Map<Long, List<AiEvidence>> = emptyMap(),
    val error: String? = null,
)

class AnalysisViewModel(
    private val repository: AIAnalysisRepository,
    private val evidenceRepository: AiEvidenceRepository,
    private val actorId: Long,
    private val taskId: Long,
) : ViewModel() {
    private val state = MutableStateFlow(AnalysisUiState())
    val uiState = state.asStateFlow()
    private var observation: Job? = null

    init { reload() }

    fun reload() {
        if (state.value.analyzing) return
        observation?.cancel()
        state.value = AnalysisUiState()
        observation = viewModelScope.launch {
            try {
                val task = repository.loadTask(taskId, actorId)
                state.update { it.copy(task = task, canAnalyze = repository.canAnalyze(task)) }
                // 已有分析结果直接展示，不重复分析
                combine(repository.observeResults(taskId, actorId), evidenceRepository.observeByTask(taskId, actorId)) { results, evidences ->
                    results to evidences.groupBy { it.analysisId }
                }.collect { (results, evidences) ->
                    state.update { it.copy(loading = false, results = results, evidences = evidences) }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { state.update { it.copy(loading = false, error = e.message ?: "加载失败") } }
        }
    }

    fun analyze() {
        if (state.value.analyzing || state.value.loading || !state.value.canAnalyze) return
        state.update { it.copy(analyzing = true, error = null) }
        viewModelScope.launch {
            try { repository.analyzeAndSave(taskId, actorId) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { state.update { it.copy(error = e.message ?: "分析失败，原结果保留") } }
            finally { state.update { it.copy(analyzing = false) } }
        }
    }
}
