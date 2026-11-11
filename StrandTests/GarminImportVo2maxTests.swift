import XCTest
import Foundation
import WhoopStore
import StrandAnalytics
@testable import Strand

/// #71: the VO2max resolver reads the Garmin export's points beside the watch's own, against a real store.
/// The watch wins a day both carry; the export fills the days the watch lacks; past weeks are written with
/// the `garmin` tag. Kotlin twin: `GarminImportVo2maxTest`. All data is synthetic.
@MainActor
final class GarminImportVo2maxTests: XCTestCase {

    private let watch = "garmin-TEST-0001"
    private var computed: String { watch + "-noop" }

    private func seed() async throws -> WhoopStore {
        let store = try await WhoopStore.inMemory()
        _ = try await store.upsertMetricSeries([MetricPoint(day: "2026-10-20", key: "vo2max", value: 52.0)], deviceId: watch)
        _ = try await store.upsertMetricSeries([
            MetricPoint(day: "2026-09-15", key: "vo2max", value: 49.0),
            MetricPoint(day: "2026-10-20", key: "vo2max", value: 48.0),
        ], deviceId: "garmin-import")
        // NOOP's own estimate for a week the export now covers.
        try await store.persistMetricSeriesWithProvenance(
            points: [MetricPoint(day: "2026-09-26", key: "vo2max_est", value: 40.0)],
            provenance: [ScoreInputProvenanceRow(day: "2026-09-26", key: "vo2max_est", sourceId: "uth")],
            deviceId: computed)
        return store
    }

    private func engine(_ deviceId: String) -> IntelligenceEngine {
        IntelligenceEngine(repo: Repository(deviceId: deviceId), profile: ProfileStore(), deviceId: deviceId)
    }

    /// The engine runs on the canonical "my-whoop"; the registry's active device names the watch (#70).
    func testWatchWinsItsDaysAndTheExportFillsTheRest() async throws {
        let store = try await seed()
        let e = engine("my-whoop")
        // Before the user switches to the watch, no Garmin watch is active and nothing resolves.
        XCTAssertNil(e.activeGarminWatchId(store: store))
        let before = await e.watchVo2max(store: store, satKey: "2026-09-19")
        XCTAssertNil(before)
        let registry = DeviceRegistryStore(dbQueue: store.registryWriter)
        try registry.add(PairedDevice(id: watch, brand: "Garmin", model: "Forerunner 165", sourceKind: .garmin,
                                      capabilities: [.hr], status: .paired, addedAt: 1, lastSeenAt: 1))
        try registry.setActive(watch)
        XCTAssertEqual(e.activeGarminWatchId(store: store), watch)
        let shared = await e.watchVo2max(store: store, satKey: "2026-10-24")
        let importOnly = await e.watchVo2max(store: store, satKey: "2026-09-19")
        let gap = await e.watchVo2max(store: store, satKey: "2026-10-17")
        XCTAssertEqual(shared, 52.0)       // the watch wins the day both carry
        XCTAssertEqual(importOnly, 49.0)   // the export fills a week the watch has nothing for
        XCTAssertNil(gap)
    }

    func testEarlierDaysTakeTheGarminValueAndTag() async throws {
        let store = try await seed()
        await engine(watch).persistWatchVo2maxHistory(store: store, watchId: watch, computedId: computed, beforeDay: "2026-10-31")
        let rows = try await store.metricSeries(deviceId: computed, key: "vo2max_est", from: "0000-01-01", to: "9999-12-31")
        // Daily: the export's 49 stands for 28 days from 09-15, then a gap, then the watch's 52 from 10-20.
        let days = (15...30).map { "2026-09-\($0)" } + (1...12).map { String(format: "2026-10-%02d", $0) }
            + (20...30).map { "2026-10-\($0)" }
        XCTAssertEqual(rows.map(\.day), days)
        XCTAssertEqual(rows.map(\.value), days.map { $0 < "2026-10-20" ? 49.0 : 52.0 })
        let sources = try await store.scoreInputSources(deviceId: computed, from: "0000-01-01", to: "9999-12-31",
                                                        keys: ["vo2max_est"])
        XCTAssertEqual(sources.map(\.day), rows.map(\.day))
        XCTAssertTrue(sources.allSatisfy { $0.sourceId == "garmin" })
    }

    func testAnotherActiveDeviceWritesNothing() async throws {
        let store = try await seed()
        await engine("my-whoop").persistWatchVo2maxHistory(store: store, watchId: "my-whoop", computedId: "my-whoop-noop", beforeDay: "2026-10-31")
        let rows = try await store.metricSeries(deviceId: "my-whoop-noop", key: "vo2max_est", from: "0000-01-01", to: "9999-12-31")
        XCTAssertTrue(rows.isEmpty)
    }
}
