package com.noop.ui

import com.noop.data.DailyMetric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #911: the SHARED anchor selector both widget producers (the in-app republish in AppViewModel AND the
 * background-service producer in WhoopConnectionService) resolve the widget's day through, so the two
 * can never drift apart around the rollover. Pins the SAME selection the iOS [WidgetAnchorTests] asserts
 * (Swift Repository.widgetAnchor) so the two platforms stay byte-for-byte in agreement: anchor on today's
 * row when scored, else carry the freshest STRICTLY-PRIOR scored day, with the #304 pre-04:00 carve-out
 * and the #547 future-day guard folded in.
 */
class WidgetAnchorTest {

    /** A day row with an optional recovery + optional banked night (the #304 carve-out keys off the
     *  banked night, `totalSleepMin`). */
    private fun day(key: String, recovery: Double?, sleepMin: Double? = null, strain: Double? = null) =
        DailyMetric(
            deviceId = "my-whoop", day = key, recovery = recovery,
            totalSleepMin = sleepMin, strain = strain,
        )

    // (a) today scored -> today's own row.
    @Test
    fun todayScored_anchorsOnTodaysRow() {
        val days = listOf(day("2026-06-18", 72.0), day("2026-06-19", 55.0, strain = 9.0))
        val anchor = widgetAnchorRow(days, logicalKey = "2026-06-19", localKey = "2026-06-19")
        assertEquals("2026-06-19", anchor?.day)
        assertEquals(55.0, anchor?.recovery)
    }

    // (b) today unscored, a prior scored day exists -> the freshest STRICTLY-PRIOR scored row.
    @Test
    fun todayUnscored_carriesFreshestPriorScoredDay() {
        val days = listOf(
            day("2026-06-17", 60.0),
            day("2026-06-18", 72.0),
            day("2026-06-19", null), // today, banked but not scored yet
        )
        val anchor = widgetAnchorRow(days, logicalKey = "2026-06-19", localKey = "2026-06-19")
        assertEquals("2026-06-18", anchor?.day)
        assertEquals(72.0, anchor?.recovery)
    }

    // (b, cont.) an unscored today row must NOT be echoed as its own anchor.
    @Test
    fun todayUnscoredPartialRow_isNotEchoed() {
        val days = listOf(day("2026-06-18", 72.0), day("2026-06-19", null))
        val anchor = widgetAnchorRow(days, logicalKey = "2026-06-19", localKey = "2026-06-19")
        assertEquals("2026-06-18", anchor?.day)
    }

    // (c) #304 pre-04:00 carve-out: local calendar day differs from the logical day. resolveTodayRow
    // prefers the LOCAL banked row, so the anchor's carriedKey is that local row's own day and a same-day
    // later-scored row is NOT resurfaced past it.
    @Test
    fun pre0400CarveOut_prefersLocalBankedRow_notASameDayLaterRow() {
        val days = listOf(
            day("2026-06-16", 60.0),
            day("2026-06-17", 71.0),                    // yesterday, scored
            day("2026-06-18", null, sleepMin = 430.0),  // local banked night, unscored = today
        )
        val anchor = widgetAnchorRow(days, logicalKey = "2026-06-17", localKey = "2026-06-18")
        // today (the local 18th row) is unscored, so carriedKey == "2026-06-18" and the freshest
        // STRICTLY-PRIOR scored day (the 17th) carries over, NOT re-echoing the local row or the 16th.
        assertEquals("2026-06-17", anchor?.day)
        assertEquals(71.0, anchor?.recovery)
    }

    @Test
    fun pre0400CarveOut_localBankedRowScored_isItsOwnAnchor() {
        val days = listOf(
            day("2026-06-17", 71.0),
            day("2026-06-18", 66.0, sleepMin = 430.0),
        )
        val anchor = widgetAnchorRow(days, logicalKey = "2026-06-17", localKey = "2026-06-18")
        assertEquals("2026-06-18", anchor?.day)
        assertEquals(66.0, anchor?.recovery)
    }

    // (d) a future-dated row (#547) is never selected as the anchor.
    @Test
    fun neverAnchorsAFutureDatedRow() {
        val days = listOf(
            day("2026-06-17", 60.0),
            day("2026-06-18", 72.0),
            day("2026-06-19", null),  // today, unscored
            day("2026-07-12", 80.0),  // stray future row
        )
        val anchor = widgetAnchorRow(days, logicalKey = "2026-06-19", localKey = "2026-06-19")
        assertEquals("2026-06-18", anchor?.day)
        assertEquals(72.0, anchor?.recovery)
    }

    @Test
    fun futureOnlyBesidesToday_returnsNull() {
        val days = listOf(
            day("2026-06-19", null),  // today, unscored
            day("2026-07-12", 80.0),  // future-only
        )
        assertNull(widgetAnchorRow(days, logicalKey = "2026-06-19", localKey = "2026-06-19"))
    }

    // (e) no data -> null (blank widget, no crash).
    @Test
    fun noData_returnsNull() {
        assertNull(widgetAnchorRow(emptyList(), logicalKey = "2026-06-19", localKey = "2026-06-19"))
    }

    @Test
    fun noPriorEverScored_returnsNull() {
        val days = listOf(day("2026-06-18", null), day("2026-06-19", null))
        assertNull(widgetAnchorRow(days, logicalKey = "2026-06-19", localKey = "2026-06-19"))
    }

    // #34: no Charge on any day (a Garmin-only wearer still calibrating) -> the freshest day up to and
    // including today that carries any other widget value.
    @Test
    fun noChargeAnywhere_fallsBackToTodayWithEffort() {
        val days = listOf(
            DailyMetric(deviceId = "garmin", day = "2026-06-18", avgHrv = 61.0),
            DailyMetric(deviceId = "garmin", day = "2026-06-19", strain = 9.0),
        )
        assertEquals("2026-06-19", widgetAnchorRow(days, logicalKey = "2026-06-19", localKey = "2026-06-19")?.day)
    }

