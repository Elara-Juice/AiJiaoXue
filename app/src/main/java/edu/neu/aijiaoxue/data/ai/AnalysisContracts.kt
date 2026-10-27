package edu.neu.aijiaoxue.data.ai

import edu.neu.aijiaoxue.data.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

data class TimeRange(val startMs: Long, val endMs: Long) {
    init { require(startMs >= 0 && endMs > startMs) { "时间区间无效" } }
    fun json() = JSONObject().put("startMs", startMs).put("endMs", endMs)
}

data class SourceRef(
    val kind: EvidenceKind, val taskId: Long?, val rowId: Long, val revision: Int,
    val itemKey: String?, val contentHash: String, val quote: String? = null,
    val classAtMs: Long? = null, val mediaRange: TimeRange? = null,
    val classRanges: List<TimeRange> = emptyList(),
) {
    fun json() = JSONObject().put("kind", kind.name).put("taskId", taskId ?: JSONObject.NULL)
        .put("rowId", rowId).put("revision", revision).put("itemKey", itemKey ?: JSONObject.NULL)
        .put("contentHash", contentHash).put("quote", quote ?: JSONObject.NULL)
        .put("classAtMs", classAtMs ?: JSONObject.NULL).put("mediaRange", mediaRange?.json() ?: JSONObject.NULL)
        .put("classRanges", JSONArray(classRanges.map { it.json() }))
}

/** 固定排序的规范化JSON；哈希不依赖Map/JSONObject的遍历顺序。 */
object AnalysisJson {
    fun canonical(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") {
            JSONObject.quote(it) + ":" + canonical(value.get(it))
        }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        is Number -> value.toString().also { require(it !in setOf("NaN", "Infinity", "-Infinity")) }
        is Boolean -> value.toString()
        else -> error("不支持的JSON类型")
    }
    fun hash(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    fun hashJson(value: Any?): String = hash(canonical(value))
    fun objects(array: JSONArray?): List<JSONObject> = if (array == null) emptyList() else
        (0 until array.length()).map { array.getJSONObject(it) }
    fun refs(value: Any?): List<JSONObject> = when (value) {
        is JSONObject -> value.keys().asSequence().toList().flatMap { key ->
            if (key in setOf("evidenceRefs", "sourceRefs")) objects(value.optJSONArray(key)) else refs(value.opt(key))
        }
        is JSONArray -> (0 until value.length()).flatMap { refs(value.opt(it)) }
        else -> emptyList()
    }
    fun summary(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().toList().filterNot { it in setOf("evidenceRefs", "sourceRefs", "producer", "words", "id", "segmentId", "inputId") }
            .joinToString("\n") { key -> "${labels[key] ?: key}：${summary(value.opt(key))}" }
        is JSONArray -> (0 until value.length()).joinToString("\n") { summary(value.opt(it)) }
        null, JSONObject.NULL -> ""
        else -> labels[value.toString()] ?: extraLabels[value.toString()] ?: value.toString()
    }
    private val extraLabels = mapOf("CLOUD_ASR" to "云端转写", "CLOUD_MODEL" to "云端分析", "DETERMINISTIC_RULE" to "本地规则统计", "MANUAL" to "人工记录", "LEGACY" to "历史模拟来源", "FAILED" to "失败", "ABRUPT_TOPIC_CHANGE" to "主题突转候选", "NO_OBSERVED_LINK" to "未观察到衔接", "REPEATED_RESTART" to "重复起始候选", "UNMARKED_GAP" to "未标注空档", "DENSE_CONTENT" to "信息密集候选", "MISSING_KEY_POINT" to "知识点支撑不足候选", "POSSIBLE_MISMATCH" to "可能不匹配")
    private val labels = mapOf("topics" to "教学主题", "name" to "名称", "keywords" to "关键词", "alignments" to "大纲对齐", "coverage" to "覆盖状态", "reason" to "依据", "deviations" to "偏离候选", "statement" to "结论", "coverageScope" to "分析范围", "segments" to "教学环节", "stage" to "环节类型", "status" to "状态", "label" to "内容", "segmentIds" to "依据环节", "activities" to "课堂活动", "links" to "目标与活动对应", "supportLevel" to "支撑程度", "unsupportedGoals" to "待补充依据的目标", "state" to "状态", "transitions" to "衔接候选", "type" to "类型", "supports" to "材料支撑", "relation" to "关联", "concerns" to "需核验项目", "ruleOrReason" to "判定依据", "expressions" to "重复表达候选", "expression" to "表达", "count" to "次数", "scope" to "统计范围", "note" to "说明", "COVERED" to "观察到覆盖", "OBSERVED" to "已观察到", "POSSIBLY_MISSING" to "可能缺失，需人工核验", "INSUFFICIENT_INPUT" to "输入不足", "DIRECT" to "直接支撑", "PARTIAL" to "部分支撑", "SUPPORTED" to "有依据", "UNREADABLE" to "无法辨读", "NO_OBSERVED_ACTIVITY" to "未观察到对应活动", "SUPPORTS" to "支撑", "MISMATCH" to "可能不匹配", "limitations" to "限制", "BRIDGE_IN" to "导入", "OBJECTIVE" to "目标", "PRE_ASSESSMENT" to "前测", "PARTICIPATORY_LEARNING" to "参与式学习", "POST_ASSESSMENT" to "后测", "SUMMARY" to "总结")
    fun analysisType(operation: AnalysisOperation): AiAnalysisType = when (operation) {
        AnalysisOperation.CONTENT -> AiAnalysisType.CONTENT
        AnalysisOperation.SYLLABUS_ALIGNMENT -> AiAnalysisType.SYLLABUS_ALIGNMENT
        AnalysisOperation.BOPPPS -> AiAnalysisType.BOPPPS
        AnalysisOperation.GOAL_ACTIVITY -> AiAnalysisType.GOAL_ACTIVITY
        AnalysisOperation.TRANSITION -> AiAnalysisType.TEACHING_TRANSITION
        AnalysisOperation.MATERIAL_SUPPORT -> AiAnalysisType.MATERIAL_SUPPORT
        AnalysisOperation.EXPRESSION_STATS -> AiAnalysisType.VERBAL_REPETITION
        AnalysisOperation.INTERACTION_STATS -> AiAnalysisType.INTERACTION
        AnalysisOperation.PARTICIPATION -> AiAnalysisType.ENGAGEMENT
        else -> error("此操作不是课堂分析结论")
    }
}
