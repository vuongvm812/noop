package com.noop.garmin

// History sync: read-only FIT download. Twin of
// Packages/GarminProtocol/Sources/GarminProtocol/GarminHistorySync.swift, where both flows (legacy GFDI and
// protobuf FileAccess), the compressed 5054 layout observed on a Forerunner 165, the resume rule and the
// read-only guarantee are documented. Pure: no Bluetooth, no timers, no clock. GarminHistorySyncTest replays
// the Swift oracle's scripted fake-watch traces and compares the printed transcript line for line, so every
// log line here matches the Swift text exactly.

/**
 * The identity of one file on the watch (the `garminSyncedFile` ledger key, in its column order). A file the
 * watch rewrites gets a new size or timestamp and so a new key. u8/u16 fields are Int, u32 fields Long.
 */
data class GarminSyncFileKey(
    val fileIndex: Int,
    val dataType: Int,
    val subType: Int,
    val fileNumber: Int,
    /** Garmin-epoch seconds (0 = the watch gave no date). */
    val garminTimestamp: Long,
    val size: Long,
) : Comparable<GarminSyncFileKey> {

    constructor(entry: GarminDirectoryEntry) : this(
        entry.fileIndex, entry.dataType, entry.subType, entry.fileNumber, entry.garminTimestamp, entry.fileSize,
    )

    override fun compareTo(other: GarminSyncFileKey): Int = compareValuesBy(
        this, other, { it.garminTimestamp }, { it.fileIndex }, { it.dataType }, { it.subType }, { it.fileNumber }, { it.size },
    )

    /** Compact diagnostic form: `128/32 #7 idx=12 size=4096 ts=1100000000`. */
    val label: String get() = "$dataType/$subType #$fileNumber idx=$fileIndex size=$size ts=$garminTimestamp"

    companion object {
        /** The `dataType` recorded for a FileAccess item; 255 is not a GFDI FIT data type (128). */
        const val FILE_ACCESS_DATA_TYPE = 255

        /** A FileAccess item's ledger key: the uid folded with FNV-1a into fileIndex / fileNumber. Null without a uid. */
        fun fileAccess(item: GarminItemReference, typeName: String? = null): GarminSyncFileKey? {
            val uid = item.uid ?: return null
            var hash = 0x811C9DC5.toInt()
            for (half in longArrayOf(uid.mostSignificant, uid.leastSignificant)) {
                for (shift in 56 downTo 0 step 8) {
                    hash = hash xor ((half ushr shift).toInt() and 0xFF)
                    hash *= 0x01000193
                }
            }
            return GarminSyncFileKey(
                fileIndex = hash and 0xFFFF,
                dataType = FILE_ACCESS_DATA_TYPE,
                subType = fitSubType(typeName) ?: 0,
                fileNumber = (hash ushr 16) and 0xFFFF,
                garminTimestamp = itemTime(item),
                size = (item.dataSize ?: 0L) and 0xFFFF_FFFFL,
            )
        }

        /** "FIT_TYPE_32" → 32 (observed naming on a Forerunner 165); null for any other name. */
        fun fitSubType(typeName: String?): Int? {
            val prefix = "FIT_TYPE_"
            if (typeName == null || !typeName.startsWith(prefix)) return null
            val digits = typeName.substring(prefix.length)
            if (digits.isEmpty() || digits.length > 3 || !digits.all { it in '0'..'9' }) return null
            val v = digits.toInt()
            return if (v <= 255) v else null
        }

        /** An item's Garmin-epoch date: its modified time, else the uid's top 32 bits (creation time). */
        fun itemTime(item: GarminItemReference): Long {
            val modified = item.modifiedTime
            if (modified != null && modified != 0L) return modified
            val created = ((item.uid?.mostSignificant ?: 0L) ushr 32) and 0xFFFF_FFFFL
            return if (created == 0xFFFF_FFFFL) 0L else created
        }
    }
}

/** FIT file sub-types (data type 128) NOOP downloads. */
enum class GarminFitFileType(val raw: Int, val wireName: String) {
    ACTIVITY(4, "ACTIVITY"),
    MONITOR_A(15, "MONITOR_A"),
    MONITOR_DAILY(28, "MONITOR_DAILY"),
    MONITOR(32, "MONITOR"),
    METRICS(44, "METRICS"),
    SLEEP(49, "SLEEP"),
    HRV_STATUS(68, "HRV_STATUS"),
    SKIN_TEMP(73, "SKIN_TEMP");

    companion object {
        const val FIT_DATA_TYPE = 128
        val WANTED_SUB_TYPES: Set<Int> = entries.map { it.raw }.toSet()
        fun fromRaw(raw: Int): GarminFitFileType? = entries.firstOrNull { it.raw == raw }
    }
}

sealed class GarminSyncFlow {
    /** GFDI directory + DOWNLOAD_REQUEST (Gadgetbridge's default). */
    object Legacy : GarminSyncFlow()
    /** Protobuf FileAccess; [legacyFlush] first downloads the legacy directory (Gadgetbridge #6700). */
    data class FileAccess(val legacyFlush: Boolean) : GarminSyncFlow()
}

