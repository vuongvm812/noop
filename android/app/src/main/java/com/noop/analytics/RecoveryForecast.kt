package com.noop.analytics

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/*
 * RecoveryForecast.kt — an evening estimate of TOMORROW-morning Charge.
 *
 * Faithful Kotlin mirror of StrandAnalytics/RecoveryForecast.swift. Keep the tunables,
 * the three signed adjustments, the band math, and the confidence tier byte-identical
 * to Swift — cross-platform parity is the contract.
 *
 * Pure, deterministic, DB-free. Given the recent Charge (recovery) history, the recent
 * Effort (strain) history, today's Effort, and how much sleep is planned / banked
 * tonight against the personal sleep need, this projects what tomorrow's Charge is
 * LIKELY to wake at — with an honest ± error band.
 *
 * This is an ESTIMATE, not a measurement. WHOOP's morning recovery is computed from
 * the NEXT night's HRV/RHR/respiration, none of which exist yet at the time this runs;
 * so this can only lean on the levers that ARE known tonight. It is a simple,
 * transparent weighting of three signed nudges around the recent Charge baseline —
 * NOT a learned model — so it stays explainable line by line.
 *
 * Model (all adjustments are signed points ADDED to the baseline mean Charge):
 *
 *   center = mean(recent Charge over the last ~baselineWindow days)
 *
 *   1. Strain debt  — today's Effort vs the recent average Effort. A harder-than-usual
 *      day suppresses tomorrow's Charge; an easier day lifts it a little.
 *      adj1 = -strainWeight * (todayEffort - meanEffort) / effortSpread   (clamped)
 *
 *   2. Sleep adequacy — planned/banked sleep tonight vs the personal sleep need.
 *      Falling short suppresses Charge; meeting or beating it is neutral-to-slightly-
 *      positive (sleeping far beyond need does not keep adding Charge).
 *      adj2 = sleepWeight * clamp(sleepHours/needHours - 1, -1, +0.25)
 *
 *   3. Mean reversion — if recent Charge has been trending, pull the projection a
 *      little back toward the baseline rather than extrapolating the streak.
 *      adj3 = -reversionWeight * recentSlopePerDay                          (clamped)
 *
 *   forecast = clamp(center + adj1 + adj2 + adj3, 0, 100)
 *
 * Error band: the recent day-to-day SD of Charge, floored at minBandPoints and
 * inflated when the baseline is thin (few nights).
 *
 * Gating: returns null unless there are at least minBaselineNights of recent Charge,
 * so a cold-start user never sees a fabricated number. The UI shows the card only when
 * this is non-null.
 */

/** An evening projection of tomorrow-morning Charge (recovery, 0–100). APPROXIMATE. */
data class RecoveryForecast(
    /** The point estimate of tomorrow-morning Charge, 0–100 (whole number). */
    val charge: Double,
    /** Symmetric ± error band on [charge], in Charge points (whole number). */
    val band: Double,
    /** Recent Charge baseline (mean) this projection is anchored to, 0–100. */
    val baseline: Double,
    /** Planned/banked sleep hours tonight that the projection assumed. */
    val plannedSleepHours: Double,
    /** Personal sleep need (hours) the adequacy term compared against. */
    val needHours: Double,
    /** Nights of recent Charge history backing the baseline (drives confidence). */
    val nights: Int,
    /** Per-score certainty tier (reuses the Charge/Effort/Rest confidence ladder). */
    val confidence: ScoreConfidence,
) {
    /** Low end of the band, clamped to [0, 100]. */
    val low: Double get() = max(0.0, charge - band)

    /** High end of the band, clamped to [0, 100]. */
    val high: Double get() = min(100.0, charge + band)
}

object RecoveryForecaster {

    // Tunables (documented, deterministic — NOT learned). Mirror Swift exactly.

    /** Trailing Charge nights used for the baseline mean / SD / slope. */
    const val baselineWindow: Int = 14

    /** Minimum recent Charge nights before a forecast is offered (else null). */
    const val minBaselineNights: Int = 5

