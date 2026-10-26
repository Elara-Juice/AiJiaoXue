package edu.neu.aijiaoxue.ui.growth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class TeachingSupportUiState(val record: TeachingSupportRecord? = null, val busy: Boolean = false,
    val error: String? = null, val message: String? = null)

class TeachingSupportViewModel(private val repository: TeachingSupportRepository, private val actorId: Long) : ViewModel() {
    private val mutable = MutableStateFlow(TeachingSupportUiState())
    val state = mutable.asStateFlow()
    private var taskId: Long? = null
    init { reload() }
    fun select(id: Long) { if (!mutable.value.busy) { taskId = id; reload() } }
    fun reload() = action { mutable.value = mutable.value.copy(record = repository.load(actorId, taskId)) }
    fun choices(itemId: Long) = mutable.value.record?.let { record ->
        record.selected?.items?.find { it.id == itemId }?.let { repository.choices(record, it) }
    }.orEmpty()
    fun saveHighlight(text: String) = action {
        repository.saveHighlight(actorId, requireNotNull(taskId), text)
        mutable.value = mutable.value.copy(record = repository.load(actorId, taskId), message = "亮点做法已保存，可在其他课堂复用")
    }
    fun adopt(itemId: Long, key: String, text: String) = action {
        repository.adopt(actorId, requireNotNull(taskId), itemId, key, text)
        mutable.value = mutable.value.copy(record = repository.load(actorId, taskId), message = "已加入计划草稿，请在改进计划中填写日期并确认")
    }
    private fun action(block: suspend () -> Unit) {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, error = null, message = null)
        viewModelScope.launch {
            try { block() } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.value = mutable.value.copy(error = e.message ?: "操作失败，请重试") }
            finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }
}
