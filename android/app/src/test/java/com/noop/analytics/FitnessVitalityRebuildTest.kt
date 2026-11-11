package com.noop.analytics

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.noop.data.DailyMetric
import com.noop.data.MetricSeriesRow
import com.noop.data.ScoreInputProvenanceRow
import com.noop.data.WhoopDatabase
import com.noop.data.WhoopRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * The one-shot daily rebuild of Fitness Age / VO₂max / Vitality / Body Age replaces the old Saturday-only
 * points with one point per day, in one transaction, and never blanks the series when there is no history.
 * All data is synthetic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class FitnessVitalityRebuildTest {

    private val db: WhoopDatabase = Room.inMemoryDatabaseBuilder(
        InstrumentationRegistry.getInstrumentation().targetContext,
        WhoopDatabase::class.java,
    ).allowMainThreadQueries().build()
    private val repo = WhoopRepository(db)
    private val computed = "my-whoop-noop"
    private val profile = UserProfile(weightKg = 80.0, heightCm = 175.0, age = 40.0, sex = "male", waistCm = 90.0)

    @After
    fun close() = db.close()

    private fun series(key: String) = runBlocking { repo.metricSeries(computed, key, "0000-01-01", "9999-12-31") }

    @Test
    fun rebuildWritesDailyPointsOverTheSaturdayOnes() = runBlocking {
        val today = LocalDate.now()
        repo.upsertDailyMetrics((0 until 10).map { i ->
            DailyMetric(
                deviceId = computed, day = today.minusDays(i.toLong()).toString(), totalSleepMin = 420.0,
                restingHr = 58 + i % 3, avgHrv = 55.0, strain = 45.0, steps = 8_000,
            )
        })
        // An old Saturday-only point on a day the rebuild recomputes (an old Saturday inside the data), and one
        // long before any daily history that it cannot recompute.
        val oldSat = today.minusDays(((today.dayOfWeek.value + 1) % 7).toLong()).toString()
        repo.upsertMetricSeriesWithProvenance(
            rows = listOf(MetricSeriesRow(computed, oldSat, "fitness_age", 99.0),
                MetricSeriesRow(computed, "2020-01-04", "fitness_age", 99.0),
                MetricSeriesRow(computed, "2020-01-04", "vo2max_est", 20.0)),
            provenance = listOf(ScoreInputProvenanceRow(computed, "2020-01-04", "vo2max_est", "uth")),
        )
        assertTrue(IntelligenceEngine.rebuildDailyFitnessVitalityHistory(repo, profile, "my-whoop"))
        val fa = series("fitness_age")
        assertTrue("a recomputed Saturday is overwritten", fa.none { it.day == oldSat && it.value == 99.0 })
        assertEquals("a Saturday it cannot recompute is kept", 99.0, fa.single { it.day == "2020-01-04" }.value, 0.0)
        // Daily: today's window holds 7 nights, so today and every earlier day with >= 4 nights has a point.
        assertTrue(fa.any { it.day == today.toString() })
        assertTrue(fa.size > 3)
        assertEquals(fa.map { it.day }, fa.map { it.day }.sorted().distinct())
        assertTrue(series("vitality").any { it.day == today.toString() })
        assertTrue(series("body_age").any { it.day == today.toString() })
    }

    @Test
    fun noHistoryKeepsTheOldPointsAndReportsNotDone() = runBlocking {
        repo.upsertMetricSeries(listOf(MetricSeriesRow(computed, "2020-01-04", "fitness_age", 41.0)))
        assertFalse(IntelligenceEngine.rebuildDailyFitnessVitalityHistory(repo, profile, "my-whoop"))
        assertEquals(listOf("2020-01-04"), series("fitness_age").map { it.day })
    }
}
