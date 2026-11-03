package com.noop.analytics

import com.noop.data.HrSample
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * #54 — Swift/Kotlin parity for the training-load and HRmax helpers, pinned by ORACLE: every expected
 * literal below is the verbatim stdout of the Swift twins (`EffortLoad`, `StrainScorer.sampleDurationsMinutes`
 * / `gapCapMinutes`, `observedHRmaxEvidence`, `resolveHRmax`) compiled standalone with `swiftc -O` over the
 * same fixtures. Swift `EffortLoadTests` pins the same lines from the other side.
 */
class EffortLoadOracleTest {
    private fun f(v: Double?): String = v?.let { String.format(Locale.US, "%.9f", it) } ?: "nil"

    private fun key(o: Int): String {
        val z = o + 719468
        val era = (if (z >= 0) z else z - 146096) / 146097
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        var y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        if (m <= 2) y += 1
        return String.format(Locale.US, "%04d-%02d-%02d", y, m, d)
    }

    @Test
    fun trimpFromEffortMatchesSwift() {
        val line = "TRIMP " + listOf(0.0, 0.01, 12.34, 50.0, 77.77, 99.99, 100.0, -1.0)
            .joinToString(" ") { f(EffortLoad.trimpFromEffort(it)) }
        assertEquals(
            "TRIMP 0.000000000 0.000888592 1.992280297 83.858706094 998.756851374 7193.606929245 7200.000000000 nil",
            line,
        )
    }

    @Test
    fun acuteChronicAndMonotonyMatchSwift() {
        val base = TrainingLoadEngine.dayOrdinal("2026-01-01")!!
        val rows = ArrayList<Pair<String, Double?>>()
        for (i in 0 until 100) {
            if (i % 9 == 4 || i % 13 == 7) continue
            val e = 20.0 + ((i * 37) % 53).toDouble() + (if (i > 80) 15.0 else 0.0)
            rows.add(key(base + i) to (if (i == 50) null else e))
        }
        val loads = EffortLoad.loadsByOrdinal(rows)
        val sb = StringBuilder("ACWR")
        for (end in listOf(base + 10, base + 30, base + 60, base + 85, base + 99)) {
            val ac = EffortLoad.acuteChronic(loads, end)
            sb.append(" ${end - base}:").append(f(ac?.acute)).append("/").append(f(ac?.chronic))
                .append("/").append(f(ac?.ratio)).append("/${ac?.observedDays ?: -1}")
                .append("/m").append(f(EffortLoad.monotony(loads, end)))
        }
        assertEquals(
            "ACWR 10:nil/nil/nil/-1/m0.629627715 30:197.915131412/120.398695713/1.643831191/23/m0.912509327 " +
                "60:175.030607516/118.425777526/1.477977271/21/m1.078187855 " +
                "85:562.990796388/284.679142255/1.977632755/22/m0.725474255 " +
                "99:368.467696960/356.209605373/1.034412580/23/m0.695271585",
            sb.toString(),
        )
    }

    @Test
    fun cadenceAwareGapCapMatchesSwift() {
        val gaps = listOf(1, 1, 1, 1, 30, 30, 30, 300, 300, 300, 300, 300, 300, 3600, 300, 300, 180, 180, 180, 180, 0, 1, 1)
        var t = 0L
        val hr = ArrayList<HrSample>()
        for (g in gaps) { hr.add(HrSample("d", t, 100)); t += g }
        hr.add(HrSample("d", t, 100))
        assertEquals(
            "DUR 0.016666667 0.016666667 0.016666667 0.016666667 0.500000000 0.500000000 0.500000000 " +
                "5.000000000 5.000000000 5.000000000 5.000000000 5.000000000 5.000000000 5.000000000 " +
                "5.000000000 5.000000000 3.000000000 3.000000000 3.000000000 3.000000000 0.016666667 " +
                "0.016666667 0.016666667 0.016666667",
            "DUR " + StrainScorer.sampleDurationsMinutes(hr).joinToString(" ") { f(it) },
        )
    }

    @Test
    fun hrmaxEvidenceAndResolverMatchSwift() {
        val evid = listOf(
            emptyList(), listOf(190.0), listOf(190.0, 195.0), listOf(230.0, 201.0, 199.0, 150.0),
            listOf(Double.NaN, 0.0, 200.0, 200.0),
        ).joinToString(" ") { f(StrainScorer.observedHRmaxEvidence(it)) }
        assertEquals("EVID nil nil 190.000000000 199.000000000 200.000000000", "EVID $evid")

        val pk = mapOf("2026-01-01" to 205.0, "2026-03-31" to 203.0, "2026-04-01" to 199.0,
            "2025-12-31" to 210.0, "bad" to 215.0)
        assertEquals(
            "WIN 203.000000000 199.000000000",
            "WIN " + f(StrainScorer.observedHRmaxEvidenceEndingOn(pk, "2026-03-31")) + " " +
                f(StrainScorer.observedHRmaxEvidenceEndingOn(pk, "2026-04-01")),
        )

        val cases = listOf<Triple<Double?, Double, Double?>>(
            Triple(180.0, 40.0, 200.0), Triple(null, 40.0, 200.0), Triple(null, 40.0, 170.0),
            Triple(null, 0.0, null), Triple(null, 0.0, 195.0), Triple(null, 0.0, 185.0), Triple(0.0, 30.0, null),
        )
        val res = cases.joinToString(" ") { (o, a, e) ->
            val r = StrainScorer.resolveHRmax(o, a, e); f(r.first) + ":" + r.second
        }
        assertEquals(
            "RES 180.000000000:override 200.000000000:observed 180.000000000:tanaka nil:default " +
                "195.000000000:observed nil:default 187.000000000:tanaka",
            "RES $res",
        )
    }
}
