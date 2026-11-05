package com.noop.ui

import com.noop.R
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.analytics.BalanceRead
import com.noop.analytics.WeeklyDigestInputs
import com.noop.analytics.WeeklyDigestWatchSeries
import com.noop.analytics.WeeklyDigest
import com.noop.analytics.WeeklyDigestEngine
import com.noop.analytics.WeeklyDigestSection
import com.noop.analytics.WeeklyDigestSectionRows
import com.noop.analytics.sections
import com.noop.analytics.WeeklyMetric
import com.noop.analytics.WeeklyMetricSummary
import com.noop.data.DailyMetric
import kotlin.math.abs
import kotlin.math.roundToInt

// MARK: - Weekly Digest (#208)
//
// A deterministic, offline "week in review". Kotlin parity for the macOS/iOS
// WeeklyDigestView. Reads the merged daily history from the view model, pulls each
// tracked metric into a "yyyy-MM-dd"→value map, and feeds the pure
// WeeklyDigestEngine to produce a Monday-anchored summary: per-metric this-week
// mean + week-over-week delta + vs-baseline, the biggest movers, a strain-vs-recovery
// balance read, and 1–2 plain-English focal points. No AI, no network.
//
// Two surfaces are exposed so navigation can wire whichever it wants:
//   • WeeklyDigestCard  — an embeddable card (drop into Today / Trends).
//   • WeeklyDigestScreen — a full ScreenScaffold screen (for a nav destination).
// Both share WeeklyDigestContent so they never drift. Framing is informational
// (non-clinical), consistent with the app disclaimer.

/**
 * The engine's Effort display factor for the user's scale (#268/#463): moverSentence's
 * "(avg X vs Y)" prints stored 0-100 Effort means, so the 0-21 toggle rescales them for
 * display only. 1.0 leaves every sentence byte-identical to the pre-toggle output.
 */
internal fun effortDisplayFactor(scale: EffortScale): Double =
    if (scale == EffortScale.WHOOP) UnitFormatter.EFFORT_SCALE_FACTOR else 1.0

/**
 * Build the weekly digest for the week containing today's logical local day from a
 * [DailyMetric] history. Extracts each metric into a day→value map and hands it to the
 * pure engine. [effortDisplayFactor] follows the Effort display-scale toggle so the
 * engine's focal sentences quote Effort on the scale the user reads everywhere else.
 */
fun buildWeeklyDigest(
    days: List<DailyMetric>,
    anchorDay: String = logicalDayKeyNow(),
    effortDisplayFactor: Double = 1.0,
    // #55: the Rest each day was scored with (imported, else the engine's persisted value). A day missing
    // here is not scored yet and falls back to the cold-start composite from its totals.
    restByDay: Map<String, Double> = emptyMap(),
    // #89: the paired watch's series (stress, Body Battery, training load…); empty for a strap-only user.
    watch: WeeklyDigestWatchSeries = WeeklyDigestWatchSeries.EMPTY,
): WeeklyDigest = WeeklyDigestEngine.build(
    byMetric = WeeklyDigestInputs.series(days.map { WeeklyDigestLoader.digestDay(it, restByDay) }, watch),
    anchorDay = anchorDay,
    effortDisplayFactor = effortDisplayFactor,
)

/**
 * The digest's inputs beyond the daily rows for the week containing [anchorDay], read through
 * [WeeklyDigestLoader] (the same calls the weekly-summary notification makes). Reloads when [days]
 * changes, which is when a pass has rewritten the series. Empty until loaded.
 */
@Composable
internal fun rememberWeeklyDigestInputs(days: List<DailyMetric>, anchorDay: String): WeeklyDigestLoader.Inputs {
    val app = LocalContext.current.applicationContext as com.noop.NoopApplication
    val state = androidx.compose.runtime.produceState(WeeklyDigestLoader.Inputs(), days, anchorDay) {
        value = WeeklyDigestLoader.load(app.repository, app.deviceRegistry, app.activeDeviceId, anchorDay)
    }
    return state.value
}

