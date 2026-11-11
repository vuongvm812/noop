package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Swift twin: `WorkoutMetricStatsTests`, same inputs and literals. */
class WorkoutMetricStatsTest {
    @Test
    fun averagePositiveSkipsZerosAndIsNullWhenNone() {
        assertEquals(200.0, WorkoutMetricStats.averagePositive(listOf(0.0, 100.0, 200.0, 0.0, 300.0))!!, 0.0)
        assertNull(WorkoutMetricStats.averagePositive(listOf(0.0, 0.0)))
        assertNull(WorkoutMetricStats.averagePositive(emptyList()))
    }

    @Test
    fun elevationGainSumsOnlyClimbs() {
        assertEquals(13.0, WorkoutMetricStats.elevationGain(listOf(10.0, 15.0, 12.0, 20.0, 20.0, 18.0)), 0.0)
        assertEquals(0.0, WorkoutMetricStats.elevationGain(listOf(5.0)), 0.0)
        assertEquals(0.0, WorkoutMetricStats.elevationGain(emptyList()), 0.0)
    }
}
