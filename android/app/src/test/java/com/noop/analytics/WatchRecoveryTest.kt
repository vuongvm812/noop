package com.noop.analytics

import com.noop.data.DailyMetric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kotlin twin of the macOS WatchRecoveryTests: the honesty-critical recovery-from-daily-aggregate engine
 * behind source-only Charge (Apple Watch / Health Connect / Oura-Fitbit-Garmin import, #823). A daily
 * source gives a sparse HRV + resting HR rather than the strap's dense R-R stream, so these fixtures pin the
 * BEHAVIOUR (at-baseline ~ mid, high-HRV / low-RHR -> high, thin history / missing today -> null +
 * calibrating) and the #53 baseline (trailing 60 days, day-1 anchor) regardless of the exact logistic
 * constants, which are inherited unchanged from RecoveryScorer so source-only recovery and strap recovery
 * sit on the same scale. Mirrors the Swift WatchRecoveryTests case for case.
 *
 * The pinned literals are Swift values, printed by a standalone oracle executable linked against the
 * production `StrandAnalytics` package (so they cannot drift from the Swift source), over the same
 * fixtures built by the same `rows` helper:
 *
 *   print(WatchRecovery.score(day: "2026-03-01", rows: rows(sdnn: …, rhr: …, todaySDNN: …, todayRHR: …)))
 */
class WatchRecoveryTest {

    private val scoredDay = "2026-03-01"

    private fun row(day: String, hrv: Double?, rhr: Int? = null) =
        DailyMetric(deviceId = "apple-health", day = day, restingHr = rhr, avgHrv = hrv)

    /** `count` consecutive nights ending the day before [scoredDay] (oldest first), then today's row.
     *  null entries are missing nights. */
    private fun rows(
        sdnn: List<Double?>, rhr: List<Double?> = emptyList(), todaySdnn: Double?, todayRhr: Int?,
    ): List<DailyMetric> {
        val n = maxOf(sdnn.size, rhr.size)
        val anchor = Baselines.isoEpochDay(scoredDay)!!
        val out = ArrayList<DailyMetric>()
        for (i in 0 until n) {
            val day = Baselines.isoDayFromEpochDay(anchor - n + i)
            out.add(row(day, hrv = sdnn.getOrNull(i), rhr = rhr.getOrNull(i)?.toInt()))
        }
        out.add(row(scoredDay, hrv = todaySdnn, rhr = todayRhr))
        return out
    }

    private fun score(
        sdnn: List<Double?>, rhr: List<Double?> = emptyList(), todaySdnn: Double?, todayRhr: Int?,
    ): WatchRecovery.Result = WatchRecovery.score(scoredDay, rows(sdnn, rhr, todaySdnn, todayRhr))

    private val hrv14: List<Double?> = List(14) { 45.0 }
    private val rhr14: List<Double?> = List(14) { 52.0 }

    /** HRV today equal to the baseline and RHR equal to baseline -> mid recovery, solid confidence (14
     *  nights of history clears the trusted gate). */
    @Test
    fun atBaselineGivesMidRecoverySolid() {
        val out = score(hrv14, rhr14, todaySdnn = 45.0, todayRhr = 52)
        assertNotNull(out.recovery)
        assertTrue(out.recovery!! in 40.0..60.0)
        assertEquals(ScoreConfidence.SOLID, out.confidence)
        assertEquals(ORACLE_AT_BASELINE, out.recovery!!, 1e-12)
    }

    /** HRV well above baseline + RHR below baseline -> high recovery. */
    @Test
    fun highHrvLowRhrGivesHighRecovery() {
        val out = score(hrv14, rhr14, todaySdnn = 70.0, todayRhr = 46)
        assertNotNull(out.recovery)
        assertTrue(out.recovery!! > 65.0)
        assertEquals(ORACLE_HIGH_LOW, out.recovery!!, 1e-12)
    }

    /** HRV well below baseline + RHR above baseline -> low recovery (a bad night must read low). */
    @Test
    fun lowHrvHighRhrGivesLowRecovery() {
        val out = score(hrv14, rhr14, todaySdnn = 22.0, todayRhr = 62)
        assertNotNull(out.recovery)
        assertTrue(out.recovery!! < 40.0)
        assertEquals(ORACLE_LOW_HIGH, out.recovery!!, 1e-12)
    }

    /** Too little history -> calibrating, null recovery (never a fabricated number). */
    @Test
    fun insufficientHistoryCalibrates() {
        val out = score(listOf(45.0, 46.0), listOf(52.0, 51.0), todaySdnn = 45.0, todayRhr = 52)
        assertNull(out.recovery)
        assertEquals(ScoreConfidence.CALIBRATING, out.confidence)
    }

    /** History just under the week gate -> still calibrating (the gate is exactly minBaselineNights). */
    @Test
    fun historyJustBelowGateCalibrates() {
        val n = WatchRecovery.minBaselineNights - 1
        val out = score(List(n) { 45.0 }, List(n) { 52.0 }, todaySdnn = 45.0, todayRhr = 52)
        assertNull(out.recovery)
        assertEquals(ScoreConfidence.CALIBRATING, out.confidence)
    }

    /** History at the week gate (and usable baseline) -> scores, no longer calibrating. */
    @Test
    fun historyAtGateScores() {
        val n = WatchRecovery.minBaselineNights
        val out = score(List(n) { 45.0 }, List(n) { 52.0 }, todaySdnn = 45.0, todayRhr = 52)
        assertNotNull(out.recovery)
        assertTrue(out.confidence != ScoreConfidence.CALIBRATING)
    }

    /** Missing today's HRV -> calibrating, null (we never score off RHR alone). */
    @Test
    fun missingTodayCalibrates() {
        val out = score(hrv14, rhr14, todaySdnn = null, todayRhr = 52)
        assertNull(out.recovery)
        assertEquals(ScoreConfidence.CALIBRATING, out.confidence)
    }

    /** Missing today's RHR (HRV present, baseline usable) -> still scores off HRV alone. */
    @Test
    fun missingTodayRhrStillScoresFromHrv() {
        val out = score(hrv14, rhr14, todaySdnn = 45.0, todayRhr = null)
        assertNotNull(out.recovery)
        assertTrue(out.recovery!! in 40.0..70.0)
    }

    // --- #53: the baseline is the trailing 60 days, ending the day before ---

    /** Nights older than the 60-day window do not count: a long history that sits entirely outside the
     *  window leaves the baseline empty, so the day calibrates instead of scoring off stale nights. */
    @Test
    fun nightsOutsideTheWindowDoNotCount() {
        val history = rows(List(20) { 45.0 }, todaySdnn = 45.0, todayRhr = null)
        val today = history.last()
        // Move every history night 70+ days before the scored day.
        val anchor = Baselines.isoEpochDay(scoredDay)!!
        val old = history.dropLast(1).mapIndexed { i, r ->
            row(Baselines.isoDayFromEpochDay(anchor - 90 + i), hrv = r.avgHrv)
        }
        val out = WatchRecovery.score(scoredDay, old + today)
        assertNull(out.recovery)
        assertEquals(ScoreConfidence.CALIBRATING, out.confidence)
    }

    /** Today's own value never enters its baseline, and a LATER day never does either. */
    @Test
    fun todayAndLaterDaysStayOutOfTheBaseline() {
        val base = rows(List(14) { 45.0 }, todaySdnn = 45.0, todayRhr = null)
        val later = row("2026-03-05", hrv = 200.0)
        assertEquals(WatchRecovery.score(scoredDay, base), WatchRecovery.score(scoredDay, base + later))
    }

    // --- Week gate counts ACCEPTED nights, not raw entries (fork issue #62) ---

    @Test
    fun sevenRawNightsWithFourValidCalibrates() {
        val out = score(listOf(45.0, 46.0, 47.0, 48.0, -1.0, 0.0, 999.0), todaySdnn = 45.0, todayRhr = null)
        assertNull(out.recovery)
        assertEquals(ScoreConfidence.CALIBRATING, out.confidence)
    }

    @Test
    fun sevenRawNightsWithThreeValidCalibrates() {
        val out = score(listOf(45.0, 46.0, 47.0, -1.0, 0.0, 999.0, 1000.0), todaySdnn = 45.0, todayRhr = null)
        assertNull(out.recovery)
        assertEquals(ScoreConfidence.CALIBRATING, out.confidence)
    }

    @Test
    fun sixRawNightsWithSixValidCalibrates() {
        val out = score(listOf(45.0, 46.0, 47.0, 48.0, 45.0, 46.0), todaySdnn = 45.0, todayRhr = null)
        assertNull(out.recovery)
        assertEquals(ScoreConfidence.CALIBRATING, out.confidence)
    }

    @Test
    fun nineRawNightsWithSevenValidScores() {
        val out = score(
            listOf(45.0, 46.0, 47.0, 48.0, 45.0, 46.0, 47.0, -1.0, 999.0), todaySdnn = 45.0, todayRhr = null,
        )
        assertNotNull(out.recovery)
        assertTrue(out.confidence != ScoreConfidence.CALIBRATING)
        assertEquals(ORACLE_NINE_SEVEN, out.recovery!!, 1e-12)
    }

    // --- RHR term needs a USABLE RHR baseline (fork issue #61) ---

    /** No RHR history -> the result is the documented HRV-only path, exactly as if today's RHR were
     *  missing. Swift twin: `testEmptyRHRHistoryScoresLikeMissingRHR`, which pins the same literal, so the
     *  oracle guards BOTH directions. */
    @Test
    fun emptyRhrHistoryScoresLikeMissingRhr() {
        val hist: List<Double?> = List(7) { 45.0 }
        val withRhr = score(hist, todaySdnn = 45.0, todayRhr = 52)
        val hrvOnly = score(hist, todaySdnn = 45.0, todayRhr = null)
        assertNotNull(withRhr.recovery)
        assertNotNull(hrvOnly.recovery)
        assertEquals(hrvOnly.recovery!!, withRhr.recovery!!, 1e-12)
        assertEquals(57.932425214874954, withRhr.recovery!!, 1e-12)
    }

    /** An RHR history entirely out of physiological range accepts no night, so it is dropped too. */
    @Test
    fun unusableRhrHistoryScoresLikeMissingRhr() {
        val hist: List<Double?> = List(7) { 45.0 }
        val junk: List<Double?> = List(7) { 300.0 }   // above restingHRCfg.maxVal (120)
        val withRhr = score(hist, junk, todaySdnn = 45.0, todayRhr = 52)
        val hrvOnly = score(hist, junk, todaySdnn = 45.0, todayRhr = null)
        assertNotNull(withRhr.recovery)
        assertEquals(hrvOnly.recovery!!, withRhr.recovery!!, 1e-12)
    }

    /** Once the RHR baseline IS usable (>= Baselines.minNightsSeed accepted nights) the term returns: a
     *  resting HR above baseline must pull the score below the HRV-only number. */
    @Test
    fun usableRhrHistoryStillContributes() {
        val hist: List<Double?> = List(7) { 45.0 }
        val rhrHist: List<Double?> = listOf(null, null, null, 52.0, 52.0, 52.0, 52.0)   // exactly the seed gate
        val withRhr = score(hist, rhrHist, todaySdnn = 45.0, todayRhr = 62)
        val hrvOnly = score(hist, rhrHist, todaySdnn = 45.0, todayRhr = null)
        assertNotNull(withRhr.recovery)
        assertNotNull(hrvOnly.recovery)
        assertTrue(withRhr.recovery!! < hrvOnly.recovery!! - 1.0)
        assertEquals(ORACLE_USABLE_RHR_WITH, withRhr.recovery!!, 1e-12)
    }

    /** Watch recovery is on the SAME scale as strap recovery: the strap scorer fed the same trailing Charge
     *  baselines reproduces WatchRecovery's number. */
    @Test
    fun sameScaleAsStrapRecovery() {
        val input = rows(hrv14, rhr14, todaySdnn = 58.0, todayRhr = 50)
        val out = WatchRecovery.score(scoredDay, input)
        val resolved = ChargeBaselines.resolveDay(
            scoredDay, ChargeBaselines.Nights(), ChargeBaselines.Nights.fromRows(input.filter { it.day < scoredDay }),
            ChargeBaselines.Nights(), emptyList(), 0.0, 0.0,
        )!!
        val strap = RecoveryScorer.recovery(
            hrv = 58.0, rhr = 50.0, resp = null,
            hrvBaseline = resolved.hrv, rhrBaseline = resolved.restingHR,
            respBaseline = null, sleepPerf = null, hrvPriorWeek = resolved.hrvPriorWeek,
        )
        assertNotNull(out.recovery)
        assertNotNull(strap)
        assertEquals(strap!!, out.recovery!!, 0.0001)
        assertEquals(ORACLE_SAME_SCALE, out.recovery!!, 1e-12)
    }

    /** A varying history (not a flat line), so the oracle covers a real mean and sample SD on both terms. */
    @Test
    fun varyingHistoryMatchesTheSwiftOracle() {
        val hrv: List<Double?> = (0 until 20).map { (40 + (it * 7) % 13).toDouble() }
        val rhr: List<Double?> = (0 until 20).map { (50 + (it * 5) % 7).toDouble() }
        val out = score(hrv, rhr, todaySdnn = 47.0, todayRhr = 53)
        assertEquals(ORACLE_VARYING, out.recovery!!, 1e-12)
        assertEquals(ORACLE_VARYING_CONFIDENCE, out.confidence)
    }

    private companion object {
        // Swift oracle stdout, verbatim (see the class comment).
        const val ORACLE_AT_BASELINE = 57.932425214875714
        const val ORACLE_HIGH_LOW = 99.50478600000888
        const val ORACLE_LOW_HIGH = 0.7762093059319971
        const val ORACLE_NINE_SEVEN = 36.04154548449397
        const val ORACLE_USABLE_RHR_WITH = 27.687819487561434
        const val ORACLE_SAME_SCALE = 98.68835851330172
        const val ORACLE_VARYING = 70.30572288962037
        val ORACLE_VARYING_CONFIDENCE = ScoreConfidence.SOLID
    }
}
