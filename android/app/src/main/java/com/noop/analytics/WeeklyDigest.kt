package com.noop.analytics

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

// WeeklyDigest.kt — a deterministic, offline "week in review".
//
// Faithful Kotlin port of the Swift StrandAnalytics/WeeklyDigest.swift. Keep the two
// in lockstep — cross-platform parity is required.
//
// Pure, deterministic, DB-free. Given the daily series for each tracked metric
// (keyed by "yyyy-MM-dd"), it builds a Monday-anchored "this week" summary:
//   • per-metric this-week stats (mean / median / min / max / SD / OLS slope),
//   • week-over-week comparison (this week vs the immediately preceding Mon–Sun week),
//   • a "vs baseline" delta (this-week mean vs the trailing [baselineWeeks] weeks),
//   • sleep consistency (SD of this week's Rest values; lower = steadier),
//   • a strain-vs-recovery balance read, the biggest movers, and 1–2 plain-English
//     focal points.
//
// Consumes plain Map<String, Double> day→value series so it stays decoupled from the
// Room DailyMetric type (the UI extracts the metrics and hands them in). No AI required.
//
// Week math is timezone/locale-free: weekday is derived from the "yyyy-MM-dd" string
// with a pure Sakamoto day-of-week, week windows are inclusive ISO-string ranges
// (string comparison is chronological for ISO days), matching the day strings the
// engine emits exactly.

/** How a metric's daily values roll up into one weekly figure. Mirrors Swift `WeeklyRollup`. */
enum class WeeklyRollup {
    /** Mean of the week's daily values. */
    MEAN,
    /** Total over the elapsed span of the week (training load, intensity minutes). */
    SUM,
    /** The week's most recent reading (VO2 max). */
    LATEST,
}

/** Which way a change in a metric reads. Mirrors Swift `WeeklyDirection`. */
enum class WeeklyDirection { HIGHER_BETTER, LOWER_BETTER, NEUTRAL }

/**
 * Everything the engine needs to know about one metric. Mirrors Swift `WeeklyMetricSpec`:
 * the original five keep a fixed [fixedSpread]; later metrics are normalised by the personal
 * SD of their baseline weeks, floored at [spreadFloor].
 */
data class WeeklyMetricSpec(
    /** English name used in the narration (the UI localises its own). */
    val label: String,
    /** Display unit suffix ("" for unitless scores). */
    val unit: String,
    val rollup: WeeklyRollup = WeeklyRollup.MEAN,
    val direction: WeeklyDirection = WeeklyDirection.HIGHER_BETTER,
    /** Fixed normaliser, or null to use the personal baseline SD. */
    val fixedSpread: Double? = null,
    /** Lower bound on the personal baseline SD. */
    val spreadFloor: Double,
    /** Decimal places for the rendered weekly values (0 or 1). */
    val decimals: Int = 0,
    /** False for metrics already in % or a deviation from 0: the change is given in units. */
    val percentChange: Boolean = true,
    /** Weekly goal for a SUM metric (intensity minutes: 150), null otherwise. */
    val weeklyTarget: Double? = null,
)

/**
 * The metrics a weekly digest reports on. The first five are the original headline set and
 * keep their order; the rest only take part when their series carries data. Case order and
 * keys are shared with the Swift enum.
 */
enum class WeeklyMetric(val key: String) {
    CHARGE("charge"),  // recovery, 0–100
    EFFORT("effort"),  // strain / Effort, 0–100
    REST("rest"),      // sleep performance composite, 0–100
    RHR("rhr"),        // resting heart rate, bpm
    HRV("hrv"),        // heart-rate variability, ms
    STEPS("steps"),
    ACTIVE_KCAL("activeKcal"),
    SLEEP_DURATION("sleepDuration"),          // hours asleep
    SLEEP_EFFICIENCY("sleepEfficiency"),      // % of time in bed asleep
    DEEP_SLEEP("deepSleep"),                  // minutes
    REM_SLEEP("remSleep"),                    // minutes
    SLEEP_SCORE("sleepScore"),                // the watch's own sleep score, 0–100
    SPO2("spo2"),                             // overnight mean SpO2, %
    RESPIRATION("respiration"),               // overnight mean breaths/min
    SKIN_TEMP_DEVIATION("skinTempDeviation"), // °C from the personal baseline
    STRESS("stress"),                         // device stress score, 0–100
    BODY_BATTERY_RECHARGE("bodyBatteryRecharge"), // overnight Body Battery gain
    TRAINING_LOAD("trainingLoad"),
    INTENSITY_MINUTES("intensityMinutes"),    // moderate + 2 × vigorous
    VO2MAX("vo2max");

