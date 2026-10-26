package edu.neu.aijiaoxue.ui.improve

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import edu.neu.aijiaoxue.data.model.DateFormats
import edu.neu.aijiaoxue.data.model.MeasureStatus
import edu.neu.aijiaoxue.ui.evaluation.validDate
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ExecutionDraft(
    val implementationNote: String = "",
    val actualAdjustment: String = "",
    val implementationDate: String = DateFormats.today(),
    val status: MeasureStatus? = null,
    val requestKey: String = UUID.randomUUID().toString(),
)

data class EvidenceImportDraft(
    val executionId: Long,
    val description: String,
    val requestKey: String = UUID.randomUUID().toString(),
    val uri: Uri? = null,
)

data class ImproveExecutionUiState(
    val loading: Boolean = true,
    val context: ExecutionContext? = null,
    val selectedMeasureId: Long? = null,
    val drafts: Map<Long, ExecutionDraft> = emptyMap(),
    val pendingEvidence: EvidenceImportDraft? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val message: String? = null,
)

/** US43/44：保存落实记录，不改写已经确认的计划；每条措施分别保留未提交草稿。 */
class ImproveExecutionViewModel(
    private val repository: ImprovementExecutionRepository,
    private val evidenceStore: ImprovementEvidenceStore,
    private val actorId: Long,
    private val taskId: Long,
) : ViewModel() {
    private val state = MutableStateFlow(ImproveExecutionUiState())
    val uiState = state.asStateFlow()

    init { reload() }

    fun onResume() {
        // 文件选择器回调和 ON_RESUME 的顺序并不固定，不能让刷新抢占待导入操作。
        if (state.value.pendingEvidence == null) reload()
    }

    fun reload() {
        if (state.value.busy) return
        state.update { it.copy(loading = true, busy = true, error = null) }
        viewModelScope.launch {
            try {
                applyContext(repository.load(actorId, taskId))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                handleReadFailure(e, e.message ?: "加载落实记录失败")
            } finally {
                state.update { it.copy(loading = false, busy = false) }
            }
        }
    }

    private fun applyContext(context: ExecutionContext) {
        state.update { current ->
            val id = current.selectedMeasureId?.takeIf { selected -> context.record.measures.any { it.id == selected } }
                ?: context.record.measures.firstOrNull()?.id
            // 返回页面或刷新数据时只更新已保存内容，不能用数据库内容覆盖正在填写的表单。
            val drafts = if (id != null && id !in current.drafts) current.drafts + (id to ExecutionDraft()) else current.drafts
            current.copy(context = context, selectedMeasureId = id, drafts = drafts)
        }
    }

    fun selectMeasure(id: Long) {
        if (state.value.busy || state.value.context?.record?.measures?.none { it.id == id } != false) return
        state.update { it.copy(selectedMeasureId = id,
            drafts = if (id in it.drafts) it.drafts else it.drafts + (id to ExecutionDraft()), error = null, message = null) }
    }

    private fun editDraft(change: (ExecutionDraft) -> ExecutionDraft) {
        if (state.value.busy) return
        val id = state.value.selectedMeasureId ?: return
        state.update { current ->
            val draft = current.drafts[id] ?: return@update current
            val updated = change(draft)
            if (updated == draft) current else current.copy(
                drafts = current.drafts + (id to updated.copy(requestKey = UUID.randomUUID().toString())),
                error = null, message = null,
            )
        }
    }

    fun setImplementationNote(value: String) = editDraft { it.copy(implementationNote = value) }
    fun setActualAdjustment(value: String) = editDraft { it.copy(actualAdjustment = value) }
    fun setImplementationDate(value: String) = editDraft { it.copy(implementationDate = value) }
    fun setStatus(value: MeasureStatus?) = editDraft { it.copy(status = value) }

    fun save() {
        val current = state.value
        if (current.busy) return
        val id = current.selectedMeasureId ?: return
        val draft = current.drafts[id] ?: return
        val error = when {
            draft.implementationNote.isBlank() -> "请填写实施说明"
            draft.actualAdjustment.isBlank() -> "请填写实际调整内容"
            draft.implementationDate.length != 10 || !validDate(draft.implementationDate) -> "请输入有效的实施日期，格式为 yyyy-MM-dd"
            else -> null
        }
        if (error != null) { reportError(error); return }
        val implementedAt = LocalDate.parse(draft.implementationDate, DateFormats.DATE)
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        state.update { it.copy(busy = true, error = null, message = null) }
        viewModelScope.launch {
            try {
                repository.append(actorId, taskId, id, ExecutionInput(
                    implementationNote = draft.implementationNote,
                    actualAdjustment = draft.actualAdjustment,
                    implementedAt = implementedAt,
                    requestKey = draft.requestKey,
                    status = draft.status,
                ))
                // 写入成功即清表单。后续查询失败仅提示刷新，避免把已落库记录再次当作待保存内容。
                state.update { it.copy(drafts = it.drafts + (id to ExecutionDraft()), message = "落实记录已保存，可在下方添加实施证据") }
                refreshAfterWrite()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { state.update { it.copy(error = e.message ?: "保存失败，已保留填写内容") } }
            finally { state.update { it.copy(busy = false) } }
        }
    }

    fun prepareEvidence(executionId: Long, description: String): Boolean {
        if (state.value.busy || state.value.context?.executions?.none { it.execution.id == executionId } != false) return false
        state.update { it.copy(pendingEvidence = EvidenceImportDraft(executionId, description.trim()), error = null, message = null) }
        return true
    }

    fun onEvidenceSelected(uri: Uri?) {
        if (uri == null) { clearPendingEvidence(); return }
        val pending = state.value.pendingEvidence ?: return
        state.update { it.copy(pendingEvidence = pending.copy(uri = uri)) }
        retryEvidenceImport()
    }

    fun retryEvidenceImport() {
        val current = state.value
        if (current.busy) return
        val pending = current.pendingEvidence ?: return
        val uri = pending.uri ?: return
        state.update { it.copy(busy = true, error = null, message = null) }
        viewModelScope.launch {
            try {
                evidenceStore.importEvidence(actorId, pending.executionId, uri, pending.description, pending.requestKey)
                state.update { it.copy(pendingEvidence = null, message = "实施证据已保存") }
                refreshAfterWrite()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                // 重试沿用同一 requestKey；不重复创建已成功导入的证据。
                state.update { it.copy(error = e.message ?: "导入失败，可重试或重新选择文件") }
            } finally { state.update { it.copy(busy = false) } }
        }
    }

    fun clearPendingEvidence() {
        if (!state.value.busy) state.update { it.copy(pendingEvidence = null) }
    }

    fun openEvidence(evidenceId: Long, onOpened: (OpenedEvidence) -> Unit) {
        if (state.value.busy) return
        state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                onOpened(evidenceStore.openEvidence(actorId, evidenceId))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { state.update { it.copy(error = e.message ?: "证据无法打开") } }
            finally { state.update { it.copy(busy = false) } }
        }
    }

    private suspend fun refreshAfterWrite() {
        try { applyContext(repository.load(actorId, taskId)) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { handleReadFailure(e, "保存已成功，列表刷新失败，请点击刷新重试") }
    }

    private fun handleReadFailure(error: Exception, retryMessage: String) {
        if (error is IllegalArgumentException || error is IllegalStateException) {
            // load 的业务拒绝表示当前记录已不再可读；不能继续展示上一次授权得到的数据。
            // 仅在读取上下文时清除，保存时的空白/日期等普通校验仍应保留用户草稿。
            state.update { it.copy(context = null, selectedMeasureId = null, drafts = emptyMap(),
                pendingEvidence = null, message = null, error = error.message ?: "当前记录已无法访问，请重新进入") }
        } else {
            state.update { it.copy(error = retryMessage) }
        }
    }

    fun reportError(message: String) { state.update { it.copy(error = message) } }
}
