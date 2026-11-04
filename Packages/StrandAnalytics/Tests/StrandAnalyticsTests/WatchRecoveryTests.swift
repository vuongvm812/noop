import XCTest
@testable import StrandAnalytics
import WhoopStore

/// Tests for `WatchRecovery`, the honesty-critical recovery-from-daily-aggregate engine behind
/// "Apple Watch as a device" and the import-only wearable Charge. The source gives sparse daily HRV +
/// resting HR rather than the strap's dense RR stream, so these fixtures pin the BEHAVIOUR (at-baseline ≈
/// mid, high-HRV / low-RHR → high, thin history / missing today → nil + calibrating) and the #53 baseline
/// (trailing 60 days, day−1 anchor) regardless of the exact logistic constants, which are inherited
/// unchanged from `RecoveryScorer` so watch recovery and strap recovery sit on the same scale.
/// Mirrors the Kotlin WatchRecoveryTest case-for-case.
final class WatchRecoveryTests: XCTestCase {

    private let scoredDay = "2026-03-01"

    private func row(_ day: String, hrv: Double?, rhr: Int? = nil) -> DailyMetric {
        DailyMetric(day: day, totalSleepMin: nil, efficiency: nil, deepMin: nil, remMin: nil, lightMin: nil,
                    disturbances: nil, restingHr: rhr, avgHrv: hrv, recovery: nil, strain: nil, exerciseCount: nil)
    }

    /// `count` consecutive nights ending the day before `scoredDay` (oldest first), then today's row.
    /// nil entries are missing nights.
    private func rows(sdnn: [Double?], rhr: [Double?] = [], todaySDNN: Double?, todayRHR: Int?) -> [DailyMetric] {
        let n = max(sdnn.count, rhr.count)
        let anchor = Baselines.isoEpochDay(scoredDay)!
        var out: [DailyMetric] = []
        for i in 0..<n {
            let day = Baselines.isoDay(fromEpochDay: anchor - n + i)
            let r = i < rhr.count ? rhr[i] : nil
            out.append(row(day, hrv: i < sdnn.count ? sdnn[i] : nil, rhr: r.map { Int($0) }))
        }
        out.append(row(scoredDay, hrv: todaySDNN, rhr: todayRHR))
        return out
    }

    private func score(sdnn: [Double?], rhr: [Double?] = [], todaySDNN: Double?, todayRHR: Int?) -> WatchRecovery.Result {
        WatchRecovery.score(day: scoredDay, rows: rows(sdnn: sdnn, rhr: rhr, todaySDNN: todaySDNN, todayRHR: todayRHR))
    }

    // HRV today equal to the baseline and RHR equal to baseline → mid recovery, solid confidence
    // (14 nights of history clears the trusted gate).
    func testAtBaselineGivesMidRecoverySolid() {
        let out = score(sdnn: Array(repeating: 45.0, count: 14), rhr: Array(repeating: 52.0, count: 14),
                        todaySDNN: 45.0, todayRHR: 52)
        XCTAssertNotNil(out.recovery)
        XCTAssertGreaterThanOrEqual(out.recovery!, 40)
        XCTAssertLessThanOrEqual(out.recovery!, 60)
        XCTAssertEqual(out.confidence, .solid)
    }

    // HRV well above baseline + RHR below baseline → high recovery.
    func testHighHRVLowRHRGivesHighRecovery() {
        let out = score(sdnn: Array(repeating: 45.0, count: 14), rhr: Array(repeating: 52.0, count: 14),
                        todaySDNN: 70.0, todayRHR: 46)
        XCTAssertNotNil(out.recovery)
        XCTAssertGreaterThan(out.recovery!, 65)
    }

    // HRV well below baseline + RHR above baseline → low recovery (a bad night must read low).
    func testLowHRVHighRHRGivesLowRecovery() {
        let out = score(sdnn: Array(repeating: 45.0, count: 14), rhr: Array(repeating: 52.0, count: 14),
                        todaySDNN: 22.0, todayRHR: 62)
        XCTAssertNotNil(out.recovery)
        XCTAssertLessThan(out.recovery!, 40)
    }

    // Too little history → calibrating, nil recovery (never a fabricated number).
    func testInsufficientHistoryCalibrates() {
        let out = score(sdnn: [45, 46], rhr: [52, 51], todaySDNN: 45.0, todayRHR: 52)
        XCTAssertNil(out.recovery)
        XCTAssertEqual(out.confidence, .calibrating)
    }

