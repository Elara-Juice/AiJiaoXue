package edu.neu.aijiaoxue.data.ai

import edu.neu.aijiaoxue.data.entity.EngagementRecord
import edu.neu.aijiaoxue.data.entity.InteractionEvent
import edu.neu.aijiaoxue.data.entity.Material
import edu.neu.aijiaoxue.data.model.AiAnalysisType
import edu.neu.aijiaoxue.data.model.EngagementType
import edu.neu.aijiaoxue.data.model.MaterialType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Sprint 2 模拟分析器：按已采集的材料、互动事件和参与状态用规则生成结论，
 * 不联网、不调用 AI 服务（AGENTS.md 硬性规则 10）。证据都指向真实记录，便于追溯。
 */
class MockAIAnalyzer : AIAnalyzer {
    private data class NoteSource(
        val text: String,
        val materialId: Long? = null,
        val eventId: Long? = null,
        val offsetMs: Long,
        val description: String,
    )

    private data class PhraseOccurrence(
        val source: NoteSource,
        val start: Int,
        val endExclusive: Int,
    )

    private data class TimelineSource(
        val offsetMs: Long,
        val materialId: Long? = null,
        val eventId: Long? = null,
        val description: String,
    )

    override suspend fun analyze(
        taskId: Long,
        materials: List<Material>,
        events: List<InteractionEvent>,
        engagements: List<EngagementRecord>,
    ): List<AIResult> = withContext(Dispatchers.Default) {
        // Sprint 3 文本统计包含候选排序，放到计算线程避免阻塞分析页。
        val classEnd = (materials.map { it.offsetMs + (it.durationMs ?: 0) } +
            events.map { it.offsetMs } + engagements.map { it.offsetMs }).maxOrNull() ?: 0L
        listOf(
            phases(materials, classEnd),
            interaction(events, classEnd),
            engagement(engagements, classEnd),
            verbalRepetition(materials, events),
            teachingTransitions(materials, events),
        )
    }

    /** US19：按 PPT、板书拍照的时间点粗分教学环节。 */
    private fun phases(materials: List<Material>, classEnd: Long): AIResult {
        val slides = materials.filter { it.type == MaterialType.PPT || it.type == MaterialType.BLACKBOARD }
        val content = if (slides.isEmpty()) "未采集到 PPT 或板书照片，无法识别教学环节"
        else "已采集 ${slides.size} 张 PPT/板书照片；仅凭这些材料无法确认完整教学环节结构"
        return AIResult(
            type = AiAnalysisType.BOPPPS,
            content = content,
            confidence = if (slides.isEmpty()) 0.3f else (0.6f + 0.05f * slides.size).coerceAtMost(0.9f),
            startMs = 0L,
            endMs = classEnd,
            evidences = slides.map {
                AIEvidenceRef("${it.type.label}照片", materialId = it.id, startMs = it.offsetMs, endMs = it.offsetMs)
            },
        )
    }

