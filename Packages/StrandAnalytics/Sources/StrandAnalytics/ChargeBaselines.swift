import Foundation
import WhoopStore

// ChargeBaselines.swift — the personal baselines Charge scores a night against (#2525, #53).
//
// Charge scores a night against personal baselines for HRV, resting HR, respiration and skin temperature.
// #53 replaced the Winsorized EWMA those baselines used to be folded with by a plain trailing mean and SD:
//
//   - PER DAY. Each scored day D reads the nights BEFORE it: the calendar window (D − windowDays, D − 1].
//     Tonight never scores against itself, and a day's baseline does not move when later nights arrive,
//     so stored Charge history stays stable on every re-score.
//   - EQUAL WEIGHT. Every night in the window counts once. The EWMA's fast early mode applied per scan
//     window rather than per wearer, so the OLDEST nights of every window carried the most weight
//     (about 42% for the oldest seven of 21 against 29% for the newest seven) and the baseline jumped as
//     nights aged out.
//   - SAMPLE SD. The EWMA spread started at the floor and moved with a 21-night half-life, so a 21-night
//     window under-read a wide wearer's spread by about half and over-read a tight one's at the floor. The
//     sample SD of the window is floored per metric instead.
//   - HRV IN THE LOG DOMAIN. The HRV baseline is the mean and SD of ln RMSSD (Plews 2012/2013, Buchheit
//     2014): RMSSD is right-skewed, so a linear z over-reads high nights and under-reads low ones. The state
//     carries `logDomain`, its `baseline` is the geometric mean back in ms, and its σ floor is 0.05 ln.
//     The same window applies to resting HR, respiration and skin temperature in their own units.
//   - ERA CUT EVERYWHERE. `epochs` is the ONE place a baseline's start is decided: the manual
//     recalibration epochs, plus the device-era cuts (#459: a Garmin switch restarts HRV and resting HR, any
//     brand switch restarts respiration). The engine and the dashboard both call it, so the "What shaped it"
//     rows, the calibration countdown and the readiness word describe the baseline the ring scored with.
//
// Which nights count is the #2525 rule, unchanged except for the window: the wearer's own nights are the
// baseline, and imported vendor nights (then same-brand wearable-export nights, #31) only seed it while the
// own nights in the window are fewer than `handoffNights`. The window is long enough now (60 days) that an
// export older than the scan window still seeds a new wearer.
//
// Pure and database-free. Byte-identical twin of the Kotlin `ChargeBaselines`.
public enum ChargeBaselines {

    /// Calendar days of nightly history a Charge baseline reads, ending the day before the scored day.
    public static let windowDays: Int = 60

    /// σ floor for the HRV baseline, in ln units: a wearer whose ln RMSSD barely moves still gets a
    /// non-degenerate band (about ±5%), so one ordinary night cannot read as several SDs.
    public static let hrvSigmaFloorLn: Double = 0.05

    /// `scoreInputProvenance` key under which the engine records which source measured each own night, so
    /// the dashboard can rebuild the same device eras the engine cut on.
    public static let baselineSourceKey: String = "charge_baseline_source"

    /// Provenance keys read for a night's source, most specific first. Rows written before #53 carry only
    /// the score keys, which name the same per-day owner.
    public static let sourceKeyPrecedence: [String] = [baselineSourceKey, "recovery", "strain"]

    // MARK: - Metrics

    /// The four nightly metrics a Charge baseline is kept for.
    public enum Metric: String, CaseIterable, Sendable {
        case hrv
        case restingHR = "rhr"
        case resp
        case skinTemp = "skin"

        /// Plausibility bounds (a night outside them is not a reading).
        public var cfg: MetricCfg {
            switch self {
            case .hrv: return Baselines.hrvCfg
            case .restingHR: return Baselines.restingHRCfg
            case .resp: return Baselines.respCfg
            case .skinTemp: return Baselines.metricCfg["skin_temp"]!
            }
        }

        /// True for HRV: its mean and SD are taken over ln RMSSD.
        public var logDomain: Bool { self == .hrv }

