package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #58: the pure helpers behind the tile sparkline callouts and the scrubbable HR-zone strip. */
class ChartScrubSparkTest {

    @Test fun sparkTailLabelsNameTheDaysTheTileDraws() {
        val series = listOf(1.0, 2.0, 3.0, 4.0)
        val days = listOf("2026-10-30", "2026-10-31", "2026-11-01", "2026-11-02")
        assertEquals(listOf("1 Nov", "2 Nov"), sparkTailDayLabels(series, days, 2))
        assertEquals(listOf("30 Oct", "31 Oct", "1 Nov", "2 Nov"), sparkTailDayLabels(series, days, 30))
    }

    @Test fun sparkTailLabelsRefuseMisalignedDays() {
        assertNull(sparkTailDayLabels(listOf(1.0, 2.0), listOf("2026-11-02"), 30))
        assertNull(sparkTailDayLabels(listOf(1.0, 2.0), emptyList(), 30))
    }

    @Test fun spo2DaysFollowTheSeriesTheTileDraws() {
        val cal = listOf(97.0, 98.0)
        val cand = listOf(95.0, 96.0, 94.0)
        val calDays = listOf("2026-11-01", "2026-11-02")
        val candDays = listOf("2026-10-31", "2026-11-01", "2026-11-02")
        assertEquals(calDays, spo2SparkDays(cal, cand, calDays, candDays))
        assertEquals(candDays, spo2SparkDays(listOf(97.0), cand, listOf("2026-11-02"), candDays))
        assertEquals(listOf("2026-11-02"), spo2SparkDays(listOf(97.0), listOf(95.0), listOf("2026-11-02"), listOf("2026-11-01")))
    }

    @Test fun segmentIndexFollowsTheDrawnWidths() {
        // Weights 1 : 0 : 3 over 100 px → segment 0 spans 0–25, segment 2 spans 25–100; 1 takes no width.
        val w = listOf(1f, 0f, 3f)
        assertEquals(0, segmentBarIndexAt(w, 100f, 10f))
        assertEquals(2, segmentBarIndexAt(w, 100f, 25f))
        assertEquals(2, segmentBarIndexAt(w, 100f, 99f))
        assertEquals(0, segmentBarIndexAt(w, 100f, -5f))
        assertEquals(2, segmentBarIndexAt(w, 100f, 140f))
        assertEquals(1, segmentBarIndexAt(listOf(0f, 2f, Float.NaN), 50f, 0f))
    }

    @Test fun segmentIndexIsMinusOneWhenNothingIsDrawn() {
        assertEquals(-1, segmentBarIndexAt(emptyList(), 100f, 10f))
        assertEquals(-1, segmentBarIndexAt(listOf(0f, 0f), 100f, 10f))
        assertEquals(-1, segmentBarIndexAt(listOf(1f), 0f, 0f))
    }

    @Test fun zoneCalloutsNameShareTimeAndZone() {
        val callouts = zoneSegmentCallouts(listOf(12.0, 0.0, 36.0, 72.0, 0.0), 120.0) { "$it%" }
        assertEquals("10% · 12m" to "Z1", callouts[0])
        assertEquals("30% · 36m" to "Z3", callouts[2])
        assertEquals("60% · 1h 12m" to "Z4", callouts[3])
        assertEquals(5, callouts.size)
    }
}
