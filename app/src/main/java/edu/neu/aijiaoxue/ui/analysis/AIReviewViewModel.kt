package edu.neu.aijiaoxue.ui.analysis

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import edu.neu.aijiaoxue.data.entity.AiAnalysisResult
import edu.neu.aijiaoxue.data.entity.AiCorrection
import edu.neu.aijiaoxue.data.repository.AiCorrectionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AIReviewUiState(
    val analysis: AiAnalysisResult? = null,
    val corrections: List<AiCorrection> = emptyList(),
    val loading: Boolean = false,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val error: String? = null,
)

class AIReviewViewModel(private val repository: AiCorrectionRepository, private val actorId: Long) : ViewModel() {
    private val state = MutableStateFlow(AIReviewUiState())
    val uiState = state.asStateFlow()
    private var observation: Job? = null

    fun load(analysisId: Long) {
        observation?.cancel()
        state.value = AIReviewUiState(loading = true)
        observation = viewModelScope.launch {
            try {
                repository.loadAnalysis(analysisId, actorId)
                repository.observeCorrections(analysisId, actorId).collect { corrections ->
                    val analysis = repository.loadAnalysis(analysisId, actorId)
                    state.update { it.copy(analysis = analysis, corrections = corrections, loading = false) }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { state.value = AIReviewUiState(error = e.message ?: "加载失败") }
        }
    }

    // 保留原Screen调用形式；original/accepted不作为可信输入，Repository会从库内推导。
    @Suppress("UNUSED_PARAMETER")
    fun save(analysisId: Long, original: String, corrected: String, accepted: Boolean) {
        if (state.value.saving || state.value.analysis?.id != analysisId) return
        state.update { it.copy(saving = true, saved = false, error = null) }
        viewModelScope.launch {
            try {
                repository.saveCorrection(analysisId, actorId, corrected)
                state.update { it.copy(analysis = it.analysis?.copy(verified = true), saved = true) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { state.update { it.copy(error = e.message ?: "保存失败") } }
            finally { state.update { it.copy(saving = false) } }
        }
    }
}
