package com.noop.analytics

/**
 * The ONE definition of a day's "Calories" figure on every surface (#51): whole-day TOTAL energy,
 * resting metabolic rate included.
 *
 * Two stores hold a day's energy. The phone's import (Apple Health / Health Connect, `AppleDaily`) splits
 * it into active and basal energy; every other source stores the total in `DailyMetric.activeKcalEst`
 * (NOOP's own estimate, and each brand import's watch total). A phone day counts only when it carries
 * BOTH halves: its active energy alone is a different quantity, and charting it beside a total reads
 * ~500 kcal next to ~2,400. Resolved imported-first, the precedence the Today surfaces already used
 * (#616), so a day the phone covers shows the phone's figure.
 *
 * Swift twin: `DayCalories`.
 */
object DayCalories {
    /** The ring scale of the Today Calories tile: a day's total energy as a fraction of this. */
    const val TILE_SCALE_KCAL: Double = 3_000.0

    /** A phone day's total energy: active + basal when both are positive, else null.
     *  Swift twin: `DayCalories.importedTotal`. */
    fun importedTotal(activeKcal: Double?, basalKcal: Double?): Double? {
        if (activeKcal == null || activeKcal <= 0.0 || basalKcal == null || basalKcal <= 0.0) return null
        return activeKcal + basalKcal
    }

    /** One phone row of a day: its active and basal energy. */
    data class ImportedDay(val day: String, val activeKcal: Double?, val basalKcal: Double?)

    /**
     * Every day either store covers, resolved: the phone's total when it has one, else the stored total.
     * Several phone rows on one day (Apple Health and Health Connect) keep the largest total. Sorted by
     * day. Swift twin: `DayCalories.byDay`.
     */
    fun byDay(imported: List<ImportedDay>, stored: Map<String, Double>): List<Pair<String, Double>> {
        val phone = HashMap<String, Double>()
        for (row in imported) {
            val total = importedTotal(row.activeKcal, row.basalKcal) ?: continue
            phone[row.day] = maxOf(phone[row.day] ?: 0.0, total)
        }
        return (phone.keys + stored.keys).toSortedSet().mapNotNull { day ->
            phone[day]?.let { day to it } ?: stored[day]?.takeIf { it > 0.0 }?.let { day to it }
        }
    }
}
