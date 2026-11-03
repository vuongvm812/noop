import XCTest
import WhoopStore
@testable import StrandAnalytics

/// Behaviour of the #55 sleep-scoring funnel: one regularity, one need, no look-ahead.
final class SleepScoringTests: XCTestCase {
    private let scored = "2026-06-15"

    /// `count` nights waking on the days before `scored`, each from `onset(i)` (hours after the previous
    /// local midnight, UTC) lasting `hours`.
    private func history(count: Int, hours: Double = 8, onset: (Int) -> Double = { _ in 23 })
        -> (nights: [SleepNeed.Night], windows: [SleepRegularity.Window]) {
        let d = DayOrdinal.ordinal(scored)!
        var nights: [SleepNeed.Night] = [], windows: [SleepRegularity.Window] = []
        for i in 0..<count {
            let wake = d - count + i
            let start = (wake - 1) * 86_400 + Int(onset(i) * 3600)
            windows.append(.init(start: start, end: start + Int(hours * 3600), offsetSec: 0))
            nights.append(.init(day: DayOrdinal.key(wake), mainMin: hours * 60))
        }
        return (nights, windows)
    }

    func testDayOrdinalRoundTrips() {
        XCTAssertEqual(DayOrdinal.ordinal("1970-01-01"), 0)
        XCTAssertEqual(DayOrdinal.ordinal("2024-02-29").map(DayOrdinal.key), "2024-02-29")
        XCTAssertEqual(DayOrdinal.ordinal("2026-03-01")! - DayOrdinal.ordinal("2026-02-28")!, 1)
        XCTAssertNil(DayOrdinal.ordinal("2026-13-01"))
        XCTAssertNil(DayOrdinal.ordinal("26-01-01"))
    }

    func testIdenticalNightsScoreAPerfectIndex() throws {
        let h = history(count: 14)
        let r = try XCTUnwrap(SleepRegularity.results(windows: h.windows, days: [scored])[scored])
        XCTAssertEqual(r.method, .sri)
        XCTAssertEqual(r.sri ?? 0, 100, accuracy: 1e-9)
        XCTAssertEqual(r.score, 1.0, accuracy: 1e-9)
    }

    func testShiftingOnsetsLowerTheIndex() throws {
        let h = history(count: 14, onset: { $0 % 2 == 0 ? 21 : 26 })
        let r = try XCTUnwrap(SleepRegularity.results(windows: h.windows, days: [scored])[scored])
        XCTAssertEqual(r.method, .sri)
        // 5 h of each 8 h night disagrees with the next: 2 × 60 of 288 epochs differ per pair.
        XCTAssertEqual(r.sri ?? 0, 200.0 * (288.0 - 120.0) / 288.0 - 100.0, accuracy: 1e-9)
        XCTAssertLessThan(r.score, 0.25)
    }

    func testSparseHistoryFallsBackToMidsleepSD() throws {
        // Every other night recorded: no consecutive pairs, so the SRI cannot run.
        let full = history(count: 12, onset: { $0 % 4 == 0 ? 22 : 23 })
        let windows = full.windows.enumerated().filter { $0.offset % 2 == 0 }.map(\.element)
        let r = try XCTUnwrap(SleepRegularity.results(windows: windows, days: [scored])[scored])
        XCTAssertEqual(r.method, .midsleepSD)
        XCTAssertNil(r.sri)
        XCTAssertEqual(r.samples, 6)
        XCTAssertGreaterThan(r.score, 0.5)
        XCTAssertLessThan(r.score, 1.0)
    }

    func testTooLittleHistoryHasNoRegularity() {
        XCTAssertNil(SleepRegularity.results(windows: history(count: 2).windows, days: [scored])[scored])
    }

    /// A night that has not happened yet (and the scored night itself) cannot change a day's context.
    func testContextsUseOnlyEarlierNights() {
        let h = history(count: 20)
        let before = SleepScoring.contexts(nights: h.nights, windows: h.windows, days: [scored], age: 30)[scored]
        let d = DayOrdinal.ordinal(scored)!
        var nights = h.nights, windows = h.windows
        for wake in [d, d + 1] {   // the scored night and the next one, both very short and late
            let start = (wake - 1) * 86_400 + 4 * 3600
            windows.append(.init(start: start, end: start + 3 * 3600, offsetSec: 0))
            nights.append(.init(day: DayOrdinal.key(wake), mainMin: 180, strain: 95))
        }
        let after = SleepScoring.contexts(nights: nights, windows: windows, days: [scored], age: 30)[scored]
        XCTAssertEqual(before, after)
    }

    func testBaseNeedFloorsAtTheAdultMinimum() {
        XCTAssertEqual(AnalyticsEngine.Rest.populationNeedFloorHours(age: 30), 7.0)
        XCTAssertEqual(AnalyticsEngine.Rest.populationNeedFloorHours(age: nil), 7.0)
        XCTAssertEqual(AnalyticsEngine.Rest.populationNeedFloorHours(age: 15), 8.0)
        XCTAssertEqual(AnalyticsEngine.Rest.populationNeedFloorHours(age: 10), 9.0)
        // A consistent 7.25 h sleeper with no debt is scored against 7.25 h, not the old 8 h floor.
        let h = history(count: 20, hours: 7.25)
        let ctx = SleepNeed.contexts(nights: h.nights, days: [scored], age: 30)[scored]!
        XCTAssertEqual(ctx.baseHours, 7.25, accuracy: 1e-9)
        XCTAssertEqual(ctx.debtMin, 0)
    }

