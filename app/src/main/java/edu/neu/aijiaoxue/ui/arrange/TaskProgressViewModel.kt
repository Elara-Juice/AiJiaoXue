package edu.neu.aijiaoxue.ui.arrange

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.dao.CourseRow
import edu.neu.aijiaoxue.data.dao.StatusCount
import edu.neu.aijiaoxue.data.dao.TaskRow
import edu.neu.aijiaoxue.data.entity.User
import edu.neu.aijiaoxue.data.model.Role
import edu.neu.aijiaoxue.data.model.TaskStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

data class TaskProgressUiState(
    val rows: List<TaskRow> = emptyList(),
    val courses: List<CourseRow> = emptyList(),
    val supervisors: List<User> = emptyList(),
    val counts: List<StatusCount> = emptyList(),
    val courseId: Long? = null,
    val supervisorId: Long? = null,
    val status: TaskStatus? = null,
)

private data class TaskProgressFilter(val courseId: Long? = null, val supervisorId: Long? = null, val status: TaskStatus? = null)

@OptIn(ExperimentalCoroutinesApi::class)
class TaskProgressViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.get(application)
    private val filter = MutableStateFlow(TaskProgressFilter())
    private val rows = filter.flatMapLatest { db.taskDao().observeRowsFiltered(it.courseId, it.supervisorId, it.status) }
    private val courses = db.courseDao().observeRows("")
    private val supervisors = db.userDao().observeByRole(Role.SUPERVISOR)
    private val counts = db.taskDao().observeStatusCounts()

    val uiState: StateFlow<TaskProgressUiState> = combine(filter, rows, courses, supervisors, counts) { selected, tasks, courseRows, users, statusCounts ->
        TaskProgressUiState(tasks, courseRows, users, statusCounts, selected.courseId, selected.supervisorId, selected.status)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TaskProgressUiState())

    fun setCourse(id: Long?) = filter.update { it.copy(courseId = id) }
    fun setSupervisor(id: Long?) = filter.update { it.copy(supervisorId = id) }
    fun setStatus(status: TaskStatus?) = filter.update { it.copy(status = status) }
}