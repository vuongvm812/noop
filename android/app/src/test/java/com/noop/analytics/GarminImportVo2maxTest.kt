package com.noop.analytics

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.noop.data.DeviceRegistry
import com.noop.data.MetricSeriesRow
import com.noop.data.PairedDeviceRow
import com.noop.data.ScoreInputProvenanceRow
import com.noop.data.WhoopDatabase
import com.noop.data.WhoopRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #71: the VO2max resolver reads the Garmin export's points beside the watch's own, against a real Room
 * database. The watch wins a day both carry; the export fills the days the watch lacks; earlier days are
 * written with the `garmin` tag. Swift twin: `GarminImportVo2maxTests` (StrandTests). All data is synthetic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class GarminImportVo2maxTest {

    private val db: WhoopDatabase = Room.inMemoryDatabaseBuilder(
        InstrumentationRegistry.getInstrumentation().targetContext,
        WhoopDatabase::class.java,
    ).allowMainThreadQueries().build()
    private val repo = WhoopRepository(db)
    private val registry = DeviceRegistry(db.whoopDao(), object : DeviceRegistry.Transactor {
        override suspend fun <R> run(block: suspend () -> R): R = block()
    })
    private val watch = "garmin-TEST-0001"
    private val computed = "$watch-noop"

    @After
    fun close() = db.close()

    private fun seed() = runBlocking {
        repo.upsertMetricSeries(listOf(MetricSeriesRow(watch, "2026-10-20", "vo2max", 52.0)))
        repo.upsertMetricSeries(listOf(
            MetricSeriesRow("garmin-import", "2026-09-15", "vo2max", 49.0),
            MetricSeriesRow("garmin-import", "2026-10-20", "vo2max", 48.0),
        ))
        // NOOP's own estimate for a week the export now covers.
        repo.upsertMetricSeriesWithProvenance(
            rows = listOf(MetricSeriesRow(computed, "2026-09-26", "vo2max_est", 40.0)),
            provenance = listOf(ScoreInputProvenanceRow(computed, "2026-09-26", "vo2max_est", "uth")),
        )
    }

    /** The pass runs under the canonical "my-whoop"; the registry's active device names the watch (#70). */
    @Test
    fun watchWinsItsDaysAndTheExportFillsTheRest() = runBlocking {
        seed()
        // Before the user switches to the watch, no Garmin watch is active and nothing resolves.
        assertNull(IntelligenceEngine.activeGarminWatchId(repo, "my-whoop"))
        assertNull(IntelligenceEngine.watchVo2max(repo, "my-whoop", "2026-09-19"))
        registry.add(PairedDeviceRow(id = watch, brand = "Garmin", model = "Forerunner 165", nickname = null,
            sourceKind = "garmin", capabilities = "hr", status = "paired", addedAt = 1, lastSeenAt = 1))
        registry.setActive(watch)
        assertEquals(watch, IntelligenceEngine.activeGarminWatchId(repo, "my-whoop"))
        assertEquals(52.0, IntelligenceEngine.watchVo2max(repo, "my-whoop", "2026-10-24")!!, 0.0)   // the watch wins the shared day
        assertEquals(49.0, IntelligenceEngine.watchVo2max(repo, "my-whoop", "2026-09-19")!!, 0.0)   // the export fills the gap
        assertNull(IntelligenceEngine.watchVo2max(repo, "my-whoop", "2026-10-17"))
    }

    @Test
    fun earlierDaysTakeTheGarminValueAndTag() = runBlocking {
        seed()
        IntelligenceEngine.persistWatchVo2maxHistory(repo, watch, computed, "2026-10-31")
        val rows = repo.metricSeries(computed, "vo2max_est", "0000-01-01", "9999-12-31")
        // Daily: the export's 49 stands for 28 days from 09-15, then a gap, then the watch's 52 from 10-20.
        val expected = (15..30).map { "2026-09-$it" to 49.0 } + (1..12).map { "2026-10-%02d".format(it) to 49.0 } +
            (20..30).map { "2026-10-$it" to 52.0 }
        assertEquals(expected, rows.map { it.day to it.value })
        val sources = repo.scoreInputSources(computed, "0000-01-01", "9999-12-31", listOf("vo2max_est"))
        assertEquals(rows.map { it.day }, sources.map { it.day })
        assertTrue(sources.all { it.sourceId == "garmin" })
    }

    @Test
    fun anotherActiveDeviceWritesNothing() = runBlocking {
        seed()
        IntelligenceEngine.persistWatchVo2maxHistory(repo, "my-whoop", "my-whoop-noop", "2026-10-31")
        assertTrue(repo.metricSeries("my-whoop-noop", "vo2max_est", "0000-01-01", "9999-12-31").isEmpty())
    }
}
