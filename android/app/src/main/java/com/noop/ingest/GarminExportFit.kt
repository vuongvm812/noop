package com.noop.ingest

import com.noop.data.GarminHistoryIngest
import com.noop.data.GarminFileIngest
import com.noop.data.GarminSyncStore
import com.noop.data.GarminSyncedFileRow
import com.noop.data.WhoopRepository

/**
 * The FIT files inside a Garmin Connect "Export Your Data" archive (`DI-Connect-Uploaded-Files/UploadedFiles_*.zip`,
 * a zip nested in the export zip). Each file is routed by its own `file_id.type`, never by its name:
 * ACTIVITY goes through the shared workout ingest, every other health type through the watch wellness decoder
 * and fold, all stored under the export's device id. Files that decode to nothing are counted per reason.
 * Swift twin: `GarminExportFit` (StrandImport) and `GarminExportFitIngest` (Strand/Data).
 */
object GarminExportFit {

    /** The device id every export row is stored under, the same one the export's JSON uses. */
    const val DEVICE_ID = "garmin-import"

    private const val TYPE_ACTIVITY = 4
    private const val TYPE_MONITOR_A = 15
    private const val TYPE_MONITOR_DAILY = 28
    private const val TYPE_MONITORING = 32
    private const val TYPE_PRIVATE = 41
    private const val TYPE_METRICS = 44
    private const val TYPE_SLEEP = 49
    private const val TYPE_HRV_STATUS = 68
    private const val TYPE_SKIN_TEMP = 73
    private const val TYPE_SLEEP_DISRUPTION = 79

    /** FIT seconds at 1989-12-31 minus the Unix epoch. */
    private const val FIT_EPOCH_OFFSET = 631_065_600L

    /** Report keys: what was stored per FIT file kind. */
    const val KEY_WORKOUTS = "workouts"
    const val KEY_ACTIVITY = "fit.activity"
    const val KEY_MONITORING = "fit.monitoring"
    const val KEY_METRICS = "fit.metrics"
    const val KEY_SLEEP = "fit.sleep"
    const val KEY_HRV_STATUS = "fit.hrvStatus"
    const val KEY_OTHER = "fit.other"

    /** Report keys: files that held nothing NOOP can store, by reason. */
    const val SKIP_INVALID_DATA = "skipped.invalidData"
    const val SKIP_PRIVATE_BACKUP = "skipped.privateBackup"
    const val SKIP_UNSUPPORTED_TYPE = "skipped.unsupportedType"
    const val SKIP_NOT_FIT = "skipped.notFit"
    const val SKIP_NO_USABLE_ACTIVITY = "skipped.noUsableActivity"
    const val SKIP_FAILED = "skipped.failed"

    /** What a FIT import stored and skipped. [counts] is name -> files (and `workouts` -> workout rows). */
    data class Report(val counts: Map<String, Int> = emptyMap()) {
        val workouts: Int get() = counts[KEY_WORKOUTS] ?: 0
        val storedFiles: Int get() = counts.filterKeys { it.startsWith("fit.") }.values.sum()
        val skippedFiles: Int get() = counts.filterKeys { it.startsWith("skipped.") }.values.sum()
    }

    /** The `file_id` message of a FIT file: its type and creation time (Unix seconds, null when absent). */
    data class FileId(val type: Int, val timeCreatedUnix: Long?)

    /** Reads `file_id` (message 0: field 0 type, field 4 time_created). Null for anything that is not a FIT file or has none. */
    fun fileId(bytes: ByteArray): FileId? {
        val stream = FitRecordStream.open(bytes) ?: return null
        var found: FileId? = null
        // file_id is the first message of a FIT file: one definition record, then its data record.
        stream.walk(2) { def, offset, _ ->
            if (def.globalNum != 0 || found != null) return@walk
            var at = offset
            var type: Int? = null
            var created: Long? = null
            for (f in def.fields) {
                if (f.num == 0 && f.size == 1) stream.uint(at, 1, def.bigEndian)?.takeIf { it != 0xFFL }?.let { type = it.toInt() }
                if (f.num == 4 && f.size == 4) stream.uint(at, 4, def.bigEndian)?.takeIf { it != 0xFFFF_FFFFL && it > 0 }?.let { created = it + FIT_EPOCH_OFFSET }
                at += f.size
            }
            type?.let { found = FileId(it, created) }
        }
        return found
    }

    /** How a file is stored. Swift twin: `GarminExportFit.Route`. */
    sealed class Route {
        object Activity : Route()
        /** A health type the wellness decoder reads; the report key it counts under. */
        class Wellness(val key: String) : Route()
        object PrivateBackup : Route()
        object Unsupported : Route()
        object NotFit : Route()
    }

