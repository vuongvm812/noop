import XCTest
import StrandAnalytics
import WhoopStore
@testable import Strand

/// The calendar side of Tomorrow's Charge (`TomorrowForecastResolver`): which evening it is, the window,
/// the typical wake and the alarm override, in a fixed zone. Twin of the Kotlin
/// `TomorrowForecastResolverTest`; both pin the same epoch literals (Europe/Berlin, March 2026, no DST).
final class TomorrowForecastResolverTests: XCTestCase {
    private var cal: Calendar {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "Europe/Berlin")!
        return c
    }
    private func at(_ ts: Double) -> Date { Date(timeIntervalSince1970: ts) }

    private let nowEvening = 1_774_033_200.0   // 2026-03-20 20:00
    private let nowSmallHours = 1_774_053_000.0  // 2026-03-21 01:30
    private let nowMorning = 1_774_065_600.0   // 2026-03-21 05:00
    private let nowLate = 1_774_046_700.0      // 2026-03-20 23:45

    /// Three nights waking 06:40, 07:10, 06:50, a nap, and tonight's sleep (23:15 → 06:45).
    private let pastNights: [(startTs: Double, endTs: Double)] = [
        (1_773_784_800, 1_773_812_400), (1_773_873_000, 1_773_900_600), (1_773_958_200, 1_773_985_800),
        (1_774_011_600, 1_774_015_200),
    ]
    private let tonightSleep: [(startTs: Double, endTs: Double)] = [(1_774_044_900, 1_774_071_900)]

    private func rows() -> [DailyMetric] {
        (0..<20).map { i in
            let day = DayOrdinal.key(DayOrdinal.ordinal("2026-03-01")! + i)
            return DailyMetric(day: day, totalSleepMin: 420 + Double(i % 3) * 10, efficiency: 0.9, deepMin: nil,
                               remMin: nil, lightMin: nil, disturbances: nil, restingHr: nil, avgHrv: nil,
                               recovery: Double(55 + i % 5), strain: 50, exerciseCount: nil)
        }
    }

    func testEveningAndTargetDayFollowTheFourAmRollover() {
        for (now, evening, target) in [(nowEvening, "2026-03-20", "2026-03-21"),
                                       (nowSmallHours, "2026-03-20", "2026-03-21"),
                                       (nowMorning, "2026-03-21", "2026-03-22")] {
            let d = TomorrowForecastResolver.days(now: at(now), calendar: cal)
            XCTAssertEqual(TomorrowForecastResolver.dayKey(d.evening, calendar: cal), evening)
            XCTAssertEqual(TomorrowForecastResolver.dayKey(d.target, calendar: cal), target)
        }
    }

    func testWindowIsNoonToFourWithAnElevenToTwoSlider() {
        let d = TomorrowForecastResolver.days(now: at(nowEvening), calendar: cal)
        let w = TomorrowForecastResolver.window(evening: d.evening, target: d.target, wakeMinute: 390, calendar: cal)
        XCTAssertEqual(w.eveningDay, "2026-03-20")
        XCTAssertEqual(w.targetDay, "2026-03-21")
        XCTAssertEqual(w.openTs, 1_774_004_400)
        XCTAssertEqual(w.closeTs, 1_774_062_000)
        XCTAssertEqual(w.earliestTs, 1_774_036_800)
        XCTAssertEqual(w.latestTs, 1_774_054_800)
        XCTAssertEqual(w.wakeTs, 1_774_071_000)
        let noWake = TomorrowForecastResolver.window(evening: d.evening, target: d.target, wakeMinute: nil, calendar: cal)
        XCTAssertEqual(noWake.wakeTs, 1_774_072_800)   // 07:00 default
    }

    func testTypicalWakeIsTheMedianOfMainNightsThatEnded() {
        let m = TomorrowForecastResolver.typicalWakeMinute(spans: pastNights + tonightSleep, now: at(nowEvening),
                                                           calendar: cal)
        XCTAssertEqual(m, 410)   // 06:40, 07:10, 06:50 → 06:50; the nap and tonight's unfinished night are out
    }

    func testAlarmForTheTargetWeekdayWinsOverTheTypicalWake() {
        var asked: [Int] = []
        let withAlarm = TomorrowForecastResolver.resolve(
            days: rows(), spans: pastNights, now: at(nowEvening), calendar: cal,
            alarmWakeMinute: { asked.append($0); return $0 == 7 ? 390 : nil }, selectedTs: nil, age: nil)
        XCTAssertEqual(asked, [7])   // 2026-03-21 is a Saturday
        XCTAssertEqual(withAlarm?.plan.wakeTs, 1_774_071_000)
        let typical = TomorrowForecastResolver.resolve(
            days: rows(), spans: pastNights, now: at(nowEvening), calendar: cal,
            alarmWakeMinute: { _ in nil }, selectedTs: nil, age: nil)
        XCTAssertEqual(typical?.plan.wakeTs, 1_774_072_200)
    }

    func testOpenInTheEveningClosedOnceSleepStartsAndInTheMorning() {
        func open(_ now: Double, _ spans: [(startTs: Double, endTs: Double)]) -> Bool? {
            TomorrowForecastResolver.resolve(days: rows(), spans: spans, now: at(now), calendar: cal,
                                             alarmWakeMinute: { _ in nil }, selectedTs: nil, age: nil)?.isOpen
        }
        XCTAssertEqual(open(nowEvening, pastNights), true)
        XCTAssertEqual(open(nowLate, pastNights + tonightSleep), false)   // frozen: sleep started 23:15
        XCTAssertEqual(open(nowMorning, pastNights), false)              // next evening not open yet
    }

    /// A small-hours check of the freeze as it happens in practice: the gate only sees sleep that has
    /// synced and lasts a main-sleep length. At 01:30 with only past nights (tonight's span not synced yet)
    /// the forecast is still open; a running span synced at 23:15 → 01:30 is under three hours, so it is
    /// still open; once tonight's main span is present it is closed.
    func testSmallHoursFreezeNeedsTonightsMainSpanToHaveSynced() {
        func open(_ spans: [(startTs: Double, endTs: Double)]) -> Bool? {
            TomorrowForecastResolver.resolve(days: rows(), spans: spans, now: at(nowSmallHours), calendar: cal,
                                             alarmWakeMinute: { _ in nil }, selectedTs: nil, age: nil)?.isOpen
        }
        XCTAssertEqual(open(pastNights), true)
        XCTAssertEqual(open(pastNights + [(startTs: 1_774_044_900, endTs: nowSmallHours)]), true)
        XCTAssertEqual(open(pastNights + tonightSleep), false)
    }

    /// One priority rule on both platforms (Kotlin twin `AppViewModel.forecastAlarmWakeMinute`, minus the
    /// Android-only phone alarm): the strap smart alarm armed for the weekday, else the wind-down wake,
    /// else nil (typical wake).
    func testAlarmWakeMinutePrefersStrapAlarmThenWindDownThenNothing() {
        func wake(_ enabled: Bool, _ weekdays: Set<Int>, _ windDown: Int?) -> Double? {
            TomorrowForecastResolver.alarmWakeMinute(weekday: 7, strapEnabled: enabled, strapMinutes: 375,
                                                     strapWeekdays: weekdays, windDown: windDown)
        }
        XCTAssertEqual(wake(true, [], 420), 375)        // empty weekday set = every day
        XCTAssertEqual(wake(true, [1, 7], 420), 375)    // armed for Saturday
        XCTAssertEqual(wake(true, [2, 3], 420), 420)    // not armed that day → wind-down
        XCTAssertEqual(wake(false, [], 420), 420)       // strap alarm off → wind-down
        XCTAssertNil(wake(true, [2], nil))              // neither → typical wake
        XCTAssertNil(wake(false, [], nil))
    }

    /// Day keys are Gregorian whatever the system calendar is, and the Repository's calendar is Gregorian.
    func testForecastCalendarIsGregorianSoDayKeysMatchStoredDays() {
        XCTAssertEqual(TomorrowForecastResolver.forecastCalendar().identifier, .gregorian)
        XCTAssertEqual(TomorrowForecastResolver.forecastCalendar().timeZone, TimeZone.current)
        XCTAssertEqual(TomorrowForecastResolver.dayKey(at(nowEvening), calendar: cal), "2026-03-20")
        var buddhist = Calendar(identifier: .buddhist)
        buddhist.timeZone = cal.timeZone
        XCTAssertEqual(TomorrowForecastResolver.dayKey(at(nowEvening), calendar: buddhist), "2569-03-20")
    }

    /// The typical wake reads only nights that ended in the last `wakeHistoryDays` days.
    func testRecentSpansKeepOnlyTheWakeHistoryWindow() {
        let now = at(nowEvening)
        let old = (startTs: nowEvening - 30 * 86_400, endTs: nowEvening - 22 * 86_400)
        let kept = TomorrowForecastResolver.recentSpans(pastNights + [old], now: now)
        XCTAssertEqual(kept.map(\.endTs), pastNights.map(\.endTs))
    }
}
