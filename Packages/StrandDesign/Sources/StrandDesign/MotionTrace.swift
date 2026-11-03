import SwiftUI

// MARK: - MotionTrace (§9.4 Sleep — restlessness trace, #407)
//
// A subordinate per-epoch MOVEMENT / restlessness trace drawn UNDERNEATH the Hypnogram, on the SAME
// horizontal timeline. Each value is one epoch's motion magnitude (the SleepStager's per-epoch movement
// on the 30 s grid persisted as `motionJSON`); higher = more movement = more restless. It reads as a
// short filled "seismograph" strip so the eye can line a restless burst up against the stage band above
// it, without competing with the hypnogram for vertical space.
//
// Scrubbing (#58): hover, click-drag on macOS, or touch-and-hold then drag on iOS shows a cursor that
// names the epoch's movement as a share of the night's peak (the trace's own scale) and, when the
// caller passes the night's `start`, the epoch's clock time.
//
// HONESTY (#407): when there is no persisted motion (older rows whose `motionJSON` is NULL) the caller
// shows an honest empty state instead of this view — a flat fabricated zero line would be a lie. This
// view itself only renders when given a non-empty series.

public struct MotionTrace: View {

    /// Per-epoch motion magnitudes, oldest→newest, laid left→right across the SAME span as the hypnogram
    /// above. Values are arbitrary-unit magnitudes (≥ 0); the trace self-normalises to its own peak so a
    /// quiet night and a restless night both fill the strip — it shows the SHAPE of movement, not an
    /// absolute scale (which the strap doesn't calibrate).
    public var epochs: [Double]
    /// Strip height. Kept short so it stays clearly subordinate to the hypnogram.
    public var height: CGFloat
    /// The trace tint — defaults to the sleep accent so it belongs to the same visual family as the
    /// hypnogram bands without mimicking a stage colour.
    public var tint: Color
    /// Wall-clock start of the first epoch. When set, the scrub callout names each epoch's clock time;
    /// when nil it shows the movement value alone.
    public var start: Date?

    public init(epochs: [Double], height: CGFloat = 44, tint: Color = StrandPalette.textTertiary,
                start: Date? = nil) {
        self.epochs = epochs
        self.height = height
        self.tint = tint
        self.start = start
    }

    /// The epoch grid the trace is drawn on: one value per 30 s.
    static let epochSeconds: TimeInterval = 30

    /// Wall-clock start of epoch `index` on a night that began at `start`.
    static func epochDate(_ index: Int, from start: Date) -> Date {
        start.addingTimeInterval(Double(index) * epochSeconds)
    }

    /// An epoch's movement as a whole percentage of the night's peak, the scale the strip is drawn on.
    /// Zero for a non-positive peak, clamped to 0...100.
    static func peakPercent(_ value: Double, peak: Double) -> Int {
        guard peak > 0 else { return 0 }
        return Int((max(0, min(value / peak, 1)) * 100).rounded())
    }

    /// Clock format for the callout: "jmm" follows the device's 12/24-hour setting, as the
    /// hypnogram's tooltip does (#337).
    static let clockFormatter: DateFormatter = {
        let f = DateFormatter(); f.locale = Locale.current; f.setLocalizedDateFormatFromTemplate("jmm"); return f
    }()

    /// The peak magnitude used to normalise the fill height. A non-positive peak (all-zero / empty) maps
    /// everything to the baseline so the strip is flat rather than dividing by zero.
    ///
    /// Computed ONCE where it is needed and threaded down, never read inside a per-epoch loop. As a
    /// computed property it rescanned every epoch on each read, and it was read from inside the `map` in
    /// `points(in:)` and the `filter` in `accessibilitySummary`, so a night cost a scan per epoch. With
    /// 30-second epochs an 8-hour night is ~960 of them, about 1.8 million comparisons every time the
    /// strip is laid out, and SwiftUI re-runs `body` on hover, animation and the 1 Hz HR tick (#2283).
    ///
    /// Android already hoists the same value (`SleepScreen.kt`), so this removes a divergence rather than
    /// creating one.
    static func peak(of epochs: [Double]) -> Double { max(epochs.max() ?? 0, 0) }

