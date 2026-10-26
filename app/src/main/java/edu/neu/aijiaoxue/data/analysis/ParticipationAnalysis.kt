package edu.neu.aijiaoxue.data.analysis

import edu.neu.aijiaoxue.data.entity.EngagementRecord
import edu.neu.aijiaoxue.data.entity.Evaluation
import edu.neu.aijiaoxue.data.entity.EvaluationIssue
import edu.neu.aijiaoxue.data.entity.InteractionEvent
import edu.neu.aijiaoxue.data.entity.Material
import edu.neu.aijiaoxue.data.model.AnomalyType
import edu.neu.aijiaoxue.data.model.DataSourceKind
import edu.neu.aijiaoxue.data.model.EngagementType
import edu.neu.aijiaoxue.data.model.EvalDimension

/*
 * Sprint 3（李奕萱）US24 多源参与 / US25 参与异常环节。
 *
 * 全部结论由真实采集记录与正式评价计算得到：
 * - 至少两类来源都有数据时才形成多源结论；只有一类来源时明确提示有效输入不足；
 * - 每条结论标注来源种类、时间范围和可检查的依据（记录条数、维度、时间点）；
 * - 照片只按采集时间对齐，不当作 PPT 内容分析；教学环节只使用已审核的分析结论。
 */

/** 一类来源在该课堂中的时间范围与记录条数，用于在界面上标注结论出处。 */
data class SourceWindow(
    val kind: DataSourceKind,
    val recordCount: Int,
    val startOffsetMs: Long?,
    val endOffsetMs: Long?,
)

/** 一条参与结论：来源、时间范围、依据。 */
data class ParticipationFinding(
    val title: String,
    val detail: String,
    val sources: List<SourceWindow>,
    /** 统一时间轴上的区间（相对课堂开始，毫秒）。 */
    val startOffsetMs: Long?,
    val endOffsetMs: Long?,
    /** 人工可检查的依据，例如参与记录条数、互动事件条数、评价维度分值。 */
    val basis: String,
)

/** 教学环节片段，来自已审核的教学环节分析结论。 */
data class PhaseSegment(
    val label: String,
    val startOffsetMs: Long,
    val endOffsetMs: Long,
)

/** 一段参与异常：区间、类型、依据，以及定位到的教学环节。 */
data class AnomalyInterval(
    val type: AnomalyType,
    val startOffsetMs: Long,
    val endOffsetMs: Long,
    val startAt: Long?,
    val endAt: Long?,
    val description: String,
    val evidence: String,
    /** 定位到的教学环节；为 null 表示没有已审核的环节结论可用于定位。 */
    val phaseLabel: String?,
    val phaseBasis: String?,
)

/** 多源参与分析的整体结果。 */
data class ParticipationInsight(
    val findings: List<ParticipationFinding>,
    val anomalies: List<AnomalyInterval>,
    val availableSourceKinds: List<DataSourceKind>,
    /** 有效来源不足两类时的提示，不生成多源结论。 */
    val insufficientNote: String?,
)

/** 分析输入：一次课堂的采集记录、正式评价和已审核的教学环节。 */
data class ParticipationInput(
    val classStartAt: Long?,
    val classEndAt: Long?,
    val engagements: List<EngagementRecord>,
    val events: List<InteractionEvent>,
    val materials: List<Material>,
    val evaluation: Evaluation?,
    val evaluationIssues: List<EvaluationIssue>,
    val phases: List<PhaseSegment>,
)

/**
 * 规则计算的多源参与分析与参与异常定位。
 *
 * 只用已采集的记录和已提交的评价，不联网、不调用模型；所有数值都可以回到原始记录核对。
 */
object ParticipationAnalysis {
    /** 参与比例低于该值视为偏低。 */
    const val LOW_ENGAGEMENT_PERCENT = 50

    /** 连续走神记录达到该条数才算一段异常。 */
    const val DISTRACTION_RUN_LENGTH = 2

    /** 相邻互动事件间隔超过该时长视为长时间无互动。 */
    const val LONG_SILENCE_MS = 10 * 60 * 1000L

    /** 参与记录与互动事件的对照窗口。 */
    const val CROSS_SOURCE_WINDOW_MS = 5 * 60 * 1000L

