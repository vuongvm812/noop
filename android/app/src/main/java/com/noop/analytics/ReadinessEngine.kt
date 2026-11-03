package com.noop.analytics

import com.noop.data.DailyMetric

/**
 * On-device "Readiness" intelligence.
 *
 * Synthesizes a handful of established, non-medical sports-science signals from the daily-metrics
 * history into a single readiness read plus the drivers behind it. Everything here is a pure,
 * deterministic function of the rows you pass in — no networking, no strap commands, no state.
 *
 * Faithful Kotlin port of
 * Packages/StrandAnalytics/Sources/StrandAnalytics/ReadinessEngine.swift (verified on macOS).
 * Same windows, same thresholds, same outputs.
 *
 * Signals and their references:
 * - **HRV readiness** — z-score of today's lnRMSSD against the personal trailing baseline. A drop of
 *   roughly half a standard deviation flags autonomic fatigue (Plews et al. 2013; Buchheit 2014).
 *   HRV, resting HR and respiration are read against the SAME per-day Charge baselines the ring is
 *   scored on ([ChargeBaselines], #53), so the readiness word and the Charge ring cannot disagree about
 *   where a night sits.
 * - **Resting-HR drift** — elevated resting HR vs baseline is a classic overtraining / illness
 *   signal (Lamberts et al. 2004).
 * - **Respiratory-rate drift** — a rise in sleeping respiratory rate is an early illness signal.
 * - **Training Stress Balance (ACWR)** — EWMA acute (7-day) vs chronic (28-day) daily TRIMP over
 *   calendar days (Williams 2017). Descriptive only; not an injury predictor (Impellizzeri 2020).
 * - **Training monotony** — mean/SD of daily TRIMP over a week; high monotony (low variety) is
 *   associated with higher strain and illness (Foster 1998).
 *
 * Not medical advice. These are approximations from a consumer strap; they describe trends in
 * *your own* data, nothing more.
 */
object ReadinessEngine {

    // MARK: Output types

    enum class Level {
        PRIMED,       // signals aligned, load supported
        BALANCED,     // nothing notable either way
        STRAINED,     // one meaningful signal down / load high
        RUNDOWN,      // several recovery signals down
        INSUFFICIENT, // not enough history yet
    }

    enum class Flag { GOOD, NEUTRAL, WATCH, BAD }

    enum class MetricUnit { MS, BPM, RPM }

    /** Semantic display-copy identifiers; Android resources are resolved only in the UI layer. */
    enum class Copy {
        TODAY_READINESS_BALANCED, TODAY_READINESS_BALANCED_SUMMARY,
        TODAY_READINESS_HRV_BAD, TODAY_READINESS_HRV_GOOD, TODAY_READINESS_HRV_WATCH,
        TODAY_READINESS_LOAD_BUILDING_FAST, TODAY_READINESS_LOAD_RAMPING_DOWN,
        TODAY_READINESS_LOAD_SPIKING, TODAY_READINESS_LOAD_SWEET_SPOT,
        TODAY_READINESS_MONOTONY_WATCH, TODAY_READINESS_MORE_NIGHTS,
        TODAY_READINESS_NORMAL_RANGE, TODAY_READINESS_PRIMED, TODAY_READINESS_PRIMED_SUMMARY,
        TODAY_READINESS_RESP_BAD, TODAY_READINESS_RESP_WATCH,
        TODAY_READINESS_RHR_BAD, TODAY_READINESS_RHR_GOOD, TODAY_READINESS_RHR_WATCH,
        TODAY_READINESS_RUN_DOWN, TODAY_READINESS_RUN_DOWN_SUMMARY,
        TODAY_READINESS_SIGNAL_HRV, TODAY_READINESS_SIGNAL_RESPIRATORY,
        TODAY_READINESS_SIGNAL_RESTING_HR, TODAY_READINESS_SIGNAL_TRAINING_LOAD,
        TODAY_READINESS_SIGNAL_TRAINING_VARIETY, TODAY_READINESS_STRAINED,
        TODAY_READINESS_STRAINED_SUMMARY, TODAY_READINESS_TITLE,
        TODAY_READINESS_WEAR_FOR_NIGHTS,
    }

