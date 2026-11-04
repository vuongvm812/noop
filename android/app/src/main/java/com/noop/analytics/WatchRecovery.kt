package com.noop.analytics

import com.noop.data.DailyMetric

/**
 * Recovery/Charge from DAILY aggregates (Apple Watch / Health Connect / an Oura/Fitbit/Garmin export).
 * Kotlin twin of the Swift `WatchRecovery`.
 *
 * A WHOOP strap gives dense overnight R-R intervals, so RecoveryScorer runs off raw-derived nightly RMSSD.
 * A daily-aggregate source does NOT: it gives a daily HRV (SDNN-ish) reading plus a resting HR. So this is
 * a genuinely lower-density computation.
 *
 * We do NOT invent a new formula. Recovery is HRV-and-RHR-vs-personal-baseline, and because every term is
 * relative to the person's OWN baseline, the metric scale cancels out: SDNN-vs-SDNN-baseline behaves like
 * RMSSD-vs-RMSSD-baseline. So we build HRV and RHR baselines with the strap's Charge baselines
 * ([ChargeBaselines], trailing 60 days, HRV in the log domain; #53) and feed them straight into the SAME
 * [RecoveryScorer.recovery] the strap uses. Source-only recovery and strap
 * recovery therefore land on the same 0-100 scale and read against the same bands.
 *
 * What we drop vs the strap path: the respiration, sleep-performance and skin-temp terms are not supplied
 * here, so RecoveryScorer renormalises the remaining HRV + RHR weights. The HRV term stays dominant.
 *
 * Honesty rule: return null recovery + CALIBRATING when today's HRV is missing, OR the HRV baseline isn't
 * usable yet, OR the baseline has accepted fewer than [minBaselineNights] nights. We NEVER fabricate a number to
 * fill a sparse week. Confidence comes from the existing [ScoreConfidence.forCharge].
 */
object WatchRecovery {

    /** Result: the score (null while calibrating) and its confidence tier. */
    data class Result(val recovery: Double?, val confidence: ScoreConfidence)

    /**
     * Minimum VALID nights (nights the baseline accepted, `BaselineState.nValid`) before we score recovery
     * from a daily-aggregate source — not raw history entries. Sits ABOVE the
     * baseline's own seed gate (4) deliberately: a strap user crosses the seed faster on dense data, but a
     * sparse daily HRV deserves a longer warm-up before we trust it. Mirrors the Swift constant.
     */
    const val minBaselineNights = 7

    /**
     * Charge for one day of a daily-aggregate source (Apple Watch / Health Connect SDNN, or an import-only
     * wearable's nightly HRV) against that source's own trailing baselines (#53).
     *
     * The baselines are the Charge ones: [ChargeBaselines.resolveDay] over this source's rows, so HRV is
     * lnRMSSD-style (log domain), every baseline is the mean and sample SD of the valid nights in the 60 days
     * before [day], and today's value never sees itself. One funnel: the engine's watch and import Charge
     * fold calls this, as does the Apple dashboard's watch confidence pill (Android has no such pill), so a
     * confidence readout can never read a different baseline from the one the number was scored on.
     *
     * @param day the scored `yyyy-MM-dd` day.
     * @param rows this source's daily rows, any order; the row for [day] carries today's values.
     * @return recovery in [0,100] with its confidence, or null recovery + CALIBRATING when today's HRV is
     *   missing, the HRV baseline isn't usable, or it holds fewer than [minBaselineNights] valid nights.
     *   Never a fabricated value. Swift twin: `WatchRecovery.score(day:rows:)`.
     */
    fun score(day: String, rows: List<DailyMetric>): Result {
        val calibrating = Result(recovery = null, confidence = ScoreConfidence.CALIBRATING)
        val prior = rows.filter { it.day < day }
        val resolved = ChargeBaselines.resolveDay(
            scoredDay = day, imported = ChargeBaselines.Nights(), own = ChargeBaselines.Nights.fromRows(prior),
            seed = ChargeBaselines.Nights(), sourceDays = emptyList(), hrvRecalibration = 0.0,
            recoveryRecalibration = 0.0,
        ) ?: return calibrating
        val today = rows.lastOrNull { it.day == day }
        val hrvBase = resolved.hrv
        val rhrBase = resolved.restingHR
        val todayHrv = today?.avgHrv
        val todayRhr = today?.restingHr

        // Confidence is the SAME helper the strap Charge uses, so the calibrating -> building -> solid arc
        // matches. It reads CALIBRATING whenever recovery would be null (no usable HRV baseline).
        val conf = ScoreConfidence.forCharge(todayHrv, hrvBase)

        // Honesty gate: no number unless we have today's HRV, a usable baseline, AND at least a week of
        // valid nights in it. A physiologically implausible reading never enters the baseline, so it
        // cannot buy a score early.
        if (todayHrv == null || !hrvBase.usable || hrvBase.nValid < minBaselineNights) return calibrating

        // Reuse the canonical Charge scorer. Resp / sleep / skin-temp are not supplied by a daily aggregate,
        // so RecoveryScorer renormalises to HRV + RHR. The RHR term needs BOTH today's reading AND a usable
        // personal RHR baseline; without either it is dropped (the documented HRV-only path).
        val recovery = RecoveryScorer.recovery(
            hrv = todayHrv,
            rhr = todayRhr?.toDouble() ?: rhrBase.baseline,
            resp = null,
            hrvBaseline = hrvBase,
            rhrBaseline = if (todayRhr != null && rhrBase.usable) rhrBase else null,
            respBaseline = null,
            sleepPerf = null,
            hrvPriorWeek = resolved.hrvPriorWeek,
        ) ?: return calibrating
        return Result(recovery = recovery, confidence = conf)
    }
}
