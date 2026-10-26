package edu.neu.aijiaoxue.sprint3

import edu.neu.aijiaoxue.data.model.MetricKey
import edu.neu.aijiaoxue.data.model.MetricRules
import edu.neu.aijiaoxue.data.model.MetricSnapshotView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * US45：指标比较口径。缺测不补零，口径不同不强行计算。
 */
class MetricRulesTest {
    private fun view(
        key: MetricKey = MetricKey.ENGAGEMENT_RATE,
        value: Double? = 60.0,
        unit: String = MetricRules.UNIT_PERCENT,
        denominator: String = MetricRules.DENOMINATOR_PRESENT,
        sampling: String = MetricRules.SAMPLING_ENGAGEMENT_FULL,
        methodVersion: String = MetricRules.METHOD_VERSION,
        start: Long? = 0L,
        end: Long? = 45 * 60 * 1000L,
    ) = MetricSnapshotView(key, value, unit, denominator, sampling, methodVersion, start, end)

    @Test
    fun sameSpecProducesDelta() {
        val outcome = MetricRules.compare(view(), view(value = 75.0))
        assertTrue(outcome is MetricRules.CompareOutcome.Comparable)
        assertEquals(15.0, (outcome as MetricRules.CompareOutcome.Comparable).delta, 0.001)
    }

    @Test
    fun missingValueIsNotZero() {
        val outcome = MetricRules.compare(view(value = null), view(value = 75.0))
        assertEquals(MetricRules.IncomparableReason.MISSING_VALUE,
            (outcome as MetricRules.CompareOutcome.NotComparable).reason)
    }

    @Test
    fun differentDenominatorIsNotCompared() {
        val outcome = MetricRules.compare(view(), view(denominator = MetricRules.DENOMINATOR_PERCENT_RECORDS))
        assertEquals(MetricRules.IncomparableReason.DENOMINATOR_MISMATCH,
            (outcome as MetricRules.CompareOutcome.NotComparable).reason)
    }

    @Test
    fun differentMethodVersionIsNotCompared() {
        val outcome = MetricRules.compare(view(), view(methodVersion = "v2"))
        assertEquals(MetricRules.IncomparableReason.METHOD_VERSION_MISMATCH,
            (outcome as MetricRules.CompareOutcome.NotComparable).reason)
    }

    @Test
    fun differentUnitIsNotCompared() {
        val outcome = MetricRules.compare(view(), view(unit = MetricRules.UNIT_SCORE))
        assertEquals(MetricRules.IncomparableReason.UNIT_MISMATCH,
            (outcome as MetricRules.CompareOutcome.NotComparable).reason)
    }

    @Test
    fun differentClassDurationIsNotCompared() {
        val outcome = MetricRules.compare(view(), view(end = 50 * 60 * 1000L))
        assertEquals(MetricRules.IncomparableReason.DURATION_MISMATCH,
            (outcome as MetricRules.CompareOutcome.NotComparable).reason)
    }

    @Test
    fun missingDurationIsReportedAsMissing() {
        val outcome = MetricRules.compare(view(start = null, end = null), view(start = null, end = null))
        assertEquals(MetricRules.IncomparableReason.DURATION_MISSING,
            (outcome as MetricRules.CompareOutcome.NotComparable).reason)
    }

    @Test
    fun totalScoreIgnoresClassDuration() {
        val key = MetricKey.EVALUATION_TOTAL
        val outcome = MetricRules.compare(
            view(key = key, unit = MetricRules.UNIT_SCORE, denominator = "百分制总分",
                sampling = MetricRules.SAMPLING_EVALUATION),
            view(key = key, value = 90.0, unit = MetricRules.UNIT_SCORE, denominator = "百分制总分",
                sampling = MetricRules.SAMPLING_EVALUATION, end = 50 * 60 * 1000L),
        )
        assertTrue(outcome is MetricRules.CompareOutcome.Comparable)
    }
}
