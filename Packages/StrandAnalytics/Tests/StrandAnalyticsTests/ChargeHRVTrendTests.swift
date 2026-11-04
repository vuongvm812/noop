import XCTest
@testable import StrandAnalytics

/// The Charge HRV trend modifier (#53): the 7-day rolling lnRMSSD read against the HRV baseline's
/// smallest-worthwhile-change band (Plews / Buchheit / Altini). Inside the band it changes nothing; outside
/// it shifts the composite by `wHRVTrend` times the excess in σ. Mirrors the Kotlin ChargeHRVTrendTest,
/// which pins the same oracle literal.
final class ChargeHRVTrendTests: XCTestCase {

    /// A trusted log-domain HRV baseline: geometric mean `meanMs`, σ = `sigmaLn` in ln units.
    private func lnBaseline(_ meanMs: Double, sigmaLn: Double) -> BaselineState {
        BaselineState(baseline: meanMs, spread: sigmaLn / 1.253, nValid: 30, nightsSinceUpdate: 1,
                      status: .trusted, logDomain: true)
    }

    private func score(_ hrv: Double, prior: [Double], base: BaselineState) -> Double? {
        RecoveryScorer.recovery(hrv: hrv, rhr: 55, resp: nil, hrvBaseline: base, rhrBaseline: nil,
                                respBaseline: nil, sleepPerf: 0.85, hrvPriorWeek: prior)
    }

    func testInsideTheBandChangesNothing() {
        let base = lnBaseline(50, sigmaLn: 0.2)
        // A week sitting on the baseline: the rolling mean is inside ±0.5σ.
        let prior = [49.0, 51.0, 50.0, 52.0, 48.0, 50.0]
        XCTAssertEqual(RecoveryScorer.hrvTrend(hrv: 50, priorWeek: prior, baseline: .init(base))?.z, 0)
        XCTAssertEqual(score(50, prior: prior, base: base), score(50, prior: [], base: base))
    }

    func testASuppressedWeekLowersChargeAndARaisedWeekLiftsIt() {
        let base = lnBaseline(50, sigmaLn: 0.2)
        let neutral = score(50, prior: [], base: base)!
        let low = score(50, prior: Array(repeating: 35.0, count: 6), base: base)!
        let high = score(50, prior: Array(repeating: 70.0, count: 6), base: base)!
        XCTAssertLessThan(low, neutral)
        XCTAssertGreaterThan(high, neutral)
        let lowTrend = RecoveryScorer.hrvTrend(hrv: 50, priorWeek: Array(repeating: 35.0, count: 6),
                                               baseline: .init(base))!
        XCTAssertLessThan(lowTrend.z, 0)
        XCTAssertLessThan(lowTrend.rollingMs, lowTrend.normalLowMs)
    }

    func testTooFewNightsOrALinearBaselineReadsNoTrend() {
        let base = lnBaseline(50, sigmaLn: 0.2)
        XCTAssertNil(RecoveryScorer.hrvTrend(hrv: 30, priorWeek: [30], baseline: .init(base)))
        XCTAssertNotNil(RecoveryScorer.hrvTrend(hrv: 30, priorWeek: [30, 30], baseline: .init(base)))
        let linear = RecoveryScorer.DriverBaseline(mean: 50, spread: 5, logDomain: false)
        XCTAssertNil(RecoveryScorer.hrvTrend(hrv: 30, priorWeek: [30, 30, 30], baseline: linear))
    }

    func testTheModifierIsClamped() {
        let base = lnBaseline(50, sigmaLn: 0.05)
        let t = RecoveryScorer.hrvTrend(hrv: 10, priorWeek: Array(repeating: 10.0, count: 6), baseline: .init(base))!
        XCTAssertEqual(t.z, -RecoveryScorer.termZClamp)
    }

    /// The breakdown names the trend whenever it was read, worth exactly what it moved the score.
    func testTheDriverRowMatchesTheHeadline() {
        let base = lnBaseline(50, sigmaLn: 0.2)
        let prior = Array(repeating: 35.0, count: 6)
        let drivers = RecoveryScorer.chargeDrivers(hrv: 50, rhr: 55, resp: nil, hrvBaseline: base,
                                                   rhrBaseline: nil, respBaseline: nil, sleepPerf: 0.85,
                                                   hrvPriorWeek: prior)
        let row = drivers.first { $0.label == "HRV 7-day trend" }
        XCTAssertNotNil(row)
        let full = score(50, prior: prior, base: base)!
        let without = score(50, prior: [], base: base)!
        XCTAssertEqual(row?.deltaPoints, Int((full - without).rounded(.toNearestOrAwayFromZero)))
        XCTAssertEqual(row?.verdict, "7-day average below your normal range, a sign of accumulated fatigue")

        let inside = RecoveryScorer.chargeDrivers(hrv: 50, rhr: 55, resp: nil, hrvBaseline: base,
                                                  rhrBaseline: nil, respBaseline: nil, sleepPerf: 0.85,
                                                  hrvPriorWeek: [50, 50, 50])
        let insideRow = inside.first { $0.label == "HRV 7-day trend" }
        XCTAssertEqual(insideRow?.deltaPoints, 0)
        XCTAssertEqual(insideRow?.verdict, "7-day average inside your normal range")

        let none = RecoveryScorer.chargeDrivers(hrv: 50, rhr: 55, resp: nil, hrvBaseline: base,
                                                rhrBaseline: nil, respBaseline: nil, sleepPerf: 0.85)
        XCTAssertNil(none.first { $0.label == "HRV 7-day trend" }, "no trend read, no row")
    }

