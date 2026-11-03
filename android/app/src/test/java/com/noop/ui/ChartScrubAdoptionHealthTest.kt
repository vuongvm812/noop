package com.noop.ui

import com.noop.analytics.DosedBehavior
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure helpers behind the scrub cursors on Compare, Health (live HR), Insights (dose curve) and Lab Book. */
class ChartScrubAdoptionHealthTest {

    @Test
    fun compareSelectionDescriptionNamesEverySeriesAndGaps() {
        val text = compareSelectionDescription(
            dayLabel = "Mon 3 Nov 2026",
            rows = listOf("HRV" to "52 ms", "Resting HR" to null),
        )
        assertEquals("Mon 3 Nov 2026, HRV 52 ms, Resting HR No data", text)
    }

    @Test
    fun liveHrReadoutSnapsToNearestSample() {
        val samples = listOf(
            LiveHrSample(1_000L, 70.4),
            LiveHrSample(2_000L, 71.6),
            LiveHrSample(3_000L, 73.0),
        )
        val r = liveHrScrubReadout(samples, 2_400L)!!
        assertEquals(1, r.hit.index)
        assertNull(r.hit.gapAt)
        assertEquals("72 bpm", r.value)
    }

    @Test
    fun liveHrReadoutReportsDropoutAsNoData() {
        // One-second cadence with a five-second dropout between 3 s and 8 s.
        val samples = listOf(1_000L, 2_000L, 3_000L, 8_000L, 9_000L).map { LiveHrSample(it, 60.0) }
        val r = liveHrScrubReadout(samples, 5_500L)!!
        assertEquals(CHART_SCRUB_NO_DATA, r.value)
        assertEquals(6_000L, r.hit.gapAt)
    }

    @Test
    fun liveHrReadoutEmptyIsNull() {
        assertNull(liveHrScrubReadout(emptyList(), 0L))
    }

    @Test
    fun doseCurveLabels() {
        assertEquals("0 drinks", doseCurveDoseLabel(DosedBehavior.ALCOHOL, 0))
        assertEquals("1 drink", doseCurveDoseLabel(DosedBehavior.ALCOHOL, 1))
        assertEquals("AM", doseCurveDoseLabel(DosedBehavior.CAFFEINE, 0))
        assertEquals("2pm+", doseCurveDoseLabel(DosedBehavior.CAFFEINE, 2))
        assertEquals("−3% Charge", doseCurveScrubValue(-3.0, "%", "Charge"))
        assertEquals("+1.5 ms HRV", doseCurveScrubValue(1.5, " ms", "HRV"))
    }

    @Test
    fun doseChoiceLabelTopBinCarriesPlus() {
        val top = com.noop.analytics.DoseResponseEngine.maxCurveDose
        assertEquals("$top+", doseChoiceLabel(DosedBehavior.ALCOHOL, top))
        assertEquals("$top+ drinks", doseCurveDoseLabel(DosedBehavior.ALCOHOL, top))
    }

    @Test
    fun labSparkNearestIndexHonoursPadding() {
        // Four points across 106 px with 3 px padding sit at 3, 36.33, 69.67, 103.
        assertEquals(0, labSparkNearestIndex(4, 106f, 3f, 0f))
        assertEquals(1, labSparkNearestIndex(4, 106f, 3f, 40f))
        assertEquals(2, labSparkNearestIndex(4, 106f, 3f, 60f))
        assertEquals(3, labSparkNearestIndex(4, 106f, 3f, 200f))
    }
}
