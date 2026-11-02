package com.noop.garmin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Byte-identical parity of [GarminSession] with Packages/GarminProtocol/.../GarminSession.swift, and the
 * handshake log assertion. [ORACLE] is the verbatim stdout of the Swift session oracle built standalone:
 *
 *   cd Packages/GarminProtocol && swiftc -O -D GARMIN_ORACLE_STANDALONE <every Sources/GarminProtocol .swift file> \
 *       Tests/GarminProtocolTests/GarminOracleCases.swift Tests/GarminProtocolTests/GarminSessionOracleCases.swift \
 *       Oracle/main.swift -o /tmp/garmin-oracle && /tmp/garmin-oracle session
 *
 * GarminSessionOracleCases.kt re-runs the same traces here; the Swift GarminSessionTests reads this literal
 * back. Every write is printed in hex beside the log line naming it, so a reordered, extra or missing message
 * fails on both platforms. Regenerate, never hand-edit.
 */
class GarminSessionTest {

    @Test
    fun kotlinMatchesTheSwiftSessionOracle() {
        val expected = ORACLE.removePrefix("\n").removeSuffix("\n").split("\n")
        val actual = garminSessionOracleLines()
        for (i in 0 until minOf(expected.size, actual.size)) {
            assertEquals("session oracle line ${i + 1}", expected[i], actual[i])
        }
        assertEquals("session oracle line count", expected.size, actual.size)
    }

    private fun clock(ms: Long) = GarminClock(ms, 1_793_546_986L, 25_200)

    private fun handshake(firstPairing: Boolean): List<GarminSessionOutput> {
        val s = GarminSession(GarminSessionConfig(oracleSessionHost(), firstPairing = firstPairing, sendsHostLifecycleEvents = true),
            GarminTransportKind.MULTI_LINK_V2, 150)
        val out = s.start(clock(0)).toMutableList()
        out += s.receive(oracleControl(6, oracleLE16(0) + bytes(0)), clock(1))
        out += s.receive(oracleControl(1, oracleLE16(1) + bytes(0, 0x65, 0)), clock(2))
        out += s.receive(bytes(0x65) + oracleCobs(oracleFr165DeviceInformation()), clock(3))
        out += s.receive(bytes(0x65) + oracleCobs(oracleFrame(5050, bytes(1, 0x09))), clock(4))
        out += s.receive(bytes(0x65) + oracleCobs(oracleFrame(5052, oracleLE32(1))), clock(5))
        assertEquals(GarminSessionPhase.Ready, s.phase)
        return out
    }

    /** The handshake log assertion: the outbound messages, in order. */
    @Test
    fun handshakeSendsExactlyTheMinimalSequence() {
        val sent = handshake(false).mapNotNull { (it as? GarminSessionOutput.Log)?.line?.text?.takeIf { t -> t.startsWith("-> ") } }
        assertEquals(listOf(
            "-> ML close all",
            "-> ML register service=1 reliable=false",
            "-> status 5024 ack",
            "-> device information reply",
            "-> status 5050 ack",
            "-> configuration 8 bits",
            "-> system event 8",
            "-> system event 6",
            "-> current time reply",
        ), sent)
        val firstPair = handshake(true)
        assertTrue(firstPair.any { it is GarminSessionOutput.PairingEventsSent })
        val events = firstPair.mapNotNull { (it as? GarminSessionOutput.Log)?.line?.text?.takeIf { t -> t.startsWith("-> system event") } }
        assertEquals(listOf("-> system event 8", "-> system event 4", "-> system event 14", "-> system event 6"), events)
    }

    /** Decode every byte written back into GFDI messages: only status replies, configuration and safe events. */
    @Test
    fun everyWrittenMessageIsAllowlisted() {
        val decoder = GarminCobsStreamDecoder()
        val types = mutableSetOf<Int>()
        for (o in handshake(true)) {
            val w = o as? GarminSessionOutput.Write ?: continue
            if (w.bytes[0].toInt() == 0) continue
            for (message in decoder.feed(w.bytes.copyOfRange(1, w.bytes.size))) {
                val frame = (GarminGfdi.decode(message) as GarminGfdi.Result.Success).frame
                types += frame.type
                if (frame.type == 5030) assertFalse(frame.payload[0].toInt() in listOf(2, 10, 11, 17))
            }
        }
        assertEquals(setOf(5000, 5050, 5030), types)
    }

    @Test
    fun minimalCapabilitiesAdvertiseNoPhoneFeatures() {
        assertEquals(listOf(0, 3, 4, 5, 55, 71, 76, 90), GarminSessionConfig.MINIMAL_CAPABILITIES.map { it.bit }.sorted())
    }