    val spec: WeeklyMetricSpec
        get() = when (this) {
            CHARGE -> WeeklyMetricSpec("Charge", "", fixedSpread = 12.0, spreadFloor = 12.0)
            EFFORT -> WeeklyMetricSpec("Effort", "", fixedSpread = 12.0, spreadFloor = 12.0)
            REST -> WeeklyMetricSpec("Rest", "", fixedSpread = 12.0, spreadFloor = 12.0)
            RHR -> WeeklyMetricSpec(
                "Resting HR", "bpm", direction = WeeklyDirection.LOWER_BETTER,
                fixedSpread = 4.0, spreadFloor = 4.0,
            )
            HRV -> WeeklyMetricSpec("HRV", "ms", fixedSpread = 8.0, spreadFloor = 8.0)
            STEPS -> WeeklyMetricSpec("Steps", "steps", spreadFloor = 500.0)
            ACTIVE_KCAL -> WeeklyMetricSpec("Active calories", "kcal", spreadFloor = 50.0)
            SLEEP_DURATION -> WeeklyMetricSpec(
                "Sleep duration", "h", direction = WeeklyDirection.NEUTRAL,
                spreadFloor = 0.25, decimals = 1,
            )
            SLEEP_EFFICIENCY -> WeeklyMetricSpec(
                "Sleep efficiency", "%", spreadFloor = 2.0, percentChange = false,
            )
            DEEP_SLEEP -> WeeklyMetricSpec("Deep sleep", "min", spreadFloor = 5.0)
            REM_SLEEP -> WeeklyMetricSpec("REM sleep", "min", spreadFloor = 5.0)
            SLEEP_SCORE -> WeeklyMetricSpec("Sleep score", "", spreadFloor = 4.0)
            SPO2 -> WeeklyMetricSpec(
                "Overnight SpO2", "%", direction = WeeklyDirection.NEUTRAL,
                spreadFloor = 0.5, decimals = 1, percentChange = false,
            )
            RESPIRATION -> WeeklyMetricSpec(
                "Respiration", "brpm", direction = WeeklyDirection.NEUTRAL,
                spreadFloor = 0.3, decimals = 1,
            )
            SKIN_TEMP_DEVIATION -> WeeklyMetricSpec(
                "Skin temp deviation", "°C", direction = WeeklyDirection.NEUTRAL,
                spreadFloor = 0.1, decimals = 1, percentChange = false,
            )
            STRESS -> WeeklyMetricSpec("Stress", "", direction = WeeklyDirection.LOWER_BETTER, spreadFloor = 3.0)
            BODY_BATTERY_RECHARGE -> WeeklyMetricSpec("Body Battery recharge", "", spreadFloor = 5.0)
            // More load is neither good nor bad on its own, so it carries no verdict.
            TRAINING_LOAD -> WeeklyMetricSpec(
                "Training load", "", rollup = WeeklyRollup.SUM,
                direction = WeeklyDirection.NEUTRAL, spreadFloor = 25.0,
            )
            INTENSITY_MINUTES -> WeeklyMetricSpec(
                "Intensity minutes", "min", rollup = WeeklyRollup.SUM,
                spreadFloor = 15.0, weeklyTarget = 150.0,
            )
            VO2MAX -> WeeklyMetricSpec(
                "VO2 max", "ml/kg/min", rollup = WeeklyRollup.LATEST,
                spreadFloor = 1.0, percentChange = false,
            )
        }

    /** Human label (matches the rest of the app's naming). */
    val label: String get() = spec.label

    /** Display unit suffix (empty for the unitless 0–100 scores). */
    val unit: String get() = spec.unit

    /** True when a HIGHER value is better. False for lower-better AND neutral metrics. */
    val higherIsBetter: Boolean get() = spec.direction == WeeklyDirection.HIGHER_BETTER

    /**
     * The fixed normaliser for the original five, or the personal-spread floor for later
     * metrics (whose actual normaliser is [WeeklyMetricSummary.personalSpread]).
     */
    val typicalSpread: Double get() = spec.fixedSpread ?: spec.spreadFloor
}

/** Summary statistics for one slice of a daily series. Mirrors Swift SeriesStat. */
data class SeriesStat(
    val mean: Double,
    val median: Double,
    val min: Double,
    val max: Double,
    val stdev: Double,
    val n: Int,
    val slopePerDay: Double,
) {
    companion object {
        val EMPTY = SeriesStat(0.0, 0.0, 0.0, 0.0, 0.0, 0, 0.0)
    }
}

