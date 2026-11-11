import Foundation

// WeeklyDigest.swift — a deterministic, offline "week in review".
//
// Pure, deterministic, DB-free. Given the daily series for each tracked metric
// (keyed by "yyyy-MM-dd"), this builds a Monday-anchored "this week" summary:
//
//   • per-metric this-week SeriesStat (mean / median / min / max / SD / slope),
//   • week-over-week PeriodComparison (this week vs the immediately preceding
//     Mon–Sun week), reusing ComparisonEngine.compare,
//   • a "vs baseline" delta: this-week mean against a trailing baseline mean
//     (the prior `baselineWeeks` complete weeks before this one),
//   • a sleep-consistency read (SD of the night's values across the week — lower
//     is steadier),
//   • a strain-vs-recovery balance read (is Effort outrunning Charge this week?),
//   • the 1–3 biggest movers ranked by normalised week-over-week change, and
//   • 1–2 plain-English focal points, rendered the way BehaviorInsights.sentence
//     renders an effect.
//
// It deliberately consumes plain [String: Double] day→value maps (not a DB row
// type) so StrandAnalytics stays decoupled from WhoopStore: the UI layer pulls
// recovery/strain/sleep/RHR/HRV out of its own DailyMetric shape and hands them
// in. No AI is required — narration is an optional later layer that can take this
// struct as input.
//
// Week math is timezone/locale-free: weekday is computed from the "yyyy-MM-dd"
// string with a pure Sakamoto/Zeller day-of-week, and week windows are produced
// as inclusive "yyyy-MM-dd" string ranges, so the split matches the day strings
// AnalyticsEngine emits exactly (string comparison is chronological for ISO days).

// MARK: - Tracked metric

/// How a metric's daily values roll up into one weekly figure.
public enum WeeklyRollup: String, Sendable {
    /// Mean of the week's daily values (the original five, steps, sleep, vitals…).
    case mean
    /// Total over the elapsed span of the week (training load, intensity minutes).
    case sum
    /// The week's most recent reading (VO2 max).
    case latest
}

/// Which way a change in a metric reads.
public enum WeeklyDirection: String, Sendable {
    case higherBetter
    case lowerBetter
    /// No good/bad framing: the change is reported with its arrow only.
    case neutral
}

/// Everything the engine needs to know about one metric. The original five keep their
/// fixed `fixedSpread`; every later metric is normalised by the personal SD of its
/// baseline weeks, floored at `spreadFloor` so a flat baseline cannot divide by ~0.
public struct WeeklyMetricSpec: Equatable, Sendable {
    /// English name used in the narration (the UI localises its own).
    public let label: String
    /// Display unit suffix ("" for unitless scores).
    public let unit: String
    public let rollup: WeeklyRollup
    public let direction: WeeklyDirection
    /// Fixed normaliser, or nil to use the personal baseline SD.
    public let fixedSpread: Double?
    /// Lower bound on the personal baseline SD.
    public let spreadFloor: Double
    /// Decimal places for the rendered weekly values (0 or 1).
    public let decimals: Int
    /// False for metrics already measured in % or as a deviation from 0, where a
    /// percent-of-a-percent change reads as noise: the change is given in units instead.
    public let percentChange: Bool
    /// Weekly goal for a `.sum` metric (intensity minutes: 150), nil otherwise.
    public let weeklyTarget: Double?

    public init(label: String, unit: String, rollup: WeeklyRollup = .mean,
                direction: WeeklyDirection = .higherBetter, fixedSpread: Double? = nil,
                spreadFloor: Double, decimals: Int = 0, percentChange: Bool = true,
                weeklyTarget: Double? = nil) {
        self.label = label
        self.unit = unit
        self.rollup = rollup
        self.direction = direction
        self.fixedSpread = fixedSpread
        self.spreadFloor = spreadFloor
        self.decimals = decimals
        self.percentChange = percentChange
        self.weeklyTarget = weeklyTarget
    }
}

/// The metrics a weekly digest reports on. The first five are the original headline
/// set and keep their order; the rest are optional extras that only take part when
/// their series carries data. Case order is shared with the Kotlin twin.
public enum WeeklyMetric: String, CaseIterable, Sendable {
    case charge   // recovery, 0–100
    case effort   // strain / Effort, 0–100
    case rest     // sleep performance composite, 0–100
    case rhr      // resting heart rate, bpm
    case hrv      // heart-rate variability, ms
    case steps
    case activeKcal
    case sleepDuration      // hours asleep
    case sleepEfficiency    // % of time in bed asleep
    case deepSleep          // minutes
    case remSleep           // minutes
    case sleepScore         // the watch's own sleep score, 0–100
    case spo2               // overnight mean SpO2, %
    case respiration        // overnight mean breaths/min
    case skinTempDeviation  // °C from the personal baseline
    case stress             // device stress score, 0–100
    case bodyBatteryRecharge // overnight Body Battery gain
    case trainingLoad
    case intensityMinutes   // moderate + 2 × vigorous
    case vo2max
    case lightSleep
    case sleepNeed
    case sleepDebt
    case sleepConsistency
    case disturbances
    case avgHr
    case maxHr
    case sdnn
    case totalKcal
    case basalKcal
    case distance
    case workoutCount
    case workoutMinutes
    case workoutKcal
    case workoutMaxHr
    case workoutPower
    case workoutCadence
    case workoutElevation
    case aerobicTe
    case anaerobicTe
    case weight
    case bodyFat
    case leanMass
    case bmi
    case fitnessAge
    case vitality
    case bodyAge
    case vo2maxEst
    case caloriesIn
    case protein
    case carbs
    case fat
    case water
    case mood

