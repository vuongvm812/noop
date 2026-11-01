package com.noop.garmin

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** GFDI framing and every message codec: round trips plus malformed input. Twin of GarminCodecTests.swift. */
class GarminCodecTest {

    private fun ok(b: ByteArray) = (GarminGfdi.decode(b) as GarminGfdi.Result.Success).frame
    private fun inbound(type: Int, payload: ByteArray) = GarminInbound.decode(ok(oracleFrame(type, payload)))

    // MARK: GFDI frame

    @Test fun gfdiRejectsShortBadCrcAndWrongLength() {
        val good = oracleFrame(5024, bytes(1, 2, 3))
        assertArrayEquals(bytes(1, 2, 3), ok(good).payload)
        val badCrc = good.copyOf().also { it[4] = (it[4].toInt() xor 1).toByte() }
        assertTrue((GarminGfdi.decode(badCrc) as GarminGfdi.Result.Failure).error is GarminGfdi.DecodeError.CrcMismatch)
        val oversize = good.copyOf().also { it[0] = 0xFF.toByte(); it[1] = 0xFF.toByte() }
        assertEquals(
            GarminGfdi.Result.Failure(GarminGfdi.DecodeError.LengthMismatch(0xFFFF, good.size)),
            GarminGfdi.decode(oversize),
        )
        assertEquals(GarminGfdi.Result.Failure(GarminGfdi.DecodeError.TooShort), GarminGfdi.decode(good.copyOfRange(0, 5)))
    }

    @Test fun compactTypeExpandsAndKeepsSequence() {
        val f = ok(oracleFrame(0x8000 or 0x0500 or 0x18, ByteArray(0)))
        assertEquals(5024, f.type)
        assertEquals(5, f.compactSequence)
    }

    @Test fun garminEpoch() {
        assertEquals(0L, GarminGfdi.garminTime(631_065_600))
        assertEquals(631_066_600L, GarminGfdi.unixTime(1_000))
    }

    // MARK: Outbound round trips: encode, then read every field back from the wire bytes.

    private fun wire(m: GarminOutbound): Pair<Int, GarminByteReader> {
        val decodedCobs = GarminCobs.decode(m.frame().bytes)!!
        assertArrayEquals(m.message(), decodedCobs)
        val f = ok(decodedCobs)
        return f.type to GarminByteReader(f.payload)
    }

    @Test fun deviceInformationReplyRoundTrip() {
        val host = GarminHostIdentity(150, 7, 99, 210, 512, "Phone", "NOOP", "M")
        val (type, r) = wire(GarminOutbound.DeviceInformationReply(host, 1))
        assertEquals(5000, type)
        assertEquals(5024, r.u16()); assertEquals(0, r.u8())
        assertEquals(150, r.u16()); assertEquals(7, r.u16()); assertEquals(99L, r.u32())
        assertEquals(210, r.u16()); assertEquals(512, r.u16())
        assertEquals("Phone", r.string8()); assertEquals("NOOP", r.string8()); assertEquals("M", r.string8())
        assertEquals(1, r.u8()); assertEquals(0, r.remaining)
    }

    @Test fun configurationRoundTrip() {
        val caps = setOf(GarminCapability.SYNC, GarminCapability.MULTI_LINK_SERVICE, GarminCapability.CURRENT_TIME_REQUEST_SUPPORT)
        val (type, r) = wire(GarminOutbound.Configuration(caps))
        assertEquals(5050, type)
        val bitmap = r.take(r.u8()!!)!!
        assertEquals(caps.map { it.bit }.toSet(), GarminCapabilityBitmap.indices(bitmap).toSet())
        val decoded = GarminInbound.decode(ok(GarminOutbound.Configuration(caps).message())) as GarminInbound.Configuration
        assertArrayEquals(bitmap, decoded.capabilities)
    }

    @Test fun authNegotiationEchoRoundTrip() {
        val m = inbound(5101, bytes(4) + oracleLE32(0x0102_0304)) as GarminInbound.AuthNegotiation
        val (type, r) = wire(GarminOutbound.AuthNegotiationReply(m.unknown, m.flags))
        assertEquals(5000, type)
        assertEquals(5101, r.u16()); assertEquals(0, r.u8()); assertEquals(0, r.u8())
        assertEquals(4, r.u8()); assertEquals(0x0102_0304L, r.u32())
    }

    @Test fun currentTimeRoundTrip() {
        assertEquals(GarminInbound.CurrentTimeRequest(31), inbound(5052, oracleLE32(31)))
        val (_, r) = wire(GarminOutbound.CurrentTimeReply(31, 5, -3_600, 9, 8))
        assertEquals(5052, r.u16()); assertEquals(0, r.u8())
        assertEquals(31L, r.u32()); assertEquals(5L, r.u32())
        assertEquals(-3_600, r.u32()!!.toInt())
        assertEquals(9L, r.u32()); assertEquals(8L, r.u32())
    }

