import XCTest
@testable import WhoopStore

/// Apple half of the shared sleep gap-fill oracle (`Tools/parity_cases/wearable_history.json`,
/// `sleepGapCases`, #31). The Kotlin `WearableHistoryParityTest` reads the same cases, so the rule that a
/// wearable-export night only fills an empty wake-day cannot drift between platforms. The keyer is the
/// UTC day number, so the cases do not depend on the test machine's zone.
final class SleepGapFillTests: XCTestCase {
    private func cases() throws -> [[String: Any]] {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Tools/parity_cases/wearable_history.json")
        let root = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
        return try XCTUnwrap(root["sleepGapCases"] as? [[String: Any]])
    }

    static func sessions(_ raw: Any?) -> [CachedSleepSession] {
        ((raw as? [[String: Any]]) ?? []).map {
            CachedSleepSession(startTs: $0["startTs"] as! Int, endTs: $0["endTs"] as! Int, efficiency: nil,
                               restingHr: nil, avgHrv: nil, stagesJSON: nil,
                               deviceId: $0["deviceId"] as? String)
        }
    }

    static func encode(_ s: [CachedSleepSession]) -> [String] {
        s.map { "\($0.deviceId ?? "nil")@\($0.startTs)-\($0.endTs)" }
    }

    func testFillingGapsMatchesSharedOracle() throws {
        for c in try cases() {
            let out = SleepMerge.fillingGaps(Self.sessions(c["base"]), history: Self.sessions(c["history"]),
                                             endDay: { String($0.endTs / 86_400) })
            XCTAssertEqual(Self.encode(out), c["expected"] as? [String], c["name"] as? String ?? "")
        }
    }

    func testNoHistoryReturnsBaseUnchanged() {
        let base = Self.sessions([["deviceId": "my-whoop", "startTs": 10, "endTs": 20]])
        XCTAssertEqual(SleepMerge.fillingGaps(base, history: [], endDay: { String($0.endTs) }), base)
    }
}
