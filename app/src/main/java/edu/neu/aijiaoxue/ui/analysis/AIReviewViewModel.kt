package edu.neu.aijiaoxue.ui.analysis

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import edu.neu.aijiaoxue.data.entity.AiAnalysisResult
import edu.neu.aijiaoxue.data.entity.AiCorrection
import edu.neu.aijiaoxue.data.repository.AiCorrectionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AIReviewUiState(
    val loading: Boolean = true,
    val analysis: AiAnalysisResult? = null,
    val corrections: List<AiCorrection> = emptyList(),
    val saving: Boolean = false,
    /** 保存成功后提示一次。 */
    val savedAt: Long? = null,
    val error: String? = null,
)

/** US27：督导确认或修正 AI 结论。 */
class AIReviewViewModel(
    private val repository: AiCorrectionRepository,
    private val actorId: Long,
    private val analysisId: Long,
) : ViewModel() {
    private val state = MutableStateFlow(AIReviewUiState())
    val uiState = state.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val analysis = repository.loadAnalysis(analysisId, actorId)
                state.update { it.copy(analysis = analysis) }
                repository.observeCorrections(analysisId).collect { list ->
                    state.update { it.copy(loading = false, corrections = list) }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { state.update { it.copy(loading = false, error = e.message ?: "加载失败") } }
        }
    }

    fun save(correctedContent: String) {
        if (state.value.saving) return
        viewModelScope.launch {
            state.update { it.copy(saving = true, error = null) }
            try {
                repository.saveCorrection(analysisId, actorId, correctedContent)
                state.update { it.copy(analysis = it.analysis?.copy(verified = true), savedAt = System.currentTimeMillis()) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { state.update { it.copy(error = e.message ?: "保存失败") } }
            finally { state.update { it.copy(saving = false) } }
        }
    }
}
