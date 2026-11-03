import XCTest
@testable import StrandAnalytics

/// Optional personal baselines obey the same eligibility contract as resting HR.
/// Kotlin twin: RecoveryOptionalBaselineUsableTest.
final class RecoveryOptionalBaselineUsableTests: XCTestCase {
    private func optionalBaselineFixture(_ mean: Double, _ spread: Double,
                                         _ status: BaselineStatus = .trusted) -> BaselineState {
        BaselineState(baseline: mean, spread: spread,
                      nValid: status == .calibrating ? 0 : (status == .provisional ? 6 : 14),
                      nightsSinceUpdate: status == .stale ? 20 : 0, status: status)
    }

    private var hrv: BaselineState { optionalBaselineFixture(50, 8) }
    private var rhr: BaselineState { optionalBaselineFixture(60, 4) }
    private let statuses: [BaselineStatus] = [.calibrating, .provisional, .trusted, .stale]
    private let respiratoryValues: [Double?] = [nil, 10, 14, 18, 22]

    private func optionalChargeFixture(respBase: BaselineState?, resp: Double? = 14) -> Double? {
        RecoveryScorer.recovery(hrv: 55, rhr: 58, resp: resp,
                                hrvBaseline: hrv, rhrBaseline: rhr, respBaseline: respBase,
                                sleepPerf: 0.85, skinTempDev: 0.2)
    }

    func testUnusableRespirationScoresExactlyLikeAbsent() {
        let states = [Baselines.foldHistory([], cfg: Baselines.respCfg),
                      Baselines.foldHistory([0, 100], cfg: Baselines.respCfg),
                      optionalBaselineFixture(16, 2, .stale)]
        for base in states {
            XCTAssertFalse(base.usable)
            for value in respiratoryValues {
                XCTAssertEqual(optionalChargeFixture(respBase: base, resp: value)?.bitPattern,
                               optionalChargeFixture(respBase: nil, resp: value)?.bitPattern)
            }
        }
    }

    func testAllOptionalStateAndValuePairsMatchEligibleDriverContract() {
        let respStates: [BaselineState?] = [nil] + statuses.map { optionalBaselineFixture(16, 2, $0) }
        var cases = 0
        var oracleLines: [String] = []
        for respBase in respStates {
            for resp in respiratoryValues {
                // The status-free API is the original math, with only eligible personal states supplied.
                let expected = RecoveryScorer.recovery(
                    hrv: 55, rhr: 58, resp: resp,
                    hrvBaseline: RecoveryScorer.DriverBaseline(hrv),
                    rhrBaseline: RecoveryScorer.DriverBaseline(rhr),
                    respBaseline: respBase.flatMap { $0.usable ? RecoveryScorer.DriverBaseline($0) : nil },
                    sleepPerf: 0.85, skinTempDev: 0.2)
                let actual = optionalChargeFixture(respBase: respBase, resp: resp)
                XCTAssertEqual(actual?.bitPattern, expected?.bitPattern)
                if resp == 14 {
                    let bits = actual.map { String($0.bitPattern, radix: 16) } ?? "nil"
                    oracleLines.append("\(respBase?.status.rawValue ?? "nil")|\(bits)")
                }
                cases += 1
            }
        }
        XCTAssertEqual(cases, 25)
        if ProcessInfo.processInfo.environment["PRINT_ORACLE"] != nil {
            print("ORACLE2-BEGIN\n\(oracleLines.joined(separator: "\n"))\nORACLE2-END")
        }
        // Compiled Swift output with only eligible states supplied, copied verbatim to Kotlin.
        XCTAssertEqual(oracleLines.joined(separator: "\n"), """
        nil|4051c59416cf2fed
        calibrating|4051c59416cf2fed
        provisional|4051f31a1925a3f3
        trusted|4051f31a1925a3f3
        stale|4051c59416cf2fed
        """)
        XCTAssertNotEqual(optionalChargeFixture(respBase: nil)?.bitPattern,
                          optionalChargeFixture(respBase: optionalBaselineFixture(16, 2))?.bitPattern)
    }

    func testRespirationRowsUseExactlyTheEligibleBaseline() {
        for status in statuses {
            let base = optionalBaselineFixture(16, 2, status)
            for value in respiratoryValues {
                let actual = RecoveryScorer.chargeDrivers(hrv: 55, rhr: 58, resp: value,
                    hrvBaseline: hrv, rhrBaseline: rhr, respBaseline: base,
                    sleepPerf: 0.85, skinTempDev: 0.2)
                let expected = RecoveryScorer.chargeDrivers(hrv: 55, rhr: 58, resp: value,
                    hrvBaseline: hrv, rhrBaseline: rhr, respBaseline: base.usable ? base : nil,
                    sleepPerf: 0.85, skinTempDev: 0.2)
                XCTAssertEqual(actual, expected)
                XCTAssertEqual(actual.contains { $0.label == "Respiratory rate" }, base.usable && value != nil)
                XCTAssertTrue(actual.contains { $0.label == "Heart rate variability" })
            }
        }
    }

    func testRespirationTraceUsesExactlyTheScoredTerms() {
        for status in statuses {
            let base = optionalBaselineFixture(16, 2, status)
            for value in respiratoryValues {
                let actual = RecoveryScorer.recoveryTrace(hrv: 55, rhr: 58, resp: value,
                    hrvBaseline: hrv, rhrBaseline: rhr, respBaseline: base,
                    sleepPerf: 0.85, skinTempDev: 0.2)
                let expected = RecoveryScorer.recoveryTrace(hrv: 55, rhr: 58, resp: value,
                    hrvBaseline: hrv, rhrBaseline: rhr, respBaseline: base.usable ? base : nil,
                    sleepPerf: 0.85, skinTempDev: 0.2)
                XCTAssertEqual(actual.score?.bitPattern, expected.score?.bitPattern)
                XCTAssertEqual(actual.trace, expected.trace)
                XCTAssertEqual(actual.trace.contains { $0.hasPrefix("charge term resp ") }, base.usable && value != nil)
                XCTAssertEqual(actual.trace.contains { $0.hasPrefix("charge baseline resp ") }, base.usable)
                XCTAssertEqual(actual.trace.contains { $0.hasPrefix("charge nilTerm dropped=") && $0.contains("resp") },
                               !base.usable || value == nil)
            }
        }
    }

    func testDominantHrvColdStartStillRefusesEveryOptionalState() {
        for status in [BaselineStatus.calibrating, .stale] {
            let coldHrv = optionalBaselineFixture(50, 8, status)
            for optionalStatus in statuses {
                let base = optionalBaselineFixture(16, 2, optionalStatus)
                XCTAssertNil(RecoveryScorer.recovery(hrv: 55, rhr: 58, resp: 14,
                    hrvBaseline: coldHrv, rhrBaseline: rhr, respBaseline: base, sleepPerf: 0.85))
                XCTAssertTrue(RecoveryScorer.chargeDrivers(hrv: 55, rhr: 58, resp: 14,
                    hrvBaseline: coldHrv, rhrBaseline: rhr, respBaseline: base, sleepPerf: 0.85).isEmpty)
                let trace = RecoveryScorer.recoveryTrace(hrv: 55, rhr: 58, resp: 14,
                    hrvBaseline: coldHrv, rhrBaseline: rhr, respBaseline: base, sleepPerf: 0.85)
                XCTAssertNil(trace.score)
                XCTAssertEqual(trace.trace.count, 1)
                XCTAssertTrue(trace.trace.first?.contains("hrvBaselineNotUsable") ?? false)
            }
        }
    }
}
