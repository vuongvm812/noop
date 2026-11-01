package com.noop.garmin

// MARK: - CRC-16/ARC and Garmin COBS framing
//
// Faithful Kotlin twin of Packages/GarminProtocol/Sources/GarminProtocol/GarminFraming.swift. Wire facts
// (Gadgetbridge, see ATTRIBUTION.md): every GFDI message ends in a CRC-16/ARC over everything before it,
// and the GFDI byte stream is COBS-encoded with a leading AND a trailing 0x00. File downloads chain the
// same CRC across chunks, each chunk carrying the CRC of all file bytes so far.

object GarminCrc {

    /** CRC-16/ARC: reflected polynomial 0xA001, seed 0 (or the running value), no final XOR. */
    fun crc16(data: ByteArray, seed: Int = 0): Int {
        var crc = seed and 0xFFFF
        for (b in data) {
            crc = crc xor (b.toInt() and 0xFF)
            repeat(8) {
                crc = if ((crc and 1) != 0) (crc ushr 1) xor 0xA001 else crc ushr 1
            }
        }
        return crc
    }
}

object GarminCobs {

    /** Encode [data] as one delimited frame: 0x00, the COBS body, 0x00. The body never contains 0x00. */
    fun encode(data: ByteArray): ByteArray {
        val out = ArrayList<Byte>(data.size + data.size / 254 + 3)
        out.add(0)
        var codeIndex = out.size
        out.add(0)
        var code = 1
        for (b in data) {
            if (b.toInt() == 0) {
                out[codeIndex] = code.toByte()
                codeIndex = out.size
                out.add(0)
                code = 1
            } else {
                out.add(b)
                code++
                if (code == 0xFF) {
                    out[codeIndex] = code.toByte()
                    codeIndex = out.size
                    out.add(0)
                    code = 1
                }
            }
        }
        out[codeIndex] = code.toByte()
        out.add(0)
        return out.toByteArray()
    }

    /**
     * Decode one COBS body (the bytes BETWEEN the delimiters). Null when a code byte claims more bytes than
     * the body holds or the body contains a 0x00: a truncated or corrupt frame, never a partial result.
     */
    fun decodeBody(body: ByteArray): ByteArray? {
        val out = java.io.ByteArrayOutputStream(body.size)
        var i = 0
        while (i < body.size) {
            val code = body[i].toInt() and 0xFF
            if (code == 0) return null
            i++
            val end = i + code - 1
            if (end > body.size) return null
            while (i < end) {
                if (body[i].toInt() == 0) return null
                out.write(body[i].toInt())
                i++
            }
            if (code != 0xFF && i < body.size) out.write(0)
        }
        return out.toByteArray()
    }

    /** Decode one complete delimited frame (0x00, body, 0x00), or null if it is not exactly that shape. */
    fun decode(frame: ByteArray): ByteArray? {
        if (frame.size < 3 || frame.first().toInt() != 0 || frame.last().toInt() != 0) return null
        return decodeBody(frame.copyOfRange(1, frame.size - 1))
    }
}

/**
 * Reassembles COBS frames from an arbitrarily fragmented byte stream. A frame starts after a 0x00 and ends
 * at the next 0x00; bytes before any 0x00 belong to a frame whose start was lost and are dropped. A body
 * that fails to decode is dropped and the delimiter that exposed it opens the next frame, so one lost
 * packet costs one message, not the session.
 */
class GarminCobsStreamDecoder(val maxBodyBytes: Int = 16_384) {

    private val body = java.io.ByteArrayOutputStream()
    private var synced = false

    /** Count of bodies dropped because they were malformed or oversize (diagnostic only). */
    var droppedFrames = 0
        private set

    /** Feed newly received bytes; returns every frame they completed, in order. */
    fun feed(bytes: ByteArray): List<ByteArray> {
        val frames = ArrayList<ByteArray>()
        for (b in bytes) {
            if (b.toInt() == 0) {
                if (synced && body.size() > 0) {
                    val decoded = GarminCobs.decodeBody(body.toByteArray())
                    if (decoded != null) frames.add(decoded) else droppedFrames++
                }
                body.reset()
                synced = true
            } else if (synced) {
                if (body.size() >= maxBodyBytes) {
                    droppedFrames++
                    body.reset()
                    synced = false
                } else {
                    body.write(b.toInt())
                }
            }
        }
        return frames
    }

    /** Forget any partial frame (call on disconnect). */
    fun reset() {
        body.reset()
        synced = false
    }
}
