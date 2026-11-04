package com.noop.huawei

import com.noop.huawei.protocol.HuaweiCommand
import com.noop.huawei.protocol.HuaweiLink
import com.noop.huawei.protocol.HuaweiProtocolException
import com.noop.huawei.protocol.HuaweiRawPush
import com.noop.huawei.protocol.HuaweiRequest
import com.noop.huawei.protocol.HuaweiTlv
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream

// Band → phone file download, read-only: NOOP asks for a named file and the band streams it. Nothing is
// written to the band and no file is deleted from it.
//
// Protocol facts (re-derived, no code copied) from Gadgetbridge `devices/huawei/packets/FileDownloadService2C.java`,
// `FileDownloadService0A.java` and `service/devices/huawei/HuaweiFileDownloadManager.java` (AGPL-3.0):
//
// 0x2C ("new sync"): 01 init {01 name, 02 type u8, 05 start u32, 06 end u32, [0C dictId u32 for type 0x16]}
// → {03 fileId u8, 04 size u32; 0 = nothing}; 03 info {01 fileId, empty 02..05} → {02 timeout s, 03 burst,
// 04 maxBlock u32, 05 noEncrypt}; 04 block {01 fileId, 02 offset u32, 03 size u32, [04 dictId]} (sent in
// plaintext when noEncrypt) answered by raw 05 packets of `fileId u8 | offset u32 | u8 | data`; 06 done
// {01 fileId, 02 status u8 (1 ok)} is not answered.
//
// 0x0A ("old sync"): 01 init (type-specific body) → {01 `;`-separated filenames}; 02 params {06 type} →
// {04 maxBlock u16, 05 timeout u16}; 03 info {01 name} → {02 length u32}; 04 block {01 name, 02 offset u32,
// 03 size u32} answered by raw 05 packets of `seq u8 | data` (seq restarts at 0 every block); 06 done {06 true}.
//
// Blocks are min(remaining, maxBlock) long; the next block is asked for once the current one is complete.
// Kotlin-only; Swift twin deferred (fork).

/** What to ask the band for. [type] is the 0x2C file type byte; [oldSyncType] the 0x0A init type (null = no 0x0A path). */
data class HuaweiFileRequest(
    val name: String,
    val type: Int,
    val startTs: Long = 0,
    val endTs: Long = 0,
    val dictId: Int = 0,
    val oldSyncType: Int? = null,
) {
    companion object {
        const val TYPE_SLEEP_STATE = 0x0E
        const val TYPE_SLEEP_DATA = 0x0F
        const val TYPE_RRI = 0x10
        const val TYPE_GPS = 0x11
        const val TYPE_SEQUENCE_DATA = 0x16

        /** 0x0A init types. */
        const val OLD_SLEEP = 0x01
        const val OLD_RRI = 0x04

        /** The dictionary id of the TruSleep `sequence_data` file. */
        const val DICT_SLEEP = 700_013
    }
}

/** Why a download produced no bytes. */
sealed class HuaweiFileOutcome {
    class Downloaded(val bytes: ByteArray) : HuaweiFileOutcome()

    /** The band reported nothing for the range (size 0 or not in its file list). */
    object Empty : HuaweiFileOutcome()

    /** The download stopped; [reason] says where, from what NOOP itself observed. */
    data class Failed(val reason: String) : HuaweiFileOutcome()
}

