package edu.neu.aijiaoxue.ui.course

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.Session
import edu.neu.aijiaoxue.data.dao.TaskRow
import edu.neu.aijiaoxue.data.model.TaskStatus
import edu.neu.aijiaoxue.data.model.isTaskOverdue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** 列表中的一项。“已逾期”是计算结果，不入库（硬性规则 3）。 */
data class MyTaskItem(
    val row: TaskRow,
    val isOverdue: Boolean,
)

data class MyTasksUiState(
    val isLoading: Boolean = true,
    /** 待开始、进行中、待评价，按状态推进程度排序：先显示做到一半的。 */
    val unfinished: List<MyTaskItem> = emptyList(),
    val completed: List<MyTaskItem> = emptyList(),
)

/**
 * US07 我的督导任务。只查当前督导本人的任务（硬性规则 6：权限校验放在查询层）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MyTasksViewModel(application: Application) : AndroidViewModel(application) {
    private val taskDao = AppDatabase.get(application).taskDao()

    val uiState: StateFlow<MyTasksUiState> = Session.user.flatMapLatest { user ->
        if (user == null) {
            flowOf(MyTasksUiState(isLoading = false))
        } else {
            taskDao.observeRowsBySupervisor(user.id).map { rows ->
                val items = rows.map { MyTaskItem(it, isTaskOverdue(it.status, it.supervisionDate)) }
                val (completed, unfinished) = items.partition { it.row.status == TaskStatus.COMPLETED }
                MyTasksUiState(
                    isLoading = false,
                    // sortedBy 是稳定排序，同一状态内保持 DAO 的督导日期倒序
                    unfinished = unfinished.sortedBy { UNFINISHED_ORDER.indexOf(it.row.status) },
                    completed = completed,
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MyTasksUiState())

    private companion object {
        val UNFINISHED_ORDER = listOf(TaskStatus.IN_PROGRESS, TaskStatus.PENDING_EVALUATION, TaskStatus.NOT_STARTED)
    }
}
