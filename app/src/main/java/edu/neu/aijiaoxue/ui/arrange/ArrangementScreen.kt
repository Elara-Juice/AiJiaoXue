package edu.neu.aijiaoxue.ui.arrange

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArrangementScreen(
    onBack: () -> Unit,
    onCourseClick: (Long) -> Unit,
    viewModel: ArrangementViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    Scaffold(topBar = { TopAppBar(title = { Text("督导安排") }, navigationIcon = { TextButton(onClick = onBack) { Text("返回") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp)) {
                Checkbox(checked = state.uncoveredOnly, onCheckedChange = viewModel::setUncoveredOnly)
                Text("仅看本学期未督导课程")
            }
            state.error?.let { Text(it, color = if (it == "已纳入待督导安排") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.rows, key = { it.id }) { row ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            TextButton(onClick = { onCourseClick(row.id) }) {
                                Text("${row.name} · ${row.code}", style = MaterialTheme.typography.titleMedium)
                            }
                            Text("任课教师：${row.teacherName} · 已完成督导 ${row.completedCount} 次")
                            Text("最近督导：${row.lastSupervisionDate ?: "未填写"}")
                            when {
                                row.hasOpenArrangement -> Text(
                                    if (row.isKeyFocus) "待督导 · 重点关注" else "待督导",
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                state.selectedCourse?.id == row.id -> {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(checked = state.isKeyFocus, onCheckedChange = viewModel::setKeyFocus)
                                        Text("重点关注")
                                    }
                                    OutlinedTextField(
                                        value = state.note,
                                        onValueChange = viewModel::setNote,
                                        label = { Text("安排说明（选填）") },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = viewModel::addArrangement, enabled = !state.isSaving) { Text("纳入安排") }
                                        TextButton(onClick = viewModel::clearSelection) { Text("取消") }
                                    }
                                }
                                else -> Button(onClick = { viewModel.selectCourse(row) }) { Text("安排此课程") }
                            }
                        }
                    }
                }
            }
        }
    }
}