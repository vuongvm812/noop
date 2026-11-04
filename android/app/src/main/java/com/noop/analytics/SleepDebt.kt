package com.noop.analytics

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/*
 * SleepDebt.kt — an actionable next-night sleep-debt estimate over the last N nights.
 *
 * Faithful Kotlin mirror of StrandAnalytics/SleepDebt.swift. Keep the window cap,
 * the skip-no-data rule, and the debt recurrence byte-identical to
 * Swift — the two clients must report the same balance for the same nights.
 *
 * Pure, deterministic, DB-free. Given a chronological series of per-night total
 * sleep (minutes) and a personal sleep need (hours), it accumulates a running
 * debt from unmet current need across a capped trailing window (14 calendar days
 * by default) and reports that debt plus the raw per-night deltas behind it. A day
 * with no record decays the debt by the same 0.55 carry (#55).
 *
 * HONEST by construction:
 *   - Debt is 55% of the current unmet need. Meeting need (including carried debt)
 *     clears it; extra sleep never creates a positive bank.
 *   - The window is capped (default 14 days) so debt never compounds across months of
 *     history — only the recent fortnight is in scope.
 *   - Nights with no usable sleep total are never zero-filled, so a gap in wear never
 *     reads as a full night of debt; the debt decays across it.
 *   - The need value is supplied by the caller ([RestScorer.defaultSleepNeedHours] =
 *     8.0 by default; the caller passes any per-user override). Computation here stays
 *     a pure function of (series, need, window).
 */

/**
 * One night's contribution to the ledger: its day key, minutes slept, and the signed
 * delta against need (positive = surplus, negative = deficit). Mirrors Swift
 * `SleepDebtNight`.
 */
data class SleepDebtNight(
    /** "yyyy-MM-dd" day key for the night (as carried on the DailyMetric). */
    val day: String,
    /** Total sleep for the night (minutes). */
    val sleptMin: Double,
    /** Signed delta vs need (minutes): sleptMin − needMin. Positive = surplus. */
    val deltaMin: Double,
)

/**
 * The rolling sleep-debt ledger over the capped trailing window. Mirrors Swift
 * `SleepDebtLedger`.
 */
data class SleepDebtLedger(
    /** Current debt as a negative balance in minutes; never positive. */
    val balanceMin: Double,
    /** Per-night contributions, oldest → newest (skipped nights absent); the per-night bar/spark. */
    val nights: List<SleepDebtNight>,
    /** Personal sleep need (minutes) the ledger was computed against (for labelling). */
    val needMin: Double,
) {
    /** Number of nights that contributed (nights with usable sleep data). */
    val nightCount: Int get() = nights.size

    /** True when the net balance is a debt (under need overall). */
    val isDebt: Boolean get() = balanceMin < 0.0

    /** Magnitude of the balance in minutes, regardless of sign. */
    val magnitudeMin: Double get() = abs(balanceMin)
}

object SleepDebt {

    /**
     * Cap the ledger at the trailing two weeks — recent enough to be actionable, short
     * enough that one rough patch doesn't read as months of compounding debt.
     */
    const val DEFAULT_WINDOW_NIGHTS: Int = 14

    /**
     * Calculated debts below this threshold are treated as balanced. Exactly ten minutes remains debt.
     */
    const val ON_TARGET_BAND_MIN: Double = 10.0

    /** Fraction of current unmet need carried into the next night's target. */
    const val DEBT_CARRY: Double = 0.55

    /**
     * Sleep credited toward debt for one day. [mainSleepMin] remains the canonical
     * main-night total used by Rest and the sleep headline; separately-recorded naps
     * add repayment credit without changing that canonical figure. A day with no
     * usable main sleep stays missing, and a malformed negative nap total is ignored.
     *
     * This is deliberately arithmetic rather than a physiological model: callers
     * classify the day's main-night group and pass only asleep minutes from blocks
     * outside that group. Mirrors Swift `SleepDebt.creditedSleepMin` value-for-value.
     */
    fun creditedSleepMin(mainSleepMin: Double?, napSleepMin: Double = 0.0): Double? {
        val main = mainSleepMin?.takeIf { it > 0.0 } ?: return null
        return main + napSleepMin.coerceAtLeast(0.0)
    }