    @Test fun systemEventFilterDownloadSupportedTypesRoundTrip() {
        val (t1, r1) = wire(GarminOutbound.SystemEvent(GarminSafeSystemEvent.SYNC_READY, 0))
        assertEquals(5030, t1); assertEquals(8, r1.u8()); assertEquals(0, r1.u8())
        val (t2, r2) = wire(GarminOutbound.Filter(GarminFilterType.ONLY_NEW))
        assertEquals(5007, t2); assertEquals(3, r2.u8())
        val (t3, r3) = wire(GarminOutbound.DownloadRequest(0x0203, 77, GarminDownloadKind.NEW, 0x1111, 88, 0))
        assertEquals(5002, t3)
        assertEquals(0x0203, r3.u16()); assertEquals(77L, r3.u32()); assertEquals(1, r3.u8())
        assertEquals(0x1111, r3.u16()); assertEquals(88L, r3.u32()); assertEquals(0, r3.u8())
        val (t4, r4) = wire(GarminOutbound.SupportedFileTypesRequest)
        assertEquals(5031, t4); assertEquals(0, r4.remaining)
    }

    @Test fun fileTransferChunkAndAckRoundTrip() {
        val data = bytes(1, 2, 3, 0, 5)
        val crc = GarminCrc.crc16(data, 0x4242)
        val m = inbound(5004, bytes(0) + oracleLE16(crc) + oracleLE32(1_000) + data) as GarminInbound.FileTransferData
        assertEquals(0, m.flags); assertEquals(crc, m.runningCrc); assertEquals(1_000L, m.dataOffset)
        assertArrayEquals(data, m.data)
        val (_, r) = wire(GarminOutbound.FileTransferDataReply(0, 1_005))
        assertEquals(5004, r.u16()); assertEquals(0, r.u8()); assertEquals(0, r.u8()); assertEquals(1_005L, r.u32())
    }

    @Test fun directoryEntryRoundTripAndFileAvailable() {
        val entry = GarminDirectoryEntry(12, 128, 44, 3, 1, 0x20, 9_999, 0)
        val w = GarminByteWriter()
        entry.write(w)
        val b = w.bytes
        assertEquals(16, b.size)
        assertEquals(listOf(entry, entry), GarminDirectoryEntry.parseDirectory(b + b))
        assertNull(entry.unixTime)
        assertNull(GarminDirectoryEntry.parseDirectory(b.copyOfRange(0, 15)))
        assertEquals(GarminInbound.FileAvailable(entry), inbound(5009, b))
        val short = inbound(5009, b.copyOfRange(0, 15)) as GarminInbound.Unknown
        assertEquals(5009, short.type)
    }

    @Test fun synchronizationAndDeviceInformationDecode() {
        assertEquals(GarminInbound.Synchronization(1, 0xFFFF_FFFFL), inbound(5037, bytes(1, 4, 0xFF, 0xFF, 0xFF, 0xFF)))
        val payload = oracleLE16(150) + oracleLE16(1) + oracleLE32(2) + oracleLE16(3) + oracleLE16(4) +
            oracleStr("a") + oracleStr("bé") + oracleStr("")
        val info = (inbound(5024, payload) as GarminInbound.DeviceInformation).info
        assertEquals("bé", info.deviceName)
        assertEquals("", info.deviceModel)
        assertNull(info.classicAddress)
    }

    @Test fun stringsTruncateOnCodePointBoundary() {
        assertArrayEquals(bytes(0x61), GarminByteWriter.truncatedUtf8("aé", 2))
        assertArrayEquals(bytes(0x61, 0xC3, 0xA9), GarminByteWriter.truncatedUtf8("aé", 3))
    }

    // MARK: FileAccess protobuf

    @Test fun fileAccessRequestsRoundTripThroughTheWireReader() {
        val item = GarminItemReference(
            uid = GarminUid(1, 2), dataType = GarminItemDataType(format = 3, typeName = "T"), dataSize = 1L shl 40,
            transactionId = 6,
        )
        val smart = GarminFileAccessRequest.PullItem(item, 24, 0, 15).encodeSmart()
        val service = GarminProtoReader.fields(smart)!!
        assertEquals(listOf(43), service.map { it.first })
        val pull = GarminProtoReader.fields(service[0].second.data!!)!!
        assertEquals(listOf(1), pull.map { it.first })
        val pullFields = GarminProtoReader.fields(pull[0].second.data!!)!!
        assertEquals(listOf(1, 2, 3, 4, 5), pullFields.map { it.first })
        assertEquals(item, GarminItemReference.decode(pullFields[0].second.data!!))

        val added = oracleSmart(12, oracleLd(1, item.encode()))
        assertEquals(GarminFileAccessMessage.ItemAdded(listOf(item)), GarminFileAccessMessage.decodeSmart(added))
    }

    @Test fun fileAccessRejectsMalformedProtobuf() {
        assertNull(GarminProtoReader.fields(bytes(0x0A, 0x05, 0x01)))
        assertNull(GarminProtoReader.fields(bytes(0x08, 0x80)))
        assertNull(GarminProtoReader.fields(bytes(0x0B)))
        assertNull(GarminFileAccessMessage.decodeSmart(bytes(0x08, 0x01)))
    }
}
