package com.noop.garmin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Byte-identical parity of the capture layer (Garmin 6/10) with the Swift package: the capture gate in the
 * live-channel table, the capture log line and the CANDIDATE decoders for channels 8 / 10 / 13.
 * [ORACLE] is the verbatim stdout of the Swift capture oracle built standalone:
 *
 *   cd Packages/GarminProtocol && swiftc -O -D GARMIN_ORACLE_STANDALONE <every Sources/GarminProtocol .swift file> \
 *       Tests/GarminProtocolTests/GarminOracleCases.swift Tests/GarminProtocolTests/GarminSessionOracleCases.swift \
 *       Tests/GarminProtocolTests/GarminSyncOracleCases.swift Tests/GarminProtocolTests/GarminRealtimeOracleCases.swift \
 *       Tests/GarminProtocolTests/GarminCaptureOracleCases.swift Oracle/main.swift -o /tmp/garmin-oracle \
 *       && /tmp/garmin-oracle capture
 *
 * GarminCaptureOracleCases.kt re-runs the same cases here; the Swift GarminCaptureTests reads this literal
 * back. Regenerate, never hand-edit.
 */
class GarminCaptureTest {

    @Test
    fun kotlinMatchesTheSwiftCaptureOracle() {
        val expected = ORACLE.removePrefix("\n").removeSuffix("\n").split("\n")
        val actual = garminCaptureOracleLines()
        for (i in 0 until minOf(expected.size, actual.size)) {
            assertEquals("capture oracle line ${i + 1}", expected[i], actual[i])
        }
        assertEquals("capture oracle line count", expected.size, actual.size)
    }

    @Test
    fun candidatesAcceptOnlyTheObservedLengths() {
        assertNull(GarminCaloriesCandidate.decode(ByteArray(7)))
        assertNull(GarminCaloriesCandidate.decode(ByteArray(9)))
        assertNull(GarminIntensityCandidate.decode(ByteArray(12)))
        assertNull(GarminStressCandidate.decode(ByteArray(2)))
        assertEquals(-4, GarminStressCandidate.decode(bytes(0xFC, 0xFF, 0, 0))!!.level)
        assertNull(GarminStressCandidate.decode(bytes(0xFC, 0xFF, 0, 0))!!.score)
        assertEquals(100, GarminStressCandidate.decode(bytes(100, 0, 0, 0))!!.score)
    }

    @Test
    fun intensityStructuralCheck() {
        val ok = GarminIntensityCandidate.decode(oracleLE16(70) + oracleLE16(30) + oracleLE16(20) + ByteArray(8))!!
        assertTrue(ok.totalMatchesParts)
        val bad = GarminIntensityCandidate.decode(oracleLE16(50) + oracleLE16(30) + oracleLE16(20) + ByteArray(8))!!
        assertFalse(bad.totalMatchesParts)
    }

    @Test
    fun captureOpensOnlyRealtimeServicesAndNeverSpo2OrAccelerometer() {
        val ids = GarminRealtimeCapture.captureChannels.map { it.raw }
        assertEquals(listOf(6, 8, 10, 13), ids)
        assertTrue(GarminRealtimeCapture.captureChannels.all { it.isRealtime })
    }

