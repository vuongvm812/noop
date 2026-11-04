package com.noop.data

import android.content.Context
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.noop.analytics.StressToday
import com.noop.huawei.FakeHuaweiLink
import com.noop.huawei.HuaweiCapabilities
import com.noop.huawei.HuaweiDataType
import com.noop.huawei.HuaweiHistoryBand
import com.noop.huawei.HuaweiHistorySync
import com.noop.huawei.HuaweiSyncSummary
import com.noop.huawei.decode.HuaweiDictionaryDecoder
import com.noop.huawei.decode.HuaweiFixtures
import com.noop.huawei.decode.HuaweiSleepFileDecoder
import com.noop.huawei.protocol.HuaweiCommand
import com.noop.huawei.protocol.HuaweiExpandCapabilities
import com.noop.huawei.protocol.HuaweiSettingRelated
import com.noop.huawei.protocol.HuaweiTlv
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

/**
 * Huawei history sync end to end without a band: scripted [HuaweiHistoryBand] sessions over a
 * [FakeHuaweiLink] (real framing and encryption), [HuaweiHistorySync], then [HuaweiHistoryIngest] into a
 * real Room database. All data is synthetic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class HuaweiHistorySyncRoomTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db: WhoopDatabase = Room.inMemoryDatabaseBuilder(context, WhoopDatabase::class.java)
        .allowMainThreadQueries().build()
    private val prefs = HuaweiSyncPrefs(context.getSharedPreferences("huawei-sync-test", Context.MODE_PRIVATE))
    private val band = "huawei-TEST-0001"

    @After
    fun close() {
        db.close()
        prefs.dropDeviceState(band)
    }

    // 2025-11-02 12:00 UTC; every day is UTC.
    private val now = 1_762_084_800L
    private val today = 1_762_041_600L
    private val yesterday = today - 86_400
    private val nightStart = today - 3_600
    private val nightEnd = nightStart + 21_600

    private fun expand(vararg bits: Int): HuaweiExpandCapabilities {
        val b = ByteArray(32)
        for (bit in bits) b[bit / 8] = (b[bit / 8].toInt() or (1 shl (bit % 8))).toByte()
        return HuaweiExpandCapabilities(b)
    }

    private fun caps(newSync: Boolean = true, legacySleep: Boolean = false, sleepFile: Boolean = true) = HuaweiCapabilities(
        commands = mapOf(
            0x07 to buildSet {
                addAll(listOf(0x0A, 0x0B, 0x16, 0x17, 0x23, 0x24))
                if (legacySleep) addAll(listOf(0x0C, 0x0D))
            },
            0x17 to setOf(0x01, 0x07, 0x08, 0x0A, 0x15),
            0x20 to setOf(0x09),
            0x34 to setOf(0x01),
        ),
        expand = if (sleepFile) expand(HuaweiHistorySync.EXPAND_NEW_SLEEP, HuaweiHistorySync.EXPAND_HRV, HuaweiHistorySync.EXPAND_SKIN_TEMPERATURE)
            else expand(HuaweiHistorySync.EXPAND_HRV, HuaweiHistorySync.EXPAND_SKIN_TEMPERATURE),
        settings = HuaweiSettingRelated(truSleepFileSync = newSync, rriFileSync = newSync, gpsFileSync = false),
    )

    private fun sleepFile(): ByteArray {
        val summary = HuaweiFixtures.tlv(0x81,
            HuaweiFixtures.summaryLong(HuaweiSleepFileDecoder.ID_FALL_ASLEEP, nightStart) +
                HuaweiFixtures.summaryLong(HuaweiSleepFileDecoder.ID_WAKEUP_TIME, nightEnd) +
                HuaweiFixtures.summaryInt(HuaweiSleepFileDecoder.ID_SLEEP_SCORE, 82) +
                HuaweiFixtures.summaryInt(HuaweiSleepFileDecoder.ID_AVG_HRV, 45))
        // light 90, deep 60, REM 60, light 120, awake 30 minutes.
        val detail = HuaweiFixtures.detail(5_400 to 1, 3_600 to 3, 3_600 to 2, 7_200 to 1, 1_800 to 4)
        return HuaweiFixtures.sequenceFile(1, HuaweiFixtures.sequenceRecord(nightStart, nightEnd, summary, detail))
    }

    private fun workout(): HuaweiHistoryBand.Workout {
        val start = yesterday + 18 * 3_600
        val totals = HuaweiTlv().putShort(0x02, 1).putInt(0x04, start.toInt()).putInt(0x05, (start + 1_800).toInt())
            .putShort(0x06, 250).putInt(0x07, 5_000).putInt(0x08, 6_000).putByte(0x14, 1)
            .put(0x0C, byteArrayOf(90.toByte(), 165.toByte())).putShort(0x0D, 120).putByte(0x0E, 32).putByte(0x20, 15)
        // Header: workout 1, page 0, first sample at start, 60 s apart, 3 samples of 1 byte, bitmap = HR.
        val header = HuaweiFixtures.be().i16(1).i16(0).i32(start).u8(60).i16(3).u8(1).i16(0x01).build()
        val page = HuaweiTlv().putShort(0x02, 1).putShort(0x03, 0).put(0x04, header)
            .put(0x05, byteArrayOf(140.toByte(), 150.toByte(), 160.toByte()))
        return HuaweiHistoryBand.Workout(1, totals, listOf(page))
    }

    private fun script(
        files: Map<String, ByteArray> = mapOf(
            "sequence_data" to sleepFile(),
            "rrisqi_data.bin" to HuaweiHistoryBand.stressFile(Triple(today + 7_200, today + 7_500, 30), Triple(today + 9_000, today + 9_300, 50)),
        ),
        maxBlock: Int = 64,
        encryptBlocks: Boolean = false,
        noEncrypt: Boolean = false,
        silentOn: Set<Pair<Int, Int>> = emptySet(),
        stallAfterBlocks: Int = Int.MAX_VALUE,
        workoutListBreaksAbove: Long = Long.MAX_VALUE,
        legacySleep: List<List<Pair<Int, ByteArray>>> = emptyList(),
    ) = HuaweiHistoryBand.Script(
        steps = listOf(
            HuaweiHistoryBand.StepRecord(yesterday + 8 * 3_600, listOf(
                0 to HuaweiHistoryBand.minute(steps = 100, kcal = 5, distanceM = 80, hr = 70),
                1 to HuaweiHistoryBand.minute(steps = 50, kcal = 3, distanceM = 40, hr = 72, rhr = 55),
            )),
            HuaweiHistoryBand.StepRecord(nightStart, listOf(
                10 to HuaweiHistoryBand.minute(hr = 52, spo2 = 95),
                20 to HuaweiHistoryBand.minute(hr = 51, spo2 = 96),
                30 to HuaweiHistoryBand.minute(hr = 50, spo2 = 97),
                40 to HuaweiHistoryBand.minute(hr = 53, spo2 = 98),
            )),
        ),
        legacySleep = legacySleep,
        files = files,
        dictionary = listOf(
            HuaweiHistoryBand.DictRecord(HuaweiDictionaryDecoder.CLASS_HRV, (nightStart + 600) * 1000, (nightStart + 900) * 1000,
                HuaweiDictionaryDecoder.VALUE_HRV_RMSSD, 48.5),
            HuaweiHistoryBand.DictRecord(HuaweiDictionaryDecoder.CLASS_HRV, (nightStart + 7_200) * 1000, (nightStart + 7_500) * 1000,
                HuaweiDictionaryDecoder.VALUE_HRV_RMSSD, 51.0),
            HuaweiHistoryBand.DictRecord(HuaweiDictionaryDecoder.CLASS_SKIN_TEMPERATURE, (nightStart + 3_600) * 1000, (nightStart + 3_900) * 1000,
                HuaweiDictionaryDecoder.VALUE_SKIN_TEMPERATURE, 34.2),
        ),
        workouts = listOf(workout()),
        maxBlock = maxBlock,
        encryptBlocks = encryptBlocks,
        noEncrypt = noEncrypt,
        silentOn = silentOn,
        stallAfterBlocks = stallAfterBlocks,
        workoutListBreaksAbove = workoutListBreaksAbove,
    )

    private class Session(val band: HuaweiHistoryBand, val link: FakeHuaweiLink)

    private fun session(s: HuaweiHistoryBand.Script): Session {
        val b = HuaweiHistoryBand(s)
        val link = FakeHuaweiLink(b, pushReplay = 0)
        link.configure(b.linkConfig())
        return Session(b, link)
    }

    private fun sync(s: Session, caps: HuaweiCapabilities = caps(), at: Long = now): HuaweiSyncSummary = runBlocking {
        HuaweiHistorySync(prefs, store = { id, batch -> HuaweiHistoryIngest.store(db, id, batch); true },
            clock = { at }, utcOffset = { 0 }, replyTimeoutMs = 300).run(s.link, caps, band)
    }

    private fun rows(sql: String, vararg args: Any): List<List<Any?>> =
        db.openHelper.readableDatabase.query(sql, args).use { c ->
            buildList {
                while (c.moveToNext()) add((0 until c.columnCount).map { i ->
                    when (c.getType(i)) {
                        android.database.Cursor.FIELD_TYPE_NULL -> null
                        android.database.Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                        android.database.Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                        else -> c.getString(i)
                    }
                })
            }
        }

    private fun snapshot(): Map<String, List<List<Any?>>> = listOf(
        "hrSample" to "SELECT ts, bpm FROM hrSample WHERE deviceId = ? ORDER BY ts",
        "deviceMetricSample" to "SELECT kind, ts, value, source FROM deviceMetricSample WHERE deviceId = ? ORDER BY kind, ts",
        "sleepSession" to "SELECT startTs, endTs, efficiency, avgHrv, stagesJSON FROM sleepSession WHERE deviceId = ? ORDER BY startTs",
        "dailyMetric" to "SELECT day, totalSleepMin, deepMin, remMin, lightMin, restingHr, avgHrv, spo2Pct, steps, activeKcalEst FROM dailyMetric WHERE deviceId = ? ORDER BY day",
        "metricSeries" to "SELECT day, `key`, value FROM metricSeries WHERE deviceId = ? ORDER BY day, `key`",
        "workout" to "SELECT startTs, endTs, sport, distanceM, energyKcal, steps, avgHr, maxHr FROM workout WHERE deviceId = ? ORDER BY startTs",
    ).associate { (name, sql) -> name to rows(sql, band) }

    private fun series(day: String, key: String): Double? =
        rows("SELECT value FROM metricSeries WHERE deviceId = ? AND day = ? AND `key` = ?", band, day, key).singleOrNull()?.get(0) as Double?

    private fun metric(kind: DeviceMetricKind): List<Pair<Long, Double>> =
        rows("SELECT ts, value FROM deviceMetricSample WHERE deviceId = ? AND kind = ? ORDER BY ts", band, kind.wire)
            .map { (it[0] as Long) to (it[1] as Double) }

    private val todayKey = "2025-11-02"
    private val yesterdayKey = "2025-11-01"

    @Test
    fun aFullSyncStoresEveryMetricTheBandHas() {
        val s = session(script())
        val summary = sync(s)
        assertTrue(summary.toString(), summary.stored)
        assertTrue(summary.failures.toString(), summary.failures.isEmpty())

        val hr = rows("SELECT ts, bpm FROM hrSample WHERE deviceId = ? ORDER BY ts", band)
        assertTrue(listOf(yesterday + 8 * 3_600, 70L) in hr)
        assertTrue("workout samples land as HR too", listOf(yesterday + 18 * 3_600 + 60, 150L) in hr)
        assertEquals(listOf(95.0, 96.0, 97.0, 98.0), metric(DeviceMetricKind.SPO2_PCT).map { it.second })
        assertEquals(listOf(48.5, 51.0), metric(DeviceMetricKind.HRV_MS).map { it.second })
        assertEquals(listOf(30.0, 50.0), metric(DeviceMetricKind.STRESS).map { it.second })
        assertEquals(listOf(nightEnd to 82.0), metric(DeviceMetricKind.SLEEP_SCORE))
        assertEquals(listOf(34.2), metric(DeviceMetricKind.SKIN_TEMP_C).map { it.second })
        assertEquals(listOf(3.2), metric(DeviceMetricKind.AEROBIC_TRAINING_EFFECT).map { it.second })
        assertEquals(listOf(120.0), metric(DeviceMetricKind.TRAINING_LOAD).map { it.second })

        val sleep = rows("SELECT startTs, endTs, stagesJSON FROM sleepSession WHERE deviceId = ?", band).single()
        assertEquals(nightStart, sleep[0])
        assertEquals(nightEnd, sleep[1])
        val stages = sleep[2] as String
        assertTrue(stages, stages.contains("\"deep\"") && stages.contains("\"rem\"") && stages.contains("\"wake\""))

        val day = rows("SELECT totalSleepMin, deepMin, remMin, lightMin, spo2Pct FROM dailyMetric WHERE deviceId = ? AND day = ?", band, todayKey).single()
        assertEquals(listOf(330.0, 60.0, 60.0, 210.0, 96.5), day)
        val prev = rows("SELECT steps, restingHr, activeKcalEst FROM dailyMetric WHERE deviceId = ? AND day = ?", band, yesterdayKey).single()
        assertEquals(listOf(150L, 55L, 8.0), prev)

        assertEquals(150.0, series(yesterdayKey, "steps"))
        assertEquals(120.0, series(yesterdayKey, "distance_m"))
        assertEquals(8.0, series(yesterdayKey, "total_kcal"))
        assertEquals(55.0, series(yesterdayKey, "rhr"))
        assertEquals(45.0, series(todayKey, "hrv"))
        assertEquals(96.5, series(todayKey, "spo2"))
        assertEquals(82.0, series(todayKey, "ref_sleep_score"))
        assertEquals(330.0, series(todayKey, "sleep_total_min"))
        assertEquals(60.0, series(todayKey, "sleep_deep_min"))
        assertEquals(40.0, series(todayKey, StressToday.HUAWEI_STRESS_KEY))
        assertNull("the band's stress never lands in WHOOP's 0-3 series", series(todayKey, "stress"))
        assertNull(series(todayKey, GarminSyncStore.GARMIN_STRESS_KEY))

        val w = rows("SELECT sport, distanceM, energyKcal, steps, avgHr, maxHr, source FROM workout WHERE deviceId = ?", band).single()
        assertEquals(listOf("Running", 5_000.0, 250.0, 6_000L, 150L, 165L, band), w)
    }

    @Test
    fun aSecondSyncIsIdempotentAndWatermarksAdvance() {
        sync(session(script()))
        val first = snapshot()
        assertEquals(now, prefs.lastSync(band, HuaweiDataType.STEPS))
        assertEquals(now, prefs.lastSync(band, HuaweiDataType.SLEEP_FILE))
        assertEquals(now, prefs.lastSync(band, HuaweiDataType.STRESS))
        assertEquals(yesterday + 18 * 3_600 + 1_800, prefs.lastWorkoutEnd(band))
        assertEquals((nightStart + 7_500) * 1000, prefs.dictionaryCursorMs(band, HuaweiDictionaryDecoder.CLASS_HRV))
        assertTrue(prefs.switchesEnabled(band))

        val again = session(script())
        sync(again, at = now + 1_800)
        assertEquals(first, snapshot())
        assertEquals(now + 1_800, prefs.lastSync(band, HuaweiDataType.STEPS))

        val sent = again.band.received
        val stepCount = sent.first { it.serviceId == 0x07 && it.commandId == 0x0A }.tlv
        assertEquals("steps re-read from the watermark's local midnight", today, stepCount.u32(0x03))
        val workoutCount = sent.first { it.serviceId == 0x17 && it.commandId == 0x07 }.tlv.tlv(0x81)
        assertEquals("workouts resume after the last stored end", yesterday + 18 * 3_600 + 1_801, workoutCount.u32(0x03))
        val dict = sent.first { it.serviceId == 0x34 && it.tlv.byte(0x01) == 2 }.tlv
        val dictReq = HuaweiTlv.parse(dict.bytes(0x07), 1)
        assertEquals("the HRV cursor resumes a second after the last record", (nightStart + 7_500) * 1000 + 1000, dictReq.unsigned(0x05))
        assertTrue("the switches are sent once per band", sent.none { it.serviceId == 0x07 && it.commandId in setOf(0x17, 0x23, 0x24) })
    }

    @Test
    fun theOldSyncPathAndTheLegacySleepRecordsStoreTheSame() {
        val legacy = listOf(listOf(
            0x06 to HuaweiFixtures.be().i32(nightStart).i16(90).build(),
            0x07 to HuaweiFixtures.be().i32(nightStart + 5_400).i16(60).build(),
        ))
        val s = session(script(files = mapOf("rrisqi_data.bin" to HuaweiHistoryBand.stressFile(Triple(today + 7_200, today + 7_500, 30))),
            legacySleep = legacy))
        val summary = sync(s, caps(newSync = false, legacySleep = true, sleepFile = false))
        assertTrue(summary.failures.toString(), summary.failures.isEmpty())
        assertTrue(s.band.received.any { it.serviceId == 0x0A && it.commandId == 0x04 })
        assertTrue(s.band.received.none { it.serviceId == 0x2C })
        val init = s.band.received.first { it.serviceId == 0x0A && it.commandId == 0x01 }.tlv
        assertEquals(0x04, init.byte(0x02))
        assertEquals(today - 14 * 86_400 + 43_200, init.tlv(0x8F).u32(0x10))
        assertEquals(listOf(30.0), metric(DeviceMetricKind.STRESS).map { it.second })
        val sleep = rows("SELECT startTs, endTs FROM sleepSession WHERE deviceId = ?", band).single()
        assertEquals(listOf(nightStart, nightStart + 9_000), sleep)
        assertEquals(150.0, rows("SELECT totalSleepMin FROM dailyMetric WHERE deviceId = ? AND day = ?", band, todayKey).single()[0])
    }

    @Test
    fun encryptedAndPlaintextBlocksDecodeAlike() {
        sync(session(script(encryptBlocks = true, maxBlock = 33)))
        val encrypted = snapshot()
        db.clearAllTables()
        prefs.dropDeviceState(band)
        val plain = session(script(noEncrypt = true, maxBlock = 33))
        sync(plain)
        assertEquals(encrypted, snapshot())
        val blocks = plain.band.received.filter { it.serviceId == 0x2C && it.commandId == 0x04 }
        assertTrue(blocks.size > 2)
        assertTrue("a band that cannot take an encrypted block request gets it in plaintext", blocks.none { it.encrypted })
        assertTrue(plain.link.sent.none { it.command == HuaweiCommand.FILE2C_BLOCK })
    }

    @Test
    fun emptyFilesAndMissingCapabilitiesStoreNothingAndStillAdvance() {
        val s = session(HuaweiHistoryBand.Script())
        val summary = sync(s, HuaweiCapabilities(commands = mapOf(0x07 to setOf(0x16), 0x20 to setOf(0x09)),
            expand = expand(HuaweiHistorySync.EXPAND_NEW_SLEEP), settings = HuaweiSettingRelated(true, true, false)))
        assertTrue(summary.empty)
        assertTrue(summary.failures.isEmpty())
        assertEquals(now, prefs.lastSync(band, HuaweiDataType.SLEEP_FILE))
        assertNull(prefs.lastSync(band, HuaweiDataType.STEPS))
        assertEquals(1, prefs.emptyStreak(band))
        assertTrue(snapshot().values.all { it.isEmpty() })
        assertEquals(setOf(0x2C to 0x01, 0x2C to 0x06), s.band.received.map { it.serviceId to it.commandId }.toSet())
        assertTrue("no switch the band did not list", s.link.sent.none { it.command == HuaweiCommand.AUTO_HEART_RATE })
    }

    @Test
    fun aTimeoutLeavesThatStreamsWatermarkAndKeepsTheRest() {
        val s = session(script(stallAfterBlocks = 1, silentOn = setOf(0x17 to 0x08)))
        val summary = sync(s)
        assertTrue(summary.stored)
        assertTrue(summary.failures.toString(), summary.failures.any { it.startsWith("sleep_file") })
        assertTrue(summary.failures.toString(), summary.failures.any { it.startsWith("workouts") })
        assertNull(prefs.lastSync(band, HuaweiDataType.SLEEP_FILE))
        assertNull(prefs.lastSync(band, HuaweiDataType.WORKOUTS))
        assertNull(prefs.lastWorkoutEnd(band))
        assertEquals(now, prefs.lastSync(band, HuaweiDataType.STEPS))
        assertFalse(metric(DeviceMetricKind.SPO2_PCT).isEmpty())
        assertTrue(rows("SELECT 1 FROM sleepSession WHERE deviceId = ?", band).isEmpty())
    }

    @Test
    fun aWorkoutListThatDisagreesSplitsTheRange() {
        val s = session(script(workoutListBreaksAbove = 3 * 86_400L))
        val summary = sync(s)
        assertTrue(summary.failures.toString(), summary.failures.isEmpty())
        val counts = s.band.received.filter { it.serviceId == 0x17 && it.commandId == 0x07 }
        assertTrue("split at least twice: ${counts.size}", counts.size >= 5)
        assertEquals(1, rows("SELECT 1 FROM workout WHERE deviceId = ?", band).size)
    }

    @Test
    fun aListThatNeverAgreesGivesUpAfterThreeSplits() {
        val s = session(script(workoutListBreaksAbove = 0L))
        val summary = sync(s)
        assertTrue(summary.failures.toString(), summary.failures.any { it.startsWith("workouts") })
        // The first half of every split fails too, so the walk stops after one request per depth.
        assertEquals(1 + HuaweiHistorySync.MAX_WORKOUT_SPLITS, s.band.received.count { it.serviceId == 0x17 && it.commandId == 0x07 })
        assertTrue(rows("SELECT 1 FROM workout WHERE deviceId = ?", band).isEmpty())
    }

    /** Every command a sync sends is a history read, a block/done, P2P to the dictionary, or a switch set ON. */
    @Test
    fun noBannedCommandIsEverSent() {
        val sessions = listOf(session(script()), session(script(noEncrypt = true)))
        sync(sessions[0])
        prefs.dropDeviceState(band)
        sync(sessions[1], caps(newSync = false, legacySleep = true))
        val allowedReads = setOf(0x07 to 0x0A, 0x07 to 0x0B, 0x07 to 0x0C, 0x07 to 0x0D, 0x17 to 0x07, 0x17 to 0x08,
            0x17 to 0x0A, 0x17 to 0x15, 0x2C to 0x01, 0x2C to 0x03, 0x2C to 0x04, 0x2C to 0x06,
            0x0A to 0x01, 0x0A to 0x02, 0x0A to 0x03, 0x0A to 0x04, 0x0A to 0x06, 0x34 to 0x01)
        val switches = setOf(0x07 to 0x17, 0x07 to 0x23, 0x07 to 0x24)
        for (s in sessions) for (r in s.band.received) {
            val id = r.serviceId to r.commandId
            assertTrue("sent %02X/%02X".format(r.serviceId, r.commandId), id in allowedReads || id in switches)
            if (id in switches) assertTrue("a switch is only ever turned on", com.noop.huawei.protocol.HuaweiRequest.isSwitchOn(r.tlv))
            if (id == 0x34 to 0x01) assertTrue(com.noop.huawei.protocol.HuaweiRequest.isDictionaryP2P(r.tlv))
            assertFalse("no service outside history sync", r.serviceId in setOf(0x09, 0x28, 0x27, 0x2A, 0x1F, 0x1C))
            assertFalse("never a factory reset", id == 0x01 to 0x0D)
        }
    }

    @Test
    fun deletingTheBandsDataDropsItsRowsAndItsWatermarks() = runBlocking {
        val registry = DeviceRegistry(db, moveCredentials = { from, to -> prefs.moveDeviceState(from, to) },
            dropSyncState = { prefs.dropDeviceState(it) })
        sync(session(script()))
        assertTrue(snapshot().values.all { it.isNotEmpty() })
        registry.deleteDeviceData(band)
        assertTrue(snapshot().values.all { it.isEmpty() })
        assertNull(prefs.lastSync(band, HuaweiDataType.STEPS))
        assertFalse(prefs.switchesEnabled(band))
    }

    @Test
    fun aReKeyMovesTheBandsRowsAndItsWatermarks() = runBlocking {
        val registry = DeviceRegistry(db, moveCredentials = { from, to -> prefs.moveDeviceState(from, to) },
            dropSyncState = { prefs.dropDeviceState(it) })
        registry.add(PairedDeviceRow(id = band, brand = "Huawei", model = "Band 10", nickname = null,
            sourceKind = "huawei", capabilities = "hr", status = "active", addedAt = 1, lastSeenAt = 1))
        sync(session(script()))
        val before = snapshot()
        val moved = "huawei-TEST-0002"
        assertTrue(registry.adoptSerialIdentity(band, moved))
        assertTrue(snapshot().values.all { it.isEmpty() })
        assertEquals(now, prefs.lastSync(moved, HuaweiDataType.STEPS))
        assertNull(prefs.lastSync(band, HuaweiDataType.STEPS))
        assertEquals(before["workout"]!!.size, rows("SELECT 1 FROM workout WHERE deviceId = ?", moved).size)
        prefs.dropDeviceState(moved)
    }

    @Test
    fun theRunnerAsksForPostSyncAnalysisOnlyWhenRowsLand() = runBlocking {
        var analyses = 0
        val runner = HuaweiHistoryIngest.runner(context, db, onStored = { analyses++ }, log = {})
        val s = session(script())
        runner.sync(s.link, caps(), band)
        assertEquals(1, analyses)
        HuaweiSyncPrefs.of(context).dropDeviceState(band)
        val empty = session(HuaweiHistoryBand.Script())
        runner.sync(empty.link, HuaweiCapabilities(), band)
        assertEquals(1, analyses)
        HuaweiSyncPrefs.of(context).dropDeviceState(band)
    }

    @Test
    fun skinTemperaturePendingRowsLandOnTheEnumKind() {
        sync(session(script()))
        assertNotNull(DeviceMetricKind.entries.firstOrNull { it.wire == com.noop.ingest.HuaweiWatchIngest.SKIN_TEMP_WIRE })
        assertEquals(listOf((nightStart + 3_600) to 34.2), metric(DeviceMetricKind.SKIN_TEMP_C))
    }
}
