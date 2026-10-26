package edu.neu.aijiaoxue.sprint3

import edu.neu.aijiaoxue.data.dao.GovernanceTaskRow
import edu.neu.aijiaoxue.data.dao.GovernanceVerificationRow
import edu.neu.aijiaoxue.data.entity.MetricSnapshot
import edu.neu.aijiaoxue.data.model.*
import edu.neu.aijiaoxue.data.repository.GovernanceCalculations
import org.junit.Assert.*
import org.junit.Test

class GovernanceCalculationsTest {
    private fun task(id: Long, teacher: Long = 1, course: Long = 1, date: String = "2026-10-20", period: String = "1-2") =
        GovernanceTaskRow(id, "T$id", date, period, null, course, "课程$course", "C$course", teacher,
            "教师$teacher", "督导", id, 80, null, "正式意见", 1000)

    private fun snapshot(id: Long, task: Long, value: Double?, version: String = "v1", unit: String = "分") =
        MetricSnapshot(id, task, MetricKey.EVALUATION_TOTAL, value, unit, "百分制总分", 100.0,
            MetricRules.SAMPLING_EVALUATION, version, if (value == null) 0 else 1, null, null, id)

    @Test fun groupsCoursesAndTeachersAndSortsPeriodsNumerically() {
        val tasks = listOf(task(2, period = "11-12"), task(1, period = "9-10"), task(3, teacher = 2, course = 2))
        val trends = GovernanceCalculations.trends(tasks, listOf(snapshot(1, 1, 70.0), snapshot(2, 2, 80.0)))
        val first = trends.single { it.courseId == 1L }
        assertEquals(listOf(1L, 2L), first.classrooms.map { it.taskId })
        assertTrue(first.hasComparableTrend)
        assertFalse(trends.single { it.courseId == 2L }.hasComparableTrend)
        val total = first.comparisons.single { it.metricKey == MetricKey.EVALUATION_TOTAL }
        assertEquals(10.0, (total.outcome as MetricRules.CompareOutcome.Comparable).delta, 0.001)
    }

    @Test fun missingDataAndDifferentUnitsNeverProduceZeroOrDelta() {
        val tasks = listOf(task(1), task(2, date = "2026-10-21"))
        val missing = GovernanceCalculations.trends(tasks, listOf(snapshot(1, 1, 80.0), snapshot(2, 2, null))).single()
        assertFalse(missing.hasComparableTrend)
        assertNull(missing.comparisons.single { it.metricKey == MetricKey.EVALUATION_TOTAL }.after!!.value)
        val mismatch = GovernanceCalculations.trends(tasks, listOf(snapshot(1, 1, 80.0), snapshot(2, 2, 90.0, unit = "%"))).single()
        val outcome = mismatch.comparisons.single { it.metricKey == MetricKey.EVALUATION_TOTAL }.outcome
        assertEquals(MetricRules.IncomparableReason.UNIT_MISMATCH, (outcome as MetricRules.CompareOutcome.NotComparable).reason)
    }

    @Test fun choosesAnExistingCommonVersionInsteadOfMixingNewAndOldMethods() {
        val result = GovernanceCalculations.trends(listOf(task(1), task(2)), listOf(
            snapshot(1, 1, 70.0), snapshot(2, 2, 80.0), snapshot(3, 2, 99.0, version = "v2"),
        )).single().comparisons.single { it.metricKey == MetricKey.EVALUATION_TOTAL }
        assertEquals("v1", result.after!!.methodVersion)
        assertEquals(10.0, (result.outcome as MetricRules.CompareOutcome.Comparable).delta, 0.001)
    }

    @Test fun deduplicatesWholeClassLinksAndBreaksSameTimeTiesByVerificationId() {
        fun row(id: Long, link: Long, time: Long, verdict: VerificationVerdict) = GovernanceVerificationRow(
            id, link, 1, null, "整堂复评", 2, "T2", verdict, "核验依据", "督导", time)
        val rows = listOf(row(1, 1, 100, VerificationVerdict.IMPROVED),
            row(2, 2, 100, VerificationVerdict.NOT_IMPROVED), row(3, 2, 99, VerificationVerdict.CONTINUE_OBSERVING))
        val latest = GovernanceCalculations.latestVerifications(rows).single()
        assertEquals(2L, latest.verificationId)
        assertEquals(VerificationVerdict.NOT_IMPROVED, latest.verdict)
    }
}
