package edu.neu.aijiaoxue.ui.governance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import edu.neu.aijiaoxue.data.repository.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GovernanceUiState(
    val filter: GovernanceFilter = GovernanceFilter(), val loading: Boolean = true,
    val dashboard: GovernanceDashboard? = null, val error: String? = null,
)

class GovernanceViewModel(private val repository: GovernanceRepository, private val actorId: Long) : ViewModel() {
    private val state = MutableStateFlow(GovernanceUiState())
    val uiState = state.asStateFlow()
    private var loadJob: Job? = null
    init { reload() }

    fun setFilter(filter: GovernanceFilter) { state.update { it.copy(filter = filter) } }

    fun reload() {
        loadJob?.cancel()
        val filter = state.value.filter
        state.update { it.copy(loading = true, error = null) }
        loadJob = viewModelScope.launch {
            try {
                val result = repository.load(actorId, filter)
                state.update { it.copy(loading = false, dashboard = result) }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                state.update { it.copy(loading = false, dashboard = null, error = e.message ?: "趋势加载失败") }
            }
        }
    }
}
