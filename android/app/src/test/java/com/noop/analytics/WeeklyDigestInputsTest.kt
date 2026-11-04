package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/**
 * The pure half of the weekly-digest input resolver (#89). [EXPECTED] is the verbatim stdout of
 * the Swift oracle (WeeklyDigestInputs.swift compiled standalone over the fixtures of
 * `WeeklyDigestInputsTests.swift`).
 */
class WeeklyDigestInputsTest {

    private fun fixtureDays() = listOf(
        WeeklyDigestDay(
            "2026-06-08", charge = 70.0, effort = 45.0, rest = 81.0, rhr = 52.0, hrv = 61.0, steps = 9000.0,
            sleepMin = 450.0, efficiency = 0.9, deepMin = 80.0, remMin = 100.0, spo2 = 96.5, respiration = 14.5,
            skinTemp = -0.2,
        ),
        WeeklyDigestDay("2026-06-09", charge = 66.0, steps = 7500.0, sleepMin = 400.0, efficiency = 92.0, skinTemp = 33.4),
        WeeklyDigestDay("2026-06-10", charge = Double.NaN, rest = 77.0),
        WeeklyDigestDay("2026-06-10", charge = 61.0, rest = 78.0),
    )

    private fun fixtureWatch() = WeeklyDigestWatchSeries(
        stress = mapOf("2026-06-08" to 31.0, "2026-06-09" to 44.0),
        sleepScore = mapOf("2026-06-08" to 82.0),
        activeKcal = mapOf("2026-06-09" to 512.0),
        trainingLoad = mapOf("2026-06-08" to 180.0),
        intensityMinutes = WeeklyDigestInputs.intensityMinutes(
            mapOf("2026-06-08" to 20.0, "2026-06-09" to 15.0),
            mapOf("2026-06-09" to 10.0, "2026-06-10" to 5.0),
        ),
        vo2max = mapOf("2026-06-08" to 48.0),
        bodyBatteryRecharge = mapOf("2026-06-09" to 42.0),
    )

    private fun f4(x: Double) = String.format(Locale.ROOT, "%.4f", x)

    private fun dumpSeries(m: Map<WeeklyMetric, Map<String, Double>>): List<String> =
        WeeklyMetric.values().mapNotNull { metric ->
            val s = m[metric]?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            "${metric.key}: " + s.keys.sorted().joinToString(" ") { "$it=${f4(s.getValue(it))}" }
        }

    private fun recharge(label: String, samples: List<Pair<Long, Double>>, start: Long, end: Long): String =
        "recharge $label=" + (WeeklyDigestInputs.bodyBatteryRecharge(samples, start, end)?.let { f4(it) } ?: "nil")

    @Test fun oracleFixturesMatchSwiftOutput() {
        val lines = dumpSeries(WeeklyDigestInputs.series(fixtureDays(), fixtureWatch())).toMutableList()
        val start = 1_000_000L
        val end = 1_028_800L
        val bb = listOf(
            start - 10_801 to 5.0, start - 10_800 to 30.0, start - 3_600 to 22.0, start to 25.0, start + 600 to 10.0,
            end - 60 to 90.0, end to 70.0, end + 3_600 to 88.0, end + 10_800 to 89.0, end + 10_801 to 99.0,
        )
        lines += recharge("normal", bb, start, end)
        lines += recharge("noBefore", bb.filter { it.first > start }, start, end)
        lines += recharge("noAfter", bb.filter { it.first < end }, start, end)
        assertEquals(EXPECTED, lines.joinToString("\n"))
    }

    @Test fun headlineMapsMatchTheDailyRows() {
        val m = WeeklyDigestInputs.series(fixtureDays(), WeeklyDigestWatchSeries.EMPTY)
        assertEquals(mapOf("2026-06-08" to 70.0, "2026-06-09" to 66.0, "2026-06-10" to 61.0), m[WeeklyMetric.CHARGE])
        assertNull(m[WeeklyMetric.STRESS])
    }

    private companion object {
        val EXPECTED = """
            charge: 2026-06-08=70.0000 2026-06-09=66.0000 2026-06-10=61.0000
            effort: 2026-06-08=45.0000
            rest: 2026-06-08=81.0000 2026-06-10=78.0000
            rhr: 2026-06-08=52.0000
            hrv: 2026-06-08=61.0000
            steps: 2026-06-08=9000.0000 2026-06-09=7500.0000
            activeKcal: 2026-06-09=512.0000
            sleepDuration: 2026-06-08=7.5000 2026-06-09=6.6667
            sleepEfficiency: 2026-06-08=90.0000 2026-06-09=92.0000
            deepSleep: 2026-06-08=80.0000
            remSleep: 2026-06-08=100.0000
            sleepScore: 2026-06-08=82.0000
            spo2: 2026-06-08=96.5000
            respiration: 2026-06-08=14.5000
            skinTempDeviation: 2026-06-08=-0.2000
            stress: 2026-06-08=31.0000 2026-06-09=44.0000
            bodyBatteryRecharge: 2026-06-09=42.0000
            trainingLoad: 2026-06-08=180.0000
            intensityMinutes: 2026-06-08=20.0000 2026-06-09=35.0000 2026-06-10=10.0000
            vo2max: 2026-06-08=48.0000
            recharge normal=67.0000
            recharge noBefore=nil
            recharge noAfter=nil
        """.trimIndent()
    }
}
