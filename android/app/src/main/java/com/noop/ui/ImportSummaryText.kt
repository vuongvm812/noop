package com.noop.ui

import com.noop.R
import com.noop.data.ImportSummary
import com.noop.ingest.ActivityFileImporter
import java.util.Locale
import kotlin.math.roundToInt

// Display-time localization for import results. These live in the UI layer on purpose: the importers keep
// their English `message` for the strap log and the JVM tests, and Android-only presentation helpers stay
// out of the data/ingest layers that the Swift-parity ledger pairs one-to-one.

/** The user-facing summary line in the active locale; falls back to the English [ImportSummary.message]. */
fun ImportSummary.displayMessage(): String = localized?.invoke() ?: message

/** A failed/empty import carrying an English [reason] (logs, tests) and its [localized] display form. */
fun ImportSummary.Companion.failure(source: String, reason: String, localized: () -> String): ImportSummary =
    ImportSummary(source = source, counts = emptyMap(), message = reason, localized = localized)

/** Localized twin of [summaryText], resolved at display time (the English form stays for logs and tests). */
internal fun ActivityFileImporter.summaryTextLocalized(a: ActivityFileImporter.Activity): String {
    val sport = workoutSport(a.sport)
    val head = if (a.distanceM != null && a.distanceM > 0) {
        val km = a.distanceM / 1000.0
        val kmText = if (km >= 10) "${km.roundToInt()}" else String.format(Locale.US, "%.2f", km)
        uiString(R.string.l10n_activity_file_importer_imported_a_1_s_km_2_34247b82, kmText, sport)
    } else {
        uiString(R.string.l10n_activity_file_importer_imported_an_1_s_activity_9f18023d, sport)
    }
    val parts = ArrayList<String>(4)
    parts.add(head)
    if (a.gpsPointCount > 0) parts.add(uiString(R.string.l10n_activity_file_importer_1_s_gps_points_331167fc, a.gpsPointCount.toString()))
    if (a.hrSampleCount > 0) parts.add(uiString(R.string.l10n_activity_file_importer_1_s_hr_samples_cc06003b, a.hrSampleCount.toString()))
    if (a.avgHr != null) parts.add(uiString(R.string.l10n_activity_file_importer_avg_1_s_bpm_37c17556, a.avgHr.toString()))
    if (a.steps != null && a.steps > 0) parts.add(uiString(R.string.l10n_activity_file_importer_1_s_steps_e38738c4, a.steps.toString()))
    return parts.joinToString(" · ")
}
