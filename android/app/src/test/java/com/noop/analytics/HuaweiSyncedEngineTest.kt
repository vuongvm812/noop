package com.noop.analytics

import android.content.Context
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.noop.data.DeviceRegistry
import com.noop.data.HuaweiHistoryIngest
import com.noop.data.HuaweiSyncPrefs
import com.noop.data.PairedDeviceRow
import com.noop.data.WhoopDatabase
import com.noop.data.WhoopRepository
import com.noop.huawei.FakeHuaweiLink
import com.noop.huawei.HuaweiCapabilities
import com.noop.huawei.HuaweiHistoryBand
import com.noop.huawei.HuaweiHistorySync
import com.noop.huawei.decode.HuaweiDictionaryDecoder
import com.noop.huawei.decode.HuaweiFixtures
import com.noop.huawei.decode.HuaweiSleepFileDecoder
import com.noop.huawei.protocol.HuaweiExpandCapabilities
import com.noop.huawei.protocol.HuaweiSettingRelated
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A Huawei band's history, pulled over the protocol ([HuaweiHistorySync] against a scripted band) and
 * stored by [HuaweiHistoryIngest], scores through the real pass like a Garmin watch's
 * ([GarminAnalyticsEngineTest]): Charge once the baseline has its nights, Rest from the band's own
 * staged nights, Effort from its heart rate. All data is synthetic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class HuaweiSyncedEngineTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db: WhoopDatabase = Room.inMemoryDatabaseBuilder(context, WhoopDatabase::class.java)
        .allowMainThreadQueries().build()
    private val repo = WhoopRepository(db)
    private val registry = DeviceRegistry(db.whoopDao(), object : DeviceRegistry.Transactor {
        override suspend fun <R> run(block: suspend () -> R): R = block()
    })
    private val prefs = HuaweiSyncPrefs(context.getSharedPreferences("huawei-engine-test", Context.MODE_PRIVATE))
    private val band = "huawei-TEST-0001"
    private val computed = "$band-noop"

    private val now = System.currentTimeMillis() / 1000L
    private val offset = java.util.TimeZone.getDefault().getOffset(now * 1000L) / 1000L
    private val todayMidnight = now - Math.floorMod(now + offset, 86_400L)
    private val nights = 9

    @After
    fun close() {
        db.close()
        prefs.dropDeviceState(band)
    }

    private fun bandRhr(i: Int) = 54 + (i % 3)
    private fun bandHrv(i: Int) = 46 + ((i * 7) % 9)
    private fun dayKey(ts: Long) = AnalyticsEngine.dayString(ts, offset)
    private fun wake(i: Int) = todayMidnight - (nights - 1 - i) * 86_400L

    private fun script(): HuaweiHistoryBand.Script {
        val steps = ArrayList<HuaweiHistoryBand.StepRecord>()
        val records = ArrayList<ByteArray>()
        val dictionary = ArrayList<HuaweiHistoryBand.DictRecord>()
        for (i in 0 until nights) {
            val wake = wake(i)
            val sleepStart = wake - 3_600
            val sleepEnd = wake + 7 * 3_600
            val workoutStart = wake + 17 * 3_600
            val workoutEnd = workoutStart + (i % 3) * 20 * 60
            var base = wake - 6 * 3_600
            while (base < minOf(now, wake + 18 * 3_600)) {
                val minutes = ArrayList<Pair<Int, ByteArray>>()
                for (m in 0 until 240) {
                    val t = base + m * 60L
                    if (t >= now) break
                    val bpm = when {
                        t in sleepStart until sleepEnd -> 50 + ((t / 60) % 5).toInt()
                        t in workoutStart until workoutEnd -> 148 + ((t / 60) % 5).toInt()
                        else -> 68 + ((t / 60) % 9).toInt()
                    }
                    val spo2 = if (t in sleepStart until sleepEnd && m % 30 == 0) 96 else null
                    val rhr = if (t == wake + 8 * 3_600) bandRhr(i) else null
                    minutes.add(m to HuaweiHistoryBand.minute(steps = if (bpm > 60) 20 else null, hr = bpm, spo2 = spo2, rhr = rhr))
                }
                steps.add(HuaweiHistoryBand.StepRecord(base, minutes))
                base += 240 * 60
            }
            val summary = HuaweiFixtures.tlv(0x81,
                HuaweiFixtures.summaryLong(HuaweiSleepFileDecoder.ID_FALL_ASLEEP, sleepStart) +
                    HuaweiFixtures.summaryLong(HuaweiSleepFileDecoder.ID_WAKEUP_TIME, sleepEnd) +
                    HuaweiFixtures.summaryInt(HuaweiSleepFileDecoder.ID_AVG_HRV, bandHrv(i).toLong()))
            // light 90, deep 90, light 180, REM 90, awake 30 minutes.
            val detail = HuaweiFixtures.detail(5_400 to 1, 5_400 to 3, 10_800 to 1, 5_400 to 2, 1_800 to 4)
            records.add(HuaweiFixtures.sequenceRecord(sleepStart, sleepEnd, summary, detail))
            for (k in 0 until 6) {
                val t = (sleepStart + k * 3_600L) * 1000
                dictionary.add(HuaweiHistoryBand.DictRecord(HuaweiDictionaryDecoder.CLASS_HRV, t, t + 300_000,
                    HuaweiDictionaryDecoder.VALUE_HRV_RMSSD, bandHrv(i).toDouble()))
            }
        }
        return HuaweiHistoryBand.Script(
            steps = steps,
            files = mapOf("sequence_data" to HuaweiFixtures.sequenceFile(1, *records.toTypedArray())),
            dictionary = dictionary,
            dictionaryPageSize = 1_000,
            maxBlock = 4_096,
            burst = 400,
        )
    }

    private fun caps(): HuaweiCapabilities {
        val bits = ByteArray(32)
        for (bit in listOf(HuaweiHistorySync.EXPAND_NEW_SLEEP, HuaweiHistorySync.EXPAND_HRV)) {
            bits[bit / 8] = (bits[bit / 8].toInt() or (1 shl (bit % 8))).toByte()
        }
        return HuaweiCapabilities(
            commands = mapOf(0x07 to setOf(0x0A, 0x0B, 0x16), 0x34 to setOf(0x01)),
            expand = HuaweiExpandCapabilities(bits),
            settings = HuaweiSettingRelated(truSleepFileSync = true, rriFileSync = true, gpsFileSync = false),
        )
    }

    private suspend fun syncAndScore() {
        registry.add(PairedDeviceRow(id = band, brand = "Huawei", model = "Band 10", nickname = null,
            sourceKind = "huawei", capabilities = "hr", status = "active", addedAt = 1, lastSeenAt = 1))
        val b = HuaweiHistoryBand(script())
        val link = FakeHuaweiLink(b, pushReplay = 0)
        link.configure(b.linkConfig())
        val summary = HuaweiHistorySync(prefs, store = { id, batch -> HuaweiHistoryIngest.store(db, id, batch); true },
            clock = { now }, utcOffset = { offset.toInt() }).run(link, caps(), band)
        assertTrue(summary.toString(), summary.stored && summary.failures.isEmpty())
        IntelligenceEngine.analyzeRecent(repo = repo, maxDays = nights + 1, importedDeviceId = band,
            nowSeconds = now, ownerSource = RegistryDayOwnerSource(registry), dayCycleMode = DayCycleMode.MIDNIGHT)
    }

    @Test
    fun aSyncedBandScoresRestAndEffortFromItsOwnData() = runBlocking {
        syncAndScore()
        val byDay = repo.dailyMetrics(computed, "0000-01-01", "9999-12-31").associateBy { it.day }
        for (i in 1 until nights) {
            val key = dayKey(wake(i))
            val row = byDay[key]
            assertNotNull("day $key must be scored", row)
            assertEquals("$key resting HR is the band's own", bandRhr(i), row!!.restingHr)
            assertEquals("$key HRV is the band's own", bandHrv(i).toDouble(), row.avgHrv ?: Double.NaN, 1e-9)
            assertTrue("$key Rest: the band's staged night is handed over: $row", (row.totalSleepMin ?: 0.0) > 400)
            assertNotNull("$key Effort comes from the band's heart rate", row.strain)
        }
    }

    @Test
    fun chargeLightsUpOnceTheBaselineHasItsNights() = runBlocking {
        syncAndScore()
        val scored = repo.dailyMetrics(computed, "0000-01-01", "9999-12-31").mapNotNull { it.recovery }
        assertTrue("Charge scores once the baseline has its nights: $scored", scored.size >= Baselines.minNightsSeed)
        assertTrue(scored.all { it in 0.0..100.0 })
    }
}
