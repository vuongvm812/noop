import XCTest
@testable import StrandAnalytics

// #55 byte-parity oracle for the sleep-scoring funnel and the Rest composite. EXPECTED is the verbatim
// stdout of the standalone oracle (the same generators, linked against this package), with every Double
// printed as its IEEE-754 bit pattern. The Kotlin twin (`SleepScoringOracleTest.kt`) pins the identical
// literals, so a change to either side's arithmetic fails on that side. The `rest.old` line records the
// pre-#55 Swift↔Kotlin Rest rounding mismatch over the grid (finding 7).

private func h(_ x: Double) -> String { String(x.bitPattern, radix: 16) }
private func ho(_ x: Double?) -> String { x.map(h) ?? "nil" }

// FNV-1a 64 over the 8 little-endian bytes of each value.
private struct FNV { var v: UInt64 = 0xcbf29ce484222325
    mutating func add(_ x: Double) { var b = x.bitPattern
        for _ in 0..<8 { v ^= b & 0xff; v = v &* 0x100000001b3; b >>= 8 } }
    mutating func add(_ i: Int) { add(Double(i)) }
}

// Deterministic LCG (31-bit), identical in Kotlin.
private struct LCG { var s: Int64
    mutating func next() -> Int64 { s = (s &* 1_103_515_245 &+ 12_345) & 0x7fff_ffff; return s }
    mutating func unit() -> Double { Double(next()) / 2_147_483_648.0 }
}

// OLD Swift arithmetic (pre-#55): 0–1 terms, weights .5/.2/.2/.1, ×10000 rounded.
private func oldSwift(_ tst: Double, _ eff: Double, _ restor: Double, _ need: Double, _ cons: Double?, _ deep: Double) -> Double {
    func c(_ x: Double) -> Double { max(0, min(1, x)) }
    let dur = c(tst / (max(need, 0.1) * 3600)); let e = c(eff)
    let df = 0.5 + 0.5 * c((deep / tst) / 0.13)
    let r = c((restor / tst) / 0.50) * df
    let w = 0.50 * dur + 0.20 * e + 0.20 * r + 0.10 * c(cons ?? 0.5)
    return (w * 10000.0).rounded() / 100.0
}
// OLD Kotlin arithmetic (pre-#55): 0–100 terms, (w×100).roundToInt() (= floor(x + 0.5)).
private func oldKotlin(_ tst: Double, _ eff: Double, _ deepS: Double, _ remS: Double, _ need: Double, _ cons: Double?) -> Double {
    let needH = max(need, 0.1)
    let dur = min(100.0, tst / 3600.0 / needH * 100.0)
    let e = max(0, min(100, eff * 100.0))
    let share = (deepS + remS) / tst
    let ad = max(0, min(1, (deepS / tst) / 0.13))
    let df = 0.5 + (1.0 - 0.5) * ad
    let r = min(100.0, share / 0.50 * 100.0) * df
    let cs = max(0, min(100, (cons ?? 0.5) * 100.0))
    let w = 0.50 * dur + 0.20 * e + 0.20 * r + 0.10 * cs
    return Double(Int((w * 100.0 + 0.5).rounded(.down))) / 100.0
}

