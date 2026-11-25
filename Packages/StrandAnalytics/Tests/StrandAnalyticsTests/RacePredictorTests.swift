import XCTest
@testable import StrandAnalytics

/// Race predictions (Riegel over whole runs). Every expected line is the stdout of this file's fixtures run
/// through `RacePredictor.swift` compiled standalone with `swiftc -O`, and the Kotlin `RacePredictorOracleTest`
/// pins the same literals from the other side.
final class RacePredictorTests: XCTestCase {
    private typealias R = RacePredictor

    private func f(_ v: Double?) -> String { v.map { String(format: "%.9f", $0) } ?? "nil" }

    private func p(_ x: R.Prediction?) -> String {
        guard let x else { return "nil" }
        return "\(x.targetIndex):\(x.seconds)@\(x.sourceStartTs)/\(f(x.sourceDistanceM))/\(f(x.sourceDurationS))"
    }

    private func line(_ items: [R.Prediction?]) -> String { items.map { p($0) }.joined(separator: " ") }

    private func run(_ start: Int, _ sport: String, _ d: Double?, _ dur: Double?, end: Int? = nil) -> R.Run {
        let e: Int
        if let end { e = end } else if let dur, dur.isFinite { e = start + Int(dur) } else { e = start }
        return R.Run(startTs: start, endTs: e, sport: sport, distanceM: d, durationS: dur)
    }

    private let now = 1_800_000_000
    private let day = 86_400

    func testRiegelValues() {
        let line = [
            R.riegel(timeS: 1500, fromM: 5000, toM: 10000),
            R.riegel(timeS: 3000, fromM: 10000, toM: 42195),
            R.riegel(timeS: 1200, fromM: 4000, toM: 5000),
            R.riegel(timeS: 7200, fromM: 21097.5, toM: 5000),
            R.riegel(timeS: 1500, fromM: 5000, toM: 5000),
        ].map { f($0) }.joined(separator: " ")
        XCTAssertEqual("RIEGEL " + line,
                       "RIEGEL 3127.397282523 13800.597999770 1520.217962846 1565.149568183 1500.000000000")
    }

    /// Walks, a 2.9 km run, an 11 min run, an 8 m/s run, a missing / NaN distance and a slow 1.4 m/s jog are
    /// rejected; a missing, zero, infinite or negative duration falls back to the end − start span.
    func testQualifyingFilters() {
        let qual: [R.Run] = [
            run(0, "Running", 5000, 1500),
            run(0, "Walking", 5000, 1500),
            run(0, "Running", 2900, 1200),
            run(0, "Running", 3000, 660),
            run(0, "Running", 9600, 1200),
            run(0, " TREADMILL RUN\n", 5000, 1800),
            run(1000, "Running", 5000, nil, end: 2500),
            run(0, "Running", nil, 1500),
            run(1000, "running", 5000, 0, end: 2600),
            run(0, "Hiking", 8000, 3600),
            run(0, "Running", 5000, 3600),
            run(0, "Running", 4500, 3000),
            run(0, "Running", 7000, 1000),
            run(0, "Running", Double.nan, 1500),
            run(0, "Trail running", 5000, 1500),
            run(0, "Running", 5000, Double.infinity, end: 1500),
            run(0, "Running", 5000, -10, end: 1500),
        ]
        XCTAssertEqual("QUAL " + qual.map { R.qualifies($0) ? "1" : "0" }.joined(), "QUAL 10000110100110011")
        XCTAssertEqual(
            "DUR " + qual.map { f(R.effectiveDurationS($0)) }.joined(separator: " "),
            "DUR 1500.000000000 1500.000000000 1200.000000000 660.000000000 1200.000000000 1800.000000000 " +
                "1500.000000000 1500.000000000 1600.000000000 3600.000000000 3600.000000000 3000.000000000 " +
                "1000.000000000 1500.000000000 1500.000000000 1500.000000000 1500.000000000")
        XCTAssertEqual(
            "SPORT " + ["Running", "running", "  Running\t", "Treadmill run", "TREADMILL RUN", "Treadmill walk",
                        "Run", "Trail running", ""].map { R.isRunningSport($0) ? "1" : "0" }.joined(),
            "SPORT 111110000")
    }

    /// A 5K run stops at the 10K (4× cap); a 10.5 km run is just short of the marathon; 10.6 km reaches it.
    func testForRunAppliesTheExtrapolationCap() {
        let runs = [run(0, "Running", 5000, 1500), run(0, "Running", 10500, 3300), run(0, "Running", 10600, 3300),
                    run(0, "Running", 21097.5, 6300), run(0, "Running", 42195, 13500),
                    run(0, "Walking", 10000, 4000), run(0, "Running", 3000, 900)]
        XCTAssertEqual(runs.map { "FOR " + line(R.forRun($0)) }, [
            "FOR 0:1500@0/5000.000000000/1500.000000000 1:3127@0/5000.000000000/1500.000000000 nil nil",
            "FOR 0:1503@0/10500.000000000/3300.000000000 1:3134@0/10500.000000000/3300.000000000 " +
                "2:6914@0/10500.000000000/3300.000000000 nil",
            "FOR 0:1488@0/10600.000000000/3300.000000000 1:3102@0/10600.000000000/3300.000000000 " +
                "2:6845@0/10600.000000000/3300.000000000 3:14271@0/10600.000000000/3300.000000000",
            "FOR 0:1370@0/21097.500000000/6300.000000000 1:2855@0/21097.500000000/6300.000000000 " +
                "2:6300@0/21097.500000000/6300.000000000 3:13135@0/21097.500000000/6300.000000000",
            "FOR 0:1408@0/42195.000000000/13500.000000000 1:2935@0/42195.000000000/13500.000000000 " +
                "2:6475@0/42195.000000000/13500.000000000 3:13500@0/42195.000000000/13500.000000000",
            "FOR nil nil nil nil",
            "FOR 0:1547@0/3000.000000000/900.000000000 1:3225@0/3000.000000000/900.000000000 nil nil",
        ])
    }

