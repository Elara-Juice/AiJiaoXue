package edu.neu.aijiaoxue.data.model

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * 跨模块共用的业务规则与格式约定。任何模块不要自己重写这些计算。
 *
 * 时间约定：
 * - 时间点（xxxAt）：Long，epoch 毫秒，System.currentTimeMillis()
 * - 相对课堂开始的偏移（offsetMs）：Long，毫秒
 * - 时长（durationMs）：Long，毫秒
 * - 日期（xxxDate）：String，ISO 格式 "yyyy-MM-dd"，可直接按字符串排序比较
 */
object DateFormats {
    val DATE: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    private val COMPACT: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE

    fun today(): String = LocalDate.now().format(DATE)

    /** "2026-10-03" -> "20261003"，用于任务编号。 */
    fun compact(date: String): String = LocalDate.parse(date, DATE).format(COMPACT)
}

object EvalRules {
    const val MIN_SCORE = 1
    const val MAX_SCORE = 5

    /** 按权重把各维度 1–5 分换算为百分制总分；缺任一维度返回 null。 */
    fun totalScore(scores: Map<EvalDimension, Int?>): Int? {
        var total = 0.0
        for (dim in EvalDimension.entries) {
            val s = scores[dim] ?: return null
            require(s in MIN_SCORE..MAX_SCORE) { "score out of range: $dim=$s" }
            total += s.toDouble() / MAX_SCORE * dim.weight
        }
        return total.roundToInt()
    }

    /** ≥90 优秀、75–89 良好、60–74 合格、<60 待改进。 */
    fun gradeOf(total: Int): EvalGrade = when {
        total >= 90 -> EvalGrade.EXCELLENT
        total >= 75 -> EvalGrade.GOOD
        total >= 60 -> EvalGrade.PASS
        else -> EvalGrade.NEEDS_IMPROVEMENT
    }
}

/** US13 参与状态录入校验，返回错误提示；null 表示可以保存。 */
object EngagementRules {
    fun validate(presentCount: Int?, engagedCount: Int?, engagedPercent: Int?): String? = when {
        engagedCount != null && engagedPercent != null -> "参与人数与参与比例只能填写一项"
        presentCount != null && presentCount < 0 -> "实到人数不能为负数"
        engagedCount != null && engagedCount < 0 -> "参与人数不能为负数"
        engagedCount != null && presentCount != null && engagedCount > presentCount -> "参与人数不能大于实到人数"
        engagedPercent != null && engagedPercent !in 0..100 -> "参与比例应在 0–100% 之间"
        else -> null
    }
}

/** 本学期开始日期，用于 US05“本学期未督导”判断。换学期时只改这里。 */
object Semester {
    const val START_DATE = "2026-09-01"
}

/** 任务编号：DD + 督导日期 + 当日序号，例如 DD20261003-001。 */
object TaskCode {
    fun of(supervisionDate: String, seq: Int): String =
        "DD${DateFormats.compact(supervisionDate)}-${seq.toString().padStart(3, '0')}"

    /** 当日编号前缀，配合 DAO 查当日已有数量。 */
    fun prefixOf(supervisionDate: String): String = "DD${DateFormats.compact(supervisionDate)}-"
}

/** 超过督导日期仍为“待开始”即为逾期（US06）。 */
fun isTaskOverdue(status: TaskStatus, supervisionDate: String, today: String = DateFormats.today()): Boolean =
    status == TaskStatus.NOT_STARTED && supervisionDate < today

/** 超过计划完成日期仍未完成即为逾期（US42）。 */
fun isMeasureOverdue(status: MeasureStatus, dueDate: String, today: String = DateFormats.today()): Boolean =
    status != MeasureStatus.DONE && dueDate < today

/**
 * Sprint 3 US45：指标口径与可比性判定。
 *
 * 比较两次课堂的指标前必须先约定单位、时长口径、分母、采样方式和方法版本（见 [MetricSpec]）。
 * 口径不一致、缺测或方法版本不同，一律判为不可比，不强行计算改善，也不把缺测补成 0。
 */
