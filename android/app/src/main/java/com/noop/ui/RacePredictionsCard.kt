package com.noop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.noop.R
import com.noop.analytics.RacePredictor
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.ceil

// Predicted 5K / 10K / half marathon / marathon times from the user's own runs ([RacePredictor]). One row
// component serves the Workouts card, the compact block in Health › Fitness Age, and the "This run
// predicts" section of a run's detail sheet; the card links to the daily trend. Mirrors the Swift
// `RacePredictionsCard.swift`.

/** "5K" / "10K" / "Half marathon" / "Marathon" for a [RacePredictor.targetDistancesM] index. */
@Composable
private fun raceTargetLabel(index: Int): String = uiString(
    when (index) {
        0 -> R.string.race_target_5k
        1 -> R.string.race_target_10k
        2 -> R.string.race_target_half
        else -> R.string.race_target_marathon
    },
)

/** The hint where no run reaches a target yet. The shortest qualifying run is rounded UP in the display
 *  unit, so a run of exactly the stated distance always unlocks it. Same rule as the Swift `unlockHint`. */
@Composable
private fun raceUnlockHint(index: Int, system: UnitSystem): String {
    val m = RacePredictor.minRunM(index)
    val shown = when (system) {
        UnitSystem.METRIC -> ceil(m / 100.0) * 100.0
        UnitSystem.IMPERIAL -> {
            val miles = ceil(UnitFormatter.kmToMiles(m / 1000.0) * 10.0) / 10.0
            miles / UnitFormatter.MILES_PER_KILOMETER * 1000.0
        }
    }
    return uiString(R.string.race_unlock_hint, UnitFormatter.distanceFromMeters(shown, system))
}

private val raceDateFormat: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault())

/** The run date for a source line, in the reader's locale. */
private fun raceRunDate(ts: Long): String = raceDateFormat.format(Instant.ofEpochSecond(ts))

/** "Based on your runs · heart rate · VO₂max": which estimates joined Riegel in [result]; null when Riegel
 *  stands alone. Same rule as the Swift `RacePredictionCopy.basis`. */
@Composable
private fun raceBasis(result: RacePredictor.Result): String? {
    val hr = result.estimates.any { it?.components?.hrS != null }
    val vo2 = result.estimates.any { it?.components?.vo2S != null }
    return when {
        hr && vo2 -> uiString(R.string.race_basis_hr_vo2)
        hr -> uiString(R.string.race_basis_hr)
        vo2 -> uiString(R.string.race_basis_vo2)
        else -> null
    }
}

/** True when every available prediction comes from the same run, so one source line covers them all. */
private fun sharesOneRun(items: List<RacePredictor.Prediction?>): Boolean {
    val shown = items.filterNotNull()
    val first = shown.firstOrNull() ?: return false
    return shown.all { it.sourceStartTs == first.sourceStartTs && it.sourceDistanceM == first.sourceDistanceM }
}

@Composable
private fun RaceDivider() {
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Palette.hairline))
}

@Composable
private fun RaceNote(text: String) {
    Text(text, style = NoopType.footnote, color = Palette.textTertiary)
}

/** One target: its label (with an optional caption) on the left, the predicted time ([seconds]) and pace
 *  ([secPerKm]) on the right, or the unlock hint when nothing predicts it. */
@Composable
internal fun RacePredictionRow(
    targetIndex: Int,
    seconds: Int?,
    secPerKm: Double,
    system: UnitSystem,
    caption: String? = null,
    highlighted: Boolean = false,
    compact: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Metrics.space2)) {
            Text(
                raceTargetLabel(targetIndex),
                style = if (compact) NoopType.subhead else NoopType.headline,
                color = Palette.textPrimary,
            )
            if (caption != null) {
                Text(
                    caption,
                    style = NoopType.footnote,
                    color = if (highlighted) Palette.statusPositive else Palette.textTertiary,
                )
            }
        }
        Spacer(Modifier.width(Metrics.space8))
        if (seconds != null) {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(Metrics.space2)) {
                Text(
                    RacePredictor.formatTime(seconds),
                    style = NoopType.number(if (compact) 17f else 22f),
                    color = if (highlighted) Palette.statusPositive else Palette.metricCyan,
                )
                Text(
                    UnitFormatter.paceFromSecPerKm(secPerKm, system),
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                )
            }
        } else {
            Text(
                raceUnlockHint(targetIndex, system),
                style = NoopType.footnote,
                color = Palette.textTertiary,
                textAlign = TextAlign.End,
            )
        }
    }
}

/** The four predictions with their source and method notes, and a link to their daily trend. [compact] is
 *  the Health › Fitness Age block: tighter rows, and the method note left to the Workouts card. */
