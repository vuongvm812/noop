import XCTest
@testable import StrandAnalytics

// Swift side of the AutoFactors oracle. `expected` is the stdout of this same driver compiled standalone
// with `swiftc -O` (AutoFactors, WhatMovesYouHighlights, EffectRanker and their deps); the Kotlin
// AutoFactorsOracleTest pins the identical text. Every fixture is synthetic (MINSTD generator).

private struct Gen {
    var s: Int
    mutating func next() -> Int { s = (s * 48271) % 2147483647; return s }
}

private func f9(_ v: Double) -> String { String(format: "%.9f", v) }

private func fixture(seed: Int, base: String, n: Int, offA: Int, offB: Int)
    -> (workouts: [AutoFactors.Workout], sleeps: [AutoFactors.Sleep], days: [AutoFactors.Day], today: String,
        lateWorkoutDays: Set<String>, lateBedDays: Set<String>) {
    var g = Gen(s: seed)
    let baseIdx = AutoFactors.dayIndex(base)!
    var ws: [AutoFactors.Workout] = []
    var ss: [AutoFactors.Sleep] = []
    var ds: [AutoFactors.Day] = []
    var lateW = Set<String>(), lateB = Set<String>()
    for i in 0..<n {
        let idx = baseIdx + i
        let off = i < n / 2 ? offA : offB
        let midnight = idx * 86_400 - off
        var bedMin = 22 * 60 + (g.next() % 90) - 30
        if g.next() % 6 == 0 { bedMin += 150; lateB.insert(AutoFactors.dayKey(idx)) }
        // A 00:40 onset after a 00:05–00:25 workout: the workout starts on the next calendar day but
        // belongs to this night, and is late.
        let afterMidnight = i % 23 == 5
        if afterMidnight { bedMin = 24 * 60 + 40; lateB.insert(AutoFactors.dayKey(idx)) }
        let onset = midnight + bedMin * 60
        let dur = 6 * 3600 + (g.next() % 120) * 60
        ss.append(AutoFactors.Sleep(startTs: onset, endTs: onset + dur, offsetSec: off))
        if g.next() % 7 == 0 {
            let nap = midnight + 14 * 3600
            ss.append(AutoFactors.Sleep(startTs: nap, endTs: nap + 40 * 60, offsetSec: off))
        }
        let w = g.next() % 4
        if w == 0 {
            // rest day
        } else if w == 1 {
            let end = onset - 600 - (g.next() % 90) * 60
            ws.append(AutoFactors.Workout(startTs: end - 2700, endTs: end, offsetSec: off))
            lateW.insert(AutoFactors.dayKey(idx))
        } else {
            let start = midnight + 7 * 3600 + (g.next() % 300) * 60
            ws.append(AutoFactors.Workout(startTs: start, endTs: start + 2700, offsetSec: off))
            if w == 3 {
                let s2 = midnight + 17 * 3600
                ws.append(AutoFactors.Workout(startTs: s2, endTs: s2 + 1800, offsetSec: off))
            }
        }
        if afterMidnight {
            let s0 = midnight + (24 * 60 + 5) * 60
            ws.append(AutoFactors.Workout(startTs: s0, endTs: s0 + 20 * 60, offsetSec: off))
            lateW.insert(AutoFactors.dayKey(idx))
        }
        // A workout still running at onset is neither late nor a control.
        if i % 19 == 7 {
            ws.append(AutoFactors.Workout(startTs: onset - 1200, endTs: onset + 600, offsetSec: off))
        }
        let effort: Double? = g.next() % 15 == 0 ? nil : Double(20 + g.next() % 60) + 0.25
        let steps: Int? = g.next() % 11 == 0 ? nil : 3000 + g.next() % 12000
        ds.append(AutoFactors.Day(day: AutoFactors.dayKey(idx), effort: effort, steps: steps))
    }
    return (ws, ss, ds, AutoFactors.dayKey(baseIdx + n - 1), lateW, lateB)
}

private func printFactors(_ tag: String, _ r: AutoFactors.Result, _ out: inout [String]) {
    for f in AutoFactors.Factor.allCases {
        let y = (r.behaviors[f.key] ?? []).sorted()
        let n = (r.controls[f.key] ?? []).sorted()
        out.append("\(tag) \(f.rawValue) yes=\(y.count) no=\(n.count) yesHead=\(y.prefix(3).joined(separator: ",")) noHead=\(n.prefix(3).joined(separator: ","))")
        out.append("\(tag) \(f.rawValue) yesAll=\(y.joined(separator: ","))")
    }
}