    public var body: some View {
        GeometryReader { geo in
            let w = geo.size.width
            let h = geo.size.height
            // ONE scan, threaded into everything below, rather than a scan per epoch. See `peak(of:)`.
            //
            // Sits inside the GeometryReader, so it is recomputed per LAYOUT pass rather than per body
            // evaluation. That distinction does not matter here (both are O(n) against the O(n^2) this
            // replaces) and keeping it here avoids restructuring `body` around the ViewBuilder.
            let peak = Self.peak(of: epochs)
            ZStack {
                // Faint baseline so the strip reads as a grounded trace even on a calm night.
                Path { p in
                    p.move(to: CGPoint(x: 0, y: h - 1))
                    p.addLine(to: CGPoint(x: w, y: h - 1))
                }
                .stroke(StrandPalette.hairline.opacity(0.4), lineWidth: 1)

                // Filled area under the per-epoch magnitude, normalised to the night's own peak.
                if epochs.count >= 2, peak > 0 {
                    let pts = Self.points(in: geo.size, epochs: epochs, peak: peak)
                    Path { p in
                        p.move(to: CGPoint(x: 0, y: h))
                        for pt in pts { p.addLine(to: pt) }
                        p.addLine(to: CGPoint(x: w, y: h))
                        p.closeSubpath()
                    }
                    .fill(tint.opacity(0.22))

                    // The crest line on top of the fill for definition.
                    Path { p in
                        guard let first = pts.first else { return }
                        p.move(to: first)
                        for pt in pts.dropFirst() { p.addLine(to: pt) }
                    }
                    .stroke(tint.opacity(0.8), style: StrokeStyle(lineWidth: 1.5, lineJoin: .round))
                } else if epochs.count == 1, peak > 0 {
                    // A single epoch can't form a line — draw it as one centered tick so it isn't invisible.
                    let y = h - CGFloat(epochs[0] / peak) * (h - 2)
                    Path { p in
                        p.move(to: CGPoint(x: w / 2, y: h))
                        p.addLine(to: CGPoint(x: w / 2, y: y))
                    }
                    .stroke(tint.opacity(0.8), lineWidth: 1.5)
                }

                #if !os(watchOS)
                MotionTraceScrubLayer(epochs: epochs, peak: peak, start: start, tint: tint, size: geo.size)
                #endif
            }
            .accessibilityElement()
            .accessibilityLabel(Text("Movement during sleep"))
            .accessibilityValue(Text(Self.accessibilitySummary(epochs: epochs, peak: peak)))
        }
        .frame(height: height)
    }

    /// One screen point per epoch: x spread evenly across the width (matching the hypnogram's left→right
    /// time mapping), y the magnitude normalised to the night's peak (0 at the baseline, full at the top).
    static func points(in size: CGSize, epochs: [Double], peak: Double) -> [CGPoint] {
        let n = epochs.count
        guard n >= 2, peak > 0 else { return [] }
        let h = size.height
        let usable = h - 2   // leave 1px top/bottom padding so the crest isn't clipped
        return epochs.enumerated().map { i, v in
            let x = CGFloat(i) / CGFloat(n - 1) * size.width
            let frac = CGFloat(max(0, min(v / peak, 1)))
            return CGPoint(x: x, y: h - frac * usable)
        }
    }

    /// A coarse VoiceOver summary — the share of epochs with above-half-peak movement — since a per-epoch
    /// trace can't be voiced point by point. "Calm" when nothing crosses the threshold.
    static func accessibilitySummary(epochs: [Double], peak: Double) -> String {
        guard peak > 0, !epochs.isEmpty else { return "no movement data" }
        let restless = epochs.filter { $0 >= peak * 0.5 }.count
        if restless == 0 { return "calm throughout" }
        let pct = Int((Double(restless) / Double(epochs.count) * 100).rounded())
        return "\(pct)% of the night had elevated movement"
    }
}

#if !os(watchOS)
/// The scrub cursor over a `MotionTrace`: a rule and dot on the nearest epoch, with a callout naming its
/// movement and clock time (#58). Kept apart from `MotionTrace` because the gesture and the hover
/// toolkit do not exist on watchOS.
private struct MotionTraceScrubLayer: View {
    let epochs: [Double]
    let peak: Double
    let start: Date?
    let tint: Color
    let size: CGSize

    @State private var scrubX: CGFloat? = nil

    var body: some View {
        let index = scrubX.flatMap { ChartHoverMath.nearestIndex(toX: $0, count: epochs.count, width: size.width) }
        ZStack(alignment: .topLeading) {
            Color.clear
            if let index, peak > 0 {
                let point = epochs.count >= 2
                    ? MotionTrace.points(in: size, epochs: epochs, peak: peak)[index]
                    : CGPoint(x: size.width / 2, y: size.height - CGFloat(epochs[0] / peak) * (size.height - 2))
                let movement = String(localized: "Movement", bundle: .module)
                CrosshairRule(x: point.x, height: size.height)
                HighlightDot(color: tint, diameter: 7)
                    .position(point)
                PositionedTooltip(
                    anchor: point,
                    container: size,
                    tooltip: ChartTooltip(
                        value: (Double(MotionTrace.peakPercent(epochs[index], peak: peak)) / 100).formatted(.percent),
                        label: start.map {
                            "\(movement) · \(MotionTrace.clockFormatter.string(from: MotionTrace.epochDate(index, from: $0)))"
                        } ?? movement,
                        accent: tint
                    )
                )
            }
        }
        .frame(width: size.width, height: size.height)
        .animation(StrandMotion.fade, value: scrubX)
        .chartScrub($scrubX, enabled: !epochs.isEmpty)
        .chartScrubHaptic(index)
    }
}
#endif

#if DEBUG
#Preview("MotionTrace") {
    let calm = (0..<60).map { _ in Double.random(in: 0...0.1) }
    let restless = (0..<60).map { i -> Double in
        i % 11 == 0 ? Double.random(in: 0.7...1.0) : Double.random(in: 0...0.2)
    }
    return VStack(alignment: .leading, spacing: 16) {
        Text("Calm night").strandOverline()
        MotionTrace(epochs: calm)
        Text("Restless night").strandOverline()
        MotionTrace(epochs: restless, start: Date(timeIntervalSince1970: 1_700_000_000))
    }
    .padding(28)
    .frame(width: 720, height: 280)
    .background(StrandPalette.surfaceBase)
    .preferredColorScheme(.dark)
}
#endif