        /// The σ floor in the domain the SD is taken in: 0.05 ln for HRV, the config's floor otherwise.
        public var sigmaFloor: Double { self == .hrv ? ChargeBaselines.hrvSigmaFloorLn : cfg.floorSpread }
    }

    // MARK: - Day arithmetic

    /// The last day a scored day's baseline reads: the day before it. nil for an unparseable key.
    public static func anchorDay(forScoredDay day: String) -> String? {
        Baselines.isoEpochDay(day).map { Baselines.isoDay(fromEpochDay: $0 - 1) }
    }

    /// True when `day` starts before `epoch` (seconds), so a baseline starting at that epoch drops it. The
    /// day starts at UTC midnight, the rule `Baselines.foldHistory` has always used. An unparseable key
    /// is dropped whenever an epoch is set.
    static func beforeEpoch(_ day: String, _ epoch: Double) -> Bool {
        guard epoch > 0 else { return false }
        guard let d = Baselines.isoEpochDay(day) else { return true }
        return Double(d) * 86_400 < epoch
    }

    // MARK: - History (which nights count)

    /// One metric's nightly history for a Charge baseline, oldest first, plus the counts the diagnostic
    /// line reports.
    public struct History: Equatable, Sendable {
        /// `yyyy-MM-dd` keys, ascending, parallel to `values`.
        public let dayKeys: [String]
        /// The nightly values; nil is a night that exists but carried no value.
        public let values: [Double?]
        /// True while imported vendor nights still take part.
        public let seededByImport: Bool
        /// Valid own nights on or after the epoch inside the window: the count the handoff is decided on.
        public let ownValidNights: Int
        /// Imported nights this history carries (0 once the own nights have taken over).
        public let importedNights: Int

        public init(dayKeys: [String], values: [Double?], seededByImport: Bool,
                    ownValidNights: Int, importedNights: Int) {
            self.dayKeys = dayKeys
            self.values = values
            self.seededByImport = seededByImport
            self.ownValidNights = ownValidNights
            self.importedNights = importedNights
        }
    }

    /// Valid nights (inside `cfg`'s bounds, on or after `baselineEpoch`) in a day-keyed history.
    public static func validCount(_ values: [Double?], dayKeys: [String], cfg: MetricCfg,
                                  baselineEpoch: Double) -> Int {
        var n = 0
        for i in 0..<min(values.count, dayKeys.count) {
            guard let v = values[i], cfg.minVal <= v, v <= cfg.maxVal,
                  !beforeEpoch(dayKeys[i], baselineEpoch) else { continue }
            n += 1
        }
        return n
    }

