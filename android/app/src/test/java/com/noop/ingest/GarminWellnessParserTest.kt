package com.noop.ingest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Garmin wellness FIT decoding, held byte-identical to Swift by oracle: [EXPECTED] is the stdout of
 * the Swift twin (`Packages/StrandImport/Tests/StrandImportTests/GarminWellnessFixtures.swift`, built
 * standalone as described in its header), pasted verbatim. The fixture builder and dump below mirror
 * that file line for line; no bytes come from Gadgetbridge or a real watch.
 */
class GarminWellnessParserTest {

    @Test
    fun oracleOutputMatchesSwift() {
        assertEquals(EXPECTED, oracleOutput())
    }

    @Test
    fun midnightRecordCountsTowardTheEndedDay() {
        val s = GarminWellnessParser.parse(midnight())
        assertEquals(listOf("2024-11-08", "2024-11-09"), s.dailyActivity.map { it.day })
        assertEquals(listOf(16_100, 31), s.dailyActivity.map { it.steps })
        assertEquals(listOf(790, 1), s.dailyActivity.map { it.activeCalories })
        // A file holding only the 00:00:00 records (as the watch's last file of a day can) still
        // credits them to the day that ended, never to the new one.
        val midnightOnly = s.monitoring.filter { (it.ts + 21_600) % 86_400 == 0L }
        assertEquals(2, midnightOnly.size)
        val days = GarminWellnessParser.dailyTotals(midnightOnly, 21_600)
        assertEquals(listOf("2024-11-08"), days.map { it.day })
        assertEquals(listOf(16_100), days.map { it.steps })
    }

    @Test
    fun candidateMessagesAreFlagged() {
        assertEquals(setOf(290, 397, 398), GarminWellnessParser.candidateMessages)
    }

    @Test
    fun notAFitFileIsEmptyAndIncomplete() {
        val cases = listOf(
            ByteArray(0),
            "hello, not a fit file".toByteArray(),
            byteArrayOf(12, 0, 0, 0, 0, 0, 0, 0, 0x2E, 0x46, 0x49, 0x54),
        )
        for (d in cases) {
            val s = GarminWellnessParser.parse(d)
            assertTrue(s.isEmpty)
            assertFalse(s.complete)
        }
    }

    @Test
    fun everyTruncationIsSafe() {
        for ((name, build) in ALL) {
            val full = build()
            val complete = GarminWellnessParser.parse(full)
            assertTrue(name, complete.complete)
            // The header keeps claiming the full size, so every cut is clamped, never over-read.
            for (cut in 0 until full.size - 2) {
                val s = GarminWellnessParser.parse(full.copyOfRange(0, cut))
                assertTrue(
                    name,
                    s.monitoring.size + s.sleepLevels.size + s.sleepRaw.size <=
                        complete.monitoring.size + complete.sleepLevels.size + complete.sleepRaw.size,
                )
            }
        }
    }

    @Test
    fun truncatedDefinitionStops() {
        val f = WellnessFit()
        f.raw(0x40, 0, 0, 0x37)          // a definition cut inside its global message number
        val s = GarminWellnessParser.parse(f.finish())
        assertFalse(s.complete)
        assertTrue(s.isEmpty)
    }

    @Test
    fun hugeFieldCountsStopWithoutReading() {
        val f = WellnessFit()
        // 255 fields of 255 bytes each: a 65 KB record the file does not hold.
        f.def(0, 55, List(255) { Triple(3, 255, U32) })
        f.data(0)
        f.raw(1, 2, 3)
        val s = GarminWellnessParser.parse(f.finish())
        assertFalse(s.complete)
        assertTrue(s.monitoring.isEmpty())

        val g = WellnessFit()
        g.raw(0x60, 0, 0, 55, 0, 0, 255) // 255 developer fields, none present
        assertFalse(GarminWellnessParser.parse(g.finish()).complete)
    }

    @Test
    fun zeroSizeFieldAndUndefinedLocalTypeStop() {
        val f = WellnessFit()
        f.def(0, 371, listOf(Triple(253, 4, U32), Triple(0, 0, U16)))
        assertFalse(GarminWellnessParser.parse(f.finish()).complete)

        val g = WellnessFit()
        g.data(5); g.u32(T)
        assertFalse(GarminWellnessParser.parse(g.finish()).complete)
        val h = WellnessFit()
        h.compressed(3, 1)
        assertFalse(GarminWellnessParser.parse(h.finish()).complete)
    }

