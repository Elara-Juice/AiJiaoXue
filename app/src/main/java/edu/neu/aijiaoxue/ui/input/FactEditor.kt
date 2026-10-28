package edu.neu.aijiaoxue.ui.input

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import edu.neu.aijiaoxue.ui.evaluation.timeText

@Composable
internal fun FactEditor(state: InputWorkspaceUiState, vm: InputWorkspaceViewModel) {
    val d = state.editor.fact
    InputBlock("人工补充事实") {
        Text("记录实际发生的事实，并关联原始来源或填写课堂发生时间。补录时间另行保留。")
        state.facts.forEach { fact ->
            Text(fact.description)
            Text("发生：${fact.classAtMs?.let(::inputTime) ?: fact.classStartMs?.let { "${inputTime(it)}–${inputTime(fact.classEndMs!!)}" } ?: "见关联来源"} · 补录：${timeText(fact.recordedAt)}", style = MaterialTheme.typography.bodySmall)
            Row {
                fact.sourceRefJson?.let { json -> TextButton(onClick = { vm.openSource(json) }) { Text("打开来源") } }
                if (state.editable) TextButton(onClick = { vm.beginFact(fact) }, enabled = !state.busy) { Text("追加更正") }
            }
            HorizontalDivider()
        }
        if (state.editable && state.task?.classEndAt != null) {
            OutlinedTextField(d.description, { value -> vm.changeFact { it.copy(description = value) } }, label = { Text(if (d.supersedesId == null) "事实描述" else "更正后的事实描述") }, modifier = Modifier.fillMaxWidth().testTag("fact-description"))
            var expanded by remember { mutableStateOf(false) }
            Box { OutlinedButton(onClick = { expanded = true }) { Text(state.sources.firstOrNull { it.sourceRefJson == d.sourceJson }?.label ?: "选择原课堂来源（可选）") }; DropdownMenu(expanded, { expanded = false }) {
                DropdownMenuItem(text = { Text("不选择来源，填写发生时间") }, onClick = { vm.changeFact { it.copy(sourceJson = null) }; expanded = false })
                state.sources.forEach { source -> DropdownMenuItem(text = { Text(source.label) }, onClick = { vm.changeFact { it.copy(sourceJson = source.sourceRefJson) }; expanded = false }) }
            } }
            TimeField(d.at, { value -> vm.changeFact { it.copy(at = value) } }, "发生时点", Modifier.fillMaxWidth())
            Text("时点与下方区间二选一", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TimeField(d.start, { value -> vm.changeFact { it.copy(start = value) } }, "开始", Modifier.weight(1f))
                TimeField(d.end, { value -> vm.changeFact { it.copy(end = value) } }, "结束", Modifier.weight(1f))
            }
            MutationMessage(state.factMutation)
            if (d.supersedesId != null) TextButton(onClick = vm::clearFact, enabled = !state.busy) { Text("取消此次更正") }
        }
    }
}
