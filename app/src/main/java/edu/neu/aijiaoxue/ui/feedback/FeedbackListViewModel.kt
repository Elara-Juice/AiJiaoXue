package edu.neu.aijiaoxue.ui.feedback

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.Session
import edu.neu.aijiaoxue.data.dao.FeedbackRow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class FeedbackListViewModel(application: Application) : AndroidViewModel(application) {
    private val teacherId = Session.requireUser().id
    private val feedbackDao = AppDatabase.get(application).evaluationDao()

    val rows: StateFlow<List<FeedbackRow>> = feedbackDao.observeFeedbackRows(teacherId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}