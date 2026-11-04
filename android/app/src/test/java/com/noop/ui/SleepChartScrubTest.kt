package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId

/** Pure callout and hit-test helpers behind the Sleep charts' scrub cursor (#58). */
class SleepChartScrubTest {

    @Test
    fun motionPercentIsShareOfTheNightPeak() {
        val epochs = listOf(0.0, 2.0, 4.0, 1.0)
        assertEquals(0, motionScrubPercent(epochs, 0))
        assertEquals(50, motionScrubPercent(epochs, 1))
        assertEquals(100, motionScrubPercent(epochs, 2))
        assertEquals(25, motionScrubPercent(epochs, 3))
        assertNull(motionScrubPercent(epochs, 4))
        assertNull(motionScrubPercent(listOf(0.0, 0.0), 0))
    }

    @Test
    fun motionTimestampSpreadsEpochsAcrossTheWindow() {
        assertEquals(1_000L, motionScrubTimestamp(1_000L, 2_000L, 3, 0))
        assertEquals(1_500L, motionScrubTimestamp(1_000L, 2_000L, 3, 1))
        assertEquals(2_000L, motionScrubTimestamp(1_000L, 2_000L, 3, 2))
        assertNull(motionScrubTimestamp(null, 2_000L, 3, 1))
        assertNull(motionScrubTimestamp(2_000L, 1_000L, 3, 1))
        assertNull(motionScrubTimestamp(1_000L, 2_000L, 3, 3))
    }

    @Test
    fun slotCentresAndNearestSlot() {
        val f = slotCentreFractions(4)
        assertEquals(listOf(0.125f, 0.375f, 0.625f, 0.875f), f)
        assertEquals(0, chartScrubNearestIndex(f, 400f, 10f))
        assertEquals(1, chartScrubNearestIndex(f, 400f, 160f))
        assertEquals(3, chartScrubNearestIndex(f, 400f, 999f))
    }

    @Test
    fun debtNightIsNamedByItsEvening() {
        assertEquals("Sat 12 Sep", debtNightLabel("2026-09-13"))
        assertEquals("garbage", debtNightLabel("garbage"))
    }

    @Test
    fun consistencyCalloutNamesWindowAndNight() {
        val utc = ZoneId.of("UTC")
        // 2026-09-12 22:50 UTC -> 2026-09-13 06:48 UTC.
        val onset = 1_789_253_400L
        val wake = 1_789_282_080L
        assertEquals("22:50 - 06:48" to "Sat 12 Sep", consistencyScrubText(onset, wake, true, utc))
        assertEquals("10:50 PM - 6:48 AM" to "Sat 12 Sep", consistencyScrubText(onset, wake, false, utc))
    }

    @Test
    fun hypnogramGapReadsNoData() {
        assertEquals(CHART_SCRUB_NO_DATA, hypnogramScrubStageLabel(ScrubHit("", 0L)))
        assertEquals("DEEP", hypnogramScrubStageLabel(ScrubHit("deep", 0L)))
    }
}
