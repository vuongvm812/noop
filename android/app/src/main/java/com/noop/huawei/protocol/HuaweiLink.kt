package com.noop.huawei.protocol

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** What the transport needs to frame and encrypt: the slice size from LinkParams and, once auth is done, the session cipher. */
data class HuaweiLinkConfig(val sliceSize: Int = HuaweiFraming.DEFAULT_SLICE_SIZE, val cipher: HuaweiCipher? = null)

/** An unsolicited message from the band (for example the 01/27 battery push), already decrypted. */
data class HuaweiPush(val serviceId: Int, val commandId: Int, val tlv: HuaweiTlv)

/**
 * A raw (non-TLV) message from the band: a file block (2C/05 or 0A/05). [body] is plaintext; an encrypted
 * block (body starting `7C 01 01`) has already been opened ([HuaweiPacketCodec.decodeRaw]).
 */
class HuaweiRawPush(serviceId: Int, commandId: Int, body: ByteArray) {
    val serviceId: Int = serviceId and 0xFF
    val commandId: Int = commandId and 0xFF
    private val bytes = body.copyOf()
    val body: ByteArray get() = bytes.copyOf()
}

/**
 * The seam between the pure protocol and the BLE transport (wave 2). An implementation frames and
 * encrypts [request]s with the current [configure]d state, matches the band's reply by service/command
 * id and returns its decrypted TLV. TLV messages that match no pending request go to [pushes]; raw
 * messages ([HuaweiFraming.isRawCommand]) go to [rawPushes]. Timeouts are applied by the caller.
 */
interface HuaweiLink {
    fun configure(config: HuaweiLinkConfig)

    suspend fun request(request: HuaweiRequest): HuaweiTlv

    val pushes: Flow<HuaweiPush>

    /**
     * Sends [request] without waiting for a reply on its own id: a file block request (2C/04, 0A/04) is
     * answered by raw 05 messages, a download-done (06) by nothing. A link that cannot do this throws
     * [UnsupportedOperationException], and history sync then skips the file downloads.
     */
    suspend fun send(request: HuaweiRequest) {
        throw UnsupportedOperationException("this HuaweiLink cannot send without awaiting a reply")
    }

    /** Raw inbound messages (file blocks), decrypted. Empty for a link that does not route them. */
    val rawPushes: Flow<HuaweiRawPush> get() = emptyFlow()
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

    /** The plaintext body of a raw frame, opening the envelope when it starts `7C 01 01`. */
    fun decodeRaw(frame: HuaweiFrame): ByteArray = decodeRaw(frame, config.cipher)

    fun reset() = assembler.reset()

    companion object {
        /**
         * A raw block body: plaintext as sent, or an encrypted envelope (`7C 01 01 …`, a TLV of tags
         * 7C/7D/7E) whose opened bytes ARE the block. Gadgetbridge `FileDownloadService2C.BlockResponse`.
         */
        fun decodeRaw(frame: HuaweiFrame, cipher: HuaweiCipher?): ByteArray {
            val body = frame.body
            val enveloped = body.size >= 3 && body[0].toInt() == HuaweiCipher.TAG_ENCRYPTED && body[1].toInt() == 1 && body[2].toInt() == 1
            if (!enveloped) return body
            val c = cipher ?: throw HuaweiProtocolException("encrypted block before a session is established")
            return c.open(HuaweiTlv.parse(body))
        }

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
