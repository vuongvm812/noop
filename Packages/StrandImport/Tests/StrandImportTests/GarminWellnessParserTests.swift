import XCTest
@testable import StrandImport

/// Garmin wellness FIT decoding. The fixtures and the canonical dump live in
/// `GarminWellnessFixtures.swift`, which also builds standalone as the parity oracle; the Kotlin twin
/// `GarminWellnessParserTest` pins the same literal.
final class GarminWellnessParserTests: XCTestCase {

    /// Oracle stdout, pasted verbatim. Also pinned in the Kotlin test.
    static let expected = """
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
        spo2 1731087296 96
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
        == bounds
        offset 21600 complete true other 0 dropped 5
        rhr 1731088800 48
        rhr 1731088801 47
        spo2 1731087316 70
        spo2 1731087326 100
        resp 1731087346 4.0000
        resp 1731087356 60.0000
        hrv 1731087386 5.0000
        hrv 1731087396 300.0000
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
        """

    func testOracleOutputIsPinned() {
        XCTAssertEqual(WellnessFixtures.oracleOutput(), Self.expected)
    }

    func testMidnightRecordCountsTowardTheEndedDay() {
        let s = GarminWellnessParser.parse(data: WellnessFixtures.midnight())
        XCTAssertEqual(s.dailyActivity.map(\.day), ["2024-11-08", "2024-11-09"])
        XCTAssertEqual(s.dailyActivity.map(\.steps), [16_100, 31])
        XCTAssertEqual(s.dailyActivity.map(\.activeCalories), [790, 1])
        // A file holding only the 00:00:00 records (as the watch's last file of a day can) still
        // credits them to the day that ended, never to the new one.
        let midnightOnly = s.monitoring.filter { ($0.ts + 21_600) % 86_400 == 0 }
        XCTAssertEqual(midnightOnly.count, 2)
        let days = GarminWellnessParser.dailyTotals(midnightOnly, utcOffsetS: 21_600)
        XCTAssertEqual(days.map(\.day), ["2024-11-08"])
        XCTAssertEqual(days.map(\.steps), [16_100])
    }

    func testCandidateMessagesAreFlagged() {
        XCTAssertEqual(GarminWellnessParser.candidateMessages, [290, 397, 398])
    }

    func testNotAFitFileIsEmptyAndIncomplete() {
        for data in [Data(), Data("hello, not a fit file".utf8), Data([12, 0, 0, 0, 0, 0, 0, 0, 0x2E, 0x46, 0x49, 0x54])] {
            let s = GarminWellnessParser.parse(data: data)
            XCTAssertTrue(s.isEmpty)
            XCTAssertFalse(s.complete)
        }
    }

    func testEveryTruncationIsSafe() {
        for (name, build) in WellnessFixtures.all {
            let full = [UInt8](build())
            let complete = GarminWellnessParser.parse(data: Data(full))
            XCTAssertTrue(complete.complete, name)
            // The header keeps claiming the full size, so every cut is clamped, never over-read.
            for cut in 0..<(full.count - 2) {
                let s = GarminWellnessParser.parse(data: Data(full[0..<cut]))
                XCTAssertLessThanOrEqual(s.monitoring.count + s.sleepLevels.count + s.sleepRaw.count,
                                         complete.monitoring.count + complete.sleepLevels.count + complete.sleepRaw.count, name)
            }
        }
    }

    func testTruncatedDefinitionStops() {
        var f = WellnessFit()
        f.raw([0x40, 0, 0, 0x37])            // a definition cut inside its global message number
        let s = GarminWellnessParser.parse(data: f.finish())
        XCTAssertFalse(s.complete)
        XCTAssertTrue(s.isEmpty)
    }

    func testHugeFieldCountsStopWithoutReading() {
        var f = WellnessFit()
        // 255 fields of 255 bytes each: a 65 KB record the file does not hold.
        f.def(0, 55, Array(repeating: (3, 255, WellnessFixtures.u32), count: 255))
        f.data(0)
        f.raw([1, 2, 3])
        let s = GarminWellnessParser.parse(data: f.finish())
        XCTAssertFalse(s.complete)
        XCTAssertTrue(s.monitoring.isEmpty)

        var g = WellnessFit()
        g.raw([0x60, 0, 0, 55, 0, 0, 255])   // 255 developer fields, none present
        XCTAssertFalse(GarminWellnessParser.parse(data: g.finish()).complete)
    }

    func testZeroSizeFieldAndUndefinedLocalTypeStop() {
        var f = WellnessFit()
        f.def(0, 371, [(253, 4, WellnessFixtures.u32), (0, 0, WellnessFixtures.u16)])
        XCTAssertFalse(GarminWellnessParser.parse(data: f.finish()).complete)

        var g = WellnessFit()
        g.data(5); g.u32(WellnessFixtures.t)
        XCTAssertFalse(GarminWellnessParser.parse(data: g.finish()).complete)
        var h = WellnessFit()
        h.compressed(3, offset: 1)
        XCTAssertFalse(GarminWellnessParser.parse(data: h.finish()).complete)
    }

    func testTimestamp16WithoutReferenceIsDropped() {
        var f = WellnessFit()
        f.def(0, 55, [(26, 2, WellnessFixtures.u16), (27, 1, WellnessFixtures.u8)])
        f.data(0); f.u16(0x1234); f.u8(60)
        let s = GarminWellnessParser.parse(data: f.finish())
        XCTAssertTrue(s.monitoring.isEmpty)
        XCTAssertEqual(s.droppedMessages, 1)
    }

    func testRecordCapStopsTheWalk() {
        let stream = FitRecordStream.open(bytes: [UInt8](WellnessFixtures.sleep()))!
        var seen = 0
        XCTAssertEqual(stream.walk(maxRecords: 3) { _, _, _ in seen += 1 }, .recordCap)
        XCTAssertEqual(seen, 1)              // one definition, one data message, then a definition
    }

    func testRandomBytesNeverCrash() {
        var seed: UInt64 = 0x9E37_79B9_7F4A_7C15
        func next() -> UInt8 {
            seed = seed &* 6_364_136_223_846_793_005 &+ 1_442_695_040_888_963_407
            return UInt8(truncatingIfNeeded: seed >> 33)
        }
        for _ in 0..<2000 {
            var f = WellnessFit()
            f.raw((0..<Int(next())).map { _ in next() })
            _ = GarminWellnessParser.parse(data: f.finish())
        }
        // Mutating real fixtures exercises deeper paths than pure noise.
        for (_, build) in WellnessFixtures.all {
            var bytes = [UInt8](build())
            for _ in 0..<300 {
                bytes[12 + Int(next()) % (bytes.count - 12)] = next()
                _ = GarminWellnessParser.parse(data: Data(bytes))
            }
        }
    }

    func testWellnessFileYieldsNoActivityButCarriesWellness() {
        // The activity decoder shares the record walk but must ignore health messages; a FIT file with
        // no workout hands its wellness data back instead.
        let r = ActivityFileImporter.parse(data: WellnessFixtures.monitoring(), filename: "monitor.fit")
        XCTAssertNil(r.activity)
        XCTAssertEqual(r.wellness, GarminWellnessParser.parse(data: WellnessFixtures.monitoring()))
        XCTAssertEqual(r.wellness?.monitoring.count, 4)
        // A workout keeps no wellness payload, and a non-FIT file never gets one.
        XCTAssertNil(ActivityFileImporter.parse(data: Data("<gpx></gpx>".utf8), filename: "a.gpx").wellness)
    }
}
