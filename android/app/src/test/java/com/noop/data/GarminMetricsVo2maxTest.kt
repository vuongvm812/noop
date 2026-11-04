package com.noop.data

import android.content.Context
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.noop.analytics.IntelligenceEngine
import com.noop.analytics.UserProfile
import com.noop.garmin.GarminDownloadedFile
import com.noop.garmin.GarminFitFileType
import com.noop.garmin.GarminSyncFileKey
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * The watch's own VO2max reaches the VO2max tile and card from a synced METRICS file (#70): the one-shot
 * METRICS re-download, then decode → fold → store → the weekly resolver, under the id the post-sync pass
 * really runs with ("my-whoop") while the registry's active device is the watch. Swift twin:
 * `GarminMetricsVo2maxTests` (StrandTests); [SUMMARY] is that test's dump, pasted verbatim. All data is synthetic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class GarminMetricsVo2maxTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db: WhoopDatabase = Room.inMemoryDatabaseBuilder(context, WhoopDatabase::class.java)
        .allowMainThreadQueries().build()
    private val repo = WhoopRepository(db)
    private val store = GarminSyncStore(db)
    private val registry = DeviceRegistry(db.whoopDao(), object : DeviceRegistry.Transactor {
        override suspend fun <R> run(block: suspend () -> R): R = block()
    })
    private val prefs = context.getSharedPreferences("GarminMetricsVo2maxTest", Context.MODE_PRIVATE)
    private val watch = "garmin-TEST-0070"

    @After
    fun close() {
        db.close()
        prefs.edit().clear().apply()
    }

    private fun row(dataType: Int, subType: Int, n: Int) = GarminSyncedFileRow(n, dataType, subType, n, 1_100_000_000L + n, 10)

    @Test
    fun metricsFileVo2maxReachesTheTileAndTheCard() = runBlocking {
        registry.add(PairedDeviceRow(id = watch, brand = "Garmin", model = "Forerunner 165", nickname = null,
            sourceKind = "garmin", capabilities = "hr", status = "paired", addedAt = 1, lastSeenAt = 1))
        registry.setActive(watch)   // the user switched to the watch
        val lines = mutableListOf<String>()

        // Before #51 these METRICS files were recorded without their VO2max; the other types stay synced.
        val metrics = GarminFitFileType.METRICS.raw
        for (r in listOf(row(255, metrics, 1), row(128, metrics, 2), row(255, 32, 3), row(128, 32, 4), row(255, 49, 5))) {
            store.record(r, watch, 1)
        }
        store.record(row(255, metrics, 6), "garmin-OTHER", 1)
        val before = store.syncedFiles(watch).size
        GarminHistoryIngest.refetchMetricsFilesOnce(store, watch, prefs) {}
        val kept = store.syncedFiles(watch)
        lines += "forgotten ${before - kept.size}"
        lines += "kept " + kept.map { "${it.dataType}/${it.subType}" }.sorted().joinToString(" ")
        lines += "other device kept ${store.syncedFiles("garmin-OTHER").size}"
        // Once per watch: a METRICS file synced after the repair stays synced.
        store.record(row(255, metrics, 1), watch, 2)
        val afterRepair = store.syncedFiles(watch).size
        GarminHistoryIngest.refetchMetricsFilesOnce(store, watch, prefs) {}
        lines += "second run forgotten ${afterRepair - store.syncedFiles(watch).size}"

        // The re-downloaded file: a VO2max measured ten days ago at local noon, inside the 28-day window of
        // this week's Saturday key and on or before it.
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val noon = today.minusDays(10).atTime(12, 0).atZone(zone).toEpochSecond()
        val bytes = metricsFile(noon, 523)
        val key = GarminSyncFileKey(1, 255, metrics, 1, 1_100_000_001L, bytes.size.toLong())
        assertTrue(GarminHistoryIngest.ingest(GarminDownloadedFile(key, bytes, null, null), watch, store, repo) {})
        val day = Instant.ofEpochSecond(noon).atZone(zone).toLocalDate().toString()
        lines += "stored vo2max " + repo.metricSeries(watch, "vo2max", day, day).joinToString(",") { it.value.toString() }

        // The weekly recompute, under the canonical id the post-sync pass runs with.
        assertTrue(IntelligenceEngine.recomputeFitnessAgeOnly(repo, UserProfile(), "my-whoop"))
        // The Today tile and the VO2max card both read the active strap's computed union, plus the card's tag.
        val latest = repo.latestMetricComputedUnion(watch, "vo2max_est")
        assertNotNull(latest)
        assertEquals(today.with(TemporalAdjusters.previousOrSame(DayOfWeek.SATURDAY)).toString(), latest!!.day)
        val tag = repo.scoreInputSource(latest.deviceId, latest.day, latest.key)
        lines += "vo2max_est ${latest.value} $tag"
        assertEquals(Vo2MaxEstimator.GARMIN, Vo2MaxEstimator.fromProvenanceId(tag))

        assertEquals(SUMMARY, lines.joinToString("\n"))
    }

    private companion object {
        /** Stdout of the Swift twin's dump (`GarminMetricsVo2maxTests.summary`), verbatim. */
        val SUMMARY = """
            forgotten 2
            kept 128/32 255/32 255/49
            other device kept 1
            second run forgotten 0
            stored vo2max 52.3
            vo2max_est 52.3 garmin
        """.trimIndent()

        /** Twin of the Swift `metricsFile`: one MAX_MET_DATA (229) record in a METRICS file. */
        fun metricsFile(unix: Long, vo2Tenths: Int): ByteArray {
            fun le(v: Long, n: Int) = (0 until n).map { ((v shr (8 * it)) and 0xFF).toInt() }
            val records = listOf(0x40, 0, 0) + le(229, 2) + listOf(2, 0, 4, 0x86, 2, 2, 0x84) +
                listOf(0x00) + le(unix - 631_065_600L, 4) + le(vo2Tenths.toLong(), 2)
            val out = ByteArrayOutputStream()
            (listOf(12, 0x20, 0, 0) + le(records.size.toLong(), 4)).forEach(out::write)
            out.write(".FIT".toByteArray())
            records.forEach(out::write)
            out.write(0); out.write(0)
            return out.toByteArray()
        }
    }
}
