package edu.neu.aijiaoxue.ui.feedback

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import edu.neu.aijiaoxue.data.model.EvalDimension

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedbackDetailScreen(taskId: Long, onBack: () -> Unit) {
    val viewModel: FeedbackDetailViewModel = viewModel(
        key = "feedback-detail-$taskId",
        factory = viewModelFactory { initializer { FeedbackDetailViewModel(this[ androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!, taskId) } },
    )
    val state by viewModel.uiState.collectAsState()
    Scaffold(topBar = { TopAppBar(title = { Text("反馈详情") }, navigationIcon = { TextButton(onClick = onBack) { Text("返回") } }) }) { padding ->
        when {
            state.isLoading -> CircularProgressIndicator(Modifier.padding(padding).padding(24.dp))
            state.error != null -> Text(state.error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(padding).padding(20.dp))
            state.evaluation != null -> {
                val evaluation = state.evaluation!!
                Column(
                    Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(state.courseName, style = MaterialTheme.typography.headlineSmall)
                    Text("督导：${state.supervisorName} · ${state.supervisionDate}")
                    Text("综合得分：${evaluation.totalScore ?: "未填写"} · ${evaluation.grade?.label ?: "未填写"}", style = MaterialTheme.typography.titleMedium)
                    EvalDimension.entries.forEach { dimension ->
                        val score = evaluation.scoreOf(dimension)?.toString() ?: "未填写"
                        val comment = when (dimension) {
                            EvalDimension.CONTENT -> evaluation.contentComment
                            EvalDimension.DESIGN -> evaluation.designComment
                            EvalDimension.INTERACTION -> evaluation.interactionComment
                            EvalDimension.ENGAGEMENT -> evaluation.engagementComment
                        }
                        Text("${dimension.label}：$score 分${comment?.takeIf { it.isNotBlank() }?.let { "\n$it" } ?: ""}")
                    }
                    Text("主要优点", style = MaterialTheme.typography.titleMedium)
                    Text(evaluation.strengths?.takeIf { it.isNotBlank() } ?: "未填写")
                    Text("改进建议", style = MaterialTheme.typography.titleMedium)
                    Text(evaluation.suggestions?.takeIf { it.isNotBlank() } ?: "未填写")
                    Text("综合意见", style = MaterialTheme.typography.titleMedium)
                    Text(evaluation.overallOpinion?.takeIf { it.isNotBlank() } ?: "未填写")
                    Text("主要问题", style = MaterialTheme.typography.titleMedium)
                    if (state.issues.isEmpty()) Text("未填写")
                    state.issues.forEachIndexed { index, issue -> Text("${index + 1}. ${issue.description}") }
                }
            }
        }
    }
}