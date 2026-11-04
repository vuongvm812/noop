import XCTest
@testable import StrandDesign

/// Pins `ChartScrubMath` (#58). The expected table is the verbatim stdout of the standalone oracle
/// (`swiftc -O twin.swift main.swift`) over these cases, and the SAME table is the expected literal in
/// Android's `ChartScrubResolveTest`, so either side drifting fails its own suite.
final class ChartScrubMathTests: XCTestCase {

    static let expected = """
empty cad=0 nil
single cad=0 idx=0 gap=nil
single-after cad=0 idx=0 gap=nil
daily-on cad=86400 idx=1 gap=nil
daily-tie cad=86400 idx=0 gap=nil
daily-gap-mid cad=86400 idx=1 gap=172800
daily-gap-edge cad=86400 idx=1 gap=nil
daily-gap-justin cad=86400 idx=1 gap=172800
daily-gap-long cad=86400 idx=1 gap=259200
before-first cad=10 idx=0 gap=nil
after-last cad=10 idx=2 gap=nil
jitter cad=300 idx=1 gap=nil
dup-instant cad=0 idx=0 gap=nil
dst-23h cad=86400 idx=1 gap=nil
hr-1hz-hole cad=1 idx=3 gap=6
negative cad=100 idx=1 gap=nil
"""

    func testResolveMatchesOracleTable() {
        let D: Int64 = 86400
        let cases: [(String, [Int64], Int64)] = [
        ("empty", [], 5),
        ("single", [100], 0),
        ("single-after", [100], 999),
        ("daily-on", [0, D, 2*D, 3*D], D),
        ("daily-tie", [0, D], D/2),
        ("daily-gap-mid", [0, D, 3*D, 4*D], 2*D),
        ("daily-gap-edge", [0, D, 3*D, 4*D], D + 3*D/4),
        ("daily-gap-justin", [0, D, 3*D, 4*D], D + 3*D/4 + 1),
        ("daily-gap-long", [0, D, 6*D, 7*D], 3*D + 7),
        ("before-first", [10, 20, 30], -50),
        ("after-last", [10, 20, 30], 500),
        ("jitter", [0, 300, 660, 900, 1200], 480),
        ("dup-instant", [5, 5, 5], 6),
        ("dst-23h", [0, 82800, 169200, 255600], 120000),
        ("hr-1hz-hole", [0, 1, 2, 3, 10, 11, 12], 6),
        ("negative", [-300, -200, -100, 0], -151),
        ]
        var lines: [String] = []
        for (name, ts, c) in cases {
            let cad = ChartScrubMath.cadence(ts)
            if let h = ChartScrubMath.resolve(times: ts, cursor: c) {
                lines.append("\(name) cad=\(cad) idx=\(h.index) gap=\(h.gapAt.map(String.init) ?? "nil")")
            } else {
                lines.append("\(name) cad=\(cad) nil")
            }
        }
        XCTAssertEqual(lines.joined(separator: "\n"), Self.expected)
    }

    func testPrecomputedCadenceMatchesDefault() {
        let ts: [Int64] = [0, 86400, 259200, 345600]
        for c in stride(from: Int64(-1000), through: 400000, by: 997) {
            XCTAssertEqual(ChartScrubMath.resolve(times: ts, cursor: c),
                           ChartScrubMath.resolve(times: ts, cursor: c, cadence: ChartScrubMath.cadence(ts)))
        }
    }

    func testDateOverloadFloorsFractionalSeconds() {
        let ds = [Date(timeIntervalSince1970: 0), Date(timeIntervalSince1970: 10.9)]
        XCTAssertEqual(ChartScrubMath.resolve(dates: ds, cursor: Date(timeIntervalSince1970: 9.99))?.index, 1)
        XCTAssertEqual(ChartScrubMath.epochSeconds(Date(timeIntervalSince1970: -0.5)), -1)
    }
}