    public var spec: WeeklyMetricSpec {
        switch self {
        case .charge: return .init(label: "Charge", unit: "", fixedSpread: 12.0, spreadFloor: 12.0)
        case .effort: return .init(label: "Effort", unit: "", fixedSpread: 12.0, spreadFloor: 12.0)
        case .rest:   return .init(label: "Rest", unit: "", fixedSpread: 12.0, spreadFloor: 12.0)
        case .rhr:    return .init(label: "Resting HR", unit: "bpm", direction: .lowerBetter,
                                   fixedSpread: 4.0, spreadFloor: 4.0)
        case .hrv:    return .init(label: "HRV", unit: "ms", fixedSpread: 8.0, spreadFloor: 8.0)
        case .steps:  return .init(label: "Steps", unit: "steps", spreadFloor: 500)
        case .activeKcal: return .init(label: "Active calories", unit: "kcal", spreadFloor: 50)
        case .sleepDuration: return .init(label: "Sleep duration", unit: "h", direction: .neutral,
                                          spreadFloor: 0.25, decimals: 1)
        case .sleepEfficiency: return .init(label: "Sleep efficiency", unit: "%", spreadFloor: 2,
                                            percentChange: false)
        case .deepSleep: return .init(label: "Deep sleep", unit: "min", spreadFloor: 5)
        case .remSleep:  return .init(label: "REM sleep", unit: "min", spreadFloor: 5)
        case .sleepScore: return .init(label: "Sleep score", unit: "", spreadFloor: 4)
        case .spo2: return .init(label: "Overnight SpO2", unit: "%", direction: .neutral,
                                 spreadFloor: 0.5, decimals: 1, percentChange: false)
        case .respiration: return .init(label: "Respiration", unit: "brpm", direction: .neutral,
                                        spreadFloor: 0.3, decimals: 1)
        case .skinTempDeviation: return .init(label: "Skin temp deviation", unit: "°C",
                                              direction: .neutral, spreadFloor: 0.1, decimals: 1,
                                              percentChange: false)
        case .stress: return .init(label: "Stress", unit: "", direction: .lowerBetter, spreadFloor: 3)
        case .bodyBatteryRecharge: return .init(label: "Body Battery recharge", unit: "", spreadFloor: 5)
        // More load is neither good nor bad on its own, so it carries no verdict.
        case .trainingLoad: return .init(label: "Training load", unit: "", rollup: .sum,
                                         direction: .neutral, spreadFloor: 25)
        case .intensityMinutes: return .init(label: "Intensity minutes", unit: "min", rollup: .sum,
                                             spreadFloor: 15, weeklyTarget: 150)
        case .vo2max: return .init(label: "VO2 max", unit: "ml/kg/min", rollup: .latest,
                                   spreadFloor: 1, percentChange: false)
        case .lightSleep: return .init(label: "Light sleep", unit: "min", direction: .neutral, spreadFloor: 5)
        case .sleepNeed: return .init(label: "Sleep need", unit: "min", direction: .neutral, spreadFloor: 5)
        case .sleepDebt: return .init(label: "Sleep debt", unit: "min", direction: .lowerBetter, spreadFloor: 10)
        case .sleepConsistency: return .init(label: "Sleep consistency", unit: "%", spreadFloor: 3, percentChange: false)
        case .disturbances: return .init(label: "Disturbances", unit: "", direction: .lowerBetter, spreadFloor: 1)
        case .avgHr: return .init(label: "Average heart rate", unit: "bpm", direction: .neutral, spreadFloor: 2)
        case .maxHr: return .init(label: "Max heart rate", unit: "bpm", direction: .neutral, spreadFloor: 4)
        case .sdnn: return .init(label: "HRV (SDNN)", unit: "ms", spreadFloor: 4)
        case .totalKcal: return .init(label: "Total calories", unit: "kcal", direction: .neutral, spreadFloor: 100)
        case .basalKcal: return .init(label: "Basal calories", unit: "kcal", direction: .neutral, spreadFloor: 50)
        case .distance: return .init(label: "Distance", unit: "km", direction: .neutral, spreadFloor: 0.5, decimals: 1)
        case .workoutCount: return .init(label: "Workouts", unit: "", rollup: .sum, direction: .neutral, spreadFloor: 1)
        case .workoutMinutes: return .init(label: "Workout time", unit: "min", rollup: .sum, spreadFloor: 15)
        case .workoutKcal: return .init(label: "Workout calories", unit: "kcal", rollup: .sum, direction: .neutral, spreadFloor: 100)
        case .workoutMaxHr: return .init(label: "Workout max heart rate", unit: "bpm", direction: .neutral, spreadFloor: 4)
        case .workoutPower: return .init(label: "Average power", unit: "W", direction: .neutral, spreadFloor: 10)
        case .workoutCadence: return .init(label: "Average cadence", unit: "rpm", direction: .neutral, spreadFloor: 3)
        case .workoutElevation: return .init(label: "Elevation gain", unit: "m", rollup: .sum, direction: .neutral, spreadFloor: 25)
        case .aerobicTe: return .init(label: "Aerobic training effect", unit: "", direction: .neutral, spreadFloor: 0.3, decimals: 1, percentChange: false)
        case .anaerobicTe: return .init(label: "Anaerobic training effect", unit: "", direction: .neutral, spreadFloor: 0.3, decimals: 1, percentChange: false)
        case .weight: return .init(label: "Weight", unit: "kg", rollup: .latest, direction: .neutral, spreadFloor: 0.3, decimals: 1, percentChange: false)
        case .bodyFat: return .init(label: "Body fat", unit: "%", rollup: .latest, direction: .lowerBetter, spreadFloor: 0.5, decimals: 1, percentChange: false)
        case .leanMass: return .init(label: "Lean mass", unit: "kg", rollup: .latest, spreadFloor: 0.3, decimals: 1, percentChange: false)
        case .bmi: return .init(label: "BMI", unit: "", rollup: .latest, direction: .neutral, spreadFloor: 0.2, decimals: 1, percentChange: false)
        case .fitnessAge: return .init(label: "Fitness Age", unit: "yrs", rollup: .latest, direction: .lowerBetter, spreadFloor: 1, percentChange: false)
        case .vitality: return .init(label: "Vitality", unit: "", rollup: .latest, spreadFloor: 3, percentChange: false)
        case .bodyAge: return .init(label: "Body Age", unit: "yrs", rollup: .latest, direction: .lowerBetter, spreadFloor: 1, percentChange: false)
        case .vo2maxEst: return .init(label: "VO2 max (estimated)", unit: "ml/kg/min", rollup: .latest, spreadFloor: 1, percentChange: false)
        case .caloriesIn: return .init(label: "Calories in", unit: "kcal", direction: .neutral, spreadFloor: 150)
        case .protein: return .init(label: "Protein", unit: "g", direction: .neutral, spreadFloor: 10)
        case .carbs: return .init(label: "Carbs", unit: "g", direction: .neutral, spreadFloor: 15)
        case .fat: return .init(label: "Fat", unit: "g", direction: .neutral, spreadFloor: 8)
        case .water: return .init(label: "Water", unit: "ml", direction: .neutral, spreadFloor: 150)
        case .mood: return .init(label: "Mood", unit: "/5", spreadFloor: 0.4, decimals: 1, percentChange: false)
        }
    }

