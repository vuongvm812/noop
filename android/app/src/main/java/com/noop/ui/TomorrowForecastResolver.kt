package com.noop.ui

import com.noop.analytics.RecoveryForecaster
import com.noop.data.DailyMetric
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Tomorrow's Charge: the calendar side of the one resolver behind the Today card and the Intelligence card.
 *
 * [RecoveryForecaster.tonight] does the arithmetic; this supplies what needs the calendar: which evening
 * it is, tomorrow's wake time, the slider range. [AppViewModel.tomorrowForecast] feeds it the stored days,
 * the sleep spans, the alarm wake and the stored slider selection, and both cards read that one flow, so
 * they cannot show two different forecasts for the same night. Pure over an injected [ZoneId].
 * Swift twin: `TomorrowForecastResolver` (Strand/Data/TomorrowForecast.swift).
 */
object TomorrowForecastResolver {
    /** The bedtime picked on the Today slider, as unix seconds. Swift twin: `TomorrowForecastPrefs.bedtimeKey`. */
    const val KEY_BEDTIME = "tomorrowCharge.bedtimeTs"

    /** Recent nights the typical wake time is read from (the resolver's sleep read window). */
    const val wakeHistoryDays = 21

    /** The evening day (the logical day of [nowSec], rolling at 04:00) and the target day after it.
     *  Swift twin: `TomorrowForecastResolver.days`. */
    fun days(nowSec: Long, zone: ZoneId): Pair<LocalDate, LocalDate> {
        val evening = logicalDay(Instant.ofEpochSecond(nowSec).atZone(zone))
        return evening to evening.plusDays(1)
    }

    /** Typical wake minute-of-day from main-sleep spans that ended before [nowSec] (oldest→newest by end).
     *  Swift twin: `TomorrowForecastResolver.typicalWakeMinute`. */
    fun typicalWakeMinute(spans: List<Pair<Double, Double>>, nowSec: Long, zone: ZoneId): Double? {
        val minutes = spans
            .filter { (start, end) -> end - start >= RecoveryForecaster.mainSleepMinSec && end <= nowSec }
            .map { it.second }
            .sorted()
            .map { ts ->
                val t = Instant.ofEpochSecond(ts.toLong()).atZone(zone)
                (t.hour * 60 + t.minute).toDouble()
            }
        return RecoveryForecaster.typicalWakeMinute(minutes)
    }

    /**
     * Which alarm sets tomorrow's wake time, as a minute-of-day; each argument is that source's wake minute
     * for the target weekday, or null when the source does not apply that day. The order is fixed:
     * (1) the phone alarm armed for that weekday, (2) else the strap smart alarm armed for that weekday
     * (its per-day override, else its default time), (3) else, with only the wind-down reminder on, the
     * wake time the reminder is anchored to; null means none applies and the typical wake is used.
     */
    fun alarmWakeMinute(phone: Int?, strap: Int?, windDown: Int?): Double? =
        (phone ?: strap ?: windDown)?.toDouble()

    /** Unix seconds of [minuteOfDay] (rounded to a whole minute) on the local [day].
     *  Swift twin: `TomorrowForecastResolver.at`. */
    fun at(day: LocalDate, minuteOfDay: Double, zone: ZoneId): Double {
        val m = Math.round(minuteOfDay).toInt()
        return day.atTime(m / 60, m % 60).atZone(zone).toEpochSecond().toDouble()
    }

    /** Tonight's clock. [wakeMinute] is the alarm's minute-of-day for the target day, else the typical
     *  wake, else [RecoveryForecaster.defaultWakeMinute]. Swift twin: `TomorrowForecastResolver.window`. */
    fun window(evening: LocalDate, target: LocalDate, wakeMinute: Double?, zone: ZoneId): RecoveryForecaster.Window {
        val wake = wakeMinute ?: RecoveryForecaster.defaultWakeMinute
        return RecoveryForecaster.Window(
            eveningDay = evening.toString(),
            targetDay = target.toString(),
            openTs = at(evening, (RecoveryForecaster.openHour * 60).toDouble(), zone),
            closeTs = at(target, (RecoveryForecaster.closeHour * 60).toDouble(), zone),
            earliestTs = at(evening, (RecoveryForecaster.sliderStartHour * 60).toDouble(), zone),
            latestTs = at(target, (RecoveryForecaster.sliderEndHour * 60).toDouble(), zone),
            wakeTs = at(target, wake, zone),
        )
    }

    /**
     * Resolve tonight from stored days and sleep spans. [alarmWakeMinute] maps the target day's weekday
     * (`Calendar.DAY_OF_WEEK`, 1 = Sunday) to its alarm minute-of-day, or null when no alarm covers it.
     * Swift twin: `TomorrowForecastResolver.resolve`.
     */
    fun resolve(
        days: List<DailyMetric>,
        spans: List<Pair<Double, Double>>,
        nowSec: Long,
        zone: ZoneId,
        alarmWakeMinute: (Int) -> Double?,
        selectedTs: Double?,
        age: Int?,
    ): RecoveryForecaster.Tonight? {
        val (evening, target) = days(nowSec, zone)
        val weekday = target.dayOfWeek.value % 7 + 1
        val wake = alarmWakeMinute(weekday) ?: typicalWakeMinute(spans, nowSec, zone)
        val w = window(evening, target, wake, zone)
        val rows = days.map {
            RecoveryForecaster.DayRow(it.day, it.recovery, it.strain, it.totalSleepMin, it.efficiency)
        }
        return RecoveryForecaster.tonight(rows, w, age, nowSec.toDouble(), spans, selectedTs)
    }
}
