import Foundation

// MARK: - Garmin scoring inputs (Garmin child 9)
//
// A paired Garmin watch owns a day the way a strap does: NOOP reads its heart rate, stages its night from
// the watch's own hypnogram, and scores Charge / Effort / Rest with the SAME engines as WHOOP. What
// differs is where the overnight vitals come from. The watch computes resting HR, overnight HRV and
// respiration itself; NOOP's raw-stream estimates of the same facts come from monitoring HR sampled
// about once a minute and no beat intervals, so they are the weaker reading. Each fact therefore goes
// through ONE resolver, `FusionResolver`, with both readings as inputs, and the arbitration table decides
// (`MetricArbitrationPolicy`: the watch is tier 0, NOOP's estimate tier 1). The daily row that feeds
// `RecoveryScorer` and the dashboard's merged row then carry the same value, so no screen can show the
// watch's resting HR beside a Charge scored from a different one.
//
// Overnight HRV: the watch's own last-night average (FIT 370) when present, else the mean of the 5-minute
// HRV values (FIT 371) inside the night's sleep blocks. Beat intervals (FIT 290) are not used: their
// decode is an unconfirmed candidate (GarminWellness), so they never reach rrInterval or this resolver.
// Respiration: the watch day's stored overnight value (`WhoopStore.garminNightMean`, FIT 297 samples
// inside the night that ends that day), the SAME number the row and the `resp_rate` series carry (#52).
//
// Pure, no I/O. Kotlin twin: android/app/src/main/java/com/noop/analytics/GarminScoringInputs.kt, held
// byte-identical by the oracle in GarminScoringInputsTests / GarminScoringInputsTest.
public enum GarminScoringInputs {

    /// Fewest in-sleep samples a mean is formed from on its own: twelve five-minute HRV values is an hour of
    /// sleep (#53; three, a quarter hour, let a sliver of the night stand for all of it).
    public static let minNightSamples = 12

    /// Alternatively, the samples may cover at least this fraction of the sleep window (each sample standing
    /// for `sampleIntervalSec`), so a short but fully sampled night still resolves.
    public static let minNightCoverage = 0.5

    /// The watch's sampling interval for the coverage rule: its overnight HRV values are five-minute values.
    public static let sampleIntervalSec = 300

    /// One timestamped watch sample (unix seconds, value in the kind's unit).
    public struct Sample: Equatable, Sendable {
        public let ts: Int
        public let value: Double
        public init(ts: Int, value: Double) { self.ts = ts; self.value = value }
    }

    /// A sleep block [start, end] in unix seconds.
    public struct Night: Equatable, Sendable {
        public let start: Int
        public let end: Int
        public init(start: Int, end: Int) { self.start = start; self.end = end }
    }

    /// The watch's own day values (its `dailyMetric` row): resting HR from FIT 211, last-night HRV average
    /// from FIT 370. nil fields were not reported.
    public struct WatchDay: Equatable, Sendable {
        public let restingHr: Int?
        public let lastNightHrvMs: Double?
        public init(restingHr: Int?, lastNightHrvMs: Double?) {
            self.restingHr = restingHr
            self.lastNightHrvMs = lastNightHrvMs
        }
    }

    /// Where the resolved overnight HRV came from.
    public enum HrvBasis: String, Equatable, Sendable {
        case watchLastNight = "fit370"     // the watch's own last-night average
        case watchOvernight = "fit371"     // mean of the watch's 5-minute values inside sleep
        case computed = "noop"             // NOOP's own estimate (no watch value)
        case none = "none"
    }

    /// The resolved scoring inputs for one day, each with the source that won it.
    public struct Resolution: Equatable, Sendable {
        public let restingHr: Int?
        public let restingHrSource: FusionSource?
        public let avgHrv: Double?
        public let avgHrvSource: FusionSource?
        public let hrvBasis: HrvBasis
        public let respRateBpm: Double?
        public let respRateSource: FusionSource?
    }

    /// Mean of the samples inside any of `nights` (inclusive bounds), or nil unless there are at least
    /// `minNightSamples` of them or they cover `minNightCoverage` of the sleep window (#53). Each sample
    /// counts once even when blocks overlap. Non-finite and non-positive values are skipped.
    /// Kotlin twin: `GarminScoringInputs.nightMean`.
    public static func nightMean(_ samples: [Sample], nights: [Night]) -> Double? {
        var sum = 0.0
        var n = 0
        for s in samples where s.value.isFinite && s.value > 0 {
            if nights.contains(where: { s.ts >= $0.start && s.ts <= $0.end }) {
                sum += s.value
                n += 1
            }
        }
        guard n > 0 else { return nil }
        if n >= minNightSamples { return sum / Double(n) }
        let window = sleepSeconds(nights)
        guard window > 0, Double(n * sampleIntervalSec) >= minNightCoverage * Double(window) else { return nil }
        return sum / Double(n)
    }

