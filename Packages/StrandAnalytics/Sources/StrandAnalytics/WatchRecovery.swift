import Foundation
import WhoopStore

// WatchRecovery.swift — recovery/Charge from Apple Watch DAILY aggregates.
//
// The honesty-critical piece of "Apple Watch as a device". A WHOOP strap gives us
// dense overnight RR intervals, so RecoveryScorer runs off raw-derived nightly RMSSD.
// The Apple Watch does NOT. It gives a handful of HRV SDNN readings a day plus a
// resting HR, both as daily aggregates. So watch recovery is a genuinely lower-density
// computation.
//
// We do NOT invent a new formula. Recovery is HRV-and-RHR-vs-personal-baseline, and
// because every term is relative to the person's OWN baseline, the metric scale cancels
// out: SDNN-vs-SDNN-baseline behaves like RMSSD-vs-RMSSD-baseline. So we build SDNN and
// RHR baselines with the strap's Charge baselines (`ChargeBaselines`, trailing 60 days,
// HRV in the log domain; #53) and feed them straight into the SAME
// `RecoveryScorer.recovery(...)` the strap uses. Watch recovery and strap
// recovery therefore land on the same 0-100 scale and read against the same bands.
//
// What we drop vs the strap path: the respiration, sleep-performance and skin-temp terms
// are not supplied here (the watch's daily aggregate doesn't carry them in the same shape),
// so RecoveryScorer renormalises the remaining HRV + RHR weights. The HRV term stays the
// dominant driver either way.
//
// The hard honesty rule: we return nil recovery + `.calibrating` when today's SDNN is
// missing, OR the SDNN baseline isn't usable yet, OR the baseline has accepted fewer than
// `minBaselineNights` nights. We NEVER fabricate a number to fill a sparse week. Confidence comes
// straight from the existing `ScoreConfidence.charge(recovery:hrvBaseline:)`, so the watch
// "calibrating → building → solid" arc is the same one the strap uses.
public enum WatchRecovery {

    /// Result of a watch-recovery computation: the score (nil while calibrating) and its
    /// confidence tier. Same shape the strap path carries onto a DailyMetric.
    public struct Result: Equatable, Sendable {
        /// Recovery in [0, 100], or nil when we can't honestly score yet (calibrating).
        public let recovery: Double?
        /// Per-score confidence, driven by the real SDNN-baseline density (not a hardcoded label).
        public let confidence: ScoreConfidence

        public init(recovery: Double?, confidence: ScoreConfidence) {
            self.recovery = recovery
            self.confidence = confidence
        }
    }

    /// Minimum VALID nights (nights the baseline accepted, `BaselineState.nValid`) before we'll score
    /// recovery from the watch — not raw history entries. The spec's
    /// honesty stance is to keep recovery "calibrating" for about a week of nights rather than
    /// ship a misleading number off a thin baseline. This sits ABOVE the baseline's own seed
    /// gate (`Baselines.minNightsSeed` = 4) deliberately: a strap user crosses the seed faster
    /// on dense data, but the watch's sparse SDNN deserves a longer warm-up before we trust it.
    public static let minBaselineNights = 7

    /// Charge for one day of a daily-aggregate source (Apple Watch SDNN, or an import-only wearable's
    /// nightly HRV) against that source's own trailing baselines (#53).
    ///
    /// The baselines are the Charge ones: `ChargeBaselines.resolveDay` over this source's rows, so HRV is
    /// lnRMSSD-style (log domain), every baseline is the mean and sample SD of the valid nights in the
    /// 60 days before `day`, and today's value never sees itself. One funnel: the engine's watch and
    /// import Charge fold and the dashboard's watch confidence pill both call this, so the pill can never
    /// read a different baseline from the one the number was scored on.
    ///
    /// - Parameters:
    ///   - day: the scored `yyyy-MM-dd` day.
    ///   - rows: this source's daily rows, any order; the row for `day` carries today's values.
    /// - Returns: recovery in [0,100] with its confidence, or nil recovery + `.calibrating` when today's
    ///   HRV is missing, the HRV baseline isn't usable, or it holds fewer than `minBaselineNights` valid
    ///   nights. Never a fabricated value. Kotlin twin: `WatchRecovery.score`.
    public static func score(day: String, rows: [DailyMetric]) -> Result {
        let calibrating = Result(recovery: nil, confidence: .calibrating)
        let prior = rows.filter { $0.day < day }
        guard let resolved = ChargeBaselines.resolveDay(scoredDay: day, imported: .init(),
                                                        own: ChargeBaselines.Nights(rows: prior), seed: .init(),
                                                        sourceDays: [], hrvRecalibration: 0,
                                                        recoveryRecalibration: 0) else { return calibrating }
        let today = rows.last { $0.day == day }
        let hrvBase = resolved.hrv
        let rhrBase = resolved.restingHR
        let todayHrv = today?.avgHrv
        let todayRHR = today?.restingHr

        // Confidence is the SAME helper the strap Charge uses, so the calibrating → building → solid
        // arc matches. It reads .calibrating whenever recovery would be nil (no usable HRV baseline).
        let conf = ScoreConfidence.charge(recovery: todayHrv, hrvBaseline: hrvBase)

        // Honesty gate: no number unless we have today's HRV, a usable baseline, AND at least a week of
        // valid nights in it. A physiologically implausible reading never enters the baseline, so it
        // cannot buy a score early.
        guard let hrv = todayHrv, hrvBase.usable, hrvBase.nValid >= minBaselineNights else {
            return calibrating
        }

        // Reuse the canonical Charge scorer. Resp / sleep / skin-temp are not supplied by a daily
        // aggregate, so RecoveryScorer renormalises to HRV + RHR. The RHR term needs BOTH today's reading
        // AND a usable personal RHR baseline; without either it is dropped (the documented HRV-only path).
        let recovery = RecoveryScorer.recovery(
            hrv: hrv,
            rhr: todayRHR.map(Double.init) ?? rhrBase.baseline,
            resp: nil,
            hrvBaseline: hrvBase,
            rhrBaseline: (todayRHR != nil && rhrBase.usable) ? rhrBase : nil,
            respBaseline: nil,
            sleepPerf: nil,
            hrvPriorWeek: resolved.hrvPriorWeek
        )
        guard let recovery else { return calibrating }
        return Result(recovery: recovery, confidence: conf)
    }
}
