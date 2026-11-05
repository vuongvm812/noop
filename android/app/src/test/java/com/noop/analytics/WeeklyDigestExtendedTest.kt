package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/**
 * The metrics added after the original five (#88). [EXPECTED] is the verbatim stdout of the
 * Swift oracle (StrandAnalytics WeeklyDigest.swift compiled standalone over the same
 * fixtures as `WeeklyDigestExtendedTests.swift`), so the two engines cannot drift.
 */
class WeeklyDigestExtendedTest {

    private fun series(f: (Int) -> Double?): Map<String, Double> {
        val out = mutableMapOf<String, Double>()
        for (k in 0 until 41) f(k)?.let { out[WeeklyDigestEngine.addDays("2026-05-04", k)] = it }
        return out
    }

    private fun extendedFixture(): Map<WeeklyMetric, Map<String, Double>> = mapOf(
        WeeklyMetric.CHARGE to series { k -> (60 + (k % 5) * 2 + (if (k >= 35) 2 else 0)).toDouble() },
        WeeklyMetric.EFFORT to series { k -> (50 + (k % 4) * 3).toDouble() },
        WeeklyMetric.REST to series { k -> (70 + (k % 3) * 4).toDouble() },
        WeeklyMetric.RHR to series { k -> (55 + k % 3).toDouble() },
        WeeklyMetric.HRV to series { k -> (60 + (k % 4) * 2).toDouble() },
        WeeklyMetric.STEPS to series { k -> (if (k < 35) 8000 + (k % 3) * 300 else 10500 + (k % 2) * 200).toDouble() },
        WeeklyMetric.ACTIVE_KCAL to series { k -> (400 + (k % 5) * 20).toDouble() },
        WeeklyMetric.SLEEP_DURATION to series { k -> if (k < 35) 7.0 + (k % 3) * 0.25 else 6.0 + (k % 2) * 0.25 },
        WeeklyMetric.SLEEP_EFFICIENCY to series { k -> (88 + k % 3).toDouble() },
        WeeklyMetric.DEEP_SLEEP to series { k -> (80 + (k % 4) * 5).toDouble() },
        WeeklyMetric.REM_SLEEP to series { k -> (95 + (k % 3) * 5).toDouble() },
        WeeklyMetric.SLEEP_SCORE to series { k -> (75 + (k % 5) * 2).toDouble() },
        WeeklyMetric.SPO2 to series { k -> 95.5 + (k % 2) * 0.5 },
        WeeklyMetric.RESPIRATION to series { k -> 14.25 + (k % 3) * 0.25 },
        WeeklyMetric.SKIN_TEMP_DEVIATION to series { k -> (k % 5 - 2) * 0.125 },
        WeeklyMetric.STRESS to series { k -> (30 + (k % 4) * 2 + (if (k < 35) 0 else 10)).toDouble() },
        WeeklyMetric.BODY_BATTERY_RECHARGE to series { k -> (55 + (k % 3) * 5).toDouble() },
        WeeklyMetric.TRAINING_LOAD to series { k -> if (k % 2 == 0) (120 + (k % 7) * 10).toDouble() else null },
        WeeklyMetric.INTENSITY_MINUTES to series { k -> if (k % 3 == 0) null else (20 + (k % 4) * 10).toDouble() },
        WeeklyMetric.VO2MAX to series { k -> if (k % 7 == 3) (if (k < 35) 47.0 else 48.0) else null },
    )

    private fun neutralFixture(): Map<WeeklyMetric, Map<String, Double>> = mapOf(
        WeeklyMetric.CHARGE to series { 65.0 },
        WeeklyMetric.SLEEP_DURATION to series { k -> if (k < 35) 7.5 + (k % 2) * 0.25 else 5.5 + (k % 2) * 0.25 },
    )

