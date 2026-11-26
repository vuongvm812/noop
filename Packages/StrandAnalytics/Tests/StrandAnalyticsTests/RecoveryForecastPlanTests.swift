import XCTest
@testable import StrandAnalytics

/// Tonight's plan (bedtime → planned sleep → forecast), the one resolver behind the Today card and the
/// Intelligence card, and the forecast-accuracy summary. Every expected line is the stdout of these fixtures
/// run through `RecoveryForecast.swift` + `ForecastAccuracy.swift` compiled standalone with `swiftc -O`; the
/// Kotlin `RecoveryForecastPlanOracleTest` pins the same literals from the other side.
final class RecoveryForecastPlanTests: XCTestCase {
    private typealias RF = RecoveryForecaster

    private static func f(_ v: Double?) -> String { v.map { String(format: "%.9f", $0) } ?? "nil" }
    private static func fc(_ x: RecoveryForecast?) -> String {
        guard let x else { return "nil" }
        return "\(f(x.charge))/\(f(x.band))/\(f(x.baseline))/\(f(x.plannedSleepHours))/\(f(x.needHours))/\(x.nights)/\(x.confidence.rawValue)"
    }
    private static let E = 1_773_403_200.0
    private static let H = 3600.0

    private static func rows() -> [RF.DayRow] {
        var out: [RF.DayRow] = []
        for i in 0..<20 {
            let day = DayOrdinal.key(DayOrdinal.ordinal("2026-03-01")! + i)
            let rec: Double? = i % 6 == 5 ? nil : Double(50 + (i * 7) % 23)
            let strain: Double? = i % 9 == 4 ? nil : Double(40 + (i * 5) % 30) + 0.5
            let sleep: Double? = i % 8 == 6 ? nil : Double(380 + (i * 13) % 90)
            let eff: Double? = i % 4 == 3 ? nil : (i % 2 == 0 ? Double(85 + i % 7) : 0.8 + Double(i % 5) * 0.03)
            out.append(RF.DayRow(day: day, recovery: rec, strain: strain, sleepMin: sleep, efficiency: eff))
        }
        out.append(RF.DayRow(day: "2026-03-21", recovery: 99, strain: 99, sleepMin: 600, efficiency: 0.99))
        out.append(RF.DayRow(day: "2026-03-25", recovery: 1, strain: 1, sleepMin: 60, efficiency: 0.5))
        return out.reversed()
    }

    private static let window = RF.Window(eveningDay: "2026-03-20", targetDay: "2026-03-21", openTs: E, closeTs: E + 16 * H,
                           earliestTs: E + 9 * H, latestTs: E + 14 * H, wakeTs: E + 18.5 * H)

    private static func tn(_ t: RF.Tonight?) -> String {
        guard let t else { return "nil" }
        let p = t.plan
        let head = "\(f(t.suggestedTs - E)) \(f(t.anchorTs - E)) \(f(t.sliderTs - E)) \(f(t.sliderStartTs - E)) \(t.isOpen ? 1 : 0) " +
            "\(f(p.needHours)) \(p.needNights) \(f(p.efficiency)) \(f(p.todayEffort)) \(p.recentCharge.count) \(p.recentEffort.count)"
        let opts = t.options.map { "\(f($0.bedtimeTs - E))=\(fc($0.forecast))" }.joined(separator: " ")
        return head + " | " + opts
    }

