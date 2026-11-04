package com.noop.huawei

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HuaweiRadioPolicyTest {

    @Test
    fun theFirstSyncAlwaysRuns() {
        for (t in HuaweiSyncTrigger.entries) assertTrue(HuaweiRadioPolicy.shouldSync(t, 1_000, null))
    }

    @Test
    fun periodicSyncsKeepTheFifteenMinuteFloor() {
        assertFalse(HuaweiRadioPolicy.shouldSync(HuaweiSyncTrigger.PERIODIC, 1_000 + 899, 1_000))
        assertTrue(HuaweiRadioPolicy.shouldSync(HuaweiSyncTrigger.PERIODIC, 1_000 + 900, 1_000))
    }

    @Test
    fun emptySyncsStretchOnlyThePeriodicFloorUpToAnHour() {
        assertEquals(listOf(1.0, 1.0, 1.0, 2.0, 4.0, 4.0), (0..5).map { HuaweiRadioPolicy.syncBackoff(it) })
        assertFalse(HuaweiRadioPolicy.shouldSync(HuaweiSyncTrigger.PERIODIC, 1_000 + 3_599, 1_000, emptyStreak = 9))
        assertTrue(HuaweiRadioPolicy.shouldSync(HuaweiSyncTrigger.PERIODIC, 1_000 + 3_600, 1_000, emptyStreak = 9))
        assertTrue(HuaweiRadioPolicy.shouldSync(HuaweiSyncTrigger.CONNECT, 1_000 + 90, 1_000, emptyStreak = 9))
        assertFalse(HuaweiRadioPolicy.shouldSync(HuaweiSyncTrigger.FOREGROUND, 1_000 + 89, 1_000))
        assertTrue(HuaweiRadioPolicy.shouldSync(HuaweiSyncTrigger.MANUAL, 1_000, 1_000, emptyStreak = 9))
    }
}