/** The comparison of a `current` period against a `previous` one. Mirrors Swift PeriodComparison. */
data class PeriodComparison(
    val current: SeriesStat,
    val previous: SeriesStat,
    /** Signed change in mean: current.mean − previous.mean. */
    val delta: Double,
    /** Percent change vs previous.mean, or null when previous is empty / mean is 0. */
    val pctChange: Double?,
    /** Direction: -1 (down), 0 (flat / a period empty), +1 (up). */
    val direction: Int,
)

/** One metric's line in the weekly digest. */
data class WeeklyMetricSummary(
    val metric: WeeklyMetric,
    val thisWeek: SeriesStat,
    val weekOverWeek: PeriodComparison,
    val baselineMean: Double?,
    val vsBaseline: Double?,
    /** Rolled-up weekly figures for a SUM / LATEST metric (null for MEAN). */
    val rolledThis: Double? = null,
    val rolledLast: Double? = null,
    /** Personal baseline SD normalising a non-headline metric, null when the baseline is thin. */
    val personalSpread: Double? = null,
) {
    /** This week's figure: the mean, or the total / latest reading per the roll-up. */
    val thisValue: Double get() = rolledThis ?: thisWeek.mean

    /** Last week's figure on the same roll-up. */
    val lastValue: Double get() = rolledLast ?: weekOverWeek.previous.mean

    /** True when this week carried at least one reading for the metric. */
    val hasData: Boolean get() = thisWeek.n > 0

    /** Fraction of the weekly target reached (SUM metrics with a target only). */
    val targetProgress: Double?
        get() {
            val target = metric.spec.weeklyTarget ?: return null
            if (target <= 0 || !hasData) return null
            return thisValue / target
        }

    /** Signed week-over-week change in the metric's own units (this − last). */
    val wowDelta: Double get() = weekOverWeek.delta

    /**
     * Week-over-week change as GOOD (+1) / BAD (-1) / FLAT (0), folding in
     * `higherIsBetter` (so a Resting-HR rise reads as worse). 0 when flat or a period empty.
     */
    val wowGoodness: Int
        get() {
            if (weekOverWeek.direction == 0 || metric.spec.direction == WeeklyDirection.NEUTRAL) return 0
            val up = weekOverWeek.direction > 0
            return if (up == metric.higherIsBetter) 1 else -1
        }

    /** The week-over-week change scaled by the metric's typical spread. 0 when a period empty. */
    val normalisedMove: Double
        get() {
            if (weekOverWeek.current.n == 0 || weekOverWeek.previous.n == 0) return 0.0
            val s = metric.spec.fixedSpread ?: personalSpread ?: return 0.0
            return if (s > 0) wowDelta / s else 0.0
        }

    /**
     * True when the week-over-week comparison rests on a sparse side: both weeks carry at
     * least one reading, but either has fewer than [WeeklyDigestEngine.MIN_DAYS_FOR_FOCUS]
     * days. A rough comparison still shows its raw arrow + %, but the UI shouldn't dress it
     * in a confident good/bad verdict — a 43% "drop" off 2 days isn't a trend (the #463
     * chips-vs-summary contradiction). Mirrors Swift `isRoughComparison`.
     */
    val isRoughComparison: Boolean
        get() {
            // A total or a latest reading is not an average of days.
            if (metric.spec.rollup != WeeklyRollup.MEAN) return false
            val cur = weekOverWeek.current.n
            val prev = weekOverWeek.previous.n
            if (cur == 0 || prev == 0) return false
            return cur < WeeklyDigestEngine.MIN_DAYS_FOR_FOCUS ||
                prev < WeeklyDigestEngine.MIN_DAYS_FOR_FOCUS
        }
}

/** How this week's Effort sat against this week's Charge. */
enum class BalanceRead {
    OVERREACHING, BALANCED, UNDERLOADED, INSUFFICIENT;

    /** Plain-English line for the UI. Byte-identical to Swift. */
    val sentence: String
        get() = when (this) {
            OVERREACHING ->
                "Your Effort outpaced your Charge this week: you leaned into the red. Watch for a recovery dip."
            BALANCED ->
                "Effort and Charge tracked together this week: a sustainable load."
            UNDERLOADED ->
                "You carried more Charge than you spent this week: there's room to push if you want it."
            INSUFFICIENT ->
                "Not enough Effort and Charge days this week to read your balance."
        }
}