    @Test
    fun timestamp16WithoutReferenceIsDropped() {
        val f = WellnessFit()
        f.def(0, 55, listOf(Triple(26, 2, U16), Triple(27, 1, U8)))
        f.data(0); f.u16(0x1234); f.u8(60)
        val s = GarminWellnessParser.parse(f.finish())
        assertTrue(s.monitoring.isEmpty())
        assertEquals(1, s.droppedMessages)
    }

    @Test
    fun recordCapStopsTheWalk() {
        val stream = FitRecordStream.open(sleep())!!
        var seen = 0
        assertEquals(FitRecordStream.Stop.RECORD_CAP, stream.walk(maxRecords = 3) { _, _, _ -> seen++ })
        assertEquals(1, seen)            // one definition, one data message, then a definition
    }

    @Test
    fun randomBytesNeverCrash() {
        var seed = -0x61c8864680b583ebL
        fun next(): Int {
            seed = seed * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L
            return ((seed ushr 33) and 0xFF).toInt()
        }
        repeat(2000) {
            val f = WellnessFit()
            val n = next()
            f.raw(*IntArray(n) { next() })
            GarminWellnessParser.parse(f.finish())
        }
        // Mutating real fixtures exercises deeper paths than pure noise.
        for ((_, build) in ALL) {
            val bytes = build()
            repeat(300) {
                bytes[12 + next() % (bytes.size - 12)] = next().toByte()
                GarminWellnessParser.parse(bytes)
            }
        }
    }

    @Test
    fun wellnessFileYieldsNoActivityButCarriesWellness() {
        // The activity decoder shares the record walk but must ignore health messages; a FIT file with
        // no workout hands its wellness data back instead.
        val r = ActivityFileImporter.parse(monitoring(), "monitor.fit")
        assertNull(r.activity)
        assertEquals(GarminWellnessParser.parse(monitoring()), r.wellness)
        assertEquals(4, r.wellness?.monitoring?.size)
        // A workout keeps no wellness payload, and a non-FIT file never gets one.
        assertNull(ActivityFileImporter.parse("<gpx></gpx>".toByteArray(), "a.gpx").wellness)
    }

    // MARK: - Fixture builder (twin of Swift `WellnessFit`)

    internal class WellnessFit {
        private val records = ByteArrayOutputStream()
        var bigEndian = false

        fun def(
            local: Int,
            global: Int,
            fields: List<Triple<Int, Int, Int>>,
            bigEndian: Boolean = false,
            devFieldSizes: List<Int> = emptyList(),
        ) {
            records.write(0x40 or (if (devFieldSizes.isEmpty()) 0 else 0x20) or (local and 0x0F))
            records.write(0)
            records.write(if (bigEndian) 1 else 0)
            if (bigEndian) {
                records.write((global shr 8) and 0xFF); records.write(global and 0xFF)
            } else {
                records.write(global and 0xFF); records.write((global shr 8) and 0xFF)
            }
            records.write(fields.size)
            for (f in fields) { records.write(f.first); records.write(f.second); records.write(f.third) }
            if (devFieldSizes.isNotEmpty()) {
                records.write(devFieldSizes.size)
                devFieldSizes.forEachIndexed { k, size -> records.write(k); records.write(size); records.write(0) }
            }
            this.bigEndian = bigEndian
        }

        fun data(local: Int) = records.write(local and 0x0F)
        fun compressed(local: Int, offset: Int) = records.write(0x80 or ((local and 0x03) shl 5) or (offset and 0x1F))
        fun raw(vararg bytes: Int) = bytes.forEach { records.write(it and 0xFF) }
        fun u8(v: Int) = records.write(v and 0xFF)
        fun uint(v: Long, size: Int) {
            val le = (0 until size).map { ((v shr (8 * it)) and 0xFF).toInt() }
            (if (bigEndian) le.reversed() else le).forEach { records.write(it) }
        }
        fun u16(v: Int) = uint(v.toLong(), 2)
        fun u32(v: Long) = uint(v, 4)
        fun f32(v: Float) = uint(v.toRawBits().toLong() and 0xFFFFFFFFL, 4)

        fun finish(): ByteArray {
            val body = records.toByteArray()
            val out = ByteArrayOutputStream()
            out.write(12); out.write(0x20); out.write(0); out.write(0)
            for (k in 0 until 4) out.write((body.size shr (8 * k)) and 0xFF)
            out.write(".FIT".toByteArray())
            out.write(body)
            out.write(0); out.write(0)   // file CRC, unchecked by the decoders
            return out.toByteArray()
        }
    }

