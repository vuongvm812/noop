import Foundation
import GRDB
import WhoopProtocol

// MARK: - Garmin history-sync persistence
//
// One downloaded watch file becomes ONE transaction: its samples, the day rollups it touches, its sleep
// sessions, and its row in the `garminSyncedFile` ledger (the record that replaces archiving the file on
// the watch). Either all of it lands or none does, so a crash mid-ingest leaves the file "new" and the
// next sync fetches it again; and a file is never recorded as synced without its data.
//
// A watch splits a day across many files (a Forerunner 165 writes a small MONITOR file every few
// minutes), so one file's day totals are partial. The writes therefore MERGE rather than overwrite:
//   - per-sample series (hrSample, deviceMetricSample) are keyed by second, so files simply add up;
//   - the day's averages and ranges (stress, Body Battery, SpO2, respiration) are RE-DERIVED from the
//     stored samples of that local day after the insert, so they cover every file seen, not just this one;
//   - cumulative counters (steps, distance, calories) keep the larger of the stored and the new value:
//     a later file carries the day's running total, and an older re-sync can never lower it;
//   - every other column keeps its stored value when the file has none (COALESCE).
// Kotlin twin: android/app/src/main/java/com/noop/data/GarminSyncStore.kt, over the same SQL.

/// One row of the `garminSyncedFile` ledger: the identity of a watch file NOOP has ingested.
public struct GarminSyncedFileRow: Equatable, Hashable, Sendable {
    public let fileIndex: Int
    public let dataType: Int
    public let subType: Int
    public let fileNumber: Int
    /// Garmin-epoch seconds, as the watch's directory gives it (0 = no date).
    public let ts: Int
    public let size: Int

    public init(fileIndex: Int, dataType: Int, subType: Int, fileNumber: Int, ts: Int, size: Int) {
        self.fileIndex = fileIndex
        self.dataType = dataType
        self.subType = subType
        self.fileNumber = fileNumber
        self.ts = ts
        self.size = size
    }
}

/// What one Garmin wellness file adds to one local day. Every field is optional: a file carries only
/// what it recorded. `dayStartTs ..< dayEndTs` is the day's span in Unix seconds (local midnight to
/// midnight, from the file's own UTC offset), used to re-derive the day's sample aggregates.
public struct GarminDayRollup: Equatable, Sendable {
    public var day: String
    public var dayStartTs: Int
    public var dayEndTs: Int
    // Cumulative counters: merged with MAX.
    public var steps: Int?
    public var distanceM: Double?
    public var activeKcal: Double?
    public var totalKcal: Double?
    // Point values: the newest file's value wins, a missing one keeps the stored value.
    public var restingHr: Int?
    public var avgHrvMs: Double?
    public var totalSleepMin: Double?
    public var deepMin: Double?
    public var lightMin: Double?
    public var remMin: Double?
    public var awakeMin: Double?
    public var efficiencyPct: Double?
    public var sleepScore: Int?

    public init(day: String, dayStartTs: Int, dayEndTs: Int) {
        self.day = day
        self.dayStartTs = dayStartTs
        self.dayEndTs = dayEndTs
    }
}

/// Everything one downloaded file contributes, ready to persist.
public struct GarminFileIngest: Equatable {
    public var hr: [HRSample] = []
    public var deviceMetric: [DeviceMetricSample] = []
    public var days: [GarminDayRollup] = []
    public var sleeps: [CachedSleepSession] = []
    public var file: GarminSyncedFileRow

    public init(file: GarminSyncedFileRow) { self.file = file }
}

/// Rows the ingest actually wrote, for the Test Centre log.
public struct GarminIngestCounts: Equatable, Sendable {
    public var hr = 0
    public var deviceMetric = 0
    public var days = 0
    public var sleeps = 0
    public var metricPoints = 0
    public init() {}
}

extension WhoopStore {

    /// The `metricSeries` keys a Garmin day writes, with how each merges. Shared with Kotlin
    /// (`GarminSyncStore.MAX_KEYS`); the names match what `WearableImporter` writes for a Garmin export.
    static let garminMaxKeys: Set<String> = ["steps", "distance_m", "energy_kcal", "total_kcal"]

    /// The day's average of the watch's own 0-100 stress score. NOT "stress": that key carries WHOOP's 0-3
    /// stress series, which the Stress model clamps to 0-3, so a Garmin 72 stored there read as maximum
    /// stress on Apple and was invisible on Android (Garmin child 9). Kotlin twin:
    /// `GarminSyncStore.GARMIN_STRESS_KEY`.
    public static let garminStressKey = "garmin_stress"

