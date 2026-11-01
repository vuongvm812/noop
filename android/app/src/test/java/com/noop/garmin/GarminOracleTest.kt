package com.noop.garmin

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Byte-identical parity with Packages/GarminProtocol. [ORACLE] is the verbatim stdout of the Swift codec
 * compiled standalone over the cases in GarminOracleCases.swift:
 *
 *   cd Packages/GarminProtocol && swiftc -O -D GARMIN_ORACLE_STANDALONE <every Sources/GarminProtocol .swift file> \
 *       Tests/GarminProtocolTests/GarminOracleCases.swift Oracle/main.swift -o /tmp/garmin-oracle && /tmp/garmin-oracle
 *
 * (the exact command is in GarminOracleCases.swift).
 *
 * It covers CRC (incl. the running chain), COBS, the MLR header over every handle × reqNum × seq, a scripted
 * MLR session, ML control frames, GFDI framing, every outbound case and every inbound codec.
 * GarminOracleCases.kt re-runs the same cases on the Kotlin codec. The Swift test GarminOracleTests reads
 * this literal back, so a byte that changes on one platform fails on both. Regenerate, never hand-edit.
 */
class GarminOracleTest {

    @Test
    fun kotlinMatchesTheSwiftOracle() {
        val expected = ORACLE.removePrefix("\n").removeSuffix("\n").split("\n")
        val actual = garminOracleLines()
        for (i in 0 until minOf(expected.size, actual.size)) {
            assertEquals("oracle line ${i + 1}", expected[i], actual[i])
        }
        assertEquals("oracle line count", expected.size, actual.size)
    }

