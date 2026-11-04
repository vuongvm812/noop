package com.noop.huawei.decode

import com.noop.huawei.decode.HuaweiFixtures.detail
import com.noop.huawei.decode.HuaweiFixtures.hex
import com.noop.huawei.decode.HuaweiFixtures.sequenceFile
import com.noop.huawei.decode.HuaweiFixtures.sequenceRecord
import com.noop.huawei.decode.HuaweiFixtures.summaryDouble
import com.noop.huawei.decode.HuaweiFixtures.summaryEntry
import com.noop.huawei.decode.HuaweiFixtures.summaryInt
import com.noop.huawei.decode.HuaweiFixtures.summaryLong
import com.noop.huawei.decode.HuaweiFixtures.tlv
import com.noop.huawei.decode.HuaweiSleepFileDecoder.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `sleep_state.bin` vector is taken from Gadgetbridge's `TestHuaweiTruSleepParser.testParseState`
 * (AGPL-3.0, commit 6e42206), used as an expected literal only; see ATTRIBUTION.md. The sequence files
 * are synthetic, built by [HuaweiFixtures].
 */
class HuaweiSleepFileDecoderTest {

    @Test fun gadgetbridgeStateVector() {
        val frames = HuaweiSleepFileDecoder.stateFile(hex("0100000002000000000000000000000003000000050000000000000000000000"))
        assertEquals(listOf(HuaweiSleepFileDecoder.StateFrame(1, 2), HuaweiSleepFileDecoder.StateFrame(3, 5)), frames)
    }

    @Test fun stateFileIgnoresPartialAndBackwardFrames() {
        val b = HuaweiFixtures.le().i32(10).i32(5).zeros(8).i32(20).i32(30).zeros(8).zeros(9).build()
        assertEquals(listOf(HuaweiSleepFileDecoder.StateFrame(20, 30)), HuaweiSleepFileDecoder.stateFile(b))
        assertEquals(emptyList<HuaweiSleepFileDecoder.StateFrame>(), HuaweiSleepFileDecoder.stateFile(ByteArray(7)))
    }

    private fun summary(fallAsleep: Long, wake: Long, score: Long, hrv: Long, hr: Long, extra: ByteArray = ByteArray(0)) = tlv(
        0x81,
        summaryLong(HuaweiSleepFileDecoder.ID_FALL_ASLEEP, fallAsleep) +
            summaryLong(HuaweiSleepFileDecoder.ID_BED_TIME, fallAsleep - 900) +
            summaryLong(HuaweiSleepFileDecoder.ID_RISING_TIME, wake + 300) +
            summaryLong(HuaweiSleepFileDecoder.ID_WAKEUP_TIME, wake) +
            summaryInt(HuaweiSleepFileDecoder.ID_SLEEP_SCORE, score) +
            summaryInt(HuaweiSleepFileDecoder.ID_VALID_DATA, 1) +
            summaryDouble(HuaweiSleepFileDecoder.ID_AVG_HRV, hrv.toDouble()) +
            summaryInt(HuaweiSleepFileDecoder.ID_AVG_HR, hr) + extra,
    )

    @Test fun twoNightsRecoverEveryInjectedValue() {
        val n1 = 1_772_400_030L // 30 s past the minute: the timeline starts on the minute below.
        val n2 = 1_772_490_000L
        val file = sequenceFile(
            1,
            sequenceRecord(n1 - 900, n1 + 9000, summary(n1, n1 + 8970, 81, 42, 58),
                detail(1200 to 1, 1800 to 3, 600 to 2, 300 to 4, 1200 to 1, 600 to 1)),
            sequenceRecord(n2, n2 + 7200, summary(n2, n2 + 7200, 67, 61, 63, summaryEntry(700_013_999, 5, "x".toByteArray())),
                detail(3600 to 3, 2400 to 2, 1200 to 5)),
        )
        val nights = HuaweiSleepFileDecoder.sequenceFile(file)
        assertEquals(2, nights.size)
        val a = nights[0]
        assertEquals(listOf(n1, n1 - 900, n1 + 9270, n1 + 8970), listOf(a.fallAsleepTs, a.bedTs, a.risingTs, a.wakeupTs))
        assertEquals(listOf(81, 1, 42, 58), listOf(a.sleepScore, a.validData, a.avgHrvMs, a.avgHr))
        val t = n1 - 30
        assertEquals(
            listOf(Stage(t, t + 1200, "light"), Stage(t + 1200, t + 3000, "deep"), Stage(t + 3000, t + 3600, "rem"),
                Stage(t + 3600, t + 3900, "wake"), Stage(t + 3900, t + 5700, "light")),
            a.stages,
        )
        val b = nights[1]
        assertEquals(listOf(67, 61, 63), listOf(b.sleepScore, b.avgHrvMs, b.avgHr))
        // Nap (5) folds into light.
        assertEquals(listOf(Stage(n2, n2 + 3600, "deep"), Stage(n2 + 3600, n2 + 6000, "rem"), Stage(n2 + 6000, n2 + 7200, "light")), b.stages)
    }

