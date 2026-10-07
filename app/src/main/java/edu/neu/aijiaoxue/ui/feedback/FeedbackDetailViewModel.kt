package edu.neu.aijiaoxue.ui.feedback

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.Session
import edu.neu.aijiaoxue.data.entity.Evaluation
import edu.neu.aijiaoxue.data.entity.EvaluationIssue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FeedbackDetailUiState(
    val evaluation: Evaluation? = null,
    val issues: List<EvaluationIssue> = emptyList(),
    val courseName: String = "",
    val supervisorName: String = "",
    val supervisionDate: String = "",
    val error: String? = null,
    val isLoading: Boolean = true,
)

class FeedbackDetailViewModel(application: Application, private val taskId: Long) : AndroidViewModel(application) {
    private val db = AppDatabase.get(application)
    private val teacherId = Session.requireUser().id
    private val _uiState = MutableStateFlow(FeedbackDetailUiState())
    val uiState: StateFlow<FeedbackDetailUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val evaluation = db.evaluationDao().getSubmittedForTeacher(taskId, teacherId)
            if (evaluation == null) {
                _uiState.update { it.copy(error = "无权查看此反馈或评价尚未提交", isLoading = false) }
                return@launch
            }
            val task = db.taskDao().getById(taskId)
            val course = task?.let { db.courseDao().getById(it.courseId) }
            val supervisor = task?.let { db.userDao().getById(it.supervisorId) }
            val issues = db.evaluationDao().getIssues(evaluation.id)
            db.evaluationDao().markRead(evaluation.id, System.currentTimeMillis())
            _uiState.value = FeedbackDetailUiState(
                evaluation = evaluation,
                issues = issues,
                courseName = course?.name.orEmpty(),
                supervisorName = supervisor?.name.orEmpty(),
                supervisionDate = task?.supervisionDate.orEmpty(),
                isLoading = false,
            )
        }
    }
}