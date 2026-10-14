package edu.neu.aijiaoxue.ui.analysis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import edu.neu.aijiaoxue.ui.evaluation.MessageCard
import edu.neu.aijiaoxue.ui.evaluation.ModulePage
import edu.neu.aijiaoxue.ui.evaluation.SectionCard
import edu.neu.aijiaoxue.ui.evidence.EvidencePanel

const val ANALYSIS_PAGE_TAG = "analysis_page"

@Composable
fun AnalysisScreen(viewModel: AnalysisViewModel, onBack: () -> Unit, onReview: (Long) -> Unit) {
    val state by viewModel.uiState.collectAsState()
    ModulePage("AI 课堂分析", onBack) { padding ->
        if (state.loading) {
            CircularProgressIndicator(Modifier.padding(padding).padding(24.dp))
            return@ModulePage
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).testTag(ANALYSIS_PAGE_TAG),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.task?.let { task ->
                item { Text("任务 ${task.code}  ${task.supervisionDate} 第 ${task.period} 节", style = MaterialTheme.typography.titleMedium) }
            }
            state.error?.let { item { MessageCard(it, error = true) } }
            if (state.task != null) item {
                if (!state.canAnalyze) MessageCard("课堂采集结束后才能进行 AI 分析")
                Button(
                    onClick = viewModel::analyze,
                    enabled = state.canAnalyze && !state.analyzing,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Text(
                        when {
                            state.analyzing -> "分析中…"
                            state.results.isEmpty() -> "开始 AI 分析"
                            else -> "重新分析"
                        }
                    )
                }
                if (state.results.isNotEmpty()) Text(
                    "重新分析会替换当前结果和审核状态",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(state.results, key = { it.id }) { result ->
                SectionCard(result.type.label) {
                    Text(result.content)
                    Text(
                        "可信度 ${(result.confidence * 100).toInt()}%  ·  ${if (result.verified) "已审核" else "待审核"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    EvidencePanel(state.evidences[result.id].orEmpty())
                    OutlinedButton(onClick = { onReview(result.id) }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (result.verified) "查看审核记录" else "审核 AI 结果")
                    }
                }
            }
        }
    }
}
