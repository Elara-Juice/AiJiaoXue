package edu.neu.aijiaoxue.ui.evaluation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun HistoryScreen(viewModel: HistoryViewModel, onBack: () -> Unit, onDetail: (Long) -> Unit) {
    val state by viewModel.uiState.collectAsState()
    ModulePage("历史督导记录", onBack) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { SectionCard("检索已完成记录") {
                OutlinedTextField(value = state.keyword, onValueChange = viewModel::keyword, singleLine = true,
                    label = { Text("课程名称或教师姓名") }, modifier = Modifier.fillMaxWidth())
                DateField("开始日期", state.from, { viewModel.dates(from = it) })
                DateField("结束日期", state.to, { viewModel.dates(to = it) })
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = viewModel::search) { Text("检索") }
                    TextButton(onClick = viewModel::reset) { Text("重置条件") }
                }
            } }
            item {
                when {
                    state.error != null -> MessageCard(state.error!!, true)
                    state.loading -> CircularProgressIndicator()
                    state.rows.isEmpty() -> MessageCard("没有符合条件的历史记录")
                    else -> Text("共 ${state.rows.size} 条 · 督导日期倒序 · 只读")
                }
            }
            items(state.rows, key = { it.taskId }) { row ->
                Card(onClick = { onDetail(row.taskId) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(row.courseName, style = MaterialTheme.typography.titleMedium)
                        Text("教师：${row.teacherName} · 督导：${row.supervisorName}")
                        Text("${row.supervisionDate} · ${row.period}节 · ${row.code}")
                        Text("${row.totalScore ?: "未评分"} 分 · ${row.grade?.label ?: "未评级"}")
                        Text("查看完整记录", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}