    /// Seconds covered by the union of `nights` (overlapping blocks counted once).
    /// Kotlin twin: `GarminScoringInputs.sleepSeconds`.
    static func sleepSeconds(_ nights: [Night]) -> Int {
        let sorted = nights.filter { $0.end > $0.start }.sorted { $0.start < $1.start }
        var total = 0
        var curStart = 0, curEnd = 0, open = false
        for b in sorted {
            if open && b.start <= curEnd {
                curEnd = max(curEnd, b.end)
            } else {
                if open { total += curEnd - curStart }
                curStart = b.start; curEnd = b.end; open = true
            }
        }
        if open { total += curEnd - curStart }
        return total
    }

    /// Resolve one Garmin-owned day's resting HR, overnight HRV and respiration. `computed*` are NOOP's own
    /// estimates from the raw streams (what `analyzeDay` produced); the watch values come from its stored
    /// day row (`watchRespRateBpm` is that row's stored overnight respiration), and the HRV fallback from its
    /// samples. Every fact is decided by `FusionResolver` over the two readings.
    /// Kotlin twin: `GarminScoringInputs.resolve`.
    public static func resolve(computedRestingHr: Int?, computedHrv: Double?, computedResp: Double?,
                               watch: WatchDay?, hrvSamples: [Sample], watchRespRateBpm: Double?,
                               nights: [Night]) -> Resolution {
        // Kotlin twin: `GarminScoringInputs.pick`.
        func pick(_ key: String, watch w: Double?, computed c: Double?) -> FusedMetricPoint? {
            var inputs: [FusionInput] = []
            if let w, w.isFinite, w > 0 { inputs.append(FusionInput(source: .garminWatch, value: w)) }
            if let c, c.isFinite, c > 0 { inputs.append(FusionInput(source: .noopComputed, value: c)) }
            return FusionResolver.resolve(metricKey: key, inputs: inputs)
        }

        let rhr = pick("rhr", watch: watch?.restingHr.map(Double.init), computed: computedRestingHr.map(Double.init))

        let lastNight = watch?.lastNightHrvMs.flatMap { $0.isFinite && $0 > 0 ? $0 : nil }
        let overnight = lastNight == nil ? nightMean(hrvSamples, nights: nights) : nil
        let hrv = pick("hrv", watch: lastNight ?? overnight, computed: computedHrv)
        let basis: HrvBasis
        switch hrv?.winningSource {
        case .garminWatch?: basis = lastNight != nil ? .watchLastNight : .watchOvernight
        case nil: basis = .none
        default: basis = .computed
        }

        let resp = pick("resp_rate", watch: watchRespRateBpm, computed: computedResp)

        return Resolution(
            restingHr: rhr.map { Int($0.value.rounded()) },
            restingHrSource: rhr?.winningSource,
            avgHrv: hrv?.value,
            avgHrvSource: hrv?.winningSource,
            hrvBasis: basis,
            respRateBpm: resp?.value,
            respRateSource: resp?.winningSource)
    }

    /// The baseline era cut for HRV and resting HR (#459) when a Garmin watch is part of the history: the
    /// first day of the current contiguous same-brand run, as `Baselines.deviceEraEpoch` computes it. The
    /// watch's overnight HRV is the watch's own method, not NOOP's beat-interval RMSSD, so a WHOOP → Garmin
    /// switch (or back) must not fold both into one baseline. 0 (no cut) when no day is a Garmin day, which
    /// keeps every non-Garmin history byte-identical. Kotlin twin: `GarminScoringInputs.baselineEraEpoch`.
    public static func baselineEraEpoch(_ sourceDays: [(day: String, sourceId: String)]) -> Double {
        guard sourceDays.contains(where: { Baselines.brandBucket($0.sourceId) == "garmin" }) else { return 0 }
        return Baselines.deviceEraEpoch(sourceDays)
    }

    // MARK: - Effort label

    /// How a Garmin-derived Effort is presented. Garmin monitoring HR is sparser than a strap's (about one
    /// sample a minute at rest). Garmin support is official in this fork (#43), so a Garmin-derived Effort
    /// carries no experimental label: it shows "calibrating" while its baselines are not usable, the same
    /// as WHOOP. Setting this back to `false` restores the label on both platforms.
    public static let effortValidated = true