/** The complete week-in-review. */
data class WeeklyDigest(
    /** The Monday that anchors "this week" ("yyyy-MM-dd"). */
    val weekStart: String,
    /** The Sunday that ends "this week" ("yyyy-MM-dd"). */
    val weekEnd: String,
    /** Per-metric summaries, in WeeklyMetric.values() order. */
    val metrics: List<WeeklyMetricSummary>,
    /** Distinct days this week that carried at least one reading. */
    val daysWithData: Int,
    /** SD of this week's Rest values (lower = steadier), or null with < 2 Rest nights. */
    val sleepConsistencySD: Double?,
    /** Strain-vs-recovery balance read for the week. */
    val balance: BalanceRead,
    /** 1–2 plain-English focal points, most salient first. */
    val focalPoints: List<String>,
) {
    fun summary(metric: WeeklyMetric): WeeklyMetricSummary? = metrics.firstOrNull { it.metric == metric }

    /** True when no metric carried a single reading this week. */
    val isEmpty: Boolean get() = daysWithData == 0
}

object WeeklyDigestEngine {

    /** Complete weeks before "this week" forming the vs-baseline comparison. */
    const val BASELINE_WEEKS = 4
    /** Min days each side before a week-over-week move is "real" enough to surface. */
    const val MIN_DAYS_FOR_FOCUS = 3
    /** Effort−Charge gap (points) inside which the week is "balanced". */
    const val BALANCE_BAND = 10.0
    /** Normalised-move threshold (in "typical spreads") for a focal mover. */
    const val FOCUS_THRESHOLD = 0.5

    // MARK: - Entry point

    /**
     * Build the weekly digest anchored on the Monday of the week containing [anchorDay]
     * ("yyyy-MM-dd", typically today). A non-parseable string yields an all-empty digest.
     *
     * [effortDisplayFactor] is a multiplier applied to EFFORT averages (and the pts
     * fallback magnitude) in the rendered focal-point sentences ONLY, so a user on the
     * 0–21 Effort scale (#268) never reads a stored 0–100 mean in prose. Percent changes
     * are scale-invariant and stay untouched. Defaults to 1.0 (stored scale) so existing
     * callers are byte-identical. Display-only: no stat, delta or threshold changes.
     */
    fun build(
        byMetric: Map<WeeklyMetric, Map<String, Double>>,
        anchorDay: String,
        effortDisplayFactor: Double = 1.0,
    ): WeeklyDigest {
        val monday = mondayOfWeek(anchorDay) ?: return emptyDigest(anchorDay, anchorDay)
        val sunday = addDays(monday, 6)
        val lastMonday = addDays(monday, -7)
        val lastSunday = addDays(monday, -1)

        // Baseline: BASELINE_WEEKS complete weeks ending the day before last week starts.
        val baselineEnd = addDays(lastMonday, -1)
        val baselineStart = addDays(lastMonday, -7 * BASELINE_WEEKS)

        val summaries = mutableListOf<WeeklyMetricSummary>()
        val daysSeen = mutableSetOf<String>()
        for (metric in WeeklyMetric.values()) {
            valuesInRange(byMetric[metric] ?: emptyMap(), monday, sunday, daysSeen)
        }
        // How far into the week the data runs, so a SUM metric compares this week so far
        // against the same weekdays of earlier weeks. A finished week spans all seven days.
        val span = daysSeen.maxOrNull()?.let { dayIndex(monday, it) + 1 } ?: 7

        for (metric in WeeklyMetric.values()) {
            val series = byMetric[metric] ?: emptyMap()
            val thisVals = valuesInRange(series, monday, sunday, null)
            val lastVals = valuesInRange(series, lastMonday, lastSunday, null)
            val baseVals = valuesInRange(series, baselineStart, baselineEnd, null)

            when (metric.spec.rollup) {
                WeeklyRollup.MEAN -> {
                    val thisStat = stat(thisVals)
                    val wow = compare(thisVals, lastVals)
                    val baseMean = if (baseVals.isEmpty()) null else baseVals.sum() / baseVals.size
                    val vsBase = baseMean?.let { thisStat.mean - it }
                    val spread = if (metric.spec.fixedSpread == null) {
                        personalSpread(baseVals, MIN_DAYS_FOR_FOCUS, metric.spec.spreadFloor)
                    } else {
                        null
                    }
                    summaries.add(WeeklyMetricSummary(metric, thisStat, wow, baseMean, vsBase, personalSpread = spread))
                }
                WeeklyRollup.SUM ->
                    summaries.add(sumSummary(metric, series, thisVals, lastMonday, baselineStart, span))
                WeeklyRollup.LATEST ->
                    summaries.add(latestSummary(metric, thisVals, lastVals, baseVals))
            }
        }

        val restStat = summaries.firstOrNull { it.metric == WeeklyMetric.REST }?.thisWeek
        val restConsistency = if ((restStat?.n ?: 0) >= 2) restStat?.stdev else null

        val balance = balanceRead(summaries)
        val focal = focalPoints(summaries, balance, restConsistency, effortDisplayFactor, span)

        return WeeklyDigest(
            weekStart = monday, weekEnd = sunday, metrics = summaries,
            daysWithData = daysSeen.size, sleepConsistencySD = restConsistency,
            balance = balance, focalPoints = focal,
        )
    }

