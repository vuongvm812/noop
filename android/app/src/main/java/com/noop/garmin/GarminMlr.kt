package com.noop.garmin

// MARK: - Multi-link reliable (MLR): header packing and the sliding-window state machine
//
// Faithful Kotlin twin of Packages/GarminProtocol/Sources/GarminProtocol/GarminMlr.swift. Wire facts
// (Gadgetbridge, see ATTRIBUTION.md):
//   byte0 = 0x80 | (handle & 7) << 4 | (reqNum >> 2) & 0x0F
//   byte1 = (reqNum & 3) << 6 | seq & 0x3F
// seq numbers this side's data packets mod 64; reqNum is the cumulative ack. At most 32 in flight;
// retransmit from 1 s doubling to 20 s (halving the window); ack after 5 packets or 20 ms.
//
// A pure state machine: the caller passes the time in ms, writes the returned packets, and calls tick()
// at nextDeadlineMs. Like the Swift twin it retransmits with the CURRENT reqNum and lets every data packet
// discharge the pending ack (see the Swift header for why).

data class GarminMlrHeader(val handle: Int, val reqNum: Int, val seq: Int) {

    val bytes: ByteArray
        get() = byteArrayOf(
            (0x80 or ((handle and 0x07) shl 4) or ((reqNum shr 2) and 0x0F)).toByte(),
            (((reqNum and 0x03) shl 6) or (seq and 0x3F)).toByte(),
        )

    companion object {
        /** Masks each field to its wire width, as the Swift initializer does. */
        fun of(handle: Int, reqNum: Int, seq: Int) = GarminMlrHeader(handle and 0x07, reqNum and 0x3F, seq and 0x3F)

        /** Unpack the first two bytes, or null if shorter or bit 7 of byte 0 is clear. */
        fun parse(packet: ByteArray): GarminMlrHeader? {
            if (packet.size < 2) return null
            val b0 = packet[0].toInt() and 0xFF
            val b1 = packet[1].toInt() and 0xFF
            if ((b0 and 0x80) == 0) return null
            return GarminMlrHeader((b0 and 0x70) shr 4, ((b0 and 0x0F) shl 2) or ((b1 shr 6) and 0x03), b1 and 0x3F)
        }
    }
}

