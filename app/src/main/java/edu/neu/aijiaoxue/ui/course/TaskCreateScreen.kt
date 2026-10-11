package edu.neu.aijiaoxue.ui.course

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import edu.neu.aijiaoxue.data.model.SupervisionType
import edu.neu.aijiaoxue.ui.evaluation.DateField

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskCreateScreen(
    courseId: Long,
    onBack: () -> Unit,
    onStartCapture: (taskId: Long) -> Unit = {},
    onMyTasks: () -> Unit = {},
    viewModel: TaskCreateViewModel = viewModel(
        key = "task-create-$courseId",
        factory = viewModelFactory { initializer { TaskCreateViewModel(this[ androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!, courseId) } },
    ),
) {
    val state by viewModel.uiState.collectAsState()
    var typeMenu by remember { mutableStateOf(false) }
    Scaffold(topBar = { TopAppBar(title = { Text("创建督导任务") }, navigationIcon = { TextButton(onClick = onBack) { Text("返回") } }) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val createdTaskId = state.createdTaskId
            if (createdTaskId != null) {
                Text("任务创建成功，已加入督导任务", style = MaterialTheme.typography.titleLarge)
                state.createdCode?.let { Text("任务编号：$it · 状态：待开始", style = MaterialTheme.typography.bodyLarge) }
                // 王博田缺陷 D02：主链第 3→4 步，创建后直接进入采集，不用绕回首页
                Button(onClick = { onStartCapture(createdTaskId) }, modifier = Modifier.fillMaxWidth()) { Text("开始采集") }
                OutlinedButton(onClick = onMyTasks, modifier = Modifier.fillMaxWidth()) { Text("查看我的督导任务") }
                TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("返回课程") }
            } else {
                // 王博田缺陷 D03：日期用日期选择器，节次用下拉选择，不再手输格式；错误分别提示在对应字段下
                DateField("督导日期", state.supervisionDate, viewModel::onDateChange, clearable = false)
                state.dateError?.let { FieldError(it) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PeriodMenu("开始节次", state.periodStart, viewModel::onPeriodStartChange, Modifier.weight(1f))
                    PeriodMenu("结束节次", state.periodEnd, viewModel::onPeriodEndChange, Modifier.weight(1f))
                }
                state.periodError?.let { FieldError(it) }
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

/** D03：节次 1–12 下拉选择，未选时显示“请选择”。 */
@Composable
private fun PeriodMenu(label: String, value: Int?, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text("$label：${value?.let { "第 $it 节" } ?: "请选择"}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            (1..TaskCreateViewModel.MAX_PERIOD).forEach { n ->
                DropdownMenuItem(text = { Text("第 $n 节") }, onClick = {
                    onChange(n)
                    expanded = false
                })
            }
        }
    }
}

/** 字段下方的错误提示，读屏会立即播报。 */
@Composable
private fun FieldError(message: String) {
    Text(
        message,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
    )
}
