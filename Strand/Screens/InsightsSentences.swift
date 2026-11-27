import SwiftUI
import Foundation
import StrandAnalytics

// InsightsSentences.swift — view-layer renderer for the Insights engines' plain-English sentences.
//
// `BehaviorInsights.sentence`, `ActivityCost.sentence()`, `RankedEffect.sentence()` and
// `DoseResponse.sentence()` return fixed English that parity/golden tests pin, so the engines stay as
// they are. The screens render these sentences from the engines' structured fields instead, through
// localized whole-sentence templates (one key per sentence shape, so word order can change per
// language). With the English catalog values each renderer returns exactly what its engine returns
// (InsightsSentencesTests asserts it). Kotlin twin: `InsightsSentences.kt`.
enum InsightsSentences {

    /// An engine outcome label ("Charge" / "Rest" / "Resting HR") as the user's language names it; any other
    /// label (HRV, or one already localized by the caller) passes through unchanged.
    static func displayOutcome(_ name: String) -> String {
        switch name {
        case "Charge": return String(localized: "Charge")
        case "Rest": return String(localized: "Rest")
        case "Resting HR": return String(localized: "Resting HR")
        default: return name
        }
    }

    // MARK: - Behaviour effect (BehaviorInsights.sentence)

    static func effect(_ e: BehaviorEffect) -> String {
        if let factor = AutoFactors.factorForKey(e.behavior) { return autoEffect(e, factor: factor) }
        let avgWith = Int(e.meanWith.rounded())
        let avgWithout = Int(e.meanWithout.rounded())
        let higher = e.delta > 0
        if e.delta == 0 || e.delta.isNaN {
            return String(localized: "On days you logged ‘\(e.behavior)’, \(e.outcome) was no different (avg \(avgWith) vs \(avgWithout), n=\(e.nWith) vs \(e.nWithout)).")
        }
        if let pct = e.pctChange {
            let p = Int(abs(pct).rounded())
            return higher
                ? String(localized: "On days you logged ‘\(e.behavior)’, \(e.outcome) was \(p)% higher (avg \(avgWith) vs \(avgWithout), n=\(e.nWith) vs \(e.nWithout)).")
                : String(localized: "On days you logged ‘\(e.behavior)’, \(e.outcome) was \(p)% lower (avg \(avgWith) vs \(avgWithout), n=\(e.nWith) vs \(e.nWithout)).")
        }
        let mag = String(describing: round1(abs(e.delta)))
        return higher
            ? String(localized: "On days you logged ‘\(e.behavior)’, \(e.outcome) was \(mag) higher (avg \(avgWith) vs \(avgWithout), n=\(e.nWith) vs \(e.nWithout)).")
            : String(localized: "On days you logged ‘\(e.behavior)’, \(e.outcome) was \(mag) lower (avg \(avgWith) vs \(avgWithout), n=\(e.nWith) vs \(e.nWithout)).")
    }

    // MARK: - Activity cost (ActivityCost.sentence)

    static func activityCost(_ c: ActivityCost) -> String {
        let mag = abs(c.delta)
        let points = Int(mag.rounded())
        if mag < ActivityCostEngine.barelyMovesPoints {
            return String(localized: "Sessions like this barely move your next-day Charge (n=\(c.n)).")
        }
        let pointsPhrase = String(localized: "\(points) Charge points")
        let costs = c.delta >= 0
        if let days = c.daysToBaseline {
            let daysPhrase = String(localized: "\(days) days")
            return costs
                ? String(localized: "Sessions like this usually cost you about \(pointsPhrase) the next morning and take about \(daysPhrase) to bounce back (n=\(c.n)).")
                : String(localized: "Sessions like this usually lift about \(pointsPhrase) the next morning and take about \(daysPhrase) to bounce back (n=\(c.n)).")
        }
        return costs
            ? String(localized: "Sessions like this usually cost you about \(pointsPhrase) the next morning (n=\(c.n)).")
            : String(localized: "Sessions like this usually lift about \(pointsPhrase) the next morning (n=\(c.n)).")
    }

    // MARK: - Ranked, lag-aware effect (RankedEffect.sentence)

    /// The lead/lag chip text ("same day" / "next morning" / "N mornings later").
    static func leadLag(_ lag: Int) -> String {
        switch lag {
        case 0: return String(localized: "same day")
        case 1: return String(localized: "next morning")
        default: return String(localized: "\(lag) mornings later")
        }
    }

    /// The same chip as a `LocalizedStringKey`, for `StatePill`.
    static func leadLagKey(_ lag: Int) -> LocalizedStringKey {
        switch lag {
        case 0: return "same day"
        case 1: return "next morning"
        default: return "\(lag) mornings later"
        }
    }

    static func ranked(_ r: RankedEffect) -> String {
        let base = effect(r.effect)
        let trimmed = base.hasSuffix(".") ? String(base.dropLast()) : base
        let lead = leadLag(r.lag)
        return String(localized: "\(trimmed) (\(lead)).")
    }

    // MARK: - Dose-response (DoseResponse.sentence)

