package com.noop.ui

import androidx.annotation.StringRes
import com.noop.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.noop.data.AppleDaily
import com.noop.data.MetricSeriesRow
import java.util.Locale
import kotlin.math.roundToInt

// MARK: - Apple Health (per-source page)
//
// Faithful port of the macOS AppleHealthView. The macOS screen is a Vitaltrends-style,
// instrument-grade page driven by ONE range control (W / M / 3M / 6M / 1Y / ALL): a
// uniform grid of fixed-height StatTiles over every metric, then four ChartCard
// sections — Heart & Vitals, Activity & Energy, Body Composition, Sleep — each with an
// avg / min / max / points footer.
//
// All history is loaded once; the pill control windows it client-side, RELATIVE TO THE
// LATEST recorded day (not "now"). Per the data contract a series may be SPARSE
// (weight / body-fat are weekly): if the selected window holds >= 1 point we show THAT
// window; only when it holds ZERO do we auto-widen to the smallest larger range that
// does — so switching ranges stays visibly distinct and only sparse windows widen.
//
// Data sources on Android:
//   - The appleDaily table (steps, active_kcal via activeKcal, vo2max, weight) under
//     deviceId "apple-health".
//   - The generic metricSeries long-format table for everything Apple writes that isn't
//     a column on appleDaily (resting_hr, hrv, spo2, resp_rate, asleep_min, body_fat,
//     lean_mass, bmi), also under deviceId "apple-health".
//
// The Apple Health *import* runs on-device now: pick an Apple Health export .zip in
// Data Sources and it backfills these tables. This page just reads the resulting cache.
// When nothing has been imported we say so (no faked data, no generic "coming soon").

private const val APPLE_DEVICE = "apple-health"

// MARK: - Range control (W / M / 3M / 6M / 1Y / ALL) — the ONE pill control.

private enum class AppleRange(
    val days: Int?,
    @StringRes val labelRes: Int,
    @StringRes val captionRes: Int,
    @StringRes val windowNameRes: Int,
) {
    Week(7, R.string.l10n_apple_health_screen_w_e2415cb7, R.string.l10n_apple_health_screen_7_days_ba6e98ec, R.string.l10n_apple_health_screen_week_c0ee32d8),
    Month(30, R.string.l10n_apple_health_screen_m_c63ae6dd, R.string.l10n_apple_health_screen_30_days_f1f09899, R.string.l10n_apple_health_screen_month_021710fa),
    Quarter(90, R.string.l10n_apple_health_screen_3m_6dce1630, R.string.l10n_apple_health_screen_90_days_52ad3549, R.string.l10n_apple_health_screen_3_months_b9e4e6ff),
    Half(180, R.string.l10n_apple_health_screen_6m_b78ebb44, R.string.l10n_apple_health_screen_180_days_c0af30e9, R.string.l10n_apple_health_screen_6_months_cd75b86f),
    Year(365, R.string.l10n_apple_health_screen_1y_28234cf2, R.string.l10n_apple_health_screen_365_days_acb50f1b, R.string.l10n_apple_health_screen_year_4ff0b153),
    All(null, R.string.l10n_apple_health_screen_all_6b42874e, R.string.l10n_apple_health_screen_all_time_85c78b60, R.string.l10n_apple_health_screen_all_history_eb5df7d2);

    val label: String get() = uiString(labelRes)
    val caption: String get() = uiString(captionRes)
    val windowName: String get() = uiString(windowNameRes)

    /** This range plus every larger range, ascending — the auto-widen search order. */
    val widening: List<AppleRange>
        get() = entries.dropWhile { it != this }
}

// MARK: - A loaded series point (day string + value), oldest first.

private data class HealthPoint(val day: String, val value: Double)

/**
 * Slice the (ascending-by-day) series to a window taken RELATIVE TO THE LATEST point.
 * The daily/metricSeries caches hold one row per day, so the trailing N rows are the
 * trailing-N-day window — matching the macOS day-distance windowing closely enough.
 */