    /// The `MetricSnapshotItem.id` a metric is read from when it comes from the snapshot stores, or nil for
    /// the original set. Kotlin twin: `WeeklyMetric.snapshotId`.
    public var snapshotId: String? {
        switch self {
        case .lightSleep: return "sleep_light_min"
        case .sleepNeed: return "sleep_need_min"
        case .sleepDebt: return "sleep_debt_min"
        case .sleepConsistency: return "sleep_consistency"
        case .disturbances: return "disturbances"
        case .avgHr: return "avg_hr"
        case .maxHr: return "max_hr"
        case .sdnn: return "sdnn"
        case .totalKcal: return "energy_kcal"
        case .basalKcal: return "basal_kcal"
        case .distance: return "distance_km"
        case .workoutCount: return "workout_count"
        case .workoutMinutes: return "workout_duration_min"
        case .workoutKcal: return "workout_energy_kcal"
        case .workoutMaxHr: return "workout_max_hr"
        case .workoutPower: return "workout_avg_power_w"
        case .workoutCadence: return "workout_avg_cadence"
        case .workoutElevation: return "workout_altitude_gain_m"
        case .aerobicTe: return "workout_aerobic_te"
        case .anaerobicTe: return "workout_anaerobic_te"
        case .weight: return "weight"
        case .bodyFat: return "body_fat"
        case .leanMass: return "lean_mass"
        case .bmi: return "bmi"
        case .fitnessAge: return "fitness_age"
        case .vitality: return "vitality"
        case .bodyAge: return "body_age"
        case .vo2maxEst: return "vo2max_est"
        case .caloriesIn: return "calories_in"
        case .protein: return "protein_g"
        case .carbs: return "carbs_g"
        case .fat: return "fat_g"
        case .water: return "hydration_ml"
        case .mood: return "mood"
        default: return nil
        }
    }

    /// Human label for the metric (matches the rest of the app's naming).
    public var label: String { spec.label }

    /// Display unit suffix (empty for the unitless 0–100 scores).
    public var unit: String { spec.unit }

    /// True when a HIGHER value is the better outcome. False for lower-better AND
    /// neutral metrics; check `spec.direction` to tell those apart.
    public var higherIsBetter: Bool { spec.direction == .higherBetter }

    /// The normaliser for the original five (a coarse "typical day-to-day range"), or the
    /// personal-spread floor for the later metrics, whose actual normaliser is the
    /// per-digest `WeeklyMetricSummary.personalSpread`.
    public var typicalSpread: Double { spec.fixedSpread ?? spec.spreadFloor }
}

// MARK: - Per-metric line

/// One metric's line in the weekly digest.
public struct WeeklyMetricSummary: Equatable, Sendable {
    public let metric: WeeklyMetric
    /// This-week stats (Mon–Sun). `.empty` when the week has no readings.
    public let thisWeek: SeriesStat
    /// This week vs the immediately preceding Mon–Sun week.
    public let weekOverWeek: PeriodComparison
    /// Mean over the trailing baseline window (the `baselineWeeks` complete weeks
    /// before this one), or nil when there were no baseline readings.
    public let baselineMean: Double?
    /// thisWeek.mean − baselineMean, or nil when baselineMean is nil. For a `.sum` or
    /// `.latest` metric both sides use the rolled-up weekly figure instead of the mean.
    public let vsBaseline: Double?
    /// The rolled-up weekly figures for a `.sum` / `.latest` metric (nil for `.mean`,
    /// where the stats' means are the figures).
    public let rolledThis: Double?
    public let rolledLast: Double?
    /// Personal baseline SD used to normalise a non-headline metric's move, nil when the
    /// baseline is too thin (the metric then cannot be a mover).
    public let personalSpread: Double?

    public init(metric: WeeklyMetric, thisWeek: SeriesStat,
                weekOverWeek: PeriodComparison, baselineMean: Double?,
                vsBaseline: Double?, rolledThis: Double? = nil, rolledLast: Double? = nil,
                personalSpread: Double? = nil) {
        self.metric = metric
        self.thisWeek = thisWeek
        self.weekOverWeek = weekOverWeek
        self.baselineMean = baselineMean
        self.vsBaseline = vsBaseline
        self.rolledThis = rolledThis
        self.rolledLast = rolledLast
        self.personalSpread = personalSpread
    }

    /// This week's figure: the mean, or the total / latest reading per the roll-up.
    public var thisValue: Double { rolledThis ?? thisWeek.mean }
    /// Last week's figure on the same roll-up.
    public var lastValue: Double { rolledLast ?? weekOverWeek.previous.mean }

    /// True when this week carried at least one reading for the metric.
    public var hasData: Bool { thisWeek.n > 0 }

    /// Fraction of the weekly target reached (`.sum` metrics with a target only).
    public var targetProgress: Double? {
        guard let target = metric.spec.weeklyTarget, target > 0, hasData else { return nil }
        return thisValue / target
    }

    /// Signed week-over-week change in the metric's own units (this − last).
    public var wowDelta: Double { weekOverWeek.delta }

    /// Direction of the week-over-week change expressed as GOOD / BAD / FLAT,
    /// folding in `higherIsBetter` (so a RHR rise reads as "worse"). 0 when flat
    /// or a period is empty.
    public var wowGoodness: Int {
        guard weekOverWeek.direction != 0, metric.spec.direction != .neutral else { return 0 }
        let up = weekOverWeek.direction > 0
        let good = (up == metric.higherIsBetter)
        return good ? 1 : -1
    }

    /// The week-over-week change scaled by the metric's typical spread, so movers
    /// on different units are comparable. 0 when a period is empty.
    public var normalisedMove: Double {
        guard weekOverWeek.current.n > 0, weekOverWeek.previous.n > 0,
              let s = metric.spec.fixedSpread ?? personalSpread else { return 0 }
        return s > 0 ? wowDelta / s : 0
    }

    /// True when the week-over-week comparison rests on a sparse side: both weeks
    /// carry at least one reading, but either has fewer than
    /// `WeeklyDigestEngine.minDaysForFocus` days. A rough comparison still shows its
    /// raw arrow + %, but the UI shouldn't dress it in a confident good/bad verdict —
    /// a 43% "drop" off 2 days isn't a trend (the #463 chips-vs-summary contradiction).
    public var isRoughComparison: Bool {
        // A total or a latest reading is not an average of days, so day count is not
        // what makes it rough.
        guard metric.spec.rollup == .mean else { return false }
        let cur = weekOverWeek.current.n
        let prev = weekOverWeek.previous.n
        guard cur > 0, prev > 0 else { return false }
        return cur < WeeklyDigestEngine.minDaysForFocus
            || prev < WeeklyDigestEngine.minDaysForFocus
    }
}

// MARK: - Digest

