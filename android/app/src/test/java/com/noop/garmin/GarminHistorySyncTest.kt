package com.noop.garmin

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * History sync against the scripted fake watch, both flows, on the Kotlin twin. [SYNC_ORACLE] is the verbatim
 * stdout of the Swift standalone oracle in sync mode (the build line is in GarminSyncOracleCases.swift);
 * GarminSyncOracleCases.kt re-runs the same traces here, and the Swift GarminHistorySyncTests reads this
 * literal back. Regenerate, never hand-edit. The other tests are the Kotlin twins of GarminHistorySyncTests.swift.
 */
class GarminHistorySyncTest {

    private class Run(val files: List<GarminDownloadedFile>, val summaries: List<GarminSyncSummary>, val session: GarminSession)

    private fun run(watch: OracleFakeWatch, steps: List<OracleSyncStep>, session: GarminSession? = null): Run {
        val files = ArrayList<GarminDownloadedFile>()
        val summaries = ArrayList<GarminSyncSummary>()
        val s = session ?: oracleNewSyncSession()
        var now = 0L
        var linkUp = true
        val inbox = ArrayDeque<ByteArray>()
        fun clock() = GarminClock(now, 1_793_546_986L, 0)
        fun absorb(outputs: List<GarminSessionOutput>) {
            for (o in outputs) when (o) {
                is GarminSessionOutput.Write -> if (linkUp) inbox.addAll(watch.receive(o.bytes))
                is GarminSessionOutput.SyncFile -> files.add(o.file)
                is GarminSessionOutput.SyncFinished -> summaries.add(o.summary)
                else -> Unit
            }
        }
        for (step in steps) {
            when (step) {
                is OracleSyncStep.Connect -> absorb(s.start(clock()))
                is OracleSyncStep.Sync -> absorb(s.startSync(step.request, clock()))
                is OracleSyncStep.WatchSends -> { now += 1; absorb(s.receive(step.bytes, clock())) }
                is OracleSyncStep.Tick -> { now += step.ms; absorb(s.tick(clock())) }
                is OracleSyncStep.DropLink -> { linkUp = false; inbox.clear() }
            }
            var guardCount = 0
            while (linkUp && inbox.isNotEmpty() && guardCount < 10_000) {
                guardCount += 1
                now += 1
                absorb(s.receive(inbox.removeFirst(), clock()))
            }
        }
        return Run(files, summaries, s)
    }

    private val synced = setOf(GarminSyncFileKey(oracleEntry(3, 44, 3, 40, ORACLE_RECENT_TS + 120)))
    private val connect = OracleSyncStep.Connect
    private fun sync(r: GarminSyncRequest) = OracleSyncStep.Sync(r)

    @Test
    fun legacyWatchInitiatedSyncDownloadsOnlyNewWantedFiles() {
        val watch = oracleStandardWatch()
        watch.corruptChunks = hashSetOf(2)
        val r = run(watch, listOf(connect, sync(GarminSyncRequest(synced = synced, watchInitiated = true, notBeforeUnix = ORACLE_NOT_BEFORE))))
        assertEquals(listOf(1, 2), r.files.map { it.key.fileIndex })
        assertArrayEquals(oracleFileBody(1, 150), r.files[0].bytes)
        assertArrayEquals(oracleFileBody(2, 70), r.files[1].bytes)
        assertEquals(listOf(GarminFitFileType.MONITOR, GarminFitFileType.SLEEP), r.files.map { it.fitType })
        val summary = r.summaries.last()
        assertEquals(GarminSyncSummary.Outcome.Completed, summary.outcome)
        assertEquals(7, summary.listed)
        assertEquals(5, summary.wanted)
        assertEquals(2, summary.alreadySynced)
        assertEquals(1, summary.skippedOld)
        assertEquals(2, summary.downloaded)
        assertTrue(watch.seen.contains("rx filter 3"))
        assertTrue(watch.seen.contains("rx chunk reply status=3 next=0"))
        assertTrue(watch.seen.contains("rx system event 0"))
    }

    @Test
    fun onWatchListsEveryWantedEntrySortedInBothFlows() {
        val legacy = run(oracleStandardWatch(), listOf(connect, sync(GarminSyncRequest(synced = synced, notBeforeUnix = ORACLE_NOT_BEFORE))))
        val s = legacy.summaries.last()
        assertEquals(s.wanted, s.onWatch.size)
        assertEquals(s.onWatch.sorted(), s.onWatch)
        assertTrue(synced.all { it in s.onWatch })
        assertTrue(legacy.files.all { it.key in s.onWatch })
        assertFalse(s.line.contains("onWatch"))

        val watch = oracleStandardWatch()
        val item = GarminItemReference(uid = GarminUid(0x4567_0000_0000_0001L, 0xA1),
            dataType = GarminItemDataType(format = 3, typeName = "FIT_TYPE_32", typeNameKey = 32), dataSize = 416)
        val app = GarminItemReference(uid = GarminUid(1, 0xA3), dataType = GarminItemDataType(typeName = "IQWatchApps"), dataSize = 5_000)
        watch.itemPages = listOf(listOf(app), listOf(item))
        watch.pullHandles = hashMapOf(0xA1L to 7)
        watch.pipeFiles = hashMapOf(7 to oracleZlibStream())
        val fa = run(watch, listOf(connect, sync(GarminSyncRequest(flow = GarminSyncFlow.FileAccess(legacyFlush = false))))).summaries.last()
        assertEquals(1, fa.wanted)
        assertEquals(listOf(0x4567_0000L), fa.onWatch.map { it.garminTimestamp })
    }

    @Test
    fun phoneStartedSyncSkipsTheFilter() {
        val watch = oracleStandardWatch()
        run(watch, listOf(connect, sync(GarminSyncRequest(synced = synced, notBeforeUnix = ORACLE_NOT_BEFORE))))
        assertFalse(watch.seen.any { it.startsWith("rx filter") })
    }

    @Test
    fun resyncIsIdempotent() {
        val watch = oracleStandardWatch()
        val first = run(watch, listOf(connect, sync(GarminSyncRequest(synced = synced, notBeforeUnix = ORACLE_NOT_BEFORE))))
        val ledger = synced + first.files.map { it.key }
        val second = run(watch, listOf(sync(GarminSyncRequest(synced = ledger, notBeforeUnix = ORACLE_NOT_BEFORE))), first.session)
        assertTrue(second.files.isEmpty())
        assertEquals(true, second.summaries.last().isEmpty)
        assertEquals(GarminSyncSummary.Outcome.Completed, second.summaries.last().outcome)
    }

    @Test
    fun interruptedTransferResumesWithContinueWithoutCorruptionOrDuplicates() {
        val watch = oracleStandardWatch()
        watch.cutAfterChunks = 4
        val cut = run(watch, listOf(connect, sync(GarminSyncRequest(synced = synced, notBeforeUnix = ORACLE_NOT_BEFORE)), OracleSyncStep.DropLink))
        assertTrue(cut.files.isEmpty())
        val held = cut.session.syncResumePoint!!
        assertEquals(128L, held.offset)
        assertArrayEquals(oracleFileBody(1, 150).copyOfRange(0, 128), held.data)
        watch.cutAfterChunks = null
        val resumed = run(watch, listOf(connect, sync(GarminSyncRequest(synced = synced, resume = held, notBeforeUnix = ORACLE_NOT_BEFORE))))
        assertEquals(listOf(1, 2), resumed.files.map { it.key.fileIndex })
        assertArrayEquals(oracleFileBody(1, 150), resumed.files[0].bytes)
        assertEquals(128L, resumed.files[0].resumedFrom)
        assertTrue(watch.seen.any { it.startsWith("rx download idx=1 offset=128 kind=0") && it.contains("size=22") })
    }