// MARK: - Embeddable card

/**
 * The weekly digest as a single card (for Today / Trends). Renders nothing when there's
 * no data this week, so it's safe to always place.
 */
@Composable
fun WeeklyDigestCard(vm: AppViewModel, modifier: Modifier = Modifier) {
    val days by vm.recentDays.collectAsStateWithLifecycle()
    val factor = effortDisplayFactor(UnitPrefs.effortScale(LocalContext.current))
    val anchorDay = logicalDayKeyNow()
    val inputs = rememberWeeklyDigestInputs(days, anchorDay)
    val digest = buildWeeklyDigest(days, anchorDay, factor, inputs.restByDay, inputs.watch)
    if (digest.isEmpty) return
    NoopCard(modifier = modifier) {
        WeeklyDigestContent(digest = digest, compact = true)
    }
}

// MARK: - Full screen

/** The weekly digest as a full screen (for a nav destination). */
@Composable
fun WeeklyDigestScreen(vm: AppViewModel) {
    val days by vm.recentDays.collectAsStateWithLifecycle()
    val factor = effortDisplayFactor(UnitPrefs.effortScale(LocalContext.current))
    val anchorDay = logicalDayKeyNow()
    val inputs = rememberWeeklyDigestInputs(days, anchorDay)
    ScreenScaffold(title = uiString(R.string.l10n_weekly_digest_card_week_in_review_66d95a07), subtitle = uiString(R.string.l10n_weekly_digest_card_your_monday_to_sunday_read_in_c475888a)) {
        val digest = buildWeeklyDigest(days, anchorDay, factor, inputs.restByDay, inputs.watch)
        if (digest.isEmpty) {
            DataPendingNote(
                title = uiString(R.string.l10n_weekly_digest_card_no_readings_this_week_yet_0745a2df),
                body = uiString(R.string.l10n_weekly_digest_card_wear_your_strap_or_import_your_b3211f76),
            )
        } else {
            NoopCard { WeeklyDigestContent(digest = digest, compact = false) }
        }
    }
}

// MARK: - Shared content

private val MONTHS = arrayOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)

private val DISPLAY_ORDER = listOf(
    WeeklyMetric.CHARGE, WeeklyMetric.EFFORT, WeeklyMetric.REST, WeeklyMetric.HRV, WeeklyMetric.RHR,
)

/**
 * The inner content shared by the card and the full screen. [compact] trims the metric
 * grid to the headline rows for the card; the full screen shows everything plus a footer.
 */
@Composable
fun WeeklyDigestContent(digest: WeeklyDigest, compact: Boolean = false) {
    // #268/#463: the Effort row follows the Effort display-scale toggle like every other Effort
    // read-out in the app (Swift's DigestScoreCard already does). Read once here, threaded to the
    // rows, so a 0-21 user can't see "Effort 22" beside a Trends chart reading 4.6.
    val effortScale = UnitPrefs.effortScale(LocalContext.current)
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // Header.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Overline("Week in review")
                Text(weekRangeLabel(digest), style = NoopType.title2, color = Palette.textPrimary)
            }
            Text(
                uiString(R.string.l10n_weekly_digest_card_digest_dayswithdata_7_days_182e6a18, digest.daysWithData),
                style = NoopType.footnote,
                color = Palette.textSecondary,
                modifier = Modifier.semantics {
                    contentDescription = uiString(R.string.l10n_weekly_digest_card_digest_dayswithdata_of_7_days_had_8068f0ee, digest.daysWithData)
                },
            )
        }

        // Focal points — the plain-English read, most salient first.
        if (digest.focalPoints.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                WeeklyDigestText.focalLines(digest).forEach { FocalRow(it) }
            }
        }

        HorizontalDivider(color = Palette.hairline)

        // Per-metric rows.
        val rows = (if (compact) listOf(WeeklyMetric.CHARGE, WeeklyMetric.EFFORT, WeeklyMetric.REST)
        else DISPLAY_ORDER).mapNotNull { digest.summary(it) }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            rows.forEach { MetricRow(it, effortScale) }
        }

        // The metrics after the headline five, grouped; a row only when it has data this week (#90).
        WeeklyDigestEngine.sections(digest).forEach { group ->
            HorizontalDivider(color = Palette.hairline)
            SectionBlock(group, effortScale)
        }

        if (!compact) {
            HorizontalDivider(color = Palette.hairline)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                digest.sleepConsistencySD?.let { sd ->
                    Text(
                        uiString(R.string.l10n_weekly_digest_card_sleep_steadiness_rest_varied_fmt1_sd_44997f21, fmt1(sd)),
                        style = NoopType.footnote,
                        color = Palette.textTertiary,
                    )
                }
                Text(WeeklyDigestText.balance(digest.balance), style = NoopType.footnote, color = Palette.textTertiary)
                Text(
                    uiString(R.string.l10n_weekly_digest_card_informational_only_not_medical_advice_593feb77),
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                )
            }
        }
    }
}