    /// Fastest wins across runs inside the 90-day window (the 10K beats the slower 5K at 5K); the old fast
    /// half outside the window is ignored while a recent run qualifies, and used (flagged) when none does.
    func testWindowFallbackAndFastestWins() {
        let mixed: [R.Run] = [
            run(now - 10 * day, "Running", 5000, 1560),
            run(now - 20 * day, "Running", 10000, 3000),
            run(now - 30 * day, "Running", 15000, 4800),
            run(now - 40 * day, "Walking", 12000, 2000),
            run(now - 200 * day, "Running", 21097.5, 5400),
            run(now - 5 * day, "Running", 2000, 500),
        ]
        let res = R.predictions(runs: mixed, nowTs: now)
        XCTAssertEqual("MIX " + (res.fromAllHistory ? "all" : "recent") + " " + line(res.items),
                       "MIX recent 0:1439@1798272000/10000.000000000/3000.000000000 " +
                           "1:3000@1798272000/10000.000000000/3000.000000000 " +
                           "2:6619@1798272000/10000.000000000/3000.000000000 " +
                           "3:14367@1797408000/15000.000000000/4800.000000000")
        let old: [R.Run] = [
            run(now - 200 * day, "Running", 21097.5, 5400),
            run(now - 400 * day, "Running", 5000, 1200),
            run(now - 91 * day, "Running", 8000, 2600),
        ]
        let resOld = R.predictions(runs: old, nowTs: now)
        XCTAssertEqual("OLD " + (resOld.fromAllHistory ? "all" : "recent") + " " + line(resOld.items),
                       "OLD all 0:1174@1782720000/21097.500000000/5400.000000000 " +
                           "1:2447@1782720000/21097.500000000/5400.000000000 " +
                           "2:5400@1782720000/21097.500000000/5400.000000000 " +
                           "3:11259@1782720000/21097.500000000/5400.000000000")
        let edge = R.predictions(runs: [run(now - 90 * day, "Running", 5000, 1500)], nowTs: now)
        XCTAssertEqual("EDGE " + (edge.fromAllHistory ? "all" : "recent") + " " + line(edge.items),
                       "EDGE recent 0:1500@1792224000/5000.000000000/1500.000000000 " +
                           "1:3127@1792224000/5000.000000000/1500.000000000 nil nil")
        let none = R.predictions(runs: [run(now, "Cycling", 30000, 3600)], nowTs: now)
        XCTAssertEqual("NONE " + (none.fromAllHistory ? "all" : "recent") + " " + line(none.items),
                       "NONE recent nil nil nil nil")
        XCTAssertEqual("PACE " + res.items.map { $0.map { f(R.secPerKm($0)) } ?? "nil" }.joined(separator: " "),
                       "PACE 287.800000000 300.000000000 313.733854722 340.490579453")
    }

    /// An exact tie goes to the newer run whatever the input order.
    func testTieBreakIsOrderIndependent() {
        let ties = [run(now - 3 * day, "Running", 5000, 1500), run(now - 2 * day, "Running", 5000, 1500),
                    run(now - 4 * day, "Running", 4000, 1500)]
        let a = R.predictions(runs: ties, nowTs: now)
        let b = R.predictions(runs: ties.reversed(), nowTs: now)
        XCTAssertEqual("TIE " + line(a.items) + " | " + line(b.items),
                       "TIE 0:1500@1799827200/5000.000000000/1500.000000000 " +
                           "1:3127@1799827200/5000.000000000/1500.000000000 nil nil | " +
                           "0:1500@1799827200/5000.000000000/1500.000000000 " +
                           "1:3127@1799827200/5000.000000000/1500.000000000 nil nil")
    }

    /// Adding a faster run moves the prediction; removing it restores the previous one.
    func testAddingAndRemovingAFasterRunMovesThePrediction() {
        let base = [run(now - 10 * day, "Running", 5000, 1560)]
        let before = R.predictions(runs: base, nowTs: now).items[0]?.seconds
        let faster = base + [run(now - 1 * day, "Running", 5000, 1440)]
        XCTAssertEqual(R.predictions(runs: faster, nowTs: now).items[0]?.seconds, 1440)
        XCTAssertEqual(R.predictions(runs: base, nowTs: now).items[0]?.seconds, before)
        XCTAssertEqual(before, 1560)
    }

    func testMinRunForEachTarget() {
        XCTAssertEqual("MINRUN " + [-1, 0, 1, 2, 3, 4].map { f(R.minRunM(targetIndex: $0)) }.joined(separator: " "),
                       "MINRUN 0.000000000 1250.000000000 2500.000000000 5274.375000000 10548.750000000 0.000000000")
    }

    func testFormatTime() {
        XCTAssertEqual("FMT " + [0, 59, 600, 1499, 3599, 3600, 3661, 10_000, 36_000, -5]
                        .map { R.formatTime($0) }.joined(separator: " "),
                       "FMT 0:00 0:59 10:00 24:59 59:59 1:00:00 1:01:01 2:46:40 10:00:00 0:00")
    }
}
