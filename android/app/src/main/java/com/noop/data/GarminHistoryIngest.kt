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
     * Once per watch, forget the stored SLEEP files so the next sync downloads them again and re-folds
     * their nights over the watch's own sleep window (#47). Files older than the sync lookback are not
     * listed again, so their nights keep the old fold. Swift twin: `GarminHistoryIngest.refetchSleepFilesOnce`.
     */
    suspend fun refetchSleepFilesOnce(store: GarminSyncStore, deviceId: String, prefs: SharedPreferences, log: (String) -> Unit) {
        val key = "noop.garmin.sleepRefold.v1.$deviceId"
        if (prefs.getBoolean(key, false)) return
        try {
            val forgotten = store.forgetFiles(deviceId, 255, GarminFitFileType.SLEEP.raw)
            prefs.edit().putBoolean(key, true).apply()
            log("Garmin: re-folding stored nights; $forgotten sleep files will download again")
        } catch (e: Exception) {
            log("Garmin: re-fold of stored nights postponed: ${e.javaClass.simpleName}: ${e.message}")
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
