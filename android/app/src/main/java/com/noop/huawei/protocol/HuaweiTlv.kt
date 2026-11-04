package com.noop.huawei.protocol

// Huawei Link Protocol TLV container.
//
// Protocol facts (re-derived, no code copied) from Gadgetbridge `devices/huawei/HuaweiTLV.java`
// (AGPL-3.0, commit 6e4220682feb6917a5644304d9d7c760f30b6eaf): a 1-byte tag, a VarInt length, then the
// value. A tag with bit 0x80 set holds a nested TLV in its value. Tag 0x7F carries a result code where
// 0x000186A0 means success. A lone 0x00 byte at the very end is padding and is skipped.

/** Any malformed inbound Huawei data: framing, TLV, crypto envelope or packet payload. */
open class HuaweiProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A TLV the decoder needs is absent. */
class HuaweiMissingTagException(val tag: Int) : HuaweiProtocolException("missing tag 0x%02X".format(tag))

/**
 * The TLV length VarInt: big-endian base-128. Each byte carries 7 bits, most significant group first,
 * and bit 0x80 means another byte follows. So 0x80 encodes as `81 00`, 460 as `83 4C`.
 */
object HuaweiVarInt {
    /** Longest encoding accepted on input: 5 groups cover any non-negative Int. */
    private const val MAX_BYTES = 5

    fun size(value: Int): Int {
        require(value >= 0) { "VarInt value must be non-negative" }
        var v = value
        var n = 0
        do {
            n++
            v = v ushr 7
        } while (v != 0)
        return n
    }

    fun encode(value: Int): ByteArray {
        val n = size(value)
        val out = ByteArray(n)
        var v = value
        out[n - 1] = (v and 0x7F).toByte()
        for (i in n - 2 downTo 0) {
            v = v ushr 7
            out[i] = ((v and 0x7F) or 0x80).toByte()
        }
        return out
    }

    /** Decodes at [offset], reading no byte at or past [limit]. Returns the value and the bytes consumed. */
    fun decode(src: ByteArray, offset: Int, limit: Int = src.size): Pair<Int, Int> {
        var result = 0L
        var i = offset
        while (true) {
            if (i >= limit) throw HuaweiProtocolException("VarInt runs past the buffer")
            if (i - offset >= MAX_BYTES) throw HuaweiProtocolException("VarInt longer than $MAX_BYTES bytes")
            val b = src[i].toInt() and 0xFF
            result = (result shl 7) or (b and 0x7F).toLong()
            i++
            if (b and 0x80 == 0) break
        }
        if (result > Int.MAX_VALUE) throw HuaweiProtocolException("VarInt value $result overflows")
        return result.toInt() to (i - offset)
    }
}

/** An ordered list of TLV entries. Duplicate tags are allowed and kept in order. */
class HuaweiTlv {

    /** One entry. [tag] is 0..255; equality compares the value bytes. */
    class Entry(tag: Int, value: ByteArray) {
        val tag: Int = tag and 0xFF
        private val bytes: ByteArray = value.copyOf()
        val value: ByteArray get() = bytes.copyOf()
        val length: Int get() = bytes.size

        internal fun valueUnsafe(): ByteArray = bytes

        fun encodedSize(): Int = 1 + HuaweiVarInt.size(bytes.size) + bytes.size

        override fun equals(other: Any?): Boolean =
            other is Entry && other.tag == tag && other.bytes.contentEquals(bytes)

        override fun hashCode(): Int = 31 * tag + bytes.contentHashCode()

        override fun toString(): String = "{tag: ${Integer.toHexString(tag)} - Value: ${HuaweiHex.encode(bytes)}}"
    }

    private val list = mutableListOf<Entry>()

    val entries: List<Entry> get() = list.toList()

    val isEmpty: Boolean get() = list.isEmpty()

    fun encodedSize(): Int = list.sumOf { it.encodedSize() }

    fun put(tag: Int): HuaweiTlv = put(tag, ByteArray(0))

    fun put(tag: Int, value: ByteArray): HuaweiTlv {
        list.add(Entry(tag, value))
        return this
    }

    fun put(tag: Int, value: HuaweiTlv): HuaweiTlv = put(tag, value.serialize())

    fun putByte(tag: Int, value: Int): HuaweiTlv = put(tag, byteArrayOf(value.toByte()))

    fun putBoolean(tag: Int, value: Boolean): HuaweiTlv = putByte(tag, if (value) 1 else 0)

    fun putShort(tag: Int, value: Int): HuaweiTlv = put(tag, HuaweiBytes.u16(value))

    fun putInt(tag: Int, value: Int): HuaweiTlv = put(tag, HuaweiBytes.u32(value.toLong()))

    fun putLong(tag: Int, value: Long): HuaweiTlv = put(tag, HuaweiBytes.u64(value))

    fun putString(tag: Int, value: String): HuaweiTlv = put(tag, value.toByteArray(Charsets.UTF_8))

    fun serialize(): ByteArray {
        val out = ByteArray(encodedSize())
        var p = 0
        for (e in list) {
            out[p++] = e.tag.toByte()
            val len = HuaweiVarInt.encode(e.length)
            len.copyInto(out, p)
            p += len.size
            e.valueUnsafe().copyInto(out, p)
            p += e.length
        }
        return out
    }