private fun List<HealthPoint>.windowFor(range: AppleRange): List<HealthPoint> {
    val n = range.days ?: return this
    if (isEmpty()) return emptyList()
    return takeLast(n)
}

// MARK: - Loaded model: every series this page pulls, keyed by metric.

private class AppleData(
    val rows: List<AppleDaily>,
    val series: Map<String, List<HealthPoint>>,
) {
    fun raw(key: String): List<HealthPoint> = series[key] ?: emptyList()

    /** True if ANY per-day row or any series holds data (drives the empty state). */
    val hasAnyData: Boolean
        get() = rows.isNotEmpty() || series.values.any { it.isNotEmpty() }
}

/** A key's resolved (possibly auto-widened) window in the active range. */
private data class Resolved(
    val requested: AppleRange,
    val effective: AppleRange,
    val rows: List<HealthPoint>,
) {
    val fellBack: Boolean get() = effective != requested
}

/**
 * The range actually shown for a key: the SELECTED range whenever its window holds
 * >= 1 point, otherwise the smallest LARGER range that does. Empty series resolve to
 * the requested range (so the card reads "0 readings · <range>", not a false widen).
 */
private fun resolve(series: List<HealthPoint>, requested: AppleRange): Resolved {
    if (series.isEmpty()) return Resolved(requested, requested, emptyList())
    val eff = requested.widening.firstOrNull { series.windowFor(it).isNotEmpty() } ?: AppleRange.All
    return Resolved(requested, eff, series.windowFor(eff))
}

// MARK: - Screen

@Composable
fun AppleHealthScreen(vm: AppViewModel) {
    var loaded by remember { mutableStateOf(false) }
    var data by remember { mutableStateOf(AppleData(emptyList(), emptyMap())) }
    var range by remember { mutableStateOf(AppleRange.Quarter) }

    // Load ALL history once (range windowing is client-side). The appleDaily table
    // supplies steps / active_kcal / vo2max / weight directly; everything else comes
    // from the generic metricSeries long-format table under the apple-health device.
    LaunchedEffect(Unit) {
        val rows: List<AppleDaily> = runCatching {
            vm.repo.appleDaily(APPLE_DEVICE, "0000-00-00", "9999-99-99")
        }.getOrDefault(emptyList()).sortedBy { it.day }

        // Series we draw straight off appleDaily columns (no metricSeries round-trip).
        val series = linkedMapOf<String, List<HealthPoint>>()
        series["steps"] = rows.mapNotNull { r -> r.steps?.let { HealthPoint(r.day, it.toDouble()) } }
        series["active_kcal"] = rows.mapNotNull { r -> r.activeKcal?.let { HealthPoint(r.day, it) } }
        series["vo2max"] = rows.mapNotNull { r -> r.vo2max?.let { HealthPoint(r.day, it) } }
        series["weight"] = rows.mapNotNull { r -> r.weightKg?.let { HealthPoint(r.day, it) } }

        // Everything Apple writes that isn't an appleDaily column lives in metricSeries.
        val seriesKeys = listOf(
            "resting_hr", "hrv", "spo2", "resp_rate", "asleep_min",
            "body_fat", "lean_mass", "bmi",
        )
        for (key in seriesKeys) {
            val pts = runCatching {
                vm.repo.metricSeries(APPLE_DEVICE, key, "0000-00-00", "9999-99-99")
            }.getOrDefault(emptyList<MetricSeriesRow>())
                .sortedBy { it.day }
                .map { HealthPoint(it.day, it.value) }
            series[key] = pts
        }

        data = AppleData(rows, series)
        loaded = true
    }

    val subtitle = spanSubtitle(loaded, data, range)

    // PERF (#707): lazy scaffold — in the populated `else` branch each chart section is its own `item { }`,
    // so only on-screen sections compose + are accessibility-walked on scroll (this data view is the long,
    // chart-heavy one). The loading/empty branches stay single items. Order + spacing are unchanged
    // (LazyColumn reproduces the eager `spacedBy(20.dp)` between the six sections).
    LazyScreenScaffold(title = uiString(R.string.l10n_apple_health_screen_apple_health_b19b87da), subtitle = subtitle) {
        when {
            !loaded -> item { LoadingCard() }
            !data.hasAnyData -> item { EmptyState() }
            else -> {
                item { RangeControl(data = data, range = range, onSelect = { range = it }) }
                item { TileGrid(data = data, range = range) }
                item { HeartSection(data = data, range = range) }
                item { ActivitySection(data = data, range = range) }
                item { BodySection(data = data, range = range) }
                item { SleepSection(data = data, range = range) }
            }
        }
    }
}

