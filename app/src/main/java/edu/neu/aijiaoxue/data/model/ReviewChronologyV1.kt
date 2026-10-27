package edu.neu.aijiaoxue.data.model

import edu.neu.aijiaoxue.data.entity.SupervisionTask
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Also used by migration 8→9: keep this version immutable when adding future rules. */
object ReviewChronologyV1 {
    const val VERSION = 1
    fun date(value: String): LocalDate? = runCatching {
        require(Regex("\\d{4}-\\d{2}-\\d{2}").matches(value))
        LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE)
    }.getOrNull()

    fun period(value: String): IntRange? {
        val match = Regex("^(\\d{1,2})(?:-(\\d{1,2}))?$").matchEntire(value.trim()) ?: return null
        val start = match.groupValues[1].toInt()
        val end = match.groupValues[2].ifEmpty { match.groupValues[1] }.toInt()
        return if (start in 1..12 && end in start..12) start..end else null
    }

    fun validate(origin: SupervisionTask, followUp: SupervisionTask): ReviewChronologyStatus =
        validate(origin.id, origin.supervisionDate, origin.period, followUp.id, followUp.supervisionDate, followUp.period)

    fun validate(originId: Long, originDate: String, originPeriod: String,
                 followUpId: Long, followUpDate: String, followUpPeriod: String): ReviewChronologyStatus {
        if (originId == followUpId) return ReviewChronologyStatus.SAME_TASK
        val beforeDate = date(originDate) ?: return ReviewChronologyStatus.UNKNOWN
        val afterDate = date(followUpDate) ?: return ReviewChronologyStatus.UNKNOWN
        if (beforeDate > afterDate) return ReviewChronologyStatus.REVERSED
        if (beforeDate < afterDate) return ReviewChronologyStatus.VALID
        val before = period(originPeriod) ?: return ReviewChronologyStatus.UNKNOWN
        val after = period(followUpPeriod) ?: return ReviewChronologyStatus.UNKNOWN
        return when {
            before.last < after.first -> ReviewChronologyStatus.VALID
            after.last < before.first -> ReviewChronologyStatus.REVERSED
            else -> ReviewChronologyStatus.OVERLAP
        }
    }
}
