package edu.neu.aijiaoxue.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import edu.neu.aijiaoxue.data.analysis.AnomalyInterval
import edu.neu.aijiaoxue.data.analysis.ParticipationFinding
import edu.neu.aijiaoxue.ui.evaluation.MessageCard
import edu.neu.aijiaoxue.ui.evaluation.ModulePage
import edu.neu.aijiaoxue.ui.evaluation.SectionCard

/**
 * Sprint 3（李奕萱）US24 多源参与、US25 参与异常环节。
 * 每条结论都标注来源、时间范围和依据；缺少已审核的环节结论时不编造环节名称。
 */
@Composable
fun ParticipationScreen(model: ParticipationViewModel, onBack: () -> Unit) {
    val state by model.uiState.collectAsState()
    val insight = state.insight

    ModulePage("参与分析与异常定位", onBack) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.error?.let { MessageCard(it, error = true) }
            if (state.loading) {
                Text("加载中…")
                return@Column
            }
            if (insight == null) {
                Text("暂无分析结果")
                return@Column
            }

            SectionCard("有效输入") {
                Text("有数据的来源：${insight.availableSourceKinds.joinToString { it.label }.ifEmpty { "无" }}")
                insight.insufficientNote?.let { MessageCard(it) }
            }

            SectionCard("多源参与结论（US24）") {
                if (insight.findings.isEmpty()) {
                    Text("暂无可形成的多源结论")
                } else {
                    insight.findings.forEach { FindingCard(it) }
                }
            }

            SectionCard("参与异常区间与环节定位（US25）") {
                if (insight.anomalies.isEmpty()) {
                    Text("未发现参与异常区间")
                } else {
                    insight.anomalies.forEach { AnomalyCard(it) }
                }
            }
        }
    }
}

@Composable
private fun FindingCard(finding: ParticipationFinding) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(finding.title, style = MaterialTheme.typography.titleSmall)
        Text(finding.detail, style = MaterialTheme.typography.bodySmall)
        Text("来源：${finding.sources.joinToString { "${it.kind.label}（${it.recordCount} 条）" }}",
            style = MaterialTheme.typography.bodySmall)
        val range = if (finding.startOffsetMs == null) "时间范围：未记录" else
            "时间范围：${offsetText(finding.startOffsetMs)}—${offsetText(finding.endOffsetMs)}"
        Text(range, style = MaterialTheme.typography.bodySmall)
        Text("依据：${finding.basis}", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun AnomalyCard(anomaly: AnomalyInterval) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(anomaly.type.label, style = MaterialTheme.typography.titleSmall)
        Text("区间：${offsetText(anomaly.startOffsetMs)}—${offsetText(anomaly.endOffsetMs)}",
            style = MaterialTheme.typography.bodySmall)
        Text(anomaly.description, style = MaterialTheme.typography.bodySmall)
        Text("依据：${anomaly.evidence}", style = MaterialTheme.typography.bodySmall)
        if (anomaly.phaseLabel != null) {
            Text("所在教学环节：${anomaly.phaseLabel}（${anomaly.phaseBasis}）", style = MaterialTheme.typography.bodySmall)
        } else {
            Text("所在教学环节：缺少已审核的教学环节分析，无法定位", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
        }
    }
}

/** 课堂内相对时间（毫秒）显示为 分:秒。 */
private fun offsetText(offsetMs: Long?): String {
    if (offsetMs == null) return "未记录"
    val totalSeconds = offsetMs / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
