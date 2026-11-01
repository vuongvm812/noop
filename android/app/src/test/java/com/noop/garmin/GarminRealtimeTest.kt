package com.noop.garmin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Byte-identical parity of the realtime channel decoders ([GarminRealtime]) and the live intake
 * ([GarminLiveIntake]) with their Swift twins. [ORACLE] is the verbatim stdout of the Swift oracle built
 * standalone:
 *
 *   cd Packages/GarminProtocol && swiftc -O -D GARMIN_ORACLE_STANDALONE <every Sources/GarminProtocol .swift file> \
 *       Tests/GarminProtocolTests/GarminOracleCases.swift Tests/GarminProtocolTests/GarminSessionOracleCases.swift \
 *       Tests/GarminProtocolTests/GarminRealtimeOracleCases.swift Oracle/main.swift -o /tmp/garmin-oracle \
 *       && /tmp/garmin-oracle realtime
 *
 * GarminRealtimeOracleCases.kt re-runs the same cases here; the Swift GarminRealtimeTests reads this literal
 * back, so a change on either platform without the other fails both. Regenerate, never hand-edit.
 */
class GarminRealtimeTest {

    @Test
    fun kotlinMatchesTheSwiftRealtimeOracle() {
        val expected = ORACLE.removePrefix("\n").removeSuffix("\n").split("\n")
        val actual = garminRealtimeOracleLines()
        for (i in 0 until minOf(expected.size, actual.size)) {
            assertEquals("realtime oracle line ${i + 1}", expected[i], actual[i])
        }
        assertEquals("realtime oracle line count", expected.size, actual.size)
    }

    @Test
    fun theAccelerometerAndTheUndecodedChannelsAreNeverRegistered() {
        val s = GarminSession(GarminSessionConfig(oracleSessionHost(), firstPairing = false, sendsHostLifecycleEvents = false),
            GarminTransportKind.MULTI_LINK_V2, 150)
        val clock = GarminClock(0, 1_793_546_986L, 0)
        s.start(clock)
        s.receive(oracleControl(6, oracleLE16(0) + bytes(0)), clock)
        s.receive(oracleControl(1, oracleLE16(1) + bytes(0, 0x65, 0)), clock)
        s.receive(bytes(0x65) + oracleCobs(oracleFr165DeviceInformation()), clock)
        s.receive(bytes(0x65) + oracleCobs(oracleFrame(5050, bytes(1, 0x09))), clock)
        val out = s.setLiveChannels(setOf(GarminService.REALTIME_ACCELEROMETER, GarminService.REALTIME_STRESS,
            GarminService.REALTIME_CALORIES, GarminService.REALTIME_INTENSITY), clock)
        assertTrue(out.none { it is GarminSessionOutput.Write })
        assertEquals(setOf<GarminService>(), s.openLiveChannels)
    }

    @Test
    fun unknownSentinelsDecodeToNothing() {
        assertNull((GarminRealtime.decode(GarminService.REALTIME_HEART_RATE, bytes(3, 0, 0)) as GarminRealtimeReading.HeartRate).bpm)
        assertNull((GarminRealtime.decode(GarminService.REALTIME_SPO2, bytes(0xFF, 1, 2, 3, 4)) as GarminRealtimeReading.Spo2).percent)
        assertNull((GarminRealtime.decode(GarminService.REALTIME_BODY_BATTERY, bytes(0x7F)) as GarminRealtimeReading.BodyBattery).value)
        assertNull((GarminRealtime.decode(GarminService.REALTIME_RESPIRATION, bytes(0xFE)) as GarminRealtimeReading.Respiration).breathsPerMinute)
        assertFalse(GarminService.REALTIME_ACCELEROMETER in GarminRealtime.DECODABLE_SERVICES)
    }

