import XCTest
@testable import StrandAnalytics

/// Pins `CoachMetricContext`. The Kotlin twin (`CoachMetricContextTest`) asserts the same literals, which were
/// produced by compiling this implementation standalone over the same fixture.
final class CoachMetricContextTests: XCTestCase {
    private let days: [String] = (0..<30).map { i in
        let d = 12 + i
        return d <= 31 ? String(format: "2026-10-%02d", d) : String(format: "2026-11-%02d", d - 31)
    }

    private var values: [String: [String: Double]] {
        var v: [String: [String: Double]] = [:]
        for (i, d) in days.enumerated() {
            v["recovery", default: [:]][d] = Double(50 + i)
            v["fitness_age", default: [:]][d] = 31.4 + Double(i % 3) * 0.2
            v["vitality", default: [:]][d] = Double(70 + i % 5)
            v["hydration_ml", default: [:]][d] = Double(1500 + i * 10)
            if i % 7 == 0 { v["weight", default: [:]][d] = 72.45 + Double(i) / 100 }
            if i % 7 == 0 { v["body_fat", default: [:]][d] = 18.25 }
            if i >= 20 { v["workout_count", default: [:]][d] = Double(i % 2 + 1) }
        }
        return v
    }

    private let notes = [
        MetricSnapshotNote(category: .mind, id: "journal:2026-11-09:Caffeine", label: "Caffeine (2026-11-09)", text: "Yes"),
        MetricSnapshotNote(category: .labs, id: "lab:ldl:1", label: "LDL (2026-10-30)", text: "2.9 mmol/L"),
    ]

    func testDefaultCategories() {
        let expected = """
ALL METRICS (ids are metric names; 30d = the last 30 days, averaged unless tagged; per-day lines are newest first, bare numbers in the units shown on the 30d line; a metric that is not listed was not recorded, it is not zero):
[Workouts]
  30d: workout_count=15 total
  2026-11-10: workout_count=2
  2026-11-09: workout_count=1
  2026-11-08: workout_count=2
  2026-11-07: workout_count=1
  2026-11-06: workout_count=2
  2026-11-05: workout_count=1
  2026-11-04: workout_count=2
  2026-11-03: workout_count=1
  2026-11-02: workout_count=2
  2026-11-01: workout_count=1
"""
        XCTAssertEqual(CoachMetricContext.block(days: days, values: values, notes: notes,
                                                enabled: CoachMetricContext.parseCategories(nil)), expected)
    }

    func testNewCategories() {
        let expected = """
ALL METRICS (ids are metric names; 30d = the last 30 days, averaged unless tagged; per-day lines are newest first, bare numbers in the units shown on the 30d line; a metric that is not listed was not recorded, it is not zero):
[Body composition]
  30d: weight=72.7 kg latest, body_fat=18.3 % latest
  2026-11-09: weight=72.7, body_fat=18.3
  2026-11-02: weight=72.7, body_fat=18.3
[Fitness scores]
  30d: fitness_age=32 yrs latest, vitality=74 latest
  2026-11-10: fitness_age=32, vitality=74
  2026-11-09: fitness_age=32, vitality=73
  2026-11-08: fitness_age=31, vitality=72
  2026-11-07: fitness_age=32, vitality=71
  2026-11-06: fitness_age=32, vitality=70
  2026-11-05: fitness_age=31, vitality=74
  2026-11-04: fitness_age=32, vitality=73
  2026-11-03: fitness_age=32, vitality=72
  2026-11-02: fitness_age=31, vitality=71
  2026-11-01: fitness_age=32, vitality=70
  2026-10-31: fitness_age=32, vitality=74
  2026-10-30: fitness_age=31, vitality=73
  2026-10-29: fitness_age=32, vitality=72
  2026-10-28: fitness_age=32, vitality=71
[Hydration]
  30d: hydration_ml=49350 ml total
  2026-11-10: hydration_ml=1790
  2026-11-09: hydration_ml=1780
  2026-11-08: hydration_ml=1770
  2026-11-07: hydration_ml=1760
  2026-11-06: hydration_ml=1750
  2026-11-05: hydration_ml=1740
  2026-11-04: hydration_ml=1730
  2026-11-03: hydration_ml=1720
  2026-11-02: hydration_ml=1710
  2026-11-01: hydration_ml=1700
  2026-10-31: hydration_ml=1690
  2026-10-30: hydration_ml=1680
  2026-10-29: hydration_ml=1670
  2026-10-28: hydration_ml=1660
[Mood & journal]
  Caffeine (2026-11-09): Yes
[Lab markers]
  LDL (2026-10-30): 2.9 mmol/L
"""
        let enabled: Set<String> = ["body_composition", "fitness_scores", "hydration", "mood_journal", "lab_markers"]
        XCTAssertEqual(CoachMetricContext.block(days: days, values: values, notes: notes, enabled: enabled), expected)
    }

