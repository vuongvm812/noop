package com.noop.analytics

import com.noop.data.DailyMetric
import com.noop.data.Vo2MaxEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Fitness Age, VO₂max, Vitality and Body Age are computed DAILY: day D is gated on the merged daily rows of
 * [D-6, D] and written under D itself. Twin of the Swift `FitnessVitalityDailyTests`; [EXPECTED] is that
 * test's stdout, pasted verbatim. All data is synthetic.
 */
class FitnessVitalityDailyTest {

    /** 08-01…08-12 minus 08-06 (a gap), a rising RHR/HRV/steps trend so each window gives a different value. */
    private fun merged() = (1..12).filter { it != 6 }.map { i ->
        DailyMetric(
            deviceId = "my-whoop", day = "2026-08-%02d".format(i), totalSleepMin = (400 + i * 5).toDouble(),
            restingHr = 58 + i % 3, avgHrv = (50 + i).toDouble(), strain = (40 + i).toDouble(), steps = 7_000 + i * 100,
        )
    }

    private fun fmt(v: Double) = String.format(Locale.ROOT, "%.6f", v)

    private fun summary(): String {
        val days = IntelligenceEngine.dayRange("2026-08-01", "2026-08-14")
        val regularity = mapOf("2026-08-10" to 0.6, "2026-08-12" to 0.8)
        val profile = UserProfile(weightKg = 80.0, heightCm = 175.0, age = 40.0, sex = "male", waistCm = 90.0)
        val lines = ArrayList<String>()
        val runs = listOf("watch" to listOf("2026-07-20" to 47.0), "noWatch" to null)
        for ((name, watch) in runs) {
            val out = IntelligenceEngine.dailyFitnessVitalityRows(
                merged(), days, profile, "my-whoop-noop", watch, Vo2MaxEstimator.GARMIN, regularity)
            for (p in out.rows) lines.add("$name ${p.day} ${p.key} ${fmt(p.value)}")
            for (r in out.provenance) lines.add("$name prov ${r.day} ${r.key} ${r.sourceId}")
        }
        lines.add("dayMinus ${IntelligenceEngine.dayMinus("2026-03-01", 6)} ${IntelligenceEngine.dayMinus("bad", 6)}")
        val ranges = listOf("2026-12-30" to "2027-01-02", "2026-08-02" to "2026-08-01", "bad" to "2026-08-01")
        lines.add("range " + ranges.joinToString(" ") { "[" + IntelligenceEngine.dayRange(it.first, it.second).joinToString(",") + "]" })
        return lines.joinToString("\n")
    }

    @Test
    fun oracleOutputMatchesSwift() {
        assertEquals(EXPECTED, summary())
    }

    /** A day whose window is too sparse writes no Fitness Age; an empty history writes nothing at all. */
    @Test
    fun sparseWindowWritesNothing() {
        val profile = UserProfile(weightKg = 80.0, heightCm = 175.0, age = 40.0, sex = "male", waistCm = 90.0)
        val one = IntelligenceEngine.dailyFitnessVitalityRows(
            merged().take(1), listOf("2026-08-01"), profile, "my-whoop-noop", null, Vo2MaxEstimator.GARMIN, emptyMap())
        assertTrue(one.rows.none { it.key == "fitness_age" })
        val none = IntelligenceEngine.dailyFitnessVitalityRows(
            emptyList(), listOf("2026-08-01"), profile, "my-whoop-noop", null, Vo2MaxEstimator.GARMIN, emptyMap())
        assertTrue(none.rows.isEmpty())
    }

