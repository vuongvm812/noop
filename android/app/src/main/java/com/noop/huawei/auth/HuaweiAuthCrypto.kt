package com.noop.huawei.auth

import com.noop.huawei.protocol.HuaweiCrypto
import com.noop.huawei.protocol.HuaweiHex

// Vendor constants and the auth-specific key derivations.
//
// The byte constants below are vendor protocol facts (fixed values every Huawei band expects), taken
// from Gadgetbridge `devices/huawei/HuaweiCrypto.java` and `HuaweiConstants.java` (AGPL-3.0, commit
// 6e4220682feb6917a5644304d9d7c760f30b6eaf) and attributed in ATTRIBUTION.md. The derivations are
// re-derived from Gadgetbridge's `HuaweiCrypto.java`, `requests/GetAuthRequest.java`,
// `requests/GetBondRequest.java` and `requests/GetHiChainRequest.java`; no code is copied.

/** Fixed vendor values. Not secrets in any useful sense: every band and every client ships them. */
object HuaweiVendorConstants {
    val SECRET_KEY_1_V1 = bytes(0x6F, 0x75, 0x6A, 0x79, 0x6D, 0x77, 0x71, 0x34, 0x63, 0x6C, 0x76, 0x39, 0x33, 0x37, 0x38, 0x79)
    val SECRET_KEY_2_V1 = bytes(0x62, 0x31, 0x30, 0x6A, 0x67, 0x66, 0x64, 0x39, 0x79, 0x37, 0x76, 0x73, 0x75, 0x64, 0x61, 0x39)
    val SECRET_KEY_1_V23 = bytes(0x55, 0x53, 0x86, 0xFC, 0x63, 0x20, 0x07, 0xAA, 0x86, 0x49, 0x35, 0x22, 0xB8, 0x6A, 0xE2, 0x5C)
    val SECRET_KEY_2_V23 = bytes(0x33, 0x07, 0x9B, 0xC5, 0x7A, 0x88, 0x6D, 0x3C, 0xF5, 0x61, 0x37, 0x09, 0x6F, 0x22, 0x80, 0x00)

    val DIGEST_SECRET_V1 = bytes(0x70, 0xFB, 0x6C, 0x24, 0x03, 0x5F, 0xDB, 0x55, 0x2F, 0x38, 0x89, 0x8A, 0xEE, 0xDE, 0x3F, 0x69)
    val DIGEST_SECRET_V2 = bytes(0x93, 0xAC, 0xDE, 0xF7, 0x6A, 0xCB, 0x09, 0x85, 0x7D, 0xBF, 0xE5, 0x26, 0x1A, 0xAB, 0xCD, 0x78)
    val DIGEST_SECRET_V3 = bytes(0x9C, 0x27, 0x63, 0xA9, 0xCC, 0xE1, 0x34, 0x76, 0x6D, 0xE3, 0xFF, 0x61, 0x18, 0x20, 0x05, 0x53)

    val MESSAGE_RESPONSE = bytes(0x01, 0x10)
    val MESSAGE_CHALLENGE = bytes(0x01, 0x00)
    val MESSAGE_CHALLENGE_V4 = bytes(0x04, 0x00)

    /** HiChain group the band's health app binds into. */
    const val GROUP_ID = "7B0BC0CBCE474F6C238D9661C63400B797B166EA7849B3A370FC73A9A236E989"
    const val HICHAIN_APP_ID = "com.huawei.health"
    const val HICHAIN_PACKAGE_BIND = "com.huawei.devicegroupmanage"
    const val HICHAIN_GROUP_NAME = "health_group_name"
    const val HICHAIN_MIN_VERSION = "1.0.0"
    const val HICHAIN_CURRENT_VERSION = "2.0.16"
    const val HICHAIN_GROUP_MODULE_VERSION = "2.0.1"

    const val INFO_ISO_SESSION_KEY = "hichain_iso_session_key"
    const val INFO_RETURN_KEY = "hichain_return_key"
    const val AAD_ISO_EXCHANGE = "hichain_iso_exchange"
    const val AAD_ISO_RESULT = "hichain_iso_result"

    private fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }
}

/** Normal / HiChain Lite digests, the bond key and the PIN decrypt. */
object HuaweiAuthCrypto {
    /** Auth mode value that selects the HiChain Lite digest variant. */
    const val AUTH_MODE_LITE = 0x02

    private const val PBKDF2_ITERATIONS = 1000
    private const val DIGEST_LENGTH = 64

