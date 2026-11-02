package com.noop.data

import androidx.room.withTransaction

/**
 * Garmin history-sync persistence. Swift twin: Packages/WhoopStore/Sources/WhoopStore/GarminSyncStore.swift: the
 * same SQL, statement for statement, so a file ingested on either platform leaves the same rows.
 *
 * One downloaded watch file becomes ONE transaction: its samples, the day rollups it touches, its sleep
 * sessions, and its row in the `garminSyncedFile` ledger (the record that replaces archiving the file on
 * the watch). Either all of it lands or none does.
 *
 * A watch splits a day across many files, so writes MERGE: per-second series add up by key; the day's
 * stress / Body Battery / SpO2 / respiration aggregates are re-derived from the stored samples of that
 * local day; cumulative counters (steps, distance, calories) keep the larger value; every other column
 * keeps its stored value when the file has none (COALESCE).
 */
data class GarminSyncedFileRow(
    val fileIndex: Int,
    val dataType: Int,
    val subType: Int,
    val fileNumber: Int,
    /** Garmin-epoch seconds, as the watch's directory gives it (0 = no date). */
    val ts: Long,
    val size: Long,
)

/** What one wellness file adds to one local day. Swift twin: `GarminDayRollup`. */
data class GarminDayRollup(
    val day: String,
    val dayStartTs: Long,
    val dayEndTs: Long,
    val steps: Int? = null,
    val distanceM: Double? = null,
    val activeKcal: Double? = null,
    val totalKcal: Double? = null,
    val restingHr: Int? = null,
    val avgHrvMs: Double? = null,
    val totalSleepMin: Double? = null,
    val deepMin: Double? = null,
    val lightMin: Double? = null,
    val remMin: Double? = null,
    val awakeMin: Double? = null,
    val efficiencyPct: Double? = null,
    val sleepScore: Int? = null,
)

/** One night as the ingest writes it to `sleepSession`. */
data class GarminSleepRow(
    val startTs: Long,
    val endTs: Long,
    val efficiency: Double?,
    val restingHr: Int?,
    val avgHrv: Double?,
    val stagesJSON: String?,
)

/** Everything one downloaded file contributes. Swift twin: `GarminFileIngest`. */
data class GarminFileIngest(
    val file: GarminSyncedFileRow,
    val hr: List<HrRow> = emptyList(),
    val deviceMetric: List<DeviceMetricRow> = emptyList(),
    val days: List<GarminDayRollup> = emptyList(),
    val sleeps: List<GarminSleepRow> = emptyList(),
)

data class GarminIngestCounts(
    var hr: Int = 0,
    var deviceMetric: Int = 0,
    var days: Int = 0,
    var sleeps: Int = 0,
    var metricPoints: Int = 0,
)

class GarminSyncStore(private val db: WhoopDatabase) {

    private val sql get() = db.openHelper.writableDatabase

