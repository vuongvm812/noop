import XCTest
@testable import Strand
import WhoopStore
import StrandAnalytics

/// The sleep detail the coach could not see (#1817), and the normalisation that stops it reading
/// nonsense. Twin of the Kotlin `AiCoachContextTest` cases; the formatters are pure, so this runs
/// headlessly with no repo reads.
@MainActor
final class AICoachSleepContextTests: XCTestCase {

    private func engine() -> AICoachEngine {
        AICoachEngine(repo: Repository(deviceId: "test-aicoach-sleep"))
    }

    private func day(deep: Double? = nil, rem: Double? = nil,
                     light: Double? = nil, efficiency: Double? = nil) -> DailyMetric {
        DailyMetric(day: "2026-06-01", totalSleepMin: 450, efficiency: efficiency,
                    deepMin: deep, remMin: rem, lightMin: light, disturbances: nil,
                    restingHr: 52, avgHrv: 65, recovery: 67, strain: 12.3, exerciseCount: nil)
    }

    /// `rest 7.5h` used to be every word the coach got about a night.
    func testStagesReachTheContextLine() {
        let line = engine().dayLine(day(deep: 84, rem: 114, light: 252, efficiency: 0.94))
        XCTAssertTrue(line.contains("deep 1.4h"), line)
        XCTAssertTrue(line.contains("REM 1.9h"), line)
        XCTAssertTrue(line.contains("light 4.2h"), line)
        XCTAssertTrue(line.contains("eff 94%"), line)
    }

    /// A night with no staging says so rather than the field vanishing, so the model cannot read a
    /// missing stage as a zero.
    func testUnstagedNightReportsDashesNotSilence() {
        let line = engine().dayLine(day())
        XCTAssertTrue(line.contains("deep —"), line)
        XCTAssertTrue(line.contains("REM —"), line)
        XCTAssertTrue(line.contains("light —"), line)
        XCTAssertTrue(line.contains("eff —"), line)
    }

    /// Efficiency arrives as a PERCENTAGE on some import paths, which `SleepView` and `StagesCard`
    /// each guard against inline. Without the same guard a bare `* 100` sends "eff 9400%".
    func testEfficiencyStoredAsPercentageIsNotMultipliedAgain() {
        let e = engine()
        XCTAssertEqual(e.efficiencyPercentOrDash(0.94), "94%")
        XCTAssertEqual(e.efficiencyPercentOrDash(94.0), "94%")
        XCTAssertEqual(e.efficiencyPercentOrDash(nil), "—")
        XCTAssertEqual(e.efficiencyPercentOrDash(0), "—")
        // Above a fraction but below the percentage split: not a value this can honestly render.
        XCTAssertEqual(e.efficiencyPercentOrDash(1.2), "—")
    }

    // MARK: - Per-category sharing (Kotlin twin: AiCoachContextTest)

    /// Every category on is the long-standing day line, field for field.
    func testAllCategoriesOnIsTheLongStandingLine() {
        let e = engine()
        let d = day(deep: 84, rem: 114, light: 252, efficiency: 0.94)
        XCTAssertEqual(e.dayLine(d), e.dayLine(d, enabled: Set(MetricSnapshotCategory.allCases.map { $0.id })))
    }

    /// A switched-off category's fields never reach the line, field by field.
    func testASwitchedOffCategoryIsNotSent() {
        let e = engine()
        let d = day(deep: 84, rem: 114, light: 252, efficiency: 0.94)
        let recoveryOnly = e.dayLine(d, enabled: [MetricSnapshotCategory.recoveryStrain.id])
        XCTAssertTrue(recoveryOnly.contains("charge 67") && recoveryOnly.contains("HRV 65ms"), recoveryOnly)
        XCTAssertFalse(recoveryOnly.contains("rest") || recoveryOnly.contains("deep") || recoveryOnly.contains("REM"), recoveryOnly)
        let sleepOnly = e.dayLine(d, enabled: [MetricSnapshotCategory.sleep.id])
        XCTAssertTrue(sleepOnly.contains("rest 7.5h, deep 1.4h, REM 1.9h, light 4.2h, eff 94%"), sleepOnly)
        XCTAssertFalse(sleepOnly.contains("charge") || sleepOnly.contains("HRV") || sleepOnly.contains("RHR"), sleepOnly)
    }

    /// The brief asks for every shared category and forbids talking about unshared ones. The text is the
    /// same sentence as Kotlin's `BRIEF_INSTRUCTION`.
    func testBriefInstructionCoversSharedCategories() {
        XCTAssertTrue(AICoachEngine.briefInstructionText.contains("every category of data shared above"))
        XCTAssertTrue(AICoachEngine.briefInstructionText.contains("never mention a category that was not shared"))
    }
}
