import XCTest
import GarminProtocol
import WhoopProtocol
@testable import Strand

/// `GarminLiveFeed`: a Garmin watch's live channels reach the SAME LiveState + store funnels as every other
/// source (live HR, the R-R intake, `hrSample` / `rrInterval` / `deviceMetricSample`). Kotlin twin:
/// `com.noop.ble.GarminLiveFeedTest`.
@MainActor
final class GarminLiveFeedTests: XCTestCase {

    private var clock = 1_793_546_940
    private var persisted: [Streams] = []
    private var logs: [String] = []

    private func makeFeed(_ live: LiveState, detail: Bool = true) -> GarminLiveFeed {
        GarminLiveFeed(live: live, persist: { self.persisted.append($0) }, log: { self.logs.append($0) },
                       detail: { detail }, now: { self.clock })
    }

    func testTheStoredKindsAreDeviceMetricKinds() {
        for kind in [GarminLiveIntake.kindBodyBattery, GarminLiveIntake.kindRespiration, GarminLiveIntake.kindSpo2] {
            XCTAssertNotNil(DeviceMetricKind(rawValue: kind), kind)
        }
    }

    func testHeartRateAndBeatsReachTheSharedLiveIntake() {
        let live = LiveState()
        let feed = makeFeed(live)
        feed.handle(.channel(.realtimeHeartRate, open: true))
        feed.handle(.reading(.heartRate(bpm: 62, resting: 55, type: 3, trailing: [0xFF, 0xFF])))
        feed.handle(.reading(.rrInterval(ms: 968, unknown: 7)))
        XCTAssertEqual(live.heartRate, 62)
        XCTAssertTrue(live.streamingLiveHR)
        XCTAssertEqual(live.rr, [968])
        XCTAssertEqual(live.rrRecent, [968])
        XCTAssertEqual(live.garminLive?.restingHeartRate, 55)
        // An unreadable sample (off the wrist) leaves the number alone; the silence timer clears it later.
        feed.handle(.reading(.heartRate(bpm: nil, resting: nil, type: 3, trailing: [])))
        XCTAssertEqual(live.heartRate, 62)
        // Closing the HR channel blanks it at once.
        feed.handle(.channel(.realtimeHeartRate, open: false))
        XCTAssertNil(live.heartRate)
        XCTAssertFalse(live.streamingLiveHR)
        let rows = persisted.reduce(into: Streams()) { $0.hr += $1.hr; $0.rr += $1.rr }
        XCTAssertEqual(rows.hr.map(\.bpm), [62])
        XCTAssertEqual(rows.rr.map(\.rrMs), [968])
        XCTAssertTrue(logs.contains("Garmin: receiving live heart rate - first sample 62 bpm"))
    }

    func testWatchMetricsAreStoredAtMostOncePerMinute() {
        let live = LiveState()
        let feed = makeFeed(live)
        for second in 0..<125 {
            clock = 1_793_546_940 + second
            feed.handle(.reading(.bodyBattery(64)))
            feed.handle(.reading(.respiration(breathsPerMinute: 14)))
        }
        feed.handle(.linkDown)
        let rows = persisted.flatMap(\.deviceMetric)
        XCTAssertEqual(rows.filter { $0.kind == .bodyBattery }.map(\.ts), [1_793_546_940, 1_793_547_000, 1_793_547_060])
        XCTAssertEqual(rows.filter { $0.kind == .respirationBpm }.count, 3)
        XCTAssertTrue(rows.allSatisfy { $0.source == "garmin_live" })
        XCTAssertNil(live.garminLive)
    }

    func testRrQualityIsReportedWhenTheChannelCloses() {
        let feed = makeFeed(LiveState())
        feed.handle(.channel(.realtimeHrv, open: true))
        for ms in [1000, 1010, 990, 1005, 400, 995, 1000, 1002, 998, 1001] {
            feed.handle(.reading(.rrInterval(ms: ms, unknown: nil)))
        }
        feed.handle(.channel(.realtimeHrv, open: false))
        XCTAssertTrue(logs.contains("Garmin: rr quality (channel closed): beats=10 kept=9 rejected=1 (10.0%)"), "\(logs)")
    }

    func testQualityLineMatchesTheKotlinText() {
        XCTAssertEqual(GarminLiveFeed.qualityLine([800, 810, 2500, 805, 790, 200, 800], reason: "x"),
                       "rr quality (x): beats=7 kept=5 rejected=2 (28.6%)")
    }
}
