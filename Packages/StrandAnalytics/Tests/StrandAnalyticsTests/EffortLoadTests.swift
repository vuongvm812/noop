import XCTest
import WhoopProtocol
import WhoopStore
@testable import StrandAnalytics

/// #54 — training load on TRIMP, EWMA ACWR over calendar days, the cadence-aware gap cap and the adaptive
/// HRmax resolver. The `…MatchesOracle` literals are the same lines the Kotlin `EffortLoadOracleTest` pins,
/// produced by compiling these Swift helpers standalone.
final class EffortLoadTests: XCTestCase {
    private func f(_ v: Double?) -> String { v.map { String(format: "%.9f", $0) } ?? "nil" }

    private func key(_ o: Int) -> String {
        let z = o + 719468; let era = (z >= 0 ? z : z - 146096) / 146097
        let doe = z - era * 146097; let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        var y = yoe + era * 400; let doy = doe - (365 * yoe + yoe / 4 - yoe / 100); let mp = (5 * doy + 2) / 153
        let d = doy - (153 * mp + 2) / 5 + 1; let m = mp < 10 ? mp + 3 : mp - 9; if m <= 2 { y += 1 }
        return String(format: "%04d-%02d-%02d", y, m, d)
    }

    func testTrimpFromEffortMatchesOracle() {
        let line = [0.0, 0.01, 12.34, 50.0, 77.77, 99.99, 100.0, -1.0]
            .map { f(EffortLoad.trimp(fromEffort: $0)) }.joined(separator: " ")
        XCTAssertEqual("TRIMP " + line,
            "TRIMP 0.000000000 0.000888592 1.992280297 83.858706094 998.756851374 7193.606929245 7200.000000000 nil")
    }

    func testTrimpInvertsTheEffortMap() {
        for trimp in [0.5, 12.0, 85.0, 640.0, 3000.0] {
            let effort = StrainScorer.trimpToStrain(trimp)
            let back = EffortLoad.trimp(fromEffort: effort)!
            XCTAssertEqual(back, trimp, accuracy: (trimp + 1) * 0.00045)
        }
    }

    func testAcuteChronicAndMonotonyMatchOracle() {
        let base = TrainingLoadEngine.dayOrdinal("2026-01-01")!
        var rows: [(day: String, effort: Double?)] = []
        for i in 0..<100 {
            if i % 9 == 4 || i % 13 == 7 { continue }
            let e = 20.0 + Double((i * 37) % 53) + (i > 80 ? 15.0 : 0.0)
            rows.append((key(base + i), i == 50 ? nil : e))
        }
        let loads = EffortLoad.loadsByOrdinal(rows)
        var line = "ACWR"
        for end in [base + 10, base + 30, base + 60, base + 85, base + 99] {
            let ac = EffortLoad.acuteChronic(loads: loads, endOrdinal: end)
            line += " \(end - base):" + f(ac?.acute) + "/" + f(ac?.chronic) + "/" + f(ac?.ratio) + "/\(ac?.observedDays ?? -1)"
            line += "/m" + f(EffortLoad.monotony(loads: loads, endOrdinal: end))
        }
        XCTAssertEqual(line,
            "ACWR 10:nil/nil/nil/-1/m0.629627715 30:197.915131412/120.398695713/1.643831191/23/m0.912509327 "
            + "60:175.030607516/118.425777526/1.477977271/21/m1.078187855 "
            + "85:562.990796388/284.679142255/1.977632755/22/m0.725474255 "
            + "99:368.467696960/356.209605373/1.034412580/23/m0.695271585")
    }

    func testCadenceAwareGapCapMatchesOracle() {
        let gaps = [1, 1, 1, 1, 30, 30, 30, 300, 300, 300, 300, 300, 300, 3600, 300, 300, 180, 180, 180, 180, 0, 1, 1]
        var t = 0
        var hr: [HRSample] = []
        for g in gaps { hr.append(HRSample(ts: t, bpm: 100)); t += g }
        hr.append(HRSample(ts: t, bpm: 100))
        XCTAssertEqual("DUR " + StrainScorer.sampleDurationsMinutes(hr).map { f($0) }.joined(separator: " "),
            "DUR 0.016666667 0.016666667 0.016666667 0.016666667 0.500000000 0.500000000 0.500000000 "
            + "5.000000000 5.000000000 5.000000000 5.000000000 5.000000000 5.000000000 5.000000000 "
            + "5.000000000 5.000000000 3.000000000 3.000000000 3.000000000 3.000000000 0.016666667 "
            + "0.016666667 0.016666667 0.016666667")
    }

    /// A dense stream keeps the fixed 2 min cap: a dropout among 30 s readings is still cut to 2 min.
    func testDenseStreamDropoutStillCappedAtTwoMinutes() {
        var hr: [HRSample] = []
        var t = 0
        for i in 0..<20 { hr.append(HRSample(ts: t, bpm: 100)); t += (i == 10 ? 3_600 : 30) }
        let d = StrainScorer.sampleDurationsMinutes(hr)
        XCTAssertEqual(d[10], StrainScorer.maxSampleGapMin)
        XCTAssertEqual(d[9], 0.5)
    }

