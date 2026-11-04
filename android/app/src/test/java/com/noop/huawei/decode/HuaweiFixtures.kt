package com.noop.huawei.decode

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Byte builders for the synthetic Huawei fixtures. Written for NOOP; nothing here comes from Gadgetbridge. */
object HuaweiFixtures {

    fun hex(s: String): ByteArray = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** A growing buffer in one byte order. */
    class Bytes(order: ByteOrder) {
        private val out = ByteArrayOutputStream()
        private val order = order
        private fun put(size: Int, fill: ByteBuffer.() -> Unit) =
            out.write(ByteBuffer.allocate(size).order(order).apply(fill).array())

        fun u8(v: Int) = apply { out.write(v and 0xFF) }
        fun i16(v: Int) = apply { put(2) { putShort(v.toShort()) } }
        fun i32(v: Long) = apply { put(4) { putInt(v.toInt()) } }
        fun i64(v: Long) = apply { put(8) { putLong(v) } }
        fun f32(v: Float) = apply { put(4) { putFloat(v) } }
        fun f64(v: Double) = apply { put(8) { putDouble(v) } }
        fun raw(b: ByteArray) = apply { out.write(b) }
        fun zeros(n: Int) = apply { out.write(ByteArray(n)) }
        fun padTo(n: Int) = apply { require(out.size() <= n); zeros(n - out.size()) }
        val size get() = out.size()
        fun build(): ByteArray = out.toByteArray()
    }

    fun be() = Bytes(ByteOrder.BIG_ENDIAN)
    fun le() = Bytes(ByteOrder.LITTLE_ENDIAN)

    /** One TLV element: tag, a 7-bit-group length (most significant first), value. */
    fun tlv(tag: Int, value: ByteArray): ByteArray {
        val groups = ArrayList<Int>()
        var n = value.size
        do { groups.add(0, n and 0x7F); n = n ushr 7 } while (n > 0)
        val len = groups.mapIndexed { i, g -> (if (i < groups.size - 1) g or 0x80 else g).toByte() }.toByteArray()
        return byteArrayOf(tag.toByte()) + len + value
    }

    /** One sequence-file summary entry `{03 id, 04 type, 05 value}` wrapped in its 0x82 container. */
    fun summaryEntry(id: Int, type: Int, value: ByteArray): ByteArray =
        tlv(0x82, tlv(0x03, be().i32(id.toLong()).build()) + tlv(0x04, byteArrayOf(type.toByte())) + tlv(0x05, value))

    fun summaryInt(id: Int, v: Long) = summaryEntry(id, 1, be().i32(v).build())
    fun summaryLong(id: Int, v: Long) = summaryEntry(id, 3, be().i64(v).build())
    fun summaryDouble(id: Int, v: Double) = summaryEntry(id, 6, be().f64(v).build())

    /** One sequence-file record: its 32-byte header, then the summary and detail. */
    fun sequenceRecord(start: Long, end: Long, summary: ByteArray, detail: ByteArray, version: Int = 1, summaryType: Int = 2): ByteArray =
        be().i32(start).i32(end).u8(version).u8(summaryType).i16(summary.size).i32(detail.size.toLong()).zeros(16)
            .raw(summary).raw(detail).build()

    fun sequenceFile(fileType: Int, vararg records: ByteArray): ByteArray {
        val body = records.fold(ByteArray(0)) { a, b -> a + b }
        return be().i32(32L + body.size).i32(700_013L).i16(fileType).i16(1).zeros(20).raw(body).build()
    }

    /** Sleep detail: (seconds, stage) pairs as 8-byte little-endian records. */
    fun detail(vararg spans: Pair<Int, Int>): ByteArray =
        spans.fold(le()) { b, (s, st) -> b.i32(s.toLong()).u8(st).zeros(3) }.build()
}