    /// Build one metric's history from the imported and own nights (#2525).
    ///
    /// - Parameters:
    ///   - imported: imported vendor nights as `(day, value)`; a repeated day keeps its last value.
    ///   - own: NOOP-computed nights, same shape.
    ///   - seed: same-brand wearable-export nights (#31), in precedence order. A seed night counts only on a
    ///     day neither `imported` nor `own` carries a value for; it then seeds like an imported night.
    ///   - anchorDay: the LAST day the window holds (the day before the scored day).
    ///   - cfg: bounds used to count valid own nights.
    ///   - baselineEpoch: own nights before it do not count toward the handoff.
    ///   - windowDays: see `windowDays`.
    ///   - handoffNights: valid own nights at which the imported nights stop taking part.
    ///
    /// Keeps days on or before the anchor and fewer than `windowDays` days before it. While seeding an
    /// imported value wins a day both sources cover; an own night fills a day the import leaves open.
    /// Kotlin twin: `ChargeBaselines.historyWithSeed`.
    public static func historyWithSeed(imported: [(day: String, value: Double?)],
                                       own: [(day: String, value: Double?)],
                                       seed: [(day: String, value: Double?)],
                                       anchorDay: String,
                                       cfg: MetricCfg,
                                       baselineEpoch: Double,
                                       windowDays: Int = ChargeBaselines.windowDays,
                                       handoffNights: Int = Baselines.minNightsTrust) -> History {
        let empty = History(dayKeys: [], values: [], seededByImport: false, ownValidNights: 0, importedNights: 0)
        guard windowDays > 0, let anchor = Baselines.isoEpochDay(anchorDay) else { return empty }
        let inWindow: (String) -> Bool = { day in
            guard let d = Baselines.isoEpochDay(day) else { return false }
            return d <= anchor && anchor - d < windowDays
        }

        var ownByDay: [String: Double?] = [:]
        for night in own where inWindow(night.day) { ownByDay[night.day] = night.value }
        let ownKeys = ownByDay.keys.sorted()
        let ownValues = ownKeys.map { ownByDay[$0]! }
        let ownValid = validCount(ownValues, dayKeys: ownKeys, cfg: cfg, baselineEpoch: baselineEpoch)

        var importedByDay: [String: Double?] = [:]
        for night in imported where inWindow(night.day) { importedByDay[night.day] = night.value }
        for night in seed where inWindow(night.day) && night.value != nil {
            if let held = importedByDay[night.day], held != nil { continue }
            if let mine = ownByDay[night.day], mine != nil { continue }
            importedByDay[night.day] = night.value
        }

        if ownValid >= handoffNights || importedByDay.isEmpty {
            return History(dayKeys: ownKeys, values: ownValues, seededByImport: false,
                           ownValidNights: ownValid, importedNights: 0)
        }

        var merged = importedByDay
        for (day, value) in ownByDay {
            if let existing = merged[day], existing != nil { continue }   // an imported value wins
            merged[day] = value
        }
        let keys = merged.keys.sorted()
        return History(dayKeys: keys, values: keys.map { merged[$0]! }, seededByImport: true,
                       ownValidNights: ownValid, importedNights: importedByDay.count)
    }

    /// `historyWithSeed` with no wearable-export seed. Kotlin twin: `ChargeBaselines.history`.
    public static func history(imported: [(day: String, value: Double?)],
                               own: [(day: String, value: Double?)],
                               anchorDay: String,
                               cfg: MetricCfg,
                               baselineEpoch: Double,
                               windowDays: Int = ChargeBaselines.windowDays,
                               handoffNights: Int = Baselines.minNightsTrust) -> History {
        historyWithSeed(imported: imported, own: own, seed: [], anchorDay: anchorDay, cfg: cfg,
                        baselineEpoch: baselineEpoch, windowDays: windowDays, handoffNights: handoffNights)
    }

    // MARK: - State (the trailing mean and SD)

    /// The trailing baseline over a history: mean and sample SD (n − 1) of the valid nights on or after
    /// `baselineEpoch`, in ln for HRV. σ is floored at `metric.sigmaFloor` (a single night has σ = floor)
    /// and stored as `spread = σ / 1.253`, the unit every z reader divides back out. `nightsSinceUpdate`
    /// is the calendar days from the newest valid night to `anchorDay`, so a wearer away longer than
    /// `Baselines.staleDays` reads stale rather than scoring against a month-old baseline. With no valid
    /// night the state is the calibrating midpoint, which no reader scores against.
    public static func state(values: [Double?], dayKeys: [String], metric: Metric, anchorDay: String,
                             baselineEpoch: Double) -> BaselineState {
        let cfg = metric.cfg
        var xs: [Double] = []
        var newest: Int? = nil
        for i in 0..<min(values.count, dayKeys.count) {
            guard let v = values[i], cfg.minVal <= v, v <= cfg.maxVal,
                  !beforeEpoch(dayKeys[i], baselineEpoch) else { continue }
            xs.append(metric.logDomain ? log(v) : v)
            if let d = Baselines.isoEpochDay(dayKeys[i]) { newest = max(newest ?? d, d) }
        }
        guard !xs.isEmpty else {
            return BaselineState(baseline: (cfg.minVal + cfg.maxVal) / 2.0, spread: metric.sigmaFloor / 1.253,
                                 nValid: 0, nightsSinceUpdate: 0, status: .calibrating,
                                 logDomain: metric.logDomain)
        }
        let n = xs.count
        var sum = 0.0
        for x in xs { sum += x }
        let mean = sum / Double(n)
        var sd = 0.0
        if n >= 2 {
            var ss = 0.0
            for x in xs { let d = x - mean; ss += d * d }
            sd = (ss / Double(n - 1)).squareRoot()
        }
        let sigma = max(sd, metric.sigmaFloor)
        let since: Int
        if let a = Baselines.isoEpochDay(anchorDay), let nw = newest { since = max(0, a - nw) } else { since = 0 }
        return BaselineState(baseline: metric.logDomain ? exp(mean) : mean, spread: sigma / 1.253,
                             nValid: n, nightsSinceUpdate: since,
                             status: Baselines.computeStatus(nValid: n, nightsSinceUpdate: since),
                             logDomain: metric.logDomain)
    }

