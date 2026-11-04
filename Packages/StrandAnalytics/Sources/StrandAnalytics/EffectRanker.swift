import Foundation

// EffectRanker.swift — the unified, LAG-AWARE "what moves your Charge" ranker.
//
// Pure, deterministic, DB-free. Generalises ActivityCostEngine's single-sport D+1 to
// EVERY logged journal behaviour against EVERY daily outcome, and searches a small fixed
// lag set so it can tell "same day" from "shows up the next morning."
//
// For each (behaviour b, outcome o, lag L ∈ {0, +1, +2}):
//   1. behaviourDays = the day keys b was logged on (dose ≥ 1; callers pass the set).
//   2. Pair behaviour day D with outcome day D+L by SHIFTING the outcome map back by L —
//      i.e. re-key outcome[D+L] under D via CorrelationEngine.shiftDay(day, by: -L). This is
//      exactly the alignment ActivityCostEngine does for D+1, parameterised over the lag and
//      reusing the same fixed-UTC day arithmetic, so behaviour day D is compared with the
//      outcome that landed L days later.
//   3. Run the EXISTING BehaviorInsights.effect on the shifted outcome map → meanWith,
//      meanWithout, delta, cohensD, Welch pApprox, significant (already gated at
//      min(nWith, nWithout) ≥ 5).
//   4. Keep, per (b, o), the lag L* with the LARGEST |cohensD| among lags whose effect
//      computed AND cleared the significance group gate, carrying L* as the lead/lag.
//   5. Multiple comparisons: every (b, o, L) test that cleared the group gate is one member of
//      the feed's family. Benjamini–Hochberg adjusts their p-values together, and a row is
//      `significant` only when its chosen lag's q-value is < α. `rankFamily` takes every outcome
//      the feed can show, so the family is behaviours × outcomes × lags; `rank` is the
//      single-outcome case (behaviours × lags).
//
// HONESTY (effect-size first, not stargazing): the primary signal is the effect SIZE +
// n + a ScoreConfidence tier, never a bare "significant" stamp. The lag search is capped
// at the small fixed set {0,1,2} so the comparison count stays bounded and explainable; we
// never claim a behaviour "causes" anything — only that it lines up with a change.
//
// Output: one RankedEffect per (b, o) that produced a usable lag, ranked with the same rule
// as BehaviorInsights.rank (significant first, |cohensD| desc, stable tiebreak), so the feed
// matches the existing Behaviour Effects ordering exactly. Self-contained except for the two
// reused primitives, so the Kotlin twin is line-for-line.
//
// (Spec: 2026-06-19-v5-insights-correlation-engine-design.md — "Lag-aware effect ranking".)

// MARK: - Result

/// One ranked, lag-aware behaviour→outcome effect: the best lag's BehaviorEffect plus the
/// lead/lag it was found at and a confidence tier from the paired-day count.
public struct RankedEffect: Equatable, Sendable {
    /// The behaviour label (e.g. "Alcohol").
    public let behavior: String
    /// The outcome metric label (e.g. "Charge").
    public let outcome: String
    /// The lag (in days) at which the strongest honest effect was found: 0 = same day,
    /// +1 = the next morning, +2 = two mornings later.
    public let lag: Int
    /// The measured effect at `lag` (means, delta, Cohen's d, Welch p, significant).
    public let effect: BehaviorEffect
    /// Per-result certainty tier from the smaller group's size at the chosen lag.
    public let confidence: ScoreConfidence

    public init(behavior: String, outcome: String, lag: Int,
                effect: BehaviorEffect, confidence: ScoreConfidence) {
        self.behavior = behavior
        self.outcome = outcome
        self.lag = lag
        self.effect = effect
        self.confidence = confidence
    }

    /// Plain-English lead/lag chip text, e.g. "same day" / "next morning" / "2 mornings later".
    public var leadLagText: String {
        switch lag {
        case 0: return "same day"
        case 1: return "next morning"
        default: return "\(lag) mornings later"
        }
    }

