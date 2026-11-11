package com.noop.analytics

// MetricSnapshot.kt — every metric the app stores, for one day or a date range, grouped by category.
//
// Pure and database-free. A data layer ([com.noop.ui.MetricSnapshotLoader]) reads the stores and
// hands the per-day values here keyed by catalog id; this file owns the catalog (ids, labels, units,
// roll-ups), the aggregation over a range and the number formatting, so the Daily summary page, the
// weekly digest sections and the AI Coach context all print the same figure the same way.
// Swift twin: `MetricSnapshot` (StrandAnalytics/MetricSnapshot.swift). Keep the two byte-identical.
//
// Entry points:
//   • [MetricSnapshotCatalog.allItems()] / [MetricSnapshotCatalog.items]
//   • [MetricSnapshotBuilder.build] — values → [MetricSnapshot] (one day: from == to)
//   • [MetricSnapshotCategory.id] — stable ids, safe to use as per-category setting keys

/**
 * The groups of a snapshot, in display order. [id] is stable and safe to persist as a setting key
 * (the Coach's per-category sharing switches); never renumber or rename an id.
 */
enum class MetricSnapshotCategory(private val categoryId: String, private val categoryLabel: String) {
    RECOVERY_STRAIN("recovery_strain", "Recovery & strain"),
    SLEEP("sleep", "Sleep"),
    HEART("heart", "Heart"),
    ACTIVITY("activity", "Activity"),
    WORKOUTS("workouts", "Workouts"),
    BODY("body_composition", "Body composition"),
    FITNESS("fitness_scores", "Fitness scores"),
    WATCH("watch", "Watch metrics"),
    NUTRITION("nutrition", "Nutrition"),
    HYDRATION("hydration", "Hydration"),
    MIND("mood_journal", "Mood & journal"),
    CYCLE("cycle", "Cycle"),
    LABS("lab_markers", "Lab markers");

    /** The stable id. Swift twin: `MetricSnapshotCategory.id`. */
    val id: String get() = categoryId

    /** English label. Swift twin: `MetricSnapshotCategory.label`. */
    val label: String get() = categoryLabel

}

/** How a metric's daily values roll up over a range. Swift twin: `SnapshotRollup`. */
enum class SnapshotRollup { MEAN, SUM, LATEST, MAX, MIN }

/** Which way a change reads, for the weekly cards. Swift twin: `SnapshotDirection`. */
enum class SnapshotDirection { HIGHER_BETTER, LOWER_BETTER, NEUTRAL }

/**
 * One metric the snapshot reports. [seriesKey] + [sources] describe how the data layer reads a stored
 * series (sources are tried in order, the first with a value for a day wins); a null [seriesKey] means the
 * data layer derives the value itself (workouts, hydration, cycle, watch samples) and supplies it by [id].
 * Swift twin: `MetricSnapshotItem`.
 */
data class MetricSnapshotItem(
    val id: String,
    val category: MetricSnapshotCategory,
    /** English label; the UI localises its own. */
    val label: String,
    /** Display unit ("" for a unitless score). "kg" and "°C" are converted by the UI layer. */
    val unit: String,
    val decimals: Int,
    val rollup: SnapshotRollup = SnapshotRollup.MEAN,
    val direction: SnapshotDirection = SnapshotDirection.NEUTRAL,
    val seriesKey: String? = null,
    val sources: List<String> = emptyList(),
)

/** A free-text line (a lab marker, a journal answer, the cycle phase). Swift twin: `MetricSnapshotNote`. */
data class MetricSnapshotNote(
    val category: MetricSnapshotCategory,
    val id: String,
    val label: String,
    val text: String,
)

/** One resolved line: [value] is null when the range carried no reading. Swift twin: `MetricSnapshotRow`. */
data class MetricSnapshotRow(
    val item: MetricSnapshotItem,
    val value: Double?,
    /** Days of the range that carried a reading. */
    val days: Int,
) {
    /** The line's figure, or "-" when missing. */
    val text: String get() = MetricSnapshotFormat.text(item, value)
}

