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
    /** 王博田缺陷 D03：节次改为选择开始、结束节，不再手输“3-4”。 */
    val periodStart: Int? = null,
    val periodEnd: Int? = null,
    val chapter: String = "",
    val type: SupervisionType = SupervisionType.REGULAR,
    /** 字段下方的具体提示；null 表示该项没有问题。 */
    val dateError: String? = null,
    val periodError: String? = null,
    val error: String? = null,
    val isSaving: Boolean = false,
    /** 创建成功后的任务，用于成功页直接进入采集（王博田缺陷 D02）。 */
    val createdTaskId: Long? = null,
    val createdCode: String? = null,
) {
    val created: Boolean get() = createdTaskId != null
}

class TaskCreateViewModel(application: Application, private val courseId: Long) : AndroidViewModel(application) {
    private val taskDao = AppDatabase.get(application).taskDao()
    private val _uiState = MutableStateFlow(TaskCreateUiState())
    val uiState: StateFlow<TaskCreateUiState> = _uiState.asStateFlow()

    fun onDateChange(value: String) = _uiState.update { it.copy(supervisionDate = value, dateError = null, error = null) }

    /** 选了开始节而结束节还没选或早于开始节时，结束节默认取下一节（常见的两节连上）。 */
    fun onPeriodStartChange(value: Int) = _uiState.update {
        val end = it.periodEnd?.takeIf { e -> e >= value } ?: minOf(value + 1, MAX_PERIOD)
        it.copy(periodStart = value, periodEnd = end, periodError = null, error = null)
    }

    fun onPeriodEndChange(value: Int) = _uiState.update { it.copy(periodEnd = value, periodError = null, error = null) }
    fun onChapterChange(value: String) = _uiState.update { it.copy(chapter = value) }
    fun onTypeChange(value: SupervisionType) = _uiState.update { it.copy(type = value) }

    fun create() {
        val state = _uiState.value
        if (state.isSaving || state.created) return
        val date = state.supervisionDate.trim()
        val dateError = when {
            date.isEmpty() -> "请选择督导日期"
            runCatching { LocalDate.parse(date).toString() == date }.getOrDefault(false) -> null
            else -> "督导日期无效，请重新选择"
        }
        val start = state.periodStart
        val end = state.periodEnd
        val periodError = when {
            start == null -> "请选择开始节次"
            end == null -> "请选择结束节次"
            start !in 1..MAX_PERIOD || end !in 1..MAX_PERIOD -> "节次应在第 1–$MAX_PERIOD 节之间"
            end < start -> "结束节次不能早于开始节次"
            else -> null
        }
        if (dateError != null || periodError != null) {
            _uiState.update { it.copy(dateError = dateError, periodError = periodError) }
            return
        }
        _uiState.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            val task = SupervisionTask(
                code = "",
                courseId = courseId,
                supervisorId = Session.requireUser().id,
                supervisionDate = date,
                // 与演示数据一致存“起-止”，如 3-4；只上一节时存 5
                period = if (start == end) "$start" else "$start-$end",
                chapter = state.chapter.trim().ifBlank { null },
                type = state.type,
            )
            runCatching { taskDao.create(task) }
                .onSuccess { taskId ->
                    if (taskId == null) {
                        _uiState.update { it.copy(isSaving = false, error = "任务已存在") }
                    } else {
                        val code = taskDao.getById(taskId)?.code
                        _uiState.update { it.copy(isSaving = false, createdTaskId = taskId, createdCode = code) }
                    }
                }
                .onFailure {
                    _uiState.update { it.copy(isSaving = false, error = "任务创建失败，请重试") }
                }
        }
    }

    companion object {
        /** 一天最多 12 节课。 */
        const val MAX_PERIOD = 12
    }
}
