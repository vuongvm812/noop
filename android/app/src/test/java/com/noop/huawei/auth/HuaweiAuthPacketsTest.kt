package com.noop.huawei.auth

import com.noop.huawei.hex
import com.noop.huawei.protocol.GadgetbridgeHuaweiVectors as V
import com.noop.huawei.protocol.HuaweiCommand
import com.noop.huawei.protocol.HuaweiCrypto
import com.noop.huawei.protocol.HuaweiFirstPairingGrant
import com.noop.huawei.protocol.HuaweiHex
import com.noop.huawei.protocol.HuaweiPacketCodec
import com.noop.huawei.protocol.HuaweiRequest
import com.noop.huawei.protocol.HuaweiTlv
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The auth request builders and key derivations. The Bond, BondParams and Auth expected literals reproduce
 * Gadgetbridge's `packets/TestDeviceConfig.java` (AGPL-3.0, commit 6e4220682feb6917a5644304d9d7c760f30b6eaf),
 * used as protocol test vectors with attribution (see ATTRIBUTION.md).
 */
class HuaweiAuthPacketsTest {

    private val serial = hex("545664544D44")
    private val grant = HuaweiFirstPairingGrant.forPairingWizard()

    /** Gadgetbridge testBondRequest: authVersion 1, band "FF:FF:FF:FF:FF:CC", zero session key and IV, CBC. */
    @Test fun bondRequestVector() {
        val iv = ByteArray(16)
        val encryptionKey = HuaweiAuthCrypto.bondEncryptionKey(1, "FF:FF:FF:FF:FF:CC")
        val key = HuaweiAuthCrypto.encryptBondingKey(0, ByteArray(16), encryptionKey, iv)
        val tlv = HuaweiAuthPackets.bond(serial, key, iv)
        assertEquals(HuaweiTlv().put(1).putByte(3, 0).put(5, serial).put(6, key).put(7, iv), tlv)
        val request = HuaweiRequest.firstPairing(HuaweiCommand.BOND, tlv, grant)
        assertArrayEquals(hex(V.BOND_REQUEST), HuaweiPacketCodec().encode(request).single())
        // The bonded key round-trips with the same derivation the band would use.
        assertArrayEquals(ByteArray(16), HuaweiAuthCrypto.decryptBondingKey(0, key, encryptionKey, iv))
    }

    @Test fun bondEncryptionKeyDependsOnVersionAndAddress() {
        val v1 = HuaweiAuthCrypto.bondEncryptionKey(1, "FF:FF:FF:FF:FF:CC")
        assertEquals(16, v1.size)
        assertNotEquals(v1.toList(), HuaweiAuthCrypto.bondEncryptionKey(2, "FF:FF:FF:FF:FF:CC").toList())
        assertNotEquals(v1.toList(), HuaweiAuthCrypto.bondEncryptionKey(1, "FF:FF:FF:FF:FF:CD").toList())
        assertArrayEquals(HuaweiAuthCrypto.bondEncryptionKey(2, "C8:2A:DD:10:20:30"), HuaweiAuthCrypto.bondEncryptionKey(3, "C8:2A:DD:10:20:30"))
        assertThrows(IllegalArgumentException::class.java) { HuaweiAuthCrypto.bondEncryptionKey(1, "AA:BB") }
    }

    @Test fun bondParamsRequestVector() {
        val tlv = HuaweiAuthPackets.bondParams(serial, "FF:FF:FF:FF:FF:CC".toByteArray())
        assertArrayEquals(hex(V.BOND_PARAMS_REQUEST), HuaweiPacketCodec().encode(HuaweiRequest.of(HuaweiCommand.BOND_PARAMS, tlv)).single())
    }

    @Test fun authRequestVector() {
        val tlv = HuaweiAuthPackets.auth(hex(V.AUTH_CHALLENGE), hex(V.AUTH_NONCE), authAlgo = null)
        assertArrayEquals(hex(V.AUTH_REQUEST), HuaweiPacketCodec().encode(HuaweiRequest.of(HuaweiCommand.AUTH, tlv)).single())
        assertEquals(3, HuaweiAuthPackets.auth(ByteArray(32), ByteArray(18), authAlgo = 1).entries.size)
    }

