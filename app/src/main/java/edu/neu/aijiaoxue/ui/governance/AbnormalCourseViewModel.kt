package edu.neu.aijiaoxue.ui.governance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import edu.neu.aijiaoxue.data.repository.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AbnormalCourseUiState(val loading: Boolean = true, val filter: GovernanceFilter = GovernanceFilter(),
                                 val result: AbnormalCourseResult? = null, val error: String? = null)

class AbnormalCourseViewModel(private val repository: AbnormalCourseRepository, private val actorId: Long) : ViewModel() {
    private val state = MutableStateFlow(AbnormalCourseUiState())
    val uiState = state.asStateFlow()
    init { reload() }
    fun setFilter(value: GovernanceFilter) { state.update { it.copy(filter = value) } }
    fun reload() {
        val filter = state.value.filter
        state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val result = repository.load(actorId, filter)
                state.update { it.copy(loading = false, result = result) }
            }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { state.update { it.copy(loading = false, error = e.message ?: "关注课程读取失败") } }
        }
    }
}