    private fun thinBaselineFixture(): Map<WeeklyMetric, Map<String, Double>> = mapOf(
        WeeklyMetric.STEPS to series { k -> if (k < 26) null else (if (k < 35) 6000.0 else 12000.0) },
        WeeklyMetric.VO2MAX to series { k -> if (k % 7 == 3) (if (k < 35) 45.0 else 47.0) else null },
    )

    private fun fmt(x: Double?): String = x?.let { String.format(Locale.ROOT, "%.6f", it) } ?: "nil"

    private fun dump(name: String, d: WeeklyDigest): List<String> {
        val lines = mutableListOf("== $name ${d.weekStart}..${d.weekEnd} days=${d.daysWithData} balance=${balanceKey(d.balance)}")
        d.focalPoints.forEach { lines.add("focal: $it") }
        for (s in d.metrics) {
            if (!s.hasData && s.weekOverWeek.previous.n == 0) continue
            lines.add(
                "${s.metric.key} n=${s.thisWeek.n}/${s.weekOverWeek.previous.n}" +
                    " this=${fmt(s.thisValue)} last=${fmt(s.lastValue)} delta=${fmt(s.wowDelta)}" +
                    " pct=${fmt(s.weekOverWeek.pctChange)} dir=${s.weekOverWeek.direction}" +
                    " base=${fmt(s.baselineMean)} vs=${fmt(s.vsBaseline)} spread=${fmt(s.personalSpread)}" +
                    " move=${fmt(s.normalisedMove)} good=${s.wowGoodness} target=${fmt(s.targetProgress)}",
            )
        }
        return lines
    }

    /** Swift's `BalanceRead.rawValue`. */
    private fun balanceKey(b: BalanceRead): String = b.name.lowercase(Locale.ROOT)

    @Test fun oracleFixturesMatchSwiftOutput() {
        val lines = mutableListOf<String>()
        lines += dump("extended", WeeklyDigestEngine.build(extendedFixture(), "2026-06-13"))
        lines += dump("neutral", WeeklyDigestEngine.build(neutralFixture(), "2026-06-13"))
        lines += dump("thinBaseline", WeeklyDigestEngine.build(thinBaselineFixture(), "2026-06-13"))
        lines += dump("extendedPastWeek", WeeklyDigestEngine.build(extendedFixture(), "2026-06-03"))
        assertEquals(EXPECTED, lines.joinToString("\n"))
    }

    @Test fun headlineOrderAndCountMatchSwift() {
        assertEquals(HEADLINE, WeeklyMetric.values().take(5))
        assertEquals(20, WeeklyMetric.values().size)
    }

    @Test fun neutralMetricHasNoVerdict() {
        val d = WeeklyDigestEngine.build(neutralFixture(), "2026-06-13")
        val s = d.summary(WeeklyMetric.SLEEP_DURATION)!!
        assertEquals(-1, s.weekOverWeek.direction)
        assertEquals(0, s.wowGoodness)
        assertFalse(d.focalPoints[0].contains("good sign"))
        assertFalse(d.focalPoints[0].contains("worth a look"))
    }

    @Test fun thinBaselineCannotBeAMover() {
        val s = WeeklyDigestEngine.build(thinBaselineFixture(), "2026-06-13").summary(WeeklyMetric.STEPS)!!
        assertNull(s.personalSpread)
        assertEquals(0.0, s.normalisedMove, 0.0)
    }

    @Test fun emptyExtrasLeaveTheDigestUnchanged() {
        val headlineOnly = extendedFixture().filterKeys { it in HEADLINE }
        val withEmptyExtras = headlineOnly + WeeklyMetric.values()
            .filter { it !in HEADLINE }.associateWith { emptyMap<String, Double>() }
        assertEquals(
            WeeklyDigestEngine.build(headlineOnly, "2026-06-13"),
            WeeklyDigestEngine.build(withEmptyExtras, "2026-06-13"),
        )
    }

