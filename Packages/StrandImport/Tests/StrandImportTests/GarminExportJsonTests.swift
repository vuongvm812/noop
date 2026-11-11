import XCTest
import ZIPFoundation
@testable import StrandImport

/// The synthetic Garmin export under Tools/parity-fixtures/garmin-export-json, parsed by the JSON-field
/// importer, must equal the shared golden. The Kotlin twin (GarminExportJsonTest) reads the SAME files and
/// the SAME golden, so the two platforms cannot drift. The fixture holds synthetic numbers only.
final class GarminExportJsonTests: XCTestCase {

    private static var fixtureRoot: URL {
        // Packages/StrandImport/Tests/StrandImportTests/<this file> → repo root is five levels up.
        URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Tools/parity-fixtures/garmin-export-json")
    }

    /// Lowercased relative path → bytes for every .json under the fixture (as the importer's zip walk keys them).
    static func loadFixture() throws -> (files: [String: Data], skipped: [String]) {
        var files: [String: Data] = [:]
        let root = fixtureRoot
        let e = FileManager.default.enumerator(at: root, includingPropertiesForKeys: nil)!
        for case let u as URL in e where u.pathExtension == "json" {
            files[String(u.path.dropFirst(root.path.count + 1)).lowercased()] = try Data(contentsOf: u)
        }
        let skipped = try String(contentsOf: root.appendingPathComponent("skipped.txt"), encoding: .utf8)
            .split(separator: "\n").map(String.init)
        return (files, skipped)
    }

    /// The result as canonical text, one record per line: the shared-golden format (Kotlin twin: GarminExportJsonTest.dump).
    static func dump(_ r: GarminExportJson.Result) -> String {
        var out: [String] = []
        for p in r.points { out.append("P|\(p.day)|\(p.key)|\(GarminExportJson.num(p.value))") }
        let o = { (_ v: Double?) -> String in v.map(GarminExportJson.num) ?? "-" }
        let i = { (_ v: Int?) -> String in v.map(String.init) ?? "-" }
        for a in r.activities {
            let fields: [String] = [
                "A", String(a.activityId), String(a.startTs), a.sport, GarminExportJson.num(a.durationS), o(a.distanceM), o(a.energyKcal),
                i(a.avgHr), i(a.maxHr), i(a.steps),
                a.zonePercents.map { $0.map(GarminExportJson.num).joined(separator: ",") } ?? "-",
                a.powerZonePercents.map { $0.map(GarminExportJson.num).joined(separator: ",") } ?? "-",
                o(a.avgPowerW), o(a.maxPowerW), o(a.normPowerW), o(a.cadenceSpm), o(a.elevationGainM), o(a.elevationLossM),
                o(a.minElevationM), o(a.maxElevationM), o(a.aerobicTe), o(a.anaerobicTe), i(a.rpe), i(a.feel),
                i(a.bodyBatteryChange), o(a.vo2max), i(a.strokes), o(a.swolf), o(a.strideM), o(a.groundContactMs),
                o(a.verticalOscillationCm), o(a.sweatLossMl),
            ]
            out.append(fields.joined(separator: "|"))
        }
        out.append("S|\(i(r.settings.maxHr))|\(i(r.settings.restingHr))|\(i(r.settings.ftpW))|\(r.settings.zoneFloors.map(String.init).joined(separator: ","))")
        for c in r.imported { out.append("I|\(c.id)|\(c.count)") }
        for c in r.skipped { out.append("K|\(c.id)|\(c.count)") }
        return out.joined(separator: "\n")
    }


    func testSyntheticExportMatchesSharedGolden() throws {
        let (files, skipped) = try Self.loadFixture()
        let dump = Self.dump(GarminExportJson.parse(files, skippedPaths: skipped))
        let golden = try String(contentsOf: Self.fixtureRoot.appendingPathComponent("golden.txt"), encoding: .utf8)
            .trimmingCharacters(in: .newlines)
        if ProcessInfo.processInfo.environment["WRITE_GOLDEN"] == "1" {
            try (dump + "\n").write(to: Self.fixtureRoot.appendingPathComponent("golden.txt"), atomically: true, encoding: .utf8)
            return
        }
        XCTAssertEqual(dump, golden)
    }

