package edu.neu.aijiaoxue.ui.course

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.dao.CourseRow
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CourseSearchUiState(
    val keyword: String = "",
    val rows: List<CourseRow> = emptyList(),
)

class CourseSearchViewModel(application: Application) : AndroidViewModel(application) {
    private val courseDao = AppDatabase.get(application).courseDao()
    private val _uiState = MutableStateFlow(CourseSearchUiState())
    val uiState: StateFlow<CourseSearchUiState> = _uiState.asStateFlow()
    private var searchJob: Job? = null

    init {
        search("")
    }

    fun onKeywordChange(value: String) {
        _uiState.update { it.copy(keyword = value) }
        search(value)
    }

    private fun search(value: String) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            courseDao.observeRows(value.trim()).collect { rows ->
                _uiState.update { it.copy(rows = rows) }
            }
        }
    }
}