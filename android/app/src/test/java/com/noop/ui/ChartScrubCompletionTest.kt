package com.noop.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.noop.analytics.RhythmScreener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure hit-test and callout helpers behind the scrub cursor on the charts #58 first left out: the Health
 * tile sparkline, the sleep stage rows, the Poincaré plot, the live R-R thread and the body-clock dial.
 */
class ChartScrubCompletionTest {

    @Test
    fun tileSparkSnapsAcrossTheFullWidth() {
        assertEquals(0, tileSparkIndexAt(5, 400f, 0f))
        assertEquals(2, tileSparkIndexAt(5, 400f, 210f))
        assertEquals(4, tileSparkIndexAt(5, 400f, 999f))
        assertNull(tileSparkIndexAt(1, 400f, 10f))
        assertNull(tileSparkIndexAt(5, 0f, 10f))
    }

    @Test
    fun tileSparkReadoutNamesValueAndNight() {
        val values = listOf(58.0, 61.0)
        val days = listOf("2026-11-01", "2026-11-02")
        val fmt: (Double) -> String = { "${it.toInt()} bpm" }
        assertEquals("61 bpm" to lastChargeDateLabel("2026-11-02"), tileSparkReadout(values, days, 1, fmt))
        assertEquals("58 bpm" to null, tileSparkReadout(values, emptyList(), 0, fmt))
        assertNull(tileSparkReadout(values, days, 2, fmt))
    }

    @Test
    fun stageRowSnapsToContainingThenNearestSegment() {
        val spans = listOf(0.0f to 0.25f, 0.75f to 0.25f)
        assertEquals(0, stageRowSegmentAt(spans, 0.1f))
        assertEquals(1, stageRowSegmentAt(spans, 0.9f))
        assertEquals(0, stageRowSegmentAt(spans, 0.3f))
        assertEquals(1, stageRowSegmentAt(spans, 0.7f))
        // Equidistant: the earlier segment.
        assertEquals(0, stageRowSegmentAt(spans, 0.5f))
        assertNull(stageRowSegmentAt(emptyList(), 0.5f))
        assertNull(stageRowSegmentAt(listOf(0.2f to 0f), 0.2f))
    }

    @Test
    fun stageRowSegmentTextIsClockRangeOrLength() {
        val onset = 1_762_131_600L
        assertEquals(
            "${clockTimeLabel(onset + 900, true)}–${clockTimeLabel(onset + 1800, true)}",
            stageRowSegmentText(0.25f to 0.25f, onset, 3600.0, true),
        )
        assertEquals("30m", stageRowSegmentText(0.0f to 0.5f, null, 3600.0, true))
    }

    @Test
    fun poincareSnapsInBothAxes() {
        // side 116, inset 8: 300 ms maps to 8 px, 1500 ms to 108 px; y grows upward.
        val points = listOf(
            RhythmScreener.PoincarePoint(300.0, 300.0),
            RhythmScreener.PoincarePoint(1500.0, 1500.0),
            RhythmScreener.PoincarePoint(300.0, 1500.0),
        )
        assertEquals(0, poincareNearestIndex(points, 116f, 8f, 10f, 100f))
        assertEquals(1, poincareNearestIndex(points, 116f, 8f, 100f, 10f))
        assertEquals(2, poincareNearestIndex(points, 116f, 8f, 12f, 12f))
        assertNull(poincareNearestIndex(emptyList(), 116f, 8f, 10f, 10f))
        assertNull(poincareNearestIndex(points, 0f, 8f, 10f, 10f))
    }

    @Test
    fun poincareCalloutNamesBothIntervals() {
        assertEquals("812 → 830 ms", poincareScrubText(RhythmScreener.PoincarePoint(812.4, 829.6)))
    }

    @Test
    fun rrThreadMatchesTheRenderersInset() {
        assertEquals(0, rrThreadIndexAt(3, 120f, 10f))
        assertEquals(1, rrThreadIndexAt(3, 120f, 60f))
        assertEquals(2, rrThreadIndexAt(3, 120f, 200f))
        assertNull(rrThreadIndexAt(1, 120f, 10f))
        assertNull(rrThreadIndexAt(3, 20f, 10f))
        val values = listOf(800, 810, 820)
        assertEquals(Offset(10f, 60f), rrThreadPoint(values, 0, 120f, 70f))
        assertEquals(Offset(110f, 10f), rrThreadPoint(values, 2, 120f, 70f))
    }

    @Test
    fun rrCalloutCountsBackFromTheLatestBeat() {
        val values = listOf(800, 900, 1000)
        assertEquals("800 ms" to "−1.9 s", rrScrubReadout(values, 0))
        assertEquals("1000 ms" to "0.0 s", rrScrubReadout(values, 2))
        assertNull(rrScrubReadout(values, 3))
    }

    @Test
    fun bodyClockPicksTheNearerBand() {
        // 200 px square at 1 px/dp: rim 90, reference band 59, night band 45.
        val size = Size(200f, 200f)
        assertEquals(BODY_CLOCK_ARC_NIGHT, bodyClockArcAt(Offset(145f, 100f), size, 1f))
        assertEquals(BODY_CLOCK_ARC_IDEAL, bodyClockArcAt(Offset(100f, 41f), size, 1f))
        // Equidistant (radius 52): the night arc.
        assertEquals(BODY_CLOCK_ARC_NIGHT, bodyClockArcAt(Offset(152f, 100f), size, 1f))
        assertNull(bodyClockArcAt(Offset(1f, 1f), Size.Zero, 1f))
    }

    @Test
    fun bodyClockHourLabelRoundsAndWraps() {
        assertEquals("23:30", bodyClockHourLabel(23.5, true))
        assertEquals("00:00", bodyClockHourLabel(23.99, true, 5))
        assertEquals("1:15 PM", bodyClockHourLabel(13.25, false))
    }
}
