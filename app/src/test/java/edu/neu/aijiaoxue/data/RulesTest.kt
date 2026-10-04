package edu.neu.aijiaoxue.data

import edu.neu.aijiaoxue.data.model.DateFormats
import edu.neu.aijiaoxue.data.model.EngagementRules
import edu.neu.aijiaoxue.data.model.EvalDimension
import edu.neu.aijiaoxue.data.model.EvalGrade
import edu.neu.aijiaoxue.data.model.EvalRules
import edu.neu.aijiaoxue.data.model.MeasureStatus
import edu.neu.aijiaoxue.data.model.Semester
import edu.neu.aijiaoxue.data.model.TaskCode
import edu.neu.aijiaoxue.data.model.TaskStatus
import edu.neu.aijiaoxue.data.model.isMeasureOverdue
import edu.neu.aijiaoxue.data.model.isTaskOverdue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesTest {
    @Test
    fun weightsSumTo100() {
        assertEquals(100, EvalDimension.entries.sumOf { it.weight })
    }

    @Test
    fun totalScoreAndGrade() {
        val all5 = EvalDimension.entries.associateWith { 5 }
        assertEquals(100, EvalRules.totalScore(all5))
        // 4,4,4,4 -> 80 良好
        val all4 = EvalDimension.entries.associateWith { 4 }
        assertEquals(80, EvalRules.totalScore(all4))
        assertEquals(EvalGrade.GOOD, EvalRules.gradeOf(80))
        assertEquals(EvalGrade.EXCELLENT, EvalRules.gradeOf(90))
        assertEquals(EvalGrade.PASS, EvalRules.gradeOf(60))
        assertEquals(EvalGrade.NEEDS_IMPROVEMENT, EvalRules.gradeOf(59))
    }

    @Test
    fun totalScoreNullWhenMissing() {
        assertNull(EvalRules.totalScore(mapOf(EvalDimension.CONTENT to 5)))
    }

    @Test
    fun taskCodeFormat() {
        assertEquals("DD20261003-001", TaskCode.of("2026-10-03", 1))
        assertEquals("DD20261003-", TaskCode.prefixOf("2026-10-03"))
    }

    @Test
    fun semesterStartIsIsoDate() {
        // 与 supervisionDate 按字符串比较，必须是 yyyy-MM-dd
        assertEquals("2026-09-01", DateFormats.DATE.format(DateFormats.DATE.parse(Semester.START_DATE)))
        assertTrue("2026-08-31" < Semester.START_DATE)
        assertFalse("2026-09-01" < Semester.START_DATE)
    }

    @Test
    fun engagementValidation() {
        assertNull(EngagementRules.validate(40, 30, null))
        assertNull(EngagementRules.validate(40, null, 80))
        assertNull(EngagementRules.validate(null, null, null))
        assertEquals("参与人数与参与比例只能填写一项", EngagementRules.validate(40, 30, 80))
        assertEquals("参与人数不能大于实到人数", EngagementRules.validate(40, 41, null))
        assertEquals("参与比例应在 0–100% 之间", EngagementRules.validate(40, null, 101))
        assertEquals("实到人数不能为负数", EngagementRules.validate(-1, null, null))
    }

    @Test
    fun overdue() {
        assertTrue(isTaskOverdue(TaskStatus.NOT_STARTED, "2026-10-01", today = "2026-10-02"))
        assertFalse(isTaskOverdue(TaskStatus.IN_PROGRESS, "2026-10-01", today = "2026-10-02"))
        assertFalse(isTaskOverdue(TaskStatus.NOT_STARTED, "2026-10-02", today = "2026-10-02"))
        assertTrue(isMeasureOverdue(MeasureStatus.IN_PROGRESS, "2026-10-01", today = "2026-10-02"))
        assertFalse(isMeasureOverdue(MeasureStatus.DONE, "2026-10-01", today = "2026-10-02"))
    }
}
