package com.noop.analytics

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/*
 * HRVReadiness.kt — OPT-IN experimental "HRV readiness (Plews/Altini)" tier readout.
 *
 * Faithful Kotlin twin of StrandAnalytics/HRVReadiness.swift. Cross-platform parity is the contract:
 * every constant, gate, and formula here must stay byte-identical to the Swift source.
 *
 * This is a PURE, deterministic, DB-free engine and it does NOT change the default Charge/ring in any
 * way — it is only surfaced (read-only) behind the [com.noop.ble.PuffinExperiment] `noopHrvReadiness`
 * flag (off by default) and feeds NO downstream gate. It reimplements the endurance-science "smallest
 * worthwhile change" (SWC) reading of nightly HRV popularised by Plews et al. and Marco Altini: work in
 * the LOG domain (ln RMSSD is closer to normal and stabilises variance), compare a short 7-night rolling
 * baseline against a longer personal normal band (±0.5 SD), and read the tier off where the short baseline
 * sits relative to that band. A single bad night barely moves the 7-night baseline, so the reading is far
 * more robust than a raw single-night z.
 *
 * INPUT: day-keyed nightly RMSSD (DailyMetric.day + avgHrv) in ms; windows are CALENDAR days ending today
 * (#53) (nulls allowed for missing nights). Physiologically implausible nights are
 * dropped through the shared [Baselines.hrvCfg] bounds (5..250 ms) so one decode glitch can't skew it.
 *
 * OUTPUT ([HRVReadinessResult]): the tier (primed / normal / suppressed), the 7-night baseline and the
 * personal normal band (all back in ms via exp), and an informational overreaching-watch flag. Returns
 * null (the honest ".calibrating" edge) below [MIN_NIGHTS] valid nights: never a fabricated number.
 * Mirrors the WatchRecovery nil+.calibrating honesty pattern.
 *
 * This is a rough / early experiment: it is NOT yet validated against varying real data (n=1). Outputs
 * are APPROXIMATE wellness estimates, not medical advice.
 */

/** Where tonight's 7-night HRV baseline sits vs the personal normal band. Mirrors Swift `ReadinessTier`. */
enum class ReadinessTier { PRIMED, NORMAL, SUPPRESSED }

/** Result of an [HRVReadiness.evaluate] pass. All *Ms fields are back in milliseconds (exp of the log
 *  domain the engine works in). Mirrors Swift `HRVReadinessResult`. */
data class HRVReadinessResult(
    /** Tier from the SWC comparison of the 7-night baseline against the personal normal band. */
    val tier: ReadinessTier,
    /** 7-night rolling HRV baseline (ms) = exp(mean of ln RMSSD over the trailing ROLL_WINDOW nights). */
    val baseline7Ms: Double,
    /** Lower edge of the personal normal band (ms) = exp(longMean − 0.5·longSD). */
    val normalLowMs: Double,
    /** Upper edge of the personal normal band (ms) = exp(longMean + 0.5·longSD). */
    val normalHighMs: Double,
    /** Informational only (does NOT change the tier): a sustained falling CV trend while the 7-night
     *  baseline sits below the long mean — the classic parasympathetic-overreaching signature. */
    val overreachingWatch: Boolean,
)

/** OPT-IN experimental HRV-readiness (Plews/Altini SWC) engine. Mirrors Swift `HRVReadiness`. */
object HRVReadiness {

    // ─────────────────────────────────────────────────────────────────────────
    // Constants (name them; mirror Swift exactly)
    // ─────────────────────────────────────────────────────────────────────────

    /** Short rolling baseline window, in CALENDAR days ending today (#53: it used to be the last 7 valid
     *  nights, which after a week off the strap could span a month). */
    const val ROLL_WINDOW: Int = 7

    /** Long personal-normal window, in calendar days ending today. */
    const val LONG_WINDOW: Int = 60

    /** Fewest valid nights the 7-day window must hold for a reading (and for one CV point). */
    const val MIN_ROLL_NIGHTS: Int = 3

    /** Smallest-worthwhile-change half-width factor: the normal band is longMean ± SWC_K·longSD. */
    const val SWC_K: Double = 0.5

    /** Minimum VALID nights inside the long window before a reading is offered (else null + the honest
     *  calibrating edge). Its OWN constant, deliberately NOT aliased to [WatchRecovery.minBaselineNights]. */
    const val MIN_NIGHTS: Int = 14

    /** Calendar days over which the CV trend (overreaching watch) is fit. */
    const val CV_TREND_WINDOW: Int = 28

