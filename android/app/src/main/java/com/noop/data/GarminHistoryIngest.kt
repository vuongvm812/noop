package com.noop.data

import android.content.SharedPreferences
import com.noop.analytics.RouteMath
import com.noop.garmin.GarminDownloadedFile
import com.noop.garmin.GarminFitFileType
import com.noop.garmin.GarminSyncFileKey
import com.noop.ingest.ActivityFileImporter
import com.noop.ingest.GarminWatchIngest
import com.noop.ingest.GarminWellnessParser
import java.util.TimeZone

/**
 * Stores what Garmin history sync downloads ([com.noop.ble.GarminSource] hands each checked file here) and
 * answers which files are already stored. Nothing here talks to the watch: the `garminSyncedFile` ledger
 * is the only record of "already downloaded", which is what lets NOOP leave every file on the watch
 * untouched. Kotlin twin of Strand/Data/GarminHistoryIngest.swift.
 *
 * - Wellness files are decoded by [GarminWellnessParser], folded by [GarminWatchIngest], and written with
 *   their ledger row in ONE transaction ([GarminSyncStore.ingest]).
 * - ACTIVITY files go through the existing workout parse ([ActivityFileImporter.parse]), stored under the
 *   watch's own device id, then recorded. A GPS route is kept as the row's `routePolyline`, as the
 *   manual workout-file import does, so the workout detail draws its map.
 */
object GarminHistoryIngest {

    suspend fun syncedKeys(store: GarminSyncStore, deviceId: String): Set<GarminSyncFileKey> =
        store.syncedFiles(deviceId).map {
            GarminSyncFileKey(it.fileIndex, it.dataType, it.subType, it.fileNumber, it.ts, it.size)
        }.toSet()

