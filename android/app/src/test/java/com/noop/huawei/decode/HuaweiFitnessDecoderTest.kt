package com.noop.huawei.decode

import com.noop.huawei.decode.HuaweiFitnessDecoder.MinuteSample
import com.noop.huawei.decode.HuaweiFixtures.hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The step-data vectors (base time, offsets and feature bytes, with their expected values) are taken from
 * Gadgetbridge's `devices/huawei/packets/TestFitnessData.java` (AGPL-3.0, commit 6e42206), used as
 * expected literals only; see ATTRIBUTION.md.
 */
class HuaweiFitnessDecoderTest {

    private val base = 0xCAFEBEEFL

    @Test fun gadgetbridgeStepResponseVectors() {
        // testMessageDataStepResponse: empty data, data too short, steps/calories/distance, an unknown field.
        assertNull(HuaweiFitnessDecoder.minuteSample(base, 0x00, ByteArray(0)))
        assertNull(HuaweiFitnessDecoder.minuteSample(base, 0x01, hex("01 02")))
        assertEquals(
            MinuteSample(ts = 0xCAFEBF67L, steps = 1, calories = 2, distanceM = 3),
            HuaweiFitnessDecoder.minuteSample(base, 0x02, hex("0e 00 01 00 02 00 03")),
        )
        assertEquals(MinuteSample(ts = 0xCAFEBF67L), HuaweiFitnessDecoder.minuteSample(base, 0x02, hex("01 00 01")))
    }

    @Test fun gadgetbridgeSingleByteVectors() {
        // testMessageDataStepResponseSingleByte: base 2, the one-byte 0x20 (unknown) and 0x40 (heart rate).
        assertEquals(MinuteSample(ts = 2), HuaweiFitnessDecoder.minuteSample(2, 0x00, hex("20 01")))
        assertEquals(MinuteSample(ts = 0x3E, heartRate = 2), HuaweiFitnessDecoder.minuteSample(2, 0x01, hex("40 02")))
    }

    @Test fun gadgetbridgeSpo2Vector() {
        // testSpoData: bitmap 2 announced by 0x80, SpO2 0x42.
        assertEquals(MinuteSample(ts = base, spo2Pct = 0x42), HuaweiFitnessDecoder.minuteSample(base, 0x00, hex("80 01 42")))
    }

    @Test fun everyFieldInOneRecordWithUnknownsSkipped() {
        // 0xFF: every bitmap-1 bit (unknown 0x01/0x10/0x20 consumed), then bitmap 2 with SpO2, resting HR and
        // an unknown 0x04 byte.
        val data = hex("ff 07  aa aa  01 2c  00 05  00 50  bb bb  cc  48  61 3a ee")
        assertEquals(
            MinuteSample(ts = 1_700_000_000L + 120, steps = 300, calories = 5, distanceM = 80, heartRate = 72, spo2Pct = 97, restingHr = 58),
            HuaweiFitnessDecoder.minuteSample(1_700_000_000L, 2, data),
        )
        // The same with the trailing unknown byte missing fails rather than reading garbage.
        assertNull(HuaweiFitnessDecoder.minuteSample(1_700_000_000L, 2, data.copyOf(data.size - 1)))
        // Bitmap 2 announced but absent.
        assertNull(HuaweiFitnessDecoder.minuteSample(1_700_000_000L, 2, hex("80")))
    }

    @Test fun offsetAndBaseAreUnsigned() {
        // A minute offset of 0xFF is 255 minutes, not -1; a base time passed sign-extended is still the u32.
        assertEquals(base + 255 * 60, HuaweiFitnessDecoder.minuteSample(base, 0xFF, hex("02 00 07"))!!.ts)
        assertEquals(base + 255 * 60, HuaweiFitnessDecoder.minuteSample(0xCAFEBEEF.toInt().toLong(), -1, hex("02 00 07"))!!.ts)
        // Two-byte values are unsigned too: 0xFFFF steps is 65535.
        assertEquals(65_535, HuaweiFitnessDecoder.minuteSample(0, 0, hex("02 ff ff"))!!.steps)
    }

    @Test fun legacySleepSpans() {
        val light = HuaweiFitnessDecoder.sleepSpan(0x06, hex("6000 0000 0400"))!!
        // 1024 minutes is 61440 s: no 16-bit wrap.
        assertEquals(HuaweiFitnessDecoder.SleepSpan(0x6000_0000L, 0x6000_0000L + 61_440, HuaweiFitnessDecoder.SleepStage.LIGHT), light)
        val deep = HuaweiFitnessDecoder.sleepSpan(0x07, hex("6553 f100 001e"))!!
        assertEquals(HuaweiFitnessDecoder.SleepSpan(0x6553_F100L, 0x6553_F100L + 1800, HuaweiFitnessDecoder.SleepStage.DEEP), deep)
        assertNull(HuaweiFitnessDecoder.sleepSpan(0x05, hex("6553 f100 001e")))
        assertNull(HuaweiFitnessDecoder.sleepSpan(0x06, hex("6553 f100 00")))
        assertNull(HuaweiFitnessDecoder.sleepSpan(0x06, hex("6553 f100 0000")))
    }
}
