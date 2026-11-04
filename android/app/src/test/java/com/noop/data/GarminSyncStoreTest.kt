package com.noop.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * History-sync persistence against a real Room database: one transaction per watch file, merge-on-write
 * day rollups, the `garminSyncedFile` ledger, idempotent re-ingest. Swift twin: `GarminSyncStoreTests`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class GarminSyncStoreTest {

    private val db: WhoopDatabase = Room.inMemoryDatabaseBuilder(
        InstrumentationRegistry.getInstrumentation().targetContext,
        WhoopDatabase::class.java,
    ).allowMainThreadQueries().build()
    private val store = GarminSyncStore(db)
    private val id = "garmin-test"
    /** 2026-11-01 00:00 at UTC+7. */
    private val dayStart = 1_793_466_000L

    @After
    fun close() = db.close()

    private fun file(index: Int, size: Long = 100) = GarminSyncedFileRow(index, 128, 32, index, 1_162_000_000L + index, size)

    private fun day(steps: Int? = null, rhr: Int? = null, sleep: Double? = null) =
        GarminDayRollup("2026-11-01", dayStart, dayStart + 86_400, steps = steps, restingHr = rhr, totalSleepMin = sleep)

    private fun count(table: String): Int =
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table WHERE deviceId = ?", arrayOf<Any?>(id))
            .use { c -> c.moveToFirst(); c.getInt(0) }

    private fun series(key: String): Double? =
        db.openHelper.readableDatabase.query(
            "SELECT value FROM metricSeries WHERE deviceId = ? AND day = '2026-11-01' AND `key` = ?", arrayOf<Any?>(id, key),
        ).use { c -> if (c.moveToFirst()) c.getDouble(0) else null }

    private fun dailyInt(column: String): Int? =
        db.openHelper.readableDatabase.query("SELECT $column FROM dailyMetric WHERE deviceId = ? AND day = '2026-11-01'", arrayOf<Any?>(id))
            .use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getInt(0) else null }

    @Test
    fun ingestIsOneTransactionWithItsLedgerRowAndIsIdempotent() = runBlocking {
        val ingest = GarminFileIngest(
            file = file(1),
            hr = listOf(HrRow(dayStart + 60, 61), HrRow(dayStart + 120, 64)),
            deviceMetric = listOf(DeviceMetricRow(dayStart + 60, DeviceMetricKind.STRESS, 20.0, "fit227"),
                DeviceMetricRow(dayStart + 60, DeviceMetricKind.BODY_BATTERY, 80.0, "fit227")),
            days = listOf(day(steps = 1_000, rhr = 59).copy(
                activityPeaks = listOf(GarminActivityPeak(6, steps = 1_000, totalKcal = 900.0)),
            )),
            sleeps = listOf(GarminSleepRow(dayStart - 20_000, dayStart + 5_000, 0.91, null, null, "[]")),
        )
        val first = store.ingest(ingest, id, 1)
        assertEquals(2, first.hr)
        assertEquals(2, first.deviceMetric)
        assertEquals(listOf(file(1)), store.syncedFiles(id))
        val tables = listOf("hrSample", "deviceMetricSample", "dailyMetric", "sleepSession", "metricSeries", "garminSyncedFile",
            "garminActivityPeak")
        val before = tables.map(::count)
        store.ingest(ingest, id, 2)
        assertEquals("re-ingesting a file adds nothing", before, tables.map(::count))
    }

    @Test
    fun aFailedIngestRecordsNothing() = runBlocking {
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_day BEFORE INSERT ON dailyMetric BEGIN SELECT RAISE(ABORT, 'test'); END")
        try {
            store.ingest(GarminFileIngest(file(1), hr = listOf(HrRow(dayStart, 60)), days = listOf(day(steps = 5))), id, 1)
            fail("expected the trigger to abort the ingest")
        } catch (_: Exception) {
        }
        assertEquals(0, count("hrSample"))
        assertTrue("a file is never recorded as synced without its data", store.syncedFiles(id).isEmpty())
    }

    @Test
    fun daysMergeAcrossFiles() = runBlocking {
        val a = GarminFileIngest(file(1), days = listOf(day(steps = 4_000, rhr = 58)), deviceMetric = listOf(
            DeviceMetricRow(dayStart + 3_600, DeviceMetricKind.STRESS, 30.0, "fit227"),
            DeviceMetricRow(dayStart + 3_600, DeviceMetricKind.BODY_BATTERY, 90.0, "fit227")))
        val b = GarminFileIngest(file(2), days = listOf(day(steps = 16_532)), deviceMetric = listOf(
            DeviceMetricRow(dayStart + 40_000, DeviceMetricKind.STRESS, 50.0, "fit227"),
            DeviceMetricRow(dayStart + 40_000, DeviceMetricKind.BODY_BATTERY, 40.0, "fit227"),
            DeviceMetricRow(dayStart + 86_400 + 10, DeviceMetricKind.STRESS, 99.0, "fit227")))
        val c = GarminFileIngest(file(3), days = listOf(day(steps = 2_000, sleep = 420.0)))
        for (f in listOf(a, b, c)) store.ingest(f, id, 1)
        assertEquals("a cumulative counter never goes down", 16_532, dailyInt("steps"))
        assertEquals("a file without resting HR keeps the stored one", 58, dailyInt("restingHr"))
        assertEquals(420, dailyInt("totalSleepMin"))
        assertEquals(16_532.0, series("steps"))
        assertEquals("the day's stress average covers every file", 40.0, series(GarminSyncStore.GARMIN_STRESS_KEY))
        assertEquals(40.0, series("body_battery_min"))
        assertEquals(90.0, series("body_battery_max"))
        assertEquals(3, store.syncedFiles(id).size)
    }

    /** #47: one Garmin night stays one session. Swift twin: `testANightReplacesTheStoredNightsItOverlaps`. */
    private fun dailyDouble(column: String): Double? =
        db.openHelper.readableDatabase.query("SELECT $column FROM dailyMetric WHERE deviceId = ? AND day = '2026-11-01'", arrayOf<Any?>(id))
            .use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getDouble(0) else null }

    /** `dailyMetric.activeKcalEst` is whole-day TOTAL energy on every source (#51). Swift twin: same name. */
    @Test
    fun caloriesColumnHoldsTheWatchTotalNotActive() = runBlocking {
        val d = day().copy(activityPeaks = listOf(GarminActivityPeak(6, steps = 9_000, totalKcal = 2_400.0, activeKcal = 500.0)))
        store.ingest(GarminFileIngest(file(1), days = listOf(d)), id, 1)
        assertEquals(2_400.0, dailyDouble("activeKcalEst"))
        assertEquals(2_400.0, series("total_kcal"))
        assertEquals(500.0, series("active_kcal"))
    }

    /**
     * A later file that carries only the activity types whose counters moved must not lower the day:
     * totals are summed over every stored type (#51). The old per-file sum merged with MAX read
     * max(5,000 + 2,000, 6,000) = 7,000 steps; per-type peaks give 6,000 walking + 2,000 running.
     * Swift twin: same name.
     */
    @Test
    fun perActivityTypeCountersSumAcrossFiles() = runBlocking {
        val a = day().copy(activityPeaks = listOf(
            GarminActivityPeak(1, steps = 2_000, distanceM = 2_500.5, totalKcal = 300.0, activeKcal = 250.0),
            GarminActivityPeak(6, steps = 5_000, distanceM = 3_000.25, totalKcal = 1_500.0, activeKcal = 200.0),
        ))
        val b = day().copy(activityPeaks = listOf(
            GarminActivityPeak(6, steps = 6_000, distanceM = 3_600.125, totalKcal = 1_600.0, activeKcal = 220.0),
            // Cycling carries no steps; its distance and energy still count.
            GarminActivityPeak(2, distanceM = 10_000.0, totalKcal = 400.0, activeKcal = 380.0),
        ))
        // An OLDER re-sync of file A's walking value lowers nothing.
        val c = day().copy(activityPeaks = listOf(GarminActivityPeak(6, steps = 4_000, totalKcal = 1_200.0, activeKcal = 150.0)))
        for ((i, d) in listOf(a, b, c).withIndex()) store.ingest(GarminFileIngest(file(i + 1), days = listOf(d)), id, 1)
        assertEquals(8_000, dailyInt("steps"))
        assertEquals(2_300.0, dailyDouble("activeKcalEst"))
        assertEquals(8_000.0, series("steps"))
        assertEquals(2_500.5 + 3_600.125 + 10_000.0, series("distance_m"))
        assertEquals(2_300.0, series("total_kcal"))
        assertEquals(850.0, series("active_kcal"))
        assertEquals(3, count("garminActivityPeak"))
    }

    @Test
    fun aNightReplacesTheStoredNightsItOverlaps() = runBlocking {
        val s = dayStart + 3_600
        fun night(start: Long, end: Long) = GarminSleepRow(start, end, null, null, null, null)
        store.ingest(GarminFileIngest(file(1), sleeps = listOf(night(s - 80_000, s - 60_000), night(s + 1_020, s + 6_000),
            night(s + 9_960, s + 11_340), night(s + 19_620, s + 29_623))), id, 1)
        store.ingest(GarminFileIngest(file(2), sleeps = listOf(night(s, s + 11_340))), id, 1)
        repeat(2) { store.ingest(GarminFileIngest(file(3), sleeps = listOf(night(s, s + 29_623))), id, 1) }
        val nights = db.openHelper.readableDatabase.query(
            "SELECT startTs, endTs FROM sleepSession WHERE deviceId = ? ORDER BY startTs", arrayOf<Any?>(id),
        ).use { c -> buildList { while (c.moveToNext()) add(listOf(c.getLong(0), c.getLong(1))) } }
        assertEquals(listOf(listOf(s - 80_000, s - 60_000), listOf(s, s + 29_623)), nights)
    }

    @Test
    fun anOverlappingNightTheUserEditedIsKept() = runBlocking {
        store.ingest(GarminFileIngest(file(1), sleeps = listOf(GarminSleepRow(dayStart + 1_000, dayStart + 5_000, null, null, null, null))), id, 1)
        db.openHelper.writableDatabase.execSQL("UPDATE sleepSession SET userEdited = 1 WHERE deviceId = ?", arrayOf<Any?>(id))
        store.ingest(GarminFileIngest(file(2), sleeps = listOf(GarminSleepRow(dayStart, dayStart + 9_000, null, null, null, null))), id, 1)
        assertEquals(2, count("sleepSession"))
    }

    @Test
    fun forgettingAFileTypeLeavesTheOthers() = runBlocking {
        store.record(GarminSyncedFileRow(9, 255, 49, 9, 1_162_000_009L, 900), id, 1)
        store.record(file(1), id, 1)
        assertEquals(1, store.forgetFiles(id, 255, 49))
        assertEquals(listOf(file(1)), store.syncedFiles(id))
    }

    @Test
    fun recordWithoutDataIsIdempotent() = runBlocking {
        store.record(file(7), id, 1)
        store.record(file(7), id, 2)
        assertEquals(listOf(file(7)), store.syncedFiles(id))
    }

    private fun dailyDouble(column: String, device: String = id, day: String = "2026-11-01"): Double? =
        db.openHelper.readableDatabase.query("SELECT $column FROM dailyMetric WHERE deviceId = ? AND day = ?", arrayOf<Any?>(device, day))
            .use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getDouble(0) else null }

    private fun sleepDay(total: Double, deep: Double, efficiency: Double) =
        day(sleep = total).copy(deepMin = deep, lightMin = total - deep, remMin = 0.0, awakeMin = 10.0, efficiency = efficiency)

    /** #49: a nap file synced after the night file keeps the night's day totals. Swift twin: `testALaterNapFileKeepsTheNightsDayTotals`. */
    @Test
    fun aLaterNapFileKeepsTheNightsDayTotals() = runBlocking {
        store.ingest(GarminFileIngest(file(1), days = listOf(sleepDay(420.0, 90.0, 0.95))), id, 1)
        store.ingest(GarminFileIngest(file(2), days = listOf(sleepDay(40.0, 0.0, 0.8))), id, 2)
        assertEquals(420.0, dailyDouble("totalSleepMin")!!, 0.0)
        assertEquals(90.0, dailyDouble("deepMin")!!, 0.0)
        assertEquals(0.95, dailyDouble("efficiency")!!, 0.0)
        assertEquals(420.0, series("sleep_total_min")!!, 0.0)
        assertEquals(10.0, series("sleep_awake_min")!!, 0.0)
        store.ingest(GarminFileIngest(file(3), days = listOf(sleepDay(420.0, 90.0, 0.951))), id, 3)
        assertEquals("the same night again replaces the day's figures", 0.951, dailyDouble("efficiency")!!, 0.0)
    }

    /** #49: the repair rescales only this watch's percent efficiencies, once. Swift twin: `testRepairRescalesPercentEfficienciesOnce`. */
    @Test
    fun repairRescalesPercentEfficienciesOnce() = runBlocking {
        store.ingest(GarminFileIngest(file(1), days = listOf(sleepDay(420.0, 90.0, 93.3)), sleeps = listOf(
            GarminSleepRow(dayStart - 30_000, dayStart, 93.3, null, null, null),
            GarminSleepRow(dayStart + 40_000, dayStart + 42_000, 0.8, null, null, null),
        )), id, 1)
        store.ingest(GarminFileIngest(file(1), sleeps = listOf(GarminSleepRow(dayStart - 30_000, dayStart, 88.0, null, null, null))),
            "garmin-other", 1)
        assertEquals(2, store.repairEfficiencyUnits(id))
        assertEquals(0, store.repairEfficiencyUnits(id))
        fun efficiencies(device: String) = db.openHelper.readableDatabase.query(
            "SELECT efficiency FROM sleepSession WHERE deviceId = ? ORDER BY efficiency", arrayOf<Any?>(device),
        ).use { c -> buildList { while (c.moveToNext()) add(Math.round(c.getDouble(0) * 1000) / 1000.0) } }
        assertEquals(listOf(0.8, 0.933), efficiencies(id))
        assertEquals(0.933, dailyDouble("efficiency")!!, 1e-12)
        assertEquals(listOf(88.0), efficiencies("garmin-other"))
    }

    // Overnight vitals (#52). Same scenario and expected text as the Swift twin.

    private fun prevDay() = GarminDayRollup("2026-10-31", dayStart - 86_400, dayStart)

    private fun m(off: Long, kind: DeviceMetricKind, v: Double) = DeviceMetricRow(dayStart + off, kind, v, "test")

    private val scenarioSamples = listOf(
        m(-21_600, DeviceMetricKind.RESPIRATION_BPM, 30.0),
        m(-3_600, DeviceMetricKind.RESPIRATION_BPM, 14.25), m(3_600, DeviceMetricKind.RESPIRATION_BPM, 15.5),
        m(20_000, DeviceMetricKind.RESPIRATION_BPM, 16.0),
        m(10_000, DeviceMetricKind.RESPIRATION_BPM, 2.0),
        m(43_200, DeviceMetricKind.RESPIRATION_BPM, 22.0),
        m(-1_800, DeviceMetricKind.SPO2_PCT, 95.0), m(7_200, DeviceMetricKind.SPO2_PCT, 93.0),
        m(25_200, DeviceMetricKind.SPO2_PCT, 97.0),
        m(12_000, DeviceMetricKind.SPO2_PCT, 50.0),
        m(50_000, DeviceMetricKind.SPO2_PCT, 88.0),
    )

    private val scenarioNight = GarminSleepRow(dayStart - 7_200, dayStart + 25_200, 90.0, null, null, null)

    private fun vitals(): String {
        fun f(v: Double?) = v?.let { String.format(java.util.Locale.ROOT, "%.4f", it) } ?: "nil"
        return "row spo2=${f(dailyDouble("spo2Pct"))} resp=${f(dailyDouble("respRateBpm"))} " +
            "series spo2=${f(series("spo2"))} resp=${f(series("resp_rate"))}"
    }

    @Test
    fun overnightVitalsComeFromTheNightEndingThatDay() = runBlocking {
        store.ingest(GarminFileIngest(file(1), deviceMetric = scenarioSamples, days = listOf(prevDay(), day())), id, 1)
        assertEquals("row spo2=nil resp=nil series spo2=nil resp=nil", vitals())
        store.ingest(GarminFileIngest(file(2), sleeps = listOf(scenarioNight), days = listOf(day(sleep = 480.0))), id, 1)
        assertEquals(OVERNIGHT_EXPECTED, vitals())
        assertEquals(null, dailyDouble("respRateBpm", day = "2026-10-31"))
    }

    @Test
    fun anEveningFileRefreshesTheWakeDay() = runBlocking {
        store.ingest(GarminFileIngest(file(1), sleeps = listOf(scenarioNight), days = listOf(day(sleep = 480.0)),
            deviceMetric = scenarioSamples.filter { it.ts >= dayStart }), id, 1)
        store.ingest(GarminFileIngest(file(2), deviceMetric = scenarioSamples.filter { it.ts < dayStart },
            days = listOf(prevDay()), nightRefreshDays = listOf(day())), id, 1)
        assertEquals(OVERNIGHT_EXPECTED, vitals())
    }

    @Test
    fun rederiveCorrectsACalendarDayMean() = runBlocking {
        store.ingest(GarminFileIngest(file(1), sleeps = listOf(scenarioNight), deviceMetric = scenarioSamples,
            days = listOf(day(sleep = 480.0))), id, 1)
        db.openHelper.writableDatabase.execSQL("UPDATE dailyMetric SET spo2Pct = 84.6, respRateBpm = 13.95 WHERE deviceId = '$id'")
        db.openHelper.writableDatabase.execSQL("UPDATE metricSeries SET value = 13.95 WHERE deviceId = '$id' AND `key` = 'resp_rate'")
        store.rederiveNightVitals(id, listOf(day(), prevDay()))
        assertEquals(OVERNIGHT_EXPECTED, vitals())
        assertEquals(listOf("2026-11-01"), store.days(id))
    }

    @Test
    fun tooFewNightSamplesClearTheValue() = runBlocking {
        store.ingest(GarminFileIngest(file(1), sleeps = listOf(scenarioNight), days = listOf(day(sleep = 480.0)),
            deviceMetric = listOf(m(3_600, DeviceMetricKind.RESPIRATION_BPM, 15.0), m(7_200, DeviceMetricKind.RESPIRATION_BPM, 16.0))), id, 1)
        assertEquals("row spo2=nil resp=nil series spo2=nil resp=nil", vitals())
    }

    private companion object {
        /** Swift `GarminSyncStoreTests.overnightExpected`, verbatim. */
        const val OVERNIGHT_EXPECTED = "row spo2=95.0000 resp=15.2500 series spo2=95.0000 resp=15.2500"
    }
}
