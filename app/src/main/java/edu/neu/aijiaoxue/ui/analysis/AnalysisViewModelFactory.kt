package edu.neu.aijiaoxue.ui.analysis

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.Session
import edu.neu.aijiaoxue.data.ai.MockAIAnalyzer
import edu.neu.aijiaoxue.data.repository.AIAnalysisRepository
import edu.neu.aijiaoxue.data.repository.AiEvidenceRepository

class AnalysisViewModelFactory(private val application: Application, private val taskId: Long) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(AnalysisViewModel::class.java))
        val db = AppDatabase.get(application)
        @Suppress("UNCHECKED_CAST")
        return AnalysisViewModel(AIAnalysisRepository(db, MockAIAnalyzer()), AiEvidenceRepository(db),
            Session.requireUser().id, taskId) as T
    }
}