    /**
     * Debt values oldest → newest for local fallback surfaces. Each usable day's value is the ledger
     * through that day over the trailing [window] CALENDAR days. An imported value wins verbatim for its
     * day's output only. Mirrors Swift `SleepDebt.debtSeries`.
     */
    fun debtSeries(
        series: List<Pair<String, Double?>>,
        needHours: Double = RestScorer.defaultSleepNeedHours,
        importedDebtMin: Map<String, Double> = emptyMap(),
        window: Int = DEFAULT_WINDOW_NIGHTS,
    ): List<Pair<String, Double>> {
        val result = ArrayList<Pair<String, Double>>(series.size)
        for ((i, row) in series.withIndex()) {
            val (day, slept) = row
            val sleptMin = slept?.takeIf { it > 0.0 }
            val imported = importedDebtMin[day]
            if (imported != null) result.add(day to imported)
            else if (sleptMin != null) {
                val prefix = series.subList(maxOf(0, i - 4 * window.coerceAtLeast(1)), i + 1)
                result.add(day to ledger(prefix, needHours = needHours, window = window).magnitudeMin)
            }
        }
        return result
    }

    /**
     * Build the ledger from a `List<Pair<day, totalSleepMin?>>` series. A null or non-positive total
     * marks a day with no usable night; a repeated day keeps its last row. [window] is CALENDAR days
     * ending at the newest usable night. Day by day: a recorded night carries
     * `0.55 × max(0, need + debt − slept)`, a day with no record decays the debt by 0.55 (#55). Below
     * ten minutes clears. Mirrors Swift `SleepDebt.ledger` byte-for-byte.
     */
    fun ledger(
        series: List<Pair<String, Double?>>,
        needHours: Double = RestScorer.defaultSleepNeedHours,
        window: Int = DEFAULT_WINDOW_NIGHTS,
    ): SleepDebtLedger {
        val needMin = needHours.coerceAtLeast(0.0) * 60.0
        val cap = window.coerceAtLeast(1)
        val byOrd = HashMap<Int, Pair<String, Double>>()
        for ((day, slept) in series) {
            val o = DayOrdinal.ordinal(day) ?: continue
            if (slept != null && slept > 0.0) byOrd[o] = day to slept else byOrd.remove(o)
        }
        val end = byOrd.keys.maxOrNull()
            ?: return SleepDebtLedger(balanceMin = 0.0, nights = emptyList(), needMin = needMin)
        val nights = ArrayList<SleepDebtNight>()
        var debt = 0.0
        for (o in (end - cap + 1)..end) {
            val night = byOrd[o]
            debt = SleepNeed.step(debt, needMin, night?.second)
            if (night != null) {
                nights.add(SleepDebtNight(day = night.first, sleptMin = night.second, deltaMin = night.second - needMin))
            }
        }
        return SleepDebtLedger(balanceMin = round1(-debt), nights = nights, needMin = needMin)
    }

    /**
     * Round to 1 decimal place — keeps Σ stable without trailing float noise.
     *
     * Matches Swift `SleepDebt.round1` byte-for-byte: Swift's `Double.rounded()` is
     * `.toNearestOrAwayFromZero` (half-AWAY-from-zero), so a negative half-tie like a
     * −0.05 balance rounds to −0.1, not 0.0. Kotlin's `Double.roundToInt()` rounds half
     * toward +∞ (`floor(x + 0.5)`), which would round that same tie to 0.0 — a real
     * cross-platform divergence on negative half-ties (audit #6). Round each sign away
     * from zero so the two clients report the same balance for the same nights.
     */
    internal fun round1(v: Double): Double {
        val scaled = v * 10.0
        val rounded = if (scaled < 0.0) ceil(scaled - 0.5) else floor(scaled + 0.5)
        return rounded / 10.0
    }
}