    /** Floor on the long-window SD so a flat history yields a deterministic, tiny non-degenerate normal
     *  band instead of an ULP-fragile zero-width one (baseline7 and longMean are both the mean of the same
     *  constant here, but can differ by a float ULP, which a zero-width band would flip to primed/suppressed). */
    const val LONG_SD_FLOOR: Double = 1e-9

    // ─────────────────────────────────────────────────────────────────────────
    // Evaluate
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Compute the Plews/Altini HRV-readiness reading over CALENDAR windows ending [today]. Returns null
     * (calibrating) below [MIN_NIGHTS] valid nights in the long window or [MIN_ROLL_NIGHTS] in the last 7
     * days — never a fabricated value.
     *
     * @param dayKeys `yyyy-MM-dd` per value (any order; unparseable keys and days after [today] are dropped).
     * @param avgHrv nightly RMSSD (ms), parallel to [dayKeys]; nulls = missing nights. Out-of-range nights
     *   (outside [Baselines.hrvCfg] 5..250 ms) are dropped as decode artefacts.
     * @param today the day the windows end on.
     * Swift twin: `HRVReadiness.evaluate(dayKeys:avgHrv:today:)`.
     */
    fun evaluate(dayKeys: List<String>, avgHrv: List<Double?>, today: String): HRVReadinessResult? {
        val cfg = Baselines.hrvCfg
        val t = Baselines.isoEpochDay(today) ?: return null
        // (epoch day, ln RMSSD) for every valid night on or before today, oldest first. A repeated day keeps
        // its last value.
        val byDay = HashMap<Int, Double>()
        for (i in 0 until minOf(dayKeys.size, avgHrv.size)) {
            val v = avgHrv[i] ?: continue
            if (!(cfg.minVal <= v && v <= cfg.maxVal)) continue
            val d = Baselines.isoEpochDay(dayKeys[i]) ?: continue
            if (d > t) continue
            byDay[d] = ln(max(v, 1.0))
        }
        val nights = byDay.keys.sorted().map { it to byDay.getValue(it) }
        /** The nights in the `days` calendar days ending at night `end`. Swift twin: `HRVReadiness.window`. */
        fun window(end: Int, days: Int): List<Double> =
            nights.filter { it.first <= end && end - it.first < days }.map { it.second }

        val longEll = window(t, LONG_WINDOW)
        val rollEll = window(t, ROLL_WINDOW)
        if (longEll.size < MIN_NIGHTS || rollEll.size < MIN_ROLL_NIGHTS) return null

        val baseline7 = RecoveryForecaster.mean(rollEll)
        val longMean = RecoveryForecaster.mean(longEll)
        val longSD = max(RecoveryForecaster.sampleSD(longEll), LONG_SD_FLOOR)

        // Smallest-worthwhile-change band (±0.5 SD in the log domain).
        val swcHalf = SWC_K * longSD
        val normalLow = longMean - swcHalf
        val normalHigh = longMean + swcHalf

        // Tier: above the band -> primed; inside (inclusive of the low edge) -> normal; below -> suppressed.
        val tier = when {
            baseline7 > normalHigh -> ReadinessTier.PRIMED
            baseline7 >= normalLow -> ReadinessTier.NORMAL
            else -> ReadinessTier.SUPPRESSED
        }

        // Overreaching watch (INFORMATIONAL — never changes the tier): a falling CV trend over the last
        // CV_TREND_WINDOW days while the 7-day baseline sits below the long mean. One CV point per day whose
        // trailing 7 calendar days hold at least MIN_ROLL_NIGHTS nights; OLS slope over the points.
        val cvSeries = ArrayList<Double>()
        for (end in (t - CV_TREND_WINDOW + 1)..t) {
            val w = window(end, ROLL_WINDOW)
            if (w.size < MIN_ROLL_NIGHTS) continue
            val m = RecoveryForecaster.mean(w)
            cvSeries.add(if (m != 0.0) 100.0 * RecoveryForecaster.sampleSD(w) / m else 0.0)
        }
        val cvSlope = RecoveryForecaster.leastSquaresSlope(cvSeries)
        val overreachingWatch = cvSlope < 0.0 && baseline7 < longMean

        return HRVReadinessResult(
            tier = tier,
            baseline7Ms = exp(baseline7),
            normalLowMs = exp(normalLow),
            normalHighMs = exp(normalHigh),
            overreachingWatch = overreachingWatch,
        )
    }
}
