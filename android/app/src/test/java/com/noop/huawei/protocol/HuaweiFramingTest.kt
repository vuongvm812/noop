package com.noop.huawei.protocol

import com.noop.huawei.hex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Framing, slicing, reassembly and the encrypted packet path. Expected literals reproduce Gadgetbridge's
 * `TestHuaweiPacket.java` (AGPL-3.0, commit 6e4220682feb6917a5644304d9d7c760f30b6eaf), used as protocol
 * test vectors with attribution (see ATTRIBUTION.md). Its encrypted vectors use an all-zero key and IV
 * with AES-CBC.
 */
class HuaweiFramingTest {

    private val zeroCipher = HuaweiCipher(ByteArray(16), useGcm = false) { ByteArray(16) }

    @Test fun crcIsXmodem() {
        // CRC-16/XMODEM check value for "123456789".
        assertEquals(0x31C3, HuaweiCrc16.xmodem("123456789".toByteArray()))
    }

    @Test fun emptyInputIsNotAFrame() {
        assertThrows(HuaweiProtocolException::class.java) { HuaweiFraming.decodeSingle(ByteArray(0)) }
    }

    @Test fun unknownUnencryptedPacketParse() {
        val frame = HuaweiFraming.decodeSingle(hex("5A0007007F7F0102030440B6"))
        assertEquals(0x7F, frame.serviceId)
        assertEquals(0x7F, frame.commandId)
        assertEquals(HuaweiTlv().putShort(0x01, 0x0304), HuaweiPacketCodec.decodeTlv(frame, null))
    }

    @Test fun unknownEncryptedPacketParse() {
        val frame = HuaweiFraming.decodeSingle(
            hex("5A002A007F7F7C01017D10000000000000000000000000000000007E109E40E1EA15F650808C4519D52ABB29B8D524"),
        )
        assertEquals(0x7F, frame.serviceId)
        assertEquals(HuaweiTlv().putShort(0x01, 0x0304), HuaweiPacketCodec.decodeTlv(frame, zeroCipher))
    }

    @Test fun knownEncryptedPacketParse() {
        val frame = HuaweiFraming.decodeSingle(
            hex("5A002A000B017C01017D10000000000000000000000000000000007E102800996F2ACB623AE6542854F8AB5483F4F4"),
        )
        assertEquals(0x0B, frame.serviceId)
        assertEquals(0x01, frame.commandId)
        assertEquals(HuaweiTlv().putBoolean(0x01, false), HuaweiPacketCodec.decodeTlv(frame, zeroCipher))
    }

    @Test fun encryptedFrameWithoutSessionIsRejected() {
        val frame = HuaweiFraming.decodeSingle(
            hex("5A002A000B017C01017D10000000000000000000000000000000007E102800996F2ACB623AE6542854F8AB5483F4F4"),
        )
        assertThrows(HuaweiProtocolException::class.java) { HuaweiPacketCodec.decodeTlv(frame, null) }
    }

    @Test fun unencryptedUnslicedSerialize() {
        val out = HuaweiFraming.encode(0x01, 0x02, HuaweiTlv().putInt(0x03, 0x05060708).serialize(), sliced = false)
        assertEquals(1, out.size)
        assertArrayEquals(hex("5A0009000102030405060708A4F0"), out[0])
    }

    @Test fun encryptedUnslicedSerialize() {
        val body = zeroCipher.seal(HuaweiTlv().putInt(0x03, 0x05060708)).serialize()
        val out = HuaweiFraming.encode(0x01, 0x02, body, sliced = false)
        assertArrayEquals(
            hex("5A002A0001027C01017D10000000000000000000000000000000007E103B89FC79D9055EED5235FE16A08A4D5393C7"),
            out.single(),
        )
    }

    @Test fun unencryptedSlicedSerialize() {
        val tlv = HuaweiTlv().putInt(1, 0).putInt(2, 0).putInt(3, 0).putInt(4, 0)
        val out = HuaweiFraming.encode(0x01, 0x02, tlv.serialize(), sliced = true, sliceSize = 0x10)
        assertEquals(3, out.size)
        assertArrayEquals(hex("5A000B0100010201040000000002CC98"), out[0])
        assertArrayEquals(hex("5A000B0201040000000003040000FAD3"), out[1])
        assertArrayEquals(hex("5A000A0302000004040000000037CA"), out[2])

        val assembler = HuaweiFrameAssembler()
        assertTrue(assembler.feed(out[0]).isEmpty())
        assertTrue(assembler.feed(out[1]).isEmpty())
        assertEquals(listOf(HuaweiFrame(0x01, 0x02, tlv.serialize())), assembler.feed(out[2]))
        assertFalse(assembler.hasPendingBytes)
    }

