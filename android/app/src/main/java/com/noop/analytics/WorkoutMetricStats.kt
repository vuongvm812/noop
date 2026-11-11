package com.noop.analytics

/**
 * Summary figures for the workout detail's power, cadence and elevation graphs, computed over the plotted
 * (bucketed) series. Pure arithmetic, no I/O. Swift twin: `WorkoutMetricStats`.
 */
object WorkoutMetricStats {
    /** Mean of the values above zero (a 0 W / 0 rpm bucket is the sensor idling, not a reading); null when none.
     *  Swift twin: `WorkoutMetricStats.averagePositive`. */
    fun averagePositive(values: List<Double>): Double? {
        var sum = 0.0
        var n = 0
        for (v in values) if (v.isFinite() && v > 0) { sum += v; n++ }
        return if (n == 0) null else sum / n
    }

    /** Total climb: the sum of the positive deltas between consecutive values.
     *  Swift twin: `WorkoutMetricStats.elevationGain`. */
    fun elevationGain(values: List<Double>): Double {
        var gain = 0.0
        for (i in 1 until values.size) {
            val d = values[i] - values[i - 1]
            if (d > 0) gain += d
        }
        return gain
    }
}
