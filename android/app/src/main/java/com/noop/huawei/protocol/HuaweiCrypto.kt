package com.noop.huawei.protocol

import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** A crypto operation failed: bad key/IV size, padding error, or a GCM tag that does not verify. */
class HuaweiCryptoException(message: String, cause: Throwable? = null) : HuaweiProtocolException(message, cause)

/** Source of random bytes. Injected everywhere so tests can replay a session byte for byte. */
fun interface HuaweiRandom {
    fun nextBytes(count: Int): ByteArray

    companion object {
        /** The production source. */
        val secure: HuaweiRandom = object : HuaweiRandom {
            private val rng = SecureRandom()
            override fun nextBytes(count: Int): ByteArray = ByteArray(count).also { rng.nextBytes(it) }
        }
    }
}

/**
 * The JCA primitives the Huawei transport and auth use. All are standard constructions:
 * HMAC-SHA256 (RFC 2104/4231), HKDF-SHA256 (RFC 5869), PBKDF2-HMAC-SHA256 (RFC 8018), AES-GCM with a
 * 128-bit tag (12- or 16-byte IV) and AES-CBC with PKCS#5 padding.
 */
object HuaweiCrypto {
    private const val HASH_LENGTH = 32
    private const val GCM_TAG_BITS = 128

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        // SecretKeySpec rejects an empty key; RFC 2104 zero-pads the key to the block size, so an
        // all-zero key of any length up to the block size is the same key.
        mac.init(SecretKeySpec(if (key.isEmpty()) ByteArray(HASH_LENGTH) else key, "HmacSHA256"))
        return mac.doFinal(message)
    }

    /** RFC 5869 extract-then-expand. An empty [salt] means HashLen zero bytes, as the RFC specifies. */
    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * HASH_LENGTH) { "HKDF length $length out of range" }
        val prk = hmacSha256(if (salt.isEmpty()) ByteArray(HASH_LENGTH) else salt, ikm)
        val out = ByteArray(length)
        var t = ByteArray(0)
        var written = 0
        var counter = 1
        while (written < length) {
            t = hmacSha256(prk, t + info + byteArrayOf(counter.toByte()))
            val n = minOf(t.size, length - written)
            t.copyInto(out, written, 0, n)
            written += n
            counter++
        }
        return out
    }

    /** PBKDF2-HMAC-SHA256. The password is encoded as UTF-8, as the JCA provider does. */
    fun pbkdf2Sha256(password: String, salt: ByteArray, iterations: Int, lengthBytes: Int): ByteArray = guard("PBKDF2") {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, lengthBytes * 8)
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    fun aesGcmEncrypt(key: ByteArray, iv: ByteArray, plaintext: ByteArray, aad: ByteArray? = null): ByteArray =
        gcm(Cipher.ENCRYPT_MODE, key, iv, plaintext, aad)

    fun aesGcmDecrypt(key: ByteArray, iv: ByteArray, ciphertext: ByteArray, aad: ByteArray? = null): ByteArray =
        gcm(Cipher.DECRYPT_MODE, key, iv, ciphertext, aad)

    fun aesCbcEncrypt(key: ByteArray, iv: ByteArray, plaintext: ByteArray): ByteArray =
        cbc(Cipher.ENCRYPT_MODE, key, iv, plaintext)

    fun aesCbcDecrypt(key: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray =
        cbc(Cipher.DECRYPT_MODE, key, iv, ciphertext)

    private fun gcm(mode: Int, key: ByteArray, iv: ByteArray, input: ByteArray, aad: ByteArray?): ByteArray = guard("AES-GCM") {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        if (aad != null) c.updateAAD(aad)
        c.doFinal(input)
    }

    private fun cbc(mode: Int, key: ByteArray, iv: ByteArray, input: ByteArray): ByteArray = guard("AES-CBC") {
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        c.doFinal(input)
    }

    private inline fun <T> guard(what: String, block: () -> T): T = try {
        block()
    } catch (e: GeneralSecurityException) {
        throw HuaweiCryptoException("$what failed: ${e.javaClass.simpleName}", e)
    } catch (e: IllegalArgumentException) {
        throw HuaweiCryptoException("$what failed: ${e.message}", e)
    }
}
