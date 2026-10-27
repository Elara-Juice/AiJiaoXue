package edu.neu.aijiaoxue.data.repository

import edu.neu.aijiaoxue.data.model.ActivityKind
import edu.neu.aijiaoxue.data.model.TimeMappingQuality
import java.security.MessageDigest

data class PhaseSegment(val id: String, val kind: ActivityKind, val label: String, val startMs: Long, val endMs: Long, val note: String = "")
data class SourceTimeRange(val startMs: Long, val endMs: Long)
data class RecordedTimeSpan(val mediaStartMs: Long, val mediaEndMs: Long, val classStartMs: Long, val classEndMs: Long, val quality: TimeMappingQuality = TimeMappingQuality.EXACT)

object InputSourceRules {
    fun hash(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun validatePhases(segments: List<PhaseSegment>, durationMs: Long?, confirmed: Boolean) {
        require(segments.isNotEmpty()) { "至少标记一个教学环节" }
        require(segments.map { it.id }.distinct().size == segments.size) { "环节编号不能重复" }
        require(!confirmed || durationMs != null) { "确认前需要有效的课堂时间范围" }
        val sorted = segments.sortedBy { it.startMs }
        sorted.forEachIndexed { i, s ->
            require(s.id.isNotBlank() && s.label.isNotBlank()) { "环节名称不能为空" }
            require(s.startMs >= 0 && s.endMs > s.startMs) { "环节结束须晚于开始" }
            require(durationMs == null || s.endMs <= durationMs) { "环节超出课堂时间范围" }
            require(i == 0 || sorted[i - 1].endMs <= s.startMs) { "活动环节不能重叠" }
        }
    }

    fun validateFactAnchor(hasSource: Boolean, atMs: Long?, startMs: Long?, endMs: Long?, durationMs: Long?) {
        require(atMs == null || (startMs == null && endMs == null)) { "发生时点与发生区间二选一" }
        require((startMs == null) == (endMs == null)) { "请同时填写区间开始和结束" }
        require(hasSource || atMs != null || startMs != null) { "请选择原始来源或填写事实发生时间" }
        if (atMs != null) require(atMs >= 0 && durationMs != null && atMs <= durationMs) { "事实时点超出已结束课堂范围" }
        if (startMs != null && endMs != null) require(startMs >= 0 && endMs > startMs && durationMs != null && endMs <= durationMs) { "事实区间无效" }
    }

    /** 不跨暂停插值；一个句段可对应多个课堂范围。 */
    fun mapMediaRange(startMs: Long, endMs: Long, spans: List<RecordedTimeSpan>): List<SourceTimeRange> {
        require(startMs >= 0 && endMs > startMs)
        return spans.filter { it.quality != TimeMappingQuality.UNKNOWN }.mapNotNull { s ->
            val from = maxOf(startMs, s.mediaStartMs)
            val to = minOf(endMs, s.mediaEndMs)
            if (from >= to || s.mediaEndMs <= s.mediaStartMs) null else {
                fun map(m: Long) = s.classStartMs + ((m - s.mediaStartMs).toDouble() * (s.classEndMs - s.classStartMs) / (s.mediaEndMs - s.mediaStartMs)).toLong()
                SourceTimeRange(map(from), map(to))
            }
        }
    }
}
