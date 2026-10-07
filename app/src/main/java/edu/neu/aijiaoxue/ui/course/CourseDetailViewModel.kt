package edu.neu.aijiaoxue.ui.course

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.dao.CourseRow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class CourseDetailViewModel(application: Application, courseId: Long) : AndroidViewModel(application) {
    private val courseDao = AppDatabase.get(application).courseDao()
    val course: StateFlow<CourseRow?> = courseDao.observeRow(courseId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}