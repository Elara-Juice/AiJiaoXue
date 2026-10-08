package edu.neu.aijiaoxue.ui.improve

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import edu.neu.aijiaoxue.data.model.MeasureSource
import edu.neu.aijiaoxue.ui.evaluation.*

@Composable
fun ImprovePlanScreen(viewModel: ImprovePlanViewModel, onBack: () -> Unit, onFocus: () -> Unit, onTodos: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    var confirming by remember { mutableStateOf(false) }
    if (!state.loading && state.record == null) { ErrorPage("改进计划", state.error ?: "加载失败", onBack, viewModel::reload); return }
    ModulePage("个人改进计划", onBack) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize().testTag("plan-list"), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                if (state.loading) CircularProgressIndicator()
                else SectionCard(state.record?.course?.name.orEmpty()) {
                    Text("督导日期：${state.record?.task?.supervisionDate}")
                    MessageCard(if (state.readOnly) "计划已确认，内容和日期已锁定" else if (state.saved) "计划草稿已保存" else "正在保存计划…")
                    ReadField("督导参考建议", state.record?.evaluation?.suggestions)
                    Text("参考建议由本次督导填写，各事项是否适用由你决定。")
                    state.error?.let { MessageCard(it, true) }
                }
            }
            state.record?.items?.forEach { item ->
                item(key = "item-${item.id}") { SectionCard("${item.priority.label}优先级 · ${item.source.label}") {
                    Text(item.description, style = MaterialTheme.typography.titleMedium)
                    if (state.measures.none { it.itemId == item.id }) Text("此事项暂无措施，请至少保留一条。")
                    if (!state.readOnly) OutlinedButton(onClick = { viewModel.add(item.id) }, enabled = !state.busy) { Text("新增措施") }
                } }
                state.measures.filter { it.itemId == item.id }.forEach { measure ->
                    item(key = "measure-${measure.id}") {
                        SectionCard(measure.source.label) {
                            val enabled = !state.readOnly && !state.busy
                            OutlinedTextField(value = measure.content, onValueChange = { viewModel.content(measure.id, it) },
                                readOnly = !enabled, label = { Text("措施内容（必填）") },
                                modifier = Modifier.fillMaxWidth().testTag("measure-content-${measure.id}"))
                            DateField("计划完成日期", measure.dueDate, { date -> viewModel.change(measure.id) { it.copy(dueDate = date) } },
                                enabled = enabled, clearable = enabled)
                            if (enabled) {
                                if (measure.source != MeasureSource.ADDED) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { viewModel.adopt(measure.id) }) { Text("采纳原建议") }
                                    TextButton(onClick = { viewModel.change(measure.id) { it.copy(source = MeasureSource.MODIFIED) } }) { Text("修改建议") }
                                }
                                TextButton(onClick = { viewModel.remove(measure.id) }) {
                                    Text(if (measure.source == MeasureSource.ADDED) "删除措施" else "放弃此建议")
                                }
                            } else Text("执行状态：${measure.status.label}")
                        }
                    }
                }
            }
            if (!state.loading && state.record != null) item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    state.error?.let { MessageCard(it, true) }
                    if (state.readOnly) Button(onClick = onTodos, modifier = Modifier.fillMaxWidth()) { Text("查看改进待办") }
                    else {
                        OutlinedButton(onClick = onFocus, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("调整改进重点") }
                        OutlinedButton(onClick = { viewModel.persist() }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("保存计划草稿") }
                        Button(onClick = { if (viewModel.validate()) confirming = true }, enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth()) { Text("确认计划") }
                    }
                }
            }
        }
    }
    if (confirming) AlertDialog(onDismissRequest = { confirming = false }, title = { Text("确认实施本计划？") },
        text = { Text("确认后生成改进待办，计划内容和日期不可修改；仍可在待办中更新执行状态。") },
        confirmButton = { TextButton(onClick = { confirming = false; viewModel.persist(confirm = true) }) { Text("确认并生成待办") } },
        dismissButton = { TextButton(onClick = { confirming = false }) { Text("继续编辑") } })
}
