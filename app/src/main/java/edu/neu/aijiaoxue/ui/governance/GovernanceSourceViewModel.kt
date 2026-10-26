package edu.neu.aijiaoxue.ui.governance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import edu.neu.aijiaoxue.data.repository.GovernanceRepository
import edu.neu.aijiaoxue.data.repository.GovernanceTaskDetail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class GovernanceSourceUiState(val loading: Boolean = true, val detail: GovernanceTaskDetail? = null, val error: String? = null)

class GovernanceSourceViewModel(
    private val repository: GovernanceRepository, private val actorId: Long, private val taskId: Long,
) : ViewModel() {
    private val state = MutableStateFlow(GovernanceSourceUiState())
    val uiState = state.asStateFlow()
    private var loadJob: Job? = null
    init { reload() }
    fun reload() {
        loadJob?.cancel()
        state.value = GovernanceSourceUiState()
        loadJob = viewModelScope.launch {
            try { state.value = GovernanceSourceUiState(loading = false, detail = repository.sourceDetail(actorId, taskId))
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { state.value = GovernanceSourceUiState(loading = false, error = e.message ?: "课堂详情加载失败") }
        }
    }
}
