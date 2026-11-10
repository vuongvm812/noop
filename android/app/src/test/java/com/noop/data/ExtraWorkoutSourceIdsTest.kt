package com.noop.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** The Workouts list reads every device id that holds workouts, not only a fixed set of sources. */
class ExtraWorkoutSourceIdsTest {

    private fun extra(present: List<String>, read: List<String>) =
        WhoopRepository.extraWorkoutSourceIds(present, read)

    @Test
    fun gadgetbridgeImportAndInactiveWatchesAreRead() {
        assertEquals(
            listOf("garmin-123", "huawei-import", "oura-ring"),
            extra(
                present = listOf("my-whoop", "oura-ring", "huawei-import", "apple-health", "garmin-123"),
                read = listOf("strap-a", "my-whoop", "apple-health", "health-connect", "lifting", "activity-file"),
            ),
        )
    }

    @Test
    fun computedIdsAreLeftToTheDetectedUnion() {
        assertEquals(emptyList<String>(), extra(listOf("garmin-123-noop", "my-whoop-noop", ""), listOf("my-whoop")))
    }

    @Test
    fun resultIsSortedAndDistinct() {
        assertEquals(listOf("a", "b"), extra(listOf("b", "a", "b"), emptyList()))
    }
}
