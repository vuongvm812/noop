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
            days = listOf(day(steps = 1_000, rhr = 59)),
            sleeps = listOf(GarminSleepRow(dayStart - 20_000, dayStart + 5_000, 0.91, null, null, "[]")),
        )
        val first = store.ingest(ingest, id, 1)
        assertEquals(2, first.hr)
        assertEquals(2, first.deviceMetric)
        assertEquals(listOf(file(1)), store.syncedFiles(id))
        val tables = listOf("hrSample", "deviceMetricSample", "dailyMetric", "sleepSession", "metricSeries", "garminSyncedFile")
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

    private fun dailyDouble(column: String, device: String = id): Double? =
        db.openHelper.readableDatabase.query("SELECT $column FROM dailyMetric WHERE deviceId = ? AND day = '2026-11-01'", arrayOf<Any?>(device))
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
}
