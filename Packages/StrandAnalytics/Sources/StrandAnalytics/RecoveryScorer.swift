import Foundation
import WhoopProtocol

// RecoveryScorer.swift — resting HR during sleep + a transparent 0–100 recovery score.
//
// Ported from server/ingest/app/analysis/recovery.py.
//
// recovery() is a z-score + logistic composite. It is APPROXIMATE — not
// WHOOP-identical (WHOOP's model is proprietary). It is a transparent,
// HRV-dominant, baseline-normalized proxy.
//
// Weighting (documented, grounded, explainable; this is "Charge" in the UI):
//   higher HRV vs baseline       → higher recovery  (W_HRV       = 0.55, dominant)
//   lower resting HR vs baseline → higher recovery  (W_RHR       = 0.20)
//   higher rest quality (sleep)  → higher recovery  (W_SLEEP     = 0.15)
//   lower resp vs baseline       → higher recovery  (W_RESP      = 0.05)
//   skin-temp deviation from 0   → lower recovery   (W_SKIN_TEMP = 0.05)
//
// The Charge/Effort/Rest redesign folds skin temperature in (illness/overreach
// signal): HRV dropped 0.60 → 0.55 to make room for W_SKIN_TEMP = 0.05. The
// skin-temp term is a SYMMETRIC penalty on the ±°C deviation (−|dev|/scale), so any
// drift away from the personal baseline — hot or cold — lowers Charge. It is added
// ONLY when a skin-temp deviation is supplied; when nil the term drops and the
// weights renormalize, leaving the no-skin-temp score IDENTICAL to before.
//
// Recovery Index (overnight HR slope) and Activity Balance (previous-day Effort) terms were declared here
// but never passed by any caller, so they were removed (#53) rather than left as dead weights.
//
// Each metric is standardized to a z-score against the personal trailing baseline (#53:
// `ChargeBaselines`; HRV in the log domain), and each term's z is clamped to ±`termZClamp` before
// weighting so one artefact night or a zero Rest cannot dominate. Missing terms are dropped and the
// weights renormalized. The composite z is squashed through a logistic anchored so that
// Z = 0 → ~58% (WHOOP's published population-average recovery).
//
// Cold-start: if the HRV baseline (dominant driver) is not yet usable
// (< MIN_NIGHTS_SEED valid nights), recovery() returns nil. Callers may use
// RECOVERY_POPULATION_MEAN (58.0) as a fallback but should flag it.

public enum RecoveryScorer {

    // MARK: - Constants (recovery.py)

    public static let wHRV: Double = 0.55
    public static let wRHR: Double = 0.20
    public static let wResp: Double = 0.05
    public static let wSleep: Double = 0.15
    /// Skin-temperature deviation weight (Charge/Effort/Rest redesign). HRV gave up
    /// 0.05 (0.60 → 0.55) to fund it.
    public static let wSkinTemp: Double = 0.05

    /// Skin-temp penalty scale (°C): a 1 °C deviation from baseline costs ≈1 z-unit of
    /// penalty before weighting. Symmetric — sign of the deviation does not matter.
    public static let skinTempScaleC: Double = 1.0

    /// Per-term z clamp (#53): every term's z is limited to ±3 before weighting. Without it a Rest of 0
    /// read as a sleep z of −7.1 and an artefact HRV night at z −5 or below, either of which outweighed every
    /// other term on its own. Three SDs is already the edge of the logistic's useful range.
    public static let termZClamp: Double = 3.0

    static func clampZ(_ z: Double) -> Double { max(-termZClamp, min(termZClamp, z)) }

    /// Logistic spread: ±2 z-units ≈ full Red–Green band (15%–95%).
    public static let logisticK: Double = 1.6
    /// Logistic offset so Z=0 → 58%.
    public static let logisticZ0: Double = -0.20
    /// WHOOP-published population-average recovery (%). Cold-start fallback.
    public static let populationMean: Double = 58.0

    /// Recovery band thresholds (WHOOP color scheme).
    public static let bandRedMax: Double = 34.0
    public static let bandYellowMax: Double = 67.0

    /// Sleep-performance center ("good night" at ~85% efficiency).
    public static let sleepPerfCenter: Double = 0.85
    /// Sleep-performance scale (±2 z spans the normal range).
    public static let sleepPerfScale: Double = 0.12

