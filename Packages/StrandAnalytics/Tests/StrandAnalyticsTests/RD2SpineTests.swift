import XCTest
@testable import StrandAnalytics
import WhoopStore

/// RD2: the readiness HRV signal reads a trailing personal baseline that follows a recent sustained
/// shift (a device swap, a fitness change) instead of rejecting the new normal, and still flags a genuine
/// drop. Since #53 that baseline is the Charge one (the trailing 60-day lnRMSSD mean and SD), not a
/// separate re-folded EWMA.
final class RD2SpineTests: XCTestCase {

    // MARK: - Readiness end-to-end (ln-space HRV)

    private func d(_ i: Int, hrv: Double?, rhr: Int?) -> DailyMetric {
        DailyMetric(day: String(format: "2024-03-%02d", i), totalSleepMin: nil, efficiency: nil,
                    deepMin: nil, remMin: nil, lightMin: nil, disturbances: nil, restingHr: rhr,
                    avgHrv: hrv, recovery: nil, strain: 10, exerciseCount: nil,
                    spo2Pct: nil, skinTempDevC: nil, respRateBpm: nil)
    }

    func testReadinessAdaptsToRecentSustainedHRVShift() {
        // 12 old nights at 130 ms, then 18 recent nights settled at 85 ms (a sustained shift), today at
        // the new normal 85. It must NOT read BAD: the trailing baseline holds the recent block, where a
        // hard-outlier-rejecting incremental fold stayed stuck at 130.
        var days: [DailyMetric] = []
        for i in 1...12 { days.append(d(i, hrv: 130, rhr: 52)) }
        for i in 13...30 { days.append(d(i, hrv: 85, rhr: 52)) }
        days.append(d(31, hrv: 85, rhr: 52))   // today: the new normal
        let hrv = ReadinessEngine.evaluate(days: days).signals.first { $0.key == "hrv" }
        XCTAssertNotNil(hrv)
        XCTAssertNotEqual(hrv?.flag, .bad,
                          "today at the recent sustained normal must not read BAD")
    }

    func testGenuineLowStillFlagsAfterSpineAdoption() {
        // Regression: the baseline must not blunt a real drop. Stable ~95 ms baseline, today a clear 68 ms.
        var days: [DailyMetric] = []
        for i in 1...30 { days.append(d(i, hrv: i % 2 == 0 ? 97 : 93, rhr: 52)) }
        days.append(d(31, hrv: 68, rhr: 52))
        let hrv = ReadinessEngine.evaluate(days: days).signals.first { $0.key == "hrv" }
        XCTAssertEqual(hrv?.flag, .bad, "a genuine ~28% HRV drop must still read BAD")
    }
}
