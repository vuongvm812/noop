package com.noop.ble

import com.noop.huawei.protocol.HuaweiLink
import com.noop.huawei.protocol.HuaweiLinkConfig
import com.noop.huawei.protocol.HuaweiPacketCodec
import com.noop.huawei.protocol.HuaweiProtocolException
import com.noop.huawei.protocol.HuaweiFraming
import com.noop.huawei.protocol.HuaweiPush
import com.noop.huawei.protocol.HuaweiRawPush
import com.noop.huawei.protocol.HuaweiRequest
import com.noop.huawei.protocol.HuaweiTlv
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** A request that got no reply within the link's own ceiling. */
class HuaweiLinkTimeoutException(message: String) : HuaweiProtocolException(message)

/** The link went down (or was closed) while a request was outstanding. */
class HuaweiLinkClosedException(message: String) : HuaweiProtocolException(message)

/**
 * [HuaweiLink] over one GATT connection: the band's FE01 write and FE02 notify characteristics.
 *
 * Requests go out one at a time: each is sliced at the [configure]d slice size by [HuaweiPacketCodec] and
 * handed to [write] slice by slice (the radio layer queues them in order). Notifications are reassembled by
 * the same codec; a completed message whose service/command id matches the request in flight answers it,
 * and everything else (the 01/27 battery push, a late reply) goes to [pushes]. Raw file blocks (2C/05,
 * 0A/05) are opened and go to [rawPushes]; [send] writes a request that is answered on another id. A reply that fails its CRC
 * or decryption is dropped with a log line, so the request it belonged to times out rather than being
 * answered with garbage.
 *
 * [requestTimeoutMs] is a ceiling, not the per-step timeout: callers apply their own (5 s for a read, 30 s
 * while the user confirms a first pairing on the band), so the ceiling sits above the longest of those.
 */