    /** Gadgetbridge's earphone vectors exercise only framing and CRC here (NOOP sends none of them). */
    @Test fun framingCrcVectors() {
        val cases = listOf(
            Triple(0xB4, HuaweiTlv().putByte(1, 2).putByte(2, 1), "5A0009002BB401010202010103C0"),
            Triple(0xB4, HuaweiTlv().putByte(1, 2).putByte(2, 0), "5A0009002BB401010202010013E1"),
            Triple(0xB4, HuaweiTlv().putByte(1, 2).putByte(3, 0), "5A0009002BB401010203010024D1"),
            Triple(0x87, HuaweiTlv().putByte(1, 1), "5A0006002B870101013EE4"),
            Triple(0x88, HuaweiTlv().put(1), "5A0005002B8801009182"),
            Triple(0x5D, HuaweiTlv().put(1, hex("0001")), "5A0007002B5D010200017F42"),
            Triple(0x6C, HuaweiTlv().putByte(1, 0), "5A0006002B6C010100B430"),
        )
        for ((cid, tlv, expected) in cases) {
            assertArrayEquals(hex(expected), HuaweiFraming.encode(0x2B, cid, tlv.serialize(), sliced = true).single())
        }
        val ok = HuaweiPacketCodec.decodeTlv(HuaweiFraming.decodeSingle(hex("5A0009002B6C7F04000186A0A497")), null)
        assertEquals(HuaweiTlv.RESULT_OK, ok.resultCode())
    }

    @Test fun assemblerBuffersPartialFramesAndSplitsBatches() {
        val a = hex("5A0007007F7F0102030440B6")
        val b = hex("5A0009000102030405060708A4F0")
        val asm = HuaweiFrameAssembler()
        assertTrue(asm.feed(a.copyOfRange(0, 2)).isEmpty())
        assertTrue(asm.feed(a.copyOfRange(2, 7)).isEmpty())
        val joined = a.copyOfRange(7, a.size) + b + b.copyOfRange(0, 4)
        assertEquals(2, asm.feed(joined).size)
        assertTrue(asm.hasPendingBytes)
        assertEquals(1, asm.feed(b.copyOfRange(4, b.size)).size)
        assertFalse(asm.hasPendingBytes)
    }

    @Test fun assemblerRejectsCorruptionAndRecovers() {
        val good = hex("5A0007007F7F0102030440B6")
        val asm = HuaweiFrameAssembler()
        val badCrc = good.copyOf().also { it[it.size - 1] = 0 }
        assertThrows(HuaweiProtocolException::class.java) { asm.feed(badCrc) }
        assertThrows(HuaweiProtocolException::class.java) { asm.feed(hex("A5000100")) }
        assertEquals(1, asm.feed(good).size)
        // A middle slice with no first slice.
        val mid = HuaweiFraming.encode(1, 2, ByteArray(40), sliced = true, sliceSize = 0x10)[1]
        assertThrows(HuaweiProtocolException::class.java) { asm.feed(mid) }
        assertFalse(asm.hasPendingBytes)
    }

    @Test fun slicedRoundTripAcrossSizes() {
        for (slice in listOf(HuaweiFraming.MIN_SLICE_SIZE, 0x10, 0x14, 0x40, 0xF4)) {
            for (size in 0..520 step 7) {
                val body = ByteArray(size) { (it * 31 + size).toByte() }
                val frames = HuaweiFraming.encode(0x01, 0x28, body, sliced = true, sliceSize = slice)
                assertTrue(frames.all { it.size <= maxOf(slice, size + 8) })
                val asm = HuaweiFrameAssembler()
                val out = frames.flatMap { asm.feed(it) }
                assertEquals("slice=$slice size=$size", listOf(HuaweiFrame(0x01, 0x28, body)), out)
            }
        }
    }

    @Test fun rawCommandsAreNotTlv() {
        assertTrue(HuaweiFraming.isRawCommand(0x0A, 0x05))
        assertTrue(HuaweiFraming.isRawCommand(0x28, 0x06))
        assertTrue(HuaweiFraming.isRawCommand(0x2C, 0x05))
        assertTrue(HuaweiFraming.isRawCommand(0x1C, 0x05))
        assertFalse(HuaweiFraming.isRawCommand(0x01, 0x05))
        val frame = HuaweiFraming.decodeSingle(HuaweiFraming.encode(0x0A, 0x05, hex("FFFF"), sliced = false).single())
        assertArrayEquals(hex("FFFF"), frame.body)
        assertThrows(HuaweiProtocolException::class.java) { HuaweiPacketCodec.decodeTlv(frame, null) }
    }

    @Test fun codecRefusesEncryptedCommandWithoutSession() {
        val codec = HuaweiPacketCodec()
        assertThrows(IllegalStateException::class.java) { codec.encode(HuaweiDeviceConfig.Battery.request()) }
        codec.config = HuaweiLinkConfig(cipher = zeroCipher)
        val frame = HuaweiFraming.decodeSingle(codec.encode(HuaweiDeviceConfig.Battery.request()).single())
        assertTrue(HuaweiCipher.isEnvelope(HuaweiTlv.parse(frame.body)))
        assertEquals(HuaweiTlv().put(1), codec.decodeTlv(frame))
    }
}
