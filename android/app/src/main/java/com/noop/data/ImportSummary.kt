package com.noop.data

/**
 * Result of importing an external data source (WHOOP export, Apple Health export, or
 * Health Connect) into the local Room store. Returned by every importer so the UI can
 * show one consistent "imported N days / M workouts" toast.
 */
data class ImportSummary(
    /** Human label of the source: "WHOOP", "Apple Health", "Health Connect". */
    val source: String,
    /** Rows actually upserted, keyed by table name (e.g. "dailyMetric" -> 1200). */
    val counts: Map<String, Int>,
    /** Earliest day touched, "YYYY-MM-DD" (null if nothing imported). */
    val firstDay: String? = null,
    /** Latest day touched, "YYYY-MM-DD". */
    val lastDay: String? = null,
    /** One-line human summary for a Toast / status line. */
    val message: String,
    /**
     * #1617: per-metric-column counts from the parsed daily rows, in the cross-platform label order —
     * see `com.noop.ingest.importColumnCoverage`. Empty for importers that do not produce daily
     * physiological rows, in which case the trace emits no coverage line at all rather than a row of
     * zeroes that would read as "everything is missing".
     */
    val columnCoverage: List<Pair<String, Int>> = emptyList(),
    /**
     * Rows the coverage above was counted over. Carried explicitly rather than inferred from the largest
     * count: if every column were sparse, the largest count would UNDER-report the row total and the line
     * would quietly overstate coverage.
     */
    val columnCoverageRows: Int = 0,
    /**
     * Localized rendering of [message], resolved lazily at display time. [message] stays English because
     * it is also written to the strap log and asserted by the JVM tests, which run without an Application;
     * the UI shows `com.noop.ui.displayMessage`, which falls back to [message] when no localized form was supplied.
     */
    val localized: (() -> String)? = null,
    /**
     * Per-category detail for importers that take several kinds of file (the Garmin export): workouts,
     * FIT files stored by kind (`fit.*`) and files that held nothing storable by reason (`skipped.*`).
     * Kept apart from [counts] so [totalRows] still sums table rows only. Empty for every other importer.
     */
    val categoryCounts: Map<String, Int> = emptyMap(),
    /** A Garmin export's per-category report and settings offer; null for every other importer. */
    val garminReport: GarminImportReport? = null,
) {
    val totalRows: Int get() = counts.values.sum()

    companion object {
        /** A failed/empty import carrying a reason. */
        fun failure(source: String, reason: String) =
            ImportSummary(source = source, counts = emptyMap(), message = reason)
    }
}

/**
 * What a Garmin export import brought in and left out, plus Garmin's own profile settings, which are only
 * ever OFFERED to the user, never applied silently. [imported] / [skipped] are category / reason ids from
 * `com.noop.ingest.GarminExportJson` in display order with their counts. Swift twin: `GarminExportJson.Result` (`WearableImportResult.garmin`).
 */
data class GarminImportReport(
    val imported: List<Pair<String, Int>>,
    val skipped: List<Pair<String, Int>>,
    val maxHr: Int?,
    val restingHr: Int?,
    val ftpW: Int?,
    /** The export's FIT files by kind (`workouts`, `fit.*`) and skip reason (`skipped.*`); keys of `GarminExportFit`. */
    val fit: Map<String, Int> = emptyMap(),
)
