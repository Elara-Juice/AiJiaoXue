package edu.neu.aijiaoxue.ui.supervisor

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.repository.*
import edu.neu.aijiaoxue.ui.improve.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class SupervisorIssueState(val detail: SupervisorIssueDetail? = null, val loading: Boolean = true, val busy: Boolean = false,
    val error: String? = null, val createdTaskId: Long? = null)
class SupervisorIssueViewModel(private val db: AppDatabase, private val actorId: Long, private val issueId: Long) : ViewModel() {
    private val repo = SupervisorPortalRepository(db)
    private val state = MutableStateFlow(SupervisorIssueState())
    val uiState = state.asStateFlow()
    private var job: Job? = null
    init { reload() }
    fun reload() {
        job?.cancel()
        job = viewModelScope.launch { safely {
            repo.issues(actorId).collect { val detail = repo.issue(actorId, issueId); state.update { current -> current.copy(detail = detail, loading = false) } }
        } }
    }
    fun create(date: String, start: Int, end: Int, chapter: String) {
        if (state.value.busy || state.value.createdTaskId != null) return
        state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch { try { safely {
            val id = repo.createFollowUp(actorId, issueId, date, start, end, chapter)
            state.update { it.copy(createdTaskId = id) }
        } } finally { state.update { it.copy(busy = false) } } }
    }
    fun openEvidence(context: Context, id: Long, opened: (OpenedEvidence) -> Unit) { viewModelScope.launch { safely {
        val evidence = ImprovementEvidenceStore(context, ImprovementExecutionRepository(db)).openEvidence(actorId, id)
        opened(evidence)
    } } }
    fun reportError(message: String) { state.update { it.copy(error = message) } }
    private suspend fun safely(block: suspend () -> Unit) {
        try { block() } catch (e: CancellationException) { throw e }
        catch (e: Exception) { state.update { it.copy(loading = false, error = e.message ?: "操作失败，请重试") } }
    }
}
