package com.noop.analytics

import kotlin.math.exp
import kotlin.math.ln
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #53: the Charge composite scores HRV in the log domain when its baseline is (the trailing lnRMSSD
 * baseline), clamps every term's z to ±[RecoveryScorer.termZClamp] before weighting, and the trace names the
 * terms the clamp bit on. Also the per-day calibration countdown read off the resolved HRV baseline.
 */
class RecoveryScorerClampTest {

    private fun state(mean: Double, sigma: Double, logDomain: Boolean = false, n: Int = 20) = BaselineState(
        baseline = mean, spread = sigma / 1.253, nValid = n, nightsSinceUpdate = 0,
        status = Baselines.computeStatus(n, 0), logDomain = logDomain,
    )

    @Test fun hrvTermIsScoredInLnForALogDomainBaseline() {
        val hrvB = state(50.0, 0.2, logDomain = true)
        // A night e^σ above the geometric mean is z = +1 in ln, whatever the linear distance.
        val hrv = 50.0 * exp(0.2)
        assertEquals(1.0, RecoveryScorer.higherBetterZ(hrv, hrvB), 1e-9)
        val score = RecoveryScorer.recovery(hrv, 60.0, null, hrvB, null, null, null)!!
        assertEquals(RecoveryScorer.logisticScore(1.0), score, 1e-9)
        // Symmetric in ln: e^-σ below reads −1.
        assertEquals(-1.0, RecoveryScorer.higherBetterZ(50.0 * exp(-0.2), hrvB), 1e-9)
        // A linear baseline keeps the linear z.
        val lin = state(50.0, 5.0)
        assertEquals(2.0, RecoveryScorer.higherBetterZ(60.0, lin), 1e-9)
        assertEquals(RecoveryScorer.higherBetterZ(60.0, lin), RecoveryScorer.zScore(60.0, 50.0, lin.spread), 0.0)
    }

    @Test fun everyTermIsClampedToThreeBeforeWeighting() {
        val hrvB = state(50.0, 0.1, logDomain = true)
        val rhrB = state(60.0, 2.0)
        // HRV z = ln(5/50)/0.1 ≈ −23: clamped to −3, so it equals any other night at or below −3σ.
        val artefact = RecoveryScorer.recovery(5.0, 60.0, null, hrvB, rhrB, null, 0.0)!!
        val atLimit = RecoveryScorer.recovery(50.0 * exp(-0.3), 60.0, null, hrvB, rhrB, null, 0.0)!!
        assertEquals(atLimit, artefact, 1e-9)
        // Sleep perf 0 is a z of about −7 unclamped; clamped it contributes exactly −3.
        val zSleep = (0.0 - RecoveryScorer.sleepPerfCenter) / RecoveryScorer.sleepPerfScale
        assertTrue("fixture must exceed the clamp", zSleep < -RecoveryScorer.termZClamp)
        val expected = (-3.0 * RecoveryScorer.wHRV + 0.0 * RecoveryScorer.wRHR - 3.0 * RecoveryScorer.wSleep) /
            (RecoveryScorer.wHRV + RecoveryScorer.wRHR + RecoveryScorer.wSleep)
        assertEquals(RecoveryScorer.logisticScore(expected), artefact, 1e-9)
        assertEquals(3.0, RecoveryScorer.clampZ(7.5), 0.0)
        assertEquals(-3.0, RecoveryScorer.clampZ(-7.5), 0.0)
        assertEquals(1.25, RecoveryScorer.clampZ(1.25), 0.0)
    }

    @Test fun traceNamesTheClampedTermsAndTheLnDomain() {
        val hrvB = state(50.0, 0.1, logDomain = true)
        val rhrB = state(60.0, 2.0)
        val (score, lines) = RecoveryScorerTrace.recoveryTrace(5.0, 60.0, null, hrvB, rhrB, null, 0.0)
        assertEquals(RecoveryScorer.recovery(5.0, 60.0, null, hrvB, rhrB, null, 0.0), score)
        assertTrue(lines.any { it.startsWith("charge baseline hrv ") && it.endsWith(" domain=ln") })
        val clamp = lines.single { it.startsWith("charge clamp ") }
        assertEquals("charge clamp terms=[hrv,sleepPerf] limit=3.0", clamp)
        // The clamp line sits right before the trend line (#53) and then the renorm line, whose composite
        // is the clamped one.
        val i = lines.indexOf(clamp)
        assertTrue(lines[i + 1].startsWith("charge trend "))
        assertTrue(lines[i + 2].startsWith("charge renorm "))
        // No clamp line when nothing exceeds the limit; no domain tag on a linear baseline.
        val (_, calm) = RecoveryScorerTrace.recoveryTrace(52.0, 60.0, null, state(50.0, 5.0), rhrB, null, 0.8)
        assertFalse(calm.any { it.startsWith("charge clamp ") })
        assertFalse(calm.any { it.contains("domain=ln") })
    }

    @Test fun calibrationNightsReadsTheResolvedHrvBaseline() {
        assertEquals(0, RecoveryScorer.calibrationNights(null, hasRecovery = false))
        assertEquals(3, RecoveryScorer.calibrationNights(state(50.0, 0.1, true, n = 3), hasRecovery = false))
        assertNull(RecoveryScorer.calibrationNights(state(50.0, 0.1, true, n = 4), hasRecovery = false))
        assertNull(RecoveryScorer.calibrationNights(state(50.0, 0.1, true, n = 1), hasRecovery = true))
        // ln helper sanity: the floor at 1 keeps a sub-1 value finite.
        assertTrue(RecoveryScorer.higherBetterZ(0.0, state(50.0, 0.1, true)).isFinite())
        assertEquals(-ln(50.0) / 0.1, RecoveryScorer.higherBetterZ(0.5, state(50.0, 0.1, true)), 1e-9)
    }
}
