import XCTest
import GRDB
import WhoopProtocol
@testable import WhoopStore

/// History-sync persistence (`ingestGarminFile`): one transaction per watch file, merge-on-write day
/// rollups, the `garminSyncedFile` ledger, and idempotent re-ingest. Kotlin twin: `GarminSyncStoreTest`.
final class GarminSyncStoreTests: XCTestCase {

    private let id = "garmin-test"
    /// 2026-11-01 00:00 at UTC+7, the Forerunner 165's zone in the hardware runs.
    private let dayStart = 1_793_466_000

    private func file(_ index: Int, size: Int = 100) -> GarminSyncedFileRow {
        GarminSyncedFileRow(fileIndex: index, dataType: 128, subType: 32, fileNumber: index, ts: 1_162_000_000 + index, size: size)
    }

    private func day(steps: Int? = nil, rhr: Int? = nil, sleep: Double? = nil) -> GarminDayRollup {
        var d = GarminDayRollup(day: "2026-11-01", dayStartTs: dayStart, dayEndTs: dayStart + 86_400)
        d.steps = steps
        d.restingHr = rhr
        d.totalSleepMin = sleep
        return d
    }

    private func count(_ store: WhoopStore, _ table: String) async throws -> Int {
        try await store.dbWriter.read { db in try Int.fetchOne(db, sql: "SELECT COUNT(*) FROM \(table) WHERE deviceId = ?", arguments: [self.id])! }
    }

    private func series(_ store: WhoopStore, _ key: String) async throws -> Double? {
        try await store.metricSeries(deviceId: id, key: key, from: "2026-11-01", to: "2026-11-01").first?.value
    }

    func testIngestIsOneTransactionWithItsLedgerRowAndIsIdempotent() async throws {
        let store = try await WhoopStore.inMemory()
        var ingest = GarminFileIngest(file: file(1))
        ingest.hr = [HRSample(ts: dayStart + 60, bpm: 61), HRSample(ts: dayStart + 120, bpm: 64)]
        ingest.deviceMetric = [DeviceMetricSample(ts: dayStart + 60, kind: .stress, value: 20, source: "fit227"),
                               DeviceMetricSample(ts: dayStart + 60, kind: .bodyBattery, value: 80, source: "fit227")]
        ingest.days = [day(steps: 1_000, rhr: 59)]
        ingest.sleeps = [CachedSleepSession(startTs: dayStart - 20_000, endTs: dayStart + 5_000, efficiency: 91,
                                            restingHr: nil, avgHrv: nil, stagesJSON: "[]")]
        let first = try await store.ingestGarminFile(ingest, deviceId: id, ingestedAt: 1)
        XCTAssertEqual(first.hr, 2)
        XCTAssertEqual(first.deviceMetric, 2)
        let ledger = try await store.garminSyncedFiles(deviceId: id)
        XCTAssertEqual(ledger, [file(1)])

        let tables = ["hrSample", "deviceMetricSample", "dailyMetric", "sleepSession", "metricSeries", "garminSyncedFile"]
        var before: [Int] = []
        for t in tables { before.append(try await count(store, t)) }
        _ = try await store.ingestGarminFile(ingest, deviceId: id, ingestedAt: 2)
        var after: [Int] = []
        for t in tables { after.append(try await count(store, t)) }
        XCTAssertEqual(before, after, "re-ingesting a file adds nothing")
        let ledgerAfter = try await store.garminSyncedFiles(deviceId: id)
        XCTAssertEqual(ledgerAfter.count, 1)
    }

    func testAFailedIngestRecordsNothing() async throws {
        let store = try await WhoopStore.inMemory()
        var ingest = GarminFileIngest(file: file(1))
        ingest.hr = [HRSample(ts: dayStart, bpm: 60)]
        // A trigger makes the day write fail mid-file: the whole file must roll back, ledger included.
        try await store.dbWriter.write { db in
            try db.execute(sql: "CREATE TRIGGER fail_day BEFORE INSERT ON dailyMetric BEGIN SELECT RAISE(ABORT, 'test'); END")
        }
        ingest.days = [day(steps: 5)]
        do {
            _ = try await store.ingestGarminFile(ingest, deviceId: id, ingestedAt: 1)
            XCTFail("expected the trigger to abort the ingest")
        } catch {}
        let hr = try await count(store, "hrSample")
        let ledger = try await store.garminSyncedFiles(deviceId: id)
        XCTAssertEqual(hr, 0)
        XCTAssertTrue(ledger.isEmpty, "a file is never recorded as synced without its data")
    }

