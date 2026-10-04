package edu.neu.aijiaoxue.ui.arrange

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.Session
import edu.neu.aijiaoxue.data.dao.CourseRow
import edu.neu.aijiaoxue.data.entity.Arrangement
import edu.neu.aijiaoxue.data.model.Semester
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ArrangementUiState(
    val rows: List<CourseRow> = emptyList(),
    val uncoveredOnly: Boolean = false,
    val selectedCourse: CourseRow? = null,
    val isKeyFocus: Boolean = false,
    val note: String = "",
    /** 王博田缺陷 D05：成功提示与失败提示分开存，界面按字段决定颜色，不再比较文案。 */
    val message: String? = null,
    val error: String? = null,
    val isSaving: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class ArrangementViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.get(application)
    private val uncoveredOnly = MutableStateFlow(false)
    private val rows = uncoveredOnly.flatMapLatest { onlyUncovered ->
        if (onlyUncovered) db.courseDao().observeUncoveredRows(Semester.START_DATE)
        else db.courseDao().observeRows("")
    }
    private val _uiState = MutableStateFlow(ArrangementUiState())
    val uiState: StateFlow<ArrangementUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(uncoveredOnly, rows) { only, courseRows -> only to courseRows }
                .collect { (only, courseRows) -> _uiState.update { it.copy(uncoveredOnly = only, rows = courseRows) } }
        }
    }

    fun setUncoveredOnly(value: Boolean) = uncoveredOnly.update { value }

    fun selectCourse(row: CourseRow) {
        _uiState.update { it.copy(selectedCourse = row, isKeyFocus = false, note = "", message = null, error = null) }
    }

    fun clearSelection() = _uiState.update { it.copy(selectedCourse = null, message = null, error = null) }
    fun setKeyFocus(value: Boolean) = _uiState.update { it.copy(isKeyFocus = value) }
    fun setNote(value: String) = _uiState.update { it.copy(note = value) }

    fun addArrangement() {
        val state = _uiState.value
        val course = state.selectedCourse ?: return
        if (state.isSaving) return
        _uiState.update { it.copy(isSaving = true, message = null, error = null) }
        viewModelScope.launch {
            val existing = db.arrangementDao().getOpenByCourse(course.id)
            if (existing != null) {
                _uiState.update { it.copy(isSaving = false, error = "该课程已在待督导安排中") }
                return@launch
            }
            runCatching {
                db.arrangementDao().insert(
                    Arrangement(
                        courseId = course.id,
                        isKeyFocus = state.isKeyFocus,
                        note = state.note.trim().ifBlank { null },
                        createdBy = Session.requireUser().id,
                    )
                )
            }.onSuccess {
                _uiState.update { it.copy(isSaving = false, selectedCourse = null, message = "已纳入待督导安排") }
            }.onFailure {
                _uiState.update { it.copy(isSaving = false, error = "安排未能保存，请重试") }
            }
        }
    }
}