import XCTest
@testable import StrandAnalytics

/// `DayCalories`, the one "Calories" figure every surface shows (#51). `expected` is `oracleOutput()` over a
/// spread of inputs, pasted verbatim; the Kotlin twin `DayCaloriesResolutionTest` pins the same text.
final class DayCaloriesResolutionTests: XCTestCase {
    static func oracleOutput() -> String {
        func f(_ v: Double?) -> String { v.map { String($0) } ?? "nil" }
        let vals: [Double?] = [nil, -5, 0, 0.5, 512.25, 1650]
        var out: [String] = []
        for a in vals { for b in vals { for s in vals {
            let day = DayCalories.byDay(imported: [.init(day: "d", activeKcal: a, basalKcal: b)],
                                        stored: s.map { ["d": $0] } ?? [:]).first?.kcal
            out.append("\(f(a)) \(f(b)) \(f(s)) -> \(f(day)) total=\(f(DayCalories.importedTotal(activeKcal: a, basalKcal: b)))")
        }}}
        let rows = [DayCalories.ImportedDay(day: "2026-11-01", activeKcal: 500, basalKcal: 1700),
                    DayCalories.ImportedDay(day: "2026-11-01", activeKcal: 450.5, basalKcal: 1800),
                    DayCalories.ImportedDay(day: "2026-11-02", activeKcal: 600, basalKcal: nil),
                    DayCalories.ImportedDay(day: "2026-11-04", activeKcal: 0, basalKcal: 1600)]
        let stored = ["2026-11-01": 2400.0, "2026-11-02": 2300.5, "2026-11-03": 0, "2026-11-05": 2100]
        for (d, k) in DayCalories.byDay(imported: rows, stored: stored) { out.append("day \(d) \(k)") }
        return out.joined(separator: "\n")
    }

    func testOracleOutputIsPinned() {
        XCTAssertEqual(Self.oracleOutput(), Self.expected)
    }

    /// A phone day with only its active half never stands in for a total.
    func testActiveOnlyPhoneDayFallsBackToTheStoredTotal() {
        let phone = [DayCalories.ImportedDay(day: "2026-11-01", activeKcal: 520, basalKcal: nil)]
        XCTAssertEqual(DayCalories.byDay(imported: phone, stored: ["2026-11-01": 2_410]).first?.kcal, 2_410)
        XCTAssertTrue(DayCalories.byDay(imported: phone, stored: [:]).isEmpty)
    }

