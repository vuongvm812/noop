package com.noop.analytics

import com.noop.data.DeviceMetricKind
import com.noop.data.DeviceMetricRow

/**
 * What the watch itself reported about one workout, read back from `deviceMetricSample` over the workout
 * window: its Training Effect and training load (from the activity file's session summary) and the
 * intensity minutes its monitoring credited during the window.
 *
 * Reference values only. NOOP shows them beside its own numbers and never scores from them: there is no
 * EPOC model here, and the watch's figures are not checked against anything.
 *
 * Swift twin: `WorkoutWatchReference`.
 */
data class WorkoutWatchReference(
    /** Aerobic Training Effect, 0.0-5.0. */
    val aerobicTrainingEffect: Double?,
    /** Anaerobic Training Effect, 0.0-5.0. */
    val anaerobicTrainingEffect: Double?,
    /** The watch's EPOC-based training load for the activity. */
    val trainingLoad: Double?,
    /** Moderate-intensity minutes credited inside the window. */
    val moderateMinutes: Double?,
    /** Vigorous-intensity minutes credited inside the window. */
    val vigorousMinutes: Double?,
) {
    /** Intensity minutes as the watch counts them toward its weekly goal: a vigorous minute counts twice.
     *  null when the window credited neither kind. */
    val intensityMinutes: Double?
        get() = if (moderateMinutes == null && vigorousMinutes == null) null
        else (moderateMinutes ?: 0.0) + 2 * (vigorousMinutes ?: 0.0)

    companion object {
        /** The stored kinds this summary reads. Swift twin: `WorkoutWatchReference.kinds`. */
        val kinds: List<DeviceMetricKind> = listOf(
            DeviceMetricKind.AEROBIC_TRAINING_EFFECT, DeviceMetricKind.ANAEROBIC_TRAINING_EFFECT,
            DeviceMetricKind.TRAINING_LOAD, DeviceMetricKind.INTENSITY_MODERATE_MIN,
            DeviceMetricKind.INTENSITY_VIGOROUS_MIN,
        )

        /**
         * Fold the samples already limited to one workout window. A per-workout value (Training Effect,
         * training load) takes the latest sample of its kind, so a re-imported file cannot double it;
         * intensity minutes are per-interval credits and add up. null when the window holds none of the kinds.
         *
         * Swift twin: `WorkoutWatchReference.summarize`.
         */
        fun summarize(samples: List<DeviceMetricRow>): WorkoutWatchReference? {
            val latest = HashMap<DeviceMetricKind, Pair<Long, Double>>()
            val sums = HashMap<DeviceMetricKind, Double>()
            for (s in samples) {
                if (!s.value.isFinite()) continue
                when (s.kind) {
                    DeviceMetricKind.INTENSITY_MODERATE_MIN, DeviceMetricKind.INTENSITY_VIGOROUS_MIN ->
                        sums[s.kind] = (sums[s.kind] ?: 0.0) + s.value
                    DeviceMetricKind.AEROBIC_TRAINING_EFFECT, DeviceMetricKind.ANAEROBIC_TRAINING_EFFECT,
                    DeviceMetricKind.TRAINING_LOAD -> {
                        val prev = latest[s.kind]
                        if (prev == null || prev.first <= s.ts) latest[s.kind] = s.ts to s.value
                    }
                    else -> Unit
                }
            }
            if (latest.isEmpty() && sums.isEmpty()) return null
            return WorkoutWatchReference(
                aerobicTrainingEffect = latest[DeviceMetricKind.AEROBIC_TRAINING_EFFECT]?.second,
                anaerobicTrainingEffect = latest[DeviceMetricKind.ANAEROBIC_TRAINING_EFFECT]?.second,
                trainingLoad = latest[DeviceMetricKind.TRAINING_LOAD]?.second,
                moderateMinutes = sums[DeviceMetricKind.INTENSITY_MODERATE_MIN],
                vigorousMinutes = sums[DeviceMetricKind.INTENSITY_VIGOROUS_MIN],
            )
        }
    }
}
