package com.noop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import com.noop.R
import com.noop.analytics.EffortLoad
import com.noop.analytics.TrainingLoadEngine
import com.noop.data.DailyMetric
import kotlin.math.max

/**
 * Training Load card (CTL / ATL / TSB) — the first UI surface for [TrainingLoadEngine], twin of the
 * Apple `TrainingLoadCard`. Overlays chronic load (CTL, 42-day fitness proxy) and acute load (ATL,
 * 7-day fatigue proxy) on ONE shared y-scale, so the vertical gap between the lines reads as the TSB /
 * "form" (CTL − ATL), surfaced as the headline number + a footer stat.
 *
 * Descriptive only: CTL/ATL/TSB never feed the Charge/Readiness score, and the load is NOOP's daily
 * Effort/strain — not TRIMP. Long-horizon by nature, so it models the full history (not the range
 * window) and shows an honest "needs N more days" state until 14+ contiguous days exist.
 */
@Composable
fun TrainingLoadCard(days: List<DailyMetric>, modifier: Modifier = Modifier) {
    // Model straight from the training-load engine; the card needs no Readiness synthesis. The load is the
    // day's TRIMP recovered from Effort ([EffortLoad]), not the log-scaled Effort itself.
    val loads = days.map { TrainingLoadEngine.DailyLoad(it.day, it.strain?.let { s -> EffortLoad.trimpFromEffort(s) }) }
    val result = TrainingLoadEngine.evaluate(loads)
    val latest = result.points.lastOrNull()

    val subtitle = when (result.state) {
        TrainingLoadEngine.State.ESTABLISHED -> stringResource(R.string.trends_tl_established)
        TrainingLoadEngine.State.BUILDING ->
            stringResource(R.string.trends_tl_building, result.contiguousDays, TrainingLoadEngine.standard.establishedDays)
        TrainingLoadEngine.State.UNAVAILABLE -> stringResource(R.string.trends_tl_unavailable)
    }

    NoopCard(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Overline(text = stringResource(R.string.trends_training_load))
                    Text(subtitle, style = NoopType.footnote, color = Palette.textTertiary)
                }
                if (latest != null) {
                    Text(signed(latest.balance), style = NoopType.bodyNumber, color = Palette.textPrimary)
                }
            }

            if (!result.isAvailable) {
                Text(
                    stringResource(
                        R.string.trends_tl_needs,
                        TrainingLoadEngine.standard.minimumDays,
                        result.contiguousDays,
                    ),
                    style = NoopType.body,
                    color = Palette.textTertiary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = Metrics.space12),
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(Metrics.space12)) {
                    LegendDot(Palette.gold, stringResource(R.string.trends_tl_legend_ctl))
                    LegendDot(Palette.metricAmber, stringResource(R.string.trends_tl_legend_atl))
                }
                TwoLineChart(result.points, Modifier.fillMaxWidth().height(Metrics.chartHeight))
                Row(Modifier.fillMaxWidth()) {
                    // CTL/ATL are universal training-science acronyms (like HRV/SpO2) — same in every language.
                    Stat("CTL", latest?.let { fmt(it.chronicLoad) } ?: "—")
                    Stat("ATL", latest?.let { fmt(it.acuteLoad) } ?: "—")
                    Stat(stringResource(R.string.trends_tl_form), latest?.let { signed(it.balance) } ?: "—")
                    Stat(stringResource(R.string.trends_days), "${result.contiguousDays}")
                }
            }
        }
    }
}

@Composable
private fun TwoLineChart(points: List<TrainingLoadEngine.Point>, modifier: Modifier) {
    val ctl = Palette.gold
    val atl = Palette.metricAmber
    // Shared y-scale over BOTH series so the gap between the lines is a faithful TSB.
    val maxY = points.fold(1.0) { acc, p -> max(acc, max(p.chronicLoad, p.acuteLoad)) }
    // Scrub cursor (#58): one daily point per index, evenly spaced, so the nearest index is the day.
    var scrubX by remember(points) { mutableStateOf<Float?>(null) }
    var chartW by remember { mutableStateOf(0f) }
    val selected = scrubX?.takeIf { points.size >= 2 }?.let { x ->
        chartScrubNearestIndex(chartScrubIndexFractions(points.size), chartW, x)
    }
    ChartScrubHaptic(selected)
    val paints = rememberChartScrubPaints()
    val calloutValue = selected?.let { trainingLoadScrubValue(points[it]) }
    val calloutLabel = selected?.let { lastChargeDateLabel(points[it].day) }
    Canvas(
        modifier
            .onSizeChanged { chartW = it.width.toFloat() }
            .chartScrub(points, onRelease = { scrubX = null }) { scrubX = it }
            .semantics { if (calloutValue != null) stateDescription = "$calloutValue, $calloutLabel" },
    ) {
        val n = points.size
        if (n < 2) return@Canvas
        val w = size.width
        val h = size.height
        fun yOf(v: Double): Float = h - (v / maxY).toFloat() * h
        fun path(value: (TrainingLoadEngine.Point) -> Double): Path {
            val p = Path()
            points.forEachIndexed { i, pt ->
                val x = w * i / (n - 1)
                val y = yOf(value(pt))
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            return p
        }
        drawPath(path { it.chronicLoad }, color = ctl, style = Stroke(width = 3f))
        drawPath(path { it.acuteLoad }, color = atl, style = Stroke(width = 3f))
        if (selected != null && calloutValue != null) {
            val x = w * selected / (n - 1)
            val pt = points[selected]
            // The ATL dot comes from the shared cursor; the CTL one is drawn here in its own colour.
            drawCircle(color = Palette.surfaceBase, radius = 6.dp.toPx(), center = Offset(x, yOf(pt.chronicLoad)))
            drawCircle(color = ctl, radius = 4.5.dp.toPx(), center = Offset(x, yOf(pt.chronicLoad)))
            drawScrubCursor(x, yOf(pt.acuteLoad), atl, calloutValue, calloutLabel, paints)
        }
    }
}

/** The scrub callout's value line for one day: both loads, under the acronyms the card's stat row uses. */
internal fun trainingLoadScrubValue(point: TrainingLoadEngine.Point): String =
    "CTL ${fmt(point.chronicLoad)} · ATL ${fmt(point.acuteLoad)}"

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metrics.space4)) {
        Spacer(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(label, style = NoopType.footnote, color = Palette.textTertiary)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Stat(label: String, value: String) {
    Column(Modifier.weight(1f)) {
        Text(label, style = NoopType.footnote, color = Palette.textTertiary)
        Text(value, style = NoopType.captionNumber, color = Palette.textSecondary)
    }
}

private fun fmt(v: Double): String = String.format(java.util.Locale.US, "%.1f", v)
private fun signed(v: Double): String = String.format(java.util.Locale.US, "%+.1f", v)