    /** Every file of this device recorded as ingested. Swift twin: `WhoopStore.garminSyncedFiles`. */
    suspend fun syncedFiles(deviceId: String): List<GarminSyncedFileRow> = db.withTransaction {
        sql.query(
            "SELECT fileIndex, dataType, subType, fileNumber, ts, size FROM garminSyncedFile " +
                "WHERE deviceId = ? ORDER BY ts, fileIndex",
            arrayOf<Any?>(deviceId),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(GarminSyncedFileRow(c.getInt(0), c.getInt(1), c.getInt(2), c.getInt(3), c.getLong(4), c.getLong(5)))
                }
            }
        }
    }

    /** Record a file as ingested without data (a stored workout, or a file that decoded to nothing).
     *  Swift twin: `WhoopStore.recordGarminSyncedFile`. */
    suspend fun record(file: GarminSyncedFileRow, deviceId: String, ingestedAt: Long) {
        db.withTransaction { insertLedgerRow(file, deviceId, ingestedAt) }
    }

    /** Persist one file's data and its ledger row in ONE transaction. Idempotent. Swift twin: `WhoopStore.ingestGarminFile`. */
    suspend fun ingest(ingest: GarminFileIngest, deviceId: String, ingestedAt: Long): GarminIngestCounts =
        db.withTransaction {
            val counts = GarminIngestCounts()
            for (s in ingest.hr) {
                counts.hr += exec(
                    // `synced` is NOT NULL without a default in Room's schema (GRDB defaults it to 0).
                    "INSERT INTO hrSample (deviceId, ts, bpm, synced) VALUES (?, ?, ?, 0) ON CONFLICT(deviceId, ts) DO NOTHING",
                    deviceId, s.ts, s.bpm,
                )
            }
            for (s in ingest.deviceMetric) {
                if (!s.value.isFinite()) continue
                counts.deviceMetric += exec(
                    "INSERT INTO deviceMetricSample (deviceId, ts, kind, value, source) VALUES (?, ?, ?, ?, ?) " +
                        "ON CONFLICT(deviceId, kind, ts) DO UPDATE SET value = excluded.value, source = excluded.source",
                    deviceId, s.ts, s.kind.wire, s.value, s.source,
                )
            }
            for (d in ingest.days) mergeDay(d, deviceId, counts)
            for (s in ingest.sleeps) {
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
            insertLedgerRow(ingest.file, deviceId, ingestedAt)
            counts
        }

    /** Swift twin: `WhoopStore.insertLedgerRow`. */
    private fun insertLedgerRow(f: GarminSyncedFileRow, deviceId: String, ingestedAt: Long) {
        exec(
            "INSERT INTO garminSyncedFile (deviceId, fileIndex, dataType, subType, fileNumber, ts, size, ingestedAt) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
            deviceId, f.fileIndex, f.dataType, f.subType, f.fileNumber, f.ts, f.size, ingestedAt,
        )
    }

    /** Swift twin: `WhoopStore.mergeGarminDay`. */
    private fun mergeDay(d: GarminDayRollup, deviceId: String, counts: GarminIngestCounts) {
        // Swift twin: `WhoopStore.aggregate`.
        fun aggregate(kind: DeviceMetricKind, fn: String): Double? =
            sql.query(
                "SELECT $fn(value) FROM deviceMetricSample WHERE deviceId = ? AND kind = ? AND ts >= ? AND ts < ?",
                arrayOf<Any?>(deviceId, kind.wire, d.dayStartTs, d.dayEndTs),
            ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getDouble(0) else null }
        val stress = aggregate(DeviceMetricKind.STRESS, "AVG")
        val bbMin = aggregate(DeviceMetricKind.BODY_BATTERY, "MIN")
        val bbMax = aggregate(DeviceMetricKind.BODY_BATTERY, "MAX")
        val spo2 = aggregate(DeviceMetricKind.SPO2_PCT, "AVG")
        val resp = aggregate(DeviceMetricKind.RESPIRATION_BPM, "AVG")

        counts.days += exec(
            "INSERT INTO dailyMetric (deviceId, day, totalSleepMin, efficiency, deepMin, remMin, lightMin, " +
                "restingHr, avgHrv, spo2Pct, respRateBpm, steps, activeKcalEst) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT(deviceId, day) DO UPDATE SET " +
                "totalSleepMin = COALESCE(excluded.totalSleepMin, dailyMetric.totalSleepMin), " +
                "efficiency = COALESCE(excluded.efficiency, dailyMetric.efficiency), " +
                "deepMin = COALESCE(excluded.deepMin, dailyMetric.deepMin), " +
                "remMin = COALESCE(excluded.remMin, dailyMetric.remMin), " +
                "lightMin = COALESCE(excluded.lightMin, dailyMetric.lightMin), " +
                "restingHr = COALESCE(excluded.restingHr, dailyMetric.restingHr), " +
                "avgHrv = COALESCE(excluded.avgHrv, dailyMetric.avgHrv), " +
                "spo2Pct = COALESCE(excluded.spo2Pct, dailyMetric.spo2Pct), " +
                "respRateBpm = COALESCE(excluded.respRateBpm, dailyMetric.respRateBpm), " +
                "steps = CASE WHEN excluded.steps IS NULL THEN dailyMetric.steps " +
                "WHEN dailyMetric.steps IS NULL THEN excluded.steps ELSE MAX(excluded.steps, dailyMetric.steps) END, " +
                "activeKcalEst = CASE WHEN excluded.activeKcalEst IS NULL THEN dailyMetric.activeKcalEst " +
                "WHEN dailyMetric.activeKcalEst IS NULL THEN excluded.activeKcalEst " +
                "ELSE MAX(excluded.activeKcalEst, dailyMetric.activeKcalEst) END",
            deviceId, d.day, d.totalSleepMin, d.efficiencyPct, d.deepMin, d.remMin, d.lightMin,
            d.restingHr, d.avgHrvMs, spo2, resp, d.steps, d.activeKcal,
        )

        val points = listOf(
            "steps" to d.steps?.toDouble(), "distance_m" to d.distanceM, "energy_kcal" to d.activeKcal,
            "total_kcal" to d.totalKcal, "rhr" to d.restingHr?.toDouble(), "hrv" to d.avgHrvMs,
            "spo2" to spo2, "resp_rate" to resp, GARMIN_STRESS_KEY to stress,
            "body_battery_min" to bbMin, "body_battery_max" to bbMax,
            "sleep_total_min" to d.totalSleepMin, "sleep_deep_min" to d.deepMin,
            "sleep_light_min" to d.lightMin, "sleep_rem_min" to d.remMin, "sleep_awake_min" to d.awakeMin,
            "ref_sleep_score" to d.sleepScore?.toDouble(),
        )
        for ((key, value) in points) {
            if (value == null || !value.isFinite()) continue
            val update = if (key in MAX_KEYS) "MAX(excluded.value, metricSeries.value)" else "excluded.value"
            counts.metricPoints += exec(
                "INSERT INTO metricSeries (deviceId, day, `key`, value) VALUES (?, ?, ?, ?) " +
                    "ON CONFLICT(deviceId, day, `key`) DO UPDATE SET value = $update",
                deviceId, d.day, key, value,
            )
        }
    }

    /** Run one statement and return the rows it changed (SQLite `changes()`). */
    private fun exec(statement: String, vararg args: Any?): Int {
        sql.execSQL(statement, args)
        return sql.query("SELECT changes()").use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    companion object {
        /** `metricSeries` keys that merge with MAX (cumulative counters). Swift twin: `garminMaxKeys`. */
        val MAX_KEYS = setOf("steps", "distance_m", "energy_kcal", "total_kcal")

        /**
         * The day's average of the watch's own 0-100 stress score. NOT "stress": that key carries WHOOP's 0-3
         * stress series, which the Stress model clamps to 0-3 (Garmin child 9). Swift twin:
         * `WhoopStore.garminStressKey`.
         */
        const val GARMIN_STRESS_KEY = "garmin_stress"
    }
}
