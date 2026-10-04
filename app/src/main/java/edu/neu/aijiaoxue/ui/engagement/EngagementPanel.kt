package edu.neu.aijiaoxue.ui.engagement

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import edu.neu.aijiaoxue.data.entity.EngagementRecord
import edu.neu.aijiaoxue.data.model.EngagementRules
import edu.neu.aijiaoxue.data.model.EngagementType
import edu.neu.aijiaoxue.ui.capture.formatDuration
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** US13 对话框填好的内容，写库前由 [EngagementRules] 再校验一次。 */
data class EngagementInput(
    val type: EngagementType,
    val presentCount: Int?,
    val engagedCount: Int?,
    val engagedPercent: Int?,
    val description: String?,
)

/**
 * US13 采集页上的参与状态卡片：“记录参与状态”入口和本节课已记录的列表。
 * 只有采集中可以新增（前置条件：任务处于采集中），结束后只看列表。
 */
@Composable
fun EngagementCard(records: List<EngagementRecord>, enabled: Boolean, onRecord: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row {
                Text("学生参与状态", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    "共 ${records.size} 条",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalButton(
                onClick = onRecord,
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) { Text("记录参与状态") }
            if (records.isEmpty()) {
                Text(
                    "暂无参与状态记录",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            records.forEachIndexed { index, record ->
                if (index > 0) HorizontalDivider()
                EngagementRow(record)
            }
        }
    }
}

@Composable
private fun EngagementRow(record: EngagementRecord) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(record.type.label, style = MaterialTheme.typography.bodyLarge)
        Text(
            engagementTimeLabel(record),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        engagementCountLabel(record)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        record.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 3) }
    }
}

/** “10:15:30 · 课堂第 12:03”，与互动记录同一格式。 */
fun engagementTimeLabel(record: EngagementRecord): String {
    val time = SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(record.recordedAt))
    return "$time · 课堂第 ${formatDuration(record.offsetMs)}"
}

/** “实到 40 人 · 参与 35 人”；人数和比例都没填时返回 null。 */
fun engagementCountLabel(record: EngagementRecord): String? {
    val parts = listOfNotNull(
        record.presentCount?.let { "实到 $it 人" },
        record.engagedCount?.let { "参与 $it 人" } ?: record.engagedPercent?.let { "参与 $it%" },
    )
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/**
 * US13 主流程 2：选择状态类型，填写实到人数、参与人数或比例、文字描述。
 * 业务规则 2：参与人数和比例二选一，用分段按钮切换，只显示一个输入框；
 * 异常处理：数值越界时提示并阻止保存，对话框不关闭、已填内容不丢。
 */
@Composable
fun EngagementDialog(onSave: (EngagementInput) -> Unit, onDismiss: () -> Unit) {
    var type by rememberSaveable { mutableStateOf(EngagementType.LISTENING) }
    var present by rememberSaveable { mutableStateOf("") }
    var byPercent by rememberSaveable { mutableStateOf(false) }
    var engaged by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("记录参与状态") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("状态类型", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EngagementType.entries.forEach { t ->
                        FilterChip(selected = type == t, onClick = { type = t }, label = { Text(t.label) })
                    }
                }
                NumberField("实到人数（选填）", present) { present = it; error = null }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(false to "按人数", true to "按比例").forEachIndexed { i, (percent, label) ->
                        SegmentedButton(
                            selected = byPercent == percent,
                            onClick = { byPercent = percent; error = null },
                            shape = SegmentedButtonDefaults.itemShape(i, 2),
                        ) { Text(label) }
                    }
                }
                NumberField(if (byPercent) "参与比例 %（选填）" else "参与人数（选填）", engaged) { engaged = it; error = null }
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("文字描述（选填）") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val presentCount = present.toCountOrNull()
                val engagedValue = engaged.toCountOrNull()
                error = when {
                    presentCount == INVALID || engagedValue == INVALID -> "人数和比例只能填写整数"
                    else -> EngagementRules.validate(
                        presentCount,
                        engagedValue.takeUnless { byPercent },
                        engagedValue.takeIf { byPercent },
                    )
                }
                if (error == null) {
                    onSave(
                        EngagementInput(
                            type = type,
                            presentCount = presentCount,
                            engagedCount = engagedValue.takeUnless { byPercent },
                            engagedPercent = engagedValue.takeIf { byPercent },
                            description = description.trim().ifEmpty { null },
                        ),
                    )
                }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** 空白为 null（未填），无法解析为整数时返回 [INVALID]，交给保存时提示。 */
private fun String.toCountOrNull(): Int? = trim().takeIf { it.isNotEmpty() }?.let { it.toIntOrNull() ?: INVALID }

private const val INVALID = Int.MIN_VALUE