final class SleepScoringOracleTests: XCTestCase {
    private func lines() -> [String] {
        var out: [String] = []
        // ---- 1. Rest rounding grid --------------------------------------------------------------------------
        let needs = [7.0, 8.0, 8.75]
        let conses: [Double?] = [nil, 0.0, 0.37, 0.81, 1.0]
        let ages: [Int?] = [nil, 16, 65]
        var oldTotal = 0, oldDiff = 0
        var fnv = FNV(); var newCount = 0
        for i in 0..<40 {
            let tst = Double(180 + 14 * i) * 60.0
            for j in 0..<9 {
                let eff = 0.60 + 0.05 * Double(j)
                for k in 0..<8 {
                    let deep = tst * (0.02 * Double(k))
                    for l in 0..<8 {
                        let rem = tst * (0.05 + 0.03 * Double(l))
                        for need in needs { for cons in conses {
                            let a = oldSwift(tst, eff, deep + rem, need, cons, deep)
                            let b = oldKotlin(tst, eff, deep, rem, need, cons)
                            oldTotal += 1; if a != b { oldDiff += 1 }
                            for age in ages {
                                let v = AnalyticsEngine.Rest.composite(tstSeconds: tst, inBedSeconds: tst / eff, efficiency: eff,
                                    restorativeSeconds: deep + rem, needHours: need, consistency: cons,
                                    deepSeconds: k == 0 ? nil : deep, age: age)
                                fnv.add(v); newCount += 1
                            }
                        } }
                    }
                }
            }
        }
        out.append("rest.old total=\(oldTotal) diff=\(oldDiff)")
        out.append("rest.new count=\(newCount) fnv=\(String(fnv.v, radix: 16))")
        // A few literal composites.
        out.append("rest.lit1 \(h(AnalyticsEngine.Rest.composite(tstSeconds: 25200, inBedSeconds: 28000, efficiency: 0.9, restorativeSeconds: 9000, needHours: 8.25, consistency: 0.73, deepSeconds: 3600, age: 35)))")
        out.append("rest.lit2 \(h(AnalyticsEngine.Rest.composite(tstSeconds: 21600, inBedSeconds: 24000, efficiency: 0.9, restorativeSeconds: 5400, needHours: 7.0, consistency: nil, deepSeconds: nil, age: 70)))")
        out.append("rest.lit3 \(h(AnalyticsEngine.Rest.composite(tstSeconds: 30000, inBedSeconds: 31000, efficiency: 0.97, restorativeSeconds: 14000, needHours: 9.5, consistency: 0.95, deepSeconds: 200, age: 15)))")
        for a in [nil, 0, 5, 12, 13, 17, 18, 59, 60, 90] as [Int?] {
            out.append("floor age=\(a.map(String.init) ?? "nil") \(h(AnalyticsEngine.Rest.populationNeedFloorHours(age: a))) restor=\(h(AnalyticsEngine.Rest.restorativeTarget(age: a))) deep=\(h(AnalyticsEngine.Rest.deepShareTarget(age: a)))")
        }

        // ---- 2. DayOrdinal ----------------------------------------------------------------------------------
        let lo = DayOrdinal.ordinal("1900-01-01")!, hi = DayOrdinal.ordinal("2100-12-31")!
        var roundTripOK = true
        for o in lo...hi { let k = DayOrdinal.key(o); if DayOrdinal.ordinal(k) != o { roundTripOK = false } }
        out.append("ord lo=\(lo) hi=\(hi) roundTrip=\(roundTripOK)")
        for k in ["1970-01-01", "2000-02-29", "2024-03-10", "2026-11-03", "1969-12-31", "2026-13-01", "2026-1-01", "abc"] {
            out.append("ord \(k) \(DayOrdinal.ordinal(k).map(String.init) ?? "nil")")
        }

        // ---- 3. Sleep Regularity ----------------------------------------------------------------------------
        typealias W = SleepRegularity.Window
        func regLines(_ name: String, _ ws: [W], _ days: [String]) {
            let r = SleepRegularity.results(windows: ws, days: days)
            for d in days {
                if let x = r[d] { out.append("reg \(name) \(d) score=\(h(x.score)) sri=\(ho(x.sri)) m=\(x.method.rawValue) n=\(x.samples)") }
                else { out.append("reg \(name) \(d) none") }
            }
        }
        let base = DayOrdinal.ordinal("2026-03-01")!
        func night(_ wakeOrd: Int, _ onsetHour: Double, _ hours: Double, _ off: Int) -> W {
            let start = (wakeOrd - 1) * 86_400 + Int(onsetHour * 3600) - off
            return W(start: start, end: start + Int(hours * 3600), offsetSec: off)
        }
        let probe = [3, 8, 15, 29, 30, 40].map { DayOrdinal.key(base + $0) }
        regLines("regular", (0..<35).map { night(base + $0, 23, 8, 0) }, probe)
        regLines("swing", (0..<35).map { night(base + $0, $0 % 2 == 0 ? 21 : 26, 7.5, 3600) }, probe)
        regLines("sparse", (0..<35).filter { $0 % 2 == 0 }.map { night(base + $0, 22.5 + Double($0 % 3) * 0.5, 7, -18000) }, probe)
        regLines("dst", (0..<35).map { night(base + $0, 23.25, 7.75, $0 < 17 ? 3600 : 7200) }, probe)
        var g = LCG(s: 42)
        var jit: [W] = []
        for i in 0..<40 where g.unit() > 0.15 {
            let onset = 21.5 + g.unit() * 4.0, len = 5.5 + g.unit() * 3.5
            jit.append(night(base + i, onset, len, -25200))
            if g.unit() > 0.8 { let s = (base + i) * 86_400 + 14 * 3600 + 25200; jit.append(W(start: s, end: s + 2400, offsetSec: -25200)) }
        }
        regLines("jitter", jit, probe)
        regLines("short", (0..<35).map { night(base + $0, 23, 2.5, 0) }, probe)

        // ---- 4. Sleep need funnel --------------------------------------------------------------------------
        var nights: [SleepNeed.Night] = []
        var g2 = LCG(s: 7)
        for i in 0..<60 {
            let r = g2.unit()
            if r < 0.1 { continue }   // a gap
            let main: Double? = r < 0.15 ? nil : 300 + g2.unit() * 260
            let nap = g2.unit() > 0.85 ? 20 + g2.unit() * 40 : 0
            let strain: Double? = g2.unit() > 0.2 ? g2.unit() * 100 : nil
            nights.append(.init(day: DayOrdinal.key(base + i), mainMin: main, napMin: nap, strain: strain))
        }
        let needDays = [0, 1, 6, 7, 8, 20, 45, 59, 60, 75].map { DayOrdinal.key(base + $0) }
        for age in [nil, 30, 15] as [Int?] {
            let c = SleepNeed.contexts(nights: nights, days: needDays, age: age)
            for d in needDays {
                guard let x = c[d] else { out.append("need age=\(age.map(String.init) ?? "nil") \(d) none"); continue }
                let after = SleepNeed.debtAfter(x, mainMin: 380, napMin: 25)
                out.append("need age=\(age.map(String.init) ?? "nil") \(d) base=\(h(x.baseHours)) strain=\(h(x.strainHours)) debt=\(h(x.debtMin)) total=\(h(x.totalHours)) after=\(h(after))")
            }
        }
        out.append("need empty \(SleepNeed.contexts(nights: [], days: ["2026-03-05", "bad"], age: 10).map { "\($0.key):\(h($0.value.baseHours))" }.sorted().joined(separator: ","))")
        for s in [nil, -5, 0, 49.9, 50, 62.5, 99, 100, 140, Double.nan] as [Double?] { out.append("strainHours \(ho(s)) \(h(SleepNeed.strainHours(s)))") }

        // joined contexts
        let joined = SleepScoring.contexts(nights: nights, windows: jit, days: needDays, age: 64)
        for d in needDays { if let x = joined[d] { out.append("ctx \(d) need=\(h(x.rest.needHours)) cons=\(ho(x.rest.consistency)) age=\(x.rest.age!)") } }

        // ---- 5. SleepDebt ledger / series -----------------------------------------------------------------
        var series: [(day: String, totalSleepMin: Double?)] = []
        var g3 = LCG(s: 99)
        for i in 0..<30 {
            let r = g3.unit()
            if r < 0.12 { continue }
            series.append((DayOrdinal.key(base + i), r < 0.2 ? nil : 280 + g3.unit() * 260))
        }
        for (need, win) in [(8.0, 14), (7.25, 7), (9.0, 1)] {
            let l = SleepDebt.ledger(series: series, needHours: need, window: win)
            out.append("ledger need=\(h(need)) win=\(win) bal=\(h(l.balanceMin)) n=\(l.nightCount) days=\(l.nights.map(\.day).joined(separator: ",")) deltas=\(l.nights.map { h($0.deltaMin) }.joined(separator: ","))")
        }
        let ser = SleepDebt.debtSeries(series: series, needHours: 8, importedDebtMin: [DayOrdinal.key(base + 10): 42.5])
        out.append("series \(ser.map { "\($0.day)=\(h($0.value))" }.joined(separator: ","))")

        // ---- 6. Nap minutes -------------------------------------------------------------------------------
        out.append("nap a \(h(SleepScoring.napMinutes([(0, 28800, 0.9), (40000, 42400, 0.8), (50000, 51800, nil)])))")
        out.append("nap b \(h(SleepScoring.napMinutes([(0, 3600, 1.2), (5000, 8600, 0.5)])))")
        out.append("nap c \(h(SleepScoring.napMinutes([(10, 5, 0.9)])))")
        out.append("nap d \(h(SleepScoring.napMinutes([(0, 1000, 0.9), (2000, 3000, 0.7)])))")
        return out
    }

