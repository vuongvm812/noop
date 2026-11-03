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

    /// Fewest in-sleep samples a mean is formed from. Five-minute HRV values: 3 = a quarter hour of sleep.
    public static let minNightSamples = 3

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

    /// Mean of the samples inside any of `nights` (inclusive bounds), or nil under `minNightSamples`. Each
    /// sample counts once even when blocks overlap. Non-finite and non-positive values are skipped.
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
        return n >= minNightSamples ? sum / Double(n) : nil
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

    /// True when an Effort score whose inputs came from `sourceId` (registry `brand`) must carry the
    /// experimental label. Kotlin twin: `GarminScoringInputs.effortIsExperimental`.
    public static func effortIsExperimental(sourceId: String, brand: String?) -> Bool {
        guard !effortValidated else { return false }
        if FusionSource.isGarminWatchId(sourceId) { return true }
        return brand?.caseInsensitiveCompare("Garmin") == .orderedSame && sourceId != "garmin-import"
    }
}
