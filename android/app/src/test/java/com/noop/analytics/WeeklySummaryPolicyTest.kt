package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The "weekly summary ready" policy (#91). Same table as Swift `WeeklySummaryPolicyTests`. */
class WeeklySummaryPolicyTest {

    // 2026-06-08 is a Monday; last week starts 2026-06-01.
    @Test fun firesOnceForLastWeekAfterMondaySync() {
        assertEquals("2026-06-01", WeeklySummaryPolicy.weekToAnnounce(true, "2026-06-08", null, true))
        assertNull(WeeklySummaryPolicy.weekToAnnounce(true, "2026-06-08", "2026-06-01", true))
    }

    @Test fun waitsForTheFirstSyncOfTheWeek() {
        assertNull(WeeklySummaryPolicy.weekToAnnounce(true, "2026-06-08", "2026-05-25", false))
    }

    @Test fun offAndUnparseableNeverFire() {
        assertNull(WeeklySummaryPolicy.weekToAnnounce(false, "2026-06-08", null, true))
        assertNull(WeeklySummaryPolicy.weekToAnnounce(true, "bad", null, true))
    }

    @Test fun sundayLogicalDayDoesNotAnnounceTheWeekItIsIn() {
        assertNull(WeeklySummaryPolicy.weekToAnnounce(true, "2026-06-07", "2026-05-25", true))
    }

    @Test fun missedMondayStillFiresLaterThatWeek() {
        assertEquals("2026-06-01", WeeklySummaryPolicy.weekToAnnounce(true, "2026-06-11", "2026-05-25", true))
    }

    @Test fun currentWeekDataAndAnchor() {
        assertTrue(WeeklySummaryPolicy.hasCurrentWeekData(listOf("2026-06-05", "2026-06-08"), "2026-06-08"))
        assertFalse(WeeklySummaryPolicy.hasCurrentWeekData(listOf("2026-06-05", "2026-06-07"), "2026-06-08"))
        assertEquals("2026-06-07", WeeklySummaryPolicy.anchorDay("2026-06-01"))
    }

    @Test fun bodyIsTheTopFocalSentence() {
        assertNull(WeeklySummaryPolicy.body(WeeklyDigestEngine.build(emptyMap(), "2026-06-07")))
        val charge = (0 until 7).associate { WeeklyDigestEngine.addDays("2026-06-01", it) to 70.0 }
        val d = WeeklyDigestEngine.build(mapOf(WeeklyMetric.CHARGE to charge), "2026-06-07")
        assertEquals(d.focalPoints.first(), WeeklySummaryPolicy.body(d))
    }
}
