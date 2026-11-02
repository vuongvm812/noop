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
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.noop.R
import com.noop.data.DailyMetric
import com.noop.data.JournalEntry
import kotlinx.coroutines.launch

/**
 * SupplementsTodayCard — logs today's supplement intake in place on Today (#33).
 *
 * The rows are the Journal catalog's Supplements-group questions ([supplementJournalItems]) and the
 * answers are ordinary native journal entries under [JOURNAL_DEVICE_ID], written with the same
 * repository calls the Journal uses, so What Moves You and the correlations read them unchanged. The
 * Journal log card leaves the Supplements group out while answering; supplement questions are still
 * added and edited in the Journal's edit mode. Today only (the call site gates `selectedDayOffset == 0`).
 * Twin of macOS `SupplementsTodayCard` (Strand/Screens/SupplementsTodayCard.swift).
 */
@Composable
fun SupplementsTodayCard(
    viewModel: AppViewModel,
    days: List<DailyMetric>,
    onOpenJournal: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val todayKey = journalDayKey(0L)

    // null = still loading → render nothing (no empty-state flash).
    var items by remember { mutableStateOf<List<JournalCatalogItem>?>(null) }
    var answers by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var numeric by remember { mutableStateOf<Map<String, Double>>(emptyMap()) }
    // Bumped after every write so the answers re-read.
    var writeSeq by remember { mutableIntStateOf(0) }

    // Re-read when Today's data refreshes (a resume/sync bumps `days`), after a write, or on a new day.
    // The catalog is re-read too, so edits made in the Journal show up when Today comes back.
    LaunchedEffect(days, writeSeq, todayKey) {
        val rows = runCatching {
            viewModel.repo.journal(JOURNAL_DEVICE_ID, todayKey, todayKey)
        }.getOrDefault(emptyList())
        answers = rows.associate { it.question to it.answeredYes }
        numeric = rows.mapNotNull { e -> e.numericValue?.let { e.question to it } }.toMap()
        items = supplementJournalItems(loadJournalCatalogItems(context))
    }

    fun write(op: suspend () -> Unit) {
        scope.launch {
            op()
            writeSeq++
        }
    }

    val rows = items ?: return

    NoopCard(tint = Palette.restColor) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Medication,
                    contentDescription = null,
                    tint = Palette.restColor,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    uiString(R.string.today_section_supplements),
                    style = NoopType.headline,
                    color = Palette.textPrimary,
                )
            }
            if (rows.isEmpty()) {
                Text(
                    uiString(R.string.supplements_today_empty),
                    style = NoopType.footnote,
                    color = Palette.textSecondary,
                )
                Text(
                    uiString(R.string.supplements_today_add_link),
                    style = NoopType.footnote,
                    color = Palette.accent,
                    modifier = Modifier.clickable { onOpenJournal() },
                )
            } else {
                rows.forEach { item ->
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            item.display,   // display = rename ?? canonical; data, not a UI literal
                            style = NoopType.body,
                            color = Palette.textPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        if (item.kind.isNumeric) {
                            JournalNumericField(
                                item = item,
                                value = numeric[item.canonical],
                                onCommit = { value ->
                                    // A numeric log writes answeredYes=true AND the value (#322), the same
                                    // row the Journal writes.
                                    write {
                                        viewModel.repo.upsertJournal(
                                            listOf(JournalEntry(JOURNAL_DEVICE_ID, todayKey, item.canonical,
                                                answeredYes = true, numericValue = value)),
                                        )
                                    }
                                },
                                onClear = {
                                    write { viewModel.repo.deleteJournalEntry(JOURNAL_DEVICE_ID, todayKey, item.canonical) }
                                },
                            )
                        } else {
                            JournalAnswerChips(answer = answers[item.canonical]) { answer ->
                                write {
                                    if (answer == null) {
                                        viewModel.repo.deleteJournalEntry(JOURNAL_DEVICE_ID, todayKey, item.canonical)
                                    } else {
                                        viewModel.repo.upsertJournal(
                                            listOf(JournalEntry(JOURNAL_DEVICE_ID, todayKey, item.canonical, answer)),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
