package com.noop.ui

import com.noop.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.data.DailyMetric
import com.noop.data.MoodStore
import kotlinx.coroutines.launch
import java.util.Locale
import com.noop.analytics.CorrelationEngine
import kotlin.math.abs

// MARK: - Mind (mood check-in + mood ↔ body correlations)
//
// Kotlin mirror of the Swift Mind lane. One 5-face check-in per local day, stored via
// MoodStore under the shared cross-platform contract (source id "noop-mood", key "mood",
// value 1.0–5.0, overwrite on edit). Once ≥7 days are checked in, up to three Pearson
// correlation lines appear — mood vs HRV / recovery / sleep duration — computed over the
// cached DailyMetric rows with the same CorrelationEngine math as the Compare screen
// (the StrandAnalytics/CorrelationEngine.swift port). Deliberately NEUTRAL palette:
// mood is self-knowledge, not a score, so nothing here is tinted good/bad.

/** One face on the check-in scale. `value` is the stored 1.0–5.0 contract value. */
private data class MoodFace(val emoji: String, val value: Double, val wordEn: String) {
    /** Localized one-word label for the face. */
    val word: String
        get() = when (value.toInt()) {
            1 -> uiString(R.string.l10n_mind_section_awful_b0dd1518)
            2 -> uiString(R.string.l10n_mind_section_low_a124947c)
            3 -> uiString(R.string.l10n_mind_section_okay_d5445583)
            4 -> uiString(R.string.l10n_mind_section_good_61dedcf0)
            else -> uiString(R.string.l10n_mind_section_great_bd846adf)
        }
}

private val MOOD_FACES = listOf(
    MoodFace("😞", 1.0, "Awful"),   // 😞
    MoodFace("😕", 2.0, "Low"),     // 😕
    MoodFace("😐", 3.0, "Okay"),    // 😐
    MoodFace("🙂", 4.0, "Good"),    // 🙂
    MoodFace("😄", 5.0, "Great"),   // 😄
)

/** Check-ins needed before the correlation lines unlock (mirrors the Swift gate). */
private const val MIND_GATE_DAYS = CorrelationEngine.minPairs

/** Minimum |r| for a mood line (mirrors the Swift `minAbsR`). */
private const val MIND_MIN_ABS_R = 0.3

/** Shared verbatim footnote — IDENTICAL string on macOS/iOS; do not reword. */
private fun mindFootnote(): String = uiString(R.string.l10n_mind_section_self_tracking_not_a_clinical_assessment_0aea15a7)

// MARK: - Section

/**
 * Mind — the daily mood check-in card plus the gated mood ↔ body correlations.
 * Hosted on the Insights screen between the journal logging card and the
 * behaviour-effects half.
 */
@Composable
fun MindSection(vm: AppViewModel) {
    val days by vm.recentDays.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val store = remember { MoodStore(vm.repo) }

    var moodSeries by remember { mutableStateOf<List<Pair<String, Double>>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var moodSeq by remember { mutableStateOf(0) }
    var editing by remember { mutableStateOf(false) }

    val todayKey = remember { MoodStore.todayKey() }
    LaunchedEffect(moodSeq) {
        moodSeries = store.moodSeries()
        loaded = true
    }
    val todayMood = moodSeries.lastOrNull { it.first == todayKey }?.second

    // One row per day by the storage PK, so size == distinct check-in days.
    val checkInDays = moodSeries.size
    val lines = remember(days, moodSeries) {
        if (moodSeries.size >= MIND_GATE_DAYS) buildMindCorrelations(days, moodSeries)
        else emptyList()
    }

    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        SectionHeader(uiString(R.string.l10n_mind_section_mind_59e63f7e), overline = uiString(R.string.l10n_mind_section_mood_check_in_38e5858e))

        NoopCard {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                if (!loaded) {
                    Text(
                        uiString(R.string.l10n_mind_section_reading_your_check_ins_71a100a9),
                        style = NoopType.subhead,
                        color = Palette.textTertiary,
                    )
                } else if (todayMood == null || editing) {
                    // --- Open check-in: the 5-face scale -----------------------
                    Text(
                        uiString(R.string.l10n_mind_section_how_are_you_feeling_today_5667deff),
                        style = NoopType.headline,
                        color = Palette.textPrimary,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MOOD_FACES.forEach { face ->
                            MoodFaceButton(
                                face = face,
                                selected = todayMood == face.value,
                                onClick = {
                                    editing = false
                                    scope.launch {
                                        store.setMood(todayKey, face.value)
                                        moodSeq++
                                    }
                                },
                            )
                        }
                    }
                    Text(
                        uiString(R.string.l10n_mind_section_one_check_in_per_day_picking_b103f22e),
                        style = NoopType.footnote,
                        color = Palette.textTertiary,
                    )
                } else {
                    // --- Collapsed: today's face + Edit ------------------------
                    val face = MOOD_FACES.minByOrNull { abs(it.value - todayMood) } ?: MOOD_FACES[2]
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(face.emoji, style = NoopType.number(26f))
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(face.word, style = NoopType.headline, color = Palette.textPrimary)
                            Text(uiString(R.string.l10n_mind_section_logged_today_0071a46c), style = NoopType.caption, color = Palette.textTertiary)
                        }
                        MoodChip(uiString(R.string.l10n_mind_section_edit_5301648d)) { editing = true }
                    }
                }

                // --- Mood ↔ body correlations (≥7-day gate) --------------------
                if (loaded) {
                    HorizontalDivider(color = Palette.hairline)
                    if (checkInDays < MIND_GATE_DAYS) {
                        val left = MIND_GATE_DAYS - checkInDays
                        Text(
                            uiString(
                                R.string.l10n_mind_section_mood_correlations_unlock_after_mind_gate_f0c62abe,
                                MIND_GATE_DAYS,
                                uiPlural(R.plurals.trends_n_days, left, left),
                            ),
                            style = NoopType.footnote,
                            color = Palette.textTertiary,
                        )
                    } else if (lines.isEmpty()) {
                        Text(
                            uiString(R.string.l10n_mind_section_not_enough_overlapping_history_to_correlate_d36b91f9),
                            style = NoopType.footnote,
                            color = Palette.textTertiary,
                        )
                    } else {
                        lines.forEachIndexed { idx, line ->
                            MindCorrelationRow(line)
                            if (idx < lines.size - 1) HorizontalDivider(color = Palette.hairline)
                        }
                    }
                }
            }
        }

        Text(mindFootnote(), style = NoopType.footnote, color = Palette.textTertiary)
    }
}