@Composable
internal fun RacePredictionsCard(result: RacePredictor.Result, vm: AppViewModel, compact: Boolean = false) {
    val system = UnitPrefs.distanceSystem(LocalContext.current)
    var showTrend by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        SectionHeader(
            uiString(R.string.garmin_cat_race_predictions),
            overline = uiString(R.string.race_from_your_runs),
        )
        NoopCard(tint = Palette.metricCyan) {
            if (result.estimates.all { it == null }) {
                Text(uiString(R.string.race_empty), style = NoopType.subhead, color = Palette.textTertiary)
            } else {
                val shared = sharesOneRun(result.items)
                val basis = raceBasis(result)
                Column(verticalArrangement = Arrangement.spacedBy(if (compact) Metrics.space8 else Metrics.space12)) {
                    result.estimates.forEachIndexed { index, e ->
                        val riegel = e?.riegel
                        RacePredictionRow(
                            targetIndex = index,
                            seconds = e?.seconds,
                            secPerKm = e?.let { RacePredictor.paceSecPerKm(it.seconds, index) } ?: 0.0,
                            system = system,
                            caption = when {
                                e == null -> null
                                riegel == null -> uiString(R.string.race_estimate_caption)
                                shared -> null
                                else -> uiString(
                                    R.string.race_source_caption,
                                    UnitFormatter.distanceFromMeters(riegel.sourceDistanceM, system),
                                    raceRunDate(riegel.sourceStartTs),
                                )
                            },
                            compact = compact,
                        )
                    }
                    RaceDivider()
                    val first = result.items.firstOrNull { it != null }
                    if (shared && first != null) {
                        RaceNote(
                            uiString(
                                R.string.race_based_on,
                                UnitFormatter.distanceFromMeters(first.sourceDistanceM, system),
                                raceRunDate(first.sourceStartTs),
                            ),
                        )
                    }
                    if (basis != null) RaceNote(basis)
                    if (result.fromAllHistory) RaceNote(uiString(R.string.race_all_history))
                    if (!compact) {
                        RaceNote(uiString(R.string.race_method_note))
                        if (basis != null) RaceNote(uiString(R.string.race_blend_note))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { showTrend = true },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            uiString(R.string.race_trend_link),
                            style = NoopType.subhead,
                            color = Palette.metricCyan,
                            modifier = Modifier.weight(1f),
                        )
                        Text("›", style = NoopType.subhead, color = Palette.textTertiary)
                    }
                }
            }
        }
    }
    if (showTrend) RacePredictionTrendSheet(vm, onDismiss = { showTrend = false })
}

/** The daily history of one target's prediction ([AppViewModel.racePredictionHistory]), picked from 5K /
 *  10K / Half / Marathon. Each point is that day's prediction replayed from the current runs, for each day
 *  of the last year. Mirrors the Swift `RacePredictionTrendSheet`. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RacePredictionTrendSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    var target by remember { mutableIntStateOf(0) }
    var values by remember { mutableStateOf<List<Pair<String, Double>>>(emptyList()) }
    LaunchedEffect(target) { values = vm.racePredictionHistory(target).map { it.day to it.value } }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Palette.surfaceOverlay,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Metrics.gap)
                .padding(bottom = Metrics.gap),
            verticalArrangement = Arrangement.spacedBy(Metrics.space12),
        ) {
            SectionHeader(uiString(R.string.garmin_cat_race_predictions), overline = uiString(R.string.race_trend_overline))
            Row(horizontalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                RacePredictor.targetDistancesM().indices.forEach { i ->
                    JournalChip(label = raceTargetLabel(i), selected = i == target, onClick = { target = i })
                }
            }
            NoopCard(tint = Palette.metricCyan) {
                Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                    Text(uiString(R.string.race_trend_title), style = NoopType.subhead, color = Palette.textPrimary)
                    if (values.size >= 2) {
                        LineChart(
                            values = values.map { it.second },
                            modifier = Modifier.fillMaxWidth().height(Metrics.chartHeight),
                            color = Palette.metricCyan,
                            fill = false,
                            formatValue = { RacePredictor.formatTime(Math.round(it).toInt()) },
                            selectionLabels = values.map { it.first },
                        )
                    } else {
                        Text(uiString(R.string.race_trend_empty), style = NoopType.subhead, color = Palette.textTertiary)
                    }
                    RaceDivider()
                    val best = values.minOfOrNull { it.second }
                    val slowest = values.maxOfOrNull { it.second }
                    RaceNote(
                        uiString(
                            R.string.race_trend_range,
                            best?.let { RacePredictor.formatTime(Math.round(it).toInt()) } ?: "—",
                            slowest?.let { RacePredictor.formatTime(Math.round(it).toInt()) } ?: "—",
                        ),
                    )
                }
            }
            RaceNote(uiString(R.string.race_trend_note))
        }
    }
}

/** "This run predicts": what one qualifying run predicts for each target it reaches, marking the targets
 *  where it is the current best ([best], null until loaded). "Your best" compares against the run-only pick
 *  ([RacePredictor.Result.items]); when the race card blends in heart rate or VO₂max, a note says its times can
 *  differ from this run's. Draws nothing for a run that does not qualify. */
@Composable
internal fun RunRacePredictionsSection(run: RacePredictor.Run, best: RacePredictor.Result?) {
    val items = RacePredictor.forRun(run)
    if (items.none { it != null }) return
    val system = UnitPrefs.distanceSystem(LocalContext.current)
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        SectionHeader(
            uiString(R.string.race_this_run_predicts),
            overline = uiString(R.string.garmin_cat_race_predictions),
        )
        NoopCard(tint = Palette.metricCyan) {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
                items.forEachIndexed { index, p ->
                    if (p != null) {
                        val b = best?.items?.getOrNull(index)
                        val isBest = b != null && b.sourceStartTs == run.startTs && b.sourceDistanceM == (run.distanceM ?: 0.0)
                        RacePredictionRow(
                            targetIndex = index,
                            seconds = p.seconds,
                            secPerKm = RacePredictor.secPerKm(p),
                            system = system,
                            caption = if (isBest) uiString(R.string.race_your_best) else null,
                            highlighted = isBest,
                        )
                    }
                }
                RaceDivider()
                RaceNote(uiString(R.string.race_this_run_note))
                val blended = best?.estimates?.any { it?.components?.hrS != null || it?.components?.vo2S != null } == true
                if (blended) RaceNote(uiString(R.string.race_this_run_blend_note))
            }
        }
    }
}
