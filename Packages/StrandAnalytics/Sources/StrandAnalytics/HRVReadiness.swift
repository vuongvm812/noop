import Foundation

// HRVReadiness.swift — OPT-IN experimental "HRV readiness (Plews/Altini)" tier readout.
//
// The Kotlin twin is android/.../analytics/HRVReadiness.kt. Cross-platform parity is the contract: every
// constant, gate, and formula here must stay byte-identical to that source.
//
// This is a PURE, deterministic, DB-free engine and it does NOT change the default Charge/ring in any way
// — it is only surfaced (read-only) behind the PuffinExperiment `noopHrvReadiness` flag (off by default)
// and feeds NO downstream gate. It reimplements the endurance-science "smallest worthwhile change" (SWC)
// reading of nightly HRV popularised by Plews et al. and Marco Altini: work in the LOG domain (ln RMSSD is
// closer to normal and stabilises variance), compare a short 7-night rolling baseline against a longer
// personal normal band (±0.5 SD), and read the tier off where the short baseline sits relative to that
// band. A single bad night barely moves the 7-night baseline, so the reading is far more robust than a raw
// single-night z.
//
// INPUT: day-keyed nightly RMSSD (DailyMetric.day + avgHrv) in ms; windows are CALENDAR days ending today (#53)
// (nils allowed for missing nights). Physiologically implausible nights are dropped
// through the shared Baselines.hrvCfg bounds (5..250 ms) so one decode glitch can't skew it.
//
// OUTPUT (HRVReadinessResult): the tier (primed / normal / suppressed), the 7-night baseline and the
// personal normal band (all back in ms via exp), and an informational overreaching-watch flag. Returns nil
// (the honest ".calibrating" edge) below `minNights` valid nights: never a fabricated number. Mirrors the
// WatchRecovery nil+.calibrating honesty pattern.
//
// This is a rough / early experiment: it is NOT yet validated against varying real data (n=1). Outputs are
// APPROXIMATE wellness estimates, not medical advice.

/// Where tonight's 7-night HRV baseline sits vs the personal normal band. Mirrors Kotlin `ReadinessTier`.
public enum ReadinessTier: String, Equatable, Sendable {
    case primed
    case normal
    case suppressed
}

/// Result of an `HRVReadiness.evaluate` pass. All `*Ms` fields are back in milliseconds (exp of the log
/// domain the engine works in). Mirrors Kotlin `HRVReadinessResult`.
public struct HRVReadinessResult: Equatable, Sendable {
    /// Tier from the SWC comparison of the 7-night baseline against the personal normal band.
    public let tier: ReadinessTier
    /// 7-night rolling HRV baseline (ms) = exp(mean of ln RMSSD over the trailing rollWindow nights).
    public let baseline7Ms: Double
    /// Lower edge of the personal normal band (ms) = exp(longMean − 0.5·longSD).
    public let normalLowMs: Double
    /// Upper edge of the personal normal band (ms) = exp(longMean + 0.5·longSD).
    public let normalHighMs: Double
    /// Informational only (does NOT change the tier): a sustained falling CV trend while the 7-night
    /// baseline sits below the long mean — the classic parasympathetic-overreaching signature.
    public let overreachingWatch: Bool

    public init(tier: ReadinessTier, baseline7Ms: Double, normalLowMs: Double, normalHighMs: Double,
                overreachingWatch: Bool) {
        self.tier = tier
        self.baseline7Ms = baseline7Ms
        self.normalLowMs = normalLowMs
        self.normalHighMs = normalHighMs
        self.overreachingWatch = overreachingWatch
    }
}

/// OPT-IN experimental HRV-readiness (Plews/Altini SWC) engine. Mirrors Kotlin `HRVReadiness`.
public enum HRVReadiness {

    // MARK: - Constants (name them; mirror Kotlin exactly)

