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
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.analytics.RankedEffect
import com.noop.analytics.WhatMovesYouHighlights
import com.noop.data.DailyMetric

// MARK: - What affects your HRV and sleep (Today card)
//
// The top three significant effects on HRV and Rest from the What Moves You ranking, journal habits and
// automatic factors alike ("Late workout → HRV −8% next morning"), each with its confidence tier. Reads the
// SAME AppViewModel.whatMovesYou snapshot the Insights hub shows, so the two cannot disagree; tapping opens
// the hub. Twin of iOS/macOS `WhatAffectsTodayCard` (Strand/Screens/WhatAffectsTodayCard.swift).

/** The Today card's rows: the strongest significant HRV and Rest effects from the shared ranking. */
internal fun whatAffectsHighlights(snap: AppViewModel.WhatMovesYouSnapshot): List<RankedEffect> {
    val outcomes = listOf(InsightsOutcome.Hrv.outcomeName, InsightsOutcome.Sleep.outcomeName)
    return WhatMovesYouHighlights.top(snap.ranked, outcomes, WhatMovesYouHighlights.limit())
}

@Composable
fun WhatAffectsTodayCard(
    viewModel: AppViewModel,
    days: List<DailyMetric>,
    onOpenInsightsHub: () -> Unit,
) {
    val snap by viewModel.whatMovesYou.collectAsStateWithLifecycle()
    val journalVersion by viewModel.journalVersion.collectAsStateWithLifecycle()
    // Reload whenever Today's data refreshes or a journal answer changes; skipped while the snapshot already
    // reflects both.
    LaunchedEffect(days, journalVersion) { viewModel.loadWhatMovesYou() }

    val openLabel = uiString(R.string.what_affects_open_what_moves_you)
    NoopCard(
        tint = Palette.accent,
        modifier = Modifier.clickable(onClickLabel = openLabel) { onOpenInsightsHub() },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space10)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Insights,
                    contentDescription = null,
                    tint = Palette.accent,
                    modifier = Modifier.size(Metrics.space18),
                )
                Spacer(Modifier.width(Metrics.space8))
                Text(
                    uiString(R.string.what_affects_title),
                    style = NoopType.headline,
                    color = Palette.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = Palette.textTertiary,
                    modifier = Modifier.size(Metrics.space18),
                )
            }
            val s = snap
            if (s == null) {
                Text(
                    uiString(R.string.l10n_insights_hub_screen_reading_your_journal_and_outcomes_4a59af69),
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                )
            } else {
                val rows = whatAffectsHighlights(s)
                if (rows.isEmpty()) {
                    Text(
                        if (s.sleepOutcomeDays < WhatMovesYouHighlights.minHistoryDays()) uiString(R.string.what_affects_needs_two_weeks)
                        else uiString(R.string.what_affects_no_patterns),
                        style = NoopType.footnote,
                        color = Palette.textSecondary,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                        for (r in rows) {
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    InsightsSentences.todayLine(r),
                                    style = NoopType.subhead,
                                    color = Palette.textPrimary,
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(Modifier.width(Metrics.space8))
                                ConfidencePill(r.confidence)
                            }
                        }
                    }
                }
            }
        }
    }
}