// MARK: - Header span + range control

/** Header subtitle reflects the windowed (visible) per-day span of the steps series. */
private fun spanSubtitle(loaded: Boolean, data: AppleData, range: AppleRange): String {
    if (!loaded) {
        return uiString(R.string.l10n_apple_health_screen_steps_heart_sleep_body_composition_and_14ca8b09)
    }
    // Use steps as the canonical per-day series for the span readout.
    val rows = resolve(data.raw("steps"), range).rows
    if (rows.isEmpty()) {
        return uiString(R.string.l10n_apple_health_screen_steps_heart_sleep_body_composition_and_14ca8b09)
    }
    val lo = rows.first().day
    val hi = rows.last().day
    val span = if (lo == hi) lo else "$lo → $hi"
    return uiPlural(R.plurals.l10n_apple_health_screen_1_d_days_2_s_afb9feba, rows.size, rows.size, span)
}

@Composable
private fun RangeControl(data: AppleData, range: AppleRange, onSelect: (AppleRange) -> Unit) {
    // Count visible days off the canonical steps series, and flag if any tracked series
    // had to auto-widen because its selected window was empty.
    val stepsRows = resolve(data.raw("steps"), range).rows
    val anyWidened = data.series.any { (_, s) -> s.isNotEmpty() && resolve(s, range).fellBack }
    val n = stepsRows.size
    val caption = if (anyWidened) uiPlural(R.plurals.l10n_apple_health_screen_1_d_days_2_s_some_4525c5c8, n, n, range.windowName) else uiPlural(R.plurals.l10n_apple_health_screen_1_d_days_2_s_afb9feba, n, n, range.windowName)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SegmentedPillControl(
                items = AppleRange.entries.toList(),
                selection = range,
                label = { it.label },
                onSelect = onSelect,
            )
            Spacer(Modifier.weight(1f))
            Overline(range.caption, color = Palette.textTertiary)
        }
        Text(
            caption,
            style = NoopType.footnote,
            color = if (anyWidened) Palette.statusWarning else Palette.textTertiary,
        )
    }
}

// MARK: - Loading + empty states

@Composable
private fun LoadingCard() {
    NoopCard(tint = Palette.metricCyan) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ConnectionDot(tone = StrandTone.Accent, pulsing = true)
            Text(
                uiString(R.string.l10n_apple_health_screen_reading_your_apple_health_history_1a3bf76d),
                style = NoopType.subhead,
                color = Palette.textSecondary,
            )
        }
    }
}

@Composable
private fun EmptyState() {
    DataPendingNote(
        title = uiString(R.string.l10n_apple_health_screen_nothing_imported_yet_f457cdbe),
        body = uiString(R.string.l10n_apple_health_screen_nothing_imported_yet_on_an_iphone_3bb3b8dc),
    )
}

// MARK: - Metric tiles (uniform fixed-height StatTiles, two per row)

private enum class Aggregate { Latest, Mean }

