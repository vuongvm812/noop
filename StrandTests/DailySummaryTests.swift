import XCTest
import StrandAnalytics
@testable import Strand

/// The Daily summary's text layer: every catalog item resolves a label and a unit-aware value, "-" when
/// missing, and the screen is routable from both shells.
final class DailySummaryTests: XCTestCase {
    func testEveryItemHasALabelAndEveryCategoryATitle() {
        for item in MetricSnapshotCatalog.allItems() { XCTAssertFalse(DailySummaryText.label(item.id).isEmpty, item.id) }
        for c in MetricSnapshotCategory.allCases { XCTAssertFalse(DailySummaryText.title(c).isEmpty, c.id) }
    }

    func testValueText() {
        func row(_ id: String, _ v: Double?) -> MetricSnapshotRow {
            MetricSnapshotRow(item: MetricSnapshotCatalog.allItems().first { $0.id == id }!, value: v, days: v == nil ? 0 : 1)
        }
        XCTAssertEqual(DailySummaryText.value(row("body_fat", nil), system: .metric, temperature: .celsius, effortScale: .hundred), "-")
        XCTAssertEqual(DailySummaryText.value(row("body_fat", 18.25), system: .metric, temperature: .celsius, effortScale: .hundred), "18.3 %")
        XCTAssertEqual(DailySummaryText.value(row("recovery", 66.5), system: .metric, temperature: .celsius, effortScale: .hundred), "67 %")
        XCTAssertEqual(DailySummaryText.value(row("weight", 80), system: .metric, temperature: .celsius, effortScale: .hundred),
                       UnitFormatter.massFromKilograms(80, system: .metric))
        XCTAssertEqual(DailySummaryText.value(row("weight", 80), system: .imperial, temperature: .fahrenheit, effortScale: .hundred),
                       UnitFormatter.massFromKilograms(80, system: .imperial))
    }

    func testRoutable() {
        XCTAssertTrue(NavItem.allCases.contains(.dailySummary))
        XCTAssertTrue(NavGroup.all.contains { $0.items.contains(.dailySummary) })
        XCTAssertEqual(NavRouter.Destination(deepLinkKey: "dailySummary"), .dailySummary)
    }
}