@Composable
private fun FocalRow(line: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = line },
    ) {
        Icon(
            Icons.Filled.AutoAwesome,
            contentDescription = null,
            tint = Palette.accent,
            modifier = Modifier.size(16.dp),
        )
        Text(line, style = NoopType.subhead, color = Palette.textPrimary)
    }
}

@Composable
private fun MetricRow(s: WeeklyMetricSummary, effortScale: EffortScale) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = rowAccessibility(s, effortScale) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            uiString(s.metric.labelRes()),
            style = NoopType.subhead,
            color = Palette.textSecondary,
            modifier = Modifier.width(92.dp),
        )
        Text(
            meanText(s, effortScale),
            style = NoopType.bodyNumber,
            color = Palette.textPrimary,
            // 84 (was 64) so the Effort scale read-out ("21.6 / 100") fits on one line.
            modifier = Modifier.width(84.dp),
        )
        Spacer(Modifier.weight(1f))
        DeltaChip(s)
    }
}

@Composable
private fun SectionBlock(group: WeeklyDigestSectionRows, effortScale: EffortScale) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            uiString(group.section.titleRes()),
            style = NoopType.caption,
            color = Palette.textTertiary,
            modifier = Modifier.semantics { heading() },
        )
        group.rows.forEach { row ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MetricRow(row, effortScale)
                val progress = row.targetProgress
                val target = row.metric.spec.weeklyTarget
                if (progress != null && target != null) TargetBar(progress, row.thisValue, target)
            }
        }
    }
}

/** Progress toward a weekly goal (intensity minutes vs 150). */
@Composable
private fun TargetBar(progress: Double, value: Double, target: Double) {
    val label = uiString(R.string.weekly_digest_target_progress, value.roundToInt(), target.roundToInt())
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.clearAndSetSemantics { contentDescription = label },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .background(Palette.hairline, RoundedCornerShape(Metrics.cornerPill)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(progress.coerceIn(0.0, 1.0).toFloat())
                    .height(6.dp)
                    .background(Palette.effortColor, RoundedCornerShape(Metrics.cornerPill)),
            )
        }
        Text(label, style = NoopType.footnote, color = Palette.textTertiary)
    }
}

@Composable
private fun DeltaChip(s: WeeklyMetricSummary) {
    val tone = chipTone(s)
    val arrow: ImageVector = when {
        s.wowDelta > 0 -> Icons.Filled.ArrowUpward
        s.wowDelta < 0 -> Icons.Filled.ArrowDownward
        else -> Icons.Filled.Remove
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier
            .background(tone.copy(alpha = 0.12f), RoundedCornerShape(Metrics.cornerPill))
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .clearAndSetSemantics { },
    ) {
        Icon(arrow, contentDescription = null, tint = tone, modifier = Modifier.size(10.dp))
        Text(deltaText(s), style = NoopType.captionNumber, color = tone)
    }
}

