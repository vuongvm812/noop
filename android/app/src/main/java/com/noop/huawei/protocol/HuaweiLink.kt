package com.noop.huawei.protocol

import kotlinx.coroutines.flow.Flow

/** What the transport needs to frame and encrypt: the slice size from LinkParams and, once auth is done, the session cipher. */
data class HuaweiLinkConfig(val sliceSize: Int = HuaweiFraming.DEFAULT_SLICE_SIZE, val cipher: HuaweiCipher? = null)

/** An unsolicited message from the band (for example the 01/27 battery push), already decrypted. */
data class HuaweiPush(val serviceId: Int, val commandId: Int, val tlv: HuaweiTlv)

/**
 * The seam between the pure protocol and the BLE transport (wave 2). An implementation frames and
 * encrypts [request]s with the current [configure]d state, matches the band's reply by service/command
 * id and returns its decrypted TLV. Messages that match no pending request go to [pushes]. Timeouts are
 * applied by the caller.
 */
interface HuaweiLink {
    fun configure(config: HuaweiLinkConfig)

    suspend fun request(request: HuaweiRequest): HuaweiTlv

    val pushes: Flow<HuaweiPush>
}

/**
 * Pure bytes ↔ messages for one connection, for a [HuaweiLink] implementation to wrap. Encrypted
 * commands are refused until a session cipher is configured, so nothing marked encrypted can leave in
 * plaintext.
 */
class HuaweiPacketCodec(config: HuaweiLinkConfig = HuaweiLinkConfig()) {
    @Volatile
    var config: HuaweiLinkConfig = config

    private val assembler = HuaweiFrameAssembler()

    fun encode(request: HuaweiRequest): List<ByteArray> {
        val plain = request.tlv
        val tlv = if (request.encrypted) {
            val cipher = config.cipher ?: throw IllegalStateException("${request.command} is encrypted but no session is established")
            cipher.seal(plain)
        } else {
            plain
        }
        return HuaweiFraming.encode(request.serviceId, request.commandId, tlv.serialize(), request.command.sliced, config.sliceSize)
    }

    /** Feeds one notification; returns every message it completes. */
    fun feed(chunk: ByteArray): List<HuaweiFrame> = assembler.feed(chunk)

    /** The plaintext TLV of a TLV frame, opening the envelope when tag 0x7C is set. */
    fun decodeTlv(frame: HuaweiFrame): HuaweiTlv = decodeTlv(frame, config.cipher)

    fun reset() = assembler.reset()

    companion object {
        fun decodeTlv(frame: HuaweiFrame, cipher: HuaweiCipher?): HuaweiTlv {
            if (HuaweiFraming.isRawCommand(frame.serviceId, frame.commandId)) {
                throw HuaweiProtocolException("%02X/%02X is a raw (non-TLV) command".format(frame.serviceId, frame.commandId))
            }
            val tlv = HuaweiTlv.parse(frame.body)
            if (!HuaweiCipher.isEnvelope(tlv)) return tlv
            val c = cipher ?: throw HuaweiProtocolException("encrypted message before a session is established")
            return HuaweiTlv.parse(c.open(tlv))
        }
    }
}
