import Foundation

// SleepDebt.swift — a recency-weighted sleep-debt estimate over the last N nights.
//
// Pure, deterministic, DB-free. Given a chronological series of per-night total
// sleep (minutes) and a personal sleep need (hours), it accumulates a running
// debt estimate across a capped trailing window (14 calendar days by default). The
// previous estimate is part of the following night's need; 55% of any unmet need
// carries forward, and a day with no record decays the debt by the same factor.
// Tiny, non-actionable results below ten minutes are cleared. The engine-side
// funnel (`SleepNeed.contexts`) runs the same step with per-night need.
//
// HONEST by construction:
//   - It is a planning estimate, not a physiological claim. Meeting base need plus
//     the current estimate clears the debt; extra sleep never creates a surplus.
//   - The window is capped (default 14 days) so "debt" never compounds indefinitely
//     across months of history — only the recent fortnight is in scope.
//   - Nights with no usable sleep total are never zero-filled, so a gap in wear
//     never reads as a full night of debt; the debt just decays across it.
//   - The need value is supplied by the caller (AnalyticsEngine.Rest.defaultNeedHours
//     = 8.0 by default; the caller passes any personal override). Computation here
//     stays a pure function of (series, need, window).
//
// Constant-explicit + dependency-free so the Kotlin mirror (android … SleepDebt.kt)
// is byte-identical.

/// One night's contribution to the ledger: its day key, minutes slept, and the
/// signed delta against need (positive = surplus, negative = deficit).
public struct SleepDebtNight: Equatable, Sendable {
    /// "yyyy-MM-dd" day key for the night (as carried on the DailyMetric).
    public let day: String
    /// Total sleep for the night (minutes).
    public let sleptMin: Double
    /// Signed delta vs need (minutes): sleptMin − needMin. Positive = surplus.
    public let deltaMin: Double

    public init(day: String, sleptMin: Double, deltaMin: Double) {
        self.day = day; self.sleptMin = sleptMin; self.deltaMin = deltaMin
    }
}

/// The sleep-debt estimate over the capped trailing window.
public struct SleepDebtLedger: Equatable, Sendable {
    /// Current estimate in the existing signed UI convention: negative = debt,
    /// zero = on target. This model never reports a positive surplus.
    public let balanceMin: Double
    /// Per-night contributions, oldest → newest, one per counted night (skipped
    /// nights are absent). The `deltaMin` values are the per-night bar/spark.
    public let nights: [SleepDebtNight]
    /// Personal sleep need (minutes) the ledger was computed against (for labelling).
    public let needMin: Double

    public init(balanceMin: Double, nights: [SleepDebtNight], needMin: Double) {
        self.balanceMin = balanceMin; self.nights = nights; self.needMin = needMin
    }

    /// Number of nights that contributed (nights with usable sleep data).
    public var nightCount: Int { nights.count }
    /// Convenience: true when the net balance is a debt (under need overall).
    public var isDebt: Bool { balanceMin < 0 }
    /// Magnitude of the balance in minutes, regardless of sign.
    public var magnitudeMin: Double { abs(balanceMin) }
}

public enum SleepDebt {

    /// Cap the ledger at the trailing two weeks — recent enough to be actionable,
    /// short enough that one rough patch doesn't read as months of compounding debt.
    public static let defaultWindowNights: Int = 14

    /// Share of the complete unmet need carried into the following night.
    public static let debtCarryFactor: Double = 0.55

    /// Calculated debts smaller than this are too small to be actionable.
    public static let minimumDebtMin: Double = 10.0

    /// "On target" deadband (minutes): a |balance| under this reads as balanced rather
    /// than as a debt/surplus, so a few stray minutes don't flip the headline.
    public static let onTargetBandMin: Double = minimumDebtMin

