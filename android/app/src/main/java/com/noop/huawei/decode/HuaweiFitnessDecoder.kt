package com.noop.huawei.decode

/**
 * The band's activity history (FitnessData service): per-minute step-data records and the legacy light /
 * deep sleep records. Layouts are protocol facts from Gadgetbridge (`devices/huawei/packets/FitnessData.java`,
 * `service/devices/huawei/requests/GetSleepDataRequest.java`, AGPL-3.0), re-derived here; see ATTRIBUTION.md.
 * Input is the value bytes wave 2 lifts out of the TLV containers; nothing here depends on the TLV codec.
 * Kotlin-only; Swift twin deferred (fork).
 */
object HuaweiFitnessDecoder {

    /**
     * One minute of activity. Every field is null when its bitmap bit was clear. [calories] is the band's
     * per-minute energy figure as sent (kcal assumed, unconfirmed on hardware); [distanceM] metres.
     */
    data class MinuteSample(
        val ts: Long,
        val steps: Int? = null,
        val calories: Int? = null,
        val distanceM: Int? = null,
        val heartRate: Int? = null,
        val spo2Pct: Int? = null,
        val restingHr: Int? = null,
    )

    /**
     * One step-data record: [baseTs] is the response's u32 base time (tag 0x03), [offsetMinutes] the record's
     * one-byte minute offset (tag 0x05), read UNSIGNED, and [data] its feature bytes (tag 0x06). Returns null
     * when the bytes are shorter than their bitmaps announce.
     *
     * Feature bitmap 1, bits 0x01..0x40 in order: 0x01 unknown (2 bytes), 0x02 steps, 0x04 calories,
     * 0x08 distance, 0x10 unknown (all 2-byte big-endian), 0x20 unknown and 0x40 heart rate (1 byte each).
     * Bit 0x80 announces bitmap 2, whose every bit carries one byte: 0x01 SpO2 %, 0x02 resting HR.
     */
    fun minuteSample(baseTs: Long, offsetMinutes: Int, data: ByteArray): MinuteSample? = try {
        val r = HuaweiByteReader(data, littleEndian = false)
        val bitmap1 = r.u8()
        val bitmap2 = if (bitmap1 and 0x80 != 0) r.u8() else 0
        var steps: Int? = null
        var calories: Int? = null
        var distance: Int? = null
        var hr: Int? = null
        for (bit in 0 until 7) {
            val mask = 1 shl bit
            if (bitmap1 and mask == 0) continue
            val v = if (mask == 0x20 || mask == 0x40) r.u8() else r.u16()
            when (mask) {
                0x02 -> steps = v
                0x04 -> calories = v
                0x08 -> distance = v
                0x40 -> hr = v
            }
        }
        var spo2: Int? = null
        var rhr: Int? = null
        for (bit in 0 until 8) {
            val mask = 1 shl bit
            if (bitmap2 and mask == 0) continue
            val v = r.u8()
            when (mask) {
                0x01 -> spo2 = v
                0x02 -> rhr = v
            }
        }
        val ts = (baseTs and 0xFFFF_FFFFL) + 60L * (offsetMinutes and 0xFF)
        MinuteSample(ts, steps, calories, distance, hr, spo2, rhr)
    } catch (_: HuaweiDecodeException) {
        null
    }

    enum class SleepStage { LIGHT, DEEP }

    /** One legacy sleep record, [startTs] ..< [endTs] in unix seconds. */
    data class SleepSpan(val startTs: Long, val endTs: Long, val stage: SleepStage)

    /**
     * One legacy sleep record (FitnessData 0x0D): [type] 0x06 light or 0x07 deep, [value] six bytes holding
     * the u32 big-endian start and the u16 big-endian duration in MINUTES. Null for any other type or
     * length. The duration is widened before scaling, so a long span cannot wrap the way a 16-bit product
     * would.
     */
    fun sleepSpan(type: Int, value: ByteArray): SleepSpan? {
        if (value.size != 6) return null
        val stage = when (type and 0xFF) {
            0x06 -> SleepStage.LIGHT
            0x07 -> SleepStage.DEEP
            else -> return null
        }
        val r = HuaweiByteReader(value, littleEndian = false)
        val start = r.u32()
        val minutes = r.u16().toLong()
        if (minutes == 0L) return null
        return SleepSpan(start, start + minutes * 60, stage)
    }
}
