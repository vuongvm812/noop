package com.noop.widget

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #94: Today's live stress tick. The tile, the pinned Stress row and the hosted curve re-ask on an aligned
 * 10-minute tick while Today is visible, gated by the producer's heart-rate fingerprint.
 */
class StressTodayTickTest {

    @Test
    fun theTodayTickIsTenMinutesAndTheBackgroundCadenceIsUntouched() {
        assertEquals(600_000L, StressWidgetProducer.TODAY_REFRESH_INTERVAL_MS)
        // The widget's service cadence stays its own (#94 changes only the foreground Today tick).
        assertEquals(15L * 60L * 1000L, StressWidgetProducer.RESCORE_INTERVAL_MS)
    }

    @Test
    fun ticksMatchTheSwiftTwinValueForValue() {
        // ORACLE: the stdout of `StressDayCurve.msUntilNextTodayTick` (Strand/Data/StressDayCurve.swift)
        // compiled standalone with swiftc -O and run over these exact cases ("now offset interval -> ms").
        val expected = """
            0 0 600000 -> 600000
            1 0 600000 -> 599999
            599999 0 600000 -> 1
            600000 0 600000 -> 600000
            1700000123456 0 600000 -> 276544
            5000 5000 600000 -> 600000
            4999 5000 600000 -> 1
            -1 0 600000 -> 1
            1700000123456 5000 600000 -> 281544
            10 0 0 -> 1
        """.trimIndent()
        val cases = listOf(
            Triple(0L, 0L, 600_000L), Triple(1L, 0L, 600_000L), Triple(599_999L, 0L, 600_000L),
            Triple(600_000L, 0L, 600_000L), Triple(1_700_000_123_456L, 0L, 600_000L),
            Triple(5_000L, 5_000L, 600_000L), Triple(4_999L, 5_000L, 600_000L), Triple(-1L, 0L, 600_000L),
            Triple(1_700_000_123_456L, 5_000L, 600_000L), Triple(10L, 0L, 0L),
        )
        val actual = cases.joinToString("\n") { (n, o, i) ->
            "$n $o $i -> ${StressWidgetProducer.msUntilNextTodayTick(n, o, i)}"
        }
        assertEquals(expected, actual)
    }

    @Test
    fun theReadingLoopLandsAFewSecondsAfterTheCurveLoopOnTheSameTick() {
        // Both loops wake on the same wall-clock tick; the reading one a fixed lag later, so it reads the
        // memo the curve just filled instead of scoring the day a second time.
        val now = 1_700_000_123_456L
        val curveWake = now + StressWidgetProducer.msUntilNextTodayTick(now)
        val readingWake = now + StressWidgetProducer.msUntilNextTodayTick(
            now, offsetMs = StressWidgetProducer.TODAY_READING_TICK_OFFSET_MS,
        )
        assertEquals(StressWidgetProducer.TODAY_READING_TICK_OFFSET_MS, readingWake - curveWake)
    }
}