    /// `state(values:dayKeys:...)` over a built history.
    public static func state(_ history: History, metric: Metric, anchorDay: String,
                             baselineEpoch: Double) -> BaselineState {
        state(values: history.values, dayKeys: history.dayKeys, metric: metric, anchorDay: anchorDay,
              baselineEpoch: baselineEpoch)
    }

    // MARK: - Eras (the one place a baseline's start is decided)

    /// One night's measuring source, for the device-era cut.
    public struct SourceDay: Equatable, Sendable {
        public let day: String
        public let sourceId: String
        public init(day: String, sourceId: String) { self.day = day; self.sourceId = sourceId }
    }

    /// Where each metric's baseline starts (epoch seconds; 0 = no cut).
    public struct Epochs: Hashable, Sendable {
        public let hrv: Double
        public let restingHR: Double
        public let resp: Double
        public let skin: Double
        public init(hrv: Double, restingHR: Double, resp: Double, skin: Double) {
            self.hrv = hrv; self.restingHR = restingHR; self.resp = resp; self.skin = skin
        }
        public static let none = Epochs(hrv: 0, restingHR: 0, resp: 0, skin: 0)

        public func epoch(_ metric: Metric) -> Double {
            switch metric {
            case .hrv: return hrv
            case .restingHR: return restingHR
            case .resp: return resp
            case .skinTemp: return skin
            }
        }
    }

    /// The epochs a day scored on `scoredDay` uses. Only nights up to and including the scored day decide
    /// its era, so a past day keeps the era it was scored in and the first night on a new device starts a
    /// new baseline with nothing before it. HRV and resting HR cut where a Garmin watch starts or stops
    /// (`GarminScoringInputs.baselineEraEpoch`); respiration cuts at any brand switch
    /// (`Baselines.deviceEraEpoch`); skin temperature only at the manual recalibration. Each is the later
    /// of its era cut and its recalibration epoch. Kotlin twin: `ChargeBaselines.epochs`.
    public static func epochs(sourceDays: [SourceDay], scoredDay: String, hrvRecalibration: Double,
                              recoveryRecalibration: Double) -> Epochs {
        let upTo = sourceDays.filter { $0.day <= scoredDay }.map { (day: $0.day, sourceId: $0.sourceId) }
        let garmin = GarminScoringInputs.baselineEraEpoch(upTo)
        let anyBrand = Baselines.deviceEraEpoch(upTo)
        return Epochs(hrv: max(hrvRecalibration, garmin), restingHR: max(recoveryRecalibration, garmin),
                      resp: max(recoveryRecalibration, anyBrand), skin: recoveryRecalibration)
    }