    private companion object {
        // ORACLE-BEGIN (Swift stdout, verbatim)
        val ORACLE = """
trace ml-full
@0 log R session start transport=V2 multi-link mtu=150 firstPairing=true
@0 phase closing-all
@0 log D -> ML close all
@0 w 00050200000000000000000000
@5 log D handle 0x60 service=1 closed status=0
@10 log D all handles closed
@10 phase registering
@10 log D -> ML register service=1 reliable=false
@10 w 00000200000000000000010000
@20 log R GFDI registered handle=0x65 reliable=false status=0
@20 phase awaiting-device-information
@31 log R watch Forerunner 165 model=165 product=4432 fw=29.05 protocol=151 maxPacket=512
@31 watch Forerunner 165 sw=2905
@31 log D -> status 5024 ack
@31 w 65000209058813a01303708900
@31 log D -> device information reply
@31 w 65000227058813a013029608ffffffffffff0117ffff044e4f4f50044e4f4f50066f7261636c6501dc8700
@31 phase handshaking
@40 log D 5024 again (the watch re-sends until answered)
@40 log D -> status 5024 ack
@40 w 65000209058813a01303708900
@40 log D -> device information reply
@40 w 65000227058813a013029608ffffffffffff0117ffff044e4f4f50044e4f4f50066f7261636c6501dc8700
@50 log R watch capabilities: 10 bits; NOOP advertises 0,3,4,5,55,71,76,90
@50 log D -> status 5050 ack
@50 w 65000209058813ba1303514e00
@50 log D -> configuration 8 bits
@50 w 6500021805ba13113901010101028003801002040101010103854500
@50 log D -> system event 8
@50 w 6500020804a6130803d5c500
@50 log D -> system event 4
@50 w 6500020804a6130403d0c500
@50 log D -> system event 14
@50 w 6500020804a6130e03d66500
@50 paired
@50 phase ready
@50 log R handshake complete in 50 ms
@50 log D -> system event 6
@50 w 6500020804a6130603d1a500
@60 log D watch acked 5050
@70 log D 5052 time request ref=90094860 -> unix=1793546986 offset=25200
@70 log D -> current time reply
@70 w 6500021d058813bc130b0cbd5e05ea0e4a4570620101010101010101010351b600
@80 log D 5101 auth negotiation echoed unknown=4 flags=16909060
@80 log D -> auth negotiation reply
@80 w 6500020f058813ed1301080404030201762a00
@90 log R refused watch request core (HTTP proxy: weather / AGPS / OAuth) (Smart field 13) id=795
@90 log D -> protobuf status request id=795 discard=true code=100
@90 w 65000211058813b313031b030101010501642fa600
@100 log D 5023 battery 87%
@100 log D -> status 5023 ack
@100 w 650002090588139f1303408500
@100 battery 87
@110 log D 5037 watch has new data
@110 log D -> status 5037 ack
@110 w 65000209058813ad1303e14a00
@110 sync synchronization type=0 mask=0xf
@120 log R refused weather request (5014)
@120 log D -> status 5014 unsupported
@120 w 65000209088813961302114600
@121 log D -> status 5014 unsupported
@121 w 65000209088813961302114600
@130 log R watch answered 5030 with status 2
@140 log R dropped GFDI frame: crc 39311 != 32749
@150 log D ignored 3 bytes on unregistered handle 0x22
@160 log R unrequested handle 0x22 for service 6; closing it
@160 log D -> ML close handle service=6 handle=34
@160 w 00020200000000000000060022
@170 log D -> system event 7
@170 w 6500020804a6130703d03500
@180 log R watch closed GFDI handle 0x65 status=0; re-registering
@180 phase registering
@180 log D -> ML register service=1 reliable=false
@180 w 00000200000000000000010000
@190 log R GFDI registered handle=0x66 reliable=false status=0
@190 phase awaiting-device-information
@195 log D 5024 again (the watch re-sends until answered)
@195 log D -> status 5024 ack
@195 w 66000209058813a01303708900
@195 log D -> device information reply
@195 w 66000227058813a013029608ffffffffffff0117ffff044e4f4f50044e4f4f50066f7261636c6501dc8700
@195 phase handshaking
@196 log R watch capabilities: 2 bits; NOOP advertises 0,3,4,5,55,71,76,90
@196 log D -> status 5050 ack
@196 w 66000209058813ba1303514e00
@196 log D -> configuration 8 bits
@196 w 6600021805ba13113901010101028003801002040101010103854500
@196 log D -> system event 8
@196 w 6600020804a6130803d5c500
@196 phase ready
@196 log R handshake complete in 16 ms
@196 log D -> system event 7
@196 w 6600020804a6130703d03500
@200 log D -> system event 6
@200 w 6600020804a6130603d1a500
end phase=ready in=14 dropped=1 deadline=-
trace ml-refused
@0 log R session start transport=V2 multi-link mtu=23 firstPairing=false
@0 phase closing-all
@0 log D -> ML close all
@0 w 00050200000000000000000000
@10 log D all handles closed
@10 phase registering
@10 log D -> ML register service=1 reliable=false
@10 w 00000200000000000000010000
@20 log R GFDI registration refused status=3
@20 log R session failed: registration refused status=3
@20 phase failed(registration refused status=3)
end phase=failed(registration refused status=3) in=0 dropped=0 deadline=-
trace ml-timeout
@0 log R session start transport=V2 multi-link mtu=23 firstPairing=false
@0 phase closing-all
@0 log D -> ML close all
@0 w 00050200000000000000000000
@1000 log R session failed: handshake timed out in closing-all after 1000 ms
@1000 phase failed(handshake timed out in closing-all after 1000 ms)
end phase=failed(handshake timed out in closing-all after 1000 ms) in=0 dropped=0 deadline=-
trace v1-direct
@0 log R session start transport=V1 mtu=23 firstPairing=false
@0 phase awaiting-device-information
@11 log R watch Forerunner 165 model=165 product=4432 fw=29.05 protocol=151 maxPacket=512
@11 watch Forerunner 165 sw=2905
@11 log D -> status 5024 ack
@11 w 000209058813a01303708900
@11 log D -> device information reply
@11 w 000227058813a013029608ffffffffffff0117
@11 w ffff044e4f4f50044e4f4f50066f7261636c65
@11 w 01dc8700
@11 phase handshaking
@20 log R watch capabilities: 2 bits; NOOP advertises 0,3,4,5,55,71,76,90
@20 log D -> status 5050 ack
@20 w 000209058813ba1303514e00
@20 log D -> configuration 8 bits
@20 w 00021805ba1311390101010102800380100204
@20 w 0101010103854500
@20 log D -> system event 8
@20 w 00020804a6130803d5c500
@20 phase ready
@20 log R handshake complete in 20 ms
@20 log D -> system event 7
@20 w 00020804a6130703d03500
@30 log D 5052 time request ref=7 -> unix=1793546986 offset=25200
@30 log D -> current time reply
@30 w 00021d058813bc130207010107ea0e4a457062
@30 w 010b10e72a4710acc145ad5500
end phase=ready in=3 dropped=0 deadline=-
trace mlr
@0 log R session start transport=V2 multi-link mtu=64 firstPairing=false
@0 phase closing-all
@0 log D -> ML close all
@0 w 00050200000000000000000000
@10 log D all handles closed
@10 phase registering
@10 log D -> ML register service=1 reliable=false
@10 w 00000200000000000000010000
@20 log R GFDI registered handle=0x81 reliable=true status=0
@20 phase awaiting-device-information
@30 log R watch Forerunner 165 model=165 product=4432 fw=29.05 protocol=151 maxPacket=512
@30 watch Forerunner 165 sw=2905
@30 log D -> status 5024 ack
@30 w 9040000209058813a01303708900
@30 log D -> device information reply
@30 w 9041000227058813a013029608ffffffffffff0117ffff044e4f4f50044e4f4f50066f7261636c6501dc8700
@30 phase handshaking
@1040 w 9040000209058813a01303708900
@1040 w 9041000227058813a013029608ffffffffffff0117ffff044e4f4f50044e4f4f50066f7261636c6501dc8700
end phase=handshaking in=1 dropped=0 deadline=3040
trace ml-live
@0 log R session start transport=V2 multi-link mtu=150 firstPairing=false
@0 phase closing-all
@0 log D -> ML close all
@0 w 00050200000000000000000000
@10 log D all handles closed
@10 phase registering
@10 log D -> ML register service=1 reliable=false
@10 w 00000200000000000000010000
@20 log R GFDI registered handle=0x65 reliable=false status=0
@20 phase awaiting-device-information
@30 log R watch Forerunner 165 model=165 product=4432 fw=29.05 protocol=151 maxPacket=512
@30 watch Forerunner 165 sw=2905
@30 log D -> status 5024 ack
@30 w 65000209058813a01303708900
@30 log D -> device information reply
@30 w 65000227058813a013029608ffffffffffff0117ffff044e4f4f50044e4f4f50066f7261636c6501dc8700
@30 phase handshaking
@40 log R watch capabilities: 2 bits; NOOP advertises 0,3,4,5,55,71,76,90
@40 log D -> status 5050 ack
@40 w 65000209058813ba1303514e00
@40 log D -> configuration 8 bits
@40 w 6500021805ba13113901010101028003801002040101010103854500
@40 log D -> system event 8
@40 w 6500020804a6130803d5c500
@40 phase ready
@40 log R handshake complete in 40 ms
@40 log D live channel 6 registering
@40 log D -> ML register service=6 reliable=false
@40 w 00000200000000000000060000
@40 log D live channel 12 registering
@40 log D -> ML register service=12 reliable=false
@40 w 000002000000000000000c0000
@50 log R live channel 6 open handle=0x66
@50 channel 6 open
@51 log R live channel 12 open handle=0x67
@51 channel 12 open
@60 log D live 6 [033e37ffff] hr bpm=62 resting=55 type=3 trailing=ffff
@60 log R candidate hr.type=3 (service 6; undecoded, not used)
@60 log R candidate hr.trailing=ffff (service 6; undecoded, not used)
@60 live hr bpm=62 resting=55 type=3 trailing=ffff
@61 log D live 6 [030000ffff] hr bpm=- resting=- type=3 trailing=ffff
@61 live hr bpm=- resting=- type=3 trailing=ffff
@62 log D live 6 [02fa00] hr bpm=250 resting=- type=2 trailing=
@62 log R candidate hr.type=2 (service 6; undecoded, not used)
@62 log R candidate hr.trailing= (service 6; undecoded, not used)
@62 live hr bpm=250 resting=- type=2 trailing=
@63 log R live channel 6 payload too short: 03
@70 log D live 12 [e80304030201] rr ms=1000 unknown=16909060
@70 log R candidate rr.unknown=16909060 (service 12; undecoded, not used)
@70 live rr ms=1000 unknown=16909060
@71 log D live 12 [000007000000] rr ms=- unknown=7
@71 log R candidate rr.unknown=7 (service 12; undecoded, not used)
@71 live rr ms=- unknown=7
@72 log D live 12 [5203] rr ms=850 unknown=-
@72 live rr ms=850 unknown=-
@73 log R live channel 12 payload too short: 05
@80 log R live channel 13 is undecoded; opens only in capture mode
@80 log R live channel 16 is not supported; not opened
@80 log D live channel 7 registering
@80 log D -> ML register service=7 reliable=false
@80 w 00000200000000000000070000
@80 log D live channel 9 registering
@80 log D -> ML register service=9 reliable=false
@80 w 00000200000000000000090000
@80 log D live channel 19 registering
@80 log D -> ML register service=19 reliable=false
@80 w 00000200000000000000130000
@80 log D live channel 20 registering
@80 log D -> ML register service=20 reliable=false
@80 w 00000200000000000000140000
@80 log D live channel 21 registering
@80 log D -> ML register service=21 reliable=false
@80 w 00000200000000000000150000
@90 log R live channel 7 open handle=0x68
@90 channel 7 open
@91 log R live channel 9 open handle=0x69
@91 channel 9 open
@92 log R live channel 19 refused status=1; not retried until asked again
@92 channel 19 refused status=1
@93 log R live channel 20 came back reliable; closing handle 0x6a
@93 log D -> ML close handle service=20 handle=106
@93 w 0002020000000000000014006a
@94 log R live channel 21 open handle=0x6b
@94 channel 21 open
@100 log D live 7 [e11000004c1d0000] steps total=4321 goal=7500
@100 live steps total=4321 goal=7500
@101 log D live 9 [030001000a00] floors up=3 down=1 target=10
@101 live floors up=3 down=1 target=10
@102 log D live 21 [0e] respiration 14
@102 live respiration 14
@103 log D live 21 [fe] respiration -
@103 live respiration -
@104 log D live 21 [00] respiration -
@104 live respiration -
@120 log R live channel 20 closed handle=0x6a status=0
@120 channel 20 closed
@120 log D -> ML register service=20 reliable=false
@120 w 00000200000000000000140000
@130 log D live channel 7 closing handle 0x68
@130 log D -> ML close handle service=7 handle=104
@130 w 00020200000000000000070068
@130 log D live channel 9 closing handle 0x69
@130 log D -> ML close handle service=9 handle=105
@130 w 00020200000000000000090069
@130 log D live channel 21 closing handle 0x6b
@130 log D -> ML close handle service=21 handle=107
@130 w 0002020000000000000015006b
@140 log R live channel 7 closed handle=0x68 status=0
@140 channel 7 closed
@141 log R live channel 9 closed handle=0x69 status=0
@141 channel 9 closed
@142 log R live channel 21 closed handle=0x6b status=0
@142 channel 21 closed
@150 log D live channel 6 closing handle 0x66
@150 log D -> ML close handle service=6 handle=102
@150 w 00020200000000000000060066
@150 log D live channel 12 closing handle 0x67
@150 log D -> ML close handle service=12 handle=103
@150 w 000202000000000000000c0067
@150 log D live channel 19 registering
@150 log D -> ML register service=19 reliable=false
@150 w 00000200000000000000130000
@160 log R live channel 19 open handle=0x6c
@160 channel 19 open
@161 log D live 19 [ff00000000] spo2 pct=- time=-
@161 live spo2 pct=- time=-
@162 log D live 19 [61ea0e4a45] spo2 pct=97 time=1162481386
@162 live spo2 pct=97 time=1162481386
@163 log D live 19 [6505000000] spo2 pct=- time=-
@163 live spo2 pct=- time=-
@170 log D live channel 19 closing handle 0x6c
@170 log D -> ML close handle service=19 handle=108
@170 w 0002020000000000000013006c
@180 log R live channel 6 closed handle=0x66 status=0
@180 channel 6 closed
@180 log D -> ML register service=6 reliable=false
@180 w 00000200000000000000060000
@181 log R live channel 12 closed handle=0x67 status=0
@181 channel 12 closed
@182 log R live channel 19 closed handle=0x6c status=0
@182 channel 19 closed
@190 log R live channel 6 open handle=0x6d
@190 channel 6 open
@191 log R unrequested handle 0x6e for service 21; closing it
@191 log D -> ML close handle service=21 handle=110
@191 w 0002020000000000000015006e
@192 log R live channel 21 closed handle=0x6e status=0
@192 channel 21 closed
@193 log D registration for service 77 status=0 ignored
@200 log D live 6 [034600ffff] hr bpm=70 resting=- type=3 trailing=ffff
@200 live hr bpm=70 resting=- type=3 trailing=ffff
@210 channel 6 closed
@210 log R watch closed all handles; re-registering GFDI
@210 phase registering
@210 log D -> ML register service=1 reliable=false
@210 w 00000200000000000000010000
@211 log D ignored 5 bytes on unregistered handle 0x6d
@220 log R GFDI registered handle=0x70 reliable=false status=0
@220 phase awaiting-device-information
@221 log D 5024 again (the watch re-sends until answered)
@221 log D -> status 5024 ack
@221 w 70000209058813a01303708900
@221 log D -> device information reply
@221 w 70000227058813a013029608ffffffffffff0117ffff044e4f4f50044e4f4f50066f7261636c6501dc8700
@221 phase handshaking
@222 log R watch capabilities: 2 bits; NOOP advertises 0,3,4,5,55,71,76,90
@222 log D -> status 5050 ack
@222 w 70000209058813ba1303514e00
@222 log D -> configuration 8 bits
@222 w 7000021805ba13113901010101028003801002040101010103854500
@222 log D -> system event 8
@222 w 7000020804a6130803d5c500
@222 phase ready
@222 log R handshake complete in 12 ms
@222 log D live channel 6 registering
@222 log D -> ML register service=6 reliable=false
@222 w 00000200000000000000060000
@230 log R live channel 6 open handle=0x71
@230 channel 6 open
@240 log D ignored control frame 0002020000000000000006007100
end phase=ready in=4 dropped=0 deadline=-
trace v1-live
@0 log R session start transport=V1 mtu=23 firstPairing=false
@0 phase awaiting-device-information
@1 log R live channels need the multi-link transport; V1 has none
end phase=awaiting-device-information in=0 dropped=0 deadline=30000
select fr165 V2 multi-link 6a4e2810 6a4e2820 found=6a4e2800[6a4e2810,6a4e2811,6a4e2820,6a4e2821,00002803] 6a4e8022[6a4e4c80,6a4ecd28] 0000180d[00002a37]
select ml-second-pair V2 multi-link 6a4e2812 6a4e2822 found=6a4e2800[6a4e2810,6a4e2812,6a4e2821,6a4e2822]
select watch-apps-only unsupported found=6a4e8022[6a4e4c80,6a4ecd28] 0000180d[00002a37]
select v1 V1 6a4ecd28 6a4e4c80 found=6a4e2401[6a4e4c80,6a4ecd28]
select v0 V0 4acbcd28 df334c80 found=9b012401[4acbcd28,df334c80]
select v1-half unsupported found=6a4e2401[6a4e4c80]
select empty unsupported found=
advert garmin true
advert other false
advert short false
advert none false
"""
        // ORACLE-END
    }
}