    private static func oracleLines() -> [String] {
        var out: [String] = []
        out.append("PLANNED " + [
            RF.plannedSleep(bedtimeTs: 0, wakeTs: 28_800, efficiency: 0.9),
            RF.plannedSleep(bedtimeTs: 0, wakeTs: 28_800, efficiency: 0.3),
            RF.plannedSleep(bedtimeTs: 0, wakeTs: 28_800, efficiency: 1.2),
            RF.plannedSleep(bedtimeTs: 30_000, wakeTs: 28_800, efficiency: 0.9),
            RF.plannedSleep(bedtimeTs: 1000.5, wakeTs: 30_000.25, efficiency: 0.875),
            RF.plannedSleep(bedtimeTs: 0, wakeTs: 28_800, efficiency: .nan),
        ].map { f($0) }.joined(separator: " "))

        out.append("SUGGEST " + [
            RF.suggestedBedtime(wakeTs: E + 18.5 * H, needHours: 8, efficiency: 0.9),
            RF.suggestedBedtime(wakeTs: E + 18.5 * H, needHours: 8.75, efficiency: 0.88),
            RF.suggestedBedtime(wakeTs: E + 18.5 * H, needHours: 0, efficiency: 0.9),
            RF.suggestedBedtime(wakeTs: E + 18.5 * H, needHours: -1, efficiency: 0.9),
            RF.suggestedBedtime(wakeTs: E + 18.5 * H, needHours: 7.5, efficiency: 0.2),
            RF.suggestedBedtime(wakeTs: E + 18.5 * H + 123, needHours: 9.333333, efficiency: 0.93),
            RF.suggestedBedtime(wakeTs: E + 18.5 * H, needHours: 8, efficiency: .infinity),
        ].map { f($0 - E) }.joined(separator: " "))

        out.append("SNAP " + [
            RF.snapBedtime(E + 10 * H + 7 * 60, earliest: E + 9 * H, latest: E + 14 * H),
            RF.snapBedtime(E + 10 * H + 8 * 60, earliest: E + 9 * H, latest: E + 14 * H),
            RF.snapBedtime(E + 10 * H + 450, earliest: E + 9 * H, latest: E + 14 * H),
            RF.snapBedtime(E + 5 * H, earliest: E + 9 * H, latest: E + 14 * H),
            RF.snapBedtime(E + 20 * H, earliest: E + 9 * H, latest: E + 14 * H),
        ].map { f($0 - E) }.joined(separator: " "))

        out.append("MEDIAN " + [RF.median([]), RF.median([3]), RF.median([4, 1]), RF.median([5, 1, 3]), RF.median([2, 8, 4, 6])]
            .map { f($0) }.joined(separator: " "))
        out.append("EFFFRAC " + [nil, .nan, 0, -0.5, 0.42, 1, 1.0001, 88, 100, 100.5]
            .map { f(RF.efficiencyFraction($0)) }.joined(separator: " "))
        out.append("EFFTYP " + [
            RF.typicalEfficiency([]),
            RF.typicalEfficiency([nil, .nan]),
            RF.typicalEfficiency([90, 0.8, nil, 85]),
            RF.typicalEfficiency([0.2, 0.3, 0.4]),
            RF.typicalEfficiency((0..<20).map { Double(70 + $0) }),
        ].map { f($0) }.joined(separator: " "))
        out.append("WAKE " + [
            RF.typicalWakeMinute([]),
            RF.typicalWakeMinute([420]),
            RF.typicalWakeMinute([400, .infinity, 430]),
            RF.typicalWakeMinute((0..<20).map { Double(360 + $0 * 5) }),
        ].map { f($0) }.joined(separator: " "))

        let needNights: [SleepNeed.Night] = [
            .init(day: "2026-03-01", mainMin: 420, strain: 55),
            .init(day: "2026-03-02", mainMin: 0, strain: 40),
            .init(day: "2026-03-03", mainMin: 465, strain: 72),
            .init(day: "2026-03-03", mainMin: 470, strain: 80),
            .init(day: "2025-11-01", mainMin: 500),
            .init(day: "2026-03-04", mainMin: nil, strain: 90),
            .init(day: "2026-03-05", mainMin: 395, strain: 61),
            .init(day: "2026-03-06", mainMin: 510, strain: 35),
            .init(day: "2026-03-07", mainMin: 430, strain: 66),
            .init(day: "2026-03-08", mainMin: 445, strain: 81),
            .init(day: "2026-03-09", mainMin: 410, strain: 58),
            .init(day: "2026-03-10", mainMin: 400, strain: 77),
            .init(day: "bad-day", mainMin: 400),
        ]
        let n1 = RF.need(nights: needNights, day: "2026-03-11", age: nil)
        let n2 = RF.need(nights: needNights, day: "2026-03-11", age: 16)
        let n3 = RF.need(nights: needNights, day: "2026-03-04", age: 30)
        let n4 = RF.need(nights: [], day: "2026-03-11", age: nil)
        let n5 = RF.need(nights: needNights, day: "nope", age: nil)
        out.append("NEED " + [n1, n2, n3, n4, n5].map { "\(f($0.hours))/\($0.nights)" }.joined(separator: " "))

        let plan = RF.Plan(recentCharge: [62, 58, 71, 66, 60, 55, 68, 72, 64, 59, 61], recentEffort: [45, 52, 60, 38, 70],
                           todayEffort: 66, needHours: 8.4, needNights: 9, wakeTs: E + 18.5 * H, efficiency: 0.9)
        let thinPlan = RF.Plan(recentCharge: [62, 58, 71, 66, 60], recentEffort: [], todayEffort: nil, needHours: 8,
                               needNights: 2, wakeTs: E + 18.5 * H, efficiency: 0.85)
        let coldPlan = RF.Plan(recentCharge: [62, 58, 71, 66], recentEffort: [], todayEffort: nil, needHours: 8,
                               needNights: 0, wakeTs: E + 18.5 * H, efficiency: 0.85)
        out.append("OPTIONS " + RF.options(plan: plan, anchorTs: E + 10.75 * H)
            .map { "\(f($0.bedtimeTs - E))=\(fc($0.forecast))" }.joined(separator: " "))
        out.append("OPTIONS2 " + RF.options(plan: thinPlan, anchorTs: E + 18 * H)
            .map { "\(f($0.bedtimeTs - E))=\(fc($0.forecast))" }.joined(separator: " "))
        out.append("OPTIONS3 \(RF.options(plan: coldPlan, anchorTs: E + 10 * H).count)")
        out.append("PLANFC " + fc(RF.forecastAt(plan: plan, bedtimeTs: E + 9 * H)) + " " + fc(RF.forecastAt(plan: coldPlan, bedtimeTs: E)))

        let lastNight: [(startTs: Double, endTs: Double)] = [(E - 14 * H, E - 6 * H)]
        let nap: [(startTs: Double, endTs: Double)] = [(E + 2 * H, E + 2.75 * H)]
        let night: [(startTs: Double, endTs: Double)] = [(E + 11 * H, E + 18 * H)]
        out.append("SLEEPSTART " + [
            RF.sleepStart(spans: [], openTs: E),
            RF.sleepStart(spans: lastNight, openTs: E),
            RF.sleepStart(spans: nap, openTs: E),
            RF.sleepStart(spans: lastNight + nap + night, openTs: E),
            RF.sleepStart(spans: [(E + 12 * H, E + 15 * H), (E + 11.5 * H, E + 14 * H)], openTs: E),
        ].map { f($0.map { $0 - E }) }.joined(separator: " "))
        out.append("GATE " + [
            RF.isOpen(nowTs: E - 1, openTs: E, closeTs: E + 16 * H, sleepStartTs: nil),
            RF.isOpen(nowTs: E, openTs: E, closeTs: E + 16 * H, sleepStartTs: nil),
            RF.isOpen(nowTs: E + 8 * H, openTs: E, closeTs: E + 16 * H, sleepStartTs: nil),
            RF.isOpen(nowTs: E + 16 * H, openTs: E, closeTs: E + 16 * H, sleepStartTs: nil),
            RF.isOpen(nowTs: E + 10 * H, openTs: E, closeTs: E + 16 * H, sleepStartTs: E + 11 * H),
            RF.isOpen(nowTs: E + 11 * H, openTs: E, closeTs: E + 16 * H, sleepStartTs: E + 11 * H),
            RF.isOpen(nowTs: E + 12 * H, openTs: E, closeTs: E + 16 * H, sleepStartTs: E + 11 * H),
        ].map { $0 ? "1" : "0" }.joined())

        let r = rows()
        out.append("TONIGHT1 " + tn(RF.tonight(rows: r, window: window, age: nil, nowTs: E + 8 * H, spans: lastNight, selectedTs: nil)))
        out.append("TONIGHT2 " + tn(RF.tonight(rows: r, window: window, age: 35, nowTs: E + 8 * H, spans: lastNight + nap,
                                          selectedTs: E + 12 * H + 8 * 60)))
        out.append("TONIGHT3 " + tn(RF.tonight(rows: r, window: window, age: nil, nowTs: E + 8 * H, spans: [],
                                          selectedTs: E + 20 * H)))
        out.append("TONIGHT4 " + tn(RF.tonight(rows: r, window: window, age: nil, nowTs: E + 11.5 * H, spans: lastNight + night,
                                          selectedTs: E + 13.5 * H)))
        out.append("TONIGHT5 " + tn(RF.tonight(rows: r, window: window, age: nil, nowTs: E + 10 * H, spans: night, selectedTs: nil)))
        out.append("TONIGHT6 " + tn(RF.tonight(rows: Array(r.suffix(4)), window: window, age: nil, nowTs: E + 8 * H, spans: [],
                                          selectedTs: nil)))
        out.append("TONIGHT7 " + tn(RF.tonight(rows: r, window: window, age: nil, nowTs: E + 16 * H, spans: [], selectedTs: nil)))
        let earlyWindow = RF.Window(eveningDay: "2026-03-20", targetDay: "2026-03-21", openTs: E, closeTs: E + 16 * H,
                                    earliestTs: E + 9 * H, latestTs: E + 14 * H, wakeTs: E + 16.5 * H)
        out.append("TONIGHT8 " + tn(RF.tonight(rows: r, window: earlyWindow, age: nil, nowTs: E + 6 * H, spans: [], selectedTs: nil)))
        out.append("TONIGHT9 " + tn(RF.tonight(rows: r, window: window, age: nil, nowTs: E + 11.4 * H, spans: lastNight,
                                               selectedTs: E + 10 * H)))

        var fcast: [String: Double] = [:], lo: [String: Double] = [:], hi: [String: Double] = [:], act: [String: Double] = [:]
        for i in 0..<18 {
            let day = DayOrdinal.key(DayOrdinal.ordinal("2026-04-01")! + i)
            let c = Double(55 + (i * 11) % 25)
            let b = Double(8 + i % 4)
            fcast[day] = c
            if i != 3 { lo[day] = c - b }
            if i != 4 { hi[day] = c + b }
            if i != 6 { act[day] = i == 7 ? .infinity : c + Double((i * 17) % 23) - 11 }
        }
        act["2026-04-17"] = fcast["2026-04-17"]! - Double(8 + 16 % 4)
        act["2026-04-18"] = fcast["2026-04-18"]! + Double(8 + 17 % 4)
        act["2026-05-01"] = 70
        let pairs = ForecastAccuracy.pairs(forecast: fcast, low: lo, high: hi, actual: act)
        out.append("PAIRS \(pairs.count) " + pairs.map { "\($0.day.suffix(2)):\(f($0.forecast)),\(f($0.low)),\(f($0.high)),\(f($0.actual))" }
            .joined(separator: " "))
        func sm(_ s: ForecastAccuracy.Summary?) -> String { s.map { "\($0.scored)/\(f($0.meanAbsError))/\($0.inside)" } ?? "nil" }
        out.append("SUMMARY " + [sm(ForecastAccuracy.summary(pairs)), sm(ForecastAccuracy.summary(Array(pairs.prefix(4)))),
                            sm(ForecastAccuracy.summary(Array(pairs.prefix(5)).reversed())), sm(ForecastAccuracy.summary([]))]
            .joined(separator: " "))
        return out
    }

