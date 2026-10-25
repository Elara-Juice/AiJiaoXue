package edu.neu.aijiaoxue.ui.improve

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import edu.neu.aijiaoxue.data.entity.ImprovementMeasure
import edu.neu.aijiaoxue.data.model.MeasureSource
import edu.neu.aijiaoxue.data.model.PlanStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class ImprovePlanUiState(val loading: Boolean = true, val record: ImprovementRecord? = null,
    val measures: List<ImprovementMeasure> = emptyList(), val busy: Boolean = false, val saved: Boolean = true,
    val error: String? = null, val leaving: Boolean = false) {
    val readOnly get() = record?.plan?.status == PlanStatus.CONFIRMED
}

class ImprovePlanViewModel(private val repository: ImprovementRepository, private val actorId: Long, private val taskId: Long) : ViewModel() {
    private val state = MutableStateFlow(ImprovePlanUiState())
    val uiState = state.asStateFlow()
    private var nextId = -1L
    private var revision = 0
    // Keep draft writes and suggestion decisions in the same order as the teacher's actions.
    // A decision must never be followed by an older autosave that deletes its new measure.
    private var pendingWrite: Job? = null
    private var navigationPending = false
    private var navigationCompleted = false
    private fun loaded(record: ImprovementRecord, busy: Boolean = false) = ImprovePlanUiState(
        loading = false, record = record, measures = record.measures,
        busy = busy || navigationPending, leaving = navigationPending,
    )
    init { reload() }
    /** A confirmed plan remains on the navigation stack while its todo page is open. */
    fun onResume() {
        if (!navigationPending || !navigationCompleted) return
        navigationPending = false
        navigationCompleted = false
        state.update { it.copy(busy = false, leaving = false) }
    }
    fun reload() {
        if (state.value.busy || navigationPending) return
        val before = state.value
        val previous = pendingWrite
        // Lock before waiting so a new edit cannot race the reload and be overwritten.
        state.update { it.copy(busy = true, error = null) }
        pendingWrite = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            previous?.join()
            try {
                if (before.record != null && !before.readOnly && !before.saved) {
                    repository.saveMeasures(actorId, taskId, before.measures)
                }
                val record = repository.load(actorId, taskId)
                requireNotNull(record.plan) { "请先确定并保存改进重点" }
                val needsSuggestions = record.plan.status != PlanStatus.CONFIRMED && record.reviewedAnalyses.isNotEmpty()
                state.value = loaded(record, busy = needsSuggestions)
                if (!needsSuggestions) return@launch
                try {
                    val refreshed = withTimeout(10_000) { repository.refreshSuggestions(actorId, taskId) }
                    state.value = loaded(refreshed)
                } catch (e: TimeoutCancellationException) {
                    state.update { it.copy(busy = false, error = "建议生成超时，已保留计划，可继续人工新增或编辑措施。") }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    state.update { it.copy(busy = false, error = "建议暂不可用，已保留计划，可继续人工新增或编辑措施。${e.message.orEmpty()}") }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                state.update { it.copy(loading = false, busy = false, error = e.message ?: "加载失败") }
            }
        }
    }
    private fun edit(transform: (List<ImprovementMeasure>) -> List<ImprovementMeasure>) {
        if (navigationPending || state.value.busy || state.value.readOnly || state.value.record == null) return
        state.update { it.copy(measures = transform(it.measures), error = null, saved = false) }; persist()
    }
    fun change(id: Long, transform: (ImprovementMeasure) -> ImprovementMeasure) = edit { rows ->
        rows.map { if (it.id == id) transform(it) else it }
    }
    fun content(id: Long, text: String) = change(id) { measure ->
        val original = measure.suggestionId?.let { suggestionId ->
            state.value.record?.suggestions?.firstOrNull { it.id == suggestionId }?.content
        } ?: state.value.record?.evaluation?.suggestions
        measure.copy(content = text, source = when {
            measure.source == MeasureSource.ADDED -> MeasureSource.ADDED
            original != null && text.trim() == original.trim() -> MeasureSource.ADOPTED
            else -> MeasureSource.MODIFIED
        })
    }
    fun adopt(id: Long) = change(id) { measure ->
        val original = measure.suggestionId?.let { suggestionId ->
            state.value.record?.suggestions?.firstOrNull { it.id == suggestionId }?.content
        } ?: state.value.record?.evaluation?.suggestions.orEmpty()
        measure.copy(content = original, source = MeasureSource.ADOPTED)
    }
    fun remove(id: Long) = edit { rows -> rows.filterNot { it.id == id } }
    fun add(itemId: Long) = edit { rows -> rows + ImprovementMeasure(
        id = nextId--, planId = state.value.record?.plan?.id ?: 0, itemId = itemId,
        content = "", source = MeasureSource.ADDED, dueDate = "",
    ) }
    fun validate(): Boolean {
        if (navigationPending) return false
        val error = planError(state.value.record?.items.orEmpty(), state.value.measures)
        state.update { it.copy(error = error) }; return error == null
    }
    fun refreshSuggestions() = suggestionAction {
        withTimeout(10_000) { repository.refreshSuggestions(actorId, taskId) }
    }
    fun adoptSuggestion(id: Long, content: String? = null) {
        if (navigationPending) return
        if (content != null && content.isBlank()) {
            state.update { it.copy(error = "修改后的建议不能为空") }
            return
        }
        suggestionAction { repository.adoptSuggestion(actorId, taskId, id, content) }
    }
    fun dismissSuggestion(id: Long, dismissed: Boolean = true) = suggestionAction {
        repository.dismissSuggestion(actorId, taskId, id, dismissed)
    }

