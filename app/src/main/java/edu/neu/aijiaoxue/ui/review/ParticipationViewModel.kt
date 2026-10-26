package edu.neu.aijiaoxue.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import edu.neu.aijiaoxue.data.analysis.ParticipationInsight
import edu.neu.aijiaoxue.data.repository.ReviewRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Sprint 3（李奕萱）US24/US25：多源参与与参与异常页状态。
 */
data class ParticipationUiState(
    val loading: Boolean = true,
    val insight: ParticipationInsight? = null,
    val error: String? = null,
)

class ParticipationViewModel(
    private val repository: ReviewRepository,
    private val actorId: Long,
    private val taskId: Long,
) : ViewModel() {
    private val state = MutableStateFlow(ParticipationUiState())
    val uiState = state.asStateFlow()

    init { load() }

    fun load() {
        state.update { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                val insight = repository.participationInsight(actorId, taskId)
                state.update { it.copy(loading = false, insight = insight, error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.update { it.copy(loading = false, error = e.message ?: "加载失败") }
            }
        }
    }
}