private func oracle() -> String {
    var out: [String] = []
    // 1. Day arithmetic.
    var dayLine: [String] = []
    for d in ["1970-01-01", "1969-12-31", "2000-02-29", "2024-03-01", "2026-11-26", "2100-03-01", "0001-01-01"] {
        let i = AutoFactors.dayIndex(d)!
        dayLine.append("\(d)=\(i)>\(AutoFactors.dayKey(i))")
    }
    for bad in ["2026-1-01", "2026-13-01", "abcd-ef-gh", "2026-+1-01", ""] {
        dayLine.append("\(bad)=\(AutoFactors.dayIndex(bad).map(String.init) ?? "nil")")
    }
    out.append("DAYS " + dayLine.joined(separator: " "))

    // 2. Night keys and onset minutes across midnight and offsets.
    var nightLine: [String] = []
    let base = AutoFactors.dayIndex("2026-03-28")! * 86_400
    for (local, off) in [(23 * 3600, 3600), (30 * 3600 + 1800, 3600), (21 * 3600 + 900, -18_000),
                         (25 * 3600, -18_000), (12 * 3600, 0), (11 * 3600 + 3540, 0), (35 * 3600 + 3599, 19_800)] {
        let s = AutoFactors.Sleep(startTs: base + local - off, endTs: base + local - off + 7 * 3600, offsetSec: off)
        nightLine.append("\(AutoFactors.dayKey(AutoFactors.nightIndex(s)))@\(AutoFactors.minutesAfterNoon(s))")
    }
    out.append("NIGHTS " + nightLine.joined(separator: " "))

    // 3. Percentiles.
    let pv: [Double] = [3, 1, 4, 1, 5, 9, 2, 6, 5, 3, 5].sorted()
    out.append("PCT " + [0.0, 0.25, 0.5, 0.75, 1.0, 0.1].map { f9(AutoFactors.percentile(pv, $0)) }.joined(separator: " ")
          + " one=" + f9(AutoFactors.percentile([7.5], 0.75)))

    // 4. Full factor sets: a DST switch (+1h → +2h) and a west-of-UTC user.
    let a = fixture(seed: 12345, base: "2026-01-15", n: 140, offA: 3600, offB: 7200)
    printFactors("EU", AutoFactors.factors(workouts: a.workouts, sleeps: a.sleeps, days: a.days, today: a.today), &out)
    let b = fixture(seed: 777, base: "2026-02-20", n: 120, offA: -18_000, offB: -14_400)
    printFactors("US", AutoFactors.factors(workouts: b.workouts, sleeps: b.sleeps, days: b.days, today: b.today), &out)
    let c = fixture(seed: 4242, base: "2026-05-01", n: 10, offA: 0, offB: 0)
    printFactors("THIN", AutoFactors.factors(workouts: c.workouts, sleeps: c.sleeps, days: c.days, today: c.today), &out)

    // 5. Auto + journal factors ranked in ONE family.
    let fa = AutoFactors.factors(workouts: a.workouts, sleeps: a.sleeps, days: a.days, today: a.today)
    var g = Gen(s: 99)
    var hrv: [String: Double] = [:], rest: [String: Double] = [:]
    var alcYes = Set<String>(), alcNo = Set<String>()
    let baseIdx = AutoFactors.dayIndex("2026-01-15")!
    for i in 0..<141 {
        let key = AutoFactors.dayKey(baseIdx + i)
        let prev = AutoFactors.dayKey(baseIdx + i - 1)
        var h = 60.0 + Double(g.next() % 100) / 10.0
        if a.lateWorkoutDays.contains(prev) { h -= 6 }
        var r = 80.0 + Double(g.next() % 80) / 10.0
        if a.lateBedDays.contains(prev) { r -= 7 }
        if alcYes.contains(prev) { h -= 3 }
        hrv[key] = h; rest[key] = r
        let j = g.next() % 5
        if j == 0 { alcYes.insert(key) } else if j < 4 { alcNo.insert(key) }
    }
    var behaviors = fa.behaviors, controls = fa.controls
    behaviors["Alcohol"] = alcYes; controls["Alcohol"] = alcNo
    let family = EffectRanker.rankFamilyExcludingSameDay(behaviors: behaviors, controls: controls, outcomes: ["HRV": hrv, "Rest": rest],
                                         noSameDay: Set(fa.behaviors.keys))
    for o in ["HRV", "Rest"] {
        for r in family[o] ?? [] {
            out.append("RANK \(o) \(r.behavior) lag=\(r.lag) d=\(f9(r.effect.cohensD)) p=\(f9(r.effect.pApprox)) q=\(f9(r.effect.pAdjusted)) sig=\(r.effect.significant) n=\(r.effect.nWith)/\(r.effect.nWithout) conf=\(r.confidence.rawValue)")
        }
    }
    // The journal row ranked alone has a smaller family, so its q-value differs from the shared one.
    let alone = EffectRanker.rankFamily(behaviors: ["Alcohol": alcYes], controls: ["Alcohol": alcNo],
                                        outcomes: ["HRV": hrv, "Rest": rest])
    for o in ["HRV", "Rest"] {
        for r in alone[o] ?? [] { out.append("ALONE \(o) \(r.behavior) lag=\(r.lag) q=\(f9(r.effect.pAdjusted)) sig=\(r.effect.significant)") }
    }
    let top = WhatMovesYouHighlights.top(family, outcomes: ["HRV", "Rest"])
    out.append("TOP " + top.map { "\($0.outcome):\($0.behavior):\($0.lag)" }.joined(separator: " "))
    out.append("TOP1 " + WhatMovesYouHighlights.top(family, outcomes: ["Rest", "HRV"], limit: 1).map { "\($0.outcome):\($0.behavior)" }.joined(separator: " "))
    return out.joined(separator: "\n")
}

