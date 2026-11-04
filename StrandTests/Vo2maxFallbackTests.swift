import XCTest
@testable import Strand
import StrandAnalytics
import WhoopStore

/// #1391: VO₂max is offered even WITHOUT a waist. `fitnessAgeRows` persists `vo2max_est` = the Nes 2011
/// waist-based estimate when a waist is set, else falls back to the Uth 2004 HR-ratio estimate
/// (15.3·HRmax/(RHR + 5), HRmax = Tanaka(age)) — so a user past the age+RHR fitness-age gate gets a VO₂max
/// instead of a blank. Twin of the Android Vo2maxFallbackTest.
///
/// `@MainActor`: `IntelligenceEngine.fitnessAgeRows` is main-actor-isolated, so the whole fixture
/// runs on the main actor to call it from a synchronous test context.
@MainActor
final class Vo2maxFallbackTests: XCTestCase {

    /// ≥ minCoverageDays (4) RHR nights + strain so the fitness-age gate can compute.
    private func gate(_ rhr: Int) -> [DailyMetric] {
        (0..<7).map { i in
            DailyMetric(day: String(format: "2026-08-%02d", 9 + i), totalSleepMin: nil, efficiency: nil,
                        deepMin: nil, remMin: nil, lightMin: nil, disturbances: nil, restingHr: rhr,
                        avgHrv: nil, recovery: nil, strain: 50, exerciseCount: nil)
        }
    }

    private func vo2maxEst(waistCm: Double) -> Double? {
        IntelligenceEngine.fitnessAgeRows(
            gateDays: gate(60), age: 40, sex: "male", waistCm: waistCm, heightCm: 175, weightKg: 80,
            computedId: "my-whoop-noop", satKey: "2026-08-15"
        ).first { $0.key == "vo2max_est" }?.value
    }

    func testNoWaistFallsBackToUthHrRatioEstimate() {
        let vo2 = vo2maxEst(waistCm: 0)   // no waist → Nes value is nil
        XCTAssertNotNil(vo2, "without a waist, VO₂max must still be offered via the Uth fallback")
        // Uth: 15.3 × Tanaka(40)=180 / (RHR 60 + 5) = 42.369… (the +5 bpm maps a sleeping RHR to the awake one, #51)
        XCTAssertEqual(vo2!, 15.3 * StrainScorer.tanakaHRmax(age: 40) / (60.0 + Calories.uthAwakeRestingOffsetBpm), accuracy: 1e-6)
    }

    func testWaistSetUsesTheNesWaistBasedEstimate() {
        let uth = vo2maxEst(waistCm: 0)!
        let nes = vo2maxEst(waistCm: 90)!
        let paIndex = FitnessAgeEngine.physicalActivityIndexFromStrain(activeDaysPerWeek: 7, meanActiveStrain: 50)
        // With a waist the persisted value is the Nes waist-based estimate…
        XCTAssertEqual(nes, FitnessAgeEngine.estimateVO2max(age: 40, sex: "male", waistCm: 90,
                                                            restingHR: 60, paIndex: paIndex), accuracy: 1e-6)
        // …and it differs from the Uth HR-ratio fallback.
        XCTAssertGreaterThan(abs(nes - uth), 0.01)
    }

    func testNewlyComputedPointRecordsTheEstimatorUsedAtComputeTime() {
        let uthRows = IntelligenceEngine.fitnessAgeRows(
            gateDays: gate(60), age: 40, sex: "male", waistCm: 0, heightCm: 175, weightKg: 80,
            computedId: "my-whoop-noop", satKey: "2026-08-15")
        let nesRows = IntelligenceEngine.fitnessAgeRows(
            gateDays: gate(60), age: 40, sex: "male", waistCm: 90, heightCm: 175, weightKg: 80,
            computedId: "my-whoop-noop", satKey: "2026-08-22")

        let uth = IntelligenceEngine.vo2MaxProvenance(points: uthRows, waistCm: 0).first
        let nes = IntelligenceEngine.vo2MaxProvenance(points: nesRows, waistCm: 90).first
        XCTAssertEqual(uth?.sourceId, Vo2MaxEstimator.uth.rawValue)
        XCTAssertEqual(uth?.day, "2026-08-15")
        XCTAssertEqual(uth?.key, "vo2max_est")
        XCTAssertEqual(nes?.sourceId, Vo2MaxEstimator.nes.rawValue)
        XCTAssertNil(Vo2MaxEstimator(rawValue: "my-whoop"))
    }

    /// #51: the watch's own VO₂max wins over the estimate and needs no fitness-age gate; its tag names the
    /// watch. Kotlin twin: `watchVo2max_winsWithoutTheGateAndIsTaggedGarmin`.
    func testWatchVo2maxWinsWithoutTheGateAndIsTaggedGarmin() {
        let gated = IntelligenceEngine.fitnessAgeRows(
            gateDays: gate(60), age: 40, sex: "male", waistCm: 90, heightCm: 175, weightKg: 80,
            computedId: "garmin-1-noop", satKey: "2026-08-15", watchVo2max: 54.2)
        XCTAssertEqual(gated.first { $0.key == "vo2max_est" }?.value, 54.2)
        XCTAssertNotNil(gated.first { $0.key == "fitness_age" })
        let ungated = IntelligenceEngine.fitnessAgeRows(
            gateDays: [], age: 0, sex: "", waistCm: 0, heightCm: 0, weightKg: 0,
            computedId: "garmin-1-noop", satKey: "2026-08-15", watchVo2max: 54.2)
        XCTAssertEqual(ungated.map(\.key), ["vo2max_est"])
        let tag = IntelligenceEngine.vo2MaxProvenance(points: ungated, waistCm: 0, watchVo2max: 54.2).first
        XCTAssertEqual(tag?.sourceId, Vo2MaxEstimator.garmin.rawValue)
        XCTAssertEqual(vo2MaxEstimatorDisplayName(.garmin), "Garmin")
    }
}