/** One category's rows plus its free-text notes. Swift twin: `MetricSnapshotSection`. */
data class MetricSnapshotSection(
    val category: MetricSnapshotCategory,
    val rows: List<MetricSnapshotRow>,
    val notes: List<MetricSnapshotNote>,
) {
    /** True when at least one row carries a value or a note exists. */
    val hasData: Boolean get() = notes.isNotEmpty() || rows.any { it.value != null }
}

/** The whole snapshot, every category present in display order. Swift twin: `MetricSnapshot`. */
data class MetricSnapshot(
    val from: String,
    val to: String,
    val sections: List<MetricSnapshotSection>,
) {
    /** The row with catalog id [id]. Swift twin: `MetricSnapshot.row`. */
    fun row(id: String): MetricSnapshotRow? = sections.firstNotNullOfOrNull { s -> s.rows.firstOrNull { it.item.id == id } }

    /** True when no category carries any value. */
    val isEmpty: Boolean get() = sections.none { it.hasData }
}

object MetricSnapshotCatalog {
    private const val W = "my-whoop"
    private const val A = "apple-health"

    /** Catalog entry builder. Swift twin: `MetricSnapshotCatalog.m`. */
    private fun m(
        id: String, cat: MetricSnapshotCategory, label: String, unit: String, decimals: Int,
        rollup: SnapshotRollup = SnapshotRollup.MEAN,
        direction: SnapshotDirection = SnapshotDirection.NEUTRAL,
        key: String? = null, sources: List<String> = emptyList(),
    ) = MetricSnapshotItem(id, cat, label, unit, decimals, rollup, direction, key, sources)

    private val C = MetricSnapshotCategory.entries