    // MARK: - Parasympathetic-saturation guard (Charge): DETECTED AND LOGGED, NOT APPLIED
    //
    // A low nightly HRV is normally a fatigue signal, so the HRV term (wHRV = 0.55, the dominant
    // driver) pulls Charge DOWN. There is a CANDIDATE benign pattern the raw z-score may mis-read:
    // parasympathetic (vagal) saturation. In a very fit or very relaxed state the RMSSD-HRV response
    // can saturate and read LOW while resting HR reads LOW too, and the two DECOUPLE. On such a night
    // the dominant HRV penalty tanks Charge even though the person is well rested.
    //
    // The candidate discriminator is the SIGN of the resting-HR term versus the HRV term. recovery()
    // builds the HRV term as (hrv - mu)/sigma (NEGATIVE when HRV is below baseline = the penalty) and
    // the RHR term as (mu - rhr)/sigma (POSITIVE when resting HR is below baseline = recovery-good). In
    // a normally-coupled autonomic response a low HRV travels WITH a HIGH resting HR (sympathetic
    // drive), so both terms point the SAME way (down). When instead the HRV term points "bad" while the
    // resting-HR term points "good", the expected HRV<->RHR coupling has broken.
    //
    // ── WHY THE EASING IS COMPUTED BUT DELIBERATELY NOT APPLIED ─────────────────────────────────
    //
    // This code DETECTS the signature and REPORTS the easing it would apply, but `recovery(...)` does
    // NOT consume it: the Charge number is byte-identical to pre-guard behaviour. Two reasons, both
    // currently unresolved:
    //
    //   1. VALIDATION GAP. The real-data validation behind this guard recorded ZERO firings over ~2
    //      weeks. That is evidence the gate does not OVER-fire; it is NOT evidence that the easing is
    //      the right size, or right at all, on a real saturation night, because no real saturation
    //      night was ever observed. Every night that would actually move Charge is covered by
    //      synthetic fixtures only (see RecoverySaturationGuardTests).
    //   2. CONTESTED PREMISE. Low HRV + low resting HR is ALSO a reported signature of parasympathetic
    //      (non-functional) OVERREACHING, which is genuinely maladaptive. From the HRV/RHR pair alone,
    //      benign saturation and overreaching are not distinguishable, and they want OPPOSITE Charge
    //      corrections. Easing the penalty on an overreaching night would hide a real problem.
    //
    // So the guard ships INSTRUMENT-FIRST: detection is live and surfaces in the Charge trace (the
    // "charge saturation active ... wouldRaiseCharge=N" line) and in the ChargeDrivers HRV verdict, so
    // real low-HRV + low-RHR nights accumulate and can be checked against ground truth. Enabling the
    // easing is a follow-up gated on confirmed real firings, not on the synthetic fixtures.
    //
    // TO ENABLE LATER: feed `easedHrvZ` into the HRV term at the marked call site in `recovery(...)`,
    // and flip the tests that currently pin the score as UNCHANGED.

    /// Personal-sigma units BOTH the low-HRV arm and the low-resting-HR arm must clear before the
    /// guard engages. 0.5 sigma keeps it OFF marginal / noise nights: a night must be clearly low on
    /// HRV AND clearly low on resting HR (each at least half a personal standard deviation into its
    /// saturation direction) before any easing applies.
    public static let satEnterZ: Double = 0.5

    /// Personal-sigma units at which the saturation signal reaches FULL strength. Between satEnterZ
    /// and this the easing ramps linearly from 0 to satMaxDampFraction; at/above it the easing is
    /// capped. 1.5 sigma means "both HRV and resting HR a clear 1.5 SD below baseline" is treated as
    /// unambiguous saturation.
    public static let satFullZ: Double = 1.5

    /// Maximum fraction of the low-HRV penalty the easing would ever credit back. 0.5 = at most HALF
    /// the penalty would be eased, so a genuinely low-HRV night would NEVER be scored as if HRV sat at
    /// baseline: at least half of the low-HRV penalty would always survive. The core conservatism knob
    /// for when the easing is enabled; today it only bounds the reported would-be delta.
    public static let satMaxDampFraction: Double = 0.5

