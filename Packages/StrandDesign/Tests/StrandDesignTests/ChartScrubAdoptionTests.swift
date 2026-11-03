import XCTest
@testable import StrandDesign

/// The pure pieces each chart adds on top of `ChartScrubMath` to read a reading under the scrub
/// cursor (#58): the motion trace's epoch clock and scale, the hypnogram's band-or-gap resolution, and
/// the heat strip's 2D cell hit-test.
final class ChartScrubAdoptionTests: XCTestCase {

    // MARK: MotionTrace

    func testEpochDateStepsThirtySecondsFromStart() {
        let start = Date(timeIntervalSince1970: 1_700_000_000)
        XCTAssertEqual(MotionTrace.epochDate(0, from: start), start)
        XCTAssertEqual(MotionTrace.epochDate(1, from: start), start.addingTimeInterval(30))
        // 8 hours of 30 s epochs: epoch 960 starts exactly 8 h after the first.
        XCTAssertEqual(MotionTrace.epochDate(960, from: start), start.addingTimeInterval(8 * 3600))
    }

    func testPeakPercentIsTheStripsOwnScale() {
        XCTAssertEqual(MotionTrace.peakPercent(0.5, peak: 2), 25)
        XCTAssertEqual(MotionTrace.peakPercent(2, peak: 2), 100)
        XCTAssertEqual(MotionTrace.peakPercent(0, peak: 2), 0)
        XCTAssertEqual(MotionTrace.peakPercent(1, peak: 3), 33)
        // Degenerate inputs stay in range rather than dividing by zero or overflowing.
        XCTAssertEqual(MotionTrace.peakPercent(1, peak: 0), 0)
        XCTAssertEqual(MotionTrace.peakPercent(-1, peak: 2), 0)
        XCTAssertEqual(MotionTrace.peakPercent(5, peak: 2), 100)
    }

    // MARK: Hypnogram

    private let holed = [
        SleepInterval(stage: .light, start: 0, end: 600),
        SleepInterval(stage: .deep, start: 600, end: 1_200),
        // A 30-minute hole: nothing recorded from 1,200 s to 3,000 s.
        SleepInterval(stage: .rem, start: 3_000, end: 3_600),
    ]

    func testHypnogramScrubReadsTheBandUnderTheCursor() {
        XCTAssertEqual(Hypnogram.scrubHit(at: 300, in: holed), .init(index: 0, inGap: false, time: 300))
        XCTAssertEqual(Hypnogram.scrubHit(at: 900, in: holed), .init(index: 1, inGap: false, time: 900))
        XCTAssertEqual(Hypnogram.scrubHit(at: 3_300, in: holed), .init(index: 2, inGap: false, time: 3_300))
    }

    func testHypnogramScrubReportsAHoleAsAGap() {
        let hit = Hypnogram.scrubHit(at: 2_000, in: holed)
        XCTAssertEqual(hit?.inGap, true)
        XCTAssertEqual(hit?.time, 2_000)
    }

    func testHypnogramScrubTreatsASubEpochSeamAsTheNeighbour() {
        let seamed = [
            SleepInterval(stage: .light, start: 0, end: 600),
            SleepInterval(stage: .deep, start: 620, end: 1_200),
        ]
        XCTAssertEqual(Hypnogram.scrubHit(at: 610, in: seamed)?.inGap, false)
        XCTAssertNil(Hypnogram.scrubHit(at: 10, in: []))
    }

    // MARK: YearHeatStrip

    func testHeatStripCellHitTest() {
        // 12 pt cells with 3 pt spacing: a 15 pt stride.
        func hit(_ x: CGFloat, _ y: CGFloat) -> [Int]? {
            YearHeatStrip.cellIndex(x: x, y: y, stride: 15, weekCount: 52).map { [$0.week, $0.row] }
        }
        XCTAssertEqual(hit(0, 0), [0, 0])
        XCTAssertEqual(hit(16, 31), [1, 2])
        // The spacing after a cell still belongs to it, so a dragged cursor never blinks off.
        XCTAssertEqual(hit(13.5, 13.5), [0, 0])
        // Outside the grid.
        XCTAssertNil(hit(-1, 0))
        XCTAssertNil(hit(0, 7 * 15))
        XCTAssertNil(hit(52 * 15, 0))
    }
}
