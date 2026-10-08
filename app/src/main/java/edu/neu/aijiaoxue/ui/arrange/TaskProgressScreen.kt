package edu.neu.aijiaoxue.ui.arrange

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.neu.aijiaoxue.data.dao.TaskRow
import edu.neu.aijiaoxue.data.model.DateFormats
import edu.neu.aijiaoxue.data.model.TaskStatus
import edu.neu.aijiaoxue.data.model.isTaskOverdue

private data class FilterOption(val id: Long?, val label: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskProgressScreen(onBack: () -> Unit, viewModel: TaskProgressViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()
    var selectedTask by remember { mutableStateOf<TaskRow?>(null) }
    Scaffold(topBar = { TopAppBar(title = { Text("任务进度") }, navigationIcon = { TextButton(onClick = onBack) { Text("返回") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterMenu(
                    "课程",
                    state.courses.firstOrNull { it.id == state.courseId }?.name ?: "全部课程",
                    listOf(FilterOption(null, "全部课程")) + state.courses.map { FilterOption(it.id, it.name) },
                    viewModel::setCourse,
                    Modifier.weight(1f),
                )
                FilterMenu(
                    "督导",
                    state.supervisors.firstOrNull { it.id == state.supervisorId }?.name ?: "全部督导",
                    listOf(FilterOption(null, "全部督导")) + state.supervisors.map { FilterOption(it.id, it.name) },
                    viewModel::setSupervisor,
                    Modifier.weight(1f),
                )
                FilterMenu(
                    "状态",
                    state.status?.label ?: "全部状态",
                    listOf(FilterOption(null, "全部状态")) + TaskStatus.entries.mapIndexed { index, status -> FilterOption(index.toLong(), status.label) },
                    { id -> viewModel.setStatus(id?.let { TaskStatus.entries[it.toInt()] }) },
                    Modifier.weight(1f),
                )
            }
            Text(
                TaskStatus.entries.joinToString("　") { status ->
                    "${status.label} ${state.counts.firstOrNull { it.status == status }?.count ?: 0}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (state.rows.isEmpty()) {
                Text("暂无符合条件的督导任务", modifier = Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.rows, key = { it.id }) { row ->
                        Card(modifier = Modifier.fillMaxWidth().clickable { selectedTask = row }) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("${row.courseName} · ${row.supervisionDate} ${row.period}", style = MaterialTheme.typography.titleMedium)
                                Text("${row.code} · ${row.supervisorName} · ${row.teacherName}")
                                Text(
                                    if (isTaskOverdue(row.status, row.supervisionDate)) "${row.status.label} · 已逾期" else row.status.label,
                                    color = if (isTaskOverdue(row.status, row.supervisionDate)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    selectedTask?.let { row ->
        val overdue = isTaskOverdue(row.status, row.supervisionDate, DateFormats.today())
        AlertDialog(
            onDismissRequest = { selectedTask = null },
            title = { Text("任务信息") },
            text = { Text("课程：${row.courseName}\n任务编号：${row.code}\n督导：${row.supervisorName}\n教师：${row.teacherName}\n日期与节次：${row.supervisionDate} ${row.period}\n类型：${row.type.label}\n状态：${row.status.label}${if (overdue) "（已逾期）" else ""}") },
            confirmButton = { TextButton(onClick = { selectedTask = null }) { Text("关闭") } },
        )
    }
}

@Composable
private fun FilterMenu(
    title: String,
    selected: String,
    options: List<FilterOption>,
    onSelected: (Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier) {
        TextButton(onClick = { expanded = true }) { Text("$title：$selected") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(option.label) }, onClick = {
                    onSelected(option.id)
                    expanded = false
                })
            }
        }
    }
}