    /** Trailing Effort nights used for the strain-debt reference average. */
    const val effortWindow: Int = 14

    /** Charge points a one-spread excess of today's Effort over average removes. */
    const val strainWeight: Double = 9.0

    /** Effort spread (points) defining "one unit" of strain excess. Fixed + explainable. */
    const val effortSpread: Double = 12.0

    /** Max |strain-debt| nudge (points). */
    const val strainAdjCap: Double = 12.0

    /** Charge points a full night short / over of sleep-need moves the estimate. */
    const val sleepWeight: Double = 14.0

    /** Sleep beyond need keeps helping only up to this fraction (diminishing returns). */
    const val sleepOverCap: Double = 0.25

    /** Charge points removed per point/day of recent up-slope (the reversion damping). */
    const val reversionWeight: Double = 1.0

    /** Max |mean-reversion| nudge (points). */
    const val reversionAdjCap: Double = 8.0

    /** Floor on the ± band (points). */
    const val minBandPoints: Double = 8.0

    /** Extra ± points while the baseline is below [trustedNights] (thin history). */
    const val thinBandPoints: Double = 6.0

    /** Recent Charge nights at/above which the band is no longer inflated for thinness. */
    const val trustedNights: Int = 10

    /** Nights informing the sleep need at/above which the need is "solid" (matches 7). */
    const val solidNeedNights: Int = 7

    /** Default personal sleep need (hours) when the caller has none to refine it. */
    const val defaultNeedHours: Double = RestScorer.defaultSleepNeedHours

    /**
     * Project tomorrow-morning Charge from tonight's known levers. APPROXIMATE; null
     * until there are at least [minBaselineNights] of recent Charge to anchor to.
     *
     * @param recentCharge recent daily Charge values, OLDEST→NEWEST (0–100). Only the
     *   trailing [baselineWindow] are used for the baseline mean/SD/slope.
     * @param recentEffort recent daily Effort values, OLDEST→NEWEST (0–100); the
     *   trailing [effortWindow] set the strain-debt reference average. May be empty —
     *   the strain term then drops.
     * @param todayEffort today's Effort (0–100), or null to drop the strain term.
     * @param plannedSleepHours sleep hours planned / already banked tonight. Negative
     *   is treated as 0.
     * @param needHours personal sleep need (hours); null → [defaultNeedHours].
     * @param needNights recent nights that informed [needHours] (0 = still the default);
     *   drives the Rest-style confidence tier.
     */
    fun forecast(
        recentCharge: List<Double>,
        recentEffort: List<Double> = emptyList(),
        todayEffort: Double?,
        plannedSleepHours: Double,
        needHours: Double? = null,
        needNights: Int = 0,
    ): RecoveryForecast? {
        val chargeWindow = recentCharge.takeLast(baselineWindow)
        val nights = chargeWindow.size
        if (nights < minBaselineNights) return null

        val center = mean(chargeWindow)
        val sd = sampleSD(chargeWindow)
        val slope = leastSquaresSlope(chargeWindow)

        // 1. Strain debt: today vs the recent average Effort (both 0–100).
        var strainAdj = 0.0
        if (todayEffort != null && recentEffort.isNotEmpty()) {
            val meanEffort = mean(recentEffort.takeLast(effortWindow))
            val excess = (todayEffort - meanEffort) / effortSpread
            strainAdj = clamp(-strainWeight * excess, -strainAdjCap, strainAdjCap)
        }

        // 2. Sleep adequacy: planned sleep vs personal need.
        val need = max(needHours ?: defaultNeedHours, 0.1)
        val sleep = max(plannedSleepHours, 0.0)
        val sleepRatio = clamp(sleep / need - 1.0, -1.0, sleepOverCap)
        val sleepAdj = sleepWeight * sleepRatio

        // 3. Mean reversion: dampen a recent streak back toward the baseline.
        val reversionAdj = clamp(-reversionWeight * slope, -reversionAdjCap, reversionAdjCap)

        val raw = center + strainAdj + sleepAdj + reversionAdj
        val charge = clamp(raw, 0.0, 100.0).roundToInt().toDouble()

        // ± band: recent SD, floored, inflated while the baseline is thin.
        var band = max(sd, minBandPoints)
        if (nights < trustedNights) band += thinBandPoints
        band = band.roundToInt().toDouble()

        // Confidence rides the SAME calibrating/building/solid ladder as the daily
        // scores. The forecast always clears minBaselineNights to reach here (so it is
        // never CALIBRATING), then it is BUILDING on a thin baseline OR an unrefined
        // sleep-need default, and SOLID only when both the baseline is full
        // (≥ trustedNights) and the personal need is informed.
        val confidence = if (nights >= trustedNights && needNights >= solidNeedNights) {
            ScoreConfidence.SOLID
        } else {
            ScoreConfidence.BUILDING
        }

        return RecoveryForecast(
            charge = charge,
            band = band,
            baseline = center,
            plannedSleepHours = sleep,
            needHours = need,
            nights = nights,
            confidence = confidence,
        )
    }