    internal companion object {
        /** A FIT time whose low 16 bits sit just below 0xFFFF, so timestamp_16 rolls over (0x4190FFC0). */
        const val T = 1_100_021_696L
        /** +06:00, chosen so the monitoring fixture crosses local midnight. */
        const val OFFSET = 21_600L

        const val U8 = 0x02
        const val S8 = 0x01
        const val EN = 0x00
        const val BY = 0x0D
        const val U16 = 0x84
        const val S16 = 0x83
        const val U32 = 0x86
        const val F32 = 0x88

        fun monitoring(): ByteArray {
            val f = WellnessFit()
            f.def(0, 103, listOf(Triple(253, 4, U32), Triple(0, 4, U32), Triple(5, 2, U16)))
            f.data(0); f.u32(T); f.u32(T + OFFSET); f.u16(1650)
            f.def(1, 55, listOf(Triple(253, 4, U32), Triple(5, 1, EN), Triple(3, 4, U32), Triple(2, 4, U32), Triple(1, 2, U16), Triple(19, 2, U16), Triple(27, 1, U8), Triple(24, 1, BY)))
            f.data(1); f.u32(T); f.u8(6); f.u32(1000); f.u32(80_000); f.u16(1700); f.u16(120); f.u8(61); f.u8((2 shl 5) or 6)
            f.def(2, 55, listOf(Triple(26, 2, U16), Triple(5, 1, EN), Triple(3, 4, U32), Triple(1, 2, U16), Triple(27, 1, U8), Triple(33, 2, U16), Triple(34, 2, U16), Triple(4, 4, U32)))
            // timestamp_16 rolls over 0xFFFF -> 0x0020 (t + 96).
            f.data(2); f.u16(((T + 96) and 0xFFFF).toInt()); f.u8(1); f.u32(400); f.u16(1750); f.u8(90); f.u16(5); f.u16(2); f.u32(2500)
            f.data(2); f.u16(((T + 128) and 0xFFFF).toInt()); f.u8(6); f.u32(1200); f.u16(1760); f.u8(70); f.u16(0xFFFF); f.u16(0xFFFF); f.u32(0xFFFF_FFFFL)
            // An hour later: past local midnight, so the walking counters have reset.
            f.data(2); f.u16(((T + 3600) and 0xFFFF).toInt()); f.u8(6); f.u32(50); f.u16(10); f.u8(58); f.u16(0); f.u16(0); f.u32(0xFFFF_FFFFL)
            // A record with nothing but invalid values is dropped.
            f.data(2); f.u16(((T + 3660) and 0xFFFF).toInt()); f.u8(0xFF); f.u32(0xFFFF_FFFFL); f.u16(0xFFFF); f.u8(0xFF); f.u16(0xFFFF); f.u16(0xFFFF); f.u32(0xFFFF_FFFFL)
            return f.finish()
        }

        /**
         * Local midnight shaped like a Forerunner 165 day boundary: at exactly 00:00:00 the watch writes
         * one record per activity type still carrying the ENDED day's totals, then the counters reset.
         */
        fun midnight(): ByteArray {
            val midnight = T + 1504 // T + OFFSET is 23:34:56 local
            val f = WellnessFit()
            f.def(0, 103, listOf(Triple(253, 4, U32), Triple(0, 4, U32)))
            f.data(0); f.u32(T); f.u32(T + OFFSET)
            f.def(1, 55, listOf(Triple(253, 4, U32), Triple(5, 1, EN), Triple(3, 4, U32), Triple(2, 4, U32), Triple(19, 2, U16)))
            f.data(1); f.u32(midnight - 600); f.u8(1); f.u32(11_000); f.u32(850_000); f.u16(560)
            // Written out of order: an earlier, lower reading after a later one must not lower the total.
            f.data(1); f.u32(midnight - 300); f.u8(6); f.u32(3_400); f.u32(240_000); f.u16(140)
            f.data(1); f.u32(midnight - 900); f.u8(6); f.u32(3_100); f.u32(220_000); f.u16(130)
            // 00:00:00: the ended day's final totals.
            f.data(1); f.u32(midnight); f.u8(1); f.u32(12_500); f.u32(960_000); f.u16(640)
            f.data(1); f.u32(midnight); f.u8(6); f.u32(3_600); f.u32(250_000); f.u16(150)
            // 00:00:01 onwards: the new day's counters, starting again from zero.
            f.data(1); f.u32(midnight + 1); f.u8(6); f.u32(5); f.u32(400); f.u16(0)
            f.data(1); f.u32(midnight + 60); f.u8(6); f.u32(31); f.u32(2_400); f.u16(1)
            return f.finish()
        }

        fun restingHeartRate(): ByteArray {
            val f = WellnessFit()
            f.def(0, 211, listOf(Triple(253, 4, U32), Triple(0, 1, U8), Triple(1, 1, U8)))
            f.data(0); f.u32(T); f.u8(55); f.u8(52)
            f.data(0); f.u32(T + 86_400); f.u8(57); f.u8(0xFF)
            f.data(0); f.u32(T + 172_800); f.u8(0xFF); f.u8(0)
            return f.finish()
        }

        fun stress(): ByteArray {
            val f = WellnessFit()
            f.def(0, 227, listOf(Triple(0, 2, S16), Triple(1, 4, U32), Triple(3, 1, S8)))
            f.data(0); f.u16(25); f.u32(T); f.u8(80)
            f.data(0); f.u16(-1); f.u32(T + 180); f.u8(79)
            f.data(0); f.u16(-2); f.u32(T + 360); f.u8(0x7F)
            // No stress_level_time and no running timestamp yet: dropped.
            f.data(0); f.u16(40); f.u32(0xFFFF_FFFFL); f.u8(60)
            f.def(1, 371, listOf(Triple(253, 4, U32), Triple(0, 2, U16)))
            f.data(1); f.u32(T + 540); f.u16(4096)
            // Now the running timestamp (t + 540) stands in.
            f.data(0); f.u16(101); f.u32(0xFFFF_FFFFL); f.u8(55)
            return f.finish()
        }

        fun spo2(): ByteArray {
            val f = WellnessFit()
            f.def(0, 269, listOf(Triple(253, 4, U32), Triple(0, 1, U8), Triple(1, 1, U8), Triple(2, 1, EN)))
            f.data(0); f.u32(T); f.u8(96); f.u8(3); f.u8(3)
            f.data(0); f.u32(T + 60); f.u8(0); f.u8(1); f.u8(3)
            f.data(0); f.u32(T + 120); f.u8(94); f.u8(0xFF); f.u8(1)
            return f.finish()
        }

        fun halfRow(movement: Int, heartRate: Int): IntArray {
            val row = IntArray(20)
            row[16] = movement and 0xFF; row[17] = movement shr 8
            row[18] = heartRate and 0xFF; row[19] = heartRate shr 8
            return row
        }

        fun sleep(): ByteArray {
            val f = WellnessFit()
            f.def(0, 273, listOf(Triple(253, 4, U32), Triple(1, 2, U16), Triple(2, 4, U32)))
            f.data(0); f.u32(T); f.u16(60); f.u32(T + OFFSET)
            f.def(1, 274, listOf(Triple(0, 20, BY)))
            f.def(2, 275, listOf(Triple(253, 4, U32), Triple(0, 1, EN)))
            // movement 0.5, heart rate 62.0
            f.data(1); f.raw(*halfRow(0x3800, 0x53C0))
            // movement 2^-24 (subnormal), heart rate +infinity -> none
            f.data(1); f.raw(*halfRow(0x0001, 0x7C00))
            // An awake row takes the third slot.
            f.data(2); f.u32(T + 120); f.u8(1)
            // movement -1.0 -> none, heart rate 300 -> out of range
            f.data(1); f.raw(*halfRow(0xBC00, 0x5CB0))
            f.data(2); f.u32(T + 600); f.u8(2)
            f.data(2); f.u32(T + 1200); f.u8(3)
            f.data(2); f.u32(T + 1800); f.u8(4)
            f.data(2); f.u32(T + 2400); f.u8(1)
            f.data(2); f.u32(T + 2400); f.u8(9)          // not a sleep level: dropped
            f.data(2); f.u32(T + 20_000); f.u8(2)        // over four hours later, no sleep window: a new night
            f.def(
                3, 346,
                listOf(
                    Triple(6, 1, U8), Triple(0, 1, U8), Triple(1, 1, U8), Triple(2, 1, U8), Triple(3, 1, U8), Triple(4, 1, U8), Triple(5, 1, U8),
                    Triple(7, 1, U8), Triple(8, 1, U8), Triple(9, 1, U8), Triple(10, 1, U8), Triple(11, 1, U8), Triple(14, 1, U8), Triple(15, 2, U16),
                ),
            )
            f.data(3); f.raw(82, 70, 71, 72, 73, 74, 75, 77, 78, 79, 80, 3, 0xFF); f.u16(2250)
            return f.finish()
        }

        /** Shaped like a real Forerunner 165 night (#47; relative times only). Swift twin: `WellnessFixtures.sleepWindow`. */
        fun sleepWindow(): ByteArray {
            val f = WellnessFit()
            f.def(0, 21, listOf(Triple(253, 4, U32), Triple(0, 1, EN), Triple(1, 1, EN)))
            f.data(0); f.u32(T - 600); f.u8(74); f.u8(1)    // a stop with no start: ignored
            f.data(0); f.u32(T); f.u8(0); f.u8(0)           // timer event: not sleep
            f.data(0); f.u32(T); f.u8(74); f.u8(0)          // sleep onset
            f.data(0); f.u32(T + 60); f.u8(74); f.u8(4)     // unknown event type: dropped
            f.def(1, 275, listOf(Triple(253, 4, U32), Triple(0, 1, EN)))
            f.data(1); f.u32(T + 1020); f.u8(2)
            f.data(1); f.u32(T + 3480); f.u8(3)
            f.data(1); f.u32(T + 3540); f.u8(1)
            f.data(1); f.u32(T + 6000); f.u8(2)
            f.data(1); f.u32(T + 9960); f.u8(3)
            f.data(1); f.u32(T + 10440); f.u8(2)
            f.data(1); f.u32(T + 11340); f.u8(4)
            f.data(1); f.u32(T + 19620); f.u8(2)
            f.data(1); f.u32(T + 19680); f.u8(1)
            f.data(1); f.u32(T + 20760); f.u8(2)
            f.data(1); f.u32(T + 22020); f.u8(1)
            f.data(1); f.u32(T + 23520); f.u8(2)
            f.data(1); f.u32(T + 25080); f.u8(4)
            f.data(1); f.u32(T + 26160); f.u8(2)
            f.data(1); f.u32(T + 29623); f.u8(4)
            f.data(0); f.u32(T + 29623); f.u8(74); f.u8(1)  // wake
            return f.finish()
        }

        /** #49: a night with a 30 minute unmeasurable span, then a nap ending the same day. Swift twin: `WellnessFixtures.sleepNap`. */
        fun sleepNap(): ByteArray {
            val f = WellnessFit()
            f.def(0, 21, listOf(Triple(253, 4, U32), Triple(0, 1, EN), Triple(1, 1, EN)))
            f.def(1, 275, listOf(Triple(253, 4, U32), Triple(0, 1, EN)))
            f.data(0); f.u32(T); f.u8(74); f.u8(0)
            f.data(1); f.u32(T + 1800); f.u8(2)
            f.data(1); f.u32(T + 5400); f.u8(3)
            f.data(1); f.u32(T + 7200); f.u8(0)
            f.data(1); f.u32(T + 14400); f.u8(4)
            f.data(1); f.u32(T + 16200); f.u8(1)
            f.data(1); f.u32(T + 27000); f.u8(2)
            f.data(0); f.u32(T + 27000); f.u8(74); f.u8(1)
            f.data(0); f.u32(T + 50000); f.u8(74); f.u8(0)
            f.data(1); f.u32(T + 51200); f.u8(2)
            f.data(1); f.u32(T + 52400); f.u8(1)
            f.data(0); f.u32(T + 52400); f.u8(74); f.u8(1)
            return f.finish()
        }

        fun respiration(): ByteArray {
            val f = WellnessFit()
            f.def(0, 297, listOf(Triple(253, 4, U32), Triple(0, 2, S16)))
            f.data(0); f.u32(T); f.u16(1450)
            f.data(0); f.u32(T + 60); f.u16(-1)
            f.data(0); f.u32(T + 120); f.u16(0x7FFF)
            return f.finish()
        }

        fun hrv(): ByteArray {
            val f = WellnessFit()
            f.def(0, 370, listOf(Triple(253, 4, U32), Triple(0, 2, U16), Triple(1, 2, U16), Triple(2, 2, U16), Triple(3, 2, U16), Triple(4, 2, U16), Triple(5, 2, U16), Triple(6, 1, EN)))
            f.data(0); f.u32(T); f.u16(5120); f.u16(4800); f.u16(7000); f.u16(0xFFFF); f.u16(4352); f.u16(6400); f.u8(4)
            f.def(1, 371, listOf(Triple(253, 4, U32), Triple(0, 2, U16)))
            f.data(1); f.u32(T + 300); f.u16(5000)
            f.data(1); f.u32(T + 600); f.u16(0xFFFF)
            return f.finish()
        }

        fun candidates(): ByteArray {
            val f = WellnessFit()
            f.def(0, 290, listOf(Triple(253, 4, U32), Triple(0, 2, U16), Triple(1, 8, U16)))
            f.data(0); f.u32(T); f.u16(512); f.u16(812); f.u16(0xFFFF); f.u16(790); f.u16(801)
            f.def(1, 397, listOf(Triple(253, 4, U32), Triple(1, 4, F32)))
            f.data(1); f.u32(T + 60); f.f32(-0.25f)
            f.data(1); f.u32(T + 120); f.u32(0xFFFF_FFFFL)
            f.def(2, 398, listOf(Triple(253, 4, U32), Triple(0, 4, U32), Triple(1, 4, F32), Triple(2, 4, F32), Triple(3, 1, U8), Triple(4, 4, F32)))
            f.data(2); f.u32(T + 3600); f.u32(T + 3600 - 18_000); f.f32(0.1f); f.f32(-0.3f); f.u8(14); f.f32(33.5f)
            return f.finish()
        }

        /** Big-endian definitions, developer fields, compressed timestamps, unknown messages and a redefined local type. */
        fun framing(): ByteArray {
            val f = WellnessFit()
            f.def(0, 0, listOf(Triple(4, 4, U32), Triple(0, 1, EN)))
            f.data(0); f.u32(T); f.u8(32)
            f.def(1, 371, listOf(Triple(253, 4, U32), Triple(0, 2, U16)), bigEndian = true)
            f.data(1); f.u32(T); f.u16(5120)
            f.def(2, 297, listOf(Triple(0, 2, S16)), devFieldSizes = listOf(3))
            f.data(2); f.u16(1500); f.raw(0xAA, 0xBB, 0xCC)
            f.compressed(2, 10); f.u16(1525); f.raw(1, 2, 3)
            // Offset 3 is behind the running timestamp's low 5 bits (10): it wraps forward to t + 35.
            f.compressed(2, 3); f.u16(1550); f.raw(4, 5, 6)
            f.def(3, 65_280, listOf(Triple(0, 1, U8)))
            f.data(3); f.u8(7)
            f.def(1, 211, listOf(Triple(253, 4, U32), Triple(1, 1, U8)))
            f.data(1); f.u32(T + 40); f.u8(50)
            return f.finish()
        }

        val ALL: List<Pair<String, () -> ByteArray>> = listOf(
            "monitoring" to ::monitoring, "midnight" to ::midnight, "restingHeartRate" to ::restingHeartRate, "stress" to ::stress,
            "spo2" to ::spo2, "sleep" to ::sleep, "sleepWindow" to ::sleepWindow, "sleepNap" to ::sleepNap, "respiration" to ::respiration, "hrv" to ::hrv,
            "candidates" to ::candidates, "framing" to ::framing,
        )

        // MARK: - Canonical dump (twin of Swift `WellnessFixtures.dump`)

        /**
         * A double as exact decimal when it has at most four places, else its IEEE bit pattern, so the
         * text cannot differ between Swift and Kotlin formatting.
         */
        fun num(v: Double?): String {
            if (v == null) return "-"
            val scaled = v * 10_000
            if (scaled == Math.rint(scaled) && kotlin.math.abs(scaled) < 1e15) {
                val n = scaled.toLong()
                val sign = if (n < 0) "-" else ""
                val a = kotlin.math.abs(n)
                return "$sign${a / 10_000}.${(a % 10_000).toString().padStart(4, '0')}"
            }
            return "bits:" + java.lang.Long.toHexString(v.toRawBits())
        }

        fun num(v: Int?): String = v?.toString() ?: "-"

        fun dump(s: GarminWellnessSamples): String {
            val out = ArrayList<String>()
            out.add("offset ${num(s.utcOffsetS)} complete ${s.complete} other ${s.otherMessages} dropped ${s.droppedMessages}")
            for (m in s.monitoring) {
                out.add(
                    "mon ${m.ts} type=${num(m.activityType)} int=${num(m.intensity)} cyc=${num(m.cycles)} " +
                        "dist=${num(m.distanceM)} cal=${num(m.calories)} act=${num(m.activeCalories)} " +
                        "time=${num(m.activeTimeS)} hr=${num(m.heartRate)} mod=${num(m.moderateMin)} vig=${num(m.vigorousMin)}",
                )
            }
            for (d in s.dailyActivity) {
                out.add("day ${d.day} steps=${num(d.steps)} dist=${num(d.distanceM)} cal=${num(d.calories)} act=${num(d.activeCalories)}")
            }
            for (r in s.restingMetabolicRate) out.add("rmr ${r.ts} ${r.value}")
            for (r in s.restingHeartRate) out.add("rhr ${r.ts} ${r.value}")
            for (r in s.stress) out.add("stress ${r.ts} ${r.value}")
            for (r in s.bodyBattery) out.add("bb ${r.ts} ${r.value}")
            for (r in s.spo2) out.add("spo2 ${r.ts} ${r.percent} conf=${num(r.confidence)} mode=${num(r.mode)}")
            for (r in s.respiration) out.add("resp ${r.ts} ${num(r.value)}")
            for (r in s.sleepLevels) out.add("level ${r.ts} ${r.value}")
            for (r in s.sleepMarks) out.add("mark ${r.ts} ${r.value}")
            for (r in s.sleepSessions) {
                out.add("night ${r.start}-${r.end} unmeasurable=${r.unmeasurableS} awake=${r.awakeS} light=${r.lightS} deep=${r.deepS} rem=${r.remS}")
            }
            for (r in s.sleepRaw) out.add("raw ${r.ts} hr=${num(r.heartRate)} move=${num(r.movement)}")
            for (a in s.sleepAssessments) {
                val subs = listOf(
                    a.combinedAwakeScore, a.awakeTimeScore, a.awakeningsCountScore, a.deepSleepScore,
                    a.sleepDurationScore, a.lightSleepScore, a.sleepQualityScore, a.sleepRecoveryScore,
                    a.remSleepScore, a.restlessnessScore, a.interruptionsScore,
                ).map { num(it) }
                out.add("assess ${a.ts} overall=${a.overallScore} subs=${subs.joinToString(",")} awakenings=${num(a.awakeningsCount)} stress=${num(a.averageStress)}")
            }
            for (h in s.hrvSummaries) {
                val vals = listOf(
                    h.weeklyAverageMs, h.lastNightAverageMs, h.lastNight5MinHighMs, h.baselineLowUpperMs,
                    h.baselineBalancedLowerMs, h.baselineBalancedUpperMs,
                ).map { num(it) }
                out.add("hrvsum ${h.ts} ${vals.joinToString(",")} status=${num(h.status)}")
            }
            for (r in s.hrvValues) out.add("hrv ${r.ts} ${num(r.value)}")
            for (b in s.beatIntervalsCandidate) out.add("beats? ${b.ts} at=${num(b.timestampMs)} ${b.intervalsMs.joinToString(",")}")
            for (r in s.skinTempRawCandidate) out.add("skinraw? ${r.ts} ${num(r.value)}")
            for (r in s.skinTempOvernightCandidate) {
                out.add(
                    "skinnight? ${r.ts} avg=${num(r.averageDeviation)} avg7=${num(r.average7DayDeviation)} " +
                        "days=${num(r.calibratedDays)} nightly=${num(r.nightlyValue)}",
                )
            }
            return out.joinToString("\n")
        }

        fun oracleOutput(): String {
            val out = ArrayList<String>()
            for ((name, build) in ALL) {
                out.add("== $name")
                out.add(dump(GarminWellnessParser.parse(build())))
            }
            out.add("== localDay")
            for ((ts, off) in listOf(0L to 0, -1L to 0, 951_782_400L to 0, 1_709_164_800L to -1, 4_102_444_799L to 50_400)) {
                out.add("$ts@$off ${GarminWellnessParser.localDay(ts, off)}")
            }
            out.add("== half")
            for (bits in listOf(0x0000, 0x8000, 0x0001, 0x03FF, 0x0400, 0x3C00, 0x7BFF, 0xFBFF, 0x7C00, 0x7E00, 0x5640)) {
                out.add("${Integer.toHexString(bits)} ${num(GarminWellnessParser.halfToDouble(bits))}")
            }
            return out.joinToString("\n")
        }

        /** Swift oracle stdout, pasted verbatim. */
        val EXPECTED = """
            == monitoring
            offset 21600 complete true other 0 dropped 1
            mon 1731087296 type=6 int=2 cyc=1000 dist=800.0000 cal=1700 act=120 time=- hr=61 mod=- vig=-
            mon 1731087392 type=1 int=- cyc=400 dist=- cal=1750 act=- time=2.5000 hr=90 mod=5 vig=2
            mon 1731087424 type=6 int=- cyc=1200 dist=- cal=1760 act=- time=- hr=70 mod=- vig=-
            mon 1731090896 type=6 int=- cyc=50 dist=- cal=10 act=- time=- hr=58 mod=0 vig=0
            day 2024-11-08 steps=1600 dist=800.0000 cal=3510 act=120
            day 2024-11-09 steps=50 dist=- cal=10 act=-
            rmr 1731087296 1650
            == midnight
            offset 21600 complete true other 0 dropped 0
            mon 1731088200 type=1 int=- cyc=11000 dist=8500.0000 cal=- act=560 time=- hr=- mod=- vig=-
            mon 1731088500 type=6 int=- cyc=3400 dist=2400.0000 cal=- act=140 time=- hr=- mod=- vig=-
            mon 1731087900 type=6 int=- cyc=3100 dist=2200.0000 cal=- act=130 time=- hr=- mod=- vig=-
            mon 1731088800 type=1 int=- cyc=12500 dist=9600.0000 cal=- act=640 time=- hr=- mod=- vig=-
            mon 1731088800 type=6 int=- cyc=3600 dist=2500.0000 cal=- act=150 time=- hr=- mod=- vig=-
            mon 1731088801 type=6 int=- cyc=5 dist=4.0000 cal=- act=0 time=- hr=- mod=- vig=-
            mon 1731088860 type=6 int=- cyc=31 dist=24.0000 cal=- act=1 time=- hr=- mod=- vig=-
            day 2024-11-08 steps=16100 dist=12100.0000 cal=- act=790
            day 2024-11-09 steps=31 dist=24.0000 cal=- act=1
            == restingHeartRate
            offset - complete true other 0 dropped 1
            rhr 1731087296 52
            rhr 1731173696 57
            == stress
            offset - complete true other 0 dropped 1
            stress 1731087296 25
            bb 1731087296 80
            bb 1731087476 79
            bb 1731087836 55
            hrv 1731087836 32.0000
            == spo2
            offset - complete true other 0 dropped 1
            spo2 1731087296 96 conf=3 mode=3
            spo2 1731087416 94 conf=- mode=1
            == sleep
            offset 21600 complete true other 0 dropped 1
            level 1731087416 1
            level 1731087896 2
            level 1731088496 3
            level 1731089096 4
            level 1731089696 1
            level 1731107296 2
            night 1731087416-1731089696 unmeasurable=0 awake=600 light=480 deep=600 rem=600
            night 1731107296-1731107296 unmeasurable=0 awake=0 light=0 deep=0 rem=0
            raw 1731087296 hr=62.0000 move=0.5000
            raw 1731087356 hr=- move=bits:3e70000000000000
            raw 1731087476 hr=- move=-
            assess 1731107296 overall=82 subs=70,71,72,73,74,75,77,78,79,80,- awakenings=3 stress=22.5000
            == sleepWindow
            offset - complete true other 1 dropped 1
            level 1731088316 2
            level 1731090776 3
            level 1731090836 1
            level 1731093296 2
            level 1731097256 3
            level 1731097736 2
            level 1731098636 4
            level 1731106916 2
            level 1731106976 1
            level 1731108056 2
            level 1731109316 1
            level 1731110816 2
            level 1731112376 4
            level 1731113456 2
            level 1731116919 4
            mark 1731086696 1
            mark 1731087296 0
            mark 1731116919 1
            night 1731087296-1731116919 unmeasurable=0 awake=1380 light=15900 deep=6420 rem=5923
            == sleepNap
            offset - complete true other 0 dropped 0
            level 1731089096 2
            level 1731092696 3
            level 1731094496 0
            level 1731101696 4
            level 1731103496 1
            level 1731114296 2
            level 1731138496 2
            level 1731139696 1
            mark 1731087296 0
            mark 1731114296 1
            mark 1731137296 0
            mark 1731139696 1
            night 1731087296-1731114296 unmeasurable=1800 awake=1800 light=12600 deep=3600 rem=7200
            night 1731137296-1731139696 unmeasurable=0 awake=1200 light=1200 deep=0 rem=0
            == respiration
            offset - complete true other 0 dropped 2
            resp 1731087296 14.5000
            == hrv
            offset - complete true other 0 dropped 1
            hrvsum 1731087296 40.0000,37.5000,54.6875,-,34.0000,50.0000 status=4
            hrv 1731087596 39.0625
            == candidates
            offset -18000 complete true other 0 dropped 1
            beats? 1731087296 at=512 812,790,801
            skinraw? 1731087356 -0.2500
            skinnight? 1731090896 avg=bits:3fb99999a0000000 avg7=bits:bfd3333340000000 days=14 nightly=33.5000
            == framing
            offset - complete true other 2 dropped 0
            rhr 1731087336 50
            resp 1731087296 15.0000
            resp 1731087306 15.2500
            resp 1731087331 15.5000
            hrv 1731087296 40.0000
            == localDay
            0@0 1970-01-01
            -1@0 1969-12-31
            951782400@0 2000-02-29
            1709164800@-1 2024-02-28
            4102444799@50400 2100-01-01
            == half
            0 0.0000
            8000 0.0000
            1 bits:3e70000000000000
            3ff bits:3f0ff80000000000
            400 bits:3f10000000000000
            3c00 1.0000
            7bff 65504.0000
            fbff -65504.0000
            7c00 -
            7e00 -
            5640 100.0000
        """.trimIndent()
    }
}
