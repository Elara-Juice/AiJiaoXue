package edu.neu.aijiaoxue.ui.course

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import edu.neu.aijiaoxue.data.model.SupervisionType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskCreateScreen(courseId: Long, onBack: () -> Unit) {
    val viewModel: TaskCreateViewModel = viewModel(
        key = "task-create-$courseId",
        factory = viewModelFactory { initializer { TaskCreateViewModel(this[ androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!, courseId) } },
    )
    val state by viewModel.uiState.collectAsState()
    var typeMenu by remember { mutableStateOf(false) }
    Scaffold(topBar = { TopAppBar(title = { Text("创建督导任务") }, navigationIcon = { TextButton(onClick = onBack) { Text("返回") } }) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.created) {
                Text("任务创建成功，已加入督导任务", style = MaterialTheme.typography.titleLarge)
                Button(onClick = onBack) { Text("返回课程") }
            } else {
                OutlinedTextField(
                    value = state.supervisionDate,
                    onValueChange = viewModel::onDateChange,
                    label = { Text("督导日期（yyyy-MM-dd）") },
                    isError = state.dateError,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.period,
                    onValueChange = viewModel::onPeriodChange,
                    label = { Text("节次（如 3-4）") },
                    isError = state.periodError,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.chapter,
                    onValueChange = viewModel::onChapterChange,
                    label = { Text("教学章节（选填）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { typeMenu = true }) { Text("督导类型：${state.type.label}  ▾") }
                DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                    SupervisionType.entries.forEach { type ->
                        DropdownMenuItem(text = { Text(type.label) }, onClick = {
                            viewModel.onTypeChange(type)
                            typeMenu = false
                        })
                    }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(onClick = viewModel::create, enabled = !state.isSaving, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.isSaving) "正在创建…" else "创建任务")
                }
            }
        }
    }
}