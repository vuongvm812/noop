package com.noop.analytics

import com.noop.data.HrSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #50: a Garmin watch workout gets an Effort from the watch's own trace, and the HRmax a rescore uses is
 * the unrounded Tanaka value on both platforms. The expected strains are the Swift twin
 * `GarminWorkoutEffortTests` output pasted verbatim, so the two platforms must agree to the same literals.
 */
class GarminWorkoutEffortTest {

    /** A 2.5 h run at 1 Hz (9000 samples, past the old 8000-sample cap): a 10 min ramp from 100 to 150,
     *  then 4 min blocks alternating 145 and 172 bpm. Same fixture as Swift. */
    private fun run(): List<HrSample> = (0 until 9000).map { i ->
        val bpm = if (i < 600) 100 + i / 12 else if ((i - 600) / 240 % 2 == 0) 145 else 172
        HrSample(deviceId = "garmin-test", ts = 1_700_000_000L + i, bpm = bpm)
    }

    private val profile = UserProfile(weightKg = 72.0, heightCm = 178.0, age = 33.0, sex = "female")

    @Test fun watchSourceIsTheRegistryGarminIdOnly() {
        assertTrue(GarminWorkoutEffort.isWatchSource("garmin-5C4C0BF8-2DF6-1B3A-18D0-3DF0B3590148"))
        assertTrue(GarminWorkoutEffort.isWatchSource("Garmin-abc"))
        assertFalse(GarminWorkoutEffort.isWatchSource("garmin-import"))
        assertFalse(GarminWorkoutEffort.isWatchSource("garmin"))
        assertFalse(GarminWorkoutEffort.isWatchSource("my-whoop"))
        assertFalse(GarminWorkoutEffort.isWatchSource("activity-file"))
        assertFalse(GarminWorkoutEffort.isWatchSource("apple-health"))
    }

    /** Same literals as the Swift `testFullHistoryReadAndAttemptKey`. */
    @Test fun fullHistoryReadAndAttemptKey() {
        assertEquals(2_147_483_647, GarminWorkoutEffort.ALL_ROWS)
        assertEquals("garmin-abc|1760000000|1760003600|running",
            GarminWorkoutEffort.attemptKey("garmin-abc", 1_760_000_000L, 1_760_003_600L, "running"))
    }

    @Test fun hrMaxIsUnroundedTanakaOrTheOverride() {
        assertEquals(184.9, ManualWorkoutRescore.hrMax(null, 33.0), 1e-12)
        assertEquals(208.0, ManualWorkoutRescore.hrMax(null, 0.0), 1e-12)
        assertEquals(191.0, ManualWorkoutRescore.hrMax(191.0, 33.0), 0.0)
    }

    @Test fun aScoredRowIsNeverRescored() {
        assertNull(GarminWorkoutEffort.strainToFill(40.0, run(), profile, 184.9, 48.0))
    }

    @Test fun aTooThinTraceStaysUnscored() {
        assertNull(GarminWorkoutEffort.strainToFill(
            null, listOf(HrSample(deviceId = "garmin-test", ts = 0L, bpm = 150)), profile, 184.9, 48.0))
    }

    @Test fun measuredRestingScoresAboveTheDefault() {
        val def = GarminWorkoutEffort.strainToFill(null, run(), profile, 184.9, null)!!
        val measured = GarminWorkoutEffort.strainToFill(null, run(), profile, 184.9, 48.0)!!
        assertTrue(measured > def)
    }

    /** Swift oracle output, verbatim. */
    @Test fun oracleStrainsMatchSwift() {
        val e = StrainScorer.Method.EDWARDS
        val b = StrainScorer.Method.BANISTER
        val cases = listOf(
            Triple(184.9, null, e) to 68.16, Triple(184.9, null, b) to 67.21,
            Triple(184.9, 48.0, e) to 71.42, Triple(184.9, 48.0, b) to 67.81,
            Triple(185.0, null, b) to 67.18, Triple(185.0, 48.0, b) to 67.79,
            Triple(191.0, null, e) to 68.11, Triple(191.0, null, b) to 65.89,
            Triple(191.0, 48.0, e) to 68.18, Triple(191.0, 48.0, b) to 66.59,
        )
        for ((c, expected) in cases) {
            val (hrMax, resting, method) = c
            val got = GarminWorkoutEffort.strainToFill(null, run(), profile, hrMax, resting, method)
            assertEquals("hrMax=$hrMax resting=$resting $method", expected, got ?: -1.0, 1e-9)
        }
    }
}
