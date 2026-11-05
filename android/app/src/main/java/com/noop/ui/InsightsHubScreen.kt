package com.noop.ui

import com.noop.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.LocalBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.noop.analytics.DoseCurvePoint
import com.noop.analytics.DoseResponse
import com.noop.analytics.DoseResponseEngine
import com.noop.analytics.DoseResponsePriors
import com.noop.analytics.DosedBehavior
import com.noop.analytics.EffectRanker
import com.noop.analytics.RankedEffect
import com.noop.analytics.ScoreConfidence
import com.noop.data.DailyMetric
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// MARK: - Insights Hub (v5)
//
// The headline n-of-1 "what actually moves YOUR recovery" surface — the Compose twin of
// Strand/Screens/InsightsHubView.swift. Two halves, both pure association on the user's
// own logged days, never advice / cause / diagnosis:
//
//  1. WHAT MOVES YOUR CHARGE — the unified lag-aware EffectRanker feed. Each row keeps the
//     strongest honest lag ({0,+1,+2}) so it reads "shows up the next morning"; carries the
//     sign-aware sentence, with/without means, a lead/lag chip, the effect-size word, and a
//     Solid / Building / Calibrating confidence pill (not a bare "significant" stamp).
//
//  2. ALCOHOL / CAFFEINE DOSE-RESPONSE — the personal DoseResponseEngine curve that SHRINKS
//     toward a documented population prior until enough nights accrue. Plots the shrunk curve,
//     states "each extra drink ≈ −N for you" (honest when prior-dominated, or when YOUR data
//     contradicts the prior), and an evening "damage forecast" — "a 2nd drink tonight ≈ −X
//     Charge tomorrow" — driven by a small dose stepper on the latest Charge. Never a nudge
//     to drink or abstain.
//
// SELF-CONTAINED: owns its own InsightsHubViewModel (constructed from vm.repo + cached days);
// does NOT edit AppViewModel / AppRoot / the central nav. Wave 3 surfaces it at the head of the
// Insights hub. All maths is in com.noop.analytics (EffectRanker / DoseResponseEngine).

@Composable
fun InsightsHubScreen(vm: AppViewModel) {
    val days by vm.recentDays.collectAsState()
    val hub = remember { InsightsHubViewModel() }
    val state by hub.state.collectAsState()

    // Re-derive whenever the cached days change underneath (journal + dose are read via repo).
    androidx.compose.runtime.LaunchedEffect(days) { hub.load(vm, days) }

    var outcome by remember { mutableStateOf(InsightsOutcome.Recovery) }
    val ranked = remember(state, outcome) { hub.rankFor(state, outcome) }

    // PERF (#707): lazy scaffold — each section (and its standalone Spacer, a real child of the eager
    // `spacedBy(20.dp)` Column) becomes one `item { }`, so the LazyColumn's matching `spacedBy(20.dp)`
    // reproduces identical spacing and only on-screen sections compose + are semantics-walked.
    LazyScreenScaffold(title = uiString(R.string.l10n_insights_hub_screen_insights_b4510362), subtitle = uiString(R.string.l10n_insights_hub_screen_patterns_in_your_own_data_association_ad6c33c2)) {
        if (!state.loaded) {
            item {
            NoopCard {
                Text(uiString(R.string.l10n_insights_hub_screen_reading_your_journal_and_outcomes_4a59af69), style = NoopType.subhead, color = Palette.textTertiary)
            }
            }
            return@LazyScreenScaffold
        }

        // --- What moves your Charge -------------------------------------------
        item { MoversSection(outcome = outcome, onOutcome = { outcome = it }, ranked = ranked) }

        item { Spacer(Modifier.height(Metrics.sectionGap - 20.dp)) }

        // --- Dose-response (alcohol / caffeine) -------------------------------
        item { DoseSection(state.doseCards) }

        item { Spacer(Modifier.height(Metrics.sectionGap - 20.dp)) }

        // --- Method / honesty note --------------------------------------------
        item {
        NoopCard {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Overline(uiString(R.string.l10n_insights_hub_screen_how_to_read_this_36beed29), color = Palette.textTertiary)
                Text(
                    uiString(R.string.l10n_insights_hub_screen_everything_here_is_a_pattern_in_ed2162a6),
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                )
            }
        }
        }
    }
}

