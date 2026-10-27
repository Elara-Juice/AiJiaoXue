package edu.neu.aijiaoxue.ui.analysis

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import edu.neu.aijiaoxue.data.ai.AnalysisJson
import edu.neu.aijiaoxue.data.ai.ManifestEvidence
import edu.neu.aijiaoxue.data.model.AnalysisOperation
import edu.neu.aijiaoxue.data.repository.StructuredPayloadValidation
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 只编辑结构字段；来源选择使用原始请求白名单，时间和计数由观测来源决定。 */
@Composable
fun StructuredReviewForm(payloadJson: String, manifestJson: String, operation: AnalysisOperation, enabled: Boolean, onChange: (String) -> Unit) {
    val payload = remember(payloadJson) { JSONObject(payloadJson) }
    val manifest = remember(manifestJson) { JSONObject(manifestJson) }
    val available = remember(manifestJson) { StructuredPayloadValidation.availableReferences(manifest) }
    val inputs = AnalysisJson.objects(manifest.getJSONArray("inputs"))
    fun ids(arrayKey: String) = inputs.filter { arrayKey != "segments" || it.getString("kind") in setOf("MANUAL_PHASE", "BOPPPS") }.flatMap { AnalysisJson.objects(it.getJSONObject("payload").optJSONArray(arrayKey)).mapNotNull { row -> row.optString("id").takeIf(String::isNotBlank) ?: row.optString("segmentId").takeIf(String::isNotBlank) } }.distinct()
    val choices = mapOf("objectiveId" to ids("objectives"), "knowledgePointId" to ids("knowledgePoints"), "imageBlockId" to ids("blocks"), "topicId" to ids("topics"), "fromSegmentId" to ids("segments"), "toSegmentId" to ids("segments"), "activityId" to AnalysisJson.objects(payload.optJSONArray("activities")).mapNotNull { it.optString("id").takeIf(String::isNotBlank) }, "segmentIds" to AnalysisJson.objects(payload.optJSONArray("segments")).mapNotNull { it.optString("id").takeIf(String::isNotBlank) })
    ObjectFields(payload, operation, available, choices, enabled) { onChange(it.toString()) }
    if (operation == AnalysisOperation.BOPPPS) {
        Text("教学结构时间边界", style = MaterialTheme.typography.titleMedium)
        Text("可在真实来源范围内缩短或拆分区间；不能跨过未采集的空档。修改阶段后请同步调整覆盖表。")
        AnalysisJson.objects(payload.optJSONArray("segments")).forEach { segment ->
            val segmentId = segment.getString("id")
            val sourceRanges = runCatching { ManifestEvidence.resolve(segment, manifest, 0).flatMap { evidence -> val ref = JSONObject(evidence.sourceRefJson!!); if (ref.optString("kind") == "TRANSCRIPT_SEGMENT" && ref.optString("mappingQuality") != "EXACT") emptyList() else AnalysisJson.objects(ref.optJSONArray("classRanges")) } }.getOrDefault(emptyList())
            fun updateRanges(ranges: JSONArray?) { val copy = JSONObject(payloadJson); val target = AnalysisJson.objects(copy.getJSONArray("segments")).first { it.getString("id") == segmentId }; if (ranges == null) target.remove("reviewedRanges") else target.put("reviewedRanges", ranges); onChange(copy.toString()) }
            Text(segment.optString("label"))
            Text("真实来源范围：" + sourceRanges.joinToString { "${it.getLong("startMs")}–${it.getLong("endMs")} 毫秒" }.ifBlank { "没有精确时间，不能裁剪" }, style = MaterialTheme.typography.bodySmall)
            val ranges = segment.optJSONArray("reviewedRanges")
            if (ranges != null) for (i in 0 until ranges.length()) {
                val range = ranges.getJSONObject(i)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (key in listOf("startMs", "endMs")) OutlinedTextField(range.opt(key).toString(), { text -> val copy = JSONArray(ranges.toString()); copy.getJSONObject(i).put(key, text.toLongOrNull() ?: text); updateRanges(copy) }, label = { Text(if (key == "startMs") "开始毫秒" else "结束毫秒") }, enabled = enabled, modifier = Modifier.weight(1f).testTag("review-range-$segmentId-$i-$key"))
                }
                if (enabled) TextButton(onClick = { val remaining = JSONArray().apply { for (j in 0 until ranges.length()) if (j != i) put(ranges.getJSONObject(j)) }; updateRanges(remaining.takeIf { it.length() > 0 }) }) { Text("移除此裁剪区间") }
            }
            if (enabled && sourceRanges.isNotEmpty()) Row {
                TextButton(onClick = { updateRanges(JSONArray(ranges?.toString() ?: "[]").put(JSONObject(sourceRanges.first().toString()))) }) { Text("添加裁剪区间") }
                if (ranges != null) TextButton(onClick = { updateRanges(null) }) { Text("恢复来源完整范围") }
            }
        }
    }
}

