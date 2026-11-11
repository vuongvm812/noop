package com.noop.ui

import com.noop.analytics.MetricSnapshotBuilder
import com.noop.analytics.MetricSnapshotCatalog
import com.noop.analytics.MetricSnapshotCategory
import com.noop.analytics.MetricSnapshotRow
import com.noop.data.WorkoutRow
import com.noop.ui.MetricSnapshotLoader.WorkoutInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure half of [MetricSnapshotLoader] and the Daily summary's display rules. The Swift twin pins the same literals. */
class MetricSnapshotLoaderTest {

    @Test fun workoutDayAggregates() {
        val a = WorkoutInput(
            "2026-06-01", 60.0, energyKcal = 500.0, distanceKm = 10.0, avgHr = 140.0, maxHr = 170.0, effort = 55.0,
            zonePercents = listOf(10.0, 20.0, 30.0, 40.0, 0.0), avgPowerW = 200.0, avgCadence = 80.0,
            altitudeGainM = 100.0, maxAltitudeM = 300.0, aerobicTe = 3.2,
        )
        val b = WorkoutInput("2026-06-01", 30.0, energyKcal = 200.0, avgHr = 120.0, maxHr = 150.0, effort = 40.0, anaerobicTe = 1.5)
        val d = MetricSnapshotLoader.workoutDay(listOf(a, b))
        assertEquals(2.0, d["workout_count"]!!, 0.0)
        assertEquals(90.0, d["workout_duration_min"]!!, 1e-9)
        assertEquals(700.0, d["workout_energy_kcal"]!!, 1e-9)
        assertEquals(10.0, d["workout_distance_km"]!!, 1e-9)
        assertEquals((140.0 * 60 + 120.0 * 30) / 90.0, d["workout_avg_hr"]!!, 1e-9)
        assertEquals(170.0, d["workout_max_hr"]!!, 0.0)
        assertEquals(55.0, d["workout_effort"]!!, 0.0)
        assertEquals(6.0, d["workout_zone1_min"]!!, 1e-9)
        assertEquals(24.0, d["workout_zone4_min"]!!, 1e-9)
        assertEquals(0.0, d["workout_zone5_min"]!!, 1e-9)
        assertEquals(200.0, d["workout_avg_power_w"]!!, 1e-9)
        assertEquals(3.2, d["workout_aerobic_te"]!!, 0.0)
        assertEquals(1.5, d["workout_anaerobic_te"]!!, 0.0)
        assertEquals(100.0, d["workout_altitude_gain_m"]!!, 0.0)
        // No workout carried a reading for these: absent, never 0.
        assertNull(MetricSnapshotLoader.workoutDay(listOf(b))["workout_avg_power_w"])
        assertNull(MetricSnapshotLoader.workoutDay(listOf(b))["workout_zone1_min"])
    }

    @Test fun nonPositiveDurationContributesNoZoneMinutesAndClampsToZero() {
        val zones = listOf(10.0, 20.0, 30.0, 40.0, 0.0)
        val neg = WorkoutInput("2026-06-01", -5.0, zonePercents = zones, avgHr = 100.0)
        val ok = WorkoutInput("2026-06-01", 30.0, zonePercents = zones, avgHr = 140.0)
        val d = MetricSnapshotLoader.workoutDay(listOf(neg, ok))
        assertEquals(30.0, d["workout_duration_min"]!!, 1e-9)
        assertEquals(3.0, d["workout_zone1_min"]!!, 1e-9)
        assertEquals(140.0, d["workout_avg_hr"]!!, 1e-9)
        assertNull(MetricSnapshotLoader.workoutDay(listOf(neg))["workout_zone1_min"])
        assertEquals(0.0, MetricSnapshotLoader.workoutDay(listOf(neg))["workout_duration_min"]!!, 0.0)
        val row = WorkoutRow("my-whoop", 5_000, 1_000, "Run", "my-whoop", durationS = null)
        assertEquals(0.0, MetricSnapshotLoader.workoutInput(row, "2026-06-01", emptyList(), emptyList(), emptyList(), null, null).durationMin, 0.0)
    }

    @Test fun safeReadFallsBackButKeepsCancellation() {
        assertEquals(7, kotlinx.coroutines.runBlocking { safeRead(7) { error("boom") } })
        var cancelled = false
        try {
            kotlinx.coroutines.runBlocking { safeRead(0) { throw kotlinx.coroutines.CancellationException("c") } }
        } catch (e: kotlinx.coroutines.CancellationException) { cancelled = true }
        assertTrue(cancelled)
    }

    @Test fun workoutDaysGroupByDayAndFeedTheBuilder() {
        val days = MetricSnapshotLoader.workoutDays(
            listOf(WorkoutInput("2026-06-01", 30.0), WorkoutInput("2026-06-02", 45.0), WorkoutInput("2026-06-02", 15.0)),
        )
        val values = mapOf(
            "workout_count" to days.mapValues { it.value["workout_count"]!! },
            "workout_duration_min" to days.mapValues { it.value["workout_duration_min"]!! },
        )
        val snap = MetricSnapshotBuilder.build("2026-06-02", "2026-06-02", values)
        assertEquals("2", snap.row("workout_count")!!.text)
        assertEquals("60 min", snap.row("workout_duration_min")!!.text)
    }

