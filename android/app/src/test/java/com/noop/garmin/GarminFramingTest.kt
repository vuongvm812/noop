package com.noop.garmin

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** CRC-16/ARC, COBS and the streaming COBS decoder. Kotlin twin of GarminFramingTests.swift. */
class GarminFramingTest {

    @Test fun crcCatalogueCheckValue() {
        assertEquals(0xBB3D, GarminCrc.crc16("123456789".toByteArray()))
        assertEquals(0, GarminCrc.crc16(ByteArray(0)))
    }

    @Test fun runningCrcChainsAcrossChunks() {
        val data = oraclePattern(777, 11, 2)
        var running = 0
        for (start in 0 until data.size step 100) {
            running = GarminCrc.crc16(data.copyOfRange(start, minOf(start + 100, data.size)), running)
        }
        assertEquals(GarminCrc.crc16(data), running)
    }

    @Test fun cobsRoundTripsAndNeverEmitsInnerZero() {
        val inputs = listOf(
            ByteArray(0), bytes(0), bytes(0, 0, 0), bytes(1), oraclePattern(1_000, 1, 0), oraclePattern(509, 3, 7),
            ByteArray(254) { 0xFF.toByte() }, ByteArray(300),
        )
        for (input in inputs) {
            val encoded = GarminCobs.encode(input)
            assertEquals(0, encoded.first().toInt())
            assertEquals(0, encoded.last().toInt())
            assertTrue(encoded.copyOfRange(1, encoded.size - 1).none { it.toInt() == 0 })
            assertArrayEquals(input, GarminCobs.decode(encoded))
        }
    }

    @Test fun cobsRejectsMalformedBodies() {
        assertNull(GarminCobs.decodeBody(bytes(0x05, 0x01)))
        assertNull(GarminCobs.decodeBody(bytes(0x02, 0x00)))
        assertNull(GarminCobs.decode(bytes(0x02, 0x11, 0x00)))
        assertNull(GarminCobs.decode(bytes(0x00, 0x02, 0x11)))
    }

    @Test fun streamDecoderSurvivesALostPacket() {
        val decoder = GarminCobsStreamDecoder()
        val first = GarminCobs.encode(bytes(1, 2, 3, 4, 5, 6))
        val second = GarminCobs.encode(bytes(9, 0, 9))
        assertTrue(decoder.feed(first.copyOfRange(0, 4)).isEmpty())
        val got = decoder.feed(second)
        assertEquals(1, got.size)
        assertArrayEquals(bytes(9, 0, 9), got[0])
        assertEquals(1, decoder.droppedFrames)
        assertArrayEquals(bytes(1, 2, 3, 4, 5, 6), decoder.feed(first).single())
    }

    @Test fun streamDecoderDropsBytesBeforeFirstDelimiterAndHandlesSplits() {
        val decoder = GarminCobsStreamDecoder()
        val frame = GarminCobs.encode(oraclePattern(600, 5, 1))
        val got = ArrayList<ByteArray>()
        got += decoder.feed(bytes(0x42, 0x43))
        for (b in frame) got += decoder.feed(byteArrayOf(b))
        assertEquals(1, got.size)
        assertArrayEquals(oraclePattern(600, 5, 1), got[0])
        assertEquals(0, decoder.droppedFrames)
    }

    @Test fun streamDecoderBoundsMemory() {
        val decoder = GarminCobsStreamDecoder(maxBodyBytes = 8)
        assertTrue(decoder.feed(bytes(0x00) + ByteArray(50) { 1 }).isEmpty())
        assertEquals(1, decoder.droppedFrames)
        assertArrayEquals(bytes(7), decoder.feed(GarminCobs.encode(bytes(7))).single())
    }
}