/** Where an interrupted transfer stopped: the checked first bytes of the file and their CRC. */
class GarminSyncResume(val key: GarminSyncFileKey, val data: ByteArray, val runningCrc: Int) {
    val offset: Long get() = data.size.toLong()
}

data class GarminSyncRequest(
    val flow: GarminSyncFlow = GarminSyncFlow.Legacy,
    /** Files already ingested (the `garminSyncedFile` ledger). Never downloaded again. */
    val synced: Set<GarminSyncFileKey> = emptySet(),
    val resume: GarminSyncResume? = null,
    /** DownloadRequest flag 0x01, for wanted files only. */
    val requestCompression: Boolean = false,
    /** The watch asked (5037): answer with FILTER first. */
    val watchInitiated: Boolean = false,
    /** Files dated before this Unix time are skipped. null = no bound. */
    val notBeforeUnix: Long? = null,
    val maxFileBytes: Long = 16L shl 20,
)

/** One file fully downloaded and checked, ready to decode. */
class GarminDownloadedFile(
    val key: GarminSyncFileKey,
    /** The FIT bytes (inflated already when sent compressed). */
    val bytes: ByteArray,
    /** The offset this transfer resumed from, null when it started at 0. */
    val resumedFrom: Long?,
    /** FileAccess type name when the watch gave one, null for the legacy flow. */
    val typeName: String?,
) {
    val fitType: GarminFitFileType? get() = GarminFitFileType.fromRaw(key.subType)
}

data class GarminSyncSummary(
    var outcome: Outcome = Outcome.Completed,
    var listed: Int = 0,
    var wanted: Int = 0,
    var alreadySynced: Int = 0,
    var skippedOld: Int = 0,
    var skippedLarge: Int = 0,
    var downloaded: Int = 0,
    var failed: Int = 0,
    var resumed: Int = 0,
    var bytes: Int = 0,
    /** Every listed wanted FIT file (the entries [wanted] counts), sorted ascending. Not part of [line]. */
    var onWatch: List<GarminSyncFileKey> = emptyList(),
) {
    sealed class Outcome(val label: String) {
        object Completed : Outcome("completed")
        data class Interrupted(val why: String) : Outcome("interrupted($why)")
        data class Failed(val why: String) : Outcome("failed($why)")
    }

    /** True when nothing new arrived: the empty-sync backoff counts these. */
    val isEmpty: Boolean get() = downloaded == 0

    val line: String
        get() = "sync ${outcome.label}: listed=$listed wanted=$wanted synced=$alreadySynced old=$skippedOld " +
            "large=$skippedLarge downloaded=$downloaded failed=$failed resumed=$resumed bytes=$bytes"
}

/** Which history flow a watch model syncs with (see GarminHistorySync.swift for the evidence). */
object GarminSyncModelPolicy {
    val FILE_ACCESS_PRODUCTS: Set<Int> = setOf(4432)
    val NO_LEGACY_FLUSH_PRODUCTS: Set<Int> = emptySet()

    fun flow(productNumber: Int?): GarminSyncFlow {
        if (productNumber == null || productNumber !in FILE_ACCESS_PRODUCTS) return GarminSyncFlow.Legacy
        return GarminSyncFlow.FileAccess(legacyFlush = productNumber !in NO_LEGACY_FLUSH_PRODUCTS)
    }
}

/** The start packet written on a FileAccess file-transfer handle: `[0][0][transferHandle u16][0][0]`. */
data class GarminPipeStartRequest(val transferHandle: Int) {
    fun packet(mlHandle: Int): ByteArray = byteArrayOf(
        mlHandle.toByte(), 0, 0, (transferHandle and 0xFF).toByte(), ((transferHandle shr 8) and 0xFF).toByte(), 0, 0,
    )
}

/** What the engine asks the session to do. */
internal sealed class GarminSyncAction {
    class Send(val message: GarminOutbound) : GarminSyncAction()
    class Control(val request: GarminMultiLink.ControlRequest) : GarminSyncAction()
    class Pipe(val request: GarminPipeStartRequest, val mlHandle: Int) : GarminSyncAction()
    class Log(val rare: Boolean, val text: String) : GarminSyncAction()
    class File(val file: GarminDownloadedFile) : GarminSyncAction()
    class Finished(val summary: GarminSyncSummary) : GarminSyncAction()
    class TransferActive(val active: Boolean) : GarminSyncAction()
}

/** A growable byte buffer (a file arrives in hundreds of chunks). */
internal class GarminByteBuffer(initial: ByteArray = ByteArray(0)) {
    private var buf = initial.copyOf(maxOf(64, initial.size))
    var size = initial.size
        private set

    fun append(data: ByteArray) {
        if (size + data.size > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + data.size))
        System.arraycopy(data, 0, buf, size, data.size)
        size += data.size
    }

    fun toByteArray(): ByteArray = buf.copyOf(size)
}

internal class GarminHistorySync {

