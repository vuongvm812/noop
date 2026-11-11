import XCTest
import Foundation
import WhoopProtocol
import WhoopStore
@testable import Strand

/// Storing a Garmin export's FIT files under `garmin-import`: activity rows with their per-record track,
/// wellness files, the per-reason report, idempotent re-import and dedup beside a watch row. Kotlin twin:
/// `GarminExportFitTest`, same fixtures and expected counts. All data is synthetic.
@MainActor
final class GarminExportFitIngestTests: XCTestCase {

    private let device = "garmin-import"
    /// A FIT time (2023-11-14) and its Unix equivalent.
    private let fitT = 1_100_021_696

    private struct Fit {
        var records: [UInt8] = []
        mutating func def(_ local: Int, _ global: Int, _ fields: [(Int, Int, UInt8)]) {
            records += [UInt8(0x40 | local), 0, 0, UInt8(global & 0xFF), UInt8(global >> 8), UInt8(fields.count)]
            for f in fields { records += [UInt8(f.0), UInt8(f.1), f.2] }
        }
        mutating func data(_ local: Int) { records.append(UInt8(local)) }
        mutating func u8(_ v: Int) { records.append(UInt8(truncatingIfNeeded: v)) }
        mutating func u16(_ v: Int) { u8(v); u8(v >> 8) }
        mutating func u32(_ v: Int) { u16(v); u16(v >> 16) }
        func finish() -> Data {
            var out: [UInt8] = [12, 0x20, 0, 0]
            out += (0..<4).map { UInt8(truncatingIfNeeded: records.count >> (8 * $0)) }
            out += Array(".FIT".utf8) + records + [0, 0]
            return Data(out)
        }
    }

    /// `body` behind a `file_id` message of `type`.
    private func fit(type: Int, _ build: (inout Fit) -> Void) -> Data {
        var f = Fit()
        f.def(15, 0, [(0, 1, 0x00), (4, 4, 0x86)])
        f.data(15); f.u8(type); f.u32(fitT)
        build(&f)
        return f.finish()
    }

    /// A 30-minute run: session summary and three records with HR, power, cadence and altitude (no GPS).
    private func activity() -> Data {
        fit(type: 4) { f in
            f.def(0, 18, [(2, 4, 0x86), (5, 1, 0x00), (7, 4, 0x86), (9, 4, 0x86), (11, 2, 0x84), (16, 1, 0x02), (17, 1, 0x02)])
            f.data(0); f.u32(fitT); f.u8(1); f.u32(1_800_000); f.u32(500_000); f.u16(310); f.u8(150); f.u8(178)
            f.def(1, 20, [(253, 4, 0x86), (3, 1, 0x02), (7, 2, 0x84), (4, 1, 0x02), (2, 2, 0x84)])
            for k in 0..<3 {
                f.data(1); f.u32(fitT + k * 10); f.u8(140 + k * 5); f.u16(200 + k * 10); f.u8(80 + k); f.u16(2600 + k * 5)
            }
        }
    }

    /// A METRICS file holding one MAX_MET_DATA (229) VO2max of 52.3.
    private func vo2max() -> Data {
        fit(type: 44) { f in
            f.def(0, 229, [(0, 4, 0x86), (2, 2, 0x84), (9, 1, 0x00)])
            f.data(0); f.u32(fitT); f.u16(523); f.u8(1)
        }
    }

    /// A MONITORING file: heart-rate readings with a steps counter.
    private func monitoring() -> Data {
        fit(type: 32) { f in
            f.def(0, 103, [(253, 4, 0x86), (0, 4, 0x86)])
            f.data(0); f.u32(fitT); f.u32(fitT + 3_600)
            f.def(1, 55, [(253, 4, 0x86), (5, 1, 0x00), (3, 4, 0x86), (27, 1, 0x02)])
            for k in 0..<3 { f.data(1); f.u32(fitT + k * 60); f.u8(6); f.u32(100 * (k + 1)); f.u8(60 + k) }
        }
    }