    @Test
    fun aWatchThatRestartsOnContinueStillYieldsTheExactFile() {
        val watch = oracleStandardWatch()
        watch.restartOnContinue = true
        val partialData = oracleFileBody(1, 150).copyOfRange(0, 64)
        val partial = GarminSyncResume(GarminSyncFileKey(watch.directory[0]), partialData, GarminCrc.crc16(partialData))
        val r = run(watch, listOf(connect, sync(GarminSyncRequest(synced = synced, resume = partial, notBeforeUnix = ORACLE_NOT_BEFORE))))
        assertEquals(1, r.files.count { it.key.fileIndex == 1 })
        assertArrayEquals(oracleFileBody(1, 150), r.files.first { it.key.fileIndex == 1 }.bytes)
        assertEquals(0, r.summaries.last().failed)
    }

    @Test
    fun refusedContinueRestartsFromZeroOnce() {
        val watch = oracleStandardWatch()
        watch.refuseContinue = true
        val d = bytes(1, 2, 3)
        val partial = GarminSyncResume(GarminSyncFileKey(watch.directory[1]), d, GarminCrc.crc16(d))
        val r = run(watch, listOf(connect, sync(GarminSyncRequest(synced = synced, resume = partial, notBeforeUnix = ORACLE_NOT_BEFORE))))
        assertEquals(2, r.files.first().key.fileIndex)
        assertArrayEquals(oracleFileBody(2, 70), r.files.first().bytes)
    }

    @Test
    fun compressedTransferInflatesAndChecksEveryCheckpoint() {
        val watch = OracleFakeWatch()
        watch.directory = listOf(oracleEntry(8, 32, 8, 416, ORACLE_RECENT_TS))
        watch.files = hashMapOf(8 to oracleZlibPlain())
        watch.compressedFiles = hashMapOf(8 to oracleZlibStream())
        val r = run(watch, listOf(connect, sync(GarminSyncRequest(requestCompression = true))))
        assertEquals(1, r.files.size)
        assertArrayEquals(oracleZlibPlain(), r.files[0].bytes)
        assertTrue(watch.seen.any { it.contains("idx=8") && it.contains("flags=1") })
        val bad = OracleFakeWatch()
        bad.directory = watch.directory
        val plain = oracleZlibPlain()
        plain[10] = (plain[10].toInt() xor 0xFF).toByte()
        bad.files = hashMapOf(8 to plain)
        bad.compressedFiles = hashMapOf(8 to oracleZlibStream())
        val rb = run(bad, listOf(connect, sync(GarminSyncRequest(requestCompression = true))))
        assertTrue(rb.files.isEmpty())
        assertEquals(1, rb.summaries.last().failed)
    }

    @Test
    fun fileAccessFlowListsPagesPullsAndInflates() {
        val watch = oracleStandardWatch()
        val item = GarminItemReference(uid = GarminUid(0x4567_0000_0000_0001L, 0xA1),
            dataType = GarminItemDataType(format = 3, typeName = "FIT_TYPE_32", typeNameKey = 32), dataSize = 416)
        val app = GarminItemReference(uid = GarminUid(1, 0xA3), dataType = GarminItemDataType(typeName = "IQWatchApps"), dataSize = 5_000)
        watch.itemPages = listOf(listOf(app), listOf(item))
        watch.pullHandles = hashMapOf(0xA1L to 7)
        watch.pipeFiles = hashMapOf(7 to oracleZlibStream())
        val r = run(watch, listOf(connect, sync(GarminSyncRequest(flow = GarminSyncFlow.FileAccess(legacyFlush = false)))))
        assertEquals(1, r.files.size)
        assertArrayEquals(oracleZlibPlain(), r.files[0].bytes)
        assertEquals(GarminFitFileType.MONITOR, r.files[0].fitType)
        assertEquals(0x4567_0000L, r.files[0].key.garminTimestamp)
        assertEquals(2, watch.seen.count { it.startsWith("rx item list") })
        assertFalse(watch.seen.any { it.startsWith("rx download") })
        assertTrue(watch.seen.contains("ml register service=8216"))
    }

    @Test
    fun legacyFlushPrecedesTheItemList() {
        val watch = oracleStandardWatch()
        watch.itemPages = listOf(emptyList())
        run(watch, listOf(connect, sync(GarminSyncRequest(flow = GarminSyncFlow.FileAccess(legacyFlush = true)))))
        val download = watch.seen.indexOfFirst { it.startsWith("rx download idx=0") }
        val list = watch.seen.indexOfFirst { it.startsWith("rx item list") }
        assertTrue(download >= 0)
        assertTrue(download < list)
    }

    @Test
    fun stallTimesOutAndASecondStartWhileRunningIsIgnored() {
        val watch = oracleStandardWatch()
        watch.silent = true
        val r = run(watch, listOf(connect, sync(GarminSyncRequest())))
        val s = r.session
        assertTrue(s.isSyncing)
        val deadline = s.nextDeadlineMs
        val again = s.startSync(GarminSyncRequest(), GarminClock(100, 0, 0))
        assertFalse(again.any { it is GarminSessionOutput.Write })
        assertEquals(deadline, s.nextDeadlineMs)
        val timedOut = s.tick(GarminClock(s.nextDeadlineMs!!, 0, 0))
        assertTrue(timedOut.any { it is GarminSessionOutput.SyncFinished && it.summary.outcome is GarminSyncSummary.Outcome.Failed })
        assertFalse(s.isSyncing)
    }

    @Test
    fun syncBeforeTheHandshakeIsRefused() {
        val s = oracleNewSyncSession()
        val out = s.startSync(GarminSyncRequest(), GarminClock(0, 0, 0))
        assertFalse(s.isSyncing)
        assertFalse(out.any { it is GarminSessionOutput.Write })
    }

    /** READ-ONLY: across every trace the fake received only allowlisted types, never a file mutation. */
    @Test
    fun everySyncTraceIsReadOnly() {
        val allowed = setOf(5000, 5002, 5007, 5030, 5031, 5043, 5050)
        val forbidden = setOf(5003, 5005, 5006, 5008) + (5102..5112)
        val lines = garminSyncOracleLines()
        val typeLines = lines.filter { it.startsWith("watch types") }
        assertTrue(typeLines.size > 10)
        for (line in typeLines) {
            for (t in line.removePrefix("watch types ").split(",").filter { it.isNotEmpty() }.map { it.toInt() }) {
                assertTrue(line, t in allowed)
                assertFalse(line, t in forbidden)
            }
        }
        assertFalse(lines.any { it.contains("rx file access field 13") || it.contains("rx file access field 15") })
    }

