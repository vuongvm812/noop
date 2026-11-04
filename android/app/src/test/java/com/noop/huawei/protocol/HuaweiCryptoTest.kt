package com.noop.huawei.protocol

import com.noop.huawei.SeededHuaweiRandom
import com.noop.huawei.hex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Crypto primitives against the published RFC vectors, plus the per-packet envelope and IV rules. */
class HuaweiCryptoTest {

    // RFC 4231 test cases 1, 2, 3 and 6.
    @Test fun hmacSha256Rfc4231() {
        assertArrayEquals(
            hex("b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7"),
            HuaweiCrypto.hmacSha256(ByteArray(20) { 0x0b }, "Hi There".toByteArray()),
        )
        assertArrayEquals(
            hex("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843"),
            HuaweiCrypto.hmacSha256("Jefe".toByteArray(), "what do ya want for nothing?".toByteArray()),
        )
        assertArrayEquals(
            hex("773ea91e36800e46854db8ebd09181a72959098b3ef8c122d9635514ced565fe"),
            HuaweiCrypto.hmacSha256(ByteArray(20) { 0xAA.toByte() }, ByteArray(50) { 0xDD.toByte() }),
        )
        assertArrayEquals(
            hex("60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54"),
            HuaweiCrypto.hmacSha256(ByteArray(131) { 0xAA.toByte() }, "Test Using Larger Than Block-Size Key - Hash Key First".toByteArray()),
        )
    }

    // RFC 5869 test cases 1 and 3 (case 3: empty salt and info).
    @Test fun hkdfSha256Rfc5869() {
        assertArrayEquals(
            hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"),
            HuaweiCrypto.hkdfSha256(ByteArray(22) { 0x0b }, ByteArray(13) { it.toByte() }, ByteArray(10) { (0xF0 + it).toByte() }, 42),
        )
        assertArrayEquals(
            hex("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8"),
            HuaweiCrypto.hkdfSha256(ByteArray(22) { 0x0b }, ByteArray(0), ByteArray(0), 42),
        )
    }

    // RFC 7914 §11 PBKDF2-HMAC-SHA256 vectors.
    @Test fun pbkdf2Sha256Rfc7914() {
        assertArrayEquals(
            hex(
                "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc" +
                    "49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783",
            ),
            HuaweiCrypto.pbkdf2Sha256("passwd", "salt".toByteArray(), 1, 64),
        )
        assertArrayEquals(
            hex(
                "4ddcd8f60b98be21830cee5ef22701f9641a4418d04c0414aeff08876b34ab56" +
                    "a1d425a1225833549adb841b51c9b3176a272bdebba1d078478f62b397f33c8d",
            ),
            HuaweiCrypto.pbkdf2Sha256("Password", "NaCl".toByteArray(), 80000, 64),
        )
    }

    @Test fun aesGcmRoundTripsWith12And16ByteIvAndAad() {
        val rng = SeededHuaweiRandom(7)
        for (keySize in listOf(16, 32)) for (ivSize in listOf(12, 16)) {
            val key = rng.nextBytes(keySize)
            val iv = rng.nextBytes(ivSize)
            val plain = rng.nextBytes(37)
            val aad = "hichain_iso_exchange".toByteArray()
            val ct = HuaweiCrypto.aesGcmEncrypt(key, iv, plain, aad)
            assertEquals(plain.size + 16, ct.size)
            assertArrayEquals(plain, HuaweiCrypto.aesGcmDecrypt(key, iv, ct, aad))
            val tampered = ct.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
            assertThrows(HuaweiCryptoException::class.java) { HuaweiCrypto.aesGcmDecrypt(key, iv, tampered, aad) }
            assertThrows(HuaweiCryptoException::class.java) { HuaweiCrypto.aesGcmDecrypt(key, iv, ct, "other".toByteArray()) }
        }
    }

