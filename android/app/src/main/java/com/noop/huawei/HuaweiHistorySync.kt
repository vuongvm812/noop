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
import com.noop.ingest.GarminWatchIngest
import com.noop.ingest.GarminWellnessParser
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
    /**
     * Of [counts], the items past the previous watermarks: minutes at or after the steps watermark, sleep
     * records, nights and frames ending after the stored night end. Data read again only for overlap counts 0.
     */
    val newCounts: Map<String, Int> = counts,
) {
    /** Nothing new arrived, so the radio policy's empty-sync back-off may engage. */
    val empty: Boolean get() = newCounts.values.all { it == 0 }
}

/** A reply carrying a result code other than OK; [code] is the band's, unmodified. */
class BandRefusedException(val code: Long, message: String) : HuaweiProtocolException(message)

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

        val carry = session.stepRead?.carry
        val batch = HuaweiWatchIngest.Batch(
            minutes = session.minutes, sleepSpans = session.sleepSpans, nights = session.nights,
            sleepFrames = session.sleepFrames,
            stress = session.stress, dictionary = session.dictionary, workouts = session.workouts,
            utcOffsetS = utcOffset(now), nowTs = now,
            countFrom = carry?.cutoffTs, dayBase = carry?.let { mapOf(it.day to it.base) } ?: emptyMap(),
        )
        val counts = linkedMapOf(
            "minutes" to batch.minutes.size, "sleep_records" to batch.sleepSpans.size, "nights" to batch.nights.size,
            "sleep_frames" to batch.sleepFrames.size,
            "stress" to batch.stress.size, "dictionary" to batch.dictionary.size, "workouts" to batch.workouts.size,
            "pace_blocks" to batch.workouts.sumOf { it.pace.size },
        )
        val newCounts = LinkedHashMap(counts).apply { putAll(session.newCounts()) }
        progress(HuaweiSyncStage.STORING)
        val stored = try {
            store(deviceId, batch)
        } catch (e: Exception) {
            log("Huawei: storing the sync failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
        if (stored) session.commitWatermarks(batch)
        val summary = HuaweiSyncSummary(stored, counts, session.failures, newCounts)
        cursor.recordSyncOutcome(deviceId, now, empty = summary.empty)
        // Read / new per stream: "minutes=120/15" read 120 minutes, 15 of them past the last sync.
        val perStream = counts.entries.joinToString(" ") { (k, v) -> "$k=$v/${newCounts[k]}" }
        log("Huawei: sync ${if (stored) "stored" else "not stored"} (read/new) $perStream" +
            (if (summary.empty) " nothing new" else "") +
            (if (session.failures.isEmpty()) "" else " failures=${session.failures}"))
        return summary
    }

    /** Where this sync's steps read started, and the carry it folds onto (null when it read from local midnight). */
    private class StepRead(val start: Long, val carry: HuaweiStepCarry?)

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
        private var workoutDropped = false
        private var p2pSequence = 0

        /** The steps read this sync made, or null when the band has no step stream. */
        var stepRead: StepRead? = null
            private set
        /** The steps watermark before this sync: minutes from here on are new. */
        private val stepsMark: Long? = cursor.lastSync(deviceId, HuaweiDataType.STEPS)
        /** Each sleep stream's stored night end before this sync: a night ending later is new. */
        private val nightMarks = SLEEP_STREAMS.associateWith { cursor.lastNightEnd(deviceId, it) }
        /** Night ends to store with the watermarks, for streams that read cleanly. */
        private val nightEnds = LinkedHashMap<HuaweiDataType, Long>()

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

        /** Writes every watermark, after [batch] was stored. The step carry moves only with a clean steps read. */
        fun commitWatermarks(batch: HuaweiWatchIngest.Batch) {
            for ((type, ts) in done) cursor.setLastSync(deviceId, type, ts)
            for ((cls, ms) in dictionaryCursors) cursor.setDictionaryCursorMs(deviceId, cls, ms)
            workoutEnd?.let { cursor.setLastWorkoutEnd(deviceId, it) }
            for ((type, ts) in nightEnds) if (type in done) cursor.setLastNightEnd(deviceId, type, ts)
            val read = stepRead
            if (read != null && HuaweiDataType.STEPS in done) cursor.setStepCarry(deviceId, nextCarry(read, batch))
        }

        /**
         * The carry after a stored batch: the cutoff moves to an hour before now (that last hour is read again,
         * so a minute the band was still filling is replaced, not half counted) and the base gains the batch's
         * counters between the old cutoff and the new one. On a new day the base restarts from that day's minutes.
         */
        private fun nextCarry(read: StepRead, batch: HuaweiWatchIngest.Batch): HuaweiStepCarry {
            val cutoff = maxOf(now - STEP_REREAD_SECONDS, read.start)
            val day = GarminWellnessParser.localDay(cutoff, batch.utcOffsetS)
            val old = read.carry?.takeIf { it.day == day }
            val base = HuaweiWatchIngest.dayCounters(batch, day, old?.cutoffTs, cutoff, old?.base ?: HuaweiWatchIngest.DayCounters())
            return HuaweiStepCarry(day, cutoff, base)
        }

        /**
         * The carry the steps read may start from: for the watermark's own local day, with a cutoff between that
         * day's midnight and the watermark. Anything else (no carry yet, a new day) reads from local midnight.
         */
        private fun usableCarry(mark: Long): HuaweiStepCarry? {
            if (stepsMark == null) return null
            val c = cursor.stepCarry(deviceId) ?: return null
            val offset = utcOffset(now)
            if (c.day != GarminWellnessParser.localDay(mark, offset)) return null
            return c.takeIf { it.cutoffTs >= GarminWatchIngest.dayStartUnix(c.day, offset) && it.cutoffTs <= mark }
        }

        /** Per stream, how many of the decoded items are past the previous watermarks (see [HuaweiSyncSummary.newCounts]). */
        fun newCounts(): Map<String, Int> {
            fun after(type: HuaweiDataType, end: Long?) = end != null && end > (nightMarks[type] ?: Long.MIN_VALUE)
            return linkedMapOf(
                "minutes" to minutes.count { stepsMark == null || it.ts >= stepsMark },
                "sleep_records" to sleepSpans.count { after(HuaweiDataType.SLEEP, it.endTs) },
                "nights" to nights.count { after(HuaweiDataType.SLEEP_FILE, HuaweiWatchIngest.nightEnd(it)) },
                "sleep_frames" to sleepFrames.count { after(HuaweiDataType.SLEEP_STATE, it.endTs) },
            )
        }

        /** A sleep stream's read start: its last finished night's end, else a day before its watermark. */
        private fun sleepStart(type: HuaweiDataType): Long = nightMarks[type] ?: (since(type) - 86_400)

        /**
         * Records the latest of [ends] that finished at least [SLEEP_FINISHED_AFTER_SECONDS] ago as [type]'s night
         * end, never moving it back. A night still in progress stays past the stored end and is read whole again.
         */
        private fun noteNightEnds(type: HuaweiDataType, ends: List<Long>) {
            val finished = ends.filter { it >= HuaweiWatchIngest.EARLIEST_TS && it <= now - SLEEP_FINISHED_AFTER_SECONDS }.maxOrNull()
            val latest = listOfNotNull(finished, nightMarks[type]).maxOrNull() ?: return
            nightEnds[type] = latest
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
                val reply = ask(command, tlv)
                val code = refusal(command, reply, "switch $command")
                if (code != null) failures.add("switch $command: band answered 0x%X".format(code))
            }
            // A switch that failed is tried again next sync; the band keeps the ones that took.
            if (failures.size == before) cursor.setSwitchesEnabled(deviceId)
        }

        // MARK: per-minute steps / HR / SpO2 / resting HR

        /**
         * 07/0A count over [start, now] then 07/0B per index. The range starts at the step carry's cutoff when
         * the carry is for the watermark's day (the batch then folds onto the carried base), else at the local
         * midnight of the watermark; either way every day the batch touches gets complete totals.
         */
        suspend fun syncSteps() {
            if (!caps.supports(0x07, 0x0A) || !caps.supports(0x07, 0x0B)) return
            stream("steps") {
                val mark = since(HuaweiDataType.STEPS)
                val carry = usableCarry(mark)
                val start = carry?.cutoffTs ?: localMidnight(mark)
                stepRead = StepRead(start, carry)
                val count = countReply(HuaweiCommand.STEP_DATA_COUNT, start, now)
                var skipped = 0
                for (i in 0 until count) {
                    // One refused or malformed index is skipped and reported; the rest of the stream still reads.
                    val what = "STEP_DATA day $i"
                    try {
                        val reply = askChecked(HuaweiCommand.STEP_DATA, HuaweiTlv().put(0x81, HuaweiTlv().putShort(0x02, i)), what)
                        if (!reply.contains(0x81)) throw HuaweiProtocolException("$what returned no data")
                        val c = reply.tlv(0x81)
                        val base = c.u32(0x03)
                        val decoded = ArrayList<HuaweiFitnessDecoder.MinuteSample>()
                        for (rec in c.tlvs(0x84)) {
                            val offset = rec.byte(0x05)
                            HuaweiFitnessDecoder.minuteSample(base, offset, rec.bytes(0x06))?.let { decoded.add(it) }
                        }
                        minutes.addAll(decoded)
                    } catch (e: HuaweiProtocolException) {
                        skipped++
                        failures.add("steps: ${e.message}")
                        if (e !is BandRefusedException) log("Huawei: $what unreadable: ${e.message}")
                    }
                }
                // The watermark moves only when every day read, so a skipped day is asked for again next sync.
                if (skipped == 0) done[HuaweiDataType.STEPS] = now
            }
        }

        // MARK: sleep

        private fun usesSleepFile(): Boolean = caps.supports(0x07, 0x16) && caps.hasExpand(EXPAND_NEW_SLEEP)

        /** Legacy light / deep records, 07/0C count then 07/0D per index, from the last finished night's end. */
        suspend fun syncLegacySleep() {
            if (!caps.supports(0x07, 0x0C) || !caps.supports(0x07, 0x0D)) return
            // A band with the TruSleep sequence file reads its sleep there; it refuses these (Band 10: 100004).
            if (usesSleepFile()) return
            stream("sleep") {
                val start = sleepStart(HuaweiDataType.SLEEP)
                try {
                    val count = countReply(HuaweiCommand.SLEEP_DATA_COUNT, start, now)
                    for (i in 0 until count) {
                        val c = askChecked(HuaweiCommand.SLEEP_DATA, HuaweiTlv().put(0x81, HuaweiTlv().putShort(0x02, i)),
                            "SLEEP_DATA record $i").tlv(0x81)
                        for (rec in c.tlvs(0x83)) {
                            HuaweiFitnessDecoder.sleepSpan(rec.byte(0x04), rec.bytes(0x05))?.let { sleepSpans.add(it) }
                        }
                    }
                } catch (e: BandRefusedException) {
                    // "Not supported" is the band's answer, not a fault of this sync: logged, no failure entry.
                    if (e.code != RESULT_NOT_SUPPORTED) throw e
                    return@stream
                }
                noteNightEnds(HuaweiDataType.SLEEP, sleepSpans.map { it.endTs })
                done[HuaweiDataType.SLEEP] = now
            }
        }

        /**
         * The TruSleep `sequence_data` file (dictionary 700013, always over 0x2C) on a band with expand bit
         * 143 and TruSleep (07/16). Bands without bit 143 keep the legacy records above: the older
         * `sleep_state.bin` / `sleep_data.bin` pair carries no stage the fold can place.
         */
        suspend fun syncSleepFile() {
            if (!usesSleepFile()) return
            stream("sleep_file") {
                val start = sleepStart(HuaweiDataType.SLEEP_FILE)
                val request = HuaweiFileRequest("sequence_data", HuaweiFileRequest.TYPE_SEQUENCE_DATA, start, now,
                    dictId = HuaweiFileRequest.DICT_SLEEP)
                when (val r = files.download(request, newSync = true)) {
                    is HuaweiFileOutcome.Downloaded -> nights.addAll(HuaweiSleepFileDecoder.sequenceFile(r.bytes))
                    HuaweiFileOutcome.Empty -> Unit
                    is HuaweiFileOutcome.Failed -> { failures.add("sleep_file: ${r.reason}"); return@stream }
                }
                noteNightEnds(HuaweiDataType.SLEEP_FILE, nights.mapNotNull { HuaweiWatchIngest.nightEnd(it) })
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
                val start = sleepStart(HuaweiDataType.SLEEP_STATE)
                val request = HuaweiFileRequest("sleep_state.bin", HuaweiFileRequest.TYPE_SLEEP_STATE, start, now,
                    oldSyncType = HuaweiFileRequest.OLD_SLEEP)
                when (val r = files.download(request, newSync = caps.settings?.truSleepFileSync == true)) {
                    is HuaweiFileOutcome.Downloaded -> sleepFrames.addAll(HuaweiSleepFileDecoder.stateFile(r.bytes))
                    HuaweiFileOutcome.Empty -> Unit
                    is HuaweiFileOutcome.Failed -> { failures.add("sleep_state: ${r.reason}"); return@stream }
                }
                noteNightEnds(HuaweiDataType.SLEEP_STATE, sleepFrames.filter { it.endTs > it.startTs }.map { it.endTs })
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
            // A band that did not serve the service is not asked again for a day (Gadgetbridge treats it as absent).
            if ((cursor.dictUnavailableUntil(deviceId) ?: 0L) > now) return
            stream("dictionary") {
                val available = try {
                    pingDictionary()
                } catch (e: TimeoutCancellationException) {
                    false
                }
                if (!available) {
                    cursor.setDictUnavailableUntil(deviceId, now + DICTIONARY_RETRY_SECONDS)
                    log("Huawei: the band's data dictionary service is unavailable; not asked again for 24 h")
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
                val walk = HeaderWalk()
                workoutHeaders(start, now, 0, walk)
                val previousEnd = cursor.lastWorkoutEnd(deviceId)
                var latestEnd = previousEnd
                for (h in walk.headers.sortedBy { it.number }) {
                    val w = workout(h, newSteps)
                    if (w == null) continue
                    workouts.add(w)
                    latestEnd = maxOf(latestEnd ?: 0L, w.totals.endTs)
                }
                // The watermark never passes a slice the band's list failed for: its workouts are asked for again.
                val skipped = walk.skippedStart
                if (skipped != null && latestEnd != null) latestEnd = maxOf(previousEnd ?: Long.MIN_VALUE, minOf(latestEnd, skipped - 1))
                if (workoutDropped) latestEnd = previousEnd
                workoutEnd = latestEnd
                if (skipped == null && !workoutDropped) done[HuaweiDataType.WORKOUTS] = now
            }
        }

        private inner class WorkoutHeader(val number: Int, val dataCount: Int, val paceCount: Int)

        /** The headers of the slices that listed cleanly, and the earliest start of a slice that did not. */
        private inner class HeaderWalk {
            val headers = ArrayList<WorkoutHeader>()
            var skippedStart: Long? = null
        }

        /**
         * 17/07 over [start, end]. A band error, or a count that disagrees with its own list, splits the range
         * in two, up to [MAX_WORKOUT_SPLITS] deep; past that only that slice is skipped and reported, and the
         * remaining slices are still read.
         */
        private suspend fun workoutHeaders(start: Long, end: Long, depth: Int, walk: HeaderWalk) {
            val reply = ask(HuaweiCommand.WORKOUT_COUNT,
                HuaweiTlv().put(0x81, HuaweiTlv().putInt(0x03, start.toInt()).putInt(0x04, end.toInt())))
            val refused = refusal(HuaweiCommand.WORKOUT_COUNT, reply, "WORKOUT_COUNT $start-$end")
            val why: String
            if (refused != null) {
                why = "band answered 0x%X".format(refused)
            } else if (!reply.contains(0x81)) {
                why = "reply without a list"
            } else {
                val c = reply.tlv(0x81)
                val count = if (c.contains(0x02)) c.u16(0x02) else 0
                val items = c.tlvs(0x85).map {
                    WorkoutHeader(it.u16(0x06), if (it.contains(0x07)) it.u16(0x07) else 0, if (it.contains(0x08)) it.u16(0x08) else 0)
                }
                if (items.size == count) {
                    walk.headers.addAll(items)
                    return
                }
                why = "count=$count list=${items.size}"
            }
            if (depth >= MAX_WORKOUT_SPLITS || end - start < 2) {
                failures.add("workouts: list for $start-$end skipped ($why)")
                walk.skippedStart = minOf(walk.skippedStart ?: start, start)
                return
            }
            val mid = start + (end - start) / 2
            workoutHeaders(start, mid, depth + 1, walk)
            workoutHeaders(mid + 1, end, depth + 1, walk)
        }

        /** Totals (17/08) and every sample page (17/0A); a GPS track (0x2C) when 01/31 has the GPS bit. */
        private suspend fun workout(h: WorkoutHeader, newSteps: Boolean): HuaweiWatchIngest.Workout? {
            val totalsReply = ask(HuaweiCommand.WORKOUT_TOTALS, HuaweiTlv().put(0x81, HuaweiTlv().putShort(0x02, h.number)))
            refusal(HuaweiCommand.WORKOUT_TOTALS, totalsReply, "WORKOUT_TOTALS workout ${h.number}")?.let {
                failures.add("workouts: WORKOUT_TOTALS workout ${h.number} answered 0x%X".format(it))
                workoutDropped = true
                return null
            }
            if (!totalsReply.contains(0x81)) return null
            val totals = HuaweiWorkoutDecoder.totals(fields(totalsReply.tlv(0x81))) ?: return null
            val samples = ArrayList<HuaweiWorkoutDecoder.Sample>()
            for (page in 0 until h.dataCount) {
                val req = HuaweiTlv().putShort(0x02, h.number).putShort(0x03, page)
                if (newSteps) req.putByte(0x06, 1)
                req.put(0x07)
                val reply = ask(HuaweiCommand.WORKOUT_DATA, HuaweiTlv().put(0x81, req))
                val refused = refusal(HuaweiCommand.WORKOUT_DATA, reply, "WORKOUT_DATA workout ${h.number} page $page")
                if (refused != null) {
                    failures.add("workouts: WORKOUT_DATA workout ${h.number} page $page answered 0x%X".format(refused))
                    workoutDropped = true
                    continue
                }
                if (!reply.contains(0x81)) {
                    failures.add("workouts: WORKOUT_DATA workout ${h.number} page $page returned no data")
                    workoutDropped = true
                    continue
                }
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
                    val refused = refusal(HuaweiCommand.WORKOUT_PACE, reply, "WORKOUT_PACE workout ${h.number} block $index")
                    if (refused != null) {
                        failures.add("workouts: WORKOUT_PACE workout ${h.number} block $index answered 0x%X".format(refused))
                        workoutDropped = true
                        continue
                    }
                    if (!reply.contains(0x81)) {
                        failures.add("workouts: WORKOUT_PACE workout ${h.number} block $index returned no data")
                        workoutDropped = true
                        continue
                    }
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

        /**
         * The band's refusal code when [reply] carries a result (tag 0x7F) other than OK, else null. Every refusal
         * is logged: service/command id, command and code, as the band sent them and nothing inferred.
         */
        private fun refusal(command: HuaweiCommand, reply: HuaweiTlv, what: String): Long? {
            val code = reply.resultCode()
            if (code == null || code == HuaweiTlv.RESULT_OK) return null
            log("Huawei: %02X/%02X %s refused, %s answered 0x%X".format(command.serviceId, command.commandId, command, what, code))
            return code
        }

        /** [ask] that throws [BandRefusedException] naming [what] and the code when the band refused. */
        private suspend fun askChecked(command: HuaweiCommand, tlv: HuaweiTlv, what: String = command.name): HuaweiTlv {
            val reply = ask(command, tlv)
            refusal(command, reply, what)?.let { throw BandRefusedException(it, "$what answered 0x%X".format(it)) }
            return reply
        }

        /** A FitnessData count request `{81, 03 start, 04 end}` → `81{02 count}`. */
        private suspend fun countReply(command: HuaweiCommand, start: Long, end: Long): Int {
            val reply = askChecked(command, HuaweiTlv().put(0x81).putInt(0x03, start.toInt()).putInt(0x04, end.toInt()))
            if (!reply.contains(0x81)) throw HuaweiProtocolException("${command.name} returned no count")
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

        /** How far before now the step carry's cutoff sits: the last hour is read again each sync. */
        const val STEP_REREAD_SECONDS = 3_600L

        /**
         * A night that ended at least this long before the sync counts as finished, and the next sleep read starts
         * at its end. Three times the fold's one-hour join gap, so a record the band writes later is not taken
         * as part of it. An estimate, not a band fact; a device log can tighten it.
         */
        const val SLEEP_FINISHED_AFTER_SECONDS = 3 * HuaweiWatchIngest.SLEEP_SPAN_JOIN_GAP_S

        /** The sleep streams that keep a night end. */
        private val SLEEP_STREAMS = listOf(HuaweiDataType.SLEEP, HuaweiDataType.SLEEP_FILE, HuaweiDataType.SLEEP_STATE)

        const val MAX_WORKOUT_SPLITS = 3
        const val MAX_DICTIONARY_PAGES = 64

        /** How long a band that did not serve the P2P dictionary is left alone. */
        const val DICTIONARY_RETRY_SECONDS = 86_400L

        /** The band's "not supported" result (100004), which a Band 10 gives the legacy sleep records. */
        const val RESULT_NOT_SUPPORTED = 0x000186A4L

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