class HuaweiGattLink(
    private val write: (ByteArray) -> Boolean,
    private val log: (String) -> Unit = {},
    private val requestTimeoutMs: Long = DEFAULT_CEILING_MS,
) : HuaweiLink {

    private val codec = HuaweiPacketCodec()
    private val sendLock = Mutex()
    private val stateLock = Any()
    private var inFlight: Pending? = null
    private var closedReason: String? = null
    private val pushFlow = MutableSharedFlow<HuaweiPush>(extraBufferCapacity = 64)
    /** Sized for a file block's burst of 05 messages arriving before the sync engine drains them. */
    private val rawFlow = MutableSharedFlow<HuaweiRawPush>(extraBufferCapacity = 1024)

    private class Pending(val serviceId: Int, val commandId: Int, val reply: CompletableDeferred<HuaweiTlv>)

    override val pushes: SharedFlow<HuaweiPush> = pushFlow.asSharedFlow()

    override val rawPushes: SharedFlow<HuaweiRawPush> = rawFlow.asSharedFlow()

    /** The slice size and cipher currently applied (Test Centre readout). */
    val config: HuaweiLinkConfig get() = codec.config

    val isClosed: Boolean get() = synchronized(stateLock) { closedReason != null }

    /** The largest slice one FE01 write carries (ATT MTU minus its 3-byte header); null until [capSliceToMtu]. */
    @Volatile
    private var sliceCap: Int? = null

    override fun configure(config: HuaweiLinkConfig) {
        codec.config = capped(config)
    }

    /**
     * Caps every later slice at the negotiated ATT [mtu], so one slice is one write: the band's LinkParams
     * slice size (0xF4 by default) is larger than a 23-byte default MTU leaves room for. Never below
     * [HuaweiFraming.MIN_SLICE_SIZE].
     */
    fun capSliceToMtu(mtu: Int) {
        sliceCap = maxSliceForMtu(mtu)
        codec.config = capped(codec.config)
    }

    private fun capped(config: HuaweiLinkConfig): HuaweiLinkConfig {
        val cap = sliceCap ?: return config
        return if (config.sliceSize > cap) config.copy(sliceSize = cap) else config
    }

    override suspend fun request(request: HuaweiRequest): HuaweiTlv = sendLock.withLock {
        val pending = Pending(request.serviceId, request.commandId, CompletableDeferred())
        synchronized(stateLock) {
            closedReason?.let { throw HuaweiLinkClosedException("link closed ($it)") }
            inFlight = pending
        }
        try {
            val slices = codec.encode(request)
            for (slice in slices) {
                if (!write(slice)) throw HuaweiLinkClosedException("write refused for ${request.command}")
            }
            withTimeoutOrNull(requestTimeoutMs) { pending.reply.await() }
                ?: throw HuaweiLinkTimeoutException("no reply to ${request.command} within ${requestTimeoutMs / 1000} s")
        } finally {
            synchronized(stateLock) { if (inFlight === pending) inFlight = null }
        }
    }

    /** Writes [request] without awaiting a reply on its own id (file block requests, download done, P2P). */
    override suspend fun send(request: HuaweiRequest) = sendLock.withLock {
        synchronized(stateLock) { closedReason?.let { throw HuaweiLinkClosedException("link closed ($it)") } }
        for (slice in codec.encode(request)) {
            if (!write(slice)) throw HuaweiLinkClosedException("write refused for ${request.command}")
        }
    }

    /** One FE02 notification. Safe to call from any thread. */
    fun onNotification(chunk: ByteArray) {
        val frames = try {
            synchronized(codec) { codec.feed(chunk) }
        } catch (e: HuaweiProtocolException) {
            synchronized(codec) { codec.reset() }
            log("huawei: dropped inbound bytes (${e.message})")
            return
        }
        for (frame in frames) {
            if (HuaweiFraming.isRawCommand(frame.serviceId, frame.commandId)) {
                val body = try {
                    codec.decodeRaw(frame)
                } catch (e: Exception) {
                    log("huawei: dropped %02X/%02X block (%s)".format(frame.serviceId, frame.commandId, e.message))
                    continue
                }
                if (!rawFlow.tryEmit(HuaweiRawPush(frame.serviceId, frame.commandId, body))) {
                    log("huawei: block %02X/%02X dropped, buffer full".format(frame.serviceId, frame.commandId))
                }
                continue
            }
            val tlv = try {
                codec.decodeTlv(frame)
            } catch (e: HuaweiProtocolException) {
                log("huawei: dropped %02X/%02X (%s)".format(frame.serviceId, frame.commandId, e.message))
                continue
            }
            val pending = synchronized(stateLock) {
                inFlight?.takeIf { it.serviceId == frame.serviceId && it.commandId == frame.commandId && !it.reply.isCompleted }
            }
            if (pending != null) {
                pending.reply.complete(tlv)
            } else if (!pushFlow.tryEmit(HuaweiPush(frame.serviceId, frame.commandId, tlv))) {
                log("huawei: push %02X/%02X dropped, buffer full".format(frame.serviceId, frame.commandId))
            }
        }
    }

    /** The link is gone: fail the request in flight and refuse every later one. Idempotent. */
    fun close(reason: String) {
        val pending = synchronized(stateLock) {
            if (closedReason == null) closedReason = reason
            inFlight.also { inFlight = null }
        }
        pending?.reply?.completeExceptionally(HuaweiLinkClosedException("link closed ($reason)"))
        synchronized(codec) { codec.reset() }
    }

    companion object {
        /** Above the 30 s first-pairing confirmation window, so only a caller's own timeout normally fires. */
        const val DEFAULT_CEILING_MS = 40_000L

        /** ATT write header: opcode + handle. */
        const val ATT_HEADER = 3

        fun maxSliceForMtu(mtu: Int): Int = maxOf(mtu - ATT_HEADER, HuaweiFraming.MIN_SLICE_SIZE)
    }
}