    @Test fun workoutInputReadsTheRowAndSamples() {
        val row = WorkoutRow(
            "my-whoop", 1_000, 4_600, "Run", "my-whoop", durationS = null, energyKcal = 300.0, avgHr = 150,
            maxHr = 180, strain = 61.0, distanceM = 8_000.0, zonesJSON = "{\"zone1\":50,\"zone2\":50}",
        )
        val i = MetricSnapshotLoader.workoutInput(row, "2026-06-01", listOf(100.0, 200.0), emptyList(), listOf(10.0, 15.0, 12.0, 20.0), 3.0, null)
        assertEquals(60.0, i.durationMin, 1e-9)
        assertEquals(8.0, i.distanceKm!!, 1e-9)
        assertEquals(150.0, i.avgPowerW!!, 1e-9)
        assertNull(i.avgCadence)
        assertEquals(13.0, i.altitudeGainM!!, 1e-9)
        assertEquals(20.0, i.maxAltitudeM!!, 0.0)
        assertEquals(listOf(50.0, 50.0, 0.0, 0.0, 0.0), i.zonePercents)
    }

    @Test fun altitudeGainNeedsTwoPoints() {
        assertNull(MetricSnapshotLoader.altitudeGain(listOf(5.0)))
        assertEquals(0.0, MetricSnapshotLoader.altitudeGain(listOf(9.0, 5.0))!!, 0.0)
    }

    @Test fun cycleDayCountsFromTheLatestStartWithinASaneSpan() {
        val starts = listOf("2026-05-01", "2026-05-29")
        assertEquals(1, MetricSnapshotLoader.cycleDay(starts, "2026-05-29"))
        assertEquals(6, MetricSnapshotLoader.cycleDay(starts, "2026-06-03"))
        assertEquals(28, MetricSnapshotLoader.cycleDay(starts, "2026-05-28"))
        assertNull(MetricSnapshotLoader.cycleDay(starts, "2026-04-30"))
        assertNull(MetricSnapshotLoader.cycleDay(starts, "2026-09-01"))
        assertNull(MetricSnapshotLoader.cycleDay(emptyList(), "2026-06-03"))
    }

    @Test fun hydrationDropsEmptyDays() {
        assertEquals(mapOf("2026-06-01" to 1500.0), MetricSnapshotLoader.hydrationDays(mapOf("2026-06-01" to 1500.0, "2026-06-02" to 0.0)))
    }

    @Test fun noteText() {
        assertEquals("Yes", MetricSnapshotLoader.journalText(true, null))
        assertEquals("No", MetricSnapshotLoader.journalText(false, null))
        assertEquals("Yes (2)", MetricSnapshotLoader.journalText(true, 2.0))
        assertEquals("Yes (2.5)", MetricSnapshotLoader.journalText(true, 2.5))
        assertEquals("2.9 mmol/L", MetricSnapshotLoader.labText(2.94, null, "mmol/L", 1))
        assertEquals("Positive", MetricSnapshotLoader.labText(null, "Positive", "", 1))
    }

    @Test fun displayTextFollowsUnitSettings() {
        fun row(id: String, v: Double?) = MetricSnapshotRow(MetricSnapshotCatalog.allItems().first { it.id == id }, v, if (v == null) 0 else 1)
        fun text(id: String, v: Double?, sys: UnitSystem = UnitSystem.METRIC, t: TemperatureUnit = TemperatureUnit.CELSIUS,
                 e: EffortScale = EffortScale.HUNDRED) = snapshotDisplayText(row(id, v), sys, t, e)
        assertEquals("-", text("body_fat", null))
        assertEquals("18.3 %", text("body_fat", 18.25))
        assertEquals("80.0 kg", text("weight", 80.0))
        assertEquals("176.4 lb", text("lean_mass", 80.0, UnitSystem.IMPERIAL))
        assertEquals("63 %", text("recovery", 62.6))
        assertEquals("${UnitFormatter.effortDisplay(48.0, EffortScale.WHOOP)} /21", text("strain", 48.0, e = EffortScale.WHOOP))
        assertEquals("48.0 /100", text("strain", 48.0))
        assertEquals("36.6 °C", text("watch_skin_temp_c", 36.6))
        assertEquals("97.9 °F", text("watch_skin_temp_c", 36.6, t = TemperatureUnit.FAHRENHEIT))
    }

    @Test fun dayPickerNeverPassesToday() {
        assertEquals("2026-06-02", shiftSummaryDay("2026-06-03", -1, "2026-06-10"))
        assertEquals("2026-06-10", shiftSummaryDay("2026-06-10", 1, "2026-06-10"))
        assertEquals("2026-07-01", shiftSummaryDay("2026-06-30", 1, "2026-07-05"))
    }

    @Test fun notificationDayExtraIsValidated() {
        assertEquals("2026-06-03", NotificationRoute.validDayOrNull("2026-06-03"))
        assertNull(NotificationRoute.validDayOrNull("not-a-day"))
        assertNull(NotificationRoute.validDayOrNull(null))
    }

    @Test fun everyCatalogItemAndCategoryHasItsOwnString() {
        for (i in MetricSnapshotCatalog.allItems()) assertNotEquals(i.id, com.noop.R.string.nav_daily_summary, snapshotItemLabelRes(i.id))
        for (c in MetricSnapshotCategory.entries) assertTrue(c.id, c.labelRes() != 0)
    }
}