    func testDaysMergeAcrossFiles() async throws {
        let store = try await WhoopStore.inMemory()
        // File 1: morning. File 2: the day's running total so far, no resting HR. File 3: an OLDER re-sync.
        var a = GarminFileIngest(file: file(1))
        a.days = [day(steps: 4_000, rhr: 58)]
        a.deviceMetric = [DeviceMetricSample(ts: dayStart + 3_600, kind: .stress, value: 30, source: "fit227"),
                          DeviceMetricSample(ts: dayStart + 3_600, kind: .bodyBattery, value: 90, source: "fit227")]
        var b = GarminFileIngest(file: file(2))
        b.days = [day(steps: 16_532)]
        b.deviceMetric = [DeviceMetricSample(ts: dayStart + 40_000, kind: .stress, value: 50, source: "fit227"),
                          DeviceMetricSample(ts: dayStart + 40_000, kind: .bodyBattery, value: 40, source: "fit227"),
                          // A sample on the NEXT day does not leak into this day's aggregates.
                          DeviceMetricSample(ts: dayStart + 86_400 + 10, kind: .stress, value: 99, source: "fit227")]
        var c = GarminFileIngest(file: file(3))
        c.days = [day(steps: 2_000, sleep: 420)]
        for f in [a, b, c] { _ = try await store.ingestGarminFile(f, deviceId: id, ingestedAt: 1) }

        let metric = try await store.dailyMetrics(deviceId: id, from: "2026-11-01", to: "2026-11-01").first
        XCTAssertEqual(metric?.steps, 16_532, "a cumulative counter never goes down")
        XCTAssertEqual(metric?.restingHr, 58, "a file without resting HR keeps the stored one")
        XCTAssertEqual(metric?.totalSleepMin, 420)
        let steps = try await series(store, "steps")
        let stress = try await series(store, WhoopStore.garminStressKey)
        let bbMin = try await series(store, "body_battery_min")
        let bbMax = try await series(store, "body_battery_max")
        XCTAssertEqual(steps, 16_532)
        XCTAssertEqual(stress, 40, "the day's stress average covers every file's samples")
        XCTAssertEqual(bbMin, 40)
        XCTAssertEqual(bbMax, 90)
        let ledger = try await store.garminSyncedFiles(deviceId: id)
        XCTAssertEqual(ledger.count, 3)
    }

    func testSleepSessionMergeKeepsAUserEdit() async throws {
        let store = try await WhoopStore.inMemory()
        var a = GarminFileIngest(file: file(1))
        a.sleeps = [CachedSleepSession(startTs: dayStart, endTs: dayStart + 20_000, efficiency: 90, restingHr: nil,
                                       avgHrv: nil, stagesJSON: "[{\"start\":1,\"end\":2,\"stage\":\"deep\"}]")]
        _ = try await store.ingestGarminFile(a, deviceId: id, ingestedAt: 1)
        _ = try await store.applySleepEdit(deviceId: id, detectedStartTs: dayStart, newStartTs: dayStart, newEndTs: dayStart + 25_000)
        var b = GarminFileIngest(file: file(2))
        b.sleeps = [CachedSleepSession(startTs: dayStart, endTs: dayStart + 21_000, efficiency: nil, restingHr: nil,
                                       avgHrv: 40, stagesJSON: nil)]
        _ = try await store.ingestGarminFile(b, deviceId: id, ingestedAt: 1)
        let night = try await store.sleepSessions(deviceId: id, from: dayStart - 1, to: dayStart + 1, limit: 4).first
        XCTAssertEqual(night?.endTs, dayStart + 25_000, "the user's correction wins")
        XCTAssertEqual(night?.efficiency, 90, "a missing value keeps the stored one")
        XCTAssertEqual(night?.avgHrv, 40)
        XCTAssertEqual(night?.stagesJSON, "[{\"start\":1,\"end\":2,\"stage\":\"deep\"}]")
    }

