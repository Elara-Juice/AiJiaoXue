package edu.neu.aijiaoxue.ui.evidence

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import edu.neu.aijiaoxue.data.entity.AiEvidence
import edu.neu.aijiaoxue.data.repository.AiEvidenceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class EvidenceUiState(val evidences: List<AiEvidence> = emptyList(), val loading: Boolean = false, val error: String? = null)

class EvidenceViewModel(private val repository: AiEvidenceRepository, private val actorId: Long) : ViewModel() {
    private val state = MutableStateFlow(EvidenceUiState())
    val uiState = state.asStateFlow()
    private var observation: Job? = null
    fun loadEvidence(analysisId: Long) {
        observation?.cancel()
        state.value = EvidenceUiState(loading = true)
        observation = viewModelScope.launch {
            try {
                repository.observeEvidence(analysisId, actorId).collect { state.value = EvidenceUiState(evidences = it) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { state.value = EvidenceUiState(error = e.message ?: "证据加载失败") }
        }
    }
}