    private companion object {
        val ORACLE = """
decode 6 033e37ffff -> hr bpm=62 resting=55 type=3 trailing=ffff
decode 6 000000ffff -> hr bpm=- resting=- type=0 trailing=ffff
decode 6 02ffff -> hr bpm=255 resting=255 type=2 trailing=
decode 6 033d -> hr bpm=61 resting=- type=3 trailing=
decode 6 03 -> nil
decode 6  -> nil
decode 12 e80304030201 -> rr ms=1000 unknown=16909060
decode 12 000000000000 -> rr ms=- unknown=0
decode 12 ffffffffffff -> rr ms=65535 unknown=4294967295
decode 12 5203 -> rr ms=850 unknown=-
decode 12 52030909 -> rr ms=850 unknown=-
decode 12 52 -> nil
decode 7 e11000004c1d0000 -> steps total=4321 goal=7500
decode 7 ffffffff00000000aa -> steps total=4294967295 goal=0
decode 7 01000000000000 -> nil
decode 9 030001000a00 -> floors up=3 down=1 target=10
decode 9 ffff00000000 -> floors up=65535 down=0 target=0
decode 9 03000100 -> nil
decode 19 61ea0e4a45 -> spo2 pct=97 time=1162481386
decode 19 ffea0e4a45 -> spo2 pct=- time=-
decode 19 0001020304 -> spo2 pct=- time=-
decode 19 6501020304 -> spo2 pct=- time=-
decode 19 8001020304 -> spo2 pct=- time=-
decode 19 640102 -> spo2 pct=100 time=-
decode 19  -> nil
decode 20 00 -> body-battery 0
decode 20 3f -> body-battery 63
decode 20 64 -> body-battery 100
decode 20 65 -> body-battery -
decode 20 7f -> body-battery -
decode 20 ff07 -> body-battery -
decode 20  -> nil
decode 21 0e -> respiration 14
decode 21 fe -> respiration -
decode 21 ff -> respiration -
decode 21 00 -> respiration -
decode 21 7f -> respiration 127
decode 21 80 -> respiration -
decode 21  -> nil
decode 13 010203 -> nil
decode 16 11111111111111111111111111111111 -> nil
decode 1 01 -> nil
decodable 6,7,9,12,19,20,21
intake @1793546940 hr bpm=62 resting=55 type=3 trailing=ffff -> hr=62 rr= writes=[] changed=true clear=false | resting=55 bb=- resp=- spo2=- steps=-/- floors=-/-/-
intake @1793546940 hr bpm=- resting=- type=0 trailing= -> hr=- rr= writes=[] changed=true clear=false | resting=- bb=- resp=- spo2=- steps=-/- floors=-/-/-
intake @1793546941 hr bpm=29 resting=25 type=3 trailing= -> hr=- rr= writes=[] changed=false clear=false | resting=- bb=- resp=- spo2=- steps=-/- floors=-/-/-
intake @1793546941 hr bpm=221 resting=221 type=3 trailing= -> hr=- rr= writes=[] changed=false clear=false | resting=- bb=- resp=- spo2=- steps=-/- floors=-/-/-
intake @1793546941 hr bpm=30 resting=220 type=3 trailing= -> hr=30 rr= writes=[] changed=true clear=false | resting=220 bb=- resp=- spo2=- steps=-/- floors=-/-/-
intake @1793546942 rr ms=1000 unknown=7 -> hr=- rr=1000 writes=[] changed=false clear=false | resting=220 bb=- resp=- spo2=- steps=-/- floors=-/-/-
intake @1793546942 rr ms=- unknown=7 -> hr=- rr= writes=[] changed=false clear=false | resting=220 bb=- resp=- spo2=- steps=-/- floors=-/-/-
intake @1793546942 rr ms=65535 unknown=- -> hr=- rr=65535 writes=[] changed=false clear=false | resting=220 bb=- resp=- spo2=- steps=-/- floors=-/-/-
intake @1793546943 body-battery 63 -> hr=- rr= writes=[body_battery@1793546943=63.0] changed=true clear=false | resting=220 bb=63 resp=- spo2=- steps=-/- floors=-/-/-
intake @1793546944 body-battery 64 -> hr=- rr= writes=[] changed=true clear=false | resting=220 bb=64 resp=- spo2=- steps=-/- floors=-/-/-
intake @1793546999 body-battery 64 -> hr=- rr= writes=[] changed=false clear=false | resting=220 bb=64 resp=- spo2=- steps=-/- floors=-/-/-
intake @1793547000 body-battery 64 -> hr=- rr= writes=[body_battery@1793547000=64.0] changed=false clear=false | resting=220 bb=64 resp=- spo2=- steps=-/- floors=-/-/-
intake @1793547001 body-battery - -> hr=- rr= writes=[] changed=true clear=false | resting=220 bb=- resp=- spo2=- steps=-/- floors=-/-/-
intake @1793547002 body-battery 65 -> hr=- rr= writes=[] changed=true clear=false | resting=220 bb=65 resp=- spo2=- steps=-/- floors=-/-/-
intake @1793546945 respiration 14 -> hr=- rr= writes=[respiration_bpm@1793546945=14.0] changed=true clear=false | resting=220 bb=65 resp=14 spo2=- steps=-/- floors=-/-/-
intake @1793546946 respiration - -> hr=- rr= writes=[] changed=true clear=false | resting=220 bb=65 resp=- spo2=- steps=-/- floors=-/-/-
intake @1793547059 respiration 15 -> hr=- rr= writes=[respiration_bpm@1793547059=15.0] changed=true clear=false | resting=220 bb=65 resp=15 spo2=- steps=-/- floors=-/-/-
intake @1793547060 respiration 15 -> hr=- rr= writes=[respiration_bpm@1793547060=15.0] changed=false clear=false | resting=220 bb=65 resp=15 spo2=- steps=-/- floors=-/-/-
intake @1793547120 respiration 127 -> hr=- rr= writes=[] changed=true clear=false | resting=220 bb=65 resp=- spo2=- steps=-/- floors=-/-/-
intake @1793547121 respiration 3 -> hr=- rr= writes=[] changed=false clear=false | resting=220 bb=65 resp=- spo2=- steps=-/- floors=-/-/-
intake @1793547122 respiration 4 -> hr=- rr= writes=[respiration_bpm@1793547122=4.0] changed=true clear=false | resting=220 bb=65 resp=4 spo2=- steps=-/- floors=-/-/-
intake @1793547123 respiration 60 -> hr=- rr= writes=[] changed=true clear=false | resting=220 bb=65 resp=60 spo2=- steps=-/- floors=-/-/-
intake @1793547124 respiration 61 -> hr=- rr= writes=[] changed=true clear=false | resting=220 bb=65 resp=- spo2=- steps=-/- floors=-/-/-
intake @1793546947 spo2 pct=- time=- -> hr=- rr= writes=[] changed=false clear=false | resting=220 bb=65 resp=- spo2=- steps=-/- floors=-/-/-
intake @1793546948 spo2 pct=97 time=1162481386 -> hr=- rr= writes=[spo2_pct@1793546948=97.0] changed=true clear=false | resting=220 bb=65 resp=- spo2=97 steps=-/- floors=-/-/-
intake @1793546949 spo2 pct=96 time=1162481387 -> hr=- rr= writes=[] changed=true clear=false | resting=220 bb=65 resp=- spo2=96 steps=-/- floors=-/-/-
intake @1793546950 steps total=4321 goal=7500 -> hr=- rr= writes=[] changed=true clear=false | resting=220 bb=65 resp=- spo2=96 steps=4321/7500 floors=-/-/-
intake @1793546951 steps total=4321 goal=7500 -> hr=- rr= writes=[] changed=false clear=false | resting=220 bb=65 resp=- spo2=96 steps=4321/7500 floors=-/-/-
intake @1793546952 floors up=3 down=1 target=10 -> hr=- rr= writes=[] changed=true clear=false | resting=220 bb=65 resp=- spo2=96 steps=4321/7500 floors=3/1/10
intake @1793547070 close 20 -> hr=- rr= writes=[] changed=true clear=false | resting=220 bb=- resp=- spo2=96 steps=4321/7500 floors=3/1/10
intake @1793547071 body-battery 66 -> hr=- rr= writes=[body_battery@1793547071=66.0] changed=true clear=false | resting=220 bb=66 resp=- spo2=96 steps=4321/7500 floors=3/1/10
intake @1793547071 close 7 -> hr=- rr= writes=[] changed=true clear=false | resting=220 bb=66 resp=- spo2=96 steps=-/- floors=3/1/10
intake @1793547071 close 9 -> hr=- rr= writes=[] changed=true clear=false | resting=220 bb=66 resp=- spo2=96 steps=-/- floors=-/-/-
intake @1793547071 close 21 -> hr=- rr= writes=[] changed=false clear=false | resting=220 bb=66 resp=- spo2=96 steps=-/- floors=-/-/-
intake @1793547071 close 19 -> hr=- rr= writes=[] changed=true clear=false | resting=220 bb=66 resp=- spo2=- steps=-/- floors=-/-/-
intake @1793547071 close 6 -> hr=- rr= writes=[] changed=true clear=true | resting=- bb=66 resp=- spo2=- steps=-/- floors=-/-/-
intake @1793547071 close 12 -> hr=- rr= writes=[] changed=false clear=false | resting=- bb=66 resp=- spo2=- steps=-/- floors=-/-/-
intake @1793547072 hr bpm=70 resting=54 type=3 trailing= -> hr=70 rr= writes=[] changed=true clear=false | resting=54 bb=66 resp=- spo2=- steps=-/- floors=-/-/-
intake @-61 respiration 12 -> hr=- rr= writes=[respiration_bpm@-61=12.0] changed=true clear=false | resting=54 bb=66 resp=12 spo2=- steps=-/- floors=-/-/-
intake @-60 respiration 12 -> hr=- rr= writes=[respiration_bpm@-60=12.0] changed=false clear=false | resting=54 bb=66 resp=12 spo2=- steps=-/- floors=-/-/-
intake @-1 respiration 12 -> hr=- rr= writes=[] changed=false clear=false | resting=54 bb=66 resp=12 spo2=- steps=-/- floors=-/-/-
intake link-down -> hr=- rr= writes=[] changed=true clear=true | resting=- bb=- resp=- spo2=- steps=-/- floors=-/-/-
intake link-down -> hr=- rr= writes=[] changed=false clear=true | resting=- bb=- resp=- spo2=- steps=-/- floors=-/-/-
kinds body_battery,respiration_bpm,spo2_pct source=garmin_live interval=60
"""
    }
}
