package com.noop.analytics

import com.noop.data.DailyMetric
import kotlin.math.sqrt

/**
 * V5HealthSignals — the small, pure adapter that turns the app's cached merged [DailyMetric] history into
 * the per-night z-scored inputs the three v5 skin-temp-suite engines consume, and runs them once per
 * analytics pass. It owns NO I/O and NO state: the caller (AppViewModel) hands it the already-loaded
 * `recentDays` list + a few prefs/profile flags and gets back a [Snapshot] of engine RESULTS to publish.
 *
 * The cycle classifier reads a lightweight per-night z (rolling mean+SD over the trailing window). The
 * illness heads-up does NOT: it goes through [IllnessInputs], the oracle-pinned twin of the Swift builder,
 * so both platforms fire on the same personal-baseline maths (#56). The engines themselves (CyclePhaseEngine / CircadianEngine / IllnessSignalEngine)
 * are the byte-for-byte cross-platform maths; this file is only the Android-side input plumbing.
 *
 * NON-CLINICAL: every output is an approximation about the user's own series — never a diagnosis. Cycle
 * awareness is OPT-IN (the caller gates on a default-OFF pref before reading [Snapshot.cycle]).
 *
 * See docs/superpowers/specs/2026-06-19-v5-skin-temp-suite-design.md and the umbrella IA (§2.4 Health hub).
 */
object V5HealthSignals {

    /** Trailing window (nights) for the rolling baseline used to z-score each signal. */
    private const val BASELINE_WINDOW = 30

    /** Minimum trailing nights before a z-score is trusted (mirrors Baselines.minNightsTrust intent). */
    private const val MIN_BASELINE_NIGHTS = 14

    /** The published engine results for the Health hub's skin-temp suite, all already decided. */
    data class Snapshot(
        val cycle: CyclePhaseEngine.Result,
        val bodyClock: CircadianEngine.PhaseEstimate?,
        val illness: IllnessSignalEngine.Result,
        /**
         * Parallel Mahalanobis illness-distance read (IllnessDistance), computed on the SAME illness-ward
         * z-vector as [illness] but NEVER gating the alert: [IllnessSignalEngine] stays the sole fire gate.
         * This only surfaces a "how strong" confidence readout in the Heads-Up card when the engine has
         * already raised. Nullable so an absent illness pass leaves it null. (Augment-only, Option A.)
         */
        val illnessDistance: IllnessDistance.Result?,
        /** True once an HRV or resting-HR baseline can honestly gate an illness heads-up. */
        val baselineTrusted: Boolean,
    )