    /// One source per night, by the history's own precedence: an own night's recorded source (`own`, nil =
    /// unknown, which falls back to `ownFallbackId`), else `importedSourceId` on an imported day, else the
    /// first seed source listed for that day. Sorted by day. Kotlin twin: `ChargeBaselines.sourceDays`.
    public static func sourceDays(own: [(day: String, sourceId: String?)], ownFallbackId: String,
                                  importedDays: [String], importedSourceId: String,
                                  seed: [SourceDay]) -> [SourceDay] {
        var byDay: [String: String] = [:]
        for s in seed where byDay[s.day] == nil { byDay[s.day] = s.sourceId }
        for d in importedDays { byDay[d] = importedSourceId }
        for o in own { byDay[o.day] = o.sourceId ?? ownFallbackId }
        return byDay.keys.sorted().map { SourceDay(day: $0, sourceId: byDay[$0]!) }
    }

    /// Each own night's recorded source from `scoreInputProvenance` rows: per day, the row whose key comes
    /// first in `sourceKeyPrecedence` (other keys are ignored). Kotlin twin: `ChargeBaselines.recordedSources`.
    public static func recordedSources(_ rows: [(day: String, key: String, sourceId: String)]) -> [String: String] {
        var best: [String: (rank: Int, sourceId: String)] = [:]
        for r in rows {
            guard let rank = sourceKeyPrecedence.firstIndex(of: r.key) else { continue }
            if let held = best[r.day], held.rank <= rank { continue }
            best[r.day] = (rank, r.sourceId)
        }
        return best.mapValues(\.sourceId)
    }

    // MARK: - Resolve (one day's four baselines)

    /// One source's nights for the four metrics.
    public struct Nights {
        public var hrv: [(day: String, value: Double?)]
        public var restingHR: [(day: String, value: Double?)]
        public var resp: [(day: String, value: Double?)]
        public var skin: [(day: String, value: Double?)]

        public init(hrv: [(day: String, value: Double?)] = [], restingHR: [(day: String, value: Double?)] = [],
                    resp: [(day: String, value: Double?)] = [], skin: [(day: String, value: Double?)] = []) {
            self.hrv = hrv; self.restingHR = restingHR; self.resp = resp; self.skin = skin
        }

        /// The four metrics read off daily rows (skin from the raw nightly `skinTempC`).
        public init(rows: [DailyMetric]) {
            self.init(hrv: rows.map { (day: $0.day, value: $0.avgHrv) },
                      restingHR: rows.map { (day: $0.day, value: $0.restingHr.map(Double.init)) },
                      resp: rows.map { (day: $0.day, value: $0.respRateBpm) },
                      skin: rows.map { (day: $0.day, value: $0.skinTempC) })
        }

        func values(_ metric: Metric) -> [(day: String, value: Double?)] {
            switch metric {
            case .hrv: return hrv
            case .restingHR: return restingHR
            case .resp: return resp
            case .skinTemp: return skin
            }
        }
    }

    /// The four baselines a scored day reads, with the histories and epochs they were built from.
    public struct Resolved: Equatable, Sendable {
        public let scoredDay: String
        public let anchorDay: String
        public let epochs: Epochs
        public let hrvHistory: History
        public let restingHRHistory: History
        public let respHistory: History
        public let skinHistory: History
        public let hrv: BaselineState
        public let restingHR: BaselineState
        public let resp: BaselineState
        public let skin: BaselineState

        /// The valid HRV nights (ms) of the six calendar days before `scoredDay`, era cut included: the
        /// rest of the 7-day window the Charge HRV trend modifier reads (`RecoveryScorer.hrvTrend`).
        public var hrvPriorWeek: [Double] {
            guard let d = Baselines.isoEpochDay(scoredDay) else { return [] }
            let cfg = Metric.hrv.cfg
            var out: [Double] = []
            for (i, key) in hrvHistory.dayKeys.enumerated() {
                guard let v = hrvHistory.values[i], cfg.minVal <= v, v <= cfg.maxVal,
                      let k = Baselines.isoEpochDay(key), k < d,
                      d - k < RecoveryScorer.hrvTrendWindowDays,
                      !ChargeBaselines.beforeEpoch(key, epochs.hrv) else { continue }
                out.append(v)
            }
            return out
        }
    }