    /// Outcome of the parasympathetic-saturation check for one night.
    ///
    /// COUNTERFACTUAL, not applied: `easedHrvZ` / `dampFraction` describe the easing this guard WOULD
    /// apply. `recovery(...)` does not consume them (see the MARK header for why). Today they exist to
    /// be logged by the Charge trace and to flag the ChargeDrivers HRV verdict, so that real firings
    /// can be counted before the easing is ever allowed to move the score.
    struct ParasympatheticSaturation: Equatable, Sendable {
        /// The HRV z the easing WOULD use: the input z shrunk toward 0 (never past it), lightening the
        /// low-HRV penalty. Equals the input hrvZ when the guard is inactive. NOT fed to the score.
        let easedHrvZ: Double
        /// True iff the saturation regime was detected, i.e. the guard WOULD have eased the penalty.
        /// The Charge number is the same either way.
        let active: Bool
        /// Fraction of the HRV penalty the easing WOULD credit back, in [0, satMaxDampFraction].
        /// 0 when inactive.
        let dampFraction: Double
    }

    /// Detect parasympathetic saturation from tonight's HRV and resting-HR z-scores and compute the
    /// easing that WOULD be applied to the low-HRV penalty (shrinking its negative z toward 0).
    ///
    /// This is pure detection + a counterfactual. It has no effect on Charge: `recovery(...)` scores
    /// the raw HRV z regardless of what this returns. See the MARK header for the validation gap and
    /// the contested premise that keep the easing switched off.
    ///
    /// - Parameters:
    ///   - hrvZ: the HRV term as recovery() builds it, (hrv - mu)/sigma. Higher is better; a
    ///     NEGATIVE value means HRV is below baseline (the penalty direction).
    ///   - rhrZ: the resting-HR term as recovery() builds it, (mu - rhr)/sigma. Higher is better; a
    ///     POSITIVE value means resting HR is below baseline (the recovery-good direction). Pass nil
    ///     when there is no resting-HR baseline (no RHR term) — with nothing to corroborate the low
    ///     HRV, the guard refuses to guess and never fires.
    /// - Returns: whether the signature fired, plus the eased HRV z and damp fraction it would imply.
    static func parasympatheticSaturation(hrvZ: Double, rhrZ: Double?) -> ParasympatheticSaturation {
        // Without a resting-HR term there is nothing to corroborate the low HRV: benign saturation
        // and real fatigue are indistinguishable from HRV alone, so report no saturation.
        guard let rhrZ = rhrZ else {
            return ParasympatheticSaturation(easedHrvZ: hrvZ, active: false, dampFraction: 0)
        }
        // Saturation SIGNATURE, in personal-sigma units:
        //   hrvLow  > 0  <=> HRV below baseline (the penalty we might ease)
        //   rhrLow  > 0  <=> resting HR below baseline (the corroborating "also low" signal)
        // Both must clear satEnterZ, so only a clear low-HRV + low-RHR decoupling is ever eased and a
        // low-HRV + HIGH-RHR fatigue night (rhrLow < 0) can never qualify.
        let hrvLow = -hrvZ
        let rhrLow = rhrZ
        guard hrvLow >= satEnterZ, rhrLow >= satEnterZ else {
            return ParasympatheticSaturation(easedHrvZ: hrvZ, active: false, dampFraction: 0)
        }
        // Strength is driven by the WEAKER of the two arms (min), normalized from the entry gate to
        // satFullZ and clamped to [0, 1]. Using the weaker arm is deliberately conservative: full
        // easing requires HRV clearly low AND resting HR clearly low; a very low HRV paired with only
        // a mildly low resting HR is limited by the resting-HR arm.
        let couplingStrength = min(hrvLow, rhrLow)
        let s = max(0.0, min(1.0, (couplingStrength - satEnterZ) / (satFullZ - satEnterZ)))
        let dampFraction = satMaxDampFraction * s
        // The easing this WOULD apply: shrink the (negative) HRV z toward 0. (1 - dampFraction) stays
        // in [1 - satMaxDampFraction, 1], so the penalty would only ever be REDUCED — never removed,
        // never sign-flipped into a bonus. Computed for reporting only; recovery() ignores it.
        let easedHrvZ = hrvZ * (1.0 - dampFraction)
        return ParasympatheticSaturation(easedHrvZ: easedHrvZ,
                                         active: dampFraction > 0,
                                         dampFraction: dampFraction)
    }

    // MARK: - Recovery band

    /// WHOOP-style color band for a recovery score [0, 100].
    public static func band(_ score: Double) -> String {
        if score < bandRedMax { return "red" }
        if score < bandYellowMax { return "yellow" }
        return "green"
    }