// MARK: - What moves your Charge

@Composable
private fun MoversSection(
    outcome: InsightsOutcome,
    onOutcome: (InsightsOutcome) -> Unit,
    ranked: List<RankedEffect>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        // Header then the outcome selector on its own row below it — on a ~360dp phone the pill
        // control can't share a row with the weighted header without compressing (matches macOS).
        SectionHeader(
            uiString(R.string.l10n_insights_screen_what_moves_your_1_s_611dfd6e, outcome.displayName.lowercase(Locale.getDefault())),
            overline = uiString(R.string.l10n_insights_hub_screen_ranked_your_data_f6378a8b),
        )
        SegmentedPillControl(
            items = InsightsOutcome.entries.toList(),
            selection = outcome,
            label = { it.label },
            onSelect = onOutcome,
        )

        if (ranked.isEmpty()) {
            NoopCard {
                Text(
                    uiString(R.string.l10n_insights_hub_screen_not_enough_overlap_between_your_journal_0ebdd7a2, outcome.displayName.lowercase(Locale.getDefault())),
                    style = NoopType.subhead,
                    color = Palette.textTertiary,
                )
            }
        } else {
            // Fade + rise the ranked mover cards in sequence (mirrors iOS .staggeredAppear(index:)).
            ranked.forEachIndexed { i, r ->
                Box(modifier = Modifier.staggeredAppear(i)) { MoverCard(r, outcome) }
            }
        }
    }
}

@Composable
private fun MoverCard(r: RankedEffect, outcome: InsightsOutcome) {
    val e = r.effect
    val movedGood: Boolean? = when {
        e.delta == 0.0 -> null
        else -> (e.delta > 0) == outcome.higherIsBetter
    }
    val tone: StrandTone = when (movedGood) {
        null -> StrandTone.Neutral
        true -> StrandTone.Positive
        false -> if (e.significant) StrandTone.Critical else StrandTone.Warning
    }
    val tintColor = tone.color
    val arrow = if (e.delta > 0) "↑" else if (e.delta < 0) "↓" else "→"
    val deltaText = e.pctChange?.let { "$arrow ${abs(it).roundToInt()}%" }
        ?: "$arrow ${String.format(Locale.US, "%.1f", abs(e.delta))}"

    NoopCard(tint = outcome.domain.color) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
            // Header: behaviour name + lead/lag chip + confidence pill.
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .drawBehind { drawCircle(tintColor) },
                    )
                    Text(
                        r.behavior,
                        style = NoopType.headline,
                        color = Palette.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                StatePill(InsightsSentences.leadLag(r.lag), tone = StrandTone.Accent, showsDot = false)
                Spacer(Modifier.width(6.dp))
                ConfidencePill(r.confidence)
            }

            Text(InsightsSentences.ranked(r), style = NoopType.body, color = Palette.textSecondary)

            // With / without means as uniform StatTiles.
            Row(horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                StatTile(
                    modifier = Modifier.weight(1f),
                    label = uiString(R.string.l10n_insights_hub_screen_with_564f8c6e),
                    value = outcome.format(e.meanWith),
                    caption = uiString(R.string.l10n_insights_screen_n_1_d_cf12fc18, e.nWith),
                    accent = tintColor,
                    delta = deltaText,
                    deltaColor = tintColor,
                )
                StatTile(
                    modifier = Modifier.weight(1f),
                    label = uiString(R.string.l10n_insights_hub_screen_without_cb735356),
                    value = outcome.format(e.meanWithout),
                    caption = uiString(R.string.l10n_insights_screen_n_1_d_cf12fc18, e.nWithout),
                    accent = Palette.textPrimary,
                )
            }

            HorizontalDivider(color = Palette.hairline)

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Overline(uiString(R.string.l10n_insights_screen_effect_size_a429d9fd), modifier = Modifier.weight(1f))
                Text(
                    uiString(R.string.l10n_insights_screen_d_1_s_90362355, String.format(Locale.US, "%.2f", e.cohensD)),
                    style = NoopType.captionNumber,
                    color = tintColor,
                )
                Spacer(Modifier.width(6.dp))
                Text(effectMagnitudeWord(e.cohensD), style = NoopType.caption, color = Palette.textTertiary)
            }
        }
    }
}

