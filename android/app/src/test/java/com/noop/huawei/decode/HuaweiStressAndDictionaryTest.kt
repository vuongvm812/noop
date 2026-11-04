package com.noop.huawei.decode

import com.noop.huawei.decode.HuaweiDictionaryDecoder.Kind
import com.noop.huawei.decode.HuaweiDictionaryDecoder.Record
import com.noop.huawei.decode.HuaweiDictionaryDecoder.Sample
import com.noop.huawei.decode.HuaweiDictionaryDecoder.Value
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic stress files and dictionary records, built here; no Gadgetbridge fixtures. */
class HuaweiStressAndDictionaryTest {

    private fun stressEntry(start: Long, end: Long, score: Int, level: Int, f0: Float) =
        HuaweiFixtures.be().i32(start).i32(end).i32(7).u8(score).u8(level).u8(1).u8(2).u8(3).u8(4)
            .f32(f0).apply { repeat(11) { f32(it.toFloat()) } }.build()

    private fun stressFile(version: Int, vararg entries: ByteArray): ByteArray =
        HuaweiFixtures.be().i64(0).i16(0).i16(version).padTo(48).apply { entries.forEach { raw(it) } }.build()

    @Test fun stressRecoversEveryEntrySortedByEnd() {
        val file = stressFile(3,
            stressEntry(1_772_403_600, 1_772_403_660, 61, 3, 1.5f),
            stressEntry(1_772_400_000, 1_772_400_060, 23, 1, 0.25f),
            stressEntry(0, 1_772_400_060, 50, 2, 0f),
            stressEntry(1_772_407_200, 1_772_407_260, 88, 4, -2f),
        )
        val e = HuaweiStressDecoder.rriFile(file)
        assertEquals(listOf(1_772_400_000L, 1_772_403_600L, 1_772_407_200L), e.map { it.startTs })
        assertEquals(listOf(23, 61, 88), e.map { it.score })
        assertEquals(listOf(1, 3, 4), e.map { it.level })
        assertEquals(listOf(0.25f, 1.5f, -2f), e.map { it.features[0] })
        val first = e[0]
        assertEquals(7L, first.algorithm)
        assertEquals(listOf(1, 2, 3, 4), listOf(first.cFlag, first.type, first.accFlag, first.ppgFlag))
        assertEquals(12, first.features.size)
        assertEquals(10f, first.features[11])
    }

    @Test fun stressOtherVersionsAndTruncationFailSafely() {
        val entry = stressEntry(1_772_403_600, 1_772_403_660, 61, 3, 1.5f)
        assertTrue(HuaweiStressDecoder.rriFile(stressFile(2, entry)).isEmpty())
        assertTrue(HuaweiStressDecoder.rriFile(ByteArray(47)).isEmpty())
        // A partial trailing entry is ignored.
        val file = stressFile(3, entry, entry.copyOf(40))
        assertEquals(1, HuaweiStressDecoder.rriFile(file).size)
        val rnd = java.util.Random(5)
        repeat(300) { HuaweiStressDecoder.rriFile(ByteArray(rnd.nextInt(300)).also(rnd::nextBytes)) }
    }

    private fun number(v: Double) = HuaweiFixtures.be().f64(v).build()

    @Test fun dictionaryRecoversHrvAndSkinTemperature() {
        val records = listOf(
            Record(500_044, 1_772_400_000_500, 1_772_400_300_000, 0, listOf(Value(500_044_831, 0x0A, number(41.5)))),
            Record(500_044, 1_772_403_600_000, 0, 1_772_403_700_000, listOf(Value(500_044_831, 0x0A, number(57.0)))),
            Record(400_012, 1_772_400_000_000, 0, 0, listOf(Value(400_012_430, 0x0A, number(33.25)))),
            Record(400_012, 1_772_403_600_000, 0, 0, listOf(Value(400_012_430, 0x0A, number(34.75)))),
        )
        assertEquals(
            listOf(
                Sample(1_772_400_000, Kind.HRV_RMSSD_MS, 41.5), Sample(1_772_403_600, Kind.HRV_RMSSD_MS, 57.0),
                Sample(1_772_400_000, Kind.SKIN_TEMP_C, 33.25), Sample(1_772_403_600, Kind.SKIN_TEMP_C, 34.75),
            ),
            HuaweiDictionaryDecoder.samples(records),
        )
    }

    @Test fun dictionaryDropsWhatItCannotTrust() {
        val records = listOf(
            Record(500_044, 1_772_400_000_000, 0, 0, listOf(
                Value(500_044_831, 0x0A, number(250.0)), // out of range
                Value(500_044_831, 0x0A, number(0.0)), // unmeasured
                Value(500_044_831, 0x0B, number(40.0)), // another tag
                Value(500_044_999, 0x0A, number(40.0)), // another value id
                Value(500_044_831, 0x0A, ByteArray(4)), // not a double
                Value(500_044_831, 0x0A, number(Double.NaN)),
            )),
            Record(400_012, 1_772_400_000_000, 0, 0, listOf(Value(400_012_430, 0x0A, number(19.9)))),
            Record(400_012, 0, 0, 0, listOf(Value(400_012_430, 0x0A, number(33.0)))), // no start
            Record(10_002, 1_772_400_000_000, 0, 0, listOf(Value(10_002_001, 0x0A, number(120.0)))), // not decoded
        )
        assertTrue(HuaweiDictionaryDecoder.samples(records).isEmpty())
    }
}