    // MARK: - Single-slice statistics (ported from ComparisonEngine.swift)

    /** Summarise a slice into a SeriesStat. Slope is OLS vs the 0-based index. EMPTY when empty. */
    fun stat(values: List<Double>): SeriesStat {
        val n = values.size
        if (n == 0) return SeriesStat.EMPTY

        val mean = values.sum() / n
        val med = median(values)
        val mn = values.min()
        val mx = values.max()

        val sd = if (n >= 2) {
            var ss = 0.0
            for (v in values) { val dd = v - mean; ss += dd * dd }
            sqrt(ss / (n - 1))
        } else 0.0

        return SeriesStat(mean, med, mn, mx, sd, n, leastSquaresSlope(values))
    }

    /** Compare a current slice to a previous slice (delta/direction on the means). */
    fun compare(current: List<Double>, previous: List<Double>): PeriodComparison {
        val cur = stat(current)
        val prev = stat(previous)
        val delta = cur.mean - prev.mean

        val pct = if (prev.n > 0 && prev.mean != 0.0) (cur.mean - prev.mean) / abs(prev.mean) * 100.0 else null

        val direction = when {
            cur.n == 0 || prev.n == 0 -> 0
            delta > 0 -> 1
            delta < 0 -> -1
            else -> 0
        }
        return PeriodComparison(cur, prev, delta, pct, direction)
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val s = values.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }

    /** OLS slope of values vs their 0-based index. 0 when n < 2. */
    private fun leastSquaresSlope(values: List<Double>): Double {
        val n = values.size
        if (n < 2) return 0.0
        val meanX = (n - 1) / 2.0
        val meanY = values.sum() / n
        var sxy = 0.0
        var sxx = 0.0
        for (i in 0 until n) {
            val dx = i - meanX
            sxy += dx * (values[i] - meanY)
            sxx += dx * dx
        }
        return if (sxx > 0) sxy / sxx else 0.0
    }

    // MARK: - Non-mean roll-ups

    /**
     * A SUM metric: this week's total so far against last week's total over the same
     * weekdays; the baseline is the mean same-span total of the baseline weeks that carried
     * any reading, and the spread is the SD of those totals.
     * Swift twin: `WeeklyDigestEngine.sumSummary`.
     */
    private fun sumSummary(
        metric: WeeklyMetric,
        series: Map<String, Double>,
        thisVals: List<Double>,
        lastMonday: String,
        baselineStart: String,
        span: Int,
    ): WeeklyMetricSummary {
        val lastVals = valuesInRange(series, lastMonday, addDays(lastMonday, span - 1), null)
        val weekTotals = mutableListOf<Double>()
        for (w in 0 until BASELINE_WEEKS) {
            val wkStart = addDays(baselineStart, 7 * w)
            val vals = valuesInRange(series, wkStart, addDays(wkStart, span - 1), null)
            if (vals.isNotEmpty()) weekTotals.add(vals.sum())
        }
        val thisTotal = thisVals.sum()
        val lastTotal = lastVals.sum()
        val baseMean = if (weekTotals.isEmpty()) null else weekTotals.sum() / weekTotals.size
        return WeeklyMetricSummary(
            metric = metric,
            thisWeek = stat(thisVals),
            weekOverWeek = rolledComparison(thisVals, lastVals, thisTotal, lastTotal),
            baselineMean = baseMean,
            vsBaseline = baseMean?.let { thisTotal - it },
            rolledThis = thisTotal,
            rolledLast = lastTotal,
            personalSpread = personalSpread(weekTotals, 2, metric.spec.spreadFloor),
        )
    }

    /** A LATEST metric: the week's most recent reading against last week's. Swift twin: `WeeklyDigestEngine.latestSummary`. */
    private fun latestSummary(
        metric: WeeklyMetric,
        thisVals: List<Double>,
        lastVals: List<Double>,
        baseVals: List<Double>,
    ): WeeklyMetricSummary {
        val thisLatest = thisVals.lastOrNull() ?: 0.0
        val lastLatest = lastVals.lastOrNull() ?: 0.0
        val baseMean = if (baseVals.isEmpty()) null else baseVals.sum() / baseVals.size
        return WeeklyMetricSummary(
            metric = metric,
            thisWeek = stat(thisVals),
            weekOverWeek = rolledComparison(thisVals, lastVals, thisLatest, lastLatest),
            baselineMean = baseMean,
            vsBaseline = if (thisVals.isEmpty()) null else baseMean?.let { thisLatest - it },
            rolledThis = thisLatest,
            rolledLast = lastLatest,
            personalSpread = personalSpread(baseVals, MIN_DAYS_FOR_FOCUS, metric.spec.spreadFloor),
        )
    }

