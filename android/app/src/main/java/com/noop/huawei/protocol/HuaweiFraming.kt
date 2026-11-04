package com.noop.huawei.protocol

// Huawei Link Protocol framing.
//
// Protocol facts (re-derived, no code copied) from Gadgetbridge `devices/huawei/HuaweiPacket.java`
// (AGPL-3.0, commit 6e4220682feb6917a5644304d9d7c760f30b6eaf):
//
//   unsliced:  5A | len u16 BE | 00 | sid | cid | body | CRC16
//   sliced:    5A | len u16 BE | flag | fragIdx | [sid | cid, first slice only] | chunk | CRC16
//
// `len` counts from the 00/flag byte to the end of the body. The CRC is CRC-16/XMODEM (poly 0x1021,
// init 0, not reflected) over everything from the magic byte to the end of the body. Slice flags are
// 1 (first), 2 (middle) and 3 (last); a message is complete on flag 3. A slice is at most the
// negotiated slice size (default 0xF4, replaced by LinkParams tag 2) including header and CRC.

/** CRC-16/XMODEM: poly 0x1021, init 0x0000, no reflection, no final XOR. */
object HuaweiCrc16 {
    fun xmodem(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        var crc = 0
        for (i in offset until offset + length) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1
            }
            crc = crc and 0xFFFF
        }
        return crc
    }
}

/** One reassembled message. [body] is everything after sid/cid: a TLV, or raw bytes for [HuaweiFraming.isRawCommand]. */
class HuaweiFrame(serviceId: Int, commandId: Int, body: ByteArray) {
    val serviceId: Int = serviceId and 0xFF
    val commandId: Int = commandId and 0xFF
    private val bytes = body.copyOf()
    val body: ByteArray get() = bytes.copyOf()

    override fun equals(other: Any?): Boolean =
        other is HuaweiFrame && other.serviceId == serviceId && other.commandId == commandId && other.bytes.contentEquals(bytes)

    override fun hashCode(): Int = (serviceId * 31 + commandId) * 31 + bytes.contentHashCode()

    override fun toString(): String = "HuaweiFrame(%02X/%02X, ${HuaweiHex.encode(bytes)})".format(serviceId, commandId)
}

object HuaweiFraming {
    const val MAGIC = 0x5A
    const val DEFAULT_SLICE_SIZE = 0xF4

    /** Magic + length + 00/flag + fragment index: the sliced header. */
    private const val SLICED_HEADER = 5
    private const val CRC_LENGTH = 2

    /** Smallest slice size that still carries one body byte after sid/cid. */
    const val MIN_SLICE_SIZE = SLICED_HEADER + CRC_LENGTH + 3

    /** The sid/cid pairs whose body is not TLV (file and data transfer blocks). */
    fun isRawCommand(serviceId: Int, commandId: Int): Boolean {
        val s = serviceId and 0xFF
        val c = commandId and 0xFF
        return (s == 0x0A && c == 0x05) || (s == 0x28 && c == 0x06) || (s == 0x2C && c == 0x05) || (s == 0x1C && c == 0x05)
    }

    /**
     * Frames one message. With [sliced] true and a body that does not fit one slice, emits flag 1/2/3
     * slices using the same chunk arithmetic as Gadgetbridge; otherwise one unsliced frame.
     */
    fun encode(serviceId: Int, commandId: Int, body: ByteArray, sliced: Boolean, sliceSize: Int = DEFAULT_SLICE_SIZE): List<ByteArray> {
        if (!sliced) return listOf(unsliced(serviceId, commandId, body))
        require(sliceSize >= MIN_SLICE_SIZE) { "slice size $sliceSize below $MIN_SLICE_SIZE" }
        val maxBody = sliceSize - SLICED_HEADER - CRC_LENGTH
        val count = (body.size + 2 + maxBody - 1) / maxBody
        if (count <= 1) return listOf(unsliced(serviceId, commandId, body))

        val out = ArrayList<ByteArray>(count)
        var read = 0
        for (i in 0 until count) {
            val first = i == 0
            val last = i == count - 1
            val packetSize = minOf(sliceSize, body.size - read + SLICED_HEADER + CRC_LENGTH)
            var content = packetSize - SLICED_HEADER - CRC_LENGTH
            if (first) content -= 2
            val frame = ByteArray(packetSize)
            frame[0] = MAGIC.toByte()
            val len = packetSize - SLICED_HEADER
            frame[1] = (len ushr 8).toByte()
            frame[2] = len.toByte()
            frame[3] = (if (first) 1 else if (last) 3 else 2).toByte()
            frame[4] = i.toByte()
            var p = SLICED_HEADER
            if (first) {
                frame[p++] = serviceId.toByte()
                frame[p++] = commandId.toByte()
            }
            body.copyInto(frame, p, read, read + content)
            read += content
            p += content
            val crc = HuaweiCrc16.xmodem(frame, 0, p)
            frame[p] = (crc ushr 8).toByte()
            frame[p + 1] = crc.toByte()
            out.add(frame)
        }
        check(read == body.size) { "slicing consumed $read of ${body.size} bytes" }
        return out
    }

