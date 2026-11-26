import Foundation
import StrandAnalytics
import WhoopStore

// MARK: - Tomorrow's Charge: the one resolver behind the Today card and the Intelligence card
//
// `RecoveryForecaster.tonight` does the arithmetic; this file supplies what needs the calendar, the alarm
// settings and the store: which evening it is, tomorrow's wake time, the slider range, the stored slider
// selection, the save of tonight's forecast and the accuracy read-back. Both cards call
// `Repository.tomorrowForecast`, so they cannot show two different forecasts for the same night.
// Kotlin twin: `TomorrowForecastResolver` (android/.../ui/TomorrowForecastResolver.kt) + `AppViewModel`.

/// The bedtime the user picked on the Today slider, as unix seconds. One value; a selection outside the
/// current night's slider range is ignored by the resolver, so yesterday's pick never leaks into tonight.
/// Kotlin twin: `TomorrowForecastResolver.KEY_BEDTIME`.
enum TomorrowForecastPrefs {
    static let bedtimeKey = "tomorrowCharge.bedtimeTs"

    /// The stored selection, or nil when none was made.
    static var selectedBedtime: Double? {
        let v = UserDefaults.standard.double(forKey: bedtimeKey)
        return v > 0 ? v : nil
    }
}

/// Calendar side of the resolver: pure over an injected `Calendar`, so the window is testable in any zone.
/// Kotlin twin: `TomorrowForecastResolver`.
enum TomorrowForecastResolver {
    /// Recent nights the typical wake time is read from (the resolver's sleep read window).
    static let wakeHistoryDays = 21

    /// The calendar every forecast day key and clock time is resolved in: Gregorian in the device's zone.
    /// `DailyMetric.day` is a Gregorian "yyyy-MM-dd" key, so `Calendar.current` (which follows a Buddhist
    /// or Japanese system calendar) would name a target day no stored row can ever match.
    static func forecastCalendar() -> Calendar {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone.current
        return c
    }

    /// The spans the resolver reads: those that ended within the last `wakeHistoryDays` days of `now`
    /// (a running night has an end at or near now, so it stays in). Kotlin twin: the
    /// `AppViewModel.forecastSleepSpans` read window.
    static func recentSpans(_ spans: [(startTs: Double, endTs: Double)],
                            now: Date) -> [(startTs: Double, endTs: Double)] {
        let from = now.timeIntervalSince1970 - Double(wakeHistoryDays) * 86_400
        return spans.filter { $0.endTs >= from }
    }

    /// Tomorrow's alarm wake minute-of-day for the target `weekday` (Calendar, 1 = Sunday), by one priority
    /// rule shared with Android: the strap smart alarm when it is armed for that weekday (an empty weekday
    /// set means every day), else the wind-down wake for that weekday when the reminder is on (`windDown`,
    /// nil when it is off), else nil so the resolver falls back to the typical wake and then 07:00. Android
    /// checks its phone alarm before all of these; Apple has none. Kotlin twin:
    /// `AppViewModel.forecastAlarmWakeMinute`.
    static func alarmWakeMinute(weekday: Int, strapEnabled: Bool, strapMinutes: Int, strapWeekdays: Set<Int>,
                                windDown: Int?) -> Double? {
        if strapEnabled && (strapWeekdays.isEmpty || strapWeekdays.contains(weekday)) {
            return Double(strapMinutes)
        }
        return windDown.map(Double.init)
    }

    /// The evening day (the logical day of `now`, rolling at 04:00) and the target day after it, as the
    /// start of each local day. Kotlin twin: `TomorrowForecastResolver.days`.
    static func days(now: Date, calendar: Calendar) -> (evening: Date, target: Date) {
        let shifted = now.addingTimeInterval(-Double(Repository.logicalDayRolloverHour) * 3600)
        let evening = calendar.startOfDay(for: shifted)
        let target = calendar.date(byAdding: .day, value: 1, to: evening) ?? evening.addingTimeInterval(86_400)
        return (evening, target)
    }

