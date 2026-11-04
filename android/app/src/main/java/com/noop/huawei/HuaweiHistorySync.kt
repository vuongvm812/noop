package com.noop.huawei

import com.noop.huawei.decode.HuaweiDictionaryDecoder
import com.noop.huawei.decode.HuaweiFitnessDecoder
import com.noop.huawei.decode.HuaweiSleepFileDecoder
import com.noop.huawei.decode.HuaweiStressDecoder
import com.noop.huawei.decode.HuaweiWorkoutDecoder
import com.noop.huawei.protocol.HuaweiBytes
import com.noop.huawei.protocol.HuaweiCommand
import com.noop.huawei.protocol.HuaweiLink
import com.noop.huawei.protocol.HuaweiProtocolException
import com.noop.huawei.protocol.HuaweiPush
import com.noop.huawei.protocol.HuaweiRequest
import com.noop.huawei.protocol.HuaweiTlv
import com.noop.ingest.HuaweiWatchIngest
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

// Huawei band history sync: reads every history stream the band says it supports over an authenticated
// HuaweiLink, decodes it with com.noop.huawei.decode and hands one HuaweiWatchIngest.Batch to [store].
// Read-only toward the band except the three measurement switches (automatic HR, automatic SpO2,
// resting HR), sent once per band and only ever as "on". Nothing on the band is deleted.
//
// Protocol facts (re-derived, no code copied) from Gadgetbridge (AGPL-3.0):
// `service/devices/huawei/HuaweiSupportProvider.java` (fetchActivityData), `HuaweiWorkoutSyncManager.java`,
// `p2p/HuaweiP2PDataDictionarySyncService.java`, `p2p/HuaweiBaseP2PService.java`,
// `devices/huawei/packets/{FitnessData,Workout,P2P}.java`, `devices/huawei/HuaweiState.java`,
// `HuaweiTruSleepParser.java` (sleep_state.bin) and `HuaweiFileDownloadManager.java`.
// Kotlin-only; Swift twin deferred (fork).

/** What one sync did. [stored] is false when nothing reached the database. */
data class HuaweiSyncSummary(
    val stored: Boolean,
    /** Decoded items per stream (minutes, sleep records, nights, stress entries, dictionary values, workouts). */
    val counts: Map<String, Int>,
    /** Streams that stopped early; their watermarks did not move. */
    val failures: List<String>,
) {
    val empty: Boolean get() = counts.values.all { it == 0 }
}

