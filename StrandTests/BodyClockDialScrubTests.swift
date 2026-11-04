import XCTest
@testable import Strand

/// The body clock dial's scrub (#58): which arc a cursor reads, and how a clock hour rounds for its
/// callout. Same numbers as the Kotlin `bodyClockArcAt` / `bodyClockHourLabel` tests.
final class BodyClockDialScrubTests: XCTestCase {

    /// A 300 × 200 pt canvas: side 200, rim 90 (the tuned rim, scale 1), so the reference band sits at
    /// radius 59 and the night band at 45, around the centre (150, 100).
    private let size = CGSize(width: 300, height: 200)

    private func point(radius: CGFloat) -> CGPoint { CGPoint(x: 150 + radius, y: 100) }

    func testCursorOnABandReadsThatArc() {
        XCTAssertEqual(BodyClockDialCard.arcAt(point(radius: 45), size: size), .night)
        XCTAssertEqual(BodyClockDialCard.arcAt(point(radius: 59), size: size), .ideal)
    }

    func testNearerBandWinsAndATieGoesToTheNight() {
        XCTAssertEqual(BodyClockDialCard.arcAt(point(radius: 10), size: size), .night)    // inside both
        XCTAssertEqual(BodyClockDialCard.arcAt(point(radius: 95), size: size), .ideal)    // outside both
        XCTAssertEqual(BodyClockDialCard.arcAt(point(radius: 52), size: size), .night)    // 7 from each
        XCTAssertEqual(BodyClockDialCard.arcAt(point(radius: 53), size: size), .ideal)
    }

    func testEmptyCanvasReadsNothing() {
        XCTAssertNil(BodyClockDialCard.arcAt(.zero, size: .zero))
    }

    func testClockHoursRoundToTheStepAndWrapPastMidnight() {
        XCTAssertEqual(BodyClockDialCard.roundedMinuteOfDay(22.5, stepMinutes: 1), 1350)
        XCTAssertEqual(BodyClockDialCard.roundedMinuteOfDay(7.05, stepMinutes: 5), 425)   // 7:03 -> 7:05
        XCTAssertEqual(BodyClockDialCard.roundedMinuteOfDay(23.99, stepMinutes: 5), 0)    // 23:59 -> 00:00
        XCTAssertEqual(BodyClockDialCard.roundedMinuteOfDay(-0.5, stepMinutes: 1), 1410)
    }
}
