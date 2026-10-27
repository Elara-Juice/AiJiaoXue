package edu.neu.aijiaoxue.data

import edu.neu.aijiaoxue.data.model.ActivityKind
import edu.neu.aijiaoxue.data.model.TimeMappingQuality
import edu.neu.aijiaoxue.data.repository.*
import org.junit.Assert.*
import org.junit.Test

class InputSourceRulesTest {
    private fun phase(id: String, start: Long, end: Long) = PhaseSegment(id, ActivityKind.LECTURE, "讲授", start, end)

    @Test fun adjacentHalfOpenPhasesAreValid() { InputSourceRules.validatePhases(listOf(phase("a", 0, 60), phase("b", 60, 100)), 100, true) }
    @Test fun overlappingPhasesAreRejected() { assertNotNull(runCatching { InputSourceRules.validatePhases(listOf(phase("a", 0, 70), phase("b", 60, 100)), 100, true) }.exceptionOrNull()) }
    @Test fun phasesCannotExceedClassDuration() { assertNotNull(runCatching { InputSourceRules.validatePhases(listOf(phase("a", 0, 101)), 100, true) }.exceptionOrNull()) }
    @Test fun unknownDurationAllowsDraftButNotConfirmation() {
        InputSourceRules.validatePhases(listOf(phase("a", 0, 100)), null, false)
        assertNotNull(runCatching { InputSourceRules.validatePhases(listOf(phase("a", 0, 100)), null, true) }.exceptionOrNull())
    }
    @Test fun zeroDurationIsNotAPhase() { assertNotNull(runCatching { InputSourceRules.validatePhases(listOf(phase("a", 0, 0)), 100, true) }.exceptionOrNull()) }
    @Test fun factPointAndIntervalAreMutuallyExclusive() { assertNotNull(runCatching { InputSourceRules.validateFactAnchor(true, 50, 20, 60, 100) }.exceptionOrNull()) }
    @Test fun factNeedsSourceOrTime() { assertNotNull(runCatching { InputSourceRules.validateFactAnchor(false, null, null, null, 100) }.exceptionOrNull()) }
    @Test fun sourceOnlyFactDoesNotInventTime() { InputSourceRules.validateFactAnchor(true, null, null, null, null) }
    @Test fun timeOnlyFactMustBelongToKnownClassRange() {
        InputSourceRules.validateFactAnchor(false, 0, null, null, 100)
        assertNotNull(runCatching { InputSourceRules.validateFactAnchor(false, 101, null, null, 100) }.exceptionOrNull())
        assertNotNull(runCatching { InputSourceRules.validateFactAnchor(false, 5, null, null, null) }.exceptionOrNull())
    }
    @Test fun pauseDoesNotMovePlaybackCoordinateOrDuplicateText() {
        val spans = listOf(RecordedTimeSpan(0, 60000, 0, 60000), RecordedTimeSpan(60000, 120000, 90000, 150000))
        assertEquals(listOf(SourceTimeRange(100000, 101000)), InputSourceRules.mapMediaRange(70000, 71000, spans))
        assertEquals(listOf(SourceTimeRange(55000, 60000), SourceTimeRange(90000, 95000)), InputSourceRules.mapMediaRange(55000, 65000, spans))
    }
    @Test fun unknownRecordingMapNeverInventsClassTime() {
        assertTrue(InputSourceRules.mapMediaRange(0, 100, emptyList()).isEmpty())
        assertTrue(InputSourceRules.mapMediaRange(0, 100, listOf(RecordedTimeSpan(0, 100, 0, 100, TimeMappingQuality.UNKNOWN))).isEmpty())
    }
}