    /// Short rolling baseline window, in CALENDAR days ending today (#53: it used to be the last 7 valid
    /// nights, which after a week off the strap could span a month).
    public static let rollWindow: Int = 7
    /// Long personal-normal window, in calendar days ending today.
    public static let longWindow: Int = 60
    /// Fewest valid nights the 7-day window must hold for a reading (and for one CV point).
    public static let minRollNights: Int = 3
    /// Smallest-worthwhile-change half-width factor: the normal band is longMean ± swcK·longSD.
    public static let swcK: Double = 0.5
    /// Minimum VALID nights inside the long window before a reading is offered (else nil + the honest
    /// calibrating edge). Its OWN constant, deliberately NOT aliased to `WatchRecovery.minBaselineNights`.
    public static let minNights: Int = 14
    /// Calendar days over which the CV trend (overreaching watch) is fit.
    public static let cvTrendWindow: Int = 28
    /// Floor on the long-window SD so a flat history yields a deterministic, tiny non-degenerate normal band
    /// instead of an ULP-fragile zero-width one.
    public static let longSDFloor: Double = 1e-9

    // MARK: - Evaluate

    /// Compute the Plews/Altini HRV-readiness reading over CALENDAR windows ending `today`. Returns nil
    /// (calibrating) below `minNights` valid nights in the long window or `minRollNights` in the last 7
    /// days — never a fabricated value.
    ///
    /// - Parameters:
    ///   - dayKeys: `yyyy-MM-dd` per value (any order; unparseable keys and days after `today` are dropped).
    ///   - avgHrv: nightly RMSSD (ms), parallel to `dayKeys`; nils = missing nights. Out-of-range nights
    ///     (outside `Baselines.hrvCfg` 5..250 ms) are dropped as decode artefacts.
    ///   - today: the day the windows end on.
    public static func evaluate(dayKeys: [String], avgHrv: [Double?], today: String) -> HRVReadinessResult? {
        let cfg = Baselines.hrvCfg
        guard let t = Baselines.isoEpochDay(today) else { return nil }
        // (epoch day, ln RMSSD) for every valid night on or before today, oldest first. A repeated day keeps
        // its last value.
        var byDay: [Int: Double] = [:]
        for i in 0..<min(dayKeys.count, avgHrv.count) {
            guard let v = avgHrv[i], cfg.minVal <= v && v <= cfg.maxVal,
                  let d = Baselines.isoEpochDay(dayKeys[i]), d <= t else { continue }
            byDay[d] = log(max(v, 1.0))
        }
        let nights = byDay.keys.sorted().map { (day: $0, ell: byDay[$0]!) }
        func window(endingAt end: Int, days: Int) -> [Double] {
            nights.filter { $0.day <= end && end - $0.day < days }.map(\.ell)
        }

        let longEll = window(endingAt: t, days: longWindow)
        let rollEll = window(endingAt: t, days: rollWindow)
        guard longEll.count >= minNights, rollEll.count >= minRollNights else { return nil }

        let baseline7 = RecoveryForecaster.mean(rollEll)
        let longMean = RecoveryForecaster.mean(longEll)
        let longSD = max(RecoveryForecaster.sampleSD(longEll), longSDFloor)

        // Smallest-worthwhile-change band (±0.5 SD in the log domain).
        let swcHalf = swcK * longSD
        let normalLow = longMean - swcHalf
        let normalHigh = longMean + swcHalf

        let tier: ReadinessTier
        if baseline7 > normalHigh {
            tier = .primed
        } else if baseline7 >= normalLow {
            tier = .normal
        } else {
            tier = .suppressed
        }

        // Overreaching watch (INFORMATIONAL — never changes the tier): a falling CV trend over the last
        // `cvTrendWindow` days while the 7-day baseline sits below the long mean. One CV point per day whose
        // trailing 7 calendar days hold at least `minRollNights` nights; OLS slope over the points.
        var cvSeries: [Double] = []
        for end in (t - cvTrendWindow + 1)...t {
            let w = window(endingAt: end, days: rollWindow)
            guard w.count >= minRollNights else { continue }
            let m = RecoveryForecaster.mean(w)
            cvSeries.append(m != 0.0 ? 100.0 * RecoveryForecaster.sampleSD(w) / m : 0.0)
        }
        let cvSlope = RecoveryForecaster.leastSquaresSlope(cvSeries)
        let overreachingWatch = cvSlope < 0.0 && baseline7 < longMean

        return HRVReadinessResult(tier: tier,
                                  baseline7Ms: exp(baseline7),
                                  normalLowMs: exp(normalLow),
                                  normalHighMs: exp(normalHigh),
                                  overreachingWatch: overreachingWatch)
    }
}
