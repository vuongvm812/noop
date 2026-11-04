import Foundation

// SleepScoring.swift — the ONE funnel for the per-night inputs Rest is scored against (#55).
//
// Before this file the same facts had several definitions: sleep need was the upper-quartile estimate
// for Rest, an age-less copy for the debt surfaces and max(7.5 h, mean) for the "hours vs needed" tile;
// "consistency" was 1 − CV of duration for Rest and Vitality but a bedtime-spread score on the Sleep tab.
// And the engine computed need and consistency ONCE from the latest history and applied them to every
// re-scored day, so an old night was scored against sleep that happened after it.
//
// This funnel replaces all of them:
//   - `SleepRegularity` — the Sleep Regularity Index (Phillips et al. 2017; Windred et al. 2024) over the
//     trailing 28 nights, from the stored sleep windows. Sparse history falls back to the SD of midsleep.
//   - `SleepNeed` — base need (upper quartile of the trailing 90 nights, floored at the AASM/SRS adult
//     minimum of 7 h), a strain adjustment and the running sleep debt, all from nights BEFORE the scored
//     day.
//   - `SleepScoring.contexts` — joins both into one `AnalyticsEngine.Rest.Context` per day.
//
// Every window ends the day before the scored day: no look-ahead. Pure and constant-explicit so the
// Kotlin twin (`com.noop.analytics.SleepScoring`) is byte-identical; verified by oracle.

/// Calendar-day arithmetic on "yyyy-MM-dd" keys, independent of any time zone or calendar object.
public enum DayOrdinal {
    /// Days since 1970-01-01 for a "yyyy-MM-dd" key (proleptic Gregorian), or nil when malformed.
    /// Kotlin twin: `DayOrdinal.ordinal`.
    public static func ordinal(_ day: String) -> Int? {
        let parts = day.split(separator: "-", omittingEmptySubsequences: false)
        guard parts.count == 3, parts[0].count == 4, parts[1].count == 2, parts[2].count == 2,
              let y = Int(parts[0]), let m = Int(parts[1]), let d = Int(parts[2]),
              (1...12).contains(m), (1...31).contains(d) else { return nil }
        // Hinnant's days_from_civil.
        let yy = m <= 2 ? y - 1 : y
        let era = (yy >= 0 ? yy : yy - 399) / 400
        let yoe = yy - era * 400
        let mp = (m + 9) % 12
        let doy = (153 * mp + 2) / 5 + d - 1
        let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }

    /// The "yyyy-MM-dd" key for a day ordinal (inverse of `ordinal`).
    /// Kotlin twin: `DayOrdinal.key`.
    public static func key(_ ordinal: Int) -> String {
        // Hinnant's civil_from_days.
        let z = ordinal + 719_468
        let era = (z >= 0 ? z : z - 146_096) / 146_097
        let doe = z - era * 146_097
        let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        let doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        let mp = (5 * doy + 2) / 153
        let d = doy - (153 * mp + 2) / 5 + 1
        let m = mp < 10 ? mp + 3 : mp - 9
        let y = yoe + era * 400 + (m <= 2 ? 1 : 0)
        return String(format: "%04d-%02d-%02d", y, m, d)
    }
}

// MARK: - Sleep Regularity Index

public enum SleepRegularity {
    /// One asleep window with the UTC offset (seconds east) that was in force that night. The offset is
    /// per window so a night recorded before a DST change or a trip keeps its own local clock.
    public struct Window: Equatable, Sendable {
        public let start: Int
        public let end: Int
        public let offsetSec: Int
        public init(start: Int, end: Int, offsetSec: Int) {
            self.start = start; self.end = end; self.offsetSec = offsetSec
        }
    }

    public enum Method: String, Sendable { case sri, midsleepSD }

    public struct Result: Equatable, Sendable {
        /// Regularity in [0, 1]: SRI ÷ 100 clamped, or the midsleep fallback.
        public let score: Double
        /// The raw SRI (−100…100) when that method ran; nil on the fallback.
        public let sri: Double?
        public let method: Method
        /// Consecutive-night pairs (SRI) or nights (fallback) behind the value.
        public let samples: Int
    }

