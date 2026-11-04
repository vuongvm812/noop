import XCTest
import WhoopProtocol
@testable import StrandAnalytics

/// Kotlin twin: `WorkoutWatchReferenceTest`, which pins the same inputs and the same printed lines.
final class WorkoutWatchReferenceTests: XCTestCase {

    static func line(_ r: WorkoutWatchReference?) -> String {
        guard let r else { return "nil" }
        func f(_ v: Double?) -> String { v.map { String(format: "%.4f", $0) } ?? "nil" }
        return "ae=\(f(r.aerobicTrainingEffect)) an=\(f(r.anaerobicTrainingEffect)) load=\(f(r.trainingLoad)) "
            + "mod=\(f(r.moderateMinutes)) vig=\(f(r.vigorousMinutes)) im=\(f(r.intensityMinutes))"
    }

    func testSummaryOracle() {
        func s(_ ts: Int, _ kind: DeviceMetricKind, _ v: Double) -> DeviceMetricSample {
            DeviceMetricSample(ts: ts, kind: kind, value: v, source: "t")
        }
        let cases: [[DeviceMetricSample]] = [
            [],
            [s(100, .stress, 40), s(100, .bodyBattery, 60)],
            [s(100, .aerobicTrainingEffect, 3.0), s(200, .aerobicTrainingEffect, 3.4),
             s(200, .anaerobicTrainingEffect, 1.0), s(100, .anaerobicTrainingEffect, 2.0),
             s(150, .trainingLoad, 85.5),
             s(110, .intensityModerateMin, 1), s(170, .intensityModerateMin, 2),
             s(180, .intensityModerateMin, .nan), s(190, .intensityVigorousMin, 2)],
            [s(110, .intensityVigorousMin, 3)],
            [s(110, .intensityModerateMin, 4)],
            [s(100, .aerobicTrainingEffect, 2.5), s(100, .aerobicTrainingEffect, 2.7)],
        ]
        XCTAssertEqual(cases.map { Self.line(WorkoutWatchReference.summarize($0)) }, [
            "nil",
            "nil",
            "ae=3.4000 an=1.0000 load=85.5000 mod=3.0000 vig=2.0000 im=7.0000",
            "ae=nil an=nil load=nil mod=nil vig=3.0000 im=6.0000",
            "ae=nil an=nil load=nil mod=4.0000 vig=nil im=4.0000",
            "ae=2.7000 an=nil load=nil mod=nil vig=nil im=nil",
        ])
    }

    func testReadsExactlyTheKindsItSummarises() {
        XCTAssertEqual(WorkoutWatchReference.kinds.map(\.rawValue), [
            "aerobic_training_effect", "anaerobic_training_effect", "training_load",
            "intensity_moderate_min", "intensity_vigorous_min",
        ])
    }
}
