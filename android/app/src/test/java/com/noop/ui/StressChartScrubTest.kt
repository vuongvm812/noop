package com.noop.ui

import com.noop.widget.StressPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pins where a scrub on the Today stress card lands on the trace's time axis (#58). */
class StressChartScrubTest {

    private val half = 1_800L
    private val t0 = 1_700_000_000L

    private fun series(vararg levels: Double?): List<StressPoint> =
        levels.mapIndexed { i, l -> StressPoint(t0 + i * half, l) }

    @Test
    fun `empty series resolves to nothing`() {
        assertNull(stressTraceScrubHit(emptyList(), 10f, 100f))
    }

    @Test
    fun `x maps onto the time span and snaps to the nearest point`() {
        val pts = series(1.0, 2.0, 0.5, 1.5, 2.5) // span 4 steps across 400 px
        assertEquals(ChartScrubHit(0), stressTraceScrubHit(pts, 0f, 400f))
        assertEquals(ChartScrubHit(1), stressTraceScrubHit(pts, 140f, 400f))
        assertEquals(ChartScrubHit(2), stressTraceScrubHit(pts, 160f, 400f))
        assertEquals(ChartScrubHit(4), stressTraceScrubHit(pts, 400f, 400f))
    }

    @Test
    fun `an exact midpoint picks the earlier point`() {
        val pts = series(1.0, 2.0, 0.5, 1.5, 2.5)
        assertEquals(ChartScrubHit(1), stressTraceScrubHit(pts, 150f, 400f))
    }

    @Test
    fun `x outside the trace clamps to its ends`() {
        val pts = series(1.0, 2.0, 0.5)
        assertEquals(ChartScrubHit(0), stressTraceScrubHit(pts, -50f, 200f))
        assertEquals(ChartScrubHit(2), stressTraceScrubHit(pts, 900f, 200f))
    }

    @Test
    fun `an unscored point is still a point, not a gap`() {
        val pts = series(1.0, null, 0.5)
        assertEquals(ChartScrubHit(1), stressTraceScrubHit(pts, 100f, 200f))
    }

    @Test
    fun `a slot missing from the series resolves as a gap at that slot`() {
        // Points at 0, 1, 2 and 5 steps: steps 3 and 4 are missing entirely.
        val pts = listOf(0L, 1L, 2L, 5L).map { StressPoint(t0 + it * half, 1.0) }
        val hit = stressTraceScrubHit(pts, 400f, 500f) // cursor at step 4
        assertEquals(ChartScrubHit(3, t0 + 4 * half), hit)
    }

    @Test
    fun `a lone point or zero width resolves to the first point`() {
        assertEquals(ChartScrubHit(0), stressTraceScrubHit(series(1.0), 50f, 100f))
        assertEquals(ChartScrubHit(0), stressTraceScrubHit(series(1.0, 2.0), 50f, 0f))
    }
}
