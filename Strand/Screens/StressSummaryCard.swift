import SwiftUI
import StrandAnalytics
import StrandDesign

// MARK: - Stress card (Today + Stress screen) — #42
//
// Today's stress at a glance: the latest level, today's average, a small intraday sparkline and the
// source (Garmin's own 0-100 stress, or NOOP's 0-3 proxy), at the top of the Stress screen. The Today
// Key Metrics Stress tile (#57) and the pinned "Your cards" Stress row (#94) read the SAME
// `StressTodayLoader` → `StressToday.resolve` and tap through to this screen, so the readouts cannot
// disagree. Twin of Android `StressSummaryCard` (android/.../ui/StressSummaryCard.kt).

struct StressSummaryCard: View {

    /// Handed in by the Stress screen, which also draws the hero gauge from it (#94): one read, two
    /// figures, rather than two reads that could land a fingerprint apart.
    let reading: StressToday.Reading?
    let loaded: Bool

    var body: some View {
        NoopCard(tint: StrandPalette.stressColor) {
            VStack(alignment: .leading, spacing: NoopMetrics.space3) {
                header
                if !loaded {
                    Text("Calibrating")
                        .font(StrandFont.subhead)
                        .foregroundStyle(StrandPalette.textTertiary)
                } else if let reading {
                    figures(reading)
                    if reading.points.count >= 2 {
                        // The scrub (#58) names each point's level and time.
                        let points = reading.points
                        Sparkline(values: points.map(\.level),
                                  gradient: Gradient(colors: [StrandPalette.stressColor.opacity(0.5),
                                                              StrandPalette.stressColor]),
                                  range: 0...reading.scaleMax,
                                  valueFormat: { Self.format($0, source: reading.source) },
                                  indexLabel: { Self.timeLabel(points[$0].ts) })
                            .frame(height: 36)
                            .accessibilityHidden(true)
                    }
                } else {
                    Text("No stress reading yet today.")
                        .font(StrandFont.subhead)
                        .foregroundStyle(StrandPalette.textTertiary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
    }

    private var header: some View {
        HStack(spacing: NoopMetrics.space2) {
            Image(systemName: "gauge.with.dots.needle.50percent")
                .font(StrandFont.headline)
                .foregroundStyle(StrandPalette.stressColor)
                .accessibilityHidden(true)
            Text("Stress")
                .font(StrandFont.headline)
                .foregroundStyle(StrandPalette.textPrimary)
            Spacer()
            if let reading {
                Text(Self.sourceLabel(reading.source))
                    .font(StrandFont.caption)
                    .foregroundStyle(StrandPalette.textTertiary)
            }
        }
    }

    private func figures(_ reading: StressToday.Reading) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: NoopMetrics.space4) {
            figure(label: Text("Latest"), value: reading.latest?.level, reading: reading,
                   caption: reading.latest.map { Self.timeLabel($0.ts) })
            figure(label: Text("Today's average"), value: reading.average, reading: reading, caption: nil)
            Spacer(minLength: 0)
        }
    }

    private func figure(label: Text, value: Double?, reading: StressToday.Reading,
                        caption: String?) -> some View {
        VStack(alignment: .leading, spacing: NoopMetrics.space1) {
            label.strandOverline()
            HStack(alignment: .firstTextBaseline, spacing: NoopMetrics.space1) {
                Text(value.map { Self.format($0, source: reading.source) } ?? "—")
                    .font(StrandFont.number(24))
                    .foregroundStyle(StrandPalette.textPrimary)
                Text(verbatim: Self.scaleSuffix(reading.scaleMax))
                    .font(StrandFont.captionNumber)
                    .foregroundStyle(StrandPalette.textTertiary)
            }
            if let caption {
                Text(caption)
                    .font(StrandFont.footnote)
                    .foregroundStyle(StrandPalette.textTertiary)
            }
        }
        .accessibilityElement(children: .combine)
    }

    /// Also the Key Metrics Stress tile's caption (#57).
    static func sourceLabel(_ source: StressToday.Source) -> String {
        switch source {
        case .garmin: return String(localized: "Garmin stress")
        case .noop:   return String(localized: "NOOP stress")
        }
    }

    /// Garmin's scale is whole numbers; NOOP's 0-3 proxy prints to one decimal like the Stress screen.
    static func format(_ value: Double, source: StressToday.Source) -> String {
        switch source {
        case .garmin: return "\(Int(value.rounded()))"
        case .noop:   return StressTrace.formatLevel(value)
        }
    }

    /// The figure the Key Metrics Stress tile and the pinned "Your cards" Stress row both print (#94): the
    /// latest level on its own scale, "1.4/3" or "37/100". Nil when the reading has no level. One funnel,
    /// so the tile and the row cannot print two numbers for one reading.
    static func cardText(_ reading: StressToday.Reading?) -> String? {
        guard let reading, let level = StressToday.tileLevel(reading) else { return nil }
        return format(level, source: reading.source) + scaleSuffix(reading.scaleMax)
    }

    /// The row's 0...1 fill for the same level, on the reading's own scale.
    static func cardFraction(_ reading: StressToday.Reading?) -> Double? {
        guard let reading, let level = StressToday.tileLevel(reading) else { return nil }
        return min(max(level / reading.scaleMax, 0), 1)
    }

    /// "/100" or "/3": the scale the figure is on. A number, so nothing to translate.
    static func scaleSuffix(_ scaleMax: Double) -> String {
        "/" + String(Int(scaleMax))
    }

    /// Also the Key Metrics Stress tile's scrub readout time (#58).
    static func timeLabel(_ ts: Int) -> String {
        Date(timeIntervalSince1970: TimeInterval(ts)).formatted(date: .omitted, time: .shortened)
    }
}