    fun analyze(input: ParticipationInput): ParticipationInsight {
        val windows = buildWindows(input)
        val kinds = windows.filter { it.recordCount > 0 }.map { it.kind }
        val findings = mutableListOf<ParticipationFinding>()
        val insufficient = if (kinds.size < 2) {
            "有效输入不足：只有 ${kinds.size} 类来源有数据（${kinds.joinToString { it.label }}），无法形成多源参与结论"
        } else null

        engagementAgainstEvents(input, windows, findings)
        engagementAgainstEvaluation(input, windows, findings)
        eventsAgainstEvaluation(input, windows, findings)
        materialAgainstEngagement(input, windows, findings)

        return ParticipationInsight(
            findings = findings,
            anomalies = detectAnomalies(input),
            availableSourceKinds = kinds,
            insufficientNote = insufficient,
        )
    }

    private fun buildWindows(input: ParticipationInput): List<SourceWindow> {
        val engagement = input.engagements
        val events = input.events
        val materials = input.materials
        val evaluation = input.evaluation
        return listOf(
            SourceWindow(
                DataSourceKind.ENGAGEMENT_RECORD, engagement.size,
                engagement.minOfOrNull { it.offsetMs }, engagement.maxOfOrNull { it.offsetMs },
            ),
            SourceWindow(
                DataSourceKind.INTERACTION_EVENT, events.size,
                events.minOfOrNull { it.offsetMs }, events.maxOfOrNull { it.offsetMs },
            ),
            SourceWindow(
                DataSourceKind.MATERIAL, materials.size,
                materials.minOfOrNull { it.offsetMs }, materials.maxOfOrNull { it.offsetMs },
            ),
            SourceWindow(
                DataSourceKind.EVALUATION, if (evaluation == null) 0 else 1,
                null, null,
            ),
        )
    }

    private fun windowOf(windows: List<SourceWindow>, kind: DataSourceKind) =
        windows.first { it.kind == kind }

    /** 参与状态记录与互动事件对照：找出一侧有记录、另一侧同期没有记录的时间段。 */
    private fun engagementAgainstEvents(
        input: ParticipationInput,
        windows: List<SourceWindow>,
        findings: MutableList<ParticipationFinding>,
    ) {
        val engagementWindow = windowOf(windows, DataSourceKind.ENGAGEMENT_RECORD)
        val eventWindow = windowOf(windows, DataSourceKind.INTERACTION_EVENT)
        if (engagementWindow.recordCount == 0 || eventWindow.recordCount == 0) return
        val eventOffsets = input.events.map { it.offsetMs }.sorted()
        val records = input.engagements.sortedBy { it.offsetMs }
        var index = 0
        records.forEach { record ->
            while (index < eventOffsets.size && eventOffsets[index] < record.offsetMs - CROSS_SOURCE_WINDOW_MS) index++
            val near = eventOffsets.drop(index).firstOrNull { it <= record.offsetMs + CROSS_SOURCE_WINDOW_MS }
            if (near == null) {
                findings += ParticipationFinding(
                    title = "参与状态记录时段缺少互动事件",
                    detail = "该时段记录了学生参与状态，前后 ${CROSS_SOURCE_WINDOW_MS / 60000} 分钟内没有互动事件记录，" +
                        "参与记录与互动事件在时间上不能互相印证。",
                    sources = listOf(engagementWindow, eventWindow),
                    startOffsetMs = record.offsetMs,
                    endOffsetMs = record.offsetMs,
                    basis = "参与状态记录 ${record.id}（${record.type.label}，偏移 ${record.offsetMs}ms），" +
                        "该前后窗口内互动事件 0 条",
                )
            }
        }
    }

    /** 参与状态记录与正式评价的“学生参与”维度对照。 */
    private fun engagementAgainstEvaluation(
        input: ParticipationInput,
        windows: List<SourceWindow>,
        findings: MutableList<ParticipationFinding>,
    ) {
        val engagementWindow = windowOf(windows, DataSourceKind.ENGAGEMENT_RECORD)
        val evaluationWindow = windowOf(windows, DataSourceKind.EVALUATION)
        if (engagementWindow.recordCount == 0 || evaluationWindow.recordCount == 0) return
        val evaluation = input.evaluation ?: return
        val score = evaluation.engagementScore ?: return
        val distracted = input.engagements.count { it.type == EngagementType.DISTRACTED }
        val percent = averageEngagementPercent(input.engagements)
        findings += ParticipationFinding(
            title = "参与记录与评价“学生参与”维度对照",
            detail = "参与状态记录共 ${input.engagements.size} 条，其中低头/走神 $distracted 条" +
                (percent?.let { "，平均参与比例 $it%" } ?: "") +
                "；督导评价“学生参与”维度打 $score 分。两处为不同来源，分别列出，不合并成单一结论。",
            sources = listOf(engagementWindow, evaluationWindow),
            startOffsetMs = engagementWindow.startOffsetMs,
            endOffsetMs = engagementWindow.endOffsetMs,
            basis = "参与状态记录 ${input.engagements.size} 条（走神 $distracted 条）；" +
                "评价维度 ${EvalDimension.ENGAGEMENT.label} = $score",
        )
    }

