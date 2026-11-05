import XCTest
import StrandAnalytics
@testable import Strand

/// The localized digest renderer must reproduce the engine's English sentences exactly (the engine
/// strings are pinned by golden tests). Runs in the English development locale. Mirrors the Android
/// `WeeklyDigestTextTest`.
final class WeeklyDigestTextTests: XCTestCase {

    private func day(_ n: Int) -> String { String(format: "2026-06-%02d", n) }

    private func assertSameEnglish(_ d: WeeklyDigest, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertFalse(d.focalPoints.isEmpty, file: file, line: line)
        XCTAssertEqual(d.focalPoints.count, d.focalDetails.count, file: file, line: line)
        XCTAssertEqual(WeeklyDigestText.focalLines(d), d.focalPoints, file: file, line: line)
    }

    func testMoverUpGoodSign() {
        var charge: [String: Double] = [:]
        for n in 8...14 { charge[day(n)] = 80 }
        for n in 1...7 { charge[day(n)] = 55 }
        assertSameEnglish(WeeklyDigestEngine.build(byMetric: [.charge: charge], anchorDay: "2026-06-13"))
    }

    func testMoverRestingHrWorthALook() {
        var rhr: [String: Double] = [:]
        for n in 8...14 { rhr[day(n)] = 60 }
        for n in 1...7 { rhr[day(n)] = 52 }
        assertSameEnglish(WeeklyDigestEngine.build(byMetric: [.rhr: rhr], anchorDay: "2026-06-13"))
    }

    func testMoverInPointsWhenPercentDoesNotApply() {
        var eff: [String: Double] = [:]
        for n in 8...14 { eff[day(n)] = 91.5 }
        for n in 1...7 { eff[day(n)] = 88 }
        for (d, v) in [("2026-05-05", 87.0), ("2026-05-12", 88.0), ("2026-05-19", 89.0), ("2026-05-26", 88.0)] {
            eff[d] = v
        }
        assertSameEnglish(WeeklyDigestEngine.build(byMetric: [.sleepEfficiency: eff], anchorDay: "2026-06-13"))
    }

    func testMoverInOwnUnitAndLatestRollup() {
        var vo2: [String: Double] = [:]
        vo2[day(3)] = 45
        vo2[day(10)] = 47
        for (d, v) in [("2026-05-06", 44.0), ("2026-05-13", 45.0), ("2026-05-20", 44.0), ("2026-05-27", 45.0)] {
            vo2[d] = v
        }
        let d = WeeklyDigestEngine.build(byMetric: [.vo2max: vo2], anchorDay: "2026-06-13")
        XCTAssertTrue(d.focalPoints[0].contains("ml/kg/min"), d.focalPoints[0])
        // The localized name is "VO₂max" where the engine's English label is "VO2 max".
        XCTAssertEqual(WeeklyDigestText.focalLines(d)[0],
                       d.focalPoints[0].replacingOccurrences(of: "VO2 max", with: "VO₂max"))
    }

    func testBalanceLinesMatchEngine() {
        for read in [BalanceRead.overreaching, .balanced, .underloaded, .insufficient] {
            XCTAssertEqual(WeeklyDigestText.balance(read), read.sentence)
        }
        var effort: [String: Double] = [:], charge: [String: Double] = [:]
        for n in 8...14 { effort[day(n)] = 80; charge[day(n)] = 50 }
        let d = WeeklyDigestEngine.build(byMetric: [.effort: effort, .charge: charge], anchorDay: "2026-06-10")
        XCTAssertEqual(d.balance, .overreaching)
        assertSameEnglish(d)
    }

    func testSteadyWeekWithRest() {
        var charge: [String: Double] = [:], effort: [String: Double] = [:], rest: [String: Double] = [:]
        for n in 1...14 { charge[day(n)] = 65; effort[day(n)] = 63; rest[day(n)] = 84 }
        assertSameEnglish(WeeklyDigestEngine.build(byMetric: [.charge: charge, .effort: effort, .rest: rest],
                                                   anchorDay: "2026-06-13"))
    }

    func testSteadyWeekWithoutRest() {
        var charge: [String: Double] = [:]
        for n in 1...14 { charge[day(n)] = 65 }
        assertSameEnglish(WeeklyDigestEngine.build(byMetric: [.charge: charge], anchorDay: "2026-06-13"))
    }

    func testSparseCurrentWeek() {
        var charge: [String: Double] = [:]
        for n in 1...7 { charge[day(n)] = 70 }
        charge[day(8)] = 40
        charge[day(9)] = 40
        assertSameEnglish(WeeklyDigestEngine.build(byMetric: [.charge: charge], anchorDay: "2026-06-09"))
    }

    func testSparsePreviousWeek() {
        var charge: [String: Double] = [:]
        charge["2026-06-06"] = 70
        charge["2026-06-07"] = 74
        for n in 8...12 { charge[day(n)] = 41 }
        assertSameEnglish(WeeklyDigestEngine.build(byMetric: [.charge: charge], anchorDay: "2026-06-12"))
    }

    func testNotificationBodyIsTopLineAndNilWhenEmpty() {
        var charge: [String: Double] = [:]
        for n in 8...14 { charge[day(n)] = 80 }
        for n in 1...7 { charge[day(n)] = 55 }
        let d = WeeklyDigestEngine.build(byMetric: [.charge: charge], anchorDay: "2026-06-13")
        XCTAssertEqual(WeeklyDigestText.notificationBody(d), d.focalPoints.first)
        XCTAssertNil(WeeklyDigestText.notificationBody(WeeklyDigestEngine.build(byMetric: [:], anchorDay: "2026-06-13")))
    }
}
