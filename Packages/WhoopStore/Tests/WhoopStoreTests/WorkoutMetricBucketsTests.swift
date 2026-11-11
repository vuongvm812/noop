import XCTest
import WhoopProtocol
@testable import WhoopStore

/// The workout detail's power / cadence / altitude read: mean per bucket over the workout window, empty when
/// the watch stored no rows. Kotlin twin: `WorkoutMetricBucketsRoomTest`.
final class WorkoutMetricBucketsTests: XCTestCase {

    private let start = 1_760_000_010          // a multiple of 15 s, the bucket width of a 1800 s window
    private func sample(_ offset: Int, _ kind: DeviceMetricKind, _ value: Double) -> DeviceMetricSample {
        DeviceMetricSample(ts: start + offset, kind: kind, value: value, source: "fit-record")
    }

    func testBucketWidthFollowsTheHrRule() {
        XCTAssertEqual(WhoopStore.workoutBucketSeconds(spanSeconds: 600), 15)       // floored
        XCTAssertEqual(WhoopStore.workoutBucketSeconds(spanSeconds: 1800), 15)
        XCTAssertEqual(WhoopStore.workoutBucketSeconds(spanSeconds: 6000), 50)
        XCTAssertEqual(WhoopStore.workoutBucketSeconds(spanSeconds: 90_000), 300)   // capped
    }

    func testBucketsAreMeansInsideTheWindowOldestFirst() async throws {
        let store = try await WhoopStore.inMemory()
        try await store.insert(Streams(deviceMetric: [
            sample(0, .powerW, 100), sample(5, .powerW, 200),      // one bucket: mean 150
            sample(15, .powerW, 300),
            sample(1801, .powerW, 999),                            // outside the window
            sample(0, .cadence, 80),                               // another kind
        ]), deviceId: "garmin-1")
        let buckets = try await store.workoutMetricBuckets(deviceIds: ["garmin-1"], kind: .powerW,
                                                           startTs: start, endTs: start + 1800)
        XCTAssertEqual(buckets, [MetricBucket(ts: start, value: 150), MetricBucket(ts: start + 15, value: 300)])
    }

    func testEarlierDeviceIdWinsABucketStart() async throws {
        let store = try await WhoopStore.inMemory()
        try await store.insert(Streams(deviceMetric: [sample(0, .altitudeM, 100)]), deviceId: "a")
        try await store.insert(Streams(deviceMetric: [sample(0, .altitudeM, 5), sample(15, .altitudeM, 300)]), deviceId: "b")
        let buckets = try await store.workoutMetricBuckets(deviceIds: ["a", "b"], kind: .altitudeM,
                                                           startTs: start, endTs: start + 1800)
        XCTAssertEqual(buckets, [MetricBucket(ts: start, value: 100), MetricBucket(ts: start + 15, value: 300)])
    }

    func testNoRowsIsEmpty() async throws {
        let store = try await WhoopStore.inMemory()
        try await store.insert(Streams(deviceMetric: [sample(0, .powerW, 100)]), deviceId: "garmin-1")
        let cadence = try await store.workoutMetricBuckets(deviceIds: ["garmin-1"], kind: .cadence,
                                                           startTs: start, endTs: start + 1800)
        XCTAssertEqual(cadence, [])
        let otherDevice = try await store.workoutMetricBuckets(deviceIds: ["nobody"], kind: .powerW,
                                                               startTs: start, endTs: start + 1800)
        XCTAssertEqual(otherDevice, [])
        let emptyWindow = try await store.workoutMetricBuckets(deviceIds: ["garmin-1"], kind: .powerW,
                                                               startTs: start, endTs: start)
        XCTAssertEqual(emptyWindow, [])
    }
}
