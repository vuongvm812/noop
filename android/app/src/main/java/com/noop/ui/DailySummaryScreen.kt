package com.noop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.noop.NoopApplication
import com.noop.R
import com.noop.analytics.MetricSnapshot
import com.noop.analytics.MetricSnapshotRow
import com.noop.analytics.SkinTempDisplay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// DailySummaryScreen.kt — every metric NOOP stores for one day, by category ("-" when missing).
// Reads through [MetricSnapshotLoader]; the day picker browses past days. Opened from More > Insights and by the
// morning recap / AI brief notification taps. Swift twin: `DailySummaryView`.

/**
 * A snapshot row's figure on the user's display units: kg and °C follow the unit settings, Effort follows its
 * 0–100 / 0–21 scale, everything else is the shared [MetricSnapshotRow.text]. "-" when missing.
 * Swift twin: `DailySummaryText.value`.
 */
internal fun snapshotDisplayText(
    row: MetricSnapshotRow,
    system: UnitSystem,
    temperature: TemperatureUnit,
    effortScale: EffortScale,
): String {
    val v = row.value ?: return "-"
    if (!v.isFinite()) return "-"
    val item = row.item
    return when {
        item.unit == "kg" -> UnitFormatter.massFromKilograms(v, system)
        item.unit == "°C" && item.id == "skin_temp" ->
            SkinTempDisplay.format(v, fahrenheit = temperature == TemperatureUnit.FAHRENHEIT, decimals = item.decimals)
        item.unit == "°C" -> UnitFormatter.temperatureFromCelsius(v, temperature, item.decimals)
        item.id == "strain" || item.id == "workout_effort" ->
            "${UnitFormatter.effortDisplay(v, effortScale)} /${UnitFormatter.effortScaleMax(effortScale)}"
        else -> row.text
    }
}

/** [day] shifted by [delta] days, never past [today]. Pure, for the day picker. */
internal fun shiftSummaryDay(day: String, delta: Int, today: String): String {
    val shifted = LocalDate.parse(day).plusDays(delta.toLong()).toString()
    return if (shifted > today) today else shifted
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailySummaryScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val app = context.applicationContext as NoopApplication
    val today = logicalDayKeyNow()
    var day by remember { mutableStateOf(today) }
    var showPicker by remember { mutableStateOf(false) }
    // A notification tap names the day to open on; consume it once.
    val pendingDay by NotificationRoute.pendingDay.collectAsState()
    LaunchedEffect(pendingDay) {
        pendingDay?.let {
            day = if (it > today) today else it
            NotificationRoute.pendingDay.value = null
        }
    }
    val system = UnitPrefs.system(context)
    val temperature = UnitPrefs.temperature(context)
    val effortScale = UnitPrefs.effortScale(context)
    val days by vm.recentDays.collectAsState()
    // Reloads when the day changes or a pass rewrites the daily rows.
    val snapshot: MetricSnapshot? by produceState<MetricSnapshot?>(null, day, days) {
        value = safeRead<MetricSnapshot?>(null) {
            MetricSnapshotLoader.snapshot(app.repository, app.deviceRegistry, app.activeDeviceId, day, day)
        }
    }

    ScreenScaffold(
        title = stringResource(R.string.nav_daily_summary),
        subtitle = stringResource(R.string.daily_summary_subtitle),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(onClick = { day = shiftSummaryDay(day, -1, today) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.daily_summary_previous_day), tint = Palette.textPrimary)
            }
            Text(
                summaryDayLabel(day, today),
                style = NoopType.headline,
                color = Palette.textPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .clickable { showPicker = true }
                    .semantics { contentDescription = context.getString(R.string.daily_summary_pick_day) },
            )
            IconButton(onClick = { day = shiftSummaryDay(day, 1, today) }, enabled = day < today) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.daily_summary_next_day),
                    tint = if (day < today) Palette.textPrimary else Palette.textTertiary,
                )
            }
        }
        val snap = snapshot
        if (snap != null && snap.isEmpty) {
            Text(stringResource(R.string.daily_summary_no_data), style = NoopType.subhead, color = Palette.textSecondary)
        }
        if (snap != null) {
            for (section in snap.sections) {
                NoopCard {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Overline(stringResource(section.category.labelRes()))
                        for ((i, row) in section.rows.withIndex()) {
                            if (i > 0) HorizontalDivider(color = Palette.hairline)
                            SummaryLine(
                                stringResource(snapshotItemLabelRes(row.item.id)),
                                snapshotDisplayText(row, system, temperature, effortScale),
                            )
                        }
                        for (note in section.notes) SummaryLine(note.label, note.text)
                        if (section.rows.isEmpty() && section.notes.isEmpty()) SummaryLine("-", "-")
                    }
                }
            }
        }
    }

    if (showPicker) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = LocalDate.parse(day).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        val picked = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()
                        day = if (picked > today) today else picked
                    }
                    showPicker = false
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text(stringResource(android.R.string.cancel)) } },
        ) { DatePicker(state = state) }
    }
}

@Composable
private fun SummaryLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .semantics(mergeDescendants = true) { },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = NoopType.subhead, color = Palette.textSecondary, modifier = Modifier.weight(1f))
        Text(value, style = NoopType.bodyNumber, color = Palette.textPrimary, textAlign = TextAlign.End)
    }
}

/** "Today" for today's day, else the day in the user's locale ("Nov 10, 2026"). */
@Composable
private fun summaryDayLabel(day: String, today: String): String =
    if (day == today) stringResource(R.string.daily_summary_today)
    else LocalDate.parse(day).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
