import XCTest
@testable import StrandAnalytics
import WhoopProtocol

/// #50: a Garmin watch workout gets an Effort from the watch's own trace, and the HRmax a rescore uses is
/// the unrounded Tanaka value on both platforms. The expected strains are this platform's output pasted
/// verbatim into the Kotlin twin `GarminWorkoutEffortTest`, so the two must agree to the same literals.
final class GarminWorkoutEffortTests: XCTestCase {

    /// A 2.5 h run at 1 Hz (9000 samples, past the old 8000-sample cap): a 10 min ramp from 100 to 150,
    /// then 4 min blocks alternating 145 and 172 bpm.
    static func run() -> [HRSample] {
        (0..<9000).map { i -> HRSample in
            let bpm = i < 600 ? 100 + i / 12 : ((i - 600) / 240 % 2 == 0 ? 145 : 172)
            return HRSample(ts: 1_700_000_000 + i, bpm: bpm)
        }
    }

    static let profile = UserProfile(weightKg: 72, heightCm: 178, age: 33, sex: "female")

    func testWatchSourceIsTheRegistryGarminIdOnly() {
        XCTAssertTrue(GarminWorkoutEffort.isWatchSource("garmin-5C4C0BF8-2DF6-1B3A-18D0-3DF0B3590148"))
        XCTAssertTrue(GarminWorkoutEffort.isWatchSource("Garmin-abc"))
        XCTAssertFalse(GarminWorkoutEffort.isWatchSource("garmin-import"))
        XCTAssertFalse(GarminWorkoutEffort.isWatchSource("garmin"))
        XCTAssertFalse(GarminWorkoutEffort.isWatchSource("my-whoop"))
        XCTAssertFalse(GarminWorkoutEffort.isWatchSource("activity-file"))
        XCTAssertFalse(GarminWorkoutEffort.isWatchSource("apple-health"))
    }

    /// Unrounded Tanaka, or the override. Age 33 is 184.9: the old Swift path rounded it to 185.
    func testHrMaxIsUnroundedTanakaOrTheOverride() {
        XCTAssertEqual(ManualWorkoutRescore.hrMax(override: nil, age: 33), 184.9, accuracy: 1e-12)
        XCTAssertEqual(ManualWorkoutRescore.hrMax(override: nil, age: 0), 208.0, accuracy: 1e-12)
        XCTAssertEqual(ManualWorkoutRescore.hrMax(override: 191, age: 33), 191.0)
    }

    func testAScoredRowIsNeverRescored() {
        XCTAssertNil(GarminWorkoutEffort.strainToFill(currentStrain: 40, windowSamples: Self.run(),
                                                       profile: Self.profile, hrMax: 184.9, restingHR: 48))
    }

    func testATooThinTraceStaysUnscored() {
        XCTAssertNil(GarminWorkoutEffort.strainToFill(currentStrain: nil, windowSamples: [HRSample(ts: 0, bpm: 150)],
                                                       profile: Self.profile, hrMax: 184.9, restingHR: 48))
    }

    /// Oracle: the strains Kotlin must reproduce for the same run.
    func testOracleStrains() {
        let cases: [(Double, Double?, StrainScorer.Method, Double)] = Self.oracle
        for (hrMax, resting, method, expected) in cases {
            let got = GarminWorkoutEffort.strainToFill(currentStrain: nil, windowSamples: Self.run(),
                                                       profile: Self.profile, hrMax: hrMax, restingHR: resting,
                                                       effortMethod: method)
            XCTAssertEqual(got ?? -1, expected, accuracy: 1e-9, "hrMax=\(hrMax) resting=\(String(describing: resting)) \(method)")
        }
    }

    /// The measured resting HR raises the run's Effort over the default 60 (#950 for Garmin rows).
    func testMeasuredRestingScoresAboveTheDefault() {
        let def = GarminWorkoutEffort.strainToFill(currentStrain: nil, windowSamples: Self.run(),
                                                   profile: Self.profile, hrMax: 184.9, restingHR: nil)!
        let measured = GarminWorkoutEffort.strainToFill(currentStrain: nil, windowSamples: Self.run(),
                                                        profile: Self.profile, hrMax: 184.9, restingHR: 48)!
        XCTAssertGreaterThan(measured, def)
    }

    /// Pasted from this test's own output. 184.9 is the unrounded HRmax for age 33 and 185 the value the
    /// old Swift path passed: they part under Banister (67.21 against 67.18), which is the divergence #50 fixed.
    static let oracle: [(Double, Double?, StrainScorer.Method, Double)] = [
        (184.9, nil, .edwards, 68.16), (184.9, nil, .banister, 67.21),
        (184.9, 48, .edwards, 71.42), (184.9, 48, .banister, 67.81),
        (185.0, nil, .banister, 67.18), (185.0, 48, .banister, 67.79),
        (191.0, nil, .edwards, 68.11), (191.0, nil, .banister, 65.89),
        (191.0, 48, .edwards, 68.18), (191.0, 48, .banister, 66.59),
    ]
}