    /** 互动事件与正式评价的“课堂互动”维度对照。 */
    private fun eventsAgainstEvaluation(
        input: ParticipationInput,
        windows: List<SourceWindow>,
        findings: MutableList<ParticipationFinding>,
    ) {
        val eventWindow = windowOf(windows, DataSourceKind.INTERACTION_EVENT)
        val evaluationWindow = windowOf(windows, DataSourceKind.EVALUATION)
        if (eventWindow.recordCount == 0 || evaluationWindow.recordCount == 0) return
        val evaluation = input.evaluation ?: return
        val score = evaluation.interactionScore ?: return
        val byType = input.events.groupingBy { it.type }.eachCount()
        findings += ParticipationFinding(
            title = "互动事件与评价“课堂互动”维度对照",
            detail = "互动事件共 ${input.events.size} 次：" +
                byType.entries.joinToString("、") { "${it.key.label} ${it.value} 次" } +
                "；督导评价“课堂互动”维度打 $score 分。事件统计只反映标注次数，不代替人工评价。",
            sources = listOf(eventWindow, evaluationWindow),
            startOffsetMs = eventWindow.startOffsetMs,
            endOffsetMs = eventWindow.endOffsetMs,
            basis = "互动事件 ${input.events.size} 次（${byType.entries.joinToString { "${it.key.label}=${it.value}" }}）；" +
                "评价维度 ${EvalDimension.INTERACTION.label} = $score",
        )
    }

