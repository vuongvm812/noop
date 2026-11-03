package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The Charge HRV trend modifier (#53): the 7-day rolling lnRMSSD read against the HRV baseline's
 * smallest-worthwhile-change band (Plews / Buchheit / Altini). Inside the band it changes nothing; outside it
 * shifts the composite by [RecoveryScorer.wHRVTrend] times the excess in σ. Kotlin twin of the Swift
 * ChargeHRVTrendTests, case for case.
 *
 * The oracle (`src/test/resources/charge_hrv_trend_oracle.txt`) is the Swift `ChargeHRVTrendTests.oracleText()`
 * stdout pasted verbatim (`PRINT_ORACLE=1 swift test --filter ChargeHRVTrendTests`), so the 216-case grid is
 * guarded in both directions: the Swift test pins the FNV-1a hash of the same text.
 */
class ChargeHRVTrendTest {

    /** A trusted log-domain HRV baseline: geometric mean [meanMs], σ = [sigmaLn] in ln units. */
    private fun lnBaseline(meanMs: Double, sigmaLn: Double) = BaselineState(
        baseline = meanMs, spread = sigmaLn / 1.253, nValid = 30, nightsSinceUpdate = 1,
        status = BaselineStatus.TRUSTED, logDomain = true,
    )

    private fun score(hrv: Double, prior: List<Double>, base: BaselineState): Double? = RecoveryScorer.recovery(
        hrv = hrv, rhr = 55.0, resp = null, hrvBaseline = base, rhrBaseline = null,
        respBaseline = null, sleepPerf = 0.85, hrvPriorWeek = prior,
    )

    private fun trend(hrv: Double, prior: List<Double>, base: BaselineState) =
        RecoveryScorer.hrvTrend(hrv, prior, RecoveryScorer.DriverBaseline(base))

    @Test fun insideTheBandChangesNothing() {
        val base = lnBaseline(50.0, sigmaLn = 0.2)
        // A week sitting on the baseline: the rolling mean is inside ±0.5σ.
        val prior = listOf(49.0, 51.0, 50.0, 52.0, 48.0, 50.0)
        assertEquals(0.0, trend(50.0, prior, base)!!.z, 0.0)
        assertEquals(score(50.0, emptyList(), base), score(50.0, prior, base))
    }

    @Test fun aSuppressedWeekLowersChargeAndARaisedWeekLiftsIt() {
        val base = lnBaseline(50.0, sigmaLn = 0.2)
        val neutral = score(50.0, emptyList(), base)!!
        val low = score(50.0, List(6) { 35.0 }, base)!!
        val high = score(50.0, List(6) { 70.0 }, base)!!
        assertTrue(low < neutral)
        assertTrue(high > neutral)
        val lowTrend = trend(50.0, List(6) { 35.0 }, base)!!
        assertTrue(lowTrend.z < 0)
        assertTrue(lowTrend.rollingMs < lowTrend.normalLowMs)
    }

    @Test fun tooFewNightsOrALinearBaselineReadsNoTrend() {
        val base = lnBaseline(50.0, sigmaLn = 0.2)
        assertNull(trend(30.0, listOf(30.0), base))
        assertNotNull(trend(30.0, listOf(30.0, 30.0), base))
        val linear = RecoveryScorer.DriverBaseline(mean = 50.0, spread = 5.0, logDomain = false)
        assertNull(RecoveryScorer.hrvTrend(30.0, listOf(30.0, 30.0, 30.0), linear))
    }

    @Test fun theModifierIsClamped() {
        val base = lnBaseline(50.0, sigmaLn = 0.05)
        val t = trend(10.0, List(6) { 10.0 }, base)!!
        assertEquals(-RecoveryScorer.termZClamp, t.z, 0.0)
    }

    /** The breakdown names the trend whenever it was read, worth exactly what it moved the score. */
    @Test fun theDriverRowMatchesTheHeadline() {
        val base = lnBaseline(50.0, sigmaLn = 0.2)
        val prior = List(6) { 35.0 }
        val drivers = RecoveryDrivers.chargeDrivers(
            hrv = 50.0, rhr = 55.0, resp = null, hrvBaseline = base,
            rhrBaseline = null, respBaseline = null, sleepPerf = 0.85, hrvPriorWeek = prior,
        )
        val row = drivers.firstOrNull { it.label == ChargeDriverLabel.HRV_7_DAY_TREND }
        assertNotNull(row)
        val full = score(50.0, prior, base)!!
        val without = score(50.0, emptyList(), base)!!
        val delta = full - without
        val expected = if (delta < 0.0) -Math.round(-delta).toInt() else Math.round(delta).toInt()
        assertEquals(expected, row!!.deltaPoints)
        assertEquals(ChargeDriverVerdict.HRV_TREND_BELOW_RANGE_FATIGUE, row.verdict)
        val t = trend(50.0, prior, base)!!
        assertEquals(t.rollingMs, row.value, 0.0)
        assertEquals(t.normalLowMs, row.rangeLow!!, 0.0)
        assertEquals(t.normalHighMs, row.rangeHigh!!, 0.0)

        val inside = RecoveryDrivers.chargeDrivers(
            hrv = 50.0, rhr = 55.0, resp = null, hrvBaseline = base,
            rhrBaseline = null, respBaseline = null, sleepPerf = 0.85, hrvPriorWeek = listOf(50.0, 50.0, 50.0),
        )
        val insideRow = inside.firstOrNull { it.label == ChargeDriverLabel.HRV_7_DAY_TREND }
        assertEquals(0, insideRow?.deltaPoints)
        assertEquals(ChargeDriverVerdict.HRV_TREND_INSIDE_RANGE, insideRow?.verdict)

        val none = RecoveryDrivers.chargeDrivers(
            hrv = 50.0, rhr = 55.0, resp = null, hrvBaseline = base,
            rhrBaseline = null, respBaseline = null, sleepPerf = 0.85,
        )
        assertNull("no trend read, no row", none.firstOrNull { it.label == ChargeDriverLabel.HRV_7_DAY_TREND })
    }

    @Test fun theTraceNamesTheTrendAndMatchesTheScore() {
        val base = lnBaseline(50.0, sigmaLn = 0.2)
        val prior = List(6) { 70.0 }
        val (traced, lines) = RecoveryScorerTrace.recoveryTrace(
            hrv = 50.0, rhr = 55.0, resp = null, hrvBaseline = base,
            rhrBaseline = null, respBaseline = null, sleepPerf = 0.85, hrvPriorWeek = prior,
        )
        assertEquals(score(50.0, prior, base), traced)
        assertTrue(lines.any { it.startsWith("charge trend hrv7=") && it.contains("nights=7") })
    }

    /** The prior week is the six calendar days before the scored day, valid nights only, era cut included. */
    @Test fun thePriorWeekIsTheSixCalendarDaysBefore() {
        val day = "2026-03-10"
        val anchor = Baselines.isoEpochDay(day)!!
        val nights: List<Pair<String, Double?>> = (1..20).map { back ->
            Baselines.isoDayFromEpochDay(anchor - back) to (if (back == 2) 999.0 else (40 + back).toDouble())
        }
        val r = ChargeBaselines.resolveDay(
            day, ChargeBaselines.Nights(), ChargeBaselines.Nights(hrv = nights), ChargeBaselines.Nights(),
            emptyList(), 0.0, 0.0,
        )!!
        // Days -1..-6; day -2 is out of range (999 ms) and drops.
        assertEquals(listOf(41.0, 43.0, 44.0, 45.0, 46.0), r.hrvPriorWeek.sorted())

        val epoch = (anchor - 3).toDouble() * 86_400
        val cut = ChargeBaselines.resolveDay(
            day, ChargeBaselines.Nights(), ChargeBaselines.Nights(hrv = nights), ChargeBaselines.Nights(),
            emptyList(), epoch, 0.0,
        )!!
        assertEquals("recalibrated on ${Baselines.isoDayFromEpochDay(anchor - 3)}", epoch, cut.epochs.hrv, 0.0)
        assertEquals(listOf(41.0, 43.0), cut.hrvPriorWeek.sorted())
    }

    // Oracle (Swift prints, Kotlin pins the same text) ------------------------------------------------

    /** Kotlin twin of Swift `ChargeHRVTrendTests.oracleText()`, line for line. */
    private fun oracleText(): String {
        val out = ArrayList<String>()
        val means = listOf(30.0, 55.0, 90.0)
        val sigmas = listOf(0.05, 0.15, 0.4)
        val tonights = listOf(20.0, 45.0, 60.0, 120.0)
        val weeks = listOf(
            emptyList(), listOf(40.0), listOf(40.0, 42.0), listOf(25.0, 30.0, 28.0, 35.0, 31.0, 27.0),
            listOf(80.0, 95.0, 70.0, 88.0, 101.0, 76.0), listOf(50.0, 55.0, 60.0, 52.0, 58.0, 54.0),
        )
        fun f6(v: Double) = String.format(Locale.ROOT, "%.6f", v)
        for (m in means) {
            for (sg in sigmas) {
                val base = BaselineState(
                    baseline = m, spread = sg / 1.253, nValid = 30, nightsSinceUpdate = 1,
                    status = BaselineStatus.TRUSTED, logDomain = true,
                )
                for (t in tonights) {
                    for ((wi, w) in weeks.withIndex()) {
                        val tr = RecoveryScorer.hrvTrend(t, w, RecoveryScorer.DriverBaseline(base))
                        val s = RecoveryScorer.recovery(
                            hrv = t, rhr = 55.0, resp = null, hrvBaseline = base,
                            rhrBaseline = null, respBaseline = null, sleepPerf = 0.8, hrvPriorWeek = w,
                        )
                        val tz = tr?.let { f6(it.z) } ?: "nil"
                        val roll = tr?.let { f6(it.rollingMs) } ?: "nil"
                        out.add(
                            "m=${m.toInt()} s=$sg t=${t.toInt()} w=$wi z=$tz roll=$roll " +
                                "score=${s?.let { f6(it) } ?: "nil"}",
                        )
                    }
                }
            }
        }
        return out.joinToString("\n")
    }

    /** FNV-1a 64 over the UTF-8 text, the hash the Swift test pins. */
    private fun fnv1a(s: String): ULong {
        var h = 0xcbf29ce484222325UL
        for (b in s.toByteArray(Charsets.UTF_8)) {
            h = h xor (b.toUByte().toULong())
            h *= 0x100000001b3UL
        }
        return h
    }

    @Test fun oracleMatchesSwiftVerbatim() {
        val swift = requireNotNull(javaClass.classLoader!!.getResourceAsStream("charge_hrv_trend_oracle.txt"))
            .bufferedReader(Charsets.UTF_8).use { it.readText() }.trimEnd('\n')
        val text = oracleText()
        assertEquals(3 * 3 * 4 * 6, text.split("\n").size)
        assertEquals(swift, text)
        assertEquals(13596742503003570243UL, fnv1a(text))
    }
}