    static let expected = """
        nil nil nil -> nil total=nil
        nil nil -5.0 -> nil total=nil
        nil nil 0.0 -> nil total=nil
        nil nil 0.5 -> 0.5 total=nil
        nil nil 512.25 -> 512.25 total=nil
        nil nil 1650.0 -> 1650.0 total=nil
        nil -5.0 nil -> nil total=nil
        nil -5.0 -5.0 -> nil total=nil
        nil -5.0 0.0 -> nil total=nil
        nil -5.0 0.5 -> 0.5 total=nil
        nil -5.0 512.25 -> 512.25 total=nil
        nil -5.0 1650.0 -> 1650.0 total=nil
        nil 0.0 nil -> nil total=nil
        nil 0.0 -5.0 -> nil total=nil
        nil 0.0 0.0 -> nil total=nil
        nil 0.0 0.5 -> 0.5 total=nil
        nil 0.0 512.25 -> 512.25 total=nil
        nil 0.0 1650.0 -> 1650.0 total=nil
        nil 0.5 nil -> nil total=nil
        nil 0.5 -5.0 -> nil total=nil
        nil 0.5 0.0 -> nil total=nil
        nil 0.5 0.5 -> 0.5 total=nil
        nil 0.5 512.25 -> 512.25 total=nil
        nil 0.5 1650.0 -> 1650.0 total=nil
        nil 512.25 nil -> nil total=nil
        nil 512.25 -5.0 -> nil total=nil
        nil 512.25 0.0 -> nil total=nil
        nil 512.25 0.5 -> 0.5 total=nil
        nil 512.25 512.25 -> 512.25 total=nil
        nil 512.25 1650.0 -> 1650.0 total=nil
        nil 1650.0 nil -> nil total=nil
        nil 1650.0 -5.0 -> nil total=nil
        nil 1650.0 0.0 -> nil total=nil
        nil 1650.0 0.5 -> 0.5 total=nil
        nil 1650.0 512.25 -> 512.25 total=nil
        nil 1650.0 1650.0 -> 1650.0 total=nil
        -5.0 nil nil -> nil total=nil
        -5.0 nil -5.0 -> nil total=nil
        -5.0 nil 0.0 -> nil total=nil
        -5.0 nil 0.5 -> 0.5 total=nil
        -5.0 nil 512.25 -> 512.25 total=nil
        -5.0 nil 1650.0 -> 1650.0 total=nil
        -5.0 -5.0 nil -> nil total=nil
        -5.0 -5.0 -5.0 -> nil total=nil
        -5.0 -5.0 0.0 -> nil total=nil
        -5.0 -5.0 0.5 -> 0.5 total=nil
        -5.0 -5.0 512.25 -> 512.25 total=nil
        -5.0 -5.0 1650.0 -> 1650.0 total=nil
        -5.0 0.0 nil -> nil total=nil
        -5.0 0.0 -5.0 -> nil total=nil
        -5.0 0.0 0.0 -> nil total=nil
        -5.0 0.0 0.5 -> 0.5 total=nil
        -5.0 0.0 512.25 -> 512.25 total=nil
        -5.0 0.0 1650.0 -> 1650.0 total=nil
        -5.0 0.5 nil -> nil total=nil
        -5.0 0.5 -5.0 -> nil total=nil
        -5.0 0.5 0.0 -> nil total=nil
        -5.0 0.5 0.5 -> 0.5 total=nil
        -5.0 0.5 512.25 -> 512.25 total=nil
        -5.0 0.5 1650.0 -> 1650.0 total=nil
        -5.0 512.25 nil -> nil total=nil
        -5.0 512.25 -5.0 -> nil total=nil
        -5.0 512.25 0.0 -> nil total=nil
        -5.0 512.25 0.5 -> 0.5 total=nil
        -5.0 512.25 512.25 -> 512.25 total=nil
        -5.0 512.25 1650.0 -> 1650.0 total=nil
        -5.0 1650.0 nil -> nil total=nil
        -5.0 1650.0 -5.0 -> nil total=nil
        -5.0 1650.0 0.0 -> nil total=nil
        -5.0 1650.0 0.5 -> 0.5 total=nil
        -5.0 1650.0 512.25 -> 512.25 total=nil
        -5.0 1650.0 1650.0 -> 1650.0 total=nil
        0.0 nil nil -> nil total=nil
        0.0 nil -5.0 -> nil total=nil
        0.0 nil 0.0 -> nil total=nil
        0.0 nil 0.5 -> 0.5 total=nil
        0.0 nil 512.25 -> 512.25 total=nil
        0.0 nil 1650.0 -> 1650.0 total=nil
        0.0 -5.0 nil -> nil total=nil
        0.0 -5.0 -5.0 -> nil total=nil
        0.0 -5.0 0.0 -> nil total=nil
        0.0 -5.0 0.5 -> 0.5 total=nil
        0.0 -5.0 512.25 -> 512.25 total=nil
        0.0 -5.0 1650.0 -> 1650.0 total=nil
        0.0 0.0 nil -> nil total=nil
        0.0 0.0 -5.0 -> nil total=nil
        0.0 0.0 0.0 -> nil total=nil
        0.0 0.0 0.5 -> 0.5 total=nil
        0.0 0.0 512.25 -> 512.25 total=nil
        0.0 0.0 1650.0 -> 1650.0 total=nil
        0.0 0.5 nil -> nil total=nil
        0.0 0.5 -5.0 -> nil total=nil
        0.0 0.5 0.0 -> nil total=nil
        0.0 0.5 0.5 -> 0.5 total=nil
        0.0 0.5 512.25 -> 512.25 total=nil
        0.0 0.5 1650.0 -> 1650.0 total=nil
        0.0 512.25 nil -> nil total=nil
        0.0 512.25 -5.0 -> nil total=nil
        0.0 512.25 0.0 -> nil total=nil
        0.0 512.25 0.5 -> 0.5 total=nil
        0.0 512.25 512.25 -> 512.25 total=nil
        0.0 512.25 1650.0 -> 1650.0 total=nil
        0.0 1650.0 nil -> nil total=nil
        0.0 1650.0 -5.0 -> nil total=nil
        0.0 1650.0 0.0 -> nil total=nil
        0.0 1650.0 0.5 -> 0.5 total=nil
        0.0 1650.0 512.25 -> 512.25 total=nil
        0.0 1650.0 1650.0 -> 1650.0 total=nil
        0.5 nil nil -> nil total=nil
        0.5 nil -5.0 -> nil total=nil
        0.5 nil 0.0 -> nil total=nil
        0.5 nil 0.5 -> 0.5 total=nil
        0.5 nil 512.25 -> 512.25 total=nil
        0.5 nil 1650.0 -> 1650.0 total=nil
        0.5 -5.0 nil -> nil total=nil
        0.5 -5.0 -5.0 -> nil total=nil
        0.5 -5.0 0.0 -> nil total=nil
        0.5 -5.0 0.5 -> 0.5 total=nil
        0.5 -5.0 512.25 -> 512.25 total=nil
        0.5 -5.0 1650.0 -> 1650.0 total=nil
        0.5 0.0 nil -> nil total=nil
        0.5 0.0 -5.0 -> nil total=nil
        0.5 0.0 0.0 -> nil total=nil
        0.5 0.0 0.5 -> 0.5 total=nil
        0.5 0.0 512.25 -> 512.25 total=nil
        0.5 0.0 1650.0 -> 1650.0 total=nil
        0.5 0.5 nil -> 1.0 total=1.0
        0.5 0.5 -5.0 -> 1.0 total=1.0
        0.5 0.5 0.0 -> 1.0 total=1.0
        0.5 0.5 0.5 -> 1.0 total=1.0
        0.5 0.5 512.25 -> 1.0 total=1.0
        0.5 0.5 1650.0 -> 1.0 total=1.0
        0.5 512.25 nil -> 512.75 total=512.75
        0.5 512.25 -5.0 -> 512.75 total=512.75
        0.5 512.25 0.0 -> 512.75 total=512.75
        0.5 512.25 0.5 -> 512.75 total=512.75
        0.5 512.25 512.25 -> 512.75 total=512.75
        0.5 512.25 1650.0 -> 512.75 total=512.75
        0.5 1650.0 nil -> 1650.5 total=1650.5
        0.5 1650.0 -5.0 -> 1650.5 total=1650.5
        0.5 1650.0 0.0 -> 1650.5 total=1650.5
        0.5 1650.0 0.5 -> 1650.5 total=1650.5
        0.5 1650.0 512.25 -> 1650.5 total=1650.5
        0.5 1650.0 1650.0 -> 1650.5 total=1650.5
        512.25 nil nil -> nil total=nil
        512.25 nil -5.0 -> nil total=nil
        512.25 nil 0.0 -> nil total=nil
        512.25 nil 0.5 -> 0.5 total=nil
        512.25 nil 512.25 -> 512.25 total=nil
        512.25 nil 1650.0 -> 1650.0 total=nil
        512.25 -5.0 nil -> nil total=nil
        512.25 -5.0 -5.0 -> nil total=nil
        512.25 -5.0 0.0 -> nil total=nil
        512.25 -5.0 0.5 -> 0.5 total=nil
        512.25 -5.0 512.25 -> 512.25 total=nil
        512.25 -5.0 1650.0 -> 1650.0 total=nil
        512.25 0.0 nil -> nil total=nil
        512.25 0.0 -5.0 -> nil total=nil
        512.25 0.0 0.0 -> nil total=nil
        512.25 0.0 0.5 -> 0.5 total=nil
        512.25 0.0 512.25 -> 512.25 total=nil
        512.25 0.0 1650.0 -> 1650.0 total=nil
        512.25 0.5 nil -> 512.75 total=512.75
        512.25 0.5 -5.0 -> 512.75 total=512.75
        512.25 0.5 0.0 -> 512.75 total=512.75
        512.25 0.5 0.5 -> 512.75 total=512.75
        512.25 0.5 512.25 -> 512.75 total=512.75
        512.25 0.5 1650.0 -> 512.75 total=512.75
        512.25 512.25 nil -> 1024.5 total=1024.5
        512.25 512.25 -5.0 -> 1024.5 total=1024.5
        512.25 512.25 0.0 -> 1024.5 total=1024.5
        512.25 512.25 0.5 -> 1024.5 total=1024.5
        512.25 512.25 512.25 -> 1024.5 total=1024.5
        512.25 512.25 1650.0 -> 1024.5 total=1024.5
        512.25 1650.0 nil -> 2162.25 total=2162.25
        512.25 1650.0 -5.0 -> 2162.25 total=2162.25
        512.25 1650.0 0.0 -> 2162.25 total=2162.25
        512.25 1650.0 0.5 -> 2162.25 total=2162.25
        512.25 1650.0 512.25 -> 2162.25 total=2162.25
        512.25 1650.0 1650.0 -> 2162.25 total=2162.25
        1650.0 nil nil -> nil total=nil
        1650.0 nil -5.0 -> nil total=nil
        1650.0 nil 0.0 -> nil total=nil
        1650.0 nil 0.5 -> 0.5 total=nil
        1650.0 nil 512.25 -> 512.25 total=nil
        1650.0 nil 1650.0 -> 1650.0 total=nil
        1650.0 -5.0 nil -> nil total=nil
        1650.0 -5.0 -5.0 -> nil total=nil
        1650.0 -5.0 0.0 -> nil total=nil
        1650.0 -5.0 0.5 -> 0.5 total=nil
        1650.0 -5.0 512.25 -> 512.25 total=nil
        1650.0 -5.0 1650.0 -> 1650.0 total=nil
        1650.0 0.0 nil -> nil total=nil
        1650.0 0.0 -5.0 -> nil total=nil
        1650.0 0.0 0.0 -> nil total=nil
        1650.0 0.0 0.5 -> 0.5 total=nil
        1650.0 0.0 512.25 -> 512.25 total=nil
        1650.0 0.0 1650.0 -> 1650.0 total=nil
        1650.0 0.5 nil -> 1650.5 total=1650.5
        1650.0 0.5 -5.0 -> 1650.5 total=1650.5
        1650.0 0.5 0.0 -> 1650.5 total=1650.5
        1650.0 0.5 0.5 -> 1650.5 total=1650.5
        1650.0 0.5 512.25 -> 1650.5 total=1650.5
        1650.0 0.5 1650.0 -> 1650.5 total=1650.5
        1650.0 512.25 nil -> 2162.25 total=2162.25
        1650.0 512.25 -5.0 -> 2162.25 total=2162.25
        1650.0 512.25 0.0 -> 2162.25 total=2162.25
        1650.0 512.25 0.5 -> 2162.25 total=2162.25
        1650.0 512.25 512.25 -> 2162.25 total=2162.25
        1650.0 512.25 1650.0 -> 2162.25 total=2162.25
        1650.0 1650.0 nil -> 3300.0 total=3300.0
        1650.0 1650.0 -5.0 -> 3300.0 total=3300.0
        1650.0 1650.0 0.0 -> 3300.0 total=3300.0
        1650.0 1650.0 0.5 -> 3300.0 total=3300.0
        1650.0 1650.0 512.25 -> 3300.0 total=3300.0
        1650.0 1650.0 1650.0 -> 3300.0 total=3300.0
        day 2026-11-01 2250.5
        day 2026-11-02 2300.5
        day 2026-11-05 2100.0
        """
}
