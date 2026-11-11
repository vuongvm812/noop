package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins [MetricSnapshot]. The expected literals are the stdout of the compiled Swift twin (the oracle). */
class MetricSnapshotTest {
    private fun fmt(id: String, v: Double?) = MetricSnapshotFormat.text(MetricSnapshotCatalog.allItems().first { it.id == id }, v)

    @Test fun formatMatchesSwiftOracle() {
        assertEquals("67 %", fmt("recovery", 66.5))
        assertEquals("66 %", fmt("recovery", 66.4999))
        assertEquals("12.3 /100", fmt("strain", 12.25))
        assertEquals("0.0 /100", fmt("strain", -0.04))
        assertEquals("81.4 kg", fmt("weight", 81.35))
        assertEquals("81.1 kg", fmt("weight", 81.05))
        assertEquals("0 yrs", fmt("fitness_age", -0.4))
        assertEquals("34 yrs", fmt("fitness_age", 33.5))
        assertEquals("-3 yrs", fmt("fitness_age", -2.5))
        assertEquals("12345 steps", fmt("steps", 12345.0))
        assertEquals("18.3 %", fmt("body_fat", 18.25))
        assertEquals("1 ms", fmt("hrv", 0.5))
        assertEquals("2 ms", fmt("hrv", 1.5))
        assertEquals("3 ms", fmt("hrv", 2.5))
        assertEquals("-0.1 °C", fmt("skin_temp", -0.05))
        assertEquals("52.0 ml/kg/min", fmt("vo2max", 52.04))
        assertEquals("-", fmt("hrv", null))
    }

    @Test fun rangeRollupAndCoachLinesMatchSwiftOracle() {
        val snap = MetricSnapshotBuilder.build(
            "2026-01-02", "2026-01-04",
            mapOf(
                "recovery" to mapOf("2026-01-01" to 10.0, "2026-01-02" to 50.0, "2026-01-04" to 61.0),
                "steps" to mapOf("2026-01-02" to 1000.0, "2026-01-03" to 2500.0),
                "weight" to mapOf("2026-01-02" to 80.0, "2026-01-04" to 79.5),
                "body_age" to mapOf("2026-01-03" to 30.0),
                "max_hr" to mapOf("2026-01-02" to 150.0, "2026-01-03" to 171.0),
                "body_battery_min" to mapOf("2026-01-02" to 20.0, "2026-01-03" to 12.0),
            ),
            listOf(MetricSnapshotNote(MetricSnapshotCategory.LABS, "lab:ldl", "LDL", "2.9 mmol/L")),
        )
        assertEquals("56 %", snap.row("recovery")!!.text)
        assertEquals("171 bpm", snap.row("max_hr")!!.text)
        assertEquals("3500 steps", snap.row("steps")!!.text)
        assertEquals("79.5 kg", snap.row("weight")!!.text)
        assertEquals("30 yrs", snap.row("body_age")!!.text)
        assertEquals("12", snap.row("body_battery_min")!!.text)
        assertEquals("2.9 mmol/L", snap.sections.first { it.category == MetricSnapshotCategory.LABS }.notes.first().text)
        assertFalse(snap.isEmpty)
        assertEquals(2, snap.row("recovery")!!.days)
    }

    @Test fun catalogIsConsistent() {
        assertEquals(MetricSnapshotCatalog.allItems().size, MetricSnapshotCatalog.allItems().map { it.id }.toSet().size)
        for (cat in MetricSnapshotCategory.entries) {
            assertEquals(cat, MetricSnapshotCategory.entries.first { it.id == cat.id })
            if (cat != MetricSnapshotCategory.LABS) assertTrue(cat.id, MetricSnapshotCatalog.items(cat).isNotEmpty())
        }
        // A series-backed item always names a source; a derived item never does.
        for (i in MetricSnapshotCatalog.allItems()) assertEquals(i.id, i.seriesKey != null, i.sources.isNotEmpty())
        assertNotNull(MetricSnapshotCatalog.allItems().firstOrNull { it.id == "body_fat" })
        assertNotNull(MetricSnapshotCatalog.allItems().firstOrNull { it.id == "lean_mass" })
        assertEquals(13, MetricSnapshotCategory.entries.size)
    }

    @Test fun emptySnapshotHasEverySectionAndDashes() {
        val snap = MetricSnapshotBuilder.build("2026-01-01", "2026-01-01", emptyMap())
        assertTrue(snap.isEmpty)
        assertEquals(13, snap.sections.size)
        assertEquals("-", snap.row("body_fat")!!.text)
    }
}
