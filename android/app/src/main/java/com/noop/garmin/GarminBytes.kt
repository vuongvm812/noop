package com.noop.garmin

// MARK: - Little-endian byte reader / writer shared by every Garmin codec
//
// Faithful Kotlin twin of Packages/GarminProtocol/Sources/GarminProtocol/GarminBytes.swift. All multi-byte
// GFDI and multi-link integers are little-endian; strings are a u8 byte count followed by UTF-8. Readers
// return null on a short buffer, so a malformed frame is rejected whole. Unsigned values widen: u8/u16 to
// Int, u32 to Long (0..2^32-1), u64 to Long holding the same 64 bits.

/** Cursor over an immutable byte buffer. Every read returns null when too few bytes remain. */
internal class GarminByteReader(val bytes: ByteArray, var position: Int = 0) {

    val remaining: Int get() = bytes.size - position

    fun u8(): Int? {
        if (remaining < 1) return null
        return bytes[position++].toInt() and 0xFF
    }

    fun u16(): Int? {
        if (remaining < 2) return null
        val v = (bytes[position].toInt() and 0xFF) or ((bytes[position + 1].toInt() and 0xFF) shl 8)
        position += 2
        return v
    }

    fun u32(): Long? {
        if (remaining < 4) return null
        var v = 0L
        for (i in 0 until 4) v = v or ((bytes[position + i].toLong() and 0xFF) shl (8 * i))
        position += 4
        return v
    }

    fun u64(): Long? {
        if (remaining < 8) return null
        var v = 0L
        for (i in 0 until 8) v = v or ((bytes[position + i].toLong() and 0xFF) shl (8 * i))
        position += 8
        return v
    }

    fun take(count: Int): ByteArray? {
        if (count < 0 || remaining < count) return null
        val out = bytes.copyOfRange(position, position + count)
        position += count
        return out
    }

    fun rest(): ByteArray {
        val out = bytes.copyOfRange(position, bytes.size)
        position = bytes.size
        return out
    }

    /** A u8-length-prefixed UTF-8 string. Invalid UTF-8 is repaired with U+FFFD rather than rejected. */
    fun string8(): String? {
        val count = u8() ?: return null
        val raw = take(count) ?: return null
        return String(raw, Charsets.UTF_8)
    }
}

/** Append-only little-endian byte builder. */
internal class GarminByteWriter {
    private val out = java.io.ByteArrayOutputStream()

    val bytes: ByteArray get() = out.toByteArray()

    fun u8(value: Int) = out.write(value and 0xFF)

    fun u16(value: Int) {
        out.write(value and 0xFF)
        out.write((value shr 8) and 0xFF)
    }

    fun u32(value: Long) {
        for (i in 0 until 4) out.write(((value shr (8 * i)) and 0xFF).toInt())
    }

    fun u64(value: Long) {
        for (i in 0 until 8) out.write(((value ushr (8 * i)) and 0xFF).toInt())
    }

    fun append(data: ByteArray) = out.write(data, 0, data.size)

    /** A u8-length-prefixed UTF-8 string, truncated to at most 255 bytes without splitting a code point. */
    fun string8(value: String) {
        val utf8 = truncatedUtf8(value, 255)
        u8(utf8.size)
        append(utf8)
    }

    companion object {
        /** The UTF-8 bytes of [value] cut to [maxBytes], moved back to a code-point start if needed. */
        fun truncatedUtf8(value: String, maxBytes: Int): ByteArray {
            val all = value.toByteArray(Charsets.UTF_8)
            if (all.size <= maxBytes) return all
            var cut = maxBytes
            while (cut > 0 && (all[cut].toInt() and 0xC0) == 0x80) cut--
            return all.copyOfRange(0, cut)
        }
    }
}

/** Lower-case hex without separators, used by diagnostics and the parity oracle. */
fun garminHex(bytes: ByteArray): String {
    val digits = "0123456789abcdef"
    val sb = StringBuilder(bytes.size * 2)
    for (b in bytes) {
        val v = b.toInt() and 0xFF
        sb.append(digits[v shr 4]).append(digits[v and 0x0F])
    }
    return sb.toString()
}

/** 16 lower-case hex digits (diagnostics). */
internal fun garminHex64(v: Long): String = java.lang.Long.toHexString(v).padStart(16, '0')
