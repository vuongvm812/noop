import SwiftUI
import StrandDesign
import StrandAnalytics

// MARK: - Tomorrow's Charge card (Today screen)
//
// An evening card: "Bed by 22:45 → ~68% (60–76)", the same forecast one hour earlier and later, and a
// bedtime slider (15-minute steps, 21:00–02:00). Shown from the afternoon until that night's sleep starts;
// the same gate decides whether tonight's forecast may still be overwritten in storage, so the card and
// the stored value can never disagree about whether the night is still open. Every number comes from
// `Repository.tomorrowForecast`, the resolver the Intelligence card reads too. Once enough nights are scored
// the footer says how close past forecasts came. Twin of Android `TomorrowChargeCard`.

struct TomorrowChargeCard: View {
    @EnvironmentObject var repo: Repository
    @EnvironmentObject var profile: ProfileStore
    @EnvironmentObject var behavior: BehaviorStore

    @AppStorage(TomorrowForecastPrefs.bedtimeKey) private var selectedTs: Double = 0
    @State private var accuracy: ForecastAccuracy.Summary?

    private var age: Int? { profile.age > 0 ? profile.age : nil }

    var body: some View {
        // Re-resolve each minute so the card disappears when the window closes without a data refresh.
        TimelineView(.periodic(from: .now, by: 60)) { context in
            if let t = repo.tomorrowForecast(now: context.date, age: age, behavior: behavior), t.isOpen {
                card(t)
                    .task(id: TomorrowForecastSaveKey(t)) {
                        // Debounced: a slider drag re-keys this on every step; only the value it rests on
                        // is written.
                        try? await Task.sleep(nanoseconds: 500_000_000)
                        guard !Task.isCancelled else { return }
                        await repo.saveTomorrowForecast(t)
                    }
                    .task(id: repo.refreshSeq) { accuracy = await repo.forecastAccuracy() }
            }
        }
    }

    private func card(_ t: RecoveryForecaster.Tonight) -> some View {
        NoopCard(tint: StrandPalette.chargeColor) {
            VStack(alignment: .leading, spacing: NoopMetrics.space3) {
                HStack(spacing: NoopMetrics.space2) {
                    Image(systemName: "moon.stars")
                        .font(StrandFont.headline)
                        .foregroundStyle(StrandPalette.chargeColor)
                        .accessibilityHidden(true)
                    Text("Tomorrow's Charge")
                        .font(StrandFont.headline)
                        .foregroundStyle(StrandPalette.textPrimary)
                    Spacer()
                    Text("Estimate")
                        .font(StrandFont.footnote)
                        .foregroundStyle(StrandPalette.textTertiary)
                }
                optionRow(t.headline, primary: true)
                ForEach(Array(t.options.dropFirst().enumerated()), id: \.offset) { _, option in
                    if Self.showsAlternative(option, in: t) { optionRow(option, primary: false) }
                }
                if Self.sliderSpan(t) != nil { slider(t) }
                Text("Suggested bedtime \(clock(t.suggestedTs)) to sleep \(duration(t.plan.needHours)) before your \(clock(t.plan.wakeTs)) wake.")
                    .font(StrandFont.footnote)
                    .foregroundStyle(StrandPalette.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
                if let a = accuracy {
                    Text("Last \(a.scored) forecasts: off by ~\(Int(a.meanAbsError.rounded())) points on average, \(a.inside)/\(a.scored) inside the range.")
                        .font(StrandFont.footnote)
                        .foregroundStyle(StrandPalette.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
    }

    private func optionRow(_ option: RecoveryForecaster.BedtimeOption, primary: Bool) -> some View {
        let f = option.forecast
        return HStack(alignment: .firstTextBaseline) {
            Text("Bed by \(clock(option.bedtimeTs))")
                .font(primary ? StrandFont.title2 : StrandFont.subhead)
                .foregroundStyle(primary ? StrandPalette.textPrimary : StrandPalette.textSecondary)
            Spacer()
            Text(verbatim: Self.chargeLabel(f))
                .font(primary ? StrandFont.title2 : StrandFont.subhead)
                .foregroundStyle(StrandPalette.recoveryColor(f.charge))
            Text(verbatim: Self.bandLabel(f))
                .font(StrandFont.captionNumber)
                .foregroundStyle(StrandPalette.textTertiary)
        }
        .accessibilityElement(children: .combine)
    }

    /// An alternative bedtime is offered only while it can still be chosen: at or after the slider's start
    /// (now, rounded up), or before the evening has reached the slider at all.
    static func showsAlternative(_ option: RecoveryForecaster.BedtimeOption,
                                 in t: RecoveryForecaster.Tonight) -> Bool {
        option.bedtimeTs >= t.sliderStartTs || t.sliderStartTs <= t.window.earliestTs
    }

    /// The slider's travel in seconds from `sliderStartTs` to the window's latest bedtime, or nil when less
    /// than one step is left (the slider is then hidden: there is nothing to pick).
    static func sliderSpan(_ t: RecoveryForecaster.Tonight) -> Double? {
        let span = t.window.latestTs - t.sliderStartTs
        return span >= RecoveryForecaster.bedtimeStepSec ? span : nil
    }

    @ViewBuilder
    private func slider(_ t: RecoveryForecaster.Tonight) -> some View {
        if let span = Self.sliderSpan(t) {
            let start = t.sliderStartTs
            VStack(spacing: NoopMetrics.space2) {
                Slider(
                    value: Binding(get: { t.sliderTs - start },
                                   set: { selectedTs = start + $0 }),
                    in: 0...span, step: RecoveryForecaster.bedtimeStepSec
                ) {
                    Text("Bedtime")
                }
                .tint(StrandPalette.chargeColor)
                HStack {
                    Text(verbatim: clock(start))
                    Spacer()
                    Text(verbatim: clock(t.window.latestTs))
                }
                .font(StrandFont.captionNumber)
                .foregroundStyle(StrandPalette.textTertiary)
                .accessibilityHidden(true)
            }
        }
    }

    /// "~68%": the forecast Charge, a number (nothing to translate), like the Charge readouts elsewhere.
    static func chargeLabel(_ f: RecoveryForecast) -> String { "~\(Int(f.charge.rounded()))%" }

    /// "(60–76)": the forecast's band.
    static func bandLabel(_ f: RecoveryForecast) -> String { "(\(Int(f.low.rounded()))–\(Int(f.high.rounded())))" }

    private func clock(_ ts: Double) -> String { AppClock.hourMinute(unix: Int(ts)) }

    /// "8h" / "8h 15m", rounded to the nearest 5 minutes.
    private func duration(_ hours: Double) -> String {
        let minutes = Int((hours * 12).rounded()) * 5
        let h = minutes / 60, m = minutes % 60
        return m == 0 ? "\(h)h" : "\(h)h \(m)m"
    }
}

/// What a forecast save is keyed on: the values it writes, the day it writes them for, whether it may
/// write at all and the bedtime they came from. A change to any one re-runs the (debounced) save; an
/// unrelated data refresh that leaves them all equal does not. Shared by the Today and Intelligence cards.
struct TomorrowForecastSaveKey: Hashable {
    let charge: Double
    let low: Double
    let high: Double
    let target: String
    let isOpen: Bool
    let anchor: Double

    init(_ t: RecoveryForecaster.Tonight) {
        let f = t.headline.forecast
        charge = f.charge
        low = f.low
        high = f.high
        target = t.window.targetDay
        isOpen = t.isOpen
        anchor = t.anchorTs
    }
}