    private sealed class Target {
        data class Directory(val flushOnly: Boolean) : Target()
        data class File(val key: GarminSyncFileKey) : Target()
    }

    private class Transfer(
        val target: Target,
        var expected: Long = 0,
        data: ByteArray = ByteArray(0),
        var crc: Int = 0,
        var compressed: Boolean,
        val resumedFrom: Long?,
    ) {
        val data = GarminByteBuffer(data)
        val stream = GarminByteBuffer()
        val checkpoints = ArrayList<Pair<Long, Int>>()
        var badChunks = 0
        var sizeKnown = false
        var reported: Long = 0
        var received = 0
        var pieces = 0
    }

    private sealed class Stage {
        object Idle : Stage()
        object AwaitingFilter : Stage()
        object AwaitingDownloadStatus : Stage()
        object Receiving : Stage()
        data class AwaitingList(val requestId: Int) : Stage()
        data class AwaitingPull(val requestId: Int) : Stage()
        object RegisteringPipe : Stage()
        data class ReceivingPipe(val mlHandle: Int) : Stage()
    }

    var request: GarminSyncRequest? = null
        private set
    private var stage: Stage = Stage.Idle
    private var transfer: Transfer? = null
    private var queue = ArrayList<GarminSyncFileKey>()
    private var items = HashMap<GarminSyncFileKey, GarminItemReference>()
    /** typeNameKey → name for the list page being read: the watch reuses key numbers across pages. */
    private var itemNames = HashMap<Long, String>()
    /** Each wanted item's name, resolved while its page was read. */
    private var itemTypeNames = HashMap<GarminSyncFileKey, String>()
    private var currentItem: GarminSyncFileKey? = null
    private var pullTransferHandle = 0
    private var pipeData = GarminByteBuffer()
    private var pipeStarted = false
    private var listSession: Long? = null
    private var fragments = HashMap<Int, ByteArray>()
    private var nextRequestId = 1
    private var summary = GarminSyncSummary()
    private var resumeFailed = HashSet<GarminSyncFileKey>()
    var deadlineMs: Long? = null
        private set
    private var now = 0L
    private val actions = ArrayList<GarminSyncAction>()

    val isActive: Boolean get() = stage != Stage.Idle

    /** What an interrupted sync left behind, kept until the next [start] takes it. */
    var heldResume: GarminSyncResume? = null
        private set

    /** The point an interrupted legacy file transfer could continue from (see the Swift twin). */
    val resumePoint: GarminSyncResume?
        get() {
            if (!isActive) return heldResume
            val t = transfer
            if (t != null && t.target is Target.File) {
                val have = if (t.compressed) checkedPrefix(t) else t.data.toByteArray()
                if (have.isNotEmpty() && (!t.sizeKnown || have.size < t.expected)) {
                    return GarminSyncResume(t.target.key, have, if (t.compressed) GarminCrc.crc16(have) else t.crc)
                }
            }
            return request?.resume
        }

    // Entry points

    fun start(request: GarminSyncRequest, nowMs: Long): List<GarminSyncAction> {
        if (stage != Stage.Idle) {
            note(false, "sync already running; trigger ignored")
            return flush()
        }
        this.request = request
        heldResume = null
        now = nowMs
        summary = GarminSyncSummary()
        queue = ArrayList()
        items = HashMap()
        itemNames = HashMap()
        itemTypeNames = HashMap()
        listSession = null
        fragments = HashMap()
        resumeFailed = HashSet()
        val flowName = when (val f = request.flow) {
            is GarminSyncFlow.Legacy -> "legacy"
            is GarminSyncFlow.FileAccess -> "file-access flush=${f.legacyFlush}"
        }
        note(true, "sync start flow=$flowName synced=${request.synced.size} " +
            "resume=${request.resume?.let { "${it.key.label}@${it.offset}" } ?: "none"} " +
            "compression=${request.requestCompression} watchInitiated=${request.watchInitiated}")
        actions.add(GarminSyncAction.TransferActive(true))
        val flow = request.flow
        if (flow is GarminSyncFlow.FileAccess && !flow.legacyFlush) {
            requestList(nowMs)
        } else if (request.watchInitiated) {
            send(GarminOutbound.Filter(GarminFilterType.ONLY_NEW))
            stage = Stage.AwaitingFilter
            touch(nowMs)
        } else {
            requestDirectory(flow != GarminSyncFlow.Legacy, nowMs)
        }
        return flush()
    }

    fun tick(nowMs: Long): List<GarminSyncAction> {
        now = nowMs
        val deadline = deadlineMs
        if (deadline != null && nowMs >= deadline) finish(GarminSyncSummary.Outcome.Failed("stalled in $stageLabel for $STALL_TIMEOUT_MS ms"))
        return flush()
    }

    /** The GFDI link went away: end without sending anything; [resumePoint] keeps what could continue. */
    fun interrupt(reason: String): List<GarminSyncAction> {
        if (!isActive) return flush()
        val held = resumePoint
        finish(GarminSyncSummary.Outcome.Interrupted(reason), sendComplete = false)
        heldResume = held
        if (held != null) note(true, "holding resume point ${held.key.label}@${held.offset}")
        return flush()
    }