    /** Every item in category order, then display order. Ids equal the stored series key where one exists. Swift twin: `MetricSnapshotCatalog.allItems`. */
    fun allItems(): List<MetricSnapshotItem> = listOf(
        // Recovery & strain
        m("recovery", C[0], "Charge", "%", 0, direction = SnapshotDirection.HIGHER_BETTER, key = "recovery", sources = listOf(W)),
        m("strain", C[0], "Effort", "/100", 1, key = "strain", sources = listOf(W)),
        m("hrv", C[0], "HRV", "ms", 0, direction = SnapshotDirection.HIGHER_BETTER, key = "hrv", sources = listOf(W)),
        m("rhr", C[0], "Resting heart rate", "bpm", 0, direction = SnapshotDirection.LOWER_BETTER, key = "rhr", sources = listOf(W)),
        m("stress", C[0], "Day stress", "/3", 1, direction = SnapshotDirection.LOWER_BETTER, key = "stress", sources = listOf(W)),
        // Sleep
        m("sleep_performance", C[1], "Rest", "%", 0, direction = SnapshotDirection.HIGHER_BETTER, key = "sleep_performance", sources = listOf(W)),
        m("sleep_total_min", C[1], "Asleep time", "min", 0, direction = SnapshotDirection.HIGHER_BETTER, key = "sleep_total_min", sources = listOf(W)),
        m("in_bed_min", C[1], "Time in bed", "min", 0, key = "in_bed_min", sources = listOf(W)),
        m("sleep_efficiency", C[1], "Sleep efficiency", "%", 0, direction = SnapshotDirection.HIGHER_BETTER, key = "sleep_efficiency", sources = listOf(W)),
        m("sleep_deep_min", C[1], "Deep sleep", "min", 0, direction = SnapshotDirection.HIGHER_BETTER, key = "sleep_deep_min", sources = listOf(W)),
        m("sleep_rem_min", C[1], "REM sleep", "min", 0, direction = SnapshotDirection.HIGHER_BETTER, key = "sleep_rem_min", sources = listOf(W)),
        m("sleep_light_min", C[1], "Light sleep", "min", 0, key = "sleep_light_min", sources = listOf(W)),
        m("restorative_min", C[1], "Restorative sleep", "min", 0, direction = SnapshotDirection.HIGHER_BETTER, key = "restorative_min", sources = listOf(W)),
        m("sleep_need_min", C[1], "Sleep need", "min", 0, key = "sleep_need_min", sources = listOf(W)),
        m("sleep_debt_min", C[1], "Sleep debt", "min", 0, direction = SnapshotDirection.LOWER_BETTER, key = "sleep_debt_min", sources = listOf(W)),
        m("sleep_consistency", C[1], "Sleep consistency", "%", 0, direction = SnapshotDirection.HIGHER_BETTER, key = "sleep_consistency", sources = listOf(W)),
        m("hours_vs_needed_pct", C[1], "Hours vs needed", "%", 0, direction = SnapshotDirection.HIGHER_BETTER, key = "hours_vs_needed_pct", sources = listOf(W)),
        m("disturbances", C[1], "Disturbances", "", 0, direction = SnapshotDirection.LOWER_BETTER),
        // Heart
        m("avg_hr", C[2], "Average heart rate", "bpm", 0, key = "avg_hr", sources = listOf(W)),
        m("max_hr", C[2], "Max heart rate", "bpm", 0, rollup = SnapshotRollup.MAX, key = "max_hr", sources = listOf(W)),
        m("resp_rate", C[2], "Respiratory rate", "rpm", 1, key = "resp_rate", sources = listOf(W)),
        m("spo2", C[2], "Blood oxygen", "%", 0, direction = SnapshotDirection.HIGHER_BETTER, key = "spo2", sources = listOf(W)),
        m("skin_temp", C[2], "Skin temperature", "°C", 1, key = "skin_temp", sources = listOf(W)),
        m("sdnn", C[2], "HRV (SDNN)", "ms", 0, direction = SnapshotDirection.HIGHER_BETTER),
        // Activity
        m("steps", C[3], "Steps", "steps", 0, rollup = SnapshotRollup.SUM, direction = SnapshotDirection.HIGHER_BETTER, key = "steps", sources = listOf(W, A)),
        m("steps_est", C[3], "Steps (estimated)", "steps", 0, rollup = SnapshotRollup.SUM, key = "steps_est", sources = listOf(W)),
        m("distance_km", C[3], "Distance", "km", 1, rollup = SnapshotRollup.SUM),
        m("energy_kcal", C[3], "Total calories", "kcal", 0, rollup = SnapshotRollup.SUM, key = "energy_kcal", sources = listOf(W)),
        m("active_kcal", C[3], "Active calories", "kcal", 0, rollup = SnapshotRollup.SUM, key = "active_kcal", sources = listOf(W, A)),
        m("basal_kcal", C[3], "Basal calories", "kcal", 0, rollup = SnapshotRollup.SUM, key = "basal_kcal", sources = listOf(A)),
        m("strength_min", C[3], "Strength activity", "min", 0, rollup = SnapshotRollup.SUM, key = "strength_min", sources = listOf(W)),
        m("hr_zones13_min", C[3], "HR zones 1-3", "min", 0, rollup = SnapshotRollup.SUM, key = "hr_zones13_min", sources = listOf(W)),
        m("hr_zones45_min", C[3], "HR zones 4-5", "min", 0, rollup = SnapshotRollup.SUM, key = "hr_zones45_min", sources = listOf(W)),
        m("hr_zones_all_min", C[3], "HR zones (all)", "min", 0, rollup = SnapshotRollup.SUM, key = "hr_zones_all_min", sources = listOf(W)),
        // Workouts (derived from the workout rows and their device samples)
        m("workout_count", C[4], "Workouts", "", 0, rollup = SnapshotRollup.SUM),
        m("workout_duration_min", C[4], "Workout time", "min", 0, rollup = SnapshotRollup.SUM),
        m("workout_energy_kcal", C[4], "Workout calories", "kcal", 0, rollup = SnapshotRollup.SUM),
        m("workout_distance_km", C[4], "Workout distance", "km", 1, rollup = SnapshotRollup.SUM),
        m("workout_avg_hr", C[4], "Workout average heart rate", "bpm", 0),
        m("workout_max_hr", C[4], "Workout max heart rate", "bpm", 0, rollup = SnapshotRollup.MAX),
        m("workout_effort", C[4], "Workout effort", "/100", 1, rollup = SnapshotRollup.MAX),
        m("workout_zone1_min", C[4], "Zone 1", "min", 0, rollup = SnapshotRollup.SUM),
        m("workout_zone2_min", C[4], "Zone 2", "min", 0, rollup = SnapshotRollup.SUM),
        m("workout_zone3_min", C[4], "Zone 3", "min", 0, rollup = SnapshotRollup.SUM),
        m("workout_zone4_min", C[4], "Zone 4", "min", 0, rollup = SnapshotRollup.SUM),
        m("workout_zone5_min", C[4], "Zone 5", "min", 0, rollup = SnapshotRollup.SUM),
        m("workout_avg_power_w", C[4], "Average power", "W", 0),
        m("workout_avg_cadence", C[4], "Average cadence", "rpm", 0),
        m("workout_altitude_gain_m", C[4], "Elevation gain", "m", 0, rollup = SnapshotRollup.SUM),
        m("workout_max_altitude_m", C[4], "Max altitude", "m", 0, rollup = SnapshotRollup.MAX),
        m("workout_aerobic_te", C[4], "Aerobic training effect", "", 1, rollup = SnapshotRollup.MAX),
        m("workout_anaerobic_te", C[4], "Anaerobic training effect", "", 1, rollup = SnapshotRollup.MAX),
        // Body composition
        m("weight", C[5], "Weight", "kg", 1, rollup = SnapshotRollup.LATEST, key = "weight", sources = listOf(A)),
        m("body_fat", C[5], "Body fat", "%", 1, rollup = SnapshotRollup.LATEST, direction = SnapshotDirection.LOWER_BETTER, key = "body_fat", sources = listOf(A)),
        m("lean_mass", C[5], "Lean mass", "kg", 1, rollup = SnapshotRollup.LATEST, direction = SnapshotDirection.HIGHER_BETTER, key = "lean_mass", sources = listOf(A)),
        m("bmi", C[5], "BMI", "", 1, rollup = SnapshotRollup.LATEST, key = "bmi", sources = listOf(A)),
        // Fitness scores
        m("fitness_age", C[6], "Fitness Age", "yrs", 0, rollup = SnapshotRollup.LATEST, direction = SnapshotDirection.LOWER_BETTER, key = "fitness_age", sources = listOf(W)),
        m("vitality", C[6], "Vitality", "", 0, rollup = SnapshotRollup.LATEST, direction = SnapshotDirection.HIGHER_BETTER, key = "vitality", sources = listOf(W)),
        m("body_age", C[6], "Body Age", "yrs", 0, rollup = SnapshotRollup.LATEST, direction = SnapshotDirection.LOWER_BETTER, key = "body_age", sources = listOf(W)),
        m("vo2max", C[6], "VO₂ max", "ml/kg/min", 1, rollup = SnapshotRollup.LATEST, direction = SnapshotDirection.HIGHER_BETTER, key = "vo2max", sources = listOf(A, W)),
        m("vo2max_est", C[6], "VO₂ max (estimated)", "ml/kg/min", 1, rollup = SnapshotRollup.LATEST, direction = SnapshotDirection.HIGHER_BETTER, key = "vo2max_est", sources = listOf(W)),
        // Watch metrics (the paired watch's own series; the data layer reads them under the watch id)
        m("watch_stress", C[7], "Watch stress", "", 0, direction = SnapshotDirection.LOWER_BETTER),
        m("body_battery_min", C[7], "Body Battery low", "", 0, rollup = SnapshotRollup.MIN),
        m("body_battery_max", C[7], "Body Battery high", "", 0, rollup = SnapshotRollup.MAX),
        m("body_battery_recharge", C[7], "Body Battery recharge", "", 0, direction = SnapshotDirection.HIGHER_BETTER),
        m("watch_sleep_score", C[7], "Watch sleep score", "", 0, direction = SnapshotDirection.HIGHER_BETTER),
        m("training_load", C[7], "Training load", "", 0, rollup = SnapshotRollup.SUM),
        m("intensity_min", C[7], "Intensity minutes", "min", 0, rollup = SnapshotRollup.SUM, direction = SnapshotDirection.HIGHER_BETTER),
        m("watch_skin_temp_c", C[7], "Watch skin temperature", "°C", 1),
        // Nutrition
        m("calories_in", C[8], "Calories in", "kcal", 0, rollup = SnapshotRollup.SUM, key = "calories_in", sources = listOf("nutrition-csv")),
        m("protein_g", C[8], "Protein", "g", 0, rollup = SnapshotRollup.SUM, key = "protein_g", sources = listOf("nutrition-csv")),
        m("carbs_g", C[8], "Carbs", "g", 0, rollup = SnapshotRollup.SUM, key = "carbs_g", sources = listOf("nutrition-csv")),
        m("fat_g", C[8], "Fat", "g", 0, rollup = SnapshotRollup.SUM, key = "fat_g", sources = listOf("nutrition-csv")),
        // Hydration
        m("hydration_ml", C[9], "Water", "ml", 0, rollup = SnapshotRollup.SUM),
        // Mood & journal
        m("mood", C[10], "Mood", "/5", 0, direction = SnapshotDirection.HIGHER_BETTER, key = "mood", sources = listOf("noop-mood")),
        // Cycle
        m("cycle_day", C[11], "Cycle day", "", 0, rollup = SnapshotRollup.LATEST),
    )