    /** US18：只统计实际采集的材料 note 和互动事件 note 中重复的连续表达。 */
    private fun verbalRepetition(materials: List<Material>, events: List<InteractionEvent>): AIResult {
        val sources = buildList {
            materials.filter { it.offsetMs >= 0 && !it.note.isNullOrBlank() }.forEach {
                add(NoteSource(it.note.orEmpty(), materialId = it.id, offsetMs = it.offsetMs,
                    description = "课堂材料 note"))
            }
            events.filter { it.offsetMs >= 0 && !it.note.isNullOrBlank() }.forEach {
                add(NoteSource(it.note.orEmpty(), eventId = it.id, offsetMs = it.offsetMs,
                    description = "互动事件 note"))
            }
        }.filter { source -> textRuns(source.text).any { it.last - it.first + 1 >= 2 } }

        if (sources.isEmpty()) {
            return AIResult(
                type = AiAnalysisType.VERBAL_REPETITION,
                content = "输入不足/无法分析：没有包含有效文字的课堂材料 note 或互动事件 note。",
                confidence = 0.3f,
            )
        }

        val candidates = mutableMapOf<String, MutableList<PhraseOccurrence>>()
        sources.forEach { source ->
            textRuns(source.text).forEach { run ->
                val maxLength = minOf(MAX_PHRASE_LENGTH, run.last - run.first + 1)
                for (length in 2..maxLength) {
                    for (start in run.first..run.last - length + 1) {
                        val phrase = source.text.substring(start, start + length)
                        candidates.getOrPut(phrase) { mutableListOf() }
                            .add(PhraseOccurrence(source, start, start + length))
                    }
                }
            }
        }

        val claimed = mutableListOf<PhraseOccurrence>()
        val repeated = candidates.entries
            .sortedWith(compareByDescending<Map.Entry<String, MutableList<PhraseOccurrence>>> { it.key.length }
                .thenByDescending { it.value.size }
                .thenBy { it.key })
            .mapNotNull { (phrase, occurrences) ->
                val available = occurrences.filter { occurrence ->
                    claimed.none { selected -> overlaps(selected, occurrence) }
                }
                if (available.size < MIN_REPETITIONS) null
                else {
                    claimed.addAll(available)
                    phrase to available
                }
            }
            .sortedWith(compareByDescending<Pair<String, List<PhraseOccurrence>>> { it.second.size }
                .thenByDescending { it.first.length }
                .thenBy { it.first })
            .take(MAX_REPORTED_PHRASES)

        val evidences = if (repeated.isEmpty()) {
            sources.map { source ->
                AIEvidenceRef(
                    description = "${source.description}，用于重复表达统计",
                    materialId = source.materialId,
                    eventId = source.eventId,
                    startMs = source.offsetMs,
                    endMs = source.offsetMs,
                )
            }
        } else {
            repeated.flatMap { (phrase, occurrences) ->
                occurrences.map { occurrence ->
                    AIEvidenceRef(
                        description = "${occurrence.source.description} 中的“$phrase”（文本字符位置 " +
                            "${occurrence.start + 1}–${occurrence.endExclusive}）",
                        materialId = occurrence.source.materialId,
                        eventId = occurrence.source.eventId,
                        startMs = occurrence.source.offsetMs,
                        endMs = occurrence.source.offsetMs,
                    )
                }
            }
        }
        val content = if (repeated.isEmpty()) {
            "已检查 ${sources.size} 条真实课堂 note，未发现规则命中的重复表达（连续 2–$MAX_PHRASE_LENGTH 字符片段至少出现 $MIN_REPETITIONS 次）。"
        } else {
            "记录文本中的重复表达：" + repeated.joinToString("；") { (phrase, occurrences) ->
                "“$phrase”${occurrences.size} 次"
            }
        }
        return AIResult(
            type = AiAnalysisType.VERBAL_REPETITION,
            content = content,
            confidence = if (repeated.isEmpty()) 0.55f else 0.7f,
            startMs = evidences.mapNotNull { it.startMs }.minOrNull(),
            endMs = evidences.mapNotNull { it.endMs }.maxOrNull(),
            evidences = evidences,
        )
    }

    /** US21：只比较真实材料/互动事件的相邻不同时间点，不推断完整教学结构。 */
    private fun teachingTransitions(
        materials: List<Material>,
        events: List<InteractionEvent>,
    ): AIResult {
        val timeline = buildList {
            materials.filter { it.offsetMs >= 0 }.forEach {
                add(TimelineSource(it.offsetMs, materialId = it.id, description = "材料（${it.type.label}）"))
            }
            events.filter { it.offsetMs >= 0 }.forEach {
                add(TimelineSource(it.offsetMs, eventId = it.id, description = "互动事件（${it.type.label}）"))
            }
        }.groupBy { it.offsetMs }.toSortedMap()

        if (timeline.size < 2) {
            val onlyTime = timeline.keys.singleOrNull()
            val evidences = timeline.values.flatten().map { source ->
                AIEvidenceRef(
                    description = "${source.description}，当前课堂位置 ${source.offsetMs}ms；缺少不同时间点的衔接记录",
                    materialId = source.materialId,
                    eventId = source.eventId,
                    startMs = source.offsetMs,
                    endMs = source.offsetMs,
                )
            }
            val content = if (onlyTime == null) {
                "输入不足/无法分析：没有带有效课堂时间位置的材料或互动事件。"
            } else {
                "输入不足/无法分析：目前仅有课堂位置 ${onlyTime}ms 的记录，至少需要两个不同时间点才能检查衔接。"
            }
            return AIResult(
                type = AiAnalysisType.TEACHING_TRANSITION,
                content = content,
                confidence = 0.3f,
                startMs = onlyTime,
                endMs = onlyTime,
                evidences = evidences,
            )
        }

        val timeGroups = timeline.entries.toList()
        val intervals = timeGroups.zipWithNext()
        val evidences = intervals.flatMap { (start, end) ->
            start.value.map { source ->
                AIEvidenceRef(
                    description = "${start.key}ms 区间起点：${source.description}",
                    materialId = source.materialId,
                    eventId = source.eventId,
                    startMs = start.key,
                    endMs = start.key,
                )
            } + end.value.map { source ->
                AIEvidenceRef(
                    description = "${end.key}ms 区间终点：${source.description}",
                    materialId = source.materialId,
                    eventId = source.eventId,
                    startMs = end.key,
                    endMs = end.key,
                )
            }
        }
        val intervalDescriptions = intervals.map { (start, end) ->
            val startKinds = start.value.map { it.sourceKind() }.distinct()
            val endKinds = end.value.map { it.sourceKind() }.distinct()
            val diagnosis = when {
                startKinds != endKinds -> "记录类型切换线索（${startKinds.joinToString("、")}→${endKinds.joinToString("、")}）"
                startKinds.singleOrNull() == "材料" -> "同类材料记录延续线索"
                startKinds.singleOrNull() == "互动事件" -> "同类互动事件记录延续线索"
                else -> "材料与互动事件记录组合延续线索"
            }
            "${start.key}–${end.key}ms：$diagnosis；" +
                "依据：${start.value.joinToString("、") { it.description }} → " +
                end.value.joinToString("、") { it.description }
        }
        return AIResult(
            type = AiAnalysisType.TEACHING_TRANSITION,
            content = "方法：按时间排序相邻记录，比较区间两端的来源类别；类别变化标记为切换线索，类别相同标记为同类记录延续线索。" +
                "诊断仅针对记录类型衔接，不代表实际教学环节变化或衔接质量。发现 ${intervals.size} 个有记录支撑的时间区间：" +
                intervalDescriptions.joinToString("；") +
                "。证据来源与时间见下方；需人工审核。",
            confidence = 0.65f,
            startMs = timeGroups.first().key,
            endMs = timeGroups.last().key,
            evidences = evidences,
        )
    }

