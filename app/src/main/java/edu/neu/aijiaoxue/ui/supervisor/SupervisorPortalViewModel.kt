package edu.neu.aijiaoxue.ui.supervisor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import edu.neu.aijiaoxue.data.dao.SupervisorTaskRow
import edu.neu.aijiaoxue.data.repository.*
import edu.neu.aijiaoxue.data.model.*
import edu.neu.aijiaoxue.navigation.Routes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class SupervisorPortalState(val loading: Boolean = true, val tasks: List<SupervisorTaskRow> = emptyList(),
    val issues: List<TrackedIssue> = emptyList(), val error: String? = null, val relatedIssueIds: Set<Long> = emptySet())

class SupervisorPortalViewModel(private val repository: SupervisorPortalRepository, private val actorId: Long, private val taskId: Long? = null) : ViewModel() {
    private val state = MutableStateFlow(SupervisorPortalState())
    val uiState = state.asStateFlow()
    private var observation: Job? = null
    init { reload() }
    fun reload() {
        observation?.cancel()
        observation = viewModelScope.launch {
            try { combine(repository.tasks(actorId), repository.issues(actorId)) { tasks, issues ->
                val related = taskId?.let { repository.task(actorId, it).origins.mapNotNull { origin -> origin.originIssueId }.toSet() }.orEmpty()
                SupervisorPortalState(false, tasks, issues, relatedIssueIds = related)
            }.collect { state.value = it } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { state.update { it.copy(loading = false, error = e.message ?: "读取失败，请重试") } }
        }
    }
}

fun SupervisorTaskRow.nextLabel(): String = when (status) {
    TaskStatus.NOT_STARTED -> "查看任务"
    TaskStatus.IN_PROGRESS -> "继续采集"
    TaskStatus.PENDING_EVALUATION -> when { activeJobCount > 0 -> "查看分析进度"; pendingReviewCount > 0 -> "核验分析"; hasDraft -> "继续评价"; resultCount > 0 -> "完成评价"; else -> "检查材料" }
    TaskStatus.COMPLETED -> "查看报告"
}
fun SupervisorTaskRow.nextRoute(): String = when (status) {
    TaskStatus.NOT_STARTED -> Routes.taskDetail(id)
    TaskStatus.IN_PROGRESS -> Routes.capture(id)
    TaskStatus.PENDING_EVALUATION -> if (activeJobCount > 0 || pendingReviewCount > 0 || (!hasDraft && resultCount == 0)) Routes.analysis(id) else Routes.evaluation(id)
    TaskStatus.COMPLETED -> Routes.historyDetail(id)
}
fun SupervisorTaskRow.progressText(): String = when {
    status == TaskStatus.COMPLETED -> "报告已发布"
    activeJobCount > 0 -> "$activeJobCount 项分析处理中"
    pendingReviewCount > 0 -> "$pendingReviewCount 项分析待核验"
    hasDraft -> "评价草稿已保存"
    else -> "已保存 $materialCount 份课堂材料"
}
