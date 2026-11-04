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
        var first0 = day(steps: 1_000, rhr: 59)
        first0.activityPeaks = [GarminActivityPeak(activityType: 6, steps: 1_000, totalKcal: 900)]
        ingest.days = [first0]
        ingest.sleeps = [CachedSleepSession(startTs: dayStart - 20_000, endTs: dayStart + 5_000, efficiency: 0.91,
                                            restingHr: nil, avgHrv: nil, stagesJSON: "[]")]
        let first = try await store.ingestGarminFile(ingest, deviceId: id, ingestedAt: 1)
        XCTAssertEqual(first.hr, 2)
        XCTAssertEqual(first.deviceMetric, 2)
        let ledger = try await store.garminSyncedFiles(deviceId: id)
        XCTAssertEqual(ledger, [file(1)])

        let tables = ["hrSample", "deviceMetricSample", "dailyMetric", "sleepSession", "metricSeries", "garminSyncedFile",
                      "garminActivityPeak"]
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

    /// `dailyMetric.activeKcalEst` is whole-day TOTAL energy on every source (#51): a Garmin day stores
    /// the watch's total (FIT monitoring `calories`, resting metabolic rate included), not its active
    /// calories, which stay in the `energy_kcal` series. Kotlin twin: same test name.
    func testCaloriesColumnHoldsTheWatchTotalNotActive() async throws {
        let store = try await WhoopStore.inMemory()
        var f = GarminFileIngest(file: file(1))
        var d = day()
        d.activityPeaks = [GarminActivityPeak(activityType: 6, steps: 9_000, totalKcal: 2_400, activeKcal: 500)]
        f.days = [d]
        _ = try await store.ingestGarminFile(f, deviceId: id, ingestedAt: 1)
        let metric = try await store.dailyMetrics(deviceId: id, from: "2026-11-01", to: "2026-11-01").first
        XCTAssertEqual(metric?.activeKcalEst, 2_400)
        let total = try await series(store, "total_kcal")
        let active = try await series(store, "active_kcal")
        XCTAssertEqual(total, 2_400)
        XCTAssertEqual(active, 500)
    }

    /// A later file that carries only the activity types whose counters moved must not lower the day:
    /// totals are summed over every stored type (#51). The old per-file sum merged with MAX read
    /// max(5,000 + 2,000, 6,000) = 7,000 steps; per-type peaks give 6,000 walking + 2,000 running.
    /// Kotlin twin: same test name.
    func testPerActivityTypeCountersSumAcrossFiles() async throws {
        let store = try await WhoopStore.inMemory()
        var a = GarminFileIngest(file: file(1))
        var da = day()
        da.activityPeaks = [GarminActivityPeak(activityType: 1, steps: 2_000, distanceM: 2_500.5, totalKcal: 300, activeKcal: 250),
                            GarminActivityPeak(activityType: 6, steps: 5_000, distanceM: 3_000.25, totalKcal: 1_500, activeKcal: 200)]
        a.days = [da]
        var b = GarminFileIngest(file: file(2))
        var db = day()
        db.activityPeaks = [GarminActivityPeak(activityType: 6, steps: 6_000, distanceM: 3_600.125, totalKcal: 1_600, activeKcal: 220),
                            // Cycling carries no steps; its distance and energy still count.
                            GarminActivityPeak(activityType: 2, distanceM: 10_000, totalKcal: 400, activeKcal: 380)]
        b.days = [db]
        // An OLDER re-sync of file A's walking value lowers nothing.
        var c = GarminFileIngest(file: file(3))
        var dc = day()
        dc.activityPeaks = [GarminActivityPeak(activityType: 6, steps: 4_000, totalKcal: 1_200, activeKcal: 150)]
        c.days = [dc]
        for f in [a, b, c] { _ = try await store.ingestGarminFile(f, deviceId: id, ingestedAt: 1) }

        let metric = try await store.dailyMetrics(deviceId: id, from: "2026-11-01", to: "2026-11-01").first
        XCTAssertEqual(metric?.steps, 8_000)
        XCTAssertEqual(metric?.activeKcalEst, 2_300)
        let steps = try await series(store, "steps")
        let distance = try await series(store, "distance_m")
        let total = try await series(store, "total_kcal")
        let active = try await series(store, "active_kcal")
        XCTAssertEqual(steps, 8_000)
        XCTAssertEqual(distance, 2_500.5 + 3_600.125 + 10_000)
        XCTAssertEqual(total, 2_300)
        XCTAssertEqual(active, 850)
        let rows = try await count(store, "garminActivityPeak")
        XCTAssertEqual(rows, 3)
    }

    func testSleepSessionMergeKeepsAUserEdit() async throws {
        let store = try await WhoopStore.inMemory()
        var a = GarminFileIngest(file: file(1))
        a.sleeps = [CachedSleepSession(startTs: dayStart, endTs: dayStart + 20_000, efficiency: 0.9, restingHr: nil,
                                       avgHrv: nil, stagesJSON: "[{\"start\":1,\"end\":2,\"stage\":\"deep\"}]")]
        _ = try await store.ingestGarminFile(a, deviceId: id, ingestedAt: 1)
        _ = try await store.applySleepEdit(deviceId: id, detectedStartTs: dayStart, newStartTs: dayStart, newEndTs: dayStart + 25_000)
        var b = GarminFileIngest(file: file(2))
        b.sleeps = [CachedSleepSession(startTs: dayStart, endTs: dayStart + 21_000, efficiency: nil, restingHr: nil,
                                       avgHrv: 40, stagesJSON: nil)]
        _ = try await store.ingestGarminFile(b, deviceId: id, ingestedAt: 1)
        let night = try await store.sleepSessions(deviceId: id, from: dayStart - 1, to: dayStart + 1, limit: 4).first
        XCTAssertEqual(night?.endTs, dayStart + 25_000, "the user's correction wins")
        XCTAssertEqual(night?.efficiency, 0.9, "a missing value keeps the stored one")
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

    private func sleepDay(_ total: Double, deep: Double, efficiency: Double) -> GarminDayRollup {
        var d = day(sleep: total)
        d.deepMin = deep; d.lightMin = total - deep; d.remMin = 0; d.awakeMin = 10; d.efficiency = efficiency
        return d
    }

    /// #49: the day's sleep figures are its main night's. A nap file synced after the night file leaves
    /// them alone; the same night again (a re-fold) replaces them, so a corrected column lands.
    func testALaterNapFileKeepsTheNightsDayTotals() async throws {
        let store = try await WhoopStore.inMemory()
        var night = GarminFileIngest(file: file(1))
        night.days = [sleepDay(420, deep: 90, efficiency: 0.95)]
        var nap = GarminFileIngest(file: file(2))
        nap.days = [sleepDay(40, deep: 0, efficiency: 0.8)]
        _ = try await store.ingestGarminFile(night, deviceId: id, ingestedAt: 1)
        _ = try await store.ingestGarminFile(nap, deviceId: id, ingestedAt: 2)
        var m = try await store.dailyMetrics(deviceId: id, from: "2026-11-01", to: "2026-11-01").first
        XCTAssertEqual(m?.totalSleepMin, 420)
        XCTAssertEqual(m?.deepMin, 90)
        XCTAssertEqual(m?.efficiency, 0.95)
        let total = try await series(store, "sleep_total_min")
        let awake = try await series(store, "sleep_awake_min")
        XCTAssertEqual(total, 420)
        XCTAssertEqual(awake, 10)

        var refold = GarminFileIngest(file: file(3))
        refold.days = [sleepDay(420, deep: 90, efficiency: 0.951)]
        _ = try await store.ingestGarminFile(refold, deviceId: id, ingestedAt: 3)
        m = try await store.dailyMetrics(deviceId: id, from: "2026-11-01", to: "2026-11-01").first
        XCTAssertEqual(m?.efficiency, 0.951, "the same night again replaces the day's figures")
    }

    /// #49: an older fold stored efficiency as a percent. The repair divides this watch's values above 1
    /// by 100, leaves fractions and other devices alone, and a second run changes nothing.
    func testRepairRescalesPercentEfficienciesOnce() async throws {
        let store = try await WhoopStore.inMemory()
        var old = GarminFileIngest(file: file(1))
        old.days = [sleepDay(420, deep: 90, efficiency: 93.3)]
        old.sleeps = [CachedSleepSession(startTs: dayStart - 30_000, endTs: dayStart, efficiency: 93.3,
                                         restingHr: nil, avgHrv: nil, stagesJSON: nil),
                      CachedSleepSession(startTs: dayStart + 40_000, endTs: dayStart + 42_000, efficiency: 0.8,
                                         restingHr: nil, avgHrv: nil, stagesJSON: nil)]
        _ = try await store.ingestGarminFile(old, deviceId: id, ingestedAt: 1)
        var other = GarminFileIngest(file: file(1))
        other.sleeps = [CachedSleepSession(startTs: dayStart - 30_000, endTs: dayStart, efficiency: 88,
                                           restingHr: nil, avgHrv: nil, stagesJSON: nil)]
        _ = try await store.ingestGarminFile(other, deviceId: "garmin-other", ingestedAt: 1)

        let changed = try await store.repairGarminEfficiencyUnits(deviceId: id)
        XCTAssertEqual(changed, 2)
        let again = try await store.repairGarminEfficiencyUnits(deviceId: id)
        XCTAssertEqual(again, 0)
        let nights = try await store.sleepSessions(deviceId: id, from: dayStart - 40_000, to: dayStart + 50_000, limit: 4)
        XCTAssertEqual(nights.map { $0.efficiency! }.sorted().map { ($0 * 1000).rounded() / 1000 }, [0.8, 0.933])
        let m = try await store.dailyMetrics(deviceId: id, from: "2026-11-01", to: "2026-11-01").first
        XCTAssertEqual(m?.efficiency ?? 0, 0.933, accuracy: 1e-12)
        let untouched = try await store.sleepSessions(deviceId: "garmin-other", from: dayStart - 40_000, to: dayStart, limit: 4)
        XCTAssertEqual(untouched.first?.efficiency, 88)
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

    // MARK: Overnight vitals (#52)

    private func prevDay() -> GarminDayRollup {
        GarminDayRollup(day: "2026-10-31", dayStartTs: dayStart - 86_400, dayEndTs: dayStart)
    }

    private func m(_ off: Int, _ kind: DeviceMetricKind, _ v: Double) -> DeviceMetricSample {
        DeviceMetricSample(ts: dayStart + off, kind: kind, value: v, source: "test")
    }

    /// One night 22:00 -> 07:00 waking on 2026-11-01, with daytime, exercise, spot-check and out-of-range
    /// readings around it. Shared with the Kotlin twin (`overnightVitalsScenario`), same values.
    private var scenarioSamples: [DeviceMetricSample] {
        [m(-21_600, .respirationBpm, 30),     // the evening before: exercise, outside the night
         m(-3_600, .respirationBpm, 14.25), m(3_600, .respirationBpm, 15.5), m(20_000, .respirationBpm, 16),
         m(10_000, .respirationBpm, 2),       // in the night but below the plausible range
         m(43_200, .respirationBpm, 22),      // midday
         m(-1_800, .spo2Pct, 95), m(7_200, .spo2Pct, 93), m(25_200, .spo2Pct, 97),   // 07:00 = night end, inclusive
         m(12_000, .spo2Pct, 50),             // implausible
         m(50_000, .spo2Pct, 88)]             // a daytime spot check
    }

    private var scenarioNight: CachedSleepSession {
        CachedSleepSession(startTs: dayStart - 7_200, endTs: dayStart + 25_200, efficiency: 90,
                           restingHr: nil, avgHrv: nil, stagesJSON: nil)
    }

    private func vitals(_ store: WhoopStore) async throws -> String {
        let row = try await store.dailyMetrics(deviceId: id, from: "2026-11-01", to: "2026-11-01").first
        let spo2 = try await series(store, "spo2")
        let resp = try await series(store, "resp_rate")
        func f(_ v: Double?) -> String { v.map { String(format: "%.4f", $0) } ?? "nil" }
        return "row spo2=\(f(row?.spo2Pct)) resp=\(f(row?.respRateBpm)) series spo2=\(f(spo2)) resp=\(f(resp))"
    }

    /// Pasted verbatim into the Kotlin twin.
    static let overnightExpected = "row spo2=95.0000 resp=15.2500 series spo2=95.0000 resp=15.2500"

    func testOvernightVitalsComeFromTheNightEndingThatDay() async throws {
        let store = try await WhoopStore.inMemory()
        // Monitoring first (no night stored yet), then the sleep file.
        var a = GarminFileIngest(file: file(1))
        a.deviceMetric = scenarioSamples
        a.days = [prevDay(), day()]
        _ = try await store.ingestGarminFile(a, deviceId: id, ingestedAt: 1)
        let noNight = try await vitals(store)
        XCTAssertEqual(noNight, "row spo2=nil resp=nil series spo2=nil resp=nil", "no night, no overnight value")

        var b = GarminFileIngest(file: file(2))
        b.sleeps = [scenarioNight]
        b.days = [day(sleep: 480)]
        _ = try await store.ingestGarminFile(b, deviceId: id, ingestedAt: 1)
        let got = try await vitals(store)
        XCTAssertEqual(got, Self.overnightExpected)
        let prev = try await store.dailyMetrics(deviceId: id, from: "2026-10-31", to: "2026-10-31").first
        XCTAssertNil(prev?.respRateBpm, "the evening's half-night belongs to the wake day")
    }

    func testAnEveningFileRefreshesTheWakeDay() async throws {
        let store = try await WhoopStore.inMemory()
        var a = GarminFileIngest(file: file(1))
        a.sleeps = [scenarioNight]
        a.days = [day(sleep: 480)]
        a.deviceMetric = scenarioSamples.filter { $0.ts >= dayStart }
        _ = try await store.ingestGarminFile(a, deviceId: id, ingestedAt: 1)
        // The pre-midnight samples arrive in a file that only touches the evening's day.
        var b = GarminFileIngest(file: file(2))
        b.deviceMetric = scenarioSamples.filter { $0.ts < dayStart }
        b.days = [prevDay()]
        b.nightRefreshDays = [day()]
        _ = try await store.ingestGarminFile(b, deviceId: id, ingestedAt: 1)
        let got = try await vitals(store)
        XCTAssertEqual(got, Self.overnightExpected)
    }

    func testRederiveCorrectsACalendarDayMean() async throws {
        let store = try await WhoopStore.inMemory()
        var a = GarminFileIngest(file: file(1))
        a.sleeps = [scenarioNight]
        a.deviceMetric = scenarioSamples
        a.days = [day(sleep: 480)]
        _ = try await store.ingestGarminFile(a, deviceId: id, ingestedAt: 1)
        // What an older ingest stored: the midnight-to-midnight mean of every sample.
        try await store.dbWriter.write { db in
            try db.execute(sql: "UPDATE dailyMetric SET spo2Pct = 84.6, respRateBpm = 13.95 WHERE deviceId = ?", arguments: [self.id])
            try db.execute(sql: "UPDATE metricSeries SET value = 13.95 WHERE deviceId = ? AND key = 'resp_rate'", arguments: [self.id])
        }
        _ = try await store.rederiveGarminNightVitals(deviceId: id, days: [day(), prevDay()])
        let got = try await vitals(store)
        XCTAssertEqual(got, Self.overnightExpected)
        let days = try await store.garminDays(deviceId: id)
        XCTAssertEqual(days, ["2026-11-01"], "a day without a row is not created")
    }

    func testTooFewNightSamplesClearTheValue() async throws {
        let store = try await WhoopStore.inMemory()
        var a = GarminFileIngest(file: file(1))
        a.sleeps = [scenarioNight]
        a.deviceMetric = [m(3_600, .respirationBpm, 15), m(7_200, .respirationBpm, 16)]
        a.days = [day(sleep: 480)]
        _ = try await store.ingestGarminFile(a, deviceId: id, ingestedAt: 1)
        let got = try await vitals(store)
        XCTAssertEqual(got, "row spo2=nil resp=nil series spo2=nil resp=nil")
    }
}