    // Tonight's plan: bedtime → planned sleep → forecast. Mirrors the Swift extension line for line:
    //
    //   plannedSleep  = (wake − bedtime) × efficiency                  (hours asleep, not hours in bed)
    //   suggested     = wake − needHours ÷ efficiency, floored to the 15-min grid
    //   options       = the anchor bedtime, one hour earlier, one hour later
    //
    // needHours is the SAME SleepNeed context the night is later scored against (base + strain + debt), and
    // needNights counts the recorded nights behind its base, so the confidence tier can reach SOLID.

    /** Trailing nights the typical sleep efficiency (and the caller's typical wake time) are taken over. */
    const val planHistoryNights: Int = 14

    /** Sleep efficiency (asleep ÷ in bed) assumed when no recent night carries one. */
    const val defaultEfficiency: Double = 0.88

    /** Lowest efficiency a plan accepts, so one fragmented night can't demand a 16-hour bedtime. */
    const val minEfficiency: Double = 0.5

    /** Bedtime grid in seconds: the slider step and the suggestion's rounding. */
    const val bedtimeStepSec: Double = 900.0

    /** Offset of the two alternative bedtimes from the anchor, in seconds. */
    const val alternativeOffsetSec: Double = 3600.0

    /** Shortest sleep span (seconds) that counts as the night's sleep starting. */
    const val mainSleepMinSec: Double = 3 * 3600.0

    /** Wake minute-of-day assumed when there is neither an alarm nor any recorded wake (07:00). */
    const val defaultWakeMinute: Double = 7 * 60.0

    /** Local hour on the evening day from which tonight's forecast is live (the afternoon). */
    const val openHour: Int = 12

    /** Local hour on the target day at which it closes at the latest (the logical-day rollover). */
    const val closeHour: Int = 4

    /** The bedtime slider's range: from this local hour on the evening day… */
    const val sliderStartHour: Int = 21

    /** …to this local hour on the target day. */
    const val sliderEndHour: Int = 2

    /** What the forecast knows tonight apart from the bedtime. Swift twin: `RecoveryForecaster.Plan`. */
    data class Plan(
        /** Recent daily Charge, OLDEST→NEWEST, up to and including this morning. */
        val recentCharge: List<Double>,
        /** Recent daily Effort, OLDEST→NEWEST. */
        val recentEffort: List<Double>,
        /** Today's Effort, or null to drop the strain term. */
        val todayEffort: Double?,
        /** Tonight's sleep need in hours (SleepNeed context total). */
        val needHours: Double,
        /** Recorded nights behind the need's base. */
        val needNights: Int,
        /** Tomorrow's wake time, unix seconds. */
        val wakeTs: Double,
        /** Typical sleep efficiency, a fraction in [minEfficiency, 1]. */
        val efficiency: Double,
    )

    /** One bedtime and the forecast it gives. Swift twin: `RecoveryForecaster.BedtimeOption`. */
    data class BedtimeOption(val bedtimeTs: Double, val forecast: RecoveryForecast)

