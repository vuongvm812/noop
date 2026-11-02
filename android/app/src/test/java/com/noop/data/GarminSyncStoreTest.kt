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
            sleeps = listOf(GarminSleepRow(dayStart - 20_000, dayStart + 5_000, 91.0, null, null, "[]")),
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

    @Test
    fun recordWithoutDataIsIdempotent() = runBlocking {
        store.record(file(7), id, 1)
        store.record(file(7), id, 2)
        assertEquals(listOf(file(7)), store.syncedFiles(id))
    }
}
