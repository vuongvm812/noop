import XCTest
import GRDB
import WhoopProtocol
@testable import WhoopStore

/// v49 migration: `deviceMetricSample` (watch-computed series) and `garminSyncedFile` (history-sync
/// bookkeeping) for the Garmin source.
///
/// The column shapes and primary keys are pinned by `SchemaOracleTests` against the shared
/// schema_oracle.json, and "delete all of this device's data" by the `DeviceRegistryStoreTests` guard
/// over every deviceId-keyed table. This file proves what those cannot: that the migration is additive,
/// and that ingesting the same samples twice leaves one copy.
///
/// Kotlin twin: `GarminMetricStorageMigrationTest`.
final class GarminMetricStorageTests: XCTestCase {

    private func samples(_ value: Double = 42) -> [DeviceMetricSample] {
        [
            DeviceMetricSample(ts: 1_760_000_000, kind: .stress, value: value, source: "fit.stress_level"),
            DeviceMetricSample(ts: 1_760_000_000, kind: .bodyBattery, value: 77, source: "fit.stress_level"),
            DeviceMetricSample(ts: 1_760_000_060, kind: .stress, value: 30, source: "fit.stress_level"),
        ]
    }

    private func metricRows(_ store: WhoopStore, deviceId: String) async throws -> [Row] {
        try await store.dbWriter.read { db in
            try Row.fetchAll(db, sql: """
                SELECT ts, kind, value, source FROM deviceMetricSample
                WHERE deviceId = ? ORDER BY kind, ts
                """, arguments: [deviceId])
        }
    }

    // MARK: - migration

    /// Rows written before v49 survive it untouched, and the two new tables start empty.
    func testMigrationIsAdditive() throws {
        let dbQueue = try DatabaseQueue()
        let migrator = WhoopStore.makeMigrator()
        try migrator.migrate(dbQueue, upTo: "v48-ecg-waveform")
        try dbQueue.write { db in
            try db.execute(sql: "INSERT INTO hrSample (deviceId, ts, bpm) VALUES ('garmin-1', 100, 61)")
        }
        try migrator.migrate(dbQueue)
        try dbQueue.read { db in
            XCTAssertEqual(try Int.fetchOne(db, sql: "SELECT bpm FROM hrSample WHERE deviceId = 'garmin-1'"), 61)
            XCTAssertEqual(try Int.fetchOne(db, sql: "SELECT COUNT(*) FROM deviceMetricSample"), 0)
            XCTAssertEqual(try Int.fetchOne(db, sql: "SELECT COUNT(*) FROM garminSyncedFile"), 0)
        }
    }

    // MARK: - deviceMetricSample

    func testSamplesRoundTripThroughTheInsertPath() async throws {
        let store = try await WhoopStore.inMemory()
        try await store.insert(Streams(deviceMetric: samples()), deviceId: "garmin-1")
        let rows = try await metricRows(store, deviceId: "garmin-1")
        XCTAssertEqual(rows.map { $0["kind"] as String }, ["body_battery", "stress", "stress"])
        XCTAssertEqual(rows.map { $0["ts"] as Int }, [1_760_000_000, 1_760_000_000, 1_760_000_060])
        XCTAssertEqual(rows.map { $0["value"] as Double }, [77, 42, 30])
        XCTAssertEqual(Set(rows.map { $0["source"] as String }), ["fit.stress_level"])
    }

    /// The acceptance criterion: re-ingesting a file is idempotent.
    func testIngestingTheSameSamplesTwiceLeavesOneCopy() async throws {
        let store = try await WhoopStore.inMemory()
        try await store.insert(Streams(deviceMetric: samples()), deviceId: "garmin-1")
        let first = try await metricRows(store, deviceId: "garmin-1")
        try await store.insert(Streams(deviceMetric: samples()), deviceId: "garmin-1")
        let second = try await metricRows(store, deviceId: "garmin-1")
        XCTAssertEqual(second.count, 3)
        XCTAssertEqual(first, second)
    }

    /// Upsert, not ignore: a re-synced partial file that now carries a different value for the same
    /// (kind, second) replaces the old one rather than being dropped.
    func testAReSyncedValueReplacesTheOldOne() async throws {
        let store = try await WhoopStore.inMemory()
        try await store.insert(Streams(deviceMetric: samples(42)), deviceId: "garmin-1")
        try await store.insert(Streams(deviceMetric: samples(55)), deviceId: "garmin-1")
        let rows = try await metricRows(store, deviceId: "garmin-1")
        XCTAssertEqual(rows.count, 3)
        let stressAtFirst = rows.first { ($0["kind"] as String) == "stress" && ($0["ts"] as Int) == 1_760_000_000 }
        XCTAssertEqual(stressAtFirst?["value"] as Double?, 55)
    }

    /// The key is per device: the same kind and second on two watches are two rows.
    func testTheKeyIsPerDevice() async throws {
        let store = try await WhoopStore.inMemory()
        try await store.insert(Streams(deviceMetric: samples()), deviceId: "garmin-1")
        try await store.insert(Streams(deviceMetric: samples()), deviceId: "garmin-2")
        let one = try await metricRows(store, deviceId: "garmin-1")
        let two = try await metricRows(store, deviceId: "garmin-2")
        XCTAssertEqual(one.count, 3)
        XCTAssertEqual(two.count, 3)
    }