// MARK: - Dose-response

@Composable
private fun DoseSection(cards: List<DoseCardData>) {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        SectionHeader(uiString(R.string.l10n_insights_hub_screen_dose_response_16a6eb52), overline = uiString(R.string.l10n_insights_hub_screen_personal_curve_prior_shrunk_33f722f4))
        if (cards.isEmpty()) {
            NoopCard {
                Text(
                    uiString(R.string.l10n_insights_hub_screen_log_alcohol_or_late_caffeine_with_dec9dadf),
                    style = NoopType.subhead,
                    color = Palette.textSecondary,
                )
            }
        } else {
            // Fade + rise the dose-response cards in sequence (mirrors iOS .staggeredAppear(index:)).
            cards.forEachIndexed { i, card ->
                Box(modifier = Modifier.staggeredAppear(i)) { DoseResponseCard(card) }
            }
        }
    }
}

@Composable
private fun DoseResponseCard(card: DoseCardData) {
    val r = card.response
    val domain = if (card.outcomeName == "HRV") DomainTheme.Rest else DomainTheme.Charge
    // The evening preview dose, defaulting to a 2nd drink so the headline reads as a 2nd-drink forecast.
    var previewDose by remember(card.id) { mutableStateOf(2) }

    NoopCard(tint = domain.color) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
            // Header.
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(card.icon, contentDescription = null, tint = domain.color, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(card.title, style = NoopType.headline, color = Palette.textPrimary, modifier = Modifier.weight(1f))
                ConfidencePill(r.confidence)
            }

            Text(InsightsSentences.dose(r), style = NoopType.body, color = Palette.textSecondary)

            // The prior-shrunk curve.
            DoseCurveChart(
                points = r.curve,
                accent = domain.color,
                description = curveDescription(card, r),
                valueText = { doseCurveScrubValue(it, card.outcomeSuffix, card.outcomeName) },
                doseLabel = { doseCurveDoseLabel(card.behavior, it, UiDoseWords) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(132.dp),
            )

            if (r.priorDominated) {
                HonestyBanner(
                    uiString(R.string.l10n_insights_hub_screen_based_mostly_on_typical_patterns_not_4265c0ba, card.unitLabel.lowercase(Locale.US)),
                    accent = Palette.textTertiary,
                )
            } else if (r.contradictsPrior) {
                HonestyBanner(
                    uiString(R.string.l10n_insights_hub_screen_in_your_data_so_far_this_3fbe810b, card.outcomeName),
                    accent = Palette.statusPositive,
                )
            }

            if (card.timingProxy) {
                Text(
                    uiString(R.string.l10n_insights_hub_screen_dose_here_is_timing_later_in_1b1e9326),
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                )
            }

            HorizontalDivider(color = Palette.hairline)

            DamageForecast(card, previewDose = previewDose, onPreviewDose = { previewDose = it }, domain = domain)
        }
    }
}

