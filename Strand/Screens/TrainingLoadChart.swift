import SwiftUI
import Charts
import StrandDesign

/// The CTL/ATL line-overlay chart for `TrainingLoadCard`, split into its own file (design-system rule:
/// don't let a screen file bloat) so the downsampling/hover code below doesn't grow `TrainingLoadCard`'s
/// already-documented model/memoization logic.
///
/// PERF: `TrainingLoadCard.days` is the full app history (the card's own doc comment: "models the full
/// history rather than the Trends range window"), so a multi-year user feeds two full-resolution EWMA
/// lines (CTL/ATL) — far more vertices than a ~360pt plot can resolve. Each series is independently
/// min/max-bucketed with the SAME `ChartDownsample.minMaxBucketed` helper `TrendChart`/`OverviewHRChart`
/// use (StrandDesign's generic overload, added so an app-target chart type outside that package can call
/// it instead of hand-duplicating the algorithm the way `CompareView.Model.minMaxBucketed` had to).
/// Downsampling CTL and ATL SEPARATELY — rather than bucketing once and reusing the choice for both —
/// keeps each curve's own peaks/troughs even though the two diverge in shape; hover always reads the
/// full-resolution `rows`, never the downsampled draw set.
///
/// Also adds the scrub cursor/tooltip readout this chart never had, reusing the exact `CrosshairRule` /
/// `HighlightDot` / `PositionedTooltip` / `ChartTooltip` components `TrendChart`'s `chartOverlay` uses —
/// no new hover mechanism invented.
struct TrainingLoadChart: View {
    let rows: [TrainingLoadCard.Row]

    /// The x-position the cursor is hovering, in chart-local coordinates.
    @State private var hoverX: CGFloat? = nil

    private var ctlRows: [TrainingLoadCard.Row] {
        ChartDownsample.minMaxBucketed(rows, threshold: ChartDownsample.markThreshold,
                                        targetCount: ChartDownsample.targetVertices,
                                        date: { $0.date }, value: { $0.ctl })
    }

    private var atlRows: [TrainingLoadCard.Row] {
        ChartDownsample.minMaxBucketed(rows, threshold: ChartDownsample.markThreshold,
                                        targetCount: ChartDownsample.targetVertices,
                                        date: { $0.date }, value: { $0.atl })
    }

    /// What the cursor at chart-local `x` is on: the full-resolution row nearest it, and the empty day's
    /// date when the cursor sits over a missing row instead (#58). Deliberately reads `rows` (not
    /// `ctlRows`/`atlRows`), matching `TrendChart.scrubTarget`: the readout always names the real datum,
    /// never a downsampled stand-in.
    private func scrubTarget(atX x: CGFloat, proxy: ChartProxy, plot: CGRect) -> (row: TrainingLoadCard.Row, gap: Date?)? {
        let relX = min(max(x, plot.minX), plot.maxX) - plot.minX
        guard let date: Date = proxy.value(atX: relX),
              let hit = ChartScrubMath.resolve(dates: rows.map(\.date), cursor: date)
        else { return nil }
        return (rows[hit.index], hit.gapAt.map { Date(timeIntervalSince1970: TimeInterval($0)) })
    }

    private func fmt(_ v: Double) -> String { String(format: "%.1f", v) }

    private static let tooltipDateFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "d MMM yyyy"
        return f
    }()

    var body: some View {
        // Floor at 1 (matching the Android `fold(1.0)` twin): an all-rest window of zero loads would
        // otherwise make the y-domain `0...0`, which Swift Charts renders as a degenerate/empty scale.
        let maxY = max(rows.map { max($0.ctl, $0.atl) }.max() ?? 1, 1)
        Chart {
            ForEach(ctlRows) { r in
                LineMark(x: .value("Day", r.date), y: .value("CTL", r.ctl),
                         series: .value("Series", "CTL"))
                    .foregroundStyle(StrandPalette.gold)
                    .interpolationMethod(.catmullRom)
            }
            ForEach(atlRows) { r in
                LineMark(x: .value("Day", r.date), y: .value("ATL", r.atl),
                         series: .value("Series", "ATL"))
                    .foregroundStyle(StrandPalette.strain100)
                    .interpolationMethod(.catmullRom)
            }
        }
        .chartYScale(domain: 0...(maxY * 1.08))
        .chartYAxis { AxisMarks(position: .leading) }
        .chartOverlay { proxy in
            GeometryReader { geo in
                let plot = proxy.plotRectCompat(in: geo)
                let target = hoverX.flatMap { scrubTarget(atX: $0, proxy: proxy, plot: plot) }
                ZStack(alignment: .topLeading) {
                    if let target, let gap = target.gap, let gx = proxy.position(forX: gap) {
                        let cx = gx + plot.minX
                        CrosshairRule(x: cx, height: geo.size.height)
                        PositionedTooltip(
                            anchor: CGPoint(x: cx, y: plot.midY),
                            container: geo.size,
                            tooltip: ChartTooltip(value: ChartScrubMath.noDataText,
                                                  label: Self.tooltipDateFormatter.string(from: gap))
                        )
                    } else if let p = target?.row,
                       let px = proxy.position(forX: p.date),
                       let pyCTL = proxy.position(forY: p.ctl),
                       let pyATL = proxy.position(forY: p.atl) {
                        let cx = px + plot.minX
                        CrosshairRule(x: cx, height: geo.size.height)
                        HighlightDot(color: StrandPalette.gold)
                            .position(x: cx, y: pyCTL + plot.minY)
                        HighlightDot(color: StrandPalette.strain100)
                            .position(x: cx, y: pyATL + plot.minY)
                        PositionedTooltip(
                            anchor: CGPoint(x: cx, y: min(pyCTL, pyATL) + plot.minY),
                            container: geo.size,
                            tooltip: ChartTooltip(
                                value: String(localized: "CTL \(fmt(p.ctl)) · ATL \(fmt(p.atl))"),
                                label: Self.tooltipDateFormatter.string(from: p.date)
                            )
                        )
                    }
                }
                .animation(StrandMotion.fade, value: hoverX)
                .frame(width: geo.size.width, height: geo.size.height, alignment: .topLeading)
                .chartScrub($hoverX)
                .chartScrubHaptic(target.map { $0.gap ?? $0.row.date })
            }
        }
        .accessibilityLabel(Text("Training load: chronic vs acute"))
    }
}