    private static let expected: [String] = [
        "PLANNED 7.200000000 4.000000000 8.000000000 0.000000000 7.048550347 7.040000000",
        "SUGGEST 34200.000000000 30600.000000000 66600.000000000 66600.000000000 12600.000000000 29700.000000000 33300.000000000",
        "SNAP 36000.000000000 36900.000000000 36900.000000000 32400.000000000 50400.000000000",
        "MEDIAN nil 3.000000000 2.500000000 3.000000000 5.000000000",
        "EFFFRAC nil nil nil nil 0.420000000 1.000000000 0.010001000 0.880000000 1.000000000 nil",
        "EFFTYP 0.880000000 0.880000000 0.850000000 0.500000000 0.825000000",
        "WAKE nil 420.000000000 415.000000000 422.500000000",
        "NEED 9.039194032/8 9.724610698/8 8.558041667/2 8.000000000/0 8.000000000/0",
        "OPTIONS 38700.000000000=51.000000000/8.000000000/63.272727273/6.975000000/8.400000000/11/solid 35100.000000000=53.000000000/8.000000000/63.272727273/7.875000000/8.400000000/11/solid 42300.000000000=50.000000000/8.000000000/63.272727273/6.075000000/8.400000000/11/solid",
        "OPTIONS2 64800.000000000=50.000000000/14.000000000/63.400000000/0.425000000/8.000000000/5/building 61200.000000000=51.000000000/14.000000000/63.400000000/1.275000000/8.000000000/5/building 68400.000000000=49.000000000/14.000000000/63.400000000/0.000000000/8.000000000/5/building",
        "OPTIONS3 0",
        "PLANFC 54.000000000/8.000000000/63.272727273/8.550000000/8.400000000/11/solid nil",
        "SLEEPSTART nil nil nil 39600.000000000 43200.000000000",
        "GATE 0110100",
        "TONIGHT1 36000.000000000 36000.000000000 36000.000000000 32400.000000000 1 7.229166667 18 0.875000000 45.500000000 17 18 | 36000.000000000=69.000000000/8.000000000/62.928571429/7.437500000/7.229166667/14/solid 32400.000000000=70.000000000/8.000000000/62.928571429/8.312500000/7.229166667/14/solid 39600.000000000=67.000000000/8.000000000/62.928571429/6.562500000/7.229166667/14/solid",
        "TONIGHT2 36000.000000000 44100.000000000 44100.000000000 32400.000000000 1 7.229166667 18 0.875000000 45.500000000 17 18 | 44100.000000000=65.000000000/8.000000000/62.928571429/5.468750000/7.229166667/14/solid 40500.000000000=67.000000000/8.000000000/62.928571429/6.343750000/7.229166667/14/solid 47700.000000000=63.000000000/8.000000000/62.928571429/4.593750000/7.229166667/14/solid",
        "TONIGHT3 36000.000000000 36000.000000000 36000.000000000 32400.000000000 1 7.229166667 18 0.875000000 45.500000000 17 18 | 36000.000000000=69.000000000/8.000000000/62.928571429/7.437500000/7.229166667/14/solid 32400.000000000=70.000000000/8.000000000/62.928571429/8.312500000/7.229166667/14/solid 39600.000000000=67.000000000/8.000000000/62.928571429/6.562500000/7.229166667/14/solid",
        "TONIGHT4 36000.000000000 48600.000000000 48600.000000000 41400.000000000 0 7.229166667 18 0.875000000 45.500000000 17 18 | 48600.000000000=63.000000000/8.000000000/62.928571429/4.375000000/7.229166667/14/solid 45000.000000000=64.000000000/8.000000000/62.928571429/5.250000000/7.229166667/14/solid 52200.000000000=61.000000000/8.000000000/62.928571429/3.500000000/7.229166667/14/solid",
        "TONIGHT5 36000.000000000 36000.000000000 36000.000000000 36000.000000000 1 7.229166667 18 0.875000000 45.500000000 17 18 | 36000.000000000=69.000000000/8.000000000/62.928571429/7.437500000/7.229166667/14/solid 32400.000000000=70.000000000/8.000000000/62.928571429/8.312500000/7.229166667/14/solid 39600.000000000=67.000000000/8.000000000/62.928571429/6.562500000/7.229166667/14/solid",
        "TONIGHT6 nil",
        "TONIGHT7 36000.000000000 50400.000000000 50400.000000000 50400.000000000 0 7.229166667 18 0.875000000 45.500000000 17 18 | 50400.000000000=62.000000000/8.000000000/62.928571429/3.937500000/7.229166667/14/solid 46800.000000000=64.000000000/8.000000000/62.928571429/4.812500000/7.229166667/14/solid 54000.000000000=60.000000000/8.000000000/62.928571429/3.062500000/7.229166667/14/solid",
        "TONIGHT8 28800.000000000 32400.000000000 32400.000000000 32400.000000000 1 7.229166667 18 0.875000000 45.500000000 17 18 | 32400.000000000=67.000000000/8.000000000/62.928571429/6.562500000/7.229166667/14/solid 28800.000000000=69.000000000/8.000000000/62.928571429/7.437500000/7.229166667/14/solid 36000.000000000=65.000000000/8.000000000/62.928571429/5.687500000/7.229166667/14/solid",
        "TONIGHT9 36000.000000000 41400.000000000 41400.000000000 41400.000000000 1 7.229166667 18 0.875000000 45.500000000 17 18 | 41400.000000000=66.000000000/8.000000000/62.928571429/6.125000000/7.229166667/14/solid 37800.000000000=68.000000000/8.000000000/62.928571429/7.000000000/7.229166667/14/solid 45000.000000000=64.000000000/8.000000000/62.928571429/5.250000000/7.229166667/14/solid",
        "PAIRS 14 01:55.000000000,47.000000000,63.000000000,44.000000000 02:66.000000000,57.000000000,75.000000000,72.000000000 03:77.000000000,67.000000000,87.000000000,77.000000000 06:60.000000000,51.000000000,69.000000000,65.000000000 09:68.000000000,60.000000000,76.000000000,78.000000000 10:79.000000000,70.000000000,88.000000000,83.000000000 11:65.000000000,55.000000000,75.000000000,63.000000000 12:76.000000000,65.000000000,87.000000000,68.000000000 13:62.000000000,54.000000000,70.000000000,71.000000000 14:73.000000000,64.000000000,82.000000000,76.000000000 15:59.000000000,49.000000000,69.000000000,56.000000000 16:70.000000000,59.000000000,81.000000000,61.000000000 17:56.000000000,48.000000000,64.000000000,48.000000000 18:67.000000000,58.000000000,76.000000000,76.000000000",
        "SUMMARY 14/6.214285714/11 nil 5/6.400000000/3 nil",
    ]

