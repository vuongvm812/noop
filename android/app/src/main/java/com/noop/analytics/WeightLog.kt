package com.noop.analytics

/**
 * The hand-logged body-weight diary and the one resolver every weight readout uses (#57).
 *
 * The user logs at most one weight per local day from the Today Weight tile. Entries live in the generic
 * `metricSeries` table under their own source ([SOURCE_ID]) and key ([KEY]), so they need no schema change
 * and travel inside the `.noopbak` SQLite like every other series. A health-store weight (Apple Health /
 * Health Connect) can sit beside them; this object decides which one a screen shows, so the Today tile, the
 * weight chart and the profile weight that calorie / BMR math reads cannot name different weights.
 *
 * Pure. Swift twin: `StrandAnalytics.WeightLog`, pinned by the same cases.
 */
object WeightLog {

    /** The `metricSeries` device id the diary is written under. Keep equal to the Swift `sourceId`. */
    const val SOURCE_ID: String = "weight-log"

    /** The `metricSeries` key, kilograms. Keep equal to the Swift `key`. */
    const val KEY: String = "weight_kg"

    /** The accepted range, the same clamp [com.noop.ui.ProfileStore.weightKg] applies, so a logged value
     *  always lands in the profile unchanged. */
    const val MIN_KG: Double = 30.0
    const val MAX_KG: Double = 250.0

    /** The app's pound factor (`UnitFormatter.POUNDS_PER_KILOGRAM`), repeated so this stays UI-free. */
    const val POUNDS_PER_KILOGRAM: Double = 2.20462

    enum class Source(val raw: String) { LOGGED("logged"), HEALTH("health") }

    /** One day's weight. [day] is ISO `yyyy-MM-dd`, which sorts chronologically. */
    data class Entry(val day: String, val kg: Double)

    data class Resolved(val entry: Entry, val source: Source)

    /** True for a finite weight inside [MIN_KG]..[MAX_KG]. Swift twin: `WeightLog.isValid`. */
    fun isValid(kg: Double): Boolean = kg.isFinite() && kg >= MIN_KG && kg <= MAX_KG

    /**
     * Parse what the user typed into kilograms, or null when it is not a valid weight. Accepts a comma or
     * a dot as the decimal separator, converts pounds when [imperial], and rounds to 0.01 kg.
     * Swift twin: `WeightLog.parseKg`.
     */
    fun parseKg(text: String, imperial: Boolean): Double? {
        val cleaned = text.trim().replace(',', '.')
        if (cleaned.isEmpty()) return null
        val value = cleaned.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
        val kg = if (imperial) value / POUNDS_PER_KILOGRAM else value
        // Math.round is half-up, which equals Swift's `.rounded()` for the positive values that pass here.
        val rounded = Math.round(kg * 100).toDouble() / 100
        return if (isValid(rounded)) rounded else null
    }

    /**
     * The most recent valid entry, or null. On a same-day tie the FIRST one wins.
     * Swift twin: `WeightLog.newest`.
     */
    fun newest(entries: List<Entry>): Entry? {
        var best: Entry? = null
        for (e in entries) {
            if (!isValid(e.kg)) continue
            if (best == null || e.day > best.day) best = e
        }
        return best
    }

    /**
     * The weight to show for [day]: the newest reading on or before it. A hand-logged weight wins a
     * same-day tie with the health store. Null when neither has one.
     * Swift twin: `WeightLog.resolve`.
     */
    fun resolve(logged: List<Entry>, health: List<Entry>, onOrBefore: String): Resolved? {
        val l = newest(logged.filter { it.day <= onOrBefore })
        val h = newest(health.filter { it.day <= onOrBefore })
        return when {
            l != null && h != null -> if (h.day > l.day) Resolved(h, Source.HEALTH) else Resolved(l, Source.LOGGED)
            l != null -> Resolved(l, Source.LOGGED)
            h != null -> Resolved(h, Source.HEALTH)
            else -> null
        }
    }

    /**
     * The daily series the weight chart draws: one value per day, oldest first, a logged day replacing the
     * health store's reading for that day. Invalid values are dropped. Within one source the first entry
     * for a day wins, the same tie rule as [newest], so the chart and the tile agree.
     * Swift twin: `WeightLog.dailySeries`.
     */
    fun dailySeries(logged: List<Entry>, health: List<Entry>): List<Entry> {
        val byDay = HashMap<String, Double>()
        val loggedDays = HashSet<String>()
        val healthDays = HashSet<String>()
        for (e in health) if (isValid(e.kg) && healthDays.add(e.day)) byDay[e.day] = e.kg
        for (e in logged) if (isValid(e.kg) && loggedDays.add(e.day)) byDay[e.day] = e.kg
        return byDay.keys.sorted().map { Entry(it, byDay.getValue(it)) }
    }

    /**
     * The profile weight after the diary changed: the newest logged weight, or null when it is empty.
     * Swift twin: `WeightLog.profileWeight`.
     */
    fun profileWeight(logged: List<Entry>): Double? = newest(logged)?.kg
}
