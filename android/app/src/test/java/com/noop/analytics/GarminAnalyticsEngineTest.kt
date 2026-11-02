package com.noop.analytics

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.noop.R
import com.noop.data.DeviceMetricKind
import com.noop.data.DeviceMetricRow
import com.noop.data.DeviceRegistry
import com.noop.data.GarminDayRollup
import com.noop.data.GarminFileIngest
import com.noop.data.GarminSleepRow
import com.noop.data.GarminSyncStore
import com.noop.data.GarminSyncedFileRow
import com.noop.data.HrRow
import com.noop.data.PairedDeviceRow
import com.noop.data.WhoopDatabase
import com.noop.data.WhoopRepository
import com.noop.ui.DisplayText
import com.noop.ui.ScoreInputProvider
import com.noop.ui.heroEffortExperimental
import com.noop.ui.provenanceDisplayLabel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Garmin child 9, end to end for a Garmin-only profile on Android: synthetic watch days go through the real
 * history-sync storage ([GarminSyncStore.ingest]) into a real Room database, then the real scoring pass
 * ([IntelligenceEngine.analyzeRecent], with the registry as owner source) runs over them. Swift twin:
 * `GarminAnalyticsTests` (StrandTests), same synthetic profile. All data is synthetic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class GarminAnalyticsEngineTest {

    private val db: WhoopDatabase = Room.inMemoryDatabaseBuilder(
        InstrumentationRegistry.getInstrumentation().targetContext,
        WhoopDatabase::class.java,
    ).allowMainThreadQueries().build()
    private val repo = WhoopRepository(db)
    private val store = GarminSyncStore(db)
    private val registry = DeviceRegistry(db.whoopDao(), object : DeviceRegistry.Transactor {
        override suspend fun <R> run(block: suspend () -> R): R = block()
    })
    private val watch = "garmin-TEST-0001"
    private val computed = "$watch-noop"
    private val now = System.currentTimeMillis() / 1000L
    private val offset = java.util.TimeZone.getDefault().getOffset(now * 1000L) / 1000L
    private val todayMidnight = now - Math.floorMod(now + offset, 86_400L)

    @After
    fun close() = db.close()

    private fun watchRhr(i: Int) = 54 + (i % 3)
    private fun watchHrv(i: Int) = 46.0 + ((i * 7) % 9)
    private fun dayKey(ts: Long) = AnalyticsEngine.dayString(ts, offset)

    /** Same shape as the Swift fixture: a night ending on `wake`, HR once a minute, a varying workout. */
    private fun file(i: Int, wake: Long): GarminFileIngest {
        val sleepStart = wake - 3_600
        val sleepEnd = wake + 7 * 3_600
        val workoutStart = wake + 17 * 3_600
        val workoutEnd = workoutStart + (i % 3) * 20 * 60
        val hr = ArrayList<HrRow>()
        var t = wake - 6 * 3_600
        while (t < minOf(now, wake + 86_400)) {
            val bpm = when {
                t in sleepStart until sleepEnd -> 50 + ((t / 60) % 5).toInt()
                t in workoutStart until workoutEnd -> 148 + ((t / 60) % 5).toInt()
                else -> 68 + ((t / 60) % 9).toInt()
            }
            hr.add(HrRow(t, bpm))
            t += 60
        }
        val metrics = ArrayList<DeviceMetricRow>()
        var m = sleepStart
        while (m < sleepEnd) {
            metrics.add(DeviceMetricRow(m, DeviceMetricKind.HRV_MS, watchHrv(i) + (m / 300 % 3) - 1, "fit371"))
            metrics.add(DeviceMetricRow(m + 60, DeviceMetricKind.RESPIRATION_BPM, 14.5, "fit297"))
            metrics.add(DeviceMetricRow(m + 120, DeviceMetricKind.SPO2_PCT, 96.0, "fit269"))
            m += 300
        }
        var h = sleepEnd
        while (h < minOf(now, wake + 86_400)) {
            metrics.add(DeviceMetricRow(h, DeviceMetricKind.STRESS, 35.0, "fit227"))
            metrics.add(DeviceMetricRow(h + 30, DeviceMetricKind.BODY_BATTERY, 60.0, "fit227"))
            h += 3_600
        }
        val seg = listOf(Triple(0, 90, "light"), Triple(90, 180, "deep"), Triple(180, 360, "light"),
            Triple(360, 450, "rem"), Triple(450, 480, "wake"))
        val stages = seg.joinToString(",", "[", "]") {
            "{\"start\":${sleepStart + it.first * 60},\"end\":${sleepStart + it.second * 60},\"stage\":\"${it.third}\"}"
        }
        return GarminFileIngest(
            file = GarminSyncedFileRow(100 + i, 128, 32, i, wake, 1),
            hr = hr,
            deviceMetric = metrics,
            days = listOf(GarminDayRollup(dayKey(wake), wake, wake + 86_400, steps = 6_000 + i * 500,
                restingHr = watchRhr(i), avgHrvMs = watchHrv(i), totalSleepMin = 450.0, deepMin = 90.0,
                lightMin = 270.0, remMin = 90.0, awakeMin = 30.0, efficiencyPct = 93.8)),
            sleeps = listOf(GarminSleepRow(sleepStart, sleepEnd, 93.8, null, watchHrv(i), stages)),
        )
    }

    private suspend fun seed(nights: Int): List<IntelligenceEngine.Computed> {
        registry.add(PairedDeviceRow(id = watch, brand = "Garmin", model = "Forerunner 165", nickname = null,
            sourceKind = "garmin", capabilities = "hr", status = "active", addedAt = 1, lastSeenAt = 1))
        for (i in 0 until nights) {
            val wake = todayMidnight - (nights - 1 - i) * 86_400L
            store.ingest(file(i, wake), watch, now)
        }
        return IntelligenceEngine.analyzeRecent(repo = repo, maxDays = nights + 1, importedDeviceId = watch,
            nowSeconds = now, ownerSource = RegistryDayOwnerSource(registry), dayCycleMode = DayCycleMode.MIDNIGHT)
    }

    @Test
    fun watchOwnedNightsScoreFromTheWatchsOwnVitals() = runBlocking {
        seed(9)
        val byDay = repo.dailyMetrics(computed, "0000-01-01", "9999-12-31").associateBy { it.day }
        for (i in 1 until 9) {
            val key = dayKey(todayMidnight - (8 - i) * 86_400L)
            val row = byDay[key]
            assertNotNull("day $key must be scored from the watch's HR", row)
            assertEquals("$key resting HR is the watch's own", watchRhr(i), row!!.restingHr)
            assertEquals("$key HRV is the watch's FIT 370 average: $row", watchHrv(i), row.avgHrv ?: Double.NaN, 1e-9)
            assertEquals("$key respiration is the watch's own: $row", 14.5, row.respRateBpm ?: Double.NaN, 1e-9)
            assertTrue("$key the watch's own night is staged", (row.totalSleepMin ?: 0.0) > 400)
            assertNotNull("$key Effort is computed from the watch's HR", row.strain)
        }
    }

    @Test
    fun chargeStaysCalibratingUntilTheBaselineIsUsable() = runBlocking {
        seed(3)
        val few = repo.dailyMetrics(computed, "0000-01-01", "9999-12-31")
        assertFalse(few.isEmpty())
        assertTrue("calibrating until the HRV baseline is usable", few.all { it.recovery == null })
        assertTrue(few.any { it.strain != null })
        assertTrue(few.any { it.avgHrv != null })
    }

    @Test
    fun chargeScoresOnceTheBaselineHasItsNights() = runBlocking {
        seed(9)
        val scored = repo.dailyMetrics(computed, "0000-01-01", "9999-12-31").mapNotNull { it.recovery }
        assertTrue("Charge lights up once the baseline has its nights", scored.size >= Baselines.minNightsSeed)
        assertTrue(scored.all { it in 0.0..100.0 })
    }

    @Test
    fun effortVariesAndIsLabelledExperimentalAndStressStaysOnItsOwnScale() = runBlocking {
        seed(9)
        val strains = repo.dailyMetrics(computed, "0000-01-01", "9999-12-31").mapNotNull { it.strain }
        assertTrue("rest / easy / hard days must not all score the same Effort",
            strains.map { Math.round(it * 10) }.toSet().size > 1)
        val key = dayKey(todayMidnight - 86_400L)
        assertEquals(watch, repo.scoreInputSource(computed, key, "strain"))
        assertTrue(heroEffortExperimental(mapOf("strain" to ScoreInputProvider(watch, "Garmin"))))
        assertEquals(DisplayText.Resource(R.string.today_source_garmin), provenanceDisplayLabel(watch, watch))
        assertTrue("Garmin stress must not land in the 0-3 stress series",
            repo.metricSeries(watch, "stress", "0000-01-01", "9999-12-31").isEmpty())
        val garminStress = repo.metricSeries(watch, GarminSyncStore.GARMIN_STRESS_KEY, "0000-01-01", "9999-12-31")
        assertFalse(garminStress.isEmpty())
        assertTrue(garminStress.all { it.value == 35.0 })
    }
}