    /// Epoch length: 5 min, 288 epochs per 24 h.
    public static let epochSec = 300
    public static let epochsPerDay = 288
    /// Trailing nights the index is computed over.
    public static let windowNights = 28
    /// Minimum consecutive-night pairs before the SRI is trusted (about a week).
    public static let minPairs = 5
    /// A night counts as recorded once it holds at least this many asleep epochs (3 h). A stray nap on a
    /// night the strap was not worn would otherwise read as a whole night awake.
    public static let minCoveredEpochs = 36
    /// Fallback: minimum recorded nights for the SD-of-midsleep score.
    public static let minMidsleepNights = 3
    /// Fallback: midsleep SD (minutes) that maps to a score of 0. A heuristic scale, not a published one.
    public static let midsleepSDZeroMin = 120.0

    /// Grid k spans local noon of day k to local noon of day k + 1, i.e. the night that wakes on day k + 1.
    static let noonOffsetSec = 43_200

    /// Kotlin twin: `SleepRegularity.floorDiv`.
    static func floorDiv(_ a: Int, _ b: Int) -> Int {
        let q = a / b
        return (a % b != 0 && (a < 0) != (b < 0)) ? q - 1 : q
    }

    /// Per-grid asleep raster plus the per-grid figures every trailing window sums.
    struct Raster {
        var grids: [Int: [Bool]] = [:]
        var asleepCount: [Int: Int] = [:]
    }

    /// Kotlin twin: `SleepRegularity.raster`.
    static func raster(_ windows: [Window]) -> Raster {
        var r = Raster()
        // An epoch is asleep when its midpoint falls inside a window, on the window's own local clock.
        let half = epochSec / 2
        for w in windows {
            let ls = w.start + w.offsetSec, le = w.end + w.offsetSec
            guard le > ls, le - ls <= 36 * 3600 else { continue }
            // First epoch g with noon + g*epoch + half >= ls; last with that midpoint < le.
            let first = -floorDiv(-(ls - noonOffsetSec - half), epochSec)
            let lastExclusive = -floorDiv(-(le - noonOffsetSec - half), epochSec)
            guard lastExclusive > first else { continue }
            for g in first..<lastExclusive {
                let k = floorDiv(g, epochsPerDay)
                let e = g - k * epochsPerDay
                var grid = r.grids[k] ?? [Bool](repeating: false, count: epochsPerDay)
                if !grid[e] {
                    grid[e] = true
                    r.asleepCount[k, default: 0] += 1
                }
                r.grids[k] = grid
            }
        }
        return r
    }

    /// Midsleep (minutes after local noon) of the grid's longest asleep run.
    /// Kotlin twin: `SleepRegularity.midsleepMin`.
    static func midsleepMin(_ grid: [Bool]) -> Double {
        var bestStart = 0, bestLen = 0, runStart = 0, runLen = 0
        for e in 0..<epochsPerDay {
            if grid[e] {
                if runLen == 0 { runStart = e }
                runLen += 1
                if runLen > bestLen { bestLen = runLen; bestStart = runStart }
            } else {
                runLen = 0
            }
        }
        return (Double(bestStart) + Double(bestLen) / 2.0) * Double(epochSec / 60)
    }

    /// Regularity for each requested day, from the nights that woke on the `windowNights` days BEFORE it.
    /// Days with too little history are absent.
    /// Kotlin twin: `SleepRegularity.results`.
    public static func results(windows: [Window], days: [String],
                               windowNights: Int = windowNights) -> [String: Result] {
        let r = raster(windows)
        /// Kotlin twin: `covered`.
        func covered(_ k: Int) -> Bool { (r.asleepCount[k] ?? 0) >= minCoveredEpochs }
        var agreeByPair: [Int: Int] = [:]   // pair (k, k + 1) keyed by k, both grids recorded
        for k in r.grids.keys where covered(k) && covered(k + 1) {
            let a = r.grids[k]!, b = r.grids[k + 1]!
            var agree = 0
            for e in 0..<epochsPerDay where a[e] == b[e] { agree += 1 }
            agreeByPair[k] = agree
        }
        var out: [String: Result] = [:]
        let span = max(windowNights, 2)
        for day in days {
            guard let d = DayOrdinal.ordinal(day) else { continue }
            // Nights waking on d − span … d − 1 → grids d − 1 − span … d − 2.
            let lo = d - 1 - span, hi = d - 2
            var pairs = 0, agreeSum = 0
            for k in lo..<hi {
                if let a = agreeByPair[k] { pairs += 1; agreeSum += a }
            }
            if pairs >= minPairs {
                let p = Double(agreeSum) / Double(epochsPerDay * pairs)
                let sri = 200.0 * p - 100.0
                out[day] = Result(score: max(0.0, min(1.0, sri / 100.0)), sri: sri, method: .sri, samples: pairs)
                continue
            }
            var mids: [Double] = []
            for k in lo...hi where covered(k) { mids.append(midsleepMin(r.grids[k]!)) }
            guard mids.count >= minMidsleepNights else { continue }
            var sum = 0.0
            for x in mids { sum += x }
            let mean = sum / Double(mids.count)
            var sq = 0.0
            for x in mids { sq += (x - mean) * (x - mean) }
            let sd = (sq / Double(mids.count)).squareRoot()
            out[day] = Result(score: max(0.0, min(1.0, 1.0 - sd / midsleepSDZeroMin)), sri: nil,
                              method: .midsleepSD, samples: mids.count)
        }
        return out
    }
}

