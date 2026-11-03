import XCTest
@testable import StrandAnalytics

final class StatTestsTests: XCTestCase {

    // df = 1 and df = 2 have closed forms; the incomplete-beta path must reproduce both.
    func testStudentTMatchesClosedForms() {
        for t in [0.1, 0.5, 1.0, 2.0, 3.5, 10.0] {
            let df1 = 1.0 - 2.0 / Double.pi * atan(t)
            XCTAssertEqual(StatTests.studentTTwoSidedP(t: t, df: 1), df1, accuracy: 1e-12, "df=1 t=\(t)")
            let df2 = 1.0 - t / (2.0 + t * t).squareRoot()
            XCTAssertEqual(StatTests.studentTTwoSidedP(t: t, df: 2), df2, accuracy: 1e-12, "df=2 t=\(t)")
        }
        XCTAssertEqual(StatTests.studentTTwoSidedP(t: 0, df: 5), 1.0)
        XCTAssertEqual(StatTests.studentTTwoSidedP(t: -2, df: 8), StatTests.studentTTwoSidedP(t: 2, df: 8))
        XCTAssertEqual(StatTests.studentTTwoSidedP(t: .infinity, df: 8), 0.0)
        XCTAssertEqual(StatTests.studentTTwoSidedP(t: 2, df: 0), 1.0)
    }

    // The ticket's example: r = 0.8 over 5 pairs is NOT p < 0.05 (the normal tail said ≈ 0.02).
    func testSmallSampleCorrelationIsNotSignificant() {
        let p = StatTests.correlationP(r: 0.8, n: 5)
        XCTAssertEqual(p, 0.1041, accuracy: 1e-4)
        XCTAssertGreaterThan(p, 0.05)
        XCTAssertEqual(StatTests.correlationP(r: 1, n: 12), 0)
        XCTAssertEqual(StatTests.correlationP(r: 0.5, n: 2), 1)
    }

    func testWelchUsesSatterthwaiteDf() {
        // Equal n and variances: Welch df = 2(n−1) = 8, t = −2 / sqrt(29/5).
        let p = StatTests.welchP(m1: 50, v1: 4, n1: 5, m2: 52, v2: 25, n2: 5)
        let a = 4.0 / 5, b = 25.0 / 5
        let df = (a + b) * (a + b) / (a * a / 4 + b * b / 4)
        XCTAssertEqual(p, StatTests.studentTTwoSidedP(t: -2 / (a + b).squareRoot(), df: df), accuracy: 0)
        XCTAssertEqual(StatTests.welchP(m1: 3, v1: 0, n1: 4, m2: 3, v2: 0, n2: 4), 1)
        XCTAssertEqual(StatTests.welchP(m1: 3, v1: 0, n1: 4, m2: 4, v2: 0, n2: 4), 0)
    }

    func testBenjaminiHochberg() {
        // Classic worked example; q keeps input order and is monotone in p.
        let q = StatTests.benjaminiHochberg([0.01, 0.04, 0.03, 0.2, 0.04, 0.5])
        XCTAssertEqual(q.map { ($0 * 1e6).rounded() / 1e6 }, [0.06, 0.06, 0.06, 0.24, 0.06, 0.5])
        XCTAssertEqual(StatTests.benjaminiHochberg([]), [])
        XCTAssertEqual(StatTests.benjaminiHochberg([0.03]), [0.03])
    }

    func testRanksAndAutocorrelation() {
        XCTAssertEqual(StatTests.averageRanks([3, 1, 4, 1, 5]), [3, 1.5, 4, 1.5, 5])
        XCTAssertEqual(StatTests.lag1Autocorrelation([1, 1, 1]), 0)
        XCTAssertLessThan(StatTests.lag1Autocorrelation([1, -1, 1, -1, 1, -1]), 0)
        XCTAssertEqual(StatTests.effectiveN(n: 20, rx: 0, ry: 0.9), 20)
        XCTAssertEqual(StatTests.effectiveN(n: 20, rx: -0.5, ry: 0.5), 20, "never inflates n")
        XCTAssertEqual(StatTests.effectiveN(n: 20, rx: 0.5, ry: 0.5), 20 * 0.75 / 1.25, accuracy: 1e-12)
    }

    // A strongly persistent pair of series loses effective n, so its p rises.
    func testCorrelationAppliesEffectiveN() throws {
        let x: [Double] = (0..<20).map { Double($0) }
        let y: [Double] = x.map { $0 * 2 + ($0.truncatingRemainder(dividingBy: 3)) }
        let c = try XCTUnwrap(CorrelationEngine.pearson(Array(zip(x, y))))
        XCTAssertLessThan(c.nEffective, 20)
        XCTAssertEqual(c.pApprox, StatTests.correlationP(r: c.r, n: c.nEffective), accuracy: 0)
    }

    func testBelowMinPairsOffersNoInference() throws {
        let xy: [(Double, Double)] = [(1, 2), (2, 4), (3, 5), (4, 9), (5, 9)]
        let c = try XCTUnwrap(CorrelationEngine.pearson(xy))
        XCTAssertEqual(c.pApprox, 1.0)
        XCTAssertGreaterThan(c.r, 0.9, "r and the line are still returned for slope-only callers")
    }

    // One outlier night drives Pearson; Spearman stays on the ranks.
    func testSpearmanIsRobustToAnOutlier() throws {
        var xy: [(Double, Double)] = (0..<12).map { (Double($0), Double(12 - $0)) }
        xy.append((100, 100))
        let p = try XCTUnwrap(CorrelationEngine.pearson(xy))
        let s = try XCTUnwrap(CorrelationEngine.spearman(xy))
        XCTAssertGreaterThan(p.r, 0.5)
        XCTAssertLessThan(s.r, 0)
        XCTAssertEqual(s.method, .spearman)
    }
}
