package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Pins the pure pieces the chart primitives use to adopt the shared scrub cursor (#58): the callout's two
 * lines, the gap label, the spoken state, the TalkBack step and the bar-bin mapping.
 */
class ChartScrubLabelTest {

    private val utc: ZoneId = ZoneOffset.UTC

    @Test fun partsPickTheFormatterAndTheWhenLabel() {
        val ts = 1_752_676_320L // 2025-07-16 14:32 UTC
        assertEquals("87 bpm" to "14:32", lineChartSelectionParts(87.2, { "${it.toInt()} bpm" }, ts, utc))
        assertEquals("87" to "16 Jul", lineChartSelectionParts(87.0, null, ts, utc, pointLabel = "16 Jul"))
        assertEquals("87" to null, lineChartSelectionParts(87.0, null))
        assertEquals("87" to null, lineChartSelectionParts(87.0, null, pointLabel = " "))
    }

    @Test fun anIntradayGapReadsAsClockTime() {
        val times = listOf(0L, 60L, 600L, 3600L)
        assertEquals("00:05", chartScrubGapLabel(300L, times, utc))
    }

    @Test fun aDailyGapOnEpochDaysReadsAsTheUtcDate() {
        val d = 86_400L
        val start = 20_285L * d // 2025-07-16
        val times = listOf(start, start + d, start + 3 * d, start + 4 * d)
        // A zone west of UTC must not shift an epoch-day midnight back to the previous date.
        assertEquals("18 Jul", chartScrubGapLabel(start + 2 * d, times, ZoneId.of("America/Los_Angeles")))
    }

    @Test fun aLongSpanOfLocalTimesUsesTheLocalDate() {
        val zone = ZoneId.of("America/Los_Angeles")
        val d = 86_400L
        val start = 20_285L * d + 7 * 3600L // 00:00 Los Angeles on 16 Jul
        val times = listOf(start, start + d, start + 3 * d)
        assertEquals("17 Jul", chartScrubGapLabel(start + d + 3600L, times, zone))
    }

    @Test fun spokenStateNamesTheLabelFirst() {
        assertEquals("16 Jul, 87 bpm", chartScrubSpoken("87 bpm", "16 Jul"))
        assertEquals("87 bpm", chartScrubSpoken("87 bpm", null))
        assertEquals("17 Jul, No data", chartScrubSpoken(CHART_SCRUB_NO_DATA, "17 Jul"))
    }

    @Test fun talkBackStepsEnterFromEitherEndAndHoldAtTheEnds() {
        assertEquals(0, chartScrubStep(-1, 5, forward = true))
        assertEquals(4, chartScrubStep(-1, 5, forward = false))
        assertEquals(3, chartScrubStep(2, 5, forward = true))
        assertEquals(4, chartScrubStep(4, 5, forward = true))
        assertEquals(0, chartScrubStep(0, 5, forward = false))
        assertEquals(-1, chartScrubStep(-1, 0, forward = true))
    }

    @Test fun cursorTimeFollowsTheTimeAxis() {
        val times = listOf(1_000L, 2_000L, 5_000L)
        assertEquals(1_000L, chartScrubTimeAtX(times, 400f, -10f))
        assertEquals(3_000L, chartScrubTimeAtX(times, 400f, 200f))
        assertEquals(5_000L, chartScrubTimeAtX(times, 400f, 999f))
    }

    @Test fun bucketIndexMatchesTheDownsampleBounds() {
        // No collapse: the index is the bar.
        assertEquals(7, meanBucketIndexOf(7, 10, 20))
        // 10 values into 4 buckets: bounds lo = 0, 2, 5, 7.
        val expected = listOf(0, 0, 1, 1, 1, 2, 2, 3, 3, 3)
        assertEquals(expected, (0 until 10).map { meanBucketIndexOf(it, 10, 4) })
        // Every source index lands in a bucket whose range holds it, at a ragged ratio.
        val n = 803
        val target = 317
        for (i in 0 until n) {
            val b = meanBucketIndexOf(i, n, target)
            val lo = (b.toLong() * n / target).toInt()
            val hi = ((b + 1).toLong() * n / target).toInt()
            assert(i in lo until hi) { "index $i not in bucket $b [$lo, $hi)" }
        }
    }

    @Test fun noGapLabelWithoutAReading() {
        assertNull(lineChartSelectionParts(1.0, null).second)
    }
}