class GarminMlrSession(
    /** The handle value the watch registered; only its low 3 bits go on the wire. */
    val handle: Int,
    mtu: Int,
) {
    companion object {
        const val SEQUENCE_MODULUS = 64
        const val MAX_IN_FLIGHT = 32
        const val INITIAL_RETRANSMIT_MS = 1_000L
        const val MAX_RETRANSMIT_MS = 20_000L
        const val ACK_DELAY_MS = 20L
        const val ACK_THRESHOLD = 5

        fun payloadSize(mtu: Int): Int = maxOf(1, mtu - 5)

        private fun distance(a: Int, b: Int): Int = (b - a + SEQUENCE_MODULUS) % SEQUENCE_MODULUS
    }

    private var payloadSize = payloadSize(mtu)
    private var nextSendSeq = 0
    private var lastRcvAck = 0
    private var nextRcvSeq = 0
    private var lastSendAck = 0
    private var window = MAX_IN_FLIGHT
    private var retransmitMs = INITIAL_RETRANSMIT_MS
    private val queue = ArrayDeque<ByteArray>()
    private val sent = arrayOfNulls<ByteArray>(SEQUENCE_MODULUS)
    private var ackDeadline: Long? = null
    private var retransmitDeadline: Long? = null

    fun setMtu(mtu: Int) {
        payloadSize = payloadSize(mtu)
    }

    /** Packets sent and not yet acked. */
    val inFlight: Int get() = distance(lastRcvAck, nextSendSeq)

    /** Fragments waiting for window space. */
    val queued: Int get() = queue.size

    val windowSize: Int get() = window

    val retransmitTimeoutMs: Long get() = retransmitMs

    /** When tick() next has work to do, or null when idle. */
    val nextDeadlineMs: Long?
        get() {
            val a = ackDeadline
            val r = retransmitDeadline
            return when {
                a != null && r != null -> minOf(a, r)
                else -> a ?: r
            }
        }

    /** Queue an outbound frame; returns the packets to write now. */
    fun send(frame: GarminOutboundFrame, nowMs: Long): List<ByteArray> {
        val bytes = frame.bytes
        var start = 0
        while (start < bytes.size) {
            val end = minOf(start + payloadSize, bytes.size)
            queue.addLast(bytes.copyOfRange(start, end))
            start = end
        }
        return pump(nowMs)
    }

    class Received(val data: ByteArray?, val outbound: List<ByteArray>) {
        override fun equals(other: Any?): Boolean {
            if (other !is Received) return false
            val sameData = if (data == null) other.data == null else other.data != null && data.contentEquals(other.data)
            return sameData && outbound.size == other.outbound.size &&
                outbound.indices.all { outbound[it].contentEquals(other.outbound[it]) }
        }

        override fun hashCode(): Int = (data?.contentHashCode() ?: 0) * 31 + outbound.size
    }

    /** Process one inbound packet for this handle (header included). */
    fun receive(packet: ByteArray, nowMs: Long): Received {
        val header = GarminMlrHeader.parse(packet)
        if (header == null || header.handle != (handle and 0x07)) return Received(null, emptyList())
        val out = ArrayList<ByteArray>()
        if (header.reqNum != lastRcvAck) processAck(header.reqNum, nowMs)

        var data: ByteArray? = null
        if (packet.size > 2) {
            if (header.seq == nextRcvSeq) {
                nextRcvSeq = (nextRcvSeq + 1) % SEQUENCE_MODULUS
                data = packet.copyOfRange(2, packet.size)
                if (distance(lastSendAck, nextRcvSeq) >= ACK_THRESHOLD) {
                    out.add(ackPacket())
                } else {
                    ackDeadline = nowMs + ACK_DELAY_MS
                }
            } else {
                out.add(ackPacket())
            }
        }
        out.addAll(pump(nowMs))
        return Received(data, out)
    }

    /** Fire any due timer; returns the packets to write. */
    fun tick(nowMs: Long): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        val ack = ackDeadline
        if (ack != null && ack <= nowMs) out.add(ackPacket())
        val rto = retransmitDeadline
        if (rto != null && rto <= nowMs) {
            retransmitMs = minOf(retransmitMs * 2, MAX_RETRANSMIT_MS)
            window = maxOf(1, window / 2)
            var seq = lastRcvAck
            while (seq != nextSendSeq) {
                sent[seq]?.let { out.add(packet(seq, it)) }
                seq = (seq + 1) % SEQUENCE_MODULUS
            }
            retransmitDeadline = nowMs + retransmitMs
        }
        return out
    }

    private fun processAck(reqNum: Int, nowMs: Long) {
        val acked = distance(lastRcvAck, reqNum)
        if (acked > inFlight) return
        var seq = lastRcvAck
        while (seq != reqNum) {
            sent[seq] = null
            seq = (seq + 1) % SEQUENCE_MODULUS
        }
        lastRcvAck = reqNum
        retransmitMs = INITIAL_RETRANSMIT_MS
        window = minOf(window + acked, MAX_IN_FLIGHT)
        retransmitDeadline = if (lastRcvAck == nextSendSeq) null else nowMs + retransmitMs
    }

    private fun pump(nowMs: Long): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        while (queue.isNotEmpty() && inFlight < window) {
            val wasIdle = inFlight == 0
            val data = queue.removeFirst()
            sent[nextSendSeq] = data
            out.add(packet(nextSendSeq, data))
            nextSendSeq = (nextSendSeq + 1) % SEQUENCE_MODULUS
            if (wasIdle) retransmitDeadline = nowMs + retransmitMs
        }
        return out
    }

    private fun packet(seq: Int, data: ByteArray): ByteArray {
        lastSendAck = nextRcvSeq
        ackDeadline = null
        return GarminMlrHeader.of(handle, nextRcvSeq, seq).bytes + data
    }

    private fun ackPacket(): ByteArray {
        lastSendAck = nextRcvSeq
        ackDeadline = null
        return GarminMlrHeader.of(handle, nextRcvSeq, 0).bytes
    }
}