@Composable
private fun DamageForecast(
    card: DoseCardData,
    previewDose: Int,
    onPreviewDose: (Int) -> Unit,
    domain: DomainTheme,
) {
    val r = card.response
    val fromDose = 1
    val delta = r.delta(fromDose, previewDose)
    val projected = card.latestOutcome?.let { max(0.0, min(card.outcomeCeiling, it + delta)) }
    val stepLabel = if (previewDose <= 1) uiString(R.string.l10n_insights_hub_screen_no_extra_35c09a52) else card.stepLabel(previewDose)

    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        // Overline then the dose stepper on its own row — the choices (0…max+) overflow a ~360dp
        // phone if they share a row with the overline (matches the macOS fix).
        Overline(card.forecastOverline, modifier = Modifier.fillMaxWidth())
        SegmentedPillControl(
            items = card.doseChoices,
            selection = previewDose,
            label = { card.doseChoiceLabel(it, UiDoseWords) },
            onSelect = onPreviewDose,
        )

        Text(
            forecastSentence(card, previewDose, delta, stepLabel),
            style = NoopType.subhead,
            color = Palette.textSecondary,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
            StatTile(
                modifier = Modifier.weight(1f),
                label = uiString(R.string.l10n_insights_hub_screen_per_extra_card_unitnoun_a92a5e91, card.unitNoun),
                value = signed(r.perUnit, card.outcomeSuffix),
                caption = if (r.priorDominated) uiString(R.string.l10n_insights_hub_screen_typical_ff28673d) else uiString(R.string.l10n_insights_hub_screen_your_data_4a10da63),
                accent = if (r.perUnit < 0) Palette.statusCritical else Palette.statusPositive,
            )
            StatTile(
                modifier = Modifier.weight(1f),
                label = uiString(R.string.l10n_insights_hub_screen_tomorrow_s_card_outcomename_ad70b7f9, card.outcomeName),
                value = projected?.let { "${it.roundToInt()}${card.outcomeSuffix}" } ?: "—",
                caption = if (projected != null) uiString(R.string.l10n_insights_hub_screen_projected_1_s_b36d9bc4, stepLabel) else uiString(R.string.l10n_insights_hub_screen_needs_a_recent_day_7ced2ffa),
                accent = domain.color,
            )
        }
    }
}

@Composable
private fun HonestyBanner(text: String, accent: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Palette.surfaceInset)
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).drawBehind { drawCircle(accent) })
        Text(text, style = NoopType.footnote, color = Palette.textSecondary)
    }
}

/** The confidence-lifecycle pill: Solid (positive/gold) / Building (accent) / Calibrating (neutral). */
@Composable
private fun ConfidencePill(c: ScoreConfidence) {
    val (label, tone) = when (c) {
        ScoreConfidence.SOLID -> uiString(R.string.scoring_guide_solid) to StrandTone.Positive
        ScoreConfidence.BUILDING -> uiString(R.string.scoring_guide_building) to StrandTone.Accent
        ScoreConfidence.CALIBRATING -> uiString(R.string.l10n_today_screen_calibrating_37c2c9bd) to StrandTone.Neutral
    }
    StatePill(label, tone = tone, showsDot = false)
}

// MARK: - Dose curve chart
//
// A compact line+area chart of the prior-shrunk curve: dose on x (0…max), modelled outcome
// DELTA on y, symmetric around a dashed zero line so the sign reads honestly. Drawn with the
// Compose Canvas idiom so it sits in the design system with no extra dependency.

