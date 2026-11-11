import XCTest
@testable import Strand
import StrandAnalytics
import WhoopStore

/// Fitness Age, VO₂max, Vitality and Body Age are computed DAILY: day D is gated on the merged daily rows of
/// [D-6, D] and written under D itself. Twin of the Android `FitnessVitalityDailyTest`; the oracle summary
/// below is this test's stdout, pasted verbatim into the Kotlin twin. All data is synthetic.
@MainActor
final class FitnessVitalityDailyTests: XCTestCase {

    /// 08-01…08-12 minus 08-06 (a gap), a rising RHR/HRV/steps trend so each window gives a different value.
    private func merged() -> [DailyMetric] {
        (1...12).filter { $0 != 6 }.map { i in
            DailyMetric(day: String(format: "2026-08-%02d", i), totalSleepMin: Double(400 + i * 5), efficiency: nil,
                        deepMin: nil, remMin: nil, lightMin: nil, disturbances: nil, restingHr: 58 + i % 3,
                        avgHrv: Double(50 + i), recovery: nil, strain: Double(40 + i), exerciseCount: nil,
                        steps: 7_000 + i * 100)
        }
    }

    private func fmt(_ v: Double) -> String { String(format: "%.6f", v) }

    private func summary() -> String {
        let days = IntelligenceEngine.dayRange("2026-08-01", "2026-08-14")
        let regularity = ["2026-08-10": 0.6, "2026-08-12": 0.8]
        var lines: [String] = []
        let runs: [(String, [(day: String, value: Double)]?)] = [("watch", [(day: "2026-07-20", value: 47.0)]), ("noWatch", nil)]
        for (name, watch) in runs {
            let out = IntelligenceEngine.dailyFitnessVitalityRows(
                merged: merged(), days: days, age: 40, sex: "male", waistCm: 90, heightCm: 175, weightKg: 80,
                computedId: "my-whoop-noop", watchPoints: watch, regularityByDay: regularity)
            for p in out.points { lines.append("\(name) \(p.day) \(p.key) \(fmt(p.value))") }
            for r in out.provenance { lines.append("\(name) prov \(r.day) \(r.key) \(r.sourceId)") }
        }
        lines.append("dayMinus \(IntelligenceEngine.dayMinus("2026-03-01", 6)) \(IntelligenceEngine.dayMinus("bad", 6))")
        let ranges = [("2026-12-30", "2027-01-02"), ("2026-08-02", "2026-08-01"), ("bad", "2026-08-01")]
        lines.append("range " + ranges.map { "[" + IntelligenceEngine.dayRange($0.0, $0.1).joined(separator: ",") + "]" }.joined(separator: " "))
        return lines.joined(separator: "\n")
    }

    func testOracleMatchesThePinnedOutput() throws {
        if let path = ProcessInfo.processInfo.environment["FITNESS_VITALITY_ORACLE_DUMP"] {
            try summary().write(toFile: path, atomically: true, encoding: .utf8)
        }
        XCTAssertEqual(summary(), Self.expected)
    }

    /// A day whose [D-6, D] window holds fewer than the gate needs writes no Fitness Age, and an empty
    /// history writes nothing at all.
    func testSparseWindowWritesNothing() {
        let one = Array(merged().prefix(1))
        let out = IntelligenceEngine.dailyFitnessVitalityRows(
            merged: one, days: ["2026-08-01"], age: 40, sex: "male", waistCm: 90, heightCm: 175, weightKg: 80,
            computedId: "my-whoop-noop", watchPoints: nil, regularityByDay: [:])
        XCTAssertTrue(out.points.filter { $0.key == "fitness_age" }.isEmpty)
        let none = IntelligenceEngine.dailyFitnessVitalityRows(
            merged: [], days: ["2026-08-01"], age: 40, sex: "male", waistCm: 90, heightCm: 175, weightKg: 80,
            computedId: "my-whoop-noop", watchPoints: nil, regularityByDay: [:])
        XCTAssertTrue(none.points.isEmpty)
    }

    static let expected = """
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
    """
}
