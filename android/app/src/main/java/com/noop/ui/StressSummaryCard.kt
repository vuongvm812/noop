package com.noop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
 * stress, or NOOP's 0-3 proxy). The SAME composable, fed by the SAME [StressTodayLoader] →
 * [StressToday.resolve], renders on Today and at the top of the Stress screen, so the two readouts cannot
 * disagree. On Today [onOpen] opens the Stress screen; the Stress screen passes null.
 * Twin of macOS `StressSummaryCard` (Strand/Screens/StressSummaryCard.swift).
 *
 * [refreshKey] re-reads when Today's data refreshes (a resume or sync moves it).
 */
@Composable
fun StressSummaryCard(
    viewModel: AppViewModel,
    refreshKey: Any?,
    onOpen: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var reading by remember { mutableStateOf<StressToday.Reading?>(null) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(refreshKey, viewModel.activeStrapId) {
        reading = runCatching {
            StressTodayLoader.load(
                viewModel.repo,
                viewModel.activeStrapId,
                personalBaseline = NoopPrefs.stressPersonalBaseline(context),
            )
        }.getOrNull()
        loaded = true
    }

    val tapModifier = if (onOpen != null) Modifier.clickable { onOpen() } else Modifier
    NoopCard(tint = Palette.stressColor, modifier = tapModifier) {
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
                if (onOpen != null) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = Palette.textTertiary,
                        modifier = Modifier.size(18.dp),
                    )
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
                        Sparkline(values = r.points.map { it.level }, color = Palette.stressColor)
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

private fun sourceLabelRes(source: StressToday.Source): Int = when (source) {
    StressToday.Source.GARMIN -> R.string.stress_summary_source_garmin
    StressToday.Source.NOOP -> R.string.stress_summary_source_noop
}

/** Garmin's scale is whole numbers; NOOP's 0-3 proxy prints to one decimal like the Stress screen. */
internal fun formatStressSummaryValue(value: Double, source: StressToday.Source): String = when (source) {
    StressToday.Source.GARMIN -> value.roundToInt().toString()
    StressToday.Source.NOOP -> StressTrace.formatLevel(value)
}

/** "/100" or "/3": the scale the figure is on. A number, so nothing to translate. */
internal fun stressScaleSuffix(scaleMax: Double): String = "/" + scaleMax.toInt()

private fun stressTimeLabel(ts: Long): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        .format(Instant.ofEpochSecond(ts).atZone(ZoneId.systemDefault()))
