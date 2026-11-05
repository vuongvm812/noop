package com.noop.ingest

import com.noop.ui.failure
import com.noop.ui.uiPlural

import com.noop.ui.uiString

import com.noop.R

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import com.noop.data.HuaweiHistoryIngest
import com.noop.data.HuaweiIngestCounts
import com.noop.data.ImportSummary
import com.noop.data.WhoopDatabase
import java.io.File
import java.io.InputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipInputStream

/**
 * Imports the Huawei band history held in a **Gadgetbridge** "Export database" file (or the zip export that
 * contains it) — fully offline, no Bluetooth, no Huawei account. Modelled on [XiaomiBandImporter].
 *
 * Table and column meanings come from the public schema of a real export (Gadgetbridge is AGPL-3.0; schema
 * facts only, no code copied). The rows are mapped by the pure [GadgetbridgeMapping] into the same
 * [HuaweiWatchIngest.Batch] a live band sync produces and stored through [HuaweiHistoryIngest.store] under
 * [DEVICE_ID], in chunks of a few local days. Read from the export: per-minute activity, sleep statistics
 * and per-minute stages, stress, workouts with their heart-rate rows. Left out: emotions, apnea, battery,
 * alarms, and the HRV / temperature tables. Known gap: the nightly average SpO2 and breath rate have no store
 * path (night vitals come from per-minute SpO2 through the shared night-vitals resolver).
 * Several Huawei devices all land under [DEVICE_ID] (as specified); two devices with samples in the same
 * minute collapse onto one row. Optional columns missing from an older Gadgetbridge schema read as not measured.
 * The file is opened READ-ONLY on a cache copy that is deleted afterwards. Kotlin-only; Swift twin deferred.
 */
object GadgetbridgeImporter {

    const val SOURCE_LABEL = "Gadgetbridge"
    const val DEVICE_ID = "huawei-import"

    /** Zip-bomb guard for the database entry (and the bare file copy). */
    internal const val MAX_ENTRY_BYTES = 512L shl 20