    /** The category's items in display order (empty for the note-only Labs category). Swift twin: `MetricSnapshotCatalog.items`. */
    fun items(category: MetricSnapshotCategory): List<MetricSnapshotItem> = allItems().filter { it.category == category }
}

object MetricSnapshotFormat {
    /** The figure printed with the same rounding on both platforms; "-" for a missing value. */
    fun text(item: MetricSnapshotItem, value: Double?): String {
        if (value == null || !value.isFinite()) return "-"
        val n = number(value, item.decimals)
        return if (item.unit.isEmpty()) n else "$n ${item.unit}"
    }

    /**
     * [value] with [decimals] places, rounded half away from zero on the scaled magnitude with plain
     * IEEE arithmetic (never `String.format`, whose tie rule differs from Swift's printf); a negative zero
     * is printed unsigned. Swift twin: `MetricSnapshotFormat.number`.
     */
    fun number(value: Double, decimals: Int): String {
        val d = decimals.coerceAtLeast(0)
        var p = 1L
        repeat(d) { p *= 10L }
        val n = Math.floor(Math.abs(value) * p + 0.5).toLong()
        val sign = if (value < 0 && n != 0L) "-" else ""
        if (d == 0) return "$sign$n"
        return "$sign${n / p}.${(n % p).toString().padStart(d, '0')}"
    }
}

