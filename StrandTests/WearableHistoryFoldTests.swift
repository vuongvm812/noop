import Foundation
import XCTest
@testable import Strand
import WhoopStore
import StrandAnalytics

/// Apple half of the shared daily-fold oracle (`Tools/parity_cases/wearable_history.json`, `foldCases`,
/// #31): wearable-export rows fold UNDER the merged daily list, column group by column group. The Kotlin
/// `WearableHistoryParityTest` reads the same cases.
final class WearableHistoryFoldTests: XCTestCase {
    static func fixture() throws -> [String: Any] {
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Tools/parity_cases/wearable_history.json")
        return try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
    }

    static func rows(_ raw: Any?) -> [DailyMetric] {
        ((raw as? [[String: Any]]) ?? []).map {
            DailyMetric(day: $0["day"] as! String, totalSleepMin: $0["totalSleepMin"] as? Double, efficiency: nil,
                        deepMin: $0["deepMin"] as? Double, remMin: nil, lightMin: nil, disturbances: nil,
                        restingHr: $0["restingHr"] as? Int, avgHrv: $0["avgHrv"] as? Double, recovery: nil,
                        strain: nil, exerciseCount: nil, steps: $0["steps"] as? Int)
        }
    }

    static func encode(_ rows: [DailyMetric]) -> [String] {
        func f(_ v: Double?) -> String { v.map { String($0) } ?? "nil" }
        func i(_ v: Int?) -> String { v.map { String($0) } ?? "nil" }
        return rows.map {
            "\($0.day) sleep=\(f($0.totalSleepMin)) deep=\(f($0.deepMin)) hrv=\(f($0.avgHrv)) rhr=\(i($0.restingHr)) steps=\(i($0.steps))"
        }
    }

    func testFoldMatchesSharedOracle() throws {
        for c in try Self.fixture()["foldCases"] as! [[String: Any]] {
            let out = Repository.foldWearableHistory(Self.rows(c["merged"]), history: Self.rows(c["history"]))
            XCTAssertEqual(Self.encode(out), c["expected"] as? [String], c["name"] as? String ?? "")
        }
    }

    /// The export namespaces never join the ids workouts, raw streams and the WHOOP CSV export read.
    @MainActor func testHistoryIdsStayOutOfTheImportedUnion() {
        let repo = Repository(deviceId: "garmin-abc")
        XCTAssertEqual(repo.importedReadIds, ["garmin-abc", "my-whoop"])
        XCTAssertEqual(repo.historyReadIds.first, "garmin-import")
        XCTAssertEqual(repo.baselineSeedIds, ["garmin-import"])
        XCTAssertTrue(Set(repo.importedReadIds).isDisjoint(with: WearableHistory.sourceIds))
    }

    /// A brand's own score never resolves as the NOOP metric of the same key; history candidates come last.
    @MainActor func testResolvedSeriesCandidatesPutHistoryLastAndSkipReferenceScores() {
        let steps = Repository.sourceCandidates(forKey: "steps", preferredSource: "my-whoop",
                                                actualWhoopSource: "garmin-abc").map(\.source)
        XCTAssertEqual(Array(steps.suffix(4)), ["garmin-import", "oura-import", "oura-api", "fitbit-import"])
        XCTAssertEqual(steps.first, "garmin-abc")
        let stress = Repository.sourceCandidates(forKey: "stress", preferredSource: "my-whoop",
                                                 actualWhoopSource: "garmin-abc").map(\.source)
        XCTAssertFalse(stress.contains("garmin-import"))
    }
}
