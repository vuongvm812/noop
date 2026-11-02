package com.noop.garmin

// zlib (RFC 1950) / DEFLATE (RFC 1951) decompression. Line-for-line twin of
// Packages/GarminProtocol/Sources/GarminProtocol/GarminInflate.swift, where the format facts are documented.
// Deliberately NOT java.util.zip: `zlibPrefix` (what a stream cut short decodes to so far) must give the
// same bytes on both platforms, so both run the same algorithm. GarminHistorySyncTest cross-checks `zlib`
// against java.util.zip.Inflater on the oracle vectors.
//
// SECURITY: every read is bounds-checked and the output is capped (`maxOutput`). Malformed input → null.

object GarminInflate {

    /** Decompress a zlib stream (2-byte header, DEFLATE body, Adler-32 trailer); null when invalid. */
    fun zlib(data: ByteArray, maxOutput: Int = 64 shl 20): ByteArray? {
        if (data.size < 6) return null
        if (!validHeader(data)) return null
        val bits = BitReader(data, 2)
        val out = Output()
        if (!inflate(bits, out, maxOutput)) return null
        val trailer = bits.alignedByteIndex
        if (trailer + 4 > data.size) return null
        val expected = (u(data[trailer]).toLong() shl 24) or (u(data[trailer + 1]).toLong() shl 16) or
            (u(data[trailer + 2]).toLong() shl 8) or u(data[trailer + 3]).toLong()
        val bytes = out.toByteArray()
        return if (adler32(bytes) == expected) bytes else null
    }

    /** The bytes a zlib stream cut short decodes to so far: every symbol fully received. Empty on a bad header. */
    fun zlibPrefix(data: ByteArray, maxOutput: Int = 64 shl 20): ByteArray {
        if (data.size < 2 || !validHeader(data)) return ByteArray(0)
        val bits = BitReader(data, 2)
        val out = Output()
        inflate(bits, out, maxOutput)
        return out.toByteArray()
    }

    fun adler32(data: ByteArray): Long {
        var a = 1L
        var b = 0L
        for (byte in data) {
            a = (a + u(byte)) % 65_521
            b = (b + a) % 65_521
        }
        return (b shl 16) or a
    }

    private fun u(b: Byte): Int = b.toInt() and 0xFF

    private fun validHeader(data: ByteArray): Boolean {
        val cmf = u(data[0])
        val flg = u(data[1])
        return (cmf and 0x0F) == 8 && (cmf shr 4) <= 7 && ((cmf shl 8) or flg) % 31 == 0 && (flg and 0x20) == 0
    }

    private class Output {
        var buf = ByteArray(256)
        var count = 0
        fun append(v: Int) {
            if (count == buf.size) buf = buf.copyOf(buf.size * 2)
            buf[count++] = v.toByte()
        }
        operator fun get(i: Int): Int = buf[i].toInt() and 0xFF
        fun toByteArray(): ByteArray = buf.copyOf(count)
    }

    private class BitReader(val data: ByteArray, var index: Int) {
        var bitBuffer = 0L
        var bitCount = 0

        fun bits(n: Int): Int? {
            while (bitCount < n) {
                if (index >= data.size) return null
                bitBuffer = bitBuffer or ((data[index].toLong() and 0xFF) shl bitCount)
                index += 1
                bitCount += 8
            }
            val value = (bitBuffer and ((1L shl n) - 1)).toInt()
            bitBuffer = bitBuffer ushr n
            bitCount -= n
            return value
        }

        fun alignToByte() {
            bitBuffer = 0
            bitCount = 0
        }

        val alignedByteIndex: Int get() = index - bitCount / 8
    }

    private class Huffman private constructor(val counts: IntArray, val symbols: IntArray) {
        fun decode(r: BitReader): Int? {
            var code = 0
            var first = 0
            var index = 0
            for (len in 1..15) {
                val bit = r.bits(1) ?: return null
                code = code or bit
                val count = counts[len]
                if (code - count < first) return symbols[index + (code - first)]
                index += count
                first += count
                first = first shl 1
                code = code shl 1
            }
            return null
        }

        companion object {
            fun build(lengths: IntArray): Huffman? {
                val counts = IntArray(16)
                for (l in lengths) counts[l] += 1
                counts[0] = 0
                var left = 1
                for (len in 1..15) {
                    left = left shl 1
                    left -= counts[len]
                    if (left < 0) return null
                }
                val offsets = IntArray(16)
                for (len in 1 until 15) offsets[len + 1] = offsets[len] + counts[len]
                val symbols = IntArray(lengths.size)
                for ((symbol, l) in lengths.withIndex()) {
                    if (l != 0) {
                        symbols[offsets[l]] = symbol
                        offsets[l] += 1
                    }
                }
                return Huffman(counts, symbols)
            }
        }
    }

