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
@160 log D registration for service 6 status=0 ignored
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
