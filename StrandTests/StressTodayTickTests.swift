import XCTest
@testable import Strand

/// #94: Today's live stress tick. Twin of the Kotlin `StressTodayTickTest`, same cases, same literals.
final class StressTodayTickTests: XCTestCase {

    func testTheTodayTickIsTenMinutes() {
        XCTAssertEqual(StressDayCurve.todayRefreshIntervalMs, 600_000)
    }

    func testTicksMatchTheKotlinTwinValueForValue() {
        let cases: [(Int64, Int64, Int64)] = [
            (0, 0, 600_000), (1, 0, 600_000), (599_999, 0, 600_000), (600_000, 0, 600_000),
            (1_700_000_123_456, 0, 600_000), (5_000, 5_000, 600_000), (4_999, 5_000, 600_000),
            (-1, 0, 600_000), (1_700_000_123_456, 5_000, 600_000), (10, 0, 0),
        ]
        let actual = cases.map { n, o, i in
            "\(n) \(o) \(i) -> \(StressDayCurve.msUntilNextTodayTick(nowMs: n, offsetMs: o, intervalMs: i))"
        }.joined(separator: "\n")
        XCTAssertEqual(actual, """
            0 0 600000 -> 600000
            1 0 600000 -> 599999
            599999 0 600000 -> 1
            600000 0 600000 -> 600000
            1700000123456 0 600000 -> 276544
            5000 5000 600000 -> 600000
            4999 5000 600000 -> 1
            -1 0 600000 -> 1
            1700000123456 5000 600000 -> 281544
            10 0 0 -> 1
            """)
    }

    /// Both Apple Today layouts run the tick, and it re-asks for the reading AND the hosted curve, so the
    /// tile, the pinned row and the curve cannot be left on different refresh rules.
    func testBothTodayLayoutsRunTheLiveTick() throws {
        let root = URL(fileURLWithPath: "\(#filePath)").deletingLastPathComponent().deletingLastPathComponent()
        for path in ["Strand/Screens/TodayView.swift", "Strand/Liquid/LiquidTodayView.swift"] {
            let body = try String(contentsOf: root.appendingPathComponent(path), encoding: .utf8)
            XCTAssertTrue(body.contains("StressDayCurve.msUntilNextTodayTick(nowMs: nowMs)"), path)
            XCTAssertTrue(body.contains("await refreshLiveStress()"), path)
            XCTAssertTrue(body.contains("await loadHostedStress()"), path)
        }
    }
}
