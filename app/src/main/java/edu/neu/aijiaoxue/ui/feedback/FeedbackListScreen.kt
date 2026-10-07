package edu.neu.aijiaoxue.ui.feedback

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.neu.aijiaoxue.data.dao.FeedbackRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedbackListScreen(
    onBack: () -> Unit,
    onFeedbackClick: (Long) -> Unit,
    viewModel: FeedbackListViewModel = viewModel(),
) {
    val rows by viewModel.rows.collectAsState()
    Scaffold(topBar = { TopAppBar(title = { Text("督导反馈") }, navigationIcon = { TextButton(onClick = onBack) { Text("返回") } }) }) { padding ->
        if (rows.isEmpty()) {
            Text("暂无督导反馈", modifier = Modifier.padding(padding).padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(rows, key = FeedbackRow::evaluationId) { row ->
                    Card(onClick = { onFeedbackClick(row.taskId) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(row.courseName, style = MaterialTheme.typography.titleMedium)
                            Text("督导：${row.supervisorName} · ${row.supervisionDate}")
                            Text("总分：${row.totalScore ?: "未填写"} · ${row.grade?.label ?: "未填写"}")
                            Text(
                                if (row.teacherReadAt == null) "未读" else "已读",
                                color = if (row.teacherReadAt == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}