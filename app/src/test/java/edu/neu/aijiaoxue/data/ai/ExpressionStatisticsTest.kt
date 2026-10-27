package edu.neu.aijiaoxue.data.ai

import edu.neu.aijiaoxue.data.analysis.*
import org.junit.Assert.*
import org.junit.Test

class ExpressionStatisticsTest {
    @Test fun professionalTermsAndStudentSpeechAreExcluded() {
        val segments = listOf(ExpressionSegment("s1", "然后我们，二叉树；然后我们，二叉树；然后我们，二叉树", true), ExpressionSegment("s2", "同学们，同学们，同学们", false))
        val rows = ExpressionStatistics.calculate(segments, setOf("二叉树"), true)
        assertEquals(3, rows.first { it.expression == "然后我们" }.count)
        assertFalse(rows.any { it.expression == "二叉树" || it.expression == "同学们" })
        assertTrue(rows.all { it.occurrences.all { occurrence -> occurrence.segmentId == "s1" } })
    }
    @Test fun noCrossSentenceMatchesOrDoubleCounting() {
        val rows = ExpressionStatistics.calculate(listOf(ExpressionSegment("s", "然后，大家；然后，大家；然后，大家", true)), emptySet(), true)
        assertFalse(rows.any { it.expression == "然后大家" })
        assertEquals(3, rows.first { it.expression == "然后" }.count)
    }
}
