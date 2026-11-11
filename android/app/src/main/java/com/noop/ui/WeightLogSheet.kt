package com.noop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.analytics.WeightLog
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * The weight diary sheet (#57), opened from the Today Weight tile.
 *
 * Log one weight per day for any past or present day, edit or delete it, and read the daily weight chart:
 * tap or drag it to read a day's value (the shared [LineChart] selection readout). The chart draws
 * [WeightLog.dailySeries], the logged days plus any health-store weigh-ins, so it agrees with the tile.
 * Twin of the Apple `WeightLogSheet` (Strand/Screens/WeightLogSheet.swift).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WeightLogSheet(viewModel: AppViewModel, unitSystem: UnitSystem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val imperial = unitSystem == UnitSystem.IMPERIAL
    val today = remember { LocalDate.now() }

    var logged by remember { mutableStateOf<List<WeightLog.Entry>>(emptyList()) }
    var health by remember { mutableStateOf<List<WeightLog.Entry>>(emptyList()) }
    var day by remember { mutableStateOf(today) }
    var text by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    var bodyComposition by remember { mutableStateOf<WeightLog.BodyComposition?>(null) }
    val seq by WeightLogStore.mutationSeq.collectAsStateWithLifecycle()

    val healthConnectOnly = remember { ProfileStore.from(context).useHealthConnectWeight }
    LaunchedEffect(Unit) {
        health = runCatching {
            healthWeightEntries(
                viewModel.repo.appleDaily("apple-health", "0000-01-01", "9999-12-31"),
                viewModel.repo.appleDaily("health-connect", "0000-01-01", "9999-12-31"),
                healthConnectOnly = healthConnectOnly,
            )
        }.getOrDefault(emptyList())
    }
    LaunchedEffect(Unit) {
        bodyComposition = runCatching { viewModel.repo.latestBodyComposition() }.getOrNull()
    }
    LaunchedEffect(seq) {
        logged = runCatching { WeightLogStore.entries(viewModel.repo) }.getOrDefault(emptyList())
    }
    val dayKey = day.toString()
    val loggedForDay = logged.firstOrNull { it.day == dayKey }
    // Prefill the field with the selected day's logged weight whenever the day or the diary changes.
    LaunchedEffect(dayKey, loggedForDay) {
        invalid = false
        text = loggedForDay?.let { String.format(Locale.getDefault(), "%.1f", displayValue(it.kg, imperial)) } ?: ""
    }

    fun save() {
        val kg = WeightLog.parseKg(text, imperial)
        if (kg == null) {
            invalid = true
            return
        }
        invalid = false
        scope.launch { WeightLogStore.set(context, viewModel.repo, dayKey, kg) }
    }

    fun delete(key: String) {
        scope.launch { WeightLogStore.delete(context, viewModel.repo, key) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Palette.surfaceOverlay,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(uiString(R.string.today_metric_weight), style = NoopType.title2, color = Palette.textPrimary)
            Text(uiString(R.string.weight_log_profile_note), style = NoopType.footnote, color = Palette.textSecondary)

            // The merged daily series feeds both the chart and the Weight box, so they agree.
            val series = remember(logged, health) { WeightLog.dailySeries(logged, health) }

            // Newest weight / body fat / lean mass, each with its reading's date; "-" when a value is missing.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                val latestWeight = series.lastOrNull()
                CompositionBox(
                    label = uiString(R.string.today_metric_weight),
                    value = latestWeight?.let { formatMass(displayValue(it.kg, imperial), imperial) },
                    day = latestWeight?.day,
                    modifier = Modifier.weight(1f),
                )
                CompositionBox(
                    label = uiString(R.string.l10n_apple_health_screen_body_fat_13aeec7b),
                    value = bodyComposition?.bodyFatPct?.let { String.format(Locale.getDefault(), "%.1f", it.value) + " %" },
                    day = bodyComposition?.bodyFatPct?.day,
                    modifier = Modifier.weight(1f),
                )
                CompositionBox(
                    label = uiString(R.string.l10n_apple_health_screen_lean_mass_a7494b5d),
                    value = bodyComposition?.leanMassKg?.let { formatMass(displayValue(it.value, imperial), imperial) },
                    day = bodyComposition?.leanMassKg?.day,
                    modifier = Modifier.weight(1f),
                )
            }

            // Chart: the merged daily series, tap/drag to read a day's value.
            NoopCard(tint = Palette.accent) {
                if (series.size >= 2) {
                    LineChart(
                        values = series.map { displayValue(it.kg, imperial) },
                        modifier = Modifier.fillMaxWidth().height(Metrics.chartHeight),
                        color = Palette.accent,
                        selectionEnabled = true,
                        selectionLabels = series.map { shortDay(it.day) },
                        formatValue = { formatMass(it, imperial) },
                    )
                } else {
                    Text(uiString(R.string.weight_log_chart_empty), style = NoopType.subhead, color = Palette.textTertiary)
                }
            }

            // Entry: a day stepper (never past today), the weight field, Save and Delete.
            NoopCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { day = day.minusDays(1) }) {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                contentDescription = uiString(R.string.weight_log_previous_day),
                                tint = Palette.accent,
                            )
                        }
                        Text(
                            longDay(day),
                            style = NoopType.headline,
                            color = Palette.textPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { if (day < today) day = day.plusDays(1) }, enabled = day < today) {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = uiString(R.string.weight_log_next_day),
                                tint = if (day < today) Palette.accent else Palette.textTertiary,
                            )
                        }
                    }
                    OutlinedTextField(
                        value = text,
                        onValueChange = { new -> text = new.filter { it.isDigit() || it == '.' || it == ',' }.take(6) },
                        label = {
                            Text(
                                uiString(if (imperial) R.string.weight_log_field_lb else R.string.weight_log_field_kg),
                                style = NoopType.footnote,
                            )
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Palette.textPrimary,
                            unfocusedTextColor = Palette.textPrimary,
                            cursorColor = Palette.accent,
                            focusedBorderColor = Palette.accent,
                            unfocusedBorderColor = Palette.hairline,
                            focusedLabelColor = Palette.accent,
                            unfocusedLabelColor = Palette.textSecondary,
                            focusedContainerColor = Palette.surfaceInset,
                            unfocusedContainerColor = Palette.surfaceInset,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (invalid) {
                        Text(
                            uiString(
                                R.string.weight_log_invalid,
                                formatMass(displayValue(WeightLog.MIN_KG, imperial), imperial),
                                formatMass(displayValue(WeightLog.MAX_KG, imperial), imperial),
                            ),
                            style = NoopType.footnote,
                            color = Palette.statusWarning,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = { save() },
                            colors = ButtonDefaults.textButtonColors(contentColor = Palette.accent),
                        ) { Text(uiString(R.string.weight_log_save), style = NoopType.headline) }
                        if (loggedForDay != null) {
                            TextButton(
                                onClick = { delete(dayKey) },
                                colors = ButtonDefaults.textButtonColors(contentColor = Palette.statusWarning),
                            ) { Text(uiString(R.string.weight_log_delete), style = NoopType.headline) }
                        }
                    }
                }
            }

            // History: every logged day, newest first. Tap a row to edit that day; the bin deletes it.
            NoopCard {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Overline(uiString(R.string.weight_log_history))
                    if (logged.isEmpty()) {
                        Text(uiString(R.string.weight_log_empty), style = NoopType.subhead, color = Palette.textTertiary)
                    } else {
                        logged.asReversed().forEach { entry ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { runCatching { LocalDate.parse(entry.day) }.getOrNull()?.let { day = it } }
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        longDayKey(entry.day),
                                        style = NoopType.subhead,
                                        color = Palette.textSecondary,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        formatMass(displayValue(entry.kg, imperial), imperial),
                                        style = NoopType.number(15f),
                                        color = Palette.textPrimary,
                                    )
                                }
                                Spacer(Modifier.width(4.dp))
                                IconButton(onClick = { delete(entry.day) }) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = uiString(R.string.weight_log_delete),
                                        tint = Palette.textTertiary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.textButtonColors(contentColor = Palette.accent),
                ) { Text(uiString(R.string.weight_log_done), style = NoopType.headline) }
            }
        }
    }
}

/** One Weight / Body fat / Lean mass box: the value ("-" when [value] is null) and the reading's date. */
@Composable
private fun CompositionBox(label: String, value: String?, day: String?, modifier: Modifier = Modifier) {
    NoopCard(modifier = modifier, padding = 12.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Overline(label)
            Text(value ?: "-", style = NoopType.number(18f), color = Palette.textPrimary)
            Text(
                day?.let { shortDay(it) } ?: " ",
                style = NoopType.footnote,
                color = Palette.textTertiary,
            )
        }
    }
}

private fun displayValue(kg: Double, imperial: Boolean): Double = if (imperial) UnitFormatter.kgToPounds(kg) else kg

private fun formatMass(value: Double, imperial: Boolean): String =
    String.format(Locale.getDefault(), "%.1f", value) + if (imperial) " lb" else " kg"

private fun shortDay(key: String): String =
    runCatching { LocalDate.parse(key).format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())) }
        .getOrDefault(key)

private fun longDay(date: LocalDate): String =
    date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault()))

private fun longDayKey(key: String): String =
    runCatching { longDay(LocalDate.parse(key)) }.getOrDefault(key)