    /** Sections list only the metrics with data this week, in the order Swift pins (#90). */
    @Test fun sectionsKeepOnlyRowsWithDataThisWeek() {
        val sections = WeeklyDigestEngine.sections(WeeklyDigestEngine.build(thinBaselineFixture(), "2026-06-13"))
        assertEquals(listOf(WeeklyDigestSection.ACTIVITY, WeeklyDigestSection.TRAINING), sections.map { it.section })
        assertEquals(listOf(WeeklyMetric.STEPS), sections[0].rows.map { it.metric })
        assertEquals(listOf(WeeklyMetric.VO2MAX), sections[1].rows.map { it.metric })
        val full = WeeklyDigestEngine.sections(WeeklyDigestEngine.build(extendedFixture(), "2026-06-13"))
        assertEquals(WeeklyDigestSection.values().toList(), full.map { it.section })
        assertEquals(15, full.sumOf { it.rows.size })
    }

    private companion object {
        /** The original five, whose outputs the legacy tests pin. */
        val HEADLINE = listOf(WeeklyMetric.CHARGE, WeeklyMetric.EFFORT, WeeklyMetric.REST, WeeklyMetric.RHR, WeeklyMetric.HRV)

        val EXPECTED = """
            == extended 2026-06-08..2026-06-14 days=6 balance=underloaded
            focal: Steps is up 28% week over week (avg 10600 vs 8300), a good sign.
            focal: You carried more Charge than you spent this week: there's room to push if you want it.
            charge n=6/7 this=65.333333 last=64.857143 delta=0.476190 pct=0.734214 dir=1 base=63.785714 vs=1.547619 spread=nil move=0.039683 good=1 target=nil
            effort n=6/7 this=54.500000 last=53.857143 delta=0.642857 pct=1.193634 dir=1 base=54.500000 vs=0.000000 spread=nil move=0.053571 good=1 target=nil
            rest n=6/7 this=74.000000 last=74.000000 delta=0.000000 pct=0.000000 dir=0 base=73.857143 vs=0.142857 spread=nil move=0.000000 good=0 target=nil
            rhr n=6/7 this=56.000000 last=56.000000 delta=0.000000 pct=0.000000 dir=0 base=55.964286 vs=0.035714 spread=nil move=0.000000 good=0 target=nil
            hrv n=6/7 this=63.000000 last=62.571429 delta=0.428571 pct=0.684932 dir=1 base=63.000000 vs=0.000000 spread=nil move=0.053571 good=1 target=nil
            steps n=6/7 this=10600.000000 last=8300.000000 delta=2300.000000 pct=27.710843 dir=1 base=8289.285714 vs=2310.714286 spread=500.000000 move=4.600000 good=1 target=nil
            activeKcal n=6/7 this=433.333333 last=448.571429 delta=-15.238095 pct=-3.397028 dir=-1 base=437.857143 vs=-4.523810 spread=50.000000 move=-0.304762 good=-1 target=nil
            sleepDuration n=6/7 this=6.125000 last=7.250000 delta=-1.125000 pct=-15.517241 dir=-1 base=7.241071 vs=-1.116071 spread=0.250000 move=-4.500000 good=0 target=nil
            sleepEfficiency n=6/7 this=89.000000 last=89.000000 delta=0.000000 pct=0.000000 dir=0 base=88.964286 vs=0.035714 spread=2.000000 move=0.000000 good=0 target=nil
            deepSleep n=6/7 this=87.500000 last=86.428571 delta=1.071429 pct=1.239669 dir=1 base=87.500000 vs=0.000000 spread=5.692750 move=0.188209 good=1 target=nil
            remSleep n=6/7 this=100.000000 last=100.000000 delta=0.000000 pct=0.000000 dir=0 base=99.821429 vs=0.178571 spread=5.000000 move=0.000000 good=0 target=nil
            sleepScore n=6/7 this=78.333333 last=79.857143 delta=-1.523810 pct=-1.908169 dir=-1 base=78.785714 vs=-0.452381 spread=4.000000 move=-0.380952 good=-1 target=nil
            spo2 n=6/7 this=95.750000 last=95.714286 delta=0.035714 pct=0.037313 dir=1 base=95.750000 vs=0.000000 spread=0.500000 move=0.071429 good=0 target=nil
            respiration n=6/7 this=14.500000 last=14.500000 delta=0.000000 pct=0.000000 dir=0 base=14.491071 vs=0.008929 spread=0.300000 move=0.000000 good=0 target=nil
            skinTempDeviation n=6/7 this=-0.041667 last=0.053571 delta=-0.095238 pct=-177.777778 dir=-1 base=-0.013393 vs=-0.028274 spread=0.177884 move=-0.535395 good=0 target=nil
            stress n=6/7 this=43.000000 last=32.571429 delta=10.428571 pct=32.017544 dir=1 base=33.000000 vs=10.000000 spread=3.000000 move=3.476190 good=-1 target=nil
            bodyBatteryRecharge n=6/7 this=60.000000 last=60.000000 delta=0.000000 pct=0.000000 dir=0 base=59.821429 vs=0.178571 spread=5.000000 move=0.000000 good=0 target=nil
            trainingLoad n=3/3 this=450.000000 last=420.000000 delta=30.000000 pct=7.142857 dir=1 base=435.000000 vs=15.000000 spread=25.000000 move=1.200000 good=0 target=nil
            intensityMinutes n=4/4 this=140.000000 last=120.000000 delta=20.000000 pct=16.666667 dir=1 base=145.000000 vs=-5.000000 spread=19.148542 move=1.044466 good=1 target=0.933333
            vo2max n=1/1 this=48.000000 last=47.000000 delta=1.000000 pct=2.127660 dir=1 base=47.000000 vs=1.000000 spread=1.000000 move=1.000000 good=1 target=nil
            == neutral 2026-06-08..2026-06-14 days=6 balance=insufficient
            focal: Sleep duration is down 26% week over week (avg 5.6 vs 7.6).
            charge n=6/7 this=65.000000 last=65.000000 delta=0.000000 pct=0.000000 dir=0 base=65.000000 vs=0.000000 spread=nil move=0.000000 good=0 target=nil
            sleepDuration n=6/7 this=5.625000 last=7.607143 delta=-1.982143 pct=-26.056338 dir=-1 base=7.625000 vs=-2.000000 spread=0.250000 move=-7.928571 good=0 target=nil
            == thinBaseline 2026-06-08..2026-06-14 days=6 balance=insufficient
            focal: VO2 max is up 2.0 ml/kg/min week over week (latest 47 vs 45), a good sign.
            steps n=6/7 this=12000.000000 last=6000.000000 delta=6000.000000 pct=100.000000 dir=1 base=6000.000000 vs=6000.000000 spread=nil move=0.000000 good=1 target=nil
            vo2max n=1/1 this=47.000000 last=45.000000 delta=2.000000 pct=4.444444 dir=1 base=45.000000 vs=2.000000 spread=1.000000 move=2.000000 good=1 target=nil
            == extendedPastWeek 2026-06-01..2026-06-07 days=7 balance=underloaded
            focal: Training load is up 33% week over week (total 600 vs 450).
            focal: You carried more Charge than you spent this week: there's room to push if you want it.
            charge n=7/7 this=64.857143 last=63.714286 delta=1.142857 pct=1.793722 dir=1 base=63.809524 vs=1.047619 spread=nil move=0.095238 good=1 target=nil
            effort n=7/7 this=53.857143 last=55.142857 delta=-1.285714 pct=-2.331606 dir=-1 base=54.285714 vs=-0.428571 spread=nil move=-0.107143 good=-1 target=nil
            rest n=7/7 this=74.000000 last=73.428571 delta=0.571429 pct=0.778210 dir=1 base=74.000000 vs=0.000000 spread=nil move=0.047619 good=1 target=nil
            rhr n=7/7 this=56.000000 last=55.857143 delta=0.142857 pct=0.255754 dir=1 base=56.000000 vs=0.000000 spread=nil move=0.035714 good=-1 target=nil
            hrv n=7/7 this=62.571429 last=63.428571 delta=-0.857143 pct=-1.351351 dir=-1 base=62.857143 vs=-0.285714 spread=nil move=-0.107143 good=-1 target=nil
            steps n=7/7 this=8300.000000 last=8257.142857 delta=42.857143 pct=0.519031 dir=1 base=8300.000000 vs=0.000000 spread=500.000000 move=0.085714 good=1 target=nil
            activeKcal n=7/7 this=448.571429 last=437.142857 delta=11.428571 pct=2.614379 dir=1 base=438.095238 vs=10.476190 spread=50.000000 move=0.228571 good=1 target=nil
            sleepDuration n=7/7 this=7.250000 last=7.214286 delta=0.035714 pct=0.495050 dir=1 base=7.250000 vs=0.000000 spread=0.250000 move=0.142857 good=0 target=nil
            sleepEfficiency n=7/7 this=89.000000 last=88.857143 delta=0.142857 pct=0.160772 dir=1 base=89.000000 vs=0.000000 spread=2.000000 move=0.071429 good=1 target=nil
            deepSleep n=7/7 this=86.428571 last=88.571429 delta=-2.142857 pct=-2.419355 dir=-1 base=87.142857 vs=-0.714286 spread=5.824824 move=-0.367884 good=-1 target=nil
            remSleep n=7/7 this=100.000000 last=99.285714 delta=0.714286 pct=0.719424 dir=1 base=100.000000 vs=0.000000 spread=5.000000 move=0.142857 good=1 target=nil
            sleepScore n=7/7 this=79.857143 last=78.714286 delta=1.142857 pct=1.451906 dir=1 base=78.809524 vs=1.047619 spread=4.000000 move=0.285714 good=1 target=nil
            spo2 n=7/7 this=95.714286 last=95.785714 delta=-0.071429 pct=-0.074571 dir=-1 base=95.738095 vs=-0.023810 spread=0.500000 move=-0.142857 good=0 target=nil
            respiration n=7/7 this=14.500000 last=14.464286 delta=0.035714 pct=0.246914 dir=1 base=14.500000 vs=0.000000 spread=0.300000 move=0.119048 good=0 target=nil
            skinTempDeviation n=7/7 this=0.053571 last=-0.017857 delta=0.071429 pct=400.000000 dir=1 base=-0.011905 vs=0.065476 spread=0.185003 move=0.386094 good=0 target=nil
            stress n=7/7 this=32.571429 last=33.428571 delta=-0.857143 pct=-2.564103 dir=-1 base=32.857143 vs=-0.285714 spread=3.000000 move=-0.285714 good=1 target=nil
            bodyBatteryRecharge n=7/7 this=60.000000 last=59.285714 delta=0.714286 pct=1.204819 dir=1 base=60.000000 vs=0.000000 spread=5.000000 move=0.142857 good=1 target=nil
            trainingLoad n=4/3 this=600.000000 last=450.000000 delta=150.000000 pct=33.333333 dir=1 base=550.000000 vs=50.000000 spread=86.602540 move=1.732051 good=0 target=nil
            intensityMinutes n=5/4 this=160.000000 last=160.000000 delta=0.000000 pct=0.000000 dir=0 base=156.666667 vs=3.333333 spread=35.118846 move=0.000000 good=0 target=1.066667
            vo2max n=1/1 this=47.000000 last=47.000000 delta=0.000000 pct=0.000000 dir=0 base=47.000000 vs=0.000000 spread=1.000000 move=0.000000 good=0 target=nil
        """.trimIndent()
    }
}
