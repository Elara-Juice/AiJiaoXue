package edu.neu.aijiaoxue.ui.input

import edu.neu.aijiaoxue.data.model.*
import edu.neu.aijiaoxue.data.repository.TranscriptEdit

data class MutationState(val requestId: String = "", val editRevision: Long = 0, val status: UiMutationStatus = UiMutationStatus.IDLE, val error: String? = null, val savedVersionId: Long? = null)
data class PhaseEditorDraft(val visible: Boolean = false, val editingId: String? = null, val label: String = "", val start: String = "", val end: String = "", val kind: ActivityKind = ActivityKind.LECTURE, val note: String = "", val baseVersionId: Long? = null, val editRevision: Long = 0)
data class FactEditorDraft(val description: String = "", val sourceJson: String? = null, val at: String = "", val start: String = "", val end: String = "", val supersedesId: Long? = null, val editRevision: Long = 0, val requestKey: String = java.util.UUID.randomUUID().toString())
data class TranscriptEditorDraft(val materialId: Long, val baseVersionId: Long, val changes: Map<String, TranscriptEdit> = emptyMap(), val editRevision: Long = 0)
data class InputEditorState(
    val tab: InputWorkspaceTab = InputWorkspaceTab.COURSE,
    val selectedMaterialId: Long? = null,
    val pages: Map<Long, Int> = emptyMap(),
    val scrollOffsets: Map<InputWorkspaceTab, Int> = emptyMap(),
    val phase: PhaseEditorDraft = PhaseEditorDraft(),
    val fact: FactEditorDraft = FactEditorDraft(),
    val transcripts: Map<Long, TranscriptEditorDraft> = emptyMap(),
)

/** 时间编辑采用分:秒，兼容只输入秒；不容许小数或负值。 */
fun parseInputTime(text: String): Long? {
    if (text.isBlank()) return null
    val pieces = text.trim().split(':')
    require(pieces.size in 1..2) { "时间请填写分:秒，例如 02:30" }
    val seconds = if (pieces.size == 1) requireNotNull(pieces[0].toLongOrNull()) { "时间请填写分:秒" } else {
        val minutes = requireNotNull(pieces[0].toLongOrNull()) { "分钟无效" }
        val remainder = requireNotNull(pieces[1].toLongOrNull()) { "秒数无效" }
        require(minutes >= 0 && minutes <= Long.MAX_VALUE / 60 && remainder in 0..59) { "时间范围无效" }
        Math.addExact(Math.multiplyExact(minutes, 60), remainder)
    }
    require(seconds in 0..Long.MAX_VALUE / 1000) { "时间范围无效" }
    return seconds * 1000
}

fun inputTime(milliseconds: Long): String = "%02d:%02d".format(milliseconds / 60_000, milliseconds / 1000 % 60)

/** 保存响应只移除提交时的补丁；新输入即使同一句也保留。 */
fun reconcileTranscriptSave(current: TranscriptEditorDraft, submitted: TranscriptEditorDraft, newVersionId: Long): TranscriptEditorDraft =
    current.copy(baseVersionId = newVersionId, changes = current.changes.filter { (id, edit) -> submitted.changes[id] != edit })
