package com.noop.huawei

import com.noop.huawei.auth.HuaweiAuthCrypto
import com.noop.huawei.auth.HuaweiVendorConstants
import com.noop.huawei.protocol.HuaweiBytes
import com.noop.huawei.protocol.HuaweiCipher
import com.noop.huawei.protocol.HuaweiCrypto
import com.noop.huawei.protocol.HuaweiFrame
import com.noop.huawei.protocol.HuaweiFrameAssembler
import com.noop.huawei.protocol.HuaweiFraming
import com.noop.huawei.protocol.HuaweiHex
import com.noop.huawei.protocol.HuaweiPacketCodec
import com.noop.huawei.protocol.HuaweiRandomIvSource
import com.noop.huawei.protocol.HuaweiTlv
import org.json.JSONObject

/**
 * The band's side of every auth mode, written independently of [com.noop.huawei.auth.HuaweiAuthMachine]
 * from the same protocol description, so a session only completes when both sides derive the same keys.
 * It speaks bytes: it reassembles NOOP's frames, opens encrypted ones with its own session key and
 * answers with framed (and, after auth, encrypted) replies.
 */
class SimulatedHuaweiBand(val config: Config, seed: Long = 99) : FakeHuaweiLink.Peer {

    data class Config(
        val deviceSupportType: Int = 0,
        val authVersion: Int = 1,
        val authAlgo: Int = 0,
        val encryptMethod: Int = 0,
        val sliceSize: Int = 0xF4,
        /** SecurityNegotiation reply: tag 1 / tag 2 bytes, null to omit. */
        val secNegTag1: Int? = null,
        val secNegTag2: Int? = null,
        val bondStatus: Int = 0,
        val counterSeed: Long = 0x1000,
        val pin: ByteArray = "135790".toByteArray(),
        val bandAddress: String = "C8:2A:DD:10:20:30",
        val bandAuthId: ByteArray = "HUAWEI-BAND-10-AUTH".toByteArray(),
        /** A key the band already holds from an earlier pairing (normal/Lite session key or HiChain auth token). */
        val knownSecret: ByteArray? = null,
        val corruptAuthResponse: Boolean = false,
        val corruptHiChainToken: Boolean = false,
        val corruptReturnCodeMac: Boolean = false,
        val hiChainErrorCode: Int = 0,
        /** Answer `(sid, cid)` with a 0x7F result code. */
        val errorCodes: Map<Pair<Int, Int>, Long> = emptyMap(),
        /** Never answer `(sid, cid)`. */
        val silentOn: Set<Pair<Int, Int>> = emptySet(),
        /** Corrupt the CRC of the reply to `(sid, cid)`. */
        val badCrcOn: Set<Pair<Int, Int>> = emptySet(),
        val supportedServices: Set<Int> = setOf(0x01, 0x07, 0x0A, 0x16),
    ) {
        companion object {
            fun normal() = Config(deviceSupportType = 0)
            fun lite() = Config(deviceSupportType = 2, secNegTag2 = 0x02)
            fun hiChain() = Config(deviceSupportType = 1, secNegTag1 = 0x01)
            fun hiChain3() = Config(deviceSupportType = 4, secNegTag1 = 0x04, secNegTag2 = 0x01)
        }
    }

    private val random = SeededHuaweiRandom(seed)
    private val assembler = HuaweiFrameAssembler()
    private val serverNonce = random.nextBytes(16)

    /** Every message NOOP sent, decrypted. */
    val received = mutableListOf<Pair<HuaweiFrame, HuaweiTlv>>()

    /** The IVs of encrypted messages and of the Bond request, in order. */
    val receivedIvs = mutableListOf<ByteArray>()

    var sessionCipher: HuaweiCipher? = null
        private set

    /** The 16-byte key NOOP bonded (normal/Lite), the HiChain auth token, and the HiChain packet key. */
    var bondedKey: ByteArray? = null
        private set
    var authToken: ByteArray? = config.knownSecret
        private set
    var packetKey: ByteArray? = null
        private set

    private var liteFirstKey: ByteArray? = null

    // HiChain per-operation state.
    private var hcRandSelf = ByteArray(0)
    private var hcRandPeer = ByteArray(0)
    private var hcPhoneId = ByteArray(0)
    private var hcPsk = ByteArray(0)
    private var hcSessionKey = ByteArray(0)
    private var hcChallenge = ByteArray(0)

