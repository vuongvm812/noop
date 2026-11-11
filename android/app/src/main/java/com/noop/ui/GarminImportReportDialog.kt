package com.noop.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.noop.R
import com.noop.data.GarminImportReport
import com.noop.ingest.GarminExportFit
import com.noop.ingest.GarminExportJson

/** The localized label of an import-report category id. Swift twin: `GarminImportReportText.category`. */
@StringRes
internal fun garminCategoryRes(id: String): Int = when (id) {
    GarminExportJson.Category.DAILY -> R.string.garmin_cat_daily_summary
    GarminExportJson.Category.SLEEP -> R.string.garmin_cat_sleep
    GarminExportJson.Category.HEALTH_STATUS -> R.string.garmin_cat_health_status
    GarminExportJson.Category.BODY -> R.string.garmin_cat_body
    GarminExportJson.Category.FITNESS -> R.string.garmin_cat_fitness
    GarminExportJson.Category.RACE -> R.string.garmin_cat_race_predictions
    GarminExportJson.Category.RECORDS -> R.string.garmin_cat_personal_records
    GarminExportJson.Category.HYDRATION -> R.string.garmin_cat_hydration
    GarminExportJson.Category.ACTIVITIES -> R.string.garmin_cat_activities
    else -> R.string.garmin_cat_settings
}

/** The localized reason of an import-report skip id. Swift twin: `GarminImportReportText.skip`. */
@StringRes
internal fun garminSkipRes(id: String): Int = when (id) {
    GarminExportJson.Skip.PRIVATE_FILE -> R.string.garmin_skip_private_undecodable
    GarminExportJson.Skip.ACCOUNT -> R.string.garmin_skip_account_data
    GarminExportJson.Skip.NO_VALUES -> R.string.garmin_skip_no_values
    else -> R.string.garmin_skip_unrecognised
}

/** The localized label of a FIT report key (`GarminExportFit.KEY_*` / `SKIP_*`). Swift twin: `GarminImportReportText.fit`. */
@StringRes
internal fun garminFitRes(key: String): Int = when (key) {
    GarminExportFit.KEY_WORKOUTS -> R.string.garmin_fit_workouts
    GarminExportFit.KEY_ACTIVITY -> R.string.garmin_fit_activity
    GarminExportFit.KEY_MONITORING -> R.string.garmin_fit_monitoring
    GarminExportFit.KEY_METRICS -> R.string.garmin_fit_metrics
    GarminExportFit.KEY_SLEEP -> R.string.garmin_fit_sleep
    GarminExportFit.KEY_HRV_STATUS -> R.string.garmin_fit_hrv_status
    GarminExportFit.KEY_OTHER -> R.string.garmin_fit_other
    GarminExportFit.SKIP_INVALID_DATA -> R.string.garmin_fit_skip_invalid_data
    GarminExportFit.SKIP_PRIVATE_BACKUP -> R.string.garmin_fit_skip_private_backup
    GarminExportFit.SKIP_UNSUPPORTED_TYPE -> R.string.garmin_fit_skip_unsupported_type
    GarminExportFit.SKIP_NOT_FIT -> R.string.garmin_fit_skip_not_fit
    GarminExportFit.SKIP_NO_USABLE_ACTIVITY -> R.string.garmin_fit_skip_no_usable_activity
    else -> R.string.garmin_fit_skip_failed
}

/** True when the export's max heart rate differs from the profile's (0 = automatic), so it is offered. */
internal fun garminOffersMaxHr(report: GarminImportReport, profileMaxHr: Int): Boolean =
    report.maxHr != null && report.maxHr != profileMaxHr

/**
 * What the Garmin export brought in and left out, and, when its max heart rate differs from the profile's, an
 * offer to adopt it. Nothing changes unless [onUseGarmin] is tapped. Swift twin: `GarminImportReportView`.
 */
@Composable
internal fun GarminImportReportDialog(
    report: GarminImportReport,
    profileMaxHr: Int,
    onUseGarmin: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val offer = garminOffersMaxHr(report, profileMaxHr)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.surfaceOverlay,
        title = { Text(uiString(R.string.garmin_report_title), style = NoopType.title2, color = Palette.textPrimary) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (report.imported.isNotEmpty()) {
                    Text(uiString(R.string.garmin_report_imported_header), style = NoopType.subhead, color = Palette.textPrimary)
                    for ((id, n) in report.imported) {
                        Text(uiString(R.string.garmin_report_line, uiString(garminCategoryRes(id)), n), style = NoopType.subhead, color = Palette.textSecondary)
                    }
                }
                val fitStored = report.fit.filterKeys { !it.startsWith("skipped.") }.toSortedMap()
                val fitSkipped = report.fit.filterKeys { it.startsWith("skipped.") }.toSortedMap()
                for ((key, n) in fitStored) {
                    Text(uiString(R.string.garmin_report_line, uiString(garminFitRes(key)), n), style = NoopType.subhead, color = Palette.textSecondary)
                }
                if (report.skipped.isNotEmpty() || fitSkipped.isNotEmpty()) {
                    Text(uiString(R.string.garmin_report_skipped_header), style = NoopType.subhead, color = Palette.textPrimary)
                    for ((id, n) in report.skipped) {
                        Text(uiString(R.string.garmin_report_line, uiString(garminSkipRes(id)), n), style = NoopType.subhead, color = Palette.textSecondary)
                    }
                    for ((key, n) in fitSkipped) {
                        Text(uiString(R.string.garmin_report_line, uiString(garminFitRes(key)), n), style = NoopType.subhead, color = Palette.textSecondary)
                    }
                }
                report.restingHr?.let { Text(uiString(R.string.garmin_offer_resting, it), style = NoopType.subhead, color = Palette.textSecondary) }
                report.ftpW?.let { Text(uiString(R.string.garmin_offer_ftp, it), style = NoopType.subhead, color = Palette.textSecondary) }
                if (offer) {
                    val garmin = report.maxHr ?: 0
                    Text(
                        if (profileMaxHr > 0) uiString(R.string.garmin_offer_max_hr_set, garmin, profileMaxHr)
                        else uiString(R.string.garmin_offer_max_hr_auto, garmin),
                        style = NoopType.subhead, color = Palette.textPrimary,
                    )
                    Text(uiString(R.string.garmin_offer_note), style = NoopType.caption, color = Palette.textTertiary)
                }
            }
        },
        confirmButton = {
            if (offer) {
                TextButton(onClick = { onUseGarmin(report.maxHr ?: 0) }) {
                    Text(uiString(R.string.garmin_offer_use), style = NoopType.body, color = Palette.accent)
                }
            } else {
                TextButton(onClick = onDismiss) { Text(uiString(R.string.garmin_report_ok), style = NoopType.body, color = Palette.accent) }
            }
        },
        dismissButton = if (offer) {
            { TextButton(onClick = onDismiss) { Text(uiString(R.string.garmin_offer_keep), style = NoopType.body, color = Palette.textSecondary) } }
        } else null,
    )
}
