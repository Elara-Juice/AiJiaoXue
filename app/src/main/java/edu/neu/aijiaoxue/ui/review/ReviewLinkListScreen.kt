package edu.neu.aijiaoxue.ui.review

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import edu.neu.aijiaoxue.ui.evaluation.MessageCard
import edu.neu.aijiaoxue.ui.evaluation.ModulePage
import edu.neu.aijiaoxue.ui.evaluation.SectionCard

/**
 * Sprint 3（李奕萱）US45/US46：复评关联列表。
 * 教师只能看本人课程的关联，督导看本人督导的课堂，管理人员看全部（权限在 SQL 层判断）。
 */
@Composable
fun ReviewLinkListScreen(
    model: ReviewLinkListViewModel,
    onBack: () -> Unit,
    onOpenLink: (Long) -> Unit,
) {
    val state by model.uiState.collectAsState()
    var showCreate by remember { mutableStateOf(false) }

    ModulePage("复评与指标比较", onBack) { padding ->
        Column(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.message?.let { MessageCard(it) }
            state.error?.let { MessageCard(it, error = true) }

            if (state.followUpTasks.isNotEmpty()) {
                TextButton(onClick = { showCreate = true }) { Text("新建复评关联") }
            }

            if (state.loading) {
                Text("加载中…")
            } else if (state.links.isEmpty()) {
                Text("暂无复评关联。督导完成“跟踪复评”课堂并提交评价后，可在这里把原课堂与复评课堂关联起来。")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(state.links, key = { it.id }) { row ->
                        Card(Modifier.fillMaxWidth().clickable { onOpenLink(row.id) }) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("${row.courseName}（${row.teacherName}）", style = MaterialTheme.typography.titleSmall)
                                Text("原课堂：${row.originDate} 第${row.originPeriod}节")
                                Text("复评课堂：${row.followUpDate} 第${row.followUpPeriod}节")
                                Text("原问题：${row.originIssueSnapshot}", style = MaterialTheme.typography.bodySmall)
                                row.originDimension?.let { Text("维度：$it", style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreate) {
        CreateLinkDialog(
            state = state,
            onSelectFollowUp = model::selectFollowUp,
            onSelectOrigin = model::selectOrigin,
            onSelectIssue = model::selectIssue,
            onConfirm = { model.submit(); showCreate = false },
            onDismiss = { showCreate = false; model.cancelCreate() },
        )
    }
}

/** 新建复评关联：先选复评课堂，再选原课堂，最后选原问题条目。 */
@Composable
private fun CreateLinkDialog(
    state: ReviewListUiState,
    onSelectFollowUp: (edu.neu.aijiaoxue.data.dao.ReviewTaskCandidateRow) -> Unit,
    onSelectOrigin: (edu.neu.aijiaoxue.data.dao.ReviewTaskCandidateRow) -> Unit,
    onSelectIssue: (Long?) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建复评关联") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    state.selectedFollowUp == null -> {
                        Text("第 1 步：选择复评课堂（跟踪复评）")
                        if (state.followUpTasks.isEmpty()) Text("没有可用的跟踪复评课堂")
                        state.followUpTasks.forEach { task ->
                            Row(verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable { onSelectFollowUp(task) }) {
                                RadioButton(selected = false, onClick = { onSelectFollowUp(task) })
                                Text("${task.courseName} ${task.supervisionDate} 第${task.period}节（${task.code}）")
                            }
                        }
                    }
                    state.selectedOrigin == null -> {
                        Text("第 2 步：选择原课堂（同课程、已提交正式评价）")
                        if (state.originCandidates.isEmpty()) Text("该课程还没有可关联的原课堂")
                        state.originCandidates.forEach { task ->
                            Row(verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable { onSelectOrigin(task) }) {
                                RadioButton(selected = false, onClick = { onSelectOrigin(task) })
                                Text("${task.courseName} ${task.supervisionDate} 第${task.period}节（${task.code}）")
                            }
                        }
                    }
                    else -> {
                        Text("第 3 步：选择要复评的原问题")
                        Row(verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { onSelectIssue(null) }) {
                            RadioButton(selected = state.selectedIssueId == null, onClick = { onSelectIssue(null) })
                            Text("整堂课堂复评（不限定单条问题）")
                        }
                        state.originIssues.forEach { issue ->
                            Row(verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable { onSelectIssue(issue.id) }) {
                                RadioButton(selected = state.selectedIssueId == issue.id,
                                    onClick = { onSelectIssue(issue.id) })
                                Text(issue.description + (issue.dimension?.let { "（$it）" } ?: ""))
                            }
                        }
                    }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm,
                enabled = state.selectedFollowUp != null && state.selectedOrigin != null && !state.submitting) {
                Text("保存关联")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