    sealed interface Evidence {
        data class MetricVsBaseline(
            val value: Double,
            val baseline: Double,
            val decimals: Int,
            val unit: MetricUnit,
        ) : Evidence
        data class Monotony(val value: Double) : Evidence
        data class TrainingLoad(val acute: Double, val chronic: Double) : Evidence
    }

    data class Signal(
        val key: String,            // "hrv" | "rhr" | "respRate" | "acwr" | "monotony"
        val labelRes: Copy,
        val detailRes: Copy,
        val flag: Flag,
        // The numbers behind the signal, e.g. "48 vs 55 ms" or "7d 12.1 / 28d 9.4". Optional and
        // backward-compatible (defaults null); rendered as a small caption under the signal in the UI.
        val evidence: Evidence? = null,
    )

    data class Readiness(
        val level: Level,
        val headlineRes: Copy,
        val summaryRes: Copy,
        val signals: List<Signal>,
        /** Acute:chronic workload ratio (null if not enough strain history). */
        val acwr: Double?,
        /** Foster training monotony over the last week (null if not enough strain history). */
        val monotony: Double?,
        /**
         * How much history backs this read (HRV/RHR baseline density) — so the card can show
         * calibrating / building / solid instead of a confident number off a 7-night baseline.
         * Defaults to CALIBRATING to match Swift's default init parameter.
         */
        val confidence: ScoreConfidence = ScoreConfidence.CALIBRATING,
    )

    // MARK: Tunables (named so the thresholds are auditable)

    private const val acuteWindow = 7
    private const val chronicWindow = 28
    private const val minChronic = 14       // observed days of the last [chronicWindow] needed for ACWR

    // Resp-rate signal is sourced from either clean cloud RR or a higher-variance on-device RSA
    // estimate (no source flag on the field), so it uses wider z thresholds than HRV/RHR and a
    // physiologic sanity band. A single noisy RSA night should not reach BAD (which feeds recoveryDown).
    private const val respZWatch = 1.5      // raised vs HRV/RHR (was 1.0) to absorb RSA night-to-night noise
    private const val respZBad = 2.0        // raised vs HRV/RHR (was 1.5) so one off-night can't trigger RUNDOWN
    // Single canonical band, owned by the producer so the stored RSA value can't disagree with this
    // gate (#78): SleepStager.respRateFromRR now NaNs anything outside it before persisting.
    private val respPlausibleRange = SleepStager.respPlausibleRangeBpm // plausible sleeping RR (bpm)

    // MARK: Entry point

    /**
     * Evaluate readiness from daily metrics. [days] may be in any order; the most recent day is
     * treated as "today" unless [today] (a YYYY-MM-DD string) is given.
     *
     * #1034 (ryanbr) perf: [evaluateUncached] SORTS the entire daily history and walks trailing windows on
     * every call, and it is read from Compose recompositions (TodayScreen / CoupledScreen) — so a recompose
     * on each ~1 Hz live-HR tick re-ran the full-history sort. Some call sites `remember{}` it; this
     * engine-level memo additionally shields the un-remembered ones and the first/uncached read, mirroring
     * the Swift ReadinessEngine.evaluateCache. Key = [today] + an ORDER-INDEPENDENT fingerprint over ONLY
     * the rows' readiness fields (day + avgHrv/restingHr/respRateBpm/strain), so a new sync re-keys but a
     * cosmetic reorder does not. The cached result is a small immutable [Readiness]; no row arrays retained.
     *
     * [baselines] (#53) are the Charge baselines the displayed day was scored against
     * (`ChargeBaselines.Inputs.resolve`): the HRV, resting-HR and respiration signals are z-scored against
     * exactly those states, era cut included, so the readiness word and the ring agree. null resolves the
     * same per-day rule from [days] alone (no imports, no device eras), for callers with nothing else to hand.
     * Swift twin: `ReadinessEngine.evaluate(days:today:baselines:)`.
     */
    fun evaluate(
        days: List<DailyMetric>,
        today: String? = null,
        baselines: ChargeBaselines.Resolved? = null,
    ): Readiness {
        val key = readinessKey(today, baselines, days)
        synchronized(evaluateCacheLock) { evaluateCache[key] }?.let { return it }
        val result = evaluateUncached(days, today, baselines)   // computed OUTSIDE the lock (the expensive sort/walk)
        synchronized(evaluateCacheLock) { evaluateCache[key] = result }
        return result
    }