// MARK: - Sleep need

public enum SleepNeed {
    /// One recorded night, keyed by its local wake day.
    public struct Night: Equatable, Sendable {
        public let day: String
        /// Main-night asleep minutes (nil or ≤ 0 = no usable night).
        public let mainMin: Double?
        /// Asleep minutes in separate naps on the same wake day; credited toward debt only.
        public let napMin: Double
        /// That day's Effort (0–100), which raises the NEXT night's need.
        public let strain: Double?
        public init(day: String, mainMin: Double?, napMin: Double = 0, strain: Double? = nil) {
            self.day = day; self.mainMin = mainMin; self.napMin = napMin; self.strain = strain
        }
    }

    public struct Context: Equatable, Sendable {
        public let baseHours: Double
        public let strainHours: Double
        /// Debt carried into the night (minutes, ≥ 0).
        public let debtMin: Double
        /// What the night is scored against: base + strain + debt.
        public var totalHours: Double { baseHours + strainHours + debtMin / 60.0 }
    }

    /// Trailing nights the base-need upper quartile is taken over.
    public static let baseWindowDays = 90
    /// Effort at which the strain adjustment starts, and the Effort where it reaches its maximum.
    /// HEURISTIC coefficients: extra need after heavy exertion is reported (Kitamura et al. 2016;
    /// recovery need after sustained load, Van Dongen et al. 2003) but no source gives a per-unit dose.
    public static let strainNeedStart = 50.0
    public static let strainNeedFull = 100.0
    public static let strainNeedMaxHours = 0.5

    /// Extra need (hours) from the previous day's Effort: 0 below `strainNeedStart`, linear to
    /// `strainNeedMaxHours` at `strainNeedFull`.
    /// Kotlin twin: `SleepNeed.strainHours`.
    public static func strainHours(_ strain: Double?) -> Double {
        guard let s = strain, s.isFinite else { return 0.0 }
        let f = max(0.0, min(1.0, (s - strainNeedStart) / (strainNeedFull - strainNeedStart)))
        return f * strainNeedMaxHours
    }

    /// The running debt recurrence, one calendar day at a time: a recorded night carries
    /// `debtCarryFactor × max(0, need + debt − credited)`, a day with no record decays the debt by the
    /// same factor (as if need were met). Below `minimumDebtMin` clears to 0.
    /// Kotlin twin: `SleepNeed.step`.
    static func step(debt: Double, needMin: Double, creditedMin: Double?) -> Double {
        let next: Double
        if let c = creditedMin {
            next = SleepDebt.debtCarryFactor * max(0.0, needMin + debt - c)
        } else {
            next = SleepDebt.debtCarryFactor * debt
        }
        return next < SleepDebt.minimumDebtMin ? 0.0 : next
    }

