import XCTest
import ZIPFoundation
import WhoopStore
@testable import StrandImport

/// The FIT files inside a Garmin Connect export: nested-zip collection, routing by `file_id`, and the
/// per-reason report. Kotlin twin: `GarminExportFitTest` (same fixtures, same expected counts). Storage is
/// covered by `GarminExportFitIngestTests` (StrandTests). All data is synthetic.
final class GarminExportFitTests: XCTestCase {
    private var tempDirs: [URL] = []

    override func tearDownWithError() throws {
        for d in tempDirs { try? FileManager.default.removeItem(at: d) }
        tempDirs.removeAll()
    }

    private func tempDir() -> URL {
        let d = FileManager.default.temporaryDirectory.appendingPathComponent("garmin-export-fit-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        tempDirs.append(d)
        return d
    }

    /// A zip of ordered `(path, bytes)` pairs, returned as its bytes.
    private func zipData(_ entries: [(String, Data)]) throws -> Data {
        let url = tempDir().appendingPathComponent("a.zip")
        let archive = try Archive(url: url, accessMode: .create)
        for (path, data) in entries {
            try archive.addEntry(with: path, type: .file, uncompressedSize: Int64(data.count)) { position, size in
                let start = data.startIndex + Int(position)
                return data.subdata(in: start ..< start + size)
            }
        }
        return try Data(contentsOf: url)
    }

    private func zipFile(_ entries: [(String, Data)]) throws -> URL {
        let url = tempDir().appendingPathComponent("export.zip")
        try zipData(entries).write(to: url)
        return url
    }

    /// `fit`'s records behind a `file_id` message of `type`.
    static func withFileID(_ type: Int, _ fit: Data) -> Data {
        let body = [UInt8](fit)[12 ..< fit.count - 2]
        var f = WellnessFit()
        f.def(15, 0, [(0, 1, WellnessFixtures.en), (4, 4, WellnessFixtures.u32)])
        f.data(15); f.u8(type); f.u32(WellnessFixtures.t)
        let head = [UInt8](f.finish())
        let records = Array(head[12 ..< head.count - 2]) + body
        var out: [UInt8] = [12, 0x20, 0, 0]
        out += (0..<4).map { UInt8(truncatingIfNeeded: records.count >> (8 * $0)) }
        out += Array(".FIT".utf8)
        out += records
        out += [0, 0]
        return Data(out)
    }

    /// A 30-minute run: session summary and three records with HR, power, cadence and altitude (no GPS).
    static func activity(startFit: Int64 = WellnessFixtures.t) -> Data {
        var f = WellnessFit()
        let u8 = WellnessFixtures.u8, u16 = WellnessFixtures.u16, u32 = WellnessFixtures.u32
        f.def(0, 18, [(2, 4, u32), (5, 1, WellnessFixtures.en), (7, 4, u32), (9, 4, u32), (11, 2, u16), (16, 1, u8), (17, 1, u8)])
        f.data(0); f.u32(startFit); f.u8(1); f.u32(1_800_000); f.u32(500_000); f.u16(310); f.u8(150); f.u8(178)
        f.def(1, 20, [(253, 4, u32), (3, 1, u8), (7, 2, u16), (4, 1, u8), (2, 2, u16)])
        for k in 0..<3 {
            f.data(1); f.u32(startFit + Int64(k) * 10); f.u8(140 + k * 5); f.u16(200 + k * 10); f.u8(80 + k); f.u16(2600 + k * 5)
        }
        return withFileID(4, f.finish())
    }

    private let json = Data(#"{"calendarDate":"2026-06-08"}"#.utf8)

    // MARK: - Collection

    func testNestedZipsAreExpandedAndFitEntriesCollected() throws {
        let inner = try zipData([("a/run.fit", Self.activity()),
                                 ("b/sleep.fit", Self.withFileID(49, WellnessFixtures.sleepWindow()))])
        let outer = try zipFile([("DI-Connect/Uploaded/UploadedFiles_1.zip", inner),
                                 ("DI-Connect/DI-Connect-Wellness/2026_sleepData.json", json)])
        let c = try WearableExportImporter().collectFiles(from: outer)
        XCTAssertEqual(c.fit.keys.sorted(), ["di-connect/uploaded/uploadedfiles_1.zip/a/run.fit",
                                             "di-connect/uploaded/uploadedfiles_1.zip/b/sleep.fit"])
        XCTAssertEqual(Array(c.files.keys), ["di-connect/di-connect-wellness/2026_sleepdata.json"])
    }

    func testNestingDeeperThanTheDepthCapIsNotExpanded() throws {
        let innermost = try zipData([("deep.fit", Self.activity())])
        let middle = try zipData([("mid.zip", innermost), ("mid.fit", Self.activity())])
        let outer = try zipFile([("top.zip", middle)])
        XCTAssertEqual(try WearableExportImporter(maxZipDepth: 2).collectFiles(from: outer).fit.keys.sorted(), ["top.zip/mid.fit"])
        XCTAssertEqual(try WearableExportImporter(maxZipDepth: 3).collectFiles(from: outer).fit.keys.sorted(),
                       ["top.zip/mid.fit", "top.zip/mid.zip/deep.fit"])
        // The default is two levels: the export zip and the zip inside it.
        XCTAssertEqual(WearableExportImporter.maxZipDepth, 2)
    }

    func testTheByteBudgetCoversNestedArchivesAndFitFiles() throws {
        var a = Self.activity(), b = Self.activity()
        a[a.count - 3] = 1; b[b.count - 3] = 2
        let inner = try zipData([("1.fit", Self.activity()), ("2.fit", a), ("3.fit", b)])
        let outer = try zipFile([("in.zip", inner)])
        // Room for the nested archive and two FIT files, not the third.
        let fitSize = Self.activity().count
        let c = try WearableExportImporter(maxTotalBytes: inner.count + 2 * fitSize + 10).collectFiles(from: outer)
        XCTAssertEqual(c.fit.count, 2)
        // A budget smaller than the nested archive itself retains nothing from it.
        XCTAssertTrue(try WearableExportImporter(maxTotalBytes: inner.count - 1).collectFiles(from: outer).fit.isEmpty)
    }

    func testAnUnreadableNestedArchiveIsSkippedNotFatal() throws {
        let outer = try zipFile([("bad.zip", Data(repeating: 7, count: 64)), ("ok.fit", Self.activity())])
        XCTAssertEqual(try WearableExportImporter().collectFiles(from: outer).fit.keys.sorted(), ["ok.fit"])
    }

    func testAFitOnlyExportIsAGarminImport() throws {
        let inner = try zipData([("run.fit", Self.activity())])
        let outer = try zipFile([("UploadedFiles_1.zip", inner)])
        let r = try WearableExportImporter().import(from: outer)
        XCTAssertEqual(r.brand, .garmin)
        XCTAssertEqual(r.fitFiles.count, 1)
        XCTAssertTrue(r.days.isEmpty)
    }

    func testAFolderWithAnUploadedFilesZipCollectsItsFit() throws {
        let dir = tempDir()
        try FileManager.default.createDirectory(at: dir.appendingPathComponent("DI-Connect-Uploaded-Files"), withIntermediateDirectories: true)
        try zipData([("run.fit", Self.activity())]).write(to: dir.appendingPathComponent("DI-Connect-Uploaded-Files/UploadedFiles_1.zip"))
        let c = try WearableExportImporter().collectFiles(from: dir)
        // (The folder prefix is dropped when the enumerator reports a symlink-resolved temp path.)
        XCTAssertEqual(c.fit.count, 1)
        XCTAssertTrue(c.fit.keys.first!.hasSuffix("uploadedfiles_1.zip/run.fit"))
    }

    // MARK: - Routing

    func testFileIDRoutesByContent() {
        XCTAssertEqual(GarminExportFit.fileID(Self.activity())?.type, 4)
        XCTAssertEqual(GarminExportFit.fileID(Self.withFileID(49, WellnessFixtures.sleepWindow()))?.type, 49)
        XCTAssertEqual(GarminExportFit.fileID(Self.activity())?.timeCreatedUnix, Int(WellnessFixtures.t) + 631_065_600)
        XCTAssertNil(GarminExportFit.fileID(WellnessFixtures.monitoring()), "no file_id first")
        XCTAssertNil(GarminExportFit.fileID(Data(count: 40)))
    }

    /// The same ten files as `GarminExportFitTest.everyFitKindIsStoredOrCountedByReason`, decoded to the
    /// same report. Activities are decoded by the app layer, so a decodable activity file counts here as
    /// the one `.activity` outcome and the no-workout file is checked against the parser.
    func testEveryFitKindIsRoutedOrCountedByReason() {
        var privateFit = WellnessFit(); privateFit.def(0, 217, [(0, 1, WellnessFixtures.u8)]); privateFit.data(0); privateFit.u8(1)
        var disruption = WellnessFit(); disruption.def(0, 500, [(0, 1, WellnessFixtures.u8)]); disruption.data(0); disruption.u8(0xFF)
        var noWorkout = WellnessFit(); noWorkout.def(0, 18, [(2, 4, WellnessFixtures.u32)]); noWorkout.data(0); noWorkout.u32(1)
        let files: [(String, Data)] = [
            ("run.fit", Self.activity()),
            ("mon.fit", Self.withFileID(32, WellnessFixtures.monitoring())),
            ("sleep.fit", Self.withFileID(49, WellnessFixtures.sleepWindow())),
            ("hrv.fit", Self.withFileID(68, WellnessFixtures.hrv())),
            ("vo2.fit", Self.withFileID(44, WellnessFixtures.vo2max())),
            ("priv.fit", Self.withFileID(41, privateFit.finish())),
            ("disruption.fit", Self.withFileID(79, disruption.finish())),
            ("settings.fit", Self.withFileID(2, WellnessFixtures.monitoring())),
            ("junk.fit", Data(count: 40)),
            ("noworkout.fit", Self.withFileID(4, noWorkout.finish())),
        ]
        var report = GarminExportFit.Report()
        for (_, data) in files {
            switch GarminExportFit.decode(data, utcOffsetS: 0) {
            case .activity:
                if let a = ActivityFileImporter.parse(data: data, filename: "x.fit").activity, (a.durationS ?? 0) > 0 {
                    report.counts[GarminExportFit.keyActivity, default: 0] += 1; report.counts[GarminExportFit.keyWorkouts, default: 0] += 1
                } else {
                    report.counts[GarminExportFit.skipNoUsableActivity, default: 0] += 1
                }
            case .wellness(let key, _): report.counts[key, default: 0] += 1
            case .skipped(let reason): report.counts[reason, default: 0] += 1
            }
        }
        XCTAssertEqual(report.counts, [
            "fit.activity": 1, "workouts": 1, "fit.monitoring": 1, "fit.sleep": 1, "fit.hrvStatus": 1, "fit.metrics": 1,
            "skipped.privateBackup": 1, "skipped.invalidData": 1, "skipped.unsupportedType": 1,
            "skipped.notFit": 1, "skipped.noUsableActivity": 1,
        ])
        XCTAssertEqual(report.workouts, 1)
        XCTAssertEqual(report.storedFiles, 5)
        XCTAssertEqual(report.skippedFiles, 5)
    }

    /// The synthetic ledger row is keyed on type, creation time and size, so a re-import writes the same row.
    func testWellnessBatchLedgerRowIsContentKeyed() throws {
        let data = Self.withFileID(44, WellnessFixtures.vo2max())
        guard case .wellness(_, let a) = GarminExportFit.decode(data, utcOffsetS: 0),
              case .wellness(_, let b) = GarminExportFit.decode(data, utcOffsetS: 0) else { return XCTFail("not wellness") }
        XCTAssertEqual(a.file, b.file)
        XCTAssertEqual(a.file.subType, 44)
        XCTAssertEqual(a.file.ts, Int(WellnessFixtures.t))
        XCTAssertEqual(a.file.size, data.count)
    }
}