/// A focal point as structured data, so a UI layer can render it in the user's language
/// instead of showing the engine's English sentence. Additive: `WeeklyDigest.focalPoints`
/// is unchanged; `focalDetails` is the same selection (same order) in structured form.
/// Numbers are carried pre-formatted exactly as the English sentence prints them.
/// Kotlin twin: `WeeklyFocal`.
public enum WeeklyFocal: Equatable, Sendable {
    /// How big a week-over-week move is.
    public enum Magnitude: Equatable, Sendable {
        /// Whole percent ("12" in "12%").
        case percent(Int)
        /// A bare points figure ("3.5" in "3.5 pts").
        case points(String)
        /// A figure in the metric's own unit ("4.0", "bpm").
        case unit(String, String)
    }

    /// One metric's move. `delta` is -1 / 0 / 1 (down / flat / up); `goodness` is
    /// 1 (good sign) / -1 (worth a look) / 0 (no verdict).
    case mover(metric: WeeklyMetric, delta: Int, magnitude: Magnitude, rollup: WeeklyRollup,
               thisValue: String, lastValue: String, goodness: Int)
    /// The Effort-vs-Charge balance line (overreaching / underloaded).
    case balance(BalanceRead)
    /// Fewer than `minDaysForFocus` days into this week.
    case earlyWeek(days: Int)
    /// Last week had fewer than `minDaysForFocus` days.
    case sparseLastWeek(days: Int)
    /// A steady week with Rest held even; `sd` is the pre-formatted ± figure.
    case steadyRest(sd: String)
    /// A steady week, nothing moved.
    case steady
}

/// The complete week-in-review.
public struct WeeklyDigest: Equatable, Sendable {
    /// The Monday that anchors "this week" ("yyyy-MM-dd").
    public let weekStart: String
    /// The Sunday that ends "this week" ("yyyy-MM-dd").
    public let weekEnd: String
    /// Per-metric summaries, in WeeklyMetric.allCases order.
    public let metrics: [WeeklyMetricSummary]
    /// Number of distinct days this week that carried at least one reading.
    public let daysWithData: Int
    /// Sleep-consistency: SD of this week's Rest values (lower = steadier). nil when
    /// fewer than 2 Rest nights this week. In Rest points.
    public let sleepConsistencySD: Double?
    /// Strain-vs-recovery balance read for the week (see `BalanceRead`).
    public let balance: BalanceRead
    /// 1–2 plain-English focal points, most salient first.
    public let focalPoints: [String]
    /// The same focal points as structured data, for localized rendering.
    public let focalDetails: [WeeklyFocal]

    public init(weekStart: String, weekEnd: String, metrics: [WeeklyMetricSummary],
                daysWithData: Int, sleepConsistencySD: Double?, balance: BalanceRead,
                focalPoints: [String], focalDetails: [WeeklyFocal] = []) {
        self.focalDetails = focalDetails
        self.weekStart = weekStart
        self.weekEnd = weekEnd
        self.metrics = metrics
        self.daysWithData = daysWithData
        self.sleepConsistencySD = sleepConsistencySD
        self.balance = balance
        self.focalPoints = focalPoints
    }

    /// Look up one metric's summary.
    public func summary(_ metric: WeeklyMetric) -> WeeklyMetricSummary? {
        metrics.first { $0.metric == metric }
    }

    /// True when no metric carried a single reading this week (caller can show an
    /// empty state instead of a digest).
    public var isEmpty: Bool { daysWithData == 0 }
}

/// How this week's Effort (strain) sat against this week's Charge (recovery).
public enum BalanceRead: String, Equatable, Sendable {
    case overreaching   // Effort high vs Charge — leaning into the red
    case balanced       // Effort and Charge roughly tracking
    case underloaded    // Effort low vs Charge — lots in the tank, little spent
    case insufficient   // not enough of both to call it

    /// Plain-English line for the UI.
    public var sentence: String {
        switch self {
        case .overreaching:
            return "Your Effort outpaced your Charge this week: you leaned into the red. Watch for a recovery dip."
        case .balanced:
            return "Effort and Charge tracked together this week: a sustainable load."
        case .underloaded:
            return "You carried more Charge than you spent this week: there's room to push if you want it."
        case .insufficient:
            return "Not enough Effort and Charge days this week to read your balance."
        }
    }
}

public enum WeeklyDigestEngine {

    /// How many complete weeks before "this week" form the vs-baseline comparison.
    public static let baselineWeeks: Int = 4
    /// Minimum days each side needs before a week-over-week move is "real" enough
    /// to surface as a focal point (guards against a 1-day week swinging wildly).
    public static let minDaysForFocus: Int = 3

    // MARK: - Entry point