@Composable
private fun TileGrid(data: AppleData, range: AppleRange) {
    // Imperial/Metric display preference (D#103). Weight + lean mass (stored kg) re-label to lb; every
    // other Apple Health metric is unit-agnostic. Display-only.
    val unitSystem = UnitPrefs.system(LocalContext.current)
    // Two columns of equal-width fixed-height tiles, mirroring the macOS adaptive grid.
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        TileRow {
            MetricTile(Modifier.weight(1f), data, range, "steps", uiString(R.string.l10n_apple_health_screen_steps_cdde4f20), Palette.metricCyan) { intString(it) }
            MetricTile(Modifier.weight(1f), data, range, "resting_hr", uiString(R.string.l10n_apple_health_screen_resting_hr_26677094), Palette.metricRose, "bpm") {
                "${it.roundToInt()}"
            }
        }
        TileRow {
            MetricTile(Modifier.weight(1f), data, range, "hrv", "HRV", Palette.metricPurple, "ms") { "${it.roundToInt()}" }
            MetricTile(Modifier.weight(1f), data, range, "vo2max", uiString(R.string.l10n_apple_health_screen_vo_max_21214fb6), Palette.accent, "ml/kg") {
                String.format(Locale.US, "%.1f", it)
            }
        }
        TileRow {
            MetricTile(Modifier.weight(1f), data, range, "weight", uiString(R.string.l10n_apple_health_screen_weight_69c0b815), Palette.accent) {
                UnitFormatter.massFromKilograms(it, unitSystem)
            }
            MetricTile(Modifier.weight(1f), data, range, "body_fat", uiString(R.string.l10n_apple_health_screen_body_fat_13aeec7b), Palette.metricAmber, "%") {
                String.format(Locale.US, "%.1f", it)
            }
        }
        TileRow {
            MetricTile(Modifier.weight(1f), data, range, "lean_mass", uiString(R.string.l10n_apple_health_screen_lean_mass_a7494b5d), Palette.accent) {
                UnitFormatter.massFromKilograms(it, unitSystem)
            }
            MetricTile(
                Modifier.weight(1f), data, range, "asleep_min", uiString(R.string.l10n_apple_health_screen_asleep_avg_52d49146), Palette.metricPurple,
                aggregate = Aggregate.Mean,
            ) { durationString(it) }
        }
    }
}

@Composable
private fun TileRow(content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) { content() }
}

/**
 * One StatTile for a metric. Sparse-safe: the window auto-widens to the smallest larger
 * range that holds data; the hero is the LATEST point ("as of <day>") unless a mean is
 * requested. Empty series render a dashed tile in tertiary ink rather than disappearing.
 */
@Composable
private fun MetricTile(
    modifier: Modifier,
    data: AppleData,
    range: AppleRange,
    key: String,
    label: String,
    accent: Color,
    unit: String = "",
    aggregate: Aggregate = Aggregate.Latest,
    fmt: (Double) -> String,
) {
    val rows = resolve(data.raw(key), range).rows
    val values = rows.map { it.value }
    val empty = values.isEmpty()

    val value: String
    val caption: String?
    when {
        empty -> {
            value = "—"
            caption = null
        }
        aggregate == Aggregate.Latest -> {
            val v = values.last()
            value = withUnit(fmt(v), unit)
            caption = rows.lastOrNull()?.let { uiString(R.string.l10n_apple_health_screen_as_of_1_s_a438333e, it.day) }
        }
        else -> {
            val m = values.average()
            value = withUnit(fmt(m), unit)
            caption = uiString(R.string.l10n_apple_health_screen_avg_1_dd_0dafaf2c, values.size)
        }
    }

    StatTile(
        modifier = modifier,
        label = label,
        value = value,
        caption = caption,
        accent = if (empty) Palette.textTertiary else accent,
    )
}

// MARK: - Chart sections (uniform ChartCard, same height per card)

