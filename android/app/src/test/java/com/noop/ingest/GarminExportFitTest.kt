package com.noop.ingest

import android.content.Context
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.noop.data.GarminHistoryIngest
import com.noop.data.GarminSyncStore
import com.noop.data.WhoopDatabase
import com.noop.data.WhoopRepository
import com.noop.ui.WorkoutEditing
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The Garmin Connect export's FIT files: nested-zip collection, routing by `file_id`, storage under
 * "garmin-import", idempotent re-import and cross-source dedup. Swift twin: `GarminExportFitTests`
 * (StrandImport, collection + routing) and `GarminExportFitIngestTests` (StrandTests, storage). All data is synthetic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class GarminExportFitTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db: WhoopDatabase = Room.inMemoryDatabaseBuilder(context, WhoopDatabase::class.java)
        .allowMainThreadQueries().build()
    private val repo = WhoopRepository(db.whoopDao())
    private val store = GarminSyncStore(db)

    @After
    fun close() = db.close()

    private fun count(table: String): Int =
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table WHERE deviceId = ?", arrayOf<Any?>(GarminExportFit.DEVICE_ID))
            .use { c -> c.moveToFirst(); c.getInt(0) }

    private fun metricCount(kind: String): Int =
        db.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM deviceMetricSample WHERE deviceId = ? AND kind = ?", arrayOf<Any?>(GarminExportFit.DEVICE_ID, kind),
        ).use { c -> c.moveToFirst(); c.getInt(0) }

    /** [fit]'s records behind a `file_id` message of [type]. */
    private fun withFileId(type: Int, fit: ByteArray): ByteArray {
        val body = fit.copyOfRange(12, fit.size - 2)
        val f = GarminWellnessParserTest.WellnessFit()
        f.def(15, 0, listOf(Triple(0, 1, GarminWellnessParserTest.EN), Triple(4, 4, GarminWellnessParserTest.U32)))
        f.data(15); f.u8(type); f.u32(GarminWellnessParserTest.T)
        return rebuild(f.finish(), body)
    }

    /** The file of [head]'s records plus [body], with the header's data size rewritten. */
    private fun rebuild(head: ByteArray, body: ByteArray): ByteArray {
        val records = head.copyOfRange(12, head.size - 2) + body
        val out = ByteArrayOutputStream()
        out.write(12); out.write(0x20); out.write(0); out.write(0)
        for (k in 0 until 4) out.write((records.size shr (8 * k)) and 0xFF)
        out.write(".FIT".toByteArray())
        out.write(records)
        out.write(0); out.write(0)
        return out.toByteArray()
    }

    /** A 30-minute run: session summary, three records with HR, power, cadence and altitude (and no GPS). */
    private fun activity(startFit: Long = GarminWellnessParserTest.T): ByteArray {
        val f = GarminWellnessParserTest.WellnessFit()
        val u8 = GarminWellnessParserTest.U8; val u16 = GarminWellnessParserTest.U16; val u32 = GarminWellnessParserTest.U32
        f.def(0, 18, listOf(
            Triple(2, 4, u32), Triple(5, 1, GarminWellnessParserTest.EN), Triple(7, 4, u32), Triple(9, 4, u32),
            Triple(11, 2, u16), Triple(16, 1, u8), Triple(17, 1, u8),
        ))
        f.data(0); f.u32(startFit); f.u8(1); f.u32(1_800_000L); f.u32(500_000L); f.u16(310); f.u8(150); f.u8(178)
        f.def(1, 20, listOf(Triple(253, 4, u32), Triple(3, 1, u8), Triple(7, 2, u16), Triple(4, 1, u8), Triple(2, 2, u16)))
        for (k in 0 until 3) {
            f.data(1); f.u32(startFit + k * 10L); f.u8(140 + k * 5); f.u16(200 + k * 10); f.u8(80 + k); f.u16(2600 + k * 5)
        }
        return withFileId(4, f.finish())
    }

    // MARK: - Collection

    private fun zipOf(entries: List<Pair<String, ByteArray>>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            for ((name, data) in entries) {
                zos.putNextEntry(ZipEntry(name)); zos.write(data); zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private val json = """{"calendarDate":"2026-06-08"}""".toByteArray()

    @Test
    fun nestedZipsAreExpandedAndFitEntriesCollected() {
        val inner = zipOf(listOf("a/run.fit" to activity(), "b/sleep.fit" to withFileId(49, GarminWellnessParserTest.sleepWindow())))
        val outer = zipOf(listOf("DI-Connect/Uploaded/UploadedFiles_1.zip" to inner, "DI-Connect/DI-Connect-Wellness/2026_sleepData.json" to json))
        val c = WearableExportImporter.collectZipEntries(ByteArrayInputStream(outer))
        assertEquals(listOf("di-connect/uploaded/uploadedfiles_1.zip/a/run.fit", "di-connect/uploaded/uploadedfiles_1.zip/b/sleep.fit"), c.fit.keys.toList())
        assertEquals(listOf("di-connect/di-connect-wellness/2026_sleepdata.json"), c.files.keys.toList())
        assertFalse(c.truncated)
    }

    @Test
    fun nestingDeeperThanTheDepthCapIsNotExpanded() {
        val innermost = zipOf(listOf("deep.fit" to activity()))
        val middle = zipOf(listOf("mid.zip" to innermost, "mid.fit" to activity()))
        val outer = zipOf(listOf("top.zip" to middle))
        val depth2 = WearableExportImporter.collectZipEntries(ByteArrayInputStream(outer), maxDepth = 2)
        assertEquals(listOf("top.zip/mid.fit"), depth2.fit.keys.toList())
        val depth3 = WearableExportImporter.collectZipEntries(ByteArrayInputStream(outer), maxDepth = 3)
        assertEquals(setOf("top.zip/mid.fit", "top.zip/mid.zip/deep.fit"), depth3.fit.keys)
        // The default is two levels: the export zip and the zip inside it.
        assertEquals(2, WearableExportImporter.MAX_ZIP_DEPTH)
    }

    @Test
    fun theByteBudgetCoversNestedArchivesAndFitFiles() {
        val fit = activity()
        val inner = zipOf(listOf("1.fit" to fit, "2.fit" to fit.copyOf().also { it[it.size - 3] = 1 }, "3.fit" to fit.copyOf().also { it[it.size - 3] = 2 }))
        val outer = zipOf(listOf("in.zip" to inner))
        // Room for the nested archive and two FIT files, not the third.
        val c = WearableExportImporter.collectZipEntries(ByteArrayInputStream(outer), maxTotalBytes = inner.size + 2L * fit.size + 10)
        assertTrue("a trip is reported, not silent", c.truncated)
        assertEquals(2, c.fit.size)
        // A budget smaller than the nested archive itself retains nothing from it.
        val none = WearableExportImporter.collectZipEntries(ByteArrayInputStream(outer), maxTotalBytes = inner.size - 1L)
        assertTrue(none.truncated)
        assertTrue(none.fit.isEmpty())
    }

    @Test
    fun anUnreadableNestedArchiveIsSkippedNotFatal() {
        val outer = zipOf(listOf("bad.zip" to ByteArray(64) { 7 }, "ok.fit" to activity()))
        val c = WearableExportImporter.collectZipEntries(ByteArrayInputStream(outer))
        assertEquals(listOf("ok.fit"), c.fit.keys.toList())
    }

    // MARK: - Routing

    @Test
    fun fileIdRoutesByContent() {
        assertEquals(4, GarminExportFit.fileId(activity())!!.type)
        assertEquals(49, GarminExportFit.fileId(withFileId(49, GarminWellnessParserTest.sleepWindow()))!!.type)
        assertEquals(GarminWellnessParserTest.T + 631_065_600L, GarminExportFit.fileId(activity())!!.timeCreatedUnix)
        assertNull("no file_id first", GarminExportFit.fileId(GarminWellnessParserTest.monitoring()))
        assertNull(GarminExportFit.fileId(ByteArray(40)))
    }

    @Test
    fun everyFitKindIsStoredOrCountedByReason() = runBlocking {
        val privateFile = withFileId(41, GarminWellnessParserTest.WellnessFit().also {
            it.def(0, 217, listOf(Triple(0, 1, GarminWellnessParserTest.U8))); it.data(0); it.u8(1)
        }.finish())
        val disruption = withFileId(79, GarminWellnessParserTest.WellnessFit().also {
            it.def(0, 500, listOf(Triple(0, 1, GarminWellnessParserTest.U8))); it.data(0); it.u8(0xFF)
        }.finish())
        val files = linkedMapOf(
            "run.fit" to activity(),
            "mon.fit" to withFileId(32, GarminWellnessParserTest.monitoring()),
            "sleep.fit" to withFileId(49, GarminWellnessParserTest.sleepWindow()),
            "hrv.fit" to withFileId(68, GarminWellnessParserTest.hrv()),
            "vo2.fit" to withFileId(44, GarminWellnessParserTest.vo2max()),
            "priv.fit" to privateFile,
            "disruption.fit" to disruption,
            "settings.fit" to withFileId(2, GarminWellnessParserTest.monitoring()),
            "junk.fit" to ByteArray(40),
            "noworkout.fit" to withFileId(4, GarminWellnessParserTest.WellnessFit().also {
                it.def(0, 18, listOf(Triple(2, 4, GarminWellnessParserTest.U32))); it.data(0); it.u32(1)
            }.finish()),
        )
        val r = GarminExportFit.ingest(files, repo, store, {}, 0)
        assertEquals(
            mapOf(
                "fit.activity" to 1, "workouts" to 1, "fit.monitoring" to 1, "fit.sleep" to 1, "fit.hrvStatus" to 1, "fit.metrics" to 1,
                "skipped.privateBackup" to 1, "skipped.invalidData" to 1, "skipped.unsupportedType" to 1,
                "skipped.notFit" to 1, "skipped.noUsableActivity" to 1,
            ),
            r.counts,
        )
        assertEquals(1, r.workouts)
        assertEquals(5, r.storedFiles)
        assertEquals(5, r.skippedFiles)
    }

    // MARK: - Storage

    @Test
    fun anActivityStoresItsRowHeartRateAndPerRecordTrack() = runBlocking {
        GarminExportFit.ingest(mapOf("run.fit" to activity()), repo, store, {}, 0)
        val start = GarminWellnessParserTest.T + 631_065_600L
        val rows = repo.workouts(GarminExportFit.DEVICE_ID, 0, Long.MAX_VALUE / 2, 10)
        assertEquals(1, rows.size)
        val w = rows.single()
        assertEquals(start, w.startTs)
        assertEquals("Running", w.sport)
        assertEquals(GarminExportFit.DEVICE_ID, w.source)
        assertEquals(150, w.avgHr); assertEquals(178, w.maxHr)
        assertEquals(3, count("hrSample"))
        assertEquals(3, metricCount("power_w"))
        assertEquals(3, metricCount("cadence"))
        assertEquals(3, metricCount("altitude_m"))
    }

    @Test
    fun wellnessFilesReachTheirStreamsAndDays() = runBlocking {
        val files = linkedMapOf(
            "mon.fit" to withFileId(32, GarminWellnessParserTest.monitoring()),
            "sleep.fit" to withFileId(49, GarminWellnessParserTest.sleepWindow()),
            "hrv.fit" to withFileId(68, GarminWellnessParserTest.hrv()),
        )
        GarminExportFit.ingest(files, repo, store, {}, 0)
        assertTrue(count("hrSample") > 0)
        assertEquals(1, count("sleepSession"))
        assertTrue(count("dailyMetric") > 0)
        assertTrue(metricCount("hrv_ms") > 0)
        assertTrue(count("garminSyncedFile") >= 3)
    }

    @Test
    fun reImportingTheSameExportChangesNothing() = runBlocking {
        val files = linkedMapOf(
            "run.fit" to activity(),
            "mon.fit" to withFileId(32, GarminWellnessParserTest.monitoring()),
            "sleep.fit" to withFileId(49, GarminWellnessParserTest.sleepWindow()),
            "vo2.fit" to withFileId(44, GarminWellnessParserTest.vo2max()),
        )
        fun snapshot() = listOf("workout", "hrSample", "deviceMetricSample", "dailyMetric", "sleepSession", "garminSyncedFile", "metricSeries").map { it to count(it) }
        val first = GarminExportFit.ingest(files, repo, store, {}, 0)
        val before = snapshot()
        val second = GarminExportFit.ingest(files, repo, store, {}, 0)
        assertEquals(before, snapshot())
        assertEquals(first, second)
        assertTrue(before.all { it.second > 0 })
    }

    /** The generic Workout-files import stores the same per-record track as the export import. */
    @Test
    fun theWorkoutFileImportStoresTheTrackToo() = runBlocking {
        val file = java.io.File.createTempFile("run", ".fit").also { it.writeBytes(activity()); it.deleteOnExit() }
        val summary = ActivityFileImporter.importExport(context, android.net.Uri.fromFile(file), repo)
        assertEquals(1, summary.counts["workouts"])
        fun n(kind: String) = db.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM deviceMetricSample WHERE deviceId = 'activity-file' AND kind = ?", arrayOf<Any?>(kind),
        ).use { c -> c.moveToFirst(); c.getInt(0) }
        assertEquals(3, n("power_w")); assertEquals(3, n("cadence")); assertEquals(3, n("altitude_m"))
        // Re-importing replaces the same samples.
        ActivityFileImporter.importExport(context, android.net.Uri.fromFile(file), repo)
        assertEquals(3, n("power_w"))
    }

    // MARK: - Dedup

    @Test
    fun anExportActivityShowsOnceBesideTheWatchRowAndBeatsAThinerRow() {
        val a = ActivityFileImporter.parse(activity(), "x.fit").activity!!
        val exportRow = GarminHistoryIngest.workoutRow(a, GarminExportFit.DEVICE_ID)
        val watchRow = GarminHistoryIngest.workoutRow(a, "garmin-ABC").copy(startTs = exportRow.startTs + 30, endTs = exportRow.endTs + 30)
        val kept = WorkoutEditing.dedupCrossSource(listOf(exportRow, watchRow))
        assertEquals("one session, not two", 1, kept.size)

        // Against a thin Health Connect style row (duration and calories only) the FIT row is the richer one.
        val thin = exportRow.copy(deviceId = "apple-health", source = "apple-health", avgHr = null, maxHr = null, distanceM = null, routePolyline = null)
        for (order in listOf(listOf(thin, exportRow), listOf(exportRow, thin))) {
            val one = WorkoutEditing.dedupCrossSource(order).single()
            assertEquals(GarminExportFit.DEVICE_ID, one.deviceId)
            assertNotNull(one.avgHr)
        }
    }
}
