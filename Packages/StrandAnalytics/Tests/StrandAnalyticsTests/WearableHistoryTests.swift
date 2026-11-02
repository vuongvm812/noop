import XCTest
@testable import StrandAnalytics

/// Apple half of the shared wearable-history oracle (`Tools/parity_cases/wearable_history.json`, #31).
/// The Kotlin `WearableHistoryParityTest` reads the same file, so the id resolver and the baseline seed
/// rule cannot drift between platforms. Expected values were produced by this Swift implementation.
final class WearableHistoryTests: XCTestCase {
    private func fixture() throws -> [String: Any] {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Tools/parity_cases/wearable_history.json")
        XCTAssertTrue(FileManager.default.fileExists(atPath: url.path), "wearable_history.json missing")
        return try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
    }

    private func nights(_ raw: Any?) -> [(day: String, value: Double?)] {
        ((raw as? [[Any]]) ?? []).map { (day: $0[0] as! String, value: $0[1] as? Double) }
    }

    func testIdResolverMatchesSharedOracle() throws {
        for c in try fixture()["idCases"] as! [[String: Any]] {
            let active = c["activeDeviceId"] as! String
            XCTAssertEqual(WearableHistory.readOrder(activeDeviceId: active), c["readOrder"] as! [String], active)
            XCTAssertEqual(WearableHistory.baselineSeedIds(activeDeviceId: active),
                           c["baselineSeedIds"] as! [String], active)
        }
    }

    func testSeedRuleMatchesSharedOracle() throws {
        for c in try fixture()["seedCases"] as! [[String: Any]] {
            let h = ChargeBaselines.historyWithSeed(imported: nights(c["imported"]), own: nights(c["own"]),
                                            seed: nights(c["seed"]), anchorDay: c["anchorDay"] as! String,
                                            cfg: Baselines.hrvCfg, baselineEpoch: 0)
            let e = c["expected"] as! [String: Any]
            let name = c["name"] as! String
            XCTAssertEqual(h.dayKeys, e["dayKeys"] as! [String], name)
            XCTAssertEqual(h.values.map { $0.map { String($0) } ?? "nil" }, e["values"] as! [String], name)
            XCTAssertEqual(h.seededByImport, e["seededByImport"] as! Bool, name)
            XCTAssertEqual(h.importedNights, e["importedNights"] as! Int, name)
            XCTAssertEqual(h.ownValidNights, e["ownValidNights"] as! Int, name)
        }
    }

    /// A WHOOP wearer's exports are history only: they never seed a WHOOP baseline (#459).
    func testWhoopWearerGetsNoSeedSources() {
        XCTAssertEqual(WearableHistory.baselineSeedIds(activeDeviceId: "my-whoop"), [])
        XCTAssertEqual(WearableHistory.readOrder(activeDeviceId: "my-whoop"), WearableHistory.sourceIds)
        XCTAssertEqual(WearableHistory.baselineSeedIds(activeDeviceId: "garmin-1"), ["garmin-import"])
    }
}