object MetricSnapshotBuilder {
    /**
     * Roll [values] (item id → day → value) up over the inclusive range [from]..[to] into a snapshot.
     * Days outside the range and non-finite values are ignored; a one-day range is the day's snapshot.
     * [notes] are grouped under their category. Swift twin: `MetricSnapshotBuilder.build`.
     */
    fun build(
        from: String,
        to: String,
        values: Map<String, Map<String, Double>>,
        notes: List<MetricSnapshotNote> = emptyList(),
    ): MetricSnapshot {
        val sections = MetricSnapshotCategory.entries.map { cat ->
            val rows = MetricSnapshotCatalog.items(cat).map { item ->
                val inRange = (values[item.id] ?: emptyMap())
                    .filter { (day, v) -> day >= from && day <= to && v.isFinite() }
                    .toList().sortedBy { it.first }
                MetricSnapshotRow(item, roll(item.rollup, inRange.map { it.second }), inRange.size)
            }
            MetricSnapshotSection(cat, rows, notes.filter { it.category == cat })
        }
        return MetricSnapshot(from, to, sections)
    }

    /** Roll a day-ordered list of values up per [rollup]; null when empty. Swift twin: `MetricSnapshotBuilder.roll`. */
    fun roll(rollup: SnapshotRollup, ordered: List<Double>): Double? {
        if (ordered.isEmpty()) return null
        return when (rollup) {
            SnapshotRollup.MEAN -> ordered.sum() / ordered.size
            SnapshotRollup.SUM -> ordered.sum()
            SnapshotRollup.LATEST -> ordered.last()
            SnapshotRollup.MAX -> ordered.max()
            SnapshotRollup.MIN -> ordered.min()
        }
    }
}