// MARK: - Formatting

private fun weekRangeLabel(digest: WeeklyDigest): String =
    "${shortDate(digest.weekStart)}-${shortDate(digest.weekEnd)}"

/** "Jun 8" from "2026-06-08", via the engine's own pure parse (no Calendar). */
private fun shortDate(ymd: String): String {
    val p = WeeklyDigestEngine.parseYMD(ymd) ?: return ymd
    val name = if (p[1] in 1..12) MONTHS[p[1] - 1] else p[1].toString()
    return "$name ${p[2]}"
}

internal fun meanText(s: WeeklyMetricSummary, effortScale: EffortScale): String {
    if (s.thisWeek.n == 0) return "—"
    // #463: Effort is STORED 0-100; render it on the user's chosen display scale WITH the denominator
    // ("4.6 / 21", "21.6 / 100") so the card can't read as a different number than the Trends chart.
    if (s.metric == WeeklyMetric.EFFORT) {
        return "${UnitFormatter.effortDisplay(s.thisWeek.mean, effortScale)} / " +
            UnitFormatter.effortScaleMax(effortScale)
    }
    if (s.metric !in HEADLINE_METRICS) return valueText(s)
    val v = s.thisWeek.mean.roundToInt()
    return if (s.metric.unit.isEmpty()) "$v" else "$v ${s.metric.unit}"
}

/** The headline five, whose rows keep their original formatting. */
private val HEADLINE_METRICS = WeeklyMetric.values().take(5).toSet()

/**
 * The this-week figure for a non-headline metric: the mean, total or latest reading per its
 * roll-up, with its unit (#90). Units that read the same in every language stay verbatim.
 * Mirrors Swift `WeeklyDigestValueText.value`.
 */
internal fun valueText(s: WeeklyMetricSummary): String {
    if (!s.hasData) return "—"
    val spec = s.metric.spec
    val n = digestNumber(s.thisValue, spec.decimals)
    return when (s.metric) {
        WeeklyMetric.SLEEP_DURATION -> uiString(R.string.weekly_digest_value_hours, n)
        WeeklyMetric.INTENSITY_MINUTES, WeeklyMetric.DEEP_SLEEP, WeeklyMetric.REM_SLEEP ->
            uiString(R.string.weekly_digest_value_minutes, s.thisValue.roundToInt())
        WeeklyMetric.SKIN_TEMP_DEVIATION -> "${if (s.thisValue > 0) "+" else ""}$n °C"
        else -> if (spec.unit.isEmpty() || spec.unit == "steps") n else "$n ${spec.unit}"
    }
}

/** Whole number, or one decimal with a "." separator (as Swift's `%.1f`). */
internal fun digestNumber(x: Double, decimals: Int): String =
    if (decimals == 0) "${x.roundToInt()}" else String.format(java.util.Locale.ROOT, "%.1f", x)

internal fun deltaText(s: WeeklyMetricSummary): String {
    if (s.weekOverWeek.current.n == 0 || s.weekOverWeek.previous.n == 0) return "new"
    // A metric already in % or a ±deviation reads its change in its own units (#90).
    if (!s.metric.spec.percentChange) return digestNumber(abs(s.wowDelta), 1)
    val pct = s.weekOverWeek.pctChange
    // Sub-1% (or unpercentable) moves read "<1%", matching Swift. The old fallback printed the raw
    // points delta: a bare "0.1", and for Effort a stored 0-100 figure the scale toggle never saw.
    return if (pct != null && abs(pct) >= 1) "${abs(pct).roundToInt()}%" else "<1%"
}

/**
 * Tone: good moves green, bad moves rose, flat/uncomparable grey — folding in each
 * metric's higherIsBetter (so a Resting-HR rise reads as a warning). A ROUGH comparison
 * (either side thin, engine's [WeeklyMetricSummary.isRoughComparison], the deferred half of
 * the 4.2.10 fix for #463) keeps its arrow + % but stays grey regardless of direction.
 */
