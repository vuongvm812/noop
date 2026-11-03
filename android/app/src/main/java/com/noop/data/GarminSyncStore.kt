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
 * stress / Body Battery aggregates are re-derived from the stored samples of that local day; its SpO2 and
 * respiration are the OVERNIGHT mean over the stored nights ending that day ([nightMean], #52), written
 * the same to dailyMetric and metricSeries; cumulative counters (steps, distance, calories) are kept per
 * activity type in `garminActivityPeak` and the day's totals summed over the stored types, so a file
 * carrying only the types that moved cannot lower the day (#51), and the day row keeps the larger value;
 * every other column keeps its stored value when the file has none (COALESCE). A night REPLACES any other stored night of
 * the watch it overlaps (unless the user edited that one), so one Garmin night stays one session when a
 * later fold places it differently (#47).
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

/**
 * One activity type's largest cumulative MONITORING counters on one local day, as one file saw them.
 * [activityType] is the FIT activity_type, or -1 when the record carried none. [steps] is set only for
 * the step types ([com.noop.ingest.GarminWellnessParser.STEP_ACTIVITY_TYPES]): other types count strokes
 * or revolutions. Swift twin: `GarminActivityPeak`.
 */
data class GarminActivityPeak(
    val activityType: Int,
    val steps: Int? = null,
    val distanceM: Double? = null,
    val totalKcal: Double? = null,
    val activeKcal: Double? = null,
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
    /** The per-activity-type counters behind the four totals above, merged per type in the store. */
    val activityPeaks: List<GarminActivityPeak> = emptyList(),
    val restingHr: Int? = null,
    val avgHrvMs: Double? = null,
    val totalSleepMin: Double? = null,
    val deepMin: Double? = null,
    val lightMin: Double? = null,
    val remMin: Double? = null,
    val awakeMin: Double? = null,
    /** Asleep / (asleep + awake) as a 0-1 fraction, the unit of `dailyMetric.efficiency` (#49). */
    val efficiency: Double? = null,
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
    /** The day after each rollup day the file did not touch; its overnight vitals are re-derived if its row exists. */
    val nightRefreshDays: List<GarminDayRollup> = emptyList(),
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
            for (s in ingest.sleeps) {
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
            // After the nights: the overnight SpO2 / respiration read the stored nights.
            for (d in ingest.days) mergeDay(d, deviceId, counts)
            for (d in ingest.nightRefreshDays) counts.metricPoints += refreshNightVitals(d, deviceId)
            insertLedgerRow(ingest.file, deviceId, ingestedAt)
            counts
        }

    /** Forget every ledger row of one FIT file type, so the next sync downloads those files again (the
     *  watch keeps them). Returns the rows removed. Swift twin: `WhoopStore.forgetGarminSyncedFiles`. */
    suspend fun forgetFiles(deviceId: String, dataType: Int, subType: Int): Int = db.withTransaction {
        exec("DELETE FROM garminSyncedFile WHERE deviceId = ? AND dataType = ? AND subType = ?", deviceId, dataType, subType)
    }

    /**
     * Divide by 100 every efficiency of this device stored above 1, the percent an older fold wrote (#49).
     * Runs once per watch ([GarminHistoryIngest.refetchSleepFilesOnce]); idempotent. Returns the rows
     * changed. Swift twin: `WhoopStore.repairGarminEfficiencyUnits`.
     */
    suspend fun repairEfficiencyUnits(deviceId: String): Int = db.withTransaction {
        exec("UPDATE sleepSession SET efficiency = efficiency / 100.0 WHERE deviceId = ? AND efficiency > 1", deviceId) +
            exec("UPDATE dailyMetric SET efficiency = efficiency / 100.0 WHERE deviceId = ? AND efficiency > 1", deviceId)
    }

    /** The days this device has a `dailyMetric` row for, oldest first. Swift twin: `WhoopStore.garminDays`. */
    suspend fun days(deviceId: String): List<String> = db.withTransaction {
        sql.query("SELECT day FROM dailyMetric WHERE deviceId = ? ORDER BY day", arrayOf<Any?>(deviceId)).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
    }

    /**
     * Re-derive the overnight SpO2 and respiration of existing day rows from the stored samples and nights,
     * in one transaction (#52); a day without a row is skipped. Returns the metricSeries rows changed.
     * Swift twin: `WhoopStore.rederiveGarminNightVitals`.
     */
    suspend fun rederiveNightVitals(deviceId: String, days: List<GarminDayRollup>): Int = db.withTransaction {
        days.sumOf { refreshNightVitals(it, deviceId) }
    }

    /**
     * The overnight mean of one vital for the night(s) waking on [d]: the stored samples of [kind] inside any
     * stored night of this device whose end falls in `d.dayStartTs ..< d.dayEndTs` (night bounds inclusive,
     * each sample counted once), within [range], or null under [GARMIN_MIN_NIGHT_SAMPLES]. Samples are looked for
     * from a day before the wake day's midnight. The ONE resolver for a Garmin day's SpO2 and respiration.
     * Swift twin: `WhoopStore.garminNightMean`.
     */
    internal fun nightMean(deviceId: String, kind: DeviceMetricKind, range: ClosedFloatingPointRange<Double>, d: GarminDayRollup): Double? =
        sql.query(
            "SELECT AVG(m.value) AS mean, COUNT(*) AS n FROM deviceMetricSample m " +
                "WHERE m.deviceId = ? AND m.kind = ? AND m.ts >= ? AND m.ts < ? AND m.value >= ? AND m.value <= ? " +
                "AND EXISTS (SELECT 1 FROM sleepSession s " +
                "WHERE s.deviceId = m.deviceId AND s.endTs >= ? AND s.endTs < ? " +
                "AND m.ts >= s.startTs AND m.ts <= s.endTs)",
            arrayOf<Any?>(deviceId, kind.wire, d.dayStartTs - 86_400, d.dayEndTs, range.start, range.endInclusive,
                d.dayStartTs, d.dayEndTs),
        ).use { c ->
            if (c.moveToFirst() && !c.isNull(0) && c.getInt(1) >= GARMIN_MIN_NIGHT_SAMPLES) c.getDouble(0) else null
        }

    /** One day's overnight SpO2 / respiration onto its existing row and points. Swift twin: `WhoopStore.refreshGarminNightVitals`. */
    private fun refreshNightVitals(d: GarminDayRollup, deviceId: String): Int {
        val exists = sql.query(
            "SELECT EXISTS (SELECT 1 FROM dailyMetric WHERE deviceId = ? AND day = ?)", arrayOf<Any?>(deviceId, d.day),
        ).use { c -> c.moveToFirst() && c.getInt(0) == 1 }
        if (!exists) return 0
        val spo2 = nightMean(deviceId, DeviceMetricKind.SPO2_PCT, GARMIN_SPO2_MIN..GARMIN_SPO2_MAX, d)
        val resp = nightMean(deviceId, DeviceMetricKind.RESPIRATION_BPM, GARMIN_RESP_MIN..GARMIN_RESP_MAX, d)
        exec("UPDATE dailyMetric SET spo2Pct = ?, respRateBpm = ? WHERE deviceId = ? AND day = ?", spo2, resp, deviceId, d.day)
        return writeNightVitalPoints(d.day, spo2, resp, deviceId)
    }

    /** A missing value removes the point, so the row and the series cannot disagree. Swift twin: `WhoopStore.writeNightVitalPoints`. */
    private fun writeNightVitalPoints(day: String, spo2: Double?, resp: Double?, deviceId: String): Int {
        var changed = 0
        for ((key, value) in listOf("spo2" to spo2, "resp_rate" to resp)) {
            changed += if (value != null) {
                exec(
                    "INSERT INTO metricSeries (deviceId, day, `key`, value) VALUES (?, ?, ?, ?) " +
                        "ON CONFLICT(deviceId, day, `key`) DO UPDATE SET value = excluded.value",
                    deviceId, day, key, value,
                )
            } else {
                exec("DELETE FROM metricSeries WHERE deviceId = ? AND day = ? AND `key` = ?", deviceId, day, key)
            }
        }
        return changed
    }

    /** Swift twin: `WhoopStore.insertLedgerRow`. */
    private fun insertLedgerRow(f: GarminSyncedFileRow, deviceId: String, ingestedAt: Long) {
        exec(
            "INSERT INTO garminSyncedFile (deviceId, fileIndex, dataType, subType, fileNumber, ts, size, ingestedAt) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
            deviceId, f.fileIndex, f.dataType, f.subType, f.fileNumber, f.ts, f.size, ingestedAt,
        )
    }

    /**
     * Store this file's per-type counters (each column the larger of stored and new), then replace the
     * rollup's four day totals with sums over every stored type of the day, so a file that carries only
     * the types that moved still yields the whole day. Sums run in activity-type order so the
     * floating-point distance is platform-independent. A rollup without peaks is returned unchanged.
     * Swift twin: `WhoopStore.mergeActivityPeaks`.
     */
    private fun mergePeaks(d: GarminDayRollup, deviceId: String): GarminDayRollup {
        if (d.activityPeaks.isEmpty()) return d
        for (p in d.activityPeaks) {
            exec(
                "INSERT INTO garminActivityPeak (deviceId, day, activityType, steps, distanceM, totalKcal, activeKcal) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?) " +
                    "ON CONFLICT(deviceId, day, activityType) DO UPDATE SET " +
                    "steps = MAX(COALESCE(excluded.steps, garminActivityPeak.steps), COALESCE(garminActivityPeak.steps, excluded.steps)), " +
                    "distanceM = MAX(COALESCE(excluded.distanceM, garminActivityPeak.distanceM), COALESCE(garminActivityPeak.distanceM, excluded.distanceM)), " +
                    "totalKcal = MAX(COALESCE(excluded.totalKcal, garminActivityPeak.totalKcal), COALESCE(garminActivityPeak.totalKcal, excluded.totalKcal)), " +
                    "activeKcal = MAX(COALESCE(excluded.activeKcal, garminActivityPeak.activeKcal), COALESCE(garminActivityPeak.activeKcal, excluded.activeKcal))",
                deviceId, d.day, p.activityType, p.steps, p.distanceM, p.totalKcal, p.activeKcal,
            )
        }
        var steps: Int? = null
        var distance: Double? = null
        var total: Double? = null
        var active: Double? = null
        sql.query(
            "SELECT steps, distanceM, totalKcal, activeKcal FROM garminActivityPeak " +
                "WHERE deviceId = ? AND day = ? ORDER BY activityType",
            arrayOf<Any?>(deviceId, d.day),
        ).use { c ->
            while (c.moveToNext()) {
                if (!c.isNull(0)) steps = (steps ?: 0) + c.getInt(0)
                if (!c.isNull(1)) distance = (distance ?: 0.0) + c.getDouble(1)
                if (!c.isNull(2)) total = (total ?: 0.0) + c.getDouble(2)
                if (!c.isNull(3)) active = (active ?: 0.0) + c.getDouble(3)
            }
        }
        return d.copy(steps = steps, distanceM = distance, totalKcal = total, activeKcal = active)
    }

    /** Swift twin: `WhoopStore.mergeGarminDay`. */
    private fun mergeDay(rollup: GarminDayRollup, deviceId: String, counts: GarminIngestCounts) {
        // The day keeps its main night: a shorter night from another file (a nap) leaves the stored sleep
        // figures alone; an equal one replaces them, so a re-ingest is idempotent (#49).
        val incoming = rollup.totalSleepMin
        val stored = if (incoming == null) null else sql.query(
            "SELECT totalSleepMin FROM dailyMetric WHERE deviceId = ? AND day = ?", arrayOf<Any?>(deviceId, rollup.day),
        ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getDouble(0) else null }
        val night = if (incoming != null && stored != null && stored > incoming) {
            rollup.copy(totalSleepMin = null, deepMin = null, lightMin = null, remMin = null, awakeMin = null, efficiency = null)
        } else rollup
        val d = mergePeaks(night, deviceId)
        // Swift twin: `WhoopStore.aggregate`.
        fun aggregate(kind: DeviceMetricKind, fn: String): Double? =
            sql.query(
                "SELECT $fn(value) FROM deviceMetricSample WHERE deviceId = ? AND kind = ? AND ts >= ? AND ts < ?",
                arrayOf<Any?>(deviceId, kind.wire, d.dayStartTs, d.dayEndTs),
            ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getDouble(0) else null }
        val stress = aggregate(DeviceMetricKind.STRESS, "AVG")
        val bbMin = aggregate(DeviceMetricKind.BODY_BATTERY, "MIN")
        val bbMax = aggregate(DeviceMetricKind.BODY_BATTERY, "MAX")
        val spo2 = nightMean(deviceId, DeviceMetricKind.SPO2_PCT, GARMIN_SPO2_MIN..GARMIN_SPO2_MAX, d)
        val resp = nightMean(deviceId, DeviceMetricKind.RESPIRATION_BPM, GARMIN_RESP_MIN..GARMIN_RESP_MAX, d)

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
                "spo2Pct = excluded.spo2Pct, " +
                "respRateBpm = excluded.respRateBpm, " +
                "steps = CASE WHEN excluded.steps IS NULL THEN dailyMetric.steps " +
                "WHEN dailyMetric.steps IS NULL THEN excluded.steps ELSE MAX(excluded.steps, dailyMetric.steps) END, " +
                "activeKcalEst = CASE WHEN excluded.activeKcalEst IS NULL THEN dailyMetric.activeKcalEst " +
                "WHEN dailyMetric.activeKcalEst IS NULL THEN excluded.activeKcalEst " +
                "ELSE MAX(excluded.activeKcalEst, dailyMetric.activeKcalEst) END",
            deviceId, d.day, d.totalSleepMin, d.efficiency, d.deepMin, d.remMin, d.lightMin,
            // activeKcalEst is whole-day TOTAL energy on every source (#51): the watch's total, resting
            // metabolic rate included. Its active calories stay in the energy_kcal series.
            d.restingHr, d.avgHrvMs, spo2, resp, d.steps, d.totalKcal,
        )

        val points = listOf(
            "steps" to d.steps?.toDouble(), "distance_m" to d.distanceM, "energy_kcal" to d.activeKcal,
            "total_kcal" to d.totalKcal, "rhr" to d.restingHr?.toDouble(), "hrv" to d.avgHrvMs,
            GARMIN_STRESS_KEY to stress,
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
        counts.metricPoints += writeNightVitalPoints(d.day, spo2, resp, deviceId)
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

        // Plausible watch values (#52); outside is a sentinel or misread. Swift twins: `WhoopStore.garminSpo2Min`
        // and the rest, same names.
        const val GARMIN_SPO2_MIN = 70.0
        const val GARMIN_SPO2_MAX = 100.0
        const val GARMIN_RESP_MIN = 4.0
        const val GARMIN_RESP_MAX = 60.0
        const val GARMIN_HRV_MIN = 5.0
        const val GARMIN_HRV_MAX = 300.0

        /** Fewest in-night samples an overnight mean is formed from. Swift twin: `WhoopStore.garminMinNightSamples`. */
        const val GARMIN_MIN_NIGHT_SAMPLES = 3
    }
}
