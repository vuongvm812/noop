package com.noop.huawei.protocol

import com.noop.huawei.hex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VarInt and TLV. The expected literals reproduce Gadgetbridge's `TestVarInt.java` and `TestHuaweiTLV.java`
 * (AGPL-3.0, commit 6e4220682feb6917a5644304d9d7c760f30b6eaf), used as protocol test vectors with
 * attribution (see ATTRIBUTION.md).
 */
class HuaweiTlvTest {

    private fun varInt(value: Int, bytes: ByteArray) {
        assertEquals(bytes.size, HuaweiVarInt.size(value))
        assertArrayEquals(bytes, HuaweiVarInt.encode(value))
        assertEquals(value to bytes.size, HuaweiVarInt.decode(bytes, 0))
    }

    @Test fun varIntGadgetbridgeVectors() {
        varInt(0, byteArrayOf(0))
        varInt(17, byteArrayOf(17))
        varInt(0x80, hex("8100"))
        varInt(460, hex("834C"))
        assertEquals(460 to 2, HuaweiVarInt.decode(hex("00834C"), 1))
    }

    @Test fun varIntRoundTripsAndRejectsGarbage() {
        for (v in listOf(1, 127, 128, 16383, 16384, 2_097_151, 2_097_152, Int.MAX_VALUE)) {
            val e = HuaweiVarInt.encode(v)
            assertEquals(v to e.size, HuaweiVarInt.decode(e, 0))
        }
        assertThrows(HuaweiProtocolException::class.java) { HuaweiVarInt.decode(hex("81"), 0) }
        assertThrows(HuaweiProtocolException::class.java) { HuaweiVarInt.decode(hex("FFFFFFFFFF7F"), 0) }
    }

    @Test fun serializeGadgetbridgeVectors() {
        assertArrayEquals(ByteArray(0), HuaweiTlv().serialize())
        assertArrayEquals(hex("0100010142"), HuaweiTlv().put(0x01).put(0x01, byteArrayOf(0x42)).serialize())
    }

    @Test fun putVariantsGadgetbridgeVectors() {
        assertEquals(listOf(HuaweiTlv.Entry(1, ByteArray(0))), HuaweiTlv().put(1).entries)
        assertEquals(listOf(HuaweiTlv.Entry(1, hex("010203"))), HuaweiTlv().put(1, hex("010203")).entries)
        assertEquals(listOf(HuaweiTlv.Entry(1, hex("13"))), HuaweiTlv().putByte(1, 0x13).entries)
        assertEquals(
            listOf(HuaweiTlv.Entry(1, hex("01")), HuaweiTlv.Entry(2, hex("00"))),
            HuaweiTlv().putBoolean(1, true).putBoolean(2, false).entries,
        )
        assertEquals(listOf(HuaweiTlv.Entry(1, hex("DEADBEEF"))), HuaweiTlv().putInt(1, 0xDEADBEEF.toInt()).entries)
        assertEquals(listOf(HuaweiTlv.Entry(1, hex("CAFE"))), HuaweiTlv().putShort(1, 0xCAFE).entries)
        assertEquals(listOf(HuaweiTlv.Entry(1, hex("48656C6C6F20776F726C6421"))), HuaweiTlv().putString(1, "Hello world!").entries)
        assertEquals(listOf(HuaweiTlv.Entry(1, hex("01021337"))), HuaweiTlv().put(1, HuaweiTlv().putShort(1, 0x1337)).entries)
        assertEquals(2, HuaweiTlv().put(1).put(1).entries.size)
    }

    @Test fun parseGadgetbridgeVectors() {
        assertTrue(HuaweiTlv.parse(ByteArray(0)).isEmpty)
        assertEquals(listOf(HuaweiTlv.Entry(1, hex("01"))), HuaweiTlv.parse(hex("010101")).entries)
        assertEquals(listOf(HuaweiTlv.Entry(1, hex("DEADBEEF"))), HuaweiTlv.parse(hex("0104DEADBEEF")).entries)
        assertTrue(HuaweiTlv.parse(ByteArray(0), 0, 0).isEmpty)
        assertEquals(listOf(HuaweiTlv.Entry(1, ByteArray(0))), HuaweiTlv.parse(hex("9090900100"), 3, 2).entries)
        assertThrows(HuaweiProtocolException::class.java) { HuaweiTlv.parse(hex("0101")) }
        assertThrows(HuaweiProtocolException::class.java) { HuaweiTlv.parse(ByteArray(0), 0, 1) }
    }