    func testStrainRaisesTheNextNightsNeed() {
        XCTAssertEqual(SleepNeed.strainHours(nil), 0)
        XCTAssertEqual(SleepNeed.strainHours(40), 0)
        XCTAssertEqual(SleepNeed.strainHours(75), 0.25, accuracy: 1e-12)
        XCTAssertEqual(SleepNeed.strainHours(120), 0.5, accuracy: 1e-12)
        let d = DayOrdinal.ordinal(scored)!
        let nights = [SleepNeed.Night(day: DayOrdinal.key(d - 1), mainMin: 480, strain: 100)]
        XCTAssertEqual(SleepNeed.contexts(nights: nights, days: [scored], age: 30)[scored]?.strainHours, 0.5)
    }

    /// Debt decays per CALENDAR day: a gap of unrecorded days shrinks it, where the old ledger carried it
    /// across the gap untouched.
    func testDebtDecaysAcrossUnrecordedDays() {
        let d = DayOrdinal.ordinal(scored)!
        let short = SleepNeed.Night(day: DayOrdinal.key(d - 5), mainMin: 240)
        let ctx = SleepNeed.contexts(nights: [short], days: [DayOrdinal.key(d - 4), scored], age: 30)
        let next = ctx[DayOrdinal.key(d - 4)]!.debtMin
        XCTAssertEqual(next, 0.55 * (8 * 60 - 240), accuracy: 1e-9)   // cold-start 8 h need
        // Four more days with no record: 0.55 each, cleared once under 10 min.
        let expected = [0.55, 0.55, 0.55, 0.55].reduce(next) { v, f in
            let n = v * f; return n < SleepDebt.minimumDebtMin ? 0 : n
        }
        XCTAssertEqual(ctx[scored]!.debtMin, expected, accuracy: 1e-9)
    }

    /// The debt persisted after a night is exactly the next day's carried debt.
    func testDebtAfterEqualsTheNextDaysCarriedDebt() {
        var h = history(count: 20, hours: 6.5)
        let d = DayOrdinal.ordinal(scored)!
        let ctx = SleepNeed.contexts(nights: h.nights, days: [scored], age: 30)[scored]!
        let after = SleepNeed.debtAfter(ctx, mainMin: 300, napMin: 20)
        h.nights.append(.init(day: scored, mainMin: 300, napMin: 20))
        let next = DayOrdinal.key(d + 1)
        XCTAssertEqual(SleepNeed.contexts(nights: h.nights, days: [next], age: 30)[next]!.debtMin, after)
        XCTAssertGreaterThan(after, 0)
    }

    func testNapMinutesExcludeTheLongestBlock() {
        let blocks: [(start: Int, end: Int, efficiency: Double?)] = [
            (0, 8 * 3600, 0.9), (50_000, 50_000 + 3600, 0.5), (60_000, 60_000 + 1800, nil),
        ]
        XCTAssertEqual(SleepScoring.napMinutes(blocks), 60 * 0.5 + 30 * 0.9, accuracy: 1e-9)
        XCTAssertEqual(SleepScoring.napMinutes([]), 0)
    }

    /// Finding 6: an unknown deep split is not zero deep.
    func testUnknownDeepIsNotScoredAsZeroDeep() throws {
        func day(deep: Double?) -> DailyMetric {
            DailyMetric(day: scored, totalSleepMin: 420, efficiency: 0.9, deepMin: deep, remMin: 100,
                        lightMin: nil, disturbances: nil, restingHr: nil, avgHrv: nil, recovery: nil,
                        strain: nil, exerciseCount: nil, spo2Pct: nil, skinTempDevC: nil, respRateBpm: nil)
        }
        let unknown = try XCTUnwrap(AnalyticsEngine.Rest.composite(daily: day(deep: nil)))
        let zero = try XCTUnwrap(AnalyticsEngine.Rest.composite(daily: day(deep: 0)))
        XCTAssertGreaterThan(unknown, zero)
    }

    func testRestorativeTargetsFollowAge() {
        XCTAssertEqual(AnalyticsEngine.Rest.restorativeTarget(age: 30), 0.40)
        XCTAssertEqual(AnalyticsEngine.Rest.restorativeTarget(age: 16), 0.45)
        XCTAssertEqual(AnalyticsEngine.Rest.deepShareTarget(age: 59), 0.13)
        XCTAssertEqual(AnalyticsEngine.Rest.deepShareTarget(age: 65), 0.10)
        // 11% deep is short of the under-60 target but meets the over-60 one.
        let args = (tst: 7.0 * 3600, deep: 0.11 * 7 * 3600)
        let young = AnalyticsEngine.Rest.composite(tstSeconds: args.tst, inBedSeconds: args.tst, efficiency: 1,
                                                   restorativeSeconds: 0.35 * args.tst, needHours: 7,
                                                   consistency: 1, deepSeconds: args.deep, age: 40)
        let older = AnalyticsEngine.Rest.composite(tstSeconds: args.tst, inBedSeconds: args.tst, efficiency: 1,
                                                   restorativeSeconds: 0.35 * args.tst, needHours: 7,
                                                   consistency: 1, deepSeconds: args.deep, age: 65)
        XCTAssertGreaterThan(older, young)
    }
}
