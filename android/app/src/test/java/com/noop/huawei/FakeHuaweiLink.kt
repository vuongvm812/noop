package com.noop.huawei

import com.noop.huawei.protocol.HuaweiFrame
import com.noop.huawei.protocol.HuaweiFraming
import com.noop.huawei.protocol.HuaweiLink
import com.noop.huawei.protocol.HuaweiLinkConfig
import com.noop.huawei.protocol.HuaweiPacketCodec
import com.noop.huawei.protocol.HuaweiProtocolException
import com.noop.huawei.protocol.HuaweiPush
import com.noop.huawei.protocol.HuaweiRandom
import com.noop.huawei.protocol.HuaweiRequest
import com.noop.huawei.protocol.HuaweiTlv
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Test double for the wave-2 BLE link. Every request goes through the real [HuaweiPacketCodec] (framing,
 * slicing, encryption) and the resulting bytes are handed to a [Peer], which answers with bytes the codec
 * reassembles and decrypts. A peer that returns null never answers, so the caller's timeout fires.
 * Messages in a reply that do not match the request, and anything sent with [deliverFromBand], go to
 * [pushes].
 */
class FakeHuaweiLink(private val peer: Peer) : HuaweiLink {

    fun interface Peer {
        /** The frames NOOP sent for one request; returns the band's frames, or null to stay silent. */
        suspend fun onFrames(frames: List<ByteArray>): List<ByteArray>?
    }

    private val codec = HuaweiPacketCodec()
    private val pushFlow = MutableSharedFlow<HuaweiPush>(replay = 16, extraBufferCapacity = 64)

    val sent = mutableListOf<HuaweiRequest>()
    val sentFrames = mutableListOf<List<ByteArray>>()
    val configs = mutableListOf<HuaweiLinkConfig>()

    val currentConfig: HuaweiLinkConfig get() = codec.config

    override val pushes: SharedFlow<HuaweiPush> = pushFlow.asSharedFlow()

    override fun configure(config: HuaweiLinkConfig) {
        codec.config = config
        configs.add(config)
    }

    override suspend fun request(request: HuaweiRequest): HuaweiTlv {
        sent.add(request)
        val frames = codec.encode(request)
        sentFrames.add(frames)
        val reply = peer.onFrames(frames) ?: awaitCancellation()
        var answer: HuaweiTlv? = null
        for (chunk in reply) {
            for (frame in codec.feed(chunk)) {
                val tlv = codec.decodeTlv(frame)
                if (answer == null && frame.serviceId == request.serviceId && frame.commandId == request.commandId) {
                    answer = tlv
                } else {
                    pushFlow.tryEmit(HuaweiPush(frame.serviceId, frame.commandId, tlv))
                }
            }
        }
        return answer ?: throw HuaweiProtocolException("no reply for ${request.command}")
    }

    /** Band-initiated bytes (for example the 01/27 battery push). */
    fun deliverFromBand(chunks: List<ByteArray>) {
        for (chunk in chunks) for (frame in codec.feed(chunk)) {
            pushFlow.tryEmit(HuaweiPush(frame.serviceId, frame.commandId, codec.decodeTlv(frame)))
        }
    }

    companion object {
        /** A plaintext peer answering each request's TLV with [respond] (null = silent). */
        fun scripted(respond: (HuaweiFrame, HuaweiTlv) -> HuaweiTlv?): FakeHuaweiLink {
            val inbound = HuaweiPacketCodec()
            return FakeHuaweiLink { frames ->
                val frame = frames.flatMap { inbound.feed(it) }.single()
                respond(frame, inbound.decodeTlv(frame))?.let {
                    HuaweiFraming.encode(frame.serviceId, frame.commandId, it.serialize(), sliced = false)
                }
            }
        }
    }
}

/** Deterministic [HuaweiRandom] for replayable sessions. */
class SeededHuaweiRandom(seed: Long) : HuaweiRandom {
    private val rng = java.util.Random(seed)
    override fun nextBytes(count: Int): ByteArray = ByteArray(count).also { rng.nextBytes(it) }
}

/** A [HuaweiRandom] that always returns zeros (for the Gadgetbridge vectors' all-zero IVs). */
object ZeroHuaweiRandom : HuaweiRandom {
    override fun nextBytes(count: Int): ByteArray = ByteArray(count)
}

fun hex(s: String): ByteArray = com.noop.huawei.protocol.HuaweiHex.decode(s.replace(" ", ""))
