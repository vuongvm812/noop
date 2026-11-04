import XCTest
@testable import StrandAnalytics

/// The pure half of the weekly-digest input resolver (#89). `expected` is the stdout of the
/// Swift oracle over these fixtures; the Kotlin `WeeklyDigestInputsTest` pins the same text.
final class WeeklyDigestInputsTests: XCTestCase {

    static func fixtureDays() -> [WeeklyDigestDay] {
        [
            WeeklyDigestDay(day: "2026-06-08", charge: 70, effort: 45, rest: 81, rhr: 52, hrv: 61, steps: 9000,
                            sleepMin: 450, efficiency: 0.9, deepMin: 80, remMin: 100, spo2: 96.5, respiration: 14.5,
                            skinTemp: -0.2),
            WeeklyDigestDay(day: "2026-06-09", charge: 66, steps: 7500, sleepMin: 400, efficiency: 92,
                            skinTemp: 33.4),
            WeeklyDigestDay(day: "2026-06-10", charge: .nan, rest: 77),
            WeeklyDigestDay(day: "2026-06-10", charge: 61, rest: 78),
        ]
    }

    static func fixtureWatch() -> WeeklyDigestWatchSeries {
        WeeklyDigestWatchSeries(
            stress: ["2026-06-08": 31, "2026-06-09": 44],
            sleepScore: ["2026-06-08": 82],
            activeKcal: ["2026-06-09": 512],
            trainingLoad: ["2026-06-08": 180],
            intensityMinutes: WeeklyDigestInputs.intensityMinutes(
                moderate: ["2026-06-08": 20, "2026-06-09": 15], vigorous: ["2026-06-09": 10, "2026-06-10": 5]),
            vo2max: ["2026-06-08": 48],
            bodyBatteryRecharge: ["2026-06-09": 42])
    }

    static func dumpSeries(_ m: [WeeklyMetric: [String: Double]]) -> [String] {
        WeeklyMetric.allCases.compactMap { metric -> String? in
            guard let s = m[metric], !s.isEmpty else { return nil }
            let pairs = s.keys.sorted().map { "\($0)=\(String(format: "%.4f", s[$0]!))" }
            return "\(metric.rawValue): " + pairs.joined(separator: " ")
        }
    }

    static func recharge(_ label: String, _ samples: [(ts: Int, value: Double)], _ start: Int, _ end: Int) -> String {
        let r = WeeklyDigestInputs.bodyBatteryRecharge(samples: samples, sleepStart: start, sleepEnd: end)
        return "recharge \(label)=" + (r.map { String(format: "%.4f", $0) } ?? "nil")
    }

    static let expected = """
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
        """

    func testOracleFixturesMatchPinnedOutput() {
        var lines = Self.dumpSeries(WeeklyDigestInputs.series(days: Self.fixtureDays(), watch: Self.fixtureWatch()))
        let start = 1_000_000, end = 1_028_800
        let bb: [(ts: Int, value: Double)] = [
            (start - 10_801, 5), (start - 10_800, 30), (start - 3_600, 22), (start, 25), (start + 600, 10),
            (end - 60, 90), (end, 70), (end + 3_600, 88), (end + 10_800, 89), (end + 10_801, 99),
        ]
        lines.append(Self.recharge("normal", bb, start, end))
        lines.append(Self.recharge("noBefore", bb.filter { $0.ts > start }, start, end))
        lines.append(Self.recharge("noAfter", bb.filter { $0.ts < end }, start, end))
        XCTAssertEqual(lines.joined(separator: "\n"), Self.expected)
    }

    /// The headline five map exactly as the hand-built maps this replaced did.
    func testHeadlineMapsMatchTheDailyRows() {
        let m = WeeklyDigestInputs.series(days: Self.fixtureDays(), watch: WeeklyDigestWatchSeries())
        XCTAssertEqual(m[.charge], ["2026-06-08": 70, "2026-06-09": 66, "2026-06-10": 61])
        XCTAssertNil(m[.stress])
    }
}
