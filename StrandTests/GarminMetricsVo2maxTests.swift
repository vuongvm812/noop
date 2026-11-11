import XCTest
import Foundation
import GarminProtocol
import StrandAnalytics
import WhoopStore
@testable import Strand

/// The watch's own VO2max reaches the VO2max tile and card from a synced METRICS file (#70): the one-shot
/// METRICS re-download, then decode → fold → store → the weekly resolver, under the engine id the app
/// really runs with ("my-whoop") while the registry's active device is the watch. Android twin:
/// `GarminMetricsVo2maxTest`; both assert the same `summary` literal. All data is synthetic.
@MainActor
final class GarminMetricsVo2maxTests: XCTestCase {
    private let watch = "garmin-TEST-0070"

    /// The canonical dump both platforms assert. Produced by this test, pasted into the Kotlin twin.
    static let summary = """
        forgotten 2
        kept 128/32 255/32 255/49
        other device kept 1
        second run forgotten 0
        stored vo2max 52.3
        vo2max_est 52.3 garmin
        """

    /// A METRICS (FIT sub-type 44) file holding one MAX_MET_DATA (229) message: update_time and vo2_max
    /// (/10), the layout `GarminWellnessParser` reads. Header, one definition, one data record, CRC 0.
    static func metricsFile(unix: Int, vo2Tenths: Int) -> [UInt8] {
        func le(_ v: Int, _ n: Int) -> [UInt8] { (0..<n).map { UInt8(truncatingIfNeeded: v >> (8 * $0)) } }
        var records: [UInt8] = [0x40, 0, 0] + le(229, 2) + [2, 0, 4, 0x86, 2, 2, 0x84]
        records += [0x00] + le(unix - 631_065_600, 4) + le(vo2Tenths, 2)
        return [12, 0x20, 0, 0] + le(records.count, 4) + Array(".FIT".utf8) + records + [0, 0]
    }

    private func row(_ dataType: Int, _ subType: Int, _ n: Int) -> GarminSyncedFileRow {
        GarminSyncedFileRow(fileIndex: n, dataType: dataType, subType: subType, fileNumber: n, ts: 1_100_000_000 + n, size: 10)
    }

    func testMetricsFileVo2maxReachesTheTileAndTheCard() async throws {
        let suite = "GarminMetricsVo2maxTests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let store = try await WhoopStore.inMemory()
        let registry = DeviceRegistryStore(dbQueue: store.registryWriter)
        try registry.add(PairedDevice(id: watch, brand: "Garmin", model: "Forerunner 165", sourceKind: .garmin,
                                      capabilities: [.hr], status: .paired, addedAt: 1, lastSeenAt: 1))
        try registry.setActive(watch)   // the user switched from the seeded strap to the watch
        var lines: [String] = []

        // Before #51 these METRICS files were recorded without their VO2max; the other types stay synced.
        let metricsType = Int(GarminFitFileType.metrics.rawValue)
        for r in [row(255, metricsType, 1), row(128, metricsType, 2), row(255, 32, 3), row(128, 32, 4), row(255, 49, 5)] {
            try await store.recordGarminSyncedFile(r, deviceId: watch, ingestedAt: 1)
        }
        try await store.recordGarminSyncedFile(row(255, metricsType, 6), deviceId: "garmin-OTHER", ingestedAt: 1)
        let before = try await store.garminSyncedFiles(deviceId: watch).count
        await GarminHistoryIngest.refetchMetricsFilesOnce(store: store, deviceId: watch, defaults: defaults, log: { _ in })
        let kept = try await store.garminSyncedFiles(deviceId: watch)
        lines.append("forgotten \(before - kept.count)")
        lines.append("kept " + kept.map { "\($0.dataType)/\($0.subType)" }.sorted().joined(separator: " "))
        lines.append("other device kept \(try await store.garminSyncedFiles(deviceId: "garmin-OTHER").count)")
        // Once per watch: a METRICS file synced after the repair stays synced.
        try await store.recordGarminSyncedFile(row(255, metricsType, 1), deviceId: watch, ingestedAt: 2)
        let afterRepair = try await store.garminSyncedFiles(deviceId: watch).count
        await GarminHistoryIngest.refetchMetricsFilesOnce(store: store, deviceId: watch, defaults: defaults, log: { _ in })
        lines.append("second run forgotten \(afterRepair - (try await store.garminSyncedFiles(deviceId: watch).count))")

        // The re-downloaded file: a VO2max measured ten days ago at local noon, inside the 28-day window of
        // this week's Saturday key and on or before it.
        let cal = Calendar.current
        let noon = cal.date(byAdding: .hour, value: 12,
                            to: cal.date(byAdding: .day, value: -10, to: cal.startOfDay(for: Date()))!)!
        let bytes = Self.metricsFile(unix: Int(noon.timeIntervalSince1970), vo2Tenths: 523)
        let key = GarminSyncFileKey(fileIndex: 1, dataType: 255, subType: GarminFitFileType.metrics.rawValue,
                                    fileNumber: 1, garminTimestamp: 1_100_000_001, size: UInt32(bytes.count))
        let stored = await GarminHistoryIngest.ingest(GarminDownloadedFile(key: key, bytes: bytes, resumedFrom: nil, typeName: nil),
                                                      deviceId: watch, store: store, log: { _ in })
        XCTAssertTrue(stored)
        let day = Repository.localDayKey(noon)
        let points = try await store.metricSeries(deviceId: watch, key: "vo2max", from: day, to: day)
        lines.append("stored vo2max " + points.map { String($0.value) }.joined(separator: ","))

        // Today's recompute, from an engine on the canonical id as the app builds it.
        let repo = Repository(deviceId: "my-whoop")
        repo.setStoreForTesting(store)
        let engine = IntelligenceEngine(repo: repo, profile: ProfileStore(), deviceId: "my-whoop")
        let wrote = await engine.recomputeFitnessAgeOnly()
        XCTAssertTrue(wrote)
        // The Today tile's read and the VO2max card's read, with the card's provenance tag.
        let tile = await repo.exploreSeries(key: "vo2max_est", source: "my-whoop").last
        let resolution = await repo.resolvedSeries(key: "vo2max_est", source: "my-whoop")
        let card = try XCTUnwrap(resolution.points.last)
        XCTAssertEqual(tile?.value, card.value, "the tile and the card read one value")
        XCTAssertEqual(card.day, Repository.localDayKey(Date()))
        let tag = await repo.scoreProvenanceTag(resolvedSource: card.source, day: card.day, metricKey: "vo2max_est")
        lines.append("vo2max_est \(card.value) \(tag ?? "nil")")

        XCTAssertEqual(lines.joined(separator: "\n"), Self.summary)
    }
}