    /// The sign-aware sentence for this row, reusing BehaviorInsights' renderer plus the
    /// lead/lag clause so each card reads "…, showing up the next morning."
    public func sentence() -> String {
        let base = BehaviorInsights.sentence(effect)
        // Drop the trailing period, append the lead/lag, restore it.
        let trimmed = base.hasSuffix(".") ? String(base.dropLast()) : base
        return "\(trimmed) (\(leadLagText))."
    }
}

// MARK: - Engine

public enum EffectRanker {

    /// The fixed, bounded lag set searched per (behaviour, outcome). Kept small and explicit
    /// — not a fitted VAR — so the multiple-comparison count stays bounded and honest at small n.
    public static let lagSet: [Int] = [0, 1, 2]

    /// Paired-day count below which a chosen lag's confidence is `.calibrating` (too thin to
    /// shout). This equals BehaviorInsights' significance group gate, so a row that clears the
    /// gate is never `.calibrating`.
    public static let calibratingBelow: Int = BehaviorInsights.minGroupForSignificance  // 5
    /// Paired-day count at/above which a chosen lag's confidence is `.solid` (else `.building`).
    public static let solidPairs: Int = 10

    // MARK: - Rank

    /// Rank every behaviour against one outcome across the lag set, keeping each behaviour's
    /// best lag. Mirrors BehaviorInsights.rank's ordering on the surviving rows.
    ///
    /// - Parameters:
    ///   - behaviors: per behaviour name, the SET of "yyyy-MM-dd" days it was logged (dose ≥ 1).
    ///   - outcomeByDay: the daily outcome series keyed "yyyy-MM-dd" (e.g. Charge 0–100).
    ///   - outcome: the outcome label carried onto each RankedEffect (e.g. "Charge").
    /// - Returns: one RankedEffect per behaviour that produced a usable lag, ranked
    ///   significant-first, |cohensD| desc, then behaviour name asc. Behaviours with no
    ///   computable lag are dropped.
    public static func rank(behaviors: [String: Set<String>],
                            controls: [String: Set<String>],
                            outcomeByDay: [String: Double],
                            outcome: String) -> [RankedEffect] {
        rankFamily(behaviors: behaviors, controls: controls,
                   outcomes: [outcome: outcomeByDay])[outcome] ?? []
    }

    /// Rank every behaviour against EVERY outcome in `outcomes` (label → day series), with the
    /// Benjamini–Hochberg family spanning behaviours × outcomes × lags. Returns the ranked rows
    /// per outcome label; an outcome with no usable row maps to an empty list.
    public static func rankFamily(behaviors: [String: Set<String>],
                                  controls: [String: Set<String>],
                                  outcomes: [String: [String: Double]]) -> [String: [RankedEffect]] {
        struct Candidate { let outcome: String; let behavior: String; let lag: Int; let effect: BehaviorEffect }
        var candidates: [Candidate] = []
        // Sorted iteration so the family order (and so tie handling) is deterministic.
        for outcome in outcomes.keys.sorted() {
            let outcomeByDay = outcomes[outcome]!
            let shiftedByLag = lagSet.map { shiftedOutcome(outcomeByDay, byLag: $0) }
            for name in behaviors.keys.sorted() {
                let days = behaviors[name]!
                // Absent from `controls` means no control group, which yields nothing — see
                // BehaviorInsights.effect. Failing closed is deliberate: a caller that forgets loses
                // the insight rather than getting one measured against every unlogged day.
                let controlDays = controls[name] ?? []
                for (k, lag) in lagSet.enumerated() {
                    guard let e = BehaviorInsights.effect(behaviorDays: days,
                                                          controlDays: controlDays,
                                                          outcomeByDay: shiftedByLag[k],
                                                          behavior: name,
                                                          outcome: outcome) else { continue }
                    // Group gate: a lag only competes (and only joins the family) if both sides
                    // clear the significance minimum, so a 2-day lag can't win on a fluke.
                    guard Swift.min(e.nWith, e.nWithout) >= BehaviorInsights.minGroupForSignificance
                    else { continue }
                    candidates.append(Candidate(outcome: outcome, behavior: name, lag: lag, effect: e))
                }
            }
        }
        let q = StatTests.benjaminiHochberg(candidates.map { $0.effect.pApprox })

        var best: [String: [String: (lag: Int, effect: BehaviorEffect)]] = [:]
        for (i, c) in candidates.enumerated() {
            let e = c.effect.adjusted(q: q[i])
            if let cur = best[c.outcome]?[c.behavior] {
                // Largest |cohensD| wins; ties break to the SMALLER lag (prefer same-day /
                // shorter lead over a longer one when the effect size is identical).
                let better = abs(e.cohensD) > abs(cur.effect.cohensD)
                    || (abs(e.cohensD) == abs(cur.effect.cohensD) && c.lag < cur.lag)
                if better { best[c.outcome, default: [:]][c.behavior] = (c.lag, e) }
            } else {
                best[c.outcome, default: [:]][c.behavior] = (c.lag, e)
            }
        }
        var out: [String: [RankedEffect]] = [:]
        for outcome in outcomes.keys {
            let rows = (best[outcome] ?? [:]).map { name, chosen in
                RankedEffect(behavior: name, outcome: outcome, lag: chosen.lag, effect: chosen.effect,
                             confidence: confidence(forPairs: Swift.min(chosen.effect.nWith,
                                                                       chosen.effect.nWithout)))
            }
            out[outcome] = sorted(rows)
        }
        return out
    }