    // NIST GCM test case 2 (AES-128, zero key, 96-bit zero IV, one zero block).
    @Test fun aesGcmKnownAnswer() {
        assertArrayEquals(
            hex("0388dace60b6a392f328c2b971b2fe78ab6e47d42cec13bdf53a67b21257bddf"),
            HuaweiCrypto.aesGcmEncrypt(ByteArray(16), ByteArray(12), ByteArray(16)),
        )
    }

    @Test fun aesCbcRoundTripsAndRejectsBadPadding() {
        val key = ByteArray(16) { it.toByte() }
        val iv = ByteArray(16) { (15 - it).toByte() }
        val ct = HuaweiCrypto.aesCbcEncrypt(key, iv, "abc".toByteArray())
        assertEquals(16, ct.size)
        assertArrayEquals("abc".toByteArray(), HuaweiCrypto.aesCbcDecrypt(key, iv, ct))
        assertThrows(HuaweiCryptoException::class.java) { HuaweiCrypto.aesCbcDecrypt(ByteArray(16), iv, ct) }
    }

    @Test fun counterIvIncrementsBeforeUseAndWrapsToOne() {
        val src = HuaweiCounterIvSource(0x10, SeededHuaweiRandom(1))
        val a = src.nextIv()
        val b = src.nextIv()
        assertEquals(16, a.size)
        assertArrayEquals(hex("00000011"), a.copyOfRange(12, 16))
        assertArrayEquals(hex("00000012"), b.copyOfRange(12, 16))
        assertFalse(a.copyOfRange(0, 12).contentEquals(b.copyOfRange(0, 12)))

        val wrap = HuaweiCounterIvSource(0xFFFFFFFEL, SeededHuaweiRandom(2))
        assertArrayEquals(hex("FFFFFFFF"), wrap.nextIv().copyOfRange(12, 16))
        assertArrayEquals(hex("00000001"), wrap.nextIv().copyOfRange(12, 16))
        assertEquals(1L, HuaweiCounterIvSource.next(0xFFFFFFFFL))
    }

    @Test fun ivSourceAndModeSelection() {
        assertTrue(HuaweiCipher.ivSourceFor(4, 0, SeededHuaweiRandom(3)) is HuaweiRandomIvSource)
        assertTrue(HuaweiCipher.ivSourceFor(0, 0, SeededHuaweiRandom(3)) is HuaweiCounterIvSource)
        assertTrue(HuaweiCipher.usesGcm(encryptMethod = 1, deviceSupportType = 0))
        assertTrue(HuaweiCipher.usesGcm(encryptMethod = 0, deviceSupportType = 4))
        assertFalse(HuaweiCipher.usesGcm(encryptMethod = 0, deviceSupportType = 1))
    }

    @Test fun envelopeRoundTripsInEveryMode() {
        val rng = SeededHuaweiRandom(11)
        for (gcm in listOf(false, true)) for (keySize in listOf(16, 32)) for (dst in listOf(0, 4)) {
            val key = rng.nextBytes(keySize)
            val tx = HuaweiCipher(key, gcm, HuaweiCipher.ivSourceFor(dst, 5, rng))
            val rx = HuaweiCipher(key, gcm, HuaweiRandomIvSource(rng))
            val tlv = HuaweiTlv().putString(1, "payload").putInt(2, 7)
            val env = tx.seal(tlv)
            assertTrue(HuaweiCipher.isEnvelope(env))
            assertEquals(16, env.bytes(HuaweiCipher.TAG_IV).size)
            assertEquals(tlv, HuaweiTlv.parse(rx.open(env)))
        }
        assertThrows(IllegalArgumentException::class.java) { HuaweiCipher(ByteArray(24), true, HuaweiRandomIvSource()) }
    }

    @Test fun secureRandomIsNotConstant() {
        val a = HuaweiRandom.secure.nextBytes(16)
        val b = HuaweiRandom.secure.nextBytes(16)
        assertNotEquals(a.toList(), b.toList())
    }
}
