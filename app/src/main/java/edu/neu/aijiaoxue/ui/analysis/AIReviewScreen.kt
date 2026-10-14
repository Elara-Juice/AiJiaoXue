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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import edu.neu.aijiaoxue.ui.evaluation.MessageCard
import edu.neu.aijiaoxue.ui.evaluation.ModulePage
import edu.neu.aijiaoxue.ui.evaluation.SectionCard
import edu.neu.aijiaoxue.ui.evaluation.timeText

/** US27：AI 结果人工审核。 */
@Composable
fun AIReviewScreen(viewModel: AIReviewViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    ModulePage("AI 结果审核", onBack) { padding ->
        val analysis = state.analysis
        if (state.loading) {
            CircularProgressIndicator(Modifier.padding(padding).padding(24.dp))
            return@ModulePage
        }
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
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
                    "可信度 ${(analysis.confidence * 100).toInt()}%  ·  ${if (analysis.verified) "已审核" else "待审核"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedTextField(
                value = corrected,
                onValueChange = { corrected = it },
                label = { Text("修正后的结论") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        corrected = analysis.content
                        viewModel.save(analysis.content)
                    },
                    enabled = !state.saving,
                    modifier = Modifier.weight(1f),
                ) { Text("确认 AI 结论") }
                Button(
                    onClick = { viewModel.save(corrected) },
                    enabled = !state.saving && corrected.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) { Text("保存修正") }
            }
            if (state.savedAt != null) MessageCard("审核结果已保存")
            SectionCard("审核记录") {
                if (state.corrections.isEmpty()) Text("暂无审核记录")
                state.corrections.forEach {
                    Text(
                        "${timeText(it.createdAt)}  ${if (it.accepted) "确认原结论" else "已修正"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!it.accepted) Text(it.correctedContent)
                }
            }
        }
    }
}