    func testHRmaxEvidenceAndResolverMatchOracle() {
        let evid = [[], [190], [190, 195], [230, 201, 199, 150], [Double.nan, 0, 200, 200]]
            .map { f(StrainScorer.observedHRmaxEvidence(dailyPeaks: $0)) }.joined(separator: " ")
        XCTAssertEqual("EVID " + evid, "EVID nil nil 190.000000000 199.000000000 200.000000000")
        let pk = ["2026-01-01": 205.0, "2026-03-31": 203.0, "2026-04-01": 199.0, "2025-12-31": 210.0, "bad": 215.0]
        XCTAssertEqual("WIN " + f(StrainScorer.observedHRmaxEvidence(peaksByDay: pk, endingOn: "2026-03-31")) + " "
                       + f(StrainScorer.observedHRmaxEvidence(peaksByDay: pk, endingOn: "2026-04-01")),
                       "WIN 203.000000000 199.000000000")
        var res = "RES"
        let cases: [(Double?, Double, Double?)] = [(180, 40, 200), (nil, 40, 200), (nil, 40, 170), (nil, 0, nil),
                                                   (nil, 0, 195), (nil, 0, 185), (0, 30, nil)]
        for (o, a, e) in cases {
            let r = StrainScorer.resolveHRmax(override: o, age: a, observedEvidence: e)
            res += " " + f(r.value) + ":" + r.source
        }
        XCTAssertEqual(res, "RES 180.000000000:override 200.000000000:observed 180.000000000:tanaka nil:default "
                       + "195.000000000:observed nil:default 187.000000000:tanaka")
    }

    /// Raised HRmax moves day Effort: a bigger reserve lowers the same day's score.
    func testAnalyzeDayScoresAgainstObservedEvidence() {
        var hr: [HRSample] = []
        for i in 0..<1_200 { hr.append(HRSample(ts: 1_000_000 + i, bpm: 160)) }
        let profile = UserProfile(age: 40)
        let tanaka = AnalyticsEngine.analyzeDay(day: "1970-01-12", hr: hr, rr: [], resp: [], gravity: [],
                                                profile: profile).daily.strain
        let raised = AnalyticsEngine.analyzeDay(day: "1970-01-12", hr: hr, rr: [], resp: [], gravity: [],
                                                profile: profile, hrmaxEvidence: 200).daily.strain
        XCTAssertNotNil(tanaka); XCTAssertNotNil(raised)
        XCTAssertLessThan(raised!, tanaka!)
    }

    private func row(_ day: String, strain: Double?) -> DailyMetric {
        DailyMetric(day: day, totalSleepMin: nil, efficiency: nil, deepMin: nil, remMin: nil, lightMin: nil,
                    disturbances: nil, restingHr: 52, avgHrv: 60, recovery: nil, strain: strain,
                    exerciseCount: nil, spo2Pct: nil, skinTempDevC: nil, respRateBpm: nil)
    }

    /// Today is a partial day: a near-zero morning Effort must not read as "ramping down".
    func testReadinessAcwrExcludesTodaysPartialDay() {
        let base = TrainingLoadEngine.dayOrdinal("2026-02-01")!
        var days = (0..<28).map { row(key(base + $0), strain: 40) }
        days.append(row(key(base + 28), strain: 0.5))
        let r = ReadinessEngine.evaluate(days: days, today: key(base + 28))
        XCTAssertEqual(r.acwr!, 1.0, accuracy: 1e-12)
        XCTAssertEqual(r.signals.first { $0.key == "acwr" }?.flag, .good)
    }

    /// The window is calendar-bounded: 14 rows spread over months are not "28 days of history".
    func testReadinessAcwrNeedsCalendarCoverage() {
        let base = TrainingLoadEngine.dayOrdinal("2026-01-01")!
        var days = (0..<20).map { row(key(base + $0 * 5), strain: 40) }
        days.append(row(key(base + 101), strain: 40))
        XCTAssertNil(ReadinessEngine.evaluate(days: days, today: key(base + 101)).acwr)
    }

    /// Load reacts on the linear axis: Effort 60 vs 40 is ~2.8x the work, not 1.5x.
    func testReadinessAcwrReadsTrimpNotEffort() {
        let base = TrainingLoadEngine.dayOrdinal("2026-02-01")!
        var days = (0..<21).map { row(key(base + $0), strain: 40) }
        days += (21..<28).map { row(key(base + $0), strain: 60) }
        days.append(row(key(base + 28), strain: 40))
        let r = ReadinessEngine.evaluate(days: days, today: key(base + 28))
        XCTAssertGreaterThan(r.acwr!, 1.5)
        XCTAssertEqual(r.signals.first { $0.key == "acwr" }?.flag, .bad)
    }
}
