package com.noop.huawei.decode

/**
 * The band's TruSleep files: the `sequence_data` sleep file (one record per night: a TLV summary and a
 * stage timeline) and `sleep_state.bin` (the TruSleep time frames). Layouts are protocol facts from
 * Gadgetbridge (`HuaweiSequenceDataFileParser.java`, `HuaweiSequenceDataParserBase.java`,
 * `HuaweiTrueSleepSequenceDataParser.java`, `HuaweiTruSleepParser.java`, AGPL-3.0), re-derived here; see
 * ATTRIBUTION.md. Kotlin-only; Swift twin deferred (fork).
 */
object HuaweiSleepFileDecoder {

    /** One stage span of a night, [start] ..< [end] in unix seconds; [stage] is "light", "rem", "deep" or "wake". */
    data class Stage(val start: Long, val end: Long, val stage: String)

    /**
     * One night of the sequence file. Times are unix seconds; a value the summary did not carry is null.
     * [stages] is empty when the night has no fall-asleep time to anchor its timeline to.
     */
    data class Night(
        val recordStart: Long,
        val recordEnd: Long,
        val fallAsleepTs: Long?,
        val bedTs: Long?,
        val risingTs: Long?,
        val wakeupTs: Long?,
        val validData: Int?,
        val sleepScore: Int?,
        val avgHrvMs: Int?,
        val avgHr: Int?,
        val stages: List<Stage>,
    )

    // Summary value ids (dictionary 700013).
    const val ID_FALL_ASLEEP = 700_013_686
    const val ID_BED_TIME = 700_013_298
    const val ID_RISING_TIME = 700_013_973
    const val ID_WAKEUP_TIME = 700_013_156
    const val ID_SLEEP_SCORE = 700_013_245
    const val ID_VALID_DATA = 700_013_786
    const val ID_AVG_HRV = 700_013_878
    const val ID_AVG_HR = 700_013_580

    private const val FILE_HEADER = 32
    private const val RECORD_HEADER = 32
    private const val DETAIL_RECORD = 8

    /** A detail span longer than a day is a misread, skipped like a zero span. */
    private const val MAX_STAGE_MINUTES = 1440

    /** Detail stage codes: 1 light, 2 REM, 3 deep, 4 awake, 5 nap (kept as light). */
    private val STAGE_NAMES = mapOf(1 to "light", 2 to "rem", 3 to "deep", 4 to "wake", 5 to "light")

    /**
     * Every night of one sequence_data file. Big-endian 32-byte file header (u32 size, u32 dictionary id,
     * u16 file type, u16 version, 20 reserved), then records of a 32-byte header (u32 start, u32 end,
     * u8 data version, u8 summary type, u16 summary length, u32 detail length, 16 reserved), the summary
     * and the detail. Only data version 1 with a TLV summary (type 2) in a file of type other than 2 carries
     * a readable summary. A record cut short ends the file: the nights before it are kept, never a guessed one.
     */
    fun sequenceFile(file: ByteArray): List<Night> {
        if (file.size < FILE_HEADER) return emptyList()
        val r = HuaweiByteReader(file, littleEndian = false)
        r.skip(8)
        val fileType = r.u16()
        r.seek(FILE_HEADER)
        val nights = ArrayList<Night>()
        try {
            while (r.remaining >= RECORD_HEADER) {
                val start = r.u32()
                val end = r.u32()
                val version = r.u8()
                val summaryType = r.u8()
                val summaryLen = r.u16()
                val detailLen = r.u32()
                r.skip(16)
                val summary = if (version == 1) r.bytes(summaryLen) else null
                if (detailLen > r.remaining) break
                val detail = r.bytes(detailLen.toInt())
                if (summary == null || summaryType != 2 || fileType == 2) continue
                nights.add(night(start, end, summary, detail) ?: continue)
            }
        } catch (_: HuaweiDecodeException) {
            // Truncated record: keep the complete nights before it.
        }
        return nights
    }

    private fun night(start: Long, end: Long, summary: ByteArray, detail: ByteArray): Night? {
        val values = summaryValues(summary) ?: return null
        fun long(id: Int): Long? = values[id]?.takeIf { it >= 0 }
        fun int(id: Int, max: Long): Int? = long(id)?.takeIf { it <= max }?.toInt()
        val fallAsleep = long(ID_FALL_ASLEEP)
        return Night(
            recordStart = start, recordEnd = end,
            fallAsleepTs = fallAsleep, bedTs = long(ID_BED_TIME), risingTs = long(ID_RISING_TIME),
            wakeupTs = long(ID_WAKEUP_TIME), validData = int(ID_VALID_DATA, Int.MAX_VALUE.toLong()),
            sleepScore = int(ID_SLEEP_SCORE, 100), avgHrvMs = int(ID_AVG_HRV, 200), avgHr = int(ID_AVG_HR, 255),
            stages = if (fallAsleep == null) emptyList() else stages(detail, fallAsleep - fallAsleep % 60) ?: emptyList(),
        )
    }