    @Test
    fun inflateMatchesZlibVectorsJavaInflaterAndRejectsCorruptStreams() {
        assertArrayEquals(oracleZlibPlain(), GarminInflate.zlib(oracleZlibStream()))
        assertArrayEquals("NOOP stored block".toByteArray(), GarminInflate.zlib(oracleHexBytes("7801011100eeff4e4f4f502073746f72656420626c6f636b34440619")))
        assertArrayEquals("abcabcabcabc NOOP".toByteArray(), GarminInflate.zlib(oracleHexBytes("78014b4c4a4e8421053f7fff0000389005f5")))
        assertNull(GarminInflate.zlib(oracleZlibStream().let { it.copyOfRange(0, it.size - 1) }))
        assertNull(GarminInflate.zlib(oracleZlibStream(), maxOutput = 100))
        val prefix = GarminInflate.zlibPrefix(oracleZlibStream().copyOfRange(0, 150))
        assertTrue(prefix.isNotEmpty())
        assertArrayEquals(oracleZlibPlain().copyOfRange(0, prefix.size), prefix)
        // The hand-written inflater agrees with the platform's on every valid vector.
        for (hex in listOf(
            "7801011100eeff4e4f4f502073746f72656420626c6f636b34440619",
            "78014b4c4a4e8421053f7fff0000389005f5",
            "78dacbc9cc4b553050c84f5328c94855484f2ccacdcc53c8cdcfcb2cc92f5248cbcc49e5ca01a93024a8c288a00a63822a4c08aa3025a8c28ca08a51df8efa76d4b7a3bea5896f01ecb1db33",
        ).map { oracleHexBytes(it) } + listOf(oracleZlibStream())) {
            val inflater = java.util.zip.Inflater()
            inflater.setInput(hex)
            val buf = ByteArray(1 shl 16)
            val n = inflater.inflate(buf)
            assertTrue(inflater.finished())
            inflater.end()
            assertArrayEquals(buf.copyOf(n), GarminInflate.zlib(hex))
        }
    }

    @Test
    fun fileAccessNamesAndModelPolicy() {
        assertEquals(49, GarminSyncFileKey.fitSubType("FIT_TYPE_49"))
        assertNull(GarminSyncFileKey.fitSubType("FIT_TYPE_256"))
        assertNull(GarminSyncFileKey.fitSubType("BACKUP_PRIMARY"))
        assertEquals(GarminSyncFlow.FileAccess(legacyFlush = true), GarminSyncModelPolicy.flow(4432))
        assertEquals(GarminSyncFlow.Legacy, GarminSyncModelPolicy.flow(1))
        assertNotNull(GarminSyncFileKey.fileAccess(ORACLE_SYNC_MONITOR))
    }

    @Test
    fun kotlinMatchesTheSwiftSyncOracle() {
        val expected = SYNC_ORACLE.removePrefix("\n").removeSuffix("\n").split("\n")
        val actual = garminSyncOracleLines()
        for (i in 0 until minOf(expected.size, actual.size)) {
            assertEquals("sync oracle line ${i + 1}", expected[i], actual[i])
        }
        assertEquals("sync oracle line count", expected.size, actual.size)
    }