    /// How long a watch VO2max still stands for a week (#51). The watch re-estimates it only after an
    /// activity that qualifies, so its latest value is its current one for weeks at a time.
    public static let watchVo2maxMaxAgeDays = 28

    /// The watch's own VO2max for the week keyed `weekKey` ("yyyy-MM-dd"): its latest positive point on or
    /// before that day and within `watchVo2maxMaxAgeDays` of it, or nil. The VO2max card prefers it over
    /// NOOP's estimate (`IntelligenceEngine.fitnessAgeRows`). Kotlin twin: `GarminScoringInputs.watchVo2max`.
    public static func watchVo2max(_ points: [(day: String, value: Double)], weekKey: String) -> Double? {
        var utc = Calendar(identifier: .gregorian)
        utc.timeZone = TimeZone(identifier: "UTC")!
        let f = DateFormatter()
        f.calendar = utc
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = utc.timeZone
        f.dateFormat = "yyyy-MM-dd"
        guard let week = f.date(from: weekKey),
              let oldest = utc.date(byAdding: .day, value: -(watchVo2maxMaxAgeDays - 1), to: week) else { return nil }
        let from = f.string(from: oldest)
        return points.filter { $0.day >= from && $0.day <= weekKey && $0.value > 0 && $0.value.isFinite }
            .max { $0.day < $1.day }?.value
    }

    /// The VO2max points the week resolver reads (#71): every live watch point, plus the Garmin export's
    /// imported points on days the watch has no usable value, oldest first. The watch's own sync wins
    /// wherever it has a day. Kotlin twin: `GarminScoringInputs.vo2maxPoints`.
    public static func vo2maxPoints(
        watch: [(day: String, value: Double)], imported: [(day: String, value: Double)]
    ) -> [(day: String, value: Double)] {
        let watchPoints = watch.filter { $0.value > 0 && $0.value.isFinite }
        let watchDays = Set(watchPoints.map(\.day))
        let merged = watchPoints + imported.filter { $0.value > 0 && $0.value.isFinite && !watchDays.contains($0.day) }
        return merged.sorted { $0.day.utf16.lexicographicallyPrecedes($1.day.utf16) }
    }

    /// Every Saturday week key strictly before `beforeWeekKey` that `watchVo2max` gives a value from
    /// `points`, with that value, oldest first (#71). The recompute pass writes these past weeks so the
    /// VO2max history shows the watch's numbers, not only for the current week. Empty when
    /// `beforeWeekKey` does not parse. Kotlin twin: `GarminScoringInputs.watchVo2maxWeeks`.
    public static func watchVo2maxWeeks(
        _ points: [(day: String, value: Double)], beforeWeekKey: String
    ) -> [(week: String, value: Double)] {
        var utc = Calendar(identifier: .gregorian)
        utc.timeZone = TimeZone(identifier: "UTC")!
        let f = DateFormatter()
        f.calendar = utc
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = utc.timeZone
        f.dateFormat = "yyyy-MM-dd"
        guard f.date(from: beforeWeekKey) != nil,
              let first = points.filter({ $0.value > 0 && $0.value.isFinite }).map(\.day).min(),
              let firstDate = f.date(from: first) else { return [] }
        // Sunday = 1 … Saturday = 7: step forward to the first Saturday on or after the oldest point.
        let toSaturday = (7 - utc.component(.weekday, from: firstDate)) % 7
        guard var week = utc.date(byAdding: .day, value: toSaturday, to: firstDate) else { return [] }
        var out: [(week: String, value: Double)] = []
        while true {
            let key = f.string(from: week)
            guard key < beforeWeekKey else { break }
            if let v = watchVo2max(points, weekKey: key) { out.append((week: key, value: v)) }
            guard let next = utc.date(byAdding: .day, value: 7, to: week) else { break }
            week = next
        }
        return out
    }

    /// True when an Effort score whose inputs came from `sourceId` (registry `brand`) must carry the
    /// experimental label. Kotlin twin: `GarminScoringInputs.effortIsExperimental`.
    public static func effortIsExperimental(sourceId: String, brand: String?) -> Bool {
        guard !effortValidated else { return false }
        if FusionSource.isGarminWatchId(sourceId) { return true }
        return brand?.caseInsensitiveCompare("Garmin") == .orderedSame && sourceId != "garmin-import"
    }
}