private fun fieldLabel(key: String): String = mapOf("topics" to "主题与知识点", "name" to "名称", "keywords" to "关键词", "alignments" to "大纲对齐", "coverage" to "覆盖情况", "reason" to "判断依据", "deviations" to "可能偏离", "statement" to "说明", "segments" to "教学结构段", "stage" to "BOPPPS阶段", "status" to "观察状态", "segmentIds" to "关联结构段", "label" to "名称", "activities" to "教学活动", "links" to "目标与活动关系", "supportLevel" to "支撑程度", "unsupportedGoals" to "未观察到活动的目标", "state" to "判断状态", "transitions" to "教学衔接", "type" to "分类", "supports" to "材料支撑关系", "relation" to "对应关系", "concerns" to "待核对问题", "ruleOrReason" to "规则或依据", "evidenceRefs" to "原始来源", "sourceRefs" to "活动来源", "objectiveId" to "教学目标", "knowledgePointId" to "知识点", "activityId" to "活动", "imageBlockId" to "图像文字块", "topicId" to "主题", "fromSegmentId" to "前一环节", "toSegmentId" to "后一环节", "expression" to "重复表达", "count" to "真实计数（只读）", "expressions" to "表达候选", "note" to "说明", "scope" to "观察范围")[key] ?: key

@Composable
private fun ObjectFields(value: JSONObject, operation: AnalysisOperation, references: List<JSONObject>, choices: Map<String, List<String>>, enabled: Boolean, onChange: (JSONObject) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        value.keys().asSequence().toList().filter { it != "reviewedRanges" }.forEach { key ->
            val child = value.opt(key)
            fun changed(replacement: Any?) { onChange(JSONObject(value.toString()).put(key, replacement ?: JSONObject.NULL)) }
            when (child) {
                is JSONObject -> { Text(fieldLabel(key), style = MaterialTheme.typography.titleSmall); ObjectFields(child, operation, references, choices, enabled && key != "coverageScope", ::changed) }
                is JSONArray -> ArrayFields(key, child, operation, references, choices, enabled, ::changed)
                is String -> {
                    val enums = StructuredPayloadValidation.enumOptions(key, operation)
                    val options = if (enums.isNotEmpty()) enums else choices[key].orEmpty()
                    if (enums.isNotEmpty() || key in choices) SelectField(fieldLabel(key), child, options, enabled) { changed(it) }
                    else OutlinedTextField(child, { changed(it) }, label = { Text(fieldLabel(key)) }, enabled = enabled && key !in setOf("id", "schemaVersion"), modifier = Modifier.fillMaxWidth().testTag("review-structured-$key"))
                }
                is Number -> Text("${fieldLabel(key)}：$child")
                is Boolean -> Row { Checkbox(child, { changed(it) }, enabled = enabled); Text(fieldLabel(key)) }
                else -> if (key in choices) SelectField(fieldLabel(key), "", listOf("") + choices[key].orEmpty(), enabled) { changed(it.takeIf(String::isNotBlank)) }
            }
        }
    }
}

@Composable
private fun SelectField(label: String, value: String, options: List<String>, enabled: Boolean, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box { OutlinedButton(onClick = { expanded = true }, enabled = enabled) { Text("$label：${if (value.isBlank()) "未选择" else AnalysisJson.summary(value)}") }; DropdownMenu(expanded, { expanded = false }) { options.distinct().forEach { option -> DropdownMenuItem(text = { Text(if (option.isBlank()) "不选择" else AnalysisJson.summary(option)) }, onClick = { onChange(option); expanded = false }) } } }
}

