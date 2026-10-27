package edu.neu.aijiaoxue.ui.evaluation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import edu.neu.aijiaoxue.data.ai.AnalysisJson
import edu.neu.aijiaoxue.data.entity.*
import edu.neu.aijiaoxue.data.model.AiAnalysisType
import org.json.JSONObject

@Composable
fun ReviewedAnalysisSection(rows: List<ReviewedAnalysisSnapshot>, isPublished: Boolean = false, publication: ReviewPublication? = null, facts: List<PublishedFactSnapshot> = emptyList(), evidence: List<PublishedEvidenceSnapshot> = emptyList(), onEvidence: ((Long) -> Unit)? = null, showOriginal: Boolean = true) {
    SectionCard(if (isPublished) "正式审核分析与人工事实" else "已审核分析参考") {
        Text("分析供人工判断参考，不自动替代评分或问题确认。")
        if (rows.isEmpty()) Text(when { !isPublished -> "暂无可用的已审核分析，可继续完成人工评价。"; publication == null -> "历史记录未归档分析依据。"; else -> "发布时没有已审核分析。" })
        rows.forEach { row -> Column(Modifier.fillMaxWidth().testTag("evaluation-reviewed-${row.analysisId}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HorizontalDivider()
            Text(runCatching { AiAnalysisType.valueOf(row.type).label }.getOrDefault(row.type), style = MaterialTheme.typography.titleSmall)
            if (showOriginal) ReadField("分析原文", row.originalContent)
            ReadField("审核后有效内容", row.effectivePayloadJson?.let { runCatching { AnalysisJson.summary(JSONObject(it)) }.getOrNull() } ?: row.effectiveContent)
            ReadField("证据摘要", row.evidenceSummary)
            Text("审核时间：${timeText(row.reviewedAt)} · 版本：${row.versionKey}", style = MaterialTheme.typography.bodySmall)
            if (onEvidence != null) evidence.filter { it.analysisSnapshotId == row.id }.forEach { ref -> TextButton(onClick = { onEvidence(ref.id) }) { Text("查看来源：${ref.excerpt.take(45)}") } }
        } }
        if (facts.isNotEmpty()) Text("人工补充事实", style = MaterialTheme.typography.titleSmall)
        facts.forEach { fact ->
            Text(fact.description)
            Text("发生时间：${fact.classAtMs?.let { "课堂 ${it / 1000} 秒" } ?: fact.classStartMs?.let { "课堂 ${it / 1000}–${fact.classEndMs?.div(1000)} 秒" } ?: "见原始来源"}；补录时间：${timeText(fact.recordedAt)}", style = MaterialTheme.typography.bodySmall)
            if (onEvidence != null) evidence.filter { it.factSnapshotId == fact.id }.forEach { ref -> TextButton(onClick = { onEvidence(ref.id) }) { Text("查看事实来源") } }
        }
        publication?.let { Text("发布人编号：${it.supervisorId} · ${timeText(it.publishedAt)}", style = MaterialTheme.typography.bodySmall) }
    }
}
