package com.noop.analytics

import android.content.Context
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.noop.ble.FakeHuaweiGatt
import com.noop.ble.HuaweiPairingRequests
import com.noop.ble.HuaweiSource
import com.noop.ble.MemoryHuaweiKeys
import com.noop.data.DeviceMetricKind
import com.noop.data.DeviceRegistry
import com.noop.data.HuaweiHistoryIngest
import com.noop.data.HuaweiSyncPrefs
import com.noop.data.PairedDeviceRow
import com.noop.data.WhoopDatabase
import com.noop.data.WhoopRepository
import com.noop.huawei.HuaweiHistoryBand
import com.noop.huawei.HuaweiHistorySync
import com.noop.huawei.SeededHuaweiRandom
import com.noop.huawei.SimulatedHuaweiBand
import com.noop.huawei.decode.HuaweiDictionaryDecoder
import com.noop.huawei.decode.HuaweiFixtures
import com.noop.huawei.decode.HuaweiSleepFileDecoder
import com.noop.huawei.protocol.HuaweiFirstPairingGrant
import com.noop.huawei.protocol.HuaweiTlv
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executors

/**
 * A Huawei band from the wizard's first pairing to the screens' data, with only the radio simulated: the
 * wizard's grant ([HuaweiPairingRequests]) reaches the [HuaweiSource] the coordinator builds, which pairs
 * with a [SimulatedHuaweiBand] over real framing and encryption, reads its capabilities, sets its clock and
 * runs the real [HuaweiHistorySync] into Room ([HuaweiHistoryIngest]). The real scoring pass then runs, and
 * the queries Today, Sleep, Trends and Workouts read come back with the band's rows. All data is synthetic;
 * nothing here was checked against a real Huawei Band 10.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class HuaweiEndToEndTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db: WhoopDatabase = Room.inMemoryDatabaseBuilder(context, WhoopDatabase::class.java)
        .allowMainThreadQueries().build()
    private val repo = WhoopRepository(db)
    private val registry = DeviceRegistry(db.whoopDao(), object : DeviceRegistry.Transactor {
        override suspend fun <R> run(block: suspend () -> R): R = block()
    })
    private val prefs = HuaweiSyncPrefs(context.getSharedPreferences("huawei-e2e-test", Context.MODE_PRIVATE))
    private val band = "huawei-C8:2A:DD:10:20:30"
    private val computed = "$band-noop"
    private val executor = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())

    private val now = System.currentTimeMillis() / 1000L
    private val offset = java.util.TimeZone.getDefault().getOffset(now * 1000L) / 1000L
    private val todayMidnight = now - Math.floorMod(now + offset, 86_400L)
    private val nights = 4

    @After
    fun close() {
        scope.cancel()
        executor.shutdownNow()
        db.close()
        prefs.dropDeviceState(band)
    }

    private fun wake(i: Int) = todayMidnight - (nights - 1 - i) * 86_400L + 3 * 3_600
    private fun dayKey(ts: Long) = AnalyticsEngine.dayString(ts, offset)
    private fun bandHrv(i: Int) = 44 + i * 3

    private fun bit(bits: ByteArray, bit: Int) {
        bits[bit / 8] = (bits[bit / 8].toInt() or (1 shl (bit % 8))).toByte()
    }

    private fun history(): HuaweiHistoryBand.Script {
        val steps = ArrayList<HuaweiHistoryBand.StepRecord>()
        val records = ArrayList<ByteArray>()
        val dictionary = ArrayList<HuaweiHistoryBand.DictRecord>()
        for (i in 0 until nights) {
            val sleepEnd = wake(i)
            val sleepStart = sleepEnd - 8 * 3_600
            var base = sleepStart - 3_600
            while (base < minOf(now, sleepEnd + 12 * 3_600)) {
                val minutes = ArrayList<Pair<Int, ByteArray>>()
                for (m in 0 until 240) {
                    val t = base + m * 60L
                    if (t >= now) break
                    val asleep = t in sleepStart until sleepEnd
                    val bpm = if (asleep) 50 + ((t / 60) % 4).toInt() else 72 + ((t / 60) % 9).toInt()
                    val spo2 = if (asleep && m % 30 == 0) 96 else null
                    val rhr = if (t == sleepEnd + 3_600) 55 else null
                    minutes.add(m to HuaweiHistoryBand.minute(steps = if (asleep) null else 15, hr = bpm, spo2 = spo2, rhr = rhr))
                }
                steps.add(HuaweiHistoryBand.StepRecord(base, minutes))
                base += 240 * 60
            }
            val summary = HuaweiFixtures.tlv(0x81,
                HuaweiFixtures.summaryLong(HuaweiSleepFileDecoder.ID_FALL_ASLEEP, sleepStart) +
                    HuaweiFixtures.summaryLong(HuaweiSleepFileDecoder.ID_WAKEUP_TIME, sleepEnd) +
                    HuaweiFixtures.summaryInt(HuaweiSleepFileDecoder.ID_SLEEP_SCORE, 80L + i) +
                    HuaweiFixtures.summaryInt(HuaweiSleepFileDecoder.ID_AVG_HRV, bandHrv(i).toLong()))
            // light 120, deep 90, REM 90, light 150, awake 30 minutes.
            val detail = HuaweiFixtures.detail(7_200 to 1, 5_400 to 3, 5_400 to 2, 9_000 to 1, 1_800 to 4)
            records.add(HuaweiFixtures.sequenceRecord(sleepStart, sleepEnd, summary, detail))
            val t = (sleepStart + 3_600) * 1000
            dictionary.add(HuaweiHistoryBand.DictRecord(HuaweiDictionaryDecoder.CLASS_HRV, t, t + 300_000,
                HuaweiDictionaryDecoder.VALUE_HRV_RMSSD, bandHrv(i).toDouble()))
        }
        val workoutStart = wake(nights - 2) + 6 * 3_600
        val totals = HuaweiTlv().putShort(0x02, 1).putInt(0x04, workoutStart.toInt()).putInt(0x05, (workoutStart + 1_800).toInt())
            .putShort(0x06, 250).putInt(0x07, 5_000).putInt(0x08, 6_000).putByte(0x14, 1)
            .put(0x0C, byteArrayOf(90.toByte(), 165.toByte()))
        val header = HuaweiFixtures.be().i16(1).i16(0).i32(workoutStart).u8(60).i16(3).u8(1).i16(0x01).build()
        val page = HuaweiTlv().putShort(0x02, 1).putShort(0x03, 0).put(0x04, header)
            .put(0x05, byteArrayOf(140.toByte(), 150.toByte(), 160.toByte()))
        return HuaweiHistoryBand.Script(
            steps = steps,
            files = mapOf(
                "sequence_data" to HuaweiFixtures.sequenceFile(1, *records.toTypedArray()),
                "rrisqi_data.bin" to HuaweiHistoryBand.stressFile(Triple(wake(nights - 2) + 7_200, wake(nights - 2) + 7_500, 42)),
            ),
            dictionary = dictionary,
            dictionaryPageSize = 100,
            workouts = listOf(HuaweiHistoryBand.Workout(1, totals, listOf(page))),
            maxBlock = 4_096,
            burst = 400,
        )
    }

    private fun bandConfig(): SimulatedHuaweiBand.Config {
        val expand = ByteArray(32)
        bit(expand, HuaweiHistorySync.EXPAND_NEW_SLEEP)
        bit(expand, HuaweiHistorySync.EXPAND_HRV)
        return SimulatedHuaweiBand.Config.normal().copy(
            bondStatus = 0,
            supportedServices = setOf(0x01, 0x07, 0x0A, 0x17, 0x2C, 0x20, 0x34, 0x1A),
            supportedCommands = mapOf(
                0x01 to setOf(0x02, 0x03, 0x07, 0x08, 0x27, 0x31, 0x37),
                0x07 to setOf(0x0A, 0x0B, 0x16),
                0x17 to setOf(0x01, 0x07, 0x08, 0x0A, 0x15),
                0x2C to setOf(0x01, 0x03, 0x04, 0x06),
                0x20 to setOf(0x09),
                0x34 to setOf(0x01),
            ),
            expand = expand,
            settingBits = 0x06,
            history = history(),
        )
    }

    @Test
    fun wizardPairingSyncsTheBandIntoEveryScreensData() = runBlocking {
        // The wizard registers the band active and arms its one-shot first-pairing grant.
        registry.add(PairedDeviceRow(id = band, brand = "Huawei", model = "Band 10", nickname = null,
            sourceKind = "huawei", capabilities = "hr,sleep,steps,spo2,skinTemp", status = "active", addedAt = 1, lastSeenAt = 1))
        HuaweiPairingRequests.arm(band, HuaweiFirstPairingGrant.forPairingWizard())

        // What SourceCoordinator.makeHuaweiSource builds, with the radio simulated.
        val sim = SimulatedHuaweiBand(bandConfig())
        val gatt = FakeHuaweiGatt(scope, sim)
        val log = java.util.Collections.synchronizedList(mutableListOf<String>())
        val runner = HuaweiHistorySync(prefs, store = { id, batch -> HuaweiHistoryIngest.store(db, id, batch); true },
            utcOffset = { offset.toInt() }, log = { log.add(it) })
        val source = HuaweiSource(
            deviceId = band, gatt = gatt, keys = MemoryHuaweiKeys(), scope = scope, syncRunner = runner,
            log = { log.add(it) }, random = SeededHuaweiRandom(11),
            onSynced = { prefs.setCompletedAt(band, it) },
            lastSyncAttemptAt = { prefs.lastSyncAt(band) }, emptyStreak = { prefs.emptyStreak(band) },
        )
        HuaweiPairingRequests.take(band)?.let { source.beginFirstPairing(it) }
        assertNull("the grant is one-shot", HuaweiPairingRequests.take(band))
        source.connect(sim.config.bandAddress)

        withTimeout(60_000) { source.lastSyncAt.first { it != null } }
        assertEquals(log.toString(), HuaweiSource.LinkPhase.READY, source.linkPhase.value)
        assertNull(log.toString(), source.lastSyncError.value)
        assertNotNull(prefs.completedAt(band))
        assertTrue("the wizard's pairing installed exactly one key", sim.received.count { it.first.serviceId == 0x01 && it.first.commandId == 0x0E } == 1)
        assertTrue("the clock was set", sim.received.any { it.first.serviceId == 0x01 && it.first.commandId == 0x05 })
        assertTrue("no account id was entered, so none is sent", sim.received.none { it.first.serviceId == 0x1A })
        assertTrue("history went over the paired session", sim.historyReceived.any { it.serviceId == 0x2C && it.encrypted })

        IntelligenceEngine.analyzeRecent(repo = repo, maxDays = nights + 1, importedDeviceId = band,
            nowSeconds = now, ownerSource = RegistryDayOwnerSource(registry), dayCycleMode = DayCycleMode.MIDNIGHT)

        val from = dayKey(wake(0) - 86_400)
        val to = dayKey(now + 86_400)
        // Today: the scored day rows (Rest from the band's staged night, Effort from its heart rate, its HRV).
        val scored = repo.dailyMetrics(computed, from, to).associateBy { it.day }
        for (i in 1 until nights) {
            val row = scored[dayKey(wake(i))]
            assertNotNull("day ${dayKey(wake(i))} is scored: ${scored.keys}", row)
            assertEquals(bandHrv(i).toDouble(), row!!.avgHrv ?: Double.NaN, 1e-9)
            assertTrue("Rest takes the band's night: $row", (row.totalSleepMin ?: 0.0) > 400)
        }
        // Sleep: the band's own staged nights.
        val sleeps = repo.sleepSessionsForDevice(band, wake(0) - 86_400, now + 86_400)
        assertEquals(nights, sleeps.size)
        assertTrue(sleeps.all { it.stagesJSON != null })
        // Trends: the band's per-day series (steps, resting HR, HRV, its stress average).
        assertTrue(repo.metricSeries(band, "steps", from, to).isNotEmpty())
        assertTrue(repo.metricSeries(band, "hrv", from, to).isNotEmpty())
        assertEquals(listOf(42.0), repo.metricSeries(band, StressToday.HUAWEI_STRESS_KEY, from, to).map { it.value })
        // SpO2 and the band's sleep score land as device metrics, as a Garmin watch's do.
        assertTrue(repo.deviceMetricSamples(band, DeviceMetricKind.SPO2_PCT, wake(0) - 86_400, now + 86_400).isNotEmpty())
        // Workouts: the band's workout, under the band's id.
        val workouts = repo.workouts(band, wake(0) - 86_400, now + 86_400)
        assertEquals(1, workouts.size)
        assertEquals(5_000.0, workouts.single().distanceM ?: 0.0, 1e-9)
    }
}