    fun copy(): HuaweiTlv = HuaweiTlv().also { c -> list.forEach { c.list.add(it) } }

    fun contains(tag: Int): Boolean = list.any { it.tag == (tag and 0xFF) }

    fun bytesOrNull(tag: Int): ByteArray? = list.firstOrNull { it.tag == (tag and 0xFF) }?.value

    fun bytes(tag: Int): ByteArray = bytesOrNull(tag) ?: throw HuaweiMissingTagException(tag)

    /** First byte of the value, unsigned. */
    fun byte(tag: Int): Int {
        val v = bytes(tag)
        if (v.isEmpty()) throw HuaweiProtocolException("tag 0x%02X is empty".format(tag))
        return v[0].toInt() and 0xFF
    }

    fun boolean(tag: Int): Boolean = byte(tag) == 1

    /** A 2-byte big-endian value, unsigned. */
    fun u16(tag: Int): Int = fixed(tag, 2).toInt()

    /** A 4-byte big-endian value as a signed Int (the raw 32 bits). */
    fun int(tag: Int): Int = fixed(tag, 4).toInt()

    /** A 4-byte big-endian value, unsigned. */
    fun u32(tag: Int): Long = fixed(tag, 4)

    fun string(tag: Int): String = bytes(tag).toString(Charsets.UTF_8)

    fun tlv(tag: Int): HuaweiTlv = parse(bytes(tag))

    fun tlvs(tag: Int): List<HuaweiTlv> = list.filter { it.tag == (tag and 0xFF) }.map { parse(it.valueUnsafe()) }

    /** A 1 to 8 byte big-endian value, unsigned (up to 63 bits meaningful). */
    fun unsigned(tag: Int): Long {
        val v = bytes(tag)
        if (v.isEmpty() || v.size > 8) throw HuaweiProtocolException("tag 0x%02X has ${v.size} bytes".format(tag))
        return HuaweiBytes.readUnsigned(v, 0, v.size)
    }

    /** Removes and returns the LAST entry with [tag], or null. */
    fun remove(tag: Int): ByteArray? {
        val i = list.indexOfLast { it.tag == (tag and 0xFF) }
        return if (i < 0) null else list.removeAt(i).value
    }

    /** The 0x7F result code, or null when the response carries none. */
    fun resultCode(): Long? = if (contains(TAG_RESULT)) unsigned(TAG_RESULT) else null

    private fun fixed(tag: Int, width: Int): Long {
        val v = bytes(tag)
        if (v.size != width) throw HuaweiProtocolException("tag 0x%02X has ${v.size} bytes, expected $width".format(tag))
        return HuaweiBytes.readUnsigned(v, 0, width)
    }

    override fun equals(other: Any?): Boolean = other is HuaweiTlv && other.list == list

    override fun hashCode(): Int = list.hashCode()

    override fun toString(): String = if (list.isEmpty()) "Empty" else list.joinToString(" - ")

    companion object {
        const val TAG_RESULT = 0x7F
        const val RESULT_OK = 0x000186A0L

        fun parse(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset): HuaweiTlv {
            if (offset < 0 || length < 0 || offset + length > buffer.size) {
                throw HuaweiProtocolException("TLV range $offset+$length exceeds ${buffer.size} bytes")
            }
            val out = HuaweiTlv()
            val end = offset + length
            var p = offset
            while (p < end) {
                val tag = buffer[p].toInt() and 0xFF
                p++
                if (p == end && tag == 0) break
                val (size, used) = HuaweiVarInt.decode(buffer, p, end)
                p += used
                if (size > end - p) throw HuaweiProtocolException("TLV value of $size bytes exceeds ${end - p} remaining")
                out.list.add(Entry(tag, buffer.copyOfRange(p, p + size)))
                p += size
            }
            return out
        }
    }
}

/** Big-endian byte helpers. */
object HuaweiBytes {
    fun u16(value: Int): ByteArray = byteArrayOf((value ushr 8).toByte(), value.toByte())

    fun u32(value: Long): ByteArray =
        byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())

    fun u64(value: Long): ByteArray = ByteArray(8) { i -> (value ushr (56 - 8 * i)).toByte() }

    fun readUnsigned(src: ByteArray, offset: Int, width: Int): Long {
        var r = 0L
        for (i in 0 until width) r = (r shl 8) or (src[offset + i].toLong() and 0xFF)
        return r
    }
}

/** Upper-case hex, the form the Huawei auth JSON and digest inputs use. */
object HuaweiHex {
    private const val DIGITS = "0123456789ABCDEF"

    fun encode(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(DIGITS[v ushr 4]).append(DIGITS[v and 0x0F])
        }
        return sb.toString()
    }

    fun decode(hex: String): ByteArray {
        if (hex.length % 2 != 0) throw HuaweiProtocolException("odd-length hex string")
        return ByteArray(hex.length / 2) { i ->
            val hi = Character.digit(hex[2 * i], 16)
            val lo = Character.digit(hex[2 * i + 1], 16)
            if (hi < 0 || lo < 0) throw HuaweiProtocolException("non-hex character in string")
            ((hi shl 4) or lo).toByte()
        }
    }
}
