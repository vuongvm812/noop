import XCTest
@testable import StrandAnalytics

/// Behavioral validation of SL1/T1: score the SAME night for different sleeper HISTORIES and show
/// how Rest moves once real regularity + personalized need replace neutral-0.5 / fixed-8h. Prints an
/// old→new table for eyeballing magnitude/direction, and pins the load-bearing directions.
final class RestWiringImpactTests: XCTestCase {
    private typealias Rest = AnalyticsEngine.Rest

    // One fixed scored night so only (need, consistency) vary: 7h TST, 90% eff, 1h deep, 1.5h REM.
    private let tst = 7.0 * 3600, inbed = 7.0 * 3600 / 0.9, eff = 0.90
    private let deep = 1.0 * 3600, rem = 1.5 * 3600

    private func restOld() -> Double {
        Rest.composite(tstSeconds: tst, inBedSeconds: inbed, efficiency: eff,
                       restorativeSeconds: deep + rem, needHours: Rest.defaultNeedHours,
                       consistency: nil, deepSeconds: deep)   // nil → neutral 0.5
    }
    /// Score the fixed night on 2026-06-15 against a 14-night history ending the day before. Each
    /// history night starts at `onsetHour` (local, UTC here) of the previous evening and lasts `hours`.
    private func restNew(history: [Double], onsets: [Double]? = nil, age: Int?)
        -> (rest: Double, need: Double, cons: Double) {
        let scored = "2026-06-15"
        let d = DayOrdinal.ordinal(scored)!
        var nights: [SleepNeed.Night] = []
        var windows: [SleepRegularity.Window] = []
        for (i, h) in history.enumerated() {
            let wakeOrd = d - history.count + i
            let onsetHour = onsets?[i] ?? 23.0
            let start = (wakeOrd - 1) * 86_400 + Int(onsetHour * 3600)
            windows.append(.init(start: start, end: start + Int(h * 3600), offsetSec: 0))
            nights.append(.init(day: DayOrdinal.key(wakeOrd), mainMin: h * 60))
        }
        let ctx = SleepScoring.contexts(nights: nights, windows: windows, days: [scored], age: age)[scored]!
        let rest = Rest.composite(tstSeconds: tst, inBedSeconds: inbed, efficiency: eff,
                                  restorativeSeconds: deep + rem, needHours: ctx.rest.needHours,
                                  consistency: ctx.rest.consistency, deepSeconds: deep, age: age)
        return (rest, ctx.rest.needHours, ctx.rest.consistency ?? -1)
    }

    func testArchetypeImpactTable() {
        let regular = Array(repeating: 8.0, count: 14)
        let irregular = Array(repeating: 8.0, count: 14)
        let irregularOnsets = (0..<14).map { $0 % 2 == 0 ? 21.0 : 26.0 }   // same length, onset swings 5 h
        let chronicShort = Array(repeating: 5.5, count: 14)
        let longSleeper = Array(repeating: 9.2, count: 14)

        let old = restOld()
        let reg = restNew(history: regular, age: 30)
        let irr = restNew(history: irregular, onsets: irregularOnsets, age: 30)
        let shortS = restNew(history: chronicShort, age: 30)
        let longS = restNew(history: longSleeper, age: 30)

        print(String(format: "cold start (neutral 0.5, need 8h):            Rest %5.1f", old))
        print(String(format: "regular good sleeper:  need %.1f cons %.2f  Rest %5.1f", reg.need, reg.cons, reg.rest))
        print(String(format: "irregular sleeper:     need %.1f cons %.2f  Rest %5.1f", irr.need, irr.cons, irr.rest))
        print(String(format: "chronic short (5.5h):  need %.1f cons %.2f  Rest %5.1f", shortS.need, shortS.cons, shortS.rest))
        print(String(format: "long sleeper (9.2h):   need %.1f cons %.2f  Rest %5.1f", longS.need, longS.cons, longS.rest))

        // Load-bearing directions (SL1): a REGULAR history lifts Rest above an IRREGULAR one for the
        // identical night — regularity now actually matters.
        XCTAssertGreaterThan(reg.rest, irr.rest)
        // T1: chronic-short base need is floored at the 7 h adult minimum, and the carried debt raises
        // the night's total need above it.
        XCTAssertGreaterThan(shortS.need, 7.0)
        // T1: a genuine long sleeper's need exceeds the old fixed 8h (more demanding duration term).
        XCTAssertGreaterThan(longS.need, 8.0)
    }
}