    // MARK: - Cold-start calibration progress

    /// The calibration countdown read straight off the resolved HRV baseline (#53): its valid-night count
    /// while below the seed gate, nil once recovery exists or the gate is crossed. The dashboard passes the
    /// `ChargeBaselines.Resolved.hrv` for the displayed day, so the countdown counts exactly the nights the
    /// ring's baseline holds, era cut included. Kotlin twin: `recoveryCalibrationNights(hrvBaseline:)`.
    public static func calibrationNights(hrvBaseline: BaselineState?, hasRecovery: Bool,
                                         seed: Int = Baselines.minNightsSeed) -> Int? {
        guard !hasRecovery else { return nil }
        let n = hrvBaseline?.nValid ?? 0
        return (0..<seed).contains(n) ? n : nil
    }

    // MARK: - Recovery score

    /// A baseline driver: mean + spread (internal abs-dev units, as in BaselineState).
    public struct DriverBaseline: Equatable, Sendable {
        public let mean: Double
        public let spread: Double
        /// True for a log-domain (lnRMSSD) baseline: `mean` is the geometric mean, `spread` in ln units.
        public let logDomain: Bool
        public init(mean: Double, spread: Double, logDomain: Bool = false) {
            self.mean = mean; self.spread = spread; self.logDomain = logDomain
        }
        public init(_ state: BaselineState) {
            self.mean = state.baseline; self.spread = state.spread; self.logDomain = state.logDomain
        }
    }

    /// z of a higher-is-better value against a baseline, in the baseline's own domain (the HRV term).
    static func higherBetterZ(_ value: Double, _ b: DriverBaseline) -> Double {
        b.logDomain ? (log(max(value, 1.0)) - log(max(b.mean, 1.0))) / max(1.253 * b.spread, 1e-9)
                    : zScore(value, mean: b.mean, spread: b.spread)
    }

    /// `higherBetterZ` over a `BaselineState`.
    static func higherBetterZ(_ value: Double, state: BaselineState) -> Double {
        higherBetterZ(value, DriverBaseline(state))
    }

    /// Robust z-score using EWMA spread: (value − mean) / (1.253 × spread).
    static func zScore(_ value: Double, mean: Double, spread: Double) -> Double {
        let sigma = max(1.253 * spread, 1e-9)
        return (value - mean) / sigma
    }