    private companion object {
        val ORACLE = """
trace capture
@0 log R session start transport=V2 multi-link mtu=150 firstPairing=false
@0 phase closing-all
@0 log D -> ML close all
@0 w 00050200000000000000000000
@1 log D all handles closed
@1 phase registering
@1 log D -> ML register service=1 reliable=false
@1 w 00000200000000000000010000
@2 log R GFDI registered handle=0x65 reliable=false status=0
@2 phase awaiting-device-information
@3 log R watch Forerunner 165 model=165 product=4432 fw=29.05 protocol=151 maxPacket=512
@3 watch Forerunner 165 sw=2905
@3 log D -> status 5024 ack
@3 w 65000209058813a01303708900
@3 log D -> device information reply
@3 w 65000227058813a013029608ffffffffffff0117ffff044e4f4f50044e4f4f50066f7261636c6501dc8700
@3 phase handshaking
@4 log R watch capabilities: 10 bits; NOOP advertises 0,3,4,5,55,71,76,90
@4 log D -> status 5050 ack
@4 w 65000209058813ba1303514e00
@4 log D -> configuration 8 bits
@4 w 6500021805ba13113901010101028003801002040101010103854500
@4 log D -> system event 8
@4 w 6500020804a6130803d5c500
@4 phase ready
@4 log R handshake complete in 4 ms
@10 log R live channel 8 is undecoded; opens only in capture mode
@10 log R live channel 10 is undecoded; opens only in capture mode
@10 log R live channel 13 is undecoded; opens only in capture mode
@10 log R live channel 16 is not supported; not opened
@10 log D live channel 6 registering
@10 log D -> ML register service=6 reliable=false
@10 w 00000200000000000000060000
@11 log D live channel 8 registering
@11 log D -> ML register service=8 reliable=false
@11 w 00000200000000000000080000
@11 log D live channel 10 registering
@11 log D -> ML register service=10 reliable=false
@11 w 000002000000000000000a0000
@11 log D live channel 13 registering
@11 log D -> ML register service=13 reliable=false
@11 w 000002000000000000000d0000
@20 log R live channel 6 open handle=0x66
@20 channel 6 open
@21 log R live channel 8 open handle=0x67
@21 channel 8 open
@22 log R live channel 10 open handle=0x68
@22 channel 10 open
@23 log R live channel 13 refused status=3; not retried until asked again
@23 channel 13 refused status=3
@30 log D live 6 [00483bffff] hr bpm=72 resting=59 type=0 trailing=ffff
@30 log R candidate hr.type=0 (service 6; undecoded, not used)
@30 log R candidate hr.trailing=ffff (service 6; undecoded, not used)
@30 live hr bpm=72 resting=59 type=0 trailing=ffff
@31 raw 8 660800009a010000
@32 raw 10 5f000f0028000700000096000000
@33 log D ignored 2 bytes on unregistered handle 0x69
@40 log D live channel 8 closing handle 0x67
@40 log D -> ML close handle service=8 handle=103
@40 w 00020200000000000000080067
@40 log D live channel 10 closing handle 0x68
@40 log D -> ML close handle service=10 handle=104
@40 w 000202000000000000000a0068
@41 log R live channel 8 closed handle=0x67 status=0
@41 channel 8 closed
@42 raw 10 01
@43 log R live channel 10 closed handle=0x68 status=0
@43 channel 10 closed
end phase=ready open=6
trace late-answer
@0 log R session start transport=V2 multi-link mtu=150 firstPairing=false
@0 phase closing-all
@0 log D -> ML close all
@0 w 00050200000000000000000000
@1 log D all handles closed
@1 phase registering
@1 log D -> ML register service=1 reliable=false
@1 w 00000200000000000000010000
@2 log R GFDI registered handle=0x65 reliable=false status=0
@2 phase awaiting-device-information
@3 log R watch Forerunner 165 model=165 product=4432 fw=29.05 protocol=151 maxPacket=512
@3 watch Forerunner 165 sw=2905
@3 log D -> status 5024 ack
@3 w 65000209058813a01303708900
@3 log D -> device information reply
@3 w 65000227058813a013029608ffffffffffff0117ffff044e4f4f50044e4f4f50066f7261636c6501dc8700
@3 phase handshaking
@4 log R watch capabilities: 10 bits; NOOP advertises 0,3,4,5,55,71,76,90
@4 log D -> status 5050 ack
@4 w 65000209058813ba1303514e00
@4 log D -> configuration 8 bits
@4 w 6500021805ba13113901010101028003801002040101010103854500
@4 log D -> system event 8
@4 w 6500020804a6130803d5c500
@4 phase ready
@4 log R handshake complete in 4 ms
@11 log D live channel 13 registering
@11 log D -> ML register service=13 reliable=false
@11 w 000002000000000000000d0000
@12 log R live channel 13 is undecoded; opens only in capture mode
@13 log D live channel 13 no longer wanted; closing handle 0x70
@13 log D -> ML close handle service=13 handle=112
@13 w 000202000000000000000d0070
@14 log R live channel 13 closed handle=0x70 status=0
@14 channel 13 closed
end phase=ready open=-
trace watch-closes
@0 log R session start transport=V2 multi-link mtu=150 firstPairing=false
@0 phase closing-all
@0 log D -> ML close all
@0 w 00050200000000000000000000
@1 log D all handles closed
@1 phase registering
@1 log D -> ML register service=1 reliable=false
@1 w 00000200000000000000010000
@2 log R GFDI registered handle=0x65 reliable=false status=0
@2 phase awaiting-device-information
@3 log R watch Forerunner 165 model=165 product=4432 fw=29.05 protocol=151 maxPacket=512
@3 watch Forerunner 165 sw=2905
@3 log D -> status 5024 ack
@3 w 65000209058813a01303708900
@3 log D -> device information reply
@3 w 65000227058813a013029608ffffffffffff0117ffff044e4f4f50044e4f4f50066f7261636c6501dc8700
@3 phase handshaking
@4 log R watch capabilities: 10 bits; NOOP advertises 0,3,4,5,55,71,76,90
@4 log D -> status 5050 ack
@4 w 65000209058813ba1303514e00
@4 log D -> configuration 8 bits
@4 w 6500021805ba13113901010101028003801002040101010103854500
@4 log D -> system event 8
@4 w 6500020804a6130803d5c500
@4 phase ready
@4 log R handshake complete in 4 ms
@11 log D live channel 8 registering
@11 log D -> ML register service=8 reliable=false
@11 w 00000200000000000000080000
@11 log D live channel 13 registering
@11 log D -> ML register service=13 reliable=false
@11 w 000002000000000000000d0000
@12 log R live channel 8 open handle=0x67
@12 channel 8 open
@13 log R live channel 13 open handle=0x66
@13 channel 13 open
@14 log R watch closed live channel 13 handle 0x66 status=0
@14 channel 13 closed
@15 channel 8 closed
@15 log R watch closed all handles; re-registering GFDI
@15 phase registering
@15 log D -> ML register service=1 reliable=false
@15 w 00000200000000000000010000
@16 log D ignored 1 bytes on unregistered handle 0x67
end phase=registering open=-
trace before-ready
@0 log R session start transport=V2 multi-link mtu=150 firstPairing=false
@0 phase closing-all
@0 log D -> ML close all
@0 w 00050200000000000000000000
@2 log D all handles closed
@2 phase registering
@2 log D -> ML register service=1 reliable=false
@2 w 00000200000000000000010000
@3 log R GFDI registered handle=0x65 reliable=false status=0
@3 phase awaiting-device-information
@4 log R watch Forerunner 165 model=165 product=4432 fw=29.05 protocol=151 maxPacket=512
@4 watch Forerunner 165 sw=2905
@4 log D -> status 5024 ack
@4 w 65000209058813a01303708900
@4 log D -> device information reply
@4 w 65000227058813a013029608ffffffffffff0117ffff044e4f4f50044e4f4f50066f7261636c6501dc8700
@4 phase handshaking
@5 log R watch capabilities: 10 bits; NOOP advertises 0,3,4,5,55,71,76,90
@5 log D -> status 5050 ack
@5 w 65000209058813ba1303514e00
@5 log D -> configuration 8 bits
@5 w 6500021805ba13113901010101028003801002040101010103854500
@5 log D -> system event 8
@5 w 6500020804a6130803d5c500
@5 phase ready
@5 log R handshake complete in 5 ms
@5 log D live channel 10 registering
@5 log D -> ML register service=10 reliable=false
@5 w 000002000000000000000a0000
end phase=ready open=-
trace v1
@0 log R session start transport=V1 mtu=150 firstPairing=false
@0 phase awaiting-device-information
@2 log R live channels need the multi-link transport; V1 has none
end phase=awaiting-device-information open=-
hr 6 72
garmin-rt ch=6 hr t=1793549639000 len=5 hr=72 raw=00483bffff u16=18432,65339 u32=4282075136 i8=0
hr 6 -
garmin-rt ch=6 hr t=1793549639001 len=5 hr=- raw=00ff3bffff u16=65280,65339 u32=4282121984 i8=0
hr 6 -
garmin-rt ch=6 hr t=1793549639002 len=2 hr=72 raw=0000 u16=0 u32=- i8=0
hr 6 -
garmin-rt ch=6 hr t=1793549639003 len=1 hr=- raw=00 u16=- u32=- i8=0
hr 8 -
garmin-rt ch=8 calories t=1793549639004 len=8 hr=72 raw=660800009a010000 u16=2150,0,410,0 u32=2150,410 i8=102 | candidate calories total=2150 active=410 kcal
hr 8 -
garmin-rt ch=8 calories t=1793549639005 len=8 hr=- raw=ffffffff00000000 u16=65535,65535,0,0 u32=4294967295,0 i8=-1 | candidate calories total=4294967295 active=0 kcal
hr 8 -
garmin-rt ch=8 calories t=1793549639006 len=4 hr=72 raw=66080000 u16=2150,0 u32=2150 i8=102
hr 10 -
garmin-rt ch=10 intensity t=1793549639007 len=14 hr=- raw=5f000f0028000700000096000000 u16=95,15,40,7,0,150,0 u32=983135,458792,9830400 i8=95 | candidate intensity week=95 moderate=15 vigorous=40 goal=150 unknown=7,0,0 check=ok
hr 10 -
garmin-rt ch=10 intensity t=1793549639008 len=14 hr=72 raw=60000f0028000700000096000000 u16=96,15,40,7,0,150,0 u32=983136,458792,9830400 i8=96 | candidate intensity week=96 moderate=15 vigorous=40 goal=150 unknown=7,0,0 check=MISMATCH
hr 10 -
garmin-rt ch=10 intensity t=1793549639009 len=14 hr=- raw=ffffffffffff0100020003000400 u16=65535,65535,65535,1,2,3,4 u32=4294967295,131071,196610 i8=-1 | candidate intensity week=65535 moderate=65535 vigorous=65535 goal=3 unknown=1,2,4 check=MISMATCH
hr 10 -
garmin-rt ch=10 intensity t=1793549639010 len=5 hr=72 raw=5f000f0028 u16=95,15 u32=983135 i8=95
hr 13 -
garmin-rt ch=13 stress t=1793549639011 len=4 hr=- raw=2a000000 u16=42,0 u32=42 i8=42 | candidate garmin-stress(0-100) level=42 unknown=0
hr 13 -
garmin-rt ch=13 stress t=1793549639012 len=4 hr=72 raw=feff0000 u16=65534,0 u32=65534 i8=-2 | candidate garmin-stress(0-100) sentinel=-2 unknown=0
hr 13 -
garmin-rt ch=13 stress t=1793549639013 len=4 hr=- raw=65000700 u16=101,7 u32=458853 i8=101 | candidate garmin-stress(0-100) out-of-range=101 unknown=7
hr 13 -
garmin-rt ch=13 stress t=1793549639014 len=4 hr=72 raw=0000ffff u16=0,65535 u32=4294901760 i8=0 | candidate garmin-stress(0-100) level=0 unknown=65535
hr 13 -
garmin-rt ch=13 stress t=1793549639015 len=1 hr=- raw=2a u16=- u32=- i8=42
hr 13 -
garmin-rt ch=13 stress t=1793549639016 len=0 hr=72 raw= u16=- u32=- i8=-
hr 21 -
garmin-rt ch=21 service21 t=1793549639017 len=1 hr=- raw=10 u16=- u32=- i8=16
garmin-rt availability model=Forerunner 165 fw=29.05 ch8=opened ch10=unanswered ch13=refused(3)
garmin-rt availability model=Venu fw=10.01 ch8=opened ch10=opened ch13=opened
realtime 1 false
realtime 4 false
realtime 6 true
realtime 7 true
realtime 8 true
realtime 9 true
realtime 10 true
realtime 12 true
realtime 13 true
realtime 16 true
realtime 19 true
realtime 20 true
realtime 21 true
realtime 8216 false
realtime 16408 false
realtime 24600 false
realtime 40984 false
realtime 49176 false
realtime 57368 false
"""
    }
}
