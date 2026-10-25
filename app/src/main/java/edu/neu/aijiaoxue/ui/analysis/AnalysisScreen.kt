
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
import edu.neu.aijiaoxue.ui.evaluation.ErrorPage
import edu.neu.aijiaoxue.ui.evaluation.MessageCard
import edu.neu.aijiaoxue.ui.evaluation.ModulePage
import edu.neu.aijiaoxue.ui.evaluation.SectionCard
import edu.neu.aijiaoxue.ui.evidence.EvidencePanel

const val ANALYSIS_PAGE_TAG = "analysis_page"

@Composable
fun AnalysisScreen(
    viewModel: AnalysisViewModel,
    onBack: () -> Unit,
    onReview: (Long) -> Unit,
    onEvaluation: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()

    if (!state.loading && state.task == null) {
        ErrorPage("AI 课堂分析", state.error ?: "无权限访问", onBack, viewModel::reload)
        return
    }

    ModulePage("AI 课堂分析", onBack) { padding ->

        if (state.loading) {
            CircularProgressIndicator(
                modifier = Modifier
                    .padding(padding)
                    .padding(24.dp)
            )
            return@ModulePage
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .testTag(ANALYSIS_PAGE_TAG),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            state.task?.let { task ->
                item {
                    Text(
                        text = "任务 ${task.code}  ${task.supervisionDate} 第 ${task.period} 节",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }

            state.error?.let { error ->
                item {
                    MessageCard(error, error = true)
                }
            }

            if (state.task != null) {
                item {
                    Text("当前使用本地模拟分析，未接入真实转写或模型；结果必须人工核对。")
                    if (!state.canAnalyze) {
                        MessageCard("课堂采集结束后才能进行 AI 分析")
                    }

                    Button(
                        onClick = viewModel::analyze,
                        enabled = state.canAnalyze && !state.analyzing,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("analysis-generate")
                            .padding(top = 8.dp)
                    ) {
                        Text(
                            when {
                                state.analyzing -> "分析中…"
                                state.results.isEmpty() -> "生成模拟分析"
                                else -> "重新生成模拟分析"
                            }
                        )
                    }

                    if (state.results.isNotEmpty()) {
                        Text(
                            text = "重新分析会替换当前结果和审核状态，已发布的评价及计划来源快照保留。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            items(state.results, key = { it.id }) { result ->
                SectionCard(result.type.label) {
                    Text(result.content)

                    Text(
                        text = "模拟分析分值 ${result.confidence}（不代表校准后的可靠概率） · " +
                            if (result.verified) "已审核" else "待审核",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    EvidencePanel(
                        state.evidences[result.id].orEmpty()
                    )

                    OutlinedButton(
                        onClick = { onReview(result.id) },
                        enabled = !state.analyzing,
                        modifier = Modifier.fillMaxWidth().testTag("analysis-review-${result.type.name}")
                    ) {
                        Text(
                            if (result.verified) {
                                "查看审核记录"
                            } else {
                                "审核 AI 结果"
                            }
                        )
                    }
                }
            }
            if (state.canAnalyze) item {
                Button(onClick = onEvaluation, enabled = !state.analyzing,
                    modifier = Modifier.fillMaxWidth().testTag("analysis-enter-evaluation")) {
                    Text("进入人工评价")
                }
            }
        }
    }
}
