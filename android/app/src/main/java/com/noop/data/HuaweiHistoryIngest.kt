package com.noop.data

import android.content.Context
import androidx.room.withTransaction
import com.noop.analytics.RouteMath
import com.noop.analytics.StressToday
import com.noop.huawei.HuaweiHistorySync
import com.noop.ingest.ActivityFileImporter
import com.noop.ingest.HuaweiWatchIngest

/** Rows one Huawei sync wrote (SQLite `changes()`, so an idempotent re-run counts the updates it made). */
data class HuaweiIngestCounts(
    var hr: Int = 0,
    var deviceMetric: Int = 0,
    var sleeps: Int = 0,
    var days: Int = 0,
    var metricPoints: Int = 0,
    var workouts: Int = 0,
) {
    val any: Boolean get() = hr + deviceMetric + sleeps + days + metricPoints + workouts > 0
}

/**
 * Stores one Huawei history sync: [HuaweiWatchIngest.fold] of the batch, written in ONE transaction to the
 * tables every watch shares (hrSample, deviceMetricSample, sleepSession, dailyMetric, metricSeries, workout),
 * under the band's registry id, so the registry's re-key and delete move and drop it with everything else.
 * Every write is keyed (UPSERT / ON CONFLICT), so storing the same batch twice leaves the same rows.
 *
 * The SQL follows [GarminSyncStore] statement for statement where the meaning is the same: a night replaces
 * any other unedited night of the band it overlaps; a day keeps its main (longest) night; steps, distance
 * and energy keep the larger value (the sync re-reads whole days, so a day's totals arrive complete); the
 * day's own stress average goes to [StressToday.HUAWEI_STRESS_KEY]; the overnight SpO2 is the ONE resolver
 * [GarminSyncStore.rederiveNightVitals]. Kotlin-only; Swift twin deferred (fork).
 */
object HuaweiHistoryIngest {

    /** The workout row's sport for the band's workout type code (Gadgetbridge `HuaweiWorkoutGbParser` codes). */
    fun sport(huaweiType: Int?): String = ActivityFileImporter.workoutSport(when (huaweiType) {
        1, 5, 11 -> "running"
        2, 13 -> "walking"
        3, 7 -> "cycling"
        4, 14 -> "hiking"
        6, 8 -> "swimming"
        135 -> "rowing"
        140 -> "strength_training"
        else -> null
    })