    func testTheTraceNamesTheTrendAndMatchesTheScore() {
        let base = lnBaseline(50, sigmaLn: 0.2)
        let prior = Array(repeating: 70.0, count: 6)
        let (traced, lines) = RecoveryScorer.recoveryTrace(hrv: 50, rhr: 55, resp: nil, hrvBaseline: base,
                                                           rhrBaseline: nil, respBaseline: nil,
                                                           sleepPerf: 0.85, hrvPriorWeek: prior)
        XCTAssertEqual(traced, score(50, prior: prior, base: base))
        XCTAssertTrue(lines.contains { $0.hasPrefix("charge trend hrv7=") && $0.contains("nights=7") })
    }

    /// The prior week is the six calendar days before the scored day, valid nights only, era cut included.
    func testThePriorWeekIsTheSixCalendarDaysBefore() {
        let day = "2026-03-10"
        let anchor = Baselines.isoEpochDay(day)!
        let nights: [(day: String, value: Double?)] = (1...20).map { back in
            (day: Baselines.isoDay(fromEpochDay: anchor - back), value: back == 2 ? 999 : Double(40 + back))
        }
        let r = ChargeBaselines.resolveDay(scoredDay: day, imported: .init(), own: .init(hrv: nights),
                                           seed: .init(), sourceDays: [], hrvRecalibration: 0,
                                           recoveryRecalibration: 0)!
        // Days -1..-6; day -2 is out of range (999 ms) and drops.
        XCTAssertEqual(r.hrvPriorWeek.sorted(), [41, 43, 44, 45, 46])

        let cutDay = Baselines.isoDay(fromEpochDay: anchor - 3)
        let epoch = Double(anchor - 3) * 86_400
        let cut = ChargeBaselines.resolveDay(scoredDay: day, imported: .init(), own: .init(hrv: nights),
                                             seed: .init(), sourceDays: [], hrvRecalibration: epoch,
                                             recoveryRecalibration: 0)!
        XCTAssertEqual(cut.epochs.hrv, epoch, "recalibrated on \(cutDay)")
        XCTAssertEqual(cut.hrvPriorWeek.sorted(), [41, 43])
    }

    // MARK: - Oracle (Swift prints, Kotlin pins the same text)

    static func oracleText() -> String {
        var out: [String] = []
        let means: [Double] = [30, 55, 90]
        let sigmas: [Double] = [0.05, 0.15, 0.4]
        let tonights: [Double] = [20, 45, 60, 120]
        let weeks: [[Double]] = [[], [40], [40, 42], [25, 30, 28, 35, 31, 27], [80, 95, 70, 88, 101, 76],
                                 [50, 55, 60, 52, 58, 54]]
        for m in means {
            for sg in sigmas {
                let base = BaselineState(baseline: m, spread: sg / 1.253, nValid: 30, nightsSinceUpdate: 1,
                                         status: .trusted, logDomain: true)
                for t in tonights {
                    for (wi, w) in weeks.enumerated() {
                        let trend = RecoveryScorer.hrvTrend(hrv: t, priorWeek: w, baseline: .init(base))
                        let s = RecoveryScorer.recovery(hrv: t, rhr: 55, resp: nil, hrvBaseline: base,
                                                        rhrBaseline: nil, respBaseline: nil, sleepPerf: 0.8,
                                                        hrvPriorWeek: w)
                        let tz = trend.map { String(format: "%.6f", $0.z) } ?? "nil"
                        let roll = trend.map { String(format: "%.6f", $0.rollingMs) } ?? "nil"
                        out.append("m=\(Int(m)) s=\(sg) t=\(Int(t)) w=\(wi) z=\(tz) roll=\(roll) "
                            + "score=\(s.map { String(format: "%.6f", $0) } ?? "nil")")
                    }
                }
            }
        }
        return out.joined(separator: "\n")
    }

    func testOracle() {
        let text = Self.oracleText()
        if ProcessInfo.processInfo.environment["PRINT_ORACLE"] != nil { print("ORACLE_BEGIN\n\(text)\nORACLE_END") }
        XCTAssertEqual(text.split(separator: "\n").count, 3 * 3 * 4 * 6)
        XCTAssertEqual(Self.fnv1a(text), Self.expectedOracleHash)
    }

    /// FNV-1a 64 over the UTF-8 oracle text; the Kotlin test pins the full text and the same hash.
    static func fnv1a(_ s: String) -> UInt64 {
        var h: UInt64 = 0xcbf29ce484222325
        for b in s.utf8 { h ^= UInt64(b); h = h &* 0x100000001b3 }
        return h
    }

    static let expectedOracleHash: UInt64 = 13596742503003570243
}
