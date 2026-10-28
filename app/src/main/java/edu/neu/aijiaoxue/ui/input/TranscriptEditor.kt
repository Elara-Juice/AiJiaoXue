package edu.neu.aijiaoxue.ui.input

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import edu.neu.aijiaoxue.data.ai.AnalysisJson
import edu.neu.aijiaoxue.data.entity.TranscriptVersion
import edu.neu.aijiaoxue.data.model.SpeakerRole
import org.json.JSONObject

@Composable
internal fun TranscriptEditor(version: TranscriptVersion, state: InputWorkspaceUiState, vm: InputWorkspaceViewModel) {
    val segments = remember(version.id) { AnalysisJson.objects(JSONObject(version.payloadJson).getJSONArray("segments")).sortedBy { it.getLong("mediaStartMs") } }
    val draft = state.editor.transcripts[version.materialId]
    val pageCount = maxOf(1, (segments.size + 19) / 20)
    val page = (state.editor.pages[version.materialId] ?: 0).coerceIn(0, pageCount - 1)
    Text(InputWorkspaceViewModel.firstClassMs(version)?.let { "已知课堂起点 ${inputTime(it)}" } ?: "课堂时间未知；仍可按文件内时间播放。", style = MaterialTheme.typography.bodySmall)
    state.transcriptMutations[version.materialId]?.let { MutationMessage(it) }
    if (draft != null && draft.changes.isNotEmpty() && draft.baseVersionId != version.id) {
        Text("转写已更新。本地修改尚未写入新版本，请逐句核对下方新正文。", color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = { vm.rebaseTranscript(version.materialId) }, enabled = !state.busy) { Text("已核对，将本次修改应用到当前版本") }
    }
    if (segments.isEmpty()) Text("未识别到语音")
    TranscriptPages(segments.size, page, pageCount) { vm.setTranscriptPage(version.materialId, it) }
    segments.drop(page * 20).take(20).forEach { segment ->
        val id = segment.getString("segmentId")
        key(version.materialId, id) {
            val patch = draft?.changes?.get(id)
            val text = patch?.text ?: segment.getString("text")
            val role = patch?.speakerRole ?: runCatching { SpeakerRole.valueOf(segment.optString("speakerRole", "UNKNOWN")) }.getOrDefault(SpeakerRole.UNKNOWN)
            if (patch != null && draft.baseVersionId != version.id) Text("当前版本正文：${segment.getString("text")}", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(text, { vm.editTranscript(version, id, it, role) }, label = { Text("${inputTime(segment.getLong("mediaStartMs"))}–${inputTime(segment.getLong("mediaEndMs"))} · 文件时间") }, modifier = Modifier.fillMaxWidth().testTag("transcript-${version.materialId}-$id"))
            Row(Modifier.selectableGroup()) {
                SpeakerRole.entries.forEach { option ->
                    Row(Modifier.weight(1f).heightIn(min = 48.dp).selectable(selected = role == option, role = Role.RadioButton, onClick = { vm.editTranscript(version, id, text, option) }), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        RadioButton(selected = role == option, onClick = null)
                        Text(when (option) { SpeakerRole.TEACHER -> "教师"; SpeakerRole.STUDENT -> "学生"; SpeakerRole.UNKNOWN -> "未知" })
                    }
                }
            }
            TextButton(onClick = { vm.openSource(InputWorkspaceViewModel.ref("TRANSCRIPT_SEGMENT", version.taskId, version.id, version.revision, version.contentHash, id, segment.getString("text")).toString()) }, enabled = !state.busy) { Text("回放本句原音频") }
            HorizontalDivider()
        }
    }
    draft?.changes?.values?.filter { edit -> segments.none { it.getString("segmentId") == edit.segmentId } }?.forEach { Text("原句段已不存在，修改保留待核对：${it.text}", color = MaterialTheme.colorScheme.error) }
    if (pageCount > 1) TranscriptPages(segments.size, page, pageCount) { vm.setTranscriptPage(version.materialId, it) }
}

@Composable
private fun TranscriptPages(total: Int, page: Int, pageCount: Int, onPage: (Int) -> Unit) {
    Text("共 $total 句 · 第 ${page + 1}/$pageCount 页", style = MaterialTheme.typography.bodySmall)
    if (pageCount > 1) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = { onPage(page - 1) }, enabled = page > 0) { Text("上一页") }
        OutlinedButton(onClick = { onPage(page + 1) }, enabled = page + 1 < pageCount) { Text("下一页") }
    }
}
