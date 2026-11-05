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
}
