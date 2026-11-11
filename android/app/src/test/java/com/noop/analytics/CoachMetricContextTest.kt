package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Coach's "all metrics" block. The expected literals are the stdout of the Swift twin
 * (`CoachMetricContext`) compiled standalone over the same fixture, pasted verbatim, so a drift in either
 * implementation fails here (and the Swift test pins the same text the other way).
 */
class CoachMetricContextTest {

    private fun days(): List<String> = (0 until 30).map { i ->
        val d = 12 + i
        if (d <= 31) "2026-10-%02d".format(d) else "2026-11-%02d".format(d - 31)
    }

    private fun values(): Map<String, Map<String, Double>> {
        val v = HashMap<String, MutableMap<String, Double>>()
        fun put(id: String, day: String, x: Double) { v.getOrPut(id) { HashMap() }[day] = x }
        days().forEachIndexed { i, d ->
            put("recovery", d, (50 + i).toDouble())
            put("fitness_age", d, 31.4 + (i % 3) * 0.2)
            put("vitality", d, (70 + i % 5).toDouble())
            put("hydration_ml", d, (1500 + i * 10).toDouble())
            if (i % 7 == 0) put("weight", d, 72.45 + i / 100.0)
            if (i % 7 == 0) put("body_fat", d, 18.25)
            if (i >= 20) put("workout_count", d, (i % 2 + 1).toDouble())
        }
        return v
    }

    private val notes = listOf(
        MetricSnapshotNote(MetricSnapshotCategory.MIND, "journal:2026-11-09:Caffeine", "Caffeine (2026-11-09)", "Yes"),
        MetricSnapshotNote(MetricSnapshotCategory.LABS, "lab:ldl:1", "LDL (2026-10-30)", "2.9 mmol/L"),
    )

    @Test
    fun defaultCategoriesMatchTheSwiftOracle() {
        val expected = "ALL METRICS (ids are metric names; 30d = the last 30 days, averaged unless tagged; per-day lines are newest first, bare numbers in the units shown on the 30d line; a metric that is not listed was not recorded, it is not zero):\n" +
            "[Workouts]\n" +
            "  30d: workout_count=15 total\n" +
            "  2026-11-10: workout_count=2\n" +
            "  2026-11-09: workout_count=1\n" +
            "  2026-11-08: workout_count=2\n" +
            "  2026-11-07: workout_count=1\n" +
            "  2026-11-06: workout_count=2\n" +
            "  2026-11-05: workout_count=1\n" +
            "  2026-11-04: workout_count=2\n" +
            "  2026-11-03: workout_count=1\n" +
            "  2026-11-02: workout_count=2\n" +
            "  2026-11-01: workout_count=1"
        assertEquals(expected, CoachMetricContext.block(days(), values(), notes, CoachMetricContext.parseCategories(null)))
    }

    @Test
    fun newCategoriesMatchTheSwiftOracle() {
        val expected = "ALL METRICS (ids are metric names; 30d = the last 30 days, averaged unless tagged; per-day lines are newest first, bare numbers in the units shown on the 30d line; a metric that is not listed was not recorded, it is not zero):\n" +
            "[Body composition]\n" +
            "  30d: weight=72.7 kg latest, body_fat=18.3 % latest\n" +
            "  2026-11-09: weight=72.7, body_fat=18.3\n" +
            "  2026-11-02: weight=72.7, body_fat=18.3\n" +
            "[Fitness scores]\n" +
            "  30d: fitness_age=32 yrs latest, vitality=74 latest\n" +
            "  2026-11-10: fitness_age=32, vitality=74\n" +
            "  2026-11-09: fitness_age=32, vitality=73\n" +
            "  2026-11-08: fitness_age=31, vitality=72\n" +
            "  2026-11-07: fitness_age=32, vitality=71\n" +
            "  2026-11-06: fitness_age=32, vitality=70\n" +
            "  2026-11-05: fitness_age=31, vitality=74\n" +
            "  2026-11-04: fitness_age=32, vitality=73\n" +
            "  2026-11-03: fitness_age=32, vitality=72\n" +
            "  2026-11-02: fitness_age=31, vitality=71\n" +
            "  2026-11-01: fitness_age=32, vitality=70\n" +
            "  2026-10-31: fitness_age=32, vitality=74\n" +
            "  2026-10-30: fitness_age=31, vitality=73\n" +
            "  2026-10-29: fitness_age=32, vitality=72\n" +
            "  2026-10-28: fitness_age=32, vitality=71\n" +
            "[Hydration]\n" +
            "  30d: hydration_ml=49350 ml total\n" +
            "  2026-11-10: hydration_ml=1790\n" +
            "  2026-11-09: hydration_ml=1780\n" +
            "  2026-11-08: hydration_ml=1770\n" +
            "  2026-11-07: hydration_ml=1760\n" +
            "  2026-11-06: hydration_ml=1750\n" +
            "  2026-11-05: hydration_ml=1740\n" +
            "  2026-11-04: hydration_ml=1730\n" +
            "  2026-11-03: hydration_ml=1720\n" +
            "  2026-11-02: hydration_ml=1710\n" +
            "  2026-11-01: hydration_ml=1700\n" +
            "  2026-10-31: hydration_ml=1690\n" +
            "  2026-10-30: hydration_ml=1680\n" +
            "  2026-10-29: hydration_ml=1670\n" +
            "  2026-10-28: hydration_ml=1660\n" +
            "[Mood & journal]\n" +
            "  Caffeine (2026-11-09): Yes\n" +
            "[Lab markers]\n" +
            "  LDL (2026-10-30): 2.9 mmol/L"
        val enabled = setOf("body_composition", "fitness_scores", "hydration", "mood_journal", "lab_markers")
        assertEquals(expected, CoachMetricContext.block(days(), values(), notes, enabled))
    }

