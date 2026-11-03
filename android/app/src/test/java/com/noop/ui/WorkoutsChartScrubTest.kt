package com.noop.ui

import com.noop.analytics.HeartRateRecovery
import com.noop.analytics.TrainingLoadEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure helpers behind the Hydration, Training Load and Workouts scrub cursors (#58). */
class WorkoutsChartScrubTest {

    @Test
    fun hydrationBarCentresMatchTheDrawnLayout() {
        // 3 bars over 320 px with 10 px gaps: each bar is 100 px wide, centres at 50, 160, 270.
        val f = hydrationBarCenterFractions(3, 320f, 10f)
        assertEquals(listOf(50f / 320f, 160f / 320f, 270f / 320f), f)
        // The finger just right of the first gap lands on the second bar.
        assertEquals(1, chartScrubNearestIndex(f, 320f, 112f))
        assertEquals(0, chartScrubNearestIndex(f, 320f, 0f))
        assertEquals(2, chartScrubNearestIndex(f, 320f, 400f))
    }

    @Test
    fun hydrationBarCentresDegenerateWidth() {
        assertEquals(listOf(0f, 0f), hydrationBarCenterFractions(2, 0f, 10f))
        assertEquals(emptyList<Float>(), hydrationBarCenterFractions(0, 100f, 10f))
    }

    @Test
    fun hydrationValueReadsInLitres() {
        assertEquals("1.8 L", hydrationScrubValue(1800.0))
        assertEquals("0.0 L", hydrationScrubValue(0.0))
        assertEquals("2.3 L", hydrationScrubValue(2250.0))
    }

    @Test
    fun trainingLoadValueNamesBothLines() {
        val p = TrainingLoadEngine.Point("2026-11-01", 50.0, 42.04, 51.25, -9.2)
        assertEquals("CTL 42.0 · ATL 51.3", trainingLoadScrubValue(p))
    }

    @Test
    fun hrrValueDashesMissingWindows() {
        val r = HeartRateRecovery.Result(endHr = 170, after1Minute = 25, after2Minutes = null, after5Minutes = -3)
        assertEquals(
            "1 min 25 · 2 min – · 5 min -3 bpm",
            hrrScrubValue(r, Triple("1 min", "2 min", "5 min"), "bpm"),
        )
    }

    @Test
    fun heatmapCellHitTestClampsOntoTheGrid() {
        // Gutter 20, month row 14, 10 px cells with 2 px gaps, 13 weeks.
        val g = HeatmapGeometry(leftPx = 20f, topPx = 14f, cellPx = 10f, gapPx = 2f, cols = 13)
        assertEquals(0 to 0, heatmapCellAt(g, 20f, 14f))
        assertEquals(1 to 2, heatmapCellAt(g, 33f, 39f))
        // The gap after a cell belongs to that cell.
        assertEquals(0 to 0, heatmapCellAt(g, 31.5f, 25.5f))
        // Gutter, month row and beyond the edges clamp to the nearest cell.
        assertEquals(0 to 0, heatmapCellAt(g, 0f, 0f))
        assertEquals(12 to 6, heatmapCellAt(g, 5000f, 5000f))
        assertNull(heatmapCellAt(g.copy(cols = 0), 30f, 30f))
    }

    @Test
    fun heatmapDayLabelFormatsAndFallsBack() {
        assertEquals("Mon 2 Nov 2026", heatmapDayLabel("2026-11-02"))
        assertEquals("garbage", heatmapDayLabel("garbage"))
    }
}