    private companion object {
        // ORACLE-BEGIN (Swift stdout, verbatim)
        val ORACLE = """
crc empty 0000
crc zero 0000
crc check bb3d
crc ff4 9401
crc seq256 bad3
crc pat1000 83a8
crc.chain 03c0,06b0,5d49,6d78,ef9f,4f39 whole=4f39
cobs empty 000100 rt=true
cobs z1 00010100 rt=true
cobs z2 0001010100 rt=true
cobs mix 00031122023300 rt=true
cobs tailzero 0002110100 rt=true
cobs ones253 len=256 fnv=f5d80a0e rt=true
cobs ones254 len=258 fnv=a484b833 rt=true
cobs ones255 len=259 fnv=993c58d1 rt=true
cobs seq256 len=260 fnv=d612e419 rt=true
cobs pat600 len=604 fnv=18f857c9 rt=true
cobs.bad overrun nil
cobs.bad innerzero nil
cobs.bad codezero nil
cobs.bad ff-short nil
cobs.frame nolead nil
cobs.frame notail nil
cobs.frame tiny nil
cobs.stream split=1 frames=[aa00bb,len300:b2b6fda5,] dropped=1
cobs.stream split=3 frames=[aa00bb,len300:b2b6fda5,] dropped=1
cobs.stream split=20 frames=[aa00bb,len300:b2b6fda5,] dropped=1
cobs.stream split=1000 frames=[aa00bb,len300:b2b6fda5,] dropped=1
cobs.oversize frames=[0708] dropped=1
mlr.pack h=0 fnv=0f47fdc5 crc=e0c8 head=800080018002800380048005 tail=8ffe8fff
mlr.pack h=1 fnv=013429c5 crc=0d76 head=900090019002900390049005 tail=9ffe9fff
mlr.pack h=2 fnv=8b4bd1c5 crc=7bb7 head=a000a001a002a003a004a005 tail=affeafff
mlr.pack h=3 fnv=35dadfc5 crc=9609 head=b000b001b002b003b004b005 tail=bffebfff
mlr.pack h=4 fnv=a396abc5 crc=9635 head=c000c001c002c003c004c005 tail=cffecfff
mlr.pack h=5 fnv=450db9c5 crc=7b8b head=d000d001d002d003d004d005 tail=dffedfff
mlr.pack h=6 fnv=5a5a67c5 crc=0d4a head=e000e001e002e003e004e005 tail=effeefff
mlr.pack h=7 fnv=52cfd9c5 crc=e0f4 head=f000f001f002f003f004f005 tail=fffeffff
mlr.unpack ok=32768 plain=true short=true
mlr.step send40 out=[b000+18,b001+18,b002+18,b003+18,b004+18,b005+18,b006+18,b007+18,b008+18,b009+18,b00a+18,b00b+18,b00c+18,b00d+18,b00e+18,b00f+18,b010+18,b011+18,b012+18,b013+18,b014+18,b015+18,b016+18,b017+18,b018+18,b019+18,b01a+18,b01b+18,b01c+18,b01d+18,b01e+18,b01f+18] inflight=32 queued=8 window=32 rto=1000 next=1000
mlr.step ack10 out=[b020+18,b021+18,b022+18,b023+18,b024+18,b025+18,b026+18,b027+18] inflight=30 queued=0 window=32 rto=1000 next=1100 data=-
mlr.step tick1099 out=[] inflight=30 queued=0 window=32 rto=1000 next=1100
mlr.step rto1 out=[b00a+18,b00b+18,b00c+18,b00d+18,b00e+18,b00f+18,b010+18,b011+18,b012+18,b013+18,b014+18,b015+18,b016+18,b017+18,b018+18,b019+18,b01a+18,b01b+18,b01c+18,b01d+18,b01e+18,b01f+18,b020+18,b021+18,b022+18,b023+18,b024+18,b025+18,b026+18,b027+18] inflight=30 queued=0 window=16 rto=2000 next=3100
mlr.step rto2 out=[b00a+18,b00b+18,b00c+18,b00d+18,b00e+18,b00f+18,b010+18,b011+18,b012+18,b013+18,b014+18,b015+18,b016+18,b017+18,b018+18,b019+18,b01a+18,b01b+18,b01c+18,b01d+18,b01e+18,b01f+18,b020+18,b021+18,b022+18,b023+18,b024+18,b025+18,b026+18,b027+18] inflight=30 queued=0 window=8 rto=4000 next=7100
mlr.step ack40+data0 out=[] inflight=0 queued=0 window=32 rto=1000 next=3220 data=a0
mlr.step acktimer out=[b040] inflight=0 queued=0 window=32 rto=1000 next=-
mlr.step dup0 out=[b040] inflight=0 queued=0 window=32 rto=1000 next=- data=-
mlr.step gap2 out=[b040] inflight=0 queued=0 window=32 rto=1000 next=- data=-
mlr.step in1 out=[] inflight=0 queued=0 window=32 rto=1000 next=3341 data=a1
mlr.step in2 out=[] inflight=0 queued=0 window=32 rto=1000 next=3342 data=a2
mlr.step in3 out=[] inflight=0 queued=0 window=32 rto=1000 next=3343 data=a3
mlr.step in4 out=[] inflight=0 queued=0 window=32 rto=1000 next=3344 data=a4
mlr.step in5 out=[b180] inflight=0 queued=0 window=32 rto=1000 next=- data=a5
mlr.step bogusack out=[] inflight=0 queued=0 window=32 rto=1000 next=-
mlr.step ack0 out=[] inflight=0 queued=0 window=32 rto=1000 next=-
mlr.step sendbig out=[b1a8+17] inflight=1 queued=0 window=32 rto=1000 next=4600
mlr.step ack41+data6 out=[] inflight=0 queued=0 window=32 rto=1000 next=3630 data=a6
mlr.wrong-handle true
ml.register 1 00000200000000000000010000 00000200000000000000010002
ml.register 4 00000200000000000000040000 00000200000000000000040002
ml.register 6 00000200000000000000060000 00000200000000000000060002
ml.register 7 00000200000000000000070000 00000200000000000000070002
ml.register 8 00000200000000000000080000 00000200000000000000080002
ml.register 9 00000200000000000000090000 00000200000000000000090002
ml.register 10 000002000000000000000a0000 000002000000000000000a0002
ml.register 12 000002000000000000000c0000 000002000000000000000c0002
ml.register 13 000002000000000000000d0000 000002000000000000000d0002
ml.register 16 00000200000000000000100000 00000200000000000000100002
ml.register 19 00000200000000000000130000 00000200000000000000130002
ml.register 20 00000200000000000000140000 00000200000000000000140002
ml.register 21 00000200000000000000150000 00000200000000000000150002
ml.register 8216 00000200000000000000182000 00000200000000000000182002
ml.register 16408 00000200000000000000184000 00000200000000000000184002
ml.register 24600 00000200000000000000186000 00000200000000000000186002
ml.register 40984 0000020000000000000018a000 0000020000000000000018a002
ml.register 49176 0000020000000000000018c000 0000020000000000000018c002
ml.register 57368 0000020000000000000018e000 0000020000000000000018e002
ml.close 00020200000000000000060005
ml.closeall 00050200000000000000000000 00050807060504030201000000
ml.parse reg.ok registered service=1 status=0 handle=1 reliable=false
ml.parse reg.mlr registered service=8216 status=0 handle=130 reliable=true
ml.parse reg.fail registered service=6 status=3 handle=0 reliable=false
ml.parse reg.short nil
ml.parse close closed service=6 handle=4 status=0
ml.parse closeall allClosed
ml.parse foreign nil
ml.parse echo nil
ml.parse unk nil
ml.parse nothandle0 nil
ml.route  empty
ml.route 0001 control 0001
ml.route 01aabb plain 1 aabb
ml.route b241cc reliable 131 b241cc
ml.route a100 reliable 130 a100
ml.route 8510 plain 133 10
ml.plain 0300020e058813,038c1301020101,0301039ded00
ml.plain.mtu 19 511 1 mlr=18
gfdi good ok raw=13a0 type=5024 seq=- payload=010203
gfdi compact ok raw=ff32 type=5050 seq=127 payload=09
gfdi empty ok raw=13a7 type=5031 seq=- payload=
gfdi badcrc err crc 3c0d c30d
gfdi oversize err length 200 9
gfdi undersize err length 9 8
gfdi short err short
epoch 1136160000 631065600 4294967295
out status 000209058813a01303708900
out status 0002090888136f1702c3b700
out deviceInformationReply 000230058813a013029608ffffffffffff6420ffff0a4e4f4f502070686f6e65044e4f4f5009537472616e6420c3a901589c00
out configuration 00021804ba131101010101010101010101010101010101038f9400
out configuration 00021805ba13113901010101028003801002040101010103854500
out authNegotiationReply 00020f058813ed1301010101010103b67a00
out authNegotiationReply 00020f058813ed1301030301010480f22600
out currentTimeReply 00021d058813bc13024d01010108ab9041b0b9ffff0ad8c14240f6dc4196ce00
out systemEvent 00020803a6130103d20500
out systemEvent 00020804a6130103d39500
out systemEvent 00020804a6130303d2f500
out systemEvent 00020804a6130403d0c500
out systemEvent 00020804a6130503d15500
out systemEvent 00020804a6130603d1a500
out systemEvent 00020804a6130703d03500
out systemEvent 00020804a6130803d5c500
out systemEvent 00020804a6130903d45500
out systemEvent 00020804a6130c03d70500
out systemEvent 00020804a6130d03d69500
out systemEvent 00020804a6130e03d66500
out systemEvent 00020804a6130f03d7f500
out systemEvent 00020804a6131003dfc500
out filter 000207038f1303891b00
out filter 000207068f130148db00
out filter 000207068f1303c91a00
out downloadRequest 000214038a130101010101020101010101010103303200
out downloadRequest 000214058a1334120210010106efbe7011010401783f00
out fileTransferDataReply 00020e0588138c130103f4010103ffe100
out supportedFileTypesRequest 00020605a7133b7500
out fileAccessRequest 00021904b3130101010101020501010205010105da02024a03b8fc00
out fileAccessRequest 00024804b3130201010101023401010234010112da02314a2f080910ac021864221209a5a501010101010411a5a50101010101062a1209a5a501010101010411a5a5010101010103e7ba00
out fileAccessRequest len=91 fnv=24f0237c
out protobufStatus 000211058813b3130205010101010101039c5d00
out protobufStatus 000211058813b4130206020a0101050164c6ed00
in devinfo devinfo proto=150 product=3869 unit=305419896 sw=1234 maxpkt=375 bt=Forerunner 165 name=Forerunner 165 model=FR165 classic=-
in devinfo.addr devinfo proto=150 product=3869 unit=305419896 sw=1234 maxpkt=375 bt=Forerunner 165 name=Forerunner 165 model=FR165 classic=a1a2a3a4a5a6
in devinfo.short unknown type=5024 payload=96001d0f78563412d20477010e466f726572756e
in config config bits=0,3,4,5,16,23
in config.short unknown type=5050 payload=050102
in auth auth unk=2 flags=00000011
in time time ref=3735928559
in sync4 sync type=1 mask=0000000080000021
in sync8 sync type=0 mask=0000001004000020
in sync.bad unknown type=5037 payload=00020101
in chunk chunk flags=0 crc=1f2e offset=512 data=dead00ef
in available available {idx=258 dt=128 st=4 num=7 sf=0 ff=16 size=2048 ts=1100000000 unix=1731065600}
in available.extra available {idx=258 dt=128 st=4 num=7 sf=0 ff=16 size=2048 ts=1100000000 unix=1731065600}
in compact.available available {idx=258 dt=128 st=4 num=7 sf=0 ff=16 size=2048 ts=1100000000 unix=1731065600}
in status.generic status orig=5030 status=0 detail=none
in status.nak status orig=5002 status=1 detail=none
in status.download status orig=5002 status=0 detail=download 0 4096
in status.download.short status orig=5002 status=0 detail=none
in status.transfer status orig=5004 status=0 detail=transfer 1 256
in status.types status orig=5031 status=0 detail=types [128/4/FIT_TYPE_4,255/0/Ü]
in status.filter status orig=5007 status=0 detail=filter 7
in status.auth status orig=5101 status=0 detail=auth 0 2 00000001
in status.protobuf status orig=5043 status=0 detail=protobuf 3 0 0 0
in status.short unknown type=5000 payload=88
in pb.itemlist pbresp id=3 offset=0 total=67 complete=true data=da0240523e08001009180c22320a1209010000000000000011020000000000000012110803120b4649545f545950455f34341807188010280b3880d6c28c0422021801
in pb.pull pbresp id=4 offset=0 total=22 complete=true data=da021312110800100018984022062f662e6669742803
in pb.added pbreq id=5 offset=0 total=57 complete=true data=da023662340a320a1209010000000000000011020000000000000012110803120b4649545f545950455f34341807188010280b3880d6c28c04
in pb.sync pbreq id=6 offset=0 total=6 complete=true data=da0203b20100
in pb.chunked pbreq id=7 offset=10 total=40 complete=false data=010203
in pb.overlong unknown type=5043 payload=070000000000280000001e000000010203
in battery battery status=1 centivolts=2 percent=3
in battery.full battery status=16 centivolts=200 percent=87
in battery.short unknown type=5023 payload=0102
in unnamed unknown type=5777 payload=09
fa pb.itemlist itemList status=0 session=9 next=12 error=- items=[{uid=0000000000000001:0000000000000002 type=3/FIT_TYPE_44/7/- size=2048 flags=- txn=11 urg=- mtime=1100000000},{uid=- type=- size=1 flags=- txn=- urg=- mtime=-}]
fa pb.pull pull result=0 transport=0 handle=8216 path=/f.fit error=-2
fa pb.added itemAdded [{uid=0000000000000001:0000000000000002 type=3/FIT_TYPE_44/7/- size=2048 flags=- txn=11 urg=- mtime=1100000000}]
fa pb.sync syncButton
fa pb.chunked nil
fa pb.notfileaccess nil
fa pb.otherservice other 13
fa pb.truncated nil
fa pb.group nil
fa pb.negerror pull result=4 transport=- handle=- path=- error=-2147483648
dir {idx=1 dt=128 st=4 num=0 sf=0 ff=0 size=0 ts=1000000 unix=632065600},{idx=2 dt=128 st=5 num=1 sf=0 ff=0 size=100 ts=0 unix=-},{idx=3 dt=128 st=6 num=2 sf=0 ff=0 size=200 ts=1000002 unix=632065602}
dir.bad nil 0
str.truncate len=279 namelen=254 fnv=c283a83b
"""
        // ORACLE-END
    }
}