@Composable
private fun HeartSection(data: AppleData, range: AppleRange) {
    ChartSection(uiString(R.string.l10n_apple_health_screen_heart_vitals_b789ad36), uiString(R.string.l10n_apple_health_screen_cardiac_a0148244), range) {
        MetricChartCard(data, range, "resting_hr", uiString(R.string.l10n_apple_health_screen_resting_heart_rate_348928d6), Palette.metricRose) {
            "${it.roundToInt()} bpm"
        }
        MetricChartCard(data, range, "hrv", uiString(R.string.l10n_apple_health_screen_heart_rate_variability_5b290995), Palette.metricPurple) {
            "${it.roundToInt()} ms"
        }
        MetricChartCard(data, range, "spo2", uiString(R.string.l10n_apple_health_screen_blood_oxygen_8cd84528), Palette.metricCyan) {
            String.format(Locale.US, "%.1f%%", it)
        }
        MetricChartCard(data, range, "resp_rate", uiString(R.string.l10n_apple_health_screen_respiratory_rate_da7b89aa), Palette.accent) {
            String.format(Locale.US, "%.1f rpm", it)
        }
    }
}

@Composable
private fun ActivitySection(data: AppleData, range: AppleRange) {
    ChartSection(uiString(R.string.l10n_apple_health_screen_activity_energy_1c075b75), uiString(R.string.l10n_apple_health_screen_movement_dc812752), range) {
        MetricChartCard(data, range, "steps", uiString(R.string.l10n_apple_health_screen_steps_cdde4f20), Palette.metricCyan) { intString(it) }
        MetricChartCard(data, range, "active_kcal", uiString(R.string.l10n_apple_health_screen_active_energy_63a05d3d), Palette.metricAmber) {
            "${intString(it)} kcal"
        }
    }
}

@Composable
private fun BodySection(data: AppleData, range: AppleRange) {
    // Weight + lean mass (stored kg) re-label to lb under the imperial preference.
    val unitSystem = UnitPrefs.system(LocalContext.current)
    ChartSection(uiString(R.string.l10n_apple_health_screen_body_composition_82c09fee), uiString(R.string.l10n_apple_health_screen_slow_threads_388dd4b2), range) {
        MetricChartCard(data, range, "weight", uiString(R.string.l10n_apple_health_screen_weight_69c0b815), Palette.accent) {
            UnitFormatter.massFromKilograms(it, unitSystem)
        }
        MetricChartCard(data, range, "body_fat", uiString(R.string.l10n_apple_health_screen_body_fat_8ce14357), Palette.metricAmber) {
            String.format(Locale.US, "%.1f%%", it)
        }
        MetricChartCard(data, range, "lean_mass", uiString(R.string.l10n_apple_health_screen_lean_body_mass_df160660), Palette.accent) {
            UnitFormatter.massFromKilograms(it, unitSystem)
        }
        MetricChartCard(data, range, "bmi", uiString(R.string.l10n_apple_health_screen_bmi_aa26664f), Palette.metricPurple) {
            String.format(Locale.US, "%.1f", it)
        }
    }
}

@Composable
private fun SleepSection(data: AppleData, range: AppleRange) {
    ChartSection(uiString(R.string.l10n_apple_health_screen_sleep_3cac34e6), uiString(R.string.l10n_apple_health_screen_rest_b79e5f48), range) {
        MetricChartCard(data, range, "asleep_min", uiString(R.string.l10n_apple_health_screen_asleep_b9692bbe), Palette.metricPurple) { durationString(it) }
    }
}

@Composable
private fun ChartSection(
    title: String,
    overline: String,
    range: AppleRange,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        SectionHeader(title, overline = overline, trailing = range.caption)
        content()
    }
}

/**
 * One uniform ChartCard for a metric series: header (title + sparse-aware subtitle +
 * trailing mean), a fixed-height LineChart body, and an avg / min / max / points footer.
 * Sparse-safe via [resolve]: a single point shows a lone read-out instead of a flat line,
 * and an empty window shows "no readings".
 */