    private data class ReadinessKey(
        val today: String?,
        val hrv: BaselineState?, val restingHR: BaselineState?, val resp: BaselineState?,
        val count: Int, val minDay: Int, val maxDay: Int, val checksum: Long,
    )

    private const val EVALUATE_CACHE_CAP = 16

    /** Access-order LRU (cap [EVALUATE_CACHE_CAP]); every access is under [evaluateCacheLock] because a
     *  LinkedHashMap in access order mutates on `get`. Twin of Swift's AnalyticsMemoCache(capacity: 16). */
    private val evaluateCache: LinkedHashMap<ReadinessKey, Readiness> =
        object : LinkedHashMap<ReadinessKey, Readiness>(EVALUATE_CACHE_CAP, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ReadinessKey, Readiness>): Boolean =
                size > EVALUATE_CACHE_CAP
        }
    private val evaluateCacheLock = Any()

    /** Order-independent fingerprint of the readiness-relevant columns — a new sync re-keys, a cosmetic
     *  reorder does not. Internal to this process's cache only (never compared cross-platform), so it need
     *  NOT match the Swift hash byte-for-byte; a collision only costs one extra recompute. Mirrors Swift
     *  `rowsFingerprint`: FNV-style commutative fold over (day, avgHrv, restingHr, respRateBpm, strain). */
    private fun readinessKey(
        today: String?, baselines: ChargeBaselines.Resolved?, days: List<DailyMetric>,
    ): ReadinessKey {
        var sum = 1469598103934665603L
        var minDay = 0
        var maxDay = 0
        for ((i, d) in days.withIndex()) {
            var h = d.day.hashCode().toLong()
            h = h * 1099511628211L xor (d.avgHrv ?: -1.0).toRawBits()
            h = h * 1099511628211L xor (d.restingHr?.toLong() ?: Long.MAX_VALUE)
            h = h * 1099511628211L xor (d.respRateBpm ?: -1.0).toRawBits()
            h = h * 1099511628211L xor (d.strain ?: -1.0).toRawBits()
            sum = sum xor h   // commutative fold → order-independent
            val dh = d.day.hashCode()
            if (i == 0) { minDay = dh; maxDay = dh } else { minDay = minOf(minDay, dh); maxDay = maxOf(maxDay, dh) }
        }
        return ReadinessKey(
            today, baselines?.hrv, baselines?.restingHR, baselines?.resp, days.size, minDay, maxDay, sum,
        )
    }

    /** The uncached readiness synthesis (formerly the body of [evaluate]). [days] may be in any order; the
     *  most recent day is "today" unless [today] is given.
     *  Swift twin: `ReadinessEngine.evaluateUncached`. */
    private fun evaluateUncached(
        days: List<DailyMetric>,
        today: String?,
        baselines: ChargeBaselines.Resolved?,
    ): Readiness {
        val sorted = days.sortedBy { it.day }
        // When an explicit [today] is given (the dashboard passes the device's real local day key), use
        // the row for THAT day and nothing else: a stale historical import has no row for today, so the
        // readiness card reads "insufficient" rather than synthesizing off the newest stored — possibly
        // months-old — row (issue #23/#24). With no [today] (live-strap default callers) fall back to the
        // most recent row exactly as before, so nothing wearing the strap nightly changes.
        val latest = if (today != null) sorted.firstOrNull { it.day == today } else sorted.lastOrNull()
        if (latest == null) {
            return Readiness(
                level = Level.INSUFFICIENT,
                headlineRes = Copy.TODAY_READINESS_TITLE,
                summaryRes = Copy.TODAY_READINESS_WEAR_FOR_NIGHTS,
                signals = emptyList(), acwr = null, monotony = null,
            )
        }
        val history = sorted.filter { it.day < latest.day }   // everything before today
        // #53: the Charge baselines for this day (window ending the day before, era-cut).
        val charge = baselines ?: ChargeBaselines.resolveDay(
            scoredDay = latest.day, imported = ChargeBaselines.Nights(), own = ChargeBaselines.Nights.fromRows(history),
            seed = ChargeBaselines.Nights(), sourceDays = emptyList(), hrvRecalibration = 0.0,
            recoveryRecalibration = 0.0,
        )

        val signals = mutableListOf<Signal>()

        // HRV readiness ------------------------------------------------------
        val hrvSignal = zSignal(
            value = latest.avgHrv,
            baseline = charge?.hrv,
            key = "hrv", labelRes = Copy.TODAY_READINESS_SIGNAL_HRV,
            unit = MetricUnit.MS, decimals = 0,
            higherIsBetter = true,
            goodTextRes = Copy.TODAY_READINESS_HRV_GOOD,
            neutralTextRes = Copy.TODAY_READINESS_NORMAL_RANGE,
            watchTextRes = Copy.TODAY_READINESS_HRV_WATCH,
            badTextRes = Copy.TODAY_READINESS_HRV_BAD,
        )
        if (hrvSignal != null) signals.add(hrvSignal)

        // Resting-HR drift ---------------------------------------------------
        val rhrSignal = zSignal(
            value = latest.restingHr?.toDouble(),
            baseline = charge?.restingHR,
            key = "rhr", labelRes = Copy.TODAY_READINESS_SIGNAL_RESTING_HR,
            unit = MetricUnit.BPM, decimals = 0,
            higherIsBetter = false,
            goodTextRes = Copy.TODAY_READINESS_RHR_GOOD,
            neutralTextRes = Copy.TODAY_READINESS_NORMAL_RANGE,
            watchTextRes = Copy.TODAY_READINESS_RHR_WATCH,
            badTextRes = Copy.TODAY_READINESS_RHR_BAD,
        )
        if (rhrSignal != null) signals.add(rhrSignal)

        // Respiratory-rate drift (illness early signal) ----------------------
        // respRateBpm may be a clean cloud value OR a higher-variance on-device RSA estimate
        // (WHOOP5 BLE-only) and carries no source flag, so gate conservatively for BOTH: keep the
        // usable-baseline guard, only act on physiologically plausible sleeping-RR (~8-25 bpm),
        // and use wider resp-only z thresholds (WATCH 1.5 / BAD 2.0) so a single noisy night can't
        // reach BAD (which would feed recoveryDown), while a sustained genuine rise still flags.
        val rr = latest.respRateBpm
        if (rr != null && rr in respPlausibleRange) {
            val b = charge?.resp
            if (b != null && b.usable && b.baseline in respPlausibleRange) {
                val m = b.baseline
                val z = (rr - m) / maxOf(1.253 * b.spread, 1e-9)
                val respEvidence = Evidence.MetricVsBaseline(rr, m, 1, MetricUnit.RPM)
                if (z >= respZBad) {
                    signals.add(
                        Signal(
                            key = "respRate", labelRes = Copy.TODAY_READINESS_SIGNAL_RESPIRATORY,
                            detailRes = Copy.TODAY_READINESS_RESP_BAD, flag = Flag.BAD,
                            evidence = respEvidence,
                        )
                    )
                } else if (z >= respZWatch) {
                    signals.add(
                        Signal(
                            key = "respRate", labelRes = Copy.TODAY_READINESS_SIGNAL_RESPIRATORY,
                            detailRes = Copy.TODAY_READINESS_RESP_WATCH, flag = Flag.WATCH,
                            evidence = respEvidence,
                        )
                    )
                }
            }
        }

        // Training Stress Balance (ACWR) + monotony --------------------------
        // On TRIMP ([EffortLoad]) over CALENDAR days ending YESTERDAY (today is a partial day), EWMA
        // acute/chronic (Williams 2017). Mirrors Swift.
        var acwr: Double? = null
        var monotony: Double? = null
        val todayOrdinal = TrainingLoadEngine.dayOrdinal(latest.day)
        if (todayOrdinal != null) {
            val loads = EffortLoad.loadsByOrdinal(history.map { it.day to it.strain })
            val end = todayOrdinal - 1
            val ac = EffortLoad.acuteChronic(loads, end, acuteWindow, chronicWindow, minChronic)
            if (ac != null) {
                val ratio = ac.ratio
                if (ratio != null) {
                    acwr = ratio
                    signals.add(acwrSignal(ratio, acute = ac.acute, chronic = ac.chronic))
                }
                // Foster monotony over the last calendar week of load.
                val mono = EffortLoad.monotony(loads, end, acuteWindow)
                if (mono != null) {
                    monotony = mono
                    if (mono >= 2.0) {
                        signals.add(
                            Signal(
                                key = "monotony", labelRes = Copy.TODAY_READINESS_SIGNAL_TRAINING_VARIETY,
                                detailRes = Copy.TODAY_READINESS_MONOTONY_WATCH, flag = Flag.WATCH,
                                evidence = Evidence.Monotony(mono),
                            )
                        )
                    }
                }
            }
        }

        val (level, headlineRes, summaryRes) = synthesize(
            signals = signals,
            hasHistory = history.isNotEmpty() || acwr != null,
        )
        // RD-confidence: surface how much history backs the read (HRV baseline density, the primary
        // readiness driver), on the Charge baseline's own trust gate so the two tiers agree. A read off a
        // thin baseline must not look as certain as one off a trusted one. Insufficient reads carry
        // CALIBRATING.
        val confidence = ScoreConfidence.readiness(
            hasRead = level != Level.INSUFFICIENT,
            baselineNights = charge?.hrv?.nValid ?: 0,
            fullWindow = Baselines.minNightsTrust,
        )
        return Readiness(
            level = level, headlineRes = headlineRes, summaryRes = summaryRes,
            signals = signals, acwr = acwr, monotony = monotony,
            confidence = confidence,
        )
    }

    // MARK: Signal builders

    /** Build a z-score signal against a Charge baseline (HRV in the log domain, the others linear). No
     *  signal while that baseline is not usable, exactly as the Charge score drops the term.
     *  Swift twin: `ReadinessEngine.zSignal`. */
    private fun zSignal(
        value: Double?, baseline: BaselineState?,
        key: String, labelRes: Copy, unit: MetricUnit, decimals: Int, higherIsBetter: Boolean,
        goodTextRes: Copy, neutralTextRes: Copy,
        watchTextRes: Copy, badTextRes: Copy,
    ): Signal? {
        if (value == null || baseline == null || !baseline.usable) return null
        // Orient z so positive always means "better".
        val z = if (higherIsBetter) RecoveryScorer.higherBetterZ(value, baseline)
        else (baseline.baseline - value) / maxOf(1.253 * baseline.spread, 1e-9)
        val flag: Flag
        val detailRes: Copy
        when {
            z >= 0.5 -> { flag = Flag.GOOD; detailRes = goodTextRes }
            z >= -0.5 -> { flag = Flag.NEUTRAL; detailRes = neutralTextRes }
            z >= -1.0 -> { flag = Flag.WATCH; detailRes = watchTextRes }
            else -> { flag = Flag.BAD; detailRes = badTextRes }
        }
        // The numbers behind the read: today's value vs the baseline, in the metric's units. `baseline` is the
        // geometric mean (ms) for the log-domain HRV state, the mean otherwise.
        val evidence = Evidence.MetricVsBaseline(value, baseline.baseline, decimals, unit)
        return Signal(key = key, labelRes = labelRes, detailRes = detailRes, flag = flag, evidence = evidence)
    }

    /**
     * Format a metric value with the given number of decimals. Mirrors Swift's helper char-for-char:
     * the 0-decimal case uses round-half-AWAY-from-zero (Swift `Int(x.rounded())`, here `Math.round`)
     * — NOT printf's "%.0f" which is round-half-to-EVEN and would disagree at an exact .5; the >0 case
     * uses "%.Nf" (round-half-to-even) to match Swift's `String(format:)`. Locale.US so the separator
     * is always ".".
     */
    private fun acwrSignal(ratio: Double, acute: Double, chronic: Double): Signal {
        // #1033 (ryanbr): route the acute:chronic ratio through the Locale.US-pinned [fmt] helper (matching
        // the evidence line below) so a comma-decimal device locale can't render "1,15" — iOS's
        // String(format:) is already locale-independent. Pure separator fix, no behavior change.
        val evidence = Evidence.TrainingLoad(acute, chronic)
        return when {
            ratio < 0.8 -> Signal(
                key = "acwr", labelRes = Copy.TODAY_READINESS_SIGNAL_TRAINING_LOAD,
                detailRes = Copy.TODAY_READINESS_LOAD_RAMPING_DOWN, flag = Flag.WATCH,
                evidence = evidence,
            )
            ratio < 1.3 -> Signal(
                key = "acwr", labelRes = Copy.TODAY_READINESS_SIGNAL_TRAINING_LOAD,
                detailRes = Copy.TODAY_READINESS_LOAD_SWEET_SPOT, flag = Flag.GOOD,
                evidence = evidence,
            )
            ratio < 1.5 -> Signal(
                key = "acwr", labelRes = Copy.TODAY_READINESS_SIGNAL_TRAINING_LOAD,
                detailRes = Copy.TODAY_READINESS_LOAD_BUILDING_FAST, flag = Flag.WATCH,
                evidence = evidence,
            )
            else -> Signal(
                key = "acwr", labelRes = Copy.TODAY_READINESS_SIGNAL_TRAINING_LOAD,
                detailRes = Copy.TODAY_READINESS_LOAD_SPIKING, flag = Flag.BAD,
                evidence = evidence,
            )
        }
    }

    // MARK: Synthesis

    private fun synthesize(signals: List<Signal>, hasHistory: Boolean): Triple<Level, Copy, Copy> {
        if (!hasHistory || signals.isEmpty()) {
            return Triple(
                Level.INSUFFICIENT, Copy.TODAY_READINESS_TITLE,
                Copy.TODAY_READINESS_MORE_NIGHTS,
            )
        }
        val bad = signals.filter { it.flag == Flag.BAD }
        val watch = signals.filter { it.flag == Flag.WATCH }
        val good = signals.filter { it.flag == Flag.GOOD }
        val recoveryDown = signals.any { it.key in listOf("hrv", "rhr", "respRate") && it.flag == Flag.BAD }
        val loadHigh = signals.any { it.key == "acwr" && it.flag == Flag.BAD }

        if (bad.size >= 2 || (recoveryDown && loadHigh)) {
            return Triple(
                Level.RUNDOWN, Copy.TODAY_READINESS_RUN_DOWN,
                Copy.TODAY_READINESS_RUN_DOWN_SUMMARY,
            )
        }
        if (recoveryDown || loadHigh || bad.size >= 1) {
            return Triple(
                Level.STRAINED, Copy.TODAY_READINESS_STRAINED,
                Copy.TODAY_READINESS_STRAINED_SUMMARY,
            )
        }
        if (good.size >= 2 && watch.isEmpty()) {
            return Triple(
                Level.PRIMED, Copy.TODAY_READINESS_PRIMED,
                Copy.TODAY_READINESS_PRIMED_SUMMARY,
            )
        }
        return Triple(
            Level.BALANCED, Copy.TODAY_READINESS_BALANCED,
            Copy.TODAY_READINESS_BALANCED_SUMMARY,
        )
    }
}