    // Inbound (each returns true when the message belonged to the sync)

    fun handleStatus(original: Int, status: Int, detail: GarminStatusDetail, nowMs: Long): Pair<Boolean, List<GarminSyncAction>> {
        now = nowMs
        val type = GarminMessageType.fromRaw(original)
        return when {
            type == GarminMessageType.FILTER && stage == Stage.AwaitingFilter -> {
                note(false, "filter acknowledged status=$status")
                requestDirectory(request?.flow != GarminSyncFlow.Legacy, nowMs)
                true to flush()
            }
            type == GarminMessageType.DOWNLOAD_REQUEST && stage == Stage.AwaitingDownloadStatus -> {
                downloadStatus(status, detail, nowMs)
                true to flush()
            }
            type == GarminMessageType.PROTOBUF_REQUEST && (stage is Stage.AwaitingList || stage is Stage.AwaitingPull) -> {
                if (status != GarminStatus.ACK.raw) {
                    note(true, "watch refused FileAccess request status=$status")
                    finish(GarminSyncSummary.Outcome.Failed("FileAccess request refused status=$status"))
                }
                true to flush()
            }
            else -> false to emptyList()
        }
    }

    fun handleChunk(flags: Int, runningCrc: Int, offset: Long, data: ByteArray, nowMs: Long): Pair<Boolean, List<GarminSyncAction>> {
        now = nowMs
        var t = transfer
        if (stage != Stage.Receiving || t == null) return false to emptyList()
        touch(nowMs)
        if (t.compressed) {
            if (t.stream.size != 0) {
                finishBadStream("5004 chunk inside a compressed transfer")
                return true to flush()
            }
            note(true, "watch sent ${describe(t.target)} uncompressed")
            t.compressed = false
        }
        val from = t.resumedFrom
        if (from != null && offset == 0L && t.data.size.toLong() == from) {
            note(true, "watch restarted ${describe(t.target)} from 0 instead of continuing at $from")
            var size = t.reported
            val target = t.target
            if (target is Target.File) size = maxOf(size, target.key.size)
            t = Transfer(target = t.target, expected = size, compressed = false, resumedFrom = null)
            t.reported = size
            t.sizeKnown = true
            summary.resumed -= 1
        }
        val have = t.data.size.toLong()
        if (offset != have) {
            t.badChunks += 1
            note(true, "chunk at offset $offset, expected $have; asking for $have")
            transfer = t
            if (t.badChunks >= MAX_BAD_CHUNKS) abortTransfer("offset mismatch")
            else send(GarminOutbound.FileTransferDataReply(TRANSFER_OFFSET_MISMATCH, have))
            return true to flush()
        }
        val computed = GarminCrc.crc16(data, t.crc)
        if (computed != runningCrc) {
            t.badChunks += 1
            note(true, "chunk crc $runningCrc != $computed at offset $offset; asking again")
            transfer = t
            if (t.badChunks >= MAX_BAD_CHUNKS) abortTransfer("crc mismatch")
            else send(GarminOutbound.FileTransferDataReply(TRANSFER_CRC_MISMATCH, have))
            return true to flush()
        }
        t.badChunks = 0
        t.crc = computed
        t.data.append(data)
        summary.bytes += data.size
        if (have == (t.resumedFrom ?: 0L)) {
            note(false, "first chunk ${data.size} bytes flags=$flags head=${garminHex(data.copyOf(minOf(8, data.size)))}")
        }
        transfer = t
        send(GarminOutbound.FileTransferDataReply(TRANSFER_OK, t.data.size.toLong()))
        if (t.data.size >= t.expected) completeTransfer(nowMs)
        return true to flush()
    }

    fun handleCompressedChunk(
        sequence: Int, flags: Int, checkpoint: Long, checkpointCrc: Int, data: ByteArray, nowMs: Long,
    ): Pair<Boolean, List<GarminSyncAction>> {
        now = nowMs
        val t = transfer
        if (stage != Stage.Receiving || t == null || !t.compressed) return false to emptyList()
        touch(nowMs)
        val due = t.pieces and 0xFF
        if (sequence != due) {
            finishBadStream("compressed piece $sequence where $due was due")
            return true to flush()
        }
        t.pieces += 1
        t.stream.append(data)
        t.received += data.size
        if (checkpoint > 0) t.checkpoints.add(checkpoint to checkpointCrc)
        summary.bytes += data.size
        send(GarminOutbound.FileTransferDataReply(TRANSFER_OK, t.received.toLong()))
        if (flags and COMPRESSED_LAST_FLAG == 0) return true to flush()
        val inflated = GarminInflate.zlib(t.stream.toByteArray(), t.expected.toInt())
        if (inflated == null) {
            finishBadStream("last piece arrived but the stream does not inflate")
            return true to flush()
        }
        if (inflated.size.toLong() != t.expected || checkpoint != t.expected) {
            finishBadStream("stream inflated to ${inflated.size} bytes, watch announced ${t.expected} (last checkpoint $checkpoint)")
            return true to flush()
        }
        for ((offset, crc) in t.checkpoints) {
            if (GarminCrc.crc16(inflated.copyOf(offset.toInt())) != crc) {
                finishBadStream("checkpoint $offset CRC does not match the inflated file")
                return true to flush()
            }
        }
        val done = Transfer(t.target, t.expected, inflated, t.crc, t.compressed, t.resumedFrom)
        done.received = t.received
        transfer = done
        completeTransfer(nowMs)
        return true to flush()
    }