    func testHostileNumbersAreDroppedNotStored() {
        let bad = Data(#"[{"calendarDate":"2026-01-01","bmrKilocalories":1e308,"maxHeartRate":9999,"respiration":{"avgWakingRespirationValue":-3}}]"#.utf8)
        let r = GarminExportJson.parse(["di_connect/di-connect-aggregator/udsfile_x.json": bad])
        XCTAssertFalse(r.points.contains { $0.key == "max_hr" || $0.key == "resp_rate" })
    }
}

extension GarminExportJsonTests {
    func testEnrichmentIsIdempotentAndZonesJsonMatchesTheFlatShape() {
        XCTAssertEqual(GarminExportJson.zonesJson([3.16, 31.58, 47.37, 15.79, 2.11]),
                       #"{"z1":3.16,"z2":31.58,"z3":47.37,"z4":15.79,"z5":2.11}"#)
        XCTAssertNil(GarminExportJson.zonesJson(nil))
        let once = GarminExportJson.enrichedNotes("Imported RUNNING", "RPE 5 · feel 75/100")
        XCTAssertEqual(once, "Imported RUNNING · RPE 5 · feel 75/100")
        XCTAssertEqual(GarminExportJson.enrichedNotes(once, "RPE 5 · feel 75/100"), once)
        XCTAssertEqual(GarminExportJson.slug("Most Steps in a Day"), "most_steps_in_a_day")
    }

    func testZipWalkKeepsHealthFilesByFullPathAndReportsTheRest() throws {
        let zipURL = FileManager.default.temporaryDirectory.appendingPathComponent("gej-\(UUID().uuidString).zip")
        defer { try? FileManager.default.removeItem(at: zipURL) }
        let archive = try Archive(url: zipURL, accessMode: .create)
        for (name, body) in [
            ("DI_CONNECT/DI-Connect-Wellness/1_fitnessAgeData.json", "[]"),
            ("DI_CONNECT/DI-Connect-User/user_profile.json", "{\"a\":1}"),
            ("IT_CONSENT_HISTORY/consentHistory.json", "{\"a\":1}"),
            ("DI_CONNECT/DI-Connect-Fitness/u_PrimaryTrainingBackup_Part1.zip", "PK"),
        ] {
            let data = Data(body.utf8)
            try archive.addEntry(with: name, type: .file, uncompressedSize: Int64(data.count)) { pos, size in
                data.subdata(in: Int(pos)..<Int(pos) + size)
            }
        }
        let collected = try WearableExportImporter().collectFiles(from: zipURL)
        let kept = collected.files
        XCTAssertEqual(Set(kept.keys), ["di_connect/di-connect-wellness/1_fitnessagedata.json"])
        XCTAssertEqual(Set(collected.skipped), ["di_connect/di-connect-user/user_profile.json", "it_consent_history/consenthistory.json",
                                         "di_connect/di-connect-fitness/u_primarytrainingbackup_part1.zip"])
        XCTAssertEqual(GarminExportJson.skipReason("di_connect/di-connect-fitness/u_primarytrainingbackup_part1.zip"), GarminExportJson.Skip.privateFile)
    }

    func testRepeatedEntriesBadDatesAndHostileFieldsAreHandled() {
        let hyd = #"[{"calendarDate":"2026-01-01","hydrationSource":"MANUAL","valueInML":500.0,"uuid":{"uuid":"a"}}]"#
        let sleep = #"[{"calendarDate":"2026-01-01","sleepStartTimestampGMT":"2026-01-01T01:00:00.0","remSleepSeconds":3600}]"#
        let act = #"[{"summarizedActivitiesExport":[{"activityId":7,"activityType":"running","startTimeGmt":1.7e12,"duration":600000,"elevationGain":1e30,"hrTimeInZone_1":1}]}]"#
        let bad = #"[{"calendarDate":20260101,"bmrKilocalories":5},{"calendarDate":"2026-13-45","bmrKilocalories":5}]"#
        let health = #"[{"calendarDate":"2026-01-01","metrics":[{"type":"SKIN_TEMP_C","value":-0.4}]}]"#
        let hz = #"[{"sport":"DEFAULT","maxHeartRateUsed":190,"zone1Floor":1e30,"zone2Floor":1,"zone3Floor":1,"zone4Floor":1,"zone5Floor":1}]"#
        let files: [String: String] = [
            "a/hydrationlogfile_1.json": hyd, "b/hydrationlogfile_2.json": hyd,
            "a/1_sleepdata.json": sleep, "b/2_sleepdata.json": sleep,
            "a/x_summarizedactivities.json": act, "b/y_summarizedactivities.json": act,
            "a/udsfile_bad.json": bad, "a/h_healthstatusdata.json": health, "a/1_heartratezones.json": hz,
        ]
        let r = GarminExportJson.parse(files.mapValues { Data($0.utf8) })
        XCTAssertEqual(r.points.first { $0.key == "hydration_ml" }?.value, 500)
        XCTAssertEqual(r.activities.count, 1)
        XCTAssertNil(r.activities[0].elevationGainM)
        XCTAssertEqual(r.imported.first { $0.id == GarminExportJson.Category.sleep }?.count, 1)
        XCTAssertFalse(r.points.contains { $0.key == "nap_min" || $0.day == "2026-13-45" || $0.day == "20260101" })
        XCTAssertEqual(r.points.first { $0.key == "skin_temp_dev_c" }?.value, -0.4)
        XCTAssertTrue(r.settings.zoneFloors.isEmpty)
    }
}
