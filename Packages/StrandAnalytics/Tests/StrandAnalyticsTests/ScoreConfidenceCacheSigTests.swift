import XCTest
@testable import StrandAnalytics

/// #1005 (backfill storm): the Charge confidence tier (`ScoreConfidence.charge`) reads ONLY the HRV
/// baseline's usable/trusted STATUS, never its value. This pins that property: the charge tier is
/// invariant to the baseline's VALUE (baseline/spread) and changes only on a genuine STATUS/tier
/// transition. (Since #53 pass 1 reads no baselines at all, so no cache signature carries one.) Twin of
/// the Android ScoreConfidenceCacheSigTest.
final class ScoreConfidenceCacheSigTests: XCTestCase {

    private func hrv(_ baseline: Double, _ spread: Double, _ nValid: Int,
                     _ status: BaselineStatus) -> BaselineState {
        BaselineState(baseline: baseline, spread: spread, nValid: nValid,
                      nightsSinceUpdate: 0, status: status)
    }

    func testChargeTierInvariantToBaselineValueDrift() {
        // Same status (trusted), different value — exactly how a rolling baseline moves as a backfill folds
        // in nights. The tier (and thus the cache signature contribution) must NOT change.
        let a = hrv(45, 6, 30, .trusted)
        let b = hrv(52, 9, 41, .trusted)
        XCTAssertEqual(ScoreConfidence.charge(recovery: 1.0, hrvBaseline: a), .solid)
        XCTAssertEqual(ScoreConfidence.charge(recovery: 1.0, hrvBaseline: a),
                       ScoreConfidence.charge(recovery: 1.0, hrvBaseline: b),
                       "a value-only baseline drift must not change the charge tier (it would drop the #1005 cache)")
    }

    func testChargeTierChangesOnStatusTransition() {
        // A real status transition MUST change the tier, so the cache correctly drops and chargeConfidence
        // is re-derived. (usable=false → calibrating; usable & !trusted → building; trusted → solid.)
        let trusted = hrv(45, 6, 30, .trusted)
        let provisional = hrv(45, 6, 6, .provisional)
        let calibrating = hrv(45, 6, 2, .calibrating)
        XCTAssertEqual(ScoreConfidence.charge(recovery: 1.0, hrvBaseline: trusted), .solid)
        XCTAssertEqual(ScoreConfidence.charge(recovery: 1.0, hrvBaseline: provisional), .building)
        XCTAssertEqual(ScoreConfidence.charge(recovery: 1.0, hrvBaseline: calibrating), .calibrating)
        XCTAssertNotEqual(ScoreConfidence.charge(recovery: 1.0, hrvBaseline: trusted),
                          ScoreConfidence.charge(recovery: 1.0, hrvBaseline: provisional))
    }
}
