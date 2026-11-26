package com.noop.ui

import com.noop.analytics.DayOrdinal
import com.noop.data.DailyMetric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId

/**
 * The calendar side of Tomorrow's Charge ([TomorrowForecastResolver]): which evening it is, the window, the
 * typical wake and the alarm override, in a fixed zone. Twin of Swift `TomorrowForecastResolverTests`; both
 * pin the same epoch literals (Europe/Berlin, March 2026, no DST).
 */
class TomorrowForecastResolverTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val nowEvening = 1_774_033_200L   // 2026-03-20 20:00
    private val nowSmallHours = 1_774_053_000L  // 2026-03-21 01:30
    private val nowMorning = 1_774_065_600L   // 2026-03-21 05:00
    private val nowLate = 1_774_046_700L      // 2026-03-20 23:45

    /** Three nights waking 06:40, 07:10, 06:50, and a nap. */
    private val pastNights = listOf(
        1_773_784_800.0 to 1_773_812_400.0, 1_773_873_000.0 to 1_773_900_600.0,
        1_773_958_200.0 to 1_773_985_800.0, 1_774_011_600.0 to 1_774_015_200.0,
    )

    /** Tonight's sleep, 23:15 → 06:45. */
    private val tonightSleep = listOf(1_774_044_900.0 to 1_774_071_900.0)

    private fun rows(): List<DailyMetric> = (0 until 20).map { i ->
        DailyMetric(
            deviceId = "my-whoop",
            day = DayOrdinal.key(DayOrdinal.ordinal("2026-03-01")!! + i),
            totalSleepMin = 420.0 + (i % 3) * 10.0,
            efficiency = 0.9,
            recovery = (55 + i % 5).toDouble(),
            strain = 50.0,
        )
    }

    @Test
    fun eveningAndTargetDayFollowTheFourAmRollover() {
        for ((now, evening, target) in listOf(
            Triple(nowEvening, "2026-03-20", "2026-03-21"),
            Triple(nowSmallHours, "2026-03-20", "2026-03-21"),
            Triple(nowMorning, "2026-03-21", "2026-03-22"),
        )) {
            val d = TomorrowForecastResolver.days(now, zone)
            assertEquals(evening, d.first.toString())
            assertEquals(target, d.second.toString())
        }
    }

    @Test
    fun windowIsNoonToFourWithAnElevenToTwoSlider() {
        val (evening, target) = TomorrowForecastResolver.days(nowEvening, zone)
        val w = TomorrowForecastResolver.window(evening, target, 390.0, zone)
        assertEquals("2026-03-20", w.eveningDay)
        assertEquals("2026-03-21", w.targetDay)
        assertEquals(1_774_004_400.0, w.openTs, 0.0)
        assertEquals(1_774_062_000.0, w.closeTs, 0.0)
        assertEquals(1_774_036_800.0, w.earliestTs, 0.0)
        assertEquals(1_774_054_800.0, w.latestTs, 0.0)
        assertEquals(1_774_071_000.0, w.wakeTs, 0.0)
        assertEquals(1_774_072_800.0, TomorrowForecastResolver.window(evening, target, null, zone).wakeTs, 0.0)
    }

    @Test
    fun typicalWakeIsTheMedianOfMainNightsThatEnded() {
        assertEquals(410.0, TomorrowForecastResolver.typicalWakeMinute(pastNights + tonightSleep, nowEvening, zone)!!, 0.0)
    }

    @Test
    fun alarmForTheTargetWeekdayWinsOverTheTypicalWake() {
        val asked = ArrayList<Int>()
        val withAlarm = TomorrowForecastResolver.resolve(
            rows(), pastNights, nowEvening, zone, { asked.add(it); if (it == 7) 390.0 else null }, null, null,
        )
        assertEquals(listOf(7), asked)   // 2026-03-21 is a Saturday
        assertEquals(1_774_071_000.0, withAlarm!!.plan.wakeTs, 0.0)
        val typical = TomorrowForecastResolver.resolve(rows(), pastNights, nowEvening, zone, { null }, null, null)
        assertEquals(1_774_072_200.0, typical!!.plan.wakeTs, 0.0)
    }

    @Test
    fun openInTheEveningClosedOnceSleepStartsAndInTheMorning() {
        fun open(now: Long, spans: List<Pair<Double, Double>>): Boolean? =
            TomorrowForecastResolver.resolve(rows(), spans, now, zone, { null }, null, null)?.isOpen
        assertEquals(true, open(nowEvening, pastNights))
        assertEquals(false, open(nowLate, pastNights + tonightSleep))   // frozen: sleep started 23:15
        assertEquals(false, open(nowMorning, pastNights))              // next evening not open yet
    }

    @Test
    fun alarmWakeSourcePriorityIsPhoneThenStrapThenWindDown() {
        assertEquals(390.0, TomorrowForecastResolver.alarmWakeMinute(390, 420, 450)!!, 0.0)
        assertEquals(390.0, TomorrowForecastResolver.alarmWakeMinute(390, null, null)!!, 0.0)
        assertEquals(420.0, TomorrowForecastResolver.alarmWakeMinute(null, 420, 450)!!, 0.0)
        assertEquals(420.0, TomorrowForecastResolver.alarmWakeMinute(null, 420, null)!!, 0.0)
        assertEquals(450.0, TomorrowForecastResolver.alarmWakeMinute(null, null, 450)!!, 0.0)
        assertNull(TomorrowForecastResolver.alarmWakeMinute(null, null, null))
    }

    /**
     * The freeze can only see sleep that has been synced. At 01:30 with tonight's span not yet in the store
     * the forecast is still open (and may still be overwritten); once the 23:15 onset arrives it closes.
     */
    @Test
    fun freezeNeedsTonightsSleepToHaveSynced() {
        fun open(spans: List<Pair<Double, Double>>): Boolean? =
            TomorrowForecastResolver.resolve(rows(), spans, nowSmallHours, zone, { null }, null, null)?.isOpen
        assertEquals(true, open(pastNights))
        assertEquals(false, open(pastNights + tonightSleep))
    }
}
