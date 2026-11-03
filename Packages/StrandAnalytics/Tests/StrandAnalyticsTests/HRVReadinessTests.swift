import XCTest
@testable import StrandAnalytics

/// HRVReadiness (Plews/Altini SWC) — the OPT-IN experimental HRV-readiness tier readout. The oracle for the
/// Android HRVReadinessTest; keep the two in lockstep (same fixtures, same assertions). Pins each tier on a
/// fixture series and the < minNights calibrating/nil edge (including that physiologically implausible
/// nights are dropped BEFORE the gate). Read-only variant: it feeds no downstream gate, so there is nothing
/// else to assert.
final class HRVReadinessTests: XCTestCase {

    private static let today = "2026-09-30"

    /// Consecutive nights ending today (the last value is today's night).
    private func eval(_ values: [Double?]) -> HRVReadinessResult? {
        let t = Baselines.isoEpochDay(Self.today)!
        let keys = (0..<values.count).map { Baselines.isoDay(fromEpochDay: t - values.count + 1 + $0) }
        return HRVReadiness.evaluate(dayKeys: keys, avgHrv: values, today: Self.today)
    }

    // A high-then-primed history: 53 nights at 50 ms, the last 7 at 75 ms — the 7-night baseline sits
    // well above the personal normal band, so the tier reads .primed.
    private let primedHistory: [Double?] =
        Array(repeating: 50.0, count: 53) + Array(repeating: 75.0, count: 7)

    // The mirror: 53 nights at 50 ms, the last 7 at 38 ms — the 7-night baseline sits below the band, .suppressed.
    private let suppressedHistory: [Double?] =
        Array(repeating: 50.0, count: 53) + Array(repeating: 38.0, count: 7)

    // A perfectly flat history: baseline7 == longMean exactly, so the band collapses to the mean → .normal.
    private let flatHistory: [Double?] = Array(repeating: 60.0, count: 60)

    // MARK: - Tier

    func testPrimedFixtureTier() {
        let r = eval(primedHistory)
        XCTAssertNotNil(r)
        XCTAssertEqual(r!.tier, .primed)
        // The 7-night baseline is the last-7 mean, exp back to ms: exp(ln 75) = 75.
        XCTAssertEqual(r!.baseline7Ms, 75.0, accuracy: 1e-6)
        // Baseline above the long mean → the overreaching (falling-CV-while-below-mean) watch cannot fire.
        XCTAssertFalse(r!.overreachingWatch)
    }

    func testSuppressedFixtureTier() {
        let r = eval(suppressedHistory)
        XCTAssertNotNil(r)
        XCTAssertEqual(r!.tier, .suppressed)
        XCTAssertEqual(r!.baseline7Ms, 38.0, accuracy: 1e-6)
    }

    func testFlatHistoryIsNormalAndBandCollapsesToMean() {
        let r = eval(flatHistory)
        XCTAssertNotNil(r)
        XCTAssertEqual(r!.tier, .normal)
        // baseline7 == longMean and longSD == 0 → the band collapses to the mean, all reading back to 60 ms.
        XCTAssertEqual(r!.baseline7Ms, 60.0, accuracy: 1e-6)
        XCTAssertEqual(r!.normalLowMs, 60.0, accuracy: 1e-6)
        XCTAssertEqual(r!.normalHighMs, 60.0, accuracy: 1e-6)
    }

    // MARK: - Calibrating / nil edge (< minNights valid nights)

    func testBelowMinNightsIsNil() {
        // 13 valid nights is one short of the gate → nil (honest calibrating, never fabricated).
        XCTAssertNil(eval(Array(repeating: 60.0, count: HRVReadiness.minNights - 1)))
        // Exactly minNights valid nights → a reading appears.
        XCTAssertNotNil(eval(Array(repeating: 60.0, count: HRVReadiness.minNights)))
    }

    func testOutOfRangeNightsAreDroppedBeforeGate() {
        // 13 in-range + 5 implausible (300 ms > hrvCfg.maxVal) → only 13 valid → still calibrating (nil).
        let mixed: [Double?] = Array(repeating: 300.0, count: 5) + Array(repeating: 60.0, count: 13)
        XCTAssertNil(eval(mixed))
        // Add one more in-range night → 14 valid → a reading appears (the implausible nights never count).
        let ok: [Double?] = Array(repeating: 300.0, count: 5) + Array(repeating: 60.0, count: 14)
        XCTAssertNotNil(eval(ok))
    }

    // MARK: - Calendar windows (#53)

    /// The "7-day" baseline is seven CALENDAR days: after a fortnight off the strap the last seven valid
    /// nights are weeks old, so there is no 7-day reading at all rather than a stale one.
    func testAWeeksOffLeavesNoSevenDayReading() {
        let history: [Double?] = Array(repeating: 50.0, count: 40) + Array(repeating: nil, count: 10)
        XCTAssertNil(eval(history))
        // Three nights back on the strap within the last week are enough again.
        XCTAssertNotNil(eval(history + [38, 38, 38]))
    }

    /// Only the last 60 calendar days form the normal band: nights older than that do not count toward the
    /// 14-night gate, however many there are.
    func testTheLongWindowIsSixtyCalendarDays() {
        let old: [Double?] = Array(repeating: 50.0, count: 100)
        let gap: [Double?] = Array(repeating: nil, count: 50)
        XCTAssertNil(eval(old + gap + Array(repeating: 50.0, count: 10)))
        XCTAssertNotNil(eval(old + gap + Array(repeating: 50.0, count: 14)))
    }

    /// Days after `today` and unparseable keys are ignored.
    func testFutureAndUnparseableDaysAreIgnored() {
        let keys = (0..<20).map { Baselines.isoDay(fromEpochDay: Baselines.isoEpochDay(Self.today)! - 19 + $0) }
        let base = HRVReadiness.evaluate(dayKeys: keys, avgHrv: Array(repeating: 60.0, count: 20), today: Self.today)
        let noisy = HRVReadiness.evaluate(dayKeys: keys + ["2026-10-01", "junk"],
                                          avgHrv: Array(repeating: 60.0, count: 20) + [10, 10], today: Self.today)
        XCTAssertEqual(base, noisy)
    }
}
