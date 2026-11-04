import SwiftUI
import StrandDesign

/// One WHOOP-style stage lane: a hatched night-long track with solid segments where `stage` occurred,
/// shared by the Sleep tab's stage rows and the hosted Stages card.
///
/// Scrubbing (#58) snaps a ring to the segment under the cursor, or the nearest of this stage, and names
/// the stage with its clock range and length. A lane is sparse by design (the other stages fill the gaps),
/// so empty track is never "no data" here. The scrub is tap-through: hover reads on macOS, touch-and-hold
/// then drag on iOS, so a click or plain tap still reaches the row's highlight toggle.
struct StageLaneTrack: View {
    let stage: SleepStage
    /// This stage's intervals only, ascending.
    let intervals: [SleepInterval]
    /// The night's first interval start and its span, in the intervals' own seconds.
    let origin: TimeInterval
    let span: TimeInterval
    /// The clock instant `origin` corresponds to, for the readout's times.
    let onset: Date
    /// Another stage is highlighted: this lane's segments grey out.
    let dimmed: Bool

    @State private var scrubX: CGFloat? = nil

    /// Clock labels in the device's 12/24-hour form, routed through AppClock like the stage axis (#1821).
    private static var clockFormatter: DateFormatter { AppClock.hourMinuteFormatter() }

    var body: some View {
        GeometryReader { geo in
            let hit = Self.segmentIndex(atX: scrubX, in: intervals, origin: origin, span: span,
                                        width: geo.size.width)
            ZStack(alignment: .topLeading) {
                HatchedTrack()
                ForEach(intervals) { iv in
                    let frame = segmentFrame(iv, width: geo.size.width)
                    RoundedRectangle(cornerRadius: 1.5, style: .continuous)
                        .fill(dimmed ? StrandPalette.textTertiary.opacity(0.55)
                                     : StrandPalette.sleepStageColor(stage))
                        .frame(width: frame.width, height: geo.size.height)
                        .offset(x: frame.minX)
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: 4, style: .continuous))
            .overlay(alignment: .topLeading) {
                if let hit {
                    readout(intervals[hit], size: geo.size)
                }
            }
            .chartScrub($scrubX, enabled: !intervals.isEmpty, tapThrough: true)
            .chartScrubHaptic(hit)
        }
        .frame(height: 20)
    }

    /// The ring on the snapped segment and its tooltip. The lane is shorter than the tooltip, so the
    /// tooltip floats just above it (`ChartTooltipPlacement`).
    @ViewBuilder
    private func readout(_ iv: SleepInterval, size: CGSize) -> some View {
        let frame = segmentFrame(iv, width: size.width)
        let anchor = CGPoint(x: frame.midX, y: size.height / 2)
        RoundedRectangle(cornerRadius: 3, style: .continuous)
            .stroke(StrandPalette.hairlineStrong, lineWidth: 1.5)
            .frame(width: frame.width + 4, height: size.height + 4)
            .position(anchor)
        PositionedTooltip(
            anchor: anchor,
            container: size,
            tooltip: ChartTooltip(
                value: stage.label,
                label: "\(clock(iv.start))–\(clock(iv.end)) · \(Self.durationText((iv.end - iv.start) / 60))",
                accent: StrandPalette.sleepStageColor(stage)
            )
        )
    }

    private func segmentFrame(_ iv: SleepInterval, width: CGFloat) -> CGRect {
        let x0 = CGFloat((iv.start - origin) / span) * width
        let w = max(2, CGFloat((iv.end - iv.start) / span) * width)
        return CGRect(x: x0, y: 0, width: w, height: 0)
    }

    private func clock(_ t: TimeInterval) -> String {
        Self.clockFormatter.string(from: onset.addingTimeInterval(t - origin))
    }

    /// The segment a cursor at `x` snaps to: the one under it, else the nearest by centre. Nil without a
    /// cursor or a segment.
    static func segmentIndex(atX x: CGFloat?, in intervals: [SleepInterval], origin: TimeInterval,
                             span: TimeInterval, width: CGFloat) -> Int? {
        guard let x, width > 0 else { return nil }
        let t = origin + Double(min(max(x, 0), width) / width) * span
        return Hypnogram.scrubHit(at: t, in: intervals)?.index
    }

    /// Minutes as "Xm" / "Yh Zm", the form the stage rows' durations use.
    static func durationText(_ minutes: Double) -> String {
        let m = Swift.max(0, Int(minutes.rounded()))
        if m < 60 { return String(localized: "\(m)m") }
        return String(localized: "\(m / 60)h \(m % 60)m")
    }

    /// WHOOP's diagonal-hatched timeline track: subtle 45° stripes over a dark inset well — reads
    /// as "the whole night" behind the solid stage segments, and makes gaps (other stages) obvious
    /// without drawing anything for them.
    private struct HatchedTrack: View {
        var body: some View {
            ZStack {
                Rectangle().fill(StrandPalette.surfaceInset.opacity(0.9))
                Canvas { context, size in
                    var path = Path()
                    let step: CGFloat = 5
                    var x: CGFloat = -size.height
                    while x < size.width {
                        path.move(to: CGPoint(x: x, y: size.height))
                        path.addLine(to: CGPoint(x: x + size.height, y: 0))
                        x += step
                    }
                    context.stroke(path, with: .color(StrandPalette.textTertiary.opacity(0.16)), lineWidth: 1)
                }
            }
        }
    }
}
