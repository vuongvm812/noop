package com.noop.ui

import androidx.annotation.StringRes
import com.noop.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

// MARK: - NoopLimitationsScreen — "what NOOP can (and can't) read off each strap"
//
// A More-tab page (Data group): the plain tri-state capability grid of what NOOP reads live off a WHOOP
// 4.0 vs a 5.0/MG, note-free, with a legend in place of per-row prose. Marks mirror the decoder/analytics
// truth (Interpreter / AnalyticsEngine / HistoricalStreams): full = read live; partial = an on-device
// estimate or an experimental / firmware-gated read; none = not off the strap (SpO₂ % is import-only on
// both; blood pressure has no path). iOS twin: NoopLimitationsView. Rendered through the shared
// ScreenScaffold like every other More destination — the bottom bar carries navigation (no close button).

/** Tri-state support for a metric on a given strap — honest, never overstated. */
private enum class LimitState { FULL, PARTIAL, NONE }

/** One row: a metric, and how it reads on a 4.0 vs a 5.0/MG. No note — the legend carries the meaning. */
private data class LimitRow(@StringRes val feature: Int, val whoop4: LimitState, val whoop5: LimitState)

private val LIMIT_ROWS: List<LimitRow> = listOf(
    LimitRow(R.string.l10n_noop_limitations_screen_live_heart_rate_13593958, LimitState.FULL, LimitState.FULL),
    LimitRow(R.string.l10n_noop_limitations_screen_hrv_rmssd_3688097d, LimitState.FULL, LimitState.FULL),
    LimitRow(R.string.l10n_noop_limitations_screen_sleep_staging_1156bd96, LimitState.FULL, LimitState.FULL),
    LimitRow(R.string.l10n_noop_limitations_screen_recovery_strain_6a775411, LimitState.FULL, LimitState.FULL),
    // PARTIAL on BOTH generations: the displayed respiratory rate is always SleepStager.respRateFromRR
    // — an on-device RSA estimate off the R-R stream, which is what PARTIAL means — computed with NO
    // family branch (AnalyticsEngine respRateDaily). The 5.0/MG v18 wire carries no respiratory channel
    // (Whoop5HistoricalDecodeTest pins resp_rate_raw null); the 4.0 v24 layout DOES carry resp_rate_raw,
    // but it is a raw ADC stored unconverted (schema: "resp rate computed server-side") and never shown.
    // Neither is "read live off the strap" (FULL) — also why an over-counted-R-R 4.0 night (#1331) blanks
    // it. Twin of the Swift NoopLimitationsView row.
    LimitRow(R.string.today_driver_respiratory, LimitState.PARTIAL, LimitState.PARTIAL),
    LimitRow(R.string.l10n_noop_limitations_screen_stress_on_device_014795e4, LimitState.FULL, LimitState.FULL),
    LimitRow(R.string.l10n_noop_limitations_screen_workout_detection_d2e39dc3, LimitState.FULL, LimitState.FULL),
    LimitRow(R.string.today_card_skin_temp_subtitle, LimitState.PARTIAL, LimitState.FULL),
    LimitRow(R.string.l10n_health_screen_steps_cdde4f20, LimitState.PARTIAL, LimitState.FULL),
    LimitRow(R.string.l10n_noop_limitations_screen_blood_oxygen_spo_12d334d5, LimitState.NONE, LimitState.NONE),
    LimitRow(R.string.l10n_noop_limitations_screen_ecg_c5be730b, LimitState.NONE, LimitState.PARTIAL),
    LimitRow(R.string.l10n_noop_limitations_screen_blood_pressure_d107bfd2, LimitState.NONE, LimitState.NONE),
)

@Composable
fun NoopLimitationsScreen() {
    ScreenScaffold(
        title = stringResource(R.string.nav_noop_limitations),
        subtitle = uiString(R.string.l10n_noop_limitations_screen_what_each_strap_can_read_d601fac3),
    ) {
        LimitTableCard()
        LegendCard()
    }
}

@Composable
private fun LimitTableCard() {
    NoopCard(padding = 20.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Overline(uiString(R.string.l10n_noop_limitations_screen_what_noop_reads_690e053e))
            // Column header.
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(uiString(R.string.l10n_whoop_model_comparison_screen_feature_ad565d9d), style = NoopType.caption, color = Palette.textTertiary, modifier = Modifier.weight(1f))
                Text("4.0", style = NoopType.caption, color = Palette.textTertiary, textAlign = TextAlign.Center, modifier = Modifier.width(48.dp))
                Text("5.0/MG", style = NoopType.caption, color = Palette.textTertiary, textAlign = TextAlign.Center, modifier = Modifier.width(48.dp))
            }
            LIMIT_ROWS.forEachIndexed { idx, row ->
                if (idx > 0) Hairline()
                Row(
                    modifier = Modifier.fillMaxWidth().semantics {
                        contentDescription = uiString(R.string.l10n_noop_limitations_screen_1_s_whoop_4_0_2_1d67e797, uiString(row.feature), row.whoop4.spoken, row.whoop5.spoken)
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(row.feature), style = NoopType.body, color = Palette.textPrimary, modifier = Modifier.weight(1f))
                    SupportCell(row.whoop4)
                    SupportCell(row.whoop5)
                }
            }
        }
    }
}

@Composable
private fun LegendCard() {
    NoopCard(padding = 20.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Overline(uiString(R.string.l10n_noop_limitations_screen_legend_5846955d))
            LegendRow(LimitState.FULL, uiString(R.string.l10n_noop_limitations_screen_read_live_off_the_strap_f3889f8f))
            LegendRow(LimitState.PARTIAL, uiString(R.string.l10n_noop_limitations_screen_on_device_estimate_or_experimental_firmware_a4ae0d4d))
            LegendRow(LimitState.NONE, uiString(R.string.l10n_noop_limitations_screen_not_from_the_strap_spo_can_6765c507))
        }
    }
}

@Composable
private fun LegendRow(state: LimitState, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SupportCell(state)
        Text(label, style = NoopType.footnote, color = Palette.textSecondary, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SupportCell(state: LimitState) {
    Box(modifier = Modifier.width(48.dp), contentAlignment = Alignment.Center) {
        when (state) {
            LimitState.FULL -> SupportGlyph(Icons.Filled.Check, Palette.statusPositive, uiString(R.string.l10n_noop_limitations_screen_yes_fb360f9c))
            LimitState.PARTIAL -> SupportGlyph(Icons.Filled.Remove, Palette.statusWarning, uiString(R.string.l10n_noop_limitations_screen_partly_446855cd))
            LimitState.NONE -> SupportGlyph(Icons.Filled.Close, Palette.textTertiary, uiString(R.string.l10n_noop_limitations_screen_no_fd128635))
        }
    }
}

@Composable
private fun SupportGlyph(icon: ImageVector, tint: Color, label: String) {
    Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(18.dp))
}

@Composable
private fun Hairline() {
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Palette.hairline))
}

/** Spoken support label for the row's accessibility description. */
private val LimitState.spoken: String
    get() = when (this) {
        LimitState.FULL -> uiString(R.string.l10n_noop_limitations_screen_yes_fb360f9c)
        LimitState.PARTIAL -> uiString(R.string.l10n_noop_limitations_screen_partly_446855cd)
        LimitState.NONE -> uiString(R.string.l10n_noop_limitations_screen_no_fd128635)
    }
