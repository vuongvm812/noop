package com.noop.huawei.decode

/** A read ran past the end of its input, or met a value the format rules out. Caught inside each decoder. */
internal class HuaweiDecodeException(message: String) : Exception(message)

/**
 * Bounds-checked cursor over untrusted bytes. Every read throws [HuaweiDecodeException] instead of reading
 * past the end, so a truncated or garbage file fails inside the decoder that owns it, never as an
 * IndexOutOfBounds in the caller. Kotlin-only; Swift twin deferred (fork).
 */
internal class HuaweiByteReader(private val data: ByteArray, private val littleEndian: Boolean, start: Int = 0) {

    var pos: Int = start
        private set

    val remaining: Int get() = data.size - pos

    private fun need(n: Int) {
        if (n < 0 || pos < 0 || n > data.size - pos) throw HuaweiDecodeException("need $n bytes at $pos of ${data.size}")
    }

    fun seek(to: Int) {
        if (to < 0 || to > data.size) throw HuaweiDecodeException("seek $to of ${data.size}")
        pos = to
    }

    fun skip(n: Int) { need(n); pos += n }

    fun u8(): Int { need(1); return data[pos++].toInt() and 0xFF }

    fun bytes(n: Int): ByteArray { need(n); return data.copyOfRange(pos, pos + n).also { pos += n } }

    /** Unsigned integer of [n] bytes (1..8) in this reader's byte order. */
    fun unsigned(n: Int): Long {
        need(n)
        var v = 0L
        for (i in 0 until n) {
            val b = (data[pos + i].toLong() and 0xFF)
            v = if (littleEndian) v or (b shl (8 * i)) else (v shl 8) or b
        }
        pos += n
        return v
    }

    fun u16(): Int = unsigned(2).toInt()
    fun i16(): Int = unsigned(2).toInt().toShort().toInt()
    fun u32(): Long = unsigned(4)
    fun i32(): Int = unsigned(4).toInt()
    fun i64(): Long = unsigned(8)
    fun f32(): Float = java.lang.Float.intBitsToFloat(i32())
    fun f64(): Double = java.lang.Double.longBitsToDouble(i64())

    companion object {
        /** Big-endian unsigned value of a whole field (0..8 bytes); null when longer than 8. */
        fun beUnsigned(b: ByteArray): Long? {
            if (b.size > 8) return null
            var v = 0L
            for (x in b) v = (v shl 8) or (x.toLong() and 0xFF)
            return v
        }
    }
}