final class AutoFactorsTests: XCTestCase {

    func testMatchesOracle() {
        let expected = Self.expected.components(separatedBy: "\n")
        let actual = oracle().components(separatedBy: "\n")
        for i in 0..<min(expected.count, actual.count) {
            XCTAssertEqual(actual[i], expected[i], "line \(i + 1)")
        }
        XCTAssertEqual(actual.count, expected.count)
    }

    func testFactorKeysRoundTrip() {
        for f in AutoFactors.Factor.allCases { XCTAssertEqual(AutoFactors.factorForKey(f.key), f) }
        XCTAssertNil(AutoFactors.factorForKey("Alcohol"))
        XCTAssertNil(AutoFactors.factorForKey("auto.unknown"))
    }

    /// Auto factors and journal habits share one Benjamini–Hochberg family: the same journal row gets a
    /// larger q-value when ranked beside the automatic factors than when ranked alone.
    func testAutoAndJournalShareOneFamily() {
        let lines = oracle().components(separatedBy: "\n")
        let shared = lines.first { $0.hasPrefix("RANK HRV Alcohol ") }
        let alone = lines.first { $0.hasPrefix("ALONE HRV Alcohol ") }
        XCTAssertNotNil(shared)
        XCTAssertNotNil(alone)
        XCTAssertTrue(shared!.contains("q=0.002973764"))
        XCTAssertTrue(alone!.contains("q=0.002099128"))
    }