    private func counts(_ store: WhoopStore) async throws -> [Int] {
        let all = 0...Int.max / 2
        let workouts = try await store.workouts(deviceId: device, from: all.lowerBound, to: all.upperBound, limit: 100).count
        let hr = try await store.hrSamples(deviceId: device, from: all.lowerBound, to: all.upperBound, limit: 10_000).count
        var metric = 0
        for kind in DeviceMetricKind.allCases {
            metric += try await store.deviceMetricSamples(deviceId: device, kind: kind, from: all.lowerBound, to: all.upperBound).count
        }
        let ledger = try await store.garminSyncedFiles(deviceId: device).count
        return [workouts, hr, metric, ledger]
    }

    func testAnActivityStoresItsRowHeartRateAndPerRecordTrack() async throws {
        let store = try await WhoopStore.inMemory()
        let defaults = try XCTUnwrap(UserDefaults(suiteName: "GarminExportFitIngestTests.\(UUID().uuidString)"))
        let report = await GarminExportFitIngest.ingest(["run.fit": activity()], store: store, routeDefaults: defaults, utcOffsetS: 0)
        XCTAssertEqual(report.counts, ["fit.activity": 1, "workouts": 1])
        let rows = try await store.workouts(deviceId: device, from: 0, to: Int.max / 2, limit: 10)
        XCTAssertEqual(rows.count, 1)
        let w = try XCTUnwrap(rows.first)
        XCTAssertEqual(w.startTs, fitT + 631_065_600)
        XCTAssertEqual(w.sport, "Running")
        XCTAssertEqual(w.source, device)
        XCTAssertEqual(w.avgHr, 150)
        XCTAssertEqual(w.maxHr, 178)
        let hr = try await store.hrSamples(deviceId: device, from: 0, to: Int.max / 2, limit: 100)
        XCTAssertEqual(hr.count, 3)
        for kind in [DeviceMetricKind.powerW, .cadence, .altitudeM] {
            let n = try await store.deviceMetricSamples(deviceId: device, kind: kind, from: 0, to: Int.max / 2).count
            XCTAssertEqual(n, 3, "\(kind)")
        }
    }

    /// The same ten files as the Kotlin twin's `everyFitKindIsStoredOrCountedByReason` (here the sleep, HRV and
    /// settings kinds are covered by StrandImportTests' `testEveryFitKindIsRoutedOrCountedByReason`).
    func testEveryFitKindIsStoredOrCountedByReason() async throws {
        let store = try await WhoopStore.inMemory()
        let defaults = try XCTUnwrap(UserDefaults(suiteName: "GarminExportFitIngestTests.\(UUID().uuidString)"))
        let privateFile = fit(type: 41) { f in f.def(0, 217, [(0, 1, 0x02)]); f.data(0); f.u8(1) }
        let disruption = fit(type: 79) { f in f.def(0, 500, [(0, 1, 0x02)]); f.data(0); f.u8(0xFF) }
        let noWorkout = fit(type: 4) { f in f.def(0, 18, [(2, 4, 0x86)]); f.data(0); f.u32(1) }
        let settings = fit(type: 2) { f in f.def(0, 500, [(0, 1, 0x02)]); f.data(0); f.u8(1) }
        let files: [String: Data] = [
            "run.fit": activity(), "mon.fit": monitoring(), "vo2.fit": vo2max(), "priv.fit": privateFile,
            "disruption.fit": disruption, "settings.fit": settings, "junk.fit": Data(count: 40), "noworkout.fit": noWorkout,
        ]
        let report = await GarminExportFitIngest.ingest(files, store: store, routeDefaults: defaults, utcOffsetS: 0)
        XCTAssertEqual(report.counts, [
            "fit.activity": 1, "workouts": 1, "fit.monitoring": 1, "fit.metrics": 1,
            "skipped.privateBackup": 1, "skipped.invalidData": 1, "skipped.unsupportedType": 1,
            "skipped.notFit": 1, "skipped.noUsableActivity": 1,
        ])
        let days = try await store.garminDays(deviceId: device)
        XCTAssertFalse(days.isEmpty, "the monitoring and metrics files reach day rows")
    }