    private val useGcm = HuaweiCipher.usesGcm(config.encryptMethod, config.deviceSupportType)
    private val lite = config.deviceSupportType in 1..4 && (config.secNegTag2 == 1 || config.secNegTag2 == 2) && config.secNegTag1 != 4
    private val authMode = when {
        config.deviceSupportType == 0 -> 0
        config.deviceSupportType == 4 -> 4
        else -> 2
    }

    override suspend fun onFrames(frames: List<ByteArray>): List<ByteArray>? {
        val messages = frames.flatMap { assembler.feed(it) }
        check(messages.size == 1) { "band expected one message, got ${messages.size}" }
        val frame = messages[0]
        val key = frame.serviceId to frame.commandId
        val raw = HuaweiTlv.parse(frame.body)
        if (HuaweiCipher.isEnvelope(raw)) receivedIvs.add(raw.bytes(HuaweiCipher.TAG_IV))
        val tlv = HuaweiPacketCodec.decodeTlv(frame, sessionCipher)
        received.add(frame to tlv)
        if (key in config.silentOn) return null
        val code = config.errorCodes[key]
        val reply = if (code != null) {
            HuaweiTlv().put(HuaweiTlv.TAG_RESULT, HuaweiBytes.u32(code))
        } else {
            answer(frame, tlv) ?: return null
        }
        val body = if (HuaweiCipher.isEnvelope(raw)) checkNotNull(sessionCipher).seal(reply).serialize() else reply.serialize()
        val out = HuaweiFraming.encode(frame.serviceId, frame.commandId, body, sliced = true, sliceSize = config.sliceSize)
        if (key in config.badCrcOn) out.last()[out.last().size - 1] = (out.last()[out.last().size - 1].toInt() xor 0xFF).toByte()
        return out
    }

    /** A band-initiated battery push, encrypted with the session key. */
    fun batteryPush(level: Int): List<ByteArray> {
        val body = checkNotNull(sessionCipher).seal(HuaweiTlv().putByte(0x01, level)).serialize()
        return HuaweiFraming.encode(0x01, 0x27, body, sliced = true, sliceSize = config.sliceSize)
    }

    private fun answer(frame: HuaweiFrame, tlv: HuaweiTlv): HuaweiTlv? {
        check(frame.serviceId == 0x01) { "unexpected service ${frame.serviceId}" }
        return when (frame.commandId) {
            0x01 -> linkParams()
            0x33 -> HuaweiTlv().also { t ->
                config.secNegTag1?.let { t.putByte(0x01, it) }
                config.secNegTag2?.let { t.putByte(0x02, it) }
            }
            0x2C -> pinCode()
            0x13 -> auth(tlv)
            0x0F -> bondParams()
            0x0E -> bond(tlv)
            0x28 -> hiChain(tlv)
            0x02 -> {
                val asked = tlv.bytes(0x01)
                HuaweiTlv().put(0x02, ByteArray(asked.size) { if ((asked[it].toInt() and 0xFF) in config.supportedServices) 1 else 0 })
            }
            0x07 -> HuaweiTlv().putString(0x03, "HW-1").putString(0x07, "10.0.0.120").putString(0x0A, "Band 10\u0000\u0000\u0000")
            0x08 -> HuaweiTlv().putByte(0x01, 77)
            0x37 -> HuaweiTlv().put(0x01, byteArrayOf(0x05, 0x00, 0x01))
            0x31 -> HuaweiTlv().putByte(0x02, 0x0E)
            else -> HuaweiTlv().put(HuaweiTlv.TAG_RESULT, HuaweiBytes.u32(0x00019001))
        }
    }

    private fun linkParams(): HuaweiTlv = HuaweiTlv()
        .putByte(0x01, 0x02)
        .putShort(0x02, config.sliceSize)
        .putShort(0x03, 0x01F4)
        .putShort(0x04, 0x000A)
        .put(0x05, HuaweiBytes.u16(config.authVersion) + serverNonce)
        .putByte(0x07, config.deviceSupportType)
        .putByte(0x08, config.authAlgo)
        .putByte(0x09, 0x00)
        .putByte(0x0C, config.encryptMethod)