    /// Build the weekly digest anchored on the Monday of the week containing
    /// `anchorDay` ("yyyy-MM-dd", typically today).
    ///
    /// - Parameters:
    ///   - byMetric: per-metric day→value maps. Missing metrics / days are simply
    ///     absent; this is robust to sparse data.
    ///   - anchorDay: any "yyyy-MM-dd" in the target week (we snap to its Monday).
    ///     A non-parseable string yields an all-empty digest.
    ///   - effortDisplayFactor: multiplier applied to EFFORT averages (and the pts
    ///     fallback magnitude) in the rendered focal-point sentences ONLY, so a user
    ///     on the 0–21 Effort scale (#268) never reads a stored 0–100 mean in prose.
    ///     Percent changes are scale-invariant and stay untouched. Defaults to 1.0
    ///     (stored scale) so existing callers are byte-identical. Display-only: no
    ///     stat, delta or threshold changes.
    public static func build(byMetric: [WeeklyMetric: [String: Double]],
                             anchorDay: String,
                             effortDisplayFactor: Double = 1.0) -> WeeklyDigest {
        guard let monday = mondayOfWeek(containing: anchorDay) else {
            return emptyDigest(weekStart: anchorDay, weekEnd: anchorDay)
        }
        let sunday = addDays(monday, 6)
        let lastMonday = addDays(monday, -7)
        let lastSunday = addDays(monday, -1)

        // Baseline window: the `baselineWeeks` complete weeks ending the day before
        // last week starts (so it never overlaps this week or last week).
        let baselineEnd = addDays(lastMonday, -1)               // Sunday before last week
        let baselineStart = addDays(lastMonday, -7 * baselineWeeks)

        var summaries: [WeeklyMetricSummary] = []
        var daysSeen: Set<String>? = []
        for metric in WeeklyMetric.allCases {
            _ = valuesInRange(byMetric[metric] ?? [:], start: monday, end: sunday, daysSeen: &daysSeen)
        }
        // How far into the week the data runs (the last day any metric reported), so a
        // `.sum` metric compares like with like: this week so far against the same
        // weekdays of earlier weeks. A finished week spans all seven days.
        let span = (daysSeen ?? []).max().map { dayIndex(from: monday, to: $0) + 1 } ?? 7

        for metric in WeeklyMetric.allCases {
            let series = byMetric[metric] ?? [:]
            var noAccum: Set<String>? = nil
            let thisVals = valuesInRange(series, start: monday, end: sunday, daysSeen: &noAccum)
            let lastVals = valuesInRange(series, start: lastMonday, end: lastSunday, daysSeen: &noAccum)
            let baseVals = valuesInRange(series, start: baselineStart, end: baselineEnd, daysSeen: &noAccum)

            switch metric.spec.rollup {
            case .mean:
                let thisStat = ComparisonEngine.stat(thisVals)
                let wow = ComparisonEngine.compare(current: thisVals, previous: lastVals)
                let baseMean: Double? = baseVals.isEmpty ? nil
                    : baseVals.reduce(0, +) / Double(baseVals.count)
                let vsBase: Double? = baseMean.map { thisStat.mean - $0 }
                let spread = metric.spec.fixedSpread == nil
                    ? personalSpread(baseVals, minCount: minDaysForFocus, floor: metric.spec.spreadFloor)
                    : nil
                summaries.append(WeeklyMetricSummary(
                    metric: metric, thisWeek: thisStat, weekOverWeek: wow,
                    baselineMean: baseMean, vsBaseline: vsBase, personalSpread: spread))
            case .sum:
                summaries.append(sumSummary(metric, series: series, thisVals: thisVals,
                                            lastMonday: lastMonday, baselineStart: baselineStart,
                                            span: span))
            case .latest:
                summaries.append(latestSummary(metric, thisVals: thisVals, lastVals: lastVals,
                                               baseVals: baseVals))
            }
        }

        // Sleep consistency: SD of this week's Rest series (lower = steadier).
        let restStat = summaries.first { $0.metric == .rest }?.thisWeek
        let restConsistency: Double? = (restStat?.n ?? 0) >= 2 ? restStat?.stdev : nil

        let balance = balanceRead(summaries)
        let focal = focalPoints(summaries: summaries, balance: balance,
                                consistencySD: restConsistency,
                                effortDisplayFactor: effortDisplayFactor, span: span)
        let focalDetails = focalEntries(summaries: summaries, balance: balance,
                                        consistencySD: restConsistency,
                                        effortDisplayFactor: effortDisplayFactor, span: span)

        return WeeklyDigest(
            weekStart: monday, weekEnd: sunday, metrics: summaries,
            daysWithData: (daysSeen ?? []).count, sleepConsistencySD: restConsistency,
            balance: balance, focalPoints: focal, focalDetails: focalDetails)
    }

    // MARK: - Non-mean roll-ups

    /// A `.sum` metric: this week's total so far against last week's total over the
    /// same weekdays; the baseline is the mean same-span total of the baseline weeks
    /// that carried any reading, and the spread is the SD of those totals.
    /// Kotlin twin: `WeeklyDigestEngine.sumSummary`.
    static func sumSummary(_ metric: WeeklyMetric, series: [String: Double], thisVals: [Double],
                           lastMonday: String, baselineStart: String,
                           span: Int) -> WeeklyMetricSummary {
        var noAccum: Set<String>? = nil
        let lastVals = valuesInRange(series, start: lastMonday, end: addDays(lastMonday, span - 1),
                                     daysSeen: &noAccum)
        var weekTotals: [Double] = []
        for w in 0..<baselineWeeks {
            let wkStart = addDays(baselineStart, 7 * w)
            let vals = valuesInRange(series, start: wkStart, end: addDays(wkStart, span - 1),
                                     daysSeen: &noAccum)
            if !vals.isEmpty { weekTotals.append(vals.reduce(0, +)) }
        }
        let thisTotal = thisVals.reduce(0, +)
        let lastTotal = lastVals.reduce(0, +)
        let baseMean: Double? = weekTotals.isEmpty ? nil
            : weekTotals.reduce(0, +) / Double(weekTotals.count)
        return WeeklyMetricSummary(
            metric: metric, thisWeek: ComparisonEngine.stat(thisVals),
            weekOverWeek: rolledComparison(thisVals, lastVals, this: thisTotal, last: lastTotal),
            baselineMean: baseMean, vsBaseline: baseMean.map { thisTotal - $0 },
            rolledThis: thisTotal, rolledLast: lastTotal,
            personalSpread: personalSpread(weekTotals, minCount: 2, floor: metric.spec.spreadFloor))
    }

    /// A `.latest` metric: the week's most recent reading against last week's.
    /// Kotlin twin: `WeeklyDigestEngine.latestSummary`.
    static func latestSummary(_ metric: WeeklyMetric, thisVals: [Double], lastVals: [Double],
                              baseVals: [Double]) -> WeeklyMetricSummary {
        let thisLatest = thisVals.last ?? 0
        let lastLatest = lastVals.last ?? 0
        let baseMean: Double? = baseVals.isEmpty ? nil
            : baseVals.reduce(0, +) / Double(baseVals.count)
        return WeeklyMetricSummary(
            metric: metric, thisWeek: ComparisonEngine.stat(thisVals),
            weekOverWeek: rolledComparison(thisVals, lastVals, this: thisLatest, last: lastLatest),
            baselineMean: baseMean,
            vsBaseline: thisVals.isEmpty ? nil : baseMean.map { thisLatest - $0 },
            rolledThis: thisLatest, rolledLast: lastLatest,
            personalSpread: personalSpread(baseVals, minCount: minDaysForFocus,
                                           floor: metric.spec.spreadFloor))
    }

    /// A PeriodComparison whose delta / % / direction are taken on rolled-up figures,
    /// keeping the per-day stats (and so the day counts the focal gate reads).
    /// Kotlin twin: `WeeklyDigestEngine.rolledComparison`.
    static func rolledComparison(_ cur: [Double], _ prev: [Double],
                                 this: Double, last: Double) -> PeriodComparison {
        let c = ComparisonEngine.stat(cur)
        let p = ComparisonEngine.stat(prev)
        let delta = this - last
        let pct: Double? = (p.n > 0 && last != 0) ? delta / abs(last) * 100.0 : nil
        let direction = (c.n == 0 || p.n == 0) ? 0 : (delta > 0 ? 1 : (delta < 0 ? -1 : 0))
        return PeriodComparison(current: c, previous: p, delta: delta, pctChange: pct,
                                direction: direction)
    }