    /// A NaN would hit the NOT NULL constraint and roll back the whole batch. It is skipped instead, and
    /// the rest of the batch (another stream included) still lands.
    func testANonFiniteValueIsSkippedWithoutLosingTheBatch() async throws {
        let store = try await WhoopStore.inMemory()
        let streams = Streams(
            hr: [HRSample(ts: 1_760_000_000, bpm: 58)],
            deviceMetric: [
                DeviceMetricSample(ts: 1_760_000_000, kind: .spo2Pct, value: .nan, source: "fit.spo2_data"),
                DeviceMetricSample(ts: 1_760_000_000, kind: .respirationBpm, value: .infinity, source: "fit.resp"),
                DeviceMetricSample(ts: 1_760_000_000, kind: .hrvMs, value: 48.5, source: "fit.hrv_value"),
            ])
        try await store.insert(streams, deviceId: "garmin-1")
        let rows = try await metricRows(store, deviceId: "garmin-1")
        XCTAssertEqual(rows.map { $0["kind"] as String }, ["hrv_ms"])
        let hr = try await store.dbWriter.read { db in
            try Int.fetchOne(db, sql: "SELECT bpm FROM hrSample WHERE deviceId = 'garmin-1'")
        }
        XCTAssertEqual(hr, 58)
    }

    /// A batch carrying ONLY watch metrics is not "empty"; if it were, a caller that skips empty batches
    /// would bank nothing.
    func testABatchOfOnlyMetricsIsNotEmpty() {
        XCTAssertFalse(Streams(deviceMetric: samples()).isEmpty)
    }

    /// The stored `kind` strings are shared with Android. Kotlin twin:
    /// `GarminMetricStorageMigrationTest.kindWireValuesMatchSwift`, which asserts this same list.
    func testKindWireValuesArePinned() {
        XCTAssertEqual(DeviceMetricKind.allCases.map(\.rawValue), [
            "stress", "body_battery", "respiration_bpm", "spo2_pct",
            "intensity_moderate_min", "intensity_vigorous_min", "active_kcal", "hrv_ms", "sleep_score",
            "aerobic_training_effect", "anaerobic_training_effect", "training_load",
        ])
    }

    // MARK: - garminSyncedFile

    /// The ledger's key is the whole directory entry. Marking the same file twice keeps one row (and the
    /// FIRST ingest time); a file the watch rewrote under the same index, now larger, is a new row.
    func testSyncedFileKeyIsTheWholeDirectoryEntry() async throws {
        let store = try await WhoopStore.inMemory()
        let insert = """
            INSERT INTO garminSyncedFile
                (deviceId, fileIndex, dataType, subType, fileNumber, ts, size, ingestedAt)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT DO NOTHING
            """
        try await store.dbWriter.write { db in
            try db.execute(sql: insert, arguments: ["garmin-1", 7, 128, 32, 1, 1_100_000_000, 4096, 1_760_000_000])
            try db.execute(sql: insert, arguments: ["garmin-1", 7, 128, 32, 1, 1_100_000_000, 4096, 1_760_009_999])
            try db.execute(sql: insert, arguments: ["garmin-1", 7, 128, 32, 1, 1_100_000_000, 8192, 1_760_010_000])
        }
        let rows = try await store.dbWriter.read { db in
            try Row.fetchAll(db, sql: "SELECT size, ingestedAt FROM garminSyncedFile ORDER BY size")
        }
        XCTAssertEqual(rows.map { $0["size"] as Int }, [4096, 8192])
        XCTAssertEqual(rows.first?["ingestedAt"] as Int?, 1_760_000_000)
    }

    /// Deleting a device's data clears its sync ledger too, so a later sync can restore files still on
    /// the watch instead of skipping them as "already ingested".
    func testDeleteAllDataClearsBothTables() async throws {
        let store = try await WhoopStore.inMemory()
        try await store.insert(Streams(deviceMetric: samples()), deviceId: "garmin-1")
        try await store.insert(Streams(deviceMetric: samples()), deviceId: "garmin-2")
        try await store.dbWriter.write { db in
            try db.execute(sql: """
                INSERT INTO garminSyncedFile
                    (deviceId, fileIndex, dataType, subType, fileNumber, ts, size, ingestedAt)
                VALUES ('garmin-1', 7, 128, 32, 1, 1100000000, 4096, 1760000000)
                """)
        }
        let writer = await store.dbWriter
        try DeviceRegistryStore(dbQueue: writer).deleteAllData(deviceId: "garmin-1")
        let left = try await store.dbWriter.read { db in
            (try Int.fetchOne(db, sql: "SELECT COUNT(*) FROM deviceMetricSample WHERE deviceId = 'garmin-1'"),
             try Int.fetchOne(db, sql: "SELECT COUNT(*) FROM garminSyncedFile"),
             try Int.fetchOne(db, sql: "SELECT COUNT(*) FROM deviceMetricSample WHERE deviceId = 'garmin-2'"))
        }
        XCTAssertEqual(left.0, 0)
        XCTAssertEqual(left.1, 0)
        XCTAssertEqual(left.2, 3, "another device's rows are untouched")
    }
}
