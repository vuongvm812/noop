import Foundation
import GarminProtocol
import StrandImport
import WhoopProtocol
import WhoopStore

/// Stores what Garmin history sync downloads (`GarminSource` hands each checked file here) and answers
/// which files are already stored. Nothing here talks to the watch: the `garminSyncedFile` ledger is the
/// only record of "already downloaded", which is what lets NOOP leave every file on the watch untouched.
///
/// - Wellness files (monitor, sleep, HRV status, metrics, skin temperature) are decoded by
///   `GarminWellnessParser`, folded by `GarminWatchIngest`, and written with their ledger row in ONE
///   transaction (`WhoopStore.ingestGarminFile`).
/// - ACTIVITY files go through the existing workout path (`ActivityFileImporter`, the same parse the
///   manual workout-file import uses), stored under the watch's own device id, then recorded.
/// Kotlin twin: `GarminHistoryIngest` (android/.../data/GarminHistoryIngest.kt).
enum GarminHistoryIngest {

    static func syncedKeys(store: WhoopStore, deviceId: String) async -> Set<GarminSyncFileKey> {
        let rows = (try? await store.garminSyncedFiles(deviceId: deviceId)) ?? []
        return Set(rows.map {
            GarminSyncFileKey(fileIndex: UInt16(truncatingIfNeeded: $0.fileIndex), dataType: UInt8(truncatingIfNeeded: $0.dataType),
                              subType: UInt8(truncatingIfNeeded: $0.subType), fileNumber: UInt16(truncatingIfNeeded: $0.fileNumber),
                              garminTimestamp: UInt32(truncatingIfNeeded: $0.ts), size: UInt32(truncatingIfNeeded: $0.size))
        })
    }

    static func ledgerRow(_ key: GarminSyncFileKey) -> GarminSyncedFileRow {
        GarminSyncedFileRow(fileIndex: Int(key.fileIndex), dataType: Int(key.dataType), subType: Int(key.subType),
                            fileNumber: Int(key.fileNumber), ts: Int(key.garminTimestamp), size: Int(key.size))
    }

    /// Decode and store one file. True when it is stored and recorded; false leaves it "new", so the
    /// next sync fetches it again.
    static func ingest(_ file: GarminDownloadedFile, deviceId: String, store: WhoopStore,
                       log: (String) -> Void) async -> Bool {
        let now = Int(Date().timeIntervalSince1970)
        let row = ledgerRow(file.key)
        do {
            if file.fitType == .activity {
                try await ingestActivity(file, deviceId: deviceId, store: store, log: log)
                try await store.recordGarminSyncedFile(row, deviceId: deviceId, ingestedAt: now)
                return true
            }
            let samples = GarminWellnessParser.parse(data: Data(file.bytes))
            let batch = GarminWatchIngest.fold(samples, file: row,
                                               fallbackUtcOffsetS: TimeZone.current.secondsFromGMT())
            let counts = try await store.ingestGarminFile(batch, deviceId: deviceId, ingestedAt: now)
            log("Garmin: stored \(file.fitType?.name ?? "file") \(file.key.label): hr=\(counts.hr) metrics=\(counts.deviceMetric) "
                + "days=\(counts.days) sleeps=\(counts.sleeps) points=\(counts.metricPoints)"
                + (samples.complete ? "" : " (file ended early; kept what decoded)"))
            return true
        } catch {
            log("Garmin: storing \(file.key.label) failed: \(error.localizedDescription)")
            return false
        }
    }

    /// The manual workout-file import's mapping (DataSourcesView `importActivityFile`), under the watch's
    /// device id: one workout row plus its per-sample heart rate. A file with no usable activity is
    /// recorded anyway, so it is not fetched on every sync.
    private static func ingestActivity(_ file: GarminDownloadedFile, deviceId: String, store: WhoopStore,
                                       log: (String) -> Void) async throws {
        let result = ActivityFileImporter.parse(data: Data(file.bytes), filename: "garmin-activity.fit")
        guard let activity = result.activity, let duration = activity.durationS, duration > 0 else {
            log("Garmin: activity \(file.key.label) has no usable workout; recorded as synced")
            return
        }
        let row = WorkoutRow(
            startTs: Int(activity.start.timeIntervalSince1970),
            endTs: Int(activity.end.timeIntervalSince1970),
            sport: ActivityFileImporter.workoutSport(from: activity.sport),
            source: deviceId,
            durationS: activity.durationS,
            energyKcal: activity.energyKcal,
            avgHr: activity.avgHr,
            maxHr: activity.maxHr,
            strain: nil,                       // never a fabricated cardiovascular strain
            distanceM: activity.distanceM,
            zonesJSON: nil,
            notes: activity.importNote(),
            steps: activity.steps)
        try await store.upsertWorkouts([row], deviceId: deviceId)
        if !activity.hrSamples.isEmpty {
            _ = try await store.insert(Streams(hr: activity.hrSamples.map { HRSample(ts: $0.ts, bpm: $0.bpm) }),
                                       deviceId: deviceId)
        }
        log("Garmin: stored workout \(row.sport) \(row.startTs)-\(row.endTs) hr=\(activity.hrSamples.count) from \(file.key.label)")
    }
}