    private val LENGTH_BASE = intArrayOf(3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31,
        35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258)
    private val LENGTH_EXTRA = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2,
        3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0)
    private val DISTANCE_BASE = intArrayOf(1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193,
        257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577)
    private val DISTANCE_EXTRA = intArrayOf(0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6,
        7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13)
    private val CODE_LENGTH_ORDER = intArrayOf(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)

    private val FIXED_LIT: Huffman by lazy {
        val lengths = IntArray(288) { 8 }
        for (i in 144 until 256) lengths[i] = 9
        for (i in 256 until 280) lengths[i] = 7
        Huffman.build(lengths)!!
    }
    private val FIXED_DIST: Huffman by lazy { Huffman.build(IntArray(30) { 5 })!! }

    /** Inflate into [out]; false on a malformed or truncated stream ([out] then holds what decoded). */
    private fun inflate(r: BitReader, out: Output, maxOutput: Int): Boolean {
        var last: Int
        do {
            val final = r.bits(1) ?: return false
            val type = r.bits(2) ?: return false
            last = final
            when (type) {
                0 -> {
                    r.alignToByte()
                    if (r.index + 4 > r.data.size) return false
                    val len = u(r.data[r.index]) or (u(r.data[r.index + 1]) shl 8)
                    val nlen = u(r.data[r.index + 2]) or (u(r.data[r.index + 3]) shl 8)
                    if (len != (nlen.inv() and 0xFFFF) || out.count + len > maxOutput) return false
                    val available = minOf(len, r.data.size - r.index - 4)
                    for (k in 0 until available) out.append(u(r.data[r.index + 4 + k]))
                    if (available != len) return false
                    r.index += 4 + len
                }
                1 -> if (!codes(r, out, FIXED_LIT, FIXED_DIST, maxOutput)) return false
                2 -> {
                    val tables = dynamicTables(r) ?: return false
                    if (!codes(r, out, tables.first, tables.second, maxOutput)) return false
                }
                else -> return false
            }
        } while (last == 0)
        return true
    }

    private fun dynamicTables(r: BitReader): Pair<Huffman, Huffman>? {
        val hlit = r.bits(5) ?: return null
        val hdist = r.bits(5) ?: return null
        val hclen = r.bits(4) ?: return null
        val nlen = hlit + 257
        val ndist = hdist + 1
        if (nlen > 286 || ndist > 30) return null
        val codeLengths = IntArray(19)
        for (i in 0 until hclen + 4) codeLengths[CODE_LENGTH_ORDER[i]] = r.bits(3) ?: return null
        val lencode = Huffman.build(codeLengths) ?: return null
        val lengths = ArrayList<Int>()
        while (lengths.size < nlen + ndist) {
            when (val symbol = lencode.decode(r) ?: return null) {
                in 0..15 -> lengths.add(symbol)
                16 -> {
                    val previous = lengths.lastOrNull() ?: return null
                    val n = r.bits(2) ?: return null
                    repeat(3 + n) { lengths.add(previous) }
                }
                17 -> {
                    val n = r.bits(3) ?: return null
                    repeat(3 + n) { lengths.add(0) }
                }
                else -> {
                    val n = r.bits(7) ?: return null
                    repeat(11 + n) { lengths.add(0) }
                }
            }
        }
        if (lengths.size != nlen + ndist || lengths[256] == 0) return null
        val lit = Huffman.build(lengths.subList(0, nlen).toIntArray()) ?: return null
        val dist = Huffman.build(lengths.subList(nlen, lengths.size).toIntArray()) ?: return null
        return lit to dist
    }

    private fun codes(r: BitReader, out: Output, lit: Huffman, dist: Huffman, maxOutput: Int): Boolean {
        while (true) {
            val symbol = lit.decode(r) ?: return false
            if (symbol < 256) {
                if (out.count >= maxOutput) return false
                out.append(symbol)
            } else if (symbol == 256) {
                return true
            } else {
                val li = symbol - 257
                if (li >= LENGTH_BASE.size) return false
                val le = r.bits(LENGTH_EXTRA[li]) ?: return false
                val length = LENGTH_BASE[li] + le
                val ds = dist.decode(r) ?: return false
                if (ds >= DISTANCE_BASE.size) return false
                val de = r.bits(DISTANCE_EXTRA[ds]) ?: return false
                val distance = DISTANCE_BASE[ds] + de
                if (distance > out.count || out.count + length > maxOutput) return false
                val start = out.count - distance
                for (k in 0 until length) out.append(out[start + k])
            }
        }
    }
}
