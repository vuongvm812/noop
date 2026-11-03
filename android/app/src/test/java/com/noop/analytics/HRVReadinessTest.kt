package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * HRVReadiness (Plews/Altini SWC) — the OPT-IN experimental HRV-readiness tier readout.
 *
 * The oracle for the Swift HRVReadinessTests; keep the two in lockstep (same fixtures, same assertions).
 * Pins each tier on a fixture series and the < MIN_NIGHTS calibrating/null edge (including that
 * physiologically implausible nights are dropped BEFORE the gate). Read-only variant: it feeds no
 * downstream gate, so there is nothing else to assert.
 */
class HRVReadinessTest {

    private val today = "2026-09-30"

    /** Consecutive nights ending today (the last value is today's night). */
    private fun eval(values: List<Double?>): HRVReadinessResult? {
        val t = Baselines.isoEpochDay(today)!!
        val keys = values.indices.map { Baselines.isoDayFromEpochDay(t - values.size + 1 + it) }
        return HRVReadiness.evaluate(keys, values, today)
    }

    // A high-then-primed history: 53 nights at 50 ms, the last 7 at 75 ms — the 7-night baseline sits
    // well above the personal normal band, so the tier reads PRIMED.
    private val primedHistory: List<Double?> = List(53) { 50.0 } + List(7) { 75.0 }

    // The mirror: 53 nights at 50 ms, the last 7 at 38 ms — the 7-night baseline sits below the band, SUPPRESSED.
    private val suppressedHistory: List<Double?> = List(53) { 50.0 } + List(7) { 38.0 }

    // A perfectly flat history: baseline7 == longMean exactly, so the band collapses to the mean → NORMAL.
    private val flatHistory: List<Double?> = List(60) { 60.0 }

    // ── Tier ────────────────────────────────────────────────────────────────

    @Test
    fun primedFixtureTier() {
        val r = eval(primedHistory)
        assertNotNull(r)
        assertEquals(ReadinessTier.PRIMED, r!!.tier)
        // The 7-night baseline is the last-7 mean, exp back to ms: exp(ln 75) = 75.
        assertEquals(75.0, r.baseline7Ms, 1e-6)
        // Baseline above the long mean → the overreaching (falling-CV-while-below-mean) watch cannot fire.
        assertFalse(r.overreachingWatch)
    }

    @Test
    fun suppressedFixtureTier() {
        val r = eval(suppressedHistory)
        assertNotNull(r)
        assertEquals(ReadinessTier.SUPPRESSED, r!!.tier)
        assertEquals(38.0, r.baseline7Ms, 1e-6)
    }

    @Test
    fun flatHistoryIsNormalAndBandCollapsesToMean() {
        val r = eval(flatHistory)
        assertNotNull(r)
        assertEquals(ReadinessTier.NORMAL, r!!.tier)
        // baseline7 == longMean and longSD == 0 → the band collapses to the mean, all reading back to 60 ms.
        assertEquals(60.0, r.baseline7Ms, 1e-6)
        assertEquals(60.0, r.normalLowMs, 1e-6)
        assertEquals(60.0, r.normalHighMs, 1e-6)
    }

    // ── Calibrating / null edge (< MIN_NIGHTS valid nights) ───────────────────

    @Test
    fun belowMinNightsIsNull() {
        // 13 valid nights is one short of the gate → null (honest calibrating, never fabricated).
        assertNull(eval(List(HRVReadiness.MIN_NIGHTS - 1) { 60.0 }))
        // Exactly MIN_NIGHTS valid nights → a reading appears.
        assertNotNull(eval(List(HRVReadiness.MIN_NIGHTS) { 60.0 }))
    }

    @Test
    fun outOfRangeNightsAreDroppedBeforeGate() {
        // 13 in-range + 5 implausible (300 ms > hrvCfg.maxVal) → only 13 valid → still calibrating (null).
        val mixed: List<Double?> = List(5) { 300.0 } + List(13) { 60.0 }
        assertNull(eval(mixed))
        // Add one more in-range night → 14 valid → a reading appears (the implausible nights never count).
        assertNotNull(eval(List(5) { 300.0 } + List(14) { 60.0 }))
    }

    // ── Calendar windows (#53) ──────────────────────────────────────────────

    /** The "7-day" baseline is seven CALENDAR days: after a fortnight off the strap there is no 7-day
     *  reading at all rather than a stale one. */
    @Test
    fun aWeeksOffLeavesNoSevenDayReading() {
        val history: List<Double?> = List(40) { 50.0 } + List(10) { null }
        assertNull(eval(history))
        // Three nights back on the strap within the last week are enough again.
        assertNotNull(eval(history + listOf(38.0, 38.0, 38.0)))
    }

    /** Only the last 60 calendar days form the normal band: older nights do not count toward the gate. */
    @Test
    fun theLongWindowIsSixtyCalendarDays() {
        val old: List<Double?> = List(100) { 50.0 }
        val gap: List<Double?> = List(50) { null }
        assertNull(eval(old + gap + List(10) { 50.0 }))
        assertNotNull(eval(old + gap + List(14) { 50.0 }))
    }

    /** Days after `today` and unparseable keys are ignored. */
    @Test
    fun futureAndUnparseableDaysAreIgnored() {
        val t = Baselines.isoEpochDay(today)!!
        val keys = (0 until 20).map { Baselines.isoDayFromEpochDay(t - 19 + it) }
        val base = HRVReadiness.evaluate(keys, List(20) { 60.0 }, today)
        val noisy = HRVReadiness.evaluate(keys + listOf("2026-10-01", "junk"), List<Double?>(20) { 60.0 } + listOf(10.0, 10.0), today)
        assertEquals(base, noisy)
    }
}