    private fun pinCode(): HuaweiTlv {
        val iv = random.nextBytes(if (config.encryptMethod == 1) 12 else 16)
        val message = HuaweiAuthCrypto.encryptPinCode(config.authVersion, config.encryptMethod, config.pin, iv)
        return HuaweiTlv().put(0x01, message).put(0x02, iv)
    }

    private fun auth(tlv: HuaweiTlv): HuaweiTlv {
        val nonceField = tlv.bytes(0x02)
        val doubleNonce = serverNonce + nonceField.copyOfRange(2, 18)
        val key = if (authMode == 2) config.pin else null
        val challenge = digest(challenge = true, key, doubleNonce)
        if (!challenge.copyOfRange(0, 32).contentEquals(tlv.bytes(0x01))) {
            return HuaweiTlv().put(HuaweiTlv.TAG_RESULT, HuaweiBytes.u32(0x00019002))
        }
        if (authMode == 2) check(tlv.byte(0x03) == config.authAlgo) { "Lite Auth must carry authAlgo" }
        liteFirstKey = challenge.copyOfRange(32, 48)
        val response = digest(challenge = false, key, doubleNonce).copyOfRange(0, 32)
        if (config.corruptAuthResponse) response[0] = (response[0].toInt() xor 1).toByte()
        return HuaweiTlv().put(0x01, response)
    }

    /** The band's own digest (written from the protocol description, not via HuaweiAuthCrypto). */
    private fun digest(challenge: Boolean, key: ByteArray?, nonce: ByteArray): ByteArray {
        val secret = when (config.authVersion) {
            1, 4 -> HuaweiVendorConstants.DIGEST_SECRET_V1
            2 -> HuaweiVendorConstants.DIGEST_SECRET_V2
            else -> HuaweiVendorConstants.DIGEST_SECRET_V3
        }
        val message = when {
            !challenge -> byteArrayOf(0x01, 0x10)
            authMode != 2 && config.authVersion == 4 -> byteArrayOf(0x04, 0x00)
            else -> byteArrayOf(0x01, 0x00)
        }
        val out = ByteArray(64)
        if (authMode == 2 && config.authVersion == 2) {
            val k = checkNotNull(key) + message
            (HuaweiCrypto.hmacSha256(k, nonce) + k).copyInto(out)
            return out
        }
        val material = if (authMode == 2) {
            val hashed = HuaweiCrypto.sha256(HuaweiHex.encode(checkNotNull(key)).toByteArray())
            ByteArray(16) { (secret[it].toInt() xor hashed[it].toInt()).toByte() } + message
        } else {
            secret + message
        }
        val step1 = if (authMode == 2 && config.authAlgo == 1) {
            HuaweiCrypto.pbkdf2Sha256(HuaweiHex.encode(material), HuaweiHex.encode(nonce).toByteArray(), 1000, 32)
        } else {
            HuaweiCrypto.hmacSha256(material, nonce)
        }
        (HuaweiCrypto.hmacSha256(step1, nonce) + step1).copyInto(out)
        return out
    }

    private fun bondParams(): HuaweiTlv {
        // A band that is already bonded talks with the key it holds from then on.
        if (config.knownSecret != null && (config.bondStatus == 1 || lite)) open(config.knownSecret)
        return HuaweiTlv().putByte(0x01, config.bondStatus).putInt(0x09, config.counterSeed.toInt())
    }

    private fun bond(tlv: HuaweiTlv): HuaweiTlv {
        val iv = tlv.bytes(0x07)
        receivedIvs.add(iv)
        val encKey = if (authMode == 2) checkNotNull(liteFirstKey) else HuaweiAuthCrypto.bondEncryptionKey(config.authVersion, config.bandAddress)
        val key = HuaweiAuthCrypto.decryptBondingKey(config.encryptMethod, tlv.bytes(0x06), encKey, iv)
        bondedKey = key
        open(key)
        return HuaweiTlv().put(HuaweiTlv.TAG_RESULT, HuaweiBytes.u32(HuaweiTlv.RESULT_OK))
    }

    private fun open(key: ByteArray) {
        sessionCipher = HuaweiCipher(key, useGcm, HuaweiRandomIvSource(random))
    }