    /**
     * Sleep need for [day] from the nights before it: the SleepNeed context total (base + strain + debt)
     * and the number of recorded nights in the base window. Swift twin: `RecoveryForecaster.need`.
     */
    fun need(nights: List<SleepNeed.Night>, day: String, age: Int?): Pair<Double, Int> {
        val hours = SleepNeed.contexts(nights, listOf(day), age)[day]?.totalHours
            ?: RestScorer.personalizedNeedHours(emptyList(), age)
        val d = DayOrdinal.ordinal(day) ?: return hours to 0
        val seen = HashSet<Int>()
        for (n in nights) {
            val o = DayOrdinal.ordinal(n.day) ?: continue
            val m = n.mainMin ?: continue
            if (o >= d - SleepNeed.baseWindowDays && o < d && m > 0) seen.add(o)
        }
        return hours to seen.size
    }

    /**
     * A stored efficiency as a fraction: values above 1 are percentages. null for a missing, non-finite
     * or non-positive value. Swift twin: `RecoveryForecaster.efficiencyFraction`.
     */
    fun efficiencyFraction(value: Double?): Double? {
        if (value == null || !value.isFinite() || value <= 0.0) return null
        val f = if (value > 1.0) value / 100.0 else value
        return if (f > 1.0) null else f
    }

    /**
     * Typical efficiency: the median of the trailing [planHistoryNights] usable values (OLDEST→NEWEST),
     * floored at [minEfficiency]; [defaultEfficiency] when none. Swift twin: `RecoveryForecaster.typicalEfficiency`.
     */
    fun typicalEfficiency(values: List<Double?>): Double {
        val usable = values.mapNotNull { efficiencyFraction(it) }
        val m = median(usable.takeLast(planHistoryNights)) ?: return defaultEfficiency
        return max(m, minEfficiency)
    }

    /** Typical wake minute-of-day: the median of the trailing [planHistoryNights] values, or null when
     *  there are none. Swift twin: `RecoveryForecaster.typicalWakeMinute`. */
    fun typicalWakeMinute(minutes: List<Double>): Double? =
        median(minutes.filter { it.isFinite() }.takeLast(planHistoryNights))