    func testSwiftMatchesPinnedOracleBitForBit() {
        XCTAssertEqual(lines(), Self.expected.split(separator: "\n").map(String.init))
    }

    private static let expected = """
rest.old total=345600 diff=577
rest.new count=1036800 fnv=711469746a192d2a
rest.lit1 40553147ae147ae1
rest.lit2 40536eb851eb851f
rest.lit3 405558f5c28f5c29
floor age=nil 401c000000000000 restor=3fd999999999999a deep=3fc0a3d70a3d70a4
floor age=0 401c000000000000 restor=3fd999999999999a deep=3fc0a3d70a3d70a4
floor age=5 4022000000000000 restor=3fdccccccccccccd deep=3fc0a3d70a3d70a4
floor age=12 4022000000000000 restor=3fdccccccccccccd deep=3fc0a3d70a3d70a4
floor age=13 4020000000000000 restor=3fdccccccccccccd deep=3fc0a3d70a3d70a4
floor age=17 4020000000000000 restor=3fdccccccccccccd deep=3fc0a3d70a3d70a4
floor age=18 401c000000000000 restor=3fd999999999999a deep=3fc0a3d70a3d70a4
floor age=59 401c000000000000 restor=3fd999999999999a deep=3fc0a3d70a3d70a4
floor age=60 401c000000000000 restor=3fd999999999999a deep=3fb999999999999a
floor age=90 401c000000000000 restor=3fd999999999999a deep=3fb999999999999a
ord lo=-25567 hi=47846 roundTrip=true
ord 1970-01-01 0
ord 2000-02-29 11016
ord 2024-03-10 19792
ord 2026-11-03 20760
ord 1969-12-31 -1
ord 2026-13-01 nil
ord 2026-1-01 nil
ord abc nil
reg regular 2026-03-04 score=3ff0000000000000 sri=nil m=midsleepSD n=3
reg regular 2026-03-09 score=3ff0000000000000 sri=4059000000000000 m=sri n=7
reg regular 2026-03-16 score=3ff0000000000000 sri=4059000000000000 m=sri n=14
reg regular 2026-03-30 score=3ff0000000000000 sri=4059000000000000 m=sri n=27
reg regular 2026-03-31 score=3ff0000000000000 sri=4059000000000000 m=sri n=27
reg regular 2026-04-10 score=3ff0000000000000 sri=4059000000000000 m=sri n=22
reg swing 2026-03-04 score=0 sri=nil m=midsleepSD n=3
reg swing 2026-03-09 score=3fc5555555555557 sri=4030aaaaaaaaaaac m=sri n=7
reg swing 2026-03-16 score=3fc5555555555557 sri=4030aaaaaaaaaaac m=sri n=14
reg swing 2026-03-30 score=3fc5555555555557 sri=4030aaaaaaaaaaac m=sri n=27
reg swing 2026-03-31 score=3fc5555555555557 sri=4030aaaaaaaaaaac m=sri n=27
reg swing 2026-04-10 score=3fc5555555555557 sri=4030aaaaaaaaaaac m=sri n=22
reg sparse 2026-03-04 none
reg sparse 2026-03-09 score=3fe95de35b00a433 sri=nil m=midsleepSD n=4
reg sparse 2026-03-16 score=3fe9126145e9ecd5 sri=nil m=midsleepSD n=8
reg sparse 2026-03-30 score=3fe99c79dab771c4 sri=nil m=midsleepSD n=14
reg sparse 2026-03-31 score=3fe99c79dab771c4 sri=nil m=midsleepSD n=14
reg sparse 2026-04-10 score=3fe977d0a3fcf4f0 sri=nil m=midsleepSD n=12
reg dst 2026-03-04 score=3ff0000000000000 sri=nil m=midsleepSD n=3
reg dst 2026-03-09 score=3ff0000000000000 sri=4059000000000000 m=sri n=7
reg dst 2026-03-16 score=3ff0000000000000 sri=4059000000000000 m=sri n=14
reg dst 2026-03-30 score=3ff0000000000000 sri=4059000000000000 m=sri n=27
reg dst 2026-03-31 score=3ff0000000000000 sri=4059000000000000 m=sri n=27
reg dst 2026-04-10 score=3ff0000000000000 sri=4059000000000000 m=sri n=22
reg jitter 2026-03-04 score=3fdf2736ceef1f36 sri=nil m=midsleepSD n=3
reg jitter 2026-03-09 score=3fe8684bda12f685 sri=4053117b425ed098 m=sri n=6
reg jitter 2026-03-16 score=3fe74fa4fa4fa4fb sri=40523638e38e38e4 m=sri n=10
reg jitter 2026-03-30 score=3fe5e38e38e38e38 sri=405119c71c71c71c m=sri n=20
reg jitter 2026-03-31 score=3fe6693e93e93e94 sri=40518238e38e38e4 m=sri n=20
reg jitter 2026-04-10 score=3fe4a57eb50295fb sri=4050214afd6a052c m=sri n=22
reg short 2026-03-04 none
reg short 2026-03-09 none
reg short 2026-03-16 none
reg short 2026-03-30 none
reg short 2026-03-31 none
reg short 2026-04-10 none
need age=nil 2026-03-01 base=4020000000000000 strain=0 debt=0 total=4020000000000000 after=4044a00000000000
need age=nil 2026-03-02 base=4020000000000000 strain=3fdf89c850000000 debt=404c1a41ab0d0001 total=4022dbec3cd63333 after=40561b2e7af39334
need age=nil 2026-03-07 base=4020000000000000 strain=3fddcbf7ed000000 debt=404ada882f460001 total=4022b8ad17e42222 after=405589ca028d0ccd
need age=nil 2026-03-08 base=4020000000000000 strain=0 debt=403c5e0efb043334 total=4020f21190e68a3d after=404c6d10eb6df47b
need age=nil 2026-03-09 base=4020000000000000 strain=3fc113df00000000 debt=4057c047c4ad7a3e total=40236f03b85b65a2 after=405879ef5879033d
need age=nil 2026-03-21 base=4020a9a90af1aaaa strain=0 debt=0 total=4020a9a90af1aaaa after=404a17b29a49bffc
need age=nil 2026-04-15 base=402064beede8aaaa strain=0 debt=4050482bb4d1d00a total=402290807d7c1bbc after=4054e412059ff26b
need age=nil 2026-04-29 base=40207151da805555 strain=0 debt=4065a0646e9d65c4 total=402635b0e6ee929a after=4061f6bcdc4c0e5d
need age=nil 2026-04-30 base=40207151da805555 strain=0 debt=4057ca081346bcbf total=40239d52ee235d6e after=405938f61651e166
need age=nil 2026-05-15 base=40207151da805555 strain=0 debt=0 total=40207151da805555 after=404846e34aa2bffc
need age=30 2026-03-01 base=4020000000000000 strain=0 debt=0 total=4020000000000000 after=4044a00000000000
need age=30 2026-03-02 base=4020000000000000 strain=3fdf89c850000000 debt=404c1a41ab0d0001 total=4022dbec3cd63333 after=40561b2e7af39334
need age=30 2026-03-07 base=4020000000000000 strain=3fddcbf7ed000000 debt=404ada882f460001 total=4022b8ad17e42222 after=405589ca028d0ccd
need age=30 2026-03-08 base=4020000000000000 strain=0 debt=403c5e0efb043334 total=4020f21190e68a3d after=404c6d10eb6df47b
need age=30 2026-03-09 base=4020000000000000 strain=3fc113df00000000 debt=4057c047c4ad7a3e total=40236f03b85b65a2 after=405879ef5879033d
need age=30 2026-03-21 base=4020a9a90af1aaaa strain=0 debt=0 total=4020a9a90af1aaaa after=404a17b29a49bffc
need age=30 2026-04-15 base=402064beede8aaaa strain=0 debt=4050482bb4d1d00a total=402290807d7c1bbc after=4054e412059ff26b
need age=30 2026-04-29 base=40207151da805555 strain=0 debt=4065a0646e9d65c4 total=402635b0e6ee929a after=4061f6bcdc4c0e5d
need age=30 2026-04-30 base=40207151da805555 strain=0 debt=4057ca081346bcbf total=40239d52ee235d6e after=405938f61651e166
need age=30 2026-05-15 base=40207151da805555 strain=0 debt=0 total=40207151da805555 after=404846e34aa2bffc
need age=15 2026-03-01 base=4020000000000000 strain=0 debt=0 total=4020000000000000 after=4044a00000000000
need age=15 2026-03-02 base=4020000000000000 strain=3fdf89c850000000 debt=404c1a41ab0d0001 total=4022dbec3cd63333 after=40561b2e7af39334
need age=15 2026-03-07 base=4020000000000000 strain=3fddcbf7ed000000 debt=404ada882f460001 total=4022b8ad17e42222 after=405589ca028d0ccd
need age=15 2026-03-08 base=4020000000000000 strain=0 debt=403c5e0efb043334 total=4020f21190e68a3d after=404c6d10eb6df47b
need age=15 2026-03-09 base=4020000000000000 strain=3fc113df00000000 debt=4057c047c4ad7a3e total=40236f03b85b65a2 after=405879ef5879033d
need age=15 2026-03-21 base=4020a9a90af1aaaa strain=0 debt=0 total=4020a9a90af1aaaa after=404a17b29a49bffc
need age=15 2026-04-15 base=402064beede8aaaa strain=0 debt=4050482bb4d1d00a total=402290807d7c1bbc after=4054e412059ff26b
need age=15 2026-04-29 base=40207151da805555 strain=0 debt=4065a0646e9d65c4 total=402635b0e6ee929a after=4061f6bcdc4c0e5d
need age=15 2026-04-30 base=40207151da805555 strain=0 debt=4057ca081346bcbf total=40239d52ee235d6e after=405938f61651e166
need age=15 2026-05-15 base=40207151da805555 strain=0 debt=0 total=40207151da805555 after=404846e34aa2bffc
need empty 2026-03-05:4022000000000000
strainHours nil 0
strainHours c014000000000000 0
strainHours 0 0
strainHours 4048f33333333333 0
strainHours 4049000000000000 0
strainHours 404f400000000000 3fc0000000000000
strainHours 4058c00000000000 3fdf5c28f5c28f5c
strainHours 4059000000000000 3fe0000000000000
strainHours 4061800000000000 3fe0000000000000
strainHours 7ff8000000000000 0
ctx 2026-03-01 need=4020000000000000 cons=nil age=64
ctx 2026-03-02 need=4022dbec3cd63333 cons=nil age=64
ctx 2026-03-07 need=4022b8ad17e42222 cons=3fe7777777777778 age=64
ctx 2026-03-08 need=4020f21190e68a3d cons=3fe8684bda12f685 age=64
ctx 2026-03-09 need=40236f03b85b65a2 cons=3fe8684bda12f685 age=64
ctx 2026-03-21 need=4020a9a90af1aaaa cons=3fe679e79e79e79f age=64
ctx 2026-04-15 need=402290807d7c1bbc cons=3fe40b60b60b60b6 age=64
ctx 2026-04-29 need=402635b0e6ee929a cons=3fe3955555555557 age=64
ctx 2026-04-30 need=40239d52ee235d6e cons=3fe3e79e79e79e7b age=64
ctx 2026-05-15 need=40207151da805555 cons=nil age=64
ledger need=4020000000000000 win=14 bal=c045266666666666 n=10 days=2026-03-17,2026-03-18,2026-03-21,2026-03-22,2026-03-24,2026-03-25,2026-03-26,2026-03-27,2026-03-29,2026-03-30 deltas=c0165bb3a8000000,c068e51c49420000,4044739e2f280000,4047e7163f600000,c022d288ca900000,4024bb05aad00000,4045a0f3e3cc0000,c0552d2800ae0000,404c5abbf8700000,c0533986710c0000
ledger need=401d000000000000 win=7 bal=c031800000000000 n=6 days=2026-03-24,2026-03-25,2026-03-26,2026-03-27,2026-03-29,2026-03-30 deltas=4041cb5dcd5c0000,404baec16ab40000,40561079f1e60000,c043da50015c0000,40596d5dfc380000,c03fe619c4300000
ledger need=4022000000000000 win=1 bal=c052d33333333333 n=1 days=2026-03-30 deltas=c0611cc338860000
series 2026-03-01=405499999999999a,2026-03-02=4046666666666666,2026-03-03=4054266666666666,2026-03-04=4059333333333333,2026-03-05=4061366666666666,2026-03-06=4056933333333333,2026-03-07=405ed9999999999a,2026-03-08=4056600000000000,2026-03-09=4054533333333333,2026-03-10=405799999999999a,2026-03-11=4045400000000000,2026-03-12=0,2026-03-13=404df33333333333,2026-03-14=403c4ccccccccccd,2026-03-15=4046cccccccccccd,2026-03-16=4042cccccccccccd,2026-03-17=4037cccccccccccd,2026-03-18=405ea66666666666,2026-03-21=0,2026-03-22=0,2026-03-24=0,2026-03-25=0,2026-03-26=0,2026-03-27=40474ccccccccccd,2026-03-29=0,2026-03-30=4045266666666666
nap a 404d800000000000
nap b 403e000000000000
nap c 0
nap d 4027555555555555
"""
}