    /**
     * A comparison whose delta / % / direction are on rolled-up figures, keeping the day stats.
     * Swift twin: `WeeklyDigestEngine.rolledComparison`.
     */
    private fun rolledComparison(
        cur: List<Double>,
        prev: List<Double>,
        thisValue: Double,
        lastValue: Double,
    ): PeriodComparison {
        val c = stat(cur)
        val p = stat(prev)
        val delta = thisValue - lastValue
        val pct = if (p.n > 0 && lastValue != 0.0) delta / abs(lastValue) * 100.0 else null
        val direction = when {
            c.n == 0 || p.n == 0 -> 0
            delta > 0 -> 1
            delta < 0 -> -1
            else -> 0
        }
        return PeriodComparison(c, p, delta, pct, direction)
    }

    /**
     * Personal SD via [Baselines.rollingMeanSD] (σ floored at [floor]); null under [minCount] values.
     * Swift twin: `WeeklyDigestEngine.personalSpread`.
     */
    private fun personalSpread(values: List<Double>, minCount: Int, floor: Double): Double? {
        if (values.size < minCount) return null
        val cfg = MetricCfg(
            minVal = -Double.MAX_VALUE, maxVal = Double.MAX_VALUE, floorSpread = floor,
            halfLifeB = 14.0, halfLifeS = 21.0,
        )
        // rollingMeanSD stores σ / 1.253 (its abs-dev space); undo that.
        return Baselines.rollingMeanSD(values, cfg, window = values.size).spread * 1.253
    }

    /** Whole days from [start] to [end]; 0 if either is unparseable. Swift twin: `WeeklyDigestEngine.dayIndex`. */
    private fun dayIndex(start: String, end: String): Int {
        val a = parseYMD(start) ?: return 0
        val b = parseYMD(end) ?: return 0
        return julianDayNumber(b[0], b[1], b[2]) - julianDayNumber(a[0], a[1], a[2])
    }

    // MARK: - Balance read

    private fun balanceRead(summaries: List<WeeklyMetricSummary>): BalanceRead {
        val effort = summaries.firstOrNull { it.metric == WeeklyMetric.EFFORT }?.thisWeek
        val charge = summaries.firstOrNull { it.metric == WeeklyMetric.CHARGE }?.thisWeek
        if (effort == null || charge == null ||
            effort.n < MIN_DAYS_FOR_FOCUS || charge.n < MIN_DAYS_FOR_FOCUS
        ) {
            return BalanceRead.INSUFFICIENT
        }
        val gap = effort.mean - charge.mean
        return when {
            gap > BALANCE_BAND -> BalanceRead.OVERREACHING
            gap < -BALANCE_BAND -> BalanceRead.UNDERLOADED
            else -> BalanceRead.BALANCED
        }
    }

    // MARK: - Focal points

    /** Pick 1–2 plain-English focal points, most salient first. Swift twin: `WeeklyDigestEngine.focalPoints`. */
    private fun focalPoints(
        summaries: List<WeeklyMetricSummary>,
        balance: BalanceRead,
        consistencySD: Double?,
        effortDisplayFactor: Double = 1.0,
        span: Int = 7,
    ): List<String> {
        val movers = summaries
            .filter { focusEligible(it, span) && abs(it.normalisedMove) >= FOCUS_THRESHOLD }
            .sortedByDescending { abs(it.normalisedMove) }

        val lines = mutableListOf<String>()

        movers.firstOrNull()?.let { lines.add(moverSentence(it, effortDisplayFactor)) }

        if (balance == BalanceRead.OVERREACHING || balance == BalanceRead.UNDERLOADED) {
            lines.add(balance.sentence)
        } else if (movers.size >= 2) {
            lines.add(moverSentence(movers[1], effortDisplayFactor))
        }

        // Nothing cleared the mover bar. Distinguish three very different reasons:
        //   • a SPARSE CURRENT week (too few days to call a week-over-week trend — saying
        //     "nothing moved" there contradicts the per-metric chips, the #463 report),
        //   • a SPARSE PREVIOUS week (typical new user in week 2): movers are gated on
        //     previous.n ≥ MIN_DAYS_FOR_FOCUS, so nothing can surface even when the chips
        //     show big raw %s off last week's 1–2 days — the same contradiction, mirrored,
        //   • a genuinely steady full week.
        if (lines.isEmpty()) {
            val currentDays = summaries.maxOfOrNull { it.weekOverWeek.current.n } ?: 0
            val prevDays = summaries.maxOfOrNull { it.weekOverWeek.previous.n } ?: 0
            if (currentDays in 1 until MIN_DAYS_FOR_FOCUS) {
                val dayWord = if (currentDays == 1) "day" else "days"
                lines.add(
                    "Only $currentDays $dayWord into this week so far, too early to " +
                        "call a week-over-week trend yet.",
                )
            } else if (currentDays >= MIN_DAYS_FOR_FOCUS && prevDays in 1 until MIN_DAYS_FOR_FOCUS) {
                val dayWord = if (prevDays == 1) "day" else "days"
                lines.add(
                    "Last week only had $prevDays $dayWord of data, so week-over-week " +
                        "changes are rough, not a trend.",
                )
            } else if (consistencySD != null && consistencySD <= 6.0) {
                lines.add("A steady week: Rest held even (±${round1(consistencySD)} pts) and nothing moved much.")
            } else {
                lines.add("A steady week: no metric moved meaningfully from last week.")
            }
        }

        return lines.take(2)
    }

