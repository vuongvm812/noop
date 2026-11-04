package com.noop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.noop.R
import com.noop.analytics.StressToday
import com.noop.widget.StressTrace
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

/**
 * StressSummaryCard — today's stress at a glance (#42).
 *
 * The latest level, today's average, a small intraday sparkline and the source (Garmin's own 0-100
 * stress, or NOOP's 0-3 proxy), at the top of the Stress screen. The Today Key Metrics Stress tile (#57)
 * and the pinned "Your cards" Stress row (#94) read the SAME [StressTodayLoader] → [StressToday.resolve]
 * and tap through to this screen, so the readouts cannot disagree. Twin of macOS `StressSummaryCard`
 * (Strand/Screens/StressSummaryCard.swift).
 *
 * [reading] is handed in by the Stress screen, which also draws its hero gauge from it (#94): one read,
 * two figures, rather than two reads that could land a fingerprint apart.
 */
@Composable
fun StressSummaryCard(
    reading: StressToday.Reading?,
    loaded: Boolean,
) {

    NoopCard(tint = Palette.stressColor) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Speed,
                    contentDescription = null,
                    tint = Palette.stressColor,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    uiString(R.string.today_section_stress),
                    style = NoopType.headline,
                    color = Palette.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                reading?.let {
                    Text(uiString(sourceLabelRes(it.source)), style = NoopType.caption, color = Palette.textTertiary)
                }
            }
            val r = reading
            when {
                !loaded -> Text(
                    uiString(R.string.l10n_today_screen_calibrating_37c2c9bd),
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                )
                r == null -> Text(
                    uiString(R.string.stress_summary_empty),
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                )
                else -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        StressFigure(
                            uiString(R.string.stress_summary_latest),
                            r.latest?.level,
                            r,
                            r.latest?.let { stressTimeLabel(it.ts) },
                        )
                        StressFigure(uiString(R.string.stress_summary_average), r.average, r, null)
                    }
                    if (r.points.size >= 2) {
                        Sparkline(
                            values = r.points.map { it.level },
                            color = Palette.stressColor,
                            formatValue = { formatStressSummaryValue(it, r.source) + stressScaleSuffix(r.scaleMax) },
                            selectionLabels = r.points.map { stressTimeLabel(it.ts) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StressFigure(label: String, value: Double?, reading: StressToday.Reading, caption: String?) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.semantics(mergeDescendants = true) {},
    ) {
        Overline(label)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                value?.let { formatStressSummaryValue(it, reading.source) } ?: "—",
                style = NoopType.number(24f),
                color = Palette.textPrimary,
            )
            Text(stressScaleSuffix(reading.scaleMax), style = NoopType.captionNumber, color = Palette.textTertiary)
        }
        if (caption != null) {
            Text(caption, style = NoopType.footnote, color = Palette.textTertiary)
        }
    }
}

/** Also the Key Metrics Stress tile's caption (#57). */
internal fun sourceLabelRes(source: StressToday.Source): Int = when (source) {
    StressToday.Source.GARMIN -> R.string.stress_summary_source_garmin
    StressToday.Source.HUAWEI -> R.string.stress_summary_source_huawei
    StressToday.Source.NOOP -> R.string.stress_summary_source_noop
}

/** A watch's scale is whole numbers; NOOP's 0-3 proxy prints to one decimal like the Stress screen. */
internal fun formatStressSummaryValue(value: Double, source: StressToday.Source): String = when (source) {
    StressToday.Source.GARMIN, StressToday.Source.HUAWEI -> value.roundToInt().toString()
    StressToday.Source.NOOP -> StressTrace.formatLevel(value)
}

/**
 * The figure the Key Metrics Stress tile and the pinned "Your cards" Stress row both print (#94): the latest
 * level on its own scale, "1.4/3" or "37/100", built from the same two parts the tile prints. Null when the
 * reading has no level. Swift twin: `StressSummaryCard.cardText`.
 */
internal fun stressCardText(reading: StressToday.Reading?): String? {
    val level = StressToday.tileLevel(reading) ?: return null
    val r = reading ?: return null
    return formatStressSummaryValue(level, r.source) + stressScaleSuffix(r.scaleMax)
}

/** The row's 0..1 fill for the same level, on the reading's own scale. Swift twin: `cardFraction`. */
internal fun stressCardFraction(reading: StressToday.Reading?): Double? {
    val level = StressToday.tileLevel(reading) ?: return null
    val r = reading ?: return null
    return (level / r.scaleMax).coerceIn(0.0, 1.0)
}

/** "/100" or "/3": the scale the figure is on. A number, so nothing to translate. */
internal fun stressScaleSuffix(scaleMax: Double): String = "/" + scaleMax.toInt()

internal fun stressTimeLabel(ts: Long): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        .format(Instant.ofEpochSecond(ts).atZone(ZoneId.systemDefault()))
