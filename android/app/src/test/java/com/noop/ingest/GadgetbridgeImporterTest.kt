package com.noop.ingest

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.noop.data.WhoopDatabase
import java.io.File
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A tiny Gadgetbridge-schema SQLite file → import → rows in the shared tables. Synthetic data only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class GadgetbridgeImporterTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db: WhoopDatabase = Room.inMemoryDatabaseBuilder(context, WhoopDatabase::class.java)
        .allowMainThreadQueries().build()
    private val dir = File(context.cacheDir, "gb-test-${System.nanoTime()}").apply { mkdirs() }
    private val utc = ZoneId.of("UTC")
    private val d0 = 1_761_955_200L // 2025-11-01T00:00:00Z
    private val now = d0 + 3 * 86_400

    @After
    fun close() { db.close(); dir.deleteRecursively() }

    private fun gbFile(withHuaweiTables: Boolean = true, manufacturer: String = "Huawei"): File {
        val f = File(dir, "Gadgetbridge").also { it.delete() }
        SQLiteDatabase.openOrCreateDatabase(f, null).use { s ->
            s.execSQL("CREATE TABLE DEVICE (_id INTEGER PRIMARY KEY, NAME TEXT, MANUFACTURER TEXT)")
            s.execSQL("INSERT INTO DEVICE VALUES (1, 'HUAWEI Band 10', '$manufacturer'), (2, 'Other', 'Xiaomi')")
            if (!withHuaweiTables) return@use
            s.execSQL("CREATE TABLE HUAWEI_ACTIVITY_SAMPLE (TIMESTAMP INTEGER, DEVICE_ID INTEGER, OTHER_TIMESTAMP INTEGER, SOURCE INTEGER, " +
                "STEPS INTEGER, CALORIES INTEGER, DISTANCE INTEGER, SPO INTEGER, HEART_RATE INTEGER, RESTING_HEART_RATE INTEGER)")
            // Day 0 afternoon: 100 + 50 steps, HR 60 / 62. A sentinel row, and a SOURCE 13 row that must be ignored.
            s.execSQL("INSERT INTO HUAWEI_ACTIVITY_SAMPLE VALUES (${d0 + 43_200}, 1, 0, 11, 100, -1, 80, -1, 60, 55)")
            s.execSQL("INSERT INTO HUAWEI_ACTIVITY_SAMPLE VALUES (${d0 + 43_260}, 1, 0, 11, 50, -1, 40, -1, 62, 0)")
            s.execSQL("INSERT INTO HUAWEI_ACTIVITY_SAMPLE VALUES (${d0 + 43_320}, 1, 0, 11, -1, -1, -1, -1, -1, -1)")
            s.execSQL("INSERT INTO HUAWEI_ACTIVITY_SAMPLE VALUES (${d0 + 43_380}, 1, 0, 13, 9999, -1, -1, -1, 70, -1)")
            s.execSQL("INSERT INTO HUAWEI_ACTIVITY_SAMPLE VALUES (${d0 + 43_440}, 2, 0, 11, 7777, -1, -1, -1, 71, -1)")
            s.execSQL("CREATE TABLE HUAWEI_SLEEP_STATS_SAMPLE (TIMESTAMP INTEGER, DEVICE_ID INTEGER, SLEEP_SCORE INTEGER, BED_TIME INTEGER, " +
                "RISING_TIME INTEGER, WAKEUP_TIME INTEGER, AVG_HRV INTEGER, AVG_HEART_RATE INTEGER)")
            val bed = (d0 + 3_600) * 1000
            s.execSQL("INSERT INTO HUAWEI_SLEEP_STATS_SAMPLE VALUES ($bed, 1, 82, $bed, ${bed + 600_000}, ${bed + 600_000}, 63, 58)")
            s.execSQL("CREATE TABLE HUAWEI_SLEEP_STAGE_SAMPLE (TIMESTAMP INTEGER, DEVICE_ID INTEGER, STAGE INTEGER)")
            for (i in 0 until 10) s.execSQL("INSERT INTO HUAWEI_SLEEP_STAGE_SAMPLE VALUES (${bed + i * 60_000L}, 1, ${if (i < 5) 1 else 3})")
            s.execSQL("CREATE TABLE HUAWEI_STRESS_SAMPLE (TIMESTAMP INTEGER, DEVICE_ID INTEGER, STRESS INTEGER, LEVEL INTEGER, START_TIME INTEGER)")
            s.execSQL("INSERT INTO HUAWEI_STRESS_SAMPLE VALUES (${(d0 + 50_000) * 1000}, 1, 38, 2, ${(d0 + 49_900) * 1000})")
            s.execSQL("CREATE TABLE HUAWEI_WORKOUT_SUMMARY_SAMPLE (WORKOUT_ID INTEGER PRIMARY KEY, DEVICE_ID INTEGER, START_TIMESTAMP INTEGER, " +
                "END_TIMESTAMP INTEGER, CALORIES INTEGER, DISTANCE INTEGER, STEP_COUNT INTEGER, TOTAL_TIME INTEGER, DURATION INTEGER, TYPE INTEGER, " +
                "MAX_HEART_RATE_PEAK INTEGER, WORKOUT_LOAD INTEGER, ELEVATION_GAIN INTEGER)")
            s.execSQL("INSERT INTO HUAWEI_WORKOUT_SUMMARY_SAMPLE VALUES (4, 1, ${d0 + 60_000}, ${d0 + 62_000}, 357, 5200, 4100, 2000, 2000, 1, 171, 90, NULL)")
            s.execSQL("CREATE TABLE HUAWEI_WORKOUT_DATA_SAMPLE (WORKOUT_ID INTEGER, TIMESTAMP INTEGER, HEART_RATE INTEGER)")
            s.execSQL("INSERT INTO HUAWEI_WORKOUT_DATA_SAMPLE VALUES (4, ${d0 + 60_030}, 130), (4, ${d0 + 60_060}, 140)")
        }
        return f
    }

    private fun import(uri: Uri, stored: () -> Unit = {}) = runBlocking {
        GadgetbridgeImporter.importExport(context, uri, db, utc, now, stored)
    }

    private fun count(sql: String): Long = db.openHelper.readableDatabase.query(sql).use { it.moveToFirst(); it.getLong(0) }

    @Test
    fun importsBareDatabaseIntoSharedTables() {
        var called = 0
        val summary = import(Uri.fromFile(gbFile())) { called++ }
        assertTrue(summary.message, summary.totalRows > 0)
        assertEquals(1, called)
        assertEquals("2025-10-31".length, summary.firstDay!!.length)
        // Two HR minutes of device 1 (no other device, no source-13 row), plus the workout's two samples.
        assertEquals(4L, count("SELECT COUNT(*) FROM hrSample WHERE deviceId = 'huawei-import'"))
        assertEquals(150L, count("SELECT steps FROM dailyMetric WHERE deviceId = 'huawei-import' AND day = '2025-11-01' AND steps IS NOT NULL"))
        assertEquals(1L, count("SELECT COUNT(*) FROM sleepSession WHERE deviceId = 'huawei-import'"))
        assertEquals(1L, count("SELECT COUNT(*) FROM workout WHERE deviceId = 'huawei-import'"))
        assertEquals(1L, count("SELECT COUNT(*) FROM metricSeries WHERE deviceId = 'huawei-import' AND `key` = 'steps'"))
        // Nothing lands under any other id, and re-importing keeps the same rows.
        assertEquals(0L, count("SELECT COUNT(*) FROM hrSample WHERE deviceId <> 'huawei-import'"))
        import(Uri.fromFile(gbFile()))
        assertEquals(1L, count("SELECT COUNT(*) FROM sleepSession WHERE deviceId = 'huawei-import'"))
        assertEquals(1L, count("SELECT COUNT(*) FROM workout WHERE deviceId = 'huawei-import'"))
        assertEquals(4L, count("SELECT COUNT(*) FROM hrSample WHERE deviceId = 'huawei-import'"))
        // The cache copy is gone.
        assertEquals(emptyList<String>(), context.cacheDir.listFiles { f -> f.name.startsWith("gadgetbridge-") }!!.map { it.name })
    }

    @Test
    fun importsFromZipExport() {
        val zip = File(dir, "export.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("prefs.json")); z.write("{}".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("Gadgetbridge")); z.write(gbFile().readBytes()); z.closeEntry()
        }
        assertTrue(import(Uri.fromFile(zip)).totalRows > 0)
        assertEquals(1L, count("SELECT COUNT(*) FROM workout WHERE deviceId = 'huawei-import'"))
    }

    @Test
    fun rejectsNonGadgetbridgeFiles() {
        var called = false
        val text = File(dir, "x.txt").apply { writeText("hello, this is not a database at all") }
        assertEquals(0, import(Uri.fromFile(text)) { called = true }.totalRows)

        val noHuawei = import(Uri.fromFile(gbFile(withHuaweiTables = false))) { called = true }
        assertEquals(0, noHuawei.totalRows)
        assertTrue(noHuawei.message, noHuawei.message.contains("Gadgetbridge"))

        val other = File(dir, "other.db")
        SQLiteDatabase.openOrCreateDatabase(other, null).use { it.execSQL("CREATE TABLE steps (a INTEGER)") }
        assertEquals(0, import(Uri.fromFile(other)) { called = true }.totalRows)

        val noHuaweiDevice = import(Uri.fromFile(gbFile(manufacturer = "Garmin"))) { called = true }
        assertEquals(0, noHuaweiDevice.totalRows)
        assertFalse(called)
        assertEquals(0L, count("SELECT COUNT(*) FROM hrSample"))
    }

    @Test
    fun oldSchemaAndJunkTimestampsStillImport() {
        val f = File(dir, "Gadgetbridge").also { it.delete() }
        SQLiteDatabase.openOrCreateDatabase(f, null).use { s ->
            s.execSQL("CREATE TABLE DEVICE (_id INTEGER PRIMARY KEY, NAME TEXT, MANUFACTURER TEXT)")
            s.execSQL("INSERT INTO DEVICE VALUES (1, 'HUAWEI Band', 'Huawei')")
            // No SPO / RESTING_HEART_RATE / DISTANCE columns (older schema); a NULL steps cell; a far-future junk row.
            s.execSQL("CREATE TABLE HUAWEI_ACTIVITY_SAMPLE (TIMESTAMP INTEGER, DEVICE_ID INTEGER, SOURCE INTEGER, STEPS INTEGER, HEART_RATE INTEGER)")
            s.execSQL("INSERT INTO HUAWEI_ACTIVITY_SAMPLE VALUES (${d0 + 100}, 1, 11, NULL, 66)")
            s.execSQL("INSERT INTO HUAWEI_ACTIVITY_SAMPLE VALUES (${d0 + 160}, 1, 11, 40, 67)")
            s.execSQL("INSERT INTO HUAWEI_ACTIVITY_SAMPLE VALUES (4102444800, 1, 11, 5, 68)")
        }
        val summary = import(Uri.fromFile(f))
        assertTrue(summary.message, summary.totalRows > 0)
        assertEquals("2025-11-01", summary.lastDay)
        assertEquals(40L, count("SELECT steps FROM dailyMetric WHERE deviceId = 'huawei-import'"))
        assertEquals(2L, count("SELECT COUNT(*) FROM hrSample WHERE deviceId = 'huawei-import'"))
    }
}