@Composable
private fun DoseCurveChart(
    points: List<DoseCurvePoint>,
    accent: Color,
    description: String,
    valueText: (Double) -> String,
    doseLabel: (Int) -> String,
    modifier: Modifier = Modifier,
) {
    val zeroColor = Palette.hairlineStrong
    // Scrub cursor (#58): snaps to the nearest dose bin and reads its modelled delta. The bins are
    // evenly spaced, the same placement `xFor` draws them at.
    var scrubX by remember(points) { mutableStateOf<Float?>(null) }
    var chartWidth by remember { mutableStateOf(0f) }
    val paints = rememberChartScrubPaints()
    val fractions = remember(points) { chartScrubIndexFractions(points.size) }
    val selected = scrubX?.let { x ->
        if (points.isEmpty()) null else chartScrubNearestIndex(fractions, chartWidth, x)
    }
    val callout = selected?.let { i -> valueText(points[i].outcomeDelta) to doseLabel(points[i].dose) }
    ChartScrubHaptic(selected)
    Canvas(
        modifier = modifier
            .onSizeChanged { chartWidth = it.width.toFloat() }
            .chartScrub(points, onRelease = { scrubX = null }) { scrubX = it }
            .clearAndSetSemantics {
                contentDescription = description
                callout?.let { (value, label) -> stateDescription = chartScrubSpoken(value, label) }
            },
    ) {
        if (points.isEmpty()) return@Canvas
        val w = size.width
        val h = size.height
        val maxAbs = max(1.0, points.maxOf { abs(it.outcomeDelta) })
        fun yFor(d: Double): Float {
            val t = (d / maxAbs + 1) / 2          // 0 (most negative) … 1 (most positive)
            return (h - t * h).toFloat()
        }
        val n = max(1, points.size - 1)
        fun xFor(i: Int): Float = i.toFloat() / n * w
        val zeroY = yFor(0.0)

        // Zero baseline (dashed).
        drawLine(
            color = zeroColor,
            start = Offset(0f, zeroY),
            end = Offset(w, zeroY),
            strokeWidth = 1f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
        )

        // Filled area between the curve and the zero line.
        val area = Path().apply {
            moveTo(xFor(0), zeroY)
            points.forEachIndexed { i, pt -> lineTo(xFor(i), yFor(pt.outcomeDelta)) }
            lineTo(xFor(points.size - 1), zeroY)
            close()
        }
        drawPath(
            area,
            brush = Brush.verticalGradient(listOf(accent.copy(alpha = 0.22f), accent.copy(alpha = 0.03f))),
        )

        // The curve line.
        val line = Path().apply {
            points.forEachIndexed { i, pt ->
                val x = xFor(i)
                val y = yFor(pt.outcomeDelta)
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
        drawPath(line, color = accent, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))

        // Dose markers.
        points.forEachIndexed { i, pt ->
            drawCircle(accent, radius = 2.5.dp.toPx(), center = Offset(xFor(i), yFor(pt.outcomeDelta)))
        }

        if (selected != null && callout != null) {
            val pt = points[selected]
            drawScrubCursor(xFor(selected), yFor(pt.outcomeDelta), accent, callout.first, callout.second, paints)
        }
    }
}

/** The dose curve callout's value line: the signed modelled delta and the outcome it moves, "−3% Charge". */
internal fun doseCurveScrubValue(delta: Double, suffix: String, outcomeName: String): String =
    "${signed(delta, suffix)} $outcomeName"

/** A dose bin as the stepper names it: the drink count ("4+" at the top bin) or the caffeine timing slot. */
internal fun doseChoiceLabel(behavior: DosedBehavior, d: Int, words: DoseWords = EnglishDoseWords): String = when (behavior) {
    DosedBehavior.ALCOHOL -> if (d >= DoseResponseEngine.maxCurveDose) "$d+" else "$d"
    DosedBehavior.CAFFEINE -> when (d) {
        0 -> words.am
        1 -> words.noon
        2 -> words.afternoon
        else -> words.evening
    }
}

/** The dose curve callout's label: the stepper's name for the bin, with the unit for drinks ("4+ drinks"). */
internal fun doseCurveDoseLabel(behavior: DosedBehavior, dose: Int, words: DoseWords = EnglishDoseWords): String {
    val choice = doseChoiceLabel(behavior, dose, words)
    return when (behavior) {
        DosedBehavior.ALCOHOL -> if (dose == 1) words.drink(choice) else words.drinks(choice)
        DosedBehavior.CAFFEINE -> choice
    }
}

/** The words a dose label is built from. English by default (what the pure helpers' tests pin); the screen
 *  passes [UiDoseWords] so the same labels read in the user's language. */
internal interface DoseWords {
    val am: String
    val noon: String
    val afternoon: String
    val evening: String
    fun drink(choice: String): String
    fun drinks(choice: String): String
}

internal object EnglishDoseWords : DoseWords {
    override val am = "AM"
    override val noon = "Noon"
    override val afternoon = "2pm+"
    override val evening = "Eve"
    override fun drink(choice: String) = "$choice drink"
    override fun drinks(choice: String) = "$choice drinks"
}

internal object UiDoseWords : DoseWords {
    override val am get() = uiString(R.string.l10n_insights_hub_screen_am_80d305c5)
    override val noon get() = uiString(R.string.l10n_insights_hub_screen_noon_ffca17fc)
    override val afternoon get() = uiString(R.string.l10n_insights_hub_screen_2pm_9c2ad532)
    override val evening get() = uiString(R.string.l10n_insights_hub_screen_eve_9a018ecc)
    override fun drink(choice: String) = uiString(R.string.l10n_insights_hub_screen_1_s_drink_61a43a75, choice)
    override fun drinks(choice: String) = uiString(R.string.l10n_insights_hub_screen_1_s_drinks_1dce314b, choice)
}

// MARK: - Outcome

internal enum class InsightsOutcome(
    // Resolved on read, not at enum init, so a language change takes effect without a process restart.
    private val labelOf: () -> String,
    /** Stable English identifier handed to the engines (map keys, priors); never localized. */
    val outcomeName: String,
    private val displayNameOf: () -> String,
    val key: String,
    val higherIsBetter: Boolean,
    val domain: DomainTheme,
    val pick: (DailyMetric) -> Double?,
    val format: (Double) -> String,
) {
    Recovery({ uiString(R.string.trends_charge) }, "Charge", { uiString(R.string.trends_charge) }, "recovery", true, DomainTheme.Charge, { it.recovery }, { "${it.roundToInt()}%" }),
    Hrv({ "HRV" }, "HRV", { "HRV" }, "hrv", true, DomainTheme.Rest, { it.avgHrv }, { "${it.roundToInt()} ms" }),
    Sleep({ uiString(R.string.trends_rest) }, "Rest", { uiString(R.string.trends_rest) }, "sleep_performance", true, DomainTheme.Rest, { it.efficiency }, { "${it.roundToInt()}%" }),
    Rhr({ uiString(R.string.l10n_insights_screen_rhr_04edf9b3) }, "Resting HR", { uiString(R.string.l10n_health_screen_resting_hr_26677094) }, "rhr", false, DomainTheme.Stress, { it.restingHr?.toDouble() }, { "${it.roundToInt()} bpm" }),
    ;

    val label: String get() = labelOf()
    /** The outcome's name in the app language, for display only. */
    val displayName: String get() = displayNameOf()
}

// MARK: - Dose card view-data

internal data class DoseCardData(
    val behavior: DosedBehavior,
    val response: DoseResponse,
    val latestOutcome: Double?,
) {
    val id: String get() = behavior.raw
    val outcomeName: String get() = InsightsSentences.displayOutcome(response.outcome)
    val title: String get() = if (behavior == DosedBehavior.ALCOHOL) uiString(R.string.l10n_insights_hub_screen_alcohol_35fa6a7b) else uiString(R.string.l10n_caffeine_log_caffeine_22859fa3)
    val icon get() = if (behavior == DosedBehavior.ALCOHOL) Icons.Filled.LocalBar else Icons.Filled.Coffee
    val unitNoun: String get() = if (behavior == DosedBehavior.ALCOHOL) uiString(R.string.l10n_insights_hub_screen_drink_7ca7a4ab) else uiString(R.string.l10n_insights_hub_screen_later_step_9c26990f)
    val unitLabel: String get() = if (behavior == DosedBehavior.ALCOHOL) uiString(R.string.l10n_insights_hub_screen_drink_7ca7a4ab) else uiString(R.string.l10n_insights_hub_screen_late_caffeine_301b6421)
    val timingProxy: Boolean get() = behavior == DosedBehavior.CAFFEINE
    val outcomeSuffix: String get() = if (outcomeName == "HRV") " ms" else "%"
    val outcomeCeiling: Double get() = if (outcomeName == "HRV") 400.0 else 100.0
    val forecastOverline: String
        get() = if (behavior == DosedBehavior.ALCOHOL) uiString(R.string.l10n_insights_hub_screen_tonight_s_forecast_1d0f5e15) else uiString(R.string.l10n_insights_hub_screen_timing_forecast_7fadc4fa)

    val doseChoices: List<Int> get() = (0..DoseResponseEngine.maxCurveDose).toList()

    fun doseChoiceLabel(d: Int, words: DoseWords = EnglishDoseWords): String = doseChoiceLabel(behavior, d, words)

    /** The stepper's dose as the forecast copy names it ("2 drinks", "3+ drinks"; the timing slot's number for caffeine). */
    fun stepLabel(d: Int): String =
        if (behavior == DosedBehavior.ALCOHOL) {
            if (d >= DoseResponseEngine.maxCurveDose) uiString(R.string.l10n_insights_hub_screen_1_d_drinks_ea703217, d) else uiString(R.string.l10n_insights_hub_screen_1_d_drinks_57bbbe72, d)
        } else "$d"
}

// MARK: - View-model
//
// Self-contained: loads the journal (behaviour → days), dose rows (under the dedicated
// noop-journal-dose source), and outcome series (cached DailyMetric rows), then runs
// EffectRanker for the ranked feed and DoseResponseEngine for each dosed behaviour with data.
// No edits to AppViewModel. Holds an immutable snapshot in a StateFlow.

internal class InsightsHubViewModel {

    data class Snapshot(
        val loaded: Boolean = false,
        val behaviours: Map<String, Set<String>> = emptyMap(),
        /** Per behaviour, the days it was logged NO — the only legitimate control group. */
        val controls: Map<String, Set<String>> = emptyMap(),
        val outcomeByKey: Map<String, Map<String, Double>> = emptyMap(),
        val doseCards: List<DoseCardData> = emptyList(),
    )

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    companion object {
        const val DOSE_SOURCE = "noop-journal-dose"
        private val OUTCOME_KEYS = listOf("recovery", "hrv", "sleep_performance", "rhr")

        fun doseKey(behavior: DosedBehavior): String = "dose_${behavior.raw}"

        fun matches(behavior: DosedBehavior, question: String): Boolean {
            val q = question.lowercase(Locale.US)
            return when (behavior) {
                DosedBehavior.ALCOHOL -> q.contains("alcohol") || q.contains("drink")
                DosedBehavior.CAFFEINE -> q.contains("caffeine") || q.contains("coffee")
            }
        }

        fun outcomeKeyFor(engineName: String): String = when (engineName) {
            "Charge" -> "recovery"
            "HRV" -> "hrv"
            "Rest" -> "sleep_performance"
            "Resting HR" -> "rhr"
            else -> "recovery"
        }
    }

    suspend fun load(vm: AppViewModel, days: List<DailyMetric>) {
        // Journal → behaviour → days (imported ∪ native, native wins). BOTH answers count now, kept in
        // separate maps; the merge is what guarantees a day cannot be Yes and No for one question.
        val imported = vm.repo.journal("my-whoop", "0000-01-01", "9999-12-31")
        val native = vm.repo.journal(JOURNAL_DEVICE_ID, "0000-01-01", "9999-12-31")
        val entries = mergeJournalEntries(imported, native)
        val byBehaviour = HashMap<String, MutableSet<String>>()
        val controlsByBehaviour = HashMap<String, MutableSet<String>>()
        // Yes days and NO days, kept apart. A day with no journal row for the question appears in
        // neither, so the ranker cannot mistake "never logged" for "logged No" (#EffectRanker.effect).
        for (e in entries) {
            val bucket = if (e.answeredYes) byBehaviour else controlsByBehaviour
            bucket.getOrPut(e.question) { mutableSetOf() }.add(e.day)
        }
        val behaviours = byBehaviour.mapValues { it.value.toSet() }
        val controls = controlsByBehaviour.mapValues { it.value.toSet() }

        // Outcome series straight off the cached DailyMetric rows (the guaranteed Android source).
        val outcomeByKey = HashMap<String, Map<String, Double>>()
        for (o in InsightsOutcome.entries) {
            val dict = HashMap<String, Double>()
            for (d in days) o.pick(d)?.let { dict[d.day] = it }
            outcomeByKey[o.key] = dict
        }

        // Dose rows per dosed behaviour, under the dedicated dose source; logged "yes" days
        // back-fill dose = 1, explicit dose rows override (matches the Swift contract).
        val doseCards = ArrayList<DoseCardData>()
        for (behavior in DosedBehavior.entries) {
            val doses = HashMap<String, Int>()
            for ((question, set) in behaviours) if (matches(behavior, question)) {
                for (day in set) doses[day] = max(doses[day] ?: 0, 1)
            }
            val rows = vm.repo.metricSeries(DOSE_SOURCE, doseKey(behavior), "0000-01-01", "9999-12-31")
            for (row in rows) doses[row.day] = row.value.roundToInt()
            if (doses.isEmpty()) continue

            val outcomeName = DoseResponsePriors.defaultOutcome(behavior)
            val outcomeDays = outcomeByKey[outcomeKeyFor(outcomeName)] ?: emptyMap()
            val response = DoseResponseEngine.estimate(behavior, doses, outcomeDays) ?: continue
            val latest = outcomeDays.keys.maxOrNull()?.let { outcomeDays[it] }
            doseCards.add(DoseCardData(behavior, response, latest))
        }

        _state.value = Snapshot(
            loaded = true,
            behaviours = behaviours,
            controls = controls,
            outcomeByKey = outcomeByKey,
            doseCards = doseCards,
        )
    }

    /** Re-rank the mover feed for a (possibly new) outcome — cheap, no DB. */
    fun rankFor(snapshot: Snapshot, outcome: InsightsOutcome): List<RankedEffect> {
        if (!snapshot.loaded) return emptyList()
        // False-discovery family = every outcome the feed can show (twin of Swift InsightsHubView).
        val outcomes = InsightsOutcome.values().associate { o ->
            o.outcomeName to (snapshot.outcomeByKey[o.key] ?: emptyMap())
        }
        return EffectRanker.rankFamily(snapshot.behaviours, snapshot.controls, outcomes)[outcome.outcomeName].orEmpty()
    }
}

// MARK: - Copy helpers

private fun forecastSentence(card: DoseCardData, previewDose: Int, delta: Double, stepLabel: String): String {
    if (previewDose <= 1) {
        return uiString(R.string.l10n_insights_hub_screen_no_extra_tonight_your_1_s_8c998e43, card.outcomeName.lowercase(Locale.US))
    }
    val mag = abs(delta).roundToInt()
    val lower = delta <= 0
    val magText = "$mag${card.outcomeSuffix}"
    val name = card.outcomeName.lowercase(Locale.US)
    if (card.response.priorDominated) {
        return if (lower) uiString(R.string.l10n_insights_hub_screen_a_1_s_tonight_tends_to_a982e45a, stepLabel, magText, name)
        else uiString(R.string.l10n_insights_hub_screen_a_1_s_tonight_tends_to_39de73c0, stepLabel, magText, name)
    }
    val nUser = card.response.nUser
    val unit = card.unitLabel.lowercase(Locale.US)
    return if (lower) uiString(R.string.l10n_insights_hub_screen_a_1_s_tonight_tends_to_65d6aa5c, stepLabel, magText, name, nUser, unit)
    else uiString(R.string.l10n_insights_hub_screen_a_1_s_tonight_tends_to_c825e69c, stepLabel, magText, name, nUser, unit)
}

private fun curveDescription(card: DoseCardData, r: DoseResponse): String =
    if (r.priorDominated) uiString(R.string.l10n_insights_hub_screen_dose_response_curve_each_extra_1_a694dce8, card.unitNoun, signed(r.perUnit, card.outcomeSuffix), card.outcomeName)
    else uiString(R.string.l10n_insights_hub_screen_dose_response_curve_each_extra_1_f8f60e40, card.unitNoun, signed(r.perUnit, card.outcomeSuffix), card.outcomeName)

private fun signed(v: Double, suffix: String): String {
    val mag = abs(v)
    val rounded = (mag * 10).roundToInt() / 10.0
    val sign = if (v < 0) "−" else if (v > 0) "+" else ""
    val body = if (rounded == rounded.toLong().toDouble()) "${rounded.toLong()}" else String.format(Locale.US, "%.1f", rounded)
    return "$sign$body$suffix"
}

private fun effectMagnitudeWord(d: Double): String = InsightsSentences.magnitudeWord(d)