@Composable
private fun MetricChartCard(
    data: AppleData,
    range: AppleRange,
    key: String,
    title: String,
    accent: Color,
    fmt: (Double) -> String,
) {
    val resolved = resolve(data.raw(key), range)
    val rows = resolved.rows
    val values = rows.map { it.value }
    val n = values.size
    val mean = if (n > 0) values.average() else null
    val trailing = mean?.let { fmt(it) } ?: "—"

    val subtitle = run {
        if (resolved.fellBack) uiPlural(R.plurals.l10n_apple_health_screen_1_d_readings_sparse_widened_to_2ef06550, n, n, resolved.effective.windowName)
        else uiPlural(R.plurals.l10n_apple_health_screen_1_d_readings_2_s_46d67880, n, n, range.windowName)
    }

    NoopCard(tint = accent) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Overline(title)
                    Text(
                        subtitle,
                        style = NoopType.footnote,
                        color = if (resolved.fellBack) Palette.statusWarning else Palette.textTertiary,
                    )
                }
                Text(trailing, style = NoopType.number(18f), color = if (n > 0) accent else Palette.textTertiary)
            }

            when {
                n >= 2 -> LineChart(
                    values = values,
                    modifier = Modifier.height(Metrics.chartHeight),
                    color = accent,
                    fill = true,
                )
                n == 1 -> SinglePoint(values.first(), accent, fmt)
                else -> EmptyChart()
            }

            ChartFooterRow(
                items = if (n > 0) {
                    listOf(
                        uiString(R.string.l10n_apple_health_screen_avg_cdc93143) to fmt(values.average()),
                        uiString(R.string.l10n_apple_health_screen_min_7eb0cee8) to fmt(values.min()),
                        uiString(R.string.l10n_apple_health_screen_max_a95e85ae) to fmt(values.max()),
                        uiString(R.string.l10n_apple_health_screen_points_4b2a6a31) to "$n",
                    )
                } else {
                    listOf(uiString(R.string.l10n_apple_health_screen_avg_cdc93143) to "—", uiString(R.string.l10n_apple_health_screen_min_7eb0cee8) to "—", uiString(R.string.l10n_apple_health_screen_max_a95e85ae) to "—", uiString(R.string.l10n_apple_health_screen_points_4b2a6a31) to "0")
                },
            )
        }
    }
}

/** Lone-reading body for a series with exactly one point in range. */
@Composable
private fun SinglePoint(value: Double, accent: Color, fmt: (Double) -> String) {
    Box(
        modifier = Modifier.fillMaxWidth().height(Metrics.chartHeight),
        contentAlignment = Alignment.CenterStart,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Overline(uiString(R.string.l10n_apple_health_screen_latest_reading_98b87f72))
            Text(fmt(value), style = NoopType.number(34f), color = accent)
        }
    }
}

@Composable
private fun EmptyChart() {
    Box(
        modifier = Modifier.fillMaxWidth().height(Metrics.chartHeight),
        contentAlignment = Alignment.Center,
    ) {
        Text(uiString(R.string.l10n_apple_health_screen_no_readings_recorded_05018b26), style = NoopType.subhead, color = Palette.textTertiary)
    }
}

@Composable
private fun ChartFooterRow(items: List<Pair<String, String>>) {
    Row(horizontalArrangement = Arrangement.spacedBy(Metrics.sectionGap)) {
        items.forEach { (label, value) ->
            Column {
                Overline(label, color = Palette.textTertiary)
                Text(value, style = NoopType.captionNumber, color = Palette.textSecondary)
            }
        }
    }
}

// MARK: - Formatting helpers

private fun withUnit(value: String, unit: String): String = if (unit.isEmpty()) value else "$value $unit"

private fun intString(v: Double): String {
    val n = v.roundToInt()
    return if (kotlin.math.abs(n) >= 1000) String.format(Locale.US, "%,d", n) else "$n"
}

private fun durationString(minutes: Double): String {
    val total = minutes.roundToInt()
    val h = total / 60
    val m = total % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}