    func testEveryOracleLineMatches() {
        let got = Self.oracleLines()
        XCTAssertEqual(got.count, Self.expected.count)
        for (g, e) in zip(got, Self.expected) { XCTAssertEqual(g, e) }
    }

    /// The stored forecast freezes once the night's sleep starts: a main-length span beginning before `now`
    /// closes the gate even though the window itself is still open, while a nap does not. The sleep starts
    /// at the suggested bedtime (22:00), so the past-bedtime clamp leaves the anchor where it was.
    func testForecastFreezesOnceTheNightsSleepStarts() {
        let E = Self.E, H = Self.H
        let rows = Self.rows()
        let before = RF.tonight(rows: rows, window: Self.window, age: nil, nowTs: E + 9.5 * H,
                                spans: [(E + 10 * H, E + 18 * H)], selectedTs: nil)
        let after = RF.tonight(rows: rows, window: Self.window, age: nil, nowTs: E + 10 * H,
                               spans: [(E + 10 * H, E + 18 * H)], selectedTs: nil)
        let napOnly = RF.tonight(rows: rows, window: Self.window, age: nil, nowTs: E + 10 * H,
                                 spans: [(E + 2 * H, E + 2.75 * H)], selectedTs: nil)
        XCTAssertEqual(before?.isOpen, true)
        XCTAssertEqual(after?.isOpen, false)
        XCTAssertEqual(napOnly?.isOpen, true)
        // The frozen value is the same forecast; only the right to overwrite it closes.
        XCTAssertEqual(before?.headline, after?.headline)
    }
}