    private fun checkedPrefix(t: Transfer): ByteArray {
        val prefix = GarminInflate.zlibPrefix(t.stream.toByteArray(), t.expected.toInt())
        for ((offset, crc) in t.checkpoints.asReversed()) {
            if (offset > prefix.size) continue
            val checked = prefix.copyOf(offset.toInt())
            return if (GarminCrc.crc16(checked) == crc) checked else ByteArray(0)
        }
        return ByteArray(0)
    }

    private fun finishBadStream(why: String) {
        val t = transfer ?: return
        note(true, "${describe(t.target)}: $why; giving it up for this sync")
        send(GarminOutbound.FileTransferDataReply(TRANSFER_ABORT, t.received.toLong()))
        transfer = null
        summary.failed += 1
        nextFile(now)
    }

    fun handleChunkedResponse(chunk: GarminProtobufChunk, nowMs: Long): Pair<Boolean, List<GarminSyncAction>> {
        now = nowMs
        val s = stage
        val ours = when (s) {
            is Stage.AwaitingList -> chunk.requestId == s.requestId
            is Stage.AwaitingPull -> chunk.requestId == s.requestId
            else -> false
        }
        if (!ours) return false to emptyList()
        touch(nowMs)
        send(GarminOutbound.ProtobufStatus(GarminProtobufDirection.RESPONSE, chunk.requestId, chunk.dataOffset, discard = false, code = 0))
        var buffer = if (chunk.dataOffset == 0L) ByteArray(0) else fragments[chunk.requestId] ?: ByteArray(0)
        if (buffer.size.toLong() != chunk.dataOffset) {
            finish(GarminSyncSummary.Outcome.Failed("FileAccess response chunk out of order"))
            return true to flush()
        }
        buffer += chunk.data
        if (buffer.size < chunk.totalLength) {
            fragments[chunk.requestId] = buffer
            return true to flush()
        }
        fragments.remove(chunk.requestId)
        val message = GarminFileAccessMessage.decodeSmart(buffer)
        if (message == null) {
            finish(GarminSyncSummary.Outcome.Failed("undecodable FileAccess response"))
            return true to flush()
        }
        fileAccessResponse(message, nowMs)
        return true to flush()
    }

    fun handleRegistered(service: Int, status: Int, handle: Int, nowMs: Long): Pair<Boolean, List<GarminSyncAction>> {
        now = nowMs
        if (stage != Stage.RegisteringPipe || service != PIPE_SERVICE.raw) return false to emptyList()
        touch(nowMs)
        if (status != 0) {
            note(true, "file-transfer service registration refused status=$status")
            itemFailed("pipe refused")
            return true to flush()
        }
        stage = Stage.ReceivingPipe(handle)
        pipeData = GarminByteBuffer()
        pipeStarted = false
        actions.add(GarminSyncAction.Pipe(GarminPipeStartRequest(pullTransferHandle), handle))
        note(false, "-> pipe start transfer=$pullTransferHandle on handle $handle")
        return true to flush()
    }

    fun handlePipeData(handle: Int, payload: ByteArray, nowMs: Long): Pair<Boolean, List<GarminSyncAction>> {
        now = nowMs
        val s = stage
        if (s !is Stage.ReceivingPipe || s.mlHandle != handle) return false to emptyList()
        touch(nowMs)
        if (!pipeStarted) {
            if (payload.size < 3 || payload[0] != 0.toByte() || payload[1] != 0.toByte() || payload[2] != 0.toByte()) {
                note(true, "pipe opened with ${garminHex(payload.copyOf(minOf(8, payload.size)))}, not an acknowledgement")
                actions.add(GarminSyncAction.Control(GarminMultiLink.ControlRequest.CloseHandle(PIPE_SERVICE, handle)))
                itemFailed("pipe start refused")
                return true to flush()
            }
            note(false, "pipe acknowledged ${garminHex(payload)}")
            pipeStarted = true
            return true to flush()
        }
        pipeData.append(payload)
        summary.bytes += payload.size
        return true to flush()
    }

    fun handleClosed(service: Int, handle: Int, nowMs: Long): Pair<Boolean, List<GarminSyncAction>> {
        now = nowMs
        val s = stage
        if (s !is Stage.ReceivingPipe || s.mlHandle != handle || service != PIPE_SERVICE.raw) return false to emptyList()
        touch(nowMs)
        val key = currentItem ?: return true to flush()
        val inflated = GarminInflate.zlib(pipeData.toByteArray())
        if (inflated == null) {
            note(true, "FileAccess item ${key.label}: ${pipeData.size} bytes did not inflate")
            itemFailed("inflate")
            return true to flush()
        }
        val name = itemTypeNames[key]
        summary.downloaded += 1
        note(true, "downloaded ${name ?: "item"} ${key.label} (${pipeData.size} -> ${inflated.size} bytes)")
        actions.add(GarminSyncAction.File(GarminDownloadedFile(key, inflated, null, name)))
        currentItem = null
        pullNext(nowMs)
        return true to flush()
    }