    @Test fun badDetailRecordsAreSkippedWithoutMovingTheClock() {
        val stages = HuaweiSleepFileDecoder.stages(detail(600 to 1, 0 to 1, 90 to 3, 600 to 0, 120_000 to 3, 600 to 9, 600 to 3), 1000)!!
        // Zero, non-whole-minute, stage-0 and over-a-day records skip; the unknown stage 9 advances the clock.
        assertEquals(listOf(Stage(1000, 1600, "light"), Stage(2200, 2800, "deep")), stages)
        assertNull(HuaweiSleepFileDecoder.stages(ByteArray(12), 0))
    }

    @Test fun fileTypeTwoAndOtherSummaryFormatsCarryNoNight() {
        val rec = sequenceRecord(1, 2, summary(1_772_400_000L, 1_772_403_600L, 70, 40, 60), detail(600 to 1))
        assertEquals(0, HuaweiSleepFileDecoder.sequenceFile(sequenceFile(2, rec)).size)
        // A data version other than 1 carries no summary bytes to read.
        val v2 = sequenceRecord(1, 2, ByteArray(0), detail(600 to 1), version = 2)
        val t1 = sequenceRecord(1, 2, summary(1_772_400_000L, 1_772_403_600L, 70, 40, 60), detail(600 to 1), summaryType = 1)
        assertEquals(1, HuaweiSleepFileDecoder.sequenceFile(sequenceFile(1, v2, t1, rec)).size)
    }

    @Test fun noFallAsleepMeansNoTimeline() {
        val s = tlv(0x81, summaryInt(HuaweiSleepFileDecoder.ID_SLEEP_SCORE, 70))
        val night = HuaweiSleepFileDecoder.sequenceFile(sequenceFile(1, sequenceRecord(1, 2, s, detail(600 to 1)))).single()
        assertNull(night.fallAsleepTs)
        assertEquals(70, night.sleepScore)
        assertTrue(night.stages.isEmpty())
    }

    @Test fun absentIntegerSentinelAndOutOfRangeValuesAreDropped() {
        val s = tlv(0x81, summaryEntry(HuaweiSleepFileDecoder.ID_SLEEP_SCORE, 1, hex("bf800000")) +
            summaryInt(HuaweiSleepFileDecoder.ID_AVG_HRV, 250))
        val values = HuaweiSleepFileDecoder.summaryValues(s)!!
        assertNull(values[HuaweiSleepFileDecoder.ID_SLEEP_SCORE])
        val night = HuaweiSleepFileDecoder.sequenceFile(sequenceFile(1, sequenceRecord(1, 2, s, ByteArray(0)))).single()
        assertNull(night.avgHrvMs)
    }

    @Test fun truncatedAndGarbageFilesFailSafely() {
        val good = sequenceRecord(1, 2, summary(1_772_400_000L, 1_772_403_600L, 70, 40, 60), detail(600 to 1))
        val file = sequenceFile(1, good, good)
        // Cut inside the second record: the first night survives.
        for (cut in listOf(file.size - 1, file.size - 20, 32 + good.size + 31)) {
            assertEquals("cut $cut", 1, HuaweiSleepFileDecoder.sequenceFile(file.copyOf(cut)).size)
        }
        assertEquals(0, HuaweiSleepFileDecoder.sequenceFile(file.copyOf(31)).size)
        // A summary whose TLV lengths overrun drops that night only.
        assertNull(HuaweiSleepFileDecoder.summaryValues(hex("81 7f 82 03")))
        val rnd = java.util.Random(77)
        repeat(500) {
            val junk = ByteArray(rnd.nextInt(400)).also(rnd::nextBytes)
            HuaweiSleepFileDecoder.sequenceFile(junk)
            HuaweiSleepFileDecoder.stateFile(junk)
            HuaweiSleepFileDecoder.summaryValues(junk)
            HuaweiSleepFileDecoder.stages(junk, 0)
        }
    }

    @Test fun multiByteTlvLengths() {
        // 300 bytes needs a two-group length (0x82 0x2c).
        val value = ByteArray(300) { it.toByte() }
        val parsed = HuaweiSleepFileDecoder.tlv(tlv(0x05, value))!!.single()
        assertEquals(0x05, parsed.first)
        assertTrue(parsed.second.contentEquals(value))
    }
}