    /**
     * Whether a metric can be a focal mover: an average needs [MIN_DAYS_FOR_FOCUS] days each
     * side; a total needs the week that far along; a latest reading needs one each side.
     * Swift twin: `WeeklyDigestEngine.focusEligible`.
     */
    private fun focusEligible(s: WeeklyMetricSummary, span: Int): Boolean {
        val cur = s.weekOverWeek.current.n
        val prev = s.weekOverWeek.previous.n
        return when (s.metric.spec.rollup) {
            WeeklyRollup.MEAN -> cur >= MIN_DAYS_FOR_FOCUS && prev >= MIN_DAYS_FOR_FOCUS
            WeeklyRollup.SUM -> span >= MIN_DAYS_FOR_FOCUS && cur >= 1 && prev >= 1
            WeeklyRollup.LATEST -> cur >= 1 && prev >= 1
        }
    }

    /**
     * Render one mover as a plain-English sentence with good/bad framing. Mirrors Swift.
     * [effortDisplayFactor] rescales the EFFORT averages (and its pts fallback) for
     * display only — % is scale-invariant.
     */
    private fun moverSentence(s: WeeklyMetricSummary, effortDisplayFactor: Double = 1.0): String {
        val spec = s.metric.spec
        val f = if (s.metric == WeeklyMetric.EFFORT) effortDisplayFactor else 1.0
        val directionWord = if (s.wowDelta > 0) "up" else if (s.wowDelta < 0) "down" else "flat"
        val pct = s.weekOverWeek.pctChange
        val magnitude = if (spec.percentChange && pct != null && abs(pct) >= 1) {
            "${roundedInt(abs(pct))}%"
        } else {
            val suffix = if (spec.unit.isEmpty() || spec.unit == "%") " pts" else " ${spec.unit}"
            "${round1(abs(s.wowDelta) * f)}$suffix"
        }
        val frame = when (s.wowGoodness) {
            1 -> ", a good sign"
            -1 -> ", worth a look"
            else -> ""
        }
        val thisAvg = formatValue(s.thisValue * f, spec.decimals)
        val lastAvg = formatValue(s.lastValue * f, spec.decimals)
        val word = when (spec.rollup) {
            WeeklyRollup.MEAN -> "avg"
            WeeklyRollup.SUM -> "total"
            WeeklyRollup.LATEST -> "latest"
        }
        return "${spec.label} is $directionWord $magnitude week over week ($word $thisAvg vs $lastAvg)$frame."
    }

    // MARK: - Range extraction

    /**
     * Values of [series] whose day is within [start, end] inclusive (ISO string comparison is
     * chronological), ordered chronologically so the SeriesStat slope is meaningful. When
     * [daysSeen] is non-null, the days that carried a value are recorded into it.
     */
    private fun valuesInRange(
        series: Map<String, Double>,
        start: String,
        end: String,
        daysSeen: MutableSet<String>?,
    ): List<Double> {
        val inRange = series.filterKeys { it in start..end }
        daysSeen?.addAll(inRange.keys)
        return inRange.entries.sortedBy { it.key }.map { it.value }
    }

    // MARK: - Pure week math (timezone/locale-free)

    /** The Monday (ISO) of the week containing [day]. null if [day] is not a valid yyyy-MM-dd. */
    fun mondayOfWeek(day: String): String? {
        val ymd = parseYMD(day) ?: return null
        val w = weekday(ymd[0], ymd[1], ymd[2]) ?: return null
        // weekday: 0=Sun … 6=Sat. Days since Monday: Mon=0 … Sun=6.
        val sinceMonday = (w + 6) % 7
        return addDays(day, -sinceMonday)
    }

