package com.noop.analytics

import com.noop.data.DeviceMetricKind
import com.noop.data.DeviceMetricRow
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** Swift twin: `WorkoutWatchReferenceTests`; the expected lines are the Swift oracle's output. */
class WorkoutWatchReferenceTest {

    private fun line(r: WorkoutWatchReference?): String {
        if (r == null) return "nil"
        fun f(v: Double?) = v?.let { String.format(Locale.US, "%.4f", it) } ?: "nil"
        return "ae=${f(r.aerobicTrainingEffect)} an=${f(r.anaerobicTrainingEffect)} load=${f(r.trainingLoad)} " +
            "mod=${f(r.moderateMinutes)} vig=${f(r.vigorousMinutes)} im=${f(r.intensityMinutes)}"
    }

    private fun s(ts: Long, kind: DeviceMetricKind, v: Double) = DeviceMetricRow(ts, kind, v, "t")

    @Test
    fun summaryOracle() {
        val cases = listOf(
            emptyList(),
            listOf(s(100, DeviceMetricKind.STRESS, 40.0), s(100, DeviceMetricKind.BODY_BATTERY, 60.0)),
            listOf(
                s(100, DeviceMetricKind.AEROBIC_TRAINING_EFFECT, 3.0), s(200, DeviceMetricKind.AEROBIC_TRAINING_EFFECT, 3.4),
                s(200, DeviceMetricKind.ANAEROBIC_TRAINING_EFFECT, 1.0), s(100, DeviceMetricKind.ANAEROBIC_TRAINING_EFFECT, 2.0),
                s(150, DeviceMetricKind.TRAINING_LOAD, 85.5),
                s(110, DeviceMetricKind.INTENSITY_MODERATE_MIN, 1.0), s(170, DeviceMetricKind.INTENSITY_MODERATE_MIN, 2.0),
                s(180, DeviceMetricKind.INTENSITY_MODERATE_MIN, Double.NaN), s(190, DeviceMetricKind.INTENSITY_VIGOROUS_MIN, 2.0),
            ),
            listOf(s(110, DeviceMetricKind.INTENSITY_VIGOROUS_MIN, 3.0)),
            listOf(s(110, DeviceMetricKind.INTENSITY_MODERATE_MIN, 4.0)),
            listOf(s(100, DeviceMetricKind.AEROBIC_TRAINING_EFFECT, 2.5), s(100, DeviceMetricKind.AEROBIC_TRAINING_EFFECT, 2.7)),
        )
        assertEquals(
            listOf(
                "nil",
                "nil",
                "ae=3.4000 an=1.0000 load=85.5000 mod=3.0000 vig=2.0000 im=7.0000",
                "ae=nil an=nil load=nil mod=nil vig=3.0000 im=6.0000",
                "ae=nil an=nil load=nil mod=4.0000 vig=nil im=4.0000",
                "ae=2.7000 an=nil load=nil mod=nil vig=nil im=nil",
            ),
            cases.map { line(WorkoutWatchReference.summarize(it)) },
        )
    }

    @Test
    fun readsExactlyTheKindsItSummarises() {
        assertEquals(
            listOf(
                "aerobic_training_effect", "anaerobic_training_effect", "training_load",
                "intensity_moderate_min", "intensity_vigorous_min",
            ),
            WorkoutWatchReference.kinds.map { it.wire },
        )
    }
}
