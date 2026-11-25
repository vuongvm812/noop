import SwiftUI
import StrandDesign
import StrandAnalytics

// MARK: - Race predictions
//
// Predicted 5K / 10K / half marathon / marathon times from the user's own runs (`RacePredictor`). One row
// component serves the Workouts card, the compact block in Health › Fitness Age, and the "This run
// predicts" section of a run's detail. Android mirrors it in `RacePredictionsCard.kt`.

/// Display copy shared by the race prediction surfaces.
enum RacePredictionCopy {
    /// "5K" / "10K" / "Half marathon" / "Marathon" for a `RacePredictor.targetDistancesM()` index.
    static func targetLabel(_ index: Int) -> String {
        switch index {
        case 0: return String(localized: "5K")
        case 1: return String(localized: "10K")
        case 2: return String(localized: "Half marathon")
        default: return String(localized: "Marathon")
        }
    }

    /// The hint shown where no run reaches a target yet. The shortest qualifying run is rounded UP in the
    /// display unit, so a run of exactly the stated distance always unlocks it.
    static func unlockHint(_ index: Int, system: UnitSystem) -> String {
        let m = RacePredictor.minRunM(targetIndex: index)
        let shown: Double
        switch system {
        case .metric:
            shown = (m / 100).rounded(.up) * 100
        case .imperial:
            let miles = (UnitFormatter.kmToMiles(m / 1000) * 10).rounded(.up) / 10
            shown = miles / UnitFormatter.milesPerKilometer * 1000
        }
        let distance = UnitFormatter.distanceFromMeters(shown, system: system)
        return String(localized: "Run \(distance) or longer to unlock")
    }

    /// The run date for a source line, in the reader's locale.
    static func runDate(_ ts: Int) -> String {
        Date(timeIntervalSince1970: TimeInterval(ts)).formatted(date: .abbreviated, time: .omitted)
    }

    /// "Based on your 10.0 km run on 3 Nov 2026."
    static func basedOn(_ p: RacePredictor.Prediction, system: UnitSystem) -> String {
        let distance = UnitFormatter.distanceFromMeters(p.sourceDistanceM, system: system)
        return String(localized: "Based on your \(distance) run on \(runDate(p.sourceStartTs)).")
    }

    /// The per-row source when the rows come from different runs: "10.0 km run · 3 Nov 2026".
    static func sourceCaption(_ p: RacePredictor.Prediction, system: UnitSystem) -> String {
        let distance = UnitFormatter.distanceFromMeters(p.sourceDistanceM, system: system)
        return String(localized: "\(distance) run · \(runDate(p.sourceStartTs))")
    }

    /// True when every available prediction comes from the same run, so one source line covers them all.
    static func sharesOneRun(_ items: [RacePredictor.Prediction?]) -> Bool {
        let shown = items.compactMap { $0 }
        guard let first = shown.first else { return false }
        return shown.allSatisfy { $0.sourceStartTs == first.sourceStartTs && $0.sourceDistanceM == first.sourceDistanceM }
    }
}

/// One target: its label (with an optional caption) on the left, the predicted time and pace on the right,
/// or the unlock hint when no run reaches it.
struct RacePredictionRow: View {
    let targetIndex: Int
    let prediction: RacePredictor.Prediction?
    let system: UnitSystem
    var caption: String?
    var highlighted = false
    var compact = false

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: NoopMetrics.space3) {
            VStack(alignment: .leading, spacing: NoopMetrics.spaceHalf) {
                Text(RacePredictionCopy.targetLabel(targetIndex))
                    .font(compact ? StrandFont.subhead : StrandFont.headline)
                    .foregroundStyle(StrandPalette.textPrimary)
                if let caption {
                    Text(caption)
                        .font(StrandFont.footnote)
                        .foregroundStyle(highlighted ? StrandPalette.statusPositive : StrandPalette.textTertiary)
                }
            }
            Spacer(minLength: NoopMetrics.space2)
            if let p = prediction {
                VStack(alignment: .trailing, spacing: NoopMetrics.spaceHalf) {
                    Text(RacePredictor.formatTime(p.seconds))
                        .font(StrandFont.number(compact ? 17 : 22))
                        .foregroundStyle(highlighted ? StrandPalette.statusPositive : StrandPalette.metricCyan)
                    Text(UnitFormatter.paceFromSecPerKm(RacePredictor.secPerKm(p), system: system))
                        .font(StrandFont.footnote)
                        .foregroundStyle(StrandPalette.textTertiary)
                }
            } else {
                Text(RacePredictionCopy.unlockHint(targetIndex, system: system))
                    .font(StrandFont.footnote)
                    .foregroundStyle(StrandPalette.textTertiary)
                    .multilineTextAlignment(.trailing)
            }
        }
        .accessibilityElement(children: .combine)
    }
}