    suspend fun store(
        db: WhoopDatabase, deviceId: String, batch: HuaweiWatchIngest.Batch, log: (String) -> Unit = {},
        logLabel: String = "Huawei",
    ): HuaweiIngestCounts {
        val fold = HuaweiWatchIngest.fold(batch)
        for (w in fold.workouts) log(workoutLine(w, logLabel))
        val sql = db.openHelper.writableDatabase
        fun exec(statement: String, vararg args: Any?): Int {
            sql.execSQL(statement, args)
            return sql.query("SELECT changes()").use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        }
        val counts = HuaweiIngestCounts()
        db.withTransaction {
            val hr = fold.hr + fold.workouts.flatMap { w -> w.hrSamples.map { HrRow(it.ts, it.bpm) } }
            for (s in hr.distinctBy { it.ts }) {
                counts.hr += exec(
                    "INSERT INTO hrSample (deviceId, ts, bpm, synced) VALUES (?, ?, ?, 0) ON CONFLICT(deviceId, ts) DO NOTHING",
                    deviceId, s.ts, s.bpm,
                )
            }
            val pending = fold.pendingMetric.mapNotNull { p ->
                DeviceMetricKind.entries.firstOrNull { it.wire == p.kindWire }?.let { DeviceMetricRow(p.ts, it, p.value, p.source) }
            }
            for (s in fold.deviceMetric + pending) {
                if (!s.value.isFinite()) continue
                counts.deviceMetric += exec(
                    "INSERT INTO deviceMetricSample (deviceId, ts, kind, value, source) VALUES (?, ?, ?, ?, ?) " +
                        "ON CONFLICT(deviceId, kind, ts) DO UPDATE SET value = excluded.value, source = excluded.source",
                    deviceId, s.ts, s.kind.wire, s.value, s.source,
                )
            }
            for (s in fold.sleeps) {
                exec(
                    "DELETE FROM sleepSession WHERE deviceId = ? AND startTs <> ? AND startTs < ? AND endTs > ? AND userEdited = 0",
                    deviceId, s.startTs, s.endTs, s.startTs,
                )
                counts.sleeps += exec(
                    "INSERT INTO sleepSession (deviceId, startTs, endTs, efficiency, restingHr, avgHrv, stagesJSON, " +
                        "userEdited, startTsAdjusted, stagingSparse) VALUES (?, ?, ?, ?, ?, ?, ?, 0, NULL, NULL) " +
                        "ON CONFLICT(deviceId, startTs) DO UPDATE SET " +
                        "endTs = CASE WHEN sleepSession.userEdited THEN sleepSession.endTs ELSE excluded.endTs END, " +
                        "efficiency = COALESCE(excluded.efficiency, sleepSession.efficiency), " +
                        "restingHr = COALESCE(excluded.restingHr, sleepSession.restingHr), " +
                        "avgHrv = COALESCE(excluded.avgHrv, sleepSession.avgHrv), " +
                        "stagesJSON = CASE WHEN sleepSession.userEdited THEN sleepSession.stagesJSON " +
                        "ELSE COALESCE(excluded.stagesJSON, sleepSession.stagesJSON) END",
                    deviceId, s.startTs, s.endTs, s.efficiency, s.restingHr, s.avgHrv, s.stagesJSON,
                )
            }
            for (d in fold.days) mergeDay(d, deviceId, counts, ::exec) { q, a ->
                sql.query(q, a).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getDouble(0) else null }
            }
            // After the nights and days: the overnight SpO2 reads the stored nights.
            counts.metricPoints += GarminSyncStore(db).rederiveNightVitals(deviceId, fold.days + fold.nightRefreshDays)
            val rows = fold.workouts.map { workoutRow(it, deviceId) }
            if (rows.isNotEmpty()) {
                db.whoopDao().upsertWorkouts(rows)
                counts.workouts += rows.size
            }
        }
        return counts
    }

    /** One day onto `dailyMetric` and its `metricSeries` points. */
    private fun mergeDay(
        rollup: GarminDayRollup,
        deviceId: String,
        counts: HuaweiIngestCounts,
        exec: (String, Array<out Any?>) -> Int,
        scalar: (String, Array<Any?>) -> Double?,
    ) {
        val incoming = rollup.totalSleepMin
        val stored = if (incoming == null) null else scalar(
            "SELECT totalSleepMin FROM dailyMetric WHERE deviceId = ? AND day = ?", arrayOf(deviceId, rollup.day),
        )
        // A shorter night (a nap) leaves the day's stored sleep figures alone; an equal one rewrites them.
        val d = if (incoming != null && stored != null && stored > incoming) {
            rollup.copy(totalSleepMin = null, deepMin = null, lightMin = null, remMin = null, awakeMin = null,
                efficiency = null, sleepScore = null)
        } else rollup
        val stress = scalar(
            "SELECT AVG(value) FROM deviceMetricSample WHERE deviceId = ? AND kind = ? AND ts >= ? AND ts < ?",
            arrayOf(deviceId, DeviceMetricKind.STRESS.wire, d.dayStartTs, d.dayEndTs),
        )
        counts.days += exec(
            "INSERT INTO dailyMetric (deviceId, day, totalSleepMin, efficiency, deepMin, remMin, lightMin, " +
                "restingHr, avgHrv, steps, activeKcalEst) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT(deviceId, day) DO UPDATE SET " +
                "totalSleepMin = COALESCE(excluded.totalSleepMin, dailyMetric.totalSleepMin), " +
                "efficiency = COALESCE(excluded.efficiency, dailyMetric.efficiency), " +
                "deepMin = COALESCE(excluded.deepMin, dailyMetric.deepMin), " +
                "remMin = COALESCE(excluded.remMin, dailyMetric.remMin), " +
                "lightMin = COALESCE(excluded.lightMin, dailyMetric.lightMin), " +
                "restingHr = COALESCE(excluded.restingHr, dailyMetric.restingHr), " +
                "avgHrv = COALESCE(excluded.avgHrv, dailyMetric.avgHrv), " +
                "steps = CASE WHEN excluded.steps IS NULL THEN dailyMetric.steps " +
                "WHEN dailyMetric.steps IS NULL THEN excluded.steps ELSE MAX(excluded.steps, dailyMetric.steps) END, " +
                "activeKcalEst = CASE WHEN excluded.activeKcalEst IS NULL THEN dailyMetric.activeKcalEst " +
                "WHEN dailyMetric.activeKcalEst IS NULL THEN excluded.activeKcalEst " +
                "ELSE MAX(excluded.activeKcalEst, dailyMetric.activeKcalEst) END",
            arrayOf(deviceId, d.day, d.totalSleepMin, d.efficiency, d.deepMin, d.remMin, d.lightMin,
                // activeKcalEst is whole-day TOTAL energy on every source (#51), as the Garmin store writes it.
                d.restingHr, d.avgHrvMs, d.steps, d.totalKcal),
        )
        val points = listOf(
            "steps" to d.steps?.toDouble(), "distance_m" to d.distanceM, "total_kcal" to d.totalKcal,
            "rhr" to d.restingHr?.toDouble(), "hrv" to d.avgHrvMs, StressToday.HUAWEI_STRESS_KEY to stress,
            "sleep_total_min" to d.totalSleepMin, "sleep_deep_min" to d.deepMin, "sleep_light_min" to d.lightMin,
            "sleep_rem_min" to d.remMin, "sleep_awake_min" to d.awakeMin, "ref_sleep_score" to d.sleepScore?.toDouble(),
        )
        for ((key, value) in points) {
            if (value == null || !value.isFinite()) continue
            val update = if (key in GarminSyncStore.MAX_KEYS) "MAX(excluded.value, metricSeries.value)" else "excluded.value"
            counts.metricPoints += exec(
                "INSERT INTO metricSeries (deviceId, day, `key`, value) VALUES (?, ?, ?, ?) " +
                    "ON CONFLICT(deviceId, day, `key`) DO UPDATE SET value = $update",
                arrayOf(deviceId, d.day, key, value),
            )
        }
    }

    /**
     * The always-on strap-log line for one workout: how many usable samples of each series the data carried
     * (present, not a sentinel, not 0). It states what was decoded, not what the band supports: a 0 can mean
     * the band does not record that series or that this sport does not use it.
     */
    internal fun workoutLine(w: HuaweiWatchIngest.WorkoutDraft, label: String = "Huawei"): String {
        val c = w.sampleCounts
        return "$label: workout ${w.startTs} type=${w.huaweiType} usable samples hr=${c.hr} cadence=${c.cadence} " +
            "altitude=${c.altitude} power=${c.power}"
    }

    /** The workout row for one band workout; `routePolyline` when the track has two points or more. */
    internal fun workoutRow(w: HuaweiWatchIngest.WorkoutDraft, deviceId: String) = WorkoutRow(
        deviceId = deviceId,
        startTs = w.startTs,
        endTs = w.endTs,
        sport = sport(w.huaweiType),
        source = deviceId,
        durationS = (w.endTs - w.startTs).toDouble(),
        energyKcal = w.energyKcal,
        avgHr = w.avgHr,
        maxHr = w.maxHr,
        strain = null,                       // never a fabricated cardiovascular strain
        distanceM = w.distanceM,
        zonesJSON = null,
        notes = null,
        routePolyline = w.route.takeIf { it.size >= 2 }?.let { RouteMath.encode(it.map { p -> RouteMath.LatLng(p.lat, p.lon) }) },
        steps = w.steps,
    )

    /**
     * The app's history-sync runner: watermarks in [HuaweiSyncPrefs], batches stored here, and [onStored]
     * (the debounced post-sync analysis, as `onGarminHistoryStored`) after a sync that wrote rows.
     */
    fun runner(context: Context, db: WhoopDatabase, onStored: () -> Unit, log: (String) -> Unit): HuaweiHistorySync =
        HuaweiHistorySync(
            cursor = HuaweiSyncPrefs.of(context),
            store = { deviceId, batch ->
                val c = store(db, deviceId, batch, log)
                log("Huawei: stored hr=${c.hr} metrics=${c.deviceMetric} sleeps=${c.sleeps} days=${c.days} " +
                    "points=${c.metricPoints} workouts=${c.workouts}")
                if (c.any) onStored()
                true
            },
            log = log,
        )
}