    static func dose(_ r: DoseResponse) -> String {
        let mag = String(describing: round1(abs(r.perUnit)))
        let lower = r.perUnit <= 0
        let outcome = displayOutcome(r.outcome)
        if r.priorDominated {
            return lower
                ? String(localized: "Each extra unit typically lines up with about \(mag) \(outcome) lower  - typical patterns, not yet yours (n=\(r.nUser)).")
                : String(localized: "Each extra unit typically lines up with about \(mag) \(outcome) higher  - typical patterns, not yet yours (n=\(r.nUser)).")
        }
        if r.contradictsPrior {
            return String(localized: "In your data so far, this doesn't move your \(outcome) the way it typically does (n=\(r.nUser)).")
        }
        return lower
            ? String(localized: "Each extra unit tends to line up with about \(mag) \(outcome) lower for you (n=\(r.nUser)).")
            : String(localized: "Each extra unit tends to line up with about \(mag) \(outcome) higher for you (n=\(r.nUser)).")
    }

    // MARK: - Automatic factors ("From your data")

    /// A ranker behaviour name as the user reads it: an automatic factor's label, or the journal question.
    static func behaviorLabel(_ name: String) -> String {
        AutoFactors.factorForKey(name).map(factorLabel) ?? name
    }

    /// The yes-group label of an automatic factor.
    static func factorLabel(_ f: AutoFactors.Factor) -> String {
        switch f {
        case .lateWorkout:      return String(localized: "Late workout")
        case .hardEffort:       return String(localized: "Hard Effort day")
        case .restDay:          return String(localized: "Rest day")
        case .lateBedtime:      return String(localized: "Late bedtime")
        case .irregularBedtime: return String(localized: "Irregular bedtime")
        case .lowSteps:         return String(localized: "Low steps")
        case .highSteps:        return String(localized: "High steps")
        }
    }

    /// The control-group label an automatic factor is compared against.
    static func factorControlLabel(_ f: AutoFactors.Factor) -> String {
        switch f {
        case .lateWorkout:      return String(localized: "Earlier workout")
        case .hardEffort:       return String(localized: "Easier day")
        case .restDay:          return String(localized: "Workout day")
        case .lateBedtime:      return String(localized: "Usual bedtime")
        case .irregularBedtime: return String(localized: "Regular bedtime")
        case .lowSteps, .highSteps: return String(localized: "Usual steps")
        }
    }

    /// The effect sentence for an automatic factor: its group against its control group.
    static func autoEffect(_ e: BehaviorEffect, factor: AutoFactors.Factor) -> String {
        let yes = factorLabel(factor)
        let control = factorControlLabel(factor)
        let avgWith = Int(e.meanWith.rounded())
        let avgWithout = Int(e.meanWithout.rounded())
        let higher = e.delta > 0
        if e.delta == 0 || e.delta.isNaN {
            return String(localized: "‘\(yes)’ vs ‘\(control)’: \(e.outcome) was no different (avg \(avgWith) vs \(avgWithout), n=\(e.nWith) vs \(e.nWithout)).")
        }
        if let pct = e.pctChange {
            let p = Int(abs(pct).rounded())
            return higher
                ? String(localized: "‘\(yes)’ vs ‘\(control)’: \(e.outcome) was \(p)% higher (avg \(avgWith) vs \(avgWithout), n=\(e.nWith) vs \(e.nWithout)).")
                : String(localized: "‘\(yes)’ vs ‘\(control)’: \(e.outcome) was \(p)% lower (avg \(avgWith) vs \(avgWithout), n=\(e.nWith) vs \(e.nWithout)).")
        }
        let mag = String(describing: round1(abs(e.delta)))
        return higher
            ? String(localized: "‘\(yes)’ vs ‘\(control)’: \(e.outcome) was \(mag) higher (avg \(avgWith) vs \(avgWithout), n=\(e.nWith) vs \(e.nWithout)).")
            : String(localized: "‘\(yes)’ vs ‘\(control)’: \(e.outcome) was \(mag) lower (avg \(avgWith) vs \(avgWithout), n=\(e.nWith) vs \(e.nWithout)).")
    }

    /// The signed change a row reads as on Today: "−8%", "+12%", or "−3.5" when there is no percentage.
    static func signedChange(_ e: BehaviorEffect) -> String {
        let sign = e.delta < 0 ? "\u{2212}" : (e.delta > 0 ? "+" : "")
        if let pct = e.pctChange { return "\(sign)\(Int(abs(pct).rounded()))%" }
        return "\(sign)\(String(describing: round1(abs(e.delta))))"
    }

    /// One Today row: "Late workout → HRV −8% next morning".
    static func todayLine(_ r: RankedEffect) -> String {
        let label = behaviorLabel(r.behavior)
        let change = signedChange(r.effect)
        let lead = leadLag(r.lag)
        return String(localized: "\(label) → \(r.outcome) \(change) \(lead)")
    }

    private static func round1(_ x: Double) -> Double { (x * 10).rounded() / 10 }
}
