package edu.neu.aijiaoxue.ui.improve

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import edu.neu.aijiaoxue.data.dao.TodoRow
import edu.neu.aijiaoxue.data.model.MeasureStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ImproveTodoUiState(val loading: Boolean = true, val rows: List<TodoRow> = emptyList(),
    val onlyOpen: Boolean = true, val status: MeasureStatus? = null, val busyId: Long? = null, val error: String? = null)

class ImproveTodoViewModel(private val repository: ImprovementRepository, private val actorId: Long) : ViewModel() {
    private val state = MutableStateFlow(ImproveTodoUiState())
    val uiState = state.asStateFlow()
    private var query: Job? = null
    init { filter(onlyOpen = true) }
    fun filter(onlyOpen: Boolean = false, status: MeasureStatus? = null) {
        query?.cancel()
        state.update { it.copy(onlyOpen = onlyOpen, status = status, loading = true, rows = emptyList(), error = null) }
        query = viewModelScope.launch {
            repository.todos(actorId, onlyOpen, status)
                .catch { e -> state.update { it.copy(loading = false, error = e.message ?: "加载失败") } }
                .collect { rows -> state.update { it.copy(loading = false, rows = rows) } }
        }
    }
    fun updateStatus(id: Long, status: MeasureStatus) {
        if (state.value.busyId != null) return
        state.update { it.copy(busyId = id, error = null) }
        viewModelScope.launch {
            try {
                repository.updateStatus(actorId, id, status)
                state.update { it.copy(busyId = null) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { state.update { it.copy(busyId = null, error = e.message ?: "更新失败") } }
        }
    }
}