    @Test
    fun tightBudgetDropsPerDayLinesWholeAndKeepsSummaries() {
        val expected = "ALL METRICS (ids are metric names; 30d = the last 30 days, averaged unless tagged; per-day lines are newest first, bare numbers in the units shown on the 30d line; a metric that is not listed was not recorded, it is not zero):\n" +
            "[Workouts]\n" +
            "  30d: workout_count=15 total\n" +
            "[Body composition]\n" +
            "  30d: weight=72.7 kg latest, body_fat=18.3 % latest\n" +
            "  2026-11-09: weight=72.7, body_fat=18.3\n" +
            "  2026-11-02: weight=72.7, body_fat=18.3\n" +
            "[Fitness scores]\n" +
            "  30d: fitness_age=32 yrs latest, vitality=74 latest\n" +
            "[Hydration]\n" +
            "  30d: hydration_ml=49350 ml total"
        val enabled = setOf("body_composition", "fitness_scores", "hydration", "workouts")
        assertEquals(expected, CoachMetricContext.block(days(), values(), notes, enabled, maxChars = 700))
    }

    @Test
    fun nothingEnabledSendsNothing() {
        assertEquals("", CoachMetricContext.block(days(), values(), notes, emptySet()))
    }

    @Test
    fun aDisabledCategoryNeverAppears() {
        val out = CoachMetricContext.block(days(), values(), notes, setOf("body_composition"))
        assertTrue(out.contains("[Body composition]"))
        assertFalse(out.contains("Fitness"))
        assertFalse(out.contains("Hydration"))
        assertFalse(out.contains("LDL"))
    }

    @Test
    fun metricsTheDayTableAlreadyPrintsAreNotRepeated() {
        assertEquals("", CoachMetricContext.block(days(), values(), notes, setOf("recovery_strain")))
    }

    /** Swift oracle: parse("labs,heart,sleep,,body_composition ") encodes to "sleep,heart,body_composition,lab_markers"-order, minus unknown ids. */
    @Test
    fun parseAndEncodeMatchTheSwiftOracle() {
        assertEquals("sleep,heart,body_composition",
            CoachMetricContext.encodeCategories(CoachMetricContext.parseCategories("labs,heart,sleep,,body_composition ")))
        assertEquals("recovery_strain,sleep,heart,activity,workouts",
            CoachMetricContext.encodeCategories(CoachMetricContext.parseCategories(null)))
        assertTrue(CoachMetricContext.parseCategories("").isEmpty())
        assertEquals(setOf("sleep", "heart", "body_composition", "lab_markers"),
            CoachMetricContext.parseCategories("lab_markers,heart,sleep,bogus,body_composition"))
    }

    /** Everything on, a full month of every metric: the block stays near the budget (~1,800 tokens). */
    @Test
    fun aFullySpreadSnapshotStaysInsideTheTokenBudget() {
        val v = HashMap<String, Map<String, Double>>()
        for ((k, item) in MetricSnapshotCatalog.allItems().withIndex()) {
            v[item.id] = days().associateWith { 10.0 + k + it.takeLast(2).toInt() / 10.0 }
        }
        val out = CoachMetricContext.block(days(), v, notes, MetricSnapshotCategory.entries.map { it.id }.toSet())
        // Summaries and notes always stay; per-day lines stop at MAX_CHARS, so the overshoot is bounded.
        assertTrue("block is ${out.length} chars", out.length <= CoachMetricContext.MAX_CHARS)
        for (c in MetricSnapshotCategory.entries) {
            assertTrue("missing ${c.label}", out.contains("[${c.label}]"))
        }
    }
}