    // History just under the week gate → still calibrating (the gate is exactly minBaselineNights).
    func testHistoryJustBelowGateCalibrates() {
        let n = WatchRecovery.minBaselineNights - 1
        let out = score(sdnn: Array(repeating: 45.0, count: n), rhr: Array(repeating: 52.0, count: n),
                        todaySDNN: 45.0, todayRHR: 52)
        XCTAssertNil(out.recovery)
        XCTAssertEqual(out.confidence, .calibrating)
    }

    // History at the week gate (and usable baseline) → scores, no longer calibrating.
    func testHistoryAtGateScores() {
        let n = WatchRecovery.minBaselineNights
        let out = score(sdnn: Array(repeating: 45.0, count: n), rhr: Array(repeating: 52.0, count: n),
                        todaySDNN: 45.0, todayRHR: 52)
        XCTAssertNotNil(out.recovery)
        XCTAssertNotEqual(out.confidence, .calibrating)
    }

    // Missing today's HRV → calibrating, nil (we never score off RHR alone).
    func testMissingTodayCalibrates() {
        let out = score(sdnn: Array(repeating: 45.0, count: 14), rhr: Array(repeating: 52.0, count: 14),
                        todaySDNN: nil, todayRHR: 52)
        XCTAssertNil(out.recovery)
        XCTAssertEqual(out.confidence, .calibrating)
    }

    // Missing today's RHR (HRV present, baseline usable) → still scores off HRV alone.
    func testMissingTodayRHRStillScoresFromHRV() {
        let out = score(sdnn: Array(repeating: 45.0, count: 14), rhr: Array(repeating: 52.0, count: 14),
                        todaySDNN: 45.0, todayRHR: nil)
        XCTAssertNotNil(out.recovery)
        XCTAssertGreaterThanOrEqual(out.recovery!, 40)
        XCTAssertLessThanOrEqual(out.recovery!, 70)
    }

    // MARK: - #53: the baseline is the trailing 60 days, ending the day before

    // Nights older than the 60-day window do not count: a long history that sits entirely outside the
    // window leaves the baseline empty, so the day calibrates instead of scoring off stale nights.
    func testNightsOutsideTheWindowDoNotCount() {
        var history = rows(sdnn: Array(repeating: 45.0, count: 20), todaySDNN: 45.0, todayRHR: nil)
        let today = history.removeLast()
        // Move every history night 70+ days before the scored day.
        let anchor = Baselines.isoEpochDay(scoredDay)!
        let old = history.enumerated().map { (i: Int, r: DailyMetric) in
            row(Baselines.isoDay(fromEpochDay: anchor - 90 + i), hrv: r.avgHrv)
        }
        let out = WatchRecovery.score(day: scoredDay, rows: old + [today])
        XCTAssertNil(out.recovery)
        XCTAssertEqual(out.confidence, .calibrating)
    }

    // Today's own value never enters its baseline, and a LATER day never does either.
    func testTodayAndLaterDaysStayOutOfTheBaseline() {
        let base = rows(sdnn: Array(repeating: 45.0, count: 14), todaySDNN: 45.0, todayRHR: nil)
        let later = row("2026-03-05", hrv: 200.0)
        XCTAssertEqual(WatchRecovery.score(day: scoredDay, rows: base + [later]),
                       WatchRecovery.score(day: scoredDay, rows: base))
    }

    // MARK: - Week gate counts ACCEPTED nights, not raw entries (fork issue #62)

    func testSevenRawNightsWithFourValidCalibrates() {
        let out = score(sdnn: [45, 46, 47, 48, -1, 0, 999], todaySDNN: 45.0, todayRHR: nil)
        XCTAssertNil(out.recovery)
        XCTAssertEqual(out.confidence, .calibrating)
    }

    func testSevenRawNightsWithThreeValidCalibrates() {
        let out = score(sdnn: [45, 46, 47, -1, 0, 999, 1000], todaySDNN: 45.0, todayRHR: nil)
        XCTAssertNil(out.recovery)
        XCTAssertEqual(out.confidence, .calibrating)
    }

    func testSixRawNightsWithSixValidCalibrates() {
        let out = score(sdnn: [45, 46, 47, 48, 45, 46], todaySDNN: 45.0, todayRHR: nil)
        XCTAssertNil(out.recovery)
        XCTAssertEqual(out.confidence, .calibrating)
    }