    private companion object {
        // ORACLE-BEGIN (Swift stdout, verbatim)
        val SYNC_ORACLE = """
trace legacy-watch-initiated
@0 log session start transport=V2 multi-link mtu=150 firstPairing=false
@0 phase closing-all
@0 w ml 00050200000000000000000000
@1 phase registering
@1 w ml 00000200000000000000010000
@2 log GFDI registered handle=0x65 reliable=false status=0
@2 phase awaiting-device-information
@3 log watch Forerunner 165 model=165 product=4432 fw=29.05 protocol=151 maxPacket=512
@3 w 5000 a01300
@3 w 5000 a013009600ffffffffffff0100ffff044e4f4f50044e4f4f50066f7261636c6501
@3 phase handshaking
@4 log watch capabilities: 10 bits; NOOP advertises 0,3,4,5,55,71,76,90
@4 w 5000 ba1300
@4 w 5050 113900000000008000801000040000000000
@4 w 5030 0800
@4 phase ready
@4 log handshake complete in 4 ms
@7 w 5000 ad1300
@7 announced synchronization type=1 mask=0x4000028 filter=true
@7 log sync start flow=legacy synced=1 resume=none compression=false watchInitiated=true
@7 transfer true
@7 w 5007 03
@8 w 5002 0000000000000100000000000000
@10 w 5000 8c13000040000000
@11 w 5000 8c13000070000000
@11 log directory: 7 entries [128/3x1 128/32x2 128/44x1 128/49x1 128/68x1 8/255x1]; 2 to download
@11 w 5002 0100000000000100000000000000
@13 log chunk crc 3811 != 61724 at offset 0; asking again
@13 w 5000 8c13000300000000
@14 w 5000 8c13000040000000
@15 w 5000 8c13000080000000
@16 w 5000 8c13000096000000
@16 log downloaded MONITOR 128/32 #1 idx=1 size=150 ts=1161900000 (150 bytes)
@16 file 128/32 #1 idx=1 size=150 ts=1161900000 n=150 fnv=e1692bc2 resumed=- type=- fit=MONITOR
@16 w 5002 0200000000000100000000000000
@18 w 5000 8c13000040000000
@19 w 5000 8c13000046000000
@19 log downloaded SLEEP 128/49 #2 idx=2 size=70 ts=1161900060 (70 bytes)
@19 file 128/49 #2 idx=2 size=70 ts=1161900060 n=70 fnv=d2a3fcaf resumed=- type=- fit=SLEEP
@19 w 5030 0000
@19 log sync completed: listed=7 wanted=5 synced=2 old=1 large=0 downloaded=2 failed=0 resumed=0 bytes=332
@19 finished sync completed: listed=7 wanted=5 synced=2 old=1 large=0 downloaded=2 failed=0 resumed=0 bytes=332 empty=false
@19 transfer false
watch saw ml close-all | ml register gfdi | rx configuration | rx system event 8 | rx filter 3 | rx download idx=0 offset=0 kind=1 seed=0 size=0 flags=0 | rx chunk reply status=0 next=64 | rx chunk reply status=0 next=112 | rx download idx=1 offset=0 kind=1 seed=0 size=0 flags=0 | rx chunk reply status=3 next=0 | rx chunk reply status=0 next=64 | rx chunk reply status=0 next=128 | rx chunk reply status=0 next=150 | rx download idx=2 offset=0 kind=1 seed=0 size=0 flags=0 | rx chunk reply status=0 next=64 | rx chunk reply status=0 next=70 | rx system event 0
watch types 5000,5002,5007,5030,5050
end syncing=false resume=- deadline=-
trace legacy-cut
@0 log session start transport=V2 multi-link mtu=150 firstPairing=false
@0 phase closing-all
@0 w ml 00050200000000000000000000
@1 phase registering
@1 w ml 00000200000000000000010000
@2 log GFDI registered handle=0x65 reliable=false status=0
@2 phase awaiting-device-information
@3 log watch Forerunner 165 model=165 product=4432 fw=29.05 protocol=151 maxPacket=512
@3 w 5000 a01300
@3 w 5000 a013009600ffffffffffff0100ffff044e4f4f50044e4f4f50066f7261636c6501
@3 phase handshaking
@4 log watch capabilities: 10 bits; NOOP advertises 0,3,4,5,55,71,76,90
@4 w 5000 ba1300
@4 w 5050 113900000000008000801000040000000000
@4 w 5030 0800
@4 phase ready
@4 log handshake complete in 4 ms
@6 log sync start flow=legacy synced=1 resume=none compression=false watchInitiated=false
@6 transfer true
@6 w 5002 0000000000000100000000000000
@8 w 5000 8c13000040000000
@9 w 5000 8c13000070000000
@9 log directory: 7 entries [128/3x1 128/32x2 128/44x1 128/49x1 128/68x1 8/255x1]; 2 to download
@9 w 5002 0100000000000100000000000000
@11 w 5000 8c13000040000000
@12 w 5000 8c13000080000000
watch saw ml close-all | ml register gfdi | rx configuration | rx system event 8 | rx download idx=0 offset=0 kind=1 seed=0 size=0 flags=0 | rx chunk reply status=0 next=64 | rx chunk reply status=0 next=112 | rx download idx=1 offset=0 kind=1 seed=0 size=0 flags=0 | rx chunk reply status=0 next=64 | rx chunk reply status=0 next=128 | link cut at offset 128
watch types 5000,5002,5030,5050
end syncing=true resume=128/32 #1 idx=1 size=150 ts=1161900000@128 crc=62518 deadline=20012
trace legacy-resume
@0 log session start transport=V2 multi-link mtu=150 firstPairing=false
@0 phase closing-all
@0 w ml 00050200000000000000000000
@1 phase registering
@1 w ml 00000200000000000000010000
@2 log GFDI registered handle=0x65 reliable=false status=0
@2 phase awaiting-device-information
@3 log watch Forerunner 165 model=165 product=4432 fw=29.05 protocol=151 maxPacket=512
@3 w 5000 a01300
@3 w 5000 a013009600ffffffffffff0100ffff044e4f4f50044e4f4f50066f7261636c6501
@3 phase handshaking
@4 log watch capabilities: 10 bits; NOOP advertises 0,3,4,5,55,71,76,90
@4 w 5000 ba1300
@4 w 5050 113900000000008000801000040000000000
@4 w 5030 0800
@4 phase ready
@4 log handshake complete in 4 ms
@6 log sync start flow=legacy synced=1 resume=128/32 #1 idx=1 size=150 ts=1161900000@128 compression=false watchInitiated=false
@6 transfer true
@6 w 5002 0000000000000100000000000000
@8 w 5000 8c13000040000000
@9 w 5000 8c13000070000000
@9 log directory: 7 entries [128/3x1 128/32x2 128/44x1 128/49x1 128/68x1 8/255x1]; 2 to download
@9 log resuming 128/32 #1 idx=1 size=150 ts=1161900000 from offset 128
@9 w 5002 0100800000000036f41600000000
@10 log watch continues 128/32 #1 idx=1 size=150 ts=1161900000 at 128: 22 bytes left
@11 w 5000 8c13000096000000
@11 log downloaded MONITOR 128/32 #1 idx=1 size=150 ts=1161900000 (150 bytes, resumed at 128)
@11 file 128/32 #1 idx=1 size=150 ts=1161900000 n=150 fnv=e1692bc2 resumed=128 type=- fit=MONITOR
@11 w 5002 0200000000000100000000000000
@13 w 5000 8c13000040000000
@14 w 5000 8c13000046000000
@14 log downloaded SLEEP 128/49 #2 idx=2 size=70 ts=1161900060 (70 bytes)
@14 file 128/49 #2 idx=2 size=70 ts=1161900060 n=70 fnv=d2a3fcaf resumed=- type=- fit=SLEEP
@14 w 5030 0000
@14 log sync completed: listed=7 wanted=5 synced=2 old=1 large=0 downloaded=2 failed=0 resumed=1 bytes=204
@14 finished sync completed: listed=7 wanted=5 synced=2 old=1 large=0 downloaded=2 failed=0 resumed=1 bytes=204 empty=false
@14 transfer false
watch saw ml close-all | ml register gfdi | rx configuration | rx system event 8 | rx download idx=0 offset=0 kind=1 seed=0 size=0 flags=0 | rx chunk reply status=0 next=64 | rx chunk reply status=0 next=112 | rx download idx=1 offset=0 kind=1 seed=0 size=0 flags=0 | rx chunk reply status=0 next=64 | rx chunk reply status=0 next=128 | link cut at offset 128 | ml close-all | ml register gfdi | rx configuration | rx system event 8 | rx download idx=0 offset=0 kind=1 seed=0 size=0 flags=0 | rx chunk reply status=0 next=64 | rx chunk reply status=0 next=112 | rx download idx=1 offset=128 kind=0 seed=62518 size=22 flags=0 | rx chunk reply status=0 next=150 | rx download idx=2 offset=0 kind=1 seed=0 size=0 flags=0 | rx chunk reply status=0 next=64 | rx chunk reply status=0 next=70 | rx system event 0
watch types 5000,5002,5030,5050
end syncing=false resume=- deadline=-
trace legacy-resume-refused
@0 log session start transport=V2 multi-link mtu=150 firstPairing=false
@0 phase closing-all
@0 w ml 00050200000000000000000000
@1 phase registering
@1 w ml 00000200000000000000010000
@2 log GFDI registered handle=0x65 reliable=false status=0
@2 phase awaiting-device-information
@3 log watch Forerunner 165 model=165 product=4432 fw=29.05 protocol=151 maxPacket=512
@3 w 5000 a01300
@3 w 5000 a013009600ffffffffffff0100ffff044e4f4f50044e4f4f50066f7261636c6501
@3 phase handshaking
@4 log watch capabilities: 10 bits; NOOP advertises 0,3,4,5,55,71,76,90
@4 w 5000 ba1300
@4 w 5050 113900000000008000801000040000000000
@4 w 5030 0800
@4 phase ready
@4 log handshake complete in 4 ms
@6 log sync start flow=legacy synced=1 resume=128/49 #2 idx=2 size=70 ts=1161900060@20 compression=false watchInitiated=false
@6 transfer true
@6 w 5002 0000000000000100000000000000
@8 w 5000 8c13000040000000
@9 w 5000 8c13000070000000
@9 log directory: 7 entries [128/3x1 128/32x2 128/44x1 128/49x1 128/68x1 8/255x1]; 2 to download
@9 log resuming 128/49 #2 idx=2 size=70 ts=1161900060 from offset 20
@9 w 5002 02001400000000c7553200000000
@10 log download of 128/49 #2 idx=2 size=70 ts=1161900060 refused status=0 download=4
@10 log resume refused; restarting 128/49 #2 idx=2 size=70 ts=1161900060 from 0
@10 w 5002 0200000000000100000000000000
@12 w 5000 8c13000040000000
@13 w 5000 8c13000046000000
@13 log downloaded SLEEP 128/49 #2 idx=2 size=70 ts=1161900060 (70 bytes)
@13 file 128/49 #2 idx=2 size=70 ts=1161900060 n=70 fnv=d2a3fcaf resumed=- type=- fit=SLEEP
@13 w 5002 0100000000000100000000000000
@15 w 5000 8c13000040000000
@16 w 5000 8c13000080000000
@17 w 5000 8c13000096000000
@17 log downloaded MONITOR 128/32 #1 idx=1 size=150 ts=1161900000 (150 bytes)
@17 file 128/32 #1 idx=1 size=150 ts=1161900000 n=150 fnv=e1692bc2 resumed=- type=- fit=MONITOR
@17 w 5030 0000
@17 log sync completed: listed=7 wanted=5 synced=2 old=1 large=0 downloaded=2 failed=0 resumed=0 bytes=332
@17 finished sync completed: listed=7 wanted=5 synced=2 old=1 large=0 downloaded=2 failed=0 resumed=0 bytes=332 empty=false
@17 transfer false
watch saw ml close-all | ml register gfdi | rx configuration | rx system event 8 | rx download idx=0 offset=0 kind=1 seed=0 size=0 flags=0 | rx chunk reply status=0 next=64 | rx chunk reply status=0 next=112 | rx download idx=2 offset=20 kind=0 seed=21959 size=50 flags=0 | rx download idx=2 offset=0 kind=1 seed=0 size=0 flags=0 | rx chunk reply status=0 next=64 | rx chunk reply status=0 next=70 | rx download idx=1 offset=0 kind=1 seed=0 size=0 flags=0 | rx chunk reply status=0 next=64 | rx chunk reply status=0 next=128 | rx chunk reply status=0 next=150 | rx system event 0
watch types 5000,5002,5030,5050
end syncing=false resume=- deadline=-
trace legacy-nothing-new
@0 log session start transport=V2 multi-link mtu=150 firstPairing=false
@0 phase closing-all
@0 w ml 00050200000000000000000000
@1 phase registering
@1 w ml 00000200000000000000010000
@2 log GFDI registered handle=0x65 reliable=false status=0
@2 phase awaiting-device-information
@3 log watch Forerunner 165 model=165 product=4432 fw=29.05 protocol=151 maxPacket=512
@3 w 5000 a01300
@3 w 5000 a013009600ffffffffffff0100ffff044e4f4f50044e4f4f50066f7261636c6501
@3 phase handshaking
@4 log watch capabilities: 10 bits; NOOP advertises 0,3,4,5,55,71,76,90
@4 w 5000 ba1300
@4 w 5050 113900000000008000801000040000000000
@4 w 5030 0800
@4 phase ready
@4 log handshake complete in 4 ms
@6 log sync start flow=legacy synced=7 resume=128/32 #9 idx=9 size=100 ts=1161900000@3 compression=false watchInitiated=false
@6 transfer true
@6 w 5002 0000000000000100000000000000
@8 w 5000 8c13000040000000
@9 w 5000 8c13000070000000
@9 log directory: 7 entries [128/3x1 128/32x2 128/44x1 128/49x1 128/68x1 8/255x1]; 0 to download
@9 log resume point 128/32 #9 idx=9 size=100 ts=1161900000 is no longer listed; dropped
@9 w 5030 0000
@9 log sync completed: listed=7 wanted=5 synced=5 old=0 large=0 downloaded=0 failed=0 resumed=0 bytes=112
@9 finished sync completed: listed=7 wanted=5 synced=5 old=0 large=0 downloaded=0 failed=0 resumed=0 bytes=112 empty=true
@9 transfer false
watch saw ml close-all | ml register gfdi | rx configuration | rx system event 8 | rx download idx=0 offset=0 kind=1 seed=0 size=0 flags=0 | rx chunk reply status=0 next=64 | rx chunk reply status=0 next=112 | rx system event 0
watch types 5000,5002,5030,5050
end syncing=false resume=- deadline=-
trace legacy-directory-refused
@0 log session start transport=V2 multi-link mtu=150 firstPairing=false
@0 phase closing-all
@0 w ml 00050200000000000000000000
@1 phase registering
@1 w ml 00000200000000000000010000
@2 log GFDI registered handle=0x65 reliable=false status=0
@2 phase awaiting-device-information
@3 log watch Forerunner 165 model=165 product=4432 fw=29.05 protocol=151 maxPacket=512
@3 w 5000 a01300
@3 w 5000 a013009600ffffffffffff0100ffff044e4f4f50044e4f4f50066f7261636c6501
@3 phase handshaking
@4 log watch capabilities: 10 bits; NOOP advertises 0,3,4,5,55,71,76,90
@4 w 5000 ba1300
@4 w 5050 113900000000008000801000040000000000
@4 w 5030 0800
@4 phase ready
@4 log handshake complete in 4 ms
@6 log sync start flow=legacy synced=0 resume=none compression=false watchInitiated=false
@6 transfer true
@6 w 5002 0000000000000100000000000000
@7 log download of directory refused status=0 download=1
@7 w 5030 0000
@7 log sync failed(directory refused status=0 download=1): listed=0 wanted=0 synced=0 old=0 large=0 downloaded=0 failed=0 resumed=0 bytes=0
@7 finished sync failed(directory refused status=0 download=1): listed=0 wanted=0 synced=0 old=0 large=0 downloaded=0 failed=0 resumed=0 bytes=0 empty=true
@7 transfer false
watch saw ml close-all | ml register gfdi | rx configuration | rx system event 8 | rx download idx=0 offset=0 kind=1 seed=0 size=0 flags=0 | rx system event 0
watch types 5000,5002,5030,5050
end syncing=false resume=- deadline=-
trace legacy-stall
@0 log session start transport=V2 multi-link mtu=150 firstPairing=false
@0 phase closing-all
@0 w ml 00050200000000000000000000
@1 phase registering
@1 w ml 00000200000000000000010000
@2 log GFDI registered handle=0x65 reliable=false status=0
@2 phase awaiting-device-information
@3 log watch Forerunner 165 model=165 product=4432 fw=29.05 protocol=151 maxPacket=512
@3 w 5000 a01300
@3 w 5000 a013009600ffffffffffff0100ffff044e4f4f50044e4f4f50066f7261636c6501
@3 phase handshaking
@4 log watch capabilities: 10 bits; NOOP advertises 0,3,4,5,55,71,76,90
@4 w 5000 ba1300
@4 w 5050 113900000000008000801000040000000000
@4 w 5030 0800
@4 phase ready
@4 log handshake complete in 4 ms
@6 log sync start flow=legacy synced=0 resume=none compression=false watchInitiated=false
@6 transfer true
@6 w 5002 0000000000000100000000000000
@20006 w 5030 0000
@20006 log sync failed(stalled in awaiting-download-status for 20000 ms): listed=0 wanted=0 synced=0 old=0 large=0 downloaded=0 failed=0 resumed=0 bytes=0
@20006 finished sync failed(stalled in awaiting-download-status for 20000 ms): listed=0 wanted=0 synced=0 old=0 large=0 downloaded=0 failed=0 resumed=0 bytes=0 empty=true
@20006 transfer false
watch saw ml close-all | ml register gfdi | rx configuration | rx system event 8 | rx download idx=0 offset=0 kind=1 seed=0 size=0 flags=0 | rx system event 0
watch types 5000,5002,5030,5050
end syncing=false resume=- deadline=-
trace legacy-gfdi-closed
@0 log session start transport=V2 multi-link mtu=150 firstPairing=false
@0 phase closing-all
@0 w ml 00050200000000000000000000
@1 phase registering
@1 w ml 00000200000000000000010000
@2 log GFDI registered handle=0x65 reliable=false status=0
@2 phase awaiting-device-information
@3 log watch Forerunner 165 model=165 product=4432 fw=29.05 protocol=151 maxPacket=512
@3 w 5000 a01300
@3 w 5000 a013009600ffffffffffff0100ffff044e4f4f50044e4f4f50066f7261636c6501
@3 phase handshaking
@4 log watch capabilities: 10 bits; NOOP advertises 0,3,4,5,55,71,76,90
@4 w 5000 ba1300
@4 w 5050 113900000000008000801000040000000000
@4 w 5030 0800
@4 phase ready
@4 log handshake complete in 4 ms
@6 log sync start flow=legacy synced=1 resume=none compression=false watchInitiated=false
@6 transfer true
@6 w 5002 0000000000000100000000000000
@8 w 5000 8c13000040000000
@9 w 5000 8c13000070000000
@9 log directory: 7 entries [128/3x1 128/32x2 128/44x1 128/49x1 128/68x1 8/255x1]; 2 to download
@9 w 5002 0100000000000100000000000000
@11 w 5000 8c13000040000000
@12 log watch closed GFDI handle 0x65 status=0; re-registering
@12 log sync interrupted(GFDI handle closed by the watch): listed=7 wanted=5 synced=2 old=1 large=0 downloaded=0 failed=0 resumed=0 bytes=176
@12 finished sync interrupted(GFDI handle closed by the watch): listed=7 wanted=5 synced=2 old=1 large=0 downloaded=0 failed=0 resumed=0 bytes=176 empty=true
@12 transfer false
@12 log holding resume point 128/32 #1 idx=1 size=150 ts=1161900000@64
@12 phase registering
@12 w ml 00000200000000000000010000
@13 log GFDI registered handle=0x65 reliable=false status=0
@13 phase awaiting-device-information
@14 w 5000 a01300
@14 w 5000 a013009600ffffffffffff0100ffff044e4f4f50044e4f4f50066f7261636c6501
@14 phase handshaking
@15 log watch capabilities: 10 bits; NOOP advertises 0,3,4,5,55,71,76,90
@15 w 5000 ba1300
@15 w 5050 113900000000008000801000040000000000
@15 w 5030 0800
@15 phase ready
@15 log handshake complete in 3 ms
watch saw ml close-all | ml register gfdi | rx configuration | rx system event 8 | rx download idx=0 offset=0 kind=1 seed=0 size=0 flags=0 | rx chunk reply status=0 next=64 | rx chunk reply status=0 next=112 | rx download idx=1 offset=0 kind=1 seed=0 size=0 flags=0 | rx chunk reply status=0 next=64 | link cut at offset 64 | ml register gfdi | rx configuration | rx system event 8
watch types 5000,5002,5030,5050
end syncing=false resume=128/32 #1 idx=1 size=150 ts=1161900000@64 crc=61724 deadline=-
trace file-access-flush
@0 log session start transport=V2 multi-link mtu=150 firstPairing=false
@0 phase closing-all
@0 w ml 00050200000000000000000000
@1 phase registering
@1 w ml 00000200000000000000010000
@2 log GFDI registered handle=0x65 reliable=false status=0
@2 phase awaiting-device-information
@3 log watch Forerunner 165 model=165 product=4432 fw=29.05 protocol=151 maxPacket=512
@3 w 5000 a01300
@3 w 5000 a013009600ffffffffffff0100ffff044e4f4f50044e4f4f50066f7261636c6501
@3 phase handshaking
@4 log watch capabilities: 10 bits; NOOP advertises 0,3,4,5,55,71,76,90
@4 w 5000 ba1300
@4 w 5050 113900000000008000801000040000000000
@4 w 5030 0800
@4 phase ready
@4 log handshake complete in 4 ms
@6 log sync start flow=file-access flush=true synced=1 resume=none compression=false watchInitiated=false
@6 transfer true
@6 w 5002 0000000000000100000000000000
@8 w 5000 8c13000040000000
@9 w 5000 8c13000070000000
@9 w 5043 0100000000001900000019000000da02164a142a1209a5a500000000000011a5a5000000000000
@11 w 5000 b413000100000000000000
@12 w 5000 b413000100340000000000
@12 w 5043 len=41 fnv=52565e6a
@14 w 5000 b413000200000000000000
@15 w 5000 b413000200380000000000
@15 log item list: 5 items; 2 to download
@15 w 5043 len=77 fnv=cbc2ff86
@17 w 5000 b413000300000000000000
@17 w ml 00000200000000000000182000
@18 w raw 66000034120000
@29 log downloaded FIT_TYPE_32 255/32 #58799 idx=16605 size=416 ts=1161900000 (328 -> 416 bytes)
@29 file 255/32 #58799 idx=16605 size=416 ts=1161900000 n=416 fnv=be5fb77d resumed=- type=FIT_TYPE_32 fit=MONITOR
@29 w 5043 len=61 fnv=4f0b8c15
@31 w 5000 b413000400000000000000
@31 log pull refused result=2 error=none
@31 w 5030 0000
@31 log sync completed: listed=5 wanted=3 synced=1 old=0 large=0 downloaded=1 failed=1 resumed=0 bytes=440
@31 finished sync completed: listed=5 wanted=3 synced=1 old=0 large=0 downloaded=1 failed=1 resumed=0 bytes=440 empty=false
@31 transfer false
watch saw ml close-all | ml register gfdi | rx configuration | rx system event 8 | rx download idx=0 offset=0 kind=1 seed=0 size=0 flags=0 | rx chunk reply status=0 next=64 | rx chunk reply status=0 next=112 | rx item list page=0 | rx item list page=1 | rx pull item uid=00000000000000a1 -> 4660 | ml register service=8216 | pipe start transfer=4660 bytes=66000034120000 | rx pull item uid=00000000000000a2 -> refused | rx system event 0
watch types 5000,5002,5030,5043,5050
end syncing=false resume=- deadline=-
trace file-access-no-flush
@0 log session start transport=V2 multi-link mtu=150 firstPairing=false
@0 phase closing-all
@0 w ml 00050200000000000000000000
@1 phase registering
@1 w ml 00000200000000000000010000
@2 log GFDI registered handle=0x65 reliable=false status=0
@2 phase awaiting-device-information
@3 log watch Forerunner 165 model=165 product=4432 fw=29.05 protocol=151 maxPacket=512
@3 w 5000 a01300
@3 w 5000 a013009600ffffffffffff0100ffff044e4f4f50044e4f4f50066f7261636c6501
@3 phase handshaking
@4 log watch capabilities: 10 bits; NOOP advertises 0,3,4,5,55,71,76,90
@4 w 5000 ba1300
@4 w 5050 113900000000008000801000040000000000
@4 w 5030 0800
@4 phase ready
@4 log handshake complete in 4 ms
@7 w 5000 b313008403000000000000
@7 announced item added count=1 filter=false
@8 w 5000 b313008503000000000000
@8 announced sync button filter=false
@9 w 5000 911300
@9 announced file available 128/32 #1 idx=1 size=150 ts=1161900000 filter=false
@9 log sync start flow=file-access flush=false synced=0 resume=none compression=false watchInitiated=false
@9 transfer true
@9 w 5043 0100000000001900000019000000da02164a142a1209a5a500000000000011a5a5000000000000
@11 w 5000 b413000100000000000000
@12 w 5000 b4130001001c0000000000
@12 log item list: 1 items; 1 to download
@12 w 5043 len=77 fnv=69130721
@14 w 5000 b413000200000000000000
@14 w ml 00000200000000000000182000
@15 w raw 66000042000000
@26 log FileAccess item 255/32 #58799 idx=16605 size=416 ts=1161900000: 327 bytes did not inflate
@26 w 5030 0000
@26 log sync completed: listed=1 wanted=1 synced=0 old=0 large=0 downloaded=0 failed=1 resumed=0 bytes=327
@26 finished sync completed: listed=1 wanted=1 synced=0 old=0 large=0 downloaded=0 failed=1 resumed=0 bytes=327 empty=true
@26 transfer false
watch saw ml close-all | ml register gfdi | rx configuration | rx system event 8 | rx item list page=0 | rx pull item uid=00000000000000a1 -> 66 | ml register service=8216 | pipe start transfer=66 bytes=66000042000000 | rx system event 0
watch types 5000,5030,5043,5050
end syncing=false resume=- deadline=-
trace compressed
@0 log session start transport=V2 multi-link mtu=150 firstPairing=false
@0 phase closing-all
@0 w ml 00050200000000000000000000
@1 phase registering
@1 w ml 00000200000000000000010000
@2 log GFDI registered handle=0x65 reliable=false status=0
@2 phase awaiting-device-information
@3 log watch Forerunner 165 model=165 product=4432 fw=29.05 protocol=151 maxPacket=512
@3 w 5000 a01300
@3 w 5000 a013009600ffffffffffff0100ffff044e4f4f50044e4f4f50066f7261636c6501
@3 phase handshaking
@4 log watch capabilities: 10 bits; NOOP advertises 0,3,4,5,55,71,76,90
@4 w 5000 ba1300
@4 w 5050 113900000000008000801000040000000000
@4 w 5030 0800
@4 phase ready
@4 log handshake complete in 4 ms
@6 log sync start flow=legacy synced=0 resume=none compression=true watchInitiated=false
@6 transfer true
@6 w 5002 0000000000000100000000000000
@8 w 5000 8c13000020000000
@8 log directory: 2 entries [128/32x1 128/49x1]; 2 to download
@8 w 5002 0800000000000100000000000001
@10 w 5000 8c1300003c000000
@11 w 5000 8c13000078000000
@12 w 5000 8c130000b4000000
@13 w 5000 8c130000f0000000
@14 w 5000 8c1300002c010000
@15 w 5000 8c13000048010000
@15 log downloaded MONITOR 128/32 #8 idx=8 size=416 ts=1161900000 (416 bytes from 328 compressed)
@15 file 128/32 #8 idx=8 size=416 ts=1161900000 n=416 fnv=be5fb77d resumed=- type=- fit=MONITOR
@15 w 5002 0900000000000100000000000001
@17 w 5000 8c1300003c000000
@18 w 5000 8c13000078000000
@19 w 5000 8c130000b4000000
@20 w 5000 8c130000f0000000
@21 w 5000 8c1300002c010000
@22 w 5000 8c13000048010000
@22 log 128/49 #9 idx=9 size=416 ts=1161900010: last piece arrived but the stream does not inflate; giving it up for this sync
@22 w 5000 8c13000248010000
@22 w 5030 0000
@22 log sync completed: listed=2 wanted=2 synced=0 old=0 large=0 downloaded=1 failed=1 resumed=0 bytes=688
@22 finished sync completed: listed=2 wanted=2 synced=0 old=0 large=0 downloaded=1 failed=1 resumed=0 bytes=688 empty=false
@22 transfer false
watch saw ml close-all | ml register gfdi | rx configuration | rx system event 8 | rx download idx=0 offset=0 kind=1 seed=0 size=0 flags=0 | rx chunk reply status=0 next=32 | rx download idx=8 offset=0 kind=1 seed=0 size=0 flags=1 | rx chunk reply status=0 next=60 | rx chunk reply status=0 next=120 | rx chunk reply status=0 next=180 | rx chunk reply status=0 next=240 | rx chunk reply status=0 next=300 | rx chunk reply status=0 next=328 | rx download idx=9 offset=0 kind=1 seed=0 size=0 flags=1 | rx chunk reply status=0 next=60 | rx chunk reply status=0 next=120 | rx chunk reply status=0 next=180 | rx chunk reply status=0 next=240 | rx chunk reply status=0 next=300 | rx chunk reply status=0 next=328 | rx chunk reply status=2 next=328 | rx system event 0
watch types 5000,5002,5030,5050
end syncing=false resume=- deadline=-
trace compressed-cut
@0 log session start transport=V2 multi-link mtu=150 firstPairing=false
@0 phase closing-all
@0 w ml 00050200000000000000000000
@1 phase registering
@1 w ml 00000200000000000000010000
@2 log GFDI registered handle=0x65 reliable=false status=0
@2 phase awaiting-device-information
@3 log watch Forerunner 165 model=165 product=4432 fw=29.05 protocol=151 maxPacket=512
@3 w 5000 a01300
@3 w 5000 a013009600ffffffffffff0100ffff044e4f4f50044e4f4f50066f7261636c6501
@3 phase handshaking
@4 log watch capabilities: 10 bits; NOOP advertises 0,3,4,5,55,71,76,90
@4 w 5000 ba1300
@4 w 5050 113900000000008000801000040000000000
@4 w 5030 0800
@4 phase ready
@4 log handshake complete in 4 ms
@6 log sync start flow=legacy synced=0 resume=none compression=true watchInitiated=false
@6 transfer true
@6 w 5002 0000000000000100000000000000
@8 w 5000 8c13000020000000
@8 log directory: 2 entries [128/32x1 128/49x1]; 2 to download
@8 w 5002 0800000000000100000000000001
@10 w 5000 8c13000078000000
@11 w 5000 8c130000f0000000
watch saw ml close-all | ml register gfdi | rx configuration | rx system event 8 | rx download idx=0 offset=0 kind=1 seed=0 size=0 flags=0 | rx chunk reply status=0 next=32 | rx download idx=8 offset=0 kind=1 seed=0 size=0 flags=1 | link cut at piece 2 | rx chunk reply status=0 next=120 | rx chunk reply status=0 next=240
watch types 5000,5002,5030,5050
end syncing=true resume=128/32 #8 idx=8 size=416 ts=1161900000@64 crc=8090 deadline=20011
trace compressed-resume-restarted
@0 log session start transport=V2 multi-link mtu=150 firstPairing=false
@0 phase closing-all
@0 w ml 00050200000000000000000000
@1 phase registering
@1 w ml 00000200000000000000010000
@2 log GFDI registered handle=0x65 reliable=false status=0
@2 phase awaiting-device-information
@3 log watch Forerunner 165 model=165 product=4432 fw=29.05 protocol=151 maxPacket=512
@3 w 5000 a01300
@3 w 5000 a013009600ffffffffffff0100ffff044e4f4f50044e4f4f50066f7261636c6501
@3 phase handshaking
@4 log watch capabilities: 10 bits; NOOP advertises 0,3,4,5,55,71,76,90
@4 w 5000 ba1300
@4 w 5050 113900000000008000801000040000000000
@4 w 5030 0800
@4 phase ready
@4 log handshake complete in 4 ms
@6 log sync start flow=legacy synced=0 resume=128/32 #8 idx=8 size=416 ts=1161900000@64 compression=true watchInitiated=false
@6 transfer true
@6 w 5002 0000000000000100000000000000
@8 w 5000 8c13000020000000
@8 log directory: 2 entries [128/32x1 128/49x1]; 2 to download
@8 log resuming 128/32 #8 idx=8 size=416 ts=1161900000 from offset 64
@8 w 5002 080040000000009a1f6001000000
@9 log watch continues 128/32 #8 idx=8 size=416 ts=1161900000 at 64: 352 bytes left
@10 log watch restarted 128/32 #8 idx=8 size=416 ts=1161900000 from 0 instead of continuing at 64
@10 w 5000 8c13000040000000
@11 w 5000 8c13000080000000
@12 w 5000 8c130000c0000000
@13 w 5000 8c13000000010000
@14 w 5000 8c13000040010000
@15 w 5000 8c13000080010000
@16 w 5000 8c130000a0010000
@16 log downloaded MONITOR 128/32 #8 idx=8 size=416 ts=1161900000 (416 bytes)
@16 file 128/32 #8 idx=8 size=416 ts=1161900000 n=416 fnv=be5fb77d resumed=- type=- fit=MONITOR
@16 w 5002 0900000000000100000000000001
@18 w 5000 8c13000078000000
@19 w 5000 8c130000f0000000
@20 w 5000 8c13000048010000
@20 log downloaded SLEEP 128/49 #9 idx=9 size=416 ts=1161900010 (416 bytes from 328 compressed)
@20 file 128/49 #9 idx=9 size=416 ts=1161900010 n=416 fnv=be5fb77d resumed=- type=- fit=SLEEP
@20 w 5030 0000
@20 log sync completed: listed=2 wanted=2 synced=0 old=0 large=0 downloaded=2 failed=0 resumed=0 bytes=776
@20 finished sync completed: listed=2 wanted=2 synced=0 old=0 large=0 downloaded=2 failed=0 resumed=0 bytes=776 empty=false
@20 transfer false
watch saw ml close-all | ml register gfdi | rx configuration | rx system event 8 | rx download idx=0 offset=0 kind=1 seed=0 size=0 flags=0 | rx chunk reply status=0 next=32 | rx download idx=8 offset=0 kind=1 seed=0 size=0 flags=1 | link cut at piece 2 | rx chunk reply status=0 next=120 | rx chunk reply status=0 next=240 | ml close-all | ml register gfdi | rx configuration | rx system event 8 | rx download idx=0 offset=0 kind=1 seed=0 size=0 flags=0 | rx chunk reply status=0 next=32 | rx download idx=8 offset=64 kind=0 seed=8090 size=352 flags=0 | rx chunk reply status=0 next=64 | rx chunk reply status=0 next=128 | rx chunk reply status=0 next=192 | rx chunk reply status=0 next=256 | rx chunk reply status=0 next=320 | rx chunk reply status=0 next=384 | rx chunk reply status=0 next=416 | rx download idx=9 offset=0 kind=1 seed=0 size=0 flags=1 | rx chunk reply status=0 next=120 | rx chunk reply status=0 next=240 | rx chunk reply status=0 next=328 | rx system event 0
watch types 5000,5002,5030,5050
end syncing=false resume=- deadline=-
trace legacy-continue-restarted
@0 log session start transport=V2 multi-link mtu=150 firstPairing=false
@0 phase closing-all
@0 w ml 00050200000000000000000000
@1 phase registering
@1 w ml 00000200000000000000010000
@2 log GFDI registered handle=0x65 reliable=false status=0
@2 phase awaiting-device-information
@3 log watch Forerunner 165 model=165 product=4432 fw=29.05 protocol=151 maxPacket=512
@3 w 5000 a01300
@3 w 5000 a013009600ffffffffffff0100ffff044e4f4f50044e4f4f50066f7261636c6501
@3 phase handshaking
@4 log watch capabilities: 10 bits; NOOP advertises 0,3,4,5,55,71,76,90
@4 w 5000 ba1300
@4 w 5050 113900000000008000801000040000000000
@4 w 5030 0800
@4 phase ready
@4 log handshake complete in 4 ms
@6 log sync start flow=legacy synced=1 resume=128/32 #1 idx=1 size=150 ts=1161900000@64 compression=false watchInitiated=false
@6 transfer true
@6 w 5002 0000000000000100000000000000
@8 w 5000 8c13000040000000
@9 w 5000 8c13000070000000
@9 log directory: 7 entries [128/3x1 128/32x2 128/44x1 128/49x1 128/68x1 8/255x1]; 2 to download
@9 log resuming 128/32 #1 idx=1 size=150 ts=1161900000 from offset 64
@9 w 5002 010040000000001cf15600000000
@10 log watch continues 128/32 #1 idx=1 size=150 ts=1161900000 at 64: 86 bytes left
@11 log watch restarted 128/32 #1 idx=1 size=150 ts=1161900000 from 0 instead of continuing at 64
@11 w 5000 8c13000040000000
@12 w 5000 8c13000080000000
@13 w 5000 8c13000096000000
@13 log downloaded MONITOR 128/32 #1 idx=1 size=150 ts=1161900000 (150 bytes)
@13 file 128/32 #1 idx=1 size=150 ts=1161900000 n=150 fnv=e1692bc2 resumed=- type=- fit=MONITOR
@13 w 5002 0200000000000100000000000000
@15 w 5000 8c13000040000000
@16 w 5000 8c13000046000000
@16 log downloaded SLEEP 128/49 #2 idx=2 size=70 ts=1161900060 (70 bytes)
@16 file 128/49 #2 idx=2 size=70 ts=1161900060 n=70 fnv=d2a3fcaf resumed=- type=- fit=SLEEP
@16 w 5030 0000
@16 log sync completed: listed=7 wanted=5 synced=2 old=1 large=0 downloaded=2 failed=0 resumed=0 bytes=332
@16 finished sync completed: listed=7 wanted=5 synced=2 old=1 large=0 downloaded=2 failed=0 resumed=0 bytes=332 empty=false
@16 transfer false
watch saw ml close-all | ml register gfdi | rx configuration | rx system event 8 | rx download idx=0 offset=0 kind=1 seed=0 size=0 flags=0 | rx chunk reply status=0 next=64 | rx chunk reply status=0 next=112 | rx download idx=1 offset=64 kind=0 seed=61724 size=86 flags=0 | rx chunk reply status=0 next=64 | rx chunk reply status=0 next=128 | rx chunk reply status=0 next=150 | rx download idx=2 offset=0 kind=1 seed=0 size=0 flags=0 | rx chunk reply status=0 next=64 | rx chunk reply status=0 next=70 | rx system event 0
watch types 5000,5002,5030,5050
end syncing=false resume=- deadline=-
trace not-ready
@0 log sync not started: session is idle
watch saw 
watch types 
end syncing=false resume=- deadline=-
inflate stored n=17 fnv=569a5b2d
inflate fixed n=17 fnv=990dc773
inflate dynamic n=1360 fnv=7e98e450
inflate bad-header nil
inflate bad-adler nil
inflate truncated nil
inflate empty nil
inflate pipe matches=true
key fa 255/32 #58799 idx=16605 size=416 ts=1161900000
key fa-none 255/0 #25229 idx=54675 size=10 ts=0
key legacy 128/49 #48879 idx=4660 size=70000 ts=4275878552
wanted 4=ACTIVITY,15=MONITOR_A,28=MONITOR_DAILY,32=MONITOR,44=METRICS,49=SLEEP,68=HRV_STATUS,73=SKIN_TEMP
fit-subtype FIT_TYPE_32=32
fit-subtype FIT_TYPE_4=4
fit-subtype FIT_TYPE_255=255
fit-subtype FIT_TYPE_256=-
fit-subtype FIT_TYPE_=-
fit-subtype FIT_TYPE_-1=-
fit-subtype FIT_TYPE_0032=-
fit-subtype IQWatchApps=-
fit-subtype (empty)=-
item-time modified=1161900000 uid=1161900000 none=0 created=1162473494 max=0
flow product=4432 fileAccess(legacyFlush: true)
flow product=3990 legacy
flow product=0 legacy
flow product=nil legacy
"""
        // ORACLE-END
    }
}