private fun chipTone(s: WeeklyMetricSummary): Color = when {
    s.isRoughComparison -> Palette.textTertiary
    s.wowGoodness == 1 -> Palette.statusPositive
    s.wowGoodness == -1 -> Palette.statusCritical
    else -> Palette.textTertiary
}

/** The localized display name for a digest metric; [WeeklyMetric.label] stays English for narration. */
@StringRes
internal fun WeeklyMetric.labelRes(): Int = when (this) {
    WeeklyMetric.CHARGE -> R.string.today_metric_charge
    WeeklyMetric.EFFORT -> R.string.today_metric_effort
    WeeklyMetric.REST -> R.string.today_metric_rest
    WeeklyMetric.RHR -> R.string.today_metric_resting_hr
    WeeklyMetric.HRV -> R.string.today_metric_hrv
    WeeklyMetric.STEPS -> R.string.weekly_digest_metric_steps
    WeeklyMetric.ACTIVE_KCAL -> R.string.weekly_digest_metric_active_kcal
    WeeklyMetric.INTENSITY_MINUTES -> R.string.weekly_digest_metric_intensity_minutes
    WeeklyMetric.SLEEP_DURATION -> R.string.weekly_digest_metric_sleep_duration
    WeeklyMetric.SLEEP_EFFICIENCY -> R.string.weekly_digest_metric_sleep_efficiency
    WeeklyMetric.DEEP_SLEEP -> R.string.weekly_digest_metric_deep_sleep
    WeeklyMetric.REM_SLEEP -> R.string.weekly_digest_metric_rem_sleep
    WeeklyMetric.SLEEP_SCORE -> R.string.weekly_digest_metric_sleep_score
    WeeklyMetric.SPO2 -> R.string.weekly_digest_metric_spo2
    WeeklyMetric.RESPIRATION -> R.string.weekly_digest_metric_respiration
    WeeklyMetric.SKIN_TEMP_DEVIATION -> R.string.weekly_digest_metric_skin_temp
    WeeklyMetric.STRESS -> R.string.weekly_digest_metric_stress
    WeeklyMetric.BODY_BATTERY_RECHARGE -> R.string.weekly_digest_metric_body_battery_recharge
    WeeklyMetric.TRAINING_LOAD -> R.string.weekly_digest_metric_training_load
    WeeklyMetric.VO2MAX -> R.string.weekly_digest_metric_vo2max
}

/** The localized heading for a card section. */
@StringRes
private fun WeeklyDigestSection.titleRes(): Int = when (this) {
    WeeklyDigestSection.ACTIVITY -> R.string.weekly_digest_section_activity
    WeeklyDigestSection.SLEEP -> R.string.weekly_digest_section_sleep
    WeeklyDigestSection.OVERNIGHT_VITALS -> R.string.weekly_digest_section_overnight_vitals
    WeeklyDigestSection.STRESS_ENERGY -> R.string.weekly_digest_section_stress_energy
    WeeklyDigestSection.TRAINING -> R.string.weekly_digest_section_training
}

private fun rowAccessibility(s: WeeklyMetricSummary, effortScale: EffortScale): String {
    val mean = meanText(s, effortScale)
    if (s.weekOverWeek.current.n == 0 || s.weekOverWeek.previous.n == 0) {
        return "${s.metric.label}: $mean this week, no comparison."
    }
    val dir = if (s.wowDelta > 0) "up" else if (s.wowDelta < 0) "down" else "unchanged"
    // A rough comparison drops the verdict framing too, so VoiceOver/TalkBack matches the neutral chip.
    val frame = when {
        s.isRoughComparison -> ""
        s.wowGoodness == 1 -> ", a good sign"
        s.wowGoodness == -1 -> ", worth a look"
        else -> ""
    }
    return "${s.metric.label}: $mean this week, $dir ${deltaText(s)} week over week$frame."
}

private fun fmt1(x: Double): String = ((x * 10).roundToInt() / 10.0).toString()