    /**
     * Once per watch, repair the nights an older fold stored: forget the stored SLEEP files so the next
     * sync re-folds them over the watch's own sleep window (#47) and rewrites the recent day rollups from
     * their main night (#49), and divide by 100 every stored efficiency above 1, the percent the older
     * fold wrote (#49; this part also reaches nights older than the sync lookback). Nothing on the watch
     * changes. Key version 2, so a watch repaired for #47 alone runs it once more.
     * Swift twin: `GarminHistoryIngest.refetchSleepFilesOnce`.
     */
    suspend fun refetchSleepFilesOnce(store: GarminSyncStore, deviceId: String, prefs: SharedPreferences, log: (String) -> Unit) {
        val key = "noop.garmin.sleepRefold.v2.$deviceId"
        if (prefs.getBoolean(key, false)) return
        try {
            val rescaled = store.repairEfficiencyUnits(deviceId)
            val forgotten = store.forgetFiles(deviceId, 255, GarminFitFileType.SLEEP.raw)
            prefs.edit().putBoolean(key, true).apply()
            log("Garmin: re-folding stored nights; $forgotten sleep files will download again, " +
                "$rescaled efficiency values rescaled from percent")
        } catch (e: Exception) {
            log("Garmin: re-fold of stored nights postponed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * Once per watch, re-derive every stored day's SpO2 and respiration as the overnight mean over its
     * stored night (#52). Older ingests stored a midnight-to-midnight mean of every sample. The day spans
     * use the phone's current UTC offset, as the fold does for a file without one.
     * Swift twin: `GarminHistoryIngest.rederiveNightVitalsOnce`.
     */
    suspend fun rederiveNightVitalsOnce(
        store: GarminSyncStore,
        deviceId: String,
        prefs: SharedPreferences,
        log: (String) -> Unit,
        utcOffsetS: Int = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000,
    ) {
        val key = "noop.garmin.nightVitals.v1.$deviceId"
        if (prefs.getBoolean(key, false)) return
        try {
            val days = store.days(deviceId).map { day ->
                val start = GarminWatchIngest.dayStartUnix(day, utcOffsetS)
                GarminDayRollup(day = day, dayStartTs = start, dayEndTs = start + 86_400)
            }
            val changed = store.rederiveNightVitals(deviceId, days)
            prefs.edit().putBoolean(key, true).apply()
            log("Garmin: re-derived overnight SpO2 / respiration for ${days.size} days ($changed points)")
        } catch (e: Exception) {
            log("Garmin: overnight vitals re-derive postponed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    fun ledgerRow(key: GarminSyncFileKey) =
        GarminSyncedFileRow(key.fileIndex, key.dataType, key.subType, key.fileNumber, key.garminTimestamp, key.size)

    /** Decode and store one file. True when stored and recorded; false leaves it "new" for the next sync. */
    suspend fun ingest(
        file: GarminDownloadedFile,
        deviceId: String,
        store: GarminSyncStore,
        repo: WhoopRepository,
        log: (String) -> Unit,
    ): Boolean {
        val now = System.currentTimeMillis() / 1000
        val row = ledgerRow(file.key)
        return try {
            if (file.fitType == GarminFitFileType.ACTIVITY) {
                ingestActivity(file, deviceId, repo, log)
                store.record(row, deviceId, now)
                return true
            }
            val samples = GarminWellnessParser.parse(file.bytes)
            val batch = GarminWatchIngest.fold(samples, row, TimeZone.getDefault().getOffset(now * 1000) / 1000)
            val c = store.ingest(batch, deviceId, now)
            log("Garmin: stored ${file.fitType?.wireName ?: "file"} ${file.key.label}: hr=${c.hr} metrics=${c.deviceMetric} " +
                "days=${c.days} sleeps=${c.sleeps} points=${c.metricPoints}" +
                if (samples.complete) "" else " (file ended early; kept what decoded)")
            true
        } catch (e: Exception) {
            log("Garmin: storing ${file.key.label} failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /** The manual workout-file import's mapping, under the watch's device id (Swift twin: `ingestActivity`). */
    private suspend fun ingestActivity(file: GarminDownloadedFile, deviceId: String, repo: WhoopRepository, log: (String) -> Unit) {
        val activity = ActivityFileImporter.parse(file.bytes, "garmin-activity.fit").activity
        val duration = activity?.durationS
        if (activity == null || duration == null || duration <= 0) {
            log("Garmin: activity ${file.key.label} has no usable workout; recorded as synced")
            return
        }
        val row = workoutRow(activity, deviceId)
        repo.upsertWorkouts(listOf(row))
        if (activity.hrSamples.isNotEmpty()) {
            repo.insertHr(activity.hrSamples.map { HrSample(deviceId = deviceId, ts = it.ts, bpm = it.bpm) })
        }
        // The watch's Training Effect and training load, read back by the workout detail.
        val reference = GarminWatchIngest.activityReference(activity)
        if (reference.isNotEmpty()) repo.insert(StreamBatch(deviceMetric = reference), deviceId)
        log("Garmin: stored workout ${row.sport} ${row.startTs}-${row.endTs} hr=${activity.hrSamples.size} " +
            "route=${if (row.routePolyline == null) 0 else activity.route.size} from ${file.key.label}")
    }

    /**
     * The workout row for a decoded watch activity. `routePolyline` is the precision-5 encoding of the
     * route when it has at least two points (Swift twin: `workoutRoute(from:)`, stored in `RouteStore`).
     */
    internal fun workoutRow(activity: ActivityFileImporter.Activity, deviceId: String) = WorkoutRow(
        deviceId = deviceId,
        startTs = activity.startTs,
        endTs = activity.endTs,
        sport = ActivityFileImporter.workoutSport(activity.sport),
        source = deviceId,
        durationS = activity.durationS,
        energyKcal = activity.energyKcal,
        avgHr = activity.avgHr,
        maxHr = activity.maxHr,
        strain = null,                       // never a fabricated cardiovascular strain
        distanceM = activity.distanceM,
        zonesJSON = null,
        notes = activity.importNote(),
        routePolyline = activity.route.takeIf { it.size >= 2 }
            ?.let { RouteMath.encode(it.map { p -> RouteMath.LatLng(p.lat, p.lon) }) },
        steps = activity.steps,
    )
}