class HuaweiFileDownload(
    private val link: HuaweiLink,
    private val replyTimeoutMs: Long = DEFAULT_REPLY_TIMEOUT_MS,
) {

    /** Downloads [file] over 0x2C when [newSync], else over 0x0A when the file has an old-sync type. */
    suspend fun download(file: HuaweiFileRequest, newSync: Boolean): HuaweiFileOutcome = try {
        when {
            newSync -> download2C(file)
            file.oldSyncType != null -> download0A(file)
            else -> HuaweiFileOutcome.Failed("${file.name} has no 0x0A download path")
        }
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        HuaweiFileOutcome.Failed("${file.name}: no reply within the timeout")
    } catch (e: UnsupportedOperationException) {
        HuaweiFileOutcome.Failed("${file.name}: the link cannot stream file blocks")
    } catch (e: HuaweiProtocolException) {
        HuaweiFileOutcome.Failed("${file.name}: ${e.message}")
    }

    private suspend fun ask(command: HuaweiCommand, tlv: HuaweiTlv): HuaweiTlv =
        withTimeout(replyTimeoutMs) { link.request(HuaweiRequest.of(command, tlv)) }

    private suspend fun download2C(file: HuaweiFileRequest): HuaweiFileOutcome {
        val init = HuaweiTlv().putString(0x01, file.name).putByte(0x02, file.type)
            .putInt(0x05, file.startTs.toInt()).putInt(0x06, file.endTs.toInt())
        val sequence = file.type == HuaweiFileRequest.TYPE_SEQUENCE_DATA && file.dictId > 0
        if (sequence) init.putInt(0x0C, file.dictId)
        val reply = ask(HuaweiCommand.FILE2C_INIT, init)
        val size = if (reply.contains(0x04)) reply.unsigned(0x04) else 0L
        val fileId = if (reply.contains(0x03)) reply.byte(0x03) else return HuaweiFileOutcome.Empty
        if (size <= 0L) {
            link.send(HuaweiRequest.of(HuaweiCommand.FILE2C_DONE, HuaweiTlv().putByte(0x01, fileId).putByte(0x02, STATUS_OK)))
            return HuaweiFileOutcome.Empty
        }
        if (size > MAX_FILE_BYTES) return HuaweiFileOutcome.Failed("${file.name}: announced size $size over $MAX_FILE_BYTES")
        val info = ask(HuaweiCommand.FILE2C_INFO, HuaweiTlv().putByte(0x01, fileId).put(0x02).put(0x03).put(0x04).put(0x05))
        val maxBlock = if (info.contains(0x04)) info.unsigned(0x04).toInt() else 0
        if (maxBlock <= 0) return HuaweiFileOutcome.Failed("${file.name}: band announced no block size")
        val timeoutMs = blockTimeout(if (info.contains(0x02)) info.byte(0x02).toLong() else 0L)
        val plain = info.contains(0x05) && info.boolean(0x05)
        val blockCommand = if (plain) HuaweiCommand.FILE2C_BLOCK_PLAIN else HuaweiCommand.FILE2C_BLOCK

        val bytes = stream(size.toInt(), maxBlock, timeoutMs, 0x2C,
            requestBlock = { offset, length ->
                val t = HuaweiTlv().putByte(0x01, fileId).putInt(0x02, offset).putInt(0x03, length)
                if (sequence) t.putInt(0x04, file.dictId)
                link.send(HuaweiRequest.of(blockCommand, t))
            },
            payload = { body ->
                if (body.size < 6) throw HuaweiProtocolException("2C block of ${body.size} bytes")
                if (body[0].toInt() and 0xFF != fileId) throw HuaweiProtocolException("2C block for file ${body[0].toInt() and 0xFF}, expected $fileId")
                body.copyOfRange(6, body.size)
            },
        )
        link.send(HuaweiRequest.of(HuaweiCommand.FILE2C_DONE, HuaweiTlv().putByte(0x01, fileId).putByte(0x02, STATUS_OK)))
        return HuaweiFileOutcome.Downloaded(bytes)
    }

    private suspend fun download0A(file: HuaweiFileRequest): HuaweiFileOutcome {
        val type = file.oldSyncType ?: return HuaweiFileOutcome.Failed("${file.name} has no 0x0A type")
        val range = HuaweiTlv().putInt(rangeTags(type).first, file.startTs.toInt()).putInt(rangeTags(type).second, file.endTs.toInt())
        val init = HuaweiTlv().putByte(0x02, type).put(rangeContainer(type), range)
        val names = ask(HuaweiCommand.FILE0A_INIT, init).bytesOrNull(0x01)?.toString(Charsets.UTF_8)
            ?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        if (file.name !in names) return HuaweiFileOutcome.Empty
        val params = ask(HuaweiCommand.FILE0A_PARAMS, HuaweiTlv().putByte(0x06, type))
        val maxBlock = if (params.contains(0x04)) params.unsigned(0x04).toInt() else 0
        if (maxBlock <= 0) return HuaweiFileOutcome.Failed("${file.name}: band announced no block size")
        val timeoutMs = blockTimeout(if (params.contains(0x05)) params.unsigned(0x05) else 0L)
        val info = ask(HuaweiCommand.FILE0A_INFO, HuaweiTlv().putString(0x01, file.name))
        val size = if (info.contains(0x02)) info.unsigned(0x02) else 0L
        if (size <= 0L) return HuaweiFileOutcome.Empty
        if (size > MAX_FILE_BYTES) return HuaweiFileOutcome.Failed("${file.name}: announced size $size over $MAX_FILE_BYTES")

        var expectedSeq = 0
        val bytes = stream(size.toInt(), maxBlock, timeoutMs, 0x0A,
            requestBlock = { offset, length ->
                expectedSeq = 0
                link.send(HuaweiRequest.of(HuaweiCommand.FILE0A_BLOCK,
                    HuaweiTlv().putString(0x01, file.name).putInt(0x02, offset).putInt(0x03, length)))
            },
            payload = { body ->
                if (body.isEmpty()) throw HuaweiProtocolException("empty 0A block")
                val seq = body[0].toInt() and 0xFF
                if (seq != expectedSeq) throw HuaweiProtocolException("0A block sequence $seq, expected $expectedSeq")
                expectedSeq = (expectedSeq + 1) and 0xFF
                body.copyOfRange(1, body.size)
            },
        )
        link.send(HuaweiRequest.of(HuaweiCommand.FILE0A_DONE, HuaweiTlv().putBoolean(0x06, true)))
        return HuaweiFileOutcome.Downloaded(bytes)
    }

    /**
     * One file's block loop over 05 packets of [service]: asks for the first block, appends each packet's
     * [payload], and asks for the next block once the current one holds `maxBlock` bytes. Each packet must
     * arrive within [timeoutMs]. Returns the first [size] bytes.
     */
    private suspend fun stream(
        size: Int,
        maxBlock: Int,
        timeoutMs: Long,
        service: Int,
        requestBlock: suspend (offset: Int, length: Int) -> Unit,
        payload: (ByteArray) -> ByteArray,
    ): ByteArray = coroutineScope {
        val inbox = Channel<HuaweiRawPush>(Channel.UNLIMITED)
        // Subscribed before the first block request, so no packet can slip past.
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            link.rawPushes.filter { it.serviceId == service && it.commandId == 0x05 }.collect { inbox.send(it) }
        }
        val out = ByteArrayOutputStream(size)
        try {
            var blockStart = 0
            requestBlock(0, minOf(size, maxBlock))
            while (out.size() < size) {
                val push = withTimeout(timeoutMs) { inbox.receive() }
                out.write(payload(push.body))
                if (out.size() < size && out.size() - blockStart >= maxBlock) {
                    blockStart = out.size()
                    requestBlock(blockStart, minOf(size - blockStart, maxBlock))
                }
            }
        } finally {
            collector.cancel()
        }
        // A band that sends more than it announced: keep the announced length.
        out.toByteArray().copyOf(size)
    }

    private fun blockTimeout(bandSeconds: Long): Long =
        if (bandSeconds in 1..MAX_BAND_TIMEOUT_S) bandSeconds * 1000 else DEFAULT_BLOCK_TIMEOUT_MS

    companion object {
        const val DEFAULT_REPLY_TIMEOUT_MS = 10_000L
        const val DEFAULT_BLOCK_TIMEOUT_MS = 10_000L
        private const val MAX_BAND_TIMEOUT_S = 60L
        const val STATUS_OK = 1

        /** No band history file is near this; a larger announced size is refused rather than allocated. */
        const val MAX_FILE_BYTES = 16L * 1024 * 1024

        /** 0x0A init: the range container tag of each type and its start/end tags. */
        fun rangeContainer(type: Int): Int = if (type == HuaweiFileRequest.OLD_RRI) 0x8F else 0x83

        fun rangeTags(type: Int): Pair<Int, Int> = if (type == HuaweiFileRequest.OLD_RRI) 0x10 to 0x11 else 0x04 to 0x05
    }
}
