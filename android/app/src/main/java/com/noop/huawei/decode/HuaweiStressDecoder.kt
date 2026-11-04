package com.noop.huawei.decode

/**
 * The band's stress file `rrisqi_data.bin`. Layout is a protocol fact from Gadgetbridge
 * (`HuaweiStressParser.java`, AGPL-3.0), re-derived here; see ATTRIBUTION.md. Kotlin-only; Swift twin
 * deferred (fork).
 */
object HuaweiStressDecoder {

    /** One stress measurement, [startTs] ..< [endTs] in unix seconds; [score] is the band's 0-100 score. */
    data class Entry(
        val startTs: Long,
        val endTs: Long,
        val algorithm: Long,
        val score: Int,
        val level: Int,
        val cFlag: Int,
        val type: Int,
        val accFlag: Int,
        val ppgFlag: Int,
        val features: List<Float>,
    )

    private const val HEADER = 48
    private const val ENTRY = 66
    private const val FEATURES = 12

    /**
     * Big-endian header of a u64 size, a u16 bitmap and a u16 version; only version 3 is known, its
     * entries starting at byte 48, 66 bytes each: u32 start, u32 end, u32 algorithm, six bytes (score,
     * level, c flag, type, acc flag, ppg flag) and twelve float32 features. Entries without a start or end
     * are dropped. Sorted by end. Any other version, or a file shorter than its header, yields nothing.
     */
    fun rriFile(file: ByteArray): List<Entry> {
        if (file.size < HEADER) return emptyList()
        val r = HuaweiByteReader(file, littleEndian = false)
        r.skip(10)
        if (r.u16() != 3) return emptyList()
        r.seek(HEADER)
        val out = ArrayList<Entry>()
        while (r.remaining >= ENTRY) {
            val start = r.u32()
            val end = r.u32()
            val algorithm = r.u32()
            val score = r.u8()
            val level = r.u8()
            val cFlag = r.u8()
            val type = r.u8()
            val acc = r.u8()
            val ppg = r.u8()
            val features = List(FEATURES) { r.f32() }
            if (start == 0L || end == 0L) continue
            out.add(Entry(start, end, algorithm, score, level, cFlag, type, acc, ppg, features))
        }
        return out.sortedBy { it.endTs }
    }
}