/// The four predictions with their source and method notes. `compact` is the Health › Fitness Age block:
/// tighter rows, and the method note left to the Workouts card.
struct RacePredictionsCard: View {
    let result: RacePredictor.Result
    let system: UnitSystem
    var compact = false

    var body: some View {
        VStack(alignment: .leading, spacing: NoopMetrics.gap) {
            SectionHeader("Race predictions", overline: "From your runs")
            NoopCard(tint: StrandPalette.metricCyan) {
                if result.items.allSatisfy({ $0 == nil }) {
                    Text("Log a run of at least 3 km and 12 minutes to see predictions.")
                        .font(StrandFont.subhead)
                        .foregroundStyle(StrandPalette.textTertiary)
                        .fixedSize(horizontal: false, vertical: true)
                } else {
                    rows
                }
            }
        }
    }

    @ViewBuilder private var rows: some View {
        let shared = RacePredictionCopy.sharesOneRun(result.items)
        VStack(alignment: .leading, spacing: compact ? NoopMetrics.space2 : NoopMetrics.space3) {
            ForEach(Array(result.items.enumerated()), id: \.offset) { index, p in
                RacePredictionRow(targetIndex: index, prediction: p, system: system,
                                  caption: shared ? nil : p.map { RacePredictionCopy.sourceCaption($0, system: system) },
                                  compact: compact)
            }
            Divider().overlay(StrandPalette.hairline)
            if shared, let first = result.items.compactMap({ $0 }).first {
                note(RacePredictionCopy.basedOn(first, system: system))
            }
            if result.fromAllHistory {
                note(String(localized: "No run in the last 90 days, so these use your whole history."))
            }
            if !compact {
                note(String(localized: "Riegel's formula over whole runs, not your fastest segments. A guide that assumes you train for the distance; least reliable for the marathon."))
            }
        }
    }

    private func note(_ text: String) -> some View {
        Text(text)
            .font(StrandFont.footnote)
            .foregroundStyle(StrandPalette.textTertiary)
            .fixedSize(horizontal: false, vertical: true)
    }
}

/// "This run predicts": what one qualifying run predicts for each target it reaches, marking the targets
/// where it is the current best. Absent for a run that does not qualify.
struct RunRacePredictionsSection: View {
    let run: RacePredictor.Run
    /// The current best predictions, to mark the targets this run holds. nil until loaded.
    let best: RacePredictor.Result?
    let system: UnitSystem

    var body: some View {
        let items = RacePredictor.forRun(run)
        if items.contains(where: { $0 != nil }) {
            VStack(alignment: .leading, spacing: NoopMetrics.gap) {
                SectionHeader("This run predicts", overline: "Race predictions")
                NoopCard(tint: StrandPalette.metricCyan) {
                    VStack(alignment: .leading, spacing: NoopMetrics.space3) {
                        ForEach(Array(items.enumerated()), id: \.offset) { index, p in
                            if let p {
                                let isBest = isCurrentBest(index)
                                RacePredictionRow(targetIndex: index, prediction: p, system: system,
                                                  caption: isBest ? String(localized: "Your best") : nil,
                                                  highlighted: isBest)
                            }
                        }
                        Divider().overlay(StrandPalette.hairline)
                        Text("Riegel's formula over this whole run. \"Your best\" marks the targets where this run sets your current prediction.")
                            .font(StrandFont.footnote)
                            .foregroundStyle(StrandPalette.textTertiary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
            }
        }
    }

    private func isCurrentBest(_ index: Int) -> Bool {
        guard let best, index < best.items.count, let b = best.items[index] else { return false }
        return b.sourceStartTs == run.startTs && b.sourceDistanceM == (run.distanceM ?? 0)
    }
}