// MARK: - Pieces

/** One tappable face. Neutral styling: an inset pill whose border firms up when selected —
 *  no good/bad tinting anywhere on the scale. */
@Composable
private fun MoodFaceButton(face: MoodFace, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    val desc = uiString(R.string.l10n_mind_section_1_s_mood_2_d_of_57d4b070, face.word, face.value.toInt())
    Box(
        modifier = Modifier
            .clip(shape)
            .background(Palette.surfaceInset)
            .border(1.dp, if (selected) Palette.hairlineStrong else Palette.hairline, shape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = desc },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            face.emoji,
            style = NoopType.number(22f),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

/** Neutral text chip (the collapsed card's Edit affordance). */
@Composable
private fun MoodChip(label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Text(
        label,
        style = NoopType.caption,
        color = Palette.textSecondary,
        modifier = Modifier
            .clip(shape)
            .background(Palette.surfaceInset)
            .border(1.dp, Palette.hairline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** One correlation line: title + r on the first line, a plain-English reading under it.
 *  Neutral colours — r is information here, not a verdict. */
@Composable
private fun MindCorrelationRow(line: MindLine) {
    val dir = if (line.r > 0) uiString(R.string.l10n_mind_section_positive_c4c6c572) else if (line.r < 0) uiString(R.string.l10n_mind_section_negative_cb47297f) else uiString(R.string.l10n_mind_section_flat_b9c7b42d)
    val lineTitle = uiString(line.titleRes)
    val sentence = uiString(R.string.l10n_mind_section_1_s_2_s_relationship_n_930658dc, mindStrengthWord(line.r), dir, line.n)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .semantics { contentDescription = uiString(R.string.l10n_mind_section_line_title_sentence_6d3418f9, lineTitle, sentence) },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                lineTitle,
                style = NoopType.subhead,
                color = Palette.textPrimary,
                modifier = Modifier.weight(1f),
            )
            Text(
                String.format(Locale.US, "r = %+.2f", line.r),
                style = NoopType.captionNumber,
                color = Palette.textSecondary,
            )
        }
        Text(sentence, style = NoopType.footnote, color = Palette.textTertiary)
    }
}

// MARK: - Correlation math

/** A computed mood ↔ outcome line. */
private data class MindLine(@androidx.annotation.StringRes val titleRes: Int, val r: Double, val n: Int)

/**
 * Up to three mood ↔ body lines over the cached daily metrics: HRV, recovery and
 * total sleep. A line only appears when Pearson r is computable over the day-aligned
 * pairs (≥3 overlapping days with variance) — no fabricated values.
 */
private fun buildMindCorrelations(
    days: List<DailyMetric>,
    mood: List<Pair<String, Double>>,
): List<MindLine> {
    fun line(@androidx.annotation.StringRes titleRes: Int, series: List<Pair<String, Double>>): MindLine? {
        // Same gates as the Swift MindSection: n ≥ minPairs and |r| ≥ 0.3, strongest first, three lines.
        val c = CorrelationEngine.pearson(CorrelationEngine.alignByDay(series, mood)) ?: return null
        if (c.n < MIND_GATE_DAYS || abs(c.r) < MIND_MIN_ABS_R) return null
        return MindLine(titleRes, c.r, c.n)
    }
    return listOfNotNull(
        line(R.string.l10n_mind_section_mood_hrv_c115b673, days.mapNotNull { d -> d.avgHrv?.let { d.day to it } }),
        line(R.string.l10n_mind_section_mood_recovery_eebeb0f2, days.mapNotNull { d -> d.recovery?.let { d.day to it } }),
        line(R.string.l10n_mind_section_mood_sleep_duration_f373ab00, days.mapNotNull { d -> d.totalSleepMin?.let { d.day to it } }),
    ).sortedByDescending { abs(it.r) }.take(3)
}

private fun mindStrengthWord(r: Double): String {
    val m = abs(r)
    return when {
        m < 0.1 -> uiString(R.string.l10n_mind_section_no_816c52fd)
        m < 0.3 -> uiString(R.string.l10n_mind_section_a_weak_750ab75b)
        m < 0.5 -> uiString(R.string.l10n_mind_section_a_moderate_d2212fac)
        m < 0.7 -> uiString(R.string.l10n_mind_section_a_strong_8c0ccb3c)
        else -> uiString(R.string.l10n_mind_section_a_very_strong_b796e265)
    }
}

