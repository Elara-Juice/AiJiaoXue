package edu.neu.aijiaoxue.ui.analysis

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.Session
import edu.neu.aijiaoxue.data.repository.AiCorrectionRepository

class AIReviewViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(AIReviewViewModel::class.java))
        @Suppress("UNCHECKED_CAST")
        return AIReviewViewModel(AiCorrectionRepository(AppDatabase.get(application)), Session.requireUser().id) as T
    }
}
