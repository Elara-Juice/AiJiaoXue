package edu.neu.aijiaoxue.data.analysis

import java.text.Normalizer
import java.util.Locale

data class ExpressionSegment(val id: String, val text: String, val teacher: Boolean)
data class ExpressionOccurrence(val segmentId: String, val charStart: Int, val charEnd: Int)
data class ExpressionCount(val expression: String, val occurrences: List<ExpressionOccurrence>) {
    val count: Int get() = occurrences.size
}

/** US18：只统计真实文本的非重叠匹配；不跨句/说话人，专业词可复现排除。 */
object ExpressionStatistics {
    const val METHOD_VERSION = "expression_exact_v1"
    fun calculate(segments: List<ExpressionSegment>, exclusions: Set<String>, teachersOnly: Boolean): List<ExpressionCount> {
        val excluded = exclusions.map { normalize(it) }.toSet()
        val hits = linkedMapOf<String, MutableList<ExpressionOccurrence>>()
        segments.filter { !teachersOnly || it.teacher }.forEach { segment ->
            Regex("[\\p{L}\\p{N}]+").findAll(segment.text).forEach { word ->
                // 逐字符归一化并保留原文位置，NFKC扩展不会改变证据字符下标。
                val normalized = StringBuilder(); val positions = mutableListOf<Int>()
                word.value.forEachIndexed { index, char ->
                    normalize(char.toString()).forEach { normalized.append(it); positions += word.range.first + index }
                }
                val value = normalized.toString()
                for (size in 2..minOf(8, value.length)) for (start in 0..value.length - size) {
                    val candidate = value.substring(start, start + size)
                    if (excluded.any { it.contains(candidate) } || candidate.all { it.isDigit() }) continue
                    val occurrence = ExpressionOccurrence(segment.id, positions[start], positions[start + size - 1] + 1)
                    val existing = hits.getOrPut(candidate) { mutableListOf() }
                    if (existing.lastOrNull()?.let { it.segmentId == segment.id && it.charEnd > occurrence.charStart } != true) existing += occurrence
                }
            }
        }
        val ranked = hits.filterValues { it.size >= 3 }.map { ExpressionCount(it.key, it.value) }
            .sortedWith(compareByDescending<ExpressionCount> { it.expression.length }.thenByDescending { it.count }.thenBy { it.expression })
        val kept = mutableListOf<ExpressionCount>()
        ranked.forEach { item ->
            if (kept.none { longer -> longer.expression.contains(item.expression) && longer.count == item.count &&
                item.occurrences.all { hit -> longer.occurrences.any { it.segmentId == hit.segmentId && it.charStart <= hit.charStart && it.charEnd >= hit.charEnd } } }) kept += item
        }
        return kept.sortedWith(compareByDescending<ExpressionCount> { it.count }.thenByDescending { it.expression.length }).take(30)
    }
    private fun normalize(text: String) = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
}
