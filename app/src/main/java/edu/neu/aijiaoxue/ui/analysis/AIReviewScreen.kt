package edu.neu.aijiaoxue.ui.analysis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import edu.neu.aijiaoxue.ui.evaluation.MessageCard
import edu.neu.aijiaoxue.ui.evaluation.ErrorPage
import edu.neu.aijiaoxue.ui.evaluation.ModulePage
import edu.neu.aijiaoxue.ui.evaluation.SectionCard
import edu.neu.aijiaoxue.ui.evaluation.timeText
import edu.neu.aijiaoxue.data.ai.AnalysisJson

/** US27：AI 结果人工审核。 */
@Composable
fun AIReviewScreen(viewModel: AIReviewViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    if (!state.loading && state.analysis == null) {
        ErrorPage("AI 结果审核", state.error ?: "分析不存在或无权限访问", onBack, viewModel::reload)
        return
    }
    ModulePage("AI 结果审核", onBack) { padding ->
        val analysis = state.analysis
        if (state.loading) {
            CircularProgressIndicator(Modifier.padding(padding).padding(24.dp))
            return@ModulePage
        }
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)
                .testTag("ai-review-page"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.error?.let { MessageCard(it, error = true) }
            if (analysis == null) return@Column
            // 以最近一次审核结果为初值，便于在上次修正的基础上继续改
            var corrected by rememberSaveable(analysis.id) {
                mutableStateOf(state.corrections.firstOrNull()?.correctedContent ?: analysis.content)
            }
            SectionCard("AI 原始结论（${analysis.type.label}）") {
                Text(analysis.content)
                Text(
                    "${AnalysisJson.summary(analysis.sourceMode.name)} · ${AnalysisJson.summary(analysis.resultState.name)} · ${if (analysis.verified) "已审核" else "待审核"}${if (!analysis.isCurrent) " · 已被新版本替代" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (analysis.schemaVersion == 1 && analysis.payloadJson != null) {
                var structured by rememberSaveable(analysis.id, state.corrections.firstOrNull()?.id) { mutableStateOf(state.corrections.firstOrNull()?.correctedPayloadJson ?: analysis.payloadJson) }
                val manifest = state.manifestJson
                var editingStructure by rememberSaveable(analysis.id) { mutableStateOf(false) }
                if (manifest == null) MessageCard("原始分析输入记录缺失，无法作为正式审核依据", error = true)
                else {
                    TextButton(onClick = { editingStructure = !editingStructure }) { Text(if (editingStructure) "收起修正表单" else "需要修正？展开逐项核对") }
                    if (editingStructure) StructuredReviewForm(requireNotNull(structured), manifest, analysis.operation, !state.saving && analysis.isCurrent) { structured = it }
                    Text("请先核对结论与证据；需要调整时再展开修正表单。", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { structured = analysis.payloadJson; viewModel.saveStructured(analysis.payloadJson) }, enabled = !state.saving && analysis.isCurrent, modifier = Modifier.weight(1f).testTag("review-accept")) { Text("确认原结构") }
                        Button(onClick = { viewModel.saveStructured(requireNotNull(structured)) }, enabled = !state.saving && analysis.isCurrent, modifier = Modifier.weight(1f).testTag("review-save")) { Text("保存结构修正") }
                    }
                }
            } else {
            OutlinedTextField(
                value = corrected,
                onValueChange = { corrected = it },
                label = { Text("修正后的结论") },
                minLines = 3,
                enabled = !state.saving,
                modifier = Modifier.fillMaxWidth().testTag("review-corrected-content"),
            )
            Text("保存会记录本次实际审核人；修改后的结论同样属于有效审核。历史未知审核人记录需重新核对。")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        corrected = analysis.content
                        viewModel.save(analysis.content)
                    },
                    enabled = !state.saving,
                    modifier = Modifier.weight(1f).testTag("review-accept"),
                ) { Text("确认 AI 结论") }
                Button(
                    onClick = { viewModel.save(corrected) },
                    enabled = !state.saving && corrected.isNotBlank(),
                    modifier = Modifier.weight(1f).testTag("review-save"),
                ) { Text("保存修正") }
            }
            }
            if (state.savedAt != null) MessageCard("审核结果已保存")
            SectionCard("审核记录") {
                if (state.corrections.isEmpty()) Text("暂无审核记录")
                state.corrections.forEach {
                    Text(
                        "${timeText(it.createdAt)}  ${when {
                            it.reviewerId == null -> "历史记录：审核人未知，不能作为正式依据"
                            it.accepted -> "确认原结论"
                            else -> "已修正"
                        }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    it.reviewerId?.let { reviewer -> Text("审核人编号：$reviewer") }
                    if (!it.accepted) Text(it.correctedContent)
                }
            }
        }
    }
}
