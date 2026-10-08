package edu.neu.aijiaoxue.ui.improve

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import edu.neu.aijiaoxue.data.model.DateFormats
import edu.neu.aijiaoxue.data.model.MeasureStatus
import edu.neu.aijiaoxue.data.model.isMeasureOverdue
import edu.neu.aijiaoxue.ui.evaluation.*
import kotlinx.coroutines.delay

@Composable
fun ImproveTodoScreen(viewModel: ImproveTodoViewModel, onBack: () -> Unit, onPlan: (Long) -> Unit, onFeedback: (Long) -> Unit) {
    val state by viewModel.uiState.collectAsState()
    val today by produceState(DateFormats.today()) {
        while (true) { value = DateFormats.today(); delay(60_000) }
    }
    ModulePage("改进待办", onBack) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize().testTag("todo-list"), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Column {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = state.onlyOpen, onClick = { viewModel.filter(onlyOpen = true) }, label = { Text("未完成") })
                    FilterChip(selected = !state.onlyOpen && state.status == null, onClick = { viewModel.filter() }, label = { Text("全部") })
                    MeasureStatus.entries.forEach { status -> FilterChip(selected = !state.onlyOpen && state.status == status,
                        onClick = { viewModel.filter(status = status) }, label = { Text(status.label) }, modifier = Modifier.testTag("filter-${status.name}")) }
                }
                Text("按计划完成日期升序 · 共 ${state.rows.size} 项")
                state.error?.let { MessageCard(it, true) }
                if (state.error != null) TextButton(onClick = { viewModel.filter(state.onlyOpen, state.status) }) { Text("重试") }
                if (state.loading) CircularProgressIndicator()
                else if (state.rows.isEmpty() && state.error == null) MessageCard("当前筛选下暂无改进待办")
            } }
            items(state.rows, key = { it.measureId }) { row ->
                SectionCard(row.courseName) {
                    Text(row.content, style = MaterialTheme.typography.titleMedium)
                    Text("原问题：${row.itemDescription}")
                    Text("${row.priority.label}优先级 · ${row.source.label}")
                    Text("督导日期：${row.supervisionDate}")
                    Text("计划完成：${row.dueDate} · ${row.status.label}")
                    if (isMeasureOverdue(row.status, row.dueDate, today)) Text("已逾期", color = MaterialTheme.colorScheme.error)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MeasureStatus.entries.forEach { status -> FilterChip(selected = row.status == status,
                            enabled = state.busyId == null, onClick = { viewModel.updateStatus(row.measureId, status) },
                            label = { Text(status.label) }, modifier = Modifier.testTag("todo-${row.measureId}-${status.name}")) }
                    }
                    Row {
                        TextButton(onClick = { onPlan(row.taskId) }) { Text("查看原计划") }
                        TextButton(onClick = { onFeedback(row.taskId) }) { Text("查看督导反馈") }
                    }
                }
            }
        }
    }
}