    /** v1 for authVersion 1 and 4, v2 for 2, v3 otherwise. Returns a fresh copy. */
    fun digestSecret(authVersion: Int): ByteArray = when (authVersion) {
        1, 4 -> HuaweiVendorConstants.DIGEST_SECRET_V1
        2 -> HuaweiVendorConstants.DIGEST_SECRET_V2
        else -> HuaweiVendorConstants.DIGEST_SECRET_V3
    }.copyOf()

    /** `HMAC(step1, nonce) || step1` where `step1 = HMAC(digestSecret || message, nonce)`; 64 bytes. */
    fun computeDigest(authVersion: Int, message: ByteArray, nonce: ByteArray): ByteArray {
        val step1 = HuaweiCrypto.hmacSha256(digestSecret(authVersion) + message, nonce)
        return pad64(HuaweiCrypto.hmacSha256(step1, nonce) + step1)
    }

    /**
     * The HiChain Lite digest: the digest secret XORed with SHA-256 of the key's upper-case hex, then
     * PBKDF2 (authAlgo 1, 1000 rounds over the hex forms) or HMAC to the first step.
     */
    fun computeDigestLite(authVersion: Int, authAlgo: Int, message: ByteArray, key: ByteArray, nonce: ByteArray): ByteArray {
        val hashKey = HuaweiCrypto.sha256(HuaweiHex.encode(key).toByteArray(Charsets.UTF_8))
        val secret = digestSecret(authVersion)
        for (i in secret.indices) secret[i] = (secret[i].toInt() xor hashKey[i].toInt()).toByte()
        val msg = secret + message
        val step1 = if (authAlgo == 0x01) {
            HuaweiCrypto.pbkdf2Sha256(HuaweiHex.encode(msg), HuaweiHex.encode(nonce).toByteArray(Charsets.UTF_8), PBKDF2_ITERATIONS, 32)
        } else {
            HuaweiCrypto.hmacSha256(msg, nonce)
        }
        return pad64(HuaweiCrypto.hmacSha256(step1, nonce) + step1)
    }

    /**
     * The 64-byte client challenge digest. Bytes 0..32 go in the Auth request; bytes 32..48 are the
     * HiChain Lite "first key" that encrypts the bonding key. [key] is the PIN (or, for authVersion 2,
     * the session key) and is used only in Lite mode.
     */
    fun digestChallenge(authVersion: Int, authAlgo: Int, authMode: Int, key: ByteArray?, nonce: ByteArray): ByteArray =
        digest(authVersion, authAlgo, authMode, key, nonce, challenge = true)

    /** The 64-byte digest the band must answer with; its first 32 bytes are compared. */
    fun digestResponse(authVersion: Int, authAlgo: Int, authMode: Int, key: ByteArray?, nonce: ByteArray): ByteArray =
        digest(authVersion, authAlgo, authMode, key, nonce, challenge = false)

    private fun digest(authVersion: Int, authAlgo: Int, authMode: Int, key: ByteArray?, nonce: ByteArray, challenge: Boolean): ByteArray {
        val message = if (challenge) HuaweiVendorConstants.MESSAGE_CHALLENGE else HuaweiVendorConstants.MESSAGE_RESPONSE
        if (authMode == AUTH_MODE_LITE) {
            requireNotNull(key) { "HiChain Lite digest needs a key" }
            if (authVersion == 0x02) {
                val k = key + message
                return pad64(HuaweiCrypto.hmacSha256(k, nonce) + k)
            }
            return computeDigestLite(authVersion, authAlgo, message, key, nonce)
        }
        if (challenge && authVersion == 4) return computeDigest(authVersion, HuaweiVendorConstants.MESSAGE_CHALLENGE_V4, nonce)
        return computeDigest(authVersion, message, nonce)
    }

    /**
     * The key that encrypts the bonding key in normal mode, mixed from the two vendor keys and the
     * band's Bluetooth address: `SHA256(((SHA256(k1<<4 ^ k2)[i] >> 6) ^ mac16[i]))[0..16]`, where
     * mac16 is the address without colons plus "0000".
     */
    fun bondEncryptionKey(authVersion: Int, bandAddress: String): ByteArray {
        val k1 = if (authVersion == 1) HuaweiVendorConstants.SECRET_KEY_1_V1 else HuaweiVendorConstants.SECRET_KEY_1_V23
        val k2 = if (authVersion == 1) HuaweiVendorConstants.SECRET_KEY_2_V1 else HuaweiVendorConstants.SECRET_KEY_2_V23
        val macKey = (bandAddress.replace(":", "") + "0000").toByteArray(Charsets.UTF_8)
        require(macKey.size >= 16) { "band address too short: $bandAddress" }
        val mixed = ByteArray(16) { i -> (((k1[i].toInt() and 0xFF) shl 4) xor (k2[i].toInt() and 0xFF)).toByte() }
        val mixedHash = HuaweiCrypto.sha256(mixed)
        val final = ByteArray(16) { i -> (((mixedHash[i].toInt() and 0xFF) shr 6) xor (macKey[i].toInt() and 0xFF)).toByte() }
        return HuaweiCrypto.sha256(final).copyOfRange(0, 16)
    }

