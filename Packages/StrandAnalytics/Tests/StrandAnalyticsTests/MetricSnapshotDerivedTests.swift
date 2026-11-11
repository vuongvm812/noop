import XCTest
@testable import StrandAnalytics

/// Pins the pure halves of the snapshot loader. The Kotlin twin (`MetricSnapshotLoaderTest`) asserts the same values.
final class MetricSnapshotDerivedTests: XCTestCase {
    func testWorkoutDayAggregation() {
        let a = SnapshotWorkout(startTs: 0, endTs: 3600, durationS: 3600, energyKcal: 500, avgHr: 140, maxHr: 170,
                                strain: 40, distanceM: 10_000, zonesJSON: "{\"z1\":10,\"z2\":20,\"z3\":30,\"z4\":40,\"z5\":0}",
                                avgPowerW: 200, avgCadence: 90, altitudeGainM: 100, maxAltitudeM: 450,
                                aerobicTe: 3.2, anaerobicTe: 1.1)
        let b = SnapshotWorkout(startTs: 7200, endTs: 9000, durationS: 1800, energyKcal: 200, avgHr: 120, maxHr: 150,
                                strain: 55.5, distanceM: 2_500, zonesJSON: "{\"zone1\":50,\"zone2\":50}")
        let d = MetricSnapshotDerived.workoutDay([a, b])
        XCTAssertEqual(d["workout_count"], 2)
        XCTAssertEqual(d["workout_duration_min"], 90)
        XCTAssertEqual(d["workout_energy_kcal"], 700)
        XCTAssertEqual(d["workout_distance_km"], 12.5)
        XCTAssertEqual(d["workout_avg_hr"]!, (140 * 60 + 120 * 30) / 90.0, accuracy: 1e-9)
        XCTAssertEqual(d["workout_max_hr"], 170)
        XCTAssertEqual(d["workout_effort"], 55.5)
        XCTAssertEqual(d["workout_zone1_min"]!, 6 + 15, accuracy: 1e-9)
        XCTAssertEqual(d["workout_zone2_min"]!, 12 + 15, accuracy: 1e-9)
        XCTAssertEqual(d["workout_zone4_min"]!, 24, accuracy: 1e-9)
        XCTAssertEqual(d["workout_zone5_min"]!, 0, accuracy: 1e-9)
        XCTAssertEqual(d["workout_avg_power_w"], 200)
        XCTAssertEqual(d["workout_altitude_gain_m"], 100)
        XCTAssertEqual(d["workout_max_altitude_m"], 450)
        XCTAssertEqual(d["workout_aerobic_te"], 3.2)
        XCTAssertEqual(d["workout_avg_cadence"], 90)
        XCTAssertEqual(MetricSnapshotDerived.workoutDay([]), [:])
    }

    func testWorkoutWithoutOptionalFigures() {
        let d = MetricSnapshotDerived.workoutDay([SnapshotWorkout(startTs: 0, endTs: 600)])
        XCTAssertEqual(d, ["workout_count": 1, "workout_duration_min": 10])
    }

    func testCycleDay() {
        let starts = ["2026-05-01", "2026-05-29"]
        XCTAssertEqual(MetricSnapshotDerived.cycleDay(starts: starts, day: "2026-05-29"), 1)
        XCTAssertEqual(MetricSnapshotDerived.cycleDay(starts: starts, day: "2026-06-03"), 6)
        XCTAssertEqual(MetricSnapshotDerived.cycleDay(starts: starts, day: "2026-05-10"), 10)
        XCTAssertNil(MetricSnapshotDerived.cycleDay(starts: starts, day: "2026-04-30"))
        XCTAssertNil(MetricSnapshotDerived.cycleDay(starts: [], day: "2026-06-03"))
        XCTAssertEqual(MetricSnapshotDerived.cycleDay(starts: ["2026-02-27"], day: "2026-03-02"), 4)
    }

    func testHydrationAndText() {
        XCTAssertEqual(MetricSnapshotDerived.hydrationTotal(manual: 500, imported: 250), 750)
        XCTAssertEqual(MetricSnapshotDerived.hydrationTotal(manual: nil, imported: 300), 300)
        XCTAssertNil(MetricSnapshotDerived.hydrationTotal(manual: 0, imported: nil))
        XCTAssertEqual(MetricSnapshotDerived.journalText(answeredYes: true, numericValue: nil), "Yes")
        XCTAssertEqual(MetricSnapshotDerived.journalText(answeredYes: false, numericValue: nil), "No")
        XCTAssertEqual(MetricSnapshotDerived.journalText(answeredYes: true, numericValue: 200), "Yes (200)")
        XCTAssertEqual(MetricSnapshotDerived.journalText(answeredYes: true, numericValue: 1.5), "Yes (1.5)")
        XCTAssertEqual(MetricSnapshotDerived.labText(value: "2.9", unit: "mmol/L", valueText: nil), "2.9 mmol/L")
        XCTAssertEqual(MetricSnapshotDerived.labText(value: nil, unit: "", valueText: "positive"), "positive")
        XCTAssertEqual(MetricSnapshotDerived.labText(value: nil, unit: "", valueText: nil), "-")
    }
}
