import XCTest
@testable import StrandAnalytics

/// #92 automation-result gates and the Swift port of ScheduledReportPolicy. The Kotlin
/// `AutomationResultPolicyTest` and `ScheduledReportPolicyTest` pin the same cases.
final class AutomationResultPolicyTests: XCTestCase {

    func testResultSuccessOncePerDayFailureOnceUntilSuccess() {
        let r = AutomationResultPolicy.shouldNotifyResult
        XCTAssertTrue(r(true, true, "2026-06-08", "2026-06-07", false))
        XCTAssertFalse(r(true, true, "2026-06-08", "2026-06-08", false))
        XCTAssertTrue(r(true, true, "2026-06-08", "2026-06-08", true))    // clears a failure
        XCTAssertTrue(r(true, false, "2026-06-08", nil, false))
        XCTAssertFalse(r(true, false, "2026-06-08", nil, true))           // already told
        XCTAssertFalse(r(false, false, "2026-06-08", nil, false))
    }

    func testCycleReadingOnlyForANewSettledPhase() {
        let c = AutomationResultPolicy.shouldNotifyCycleReading
        XCTAssertTrue(c(true, "luteal", "follicular"))
        XCTAssertFalse(c(true, "luteal", "luteal"))
        XCTAssertFalse(c(true, "learning", nil))
        XCTAssertFalse(c(true, "unknown", nil))
        XCTAssertFalse(c(true, nil, nil))
        XCTAssertFalse(c(false, "luteal", nil))
    }

    func testScheduledReportPortMatchesKotlin() {
        XCTAssertTrue(ScheduledReportPolicy.shouldNotifyMorning(enabled: true, chargeOrRestPresent: true,
                                                                lastNotifiedDay: nil, reportDay: "2026-06-08",
                                                                nowMinuteOfDay: 7 * 60))
        XCTAssertFalse(ScheduledReportPolicy.shouldNotifyMorning(enabled: true, chargeOrRestPresent: true,
                                                                 lastNotifiedDay: nil, reportDay: "2026-06-08",
                                                                 nowMinuteOfDay: 40))
        XCTAssertFalse(ScheduledReportPolicy.shouldNotifyMorning(enabled: true, chargeOrRestPresent: true,
                                                                 lastNotifiedDay: "2026-06-08", reportDay: "2026-06-08",
                                                                 nowMinuteOfDay: 7 * 60))
        XCTAssertTrue(ScheduledReportPolicy.shouldNotifyWorkout(enabled: true, newestWorkoutTs: 5, lastWorkoutTs: 4))
        XCTAssertFalse(ScheduledReportPolicy.shouldNotifyWorkout(enabled: true, newestWorkoutTs: 4, lastWorkoutTs: 4))
        let m = ScheduledReportPolicy.morningCopy(chargePct: 72, restPct: nil)
        XCTAssertEqual(m?.title, "Good morning: last night's recap")
        XCTAssertEqual(m?.body, "Charge 72. Recovery from your strap, scored after it synced this morning.")
        XCTAssertNil(ScheduledReportPolicy.morningCopy(chargePct: nil, restPct: nil))
        let w = ScheduledReportPolicy.workoutCopy(sportLabel: "Running", effortDisplay: "12.4", effortMaxLabel: "21",
                                                  durationLabel: "42 min", avgHr: 151)
        XCTAssertEqual(w.title, "Workout logged: Running")
        XCTAssertEqual(w.body, "Effort 12.4/21 · 42 min · avg 151 bpm. Summarised after your strap synced.")
        XCTAssertEqual(ScheduledReportPolicy.durationLabel(minutes: 0), "under a minute")
        XCTAssertEqual(ScheduledReportPolicy.durationLabel(minutes: 120), "2 h")
        XCTAssertEqual(ScheduledReportPolicy.durationLabel(minutes: 68), "1 h 8 min")
    }
}
