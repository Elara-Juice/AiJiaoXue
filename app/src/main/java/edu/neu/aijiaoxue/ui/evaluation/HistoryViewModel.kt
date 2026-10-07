package edu.neu.aijiaoxue.ui.evaluation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import edu.neu.aijiaoxue.data.dao.HistoryRow
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HistoryUiState(val keyword: String = "", val from: String = "", val to: String = "",
    val loading: Boolean = true, val rows: List<HistoryRow> = emptyList(), val error: String? = null)

class HistoryViewModel(private val repository: EvaluationRepository, private val actorId: Long) : ViewModel() {
    private val state = MutableStateFlow(HistoryUiState())
    val uiState = state.asStateFlow()
    private var query: Job? = null
    init { search() }
    fun keyword(value: String) { state.update { it.copy(keyword = value) } }
    fun dates(from: String = state.value.from, to: String = state.value.to) {
        state.update { it.copy(from = from, to = to) }; search()
    }
    fun reset() { state.value = HistoryUiState(); search() }
    fun search() {
        query?.cancel()
        val filter = state.value
        state.update { it.copy(loading = true, error = null, rows = emptyList()) }
        query = viewModelScope.launch {
            repository.history(actorId, filter.keyword, filter.from, filter.to)
                .catch { e -> state.update { it.copy(loading = false, error = e.message ?: "检索失败") } }
                .collect { rows -> state.update { it.copy(loading = false, rows = rows) } }
        }
    }
}