@Composable
private fun ArrayFields(key: String, array: JSONArray, operation: AnalysisOperation, references: List<JSONObject>, choices: Map<String, List<String>>, enabled: Boolean, onChange: (JSONArray) -> Unit) {
    Text(fieldLabel(key), style = MaterialTheme.typography.titleSmall)
    if (key in setOf("evidenceRefs", "sourceRefs")) {
        val selected = AnalysisJson.objects(array).map { AnalysisJson.canonical(it) }.toSet()
        AnalysisJson.objects(array).forEachIndexed { index, ref ->
            Text("${ref.optString("inputId")} / ${ref.optString("itemKey")}：${ref.optString("quote").take(180)}", style = MaterialTheme.typography.bodySmall)
            if (enabled) TextButton(onClick = { onChange(JSONArray().apply { for (i in 0 until array.length()) if (i != index) put(array.get(i)) }) }) { Text("移除此来源") }
        }
        var expanded by remember { mutableStateOf(false) }
        Box { TextButton(onClick = { expanded = true }, enabled = enabled) { Text("选择原请求中的来源") }; DropdownMenu(expanded, { expanded = false }) {
            references.forEach { ref -> val canonical = AnalysisJson.canonical(ref); val checked = canonical in selected
                DropdownMenuItem(text = { Text("${if (checked) "✓ " else ""}${ref.optString("itemKey")} ${ref.optString("quote").take(50)}") }, onClick = {
                    val remaining = AnalysisJson.objects(array).filterNot { AnalysisJson.canonical(it) == canonical }.toMutableList()
                    if (!checked) remaining += JSONObject(ref.toString())
                    onChange(JSONArray(remaining))
                })
            }
        } }
        return
    }
    for (i in 0 until array.length()) {
        val item = array.opt(i)
        fun changed(replacement: Any?) { onChange(JSONArray(array.toString()).put(i, replacement ?: JSONObject.NULL)) }
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (item) {
                is JSONObject -> ObjectFields(item, operation, references, choices, enabled && key != "expressions" && operation != AnalysisOperation.INTERACTION_STATS, ::changed)
                is String -> if (key in choices) SelectField(fieldLabel(key), item, choices[key].orEmpty(), enabled) { changed(it) } else OutlinedTextField(item, { changed(it) }, enabled = enabled, modifier = Modifier.fillMaxWidth())
                else -> Text(item.toString())
            }
            if (enabled && key != "coverage") TextButton(onClick = { onChange(JSONArray().apply { for (j in 0 until array.length()) if (j != i) put(array.get(j)) }) }) { Text("删除此项") }
        } }
    }
    if (enabled && key != "coverage" && key != "expressions" && operation != AnalysisOperation.INTERACTION_STATS) TextButton(onClick = {
        val value = newArrayItem(key)
        if (value != null) onChange(JSONArray(array.toString()).put(value))
    }, enabled = newArrayItem(key) != null) { Text("添加${fieldLabel(key)}") }
}

private fun newArrayItem(key: String): Any? {
    val id = "manual-${UUID.randomUUID()}"
    fun row(vararg pairs: Pair<String, Any?>) = JSONObject().apply { pairs.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) } }
    return when (key) {
        "topics" -> row("id" to id, "name" to "", "keywords" to JSONArray(), "evidenceRefs" to JSONArray())
        "segments" -> row("id" to id, "stage" to "PARTICIPATORY_LEARNING", "label" to "", "evidenceRefs" to JSONArray())
        "activities" -> row("id" to id, "label" to "", "sourceRefs" to JSONArray())
        "links" -> row("objectiveId" to "", "activityId" to "", "supportLevel" to "DIRECT", "reason" to "", "evidenceRefs" to JSONArray())
        "unsupportedGoals" -> row("objectiveId" to "", "state" to "NO_OBSERVED_ACTIVITY", "reason" to "")
        "alignments" -> row("knowledgePointId" to "", "coverage" to "INSUFFICIENT_INPUT", "evidenceRefs" to JSONArray(), "reason" to "")
        "deviations" -> row("id" to id, "statement" to "", "reason" to "", "evidenceRefs" to JSONArray())
        "transitions" -> row("fromSegmentId" to "", "toSegmentId" to "", "type" to "NO_OBSERVED_LINK", "statement" to "", "reason" to "", "evidenceRefs" to JSONArray())
        "supports" -> row("imageBlockId" to "", "topicId" to null, "knowledgePointId" to null, "relation" to "PARTIAL", "reason" to "", "evidenceRefs" to JSONArray())
        "concerns" -> row("type" to "POSSIBLE_MISMATCH", "statement" to "", "ruleOrReason" to "", "evidenceRefs" to JSONArray())
        "keywords", "segmentIds", "limitations" -> ""
        else -> null
    }
}
