package edu.neu.aijiaoxue.ui.analysis

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import edu.neu.aijiaoxue.data.entity.AiAnalysisResult
import edu.neu.aijiaoxue.data.entity.AiCorrection
import edu.neu.aijiaoxue.data.repository.AiCorrectionRepository
import edu.neu.aijiaoxue.data.ai.AnalysisJson
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
    val manifestJson: String? = null,
)

/** US27：督导确认或修正 AI 结论。 */
class AIReviewViewModel(
    private val repository: AiCorrectionRepository,
    private val actorId: Long,
    private val analysisId: Long,
) : ViewModel() {
    private val state = MutableStateFlow(AIReviewUiState())
    val uiState = state.asStateFlow()
    private var observation: Job? = null

    init { reload() }

    fun reload() {
        if (state.value.saving) return
        observation?.cancel()
        state.value = AIReviewUiState()
        observation = viewModelScope.launch {
            try {
                val analysis = repository.loadAnalysis(analysisId, actorId)
                val manifest = repository.loadManifest(analysisId, actorId)
                state.update { it.copy(analysis = analysis, manifestJson = manifest) }
                repository.observeCorrections(analysisId, actorId).collect { list ->
                    val current = repository.loadAnalysis(analysisId, actorId)
                    state.update { it.copy(loading = false, analysis = current, corrections = list) }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { state.update { it.copy(loading = false, error = e.message ?: "加载失败") } }
        }
    }

    fun saveStructured(payloadJson: String) {
        val analysis = state.value.analysis ?: return
        if (state.value.saving || state.value.loading) return
        state.update { it.copy(saving = true, savedAt = null, error = null) }
        viewModelScope.launch {
            try {
                repository.saveStructuredCorrection(analysisId, actorId, requireNotNull(analysis.inputFingerprint), AnalysisJson.hashJson(JSONObject(requireNotNull(analysis.payloadJson))), payloadJson)
                state.update { it.copy(analysis = it.analysis?.copy(verified = true), savedAt = System.currentTimeMillis()) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { state.update { it.copy(error = e.message ?: "结构化审核保存失败") } }
            finally { state.update { it.copy(saving = false) } }
        }
    }

    fun save(correctedContent: String) {
        if (state.value.saving || state.value.loading || state.value.analysis == null) return
        state.update { it.copy(saving = true, savedAt = null, error = null) }
        viewModelScope.launch {
            try {
                repository.saveCorrection(analysisId, actorId, correctedContent)
                state.update { it.copy(analysis = it.analysis?.copy(verified = true), savedAt = System.currentTimeMillis()) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { state.update { it.copy(error = e.message ?: "保存失败") } }
            finally { state.update { it.copy(saving = false) } }
        }
    }
}