    /// Resolve the four baselines for `scoredDay`: window ending the day before, epochs from `epochs`.
    /// Skin temperature has no imported counterpart (imports carry a deviation, not the raw mean), so its
    /// history is the own nights alone. nil for an unparseable day. Kotlin twin: `ChargeBaselines.resolveDay`.
    public static func resolveDay(scoredDay: String, imported: Nights, own: Nights, seed: Nights,
                                  sourceDays: [SourceDay], hrvRecalibration: Double,
                                  recoveryRecalibration: Double) -> Resolved? {
        guard let anchor = anchorDay(forScoredDay: scoredDay) else { return nil }
        let e = epochs(sourceDays: sourceDays, scoredDay: scoredDay, hrvRecalibration: hrvRecalibration,
                       recoveryRecalibration: recoveryRecalibration)
        func build(_ m: Metric) -> (History, BaselineState) {
            let h = m == .skinTemp
                ? history(imported: [], own: own.skin, anchorDay: anchor, cfg: m.cfg, baselineEpoch: e.skin)
                : historyWithSeed(imported: imported.values(m), own: own.values(m), seed: seed.values(m),
                                  anchorDay: anchor, cfg: m.cfg, baselineEpoch: e.epoch(m))
            return (h, state(h, metric: m, anchorDay: anchor, baselineEpoch: e.epoch(m)))
        }
        let hrv = build(.hrv), rhr = build(.restingHR), resp = build(.resp), skin = build(.skinTemp)
        return Resolved(scoredDay: scoredDay, anchorDay: anchor, epochs: e,
                        hrvHistory: hrv.0, restingHRHistory: rhr.0, respHistory: resp.0, skinHistory: skin.0,
                        hrv: hrv.1, restingHR: rhr.1, resp: resp.1, skin: skin.1)
    }

    /// The stored rows the dashboard resolves baselines from: the same buckets the engine reads.
    public struct Inputs: Equatable {
        public let imported: [DailyMetric]
        public let own: [DailyMetric]
        public let seed: [DailyMetric]
        public let sourceDays: [SourceDay]
        public let hrvRecalibration: Double
        public let recoveryRecalibration: Double

        public init(imported: [DailyMetric], own: [DailyMetric], seed: [DailyMetric], sourceDays: [SourceDay],
                    hrvRecalibration: Double, recoveryRecalibration: Double) {
            self.imported = imported; self.own = own; self.seed = seed; self.sourceDays = sourceDays
            self.hrvRecalibration = hrvRecalibration; self.recoveryRecalibration = recoveryRecalibration
        }

        /// The baselines `scoredDay` was scored against. Kotlin twin: `ChargeBaselines.Inputs.resolve`.
        public func resolve(scoredDay: String) -> Resolved? {
            var importedNights = Nights(rows: imported)
            importedNights.skin = []
            var seedNights = Nights(rows: seed)
            seedNights.skin = []
            return ChargeBaselines.resolveDay(scoredDay: scoredDay, imported: importedNights,
                                              own: Nights(rows: own), seed: seedNights,
                                              sourceDays: sourceDays, hrvRecalibration: hrvRecalibration,
                                              recoveryRecalibration: recoveryRecalibration)
        }

    }

    // MARK: - Diagnostics

    /// The Recovery test-mode line naming what each Charge baseline was folded from for one scored day.
    /// Per metric, `own/N` means the own nights alone, N valid; `seed/N+M` means N valid own nights still
    /// seeded by M imported nights. Kotlin twin: `ChargeBaselines.logLine`, byte-identical.
    public static func logLine(scoredDay: String, hrv: History, restingHR: History, resp: History,
                               skin: History, windowDays: Int = ChargeBaselines.windowDays) -> String {
        let part: (History) -> String = { h in
            h.seededByImport ? "seed/\(h.ownValidNights)+\(h.importedNights)" : "own/\(h.ownValidNights)"
        }
        return "charge baseline day=\(scoredDay) window=\(windowDays)d hrv=\(part(hrv)) " +
            "rhr=\(part(restingHR)) resp=\(part(resp)) skin=\(part(skin))"
    }
}