    /// #47: one Garmin night stays one session. A night REPLACES the stored nights of the watch it overlaps
    /// (an older fold's split rows, or a partial night from a mid-night sync), keeps the other nights, and
    /// re-ingesting it changes nothing.
    func testANightReplacesTheStoredNightsItOverlaps() async throws {
        let store = try await WhoopStore.inMemory()
        let s = dayStart + 3_600
        func night(_ start: Int, _ end: Int) -> CachedSleepSession {
            CachedSleepSession(startTs: start, endTs: end, efficiency: nil, restingHr: nil, avgHrv: nil, stagesJSON: nil)
        }
        var old = GarminFileIngest(file: file(1))
        old.sleeps = [night(s - 80_000, s - 60_000), night(s + 1_020, s + 6_000), night(s + 9_960, s + 11_340),
                      night(s + 19_620, s + 29_623)]
        _ = try await store.ingestGarminFile(old, deviceId: id, ingestedAt: 1)
        var partial = GarminFileIngest(file: file(2))
        partial.sleeps = [night(s, s + 11_340)]
        _ = try await store.ingestGarminFile(partial, deviceId: id, ingestedAt: 1)
        var full = GarminFileIngest(file: file(3))
        full.sleeps = [night(s, s + 29_623)]
        for _ in 0..<2 { _ = try await store.ingestGarminFile(full, deviceId: id, ingestedAt: 1) }
        let nights = try await store.sleepSessions(deviceId: id, from: s - 90_000, to: s + 90_000, limit: 10)
        XCTAssertEqual(nights.map { [$0.startTs, $0.endTs] }.sorted { $0[0] < $1[0] },
                       [[s - 80_000, s - 60_000], [s, s + 29_623]])
    }

    func testAnOverlappingNightTheUserEditedIsKept() async throws {
        let store = try await WhoopStore.inMemory()
        var a = GarminFileIngest(file: file(1))
        a.sleeps = [CachedSleepSession(startTs: dayStart + 1_000, endTs: dayStart + 5_000, efficiency: nil, restingHr: nil,
                                       avgHrv: nil, stagesJSON: nil)]
        _ = try await store.ingestGarminFile(a, deviceId: id, ingestedAt: 1)
        _ = try await store.applySleepEdit(deviceId: id, detectedStartTs: dayStart + 1_000, newStartTs: dayStart + 1_000,
                                           newEndTs: dayStart + 6_000)
        var b = GarminFileIngest(file: file(2))
        b.sleeps = [CachedSleepSession(startTs: dayStart, endTs: dayStart + 9_000, efficiency: nil, restingHr: nil,
                                       avgHrv: nil, stagesJSON: nil)]
        _ = try await store.ingestGarminFile(b, deviceId: id, ingestedAt: 1)
        let count = try await count(store, "sleepSession")
        XCTAssertEqual(count, 2)
    }

    func testForgettingAFileTypeLeavesTheOthers() async throws {
        let store = try await WhoopStore.inMemory()
        let sleep = GarminSyncedFileRow(fileIndex: 9, dataType: 255, subType: 49, fileNumber: 9, ts: 1_162_000_009, size: 900)
        try await store.recordGarminSyncedFile(sleep, deviceId: id, ingestedAt: 1)
        try await store.recordGarminSyncedFile(file(1), deviceId: id, ingestedAt: 1)
        let forgotten = try await store.forgetGarminSyncedFiles(deviceId: id, dataType: 255, subType: 49)
        XCTAssertEqual(forgotten, 1)
        let left = try await store.garminSyncedFiles(deviceId: id)
        XCTAssertEqual(left, [file(1)])
    }

    func testRecordWithoutDataAndDeleteAllClearsTheLedger() async throws {
        let store = try await WhoopStore.inMemory()
        try await store.recordGarminSyncedFile(file(7), deviceId: id, ingestedAt: 1)
        try await store.recordGarminSyncedFile(file(7), deviceId: id, ingestedAt: 2)
        let recorded = try await store.garminSyncedFiles(deviceId: id)
        XCTAssertEqual(recorded, [file(7)])
        try await store.deleteAllData(deviceId: id)
        let cleared = try await store.garminSyncedFiles(deviceId: id)
        XCTAssertTrue(cleared.isEmpty)
    }
}