    private fun TimelineSource.sourceKind() =
        if (materialId != null) "材料" else "互动事件"

    private fun textRuns(text: String): List<IntRange> {
        val runs = mutableListOf<IntRange>()
        var start = -1
        text.forEachIndexed { index, char ->
            if (char.isLetterOrDigit()) {
                if (start < 0) start = index
            } else if (start >= 0) {
                runs.add(start until index)
                start = -1
            }
        }
        if (start >= 0) runs.add(start until text.length)
        return runs
    }

    private fun overlaps(first: PhraseOccurrence, second: PhraseOccurrence): Boolean =
        first.source.materialId == second.source.materialId &&
            first.source.eventId == second.source.eventId &&
            first.start < second.endExclusive && second.start < first.endExclusive

    /** US23：按互动标注统计频次。 */
    private fun interaction(events: List<InteractionEvent>, classEnd: Long): AIResult {
        val counts = events.groupingBy { it.type }.eachCount()
        val content = if (events.isEmpty()) "未标注互动事件，无法判断课堂互动情况"
        else "共标注互动 ${events.size} 次：" + counts.entries.joinToString("、") { "${it.key.label} ${it.value} 次" }
        return AIResult(
            type = AiAnalysisType.INTERACTION,
            content = content,
            confidence = if (events.isEmpty()) 0.5f else 0.85f,
            startMs = events.minOfOrNull { it.offsetMs } ?: 0L,
            endMs = events.maxOfOrNull { it.offsetMs } ?: classEnd,
            evidences = events.map {
                AIEvidenceRef(it.type.label + (it.note?.let { n -> "：$n" } ?: ""), eventId = it.id,
                    startMs = it.offsetMs, endMs = it.offsetMs)
            },
        )
    }

    /** US13 记录的参与状态汇总；参与状态没有独立外键，证据按时间点记录。 */
    private fun engagement(records: List<EngagementRecord>, classEnd: Long): AIResult {
        val distracted = records.count { it.type == EngagementType.DISTRACTED }
        val content = when {
            records.isEmpty() -> "未记录学生参与状态"
            distracted * 2 > records.size -> "低头/走神记录较多（$distracted/${records.size}），建议关注课堂节奏"
            else -> "学生参与状态整体良好（${records.size} 条记录中走神 $distracted 条）"
        }
        return AIResult(
            type = AiAnalysisType.ENGAGEMENT,
            content = content,
            confidence = if (records.isEmpty()) 0.3f else 0.8f,
            startMs = records.minOfOrNull { it.offsetMs } ?: 0L,
            endMs = records.maxOfOrNull { it.offsetMs } ?: classEnd,
            evidences = records.map {
                AIEvidenceRef(it.type.label + (it.description?.let { d -> "：$d" } ?: ""),
                    startMs = it.offsetMs, endMs = it.offsetMs)
            },
        )
    }

    private companion object {
        const val MIN_REPETITIONS = 2
        const val MAX_PHRASE_LENGTH = 8
        const val MAX_REPORTED_PHRASES = 5
    }
}
