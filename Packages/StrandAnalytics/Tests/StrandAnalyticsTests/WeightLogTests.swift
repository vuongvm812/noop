import XCTest
@testable import StrandAnalytics

/// #57: the hand-logged weight diary resolver behind the Today Weight tile, the weight chart and the
/// profile weight. The expected table is the stdout of this file's `WeightLog` compiled standalone over
/// the same cases; the Kotlin `WeightLogTest` pins the same literal. Change both together.
final class WeightLogTests: XCTestCase {
    typealias E = WeightLog.Entry

    static func oracleLines() -> [String] {
        var out: [String] = []
        let inputs = ["72.5", " 72,5 ", "165", "abc", "", "29.99", "250", "250.01", "551.2", "66.1", "nan", "inf",
                      "-70", "80.005", "1e2"]
        for t in inputs {
            let m = WeightLog.parseKg(t, imperial: false).map { "\($0)" } ?? "nil"
            let i = WeightLog.parseKg(t, imperial: true).map { "\($0)" } ?? "nil"
            out.append("parse|\(t)|\(m)|\(i)")
        }
        let logged = [E(day: "2026-10-01", kg: 80), E(day: "2026-10-05", kg: 79.4), E(day: "2026-10-05", kg: 70),
                      E(day: "2026-10-09", kg: 10), E(day: "2026-10-12", kg: 78.8)]
        let health = [E(day: "2026-10-03", kg: 80.6), E(day: "2026-10-05", kg: 79.9), E(day: "2026-10-10", kg: 79.1),
                      E(day: "2026-10-11", kg: .nan)]
        func show(_ r: WeightLog.Resolved?) -> String {
            r.map { "\($0.source.rawValue):\($0.entry.day):\($0.entry.kg)" } ?? "nil"
        }
        for d in ["2026-09-30", "2026-10-01", "2026-10-04", "2026-10-05", "2026-10-09", "2026-10-10", "2026-10-11",
                  "2026-10-12", "2026-11-01"] {
            out.append("resolve|\(d)|" + show(WeightLog.resolve(logged: logged, health: health, onOrBefore: d)))
        }
        out.append("resolve-health-only|" + show(WeightLog.resolve(logged: [], health: health, onOrBefore: "2026-10-31")))
        out.append("series|" + WeightLog.dailySeries(logged: logged, health: health)
            .map { "\($0.day)=\($0.kg)" }.joined(separator: ","))
        out.append("profile|" + (WeightLog.profileWeight(logged: logged).map { "\($0)" } ?? "nil"))
        out.append("profile-empty|" + (WeightLog.profileWeight(logged: []).map { "\($0)" } ?? "nil"))
        out.append("newest-tie|" + (WeightLog.newest([E(day: "2026-10-05", kg: 70), E(day: "2026-10-05", kg: 71)])
            .map { "\($0.kg)" } ?? "nil"))
        return out
    }

    func testMatchesTheParityTable() {
        XCTAssertEqual(Self.oracleLines(), [
            "parse|72.5|72.5|32.89",
            "parse| 72,5 |72.5|32.89",
            "parse|165|165.0|74.84",
            "parse|abc|nil|nil",
            "parse||nil|nil",
            "parse|29.99|nil|nil",
            "parse|250|250.0|113.4",
            "parse|250.01|nil|113.4",
            "parse|551.2|nil|nil",
            "parse|66.1|66.1|nil",
            "parse|nan|nil|nil",
            "parse|inf|nil|nil",
            "parse|-70|nil|nil",
            "parse|80.005|80.01|36.29",
            "parse|1e2|100.0|45.36",
            "resolve|2026-09-30|nil",
            "resolve|2026-10-01|logged:2026-10-01:80.0",
            "resolve|2026-10-04|health:2026-10-03:80.6",
            "resolve|2026-10-05|logged:2026-10-05:79.4",
            "resolve|2026-10-09|logged:2026-10-05:79.4",
            "resolve|2026-10-10|health:2026-10-10:79.1",
            "resolve|2026-10-11|health:2026-10-10:79.1",
            "resolve|2026-10-12|logged:2026-10-12:78.8",
            "resolve|2026-11-01|logged:2026-10-12:78.8",
            "resolve-health-only|health:2026-10-10:79.1",
            "series|2026-10-01=80.0,2026-10-03=80.6,2026-10-05=79.4,2026-10-10=79.1,2026-10-12=78.8",
            "profile|78.8",
            "profile-empty|nil",
            "newest-tie|70.0",
        ])
    }

    /// The same ids the Kotlin twin writes, so a `.noopbak` restored on the other platform reads the diary.
    func testStorageIdsArePinned() {
        XCTAssertEqual(WeightLog.sourceId, "weight-log")
        XCTAssertEqual(WeightLog.key, "weight_kg")
    }

    /// Pinned against the Kotlin `WeightLogTest.bodyReadingMatchesTheSwiftOracle` literal.
    func testBodyReadingMatchesTheParityTable() {
        typealias B = WeightLog.BodyReading
        func b(_ d: String, _ v: Double) -> B? { B(day: d, value: v) }
        func show(_ r: B?) -> String { r.map { "\($0.day):\($0.value)" } ?? "nil" }
        let cases: [(String, [B?], Double?)] = [
            ("empty", [], nil),
            ("allNil", [nil, nil, nil], nil),
            ("newestWins", [b("2026-10-01", 18.2), b("2026-10-05", 17.9), b("2026-10-03", 19.0)], 100),
            ("tieFirst", [b("2026-10-05", 18.0), b("2026-10-05", 17.0)], 100),
            ("skipNil", [nil, b("2026-09-30", 61.4)], nil),
            ("invalidDropped", [b("2026-10-09", 0), b("2026-10-08", .nan), b("2026-10-07", -3), b("2026-10-01", 61.0)], nil),
            ("overMax", [b("2026-10-09", 140), b("2026-10-02", 22.5)], 100),
            ("atMax", [b("2026-10-09", 100)], 100),
        ]
        let actual = cases.map { "body|\($0.0)|" + show(WeightLog.newestBodyReading($0.1, maxValue: $0.2)) }
        XCTAssertEqual(actual, [
            "body|empty|nil",
            "body|allNil|nil",
            "body|newestWins|2026-10-05:17.9",
            "body|tieFirst|2026-10-05:18.0",
            "body|skipNil|2026-09-30:61.4",
            "body|invalidDropped|2026-10-01:61.0",
            "body|overMax|2026-10-02:22.5",
            "body|atMax|2026-10-09:100.0",
        ])
    }
}
