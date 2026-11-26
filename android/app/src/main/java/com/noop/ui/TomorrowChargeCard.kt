package com.noop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.noop.R
import com.noop.analytics.ForecastAccuracy
import com.noop.analytics.RecoveryForecast
import com.noop.analytics.RecoveryForecaster
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Tomorrow's Charge (Today screen): "Bed by 22:45 → ~68% (60–76)", the same forecast one hour earlier and
 * later, and a bedtime slider (15-minute steps, 21:00–02:00). Shown from the afternoon until that night's
 * sleep starts (the loop-level gate in TodayScreen reads [RecoveryForecaster.Tonight.isOpen]); the same
 * gate decides whether tonight's forecast may still be overwritten in storage. Every number comes from
 * [AppViewModel.tomorrowForecast], the flow the Intelligence card reads too. Once enough nights are scored
 * the footer says how close past forecasts came. Twin of Swift `TomorrowChargeCard`.
 */
@Composable
fun TomorrowChargeCard(vm: AppViewModel, t: RecoveryForecaster.Tonight, accuracy: ForecastAccuracy.Summary?) {
    val context = LocalContext.current
    val is24h = ClockPrefs.uses24Hour(context)
    fun clock(ts: Double): String = clockTimeLabel(ts.toLong(), is24h)

    NoopCard(tint = Palette.chargeColor) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Metrics.space8),
            ) {
                Icon(
                    Icons.Filled.Bedtime,
                    contentDescription = null,
                    tint = Palette.chargeColor,
                    modifier = Modifier.size(Metrics.iconSmall),
                )
                Text(
                    uiString(R.string.l10n_intelligence_screen_tomorrow_s_charge_b98be38b),
                    style = NoopType.headline,
                    color = Palette.textPrimary,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    uiString(R.string.l10n_intelligence_screen_estimate_2352230a),
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                )
            }
            OptionRow(t.headline, primary = true, clock = ::clock)
            // An alternative bedtime already in the past (before the slider's start, which is now once
            // that is past the window's earliest bedtime) is hidden; before then every alternative shows.
            t.options.drop(1)
                .filter { it.bedtimeTs >= t.sliderStartTs || t.sliderStartTs <= t.window.earliestTs }
                .forEach { OptionRow(it, primary = false, clock = ::clock) }
            BedtimeSlider(vm, t, ::clock)
            Text(
                uiString(
                    R.string.tomorrow_charge_suggested,
                    clock(t.suggestedTs),
                    durationLabel(t.plan.needHours),
                    clock(t.plan.wakeTs),
                ),
                style = NoopType.footnote,
                color = Palette.textTertiary,
            )
            if (accuracy != null) {
                Text(
                    uiString(
                        R.string.tomorrow_charge_accuracy,
                        accuracy.scored,
                        accuracy.meanAbsError.roundToInt(),
                        accuracy.inside,
                        accuracy.scored,
                    ),
                    style = NoopType.footnote,
                    color = Palette.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun OptionRow(option: RecoveryForecaster.BedtimeOption, primary: Boolean, clock: (Double) -> String) {
    val f = option.forecast
    val style = if (primary) NoopType.title2 else NoopType.subhead
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Text(
            uiString(R.string.tomorrow_charge_bed_by, clock(option.bedtimeTs)),
            style = style,
            color = if (primary) Palette.textPrimary else Palette.textSecondary,
            modifier = Modifier.weight(1f),
        )
        Text(chargeLabel(f), style = style, color = Palette.recoveryColor(f.charge))
        Spacer(Modifier.size(Metrics.space6))
        Text(
            bandLabel(f),
            style = NoopType.captionNumber,
            color = Palette.textTertiary,
        )
    }
}

@Composable
private fun BedtimeSlider(vm: AppViewModel, t: RecoveryForecaster.Tonight, clock: (Double) -> String) {
    // The slider runs from the lowest bedtime still selectable (now, once that is past the window's
    // earliest) to the latest, the same range the resolver clamps the headline into, so the thumb and the
    // headline cannot disagree. With less than one step left there is nothing to choose: no slider.
    val start = t.sliderStartTs
    val span = t.window.latestTs - start
    if (span < RecoveryForecaster.bedtimeStepSec) return
    val stops = (span / RecoveryForecaster.bedtimeStepSec).roundToInt()
    val label = uiString(R.string.tomorrow_charge_bedtime)
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space2)) {
        Slider(
            value = (t.sliderTs - start).toFloat(),
            onValueChange = { v ->
                val snapped = (v / RecoveryForecaster.bedtimeStepSec).roundToInt() * RecoveryForecaster.bedtimeStepSec
                vm.setTomorrowBedtime(start + snapped)
            },
            valueRange = 0f..span.toFloat(),
            steps = max(stops - 1, 0),
            colors = SliderDefaults.colors(
                thumbColor = Palette.chargeColor,
                activeTrackColor = Palette.chargeColor,
                inactiveTrackColor = Palette.surfaceInset,
            ),
            modifier = Modifier.semantics { contentDescription = label },
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(clock(start), style = NoopType.captionNumber, color = Palette.textTertiary)
            Spacer(Modifier.weight(1f))
            Text(clock(t.window.latestTs), style = NoopType.captionNumber, color = Palette.textTertiary)
        }
    }
}

/** "~68%": the forecast Charge, a number (nothing to translate). Twin of the Swift card's `chargeLabel`. */
internal fun chargeLabel(f: RecoveryForecast): String = "~${f.charge.roundToInt()}%"

/** "(60–76)": the forecast's band. Twin of the Swift card's `bandLabel`. */
internal fun bandLabel(f: RecoveryForecast): String = "(${f.low.roundToInt()}–${f.high.roundToInt()})"

/** "8h" / "8h 15m", rounded to the nearest 5 minutes. Twin of the Swift card's `duration`. */
private fun durationLabel(hours: Double): String {
    val minutes = (hours * 12).roundToInt() * 5
    val h = minutes / 60
    val m = minutes % 60
    return if (m == 0) "${h}h" else "${h}h ${m}m"
}
