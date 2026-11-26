import XCTest
import StrandAnalytics
@testable import Strand

/// The view-layer renderer must read exactly like the (parity-pinned) engine sentences in English.
final class InsightsSentencesTests: XCTestCase {

    override func setUpWithError() throws {
        try XCTSkipUnless(Locale.preferredLanguages.first?.hasPrefix("en") ?? false,
                          "English-equality oracle: only meaningful when the app resolves to English")
    }

    private func effect(delta: Double, pct: Double?, withMean: Double = 61, without: Double = 69) -> BehaviorEffect {
        BehaviorEffect(behavior: "Alcohol", outcome: "Charge", meanWith: withMean, meanWithout: without,
                       delta: delta, pctChange: pct, nWith: 140, nWithout: 498,
                       cohensD: 0.4, pApprox: 0.01, significant: true)
    }

    func testEffectMatchesEngine() {
        let cases = [
            effect(delta: -8, pct: -11.6),
            effect(delta: 8, pct: 12.4, withMean: 69, without: 61),
            effect(delta: -2.46, pct: nil),
            effect(delta: 3.04, pct: nil),
            effect(delta: 0, pct: 0, withMean: 65, without: 65),
        ]
        for e in cases {
            XCTAssertEqual(InsightsSentences.effect(e), BehaviorInsights.sentence(e))
        }
    }

    func testActivityCostMatchesEngine() {
        let cases: [(Double, Int?)] = [(0.4, nil), (-0.9, 2), (4.2, 3), (1.0, 1), (6.0, nil), (-3.0, 1), (-2.6, nil)]
        for (delta, days) in cases {
            let c = ActivityCost(sport: "Running", delta: delta, meanNextMorning: 60, baselineMean: 64,
                                 daysToBaseline: days, n: 7, confidence: .solid)
            XCTAssertEqual(InsightsSentences.activityCost(c), c.sentence(), "delta \(delta) days \(String(describing: days))")
        }
    }

    func testRankedMatchesEngine() {
        for lag in [0, 1, 2, 3] {
            let r = RankedEffect(behavior: "Alcohol", outcome: "Charge", lag: lag,
                                 effect: effect(delta: -8, pct: -11.6), confidence: .building)
            XCTAssertEqual(InsightsSentences.ranked(r), r.sentence())
        }
    }

    func testDoseMatchesEngine() {
        func resp(perUnit: Double, prior: Bool, contradicts: Bool) -> DoseResponse {
            DoseResponse(behavior: .alcohol, outcome: "Charge", perUnit: perUnit, userSlope: nil, priorSlope: -3,
                         weight: 0.5, nUser: 6, priorDominated: prior, contradictsPrior: contradicts,
                         confidence: .building, curve: [])
        }
        for (u, p, c) in [(-3.0, true, false), (2.5, true, false), (-4.26, false, false),
                          (1.0, false, false), (0.0, false, false), (3.0, false, true)] {
            let r = resp(perUnit: u, prior: p, contradicts: c)
            XCTAssertEqual(InsightsSentences.dose(r), r.sentence(), "perUnit \(u)")
        }
    }

    private func autoEffect(_ key: String, _ outcome: String, _ delta: Double, _ pct: Double?) -> BehaviorEffect {
        BehaviorEffect(behavior: key, outcome: outcome, meanWith: 55.4, meanWithout: 60.2, delta: delta,
                       pctChange: pct, nWith: 26, nWithout: 67, cohensD: -1.2, pApprox: 0.001, significant: true)
    }

    /// Automatic factors read as "group vs control". Kotlin `InsightsSentencesTest` pins the same English.
    func testAutoFactorSentencesAndTodayLine() {
        let late = AutoFactors.Factor.lateWorkout.key
        XCTAssertEqual(InsightsSentences.effect(autoEffect(late, "HRV", -4.8, -7.97)),
                       "‘Late workout’ vs ‘Earlier workout’: HRV was 8% lower (avg 55 vs 60, n=26 vs 67).")
        XCTAssertEqual(InsightsSentences.effect(autoEffect(AutoFactors.Factor.highSteps.key, "Rest", 3.46, nil)),
                       "‘High steps’ vs ‘Usual steps’: Rest was 3.5 higher (avg 55 vs 60, n=26 vs 67).")
        XCTAssertEqual(InsightsSentences.effect(autoEffect(AutoFactors.Factor.restDay.key, "Charge", 0, 0)),
                       "‘Rest day’ vs ‘Workout day’: Charge was no different (avg 55 vs 60, n=26 vs 67).")
        let row = RankedEffect(behavior: late, outcome: "HRV", lag: 1,
                               effect: autoEffect(late, "HRV", -4.8, -7.97), confidence: .solid)
        XCTAssertEqual(InsightsSentences.todayLine(row), "Late workout → HRV \u{2212}8% next morning")
        let journal = RankedEffect(behavior: "Alcohol", outcome: "Rest", lag: 2,
                                   effect: autoEffect("Alcohol", "Rest", 3.46, nil), confidence: .building)
        XCTAssertEqual(InsightsSentences.todayLine(journal), "Alcohol → Rest +3.5 2 mornings later")
        XCTAssertEqual(InsightsSentences.behaviorLabel("auto.lateBedtime"), "Late bedtime")
        XCTAssertEqual(InsightsSentences.behaviorLabel("Alcohol"), "Alcohol")
    }
}