    private companion object {
        val EXPECTED = """
            watch 2026-08-01 vo2max_est 47.000000
            watch 2026-08-01 vitality 52.730432
            watch 2026-08-01 body_age 38.907827
            watch 2026-08-02 vo2max_est 47.000000
            watch 2026-08-02 vitality 52.843126
            watch 2026-08-02 body_age 38.862750
            watch 2026-08-03 vo2max_est 47.000000
            watch 2026-08-03 vitality 53.172224
            watch 2026-08-03 body_age 38.731110
            watch 2026-08-04 fitness_age 37.971565
            watch 2026-08-04 vo2max_est 47.000000
            watch 2026-08-04 vitality 53.393120
            watch 2026-08-04 body_age 38.642752
            watch 2026-08-05 fitness_age 35.203829
            watch 2026-08-05 vo2max_est 47.000000
            watch 2026-08-05 vitality 53.614017
            watch 2026-08-05 body_age 38.554393
            watch 2026-08-06 fitness_age 35.203829
            watch 2026-08-06 vo2max_est 47.000000
            watch 2026-08-06 vitality 53.614017
            watch 2026-08-06 body_age 38.554393
            watch 2026-08-07 fitness_age 35.118994
            watch 2026-08-07 vo2max_est 47.000000
            watch 2026-08-07 vitality 53.891058
            watch 2026-08-07 body_age 38.443577
            watch 2026-08-08 fitness_age 35.232357
            watch 2026-08-08 vo2max_est 47.000000
            watch 2026-08-08 vitality 54.115484
            watch 2026-08-08 body_age 38.353806
            watch 2026-08-09 fitness_age 34.822072
            watch 2026-08-09 vo2max_est 47.000000
            watch 2026-08-09 vitality 54.542653
            watch 2026-08-09 body_age 38.182939
            watch 2026-08-10 fitness_age 34.673611
            watch 2026-08-10 vo2max_est 47.000000
            watch 2026-08-10 vitality 52.913982
            watch 2026-08-10 body_age 38.834407
            watch 2026-08-11 fitness_age 34.786974
            watch 2026-08-11 vo2max_est 47.000000
            watch 2026-08-11 vitality 55.019923
            watch 2026-08-11 body_age 37.992031
            watch 2026-08-12 fitness_age 34.376689
            watch 2026-08-12 vo2max_est 47.000000
            watch 2026-08-12 vitality 55.394630
            watch 2026-08-12 body_age 37.842148
            watch 2026-08-13 fitness_age 34.376689
            watch 2026-08-13 vo2max_est 47.000000
            watch 2026-08-13 vitality 55.394630
            watch 2026-08-13 body_age 37.842148
            watch 2026-08-14 fitness_age 34.313063
            watch 2026-08-14 vo2max_est 47.000000
            watch 2026-08-14 vitality 55.516341
            watch 2026-08-14 body_age 37.793464
            watch prov 2026-08-01 vo2max_est garmin
            watch prov 2026-08-02 vo2max_est garmin
            watch prov 2026-08-03 vo2max_est garmin
            watch prov 2026-08-04 vo2max_est garmin
            watch prov 2026-08-05 vo2max_est garmin
            watch prov 2026-08-06 vo2max_est garmin
            watch prov 2026-08-07 vo2max_est garmin
            watch prov 2026-08-08 vo2max_est garmin
            watch prov 2026-08-09 vo2max_est garmin
            watch prov 2026-08-10 vo2max_est garmin
            watch prov 2026-08-11 vo2max_est garmin
            watch prov 2026-08-12 vo2max_est garmin
            watch prov 2026-08-13 vo2max_est garmin
            watch prov 2026-08-14 vo2max_est garmin
            noWatch 2026-08-01 vitality 52.730432
            noWatch 2026-08-01 body_age 38.907827
            noWatch 2026-08-02 vitality 52.843126
            noWatch 2026-08-02 body_age 38.862750
            noWatch 2026-08-03 vitality 53.172224
            noWatch 2026-08-03 body_age 38.731110
            noWatch 2026-08-04 fitness_age 37.971565
            noWatch 2026-08-04 vo2max_est 46.875417
            noWatch 2026-08-04 vitality 53.393120
            noWatch 2026-08-04 body_age 38.642752
            noWatch 2026-08-05 fitness_age 35.203829
            noWatch 2026-08-05 vo2max_est 47.694667
            noWatch 2026-08-05 vitality 53.614017
            noWatch 2026-08-05 body_age 38.554393
            noWatch 2026-08-06 fitness_age 35.203829
            noWatch 2026-08-06 vo2max_est 47.694667
            noWatch 2026-08-06 vitality 53.614017
            noWatch 2026-08-06 body_age 38.554393
            noWatch 2026-08-07 fitness_age 35.118994
            noWatch 2026-08-07 vo2max_est 47.719778
            noWatch 2026-08-07 vitality 53.891058
            noWatch 2026-08-07 body_age 38.443577
            noWatch 2026-08-08 fitness_age 35.232357
            noWatch 2026-08-08 vo2max_est 47.686222
            noWatch 2026-08-08 vitality 54.115484
            noWatch 2026-08-08 body_age 38.353806
            noWatch 2026-08-09 fitness_age 34.822072
            noWatch 2026-08-09 vo2max_est 47.807667
            noWatch 2026-08-09 vitality 54.542653
            noWatch 2026-08-09 body_age 38.182939
            noWatch 2026-08-10 fitness_age 34.673611
            noWatch 2026-08-10 vo2max_est 47.851611
            noWatch 2026-08-10 vitality 52.913982
            noWatch 2026-08-10 body_age 38.834407
            noWatch 2026-08-11 fitness_age 34.786974
            noWatch 2026-08-11 vo2max_est 47.818056
            noWatch 2026-08-11 vitality 55.019923
            noWatch 2026-08-11 body_age 37.992031
            noWatch 2026-08-12 fitness_age 34.376689
            noWatch 2026-08-12 vo2max_est 47.939500
            noWatch 2026-08-12 vitality 55.394630
            noWatch 2026-08-12 body_age 37.842148
            noWatch 2026-08-13 fitness_age 34.376689
            noWatch 2026-08-13 vo2max_est 47.939500
            noWatch 2026-08-13 vitality 55.394630
            noWatch 2026-08-13 body_age 37.842148
            noWatch 2026-08-14 fitness_age 34.313063
            noWatch 2026-08-14 vo2max_est 47.958333
            noWatch 2026-08-14 vitality 55.516341
            noWatch 2026-08-14 body_age 37.793464
            noWatch prov 2026-08-04 vo2max_est nes
            noWatch prov 2026-08-05 vo2max_est nes
            noWatch prov 2026-08-06 vo2max_est nes
            noWatch prov 2026-08-07 vo2max_est nes
            noWatch prov 2026-08-08 vo2max_est nes
            noWatch prov 2026-08-09 vo2max_est nes
            noWatch prov 2026-08-10 vo2max_est nes
            noWatch prov 2026-08-11 vo2max_est nes
            noWatch prov 2026-08-12 vo2max_est nes
            noWatch prov 2026-08-13 vo2max_est nes
            noWatch prov 2026-08-14 vo2max_est nes
            dayMinus 2026-02-23 bad
            range [2026-12-30,2026-12-31,2027-01-01,2027-01-02] [] []
        """.trimIndent()
    }
}