    private fun suggestionAction(action: suspend () -> ImprovementRecord) {
        if (navigationPending || state.value.busy || state.value.readOnly || state.value.record == null) return
        val snapshot = state.value.measures
        val previous = pendingWrite
        ++revision
        state.update { it.copy(busy = true, error = null) }
        pendingWrite = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) { withContext(NonCancellable) {
            try {
                previous?.join()
                // Flush even when the latest autosave failed; only then apply the decision.
                repository.saveMeasures(actorId, taskId, snapshot)
                val saved = repository.load(actorId, taskId)
                state.update { it.copy(record = saved, measures = saved.measures, saved = true) }
                val record = action()
                state.value = loaded(record)
            } catch (e: TimeoutCancellationException) {
                state.update { it.copy(busy = false, error = "建议生成超时，已保留计划，可继续人工新增或编辑措施。") }
            } catch (e: Exception) {
                state.update { it.copy(busy = false, error = "建议处理未完成，已保留现有计划，可继续人工编辑。${e.message.orEmpty()}") }
            }
        } }
    }

    /** Drain every queued write before another page/ViewModel can read or mutate this plan. */
    fun flushAndNavigate(onReady: () -> Unit) {
        if (navigationPending) return
        navigationPending = true
        navigationCompleted = false
        state.update { it.copy(busy = true, leaving = true) }
        if (state.value.readOnly) {
            navigationCompleted = true
            onReady()
            return
        }
        val previous = pendingWrite
        pendingWrite = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) { withContext(NonCancellable) {
            try {
                previous?.join()
                // A pending suggestion decision may have added measures, so capture AFTER it.
                val latest = state.value
                if (latest.record != null && !latest.readOnly) {
                    repository.saveMeasures(actorId, taskId, latest.measures)
                }
                state.update { it.copy(saved = true, error = null) }
                navigationCompleted = true
                onReady()
            } catch (e: Exception) {
                navigationPending = false
                navigationCompleted = false
                state.update { it.copy(busy = false, leaving = false, saved = false,
                    error = "离开前保存失败，请重试。${e.message.orEmpty()}") }
            }
        } }
    }

    fun persist(confirm: Boolean = false) {
        if (navigationPending || state.value.busy || state.value.readOnly || state.value.record == null) return
        if (confirm && !validate()) return
        val snapshot = state.value.measures
        val ticket = ++revision
        val previous = pendingWrite
        state.update { it.copy(busy = confirm, saved = false) }
        pendingWrite = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) { withContext(NonCancellable) {
            try {
                previous?.join()
                repository.saveMeasures(actorId, taskId, snapshot, confirm)
                if (confirm) {
                    val record = repository.load(actorId, taskId)
                    state.value = loaded(record)
                } else if (ticket == revision) state.update { it.copy(saved = true) }
            } catch (e: Exception) {
                if (ticket == revision) state.update { it.copy(busy = false, saved = false, error = e.message ?: "保存失败") }
            }
        } }
    }
}