    @Test fun securityNegotiationLayouts() {
        val id = "ABCD".toByteArray()
        assertEquals(
            HuaweiTlv().putByte(1, 2).putByte(2, 1).put(5, id).putByte(3, 1).putByte(4, 0),
            HuaweiAuthPackets.securityNegotiation(2, id, "NOOP", encryptMethod = 0),
        )
        assertEquals(
            HuaweiTlv().putByte(1, 4).putByte(2, 1).put(5, id).putByte(3, 1).putByte(4, 0).put(6).putString(7, "NOOP").putByte(0x0D, 1),
            HuaweiAuthPackets.securityNegotiation(4, id, "NOOP", encryptMethod = 1),
        )
    }

    /** The normal-mode digest written out from its definition, checked against the implementation. */
    @Test fun normalDigestMatchesDefinition() {
        val nonce = ByteArray(32) { it.toByte() }
        for (version in 1..4) {
            val secret = HuaweiAuthCrypto.digestSecret(version)
            val message = if (version == 4) hex("0400") else hex("0100")
            val step1 = HuaweiCrypto.hmacSha256(secret + message, nonce)
            val expected = HuaweiCrypto.hmacSha256(step1, nonce) + step1
            assertArrayEquals(expected, HuaweiAuthCrypto.digestChallenge(version, 0, 0, null, nonce))
            val r1 = HuaweiCrypto.hmacSha256(secret + hex("0110"), nonce)
            assertArrayEquals(HuaweiCrypto.hmacSha256(r1, nonce) + r1, HuaweiAuthCrypto.digestResponse(version, 0, 0, null, nonce))
        }
        assertArrayEquals(HuaweiVendorConstants.DIGEST_SECRET_V1, HuaweiAuthCrypto.digestSecret(4))
        assertArrayEquals(HuaweiVendorConstants.DIGEST_SECRET_V3, HuaweiAuthCrypto.digestSecret(9))
    }

    @Test fun liteDigestVariants() {
        val nonce = ByteArray(32) { (it * 3).toByte() }
        val pin = "135790".toByteArray()
        val hmac = HuaweiAuthCrypto.digestChallenge(1, 0, 2, pin, nonce)
        val pbkdf2 = HuaweiAuthCrypto.digestChallenge(1, 1, 2, pin, nonce)
        assertEquals(64, hmac.size)
        assertEquals(64, pbkdf2.size)
        assertNotEquals(hmac.toList(), pbkdf2.toList())
        // authVersion 2: HMAC over the raw key + message, the key itself in bytes 32..48.
        val key = ByteArray(16) { 7 }
        val v2 = HuaweiAuthCrypto.digestChallenge(2, 0, 2, key, nonce)
        assertArrayEquals(key, v2.copyOfRange(32, 48))
        assertArrayEquals(HuaweiCrypto.hmacSha256(key + hex("0100"), nonce), v2.copyOfRange(0, 32))
        assertThrows(IllegalArgumentException::class.java) { HuaweiAuthCrypto.digestChallenge(1, 0, 2, null, nonce) }
    }

    @Test fun pinCodeRoundTripsUnderDigestSecret() {
        for (method in listOf(0, 1)) {
            val iv = ByteArray(if (method == 1) 12 else 16) { it.toByte() }
            val enc = HuaweiAuthCrypto.encryptPinCode(3, method, "246810".toByteArray(), iv)
            assertArrayEquals("246810".toByteArray(), HuaweiAuthCrypto.decryptPinCode(3, method, enc, iv))
        }
    }

    @Test fun hiChainPskKeyUsesUppercasePinHex() {
        val pin = hex("0A0B")
        assertArrayEquals(HuaweiCrypto.sha256("0A0B".toByteArray()), HuaweiHiChainCrypto.pskKey(pin, null, 1))
        assertArrayEquals(hex("01"), HuaweiHiChainCrypto.pskKey(null, hex("01"), 2))
        assertEquals("0A0B", HuaweiHex.encode(pin))
    }

    @Test fun jsonWriterKeepsOrderAndEscapes() {
        assertEquals(
            """{"a":1,"b":{"c":"x\"y"},"d":false,"e":12345678901}""",
            HuaweiJson.write(linkedMapOf("a" to 1, "b" to linkedMapOf("c" to "x\"y"), "d" to false, "e" to 12345678901L)),
        )
    }

    @Test fun phoneIdentity() {
        val id = HuaweiPhoneIdentity.generate(com.noop.huawei.SeededHuaweiRandom(4))
        assertEquals(32, id.androidId.length)
        assertEquals(17, id.phoneAddress.length)
        assertEquals(id.phoneAddress.replace(":", "").substring(6, 12), String(id.serial))
        assertEquals("NOOP", id.phoneModel)
    }
}
