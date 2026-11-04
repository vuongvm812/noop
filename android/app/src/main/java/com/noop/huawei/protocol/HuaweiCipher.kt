package com.noop.huawei.protocol

// The per-packet encryption envelope.
//
// Protocol facts (re-derived, no code copied) from Gadgetbridge `devices/huawei/HuaweiTLV.java`,
// `HuaweiPacket.java` (ParamsProvider.getIv) and `HuaweiCrypto.java` (AGPL-3.0, commit
// 6e4220682feb6917a5644304d9d7c760f30b6eaf): an encrypted TLV is replaced by
// `{7C: 01, 7D: IV, 7E: ciphertext}`. AES-GCM (128-bit tag, no AAD) is used when LinkParams
// encryptMethod (tag 0x0C) is 1 or deviceSupportType (tag 7) is 4, AES-CBC/PKCS#5 otherwise. The IV is
// 16 random bytes for deviceSupportType 4; otherwise 12 random bytes followed by a 4-byte big-endian
// counter seeded from BondParams tag 9 and incremented before every packet.

/** Produces the IV for each encrypted packet. Stateful: one instance per connection. */
fun interface HuaweiIvSource {
    fun nextIv(): ByteArray
}

/** 12 random bytes + a 4-byte big-endian counter, incremented before use. */
class HuaweiCounterIvSource(seed: Long, private val random: HuaweiRandom = HuaweiRandom.secure) : HuaweiIvSource {
    var counter: Long = seed and COUNTER_MAX
        private set

    override fun nextIv(): ByteArray {
        counter = next(counter)
        return random.nextBytes(12) + HuaweiBytes.u32(counter)
    }

    companion object {
        const val COUNTER_MAX = 0xFFFFFFFFL

        /**
         * The counter after [counter]: +1, wrapping 0xFFFFFFFF to 1 (0 is never used). Gadgetbridge
         * compares against the Java int literal 0xFFFFFFFF, which is -1 as a long, so its wrap actually
         * passes through 0; NOOP follows the intended 1 and the difference only shows after 2^32 packets.
         */
        fun next(counter: Long): Long = if ((counter and COUNTER_MAX) == COUNTER_MAX) 1 else (counter and COUNTER_MAX) + 1
    }
}

/** 16 random bytes per packet (deviceSupportType 4). */
class HuaweiRandomIvSource(private val random: HuaweiRandom = HuaweiRandom.secure) : HuaweiIvSource {
    override fun nextIv(): ByteArray = random.nextBytes(16)
}

/**
 * Seals and opens the `{7C, 7D, 7E}` envelope with a session key: 16 bytes for normal and HiChain Lite
 * sessions (AES-128), 32 bytes for HiChain (AES-256).
 */
class HuaweiCipher(key: ByteArray, val useGcm: Boolean, private val ivSource: HuaweiIvSource) {
    private val key = key.copyOf()

    init {
        require(key.size == 16 || key.size == 32) { "session key must be 16 or 32 bytes, got ${key.size}" }
    }

    fun seal(plaintext: ByteArray): HuaweiTlv {
        val iv = ivSource.nextIv()
        val ct = if (useGcm) HuaweiCrypto.aesGcmEncrypt(key, iv, plaintext) else HuaweiCrypto.aesCbcEncrypt(key, iv, plaintext)
        return HuaweiTlv().putByte(TAG_ENCRYPTED, 1).put(TAG_IV, iv).put(TAG_CIPHERTEXT, ct)
    }

    fun seal(tlv: HuaweiTlv): HuaweiTlv = seal(tlv.serialize())

    fun open(envelope: HuaweiTlv): ByteArray {
        val iv = envelope.bytes(TAG_IV)
        val ct = envelope.bytes(TAG_CIPHERTEXT)
        return if (useGcm) HuaweiCrypto.aesGcmDecrypt(key, iv, ct) else HuaweiCrypto.aesCbcDecrypt(key, iv, ct)
    }

    companion object {
        const val TAG_ENCRYPTED = 0x7C
        const val TAG_IV = 0x7D
        const val TAG_CIPHERTEXT = 0x7E

        fun usesGcm(encryptMethod: Int, deviceSupportType: Int): Boolean = encryptMethod == 1 || deviceSupportType == 4

        fun ivSourceFor(deviceSupportType: Int, counterSeed: Long, random: HuaweiRandom): HuaweiIvSource =
            if (deviceSupportType == 4) HuaweiRandomIvSource(random) else HuaweiCounterIvSource(counterSeed, random)

        /** True when [tlv] is an envelope (tag 0x7C present and 1). */
        fun isEnvelope(tlv: HuaweiTlv): Boolean = tlv.contains(TAG_ENCRYPTED) && tlv.boolean(TAG_ENCRYPTED)
    }
}
