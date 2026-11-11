package com.noop.analytics

// WeeklyDigestInputs.kt — the pure half of the weekly-digest input resolver (#89).
//
// The data layer reads the stores (daily rows, a paired watch's daily series and intraday
// samples) and hands the results here as plain values; this file turns them into the
// day→value maps WeeklyDigestEngine.build consumes, so the Trends card and the Monday
// notification build the digest from the same function. Swift twin: `WeeklyDigestInputs`.

/**
 * One day's figures from the daily row, already resolved by the data layer (Rest through the
 * one Rest resolver). Mirrors Swift `WeeklyDigestDay`.
 */
data class WeeklyDigestDay(
    val day: String,
    val charge: Double? = null,
    val effort: Double? = null,
    val rest: Double? = null,
    val rhr: Double? = null,
    val hrv: Double? = null,
    val steps: Double? = null,
    val sleepMin: Double? = null,
    /** As stored: a 0–1 fraction or a percent, depending on the source. */
    val efficiency: Double? = null,
    val deepMin: Double? = null,
    val remMin: Double? = null,
    val spo2: Double? = null,
    val respiration: Double? = null,
    /** As stored: a ±°C deviation, or an absolute °C from a CSV import (dropped here). */
    val skinTemp: Double? = null,
)

/** The paired watch's per-day series, keyed "yyyy-MM-dd". Mirrors Swift `WeeklyDigestWatchSeries`. */
data class WeeklyDigestWatchSeries(
    val stress: Map<String, Double> = emptyMap(),
    val sleepScore: Map<String, Double> = emptyMap(),
    val activeKcal: Map<String, Double> = emptyMap(),
    val trainingLoad: Map<String, Double> = emptyMap(),
    val intensityMinutes: Map<String, Double> = emptyMap(),
    val vo2max: Map<String, Double> = emptyMap(),
    val bodyBatteryRecharge: Map<String, Double> = emptyMap(),
) {
    companion object {
        val EMPTY = WeeklyDigestWatchSeries()
    }
}

object WeeklyDigestInputs {

    /** The Body Battery look-around each side of the night (3 h before sleep, 3 h after wake). */
    const val RECHARGE_WINDOW_SECONDS: Long = 3L * 3600L

    /**
     * The engine's series from the daily rows and the watch series. A day listed twice keeps
     * its last row. [snapshotValues] is [MetricSnapshotItem.id] → day → value from the snapshot loader.
     * Swift twin: `WeeklyDigestInputs.series`.
     */
    fun series(days: List<WeeklyDigestDay>, watch: WeeklyDigestWatchSeries): Map<WeeklyMetric, Map<String, Double>> {
        val out = HashMap<WeeklyMetric, HashMap<String, Double>>()
        // Swift twin: `WeeklyDigestInputs.put` (the same local helper).
        fun put(m: WeeklyMetric, day: String, v: Double?) {
            if (v == null || !v.isFinite()) return
            out.getOrPut(m) { HashMap() }[day] = v
        }
        for (d in days) {
            put(WeeklyMetric.CHARGE, d.day, d.charge)
            put(WeeklyMetric.EFFORT, d.day, d.effort)
            put(WeeklyMetric.REST, d.day, d.rest)
            put(WeeklyMetric.RHR, d.day, d.rhr)
            put(WeeklyMetric.HRV, d.day, d.hrv)
            put(WeeklyMetric.STEPS, d.day, d.steps)
            put(WeeklyMetric.SLEEP_DURATION, d.day, d.sleepMin?.let { it / 60.0 })
            put(WeeklyMetric.SLEEP_EFFICIENCY, d.day, d.efficiency?.let { efficiencyPercent(it) })
            put(WeeklyMetric.DEEP_SLEEP, d.day, d.deepMin)
            put(WeeklyMetric.REM_SLEEP, d.day, d.remMin)
            put(WeeklyMetric.SPO2, d.day, d.spo2)
            put(WeeklyMetric.RESPIRATION, d.day, d.respiration)
            put(WeeklyMetric.SKIN_TEMP_DEVIATION, d.day, d.skinTemp?.takeUnless { VitalBands.isAbsoluteSkinTemp(it) })
        }
        val watchMaps = listOf(
            WeeklyMetric.STRESS to watch.stress,
            WeeklyMetric.SLEEP_SCORE to watch.sleepScore,
            WeeklyMetric.ACTIVE_KCAL to watch.activeKcal,
            WeeklyMetric.TRAINING_LOAD to watch.trainingLoad,
            WeeklyMetric.INTENSITY_MINUTES to watch.intensityMinutes,
            WeeklyMetric.VO2MAX to watch.vo2max,
            WeeklyMetric.BODY_BATTERY_RECHARGE to watch.bodyBatteryRecharge,
        )
        for ((m, map) in watchMaps) for ((day, v) in map) put(m, day, v)
        return out
    }

    /**
     * The series of the metrics read from the snapshot stores (body composition, workouts, nutrition, ...):
     * each [WeeklyMetric] naming a [WeeklyMetric.snapshotId] takes that item's per-day values from
     * [snapshotValues] ([MetricSnapshotItem.id] → day → value). Disjoint from [series]'s metrics, so callers
     * merge the two. Swift twin: `WeeklyDigestInputs.snapshotSeries`.
     */
    fun snapshotSeries(snapshotValues: Map<String, Map<String, Double>>): Map<WeeklyMetric, Map<String, Double>> {
        val out = HashMap<WeeklyMetric, Map<String, Double>>()
        for (m in WeeklyMetric.values()) {
            val id = m.snapshotId ?: continue
            val vals = snapshotValues[id]?.filterValues { it.isFinite() } ?: continue
            if (vals.isNotEmpty()) out[m] = vals
        }
        return out
    }

    /** Efficiency as a percent: a 0–1 fraction is scaled. Swift twin: `WeeklyDigestInputs.efficiencyPercent`. */
    fun efficiencyPercent(v: Double): Double = if (v <= 1.0) v * 100.0 else v

    /** Moderate + 2 × vigorous per day. Swift twin: `WeeklyDigestInputs.intensityMinutes`. */
    fun intensityMinutes(moderate: Map<String, Double>, vigorous: Map<String, Double>): Map<String, Double> =
        (moderate.keys + vigorous.keys).associateWith { (moderate[it] ?: 0.0) + 2 * (vigorous[it] ?: 0.0) }

    /**
     * One night's Body Battery recharge: the morning peak in the window after wake minus the
     * lowest reading in the window before sleep starts; null when either side has no reading.
     * Swift twin: `WeeklyDigestInputs.bodyBatteryRecharge`.
     */
    fun bodyBatteryRecharge(samples: List<Pair<Long, Double>>, sleepStart: Long, sleepEnd: Long): Double? {
        val low = samples.filter { it.first in (sleepStart - RECHARGE_WINDOW_SECONDS)..sleepStart }
            .minOfOrNull { it.second } ?: return null
        val peak = samples.filter { it.first in sleepEnd..(sleepEnd + RECHARGE_WINDOW_SECONDS) }
            .maxOfOrNull { it.second } ?: return null
        return peak - low
    }
}