    /** Median (mean of the middle two for an even count); null when empty. Swift twin: `RecoveryForecaster.median`. */
    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 0) (s[mid - 1] + s[mid]) / 2 else s[mid]
    }

    /** [efficiency] clamped to [minEfficiency, 1]; [defaultEfficiency] when it is not finite.
     *  Swift twin: `RecoveryForecaster.planEfficiency`. */
    internal fun planEfficiency(efficiency: Double): Double =
        if (efficiency.isFinite()) clamp(efficiency, minEfficiency, 1.0) else defaultEfficiency

    /** Hours asleep for a bedtime: time in bed until the wake, times the efficiency. 0 when the bedtime is
     *  at or after the wake. Swift twin: `RecoveryForecaster.plannedSleep`. */
    fun plannedSleep(bedtimeTs: Double, wakeTs: Double, efficiency: Double): Double {
        return max(wakeTs - bedtimeTs, 0.0) / 3600 * planEfficiency(efficiency)
    }

    /** The latest bedtime on the 15-minute grid that still meets [needHours] asleep by the wake.
     *  Swift twin: `RecoveryForecaster.suggestedBedtime`. */
    fun suggestedBedtime(wakeTs: Double, needHours: Double, efficiency: Double): Double {
        val inBed = max(needHours, 0.0) * 3600 / planEfficiency(efficiency)
        return floor((wakeTs - inBed) / bedtimeStepSec) * bedtimeStepSec
    }

    /** [ts] rounded to the nearest grid step, then clamped to [earliest, latest] (the slider's range).
     *  Swift twin: `RecoveryForecaster.snapBedtime`. */
    fun snapBedtime(ts: Double, earliest: Double, latest: Double): Double {
        val snapped = floor(ts / bedtimeStepSec + 0.5) * bedtimeStepSec
        return clamp(snapped, earliest, latest)
    }

    /** The forecast for one bedtime under [plan]; null while the Charge baseline is too thin.
     *  Swift twin: `RecoveryForecaster.forecastAt`. */
    fun forecastAt(plan: Plan, bedtimeTs: Double): RecoveryForecast? =
        forecast(
            recentCharge = plan.recentCharge,
            recentEffort = plan.recentEffort,
            todayEffort = plan.todayEffort,
            plannedSleepHours = plannedSleep(bedtimeTs, plan.wakeTs, plan.efficiency),
            needHours = plan.needHours,
            needNights = plan.needNights,
        )

    /** The anchor bedtime first, then one hour earlier and one hour later; empty while the Charge baseline
     *  is too thin. Swift twin: `RecoveryForecaster.options`. */
    fun options(plan: Plan, anchorTs: Double): List<BedtimeOption> {
        val out = ArrayList<BedtimeOption>(3)
        for (ts in listOf(anchorTs, anchorTs - alternativeOffsetSec, anchorTs + alternativeOffsetSec)) {
            val f = forecastAt(plan, ts) ?: return emptyList()
            out.add(BedtimeOption(ts, f))
        }
        return out
    }

    /** When the night's sleep started: the earliest start among spans that begin at or after [openTs] and
     *  last at least [mainSleepMinSec]; null when none has. Swift twin: `RecoveryForecaster.sleepStart`. */
    fun sleepStart(spans: List<Pair<Double, Double>>, openTs: Double): Double? {
        var first: Double? = null
        for ((start, end) in spans) {
            if (start >= openTs && end - start >= mainSleepMinSec) {
                if (first == null || start < first) first = start
            }
        }
        return first
    }

    /**
     * Whether tonight's forecast is live: inside [openTs, closeTs) and before the night's sleep started.
     * The one gate for both showing the Today card and overwriting the stored forecast; once it closes the
     * stored forecast is frozen. Swift twin: `RecoveryForecaster.isOpen`.
     */
    fun isOpen(nowTs: Double, openTs: Double, closeTs: Double, sleepStartTs: Double?): Boolean {
        if (nowTs < openTs || nowTs >= closeTs) return false
        if (sleepStartTs != null && nowTs >= sleepStartTs) return false
        return true
    }

    // Tonight: one resolver for every surface that shows tomorrow's Charge.

    /** One stored day as the evening plan reads it. Swift twin: `RecoveryForecaster.DayRow`. */
    data class DayRow(
        val day: String,
        val recovery: Double?,
        val strain: Double?,
        val sleepMin: Double?,
        val efficiency: Double?,
    )

    /** Tonight's clock, resolved by the caller from the local calendar and the alarm settings.
     *  Swift twin: `RecoveryForecaster.Window`. */
    data class Window(
        /** The day whose evening this is (its Effort is "today's"). */
        val eveningDay: String,
        /** The day whose morning Charge is forecast. */
        val targetDay: String,
        /** From when the forecast is live (the evening day's afternoon). */
        val openTs: Double,
        /** When it closes at the latest (the target day's early morning). */
        val closeTs: Double,
        /** The slider's earliest bedtime. */
        val earliestTs: Double,
        /** The slider's latest bedtime. */
        val latestTs: Double,
        /** Tomorrow's wake time. */
        val wakeTs: Double,
    )

    /** Tonight's resolved forecast. Swift twin: `RecoveryForecaster.Tonight`. */
    data class Tonight(
        val window: Window,
        val plan: Plan,
        /** The bedtime that meets the need by the wake (not snapped to the slider range). */
        val suggestedTs: Double,
        /** The bedtime the headline uses: a selection inside [[sliderStartTs], latestTs], else the
         *  suggestion, either one snapped to the grid and clamped into that range. */
        val anchorTs: Double,
        /** Where the slider thumb sits: always [anchorTs], so the thumb and the headline cannot disagree. */
        val sliderTs: Double,
        /** The slider's lowest selectable bedtime: the window's earliest bedtime, or now rounded up to
         *  the 15-minute grid once that is later (never past latestTs). */
        val sliderStartTs: Double,
        /** The anchor first, then one hour earlier and one hour later. */
        val options: List<BedtimeOption>,
        /** True while the forecast may be shown on Today and overwritten in storage. */
        val isOpen: Boolean,
    ) {
        /** The headline forecast (the anchor's). */
        val headline: BedtimeOption get() = options[0]
    }

    /**
     * Resolve tonight from the stored days (any order; days on or after the target are ignored), the
     * caller's clock, the recorded sleep spans and an optional slider selection. The anchor is the
     * selection when it lies in [sliderStartTs, latestTs], else the suggestion, clamped into that range,
     * so the headline never names a bedtime outside the slider or already in the past. null while the
     * Charge baseline is too thin. Swift twin: `RecoveryForecaster.tonight`.
     */
    fun tonight(
        rows: List<DayRow>,
        window: Window,
        age: Int?,
        nowTs: Double,
        spans: List<Pair<Double, Double>>,
        selectedTs: Double?,
    ): Tonight? {
        val past = rows.filter { it.day < window.targetDay }.sortedBy { it.day }
        val nights = past.map { SleepNeed.Night(day = it.day, mainMin = it.sleepMin, strain = it.strain) }
        val (needHours, needNights) = need(nights, window.targetDay, age)
        val plan = Plan(
            recentCharge = past.mapNotNull { it.recovery },
            recentEffort = past.mapNotNull { it.strain },
            todayEffort = past.lastOrNull { it.day == window.eveningDay }?.strain,
            needHours = needHours,
            needNights = needNights,
            wakeTs = window.wakeTs,
            efficiency = typicalEfficiency(past.map { it.efficiency }),
        )
        val suggested = suggestedBedtime(plan.wakeTs, plan.needHours, plan.efficiency)
        val floorTs = min(max(window.earliestTs, ceil(nowTs / bedtimeStepSec) * bedtimeStepSec), window.latestTs)
        val anchor = if (selectedTs != null && selectedTs.isFinite() &&
            selectedTs >= floorTs && selectedTs <= window.latestTs
        ) {
            snapBedtime(selectedTs, floorTs, window.latestTs)
        } else {
            snapBedtime(suggested, floorTs, window.latestTs)
        }
        val opts = options(plan, anchor)
        if (opts.isEmpty()) return null
        val open = isOpen(nowTs, window.openTs, window.closeTs, sleepStart(spans, window.openTs))
        return Tonight(
            window = window,
            plan = plan,
            suggestedTs = suggested,
            anchorTs = anchor,
            sliderTs = anchor,
            sliderStartTs = floorTs,
            options = opts,
            isOpen = open,
        )
    }

    // Stats (self-contained so the Swift mirror is line-for-line).

    internal fun mean(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        return values.sum() / values.size
    }

    /** Sample standard deviation (ddof = 1); 0 for fewer than 2 values. */
    internal fun sampleSD(values: List<Double>): Double {
        val n = values.size
        if (n < 2) return 0.0
        val m = mean(values)
        var ss = 0.0
        for (v in values) {
            val d = v - m
            ss += d * d
        }
        return sqrt(ss / (n - 1))
    }

    /** OLS slope of value vs the 0-based index (per-day trend); 0 for < 2 points. */
    internal fun leastSquaresSlope(values: List<Double>): Double {
        val n = values.size
        if (n < 2) return 0.0
        val meanX = (n - 1) / 2.0
        val meanY = mean(values)
        var num = 0.0
        var den = 0.0
        values.forEachIndexed { i, v ->
            val dx = i - meanX
            num += dx * (v - meanY)
            den += dx * dx
        }
        return if (den == 0.0) 0.0 else num / den
    }

    internal fun clamp(x: Double, lo: Double, hi: Double): Double {
        // Inclusive-bound equality is already in range: return x itself so Swift
        // and Kotlin preserve the same IEEE signed-zero bit (#56).
        if (x < lo) return lo
        if (x > hi) return hi
        return x
    }
}