    /// Personal SD of baseline values via `Baselines.rollingMeanSD` (σ floored at
    /// `floor`), or nil with fewer than `minCount` values.
    /// Kotlin twin: `WeeklyDigestEngine.personalSpread`.
    static func personalSpread(_ values: [Double], minCount: Int, floor: Double) -> Double? {
        guard values.count >= minCount else { return nil }
        let cfg = MetricCfg(minVal: -Double.greatestFiniteMagnitude,
                            maxVal: Double.greatestFiniteMagnitude,
                            floorSpread: floor, halfLifeB: 14.0, halfLifeS: 21.0)
        let state = Baselines.rollingMeanSD(values, cfg: cfg, window: values.count)
        // rollingMeanSD stores σ / 1.253 (its abs-dev space); undo that.
        return state.spread * 1.253
    }

    /// Whole days from `start` to `end` (both "yyyy-MM-dd"); 0 if either is unparseable.
    /// Kotlin twin: `WeeklyDigestEngine.dayIndex`.
    static func dayIndex(from start: String, to end: String) -> Int {
        guard let (y0, m0, d0) = parseYMD(start), let (y1, m1, d1) = parseYMD(end) else { return 0 }
        return julianDayNumber(y1, m1, d1) - julianDayNumber(y0, m0, d0)
    }

    // MARK: - Balance read

    /// Read this week's Effort against this week's Charge. Both are 0–100; a clearly
    /// higher Effort mean than Charge mean is "overreaching", clearly lower is
    /// "underloaded", within `balanceBand` is "balanced". Needs ≥ minDaysForFocus
    /// of each, else `.insufficient`.
    static let balanceBand: Double = 10.0

    static func balanceRead(_ summaries: [WeeklyMetricSummary]) -> BalanceRead {
        guard
            let effort = summaries.first(where: { $0.metric == .effort })?.thisWeek,
            let charge = summaries.first(where: { $0.metric == .charge })?.thisWeek,
            effort.n >= minDaysForFocus, charge.n >= minDaysForFocus
        else { return .insufficient }

        let gap = effort.mean - charge.mean
        if gap > balanceBand { return .overreaching }
        if gap < -balanceBand { return .underloaded }
        return .balanced
    }

    // MARK: - Focal points

    /// Pick 1–2 plain-English focal points, most salient first.
    ///
    /// Priority order:
    ///   1. The single biggest *meaningful* week-over-week mover (both weeks have
    ///      ≥ minDaysForFocus days, and the normalised move clears `focusThreshold`),
    ///      rendered with its good/bad framing.
    ///   2. Either the balance read (when not balanced/insufficient) OR the second
    ///      biggest mover — whichever is more salient — as a supporting line.
    ///
    /// If nothing clears the bar, a single steady-week line is returned.
    /// Kotlin twin: `WeeklyDigestEngine.focalPoints`.
    static let focusThreshold: Double = 0.5   // half a "typical spread" of movement

    static func focalPoints(summaries: [WeeklyMetricSummary],
                            balance: BalanceRead,
                            consistencySD: Double?,
                            effortDisplayFactor: Double = 1.0,
                            span: Int = 7) -> [String] {
        // Rank movers by |normalised move|, significant (enough days) first.
        let movers = summaries
            .filter { focusEligible($0, span: span) && abs($0.normalisedMove) >= focusThreshold }
            .sorted { abs($0.normalisedMove) > abs($1.normalisedMove) }

        var lines: [String] = []

        if let top = movers.first {
            lines.append(moverSentence(top, effortDisplayFactor: effortDisplayFactor))
        }

        // Supporting line: prefer a non-trivial balance read, else the 2nd mover.
        if balance == .overreaching || balance == .underloaded {
            lines.append(balance.sentence)
        } else if movers.count >= 2 {
            lines.append(moverSentence(movers[1], effortDisplayFactor: effortDisplayFactor))
        }

        // Nothing cleared the mover bar. Distinguish three very different reasons:
        //   • the CURRENT week is SPARSE (fewer than minDaysForFocus days in) — we simply
        //     can't call a week-over-week trend yet, even though the per-metric chips may
        //     show a big raw swing off 1–2 days. Saying "a steady week — nothing moved"
        //     there flatly contradicts those chips (the #463 report). Be honest instead.
        //   • the PREVIOUS week is SPARSE (typical new user in week 2): movers are gated
        //     on previous.n ≥ minDaysForFocus, so nothing can surface even when the chips
        //     show big raw %s off last week's 1–2 days — the same #463 contradiction,
        //     mirrored. Same honesty, aimed at last week.
        //   • the week has enough days and genuinely held even — the calm "steady" read.
        if lines.isEmpty {
            let currentDays = summaries.map { $0.weekOverWeek.current.n }.max() ?? 0
            let prevDays = summaries.map { $0.weekOverWeek.previous.n }.max() ?? 0
            if currentDays >= 1 && currentDays < minDaysForFocus {
                let dayWord = currentDays == 1 ? "day" : "days"
                lines.append("Only \(currentDays) \(dayWord) into this week so far, too early to "
                    + "call a week-over-week trend yet.")
            } else if currentDays >= minDaysForFocus, prevDays >= 1, prevDays < minDaysForFocus {
                let dayWord = prevDays == 1 ? "day" : "days"
                lines.append("Last week only had \(prevDays) \(dayWord) of data, so week-over-week "
                    + "changes are rough, not a trend.")
            } else if let sd = consistencySD, sd <= 6.0 {
                lines.append("A steady week: Rest held even (±\(round1(sd)) pts) and nothing moved much.")
            } else {
                lines.append("A steady week: no metric moved meaningfully from last week.")
            }
        }

        return Array(lines.prefix(2))
    }

    /// Structured twin of `focalPoints`: the same selection and order, as data.
    /// Kotlin twin: `WeeklyDigestEngine.focalEntries`.
    static func focalEntries(summaries: [WeeklyMetricSummary],
                             balance: BalanceRead,
                             consistencySD: Double?,
                             effortDisplayFactor: Double = 1.0,
                             span: Int = 7) -> [WeeklyFocal] {
        let movers = summaries
            .filter { focusEligible($0, span: span) && abs($0.normalisedMove) >= focusThreshold }
            .sorted { abs($0.normalisedMove) > abs($1.normalisedMove) }
        var lines: [WeeklyFocal] = []
        if let top = movers.first {
            lines.append(moverFocal(top, effortDisplayFactor: effortDisplayFactor))
        }
        if balance == .overreaching || balance == .underloaded {
            lines.append(.balance(balance))
        } else if movers.count >= 2 {
            lines.append(moverFocal(movers[1], effortDisplayFactor: effortDisplayFactor))
        }
        if lines.isEmpty {
            let currentDays = summaries.map { $0.weekOverWeek.current.n }.max() ?? 0
            let prevDays = summaries.map { $0.weekOverWeek.previous.n }.max() ?? 0
            if currentDays >= 1 && currentDays < minDaysForFocus {
                lines.append(.earlyWeek(days: currentDays))
            } else if currentDays >= minDaysForFocus, prevDays >= 1, prevDays < minDaysForFocus {
                lines.append(.sparseLastWeek(days: prevDays))
            } else if let sd = consistencySD, sd <= 6.0 {
                lines.append(.steadyRest(sd: "\(round1(sd))"))
            } else {
                lines.append(.steady)
            }
        }
        return Array(lines.prefix(2))
    }

