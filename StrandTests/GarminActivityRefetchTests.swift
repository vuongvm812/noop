import XCTest
import Foundation
import GarminProtocol
import WhoopStore
@testable import Strand

/// The one-shot ACTIVITY re-download: activities synced before power / cadence / altitude were stored are
/// forgotten once, on both listing flows, for this watch only. Kotlin twin: `GarminActivityRefetchTest`;
/// `summary` matches that test's dump. All data is synthetic.
@MainActor
final class GarminActivityRefetchTests: XCTestCase {
    private let watch = "garmin-TEST-0165"

    static let summary = """
        forgotten 2
        kept 128/44 255/32 255/49
        other device kept 1
        second run forgotten 0
        """

    private func row(_ dataType: Int, _ subType: Int, _ n: Int) -> GarminSyncedFileRow {
        GarminSyncedFileRow(fileIndex: n, dataType: dataType, subType: subType, fileNumber: n, ts: 1_100_000_000 + n, size: 10)
    }

    func testActivityFilesAreForgottenOncePerWatch() async throws {
        let suite = "GarminActivityRefetchTests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let store = try await WhoopStore.inMemory()
        let activity = Int(GarminFitFileType.activity.rawValue)
        var lines: [String] = []
        for r in [row(255, activity, 1), row(128, activity, 2), row(255, 32, 3), row(128, 44, 4), row(255, 49, 5)] {
            try await store.recordGarminSyncedFile(r, deviceId: watch, ingestedAt: 1)
        }
        try await store.recordGarminSyncedFile(row(255, activity, 6), deviceId: "garmin-OTHER", ingestedAt: 1)
        let before = try await store.garminSyncedFiles(deviceId: watch).count
        await GarminHistoryIngest.refetchActivityFilesOnce(store: store, deviceId: watch, defaults: defaults, log: { _ in })
        let kept = try await store.garminSyncedFiles(deviceId: watch)
        lines.append("forgotten \(before - kept.count)")
        lines.append("kept " + kept.map { "\($0.dataType)/\($0.subType)" }.sorted().joined(separator: " "))
        lines.append("other device kept \(try await store.garminSyncedFiles(deviceId: "garmin-OTHER").count)")
        // Once per watch: an activity synced after the re-download stays synced.
        try await store.recordGarminSyncedFile(row(255, activity, 1), deviceId: watch, ingestedAt: 2)
        let afterRepair = try await store.garminSyncedFiles(deviceId: watch).count
        await GarminHistoryIngest.refetchActivityFilesOnce(store: store, deviceId: watch, defaults: defaults, log: { _ in })
        lines.append("second run forgotten \(afterRepair - (try await store.garminSyncedFiles(deviceId: watch).count))")
        XCTAssertEqual(lines.joined(separator: "\n"), Self.summary)
    }
}