object MetricRules {
    /** 当前指标口径版本。口径变更时新增版本号，旧快照原样保留。 */
    const val METHOD_VERSION = "v1"

    const val UNIT_PERCENT = "%"
    const val UNIT_COUNT_PER_10MIN = "次/10分钟"
    const val UNIT_SCORE = "分"

    /** 参与率按人数统计时的分母口径。 */
    const val DENOMINATOR_PRESENT = "实到人数合计"
    /** 参与率只有比例记录时的分母口径，与按人数统计的口径不同，两者不可比。 */
    const val DENOMINATOR_PERCENT_RECORDS = "参与比例记录条数"

    const val SAMPLING_ENGAGEMENT_FULL = "课堂全时段参与状态记录"
    const val SAMPLING_INTERACTION_FULL = "课堂全时段互动事件计数"
    const val SAMPLING_EVALUATION = "已提交正式评价"

    /** 指标口径定义。 */
    data class MetricSpec(
        val key: MetricKey,
        val unit: String,
        val denominatorLabel: String,
        val sampling: String,
        val methodVersion: String = METHOD_VERSION,
        /**
         * 指标是否依赖课堂时长。依赖时，两次课堂时长不一致则不可比；
         * 比例类与已按时长折算的指标不依赖。
         */
        val durationSensitive: Boolean = false,
    )

    fun spec(key: MetricKey): MetricSpec = when (key) {
        // 参与率按“课堂全时段参与状态记录”采样，采样基础是整堂课，
        // 因此两次课堂时长必须一致，否则先说明不可比再谈改善。
        MetricKey.ENGAGEMENT_RATE -> MetricSpec(
            key, UNIT_PERCENT, DENOMINATOR_PRESENT, SAMPLING_ENGAGEMENT_FULL,
            durationSensitive = true,
        )
        MetricKey.INTERACTION_DENSITY -> MetricSpec(
            key, UNIT_COUNT_PER_10MIN, "每10分钟折算", SAMPLING_INTERACTION_FULL,
        )
        MetricKey.EVALUATION_TOTAL -> MetricSpec(
            key, UNIT_SCORE, "百分制总分", SAMPLING_EVALUATION,
        )
        MetricKey.EVALUATION_DESIGN -> MetricSpec(
            key, "score", "single_evaluation", "submitted_design_dimension", "manual_design_score_v1",
        )
    }

    /** 不可比的原因，说明给使用者看。 */
    enum class IncomparableReason(val message: String) {
        MISSING_VALUE("存在缺测数据，暂不能比较"),
        UNIT_MISMATCH("指标单位不一致"),
        DENOMINATOR_MISMATCH("分母口径不一致"),
        SAMPLING_MISMATCH("采样方式不一致"),
        METHOD_VERSION_MISMATCH("方法版本不同"),
        DURATION_MISMATCH("两次课堂时长不一致"),
        DURATION_MISSING("课堂时长未记录，暂不能比较"),
        INVALID_VALUE("指标不是有效的有限数值"),
        METRIC_MISMATCH("所选快照不是同一指标"),
    }

    /** 一次比较的结果：可比时给出差值，不可比时给出原因。 */
    sealed interface CompareOutcome {
        data class Comparable(val delta: Double, val before: Double, val after: Double) : CompareOutcome
        data class NotComparable(val reason: IncomparableReason) : CompareOutcome
    }