    /// Structured twin of `moverSentence`. Kotlin twin: `WeeklyDigestEngine.moverFocal`.
    static func moverFocal(_ s: WeeklyMetricSummary,
                           effortDisplayFactor: Double = 1.0) -> WeeklyFocal {
        let spec = s.metric.spec
        let f = s.metric == .effort ? effortDisplayFactor : 1.0
        let delta = s.wowDelta > 0 ? 1 : (s.wowDelta < 0 ? -1 : 0)
        let magnitude: WeeklyFocal.Magnitude
        if spec.percentChange, let pct = s.weekOverWeek.pctChange, abs(pct) >= 1 {
            magnitude = .percent(roundedInt(abs(pct)))
        } else if spec.unit.isEmpty || spec.unit == "%" {
            magnitude = .points("\(round1(abs(s.wowDelta) * f))")
        } else {
            magnitude = .unit("\(round1(abs(s.wowDelta) * f))", spec.unit)
        }
        return .mover(metric: s.metric, delta: delta, magnitude: magnitude, rollup: spec.rollup,
                      thisValue: formatValue(s.thisValue * f, decimals: spec.decimals),
                      lastValue: formatValue(s.lastValue * f, decimals: spec.decimals),
                      goodness: s.wowGoodness)
    }

    /// Whether a metric has enough behind it to be a focal mover: an average needs
    /// `minDaysForFocus` days on each side; a total needs the week that far along; a
    /// latest reading needs one reading on each side.
    /// Kotlin twin: `WeeklyDigestEngine.focusEligible`.
    static func focusEligible(_ s: WeeklyMetricSummary, span: Int) -> Bool {
        let cur = s.weekOverWeek.current.n
        let prev = s.weekOverWeek.previous.n
        switch s.metric.spec.rollup {
        case .mean:   return cur >= minDaysForFocus && prev >= minDaysForFocus
        case .sum:    return span >= minDaysForFocus && cur >= 1 && prev >= 1
        case .latest: return cur >= 1 && prev >= 1
        }
    }

    /// Render one mover as a plain-English sentence, the way BehaviorInsights.sentence
    /// renders an effect. Folds in good/bad framing (a Charge rise is "up — good", a
    /// Resting HR rise is "up — worth a look"). `effortDisplayFactor` rescales the
    /// EFFORT averages (and its pts fallback) for display only — % is scale-invariant.
    static func moverSentence(_ s: WeeklyMetricSummary,
                              effortDisplayFactor: Double = 1.0) -> String {
        let spec = s.metric.spec
        let f = s.metric == .effort ? effortDisplayFactor : 1.0
        let directionWord = s.wowDelta > 0 ? "up" : (s.wowDelta < 0 ? "down" : "flat")
        let magnitude: String
        if spec.percentChange, let pct = s.weekOverWeek.pctChange, abs(pct) >= 1 {
            magnitude = "\(roundedInt(abs(pct)))%"
        } else {
            let suffix = (spec.unit.isEmpty || spec.unit == "%") ? " pts" : " " + spec.unit
            magnitude = "\(round1(abs(s.wowDelta) * f))\(suffix)"
        }
        let frame: String
        switch s.wowGoodness {
        case 1:  frame = ", a good sign"
        case -1: frame = ", worth a look"
        default: frame = ""
        }
        let thisAvg = formatValue(s.thisValue * f, decimals: spec.decimals)
        let lastAvg = formatValue(s.lastValue * f, decimals: spec.decimals)
        let word: String
        switch spec.rollup {
        case .mean:   word = "avg"
        case .sum:    word = "total"
        case .latest: word = "latest"
        }
        return "\(spec.label) is \(directionWord) \(magnitude) week over week"
            + " (\(word) \(thisAvg) vs \(lastAvg))\(frame)."
    }

    // MARK: - Range extraction

    /// Collect the values of `series` whose day is within [start, end] inclusive
    /// (ISO string comparison is chronological), ordered chronologically so the
    /// resulting SeriesStat slope is meaningful. When `daysSeen` is non-nil, the days
    /// that carried a value are recorded into it (pass `&someNilOptional` to skip).
    static func valuesInRange(_ series: [String: Double], start: String, end: String,
                              daysSeen: inout Set<String>?) -> [Double] {
        let inRange = series.filter { $0.key >= start && $0.key <= end }
        if daysSeen != nil {
            for k in inRange.keys { daysSeen?.insert(k) }
        }
        // Sort by day string so the slope is chronological regardless of dict order.
        return inRange.sorted { $0.key < $1.key }.map { $0.value }
    }

    // MARK: - Pure week math (timezone/locale-free)

    /// The Monday (ISO "yyyy-MM-dd") of the week containing `day`. nil if `day`
    /// can't be parsed as a valid yyyy-MM-dd.
    public static func mondayOfWeek(containing day: String) -> String? {
        guard let (y, m, d) = parseYMD(day), let w = weekday(y, m, d) else { return nil }
        // weekday: 0=Sunday … 6=Saturday. Days since Monday: Mon=0 … Sun=6.
        let sinceMonday = (w + 6) % 7
        return addDays(day, -sinceMonday)
    }

    /// Add `n` days (may be negative) to a "yyyy-MM-dd" day, returning "yyyy-MM-dd".
    /// Falls back to the input string if it can't be parsed.
    public static func addDays(_ day: String, _ n: Int) -> String {
        guard let (y, m, d) = parseYMD(day) else { return day }
        let jdn = julianDayNumber(y, m, d) + n
        let (ny, nm, nd) = fromJulianDayNumber(jdn)
        return formatYMD(ny, nm, nd)
    }