    func testNineRawNightsWithSevenValidScores() {
        let out = score(sdnn: [45, 46, 47, 48, 45, 46, 47, -1, 999], todaySDNN: 45.0, todayRHR: nil)
        XCTAssertNotNil(out.recovery)
        XCTAssertNotEqual(out.confidence, .calibrating)
    }

    // MARK: - RHR term needs a USABLE RHR baseline (fork issue #61)

    // No RHR history → the result is the documented HRV-only path, exactly as if today's RHR were missing.
    func testEmptyRHRHistoryScoresLikeMissingRHR() {
        let hist: [Double?] = Array(repeating: 45.0, count: 7)
        let withRHR = score(sdnn: hist, todaySDNN: 45.0, todayRHR: 52)
        let hrvOnly = score(sdnn: hist, todaySDNN: 45.0, todayRHR: nil)
        XCTAssertNotNil(withRHR.recovery)
        XCTAssertNotNil(hrvOnly.recovery)
        XCTAssertEqual(withRHR.recovery!, hrvOnly.recovery!, accuracy: 1e-12)
        // The same literal the Kotlin twin pins, so the oracle guards BOTH directions.
        XCTAssertEqual(withRHR.recovery!, 57.932425214874954, accuracy: 1e-12)
    }

    // An RHR history entirely out of physiological range accepts no night, so it is dropped too.
    func testUnusableRHRHistoryScoresLikeMissingRHR() {
        let hist: [Double?] = Array(repeating: 45.0, count: 7)
        let junk: [Double?] = Array(repeating: 300.0, count: 7)   // above restingHRCfg.maxVal (120)
        let withRHR = score(sdnn: hist, rhr: junk, todaySDNN: 45.0, todayRHR: 52)
        let hrvOnly = score(sdnn: hist, rhr: junk, todaySDNN: 45.0, todayRHR: nil)
        XCTAssertNotNil(withRHR.recovery)
        XCTAssertEqual(withRHR.recovery!, hrvOnly.recovery!, accuracy: 1e-12)
    }

    // Once the RHR baseline IS usable (≥ Baselines.minNightsSeed accepted nights) the term returns: a
    // resting HR above baseline must pull the score below the HRV-only number.
    func testUsableRHRHistoryStillContributes() {
        let hist: [Double?] = Array(repeating: 45.0, count: 7)
        let rhrHist: [Double?] = [nil, nil, nil, 52, 52, 52, 52]   // exactly the seed gate → provisional
        let withRHR = score(sdnn: hist, rhr: rhrHist, todaySDNN: 45.0, todayRHR: 62)
        let hrvOnly = score(sdnn: hist, rhr: rhrHist, todaySDNN: 45.0, todayRHR: nil)
        XCTAssertNotNil(withRHR.recovery)
        XCTAssertNotNil(hrvOnly.recovery)
        XCTAssertLessThan(withRHR.recovery!, hrvOnly.recovery! - 1.0)
    }

    // Watch recovery is on the SAME scale as strap recovery: the strap scorer fed the same trailing
    // Charge baselines reproduces WatchRecovery's number.
    func testSameScaleAsStrapRecovery() {
        let input = rows(sdnn: Array(repeating: 45.0, count: 14), rhr: Array(repeating: 52.0, count: 14),
                         todaySDNN: 58.0, todayRHR: 50)
        let out = WatchRecovery.score(day: scoredDay, rows: input)
        let resolved = ChargeBaselines.resolveDay(
            scoredDay: scoredDay, imported: .init(),
            own: ChargeBaselines.Nights(rows: input.filter { $0.day < scoredDay }), seed: .init(),
            sourceDays: [], hrvRecalibration: 0, recoveryRecalibration: 0)!
        let strap = RecoveryScorer.recovery(hrv: 58.0, rhr: 50.0, resp: nil,
                                            hrvBaseline: resolved.hrv, rhrBaseline: resolved.restingHR,
                                            respBaseline: nil, sleepPerf: nil,
                                            hrvPriorWeek: resolved.hrvPriorWeek)
        XCTAssertNotNil(out.recovery)
        XCTAssertNotNil(strap)
        XCTAssertEqual(out.recovery!, strap!, accuracy: 0.0001)
    }
}