    func testTightBudgetDropsPerDayLinesWhole() {
        let expected = """
ALL METRICS (ids are metric names; 30d = the last 30 days, averaged unless tagged; per-day lines are newest first, bare numbers in the units shown on the 30d line; a metric that is not listed was not recorded, it is not zero):
[Workouts]
  30d: workout_count=15 total
[Body composition]
  30d: weight=72.7 kg latest, body_fat=18.3 % latest
  2026-11-09: weight=72.7, body_fat=18.3
  2026-11-02: weight=72.7, body_fat=18.3
[Fitness scores]
  30d: fitness_age=32 yrs latest, vitality=74 latest
[Hydration]
  30d: hydration_ml=49350 ml total
"""
        let enabled: Set<String> = ["body_composition", "fitness_scores", "hydration", "workouts"]
        XCTAssertEqual(CoachMetricContext.block(days: days, values: values, notes: notes, enabled: enabled, maxChars: 700), expected)
    }

    func testNothingEnabledSendsNothing() {
        XCTAssertEqual(CoachMetricContext.block(days: days, values: values, notes: notes, enabled: []), "")
        XCTAssertEqual(CoachMetricContext.block(days: days, values: values, notes: notes, enabled: ["recovery_strain"]), "")
    }

    func testADisabledCategoryNeverAppears() {
        let out = CoachMetricContext.block(days: days, values: values, notes: notes, enabled: ["body_composition"])
        XCTAssertTrue(out.contains("[Body composition]"))
        XCTAssertFalse(out.contains("Fitness") || out.contains("Hydration") || out.contains("LDL"))
    }

    func testParseAndEncode() {
        XCTAssertEqual(CoachMetricContext.encodeCategories(
            CoachMetricContext.parseCategories("labs,heart,sleep,,body_composition ")), "sleep,heart,body_composition")
        XCTAssertEqual(CoachMetricContext.encodeCategories(CoachMetricContext.parseCategories(nil)),
                       "recovery_strain,sleep,heart,activity,workouts")
        XCTAssertTrue(CoachMetricContext.parseCategories("").isEmpty)
    }

    /// Everything on, a full month of every metric: the block stays near the budget (~1,800 tokens).
    func testAFullySpreadSnapshotStaysInsideTheTokenBudget() {
        var v: [String: [String: Double]] = [:]
        for (k, item) in MetricSnapshotCatalog.allItems().enumerated() {
            var perDay: [String: Double] = [:]
            for day in days { perDay[day] = 10.0 + Double(k) + Double(Int(day.suffix(2))!) / 10 }
            v[item.id] = perDay
        }
        let out = CoachMetricContext.block(days: days, values: v, notes: notes,
                                           enabled: Set(MetricSnapshotCategory.allCases.map { $0.id }))
        XCTAssertLessThanOrEqual(out.utf16.count, CoachMetricContext.maxChars)
        for c in MetricSnapshotCategory.allCases { XCTAssertTrue(out.contains("[\(c.label)]"), c.label) }
    }
}