    /**
     * 判断两个指标快照是否可比。调用方需先保证是同一指标。
     * 缺测（value 为 null）按缺测处理，不按 0 参与比较。
     */
    fun compare(before: MetricSnapshotView, after: MetricSnapshotView): CompareOutcome {
        if (before.metricKey != after.metricKey) return CompareOutcome.NotComparable(IncomparableReason.METRIC_MISMATCH)
        if (before.value?.isFinite() == false || after.value?.isFinite() == false) {
            return CompareOutcome.NotComparable(IncomparableReason.INVALID_VALUE)
        }
        if (before.value == null || after.value == null) {
            return CompareOutcome.NotComparable(IncomparableReason.MISSING_VALUE)
        }
        if (before.unit != after.unit) return CompareOutcome.NotComparable(IncomparableReason.UNIT_MISMATCH)
        if (before.denominator != after.denominator) {
            return CompareOutcome.NotComparable(IncomparableReason.DENOMINATOR_MISMATCH)
        }
        if (before.sampling != after.sampling) return CompareOutcome.NotComparable(IncomparableReason.SAMPLING_MISMATCH)
        if (before.methodVersion != after.methodVersion) {
            return CompareOutcome.NotComparable(IncomparableReason.METHOD_VERSION_MISMATCH)
        }
        if (spec(before.metricKey).durationSensitive) {
            val beforeMinutes = durationMinutes(before)
            val afterMinutes = durationMinutes(after)
            // 课堂时长缺测与时长不一致要分开说，避免把“没记录”说成“不一致”。
            if (beforeMinutes == null || afterMinutes == null) {
                return CompareOutcome.NotComparable(IncomparableReason.DURATION_MISSING)
            }
            if (beforeMinutes != afterMinutes) {
                return CompareOutcome.NotComparable(IncomparableReason.DURATION_MISMATCH)
            }
        }
        val delta = after.value - before.value
        if (!delta.isFinite()) return CompareOutcome.NotComparable(IncomparableReason.INVALID_VALUE)
        return CompareOutcome.Comparable(delta, before.value, after.value)
    }

    /** 快照覆盖的课堂时长（分钟），没有区间时为 null。 */
    fun durationMinutes(snapshot: MetricSnapshotView): Long? {
        val start = snapshot.scopeStartAt ?: return null
        val end = snapshot.scopeEndAt ?: return null
        if (end < start) return null
        return (end - start) / 60_000L
    }
}

/**
 * 指标快照的只读视图。规则层不依赖 Room 实体，便于单元测试直接构造样例。
 */
data class MetricSnapshotView(
    val metricKey: MetricKey,
    val value: Double?,
    val unit: String,
    val denominator: String,
    val sampling: String,
    val methodVersion: String,
    val scopeStartAt: Long?,
    val scopeEndAt: Long?,
)

/**
 * US46 核验规则：判断、依据、核验人、时间四项齐全才算一次核验。
 * 规则放在这里，页面不自己判断“能不能算改善”。
 */
object VerificationRules {
    /** 核验依据最长字数。 */
    const val MAX_BASIS_LENGTH = 500

    /** 问题描述相似度阈值（字符二元组重合比例）。 */
    const val SIMILARITY_THRESHOLD = 0.35

    /**
     * 判断复评问题条目是否仍在说同一个问题。
     * 用字符二元组的 Jaccard 相似度，避免依赖分词或模型。
     */
    fun isSimilarIssue(origin: String, candidate: String, threshold: Double = SIMILARITY_THRESHOLD): Boolean =
        similarity(origin, candidate) >= threshold

    /**
     * 两段中文文本的相似度（0—1）。
     * 用较短一段为分母，这样“复评评价里的一条具体问题”能被更长的原问题描述认出来；
     * 只重合一个二元组不算同一问题。
     */
    fun similarity(left: String, right: String): Double {
        val a = bigrams(left)
        val b = bigrams(right)
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val intersection = a.count { it in b }
        if (intersection < 2) return 0.0
        val smaller = minOf(a.size, b.size)
        return intersection.toDouble() / smaller
    }

    private fun bigrams(text: String): Set<String> {
        val cleaned = text.filter { !it.isWhitespace() && it !in "，,。.、；;：:！!？?（）()《》\"'“”‘’" }
        if (cleaned.length < 2) return if (cleaned.isEmpty()) emptySet() else setOf(cleaned)
        return (0 until cleaned.length - 1).map { cleaned.substring(it, it + 2) }.toSet()
    }
}
