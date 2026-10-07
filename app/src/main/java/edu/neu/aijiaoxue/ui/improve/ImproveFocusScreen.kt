package edu.neu.aijiaoxue.ui.improve

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import edu.neu.aijiaoxue.data.model.Priority
import edu.neu.aijiaoxue.ui.evaluation.*

@Composable
fun ImproveFocusScreen(viewModel: ImproveFocusViewModel, onBack: () -> Unit, onPlan: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    var removing by remember { mutableStateOf(false) }
    LaunchedEffect(state.openPlan) { if (state.openPlan) { viewModel.planOpened(); onPlan() } }
    if (!state.loading && state.record == null) { ErrorPage("改进重点", state.error ?: "加载失败", onBack, viewModel::reload); return }
    ModulePage("确定改进重点", onBack) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize().testTag("focus-list"), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                if (state.loading) CircularProgressIndicator()
                else SectionCard(state.record?.course?.name.orEmpty()) {
                    Text("督导日期：${state.record?.task?.supervisionDate}")
                    Text("至少选择一项，建议集中在三项以内。")
                    Text("已选 ${state.choices.count { it.selected }} 项")
                    if (state.choices.count { it.selected } > 3) MessageCard("已超过三项，建议聚焦重点；仍可保存。")
                    if (state.readOnly) MessageCard("计划已确认，改进重点只读")
                    state.error?.let { MessageCard(it, true) }
                }
            }
            items(state.choices, key = { it.item.id }) { choice ->
                val enabled = !state.busy && !state.readOnly
                SectionCard(choice.item.source.label) {
                    Row {
                        Checkbox(checked = choice.selected, enabled = enabled,
                            onCheckedChange = { checked -> viewModel.change(choice.item.id) { it.copy(selected = checked) } },
                            modifier = Modifier.testTag("focus-select-${choice.item.issueId ?: choice.item.id}"))
                        if (choice.item.issueId == null) OutlinedTextField(value = choice.item.description,
                            onValueChange = { text -> viewModel.change(choice.item.id) { it.copy(item = it.item.copy(description = text)) } },
                            readOnly = !enabled, label = { Text("教师自选事项") }, modifier = Modifier.weight(1f))
                        else Text(choice.item.description, Modifier.padding(top = 12.dp).weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Priority.entries.forEach { priority -> FilterChip(selected = choice.item.priority == priority,
                            enabled = enabled && choice.selected, onClick = {
                                viewModel.change(choice.item.id) { it.copy(item = it.item.copy(priority = priority)) }
                            }, label = { Text("${priority.label}优先级") }) }
                    }
                }
            }
            if (!state.loading && state.record != null) item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    state.error?.let { MessageCard(it, true) }
                    if (state.readOnly) Button(onClick = onPlan, modifier = Modifier.fillMaxWidth()) { Text("查看改进计划") }
                    else {
                        OutlinedButton(onClick = viewModel::addCustom, enabled = !state.busy) { Text("添加教师自选事项") }
                        Button(onClick = {
                            if (viewModel.removalNeedsConfirmation()) removing = true else viewModel.save()
                        }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("保存重点并制定计划") }
                    }
                }
            }
        }
    }
    if (removing) AlertDialog(onDismissRequest = { removing = false }, title = { Text("移除事项及其措施？") },
        text = { Text("被取消选择的事项已有措施，保存后将一并删除。保留事项的措施不受影响。") },
        confirmButton = { TextButton(onClick = { removing = false; viewModel.save(allowRemoval = true) }) { Text("确认移除并保存") } },
        dismissButton = { TextButton(onClick = { removing = false }) { Text("继续调整") } })
}
