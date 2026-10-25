package edu.neu.aijiaoxue.ui.evidence

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import edu.neu.aijiaoxue.data.entity.AiEvidence
import edu.neu.aijiaoxue.ui.capture.formatDuration

/** US26：展示 AI 结论对应的课堂证据，时间按课堂开始后的偏移显示。 */
@Composable
fun EvidencePanel(evidences: List<AiEvidence>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("分析依据（${evidences.size}）", style = MaterialTheme.typography.labelLarge)
        if (evidences.isEmpty()) Text("暂无可追溯的课堂记录", style = MaterialTheme.typography.bodySmall)
        evidences.forEach { evidence ->
            val time = evidence.startMs?.let { "[${formatDuration(it)}] " } ?: ""
            val source = when {
                evidence.materialId != null -> "材料"
                evidence.eventId != null -> "互动事件"
                else -> "说明或时间信息（未绑定可访问的材料／事件）"
            }
            Text("$time$source · ${evidence.description}", style = MaterialTheme.typography.bodySmall)
        }
    }
}