    func testReImportingTheSameExportChangesNothing() async throws {
        let store = try await WhoopStore.inMemory()
        let defaults = try XCTUnwrap(UserDefaults(suiteName: "GarminExportFitIngestTests.\(UUID().uuidString)"))
        let files: [String: Data] = ["run.fit": activity(), "mon.fit": monitoring(), "vo2.fit": vo2max()]
        let first = await GarminExportFitIngest.ingest(files, store: store, routeDefaults: defaults, utcOffsetS: 0)
        let before = try await counts(store)
        let daysBefore = try await store.garminDays(deviceId: device)
        let second = await GarminExportFitIngest.ingest(files, store: store, routeDefaults: defaults, utcOffsetS: 0)
        let after = try await counts(store)
        let daysAfter = try await store.garminDays(deviceId: device)
        XCTAssertEqual(after, before)
        XCTAssertEqual(daysAfter, daysBefore)
        XCTAssertEqual(first, second)
        XCTAssertTrue(before.allSatisfy { $0 > 0 }, "\(before)")
    }

    func testAnExportActivityShowsOnceBesideTheWatchRowAndBeatsAThinnerRow() {
        let start = fitT + 631_065_600
        func row(_ source: String, offset: Int = 0, rich: Bool = true) -> WorkoutRow {
            WorkoutRow(startTs: start + offset, endTs: start + offset + 1_800, sport: "Running", source: source,
                       durationS: 1_800, energyKcal: 310, avgHr: rich ? 150 : nil, maxHr: rich ? 178 : nil,
                       strain: nil, distanceM: rich ? 5_000 : nil, zonesJSON: nil, notes: nil, steps: nil)
        }
        let exportRow = row(device)
        let watchRow = row("garmin-ABC", offset: 30)
        XCTAssertEqual(WorkoutSource.dedupCrossSource([exportRow, watchRow]).count, 1, "one session, not two")

        // Against a thin Health Connect style row (duration and calories only) the FIT row is the richer one.
        let thin = row("apple-health", rich: false)
        for order in [[thin, exportRow], [exportRow, thin]] {
            let kept = WorkoutSource.dedupCrossSource(order)
            XCTAssertEqual(kept.count, 1)
            XCTAssertEqual(kept.first?.source, device)
        }
    }

    /// A FIT file with no sport lands as the generic "Activity"; a summary of any sport must still match it. Kotlin
    /// twin: `GarminExportJsonTest.aGenericFitActivityMatchesASummaryOfAnySport`.
    func testAGenericFitActivityMatchesASummaryOfAnySport() {
        func row(_ sport: String, _ s: Int, _ e: Int) -> WorkoutRow {
            WorkoutRow(startTs: s, endTs: e, sport: sport, source: "garmin-import", durationS: nil, energyKcal: nil,
                       avgHr: nil, maxHr: nil, strain: nil, distanceM: nil, zonesJSON: nil, notes: nil, steps: nil)
        }
        XCTAssertTrue(WearableImporter.sameWorkout(row("Activity", 1000, 2000), row("Breathwork", 1010, 1990)))
        XCTAssertTrue(WearableImporter.sameWorkout(row("Running", 1000, 2000), row("Running", 1010, 2010)))
        XCTAssertFalse(WearableImporter.sameWorkout(row("Running", 1000, 2000), row("Cycling", 1010, 2010)))
        XCTAssertFalse(WearableImporter.sameWorkout(row("Activity", 1000, 2000), row("Breathwork", 3000, 4000)))
    }
}
