package edu.neu.aijiaoxue.ui.improve

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import edu.neu.aijiaoxue.data.entity.ImprovementItem
import edu.neu.aijiaoxue.data.model.ItemSource
import edu.neu.aijiaoxue.data.model.PlanStatus
import edu.neu.aijiaoxue.data.model.Priority
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class FocusChoice(val item: ImprovementItem, val selected: Boolean)
data class ImproveFocusUiState(val loading: Boolean = true, val busy: Boolean = false,
    val record: ImprovementRecord? = null, val choices: List<FocusChoice> = emptyList(),
    val error: String? = null, val openPlan: Boolean = false) {
    val readOnly get() = record?.plan?.status == PlanStatus.CONFIRMED
}

class ImproveFocusViewModel(private val repository: ImprovementRepository, private val actorId: Long, private val taskId: Long) : ViewModel() {
    private val state = MutableStateFlow(ImproveFocusUiState())
    val uiState = state.asStateFlow()
    private var nextId = -1L
    init { reload() }
    fun reload() { viewModelScope.launch {
        state.value = ImproveFocusUiState()
        try {
            val record = repository.load(actorId, taskId)
            val choices = record.issues.map { issue ->
                val existing = record.items.find { it.issueId == issue.id }
                FocusChoice(existing ?: ImprovementItem(id = nextId--, planId = record.plan?.id ?: 0,
                    issueId = issue.id, source = ItemSource.SUPERVISOR, description = issue.description, priority = Priority.MEDIUM),
                    selected = existing != null)
            } + record.items.filter { it.issueId == null }.map { FocusChoice(it, true) }
            state.value = ImproveFocusUiState(loading = false, record = record, choices = choices)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { state.value = ImproveFocusUiState(loading = false, error = e.message ?: "加载失败") }
    } }
    fun change(id: Long, transform: (FocusChoice) -> FocusChoice) {
        if (state.value.readOnly || state.value.busy) return
        state.update { it.copy(choices = it.choices.map { choice -> if (choice.item.id == id) transform(choice) else choice }, error = null) }
    }
    fun addCustom() {
        if (state.value.readOnly || state.value.busy) return
        val item = ImprovementItem(id = nextId--, planId = state.value.record?.plan?.id ?: 0, source = ItemSource.TEACHER,
            description = "", priority = Priority.MEDIUM)
        state.update { it.copy(choices = it.choices + FocusChoice(item, true), error = null) }
    }
    fun removalNeedsConfirmation(): Boolean {
        val retained = state.value.choices.filter { it.selected }.map { it.item.id }.toSet()
        return state.value.record?.measures?.any { it.itemId !in retained } == true
    }
    fun save(allowRemoval: Boolean = false) {
        if (state.value.busy || state.value.readOnly) return
        val selected = state.value.choices.filter { it.selected }.map { it.item }
        state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch { withContext(NonCancellable) {
            try {
                repository.saveFocus(actorId, taskId, selected, allowRemoval)
                state.update { it.copy(busy = false, openPlan = true) }
            } catch (e: Exception) { state.update { it.copy(busy = false, error = e.message ?: "保存失败") } }
        } }
    }
    fun planOpened() { state.update { it.copy(openPlan = false) } }
}
