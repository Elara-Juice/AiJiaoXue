package edu.neu.aijiaoxue.ui.evidence

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.Session
import edu.neu.aijiaoxue.data.repository.AiEvidenceRepository

class EvidenceViewModelFactory(private val database: AppDatabase) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(EvidenceViewModel::class.java))
        @Suppress("UNCHECKED_CAST")
        return EvidenceViewModel(AiEvidenceRepository(database), Session.requireUser().id) as T
    }
}
