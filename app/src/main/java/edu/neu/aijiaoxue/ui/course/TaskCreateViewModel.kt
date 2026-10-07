package edu.neu.aijiaoxue.ui.course

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.Session
import edu.neu.aijiaoxue.data.entity.SupervisionTask
import edu.neu.aijiaoxue.data.model.DateFormats
import edu.neu.aijiaoxue.data.model.SupervisionType
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TaskCreateUiState(
    val supervisionDate: String = DateFormats.today(),
    val period: String = "",
    val chapter: String = "",
    val type: SupervisionType = SupervisionType.REGULAR,
    val dateError: Boolean = false,
    val periodError: Boolean = false,
    val error: String? = null,
    val created: Boolean = false,
    val isSaving: Boolean = false,
)

class TaskCreateViewModel(application: Application, private val courseId: Long) : AndroidViewModel(application) {
    private val taskDao = AppDatabase.get(application).taskDao()
    private val _uiState = MutableStateFlow(TaskCreateUiState())
    val uiState: StateFlow<TaskCreateUiState> = _uiState.asStateFlow()

    fun onDateChange(value: String) = _uiState.update { it.copy(supervisionDate = value, dateError = false, error = null) }
    fun onPeriodChange(value: String) = _uiState.update { it.copy(period = value, periodError = false, error = null) }
    fun onChapterChange(value: String) = _uiState.update { it.copy(chapter = value) }
    fun onTypeChange(value: SupervisionType) = _uiState.update { it.copy(type = value) }

    fun create() {
        val state = _uiState.value
        if (state.isSaving || state.created) return
        val date = state.supervisionDate.trim()
        val validDate = runCatching { LocalDate.parse(date).toString() == date }.getOrDefault(false)
        val validPeriod = state.period.isNotBlank()
        if (!validDate || !validPeriod) {
            _uiState.update { it.copy(dateError = !validDate, periodError = !validPeriod, error = "请填写有效的督导日期和节次") }
            return
        }
        _uiState.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            val task = SupervisionTask(
                code = "",
                courseId = courseId,
                supervisorId = Session.requireUser().id,
                supervisionDate = date,
                period = state.period.trim(),
                chapter = state.chapter.trim().ifBlank { null },
                type = state.type,
            )
            runCatching { taskDao.create(task) }
                .onSuccess { taskId ->
                    if (taskId == null) {
                        _uiState.update { it.copy(isSaving = false, error = "任务已存在") }
                    } else {
                        _uiState.update { it.copy(isSaving = false, created = true) }
                    }
                }
                .onFailure {
                    _uiState.update { it.copy(isSaving = false, error = "任务创建失败，请重试") }
                }
        }
    }
}