package com.noop.huawei.decode

import kotlin.math.cos

/**
 * The band's workout records: totals, per-sample data, pace blocks and the GPS track file. Field tags,
 * layouts and scales are protocol facts from Gadgetbridge (`devices/huawei/packets/Workout.java`,
 * `HuaweiWorkoutGbParser.java`, `HuaweiGpsParser.java`, AGPL-3.0), re-derived here; see ATTRIBUTION.md.
 * Totals and pace take the tag → value-bytes map of one TLV container, which wave 2 extracts.
 * Kotlin-only; Swift twin deferred (fork).
 */
object HuaweiWorkoutDecoder {

    /**
     * One workout's totals. Times are unix seconds; [aerobicEffect] and [anaerobicEffect] are already
     * divided by ten (0.0-5.0), [elevationGainM] likewise; null where the band sent nothing.
     */
    data class Totals(
        val number: Int?,
        val status: Int?,
        val startTs: Long,
        val endTs: Long,
        val kcal: Long?,
        val distanceM: Long?,
        val steps: Long?,
        val totalTimeS: Long?,
        val durationS: Long?,
        val type: Int?,
        val minHr: Int?,
        val maxHr: Int?,
        val load: Long?,
        val aerobicEffect: Double?,
        val anaerobicEffect: Double?,
        val elevationGainM: Double?,
    )

    /**
     * The totals container (0x81) of one WorkoutTotals reply as tag → value bytes. Integers are big-endian
     * unsigned of whatever width the band used (1-4 bytes). Null without a start (0x04) and end (0x05).
     */
    fun totals(fields: Map<Int, ByteArray>): Totals? {
        fun n(tag: Int): Long? = fields[tag]?.takeIf { it.size in 1..4 }?.let { HuaweiByteReader.beUnsigned(it) }
        val start = n(0x04) ?: return null
        val end = n(0x05) ?: return null
        val hr = fields[0x0C]?.takeIf { it.size >= 2 }
        return Totals(
            number = n(0x02)?.toInt(), status = n(0x03)?.toInt(), startTs = start, endTs = end,
            kcal = n(0x06), distanceM = n(0x07), steps = n(0x08), totalTimeS = n(0x09), durationS = n(0x12),
            type = n(0x14)?.toInt(),
            minHr = hr?.let { it[0].toInt() and 0xFF }, maxHr = hr?.let { it[1].toInt() and 0xFF },
            load = n(0x0D), aerobicEffect = n(0x0E)?.let { it / 10.0 }, anaerobicEffect = n(0x20)?.let { it / 10.0 },
            elevationGainM = n(0x0B)?.let { it / 10.0 },
        )
    }

    /** One sample of a workout's data stream; a field is null when its bitmap bit was clear. */
    data class Sample(
        val ts: Long,
        val heartRate: Int? = null,
        /** Metres per second. */
        val speedMps: Double? = null,
        val stepRate: Int? = null,
        val swolf: Int? = null,
        val strokeRate: Int? = null,
        /** Metres. */
        val altitudeM: Double? = null,
        val kcal: Int? = null,
        val frequency: Int? = null,
        val powerW: Int? = null,
        /** The inner block's fields by inner-bitmap bit index (cadence = 0, step length = 1, ...), unsigned. */
        val inner: Map<Int, Int> = emptyMap(),
    )

    /** Byte widths of the header bitmap's bits 0..9; bit 6 is the inner block, sized by the inner bitmap. */
    private val FIELD_WIDTHS = intArrayOf(1, 2, 1, 2, 2, 4, -1, 2, 2, 2)

    /** Byte widths of the inner bitmap's bits 0..22. */
    private val INNER_WIDTHS = intArrayOf(2, 2, 2, 1, 2, 1, 1, 1, 1, 2, 2, 1, 2, 2, 2, 2, 1, 1, 1, 2, 2, 1, 2)

