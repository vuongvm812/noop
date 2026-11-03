package com.noop.ui

import com.noop.analytics.Baselines
import com.noop.analytics.ChargeBaselines
import com.noop.analytics.ReadinessEngine
import com.noop.data.DailyMetric
import com.noop.data.ScoreInputProvenanceRow
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #53: the dashboard rebuilds the engine's device eras from the recorded provenance
 * ([chargeBaselineInputs]), and every Charge readout below the headline reads the baseline the displayed
 * day was scored against: the "What shaped it" rows, the calibration countdown and the readiness word.
 * The fixture is a WHOOP -> Garmin switch: thirty WHOOP nights, then Garmin nights at about half the HRV.
 */
class ChargeBaselineEraWiringTest {

    private val today = LocalDate.parse("2026-09-30")
    private fun key(back: Int) = today.minusDays(back.toLong()).toString()

    /** Own computed rows: WHOOP nights 40..3 days back at ~80 ms, Garmin nights 2..0 back at ~40 ms. */
    private val own = (0..40).map { back ->
        val garmin = back <= 2
        DailyMetric(
            deviceId = "my-whoop-noop", day = key(back),
            avgHrv = if (garmin) 40.0 + back else 80.0 + back % 5,
            restingHr = if (garmin) 52 else 56 + back % 3,
            respRateBpm = 15.0, efficiency = 0.9,
            recovery = if (back == 0) null else 60.0,
        )
    }

    /** What the engine recorded for each night (#53 key on Garmin nights, a pre-#53 score key on WHOOP ones). */
    private val provenance = own.map { row ->
        val back = (today.toEpochDay() - LocalDate.parse(row.day).toEpochDay()).toInt()
        if (back <= 2) ScoreInputProvenanceRow("my-whoop-noop", row.day, ChargeBaselines.baselineSourceKey, "garmin-AAA")
        else ScoreInputProvenanceRow("my-whoop-noop", row.day, "recovery", "my-whoop")
    }

    private val inputs = chargeBaselineInputs(
        imported = emptyList(), own = own, seed = emptyList(), provenance = provenance,
        importedSourceId = "my-whoop", hrvRecalibration = 0.0, recoveryRecalibration = 0.0,
    )

    @Test fun recordedSourcesRebuildTheGarminEra() {
        assertEquals("garmin-AAA", inputs.sourceDays.single { it.day == key(0) }.sourceId)
        assertEquals("my-whoop", inputs.sourceDays.single { it.day == key(5) }.sourceId)
        val cut = LocalDate.parse(key(2)).toEpochDay().toDouble() * 86_400
        assertEquals(ChargeBaselines.Epochs(cut, cut, cut, 0.0), inputs.epochs(key(0)))
        // The last WHOOP day is still in the WHOOP era.
        assertEquals(ChargeBaselines.Epochs.none, inputs.epochs(key(3)))
        // A night with no recorded source falls back to the engine's importedDeviceId.
        val bare = chargeBaselineInputs(emptyList(), own, emptyList(), emptyList(), "my-whoop", 0.0, 0.0)
        assertTrue(bare.sourceDays.all { it.sourceId == "my-whoop" })
        assertEquals(ChargeBaselines.Epochs.none, bare.epochs(key(0)))
    }

    /** The first Garmin night has nothing before it in its era: no usable HRV baseline, so no driver rows and
     *  the countdown reads 0 rather than a WHOOP-era count. */
    @Test fun firstGarminNightHasNoUsableBaselineAndCountsDownFromZero() {
        val first = own.single { it.day == key(2) }.copy(recovery = null)
        val r = inputs.resolve(first.day)!!
        assertEquals(0, r.hrv.nValid)
        assertTrue(recoveryChargeDrivers(r, first).isEmpty())
        assertEquals(0, recoveryCalibrationNights(r.hrv, hasRecovery = false))
        // Today (two Garmin nights before it) counts two, the era's own nights.
        assertEquals(2, recoveryCalibrationNights(inputs.resolve(key(0))!!.hrv, hasRecovery = false))
        // Without the era cut the WHOOP nights would have made it a trusted baseline.
        val bare = chargeBaselineInputs(emptyList(), own, emptyList(), emptyList(), "my-whoop", 0.0, 0.0)
        assertNull(recoveryCalibrationNights(bare.resolve(key(0))!!.hrv, hasRecovery = false))
    }

    /** A WHOOP-era day's breakdown still reads its full WHOOP baseline, excluding itself. */
    @Test fun aWhoopDayKeepsItsOwnBaseline() {
        val day = own.single { it.day == key(10) }
        val r = inputs.resolve(day.day)!!
        assertEquals(30, r.hrv.nValid) // 40..11 back
        assertTrue(r.hrv.logDomain)
        assertTrue(recoveryChargeDrivers(r, day).isNotEmpty())
    }

    /** The readiness word never compares a Garmin night with the WHOOP-era HRV baseline. */
    @Test fun readinessIgnoresWhoopEraHrv() {
        val days = own.sortedBy { it.day }
        val withCut = ReadinessEngine.evaluate(days, key(0), inputs.epochsFor(key(0)))
        val noCut = ReadinessEngine.evaluate(days, key(0))
        // Two Garmin nights is too few for an HRV signal; without the cut a WHOOP baseline flags 40 ms low.
        assertTrue(withCut.signals.none { it.key == "hrv" })
        assertTrue(noCut.signals.any { it.key == "hrv" })
        assertNotEquals(noCut, withCut)
        assertEquals(ChargeBaselines.Epochs.none, (null as ChargeBaselines.Inputs?).epochsFor(key(0)))
        assertEquals(Baselines.minNightsSeed, 4)
    }
}
