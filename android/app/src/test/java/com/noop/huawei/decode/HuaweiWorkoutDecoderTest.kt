package com.noop.huawei.decode

import com.noop.huawei.decode.HuaweiFixtures.hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The totals body and the data header/samples are taken from Gadgetbridge's
 * `devices/huawei/packets/TestWorkout.java` (AGPL-3.0, commit 6e42206), used as expected literals only;
 * see ATTRIBUTION.md. The HR/altitude stream and the GPS file are synthetic.
 */
class HuaweiWorkoutDecoderTest {

    private fun fields(body: ByteArray) = HuaweiSleepFileDecoder.tlv(body)!!.toMap()

    @Test fun gadgetbridgeTotalsVector() {
        val body = hex("02021337 030101 040401020304 050405060708 0604090a0b0c 07040d0e0f10 080411121314 090415161718 1204191a1b1c 14011d")
        val t = HuaweiWorkoutDecoder.totals(fields(body))!!
        assertEquals(0x1337, t.number)
        assertEquals(0x01, t.status)
        assertEquals(0x01020304L, t.startTs)
        assertEquals(0x05060708L, t.endTs)
        assertEquals(0x090a0b0cL, t.kcal)
        assertEquals(0x0d0e0f10L, t.distanceM)
        assertEquals(0x11121314L, t.steps)
        assertEquals(0x15161718L, t.totalTimeS)
        assertEquals(0x191a1b1cL, t.durationS)
        assertEquals(0x1d, t.type)
        assertNull(t.aerobicEffect)
    }

    @Test fun totalsScalesAndRequiredFields() {
        val body = hex("0404 69a5 2b00 0504 69a5 3200 0b02 007d 0c02 5a96 0d01 55 0e01 20 2001 0b")
        val t = HuaweiWorkoutDecoder.totals(fields(body))!!
        assertEquals(12.5, t.elevationGainM!!, 0.0)
        assertEquals(listOf(90, 150), listOf(t.minHr, t.maxHr))
        assertEquals(85L, t.load)
        assertEquals(3.2, t.aerobicEffect!!, 1e-12)
        assertEquals(1.1, t.anaerobicEffect!!, 1e-12)
        assertNull(HuaweiWorkoutDecoder.totals(fields(hex("040401020304"))))
    }

    @Test fun gadgetbridgeDataVector() {
        // Speed plus the inner block, inner bitmap 0x01FF / length 13 (the AW defaults the test runs with).
        val header = hex("01020304050607080900020f0042")
        val data = hex("0a0b0c0d0e0f101112131415161718191a1b1c1d1e1f2021222324252627")
        val s = HuaweiWorkoutDecoder.samples(header, data, innerBitmap = 0x01FF, innerLength = 13)
        assertEquals(listOf(0x05060708L, 0x05060708L + 9), s.map { it.ts })
        assertEquals(listOf(0x0a0b / 10.0, 0x191a / 10.0), s.map { it.speedMps })
        assertEquals(mapOf(0 to 0x0c0d, 1 to 0x0e0f, 2 to 0x1011, 3 to 0x12, 4 to 0x1314, 5 to 0x15, 6 to 0x16, 7 to 0x17, 8 to 0x18), s[0].inner)
        assertEquals(mapOf(0 to 0x1b1c, 1 to 0x1d1e, 2 to 0x1f20, 3 to 0x21, 4 to 0x2223, 5 to 0x24, 6 to 0x25, 7 to 0x26, 8 to 0x27), s[1].inner)
        assertNull(s[0].heartRate)
        // Without the inner sizing the lengths disagree and nothing is guessed.
        assertTrue(HuaweiWorkoutDecoder.samples(header, data).isEmpty())
    }