    /// Sakamoto's day-of-week: 0=Sunday, 1=Monday … 6=Saturday. nil for an invalid
    /// calendar date.
    static func weekday(_ y: Int, _ m: Int, _ d: Int) -> Int? {
        guard (1...12).contains(m), d >= 1, d <= daysInMonth(y, m) else { return nil }
        let t = [0, 3, 2, 5, 0, 3, 5, 1, 4, 6, 2, 4]
        var yy = y
        if m < 3 { yy -= 1 }
        return (yy + yy / 4 - yy / 100 + yy / 400 + t[m - 1] + d) % 7
    }

    /// Days in a month, leap-year aware.
    static func daysInMonth(_ y: Int, _ m: Int) -> Int {
        switch m {
        case 1, 3, 5, 7, 8, 10, 12: return 31
        case 4, 6, 9, 11:           return 30
        case 2: return isLeap(y) ? 29 : 28
        default: return 0
        }
    }

    static func isLeap(_ y: Int) -> Bool { (y % 4 == 0 && y % 100 != 0) || (y % 400 == 0) }

    /// Parse "yyyy-MM-dd" into integer components, validating the date is real.
    /// Public so UI layers can format week-range labels without re-implementing the
    /// (timezone-free) date parse.
    public static func parseYMD(_ s: String) -> (Int, Int, Int)? {
        let parts = s.split(separator: "-", omittingEmptySubsequences: false)
        guard parts.count == 3,
              let y = Int(parts[0]), let m = Int(parts[1]), let d = Int(parts[2]),
              (1...12).contains(m), d >= 1, d <= daysInMonth(y, m) else { return nil }
        return (y, m, d)
    }

    /// Zero-padded "yyyy-MM-dd".
    static func formatYMD(_ y: Int, _ m: Int, _ d: Int) -> String {
        let yy = y < 1000 ? String(format: "%04d", y) : "\(y)"
        let mm = m < 10 ? "0\(m)" : "\(m)"
        let dd = d < 10 ? "0\(d)" : "\(d)"
        return "\(yy)-\(mm)-\(dd)"
    }

    /// Convert a proleptic-Gregorian date to a Julian Day Number (integer-only,
    /// timezone-free). Used purely for date arithmetic.
    static func julianDayNumber(_ y: Int, _ m: Int, _ d: Int) -> Int {
        let a = (14 - m) / 12
        let yy = y + 4800 - a
        let mm = m + 12 * a - 3
        return d + (153 * mm + 2) / 5 + 365 * yy + yy / 4 - yy / 100 + yy / 400 - 32045
    }

    /// Inverse of `julianDayNumber`.
    static func fromJulianDayNumber(_ jdn: Int) -> (Int, Int, Int) {
        let a = jdn + 32044
        let b = (4 * a + 3) / 146097
        let c = a - (146097 * b) / 4
        let dd = (4 * c + 3) / 1461
        let e = c - (1461 * dd) / 4
        let mm = (5 * e + 2) / 153
        let day = e - (153 * mm + 2) / 5 + 1
        let month = mm + 3 - 12 * (mm / 10)
        let year = 100 * b + dd - 4800 + mm / 10
        return (year, month, day)
    }

    // MARK: - Empty digest

    static func emptyDigest(weekStart: String, weekEnd: String) -> WeeklyDigest {
        let summaries = WeeklyMetric.allCases.map { m in
            WeeklyMetricSummary(metric: m, thisWeek: .empty,
                                weekOverWeek: ComparisonEngine.compare(current: [], previous: []),
                                baselineMean: nil, vsBaseline: nil)
        }
        return WeeklyDigest(weekStart: weekStart, weekEnd: weekEnd, metrics: summaries,
                            daysWithData: 0, sleepConsistencySD: nil,
                            balance: .insufficient, focalPoints: [])
    }

    // MARK: - Formatting helpers (mirror BehaviorInsights)

    static func roundedInt(_ x: Double) -> Int { Int(x.rounded()) }
    /// A weekly value for narration: whole number, or one decimal (never "-0.0").
    /// Kotlin twin: `WeeklyDigestEngine.formatValue`.
    static func formatValue(_ x: Double, decimals: Int) -> String {
        decimals == 0 ? "\(roundedInt(x))" : "\(round1(x) + 0.0)"
    }
    static func round1(_ x: Double) -> Double { (x * 10).rounded() / 10 }
}

// MARK: - Card sections (#90)

/// How the weekly card groups the metrics after the headline five. Shared with Android so
/// both cards list the same rows in the same order. Kotlin twin: `WeeklyDigestSection`.
public enum WeeklyDigestSection: String, CaseIterable, Sendable {
    case activity
    case sleep
    case overnightVitals
    case stressEnergy
    case training
    case heart
    case workouts
    case bodyComposition
    case fitnessScores
    case nutrition
    case mind

    /// The section's metrics, in display order.
    public var metrics: [WeeklyMetric] {
        switch self {
        case .activity:        return [.steps, .activeKcal, .totalKcal, .basalKcal, .distance, .intensityMinutes]
        case .sleep:           return [.sleepDuration, .sleepEfficiency, .deepSleep, .remSleep, .lightSleep, .sleepScore,
                                       .sleepNeed, .sleepDebt, .sleepConsistency, .disturbances]
        case .overnightVitals: return [.spo2, .respiration, .skinTempDeviation]
        case .stressEnergy:    return [.stress, .bodyBatteryRecharge]
        case .training:        return [.trainingLoad, .vo2max]
        case .heart:           return [.avgHr, .maxHr, .sdnn]
        case .workouts:        return [.workoutCount, .workoutMinutes, .workoutKcal, .workoutMaxHr, .workoutPower,
                                       .workoutCadence, .workoutElevation, .aerobicTe, .anaerobicTe]
        case .bodyComposition: return [.weight, .bodyFat, .leanMass, .bmi]
        case .fitnessScores:   return [.fitnessAge, .vitality, .bodyAge, .vo2maxEst]
        case .nutrition:       return [.caloriesIn, .protein, .carbs, .fat, .water]
        case .mind:            return [.mood]
        }
    }
}

/// One section's rows: only the metrics that carried a reading this week.
public struct WeeklyDigestSectionRows: Equatable, Sendable {
    public let section: WeeklyDigestSection
    public let rows: [WeeklyMetricSummary]
}

extension WeeklyDigestEngine {
    /// The card's sections for `digest`: a row only when its metric has data this week, and
    /// a section only when it has a row. Kotlin twin: `WeeklyDigestEngine.sections`.
    public static func sections(_ digest: WeeklyDigest) -> [WeeklyDigestSectionRows] {
        WeeklyDigestSection.allCases.compactMap { section in
            let rows = section.metrics.compactMap { digest.summary($0) }.filter { $0.hasData }
            return rows.isEmpty ? nil : WeeklyDigestSectionRows(section: section, rows: rows)
        }
    }
}
