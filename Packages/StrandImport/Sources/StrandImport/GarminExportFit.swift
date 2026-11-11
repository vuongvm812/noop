import Foundation
import WhoopStore

// MARK: - The FIT files inside a Garmin Connect "Export Your Data" archive
//
// `DI-Connect-Uploaded-Files/UploadedFiles_*.zip` (a zip nested in the export zip) holds the watch's own
// FIT files. Each is routed by its own `file_id.type`, never by its name: ACTIVITY goes through the shared
// workout ingest, every other health type through the watch wellness decoder and fold, all stored under
// the export's device id. Files that decode to nothing are counted per reason, never silently dropped.
// This file is the pure half (routing, decode, report); `Strand/Data/GarminExportFitIngest.swift` stores.
// Kotlin twin: `GarminExportFit` (com.noop.ingest).

public enum GarminExportFit {

    /// The device id every export row is stored under, the same one the export's JSON uses.
    public static let deviceId = "garmin-import"

    static let typeActivity = 4
    static let typeMonitorA = 15
    static let typeMonitorDaily = 28
    static let typeMonitoring = 32
    static let typePrivate = 41
    static let typeMetrics = 44
    static let typeSleep = 49
    static let typeHrvStatus = 68
    static let typeSkinTemp = 73
    static let typeSleepDisruption = 79

    /// FIT seconds at 1989-12-31 minus the Unix epoch.
    static let fitEpochOffset = 631_065_600

    /// Report keys: what was stored per FIT file kind.
    public static let keyWorkouts = "workouts"
    public static let keyActivity = "fit.activity"
    public static let keyMonitoring = "fit.monitoring"
    public static let keyMetrics = "fit.metrics"
    public static let keySleep = "fit.sleep"
    public static let keyHrvStatus = "fit.hrvStatus"
    public static let keyOther = "fit.other"

    /// Report keys: files that held nothing NOOP can store, by reason.
    public static let skipInvalidData = "skipped.invalidData"
    public static let skipPrivateBackup = "skipped.privateBackup"
    public static let skipUnsupportedType = "skipped.unsupportedType"
    public static let skipNotFit = "skipped.notFit"
    public static let skipNoUsableActivity = "skipped.noUsableActivity"
    public static let skipFailed = "skipped.failed"

    /// What a FIT import stored and skipped. `counts` is name → files (and `workouts` → workout rows).
    public struct Report: Sendable, Equatable {
        public var counts: [String: Int]
        public init(counts: [String: Int] = [:]) { self.counts = counts }

        public var workouts: Int { counts[GarminExportFit.keyWorkouts] ?? 0 }
        public var storedFiles: Int { counts.filter { $0.key.hasPrefix("fit.") }.values.reduce(0, +) }
        public var skippedFiles: Int { counts.filter { $0.key.hasPrefix("skipped.") }.values.reduce(0, +) }
    }

    /// The `file_id` message of a FIT file: its type and creation time (Unix seconds, nil when absent).
    public struct FileID: Equatable, Sendable {
        public let type: Int
        public let timeCreatedUnix: Int?
    }

    /// Reads `file_id` (message 0: field 0 type, field 4 time_created). Nil for anything that is not a FIT
    /// file or has none. Kotlin twin: `GarminExportFit.fileId`.
    public static func fileID(_ data: Data) -> FileID? {
        guard let stream = FitRecordStream.open(bytes: [UInt8](data)) else { return nil }
        var found: FileID?
        // file_id is the first message of a FIT file: one definition record, then its data record.
        _ = stream.walk(maxRecords: 2) { def, offset, _ in
            guard def.globalNum == 0, found == nil else { return }
            var at = offset
            var type: Int?
            var created: Int?
            for f in def.fields {
                if f.num == 0, f.size == 1, let v = stream.uint(at: at, size: 1, bigEndian: def.bigEndian), v != 0xFF { type = Int(v) }
                if f.num == 4, f.size == 4, let v = stream.uint(at: at, size: 4, bigEndian: def.bigEndian),
                   v != 0xFFFF_FFFF, v > 0 { created = Int(v) + fitEpochOffset }
                at += f.size
            }
            if let type { found = FileID(type: type, timeCreatedUnix: created) }
        }
        return found
    }

    /// How a file is stored.
    public enum Route: Equatable, Sendable {
        case activity
        /// A health type the wellness decoder reads; the report key it counts under.
        case wellness(String)
        case privateBackup
        case unsupported
        case notFit
    }

    /// Routes a FIT file by its `file_id.type`; the Kotlin importer does the same inline.
    public static func route(_ data: Data) -> (route: Route, id: FileID?) {
        guard let id = fileID(data) else { return (.notFit, nil) }
        switch id.type {
        case typeActivity: return (.activity, id)
        case typePrivate: return (.privateBackup, id)
        case typeMonitorA, typeMonitorDaily, typeMonitoring: return (.wellness(keyMonitoring), id)
        case typeMetrics: return (.wellness(keyMetrics), id)
        case typeSleep: return (.wellness(keySleep), id)
        case typeHrvStatus: return (.wellness(keyHrvStatus), id)
        case typeSkinTemp, typeSleepDisruption: return (.wellness(keyOther), id)
        default: return (.unsupported, id)
        }
    }

    /// The store batch of one wellness FIT file under a synthetic ledger row (keyed on type, creation time and
    /// size, so re-importing the same file is idempotent), or nil when the file decoded to nothing storable.
    public static func wellnessBatch(_ data: Data, id: FileID, utcOffsetS: Int) -> GarminFileIngest? {
        let samples = GarminWellnessParser.parse(data: data)
        let created = id.timeCreatedUnix.map { $0 - fitEpochOffset } ?? 0
        let row = GarminSyncedFileRow(fileIndex: 0, dataType: 128, subType: id.type, fileNumber: 0, ts: created, size: data.count)
        let batch = GarminWatchIngest.fold(samples, file: row, fallbackUtcOffsetS: utcOffsetS)
        let empty = batch.hr.isEmpty && batch.deviceMetric.isEmpty && batch.days.isEmpty && batch.sleeps.isEmpty
        return empty ? nil : batch
    }

    /// What one FIT file amounts to, before anything is stored.
    public enum Outcome: Equatable {
        /// An ACTIVITY file; the app layer decodes and stores it (it may still hold no usable workout).
        case activity
        /// A wellness file with something to store, and the report key it counts under.
        case wellness(key: String, batch: GarminFileIngest)
        /// Nothing storable, and the `skipped.*` reason.
        case skipped(String)
    }

    /// Routes and decodes one FIT file; the Kotlin importer does the same inline.
    public static func decode(_ data: Data, utcOffsetS: Int) -> Outcome {
        let (route, id) = route(data)
        switch route {
        case .notFit: return .skipped(skipNotFit)
        case .activity: return .activity
        case .privateBackup: return .skipped(skipPrivateBackup)
        case .unsupported: return .skipped(skipUnsupportedType)
        case .wellness(let key):
            guard let id, let batch = wellnessBatch(data, id: id, utcOffsetS: utcOffsetS) else { return .skipped(skipInvalidData) }
            return .wellness(key: key, batch: batch)
        }
    }
}
