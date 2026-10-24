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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import edu.neu.aijiaoxue.ui.evaluation.ModulePage
import edu.neu.aijiaoxue.ui.evaluation.SectionCard

@Composable
fun AIReviewScreen(analysisId: Long, viewModel: AIReviewViewModel, onBack: () -> Unit = {}) {
    val state by viewModel.uiState.collectAsState()
    var correctedText by remember(analysisId) { mutableStateOf("") }
    LaunchedEffect(analysisId) { viewModel.load(analysisId) }
    LaunchedEffect(state.analysis?.id) {
        if (state.analysis != null) correctedText = state.corrections.firstOrNull()?.correctedContent ?: state.analysis!!.content
    }
    ModulePage("AI结果人工审核", onBack) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                SectionCard("原分析") {
                    if (state.loading) CircularProgressIndicator()
                    state.analysis?.let { Text(it.content) }
                    state.error?.let { Text(it) }
                    if (state.saved) Text("审核结果已保存，评价参考可读取此修正。")
                }
            }
            if (state.analysis != null) item {
                SectionCard("人工核对与修正") {
                    OutlinedTextField(value = correctedText, onValueChange = { correctedText = it },
                        label = { Text("修正后的结论") }, enabled = !state.saving, modifier = Modifier.fillMaxWidth())
                    Text("空白表示确认原结论；保存会记录本次实际审核人。历史无审核人记录需重新核对。")
                    Button(onClick = { viewModel.save(analysisId, state.analysis!!.content, correctedText, false) },
                        enabled = !state.saving && !state.loading) { Text(if (state.saving) "保存中…" else "保存审核结果") }
                }
            }
            items(state.corrections, key = { it.id }) { correction ->
                SectionCard(if (correction.reviewerId == null) "历史记录：审核人未知，不能作为正式依据"
                    else if (correction.accepted) "人工确认原结论" else "人工修正") {
                    Text(correction.correctedContent)
                    correction.reviewerId?.let { Text("审核人编号：$it") }
                }
            }
        }
    }
}