    /// Every file of this device recorded as ingested. Kotlin twin: `GarminSyncStore.syncedFiles`.
    public func garminSyncedFiles(deviceId: String) async throws -> [GarminSyncedFileRow] {
        try syncRead { db in
            try Row.fetchAll(db, sql: """
                SELECT fileIndex, dataType, subType, fileNumber, ts, size FROM garminSyncedFile
                WHERE deviceId = ? ORDER BY ts, fileIndex
                """, arguments: [deviceId]).map {
                GarminSyncedFileRow(fileIndex: $0["fileIndex"], dataType: $0["dataType"], subType: $0["subType"],
                                    fileNumber: $0["fileNumber"], ts: $0["ts"], size: $0["size"])
            }
        }
    }

    /// One watch-computed series for a device over [from, to] (inclusive, unix seconds), oldest first. The
    /// analytics pass reads a Garmin-owned night's HRV and respiration values from here (Garmin child 9).
    /// Kotlin twin: `WhoopRepository.deviceMetricSamples`.
    public func deviceMetricSamples(deviceId: String, kind: DeviceMetricKind, from: Int, to: Int,
                                    limit: Int = 50_000) async throws -> [DeviceMetricSample] {
        try syncRead { db in
            try Row.fetchAll(db, sql: """
                SELECT ts, value, source FROM deviceMetricSample
                WHERE deviceId = ? AND kind = ? AND ts >= ? AND ts <= ? ORDER BY ts LIMIT ?
                """, arguments: [deviceId, kind.rawValue, from, to, limit]).map {
                DeviceMetricSample(ts: $0["ts"], kind: kind, value: $0["value"], source: $0["source"])
            }
        }
    }

    /// Record a file as ingested without data (an ACTIVITY file the workout importer stored, or a file
    /// that decoded to nothing), so it is not fetched again. Idempotent. Kotlin twin: `GarminSyncStore.record`.
    public func recordGarminSyncedFile(_ file: GarminSyncedFileRow, deviceId: String, ingestedAt: Int) async throws {
        try syncWrite { db in try Self.insertLedgerRow(db, file, deviceId: deviceId, ingestedAt: ingestedAt) }
    }

    /// Persist one file's data and its ledger row in ONE transaction (see the header comment for the
    /// merge rules). Idempotent: re-ingesting the same file leaves every table as it was.
    /// Kotlin twin: `GarminSyncStore.ingest`.
    @discardableResult
    public func ingestGarminFile(_ ingest: GarminFileIngest, deviceId: String, ingestedAt: Int) async throws
        -> GarminIngestCounts {
        try syncWrite { db in
            var counts = GarminIngestCounts()
            let hr = try db.cachedStatement(sql: """
                INSERT INTO hrSample (deviceId, ts, bpm, synced) VALUES (?, ?, ?, 0)
                ON CONFLICT(deviceId, ts) DO NOTHING
                """)
            for s in ingest.hr {
                try hr.execute(arguments: [deviceId, s.ts, s.bpm])
                counts.hr += db.changesCount
            }
            let metric = try db.cachedStatement(sql: """
                INSERT INTO deviceMetricSample (deviceId, ts, kind, value, source) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(deviceId, kind, ts) DO UPDATE SET value = excluded.value, source = excluded.source
                """)
            for s in ingest.deviceMetric where s.value.isFinite {
                try metric.execute(arguments: [deviceId, s.ts, s.kind.rawValue, s.value, s.source])
                counts.deviceMetric += db.changesCount
            }
            for day in ingest.days {
                try Self.mergeGarminDay(db, day, deviceId: deviceId, counts: &counts)
            }
            for s in ingest.sleeps {
                try db.execute(sql: """
                    INSERT INTO sleepSession (deviceId, startTs, endTs, efficiency, restingHr, avgHrv, stagesJSON,
                                              userEdited, startTsAdjusted, stagingSparse)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 0, NULL, NULL)
                    ON CONFLICT(deviceId, startTs) DO UPDATE SET
                        endTs = CASE WHEN sleepSession.userEdited THEN sleepSession.endTs ELSE excluded.endTs END,
                        efficiency = COALESCE(excluded.efficiency, sleepSession.efficiency),
                        restingHr = COALESCE(excluded.restingHr, sleepSession.restingHr),
                        avgHrv = COALESCE(excluded.avgHrv, sleepSession.avgHrv),
                        stagesJSON = CASE WHEN sleepSession.userEdited THEN sleepSession.stagesJSON
                                          ELSE COALESCE(excluded.stagesJSON, sleepSession.stagesJSON) END
                    """, arguments: [deviceId, s.startTs, s.endTs, s.efficiency, s.restingHr, s.avgHrv, s.stagesJSON])
                counts.sleeps += db.changesCount
            }
            try Self.insertLedgerRow(db, ingest.file, deviceId: deviceId, ingestedAt: ingestedAt)
            return counts
        }
    }