    /**
     * Run the three engines over [days] (oldest→newest). [cycleOptedIn] gates whether the cycle classifier
     * is run at all (it returns a cheap LEARNING result when off, so the caller can publish unconditionally
     * and the UI's opt-in card still shows). [loggedPeriodStarts] are optional "yyyy-MM-dd" period-start
     * days. [journalYesQuestions] are the questions answered YES on the recent nights' days, matched on
     * canonical catalog keys by [IllnessInputs.context].
     */
    fun evaluate(
        days: List<DailyMetric>,
        cycleOptedIn: Boolean,
        loggedPeriodStarts: List<String> = emptyList(),
        journalYesQuestions: List<String> = emptyList(),
        habitualWakeHour: Double = 7.0,
        activityBins: List<CircadianEngine.ActivityBin> = emptyList(),
        daysObserved: Int = 0,
    ): Snapshot {
        // #2131: trust belongs to a SIGNAL, not to a row. Counting rows with any vital let fourteen
        // unrelated or mixed measurements lend confidence to an HRV/RHR illness claim even though no
        // one baseline had fourteen observations. Use the same trailing window zAgainst uses for the
        // newest night and mirror iOS's illness gate: a trusted RHR OR HRV baseline may raise. Keep the
        // cycle classifier's existing availability gate separate so this illness fix cannot change it.
        val cycleBaselineUsable = days.count { hasAnyVital(it) } >= MIN_BASELINE_NIGHTS

        // ── Per-night z-scores against each signal's trailing rolling baseline ──
        val nights = ArrayList<CyclePhaseEngine.Night>(days.size)
        for ((i, d) in days.withIndex()) {
            val window = days.subList(maxOf(0, i - BASELINE_WINDOW), i)
            nights.add(
                CyclePhaseEngine.Night(
                    day = d.day,
                    tempZ = zAgainst(d.skinTempDevC, window) { it.skinTempDevC },
                    rhrZ = zAgainst(d.restingHr?.toDouble(), window) { it.restingHr?.toDouble() },
                    hrvZ = zAgainst(d.avgHrv, window) { it.avgHrv },
                )
            )
        }

        // ── Cycle awareness (opt-in) ──
        val cycle = if (cycleOptedIn) {
            CyclePhaseEngine.classify(
                nights,
                baselineUsable = cycleBaselineUsable,
                loggedPeriodStarts = loggedPeriodStarts,
            )
        } else {
            CyclePhaseEngine.Result(
                phase = CyclePhaseEngine.Phase.LEARNING,
                confidence = CyclePhaseEngine.Confidence.LEARNING,
                cycleDayLow = null, cycleDayHigh = null, cycleLengthDays = null,
                nextPeriodWindow = null, shiftMarkers = emptyList(),
                note = "Turn on cycle awareness to read a coarse phase from your nightly temperature.",
            )
        }

        // ── Illness heads-up (confounder-suppressed) ──
        // #56: one shared builder (twin of Swift IllnessInputs) — personal floored EWMA baselines with
        // a gap, two-night persistence, and a skin-temp baseline of the newest night's kind. The
        // journal context's confounder flags are kept; trust and the hard-workout flag come from the
        // builder so both platforms gate on the same baseline.
        val built = IllnessInputs.build(days.map { illnessNight(it) })
        val baselineTrusted = built.baselineTrusted
        val firedLabels = HashMap<String, String>()
        built.restingHR?.reading?.let { if (it.present && it.zIllnessward >= IllnessSignalEngine.signalZThreshold) firedLabels["restingHR"] = "RHR up" }
        built.skinTemp?.reading?.let { if (it.present && it.zIllnessward >= IllnessSignalEngine.signalZThreshold) firedLabels["skinTemp"] = "skin temp up" }
        built.hrv?.reading?.let { if (it.present && it.zIllnessward >= IllnessSignalEngine.signalZThreshold) firedLabels["hrv"] = "HRV down" }
        built.respiration?.reading?.let { if (it.present && it.zIllnessward >= IllnessSignalEngine.signalZThreshold) firedLabels["respiration"] = "respiration up" }

        val illness = IllnessSignalEngine.evaluate(
            inputs = built.inputs,
            context = IllnessInputs.context(journalYesQuestions, built),
            firedLabels = firedLabels,
        )

        // PARALLEL Mahalanobis distance on the SAME illness-ward z-vector. This NEVER gates the alert:
        // the IllnessSignalEngine remains the sole fire gate. correlation = null (identity). Mirrors iOS.
        val illnessDistance = IllnessDistance.evaluate(features = built.distanceFeatures, correlation = null)

        // ── Body clock (#852): the caller pools HR by local hour into [activityBins] — the same
        //    activity proxy the Swift twin uses in AppModel.computeCircadianPhase, which has shipped
        //    this card on iOS/macOS all along. Android had the engine, the tests and the card, and only
        //    ever lacked these twenty lines of input, behind a comment claiming the data did not exist.
        //
        //    Under six populated hours is too thin to fit, so it stays null and HealthScreen's `?.let`
        //    skips the card — no fabricated estimate. Both parameters default, so every existing caller
        //    is byte-identical. ──
        val bodyClock: CircadianEngine.PhaseEstimate? =
            if (activityBins.size >= MIN_CIRCADIAN_BINS) {
                CircadianEngine.estimatePhase(activityBins, daysObserved, habitualWakeHour)
            } else {
                null
            }

        return Snapshot(cycle = cycle, bodyClock = bodyClock, illness = illness,
            illnessDistance = illnessDistance, baselineTrusted = baselineTrusted)
    }

    /** Populated local hours needed before a cosinor fit is worth attempting. Mirrors the Swift
     *  twin's `guard bins.count >= 6` in AppModel.computeCircadianPhase. */
    internal const val MIN_CIRCADIAN_BINS = 6

    /** The illness builder's view of one cached day (twin of Swift `AppModel.illnessNight`). */
    fun illnessNight(d: DailyMetric): IllnessInputs.Night = IllnessInputs.Night(
        day = d.day, restingHR = d.restingHr?.toDouble(), hrv = d.avgHrv,
        respiration = d.respRateBpm, skinTemp = d.skinTempDevC, strain = d.strain,
    )

    /** Whether a row can contribute to the cycle classifier's availability gate. */
    private fun hasAnyVital(d: DailyMetric): Boolean =
        d.restingHr != null || d.avgHrv != null || d.skinTempDevC != null || d.respRateBpm != null

    /**
     * Z-score [value] against the trailing [window]'s rolling mean + sample SD for [selector]. Returns
     * null when the value is absent or there isn't enough trailing data to trust the spread. A floor on
     * the SD prevents a near-constant series from exploding the z.
     */
    private inline fun zAgainst(
        value: Double?,
        window: List<DailyMetric>,
        selector: (DailyMetric) -> Double?,
    ): Double? {
        if (value == null) return null
        val xs = window.mapNotNull(selector)
        if (xs.size < MIN_BASELINE_NIGHTS) return null
        val mean = xs.average()
        val variance = xs.sumOf { (it - mean) * (it - mean) } / (xs.size - 1).coerceAtLeast(1)
        val sd = sqrt(variance).coerceAtLeast(1e-6)
        return (value - mean) / sd
    }
}
