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
//   - the day's stress average and Body Battery range are RE-DERIVED from the stored samples of that local
//     day after the insert, so they cover every file seen, not just this one;
//   - the day's SpO2 and respiration are the OVERNIGHT mean (`garminNightMean`): the stored samples inside
//     the stored nights that end on that day (the wake day), not a midnight-to-midnight mean that mixes in
//     daytime and exercise readings and two half-nights (#52). The same value goes to dailyMetric and
//     metricSeries, and the scoring pass reads it back from the row, so Charge and Trends agree;
//   - cumulative counters (steps, distance, calories) keep the larger of the stored and the new value:
//     a later file carries the day's running total, and an older re-sync can never lower it;
//   - every other column keeps its stored value when the file has none (COALESCE);
//   - the day's sleep figures are its MAIN night's: a file's night replaces them only when it has at least
//     as much time asleep as the stored one, so a nap file synced after the night file cannot overwrite
//     the overnight totals (#49);
//   - a night REPLACES any other stored night of the watch it overlaps (unless the user edited that one),
//     so one Garmin night stays one session when a later fold places it differently (#47: an older fold
//     split a night at long single-stage stretches and started it at its first stage change).
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
    /// Asleep / (asleep + awake) as a 0-1 fraction, the unit of `dailyMetric.efficiency` (#49).
    public var efficiency: Double?
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
    /// The day after each rollup day, when the file did not touch it: a night that began before midnight
    /// ends there, so its overnight SpO2 / respiration is re-derived if that day's row already exists.
    public var nightRefreshDays: [GarminDayRollup] = []
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

    // Plausible watch values (#52). Anything outside is a sensor sentinel or a misread, not a reading: the
    // decoder drops it, and the overnight resolver skips older stored samples outside it. Kotlin twins:
    // `GarminSyncStore.GARMIN_SPO2_MIN` and the rest, same names.
    public static let garminSpo2Min = 70.0
    public static let garminSpo2Max = 100.0
    public static let garminRespMin = 4.0
    public static let garminRespMax = 60.0
    public static let garminHrvMin = 5.0
    public static let garminHrvMax = 300.0

    /// Fewest in-night samples an overnight mean is formed from; the same floor as
    /// `GarminScoringInputs.minNightSamples`. Kotlin twin: `GarminSyncStore.GARMIN_MIN_NIGHT_SAMPLES`.
    public static let garminMinNightSamples = 3

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
            for s in ingest.sleeps {
                try db.execute(sql: """
                    DELETE FROM sleepSession
                    WHERE deviceId = ? AND startTs <> ? AND startTs < ? AND endTs > ? AND userEdited = 0
                    """, arguments: [deviceId, s.startTs, s.endTs, s.startTs])
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
            // After the nights: the overnight SpO2 / respiration read the stored nights.
            for day in ingest.days {
                try Self.mergeGarminDay(db, day, deviceId: deviceId, counts: &counts)
            }
            for day in ingest.nightRefreshDays {
                counts.metricPoints += try Self.refreshGarminNightVitals(db, day, deviceId: deviceId)
            }
            try Self.insertLedgerRow(db, ingest.file, deviceId: deviceId, ingestedAt: ingestedAt)
            return counts
        }
    }

    /// Forget every ledger row of one FIT file type, so the next sync downloads those files again (the
    /// watch keeps them; nothing on it changes). Returns the rows removed. Kotlin twin:
    /// `GarminSyncStore.forgetFiles`.
    @discardableResult
    public func forgetGarminSyncedFiles(deviceId: String, dataType: Int, subType: Int) async throws -> Int {
        try syncWrite { db in
            try db.execute(sql: "DELETE FROM garminSyncedFile WHERE deviceId = ? AND dataType = ? AND subType = ?",
                           arguments: [deviceId, dataType, subType])
            return db.changesCount
        }
    }

    /// Divide by 100 every efficiency of this device stored above 1, the percent an older fold wrote
    /// (#49). Runs once per watch (`GarminHistoryIngest.refetchSleepFilesOnce`); a fraction is never
    /// above 1, so a second run changes nothing. Returns the rows changed. Kotlin twin:
    /// `GarminSyncStore.repairEfficiencyUnits`.
    @discardableResult
    public func repairGarminEfficiencyUnits(deviceId: String) async throws -> Int {
        try syncWrite { db in
            try db.execute(sql: "UPDATE sleepSession SET efficiency = efficiency / 100.0 WHERE deviceId = ? AND efficiency > 1",
                           arguments: [deviceId])
            var changed = db.changesCount
            try db.execute(sql: "UPDATE dailyMetric SET efficiency = efficiency / 100.0 WHERE deviceId = ? AND efficiency > 1",
                           arguments: [deviceId])
            changed += db.changesCount
            return changed
        }
    }

    /// The days this device has a `dailyMetric` row for, oldest first. Kotlin twin: `GarminSyncStore.days`.
    public func garminDays(deviceId: String) async throws -> [String] {
        try syncRead { db in
            try String.fetchAll(db, sql: "SELECT day FROM dailyMetric WHERE deviceId = ? ORDER BY day",
                                arguments: [deviceId])
        }
    }

    /// Re-derive the overnight SpO2 and respiration of existing day rows from the stored samples and nights,
    /// in one transaction. Corrects rows an older ingest wrote as calendar-day means (#52); a day without a
    /// row is skipped. Returns the metricSeries rows changed. Kotlin twin: `GarminSyncStore.rederiveNightVitals`.
    @discardableResult
    public func rederiveGarminNightVitals(deviceId: String, days: [GarminDayRollup]) async throws -> Int {
        try syncWrite { db in
            var changed = 0
            for d in days { changed += try Self.refreshGarminNightVitals(db, d, deviceId: deviceId) }
            return changed
        }
    }

    /// The overnight mean of one vital for the night(s) waking on `d`: the stored samples of `kind` inside
    /// any stored night of this device whose end falls in `d.dayStartTs ..< d.dayEndTs` (night bounds
    /// inclusive, each sample counted once), within `range`, or nil under `garminMinNightSamples`. Samples
    /// are looked for from a day before the wake day's midnight, which no real night outlasts. The ONE
    /// resolver for a Garmin day's SpO2 and respiration. Kotlin twin: `GarminSyncStore.nightMean`.
    static func garminNightMean(_ db: Database, deviceId: String, kind: DeviceMetricKind,
                                range: ClosedRange<Double>, _ d: GarminDayRollup) throws -> Double? {
        guard let row = try Row.fetchOne(db, sql: """
            SELECT AVG(m.value) AS mean, COUNT(*) AS n FROM deviceMetricSample m
            WHERE m.deviceId = ? AND m.kind = ? AND m.ts >= ? AND m.ts < ? AND m.value >= ? AND m.value <= ?
              AND EXISTS (SELECT 1 FROM sleepSession s
                          WHERE s.deviceId = m.deviceId AND s.endTs >= ? AND s.endTs < ?
                            AND m.ts >= s.startTs AND m.ts <= s.endTs)
            """, arguments: [deviceId, kind.rawValue, d.dayStartTs - 86_400, d.dayEndTs,
                             range.lowerBound, range.upperBound, d.dayStartTs, d.dayEndTs])
        else { return nil }
        let n: Int = row["n"]
        return n >= garminMinNightSamples ? row["mean"] : nil
    }

    /// Write one day's overnight SpO2 and respiration to its existing row and its metricSeries points (a
    /// missing value removes the point, so the two cannot disagree). Returns the metricSeries rows changed.
    /// Kotlin twin: `GarminSyncStore.refreshNightVitals`.
    private static func refreshGarminNightVitals(_ db: Database, _ d: GarminDayRollup, deviceId: String) throws -> Int {
        guard try Bool.fetchOne(db, sql: "SELECT EXISTS (SELECT 1 FROM dailyMetric WHERE deviceId = ? AND day = ?)",
                                arguments: [deviceId, d.day]) == true else { return 0 }
        let spo2 = try garminNightMean(db, deviceId: deviceId, kind: .spo2Pct, range: garminSpo2Min...garminSpo2Max, d)
        let resp = try garminNightMean(db, deviceId: deviceId, kind: .respirationBpm, range: garminRespMin...garminRespMax, d)
        try db.execute(sql: "UPDATE dailyMetric SET spo2Pct = ?, respRateBpm = ? WHERE deviceId = ? AND day = ?",
                       arguments: [spo2, resp, deviceId, d.day])
        return try writeNightVitalPoints(db, d.day, spo2: spo2, resp: resp, deviceId: deviceId)
    }

    /// Kotlin twin: `GarminSyncStore.writeNightVitalPoints`.
    private static func writeNightVitalPoints(_ db: Database, _ day: String, spo2: Double?, resp: Double?,
                                              deviceId: String) throws -> Int {
        var changed = 0
        for (key, value) in [("spo2", spo2), ("resp_rate", resp)] {
            if let value {
                try db.execute(sql: """
                    INSERT INTO metricSeries (deviceId, day, key, value) VALUES (?, ?, ?, ?)
                    ON CONFLICT(deviceId, day, key) DO UPDATE SET value = excluded.value
                    """, arguments: [deviceId, day, key, value])
            } else {
                try db.execute(sql: "DELETE FROM metricSeries WHERE deviceId = ? AND day = ? AND key = ?",
                               arguments: [deviceId, day, key])
            }
            changed += db.changesCount
        }
        return changed
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
    /// re-derived from every stored sample of the day and the overnight vitals from its night.
    /// Kotlin twin: `GarminSyncStore.mergeDay`.
    private static func mergeGarminDay(_ db: Database, _ rollup: GarminDayRollup, deviceId: String,
                                       counts: inout GarminIngestCounts) throws {
        var d = rollup
        // The day keeps its main night: a shorter night from another file (a nap) leaves the stored
        // sleep figures alone. An equal one replaces them, so re-ingesting a night is idempotent and a
        // re-fold of the same night can correct its other columns.
        if let incoming = d.totalSleepMin,
           let stored = try Double.fetchOne(db, sql: "SELECT totalSleepMin FROM dailyMetric WHERE deviceId = ? AND day = ?",
                                            arguments: [deviceId, d.day]),
           stored > incoming {
            d.totalSleepMin = nil; d.deepMin = nil; d.lightMin = nil; d.remMin = nil; d.awakeMin = nil; d.efficiency = nil
        }
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
        let spo2 = try garminNightMean(db, deviceId: deviceId, kind: .spo2Pct, range: garminSpo2Min...garminSpo2Max, d)
        let resp = try garminNightMean(db, deviceId: deviceId, kind: .respirationBpm, range: garminRespMin...garminRespMax, d)

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
                spo2Pct = excluded.spo2Pct,
                respRateBpm = excluded.respRateBpm,
                steps = CASE WHEN excluded.steps IS NULL THEN dailyMetric.steps
                             WHEN dailyMetric.steps IS NULL THEN excluded.steps
                             ELSE MAX(excluded.steps, dailyMetric.steps) END,
                activeKcalEst = CASE WHEN excluded.activeKcalEst IS NULL THEN dailyMetric.activeKcalEst
                                     WHEN dailyMetric.activeKcalEst IS NULL THEN excluded.activeKcalEst
                                     ELSE MAX(excluded.activeKcalEst, dailyMetric.activeKcalEst) END
            """, arguments: [deviceId, d.day, d.totalSleepMin, d.efficiency, d.deepMin, d.remMin, d.lightMin,
                             d.restingHr, d.avgHrvMs, spo2, resp, d.steps, d.activeKcal])
        counts.days += db.changesCount

        let points: [(String, Double?)] = [
            ("steps", d.steps.map(Double.init)), ("distance_m", d.distanceM), ("energy_kcal", d.activeKcal),
            ("total_kcal", d.totalKcal), ("rhr", d.restingHr.map(Double.init)), ("hrv", d.avgHrvMs),
            (garminStressKey, stress),
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
        counts.metricPoints += try writeNightVitalPoints(db, d.day, spo2: spo2, resp: resp, deviceId: deviceId)
    }
}