    // Legacy flow

    private fun requestDirectory(flushOnly: Boolean, nowMs: Long) {
        transfer = Transfer(Target.Directory(flushOnly), compressed = false, resumedFrom = null)
        send(GarminOutbound.DownloadRequest(0, 0, GarminDownloadKind.NEW, 0, 0, 0))
        stage = Stage.AwaitingDownloadStatus
        touch(nowMs)
    }

    private fun requestFile(key: GarminSyncFileKey, nowMs: Long) {
        val compress = request?.requestCompression ?: false
        val resume = request?.resume
        if (resume != null && resume.key == key && key !in resumeFailed && resume.offset > 0) {
            transfer = Transfer(Target.File(key), data = resume.data, crc = resume.runningCrc, compressed = false,
                resumedFrom = resume.offset)
            note(true, "resuming ${key.label} from offset ${resume.offset}")
            send(GarminOutbound.DownloadRequest(key.fileIndex, resume.offset, GarminDownloadKind.CONTINUE,
                resume.runningCrc, key.size - minOf(key.size, resume.offset), 0))
        } else {
            transfer = Transfer(Target.File(key), compressed = compress, resumedFrom = null)
            send(GarminOutbound.DownloadRequest(key.fileIndex, 0, GarminDownloadKind.NEW, 0, 0, if (compress) 1 else 0))
        }
        stage = Stage.AwaitingDownloadStatus
        touch(nowMs)
    }

    private fun downloadStatus(status: Int, detail: GarminStatusDetail, nowMs: Long) {
        val t = transfer ?: return
        var downloadStatus = 0xFF
        var size = 0L
        if (detail is GarminStatusDetail.DownloadRequest) {
            downloadStatus = detail.downloadStatus
            size = detail.maxFileSize
        }
        if (status != GarminStatus.ACK.raw || downloadStatus != 0) {
            note(true, "download of ${describe(t.target)} refused status=$status download=$downloadStatus")
            when (val target = t.target) {
                is Target.Directory -> finish(GarminSyncSummary.Outcome.Failed("directory refused status=$status download=$downloadStatus"))
                is Target.File -> if (t.resumedFrom != null) {
                    resumeFailed.add(target.key)
                    note(true, "resume refused; restarting ${target.key.label} from 0")
                    requestFile(target.key, nowMs)
                } else {
                    summary.failed += 1
                    nextFile(nowMs)
                }
            }
            return
        }
        t.expected = size + (t.resumedFrom ?: 0L)
        t.reported = size
        t.sizeKnown = true
        val target = t.target
        if (target is Target.File && t.resumedFrom == null && !t.compressed && size != target.key.size) {
            note(true, "${target.key.label}: watch reports $size bytes")
        }
        val from = t.resumedFrom
        if (from != null) {
            summary.resumed += 1
            note(true, "watch continues ${describe(t.target)} at $from: $size bytes left")
        } else {
            note(false, "download ${describe(t.target)} size=$size")
        }
        stage = Stage.Receiving
        touch(nowMs)
        if (t.data.size >= t.expected) completeTransfer(nowMs)
    }

    private fun completeTransfer(nowMs: Long) {
        val t = transfer ?: return
        transfer = null
        when (val target = t.target) {
            is Target.Directory -> {
                val data = t.data.toByteArray()
                val entries = GarminDirectoryEntry.parseDirectory(data)
                if (entries == null) {
                    finish(GarminSyncSummary.Outcome.Failed("directory of ${data.size} bytes is not whole 16-byte entries"))
                    return
                }
                if (target.flushOnly) {
                    note(false, "legacy directory flushed (${entries.size} entries); listing items")
                    requestList(nowMs)
                    return
                }
                planLegacy(entries)
                nextFile(nowMs)
            }
            is Target.File -> {
                val key = target.key
                val bytes = t.data.toByteArray()
                val fitCrcOk = bytes.size < 14 || GarminCrc.crc16(bytes) == 0
                val noCrc = bytes.size >= 2 && bytes[bytes.size - 2] == 0.toByte() && bytes[bytes.size - 1] == 0.toByte()
                if (!(fitCrcOk || noCrc)) {
                    note(true, "${key.label}: FIT CRC does not check (${bytes.size} bytes); dropped")
                    summary.failed += 1
                    if (request?.resume?.key == key) request = request?.copy(resume = null)
                    nextFile(nowMs)
                    return
                }
                summary.downloaded += 1
                note(true, "downloaded ${GarminFitFileType.fromRaw(key.subType)?.wireName ?: "file"} ${key.label} " +
                    "(${bytes.size} bytes${if (t.compressed) " from ${t.received} compressed" else ""}" +
                    "${t.resumedFrom?.let { ", resumed at $it" } ?: ""}${if (fitCrcOk) "" else ", no FIT CRC"})")
                actions.add(GarminSyncAction.File(GarminDownloadedFile(key, bytes, t.resumedFrom, null)))
                if (request?.resume?.key == key) request = request?.copy(resume = null)
                nextFile(nowMs)
            }
        }
    }

