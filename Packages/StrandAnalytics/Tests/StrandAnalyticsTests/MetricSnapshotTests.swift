import XCTest
@testable import StrandAnalytics

/// Pins `MetricSnapshot`. The Kotlin twin (`MetricSnapshotTest`) asserts the same literals.
final class MetricSnapshotTests: XCTestCase {
    private func fmt(_ id: String, _ v: Double?) -> String {
        MetricSnapshotFormat.text(MetricSnapshotCatalog.allItems().first { $0.id == id }!, v)
    }

    func testFormat() {
        XCTAssertEqual(fmt("recovery", 66.5), "67 %")
        XCTAssertEqual(fmt("recovery", 66.4999), "66 %")
        XCTAssertEqual(fmt("strain", 12.25), "12.3 /100")
        XCTAssertEqual(fmt("strain", -0.04), "0.0 /100")
        XCTAssertEqual(fmt("weight", 81.35), "81.4 kg")
        XCTAssertEqual(fmt("weight", 81.05), "81.1 kg")
        XCTAssertEqual(fmt("fitness_age", -0.4), "0 yrs")
        XCTAssertEqual(fmt("fitness_age", 33.5), "34 yrs")
        XCTAssertEqual(fmt("fitness_age", -2.5), "-3 yrs")
        XCTAssertEqual(fmt("steps", 12345), "12345 steps")
        XCTAssertEqual(fmt("body_fat", 18.25), "18.3 %")
        XCTAssertEqual(fmt("hrv", 0.5), "1 ms")
        XCTAssertEqual(fmt("hrv", 1.5), "2 ms")
        XCTAssertEqual(fmt("hrv", 2.5), "3 ms")
        XCTAssertEqual(fmt("skin_temp", -0.05), "-0.1 °C")
        XCTAssertEqual(fmt("vo2max", 52.04), "52.0 ml/kg/min")
        XCTAssertEqual(fmt("hrv", nil), "-")
    }

    func testRangeRollupAndCoachLines() {
        let snap = MetricSnapshotBuilder.build(
            from: "2026-01-02", to: "2026-01-04",
            values: [
                "recovery": ["2026-01-01": 10, "2026-01-02": 50, "2026-01-04": 61],
                "steps": ["2026-01-02": 1000, "2026-01-03": 2500],
                "weight": ["2026-01-02": 80.0, "2026-01-04": 79.5],
                "body_age": ["2026-01-03": 30.0],
                "max_hr": ["2026-01-02": 150, "2026-01-03": 171],
                "body_battery_min": ["2026-01-02": 20, "2026-01-03": 12],
            ],
            notes: [MetricSnapshotNote(category: .labs, id: "lab:ldl", label: "LDL", text: "2.9 mmol/L")])
        XCTAssertEqual(snap.row("recovery")?.text, "56 %")
        XCTAssertEqual(snap.row("max_hr")?.text, "171 bpm")
        XCTAssertEqual(snap.row("steps")?.text, "3500 steps")
        XCTAssertEqual(snap.row("weight")?.text, "79.5 kg")
        XCTAssertEqual(snap.row("body_age")?.text, "30 yrs")
        XCTAssertEqual(snap.row("body_battery_min")?.text, "12")
        XCTAssertEqual(snap.sections.first { $0.category == .labs }?.notes.first?.text, "2.9 mmol/L")
        XCTAssertFalse(snap.isEmpty)
        XCTAssertEqual(snap.row("recovery")?.days, 2)
    }

    func testCatalogIsConsistent() {
        XCTAssertEqual(MetricSnapshotCatalog.allItems().count, Set(MetricSnapshotCatalog.allItems().map { $0.id }).count)
        for cat in MetricSnapshotCategory.allCases {
            XCTAssertEqual(MetricSnapshotCategory(rawValue: cat.id), cat)
            if cat != .labs { XCTAssertFalse(MetricSnapshotCatalog.items(in: cat).isEmpty, cat.id) }
        }
        for i in MetricSnapshotCatalog.allItems() { XCTAssertEqual(i.seriesKey != nil, !i.sources.isEmpty, i.id) }
        XCTAssertEqual(MetricSnapshotCategory.allCases.count, 13)
    }

    func testEmptySnapshotHasEverySectionAndDashes() {
        let snap = MetricSnapshotBuilder.build(from: "2026-01-01", to: "2026-01-01", values: [:])
        XCTAssertTrue(snap.isEmpty)
        XCTAssertEqual(snap.sections.count, 13)
        XCTAssertEqual(snap.row("body_fat")?.text, "-")
    }
}