    /** Routes a FIT file by its `file_id.type`. Swift twin: `GarminExportFit.route`. */
    fun route(bytes: ByteArray): Pair<Route, FileId?> {
        val id = fileId(bytes) ?: return Route.NotFit to null
        val route = when (id.type) {
            TYPE_ACTIVITY -> Route.Activity
            TYPE_PRIVATE -> Route.PrivateBackup
            TYPE_MONITOR_A, TYPE_MONITOR_DAILY, TYPE_MONITORING -> Route.Wellness(KEY_MONITORING)
            TYPE_METRICS -> Route.Wellness(KEY_METRICS)
            TYPE_SLEEP -> Route.Wellness(KEY_SLEEP)
            TYPE_HRV_STATUS -> Route.Wellness(KEY_HRV_STATUS)
            TYPE_SKIN_TEMP, TYPE_SLEEP_DISRUPTION -> Route.Wellness(KEY_OTHER)
            else -> Route.Unsupported
        }
        return route to id
    }

    /**
     * The store batch of one wellness FIT file under a synthetic ledger row (keyed on type, creation time and
     * size, so re-importing the same file is idempotent), or null when the file decoded to nothing storable.
     * Swift twin: `GarminExportFit.wellnessBatch`.
     */
    fun wellnessBatch(bytes: ByteArray, id: FileId, utcOffsetS: Int): GarminFileIngest? {
        val samples = GarminWellnessParser.parse(bytes)
        val created = id.timeCreatedUnix?.minus(FIT_EPOCH_OFFSET) ?: 0L
        val row = GarminSyncedFileRow(0, 128, id.type, 0, created, bytes.size.toLong())
        val batch = GarminWatchIngest.fold(samples, row, utcOffsetS)
        val empty = batch.hr.isEmpty() && batch.deviceMetric.isEmpty() && batch.days.isEmpty() && batch.sleeps.isEmpty()
        return if (empty) null else batch
    }

    /** What one FIT file amounts to, before anything is stored. Swift twin: `GarminExportFit.Outcome`. */
    sealed class Outcome {
        /** An ACTIVITY file; it may still hold no usable workout. */
        object Activity : Outcome()
        /** A wellness file with something to store, and the report key it counts under. */
        class Wellness(val key: String, val batch: GarminFileIngest) : Outcome()
        /** Nothing storable, and the `skipped.*` reason. */
        class Skipped(val reason: String) : Outcome()
    }

    /** Routes and decodes one FIT file. Swift twin: `GarminExportFit.decode`. */
    fun decode(bytes: ByteArray, utcOffsetS: Int): Outcome {
        val (route, id) = route(bytes)
        return when (route) {
            Route.NotFit -> Outcome.Skipped(SKIP_NOT_FIT)
            Route.Activity -> Outcome.Activity
            Route.PrivateBackup -> Outcome.Skipped(SKIP_PRIVATE_BACKUP)
            Route.Unsupported -> Outcome.Skipped(SKIP_UNSUPPORTED_TYPE)
            is Route.Wellness -> {
                val batch = if (id == null) null else wellnessBatch(bytes, id, utcOffsetS)
                if (batch == null) Outcome.Skipped(SKIP_INVALID_DATA) else Outcome.Wellness(route.key, batch)
            }
        }
    }

    /**
     * Stores every FIT file of [files] (path -> bytes) under [DEVICE_ID]. Never throws for a bad file: it is
     * counted under a skip reason. Idempotent: workouts upsert by natural key, HR / metrics / days / sleeps by
     * their keys, and a file's ledger row is keyed on its type, creation time and size.
     * Swift twin: `GarminExportFitIngest.ingest`.
     */
    suspend fun ingest(
        files: Map<String, ByteArray>,
        repo: WhoopRepository,
        store: GarminSyncStore,
        log: (String) -> Unit,
        utcOffsetS: Int,
    ): Report {
        val counts = LinkedHashMap<String, Int>()
        val now = System.currentTimeMillis() / 1000
        if (files.isNotEmpty()) repo.upsertDevice(DEVICE_ID, name = "Garmin import")

        // Sorted by path, as the Swift ingest does, so the order-sensitive store merges agree across platforms.
        for ((path, bytes) in files.toSortedMap()) {
            var key: String
            try {
                when (val outcome = decode(bytes, utcOffsetS)) {
                    is Outcome.Skipped -> key = outcome.reason
                    Outcome.Activity -> {
                        if (GarminHistoryIngest.storeActivity(bytes, DEVICE_ID, repo, log, path)) {
                            counts[KEY_WORKOUTS] = (counts[KEY_WORKOUTS] ?: 0) + 1
                            key = KEY_ACTIVITY
                        } else {
                            key = SKIP_NO_USABLE_ACTIVITY
                        }
                    }
                    is Outcome.Wellness -> {
                        store.ingest(outcome.batch, DEVICE_ID, now)
                        key = outcome.key
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Garmin export: $path failed: ${e.javaClass.simpleName}: ${e.message}")
                key = SKIP_FAILED
            }
            counts[key] = (counts[key] ?: 0) + 1
        }
        return Report(counts)
    }
}
