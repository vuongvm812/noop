import SwiftUI
import StrandDesign
import StrandAnalytics

// MARK: - Race predictions
//
// Predicted 5K / 10K / half marathon / marathon times from the user's own runs (`RacePredictor`). One row
// component serves the Workouts card, the compact block in Health › Fitness Age, and the "This run
// predicts" section of a run's detail; the card links to the daily trend. Android mirrors it in
// `RacePredictionsCard.kt`.

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

    /// "Based on your runs · heart rate · VO₂max": which estimates joined Riegel in `result`. nil when Riegel
    /// stands alone, since the run source line already says where the numbers come from.
    static func basis(_ result: RacePredictor.Result) -> String? {
        let hr = result.estimates.contains { $0?.components.hrS != nil }
        let vo2 = result.estimates.contains { $0?.components.vo2S != nil }
        switch (hr, vo2) {
        case (true, true): return String(localized: "Based on your runs · heart rate · VO₂max")
        case (true, false): return String(localized: "Based on your runs · heart rate")
        case (false, true): return String(localized: "Based on your runs · VO₂max")
        case (false, false): return nil
        }
    }

    /// True when every available prediction comes from the same run, so one source line covers them all.
    static func sharesOneRun(_ items: [RacePredictor.Prediction?]) -> Bool {
        let shown = items.compactMap { $0 }
        guard let first = shown.first else { return false }
        return shown.allSatisfy { $0.sourceStartTs == first.sourceStartTs && $0.sourceDistanceM == first.sourceDistanceM }
    }
}

