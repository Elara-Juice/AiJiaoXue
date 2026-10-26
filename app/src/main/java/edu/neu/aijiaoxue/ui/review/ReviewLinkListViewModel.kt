package edu.neu.aijiaoxue.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import edu.neu.aijiaoxue.data.dao.ReviewLinkRow
import edu.neu.aijiaoxue.data.entity.EvaluationIssue
import edu.neu.aijiaoxue.data.dao.ReviewTaskCandidateRow
import edu.neu.aijiaoxue.data.repository.ReviewRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Sprint 3（李奕萱）复评关联列表页状态。
 * 新建关联分三步：选复评课堂 → 选原课堂 → 选原问题条目（可整堂复评）。
 */
data class ReviewListUiState(
    val loading: Boolean = true,
    val links: List<ReviewLinkRow> = emptyList(),
    val followUpTasks: List<ReviewTaskCandidateRow> = emptyList(),
    val creating: Boolean = false,
    val selectedFollowUp: ReviewTaskCandidateRow? = null,
    val originCandidates: List<ReviewTaskCandidateRow> = emptyList(),
    val selectedOrigin: ReviewTaskCandidateRow? = null,
    val originIssues: List<EvaluationIssue> = emptyList(),
    val selectedIssueId: Long? = null,
    val submitting: Boolean = false,
    val error: String? = null,
    val message: String? = null,
)

class ReviewLinkListViewModel(private val repository: ReviewRepository, private val actorId: Long) : ViewModel() {
    private val state = MutableStateFlow(ReviewListUiState())
    val uiState = state.asStateFlow()
    private var linkJob: Job? = null
    private var taskJob: Job? = null

    init { refresh() }

    fun refresh() {
        linkJob?.cancel()
        linkJob = viewModelScope.launch {
            repository.observeLinks(actorId).collect { rows ->
                state.update { it.copy(loading = false, links = rows) }
            }
        }
        taskJob?.cancel()
        taskJob = viewModelScope.launch {
            repository.observeFollowUpTasks(actorId).collect { tasks ->
                state.update { it.copy(followUpTasks = tasks) }
            }
        }
    }

    /** 打开新建关联面板。 */
    fun startCreate() {
        state.update { it.copy(creating = true, selectedFollowUp = null, originCandidates = emptyList(),
            selectedOrigin = null, originIssues = emptyList(), selectedIssueId = null, error = null, message = null) }
    }

    fun cancelCreate() {
        state.update { it.copy(creating = false, selectedFollowUp = null, originCandidates = emptyList(),
            selectedOrigin = null, originIssues = emptyList(), selectedIssueId = null) }
    }

    fun selectFollowUp(task: ReviewTaskCandidateRow) {
        state.update { it.copy(selectedFollowUp = task, selectedOrigin = null, originIssues = emptyList(),
            selectedIssueId = null, error = null) }
        viewModelScope.launch {
            try {
                val candidates = repository.originCandidates(actorId, task.id)
                state.update { it.copy(originCandidates = candidates) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.update { it.copy(error = e.message ?: "读取原课堂候选失败") }
            }
        }
    }

    fun selectOrigin(task: ReviewTaskCandidateRow) {
        state.update { it.copy(selectedOrigin = task, selectedIssueId = null, error = null) }
        viewModelScope.launch {
            try {
                val issues = repository.originIssues(actorId, task.id)
                state.update { it.copy(originIssues = issues) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.update { it.copy(error = e.message ?: "读取原问题失败") }
            }
        }
    }

    fun selectIssue(issueId: Long?) {
        state.update { it.copy(selectedIssueId = issueId) }
    }

    /** 保存复评关联。 */
    fun submit() {
        val current = state.value
        val followUp = current.selectedFollowUp ?: return
        val origin = current.selectedOrigin ?: return
        if (current.submitting) return
        state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            try {
                repository.createLink(actorId, followUp.id, origin.id, current.selectedIssueId)
                state.update { it.copy(submitting = false, creating = false, selectedFollowUp = null,
                    selectedOrigin = null, originIssues = emptyList(), selectedIssueId = null,
                    message = "已建立复评关联") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.update { it.copy(submitting = false, error = e.message ?: "建立复评关联失败") }
            }
        }
    }

    fun clearMessage() {
        state.update { it.copy(message = null, error = null) }
    }
}
