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
import com.noop.ui.provenanceDisplayLabel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A Huawei Band 10 takes the same watch path through the real scoring pass as a Garmin watch: the stored
 * night is handed over (stored-hypnogram gate), a day with no stored night is never staged from heart rate,
 * and the watch's own resting HR / HRV / respiration win (applyGarminVitals). The same synthetic days are
 * written under a "huawei-" and a "garmin-" id into two databases, and the scored rows must match. The rows
 * go in through [GarminSyncStore.ingest] only as a storage fixture; no Huawei protocol is involved. All data
 * is synthetic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class HuaweiWatchEngineTest {

    private val now = System.currentTimeMillis() / 1000L
    private val offset = java.util.TimeZone.getDefault().getOffset(now * 1000L) / 1000L
    private val todayMidnight = now - Math.floorMod(now + offset, 86_400L)

    private fun watchRhr(i: Int) = 54 + (i % 3)
    private fun watchHrv(i: Int) = 46.0 + ((i * 7) % 9)
    private fun dayKey(ts: Long) = AnalyticsEngine.dayString(ts, offset)

    /** A day ending on `wake`: HR once a minute, HRV / respiration through the night, a varying workout. */
    private fun file(i: Int, wake: Long, withSleep: Boolean): GarminFileIngest {
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
            metrics.add(DeviceMetricRow(m, DeviceMetricKind.HRV_MS, watchHrv(i) + (m / 300 % 3) - 1, "test"))
            metrics.add(DeviceMetricRow(m + 60, DeviceMetricKind.RESPIRATION_BPM, 14.5, "test"))
            m += 300
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
                restingHr = watchRhr(i), avgHrvMs = watchHrv(i),
                totalSleepMin = if (withSleep) 450.0 else null, deepMin = null, lightMin = null, remMin = null,
                awakeMin = null, efficiency = null)),
            sleeps = if (withSleep) listOf(GarminSleepRow(sleepStart, sleepEnd, 0.938, null, watchHrv(i), stages)) else emptyList(),
        )
    }

    /** One scored day, reduced to the values the watch path decides. */
    private data class Scored(val day: String, val rhr: Int?, val hrv: Double?, val resp: Double?, val sleepMin: Double?)

    private fun score(watch: String, brand: String, kind: String, nights: Int, withSleep: Boolean): List<Scored> = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, WhoopDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val repo = WhoopRepository(db)
            val registry = DeviceRegistry(db.whoopDao(), object : DeviceRegistry.Transactor {
                override suspend fun <R> run(block: suspend () -> R): R = block()
            })
            registry.add(PairedDeviceRow(id = watch, brand = brand, model = "test", nickname = null,
                sourceKind = kind, capabilities = "hr", status = "active", addedAt = 1, lastSeenAt = 1))
            val store = GarminSyncStore(db)
            for (i in 0 until nights) {
                store.ingest(file(i, todayMidnight - (nights - 1 - i) * 86_400L, withSleep), watch, now)
            }
            IntelligenceEngine.analyzeRecent(repo = repo, maxDays = nights + 1, importedDeviceId = watch,
                nowSeconds = now, ownerSource = RegistryDayOwnerSource(registry), dayCycleMode = DayCycleMode.MIDNIGHT)
            repo.dailyMetrics("$watch-noop", "0000-01-01", "9999-12-31")
                .map { Scored(it.day, it.restingHr, it.avgHrv, it.respRateBpm, it.totalSleepMin) }
                .sortedBy { it.day }
        } finally {
            db.close()
        }
    }

    @Test
    fun huaweiNightsScoreFromTheBandsOwnVitals() {
        val rows = score("huawei-TEST-0001", "Huawei", "huawei", 9, withSleep = true).associateBy { it.day }
        for (i in 1 until 9) {
            val key = dayKey(todayMidnight - (8 - i) * 86_400L)
            val row = rows[key]
            assertNotNull("day $key must be scored", row)
            assertEquals("$key resting HR is the band's own", watchRhr(i), row!!.rhr)
            assertEquals("$key HRV is the band's own", watchHrv(i), row.hrv ?: Double.NaN, 1e-9)
            assertEquals("$key respiration is the band's own", 14.5, row.resp ?: Double.NaN, 1e-9)
            assertTrue("$key the band's stored night is handed over", (row.sleepMin ?: 0.0) > 400)
        }
    }

    @Test
    fun huaweiScoresExactlyLikeGarminOnTheSameDays() {
        assertEquals(
            score("garmin-TEST-0001", "Garmin", "garmin", 9, withSleep = true),
            score("huawei-TEST-0001", "Huawei", "huawei", 9, withSleep = true),
        )
    }

    /** No stored night means no night: the band's heart rate is never staged into one (the watch gate). */
    @Test
    fun aHuaweiDayWithNoStoredNightIsNeverStagedFromHeartRate() {
        val huawei = score("huawei-TEST-0001", "Huawei", "huawei", 5, withSleep = false)
        assertTrue("no Huawei day may carry an HR-staged night: $huawei", huawei.all { (it.sleepMin ?: 0.0) == 0.0 })
        assertEquals(score("garmin-TEST-0001", "Garmin", "garmin", 5, withSleep = false), huawei)
    }

    @Test
    fun aHuaweiRowReadsHuaweiNotWhoop() {
        val id = "huawei-TEST-0001"
        assertEquals(DisplayText.Resource(R.string.today_source_huawei), provenanceDisplayLabel(id, id))
        assertEquals(DisplayText.Resource(R.string.today_source_garmin), provenanceDisplayLabel("garmin-1", "garmin-1"))
    }
}