    private fun hiChain(tlv: HuaweiTlv): HuaweiTlv {
        val op = tlv.byte(0x02)
        val value = JSONObject(tlv.string(0x01))
        val message = value.getInt("message")
        val payload = value.getJSONObject("payload")
        check((message and 0x10 != 0) == (op == 2)) { "message $message does not match operation $op" }
        check(tlv.bytes(0x03).size == 8) { "request id must be 8 bytes" }
        if (config.hiChainErrorCode != 0) return json(message, JSONObject().put("errorCode", config.hiChainErrorCode))
        return when (message and 0x0F) {
            1 -> {
                if (op == 1) check(value.getString("appId") == HuaweiVendorConstants.HICHAIN_APP_ID)
                hcRandSelf = HuaweiHex.decode(payload.getString("isoSalt"))
                hcPhoneId = HuaweiHex.decode(payload.getString("peerAuthId"))
                val seed = HuaweiHex.decode(payload.getString("seed"))
                val k = if (op == 1) HuaweiCrypto.sha256(HuaweiHex.encode(config.pin).toByteArray()) else checkNotNull(authToken)
                hcPsk = HuaweiCrypto.hmacSha256(k, seed)
                hcRandPeer = random.nextBytes(16)
                val token = HuaweiCrypto.hmacSha256(hcPsk, hcRandPeer + hcRandSelf + hcPhoneId + config.bandAuthId)
                if (config.corruptHiChainToken) token[5] = (token[5].toInt() xor 0x40).toByte()
                json(
                    message,
                    JSONObject()
                        .put("isoSalt", HuaweiHex.encode(hcRandPeer))
                        .put("peerAuthId", HuaweiHex.encode(config.bandAuthId))
                        .put("peerUserType", 0)
                        .put("token", HuaweiHex.encode(token)),
                )
            }
            2 -> {
                val token = HuaweiHex.decode(payload.getString("token"))
                val expected = HuaweiCrypto.hmacSha256(hcPsk, hcRandSelf + hcRandPeer + config.bandAuthId + hcPhoneId)
                if (!expected.contentEquals(token)) return json(message, JSONObject().put("errorCode", 0x1001))
                hcSessionKey = HuaweiCrypto.hkdfSha256(hcPsk, hcRandSelf + hcRandPeer, "hichain_iso_session_key".toByteArray(), 32)
                val mac = HuaweiCrypto.hmacSha256(hcPsk, ByteArray(4))
                if (config.corruptReturnCodeMac) mac[0] = (mac[0].toInt() xor 1).toByte()
                json(message, JSONObject().put("returnCodeMac", HuaweiHex.encode(mac)))
            }
            3 -> if (op == 1) exchange(message, payload) else result(op, payload)
            4 -> result(op, payload)
            else -> error("unexpected HiChain message $message")
        }
    }

    private fun exchange(message: Int, payload: JSONObject): HuaweiTlv {
        hcChallenge = HuaweiCrypto.aesGcmDecrypt(
            hcSessionKey,
            HuaweiHex.decode(payload.getString("nonce")),
            HuaweiHex.decode(payload.getString("encData")),
            "hichain_iso_exchange".toByteArray(),
        )
        val token = random.nextBytes(32)
        authToken = token
        val nonce = random.nextBytes(12)
        val enc = HuaweiCrypto.aesGcmEncrypt(hcSessionKey, nonce, token, hcChallenge)
        return json(message, JSONObject().put("nonce", HuaweiHex.encode(nonce)).put("encAuthToken", HuaweiHex.encode(enc)))
    }

    private fun result(op: Int, payload: JSONObject): HuaweiTlv {
        val plain = HuaweiCrypto.aesGcmDecrypt(
            hcSessionKey,
            HuaweiHex.decode(payload.getString("nonce")),
            HuaweiHex.decode(payload.getString("encResult")),
            "hichain_iso_result".toByteArray(),
        )
        check(plain.contentEquals(ByteArray(4))) { "encResult must be four zero bytes" }
        check(payload.getInt("operationCode") == op)
        if (op == 2) {
            val key = HuaweiCrypto.hkdfSha256(hcSessionKey, hcRandSelf + hcRandPeer, "hichain_return_key".toByteArray(), 32)
            packetKey = key
            open(key)
        }
        return HuaweiTlv().putByte(0x04, 0x01)
    }

    private fun json(message: Int, payload: JSONObject): HuaweiTlv =
        HuaweiTlv().putString(0x01, JSONObject().put("message", message or 0x8000).put("payload", payload).toString()).putByte(0x04, 0x00)
}