    /** GCM when encryptMethod is 1, CBC otherwise (the deviceSupportType does not enter here). */
    fun encryptBondingKey(encryptMethod: Int, data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray =
        if (encryptMethod == 0x01) HuaweiCrypto.aesGcmEncrypt(key, iv, data) else HuaweiCrypto.aesCbcEncrypt(key, iv, data)

    fun decryptBondingKey(encryptMethod: Int, data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray =
        if (encryptMethod == 0x01) HuaweiCrypto.aesGcmDecrypt(key, iv, data) else HuaweiCrypto.aesCbcDecrypt(key, iv, data)

    /** The PinCode 01/2C response is encrypted with the digest secret for the band's authVersion. */
    fun decryptPinCode(authVersion: Int, encryptMethod: Int, message: ByteArray, iv: ByteArray): ByteArray =
        decryptBondingKey(encryptMethod, message, digestSecret(authVersion), iv)

    fun encryptPinCode(authVersion: Int, encryptMethod: Int, pin: ByteArray, iv: ByteArray): ByteArray =
        encryptBondingKey(encryptMethod, pin, digestSecret(authVersion), iv)

    private fun pad64(bytes: ByteArray): ByteArray = if (bytes.size >= DIGEST_LENGTH) bytes else bytes.copyOf(DIGEST_LENGTH)
}

/** The HiChain (device_auth ISO) key schedule. */
object HuaweiHiChainCrypto {
    /** First pairing: SHA-256 of the PIN's upper-case hex. Reconnect: the stored auth token as is. */
    fun pskKey(pinCode: ByteArray?, storedToken: ByteArray?, operation: Int): ByteArray = if (operation == 1) {
        HuaweiCrypto.sha256(HuaweiHex.encode(requireNotNull(pinCode) { "HiChain bind needs the PIN" }).toByteArray(Charsets.UTF_8))
    } else {
        requireNotNull(storedToken) { "HiChain operation 2 needs the stored auth token" }
    }

    fun psk(pskKey: ByteArray, seed: ByteArray): ByteArray = HuaweiCrypto.hmacSha256(pskKey, seed)

    /** The token the band proves itself with: HMAC(psk, randPeer || randSelf || authIdSelf || authIdPeer). */
    fun peerToken(psk: ByteArray, randSelf: ByteArray, randPeer: ByteArray, authIdSelf: ByteArray, authIdPeer: ByteArray): ByteArray =
        HuaweiCrypto.hmacSha256(psk, randPeer + randSelf + authIdSelf + authIdPeer)

    /** The token NOOP proves itself with: HMAC(psk, randSelf || randPeer || authIdPeer || authIdSelf). */
    fun selfToken(psk: ByteArray, randSelf: ByteArray, randPeer: ByteArray, authIdSelf: ByteArray, authIdPeer: ByteArray): ByteArray =
        HuaweiCrypto.hmacSha256(psk, randSelf + randPeer + authIdPeer + authIdSelf)

    fun returnCodeMac(psk: ByteArray): ByteArray = HuaweiCrypto.hmacSha256(psk, ByteArray(4))

    fun sessionKey(psk: ByteArray, randSelf: ByteArray, randPeer: ByteArray): ByteArray =
        HuaweiCrypto.hkdfSha256(psk, randSelf + randPeer, HuaweiVendorConstants.INFO_ISO_SESSION_KEY.toByteArray(Charsets.UTF_8), 32)

    /** The 32-byte packet key a finished operation 2 installs. */
    fun returnKey(sessionKey: ByteArray, randSelf: ByteArray, randPeer: ByteArray): ByteArray =
        HuaweiCrypto.hkdfSha256(sessionKey, randSelf + randPeer, HuaweiVendorConstants.INFO_RETURN_KEY.toByteArray(Charsets.UTF_8), 32)
}