class HuaweiHistorySync(
    private val cursor: HuaweiSyncCursor,
    /** Persists one batch; true when it committed. The app's is [com.noop.data.HuaweiHistoryIngest.store]. */
    private val store: suspend (deviceId: String, batch: HuaweiWatchIngest.Batch) -> Boolean,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
    private val utcOffset: (Long) -> Int = { ts -> java.util.TimeZone.getDefault().getOffset(ts * 1000) / 1000 },
    private val log: (String) -> Unit = {},
    private val replyTimeoutMs: Long = HuaweiFileDownload.DEFAULT_REPLY_TIMEOUT_MS,
) : HuaweiSyncRunner {

    override suspend fun sync(link: HuaweiLink, capabilities: HuaweiCapabilities, deviceId: String) {
        run(link, capabilities, deviceId)
    }

    /** One full sync. Each stream runs on its own; a failure in one leaves the others and its own watermark. */
    override suspend fun run(
        link: HuaweiLink,
        capabilities: HuaweiCapabilities,
        deviceId: String,
        progress: (HuaweiSyncStage) -> Unit,
    ): HuaweiSyncSummary {
        val now = clock()
        val session = Session(link, capabilities, deviceId, now)
        progress(HuaweiSyncStage.SETTINGS)
        session.enableSwitchesOnce()
        progress(HuaweiSyncStage.STEPS)
        session.syncSteps()
        progress(HuaweiSyncStage.SLEEP)
        session.syncLegacySleep()
        session.syncSleepFile()
        session.syncSleepState()
        progress(HuaweiSyncStage.STRESS)
        session.syncStress()
        progress(HuaweiSyncStage.DICTIONARY)
        session.syncDictionary()
        progress(HuaweiSyncStage.WORKOUTS)
        session.syncWorkouts()

        val batch = HuaweiWatchIngest.Batch(
            minutes = session.minutes, sleepSpans = session.sleepSpans, nights = session.nights,
            sleepFrames = session.sleepFrames,
            stress = session.stress, dictionary = session.dictionary, workouts = session.workouts,
            utcOffsetS = utcOffset(now), nowTs = now,
        )
        val counts = linkedMapOf(
            "minutes" to batch.minutes.size, "sleep_records" to batch.sleepSpans.size, "nights" to batch.nights.size,
            "sleep_frames" to batch.sleepFrames.size,
            "stress" to batch.stress.size, "dictionary" to batch.dictionary.size, "workouts" to batch.workouts.size,
            "pace_blocks" to batch.workouts.sumOf { it.pace.size },
        )
        progress(HuaweiSyncStage.STORING)
        val stored = try {
            store(deviceId, batch)
        } catch (e: Exception) {
            log("Huawei: storing the sync failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
        if (stored) session.commitWatermarks()
        cursor.recordSyncOutcome(deviceId, now, empty = counts.values.all { it == 0 })
        val summary = HuaweiSyncSummary(stored, counts, session.failures)
        log("Huawei: sync ${if (stored) "stored" else "not stored"} $counts" +
            if (session.failures.isEmpty()) "" else " failures=${session.failures}")
        return summary
    }

    private inner class Session(val link: HuaweiLink, val caps: HuaweiCapabilities, val deviceId: String, val now: Long) {
        val minutes = ArrayList<HuaweiFitnessDecoder.MinuteSample>()
        val sleepSpans = ArrayList<HuaweiFitnessDecoder.SleepSpan>()
        val nights = ArrayList<HuaweiSleepFileDecoder.Night>()
        val sleepFrames = ArrayList<HuaweiSleepFileDecoder.StateFrame>()
        val stress = ArrayList<HuaweiStressDecoder.Entry>()
        val dictionary = ArrayList<HuaweiDictionaryDecoder.Sample>()
        val workouts = ArrayList<HuaweiWatchIngest.Workout>()
        val failures = ArrayList<String>()

        /** Watermarks to write once the batch is stored. */
        private val done = LinkedHashMap<HuaweiDataType, Long>()
        private val dictionaryCursors = LinkedHashMap<Int, Long>()
        private var workoutEnd: Long? = null
        private var p2pSequence = 0

        private val files = HuaweiFileDownload(link, replyTimeoutMs)

        private suspend fun ask(command: HuaweiCommand, tlv: HuaweiTlv): HuaweiTlv =
            withTimeout(replyTimeoutMs) { link.request(HuaweiRequest.of(command, tlv)) }

        /** Runs one stream; a timeout or malformed reply is recorded and the stream's watermark stays. */
        private suspend fun stream(name: String, block: suspend () -> Unit) {
            try {
                block()
            } catch (e: TimeoutCancellationException) {
                failures.add("$name: no reply within ${replyTimeoutMs} ms")
            } catch (e: HuaweiProtocolException) {
                failures.add("$name: ${e.message}")
            } catch (e: UnsupportedOperationException) {
                failures.add("$name: ${e.message}")
            }
        }

        /** Start of a stream's range: its watermark, or the first backfill window. */
        private fun since(type: HuaweiDataType): Long =
            cursor.lastSync(deviceId, type) ?: (now - HuaweiRadioPolicy.FIRST_BACKFILL_SECONDS)

        fun commitWatermarks() {
            for ((type, ts) in done) cursor.setLastSync(deviceId, type, ts)
            for ((cls, ms) in dictionaryCursors) cursor.setDictionaryCursorMs(deviceId, cls, ms)
            workoutEnd?.let { cursor.setLastWorkoutEnd(deviceId, it) }
        }

        // MARK: measurement switches

        /**
         * Automatic HR (07/17), automatic SpO2 (07/24) and the resting-HR notification (07/23), once per band
         * and only those it supports. Each is a reversible setting the user can turn off on the band.
         */
        suspend fun enableSwitchesOnce() {
            if (cursor.switchesEnabled(deviceId)) return
            val switches = listOf(
                HuaweiCommand.AUTO_HEART_RATE to HuaweiTlv().putBoolean(0x01, true),
                HuaweiCommand.AUTO_SPO2 to HuaweiTlv().putBoolean(0x01, true),
                HuaweiCommand.RESTING_HEART_RATE to HuaweiTlv().putInt(0x01, 1),
            ).filter { (c, _) -> caps.supports(c.serviceId, c.commandId) }
            val before = failures.size
            for ((command, tlv) in switches) stream("switch $command") {
                val code = ask(command, tlv).resultCode()
                if (code != null && code != HuaweiTlv.RESULT_OK) failures.add("switch $command: band answered 0x%X".format(code))
            }
            // A switch that failed is tried again next sync; the band keeps the ones that took.
            if (failures.size == before) cursor.setSwitchesEnabled(deviceId)
        }

        // MARK: per-minute steps / HR / SpO2 / resting HR

        /**
         * 07/0A count over [start, now] then 07/0B per index. The range starts at the local midnight of the
         * watermark, so every day the batch touches arrives whole and its summed totals are complete.
         */
        suspend fun syncSteps() {
            if (!caps.supports(0x07, 0x0A) || !caps.supports(0x07, 0x0B)) return
            stream("steps") {
                val start = localMidnight(since(HuaweiDataType.STEPS))
                val count = countReply(HuaweiCommand.STEP_DATA_COUNT, start, now)
                for (i in 0 until count) {
                    val c = ask(HuaweiCommand.STEP_DATA, HuaweiTlv().put(0x81, HuaweiTlv().putShort(0x02, i))).tlv(0x81)
                    val base = c.u32(0x03)
                    for (rec in c.tlvs(0x84)) {
                        val offset = rec.byte(0x05)
                        HuaweiFitnessDecoder.minuteSample(base, offset, rec.bytes(0x06))?.let { minutes.add(it) }
                    }
                }
                done[HuaweiDataType.STEPS] = now
            }
        }

        // MARK: sleep

        /** Legacy light / deep records, 07/0C count then 07/0D per index. Re-reads a day back for a night in progress. */
        suspend fun syncLegacySleep() {
            if (!caps.supports(0x07, 0x0C) || !caps.supports(0x07, 0x0D)) return
            stream("sleep") {
                val start = since(HuaweiDataType.SLEEP) - 86_400
                val count = countReply(HuaweiCommand.SLEEP_DATA_COUNT, start, now)
                for (i in 0 until count) {
                    val c = ask(HuaweiCommand.SLEEP_DATA, HuaweiTlv().put(0x81, HuaweiTlv().putShort(0x02, i))).tlv(0x81)
                    for (rec in c.tlvs(0x83)) {
                        HuaweiFitnessDecoder.sleepSpan(rec.byte(0x04), rec.bytes(0x05))?.let { sleepSpans.add(it) }
                    }
                }
                done[HuaweiDataType.SLEEP] = now
            }
        }

        /**
         * The TruSleep `sequence_data` file (dictionary 700013, always over 0x2C) on a band with expand bit
         * 143 and TruSleep (07/16). Bands without bit 143 keep the legacy records above: the older
         * `sleep_state.bin` / `sleep_data.bin` pair carries no stage the fold can place.
         */
        suspend fun syncSleepFile() {
            if (!caps.supports(0x07, 0x16) || !caps.hasExpand(EXPAND_NEW_SLEEP)) return
            stream("sleep_file") {
                val start = since(HuaweiDataType.SLEEP_FILE) - 86_400
                val request = HuaweiFileRequest("sequence_data", HuaweiFileRequest.TYPE_SEQUENCE_DATA, start, now,
                    dictId = HuaweiFileRequest.DICT_SLEEP)
                when (val r = files.download(request, newSync = true)) {
                    is HuaweiFileOutcome.Downloaded -> nights.addAll(HuaweiSleepFileDecoder.sequenceFile(r.bytes))
                    HuaweiFileOutcome.Empty -> Unit
                    is HuaweiFileOutcome.Failed -> { failures.add("sleep_file: ${r.reason}"); return@stream }
                }
                done[HuaweiDataType.SLEEP_FILE] = now
            }
        }

        /**
         * TruSleep `sleep_state.bin` on a band with TruSleep (07/16) but without expand bit 143: its time frames
         * are sleep ranges with no stages, which the fold keeps only where no other record covers the night.
         * Over 0x2C when 01/31 has the TruSleep file bit, else 0x0A.
         */
        suspend fun syncSleepState() {
            if (!caps.supports(0x07, 0x16) || caps.hasExpand(EXPAND_NEW_SLEEP)) return
            stream("sleep_state") {
                val start = since(HuaweiDataType.SLEEP_STATE) - 86_400
                val request = HuaweiFileRequest("sleep_state.bin", HuaweiFileRequest.TYPE_SLEEP_STATE, start, now,
                    oldSyncType = HuaweiFileRequest.OLD_SLEEP)
                when (val r = files.download(request, newSync = caps.settings?.truSleepFileSync == true)) {
                    is HuaweiFileOutcome.Downloaded -> sleepFrames.addAll(HuaweiSleepFileDecoder.stateFile(r.bytes))
                    HuaweiFileOutcome.Empty -> Unit
                    is HuaweiFileOutcome.Failed -> { failures.add("sleep_state: ${r.reason}"); return@stream }
                }
                done[HuaweiDataType.SLEEP_STATE] = now
            }
        }

        // MARK: stress

        /** `rrisqi_data.bin` on a band with automatic stress (20/09): 0x2C when 01/31 has the RRI bit, else 0x0A. */
        suspend fun syncStress() {
            if (!caps.supports(0x20, 0x09)) return
            stream("stress") {
                val start = since(HuaweiDataType.STRESS)
                val request = HuaweiFileRequest("rrisqi_data.bin", HuaweiFileRequest.TYPE_RRI, start, now,
                    oldSyncType = HuaweiFileRequest.OLD_RRI)
                when (val r = files.download(request, newSync = caps.settings?.rriFileSync == true)) {
                    is HuaweiFileOutcome.Downloaded -> stress.addAll(HuaweiStressDecoder.rriFile(r.bytes))
                    HuaweiFileOutcome.Empty -> Unit
                    is HuaweiFileOutcome.Failed -> { failures.add("stress: ${r.reason}"); return@stream }
                }
                done[HuaweiDataType.STRESS] = now
            }
        }

        // MARK: P2P data dictionary (HRV, skin temperature)

        suspend fun syncDictionary() {
            if (!caps.supports(0x34, 0x01)) return
            val classes = buildList {
                if (caps.hasExpand(EXPAND_HRV)) add(HuaweiDictionaryDecoder.CLASS_HRV)
                if (caps.hasExpand(EXPAND_SKIN_TEMPERATURE)) add(HuaweiDictionaryDecoder.CLASS_SKIN_TEMPERATURE)
            }
            if (classes.isEmpty()) return
            stream("dictionary") {
                if (!pingDictionary()) {
                    failures.add("dictionary: the band's dictionary service did not answer as available")
                    return@stream
                }
                for (cls in classes) syncDictionaryClass(cls)
            }
        }

        /** P2P ping; available when the band's answer carries code 0xCA. */
        private suspend fun pingDictionary(): Boolean {
            val seq = nextSequence()
            val reply = p2pExchange(
                HuaweiTlv().putByte(0x01, P2P_PING).putShort(0x02, seq)
                    .putString(0x03, PKG_HEALTH).putString(0x04, PKG_BAND_FILESYNC),
            ) { it.p2pSequence() == seq && it.tlv.contains(0x08) }
            return reply.tlv.unsigned(0x08) == P2P_AVAILABLE.toLong()
        }

        /** Pages one class from its cursor until the band returns no records. */
        private suspend fun syncDictionaryClass(cls: Int) {
            var startMs = cursor.dictionaryCursorMs(deviceId, cls)?.plus(1000) ?: ((now - HuaweiRadioPolicy.FIRST_BACKFILL_SECONDS) * 1000)
            repeat(MAX_DICTIONARY_PAGES) {
                val payload = HuaweiTlv().putByte(0x01, 1).put(0x02, minimalBytes(cls))
                    .putLong(0x05, startMs).putLong(0x06, now * 1000).putByte(0x0D, 1)
                val seq = nextSequence()
                val push = p2pExchange(
                    HuaweiTlv().putByte(0x01, P2P_DATA).putShort(0x02, seq)
                        .putString(0x03, PKG_DICTIONARY).putString(0x04, PKG_BAND_FILESYNC)
                        .putString(0x05, FP_DICTIONARY).putString(0x06, FP_BAND)
                        .put(0x07, byteArrayOf(0x01) + payload.serialize()),
                ) { it.p2pCommand() == P2P_DATA && dictionaryClass(it) == cls }
                ackP2P(push)
                val records = dictionaryRecords(push.tlv.bytes(0x07), cls)
                if (records.isEmpty()) return
                dictionary.addAll(HuaweiDictionaryDecoder.samples(records))
                val last = records.maxOf { maxOf(it.startMs, it.endMs, it.modifiedMs) }
                dictionaryCursors[cls] = last
                if (last + 1000 <= startMs) return
                startMs = last + 1000
            }
        }

        /** Sends a P2P message and waits for the band's 34/01 push that [matches]. */
        private suspend fun p2pExchange(tlv: HuaweiTlv, matches: (HuaweiPush) -> Boolean): HuaweiPush = coroutineScope {
            val inbox = Channel<HuaweiPush>(Channel.UNLIMITED)
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                link.pushes.filter { it.serviceId == 0x34 && it.commandId == 0x01 }.collect { inbox.send(it) }
            }
            try {
                link.send(HuaweiRequest.of(HuaweiCommand.P2P, tlv))
                withTimeout(replyTimeoutMs) {
                    var push = inbox.receive()
                    while (!matches(push)) push = inbox.receive()
                    push
                }
            } finally {
                collector.cancel()
            }
        }

        /** Acknowledges a band data message with the code Gadgetbridge's dictionary sync answers (0xCF). */
        private suspend fun ackP2P(push: HuaweiPush) {
            val t = push.tlv
            val seq = push.p2pSequence() ?: return
            val src = t.bytesOrNull(0x04)?.toString(Charsets.UTF_8) ?: PKG_DICTIONARY
            val dst = t.bytesOrNull(0x03)?.toString(Charsets.UTF_8) ?: PKG_BAND_FILESYNC
            link.send(HuaweiRequest.of(HuaweiCommand.P2P, HuaweiTlv().putByte(0x01, P2P_ACK).putShort(0x02, seq)
                .putString(0x03, src).putString(0x04, dst).putInt(0x08, P2P_ACK_CODE)))
        }

        private fun nextSequence(): Int {
            p2pSequence = if (p2pSequence >= 32_766) 1 else p2pSequence + 1
            return p2pSequence
        }

        // MARK: workouts

        suspend fun syncWorkouts() {
            if (!caps.supports(0x17, 0x01) || !caps.supports(0x17, 0x07)) return
            stream("workouts") {
                val newSteps = caps.supports(0x17, 0x15) && try {
                    val flags = ask(HuaweiCommand.WORKOUT_CAPABILITY, HuaweiTlv().put(0x01))
                    flags.contains(0x01) && flags.unsigned(0x01) and 0x02L != 0L
                } catch (e: TimeoutCancellationException) {
                    false
                }
                val start = cursor.lastWorkoutEnd(deviceId)?.plus(1) ?: (now - HuaweiRadioPolicy.FIRST_BACKFILL_SECONDS)
                val headers = workoutHeaders(start, now, 0) ?: return@stream
                var latestEnd = cursor.lastWorkoutEnd(deviceId)
                for (h in headers.sortedBy { it.number }) {
                    val w = workout(h, newSteps) ?: continue
                    workouts.add(w)
                    latestEnd = maxOf(latestEnd ?: 0L, w.totals.endTs)
                }
                workoutEnd = latestEnd
                done[HuaweiDataType.WORKOUTS] = now
            }
        }

        private inner class WorkoutHeader(val number: Int, val dataCount: Int, val paceCount: Int)

        /**
         * 17/07 over [start, end]. A band error, or a count that disagrees with its own list, splits the range
         * in two, up to [MAX_WORKOUT_SPLITS] deep; past that the range is reported and null returned.
         */
        private suspend fun workoutHeaders(start: Long, end: Long, depth: Int): List<WorkoutHeader>? {
            val reply = ask(HuaweiCommand.WORKOUT_COUNT,
                HuaweiTlv().put(0x81, HuaweiTlv().putInt(0x03, start.toInt()).putInt(0x04, end.toInt())))
            val code = reply.resultCode()
            val list = if (code == null && reply.contains(0x81)) {
                val c = reply.tlv(0x81)
                val count = if (c.contains(0x02)) c.u16(0x02) else 0
                val items = c.tlvs(0x85).map {
                    WorkoutHeader(it.u16(0x06), if (it.contains(0x07)) it.u16(0x07) else 0, if (it.contains(0x08)) it.u16(0x08) else 0)
                }
                items.takeIf { it.size == count }
            } else null
            if (list != null) return list
            if (depth >= MAX_WORKOUT_SPLITS || end - start < 2) {
                failures.add("workouts: the band's list for $start-$end stayed inconsistent")
                return null
            }
            val mid = start + (end - start) / 2
            val a = workoutHeaders(start, mid, depth + 1) ?: return null
            val b = workoutHeaders(mid + 1, end, depth + 1) ?: return null
            return a + b
        }

        /** Totals (17/08) and every sample page (17/0A); a GPS track (0x2C) when 01/31 has the GPS bit. */
        private suspend fun workout(h: WorkoutHeader, newSteps: Boolean): HuaweiWatchIngest.Workout? {
            val totalsReply = ask(HuaweiCommand.WORKOUT_TOTALS, HuaweiTlv().put(0x81, HuaweiTlv().putShort(0x02, h.number)))
            if (!totalsReply.contains(0x81)) return null
            val totals = HuaweiWorkoutDecoder.totals(fields(totalsReply.tlv(0x81))) ?: return null
            val samples = ArrayList<HuaweiWorkoutDecoder.Sample>()
            for (page in 0 until h.dataCount) {
                val req = HuaweiTlv().putShort(0x02, h.number).putShort(0x03, page)
                if (newSteps) req.putByte(0x06, 1)
                req.put(0x07)
                val reply = ask(HuaweiCommand.WORKOUT_DATA, HuaweiTlv().put(0x81, req))
                if (!reply.contains(0x81)) continue
                val c = reply.tlv(0x81)
                val header = c.bytesOrNull(0x04) ?: continue
                val data = c.bytesOrNull(0x05) ?: continue
                val innerLength = if (c.contains(0x08)) c.unsigned(0x08).toInt() else DEFAULT_INNER_LENGTH
                val innerBitmap = if (c.contains(0x09)) c.unsigned(0x09).toInt() else DEFAULT_INNER_BITMAP
                samples.addAll(HuaweiWorkoutDecoder.samples(header, data, innerBitmap, innerLength))
            }
            val pace = ArrayList<HuaweiWorkoutDecoder.PaceBlock>()
            if (caps.supports(0x17, 0x0C)) {
                for (index in 0 until h.paceCount) {
                    val reply = ask(HuaweiCommand.WORKOUT_PACE, HuaweiTlv().put(0x81, HuaweiTlv().putShort(0x02, h.number).putShort(0x08, index)))
                    if (!reply.contains(0x81)) continue
                    val c = reply.tlv(0x81)
                    // A reply for another workout or block is skipped, as Gadgetbridge's pace sync skips it.
                    if (!c.contains(0x02) || c.u16(0x02) != h.number || !c.contains(0x08) || c.u16(0x08) != index) continue
                    pace.addAll(c.tlvs(0x83).mapNotNull { HuaweiWorkoutDecoder.paceBlock(fields(it)) })
                }
            }
            var gps = emptyList<HuaweiWorkoutDecoder.GpsPoint>()
            if (caps.settings?.gpsFileSync == true) {
                when (val r = files.download(HuaweiFileRequest("${h.number}_gps.bin", HuaweiFileRequest.TYPE_GPS), newSync = true)) {
                    is HuaweiFileOutcome.Downloaded -> gps = HuaweiWorkoutDecoder.gpsFile(r.bytes)
                    HuaweiFileOutcome.Empty -> Unit
                    is HuaweiFileOutcome.Failed -> failures.add("workout gps: ${r.reason}")
                }
            }
            return HuaweiWatchIngest.Workout(totals, samples, gps, pace)
        }

        // MARK: helpers

        /** A FitnessData count request `{81, 03 start, 04 end}` → `81{02 count}`. */
        private suspend fun countReply(command: HuaweiCommand, start: Long, end: Long): Int {
            val reply = ask(command, HuaweiTlv().put(0x81).putInt(0x03, start.toInt()).putInt(0x04, end.toInt()))
            val code = reply.resultCode()
            if (code != null && code != HuaweiTlv.RESULT_OK) throw HuaweiProtocolException("$command answered 0x%X".format(code))
            if (!reply.contains(0x81)) return 0
            val c = reply.tlv(0x81)
            return if (c.contains(0x02)) c.u16(0x02) else 0
        }

        private fun localMidnight(ts: Long): Long = ts - Math.floorMod(ts + utcOffset(ts), 86_400L)
    }

    companion object {
        /** True only when the band answered 01/37 with [bit] set. */
        private fun HuaweiCapabilities.hasExpand(bit: Int): Boolean = expand?.has(bit) == true

        /** 01/37 expand-capability bits (Gadgetbridge `HuaweiState`). */
        const val EXPAND_SKIN_TEMPERATURE = 0x1D
        const val EXPAND_NEW_SLEEP = 143
        const val EXPAND_BED_TIME = 199
        const val EXPAND_HRV = 235

        const val MAX_WORKOUT_SPLITS = 3
        const val MAX_DICTIONARY_PAGES = 64

        /** The inner sample block's size and bitmap when a 17/0A reply omits them. */
        const val DEFAULT_INNER_LENGTH = 13
        const val DEFAULT_INNER_BITMAP = 0x01FF

        const val PKG_HEALTH = "com.huawei.health"
        const val PKG_DICTIONARY = "hw.unitedevice.datadictionarysync"
        const val PKG_BAND_FILESYNC = "hw.watch.health.filesync"
        const val FP_DICTIONARY = "UniteDeviceManagement"
        const val FP_BAND = "SystemApp"
        const val P2P_PING = 1
        const val P2P_DATA = 2
        const val P2P_ACK = 3
        const val P2P_AVAILABLE = 0xCA
        const val P2P_ACK_CODE = 0xCF

        /** A container's entries as tag → value bytes (first occurrence wins). */
        fun fields(tlv: HuaweiTlv): Map<Int, ByteArray> {
            val out = LinkedHashMap<Int, ByteArray>()
            for (e in tlv.entries) if (e.tag !in out) out[e.tag] = e.value
            return out
        }

        /** Big-endian with no leading zero bytes (one byte for values below 256). */
        fun minimalBytes(v: Int): ByteArray {
            val full = HuaweiBytes.u32(v.toLong())
            val first = full.indexOfFirst { it.toInt() != 0 }.let { if (it < 0) 3 else it }
            return full.copyOfRange(first, 4)
        }

        private fun HuaweiPush.p2pSequence(): Int? = if (tlv.contains(0x02)) tlv.unsigned(0x02).toInt() else null

        private fun HuaweiPush.p2pCommand(): Int? = if (tlv.contains(0x01)) tlv.byte(0x01) else null

        /** The dictionary class a data push answers, or null when it is not a dictionary reply. */
        private fun dictionaryClass(push: HuaweiPush): Int? {
            val data = push.tlv.bytesOrNull(0x07) ?: return null
            if (data.isEmpty() || data[0].toInt() != 1) return null
            return try {
                val t = HuaweiTlv.parse(data, 1)
                if (t.contains(0x02)) t.unsigned(0x02).toInt() else null
            } catch (e: HuaweiProtocolException) {
                null
            }
        }

        /**
         * The records of one dictionary reply: `01` then TLV `{01 operation = 1, 02 class, 83[] { 84[] {05 start,
         * 06 end, 0C modified, 87[] { 88[] {09 value id, 0A | 0B value}}}}}`. Times in milliseconds.
         */
        fun dictionaryRecords(data: ByteArray, cls: Int): List<HuaweiDictionaryDecoder.Record> {
            if (data.isEmpty() || data[0].toInt() != 1) throw HuaweiProtocolException("dictionary reply without its type byte")
            val t = HuaweiTlv.parse(data, 1)
            if (t.contains(0x01) && t.unsigned(0x01) != 1L) return emptyList()
            val out = ArrayList<HuaweiDictionaryDecoder.Record>()
            for (block in t.tlvs(0x83)) for (rec in block.tlvs(0x84)) {
                fun time(tag: Int) = if (rec.contains(tag)) rec.unsigned(tag) else 0L
                val values = ArrayList<HuaweiDictionaryDecoder.Value>()
                for (group in rec.tlvs(0x87)) for (v in group.tlvs(0x88)) {
                    if (!v.contains(0x09)) continue
                    val id = v.unsigned(0x09).toInt()
                    val tag = if (v.contains(0x0A)) 0x0A else if (v.contains(0x0B)) 0x0B else continue
                    values.add(HuaweiDictionaryDecoder.Value(id, tag, v.bytes(tag)))
                }
                out.add(HuaweiDictionaryDecoder.Record(cls, time(0x05), time(0x06), time(0x0C), values))
            }
            return out
        }
    }
}