    /// Typical wake minute-of-day from main-sleep spans that ended before `now` (oldest→newest by end).
    /// Kotlin twin: `TomorrowForecastResolver.typicalWakeMinute`.
    static func typicalWakeMinute(spans: [(startTs: Double, endTs: Double)], now: Date,
                                  calendar: Calendar) -> Double? {
        let nowTs = now.timeIntervalSince1970
        let ends = spans
            .filter { $0.endTs - $0.startTs >= RecoveryForecaster.mainSleepMinSec && $0.endTs <= nowTs }
            .map(\.endTs)
            .sorted()
        let minutes = ends.map { ts -> Double in
            let c = calendar.dateComponents([.hour, .minute], from: Date(timeIntervalSince1970: ts))
            return Double((c.hour ?? 0) * 60 + (c.minute ?? 0))
        }
        return RecoveryForecaster.typicalWakeMinute(minutes)
    }

    /// "yyyy-MM-dd" for a local day in `calendar`'s zone.
    static func dayKey(_ date: Date, calendar: Calendar) -> String {
        let c = calendar.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0)
    }

    /// Unix seconds of `minuteOfDay` (rounded to a whole minute) on the local `day`.
    static func at(_ day: Date, minuteOfDay: Double, calendar: Calendar) -> Double {
        let m = Int(minuteOfDay.rounded())
        let d = calendar.date(bySettingHour: m / 60, minute: m % 60, second: 0, of: day) ?? day
        return d.timeIntervalSince1970
    }

    /// Tonight's clock. `wakeMinute` is the alarm's minute-of-day for the target day, else the typical
    /// wake, else `RecoveryForecaster.defaultWakeMinute`. Kotlin twin: `TomorrowForecastResolver.window`.
    static func window(evening: Date, target: Date, wakeMinute: Double?,
                       calendar: Calendar) -> RecoveryForecaster.Window {
        let wake = wakeMinute ?? RecoveryForecaster.defaultWakeMinute
        return RecoveryForecaster.Window(
            eveningDay: dayKey(evening, calendar: calendar),
            targetDay: dayKey(target, calendar: calendar),
            openTs: at(evening, minuteOfDay: Double(RecoveryForecaster.openHour * 60), calendar: calendar),
            closeTs: at(target, minuteOfDay: Double(RecoveryForecaster.closeHour * 60), calendar: calendar),
            earliestTs: at(evening, minuteOfDay: Double(RecoveryForecaster.sliderStartHour * 60), calendar: calendar),
            latestTs: at(target, minuteOfDay: Double(RecoveryForecaster.sliderEndHour * 60), calendar: calendar),
            wakeTs: at(target, minuteOfDay: wake, calendar: calendar))
    }

    /// Resolve tonight from stored days and sleep spans. `alarmWakeMinute` maps the target day's weekday
    /// (Calendar, 1 = Sunday) to its alarm minute-of-day, or nil when no alarm covers it.
    /// Kotlin twin: `TomorrowForecastResolver.resolve`.
    static func resolve(days: [DailyMetric], spans: [(startTs: Double, endTs: Double)], now: Date,
                        calendar: Calendar, alarmWakeMinute: (Int) -> Double?, selectedTs: Double?,
                        age: Int?) -> RecoveryForecaster.Tonight? {
        let (evening, target) = Self.days(now: now, calendar: calendar)
        let weekday = calendar.component(.weekday, from: target)
        let wake = alarmWakeMinute(weekday) ?? typicalWakeMinute(spans: spans, now: now, calendar: calendar)
        let w = window(evening: evening, target: target, wakeMinute: wake, calendar: calendar)
        let rows = days.map {
            RecoveryForecaster.DayRow(day: $0.day, recovery: $0.recovery, strain: $0.strain,
                                      sleepMin: $0.totalSleepMin, efficiency: $0.efficiency)
        }
        return RecoveryForecaster.tonight(rows: rows, window: w, age: age, nowTs: now.timeIntervalSince1970,
                                          spans: spans, selectedTs: selectedTs)
    }
}

extension Repository {

