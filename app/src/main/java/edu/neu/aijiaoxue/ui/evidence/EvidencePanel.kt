package edu.neu.aijiaoxue.ui.evidence

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import edu.neu.aijiaoxue.data.entity.AiEvidence

/** 只接收授权Repository返回的本条分析证据；未关联原材料时不伪装成可追溯证据。 */
@Composable
fun EvidencePanel(evidences: List<AiEvidence>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("关联依据")
        if (evidences.isEmpty()) Text("未提供已关联的课堂证据，请结合原始课堂材料人工核对。")
        evidences.forEach { evidence ->
            Text(evidence.description)
            if (evidence.materialId == null && evidence.eventId == null) Text("仅有说明／时间信息，未绑定可访问的材料或事件。")
            evidence.startMs?.let { Text("课堂时间：${it / 1000}秒" + (evidence.endMs?.let { end -> "—${end / 1000}秒" } ?: "")) }
        }
    }
}
