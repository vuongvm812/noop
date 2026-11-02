package com.noop.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `deviceMetricSample` and `garminSyncedFile` against a real Room database (Robolectric), so the
 * dedup keys are proven by SQLite rather than by a mocked DAO. Swift twin: `GarminMetricStorageTests`,
 * which runs the same cases against GRDB.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class GarminMetricStorageRoomTest {

    private val db: WhoopDatabase = Room.inMemoryDatabaseBuilder(
        InstrumentationRegistry.getInstrumentation().targetContext,
        WhoopDatabase::class.java,
    ).allowMainThreadQueries().build()

    private val repo = WhoopRepository(db)

    @After
    fun close() = db.close()

    private fun samples(stress: Double = 42.0) = StreamBatch(deviceMetric = listOf(
        DeviceMetricRow(1_760_000_000L, DeviceMetricKind.STRESS, stress, "fit.stress_level"),
        DeviceMetricRow(1_760_000_000L, DeviceMetricKind.BODY_BATTERY, 77.0, "fit.stress_level"),
        DeviceMetricRow(1_760_000_060L, DeviceMetricKind.STRESS, 30.0, "fit.stress_level"),
    ))

    /** (kind, ts, value) for one device, in key order. */
    private fun metricRows(deviceId: String): List<Triple<String, Long, Double>> =
        db.openHelper.readableDatabase.query(
            "SELECT kind, ts, value FROM deviceMetricSample WHERE deviceId = ? ORDER BY kind, ts",
            arrayOf(deviceId),
        ).use { c ->
            buildList { while (c.moveToNext()) add(Triple(c.getString(0), c.getLong(1), c.getDouble(2))) }
        }

    private fun count(sql: String): Int =
        db.openHelper.readableDatabase.query(sql).use { c -> c.moveToFirst(); c.getInt(0) }

    /** The acceptance criterion: re-ingesting a file is idempotent. */
    @Test
    fun ingestingTheSameSamplesTwiceLeavesOneCopy() = runBlocking {
        repo.insert(samples(), deviceId = "garmin-1")
        val first = metricRows("garmin-1")
        repo.insert(samples(), deviceId = "garmin-1")
        assertEquals(
            listOf(
                Triple("body_battery", 1_760_000_000L, 77.0),
                Triple("stress", 1_760_000_000L, 42.0),
                Triple("stress", 1_760_000_060L, 30.0),
            ),
            first,
        )
        assertEquals(first, metricRows("garmin-1"))
    }

    /** Upsert, not ignore: a re-synced value for the same (kind, second) replaces the old one. */
    @Test
    fun aReSyncedValueReplacesTheOldOne() = runBlocking {
        repo.insert(samples(42.0), deviceId = "garmin-1")
        repo.insert(samples(55.0), deviceId = "garmin-1")
        val rows = metricRows("garmin-1")
        assertEquals(3, rows.size)
        assertEquals(55.0, rows.single { it.first == "stress" && it.second == 1_760_000_000L }.third, 0.0)
    }

    @Test
    fun theKeyIsPerDevice() = runBlocking {
        repo.insert(samples(), deviceId = "garmin-1")
        repo.insert(samples(), deviceId = "garmin-2")
        assertEquals(3, metricRows("garmin-1").size)
        assertEquals(3, metricRows("garmin-2").size)
    }

    /** The ledger key is the whole directory entry: the same file twice keeps one row and its FIRST
     *  ingest time; the same index rewritten larger is a new row. */
    @Test
    fun syncedFileKeyIsTheWholeDirectoryEntry() {
        val w = db.openHelper.writableDatabase
        val insert = "INSERT INTO garminSyncedFile " +
            "(deviceId, fileIndex, dataType, subType, fileNumber, ts, size, ingestedAt) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING"
        w.execSQL(insert, arrayOf<Any>("garmin-1", 7, 128, 32, 1, 1_100_000_000L, 4096L, 1_760_000_000L))
        w.execSQL(insert, arrayOf<Any>("garmin-1", 7, 128, 32, 1, 1_100_000_000L, 4096L, 1_760_009_999L))
        w.execSQL(insert, arrayOf<Any>("garmin-1", 7, 128, 32, 1, 1_100_000_000L, 8192L, 1_760_010_000L))
        assertEquals(2, count("SELECT COUNT(*) FROM garminSyncedFile"))
        assertEquals(1_760_000_000, count("SELECT ingestedAt FROM garminSyncedFile WHERE size = 4096"))
    }
}
