package edu.neu.aijiaoxue.ui.evaluation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HistoryDetailUiState(val loading: Boolean = true, val record: ClassroomRecord? = null, val error: String? = null)

class HistoryDetailViewModel(private val repository: EvaluationRepository, private val actorId: Long, private val taskId: Long) : ViewModel() {
    private val state = MutableStateFlow(HistoryDetailUiState())
    val uiState = state.asStateFlow()
    init { reload() }
    fun reload() { viewModelScope.launch {
        state.value = HistoryDetailUiState()
        try { state.value = HistoryDetailUiState(false, repository.load(actorId, taskId, history = true)) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { state.value = HistoryDetailUiState(false, error = e.message ?: "加载失败") }
    } }
}