    private fun planLegacy(entries: List<GarminDirectoryEntry>) {
        val request = this.request ?: return
        summary.listed = entries.size
        val wanted = ArrayList<GarminSyncFileKey>()
        val counts = HashMap<String, Int>()
        for (entry in entries) {
            val k = "${entry.dataType}/${entry.subType}"
            counts[k] = (counts[k] ?: 0) + 1
            if (entry.dataType != GarminFitFileType.FIT_DATA_TYPE || entry.subType !in GarminFitFileType.WANTED_SUB_TYPES) continue
            summary.wanted += 1
            val key = GarminSyncFileKey(entry)
            summary.onWatch = summary.onWatch + key
            if (key in request.synced) { summary.alreadySynced += 1; continue }
            if (entry.fileSize == 0L) { summary.alreadySynced += 1; continue }
            val floor = request.notBeforeUnix
            val unix = entry.unixTime
            if (floor != null && unix != null && unix < floor && request.resume?.key != key) { summary.skippedOld += 1; continue }
            if (entry.fileSize > request.maxFileBytes) { summary.skippedLarge += 1; continue }
            wanted.add(key)
        }
        val types = counts.keys.sorted().joinToString(" ") { "${it}x${counts[it]}" }
        note(true, "directory: ${entries.size} entries [$types]; ${wanted.size} to download")
        wanted.sort()
        val resume = request.resume
        val i = if (resume != null) wanted.indexOf(resume.key) else -1
        if (resume != null && i >= 0) {
            wanted.add(0, wanted.removeAt(i))
        } else if (resume != null) {
            note(true, "resume point ${resume.key.label} is no longer listed; dropped")
            this.request = this.request?.copy(resume = null)
        }
        queue = wanted
    }

    private fun nextFile(nowMs: Long) {
        if (queue.isEmpty()) {
            finish(GarminSyncSummary.Outcome.Completed)
            return
        }
        requestFile(queue.removeAt(0), nowMs)
    }

    private fun abortTransfer(why: String) {
        val t = transfer ?: return
        send(GarminOutbound.FileTransferDataReply(TRANSFER_ABORT, t.data.size.toLong()))
        note(true, "gave up ${describe(t.target)} after $MAX_BAD_CHUNKS bad chunks ($why)")
        transfer = null
        when (t.target) {
            is Target.Directory -> finish(GarminSyncSummary.Outcome.Failed("directory transfer: $why"))
            is Target.File -> {
                summary.failed += 1
                nextFile(now)
            }
        }
    }

    // FileAccess flow

    private fun requestList(nowMs: Long) {
        val id = takeRequestId()
        send(GarminOutbound.FileAccessRequest(id, GarminFileAccessRequest.ItemList(
            sessionId = listSession, transactionId = null, maxItemCount = null,
            excludeAllFlags = emptyList(), getFlags = listOf(SYNCED_FLAG),
        )))
        stage = Stage.AwaitingList(id)
        touch(nowMs)
    }

    private fun fileAccessResponse(message: GarminFileAccessMessage, nowMs: Long) {
        val s = stage
        if (message is GarminFileAccessMessage.ItemListResponse && s is Stage.AwaitingList) {
            val status = message.status
            if (status != null && status != 0L) {
                note(true, "item list status=$status error=${message.errorCode?.toString() ?: "none"}")
            }
            planItems(message.items)
            listSession = message.sessionId
            if (message.sessionId != null) {
                requestList(nowMs)
            } else {
                note(true, "item list: ${summary.listed} items; ${queue.size} to download")
                pullNext(nowMs)
            }
        } else if (message is GarminFileAccessMessage.PullItemResponse && s is Stage.AwaitingPull) {
            val result = message.result
            val handle = message.transferHandle
            if (!(result == null || result == 0L) || handle == null) {
                note(true, "pull refused result=${result?.toString() ?: "none"} error=${message.errorCode?.toString() ?: "none"}")
                itemFailed("pull refused")
                return
            }
            pullTransferHandle = (handle and 0xFFFF).toInt()
            stage = Stage.RegisteringPipe
            actions.add(GarminSyncAction.Control(GarminMultiLink.ControlRequest.Register(PIPE_SERVICE, false)))
            note(false, "-> ML register file transfer for transfer handle $handle")
        } else {
            note(true, "unexpected FileAccess message in $stageLabel")
        }
    }

