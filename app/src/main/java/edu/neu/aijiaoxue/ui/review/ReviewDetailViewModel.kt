package edu.neu.aijiaoxue.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import edu.neu.aijiaoxue.data.model.VerificationVerdict
import edu.neu.aijiaoxue.data.repository.ReviewLinkDetail
import edu.neu.aijiaoxue.data.repository.ReviewRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Sprint 3（李奕萱）US46：重点问题改善核验页状态。
 * 核验必须有人工判断、依据、核验人和核验时间；证据不足时保留“继续观察”。
 */
data class ReviewDetailUiState(
    val loading: Boolean = true,
    val detail: ReviewLinkDetail? = null,
    val verdict: VerificationVerdict = VerificationVerdict.CONTINUE_OBSERVING,
    val basis: String = "",
    val saving: Boolean = false,
    val capturing: Boolean = false,
    val error: String? = null,
    val message: String? = null,
)

class ReviewDetailViewModel(
    private val repository: ReviewRepository,
    private val actorId: Long,
    private val linkId: Long,
) : ViewModel() {
    private val state = MutableStateFlow(ReviewDetailUiState())
    val uiState = state.asStateFlow()

    init { load() }

    fun load() {
        state.update { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                val detail = repository.linkDetail(actorId, linkId)
                if (detail == null) {
                    state.update { it.copy(loading = false, error = "复评关联不存在或无权访问") }
                } else {
                    state.update { it.copy(loading = false, detail = detail, error = null) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.update { it.copy(loading = false, error = e.message ?: "加载失败") }
            }
        }
    }

    fun setVerdict(verdict: VerificationVerdict) {
        state.update { it.copy(verdict = verdict) }
    }

    fun setBasis(basis: String) {
        state.update { it.copy(basis = basis) }
    }

    /** 保存核验结论。 */
    fun save() {
        val current = state.value
        if (current.saving) return
        state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                val receipt = repository.saveVerification(actorId, linkId, current.verdict, current.basis)
                state.update { it.copy(saving = false, basis = "", message = "已保存核验结论：${receipt.verdict.label}（${receipt.evidence}）") }
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.update { it.copy(saving = false, error = e.message ?: "保存核验结论失败") }
            }
        }
    }

    /** 保存某次课堂的正式指标快照（只追加，已有快照不会被覆盖）。 */
    fun captureSnapshots(taskId: Long) {
        if (state.value.capturing) return
        state.update { it.copy(capturing = true, error = null) }
        viewModelScope.launch {
            try {
                val inserted = repository.captureSnapshots(actorId, taskId)
                state.update {
                    it.copy(capturing = false,
                        message = if (inserted > 0) "已保存 $inserted 项正式指标快照" else "该课堂的正式指标快照已存在，未覆盖")
                }
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.update { it.copy(capturing = false, error = e.message ?: "保存指标快照失败") }
            }
        }
    }
}
