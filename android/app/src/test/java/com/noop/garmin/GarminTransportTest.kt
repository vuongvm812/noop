package com.noop.garmin

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Multi-link control frames, plain framing, routing and the MLR state machine. Twin of GarminTransportTests.swift. */
class GarminTransportTest {

    private val client = bytes(2, 0, 0, 0, 0, 0, 0, 0)

    @Test fun controlFramesAreThirteenBytes() {
        assertArrayEquals(bytes(0, 5) + client + bytes(0, 0, 0), GarminMultiLink.ControlRequest.CloseAll.encode())
        assertArrayEquals(bytes(0, 0) + client + bytes(1, 0, 0), GarminMultiLink.ControlRequest.Register(GarminService.GFDI, false).encode())
        assertArrayEquals(bytes(0, 0) + client + bytes(1, 0, 2), GarminMultiLink.ControlRequest.Register(GarminService.GFDI, true).encode())
        assertArrayEquals(
            bytes(0, 2) + client + bytes(19, 0, 7),
            GarminMultiLink.ControlRequest.CloseHandle(GarminService.REALTIME_SPO2, 7).encode(),
        )
        assertEquals(13, GarminMultiLink.ControlRequest.Register(GarminService.FILE_TRANSFER_E, true).encode().size)
    }

    @Test fun registerResponseParsesAndForeignClientIsIgnored() {
        assertEquals(
            GarminMultiLink.ControlResponse.Registered(1, 0, 5, true),
            GarminMultiLink.parseControl(bytes(0, 1) + client + bytes(1, 0, 0, 5, 1)),
        )
        val foreign = bytes(0, 1, 3, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 5, 1)
        assertNull(GarminMultiLink.parseControl(foreign))
        assertEquals(GarminMultiLink.ControlResponse.Registered(1, 0, 5, true), GarminMultiLink.parseControl(foreign, 3))
        assertNull(GarminMultiLink.parseControl(bytes(0, 1) + client))
        assertEquals(GarminMultiLink.ControlResponse.HandleClosed(6, 9, 0), GarminMultiLink.parseControl(bytes(0, 3) + client + bytes(6, 0, 9, 0)))
        assertEquals(GarminMultiLink.ControlResponse.AllClosed, GarminMultiLink.parseControl(bytes(0, 6) + client))
    }

    @Test fun plainPacketsFragmentToMtuMinusFour() {
        val frame = GarminOutbound.SupportedFileTypesRequest.frame() // 9 bytes after COBS
        val packets = GarminMultiLink.plainPackets(frame, 4, 7)
        assertEquals(listOf(4, 4, 4), packets.map { it.size })
        assertTrue(packets.all { it[0].toInt() == 4 })
        assertArrayEquals(frame.bytes, packets.flatMap { it.drop(1) }.toByteArray())
    }

    @Test fun routeOnlySendsRegisteredReliableHandlesToMlr() {
        val plain = GarminMultiLink.route(bytes(0x85, 1, 2), emptySet()) as GarminMultiLink.Route.Plain
        assertEquals(0x85, plain.handle)
        assertArrayEquals(bytes(1, 2), plain.payload)
        val reliable = GarminMultiLink.route(bytes(0xD0, 1, 2), setOf(0x85)) as GarminMultiLink.Route.Reliable
        assertEquals(0x85, reliable.handle)
        assertTrue(GarminMultiLink.route(bytes(0x00, 1), setOf(0x85)) is GarminMultiLink.Route.Control)
        assertEquals(GarminMultiLink.Route.Empty, GarminMultiLink.route(ByteArray(0), emptySet()))
    }

    @Test fun mlrHeaderRoundTripsOverWholeSpace() {
        for (h in 0 until 8) for (r in 0 until 64) for (s in 0 until 64) {
            val header = GarminMlrHeader.of(h, r, s)
            val b = header.bytes
            assertEquals(0x80 or (h shl 4) or (r shr 2), b[0].toInt() and 0xFF)
            assertEquals(((r and 3) shl 6) or s, b[1].toInt() and 0xFF)
            assertEquals(header, GarminMlrHeader.parse(b))
        }
        assertNull(GarminMlrHeader.parse(bytes(0x7F, 0)))
    }

    private fun frame(count: Int) = GarminOutboundFrame(oraclePattern(count, 1, 1))

    @Test fun mlrCapsInFlightAtThirtyTwo() {
        val s = GarminMlrSession(0x81, 23)
        val sent = s.send(frame(18 * 50), 0)
        assertEquals(32, sent.size)
        assertEquals(32, s.inFlight)
        assertEquals(18, s.queued)
        assertTrue(sent.all { it.size == 20 })
        assertEquals((0 until 32).toList(), sent.map { GarminMlrHeader.parse(it)!!.seq })
    }

    @Test fun mlrBatchesAcksAtFivePacketsOrTwentyMs() {
        val s = GarminMlrSession(0x81, 23)
        fun data(seq: Int) = GarminMlrHeader.of(1, 0, seq).bytes + bytes(seq)
        var acks = 0
        for (seq in 0 until 4) {
            val r = s.receive(data(seq), seq.toLong())
            assertArrayEquals(bytes(seq), r.data)
            acks += r.outbound.size
        }
        assertEquals("no ack per packet", 0, acks)
        assertEquals(3L + 20, s.nextDeadlineMs)
        val fifth = s.receive(data(4), 4)
        assertEquals(1, fifth.outbound.size)
        assertArrayEquals(GarminMlrHeader.of(1, 5, 0).bytes, fifth.outbound[0])
        assertNull(s.nextDeadlineMs)
        s.receive(data(5), 10)
        assertTrue(s.tick(29).isEmpty())
        assertArrayEquals(GarminMlrHeader.of(1, 6, 0).bytes, s.tick(30).single())
    }

    @Test fun mlrRetransmitBacksOffToTwentySecondsAndAckRestores() {
        val s = GarminMlrSession(0x82, 23)
        s.send(frame(10), 0)
        var now = 0L
        val timeouts = ArrayList<Long>()
        repeat(8) {
            now = s.nextDeadlineMs!!
            assertEquals(1, s.tick(now).size)
            timeouts.add(s.retransmitTimeoutMs)
        }
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 20_000L, 20_000L, 20_000L, 20_000L), timeouts)
        assertEquals(1, s.windowSize)
        s.receive(GarminMlrHeader.of(2, 1, 0).bytes, now + 1)
        assertEquals(0, s.inFlight)
        assertEquals(1_000L, s.retransmitTimeoutMs)
        assertEquals(2, s.windowSize)
        assertNull(s.nextDeadlineMs)
    }

    @Test fun mlrDataPacketCarriesTheAck() {
        val s = GarminMlrSession(0x81, 23)
        s.receive(GarminMlrHeader.of(1, 0, 0).bytes + bytes(0xAA), 0)
        assertNotNull(s.nextDeadlineMs)
        val out = s.send(frame(3), 5)
        assertEquals(1, GarminMlrHeader.parse(out[0])!!.reqNum)
        assertEquals(1_005L, s.nextDeadlineMs)
    }
}