    /// Context for each requested day from the nights BEFORE it. `nights` may be in any order; a
    /// repeated day keeps its last row. Base need uses only `mainMin`; the debt credits naps too.
    /// Kotlin twin: `SleepNeed.contexts`.
    public static func contexts(nights: [Night], days: [String], age: Int?) -> [String: Context] {
        var byOrd: [Int: Night] = [:]
        for n in nights { if let o = DayOrdinal.ordinal(n.day) { byOrd[o] = n } }
        guard let firstOrd = byOrd.keys.min() else {
            var out: [String: Context] = [:]
            let base = AnalyticsEngine.Rest.personalizedNeedHours(nightlyHours: [], age: age)
            for day in days where DayOrdinal.ordinal(day) != nil {
                out[day] = Context(baseHours: base, strainHours: 0, debtMin: 0)
            }
            return out
        }
        var baseMemo: [Int: Double] = [:]
        /// Kotlin twin: `base`.
        func base(_ d: Int) -> Double {
            if let b = baseMemo[d] { return b }
            var hours: [Double] = []
            for o in (d - baseWindowDays)..<d {
                if let m = byOrd[o]?.mainMin, m > 0 { hours.append(m / 60.0) }
            }
            let b = AnalyticsEngine.Rest.personalizedNeedHours(nightlyHours: hours, age: age)
            baseMemo[d] = b
            return b
        }
        /// Kotlin twin: `credited`.
        func credited(_ o: Int) -> Double? {
            guard let n = byOrd[o] else { return nil }
            return SleepDebt.creditedSleepMin(mainSleepMin: n.mainMin, napSleepMin: n.napMin)
        }
        /// Kotlin twin: `needMin`.
        func needMin(_ o: Int) -> Double { (base(o) + strainHours(byOrd[o - 1]?.strain)) * 60.0 }

        // Debt carried into each day, running from the first recorded night.
        let targets = days.compactMap { day in DayOrdinal.ordinal(day).map { (day, $0) } }
        guard let lastTarget = targets.map(\.1).max() else { return [:] }
        var debtInto: [Int: Double] = [:]
        var debt = 0.0
        var o = firstOrd
        debtInto[o] = 0.0
        while o < lastTarget {
            debt = step(debt: debt, needMin: needMin(o), creditedMin: credited(o))
            o += 1
            debtInto[o] = debt
        }
        var out: [String: Context] = [:]
        for (day, d) in targets {
            out[day] = Context(baseHours: base(d), strainHours: strainHours(byOrd[d - 1]?.strain),
                               debtMin: debtInto[d] ?? 0.0)
        }
        return out
    }

    /// Debt after the scored night: one recurrence step from the context the night was scored with. Equal
    /// to the next day's `Context.debtMin` when the next day is computed from the same nights.
    /// Kotlin twin: `SleepNeed.debtAfter`.
    public static func debtAfter(_ context: Context, mainMin: Double?, napMin: Double) -> Double {
        let needMin = (context.baseHours + context.strainHours) * 60.0
        return step(debt: context.debtMin, needMin: needMin,
                    creditedMin: SleepDebt.creditedSleepMin(mainSleepMin: mainMin, napSleepMin: napMin))
    }
}

// MARK: - Joined Rest context

public enum SleepScoring {
    public struct DayContext: Equatable, Sendable {
        public let need: SleepNeed.Context
        public let regularity: SleepRegularity.Result?
        public let rest: AnalyticsEngine.Rest.Context
    }

    /// One `Rest.Context` per requested day: trailing need + debt, trailing regularity, age targets.
    /// Kotlin twin: `SleepScoring.contexts`.
    public static func contexts(nights: [SleepNeed.Night], windows: [SleepRegularity.Window],
                                days: [String], age: Int?) -> [String: DayContext] {
        let need = SleepNeed.contexts(nights: nights, days: days, age: age)
        let reg = SleepRegularity.results(windows: windows, days: days)
        var out: [String: DayContext] = [:]
        for day in days {
            guard let n = need[day] else { continue }
            let r = reg[day]
            out[day] = DayContext(need: n, regularity: r,
                                  rest: AnalyticsEngine.Rest.Context(needHours: n.totalHours,
                                                                     consistency: r?.score, age: age))
        }
        return out
    }

    /// Asleep minutes outside the longest block of one wake day: the nap credit for the debt. Blocks
    /// are (start, end, efficiency); a missing or out-of-range efficiency counts as 0.9.
    /// Kotlin twin: `SleepScoring.napMinutes`.
    public static func napMinutes(_ blocks: [(start: Int, end: Int, efficiency: Double?)]) -> Double {
        let valid = blocks.filter { $0.end > $0.start }
        guard let longest = valid.indices.max(by: { (valid[$0].end - valid[$0].start) < (valid[$1].end - valid[$1].start) })
        else { return 0.0 }
        var total = 0.0
        for (i, b) in valid.enumerated() where i != longest {
            let eff = b.efficiency.flatMap { (0.0 < $0 && $0 <= 1.0) ? $0 : nil } ?? 0.9
            total += Double(b.end - b.start) / 60.0 * eff
        }
        return total
    }
}