    private val DAY_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1)

    suspend fun importExport(
        context: Context,
        uri: Uri,
        db: WhoopDatabase = WhoopDatabase.get(context),
        zone: ZoneId = ZoneId.systemDefault(),
        nowTs: Long = System.currentTimeMillis() / 1000,
        onStored: () -> Unit = {},
    ): ImportSummary {
        val work = File(context.cacheDir, "gadgetbridge-${System.nanoTime()}").apply { mkdirs() }
        try {
            val dbFile = try {
                materialize(context, uri, work)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                return ImportSummary.failure(SOURCE_LABEL, e.message ?: "Couldn't read the export.") { e.message ?: uiString(R.string.l10n_gadgetbridge_importer_couldn_t_read_the_export_a6a3eadd) }
            } ?: return ImportSummary.failure(
                SOURCE_LABEL, "No Gadgetbridge database found. Use Gadgetbridge's \"Export database\" file.") { uiString(R.string.l10n_gadgetbridge_importer_no_gadgetbridge_database_found_use_gadgetbridge_d3b98e59) }
            return try {
                SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { src ->
                    importFrom(src, db, zone, nowTs, onStored)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                ImportSummary.failure(SOURCE_LABEL, "Import failed: ${e.message}") { uiString(R.string.l10n_gadgetbridge_importer_import_failed_1_s_e32168fa, e.message.toString()) }
            }
        } finally {
            work.deleteRecursively()
        }
    }

    /** Copy the picked content into [work] (extracting the `Gadgetbridge` entry of a zip); null if not found. */
    private fun materialize(context: Context, uri: Uri, work: File): File? {
        val resolver = context.contentResolver
        val head = ByteArray(16)
        val read = resolver.openInputStream(uri)?.use { it.read(head) }
            ?: throw IllegalStateException("Couldn't open the selected file.")
        val out = File(work, "gadgetbridge.db")
        if (read >= 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) {
            var found = false
            resolver.openInputStream(uri)?.use { raw ->
                ZipInputStream(raw).use { zin ->
                    var entry = zin.nextEntry
                    while (entry != null) {
                        val base = entry.name.substringAfterLast('/').lowercase()
                        if (!entry.isDirectory && (base == "gadgetbridge" || base == "gadgetbridge.db")) {
                            out.outputStream().use { copyBounded(zin, it) }
                            found = true
                            break
                        }
                        entry = zin.nextEntry
                    }
                }
            }
            return if (found && hasSqliteHeader(out)) out else null
        }
        if (read < SQLITE_MAGIC.size || !head.copyOf(SQLITE_MAGIC.size).contentEquals(SQLITE_MAGIC)) return null
        resolver.openInputStream(uri)?.use { input -> out.outputStream().use { copyBounded(input, it) } }
        return out
    }

    private fun hasSqliteHeader(f: File): Boolean = f.inputStream().use { s ->
        val h = ByteArray(SQLITE_MAGIC.size)
        s.read(h) == h.size && h.contentEquals(SQLITE_MAGIC)
    }

    private fun copyBounded(input: InputStream, out: java.io.OutputStream) {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        var n: Int
        while (input.read(buf).also { n = it } >= 0) {
            total += n
            if (total > MAX_ENTRY_BYTES) throw IllegalStateException("The database is larger than 512 MB.")
            out.write(buf, 0, n)
        }
    }

    // ------------------------------------------------------------------------
    // Read + store
    // ------------------------------------------------------------------------

    /** Import every Huawei device of an opened Gadgetbridge database. Internal so tests can pass a file. */
    internal suspend fun importFrom(
        src: SQLiteDatabase,
        db: WhoopDatabase,
        zone: ZoneId,
        nowTs: Long,
        onStored: () -> Unit,
    ): ImportSummary {
        if (!hasTable(src, "DEVICE") || !hasTable(src, "HUAWEI_ACTIVITY_SAMPLE")) {
            return ImportSummary.failure(
                SOURCE_LABEL, "This isn't a Gadgetbridge database with Huawei data (no DEVICE / HUAWEI_ACTIVITY_SAMPLE).") { uiString(R.string.l10n_gadgetbridge_importer_this_isn_t_a_gadgetbridge_database_bc3d0924) }
        }
        val devices = ArrayList<Long>()
        src.rawQuery("SELECT _id FROM DEVICE WHERE LOWER(MANUFACTURER) = 'huawei'", null).use { c ->
            while (c.moveToNext()) devices.add(long(c, 0))
        }
        if (devices.isEmpty()) return ImportSummary.failure(SOURCE_LABEL, "No Huawei device in this Gadgetbridge database.") { uiString(R.string.l10n_gadgetbridge_importer_no_huawei_device_in_this_gadgetbridge_e1e23c5f) }

        val total = HuaweiIngestCounts()
        try {
            return importDevices(src, db, zone, nowTs, devices, total)
        } finally {
            // Chunks already stored stay stored even if a later one fails: still run the analysis over them.
            if (total.any) onStored()
        }
    }

    private suspend fun importDevices(
        src: SQLiteDatabase, db: WhoopDatabase, zone: ZoneId, nowTs: Long, devices: List<Long>, total: HuaweiIngestCounts,
    ): ImportSummary {
        var firstTs = Long.MAX_VALUE
        var lastTs = Long.MIN_VALUE
        for (device in devices) {
            val (minTs, maxTs) = timeRange(src, device, nowTs) ?: continue
            firstTs = minOf(firstTs, minTs)
            lastTs = maxOf(lastTs, maxTs)
            for (chunk in GadgetbridgeMapping.chunks(minTs, maxTs, zone)) {
                val batch = readChunk(src, device, chunk, nowTs)
                val c = HuaweiHistoryIngest.store(db, DEVICE_ID, batch)
                total.hr += c.hr; total.deviceMetric += c.deviceMetric; total.sleeps += c.sleeps
                total.days += c.days; total.metricPoints += c.metricPoints; total.workouts += c.workouts
            }
        }
        if (!total.any) return ImportSummary.failure(SOURCE_LABEL, "No Huawei health rows found in this database.") { uiString(R.string.l10n_gadgetbridge_importer_no_huawei_health_rows_found_in_c7f07fa9) }
        val counts = linkedMapOf(
            "hrSample" to total.hr, "deviceMetricSample" to total.deviceMetric, "sleepSession" to total.sleeps,
            "dailyMetric" to total.days, "metricSeries" to total.metricPoints, "workout" to total.workouts,
        ).filterValues { it > 0 }
        fun day(ts: Long) = DAY_FMT.format(Instant.ofEpochSecond(ts).atZone(zone))
        return ImportSummary(
            source = SOURCE_LABEL, counts = counts, firstDay = day(firstTs), lastDay = day(lastTs),
            message = "Imported ${total.days} days, ${total.sleeps} sleeps and ${total.workouts} workouts from Gadgetbridge.",
            localized = { uiString(R.string.l10n_gadgetbridge_importer_imported_1_s_2_s_and_18c6e4a2, uiPlural(R.plurals.l10n_gadgetbridge_importer_1_d_days_8808cc6b, total.days, total.days), uiPlural(R.plurals.l10n_gadgetbridge_importer_1_d_sleeps_8dae94eb, total.sleeps, total.sleeps), uiPlural(R.plurals.l10n_gadgetbridge_importer_1_d_workouts_acbb4e56, total.workouts, total.workouts)) },
        )
    }

    /** Earliest and latest second across the device's per-minute, sleep, stress and workout rows. */
    private fun timeRange(src: SQLiteDatabase, device: Long, nowTs: Long): Pair<Long, Long>? {
        var lo = Long.MAX_VALUE
        var hi = Long.MIN_VALUE
        // Rows with an unset clock (0 / -1 bed times) or a far-future one are not a time: bound every scan below.
        fun scan(minCol: String, maxCol: String, table: String, extra: String, scale: Long) {
            val floor = HuaweiWatchIngest.EARLIEST_TS * scale
            val ceiling = (nowTs + 86_400) * scale  // the fold drops later rows anyway; one junk row must not mint years of chunks
            src.rawQuery(
                "SELECT MIN($minCol), MAX($maxCol) FROM $table WHERE DEVICE_ID = ? AND $minCol >= $floor AND $maxCol >= $floor AND $minCol <= $ceiling AND $maxCol <= $ceiling$extra",
                arrayOf(device.toString()),
            ).use { c ->
                if (c.moveToFirst() && !c.isNull(0) && !c.isNull(1)) {
                    lo = minOf(lo, long(c, 0) / scale); hi = maxOf(hi, long(c, 1) / scale)
                }
            }
        }
        scan("TIMESTAMP", "TIMESTAMP", "HUAWEI_ACTIVITY_SAMPLE", " AND SOURCE = ${GadgetbridgeMapping.ACTIVITY_SOURCE_MINUTE}", 1)
        if (hasTable(src, "HUAWEI_SLEEP_STATS_SAMPLE")) scan("BED_TIME", "WAKEUP_TIME", "HUAWEI_SLEEP_STATS_SAMPLE", "", 1000)
        if (hasTable(src, "HUAWEI_STRESS_SAMPLE")) scan("TIMESTAMP", "TIMESTAMP", "HUAWEI_STRESS_SAMPLE", "", 1000)
        if (hasTable(src, "HUAWEI_WORKOUT_SUMMARY_SAMPLE")) scan("START_TIMESTAMP", "END_TIMESTAMP", "HUAWEI_WORKOUT_SUMMARY_SAMPLE", "", 1)
        return if (lo > hi) null else lo to hi
    }

    private fun readChunk(src: SQLiteDatabase, device: Long, chunk: GadgetbridgeMapping.Chunk, nowTs: Long): HuaweiWatchIngest.Batch {
        val d = device.toString()
        val from = chunk.from
        val to = chunk.to
        val activity = ArrayList<GadgetbridgeMapping.ActivityRow>()
        val ac = columns(src, "HUAWEI_ACTIVITY_SAMPLE")
        src.rawQuery(
            "SELECT TIMESTAMP, ${sel(ac, "STEPS")}, NULL, ${sel(ac, "DISTANCE")}, ${sel(ac, "SPO")}, ${sel(ac, "HEART_RATE")}, ${sel(ac, "RESTING_HEART_RATE")} FROM HUAWEI_ACTIVITY_SAMPLE " +
                "WHERE DEVICE_ID = ? AND SOURCE = ${GadgetbridgeMapping.ACTIVITY_SOURCE_MINUTE} AND TIMESTAMP >= ? AND TIMESTAMP < ?",
            arrayOf(d, from.toString(), to.toString()),
        ).use { c ->
            while (c.moveToNext()) activity.add(GadgetbridgeMapping.ActivityRow(
                long(c, 0), long(c, 1), long(c, 2), long(c, 3), long(c, 4), long(c, 5), long(c, 6)))
        }

        val stats = ArrayList<GadgetbridgeMapping.SleepStatsRow>()
        val stages = ArrayList<GadgetbridgeMapping.StageRow>()
        if (hasTable(src, "HUAWEI_SLEEP_STATS_SAMPLE")) {
            val sc = columns(src, "HUAWEI_SLEEP_STATS_SAMPLE")
            // A night belongs to the chunk holding its wake-up time; its stages are read whole, whatever the chunk.
            src.rawQuery(
                "SELECT TIMESTAMP, ${sel(sc, "SLEEP_SCORE")}, BED_TIME, ${sel(sc, "RISING_TIME")}, WAKEUP_TIME, ${sel(sc, "AVG_HRV")}, ${sel(sc, "AVG_HEART_RATE")} " +
                    "FROM HUAWEI_SLEEP_STATS_SAMPLE WHERE DEVICE_ID = ? AND WAKEUP_TIME >= ? AND WAKEUP_TIME < ?",
                arrayOf(d, (from * 1000).toString(), (to * 1000).toString()),
            ).use { c ->
                while (c.moveToNext()) stats.add(GadgetbridgeMapping.SleepStatsRow(
                    long(c, 0), long(c, 1), long(c, 2), long(c, 3), long(c, 4), long(c, 5), long(c, 6)))
            }
            if (stats.isNotEmpty() && hasTable(src, "HUAWEI_SLEEP_STAGE_SAMPLE")) {
                src.rawQuery(
                    "SELECT TIMESTAMP, STAGE FROM HUAWEI_SLEEP_STAGE_SAMPLE WHERE DEVICE_ID = ? AND TIMESTAMP >= ? AND TIMESTAMP <= ?",
                    arrayOf(d, stats.minOf { it.bedMs }.toString(), stats.maxOf { it.wakeupMs }.toString()),
                ).use { c -> while (c.moveToNext()) stages.add(GadgetbridgeMapping.StageRow(long(c, 0), long(c, 1))) }
            }
        }

        val stress = ArrayList<GadgetbridgeMapping.StressRow>()
        if (hasTable(src, "HUAWEI_STRESS_SAMPLE")) {
            val tc = columns(src, "HUAWEI_STRESS_SAMPLE")
            src.rawQuery(
                "SELECT TIMESTAMP, STRESS, ${sel(tc, "LEVEL")}, ${sel(tc, "START_TIME")} FROM HUAWEI_STRESS_SAMPLE WHERE DEVICE_ID = ? AND TIMESTAMP >= ? AND TIMESTAMP < ?",
                arrayOf(d, (from * 1000).toString(), (to * 1000).toString()),
            ).use { c ->
                while (c.moveToNext()) stress.add(GadgetbridgeMapping.StressRow(long(c, 0), long(c, 1), long(c, 2), long(c, 3)))
            }
        }

        val summaries = ArrayList<GadgetbridgeMapping.WorkoutSummaryRow>()
        val workoutHr = ArrayList<GadgetbridgeMapping.WorkoutHrRow>()
        if (hasTable(src, "HUAWEI_WORKOUT_SUMMARY_SAMPLE")) {
            val wc = columns(src, "HUAWEI_WORKOUT_SUMMARY_SAMPLE")
            src.rawQuery(
                "SELECT WORKOUT_ID, START_TIMESTAMP, END_TIMESTAMP, ${sel(wc, "CALORIES")}, ${sel(wc, "DISTANCE")}, " +
                    "${sel(wc, "STEP_COUNT")}, ${sel(wc, "TOTAL_TIME")}, ${sel(wc, "DURATION")}, ${sel(wc, "TYPE")}, " +
                    "${sel(wc, "MAX_HEART_RATE_PEAK")}, ${sel(wc, "WORKOUT_LOAD")}, ${sel(wc, "ELEVATION_GAIN")} FROM HUAWEI_WORKOUT_SUMMARY_SAMPLE " +
                    "WHERE DEVICE_ID = ? AND START_TIMESTAMP >= ? AND START_TIMESTAMP < ?",
                arrayOf(d, from.toString(), to.toString()),
            ).use { c ->
                while (c.moveToNext()) summaries.add(GadgetbridgeMapping.WorkoutSummaryRow(
                    long(c, 0), long(c, 1), long(c, 2), long(c, 3), long(c, 4), long(c, 5), long(c, 6),
                    long(c, 7), long(c, 8), long(c, 9), long(c, 10), if (c.isNull(11)) null else c.getLong(11)))
            }
            if (summaries.isNotEmpty() && hasTable(src, "HUAWEI_WORKOUT_DATA_SAMPLE")) {
                for (w in summaries) src.rawQuery(
                    "SELECT WORKOUT_ID, TIMESTAMP, HEART_RATE FROM HUAWEI_WORKOUT_DATA_SAMPLE WHERE WORKOUT_ID = ?",
                    arrayOf(w.id.toString()),
                ).use { c: Cursor ->
                    while (c.moveToNext()) workoutHr.add(GadgetbridgeMapping.WorkoutHrRow(long(c, 0), long(c, 1), long(c, 2)))
                }
            }
        }

        return HuaweiWatchIngest.Batch(
            minutes = GadgetbridgeMapping.minutes(activity),
            nights = GadgetbridgeMapping.nights(stats, stages),
            stress = GadgetbridgeMapping.stress(stress),
            workouts = GadgetbridgeMapping.workouts(summaries, workoutHr),
            utcOffsetS = chunk.utcOffsetS,
            nowTs = nowTs,
        )
    }

    /** A NULL column is "not measured": the mapping's -1 sentinel, never a silent 0. */
    private fun long(c: Cursor, i: Int): Long = if (c.isNull(i)) -1L else c.getLong(i)

    private fun columns(db: SQLiteDatabase, table: String): Set<String> =
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            val out = HashSet<String>()
            while (c.moveToNext()) out.add(c.getString(1).uppercase())
            out
        }

    /** `col` when [table] has it, else `NULL`: an older Gadgetbridge schema lacks some later columns. */
    private fun sel(have: Set<String>, col: String): String = if (col in have) col else "NULL"

    private fun hasTable(db: SQLiteDatabase, name: String): Boolean =
        db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(name)).use { it.moveToFirst() }
}