    private fun unsliced(serviceId: Int, commandId: Int, body: ByteArray): ByteArray {
        val len = body.size + 3
        require(len <= 0xFFFF) { "body of ${body.size} bytes too long for one frame" }
        val frame = ByteArray(3 + len + CRC_LENGTH)
        frame[0] = MAGIC.toByte()
        frame[1] = (len ushr 8).toByte()
        frame[2] = len.toByte()
        frame[3] = 0
        frame[4] = serviceId.toByte()
        frame[5] = commandId.toByte()
        body.copyInto(frame, 6)
        val crc = HuaweiCrc16.xmodem(frame, 0, 3 + len)
        frame[3 + len] = (crc ushr 8).toByte()
        frame[3 + len + 1] = crc.toByte()
        return frame
    }

    /** Decodes exactly one complete unsliced frame (a test and log-parsing convenience). */
    fun decodeSingle(bytes: ByteArray): HuaweiFrame {
        val assembler = HuaweiFrameAssembler()
        val frames = assembler.feed(bytes)
        if (frames.size != 1 || assembler.hasPendingBytes) {
            throw HuaweiProtocolException("expected one complete frame, got ${frames.size}")
        }
        return frames[0]
    }
}

/**
 * Turns the inbound byte stream into messages. A notification may hold part of a frame, several frames,
 * or one slice of a sliced message; bytes are buffered until a frame is complete and slices are joined
 * until flag 3. A corrupt frame (bad magic, length or CRC) throws and clears the buffer and any partial
 * sliced message, so the next notification starts clean.
 */
class HuaweiFrameAssembler(private val maxMessageBytes: Int = 1 shl 20) {
    private var buffer = ByteArray(0)
    private var slices: java.io.ByteArrayOutputStream? = null

    val hasPendingBytes: Boolean get() = buffer.isNotEmpty() || slices != null

    fun reset() {
        buffer = ByteArray(0)
        slices = null
    }

    fun feed(chunk: ByteArray): List<HuaweiFrame> {
        if (buffer.size + chunk.size > maxMessageBytes) fail("inbound buffer over $maxMessageBytes bytes")
        buffer += chunk
        val out = mutableListOf<HuaweiFrame>()
        while (buffer.isNotEmpty()) {
            if (buffer[0].toInt() and 0xFF != HuaweiFraming.MAGIC) {
                fail("magic mismatch: 0x%02X != 0x5A".format(buffer[0].toInt() and 0xFF))
            }
            if (buffer.size < 3) break
            val len = ((buffer[1].toInt() and 0xFF) shl 8) or (buffer[2].toInt() and 0xFF)
            if (len < 1) fail("frame length $len")
            val total = 3 + len + 2
            if (buffer.size < total) break
            val expected = ((buffer[3 + len].toInt() and 0xFF) shl 8) or (buffer[3 + len + 1].toInt() and 0xFF)
            val actual = HuaweiCrc16.xmodem(buffer, 0, 3 + len)
            if (expected != actual) fail("checksum mismatch: 0x%04X != 0x%04X".format(actual, expected))
            val frame = buffer.copyOfRange(0, 3 + len)
            buffer = buffer.copyOfRange(total, buffer.size)
            handle(frame, len)?.let { out.add(it) }
        }
        return out
    }

    private fun handle(frame: ByteArray, len: Int): HuaweiFrame? {
        when (val flag = frame[3].toInt() and 0xFF) {
            0 -> return message(frame.copyOfRange(4, 3 + len))
            1, 2, 3 -> {
                if (len < 2) fail("sliced frame length $len")
                val chunk = frame.copyOfRange(5, 3 + len)
                val acc = when (flag) {
                    1 -> java.io.ByteArrayOutputStream().also { slices = it }
                    else -> slices ?: fail("slice flag $flag without a first slice")
                }
                if (acc.size() + chunk.size > maxMessageBytes) fail("sliced message over $maxMessageBytes bytes")
                acc.write(chunk)
                if (flag != 3) return null
                slices = null
                return message(acc.toByteArray())
            }
            else -> fail("unknown slice flag $flag")
        }
    }

    private fun message(payload: ByteArray): HuaweiFrame {
        if (payload.size < 2) fail("message of ${payload.size} bytes has no service/command id")
        return HuaweiFrame(payload[0].toInt(), payload[1].toInt(), payload.copyOfRange(2, payload.size))
    }

    private fun fail(message: String): Nothing {
        reset()
        throw HuaweiProtocolException(message)
    }
}
