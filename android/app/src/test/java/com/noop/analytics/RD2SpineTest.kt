package com.noop.analytics

import com.noop.data.DailyMetric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Faithful Kotlin port of
 * Packages/StrandAnalytics/Tests/StrandAnalyticsTests/RD2SpineTests.swift (RD2, PR #469).
 *
 * RD2: the readiness HRV signal reads a trailing personal baseline that follows a recent sustained shift
 * (a device swap, a fitness change) instead of rejecting the new normal, and still flags a genuine drop.
 * Since #53 that baseline is the Charge one (the trailing 60-day lnRMSSD mean and SD), not a separate
 * re-folded EWMA. Same fixtures and thresholds as the Swift test.
 */
class RD2SpineTest {

    // Readiness end-to-end (ln-space HRV) ----------------------------------------------------------

    private fun d(i: Int, hrv: Double?, rhr: Int?): DailyMetric = DailyMetric(
        deviceId = "test",
        day = "2024-03-%02d".format(i),
        restingHr = rhr,
        avgHrv = hrv,
        strain = 10.0,
    )

    @Test
    fun readinessAdaptsToRecentSustainedHRVShift() {
        // 12 old nights at 130 ms, then 18 recent nights settled at 85 ms (a sustained shift), today at
        // the new normal 85. It must NOT read BAD: the trailing baseline holds the recent block, where a
        // hard-outlier-rejecting incremental fold stayed stuck at 130.
        val days = mutableListOf<DailyMetric>()
        for (i in 1..12) days.add(d(i, hrv = 130.0, rhr = 52))
        for (i in 13..30) days.add(d(i, hrv = 85.0, rhr = 52))
        days.add(d(31, hrv = 85.0, rhr = 52))   // today: the new normal
        val hrv = ReadinessEngine.evaluate(days).signals.firstOrNull { it.key == "hrv" }
        assertNotNull(hrv)
        assertNotEquals(
            "today at the recent sustained normal must not read BAD",
            ReadinessEngine.Flag.BAD, hrv!!.flag,
        )
    }

    @Test
    fun genuineLowStillFlagsAfterSpineAdoption() {
        // Regression: the baseline must not blunt a real drop. Stable ~95 ms baseline, today a clear 68 ms.
        val days = mutableListOf<DailyMetric>()
        for (i in 1..30) days.add(d(i, hrv = if (i % 2 == 0) 97.0 else 93.0, rhr = 52))
        days.add(d(31, hrv = 68.0, rhr = 52))
        val hrv = ReadinessEngine.evaluate(days).signals.firstOrNull { it.key == "hrv" }
        assertEquals("a genuine ~28% HRV drop must still read BAD", ReadinessEngine.Flag.BAD, hrv?.flag)
    }
}