    /// Z-score + logistic recovery score in [0, 100]. APPROXIMATE.
    ///
    /// Returns nil when the HRV baseline (dominant driver) is not yet usable, or
    /// no valid driver is available at all.
    ///
    /// - Parameters:
    ///   - hrv: tonight's HRV (RMSSD, ms).
    ///   - rhr: tonight's resting HR (bpm).
    ///   - resp: tonight's respiration (raw or calibrated — z is scale-invariant);
    ///           nil drops the term.
    ///   - hrvBaseline: HRV baseline (required for a score — nil returns nil).
    ///   - rhrBaseline: resting-HR baseline; nil drops the RHR term. On the `BaselineState` overload an
    ///     UNUSABLE baseline (#1988) is treated as nil, so a synthetic cold-start midpoint never scores.
    ///   - respBaseline: respiration baseline; nil drops the resp term.
    ///   - sleepPerf: Rest quality (Rest composite ÷100, 0..1; was raw efficiency);
    ///     nil drops the term.
    ///   - skinTempDev: skin-temperature deviation from the personal baseline (±°C,
    ///     `DailyMetric.skinTempDevC`). Entered as a SYMMETRIC penalty −|dev|/scale,
    ///     weight wSkinTemp. nil drops the term and the weights renormalize so the
    ///     no-skin-temp score is identical to before.
    ///   - hrvBaselineUsable: whether the HRV baseline has enough nights
    ///     (BaselineState.usable). When false, returns nil (cold-start).
    public static func recovery(hrv: Double,
                                rhr: Double,
                                resp: Double?,
                                hrvBaseline: DriverBaseline?,
                                rhrBaseline: DriverBaseline?,
                                respBaseline: DriverBaseline?,
                                sleepPerf: Double?,
                                skinTempDev: Double? = nil,
                                hrvBaselineUsable: Bool = true) -> Double? {
        // Cold-start gate: HRV is the dominant driver; if its baseline isn't
        // usable, refuse to score (more honest than a fabricated value).
        if !hrvBaselineUsable { return nil }
        // Required-driver gate: the HRV baseline is REQUIRED for a score. It is Optional here only
        // for callers that may not have one yet, and hrvBaselineUsable defaults to true, so without
        // this an absent baseline let any other optional term (sleepPerf alone, say) produce a
        // Charge score carrying no HRV term at all.
        guard let hrvB = hrvBaseline else { return nil }

        var terms: [(z: Double, w: Double)] = []

        // HRV term: higher is better.
        //
        // INSTRUMENT-FIRST CALL SITE for the parasympathetic-saturation guard. The guard is NOT applied
        // here: this term is the RAW z, so Charge is byte-identical to pre-guard behaviour. The
        // signature is still detected and reported out-of-band (Charge trace + ChargeDrivers verdict)
        // so real firings can be counted first. See the MARK header for why, and swap in
        // `parasympatheticSaturation(hrvZ:rhrZ:).easedHrvZ` here to enable it.
        terms.append((higherBetterZ(hrv, hrvB), wHRV))
        // RHR term: lower is better → (μ − x) / σ.
        if let b = rhrBaseline {
            terms.append((zScore(b.mean, mean: rhr, spread: b.spread), wRHR))
        }
        // Resp term: lower is better, optional.
        if let r = resp, let b = respBaseline {
            terms.append((zScore(b.mean, mean: r, spread: b.spread), wResp))
        }
        // Sleep-performance / Rest-quality term: no baseline needed; centered at SLEEP_PERF_CENTER.
        if let sp = sleepPerf {
            terms.append(((sp - sleepPerfCenter) / sleepPerfScale, wSleep))
        }
        // Skin-temp term: SYMMETRIC penalty on |deviation| (illness/overreach). Any
        // drift from the personal baseline lowers Charge; added only when supplied.
        if let dev = skinTempDev {
            terms.append((-abs(dev) / skinTempScaleC, wSkinTemp))
        }

        guard !terms.isEmpty else { return nil }
        let totalWeight = terms.reduce(0) { $0 + $1.w }
        guard totalWeight > 0 else { return nil }

        let z = terms.reduce(0) { $0 + clampZ($1.z) * $1.w } / totalWeight
        return logisticScore(compositeZ: z)
    }

    /// The Charge logistic: weighted-composite z -> 0-100, clamped. Factored out of `recovery(...)` so
    /// the trace's saturation counterfactual ("what Charge WOULD read if the easing were applied") runs
    /// through the EXACT same curve the real score does and cannot drift from it. Same expression as
    /// before it was extracted, so every existing score is unchanged.
    static func logisticScore(compositeZ z: Double) -> Double {
        let score = 100.0 / (1.0 + exp(-logisticK * (z - logisticZ0)))
        return max(0.0, min(100.0, score))
    }

    /// Convenience overload taking BaselineState directly. HRV must be usable for a score;
    /// unusable optional personal baselines are omitted before weight renormalization.
    public static func recovery(hrv: Double,
                                rhr: Double,
                                resp: Double?,
                                hrvBaseline: BaselineState,
                                rhrBaseline: BaselineState?,
                                respBaseline: BaselineState?,
                                sleepPerf: Double?,
                                skinTempDev: Double? = nil) -> Double? {
        recovery(hrv: hrv,
                 rhr: rhr,
                 resp: resp,
                 hrvBaseline: DriverBaseline(hrvBaseline),
                 // #1988: an UNUSABLE resting-HR baseline is treated as absent. foldHistory returns
                 // the config's synthetic midpoint (about 75 bpm) for an empty or all-implausible
                 // history, which is nobody's resting HR, so scoring against it moved Charge on a
                 // baseline the user never had. Gated here, in the one place every BaselineState
                 // caller passes through, rather than at each call site: the headline and the driver
                 // breakdown then agree by construction. Mirrors hrvBaselineUsable below.
                 rhrBaseline: rhrBaseline.flatMap { $0.usable ? $0 : nil }.map(DriverBaseline.init),
                 respBaseline: respBaseline.flatMap { $0.usable ? $0 : nil }.map(DriverBaseline.init),
                 sleepPerf: sleepPerf,
                 skinTempDev: skinTempDev,
                 hrvBaselineUsable: hrvBaseline.usable)
    }
}