/// One target: its label (with an optional caption) on the left, the predicted time and pace on the right,
/// or the unlock hint when nothing predicts it.
struct RacePredictionRow: View {
    let targetIndex: Int
    /// The predicted time in whole seconds; nil shows the unlock hint.
    let seconds: Int?
    /// Pace for `seconds`, seconds per kilometre.
    let secPerKm: Double
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
            if let seconds {
                VStack(alignment: .trailing, spacing: NoopMetrics.spaceHalf) {
                    Text(RacePredictor.formatTime(seconds))
                        .font(StrandFont.number(compact ? 17 : 22))
                        .foregroundStyle(highlighted ? StrandPalette.statusPositive : StrandPalette.metricCyan)
                    Text(UnitFormatter.paceFromSecPerKm(secPerKm, system: system))
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

/// The four predictions with their source and method notes, and a link to their daily trend. `compact` is
/// the Health › Fitness Age block: tighter rows, and the method note left to the Workouts card.
struct RacePredictionsCard: View {
    let result: RacePredictor.Result
    let system: UnitSystem
    var compact = false
    @EnvironmentObject private var repo: Repository
    @State private var showTrend = false

    var body: some View {
        VStack(alignment: .leading, spacing: NoopMetrics.gap) {
            SectionHeader("Race predictions", overline: "From your runs")
            NoopCard(tint: StrandPalette.metricCyan) {
                if result.estimates.allSatisfy({ $0 == nil }) {
                    Text("Log a run of at least 3 km and 12 minutes to see predictions.")
                        .font(StrandFont.subhead)
                        .foregroundStyle(StrandPalette.textTertiary)
                        .fixedSize(horizontal: false, vertical: true)
                } else {
                    rows
                }
            }
        }
        .sheet(isPresented: $showTrend) {
            // Re-injected: a sheet is its own presentation and the app does not rely on it inheriting.
            RacePredictionTrendSheet(system: system, onClose: { showTrend = false })
                .environmentObject(repo)
        }
    }

    @ViewBuilder private var rows: some View {
        let shared = RacePredictionCopy.sharesOneRun(result.items)
        let blended = RacePredictionCopy.basis(result)
        VStack(alignment: .leading, spacing: compact ? NoopMetrics.space2 : NoopMetrics.space3) {
            ForEach(Array(result.estimates.enumerated()), id: \.offset) { index, e in
                RacePredictionRow(targetIndex: index, seconds: e?.seconds,
                                  secPerKm: e.map { RacePredictor.paceSecPerKm(seconds: $0.seconds, targetIndex: index) } ?? 0,
                                  system: system,
                                  caption: caption(e, shared: shared), compact: compact)
            }
            Divider().overlay(StrandPalette.hairline)
            if shared, let first = result.items.compactMap({ $0 }).first {
                note(RacePredictionCopy.basedOn(first, system: system))
            }
            if let blended { note(blended) }
            if result.fromAllHistory {
                note(String(localized: "No run in the last 90 days, so these use your whole history."))
            }
            if !compact {
                note(String(localized: "Riegel's formula over whole runs, not your fastest segments. A guide that assumes you train for the distance; least reliable for the marathon."))
                if blended != nil {
                    note(String(localized: "Your running heart rate and VO₂max are read through Daniels' VDOT tables and blended in, so easy runs count too."))
                }
            }
            Button { showTrend = true } label: {
                HStack {
                    Text("Prediction trend")
                        .font(StrandFont.subhead)
                        .foregroundStyle(StrandPalette.metricCyan)
                    Spacer()
                    Image(systemName: "chevron.right")
                        .font(StrandFont.footnote)
                        .foregroundStyle(StrandPalette.textTertiary)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
    }

    /// The row caption: "Estimate" where no run reaches the target, else the run behind the Riegel pick when
    /// the rows come from different runs.
    private func caption(_ e: RacePredictor.Estimate?, shared: Bool) -> String? {
        guard let e else { return nil }
        guard let riegel = e.riegel else { return String(localized: "Estimate: no run this long yet") }
        return shared ? nil : RacePredictionCopy.sourceCaption(riegel, system: system)
    }

    private func note(_ text: String) -> some View {
        Text(text)
            .font(StrandFont.footnote)
            .foregroundStyle(StrandPalette.textTertiary)
            .fixedSize(horizontal: false, vertical: true)
    }
}

/// The daily history of one target's prediction (`Repository.racePredictionHistory`), picked from 5K / 10K /
/// Half / Marathon. Each point is replayed from the current runs for one day of the last year, as of that day.
struct RacePredictionTrendSheet: View {
    @EnvironmentObject var repo: Repository
    let system: UnitSystem
    let onClose: () -> Void
    @State private var target = 0
    @State private var points: [TrendPoint] = []

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: NoopMetrics.gap) {
                    Picker("Distance", selection: $target) {
                        ForEach(0..<RacePredictor.targetDistancesM().count, id: \.self) { i in
                            Text(RacePredictionCopy.targetLabel(i)).tag(i)
                        }
                    }
                    .pickerStyle(.segmented)
                    ChartCard(title: "Predicted time",
                              subtitle: String(localized: "Each day over the last year"),
                              trailing: points.last.map { RacePredictor.formatTime(Int($0.value)) },
                              tint: StrandPalette.metricCyan,
                              chart: {
                                  if points.count >= 2 {
                                      TrendChart(points: points,
                                                 gradient: Gradient(colors: [StrandPalette.metricCyan, StrandPalette.metricCyan]),
                                                 valueRange: range,
                                                 showsArea: false,
                                                 height: NoopMetrics.chartHeight,
                                                 valueFormat: { RacePredictor.formatTime(Int($0.rounded())) },
                                                 accessibilityLabel: String(localized: "Race prediction trend"))
                                  } else {
                                      Text("Not enough days yet.")
                                          .font(StrandFont.subhead)
                                          .foregroundStyle(StrandPalette.textTertiary)
                                          .frame(maxWidth: .infinity, maxHeight: .infinity)
                                  }
                              },
                              footer: {
                                  ChartFooter([
                                      ("Best", points.map(\.value).min().map { RacePredictor.formatTime(Int($0)) } ?? "—"),
                                      ("Slowest", points.map(\.value).max().map { RacePredictor.formatTime(Int($0)) } ?? "—"),
                                  ])
                              })
                    Text("Lower is faster. Compare shows this beside your watch's own predictions.")
                        .font(StrandFont.footnote)
                        .foregroundStyle(StrandPalette.textTertiary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .padding(NoopMetrics.gap)
            }
            .navigationTitle(Text("Race predictions"))
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { onClose() } }
            }
        }
        #if os(macOS)
        .frame(minWidth: 480, minHeight: 520)
        #endif
        .task(id: target) { await load() }
    }

    /// A value range with a little headroom, so a flat line does not sit on the chart's edge.
    private var range: ClosedRange<Double> {
        let v = points.map(\.value)
        guard let lo = v.min(), let hi = v.max() else { return 0...1 }
        let pad = max(30, (hi - lo) * 0.1)
        return (lo - pad)...(hi + pad)
    }

    private func load() async {
        let rows = await repo.racePredictionHistory(targetIndex: target)
        points = rows.compactMap { p in Self.localDate(p.day).map { TrendPoint(date: $0, value: p.value) } }
    }

    /// Local midnight of a "yyyy-MM-dd" day, or nil when it does not parse.
    private static func localDate(_ day: String) -> Date? {
        let parts = day.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3 else { return nil }
        return Calendar.current.date(from: DateComponents(year: parts[0], month: parts[1], day: parts[2]))
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
                                RacePredictionRow(targetIndex: index, seconds: p.seconds,
                                                  secPerKm: RacePredictor.secPerKm(p), system: system,
                                                  caption: isBest ? String(localized: "Your best") : nil,
                                                  highlighted: isBest)
                            }
                        }
                        Divider().overlay(StrandPalette.hairline)
                        Text("Riegel's formula over this whole run. \"Your best\" marks the targets where this run sets your current prediction.")
                            .font(StrandFont.footnote)
                            .foregroundStyle(StrandPalette.textTertiary)
                            .fixedSize(horizontal: false, vertical: true)
                        if cardBlends {
                            Text(String(localized: "Your race card also blends in heart rate and VO₂max, so its times can differ from this run's."))
                                .font(StrandFont.footnote)
                                .foregroundStyle(StrandPalette.textTertiary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                }
            }
        }
    }

    /// True when the race card's times blend in a heart-rate or VO₂max estimate, so they can differ from
    /// the Riegel-only "Your best" marks here.
    private var cardBlends: Bool {
        best?.estimates.contains { $0?.components.hrS != nil || $0?.components.vo2S != nil } ?? false
    }

    private func isCurrentBest(_ index: Int) -> Bool {
        guard let best, index < best.items.count, let b = best.items[index] else { return false }
        return b.sourceStartTs == run.startTs && b.sourceDistanceM == (run.distanceM ?? 0)
    }
}