    /// Tonight's forecast from the loaded days and recent sleeps, tomorrow's alarm wake time (strap smart
    /// alarm, else wind-down, see `TomorrowForecastResolver.alarmWakeMinute`) and the stored slider
    /// selection. nil until the Charge baseline has enough nights. Both forecast cards call this with the
    /// same `behavior`, so they resolve one wake time. Kotlin twin: `AppViewModel.tomorrowForecast`.
    func tomorrowForecast(now: Date = Date(), age: Int?, behavior: BehaviorStore) -> RecoveryForecaster.Tonight? {
        let strapEnabled = behavior.smartAlarmEnabled
        let strapMinutes = behavior.smartAlarmMinutes
        let strapWeekdays = behavior.smartAlarmWeekdays
        let windDownOn = WindDownNudge.isEnabled
        return TomorrowForecastResolver.resolve(
            days: days, spans: TomorrowForecastResolver.recentSpans(forecastSpans, now: now), now: now,
            calendar: TomorrowForecastResolver.forecastCalendar(),
            alarmWakeMinute: { weekday in
                TomorrowForecastResolver.alarmWakeMinute(
                    weekday: weekday, strapEnabled: strapEnabled, strapMinutes: strapMinutes,
                    strapWeekdays: strapWeekdays,
                    windDown: windDownOn ? WindDownNudge.wakeMinutes(forWeekday: weekday) : nil)
            },
            selectedTs: TomorrowForecastPrefs.selectedBedtime, age: age)
    }

    /// The loaded sleeps as the forecaster's (start, end) spans.
    private var forecastSpans: [(startTs: Double, endTs: Double)] {
        sleeps.map { (startTs: Double($0.effectiveStartTs), endTs: Double($0.endTs)) }
    }

    /// Store tonight's headline forecast for the day it targets, under the computed source. Only while the
    /// forecast is open: once the night's sleep has started (or the window closed) the stored value is
    /// frozen and this does nothing. The gate is checked again at write time against the clock and the
    /// sleeps loaded then, so a save resolved before the night's sleep synced cannot overwrite a frozen
    /// value.
    func saveTomorrowForecast(_ t: RecoveryForecaster.Tonight) async {
        guard t.isOpen, let id = computedReadIds.first, let store = await storeHandle() else { return }
        let stillOpen = RecoveryForecaster.isOpen(
            nowTs: Date().timeIntervalSince1970, openTs: t.window.openTs, closeTs: t.window.closeTs,
            sleepStartTs: RecoveryForecaster.sleepStart(spans: forecastSpans, openTs: t.window.openTs))
        guard stillOpen else { return }
        let f = t.headline.forecast
        let day = t.window.targetDay
        _ = try? await store.upsertMetricSeries([
            MetricPoint(day: day, key: ForecastAccuracy.forecastKey, value: f.charge),
            MetricPoint(day: day, key: ForecastAccuracy.lowKey, value: f.low),
            MetricPoint(day: day, key: ForecastAccuracy.highKey, value: f.high),
        ], deviceId: id)
    }

    /// How the stored forecasts compare with the Charge then scored, over the last scored days; nil until
    /// `ForecastAccuracy.minPairs` days are scored.
    func forecastAccuracy(now: Date = Date()) async -> ForecastAccuracy.Summary? {
        guard let store = await storeHandle() else { return nil }
        let to = Self.localDayKey(now)
        let from = Self.localDayKey(now.addingTimeInterval(-60 * 86_400))
        /// Active computed id wins a day both ids hold: read the canonical id first, then overwrite.
        func read(_ key: String) async -> [String: Double] {
            var out: [String: Double] = [:]
            for id in computedReadIds.reversed() {
                let points = (try? await store.metricSeries(deviceId: id, key: key, from: from, to: to)) ?? []
                for p in points { out[p.day] = p.value }
            }
            return out
        }
        var actual: [String: Double] = [:]
        for d in days { if let r = d.recovery { actual[d.day] = r } }
        let pairs = ForecastAccuracy.pairs(forecast: await read(ForecastAccuracy.forecastKey),
                                           low: await read(ForecastAccuracy.lowKey),
                                           high: await read(ForecastAccuracy.highKey),
                                           actual: actual)
        return ForecastAccuracy.summary(pairs)
    }
}