    private fun planItems(list: List<GarminItemReference>) {
        val request = this.request ?: return
        itemNames = HashMap()
        for (item in list) {
            summary.listed += 1
            val k = item.dataType?.typeNameKey
            val n = item.dataType?.typeName
            if (k != null && n != null) itemNames[k] = n
            val name = typeName(item)
            note(false, "item ${name ?: "untyped"} uid=${item.uid?.let { garminHex64(it.mostSignificant) + ":" + garminHex64(it.leastSignificant) } ?: "-"} " +
                "size=${item.dataSize?.toString() ?: "-"} mtime=${item.modifiedTime?.toString() ?: "-"} " +
                "flags=${item.requestedFlags?.toString() ?: "-"} format=${item.dataType?.format?.toString() ?: "-"} " +
                "key=${item.dataType?.typeNameKey?.toString() ?: "-"} id=${item.dataType?.fileIdentifier ?: "-"}")
            val sub = GarminSyncFileKey.fitSubType(name) ?: continue
            if (sub !in GarminFitFileType.WANTED_SUB_TYPES) continue
            val key = GarminSyncFileKey.fileAccess(item, name) ?: continue
            summary.wanted += 1
            summary.onWatch = summary.onWatch + key
            if (key in request.synced) { summary.alreadySynced += 1; continue }
            val floor = request.notBeforeUnix
            if (floor != null && key.garminTimestamp != 0L && GarminGfdi.unixTime(key.garminTimestamp) < floor) {
                summary.skippedOld += 1; continue
            }
            if (key.size > request.maxFileBytes) { summary.skippedLarge += 1; continue }
            items[key] = item
            if (name != null) itemTypeNames[key] = name
            queue.add(key)
        }
        queue.sort()
    }

    private fun typeName(item: GarminItemReference): String? {
        val name = item.dataType?.typeName
        if (name != null && name.isNotEmpty()) return name
        val key = item.dataType?.typeNameKey ?: return null
        return itemNames[key]
    }

    private fun pullNext(nowMs: Long) {
        while (true) {
            if (queue.isEmpty()) {
                finish(GarminSyncSummary.Outcome.Completed)
                return
            }
            val key = queue.removeAt(0)
            val item = items[key] ?: continue
            currentItem = key
            val id = takeRequestId()
            send(GarminOutbound.FileAccessRequest(id, GarminFileAccessRequest.PullItem(item, PULL_PRIORITY, 0, PULL_COMPRESSION_WINDOW)))
            stage = Stage.AwaitingPull(id)
            touch(nowMs)
            return
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun itemFailed(why: String) {
        summary.failed += 1
        currentItem = null
        pullNext(now)
    }

    private fun takeRequestId(): Int {
        val id = nextRequestId
        nextRequestId = if (nextRequestId == 0xFFFF) 1 else nextRequestId + 1
        return id
    }

    // Plumbing

    private fun finish(outcome: GarminSyncSummary.Outcome, sendComplete: Boolean = true) {
        summary.outcome = outcome
        summary.onWatch = summary.onWatch.sorted()
        if (sendComplete) send(GarminOutbound.SystemEvent(GarminSafeSystemEvent.SYNC_COMPLETE, 0))
        note(true, summary.line)
        actions.add(GarminSyncAction.Finished(summary.copy()))
        actions.add(GarminSyncAction.TransferActive(false))
        stage = Stage.Idle
        transfer = null
        queue = ArrayList()
        currentItem = null
        pipeData = GarminByteBuffer()
        deadlineMs = null
        request = null
    }

    private fun touch(nowMs: Long) { deadlineMs = nowMs + STALL_TIMEOUT_MS }

    private fun send(message: GarminOutbound) { actions.add(GarminSyncAction.Send(message)) }

    private fun note(rare: Boolean, text: String) { actions.add(GarminSyncAction.Log(rare, text)) }

    private fun flush(): List<GarminSyncAction> {
        val copy = actions.toList()
        actions.clear()
        return copy
    }

    private fun describe(target: Target): String = when (target) {
        is Target.Directory -> "directory"
        is Target.File -> target.key.label
    }

    private val stageLabel: String
        get() = when (stage) {
            Stage.Idle -> "idle"
            Stage.AwaitingFilter -> "awaiting-filter"
            Stage.AwaitingDownloadStatus -> "awaiting-download-status"
            Stage.Receiving -> "receiving"
            is Stage.AwaitingList -> "awaiting-list"
            is Stage.AwaitingPull -> "awaiting-pull"
            Stage.RegisteringPipe -> "registering-pipe"
            is Stage.ReceivingPipe -> "receiving-pipe"
        }

    companion object {
        const val STALL_TIMEOUT_MS = 20_000L
        const val MAX_BAD_CHUNKS = 3
        const val TRANSFER_OK = 0
        const val TRANSFER_ABORT = 2
        const val TRANSFER_CRC_MISMATCH = 3
        const val TRANSFER_OFFSET_MISMATCH = 4
        const val COMPRESSED_LAST_FLAG = 0x02
        val SYNCED_FLAG = GarminUid(42_405, 42_405)
        const val PULL_PRIORITY = 24L
        const val PULL_COMPRESSION_WINDOW = 15L
        val PIPE_SERVICE = GarminService.FILE_TRANSFER_2
    }
}