    @Test
    fun noChargeAnywhere_emptyToday_fallsBackToPriorVitalsDay() {
        val days = listOf(
            DailyMetric(deviceId = "garmin", day = "2026-06-18", avgHrv = 61.0, restingHr = 52),
            DailyMetric(deviceId = "garmin", day = "2026-06-19"),
        )
        assertEquals("2026-06-18", widgetAnchorRow(days, logicalKey = "2026-06-19", localKey = "2026-06-19")?.day)
    }

    @Test
    fun noChargeAnywhere_neverFallsBackToAFutureRow() {
        val days = listOf(
            DailyMetric(deviceId = "garmin", day = "2026-06-19"),
            DailyMetric(deviceId = "garmin", day = "2026-07-12", strain = 9.0),
        )
        assertNull(widgetAnchorRow(days, logicalKey = "2026-06-19", localKey = "2026-06-19"))
    }

    // #34: a Charge on an OLDER day still wins over newer unscored rows that carry Effort or HRV.
    @Test
    fun chargeOnOlderDay_winsOverNewerUnscoredRows() {
        val days = listOf(
            day("2026-06-17", 64.0),
            DailyMetric(deviceId = "garmin", day = "2026-06-18", avgHrv = 61.0),
            DailyMetric(deviceId = "garmin", day = "2026-06-19", strain = 9.0),
        )
        val anchor = widgetAnchorRow(days, logicalKey = "2026-06-19", localKey = "2026-06-19")
        assertEquals("2026-06-17", anchor?.day)
        assertEquals(64.0, anchor?.recovery)
    }

    /**
     * #34 parity oracle. The expected string is the verbatim stdout of the Swift `Repository.widgetAnchor`
     * (resolveToday + widgetAnchor + hasWidgetValue lifted out of Repository.swift, compiled with
     * `swiftc -O`) over the same enumeration: rows 17/18/19 each absent, empty, or carrying exactly one
     * of Charge / Effort / sleep / HRV / resting HR, plus a future row 20 absent / scored / with Effort,
     * under the daytime keys (19, 19) and the pre-04:00 carve-out keys (18, 19). Each char is the last
     * digit of the anchor's day, or '-' for none.
     */
    @Test
    fun anchorMatchesSwiftOracle() {
        fun row(day: String, s: Int): DailyMetric? = when (s) {
            0 -> null
            1 -> DailyMetric(deviceId = "d", day = day)
            2 -> DailyMetric(deviceId = "d", day = day, recovery = 50.0)
            3 -> DailyMetric(deviceId = "d", day = day, strain = 9.0)
            4 -> DailyMetric(deviceId = "d", day = day, totalSleepMin = 400.0)
            5 -> DailyMetric(deviceId = "d", day = day, avgHrv = 60.0)
            else -> DailyMetric(deviceId = "d", day = day, restingHr = 55)
        }
        val out = StringBuilder()
        for ((lk, ck) in listOf("2026-06-19" to "2026-06-19", "2026-06-18" to "2026-06-19")) {
            for (a in 0 until 7) for (b in 0 until 7) for (c in 0 until 7) for (f in listOf(0, 2, 3)) {
                val days = listOfNotNull(row("2026-06-17", a), row("2026-06-18", b), row("2026-06-19", c), row("2026-06-20", f))
                out.append(widgetAnchorRow(days, lk, ck)?.day?.last() ?: '-')
            }
        }
        val expected = listOf(
            "------999999999999999------99999999999999988888899988888888888888888899999999999999988888899999999",
            "9999999888888999999999999999888888999999999999999------999999999999999------9999999999999998888889",
            "99888888888888888888999999999999999888888999999999999999888888999999999999999888888999999999999999",
            "77777799977777777777777777799977777777777788888899988888888888877777799977777777777777777799977777",
            "77777777777779997777777777777777779997777777777777777779999999999999997777779999999999999998888889",
            "99888888888888888888999999999999999888888999999999999999888888999999999999999888888999999999999999",
            "77777799999999999999977777799999999999999988888899988888888888888888899999999999999988888899999999",
            "99999998888889999999999999998888889999999999999997777779999999999999997777779999999999999998888889",
            "99888888888888888888999999999999999888888999999999999999888888999999999999999888888999999999999999",
            "77777799999999999999977777799999999999999988888899988888888888888888899999999999999988888899999999",
            "9999999888888999999999999999888888999999999999999------------999------------------999------8888888",
            "88888888888888888888888888999888888888888888888999888888888888888888999888888888888888888999888888",
            "------------999------------------999------88888888888888888888888888888888899988888888888888888899",
            "98888888888888888889998888888888888888889998888887777777777777777777777777777777777777777778888888",
            "88888888888888777777777777777777777777777777777777777777777777777777777777777777777777777777777777",
            "77777777777799977777777777777777799977777788888888888888888888888888888888899988888888888888888899",
            "98888888888888888889998888888888888888889998888887777777777779997777777777777777779997777778888888",
            "88888888888888888888888888999888888888888888888999888888888888888888999888888888888888888999888888",
            "77777777777799977777777777777777799977777788888888888888888888888888888888899988888888888888888899",
            "98888888888888888889998888888888888888889998888887777777777779997777777777777777779997777778888888",
            "88888888888888888888888888999888888888888888888999888888888888888888999888888888888888888999888888",
        ).joinToString("")
        assertEquals(expected, out.toString())
    }
}