    /**
     * The stage timeline from [anchor] (the fall-asleep time, rounded down to the minute). Each 8-byte
     * LITTLE-endian record is an i32 duration in seconds, a stage byte and three pad bytes. A record with a
     * zero, non-whole-minute or over-a-day duration, or stage 0, is skipped without advancing the clock; an
     * unknown stage advances it but names no span. Null when the detail is not whole records.
     */
    fun stages(detail: ByteArray, anchor: Long): List<Stage>? {
        if (detail.size % DETAIL_RECORD != 0) return null
        val r = HuaweiByteReader(detail, littleEndian = true)
        val out = ArrayList<Stage>()
        var t = anchor
        while (r.remaining >= DETAIL_RECORD) {
            val duration = r.i32()
            val code = r.u8()
            r.skip(3)
            if (duration <= 0 || code == 0 || duration % 60 != 0 || duration / 60 > MAX_STAGE_MINUTES) continue
            val name = STAGE_NAMES[code]
            if (name != null) {
                val last = out.lastOrNull()
                if (last != null && last.stage == name && last.end == t) out[out.size - 1] = last.copy(end = t + duration)
                else out.add(Stage(t, t + duration, name))
            }
            t += duration
        }
        return out
    }

    /**
     * The summary's `{03 value id, 04 type, 05 value}` entries, each nested two containers deep, as signed
     * longs. Types 1, 2 and 4 are big-endian integers of up to 4 bytes (0xBF800000, float -1, means absent),
     * 3 a big-endian long of up to 8 bytes, 6 a big-endian IEEE double (truncated toward zero); strings (5)
     * and other types are dropped. Null when the bytes are not well-formed TLV.
     */
    fun summaryValues(summary: ByteArray): Map<Int, Long>? {
        val out = HashMap<Int, Long>()
        val top = tlv(summary) ?: return null
        for ((tag, outer) in top) {
            if (tag and 0x80 == 0) continue
            for ((tag2, inner) in tlv(outer) ?: return null) {
                if (tag2 and 0x80 == 0) continue
                val fields = tlv(inner)?.toMap() ?: return null
                val id = fields[0x03]?.let { HuaweiByteReader.beUnsigned(it) }?.takeIf { it <= Int.MAX_VALUE } ?: continue
                val type = fields[0x04]?.let { HuaweiByteReader.beUnsigned(it) } ?: continue
                val value = fields[0x05] ?: continue
                out[id.toInt()] = summaryValue(type.toInt(), value) ?: continue
            }
        }
        return out
    }

    private val FLOAT_MINUS_ONE = byteArrayOf(0xBF.toByte(), 0x80.toByte(), 0, 0)

    private fun summaryValue(type: Int, v: ByteArray): Long? = when (type) {
        1, 2, 4 -> if (v.isEmpty() || v.size > 4 || v.contentEquals(FLOAT_MINUS_ONE)) null
            else HuaweiByteReader.beUnsigned(v)!!.toInt().toLong()
        3 -> if (v.isEmpty()) null else HuaweiByteReader.beUnsigned(v)
        6 -> if (v.size != 8) null else java.lang.Double.longBitsToDouble(HuaweiByteReader.beUnsigned(v)!!)
            .takeIf { it.isFinite() }?.toLong()
        else -> null
    }

    /**
     * A flat TLV list: one tag byte, a length in 7-bit groups (most significant first, high bit = more),
     * then the value. Null when a length overruns the buffer. A trailing lone zero byte is padding.
     */
    internal fun tlv(b: ByteArray): List<Pair<Int, ByteArray>>? {
        val out = ArrayList<Pair<Int, ByteArray>>()
        var i = 0
        while (i < b.size) {
            val tag = b[i++].toInt() and 0xFF
            if (i == b.size && tag == 0) break
            var len = 0L
            while (true) {
                if (i >= b.size) return null
                val x = b[i++].toInt() and 0xFF
                len = (len shl 7) or (x and 0x7F).toLong()
                if (len > b.size) return null
                if (x and 0x80 == 0) break
            }
            if (len > b.size - i) return null
            out.add(tag to b.copyOfRange(i, i + len.toInt()))
            i += len.toInt()
        }
        return out
    }

    /** One TruSleep time frame, unix seconds. */
    data class StateFrame(val startTs: Long, val endTs: Long)

    /**
     * `sleep_state.bin`: 16-byte LITTLE-endian records of a u32 start, a u32 end and 8 undecoded bytes. A
     * trailing partial record is ignored; a frame ending before it starts is dropped.
     */
    fun stateFile(file: ByteArray): List<StateFrame> {
        val r = HuaweiByteReader(file, littleEndian = true)
        val out = ArrayList<StateFrame>()
        while (r.remaining >= 16) {
            val start = r.u32()
            val end = r.u32()
            r.skip(8)
            if (end >= start) out.add(StateFrame(start, end))
        }
        return out
    }
}