    /// Sleep credited toward debt for one day. `mainSleepMin` remains the canonical
    /// main-night total used by Rest and the sleep headline; separately-recorded naps
    /// add repayment credit without changing that canonical figure. A day with no
    /// usable main sleep stays missing, and a malformed negative nap total is ignored.
    ///
    /// This is deliberately arithmetic rather than a physiological model: callers
    /// classify the day's main-night group and pass only asleep minutes from blocks
    /// outside that group. Mirrored value-for-value in Kotlin `SleepDebt`.
    public static func creditedSleepMin(mainSleepMin: Double?, napSleepMin: Double = 0) -> Double? {
        guard let mainSleepMin, mainSleepMin > 0 else { return nil }
        return mainSleepMin + max(napSleepMin, 0)
    }

    /// Debt values oldest → newest for local fallback surfaces. Each usable day's value is the ledger
    /// through that day over the trailing `window` CALENDAR days. An imported value wins verbatim for
    /// that day's output only; it never seeds or replaces the local recurrence. Rows with neither
    /// usable sleep nor an imported value have no debt observation and are omitted.
    public static func debtSeries(
        series: [(day: String, totalSleepMin: Double?)],
        needHours: Double = AnalyticsEngine.Rest.defaultNeedHours,
        importedDebtMin: [String: Double] = [:],
        window: Int = defaultWindowNights
    ) -> [(day: String, value: Double)] {
        var result: [(day: String, value: Double)] = []
        result.reserveCapacity(series.count)
        for (i, row) in series.enumerated() {
            let sleptMin = row.totalSleepMin.flatMap { $0 > 0 ? $0 : nil }
            if let imported = importedDebtMin[row.day] {
                result.append((row.day, imported))
            } else if sleptMin != nil {
                let prefix = Array(series[max(0, i - 4 * max(window, 1))...i])
                result.append((row.day, ledger(series: prefix, needHours: needHours, window: window).magnitudeMin))
            }
        }
        return result
    }

    /// Build the ledger from a chronological `[(day, totalSleepMin?)]` series.
    ///
    /// - Parameters:
    ///   - series: per-night `(day, totalSleepMin)` rows. A nil or non-positive `totalSleepMin` marks a
    ///     night with no usable data. A repeated day keeps its last row.
    ///   - needHours: sleep need (hours) each night is measured against.
    ///   - window: CALENDAR days ending at the newest usable night. Clamped to ≥ 1.
    ///
    /// Day by day: a recorded night carries `0.55 × max(0, need + debt − slept)`; a day with no record
    /// decays the debt by the same 0.55 (as if need were met), so the decay is per calendar day, not per
    /// night with data (#55). Results below ten minutes clear to zero. `SleepDebtNight.deltaMin` stays
    /// the raw `slept − need` used by the per-night bars.
    public static func ledger(series: [(day: String, totalSleepMin: Double?)],
                              needHours: Double = AnalyticsEngine.Rest.defaultNeedHours,
                              window: Int = defaultWindowNights) -> SleepDebtLedger {
        let needMin = max(needHours, 0.0) * 60.0
        let cap = max(window, 1)
        var byOrd: [Int: (day: String, slept: Double)] = [:]
        for row in series {
            guard let o = DayOrdinal.ordinal(row.day) else { continue }
            if let m = row.totalSleepMin, m > 0 { byOrd[o] = (row.day, m) } else { byOrd[o] = nil }
        }
        guard let end = byOrd.keys.max() else {
            return SleepDebtLedger(balanceMin: 0, nights: [], needMin: needMin)
        }
        var nights: [SleepDebtNight] = []
        var debt = 0.0
        for o in (end - cap + 1)...end {
            let night = byOrd[o]
            debt = SleepNeed.step(debt: debt, needMin: needMin, creditedMin: night?.slept)
            if let night {
                nights.append(SleepDebtNight(day: night.day, sleptMin: night.slept, deltaMin: night.slept - needMin))
            }
        }
        return SleepDebtLedger(balanceMin: round1(-debt), nights: nights, needMin: needMin)
    }

    /// Round to 1 decimal place (the ledger is reported in whole/near-whole minutes;
    /// 1 dp keeps Σ stable without trailing float noise). Mirrors the Kotlin rounding.
    static func round1(_ v: Double) -> Double { (v * 10.0).rounded() / 10.0 }
}