    /// Find the best-lag RankedEffect for ONE behaviour against ONE outcome, or nil when no
    /// lag in `lagSet` yields a computable effect that clears the group gate. The family here is
    /// that behaviour's lags (Benjamini–Hochberg across them).
    public static func bestLag(behaviorDays: Set<String>,
                               controlDays: Set<String>,
                               outcomeByDay: [String: Double],
                               behavior: String,
                               outcome: String) -> RankedEffect? {
        rankFamily(behaviors: [behavior: behaviorDays], controls: [behavior: controlDays],
                   outcomes: [outcome: outcomeByDay])[outcome]?.first
    }

    // MARK: - Lag alignment

    /// Re-key the outcome series so behaviour day D is paired with the outcome that landed
    /// `lag` days later: out'[D] = out[D+lag]. We move the VALUE from key D+lag back to key D
    /// by shifting each existing key BACKWARD by `lag` (shiftDay(day, by: -lag)). Then a plain
    /// BehaviorInsights split on behaviour day D reads the D+lag outcome — the same join
    /// ActivityCostEngine performs for D+1, generalised. `lag == 0` is the identity map.
    static func shiftedOutcome(_ outcomeByDay: [String: Double], byLag lag: Int) -> [String: Double] {
        if lag == 0 { return outcomeByDay }
        var out: [String: Double] = [:]
        out.reserveCapacity(outcomeByDay.count)
        for (day, value) in outcomeByDay {
            // The outcome ON day `day` belongs to behaviour day `day - lag`.
            if let behaviourKey = CorrelationEngine.shiftDay(day, by: -lag) {
                out[behaviourKey] = value
            }
        }
        return out
    }

    // MARK: - Confidence

    /// Confidence from the smaller group's paired-day count: below the group gate →
    /// `.calibrating`; gate…<solidPairs → `.building`; ≥ solidPairs → `.solid`.
    static func confidence(forPairs pairs: Int) -> ScoreConfidence {
        if pairs < calibratingBelow { return .calibrating }
        return pairs >= solidPairs ? .solid : .building
    }

    // MARK: - Ranking

    /// Stable rank matching BehaviorInsights.rank: significant first, |cohensD| desc, then
    /// behaviour name ascending.
    static func sorted(_ rows: [RankedEffect]) -> [RankedEffect] {
        rows.sorted { a, b in
            if a.effect.significant != b.effect.significant { return a.effect.significant }
            let la = abs(a.effect.cohensD), lb = abs(b.effect.cohensD)
            if la != lb { return la > lb }
            return a.behavior < b.behavior
        }
    }
}
