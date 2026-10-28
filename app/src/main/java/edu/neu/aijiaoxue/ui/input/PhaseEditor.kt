package edu.neu.aijiaoxue.ui.input

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import edu.neu.aijiaoxue.data.model.*

@Composable
internal fun PhaseEditor(state: InputWorkspaceUiState, vm: InputWorkspaceViewModel) {
    InputBlock("人工教学环节") {
        Text(if (state.phaseStatus == PhaseStatus.DRAFT) "当前为未确认草稿，分析仍使用上一确认版本。" else "按真实课堂时间标记环节，允许留出未观测的空档。", style = MaterialTheme.typography.bodySmall)
        MutationMessage(state.phaseMutation)
        if (state.phases.isEmpty()) Text("尚未标记教学环节")
        state.phases.forEach { p ->
            Text("${p.label} · ${activityLabel(p.kind)} · ${inputTime(p.startMs)}–${inputTime(p.endMs)}")
            if (state.editable) Row {
                TextButton(onClick = { vm.beginPhase(p) }, enabled = !state.busy) { Text("编辑") }
                TextButton(onClick = { vm.savePhaseList(state.phases.filterNot { it.id == p.id }, false) }, enabled = !state.busy && state.phases.size > 1) { Text("删除") }
            }
            HorizontalDivider()
        }
        if (state.editable) {
            if (state.editor.phase.label.isNotBlank() || state.editor.phase.start.isNotBlank()) OutlinedButton(onClick = vm::resumePhase) { Text("继续未保存的环节编辑") }
            OutlinedButton(onClick = { vm.beginPhase() }, enabled = !state.busy && state.editor.phase.label.isBlank() && state.editor.phase.start.isBlank()) { Text("添加教学环节") }
            Button(onClick = { vm.savePhaseList(state.phases, true) }, enabled = !state.busy && state.phases.isNotEmpty() && state.phaseStatus != PhaseStatus.CONFIRMED) { Text("确认当前环节版本") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhaseEditorSheet(state: InputWorkspaceUiState, vm: InputWorkspaceViewModel) {
    val d = state.editor.phase
    ModalBottomSheet(onDismissRequest = vm::hidePhase, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (d.editingId == null) "添加教学环节" else "修改教学环节", style = MaterialTheme.typography.titleLarge)
            Text("填写分:秒（如 02:30），也可直接填写秒数。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(d.label, { value -> vm.changePhase { it.copy(label = value) } }, label = { Text("环节名称") }, modifier = Modifier.fillMaxWidth().testTag("phase-label"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TimeField(d.start, { value -> vm.changePhase { it.copy(start = value) } }, "开始时间", Modifier.weight(1f).testTag("phase-start"))
                TimeField(d.end, { value -> vm.changePhase { it.copy(end = value) } }, "结束时间", Modifier.weight(1f).testTag("phase-end"))
            }
            var expanded by remember { mutableStateOf(false) }
            Box { OutlinedButton(onClick = { expanded = true }) { Text("活动类型：${activityLabel(d.kind)}") }; DropdownMenu(expanded, { expanded = false }) { ActivityKind.entries.forEach { kind -> DropdownMenuItem(text = { Text(activityLabel(kind)) }, onClick = { vm.changePhase { it.copy(kind = kind) }; expanded = false }) } } }
            OutlinedTextField(d.note, { value -> vm.changePhase { it.copy(note = value) } }, label = { Text("事实备注") }, modifier = Modifier.fillMaxWidth())
            MutationMessage(state.phaseMutation)
            if (d.baseVersionId != state.phaseVersionId) {
                Text("当前列表已是新版本，本地编辑仍基于旧版本。请核对列表后再应用。", color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = vm::rebasePhase, enabled = !state.busy) { Text("已核对，将此编辑应用到当前版本") }
            }
            Button(onClick = vm::savePhaseDraft, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("phase-save")) { Text(if (state.phaseMutation.status == UiMutationStatus.SAVING) "正在保存…" else "保存环节草稿") }
            TextButton(onClick = vm::hidePhase) { Text("稍后继续，保留输入") }
        }
    }
}

@Composable
internal fun TimeField(value: String, onValue: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    OutlinedTextField(value, onValue, label = { Text("$label（分:秒）") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = modifier)
}

internal fun activityLabel(kind: ActivityKind): String = when (kind) {
    ActivityKind.INTRODUCTION -> "导入"; ActivityKind.LECTURE -> "讲授"; ActivityKind.DISCUSSION -> "讨论"
    ActivityKind.PRACTICE -> "练习"; ActivityKind.SUMMARY -> "总结"; ActivityKind.OTHER -> "其他"
}
