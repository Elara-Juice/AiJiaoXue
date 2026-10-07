package edu.neu.aijiaoxue.ui.improve

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import edu.neu.aijiaoxue.data.entity.ImprovementMeasure
import edu.neu.aijiaoxue.data.model.MeasureSource
import edu.neu.aijiaoxue.data.model.PlanStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ImprovePlanUiState(val loading: Boolean = true, val record: ImprovementRecord? = null,
    val measures: List<ImprovementMeasure> = emptyList(), val busy: Boolean = false, val saved: Boolean = true, val error: String? = null) {
    val readOnly get() = record?.plan?.status == PlanStatus.CONFIRMED
}

class ImprovePlanViewModel(private val repository: ImprovementRepository, private val actorId: Long, private val taskId: Long) : ViewModel() {
    private val state = MutableStateFlow(ImprovePlanUiState())
    val uiState = state.asStateFlow()
    private var nextId = -1L
    private var revision = 0
    init { reload() }
    fun reload() { viewModelScope.launch {
        state.value = ImprovePlanUiState()
        try {
            val record = repository.load(actorId, taskId)
            requireNotNull(record.plan) { "请先确定并保存改进重点" }
            state.value = ImprovePlanUiState(loading = false, record = record, measures = record.measures)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { state.value = ImprovePlanUiState(loading = false, error = e.message ?: "加载失败") }
    } }
    private fun edit(transform: (List<ImprovementMeasure>) -> List<ImprovementMeasure>) {
        if (state.value.busy || state.value.readOnly || state.value.record == null) return
        state.update { it.copy(measures = transform(it.measures), error = null, saved = false) }; persist()
    }
    fun change(id: Long, transform: (ImprovementMeasure) -> ImprovementMeasure) = edit { rows ->
        rows.map { if (it.id == id) transform(it) else it }
    }
    fun content(id: Long, text: String) = change(id) {
        it.copy(content = text, source = if (it.source == MeasureSource.ADDED) MeasureSource.ADDED else MeasureSource.MODIFIED)
    }
    fun adopt(id: Long) = change(id) { it.copy(content = state.value.record?.evaluation?.suggestions.orEmpty(), source = MeasureSource.ADOPTED) }
    fun remove(id: Long) = edit { rows -> rows.filterNot { it.id == id } }
    fun add(itemId: Long) = edit { rows -> rows + ImprovementMeasure(
        id = nextId--, planId = state.value.record?.plan?.id ?: 0, itemId = itemId,
        content = "", source = MeasureSource.ADDED, dueDate = "",
    ) }
    fun validate(): Boolean {
        val error = planError(state.value.record?.items.orEmpty(), state.value.measures)
        state.update { it.copy(error = error) }; return error == null
    }
    fun persist(confirm: Boolean = false) {
        if (state.value.busy || state.value.readOnly || state.value.record == null) return
        if (confirm && !validate()) return
        val snapshot = state.value.measures
        val ticket = ++revision
        state.update { it.copy(busy = confirm, saved = false) }
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) { withContext(NonCancellable) {
            try {
                repository.saveMeasures(actorId, taskId, snapshot, confirm)
                if (confirm) {
                    val record = repository.load(actorId, taskId)
                    state.value = ImprovePlanUiState(loading = false, record = record, measures = record.measures)
                } else if (ticket == revision) state.update { it.copy(saved = true) }
            } catch (e: Exception) {
                if (ticket == revision) state.update { it.copy(busy = false, saved = false, error = e.message ?: "保存失败") }
            }
        } }
    }
}
