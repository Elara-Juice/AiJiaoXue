package edu.neu.aijiaoxue.ui.evaluation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import edu.neu.aijiaoxue.data.entity.Evaluation
import edu.neu.aijiaoxue.data.entity.EvaluationIssue
import edu.neu.aijiaoxue.data.model.EvalDimension
import edu.neu.aijiaoxue.data.model.EvalStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class EvaluationUiState(
    val loading: Boolean = true,
    val record: ClassroomRecord? = null,
    val draft: EvaluationDraft = EvaluationDraft(Evaluation(taskId = 0)),
    val busy: Boolean = false,
    val saved: Boolean = true,
    val error: String? = null,
    val invalidIndex: Int? = null,
    val validationAttempt: Int = 0,
) {
    val readOnly get() = draft.evaluation.status == EvalStatus.SUBMITTED
}

class EvaluationViewModel(private val repository: EvaluationRepository, private val actorId: Long, private val taskId: Long) : ViewModel() {
    private val state = MutableStateFlow(EvaluationUiState())
    val uiState = state.asStateFlow()
    private var revision = 0
    private var nextIssueId = -1L

    init { reload() }

    fun reload() { viewModelScope.launch {
        state.update { it.copy(loading = true, error = null) }
        try {
            val record = repository.load(actorId, taskId)
            state.value = EvaluationUiState(loading = false, record = record, draft = record.draft)
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { state.update { it.copy(loading = false, error = e.message ?: "加载失败") } }
    } }

    private fun edit(transform: (EvaluationDraft) -> EvaluationDraft) {
        if (state.value.loading || state.value.busy || state.value.readOnly || state.value.record == null) return
        state.update { it.copy(draft = transform(it.draft), saved = false, error = null, invalidIndex = null) }
        persist()
    }

    fun score(dimension: EvalDimension, score: Int) = edit { draft ->
        draft.copy(evaluation = when (dimension) {
            EvalDimension.CONTENT -> draft.evaluation.copy(contentScore = score)
            EvalDimension.DESIGN -> draft.evaluation.copy(designScore = score)
            EvalDimension.INTERACTION -> draft.evaluation.copy(interactionScore = score)
            EvalDimension.ENGAGEMENT -> draft.evaluation.copy(engagementScore = score)
        })
    }
    fun comment(dimension: EvalDimension, text: String) = edit { draft ->
        draft.copy(evaluation = when (dimension) {
            EvalDimension.CONTENT -> draft.evaluation.copy(contentComment = text)
            EvalDimension.DESIGN -> draft.evaluation.copy(designComment = text)
            EvalDimension.INTERACTION -> draft.evaluation.copy(interactionComment = text)
            EvalDimension.ENGAGEMENT -> draft.evaluation.copy(engagementComment = text)
        })
    }
    fun opinion(transform: (Evaluation) -> Evaluation) = edit { it.copy(evaluation = transform(it.evaluation)) }
    fun addIssue() = edit { it.copy(issues = it.issues + EvaluationIssue(id = nextIssueId--, evaluationId = 0, description = "")) }
    fun changeIssue(id: Long, transform: (EvaluationIssue) -> EvaluationIssue) = edit {
        it.copy(issues = it.issues.map { issue -> if (issue.id == id) transform(issue) else issue })
    }
    fun removeIssue(id: Long) = edit { it.copy(issues = it.issues.filterNot { issue -> issue.id == id }) }

    fun validate(): Boolean {
        val draft = state.value.draft
        val error = evaluationError(draft)
        val index = when {
            EvalDimension.entries.any { draft.evaluation.scoreOf(it) == null } ->
                3 + EvalDimension.entries.indexOfFirst { draft.evaluation.scoreOf(it) == null }
            draft.issues.isEmpty() || draft.issues.any { it.description.isBlank() } -> 8
            else -> 10
        }
        state.update { it.copy(error = error, invalidIndex = if (error == null) null else index, validationAttempt = it.validationAttempt + 1) }
        return error == null
    }

    fun persist(submit: Boolean = false) {
        if (state.value.readOnly || state.value.busy || state.value.record == null) return
        if (submit && !validate()) return
        val snapshot = state.value.draft
        val currentRevision = ++revision
        state.update { it.copy(busy = submit, saved = false) }
        // US07/29：不做防抖丢弃；已产生的编辑在导航销毁ViewModel后仍完成落库。
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                try {
                    repository.save(actorId, taskId, snapshot, submit)
                    if (submit) {
                        val record = repository.load(actorId, taskId)
                        state.value = EvaluationUiState(loading = false, record = record, draft = record.draft)
                    } else if (revision == currentRevision) state.update { it.copy(saved = true) }
                } catch (e: Exception) {
                    if (revision == currentRevision) state.update { it.copy(busy = false, saved = false, error = e.message ?: "保存失败，请重试") }
                }
            }
        }
    }
}

fun Evaluation.commentOf(dimension: EvalDimension): String = when (dimension) {
    EvalDimension.CONTENT -> contentComment
    EvalDimension.DESIGN -> designComment
    EvalDimension.INTERACTION -> interactionComment
    EvalDimension.ENGAGEMENT -> engagementComment
}.orEmpty()