    /** Add [n] days (may be negative) to a "yyyy-MM-dd" day. Returns the input if unparseable. */
    fun addDays(day: String, n: Int): String {
        val ymd = parseYMD(day) ?: return day
        val jdn = julianDayNumber(ymd[0], ymd[1], ymd[2]) + n
        val out = fromJulianDayNumber(jdn)
        return formatYMD(out[0], out[1], out[2])
    }

    /** Sakamoto's day-of-week: 0=Sunday … 6=Saturday. null for an invalid date. */
    fun weekday(y: Int, m: Int, d: Int): Int? {
        if (m !in 1..12 || d < 1 || d > daysInMonth(y, m)) return null
        val t = intArrayOf(0, 3, 2, 5, 0, 3, 5, 1, 4, 6, 2, 4)
        var yy = y
        if (m < 3) yy -= 1
        return (yy + yy / 4 - yy / 100 + yy / 400 + t[m - 1] + d) % 7
    }

    private fun daysInMonth(y: Int, m: Int): Int = when (m) {
        1, 3, 5, 7, 8, 10, 12 -> 31
        4, 6, 9, 11 -> 30
        2 -> if (isLeap(y)) 29 else 28
        else -> 0
    }

    private fun isLeap(y: Int): Boolean = (y % 4 == 0 && y % 100 != 0) || (y % 400 == 0)

    /** Parse "yyyy-MM-dd" into [y, m, d], validating the date is real. null otherwise. */
    fun parseYMD(s: String): IntArray? {
        val parts = s.split("-")
        if (parts.size != 3) return null
        val y = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        val d = parts[2].toIntOrNull() ?: return null
        if (m !in 1..12 || d < 1 || d > daysInMonth(y, m)) return null
        return intArrayOf(y, m, d)
    }

    private fun formatYMD(y: Int, m: Int, d: Int): String {
        val yy = if (y < 1000) y.toString().padStart(4, '0') else y.toString()
        val mm = if (m < 10) "0$m" else "$m"
        val dd = if (d < 10) "0$d" else "$d"
        return "$yy-$mm-$dd"
    }

    /** Proleptic-Gregorian date → Julian Day Number (integer-only date arithmetic). */
    private fun julianDayNumber(y: Int, m: Int, d: Int): Int {
        val a = (14 - m) / 12
        val yy = y + 4800 - a
        val mm = m + 12 * a - 3
        return d + (153 * mm + 2) / 5 + 365 * yy + yy / 4 - yy / 100 + yy / 400 - 32045
    }

    /** Inverse of [julianDayNumber] → [y, m, d]. */
    private fun fromJulianDayNumber(jdn: Int): IntArray {
        val a = jdn + 32044
        val b = (4 * a + 3) / 146097
        val c = a - (146097 * b) / 4
        val dd = (4 * c + 3) / 1461
        val e = c - (1461 * dd) / 4
        val mm = (5 * e + 2) / 153
        val day = e - (153 * mm + 2) / 5 + 1
        val month = mm + 3 - 12 * (mm / 10)
        val year = 100 * b + dd - 4800 + mm / 10
        return intArrayOf(year, month, day)
    }

    // MARK: - Empty digest

    private fun emptyDigest(weekStart: String, weekEnd: String): WeeklyDigest {
        val summaries = WeeklyMetric.values().map { m ->
            WeeklyMetricSummary(m, SeriesStat.EMPTY, compare(emptyList(), emptyList()), null, null)
        }
        return WeeklyDigest(weekStart, weekEnd, summaries, 0, null, BalanceRead.INSUFFICIENT, emptyList())
    }

    // MARK: - Formatting helpers

    /**
     * Round half away from zero, as Swift's `rounded()` does (`roundToInt` rounds half up,
     * which differs for negative halves; skin-temp deviation can be negative). `a - floor(a)`
     * is exact, unlike `floor(a + 0.5)`. Swift twin: `WeeklyDigestEngine.roundedInt`.
     */
    private fun roundedInt(x: Double): Int {
        val a = abs(x)
        val f = floor(a)
        val r = if (a - f >= 0.5) f + 1 else f
        return (if (x < 0) -r else r).toInt()
    }

    private fun round1(x: Double): Double = roundedInt(x * 10) / 10.0

    /** A weekly value for narration: whole number, or one decimal (never "-0.0"). Swift twin: `WeeklyDigestEngine.formatValue`. */
    private fun formatValue(x: Double, decimals: Int): String =
        if (decimals == 0) "${roundedInt(x)}" else "${round1(x) + 0.0}"
}
