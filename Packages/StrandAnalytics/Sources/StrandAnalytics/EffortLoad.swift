import Foundation

/// Daily training load on a LINEAR axis, and the descriptive load ratios built on it.
///
/// Day Effort is `100 × ln(TRIMP + 1) / ln(D)`: a log compression meant for a 0–100 gauge. Averaging or
/// smoothing that number under-reacts, because doubling the work done moves Effort by only a few points.
/// Load models (ACWR, monotony, CTL/ATL) need the work itself, so they read TRIMP.
///
/// TRIMP is recovered from the stored Effort by inverting the log map, not read from a second stored
/// column. The map is strictly monotonic, so the inverse is exact up to the 2 dp rounding `trimpToStrain`
/// applies: at most 0.045 % of `TRIMP + 1`. That is below any physiological resolution, and it means every
/// existing row (imports included) gets a load value without a backfill, and the load series can never
/// disagree with the Effort the same day shows.
///
/// The inverse uses Edwards' denominator. A day scored by Banister has its own D, so its recovered TRIMP
/// sits on the Edwards axis rather than Banister's own. The ratios here are scale-free, so only the
/// absolute CTL/ATL figures carry that difference.
///
/// Kotlin twin: `EffortLoad`.
public enum EffortLoad {
    /// Acute EWMA span (days).
    public static let acuteSpanDays = 7
    /// Chronic EWMA span (days).
    public static let chronicSpanDays = 28
    /// Observed days required inside the last `chronicSpanDays` calendar days before a ratio is emitted.
    public static let minChronicObservedDays = 14
    /// Calendar days the EWMA runs over, ending on the target day. Three chronic spans: by then the seed's
    /// weight in the chronic state is below 3 %.
    public static let runInDays = 84
    /// Observed days required inside the last week before Foster monotony is emitted.
    public static let minMonotonyDays = 4

    /// TRIMP recovered from a day's Effort (0–100). nil for a non-finite or negative Effort.
    ///
    /// Kotlin twin: `EffortLoad.trimpFromEffort`.
    public static func trimp(fromEffort effort: Double) -> Double? {
        guard effort.isFinite, effort >= 0 else { return nil }
        return exp(effort / StrainScorer.maxStrain * log(StrainScorer.strainDenominator)) - 1.0
    }

    /// Williams et al. (2017) EWMA decay for a span of `days`: λ = 2 / (N + 1).
    ///
    /// Kotlin twin: `EffortLoad.ewmaLambda`.
    public static func ewmaLambda(spanDays: Int) -> Double {
        2.0 / Double(spanDays + 1)
    }

    /// Acute and chronic exponentially weighted load through `endOrdinal` (inclusive).
    public struct AcuteChronic: Sendable, Equatable {
        public let acute: Double
        public let chronic: Double
        /// Observed days in the last `chronicSpanDays` calendar days.
        public let observedDays: Int
        /// acute / chronic, or nil when chronic is 0.
        public var ratio: Double? { chronic > 0 ? acute / chronic : nil }
    }

    /// EWMA acute:chronic load over CALENDAR days ending on `endOrdinal`.
    ///
    /// `loads` maps a day ordinal (see `TrainingLoadEngine.dayOrdinal`) to that day's TRIMP. A day with no
    /// entry was not observed; the states HOLD across it rather than decay toward zero. A day with no
    /// stored row is a day the strap was not worn, not a rest day (a worn rest day still has its own small
    /// Effort row), so zero-filling it would invent rest, the same rule `TrainingLoadEngine` keeps. What
    /// calendar days do change is the reach: the run is bounded to the last `runInDays` calendar days, so
    /// a sparse history can no longer stretch a "28-day" mean across half a year.
    ///
    /// Both states are seeded with the first observed load in the run. nil until at least `minObserved` of
    /// the last `chronicSpan` calendar days are observed. The spans default to `acuteSpanDays` /
    /// `chronicSpanDays` / `minChronicObservedDays`; a caller with its own named tunables passes them.
    ///
    /// Descriptive only: the ratio describes how recent load compares with the recent norm. It is not an
    /// injury predictor (Impellizzeri et al. 2020).
    ///
    /// Kotlin twin: `EffortLoad.acuteChronic`.
    public static func acuteChronic(loads: [Int: Double], endOrdinal: Int,
                                    acuteSpan: Int = acuteSpanDays, chronicSpan: Int = chronicSpanDays,
                                    minObserved: Int = minChronicObservedDays) -> AcuteChronic? {
        var observed = 0
        for d in (endOrdinal - chronicSpan + 1)...endOrdinal where loads[d] != nil { observed += 1 }
        guard observed >= minObserved else { return nil }
        let lambdaAcute = ewmaLambda(spanDays: acuteSpan)
        let lambdaChronic = ewmaLambda(spanDays: chronicSpan)
        var acute: Double? = nil
        var chronic = 0.0
        for d in (endOrdinal - runInDays + 1)...endOrdinal {
            guard let x = loads[d] else { continue }
            if let a = acute {
                acute = a + lambdaAcute * (x - a)
                chronic += lambdaChronic * (x - chronic)
            } else {
                acute = x
                chronic = x
            }
        }
        guard let a = acute else { return nil }
        return AcuteChronic(acute: a, chronic: chronic, observedDays: observed)
    }

    /// Foster (1998) monotony, mean / sample SD, over the observed loads in the `spanDays` (default
    /// `acuteSpanDays`) calendar days ending on `endOrdinal`. nil with fewer than `minMonotonyDays` observed days or no spread
    /// (SD at or below 1e-9 of the mean).
    ///
    /// Kotlin twin: `EffortLoad.monotony`.
    public static func monotony(loads: [Int: Double], endOrdinal: Int, spanDays: Int = acuteSpanDays) -> Double? {
        var week: [Double] = []
        for d in (endOrdinal - spanDays + 1)...endOrdinal {
            if let x = loads[d] { week.append(x) }
        }
        guard week.count >= minMonotonyDays else { return nil }
        let m = week.reduce(0, +) / Double(week.count)
        let ss = week.reduce(0) { $0 + ($1 - m) * ($1 - m) }
        let sd = (ss / Double(week.count - 1)).squareRoot()
        // Relative, not `sd > 0`: a week of identical loads sums with rounding noise, and a 1e-16 spread
        // would read as monotony ~1e16 rather than "no spread".
        guard sd > m * 1e-9 else { return nil }
        return m / sd
    }

    /// Day ordinal → TRIMP for every row with a usable Effort. Rows sharing a day keep the larger load,
    /// so the result does not depend on input order.
    ///
    /// Kotlin twin: `EffortLoad.loadsByOrdinal`.
    public static func loadsByOrdinal(_ rows: [(day: String, effort: Double?)]) -> [Int: Double] {
        var out: [Int: Double] = [:]
        for row in rows {
            guard let e = row.effort, let t = trimp(fromEffort: e),
                  let ordinal = TrainingLoadEngine.dayOrdinal(row.day) else { continue }
            out[ordinal] = max(out[ordinal] ?? t, t)
        }
        return out
    }
}
