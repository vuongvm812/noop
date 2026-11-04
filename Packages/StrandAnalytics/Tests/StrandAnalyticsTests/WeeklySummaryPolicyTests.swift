import XCTest
@testable import StrandAnalytics

/// The "weekly summary ready" policy (#91). The Kotlin `WeeklySummaryPolicyTest` pins the same table.
final class WeeklySummaryPolicyTests: XCTestCase {

    // 2026-06-08 is a Monday; last week starts 2026-06-01.
    func testFiresOnceForLastWeekAfterMondaySync() {
        XCTAssertEqual(WeeklySummaryPolicy.weekToAnnounce(enabled: true, logicalToday: "2026-06-08",
                                                          lastAnnouncedWeek: nil, hasCurrentWeekData: true),
                       "2026-06-01")
        XCTAssertNil(WeeklySummaryPolicy.weekToAnnounce(enabled: true, logicalToday: "2026-06-08",
                                                        lastAnnouncedWeek: "2026-06-01", hasCurrentWeekData: true))
    }

    func testWaitsForTheFirstSyncOfTheWeek() {
        XCTAssertNil(WeeklySummaryPolicy.weekToAnnounce(enabled: true, logicalToday: "2026-06-08",
                                                        lastAnnouncedWeek: "2026-05-25", hasCurrentWeekData: false))
    }

    func testOffAndUnparseableNeverFire() {
        XCTAssertNil(WeeklySummaryPolicy.weekToAnnounce(enabled: false, logicalToday: "2026-06-08",
                                                        lastAnnouncedWeek: nil, hasCurrentWeekData: true))
        XCTAssertNil(WeeklySummaryPolicy.weekToAnnounce(enabled: true, logicalToday: "bad",
                                                        lastAnnouncedWeek: nil, hasCurrentWeekData: true))
    }

    /// Sunday night before the 04:00 roll is still the old week: it announces the week before
    /// that one, which is already marked, so nothing fires.
    func testSundayLogicalDayDoesNotAnnounceTheWeekItIsIn() {
        XCTAssertNil(WeeklySummaryPolicy.weekToAnnounce(enabled: true, logicalToday: "2026-06-07",
                                                        lastAnnouncedWeek: "2026-05-25", hasCurrentWeekData: true))
    }

    func testMissedMondayStillFiresLaterThatWeek() {
        XCTAssertEqual(WeeklySummaryPolicy.weekToAnnounce(enabled: true, logicalToday: "2026-06-11",
                                                          lastAnnouncedWeek: "2026-05-25", hasCurrentWeekData: true),
                       "2026-06-01")
    }

    func testCurrentWeekDataAndAnchor() {
        XCTAssertTrue(WeeklySummaryPolicy.hasCurrentWeekData(dayKeys: ["2026-06-05", "2026-06-08"],
                                                             logicalToday: "2026-06-08"))
        XCTAssertFalse(WeeklySummaryPolicy.hasCurrentWeekData(dayKeys: ["2026-06-05", "2026-06-07"],
                                                              logicalToday: "2026-06-08"))
        XCTAssertEqual(WeeklySummaryPolicy.anchorDay(forWeek: "2026-06-01"), "2026-06-07")
    }

    func testBodyIsTheTopFocalSentence() {
        let empty = WeeklyDigestEngine.build(byMetric: [:], anchorDay: "2026-06-07")
        XCTAssertNil(WeeklySummaryPolicy.body(empty))
        var charge: [String: Double] = [:]
        for k in 0..<7 { charge[WeeklyDigestEngine.addDays("2026-06-01", k)] = 70 }
        let d = WeeklyDigestEngine.build(byMetric: [.charge: charge], anchorDay: "2026-06-07")
        XCTAssertEqual(WeeklySummaryPolicy.body(d), d.focalPoints.first)
    }
}
