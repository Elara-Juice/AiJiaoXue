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
