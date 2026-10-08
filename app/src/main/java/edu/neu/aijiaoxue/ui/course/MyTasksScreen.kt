package edu.neu.aijiaoxue.ui.course

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.neu.aijiaoxue.data.model.TaskStatus
import edu.neu.aijiaoxue.navigation.Routes

/**
 * US07 我的督导任务（Routes.MY_TASKS）。
 * 点“继续”回到任务当前所处的环节：待开始 / 进行中 → 采集页，待评价 → 评价页；
 * 已采集的材料、互动记录由采集页进入时从数据库恢复。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyTasksScreen(
    onBack: () -> Unit,
    onNavigate: (String) -> Unit,
    viewModel: MyTasksViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("我的督导任务") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
    ) { innerPadding ->
        val modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
        if (state.isLoading) {
            Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        LazyColumn(
            modifier = modifier,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SectionTitle("未完成") }
            if (state.unfinished.isEmpty()) {
                item { EmptyHint("没有未完成的任务，可在“课程检索”中创建督导任务") }
            }
            items(state.unfinished, key = { it.row.id }) { item ->
                TaskCard(item, onNavigate)
            }
            item { SectionTitle("已完成") }
            if (state.completed.isEmpty()) {
                item { EmptyHint("暂无已完成的任务") }
            }
            items(state.completed, key = { it.row.id }) { item ->
                TaskCard(item, onNavigate)
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun EmptyHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun TaskCard(item: MyTaskItem, onNavigate: (String) -> Unit) {
    val row = item.row
    // testTag 供界面测试定位到某个任务的卡片
    Card(
        Modifier
            .fillMaxWidth()
            .testTag(taskCardTag(row.id)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.courseName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    if (item.isOverdue) "${row.status.label} · 已逾期" else row.status.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (item.isOverdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                "${row.code} · ${row.teacherName} · ${row.supervisionDate} 第 ${row.period} 节 · ${row.type.label}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TaskActions(row.id, row.status, onNavigate)
        }
    }
}

internal fun taskCardTag(taskId: Long) = "task-$taskId"

/** US07 主流程 3：按状态给出下一步入口（NF-02）。 */
@Composable
private fun TaskActions(taskId: Long, status: TaskStatus, onNavigate: (String) -> Unit) {
    val buttonModifier = Modifier.height(48.dp)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
    ) {
        when (status) {
            TaskStatus.NOT_STARTED -> Button(
                onClick = { onNavigate(Routes.capture(taskId)) },
                modifier = buttonModifier,
            ) { Text("开始采集") }

            TaskStatus.IN_PROGRESS -> Button(
                onClick = { onNavigate(Routes.capture(taskId)) },
                modifier = buttonModifier,
            ) { Text("继续采集") }

            TaskStatus.PENDING_EVALUATION -> {
                // 需求 3.3：待评价状态下仍可补充材料、修改互动记录
                OutlinedButton(
                    onClick = { onNavigate(Routes.capture(taskId)) },
                    modifier = buttonModifier,
                ) { Text("查看材料") }
                Button(
                    onClick = { onNavigate(Routes.evaluation(taskId)) },
                    modifier = buttonModifier,
                ) { Text("继续评价") }
            }

            TaskStatus.COMPLETED -> OutlinedButton(
                onClick = { onNavigate(Routes.historyDetail(taskId)) },
                modifier = buttonModifier,
            ) { Text("查看记录") }
        }
    }
}