    @Test fun heartRateAltitudeEnergyPowerStream() {
        // bitmap 0x02A1: HR (1), altitude (4), kcal (2), power (2) = 9 bytes per sample, 3 samples, 5 s apart.
        val header = HuaweiFixtures.be().i16(1).i16(0).i32(1_772_443_200).u8(5).i16(3).u8(9).i16(0x02A1).build()
        val data = HuaweiFixtures.be()
            .u8(120).i32(1234).i16(3).i16(180)
            .u8(131).i32(-25).i16(4).i16(210)
            .u8(142).i32(1250).i16(5).i16(0)
            .build()
        val s = HuaweiWorkoutDecoder.samples(header, data)
        assertEquals(listOf(1_772_443_200L, 1_772_443_205L, 1_772_443_210L), s.map { it.ts })
        assertEquals(listOf(120, 131, 142), s.map { it.heartRate })
        assertEquals(listOf(123.4, -2.5, 125.0), s.map { it.altitudeM })
        assertEquals(listOf(3, 4, 5), s.map { it.kcal })
        assertEquals(listOf(180, 210, 0), s.map { it.powerW })
        // Truncated data, a short header, and an unknown bitmap bit all fail safely.
        assertTrue(HuaweiWorkoutDecoder.samples(header, data.copyOf(26)).isEmpty())
        assertTrue(HuaweiWorkoutDecoder.samples(header.copyOf(13), data).isEmpty())
        val unknownBit = header.copyOf().also { it[13] = 0xA1.toByte(); it[12] = 0x06 }
        assertTrue(HuaweiWorkoutDecoder.samples(unknownBit, data).isEmpty())
    }

    @Test fun paceBlocks() {
        val b = HuaweiWorkoutDecoder.paceBlock(fields(hex("0402 03e8 0501 00 0604 0000 0136 0702 0011 0902 fffe")))!!
        assertEquals(HuaweiWorkoutDecoder.PaceBlock(1000, 0, 310, 17, -2), b)
        assertNull(HuaweiWorkoutDecoder.paceBlock(fields(hex("0402 03e8 0501 00"))))
    }

    /** One degree of latitude on the band's sphere, in metres. */
    private val degM = 6_378_245.0 * Math.PI / 180

    @Test fun gpsTrackRecoversEveryPoint() {
        val lat0 = 52.0
        val lon0 = 13.0
        val cos = Math.cos(Math.toRadians(lat0))
        fun point(dt: Int, dLonDeg: Double, dLatDeg: Double, pause: Int) = HuaweiFixtures.le()
            .i16(dt).i16(0).f32((dLonDeg * degM * cos).toFloat()).f32((dLatDeg * degM).toFloat()).u8(0).u8(0).u8(pause).build()
        val file = HuaweiFixtures.le().zeros(0x20).u8(0x00).i32(1_772_443_200).f64(lon0).f64(lat0).padTo(62)
            .raw(point(5, 0.001, 0.002, 0)).raw(point(5, 0.001, -0.001, 0)).raw(point(10, 0.0, 0.0, 1))
            .zeros(7).build()
        val pts = HuaweiWorkoutDecoder.gpsFile(file)
        assertEquals(listOf(1_772_443_205L, 1_772_443_210L, 1_772_443_220L), pts.map { it.ts })
        val expected = listOf(52.002 to 13.001, 52.001 to 13.002, 52.001 to 13.002)
        for ((p, e) in pts.zip(expected)) {
            assertEquals(e.first, p.lat, 1e-6)
            assertEquals(e.second, p.lon, 1e-6)
            assertNull(p.altitudeM)
        }
        assertEquals(listOf(false, false, true), pts.map { it.paused })
    }

    @Test fun gpsWithAltitudeAndGarbage() {
        val file = HuaweiFixtures.le().zeros(0x20).u8(0x03).i32(1_772_443_200).f64(13.0).f64(52.0).f64(40.0).padTo(70)
            .i16(1).i16(0).f32(0f).f32(0f).u8(0).u8(0).u8(0).i16(42).i16(0)
            .build()
        val p = HuaweiWorkoutDecoder.gpsFile(file).single()
        assertEquals(42.0, p.altitudeM!!, 0.0)
        assertTrue(HuaweiWorkoutDecoder.gpsFile(file.copyOf(50)).isEmpty())
        // A first fix off the globe yields nothing.
        val bad = HuaweiFixtures.le().zeros(0x20).u8(0).i32(1).f64(500.0).f64(52.0).padTo(62).build()
        assertTrue(HuaweiWorkoutDecoder.gpsFile(bad).isEmpty())
        val rnd = java.util.Random(9)
        repeat(300) { HuaweiWorkoutDecoder.gpsFile(ByteArray(rnd.nextInt(200)).also(rnd::nextBytes)) }
        repeat(300) {
            HuaweiWorkoutDecoder.samples(ByteArray(14).also(rnd::nextBytes), ByteArray(rnd.nextInt(100)).also(rnd::nextBytes),
                rnd.nextInt(), rnd.nextInt(64))
        }
    }
}