    private static let expected = """
DAYS 1970-01-01=0>1970-01-01 1969-12-31=-1>1969-12-31 2000-02-29=11016>2000-02-29 2024-03-01=19783>2024-03-01 2026-11-26=20783>2026-11-26 2100-03-01=47541>2100-03-01 0001-01-01=-719162>0001-01-01 2026-1-01=nil 2026-13-01=nil abcd-ef-gh=nil 2026-+1-01=nil =nil
NIGHTS 2026-03-28@660 2026-03-28@1110 2026-03-28@555 2026-03-28@780 2026-03-28@0 2026-03-27@1439 2026-03-28@1439
PCT 1.000000000 2.500000000 4.000000000 5.000000000 9.000000000 1.000000000 one=7.500000000
EU lateWorkout yes=27 no=66 yesHead=2026-01-20,2026-02-12,2026-02-22 noHead=2026-01-15,2026-01-18,2026-01-22
EU lateWorkout yesAll=2026-01-20,2026-02-12,2026-02-22,2026-03-01,2026-03-04,2026-03-05,2026-03-07,2026-03-12,2026-03-18,2026-03-21,2026-03-22,2026-03-29,2026-03-30,2026-04-12,2026-04-15,2026-04-22,2026-05-03,2026-05-04,2026-05-06,2026-05-10,2026-05-14,2026-05-15,2026-05-17,2026-05-18,2026-05-26,2026-05-29,2026-05-31
EU hardEffort yes=38 no=63 yesHead=2026-01-18,2026-01-19,2026-01-23 noHead=2026-01-16,2026-01-17,2026-01-20
EU hardEffort yesAll=2026-01-18,2026-01-19,2026-01-23,2026-01-26,2026-01-31,2026-02-02,2026-02-03,2026-02-04,2026-02-05,2026-02-07,2026-02-13,2026-02-18,2026-02-19,2026-02-24,2026-02-26,2026-03-03,2026-03-08,2026-03-09,2026-03-10,2026-03-15,2026-03-26,2026-03-27,2026-03-31,2026-04-04,2026-04-06,2026-04-07,2026-04-08,2026-04-11,2026-04-12,2026-04-17,2026-04-23,2026-04-24,2026-05-05,2026-05-07,2026-05-09,2026-05-12,2026-05-14,2026-05-20
EU restDay yes=43 no=97 yesHead=2026-01-16,2026-01-17,2026-01-19 noHead=2026-01-15,2026-01-18,2026-01-20
EU restDay yesAll=2026-01-16,2026-01-17,2026-01-19,2026-01-27,2026-01-30,2026-01-31,2026-02-03,2026-02-05,2026-02-08,2026-02-09,2026-02-14,2026-02-15,2026-02-16,2026-02-18,2026-02-19,2026-02-20,2026-02-23,2026-02-24,2026-02-25,2026-02-26,2026-03-10,2026-03-15,2026-04-03,2026-04-04,2026-04-06,2026-04-09,2026-04-13,2026-04-16,2026-04-18,2026-04-19,2026-04-20,2026-04-24,2026-04-26,2026-04-28,2026-04-30,2026-05-05,2026-05-21,2026-05-23,2026-05-24,2026-05-25,2026-05-27,2026-05-30,2026-06-03
EU lateBedtime yes=28 no=69 yesHead=2026-01-19,2026-01-20,2026-01-25 noHead=2026-01-16,2026-01-18,2026-01-21
EU lateBedtime yesAll=2026-01-19,2026-01-20,2026-01-25,2026-02-03,2026-02-05,2026-02-12,2026-02-13,2026-02-15,2026-02-20,2026-03-01,2026-03-07,2026-03-11,2026-03-12,2026-03-21,2026-03-22,2026-03-25,2026-03-27,2026-03-30,2026-04-06,2026-04-22,2026-04-30,2026-05-01,2026-05-03,2026-05-07,2026-05-09,2026-05-15,2026-05-20,2026-05-31
EU irregularBedtime yes=37 no=63 yesHead=2026-01-19,2026-01-20,2026-01-25 noHead=2026-01-23,2026-01-24,2026-01-26
EU irregularBedtime yesAll=2026-01-19,2026-01-20,2026-01-25,2026-02-01,2026-02-02,2026-02-03,2026-02-05,2026-02-10,2026-02-12,2026-02-13,2026-02-15,2026-02-20,2026-03-01,2026-03-07,2026-03-11,2026-03-12,2026-03-15,2026-03-16,2026-03-19,2026-03-21,2026-03-22,2026-03-25,2026-03-27,2026-03-28,2026-03-29,2026-03-30,2026-03-31,2026-04-06,2026-04-22,2026-04-30,2026-05-01,2026-05-03,2026-05-07,2026-05-09,2026-05-15,2026-05-20,2026-05-31
EU lowSteps yes=32 no=61 yesHead=2026-01-19,2026-01-21,2026-01-23 noHead=2026-01-16,2026-01-20,2026-01-25
EU lowSteps yesAll=2026-01-19,2026-01-21,2026-01-23,2026-01-29,2026-02-05,2026-02-06,2026-02-10,2026-02-11,2026-02-14,2026-02-24,2026-02-28,2026-03-06,2026-03-09,2026-03-10,2026-03-11,2026-03-22,2026-03-23,2026-03-24,2026-03-27,2026-04-02,2026-04-08,2026-04-12,2026-04-17,2026-04-25,2026-04-30,2026-05-05,2026-05-08,2026-05-12,2026-05-20,2026-05-27,2026-05-29,2026-05-31
EU highSteps yes=38 no=61 yesHead=2026-01-15,2026-01-17,2026-01-18 noHead=2026-01-16,2026-01-20,2026-01-25
EU highSteps yesAll=2026-01-15,2026-01-17,2026-01-18,2026-01-24,2026-01-26,2026-01-30,2026-02-01,2026-02-02,2026-02-07,2026-02-08,2026-02-15,2026-02-16,2026-02-21,2026-02-25,2026-02-26,2026-03-01,2026-03-05,2026-03-07,2026-03-08,2026-03-16,2026-03-26,2026-03-29,2026-04-04,2026-04-05,2026-04-07,2026-04-16,2026-04-18,2026-04-20,2026-04-21,2026-05-02,2026-05-03,2026-05-09,2026-05-11,2026-05-17,2026-05-19,2026-05-24,2026-06-01,2026-06-02
US lateWorkout yes=35 no=58 yesHead=2026-02-20,2026-02-24,2026-02-25 noHead=2026-02-22,2026-02-23,2026-02-27
US lateWorkout yesAll=2026-02-20,2026-02-24,2026-02-25,2026-02-26,2026-02-28,2026-03-05,2026-03-06,2026-03-07,2026-03-10,2026-03-12,2026-03-16,2026-03-20,2026-03-21,2026-03-28,2026-04-02,2026-04-12,2026-04-15,2026-04-20,2026-04-21,2026-04-25,2026-04-28,2026-04-29,2026-05-05,2026-05-07,2026-05-08,2026-05-13,2026-05-18,2026-05-19,2026-05-28,2026-05-31,2026-06-02,2026-06-06,2026-06-08,2026-06-12,2026-06-17
US hardEffort yes=29 no=55 yesHead=2026-02-23,2026-02-25,2026-03-01 noHead=2026-02-21,2026-02-24,2026-02-26
US hardEffort yesAll=2026-02-23,2026-02-25,2026-03-01,2026-03-05,2026-03-12,2026-03-16,2026-03-20,2026-03-26,2026-04-02,2026-04-08,2026-04-11,2026-04-12,2026-04-13,2026-04-14,2026-04-24,2026-04-25,2026-04-28,2026-05-04,2026-05-07,2026-05-09,2026-05-14,2026-05-17,2026-06-03,2026-06-04,2026-06-10,2026-06-12,2026-06-15,2026-06-16,2026-06-17
US restDay yes=24 no=96 yesHead=2026-02-21,2026-02-25,2026-03-02 noHead=2026-02-20,2026-02-22,2026-02-23
US restDay yesAll=2026-02-21,2026-02-25,2026-03-02,2026-03-03,2026-03-14,2026-03-15,2026-03-24,2026-03-26,2026-03-27,2026-03-30,2026-04-04,2026-04-08,2026-04-11,2026-04-17,2026-04-18,2026-04-26,2026-04-27,2026-05-10,2026-05-11,2026-05-16,2026-05-21,2026-05-22,2026-05-30,2026-06-07
US lateBedtime yes=26 no=68 yesHead=2026-02-20,2026-02-21,2026-02-25 noHead=2026-02-22,2026-02-24,2026-03-01
US lateBedtime yesAll=2026-02-20,2026-02-21,2026-02-25,2026-02-26,2026-03-09,2026-03-10,2026-03-19,2026-03-20,2026-03-25,2026-03-28,2026-03-29,2026-04-01,2026-04-09,2026-04-12,2026-04-15,2026-04-24,2026-04-30,2026-05-02,2026-05-05,2026-05-09,2026-05-19,2026-05-21,2026-05-28,2026-06-06,2026-06-09,2026-06-10
US irregularBedtime yes=30 no=55 yesHead=2026-02-24,2026-02-25,2026-02-27 noHead=2026-03-01,2026-03-02,2026-03-03
US irregularBedtime yesAll=2026-02-24,2026-02-25,2026-02-27,2026-03-09,2026-03-10,2026-03-14,2026-03-19,2026-03-20,2026-03-23,2026-03-25,2026-03-26,2026-03-28,2026-03-29,2026-04-01,2026-04-09,2026-04-12,2026-04-15,2026-04-23,2026-04-24,2026-04-30,2026-05-02,2026-05-05,2026-05-09,2026-05-13,2026-05-19,2026-05-21,2026-05-28,2026-06-06,2026-06-09,2026-06-10
US lowSteps yes=29 no=58 yesHead=2026-02-21,2026-02-22,2026-02-23 noHead=2026-02-20,2026-02-24,2026-02-26
US lowSteps yesAll=2026-02-21,2026-02-22,2026-02-23,2026-02-25,2026-03-01,2026-03-07,2026-03-13,2026-03-16,2026-03-22,2026-03-24,2026-04-06,2026-04-09,2026-04-10,2026-04-14,2026-04-15,2026-04-19,2026-04-23,2026-04-25,2026-04-26,2026-04-27,2026-04-28,2026-05-04,2026-05-20,2026-05-23,2026-05-30,2026-06-03,2026-06-11,2026-06-12,2026-06-18
US highSteps yes=25 no=58 yesHead=2026-02-28,2026-03-05,2026-03-19 noHead=2026-02-20,2026-02-24,2026-02-26
US highSteps yesAll=2026-02-28,2026-03-05,2026-03-19,2026-03-21,2026-03-26,2026-03-29,2026-03-30,2026-03-31,2026-04-03,2026-04-04,2026-04-07,2026-04-12,2026-04-18,2026-04-20,2026-04-29,2026-05-03,2026-05-07,2026-05-12,2026-05-18,2026-05-21,2026-05-24,2026-05-25,2026-06-04,2026-06-13,2026-06-16
THIN lateWorkout yes=3 no=4 yesHead=2026-05-05,2026-05-06,2026-05-07 noHead=2026-05-03,2026-05-04,2026-05-09
THIN lateWorkout yesAll=2026-05-05,2026-05-06,2026-05-07
THIN hardEffort yes=0 no=0 yesHead= noHead=
THIN hardEffort yesAll=
THIN restDay yes=0 no=0 yesHead= noHead=
THIN restDay yesAll=
THIN lateBedtime yes=0 no=0 yesHead= noHead=
THIN lateBedtime yesAll=
THIN irregularBedtime yes=1 no=3 yesHead=2026-05-06 noHead=2026-05-05,2026-05-07,2026-05-09
THIN irregularBedtime yesAll=2026-05-06
THIN lowSteps yes=0 no=0 yesHead= noHead=
THIN lowSteps yesAll=
THIN highSteps yes=0 no=0 yesHead= noHead=
THIN highSteps yesAll=
RANK HRV auto.lateWorkout lag=1 d=-1.732617581 p=0.000000000 q=0.000000005 sig=true n=27/66 conf=solid
RANK HRV Alcohol lag=1 d=-0.797612665 p=0.000349855 q=0.002973764 sig=true n=29/90 conf=solid
RANK HRV auto.restDay lag=1 d=0.396179234 p=0.022721816 q=0.113027583 sig=false n=43/97 conf=solid
RANK HRV auto.lateBedtime lag=1 d=-0.365042644 p=0.133678380 q=0.454506493 sig=false n=28/69 conf=solid
RANK HRV auto.hardEffort lag=2 d=-0.264008265 p=0.195576201 q=0.554132568 sig=false n=38/63 conf=solid
RANK HRV auto.lowSteps lag=2 d=0.202355251 p=0.339382319 q=0.655204249 sig=false n=32/60 conf=solid
RANK HRV auto.irregularBedtime lag=1 d=-0.089421360 p=0.677581028 q=0.942287828 sig=false n=37/63 conf=solid
RANK HRV auto.highSteps lag=1 d=-0.061876649 p=0.760505480 q=0.942287828 sig=false n=38/61 conf=solid
RANK Rest auto.lateBedtime lag=1 d=-2.791760242 p=0.000000000 q=0.000000000 sig=true n=28/69 conf=solid
RANK Rest auto.irregularBedtime lag=1 d=-1.698483656 p=0.000000000 q=0.000000005 sig=true n=37/63 conf=solid
RANK Rest auto.lateWorkout lag=1 d=-0.544237107 p=0.023270385 q=0.113027583 sig=false n=27/66 conf=solid
RANK Rest auto.lowSteps lag=2 d=-0.541766126 p=0.012651433 q=0.086029747 sig=false n=32/60 conf=solid
RANK Rest auto.hardEffort lag=2 d=-0.423739509 p=0.048391600 q=0.205664302 sig=false n=38/63 conf=solid
RANK Rest Alcohol lag=1 d=0.376411922 p=0.058818984 q=0.222205052 sig=false n=29/90 conf=solid
RANK Rest auto.restDay lag=2 d=0.232801119 p=0.165686283 q=0.512121239 sig=false n=42/97 conf=solid
RANK Rest auto.highSteps lag=1 d=0.227458884 p=0.276683372 q=0.655204249 sig=false n=38/61 conf=solid
ALONE HRV Alcohol lag=1 q=0.002099128 sig=true
ALONE Rest Alcohol lag=1 q=0.176456953 sig=false
TOP Rest:auto.lateBedtime:1 HRV:auto.lateWorkout:1 Rest:auto.irregularBedtime:1
TOP1 Rest:auto.lateBedtime
"""
}
