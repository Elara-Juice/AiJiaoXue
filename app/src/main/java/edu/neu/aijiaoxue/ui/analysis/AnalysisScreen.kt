package edu.neu.aijiaoxue.ui.analysis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import edu.neu.aijiaoxue.ui.evaluation.ErrorPage
import edu.neu.aijiaoxue.ui.evaluation.ModulePage
import edu.neu.aijiaoxue.ui.evaluation.SectionCard
import edu.neu.aijiaoxue.ui.evidence.EvidencePanel

@Composable
fun AnalysisScreen(viewModel: AnalysisViewModel, onBack: () -> Unit, onReview: (Long) -> Unit, onEvaluation: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    if (!state.loading && !state.authorized) {
        ErrorPage("课堂分析", state.error ?: "无权限访问", onBack, viewModel::reload)
        return
    }
    ModulePage("课堂分析与审核", onBack) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("当前为模拟分析，未接入真实转写或模型；结果仅供流程联调，必须人工核对。")
                    if (state.loading) CircularProgressIndicator()
                    if (!state.loading && !state.canAnalyze) Text("请先结束课堂采集。")
                    state.error?.let { Text(it) }
                    Button(onClick = viewModel::analyze, enabled = state.canAnalyze && !state.analyzing) {
                        Text(if (state.analyzing) "分析中…" else if (state.results.isEmpty()) "生成模拟分析" else "重新生成模拟分析")
                    }
                    if (state.results.isNotEmpty()) Text("重新生成会替换当前分析和审核记录，已发布的评价及计划来源快照保留。")
                }
            }
            items(state.results, key = { it.id }) { result ->
                SectionCard(result.type) {
                    Text(result.content)
                    Text(if (result.verified) "已审核" else "待人工审核")
                    Text("模拟分析分值：${result.confidence}（不代表校准后的可靠概率）")
                    EvidencePanel(state.evidences.filter { it.analysisId == result.id })
                    OutlinedButton(onClick = { onReview(result.id) }, enabled = !state.analyzing) { Text("审核此条结果") }
                }
            }
            if (state.canAnalyze) item {
                Button(onClick = onEvaluation, modifier = Modifier.fillMaxWidth()) { Text("进入人工评价") }
            }
        }
    }
}
