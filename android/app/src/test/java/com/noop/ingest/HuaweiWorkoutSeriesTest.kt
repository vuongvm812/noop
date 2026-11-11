package com.noop.ingest

import com.noop.data.DeviceMetricKind
import com.noop.huawei.decode.HuaweiWorkoutDecoder
import com.noop.huawei.decode.HuaweiWorkoutDecoder.Sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Per-sample workout cadence / altitude / power through the fold: what is kept, what is skipped. */
class HuaweiWorkoutSeriesTest {

    private val start = GarminWatchIngest.dayStartUnix("2026-03-10", 0) + 3_600
    private val end = start + 600

    private fun totals(type: Int) = HuaweiWorkoutDecoder.Totals(
        number = 1, status = 1, startTs = start, endTs = end, kcal = null, distanceM = null, steps = null,
        totalTimeS = 600, durationS = 600, type = type, minHr = null, maxHr = null, load = null,
        aerobicEffect = null, anaerobicEffect = null, elevationGainM = null,
    )

    private fun fold(type: Int, vararg s: Sample, source: String = HuaweiWatchIngest.SOURCE_WORKOUT_SAMPLES) =
        HuaweiWatchIngest.fold(HuaweiWatchIngest.Batch(
            workouts = listOf(HuaweiWatchIngest.Workout(totals(type), s.toList(), sampleSource = source)),
            utcOffsetS = 0, nowTs = end + 3_600,
        ))

    private fun HuaweiWatchIngest.Ingest.rows(kind: DeviceMetricKind) = deviceMetric.filter { it.kind == kind }

    @Test fun runningKeepsInnerCadenceAltitudeAndPowerWithTheirSource() {
        val out = fold(1, Sample(ts = start + 10, heartRate = 140, stepRate = 150, altitudeM = 123.4, powerW = 230, inner = mapOf(0 to 170)))
        val c = out.rows(DeviceMetricKind.CADENCE).single()
        assertEquals(start + 10, c.ts); assertEquals(170.0, c.value, 0.0); assertEquals("huawei-17-0a", c.source)
        assertEquals(123.4, out.rows(DeviceMetricKind.ALTITUDE_M).single().value, 1e-9)
        assertEquals(230.0, out.rows(DeviceMetricKind.POWER_W).single().value, 0.0)
        assertEquals(HuaweiWatchIngest.SampleCounts(1, 1, 1, 1), out.workouts.single().sampleCounts)
    }

    @Test fun walkingPrefersTheStepRateAndEachFallsBackToTheOther() {
        assertEquals(150.0, fold(2, Sample(ts = start + 1, stepRate = 150, inner = mapOf(0 to 170))).rows(DeviceMetricKind.CADENCE).single().value, 0.0)
        assertEquals(150.0, fold(1, Sample(ts = start + 1, stepRate = 150)).rows(DeviceMetricKind.CADENCE).single().value, 0.0)
        assertEquals(170.0, fold(2, Sample(ts = start + 1, inner = mapOf(0 to 170))).rows(DeviceMetricKind.CADENCE).single().value, 0.0)
    }

    @Test fun zeroSentinelAndAbsentValuesAreNotStored() {
        val out = fold(
            1,
            Sample(ts = start + 1, heartRate = 120, stepRate = 0, altitudeM = 0.0, powerW = 0, inner = mapOf(0 to 0)),
            Sample(ts = start + 2, stepRate = 255, altitudeM = -0.1, powerW = 0xFFFF, inner = mapOf(0 to 0xFFFF)),
            Sample(ts = start + 3, heartRate = 125),
        )
        assertTrue(out.deviceMetric.none { it.kind in listOf(DeviceMetricKind.CADENCE, DeviceMetricKind.ALTITUDE_M, DeviceMetricKind.POWER_W) })
        assertEquals(HuaweiWatchIngest.SampleCounts(2, 0, 0, 0), out.workouts.single().sampleCounts)
    }

    @Test fun aNegativeAltitudeIsKeptAndSamplesOutsideTheWorkoutAreDropped() {
        val out = fold(1, Sample(ts = start + 1, altitudeM = -5.5), Sample(ts = start - 1, powerW = 100), Sample(ts = end + 1, powerW = 100))
        assertEquals(-5.5, out.rows(DeviceMetricKind.ALTITUDE_M).single().value, 1e-9)
        assertTrue(out.rows(DeviceMetricKind.POWER_W).isEmpty())
    }

    @Test fun theSourceFollowsTheWorkoutsOrigin() {
        val out = fold(1, Sample(ts = start + 1, powerW = 90), source = HuaweiWatchIngest.SOURCE_WORKOUT_SAMPLES_GADGETBRIDGE)
        assertEquals("gadgetbridge", out.rows(DeviceMetricKind.POWER_W).single().source)
    }
}