    /** 采集材料与参与状态记录按采集时间对齐，不做 PPT 内容分析。 */
    private fun materialAgainstEngagement(
        input: ParticipationInput,
        windows: List<SourceWindow>,
        findings: MutableList<ParticipationFinding>,
    ) {
        val materialWindow = windowOf(windows, DataSourceKind.MATERIAL)
        val engagementWindow = windowOf(windows, DataSourceKind.ENGAGEMENT_RECORD)
        if (materialWindow.recordCount == 0 || engagementWindow.recordCount == 0) return
        val materialOffsets = input.materials.map { it.offsetMs }.sorted()
        val covered = input.engagements.count { record ->
            materialOffsets.any { kotlin.math.abs(it - record.offsetMs) <= CROSS_SOURCE_WINDOW_MS }
        }
        findings += ParticipationFinding(
            title = "采集材料与参与状态记录的时间覆盖",
            detail = "采集材料 ${input.materials.size} 件，参与状态记录 ${input.engagements.size} 条，" +
                "其中 $covered 条落在材料采集时间前后 ${CROSS_SOURCE_WINDOW_MS / 60000} 分钟内。" +
                "这里只按采集时间对齐，材料数量不代表 PPT 内容，学生反应也不能由照片推断。",
            sources = listOf(materialWindow, engagementWindow),
            startOffsetMs = materialWindow.startOffsetMs,
            endOffsetMs = materialWindow.endOffsetMs,
            basis = "材料 ${input.materials.size} 件（${input.materials.groupingBy { it.type }.eachCount()
                .entries.joinToString("、") { "${it.key.label}=${it.value}" }}）；参与状态记录 ${input.engagements.size} 条",
        )
    }

    /** 参与异常区间识别与教学环节定位（US25）。 */
    fun detectAnomalies(input: ParticipationInput): List<AnomalyInterval> {
        val anomalies = mutableListOf<AnomalyInterval>()
        anomalies += distractionRuns(input)
        anomalies += lowEngagement(input)
        anomalies += silenceGaps(input)
        return anomalies.sortedWith(compareBy({ it.startOffsetMs }, { it.type.ordinal }))
    }

    private fun distractionRuns(input: ParticipationInput): List<AnomalyInterval> {
        val records = input.engagements.sortedBy { it.offsetMs }
        val result = mutableListOf<AnomalyInterval>()
        var run = mutableListOf<EngagementRecord>()
        fun flush() {
            if (run.size >= DISTRACTION_RUN_LENGTH) {
                val first = run.first()
                val last = run.last()
                result += AnomalyInterval(
                    type = AnomalyType.DISTRACTION_RUN,
                    startOffsetMs = first.offsetMs,
                    endOffsetMs = last.offsetMs,
                    startAt = first.recordedAt,
                    endAt = last.recordedAt,
                    description = "连续 ${run.size} 条记录为低头/走神，区间 ${first.offsetMs}ms—${last.offsetMs}ms",
                    evidence = "参与状态记录 ${run.joinToString { it.id.toString() }}",
                    phaseLabel = null,
                    phaseBasis = null,
                )
            }
            run = mutableListOf()
        }
        records.forEach { record ->
            if (record.type == EngagementType.DISTRACTED) run += record else flush()
        }
        flush()
        return result
    }

    private fun lowEngagement(input: ParticipationInput): List<AnomalyInterval> =
        input.engagements.mapNotNull { record ->
            val percent = record.engagedPercent
                ?: record.engagedCount?.let { engaged ->
                    record.presentCount?.takeIf { it > 0 }?.let { engaged * 100.0 / it }?.toInt()
                }
            if (percent == null || percent >= LOW_ENGAGEMENT_PERCENT) null else AnomalyInterval(
                type = AnomalyType.LOW_ENGAGEMENT,
                startOffsetMs = record.offsetMs,
                endOffsetMs = record.offsetMs,
                startAt = record.recordedAt,
                endAt = record.recordedAt,
                description = "该记录参与比例 $percent%，低于 $LOW_ENGAGEMENT_PERCENT%",
                evidence = "参与状态记录 ${record.id}（${record.type.label}）",
                phaseLabel = null,
                phaseBasis = null,
            )
        }

    private fun silenceGaps(input: ParticipationInput): List<AnomalyInterval> {
        val offsets = input.events.map { it.offsetMs }.sorted()
        if (offsets.size < 2) return emptyList()
        val result = mutableListOf<AnomalyInterval>()
        for (index in 0 until offsets.size - 1) {
            val from = offsets[index]
            val to = offsets[index + 1]
            if (to - from > LONG_SILENCE_MS) {
                result += AnomalyInterval(
                    type = AnomalyType.NO_INTERACTION,
                    startOffsetMs = from,
                    endOffsetMs = to,
                    startAt = null,
                    endAt = null,
                    description = "相邻互动事件间隔 ${(to - from) / 60000} 分钟，超过 ${LONG_SILENCE_MS / 60000} 分钟",
                    evidence = "互动事件偏移 ${from}ms 与 ${to}ms 之间无标注事件",
                    phaseLabel = null,
                    phaseBasis = null,
                )
            }
        }
        return result
    }

    /**
     * 环节定位：只用已审核的教学环节分析结论。没有可用片段时保留 null，
     * 由界面提示缺少已审核的环节结论，不自行编造环节名称。
     */
    fun locatePhases(anomalies: List<AnomalyInterval>, phases: List<PhaseSegment>): List<AnomalyInterval> {
        if (phases.isEmpty()) return anomalies
        return anomalies.map { anomaly ->
            val hit = phases.firstOrNull { anomaly.startOffsetMs in it.startOffsetMs..it.endOffsetMs }
                ?: phases.minByOrNull { kotlin.math.abs(it.startOffsetMs - anomaly.startOffsetMs) }
                    ?.takeIf { it.startOffsetMs <= anomaly.startOffsetMs && anomaly.startOffsetMs <= it.endOffsetMs }
            if (hit == null) anomaly
            else anomaly.copy(
                phaseLabel = hit.label,
                phaseBasis = "已审核的教学环节分析：${hit.startOffsetMs}ms—${hit.endOffsetMs}ms",
            )
        }
    }

    private fun averageEngagementPercent(records: List<EngagementRecord>): Int? {
        val percents = records.mapNotNull { record ->
            record.engagedPercent ?: record.engagedCount?.let { engaged ->
                record.presentCount?.takeIf { it > 0 }?.let { engaged * 100.0 / it }?.toInt()
            }
        }
        if (percents.isEmpty()) return null
        return percents.average().toInt()
    }
}