    @Test fun parseSkipsTrailingPaddingByteAndHandlesLongValues() {
        assertEquals(listOf(HuaweiTlv.Entry(1, hex("AA"))), HuaweiTlv.parse(hex("0101AA00")).entries)
        val long = ByteArray(300) { it.toByte() }
        val tlv = HuaweiTlv().put(0x7E, long)
        val wire = tlv.serialize()
        assertArrayEquals(hex("7E822C"), wire.copyOfRange(0, 3))
        assertEquals(tlv, HuaweiTlv.parse(wire))
    }

    @Test fun gettersGadgetbridgeVectors() {
        val t = HuaweiTlv.parse(
            HuaweiTlv().put(1, hex("010203")).putBoolean(2, true).putBoolean(3, false).put(4, hex("DEADBEEF"))
                .put(5, hex("CAFE")).putString(6, "Hello world!").put(7, hex("0100")).serialize(),
        )
        assertArrayEquals(hex("010203"), t.bytes(1))
        assertTrue(t.boolean(2))
        assertFalse(t.boolean(3))
        assertEquals(0xDEADBEEF.toInt(), t.int(4))
        assertEquals(0xDEADBEEFL, t.u32(4))
        assertEquals(0xCAFE, t.u16(5))
        assertEquals("Hello world!", t.string(6))
        assertEquals(HuaweiTlv().put(1), t.tlv(7))
        assertTrue(t.contains(1))
        assertFalse(t.contains(9))
        assertThrows(HuaweiMissingTagException::class.java) { t.bytes(9) }
    }

    @Test fun removeGadgetbridgeVectors() {
        val t = HuaweiTlv().put(1, hex("1337")).put(2)
        assertArrayEquals(hex("1337"), t.remove(1))
        assertEquals(HuaweiTlv().put(2), t)
        assertNull(t.remove(1))

        val d = HuaweiTlv().put(1, hex("CA")).put(1, hex("FE"))
        assertArrayEquals(hex("FE"), d.remove(1))
        assertArrayEquals(hex("CA"), d.remove(1))
        assertTrue(d.isEmpty)
    }

    @Test fun toStringGadgetbridgeVectors() {
        assertEquals("Empty", HuaweiTlv().toString())
        assertEquals("{tag: 1 - Value: 0102} - {tag: 2 - Value: 0304}", HuaweiTlv().put(1, hex("0102")).put(2, hex("0304")).toString())
    }

    /** Gadgetbridge TestHuaweiTLV.testEncrypt / testDecrypt: key and IV all zero, AES-CBC. */
    @Test fun envelopeGadgetbridgeVector() {
        val cipher = HuaweiCipher(ByteArray(16), useGcm = false) { ByteArray(16) }
        val expectedCiphertext = hex("0EA001BB1EDACB098320407D971BF6D0")
        val sealed = cipher.seal(HuaweiTlv().put(1, hex("CAFE")))
        assertEquals(HuaweiTlv().put(0x7C, hex("01")).put(0x7D, ByteArray(16)).put(0x7E, expectedCiphertext), sealed)
        assertEquals(HuaweiTlv().put(1, hex("CAFE")), HuaweiTlv.parse(cipher.open(sealed)))
    }

    @Test fun nestedContainersAndResultCode() {
        val inner = HuaweiTlv().putByte(0x02, 1).put(0x03, hex("0102"))
        val outer = HuaweiTlv.parse(HuaweiTlv().put(0x81, inner).put(0x81, inner).put(0x7F, hex("000186A0")).serialize())
        assertEquals(listOf(inner, inner), outer.tlvs(0x81))
        assertEquals(HuaweiTlv.RESULT_OK, outer.resultCode())
        assertNull(HuaweiTlv().resultCode())
    }

    @Test fun fixedWidthGettersRejectWrongSizes() {
        val t = HuaweiTlv().put(1, hex("01")).put(2)
        assertThrows(HuaweiProtocolException::class.java) { t.u16(1) }
        assertThrows(HuaweiProtocolException::class.java) { t.byte(2) }
        assertEquals(1L, t.unsigned(1))
    }
}
