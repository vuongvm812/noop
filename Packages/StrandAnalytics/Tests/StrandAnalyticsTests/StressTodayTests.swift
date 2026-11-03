import XCTest
@testable import StrandAnalytics

/// #42: the one resolver behind the Today stress card and the Stress screen. The table below is the
/// oracle the Kotlin `StressTodayTest` pins byte-for-byte; change both together.
final class StressTodayTests: XCTestCase {
    typealias P = StressToday.Point

    private func show(_ r: StressToday.Reading?) -> String {
        guard let r else { return "nil" }
        let l = r.latest.map { "\($0.ts):\($0.level)" } ?? "nil"
        let a = r.average.map { "\($0)" } ?? "nil"
        return "\(r.source.rawValue)|\(l)|\(a)|\(r.points.count)|\(r.scaleMax)"
    }

    func testResolverMatchesTheParityTable() {
        let g = [P(ts: 300, level: 40), P(ts: 100, level: 25), P(ts: 200, level: 101), P(ts: 250, level: -1),
                 P(ts: 400, level: 33)]
        let n = [P(ts: 1000, level: 1.2), P(ts: 1800, level: 2.4), P(ts: 2600, level: 0.7)]
        let cases: [(String, [P], Double?, Double?, [P], Double?)] = [
            ("garmin-ab12", g, 31, 50, n, 1.4),
            ("garmin-ab12", [], 31, 50, n, 1.4),
            ("garmin-ab12", [], nil, 47, [], nil),
            ("garmin-ab12", [], nil, nil, n, 1.4),
            ("garmin-ab12", [], 150, 47, [], nil),
            ("my-whoop", g, 31, 50, n, 1.4),
            ("my-whoop", g, nil, nil, [], nil),
            ("my-whoop", [], nil, nil, [], 1.1),
            ("my-whoop", [], nil, nil, [P(ts: 5, level: 3.5)], nil),
            ("whoop-uuid", [], nil, nil, [], nil),
        ]
        let out = cases.map {
            show(StressToday.resolve(activeDeviceId: $0.0, garminSamples: $0.1, garminWatchDay: $0.2,
                                     garminImportDay: $0.3, noopTimeline: $0.4, noopDayMean: $0.5))
        }
        XCTAssertEqual(out, [
            "garmin|400:33.0|32.666666666666664|3|100.0",
            "garmin|nil|31.0|0|100.0",
            "garmin|nil|47.0|0|100.0",
            "noop|2600:0.7|1.4|3|3.0",
            "garmin|nil|47.0|0|100.0",
            "noop|2600:0.7|1.4|3|3.0",
            "garmin|400:33.0|32.666666666666664|3|100.0",
            "noop|nil|1.1|0|3.0",
            "nil",
            "nil",
        ])
    }

    /// A Garmin-only day filled from the imported export (#31): no watch samples, no watch day, only the
    /// export's `stress` average. It must still resolve, as a Garmin reading with no sparkline.
    func testImportedGarminDayResolvesWithoutSamples() {
        let r = StressToday.resolve(activeDeviceId: "garmin-import", garminSamples: [], garminWatchDay: nil,
                                    garminImportDay: 42, noopTimeline: [], noopDayMean: nil)
        XCTAssertEqual(r?.source, .garmin)
        XCTAssertEqual(r?.average, 42)
        XCTAssertNil(r?.latest)
        XCTAssertTrue(r?.points.isEmpty ?? false)
    }

    /// #57: the Key Metrics tile prints the latest level, else today's average. Kotlin twin:
    /// `StressTodayTest.tileLevelMatchesTheSwiftOracle`.
    func testTileLevelPrefersLatestThenAverage() {
        let withLatest = StressToday.resolve(activeDeviceId: "my-whoop", garminSamples: [], garminWatchDay: nil,
                                             garminImportDay: nil, noopTimeline: [P(ts: 5, level: 2.5)],
                                             noopDayMean: 1.1)
        let averageOnly = StressToday.resolve(activeDeviceId: "garmin-ab12", garminSamples: [], garminWatchDay: 31,
                                              garminImportDay: nil, noopTimeline: [], noopDayMean: nil)
        XCTAssertEqual(StressToday.tileLevel(withLatest), 2.5)
        XCTAssertEqual(StressToday.tileLevel(averageOnly), 31)
        XCTAssertNil(StressToday.tileLevel(nil))
    }
}
