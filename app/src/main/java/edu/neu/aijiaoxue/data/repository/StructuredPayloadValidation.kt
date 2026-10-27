package edu.neu.aijiaoxue.data.repository

import edu.neu.aijiaoxue.data.ai.AnalysisJson
import edu.neu.aijiaoxue.data.ai.ManifestEvidence
import edu.neu.aijiaoxue.data.model.AnalysisOperation
import org.json.JSONArray
import org.json.JSONObject

/** 与服务端结构契约一致；来源永远由原请求白名单解析，人工编辑不授予新的数据权限。 */
object StructuredPayloadValidation {
    val stages = listOf("BRIDGE_IN", "OBJECTIVE", "PRE_ASSESSMENT", "PARTICIPATORY_LEARNING", "POST_ASSESSMENT", "SUMMARY")
    fun enumOptions(key: String, operation: AnalysisOperation): List<String> = when (key) {
        "stage" -> stages
        "coverage" -> listOf("COVERED", "POSSIBLY_MISSING", "INSUFFICIENT_INPUT")
        "status" -> listOf("OBSERVED", "POSSIBLY_MISSING", "INSUFFICIENT_INPUT")
        "supportLevel" -> listOf("DIRECT", "PARTIAL")
        "state" -> listOf("NO_OBSERVED_ACTIVITY", "INSUFFICIENT_INPUT")
        "relation" -> listOf("SUPPORTS", "PARTIAL", "MISMATCH")
        "type" -> if (operation == AnalysisOperation.TRANSITION) listOf("ABRUPT_TOPIC_CHANGE", "NO_OBSERVED_LINK", "REPEATED_RESTART", "UNMARKED_GAP") else if (operation == AnalysisOperation.MATERIAL_SUPPORT) listOf("DENSE_CONTENT", "MISSING_KEY_POINT", "POSSIBLE_MISMATCH") else emptyList()
        else -> emptyList()
    }