    /// Kotlin twin: `GarminSyncStore.insertLedgerRow`.
    private static func insertLedgerRow(_ db: Database, _ f: GarminSyncedFileRow, deviceId: String, ingestedAt: Int) throws {
        try db.execute(sql: """
            INSERT INTO garminSyncedFile (deviceId, fileIndex, dataType, subType, fileNumber, ts, size, ingestedAt)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT DO NOTHING
            """, arguments: [deviceId, f.fileIndex, f.dataType, f.subType, f.fileNumber, f.ts, f.size, ingestedAt])
    }

    /// Merge one day: the dailyMetric row, then the metricSeries points, with the sample aggregates
    /// re-derived from every stored sample of the day. Kotlin twin: `GarminSyncStore.mergeDay`.
    private static func mergeGarminDay(_ db: Database, _ d: GarminDayRollup, deviceId: String,
                                       counts: inout GarminIngestCounts) throws {
        // Kotlin twin: `GarminSyncStore.aggregate`.
        func aggregate(_ kind: DeviceMetricKind, _ fn: String) throws -> Double? {
            try Double.fetchOne(db, sql: """
                SELECT \(fn)(value) FROM deviceMetricSample
                WHERE deviceId = ? AND kind = ? AND ts >= ? AND ts < ?
                """, arguments: [deviceId, kind.rawValue, d.dayStartTs, d.dayEndTs])
        }
        let stress = try aggregate(.stress, "AVG")
        let bbMin = try aggregate(.bodyBattery, "MIN")
        let bbMax = try aggregate(.bodyBattery, "MAX")
        let spo2 = try aggregate(.spo2Pct, "AVG")
        let resp = try aggregate(.respirationBpm, "AVG")

        try db.execute(sql: """
            INSERT INTO dailyMetric (deviceId, day, totalSleepMin, efficiency, deepMin, remMin, lightMin,
                                     restingHr, avgHrv, spo2Pct, respRateBpm, steps, activeKcalEst)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(deviceId, day) DO UPDATE SET
                totalSleepMin = COALESCE(excluded.totalSleepMin, dailyMetric.totalSleepMin),
                efficiency = COALESCE(excluded.efficiency, dailyMetric.efficiency),
                deepMin = COALESCE(excluded.deepMin, dailyMetric.deepMin),
                remMin = COALESCE(excluded.remMin, dailyMetric.remMin),
                lightMin = COALESCE(excluded.lightMin, dailyMetric.lightMin),
                restingHr = COALESCE(excluded.restingHr, dailyMetric.restingHr),
                avgHrv = COALESCE(excluded.avgHrv, dailyMetric.avgHrv),
                spo2Pct = COALESCE(excluded.spo2Pct, dailyMetric.spo2Pct),
                respRateBpm = COALESCE(excluded.respRateBpm, dailyMetric.respRateBpm),
                steps = CASE WHEN excluded.steps IS NULL THEN dailyMetric.steps
                             WHEN dailyMetric.steps IS NULL THEN excluded.steps
                             ELSE MAX(excluded.steps, dailyMetric.steps) END,
                activeKcalEst = CASE WHEN excluded.activeKcalEst IS NULL THEN dailyMetric.activeKcalEst
                                     WHEN dailyMetric.activeKcalEst IS NULL THEN excluded.activeKcalEst
                                     ELSE MAX(excluded.activeKcalEst, dailyMetric.activeKcalEst) END
            """, arguments: [deviceId, d.day, d.totalSleepMin, d.efficiencyPct, d.deepMin, d.remMin, d.lightMin,
                             d.restingHr, d.avgHrvMs, spo2, resp, d.steps, d.activeKcal])
        counts.days += db.changesCount

        let points: [(String, Double?)] = [
            ("steps", d.steps.map(Double.init)), ("distance_m", d.distanceM), ("energy_kcal", d.activeKcal),
            ("total_kcal", d.totalKcal), ("rhr", d.restingHr.map(Double.init)), ("hrv", d.avgHrvMs),
            ("spo2", spo2), ("resp_rate", resp), (garminStressKey, stress),
            ("body_battery_min", bbMin), ("body_battery_max", bbMax),
            ("sleep_total_min", d.totalSleepMin), ("sleep_deep_min", d.deepMin),
            ("sleep_light_min", d.lightMin), ("sleep_rem_min", d.remMin), ("sleep_awake_min", d.awakeMin),
            ("ref_sleep_score", d.sleepScore.map(Double.init)),
        ]
        for (key, value) in points {
            guard let value, value.isFinite else { continue }
            let update = garminMaxKeys.contains(key) ? "MAX(excluded.value, metricSeries.value)" : "excluded.value"
            try db.execute(sql: """
                INSERT INTO metricSeries (deviceId, day, key, value) VALUES (?, ?, ?, ?)
                ON CONFLICT(deviceId, day, key) DO UPDATE SET value = \(update)
                """, arguments: [deviceId, d.day, key, value])
            counts.metricPoints += db.changesCount
        }
    }
}