    /**
     * One WorkoutData reply. [header] (tag 0x04) is 14 big-endian bytes: u16 workout number, u16 data
     * number, u32 first timestamp, u8 interval in seconds, u16 sample count, u8 sample length, u16 bitmap.
     * [data] (tag 0x05) holds the samples; [innerBitmap] (tag 0x09) and [innerLength] (tag 0x08) size the
     * inner block (bit 6), and are the caller's to default when the band omits them. Every sample's
     * fields follow the bitmap's bit order. Empty when the lengths disagree with the header or the bitmap,
     * or the bitmap sets a bit of unknown width: a mis-sized stream would shift every later field.
     */
    fun samples(header: ByteArray, data: ByteArray, innerBitmap: Int = 0, innerLength: Int = 0): List<Sample> {
        if (header.size < 14) return emptyList()
        val h = HuaweiByteReader(header, littleEndian = false)
        h.skip(4)
        val first = h.u32()
        val interval = h.u8()
        val count = h.u16()
        val sampleLen = h.u8()
        val bitmap = h.u16()
        if (count * sampleLen != data.size) return emptyList()
        if (bitmap ushr FIELD_WIDTHS.size != 0) return emptyList()
        var innerNeeded = 0
        for (i in INNER_WIDTHS.indices) if (innerBitmap and (1 shl i) != 0) innerNeeded += INNER_WIDTHS[i]
        if (innerBitmap ushr INNER_WIDTHS.size != 0 || innerNeeded > innerLength) return emptyList()
        var perSample = 0
        for (i in FIELD_WIDTHS.indices) if (bitmap and (1 shl i) != 0) perSample += if (i == 6) innerLength else FIELD_WIDTHS[i]
        if (perSample * count != data.size) return emptyList()

        val r = HuaweiByteReader(data, littleEndian = false)
        return List(count) { idx ->
            var s = Sample(ts = first + interval.toLong() * idx)
            for (bit in FIELD_WIDTHS.indices) {
                if (bitmap and (1 shl bit) == 0) continue
                s = when (bit) {
                    0 -> s.copy(heartRate = r.u8())
                    1 -> s.copy(speedMps = r.u16() / 10.0)
                    2 -> s.copy(stepRate = r.u8())
                    3 -> s.copy(swolf = r.u16())
                    4 -> s.copy(strokeRate = r.u16())
                    5 -> s.copy(altitudeM = r.i32() / 10.0)
                    6 -> s.copy(inner = inner(r.bytes(innerLength), innerBitmap))
                    7 -> s.copy(kcal = r.u16())
                    8 -> s.copy(frequency = r.u16())
                    else -> s.copy(powerW = r.u16())
                }
            }
            s
        }
    }

    private fun inner(block: ByteArray, bitmap: Int): Map<Int, Int> {
        val r = HuaweiByteReader(block, littleEndian = false)
        val out = LinkedHashMap<Int, Int>()
        for (i in INNER_WIDTHS.indices) {
            if (bitmap and (1 shl i) == 0) continue
            out[i] = if (INNER_WIDTHS[i] == 1) r.u8() else r.u16()
        }
        return out
    }

    /** One pace block: [paceS] seconds per [distance] unit, [type] the band's unit code. */
    data class PaceBlock(val distance: Int, val type: Int, val paceS: Long, val pointIndex: Int?, val correction: Int?)

    /** One pace block container (0x83) as tag → value bytes; null without its distance, type and pace. */
    fun paceBlock(fields: Map<Int, ByteArray>): PaceBlock? {
        fun n(tag: Int): Long? = fields[tag]?.takeIf { it.size in 1..4 }?.let { HuaweiByteReader.beUnsigned(it) }
        return PaceBlock(
            distance = n(0x04)?.toInt() ?: return null, type = n(0x05)?.toInt() ?: return null,
            paceS = n(0x06) ?: return null, pointIndex = n(0x07)?.toInt(), correction = n(0x09)?.toShort()?.toInt(),
        )
    }

    /** One GPS track point, unix seconds and WGS-84-ish degrees as the band computes them. */
    data class GpsPoint(val ts: Long, val lat: Double, val lon: Double, val altitudeM: Double?, val paused: Boolean)

    /** Earth radius the band's metre offsets are scaled by (the Krassovsky 1940 ellipsoid's major axis). */
    private const val EARTH_RADIUS_M = 6_378_245.0

    /**
     * The GPS track file, little-endian from byte 0x20: a type byte (low two bits both set = altitude
     * present), u32 first timestamp, f64 longitude, f64 latitude in degrees, f64 altitude when present;
     * points start at byte 62 (70 with altitude). Each point is i16 time delta, u16 unknown, f32 east and
     * f32 north offset deltas in metres, two unknown bytes and a pause flag, plus i16 altitude and i16
     * unknown with altitude. Offsets accumulate from the first fix; a point is the fix moved by its summed
     * offset on a sphere. A trailing partial point is ignored; a point off the globe ends the track.
     */
    fun gpsFile(file: ByteArray): List<GpsPoint> {
        if (file.size < 0x21) return emptyList()
        val r = HuaweiByteReader(file, littleEndian = true, start = 0x20)
        return try {
            val hasAlt = r.u8() and 0x03 == 0x03
            var t = r.u32()
            val lon0 = r.f64()
            val lat0 = r.f64()
            if (hasAlt) r.f64()
            r.seek(if (hasAlt) 70 else 62)
            if (!lat0.isFinite() || !lon0.isFinite() || lat0 !in -90.0..90.0 || lon0 !in -180.0..180.0) return emptyList()
            val cosLat = cos(Math.toRadians(lat0))
            val size = if (hasAlt) 19 else 15
            var east = 0.0
            var north = 0.0
            val out = ArrayList<GpsPoint>()
            while (r.remaining >= size) {
                t += r.i16()
                r.skip(2)
                east += r.f32()
                north += r.f32()
                r.skip(2)
                val paused = r.u8() == 1
                val alt = if (hasAlt) r.i16().toDouble().also { r.skip(2) } else null
                val lat = lat0 + Math.toDegrees(north / EARTH_RADIUS_M)
                val lon = lon0 + Math.toDegrees(east / EARTH_RADIUS_M / cosLat)
                if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) break
                out.add(GpsPoint(t, lat, lon, alt, paused))
            }
            out
        } catch (_: HuaweiDecodeException) {
            emptyList()
        }
    }
}