    fun validate(operation: AnalysisOperation, original: JSONObject, edited: JSONObject, manifest: JSONObject, taskId: Long) {
        require(original.keys().asSequence().toSet() == edited.keys().asSequence().toSet()) { "不能增删结果的顶层字段" }
        fun walk(key: String, value: Any?) {
            when (value) {
                is JSONObject -> value.keys().forEach { child -> walk(child, value.opt(child)) }
                is JSONArray -> {
                    if (key in setOf("evidenceRefs", "sourceRefs")) {
                        AnalysisJson.objects(value).forEach { require(it.keys().asSequence().all { k -> k in setOf("inputId", "itemKey", "quote") }) { "引用只能选择原输入记录" } }
                    } else {
                        val objects = (0 until value.length()).map { value.opt(it) }.filterIsInstance<JSONObject>()
                        val ids = objects.mapNotNull { it.optString("id").takeIf(String::isNotBlank) }
                        require(ids.distinct().size == ids.size) { "同类结果编号不能重复" }
                        for (i in 0 until value.length()) walk(key, value.opt(i))
                    }
                }
                is String -> {
                    val options = enumOptions(key, operation)
                    require(options.isEmpty() || value in options) { "$key 的分类无效" }
                    if (key in setOf("id", "name", "label", "statement", "reason", "ruleOrReason", "expression")) require(value.isNotBlank()) { "$key 不能为空" }
                }
                is Number -> require(value.toDouble().isFinite()) { "结果不能包含非有限数字" }
            }
        }
        walk("", edited)
        ManifestEvidence.resolve(edited, manifest, taskId)
        val allInputs = AnalysisJson.objects(manifest.getJSONArray("inputs"))
        fun inputIds(arrayKey: String, kinds: Set<String>? = null): Set<String> = allInputs.filter { kinds == null || it.getString("kind") in kinds }.flatMap { input -> AnalysisJson.objects(input.getJSONObject("payload").optJSONArray(arrayKey)).mapNotNull { row -> row.optString("id").takeIf(String::isNotBlank) ?: row.optString("segmentId").takeIf(String::isNotBlank) } }.toSet()
        fun rows(key: String) = AnalysisJson.objects(edited.optJSONArray(key))
        fun requireRefs(row: JSONObject, key: String = "evidenceRefs", minimum: Int = 1) { require((row.optJSONArray(key)?.length() ?: 0) >= minimum) { "结论至少选择 $minimum 个真实来源" } }
        val hasTranscript = allInputs.any { it.getString("kind") in setOf("TRANSCRIPT", "TRANSCRIPT_SEGMENT") && (it.getJSONObject("payload").optJSONArray("segments")?.length() ?: 0) > 0 }
        val context = allInputs.firstOrNull { it.getString("kind") == "CONTEXT" }?.getJSONObject("payload")?.optJSONObject("context")
        val duration = context?.optLong("classDurationMs", -1) ?: -1
        val spans = AnalysisJson.objects(context?.optJSONArray("recordingSpans")).filter { it.optString("mappingQuality") in setOf("EXACT", "ESTIMATED") && it.optLong("classStartMs", -1) >= 0 && it.optLong("classEndMs", -1) > it.optLong("classStartMs") && it.optLong("classEndMs") <= duration }.sortedBy { it.getLong("classStartMs") }
        var covered = 0L; var previousEnd = 0L
        spans.forEach { covered += maxOf(0, it.getLong("classEndMs") - maxOf(previousEnd, it.getLong("classStartMs"))); previousEnd = maxOf(previousEnd, it.getLong("classEndMs")) }
        val sufficientCoverage = duration > 0 && context?.optBoolean("allAudioTranscribed") == true && context.optJSONArray("missingAudioIds")?.length() == 0 && hasTranscript && covered.toDouble() / duration >= 0.9
        fun hasTranscriptRef(row: JSONObject): Boolean = AnalysisJson.objects(row.optJSONArray("evidenceRefs")).any { ref -> allInputs.any { it.getString("inputId") == ref.getString("inputId") && it.getString("kind") in setOf("TRANSCRIPT", "TRANSCRIPT_SEGMENT") } }
        when (operation) {
            AnalysisOperation.CONTENT -> rows("topics").forEach { requireRefs(it); require(hasTranscriptRef(it)) { "主题须引用实际课堂转写" } }
            AnalysisOperation.SYLLABUS_ALIGNMENT -> {
                val alignments = rows("alignments")
                require(AnalysisJson.canonical(original.optJSONObject("coverageScope")) == AnalysisJson.canonical(edited.optJSONObject("coverageScope"))) { "输入覆盖范围不可手工改写，请重新分析" }
                if (hasTranscript || alignments.isNotEmpty()) require(alignments.map { it.getString("knowledgePointId") }.toSet() == inputIds("knowledgePoints") && alignments.map { it.getString("knowledgePointId") }.distinct().size == alignments.size) { "请逐项说明所有选定知识点" }
                alignments.forEach { require(it.getString("knowledgePointId") in inputIds("knowledgePoints")) { "知识点不在本次范围" }; if (it.getString("coverage") == "COVERED") { requireRefs(it); require(hasTranscriptRef(it)) { "已覆盖判断必须引用实际转写" } }; if (it.getString("coverage") == "POSSIBLY_MISSING") require(sufficientCoverage) { "课堂采集覆盖不足，请使用输入不足并说明限制" } }
                rows("deviations").forEach { requireRefs(it) }
            }
            AnalysisOperation.BOPPPS -> {
                val coverage = rows("coverage"); val segments = rows("segments")
                require(coverage.size == 6 && coverage.map { it.getString("stage") }.toSet() == stages.toSet()) { "BOPPPS覆盖表须保留六个不同阶段" }
                val ids = segments.map { it.getString("id") }.toSet()
                coverage.forEach { row -> row.getJSONArray("segmentIds").let { a ->
                    require((row.getString("status") == "OBSERVED") == (a.length() > 0)) { "观察状态与关联结构段不一致" }
                    for (i in 0 until a.length()) require(a.getString(i) in ids && segments.any { it.getString("id") == a.getString(i) && it.getString("stage") == row.getString("stage") }) { "调整阶段后请同步更新覆盖表的关联结构段" }
                }; if (row.getString("status") == "POSSIBLY_MISSING") require(sufficientCoverage) { "采集覆盖不足，不能确认环节缺失" } }
                segments.forEach { segment ->
                    requireRefs(segment)
                    segment.optJSONArray("reviewedRanges")?.let { clips ->
                        require(clips.length() > 0) { "裁剪区间不能为空" }
                        val sourceRanges = ManifestEvidence.resolve(segment, manifest, taskId).flatMap { evidence -> val ref = JSONObject(evidence.sourceRefJson!!); if (ref.optString("kind") == "TRANSCRIPT_SEGMENT" && ref.optString("mappingQuality") != "EXACT") emptyList() else AnalysisJson.objects(ref.optJSONArray("classRanges")) }.map { it.getLong("startMs") to it.getLong("endMs") }.sortedBy { it.first }
                        val merged = mutableListOf<Pair<Long, Long>>()
                        sourceRanges.forEach { range -> if (merged.isNotEmpty() && range.first <= merged.last().second) merged[merged.lastIndex] = merged.last().first to maxOf(merged.last().second, range.second) else merged += range }
                        val parsed = AnalysisJson.objects(clips).map { range ->
                            require(range.opt("startMs") is Int || range.opt("startMs") is Long) { "裁剪开始时间必须为整数毫秒" }; require(range.opt("endMs") is Int || range.opt("endMs") is Long) { "裁剪结束时间必须为整数毫秒" }
                            val start = range.getLong("startMs"); val end = range.getLong("endMs")
                            require(start >= 0 && end > start && merged.sumOf { maxOf(0L, minOf(end, it.second) - maxOf(start, it.first)) } == end - start) { "人工时间边界超出真实来源，或跨越未采集空档" }
                            start to end
                        }.sortedBy { it.first }
                        require(parsed.zipWithNext().all { (a, b) -> a.second <= b.first }) { "人工裁剪区间不能重叠" }
                    }
                }
            }
            AnalysisOperation.GOAL_ACTIVITY -> {
                val ids = rows("activities").map { it.getString("id") }.toSet()
                rows("activities").forEach { requireRefs(it, "sourceRefs") }
                rows("links").forEach { require(it.getString("objectiveId") in inputIds("objectives") && it.getString("activityId") in ids) { "目标或活动不存在" }; requireRefs(it) }
                val linked = rows("links").map { it.getString("objectiveId") }.toSet()
                val unsupported = rows("unsupportedGoals").map { it.getString("objectiveId") }
                require(unsupported.distinct().size == unsupported.size && unsupported.none { it in linked }) { "同一目标不能既有关联又声明无活动" }
                if (hasTranscript || linked.isNotEmpty() || unsupported.isNotEmpty()) require(linked + unsupported == inputIds("objectives")) { "请逐项说明所有选定教学目标" }
                rows("unsupportedGoals").forEach { require(it.getString("objectiveId") in inputIds("objectives")) { "目标不在当前课堂范围" }; if (it.getString("state") == "NO_OBSERVED_ACTIVITY") require(sufficientCoverage) { "输入覆盖不足，请选择输入不足" } }
            }
            AnalysisOperation.TRANSITION -> {
                val ids = inputIds("segments", setOf("MANUAL_PHASE", "BOPPPS"))
                rows("transitions").forEach { require(it.getString("fromSegmentId") in ids && it.getString("toSegmentId") in ids && it.getString("fromSegmentId") != it.getString("toSegmentId")) { "衔接前后段无效" }; requireRefs(it) }
            }
            AnalysisOperation.MATERIAL_SUPPORT -> {
                rows("supports").forEach { row ->
                    require(row.getString("imageBlockId") in inputIds("blocks")) { "图像块不存在" }
                    val topic = row.optString("topicId").takeIf { it.isNotBlank() && it != "null" }; val point = row.optString("knowledgePointId").takeIf { it.isNotBlank() && it != "null" }
                    require((topic == null) != (point == null)) { "请选择知识点或主题中的一种" }
                    require(topic == null || topic in inputIds("topics")) { "主题不存在" }; require(point == null || point in inputIds("knowledgePoints")) { "知识点不存在" }; requireRefs(row, minimum = 2)
                }
                rows("concerns").forEach { requireRefs(it) }
            }
            AnalysisOperation.EXPRESSION_STATS -> {
                // 次数属于确定性计算，审核可校正文案和判断，不能手改观测次数。
                val old = AnalysisJson.objects(original.optJSONArray("expressions")).associateBy { it.getString("expression") }
                rows("expressions").forEach { require(AnalysisJson.canonical(it) == old[it.getString("expression")]?.let(AnalysisJson::canonical)) { "表达、次数、位置及来源是原始观测，请重新计算；可删除候选或修改说明" }; requireRefs(it) }
            }
            AnalysisOperation.INTERACTION_STATS -> {
                require(AnalysisJson.canonical(original.opt("每分钟事件数")) == AnalysisJson.canonical(edited.opt("每分钟事件数"))) { "互动密度必须由记录计算" }
                val old = AnalysisJson.objects(original.optJSONArray("activities")).associateBy { it.getString("label") }
                rows("activities").forEach { require(it.getLong("count") == old[it.getString("label")]?.getLong("count")) { "互动次数须重新计算，不能手工改写" }; requireRefs(it) }
            }
            else -> Unit
        }
    }

    fun availableReferences(manifest: JSONObject): List<JSONObject> = AnalysisJson.objects(manifest.getJSONArray("inputs")).filter { it.has("sourceRef") }.flatMap { input ->
        val found = mutableListOf<JSONObject>()
        fun walk(value: Any?) {
            when (value) {
                is JSONObject -> {
                    val key = value.optString("id").takeIf(String::isNotBlank) ?: value.optString("segmentId").takeIf(String::isNotBlank)
                    if (key != null) {
                        val quote = listOf("text", "name", "label", "statement").firstNotNullOfOrNull { value.optString(it).takeIf { s -> s.isNotBlank() && s != "null" } }
                        found += JSONObject().put("inputId", input.getString("inputId")).put("itemKey", key).put("quote", quote ?: JSONObject.NULL)
                    }
                    value.keys().forEach { walk(value.opt(it)) }
                }
                is JSONArray -> for (i in 0 until value.length()) walk(value.opt(i))
            }
        }
        walk(input.getJSONObject("payload"))
        if (found.isEmpty()) found += JSONObject().put("inputId", input.getString("inputId")).put("itemKey", JSONObject.NULL).put("quote", JSONObject.NULL)
        found
    }.distinctBy { AnalysisJson.canonical(it) }
}
