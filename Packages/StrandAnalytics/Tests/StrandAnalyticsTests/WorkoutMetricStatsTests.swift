import XCTest
@testable import StrandAnalytics

/// Kotlin twin: `WorkoutMetricStatsTest`, same inputs and literals.
final class WorkoutMetricStatsTests: XCTestCase {
    func testAveragePositiveSkipsZerosAndIsNilWhenNone() {
        XCTAssertEqual(WorkoutMetricStats.averagePositive([0, 100, 200, 0, 300]), 200)
        XCTAssertNil(WorkoutMetricStats.averagePositive([0, 0]))
        XCTAssertNil(WorkoutMetricStats.averagePositive([]))
    }

    func testElevationGainSumsOnlyClimbs() {
        XCTAssertEqual(WorkoutMetricStats.elevationGain([10, 15, 12, 20, 20, 18]), 13)
        XCTAssertEqual(WorkoutMetricStats.elevationGain([5]), 0)
        XCTAssertEqual(WorkoutMetricStats.elevationGain([]), 0)
    }
}
