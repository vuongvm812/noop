import XCTest
import WhoopProtocol
import WhoopStore
@testable import StrandImport

/// The fold from one decoded Garmin wellness file to what the store keeps (`GarminWatchIngest`). The
/// dump below runs the fold over every wellness fixture; the Kotlin twin (`GarminWatchIngestTest`) runs
/// the same fixtures through its own fold and pins this exact text, so the two cannot drift.
final class GarminWatchIngestTests: XCTestCase {

    static func num(_ v: Double?) -> String { WellnessFixtures.num(v) }
    static func num(_ v: Int?) -> String { v.map(String.init) ?? "-" }

    static func dump(_ b: GarminFileIngest) -> [String] {
        var out: [String] = []
        let f = b.file
        out.append("file idx=\(f.fileIndex) dt=\(f.dataType) st=\(f.subType) num=\(f.fileNumber) ts=\(f.ts) size=\(f.size)")
        for s in b.hr { out.append("hr \(s.ts) \(s.bpm)") }
        for m in b.deviceMetric { out.append("metric \(m.ts) \(m.kind.rawValue) \(num(m.value)) \(m.source)") }
        for d in b.days {
            out.append("day \(d.day) \(d.dayStartTs)..\(d.dayEndTs) steps=\(num(d.steps)) dist=\(num(d.distanceM)) "
                       + "act=\(num(d.activeKcal)) tot=\(num(d.totalKcal)) rhr=\(num(d.restingHr)) hrv=\(num(d.avgHrvMs)) "
                       + "sleep=\(num(d.totalSleepMin)) deep=\(num(d.deepMin)) light=\(num(d.lightMin)) rem=\(num(d.remMin)) "
                       + "awake=\(num(d.awakeMin)) eff=\(num(d.efficiency)) score=\(num(d.sleepScore))")
            for p in d.activityPeaks {
                out.append("peak \(d.day) type=\(p.activityType) steps=\(num(p.steps)) dist=\(num(p.distanceM)) "
                           + "tot=\(num(p.totalKcal)) act=\(num(p.activeKcal))")
            }
        }
        for s in b.sleeps {
            out.append("sleep \(s.startTs)-\(s.endTs) eff=\(num(s.efficiency)) hrv=\(num(s.avgHrv)) stages=\(s.stagesJSON ?? "-")")
        }
        for d in b.nightRefreshDays { out.append("refresh \(d.day) \(d.dayStartTs)..\(d.dayEndTs)") }
        return out
    }

    /// Every wellness fixture folded (file row from its position, fallback offset +1 h), then the day
    /// helpers at their edges.
    static func oracleOutput() -> String {
        var out: [String] = []
        for (i, (name, build)) in WellnessFixtures.all.enumerated() {
            out.append("== \(name)")
            let row = GarminSyncedFileRow(fileIndex: i + 1, dataType: 128, subType: 32, fileNumber: 100 + i,
                                          ts: 1_100_000_000 + i, size: 1_000 + i)
            out += dump(GarminWatchIngest.fold(GarminWellnessParser.parse(data: build()), file: row,
                                               fallbackUtcOffsetS: 3_600))
        }
        out.append("== dayStart")
        for (day, off) in [("1970-01-01", 0), ("2024-02-29", -18_000), ("2026-11-01", 25_200), ("2000-03-01", 50_400), ("bad", 0)] {
            out.append("\(day)@\(off) \(GarminWatchIngest.dayStartUnix(day, utcOffsetS: off))")
        }
        out.append("== efficiency")
        for (asleep, awake) in [(Int64(0), Int64(0)), (3_600, 0), (25_200, 1_800), (1, 2)] {
            out.append("\(asleep)/\(awake) \(num(GarminWatchIngest.efficiency(asleep: asleep, awake: awake)))")
        }
        return out.joined(separator: "\n")
    }

    func testOracleOutputIsPinned() {
        XCTAssertEqual(Self.oracleOutput(), Self.expected)
    }

    func testDayStartRoundTripsWithLocalDay() {
        for ts: Int64 in [0, 86_399, 951_782_400, 1_793_538_660, 4_102_444_799] {
            for off in [-43_200, -18_000, 0, 25_200, 50_400] {
                let day = GarminWellnessParser.localDay(ts, utcOffsetS: off)
                let start = GarminWatchIngest.dayStartUnix(day, utcOffsetS: off)
                XCTAssertLessThanOrEqual(Int64(start), ts)
                XCTAssertLessThan(ts, Int64(start) + 86_400)
                XCTAssertEqual(GarminWellnessParser.localDay(Int64(start), utcOffsetS: off), day)
            }
        }
    }

    func testSleepFixtureBecomesASessionWithStagesAndADayRollup() throws {
        let row = GarminSyncedFileRow(fileIndex: 1, dataType: 128, subType: 49, fileNumber: 1, ts: 0, size: 0)
        let batch = GarminWatchIngest.fold(GarminWellnessParser.parse(data: WellnessFixtures.sleep()), file: row,
                                           fallbackUtcOffsetS: 0)
        let night = try XCTUnwrap(batch.sleeps.first)
        XCTAssertGreaterThan(night.endTs, night.startTs)
        XCTAssertNotNil(night.stagesJSON)
        let json = try XCTUnwrap(night.stagesJSON?.data(using: .utf8))
        let segments = try XCTUnwrap(try JSONSerialization.jsonObject(with: json) as? [[String: Any]])
        XCTAssertFalse(segments.isEmpty)
        XCTAssertTrue(segments.allSatisfy { ["wake", "light", "deep", "rem"].contains($0["stage"] as? String ?? "") })
        XCTAssertTrue(batch.days.contains { $0.totalSleepMin != nil })
    }

    /// #47: a night shaped like the real Forerunner 165 one (rows only at stage changes, 66 and 138 minute
    /// single-stage stretches, the first row 17 minutes after onset) folds into ONE session over the
    /// watch's own window, with the asleep total the watch reports (7 h 50 min).
    func testARealShapedNightIsOneSessionOverTheWatchWindow() throws {
        let row = GarminSyncedFileRow(fileIndex: 1, dataType: 255, subType: 49, fileNumber: 1, ts: 0, size: 0)
        let batch = GarminWatchIngest.fold(GarminWellnessParser.parse(data: WellnessFixtures.sleepWindow()), file: row,
                                           fallbackUtcOffsetS: 25_200)
        XCTAssertEqual(batch.sleeps.count, 1)
        let night = try XCTUnwrap(batch.sleeps.first)
        let onset = Int(WellnessFixtures.t + GarminWellnessParser.fitEpochOffset)
        XCTAssertEqual(night.startTs, onset)
        XCTAssertEqual(night.endTs, onset + 29_623)
        let asleep = try XCTUnwrap(batch.days.first?.totalSleepMin)
        XCTAssertEqual(asleep, 470.7, accuracy: 0.1)
        XCTAssertEqual(batch.days.first?.awakeMin, 23)
    }

    /// #49: efficiency is the 0-1 fraction every other source stores, not a percent.
    func testEfficiencyIsAFraction() throws {
        let row = GarminSyncedFileRow(fileIndex: 1, dataType: 255, subType: 49, fileNumber: 1, ts: 0, size: 0)
        let batch = GarminWatchIngest.fold(GarminWellnessParser.parse(data: WellnessFixtures.sleepWindow()), file: row,
                                           fallbackUtcOffsetS: 25_200)
        let stored = try XCTUnwrap(batch.sleeps.first?.efficiency)
        XCTAssertEqual(stored, 0.953)
        XCTAssertEqual(batch.days.first?.efficiency, 0.953)
        XCTAssertLessThanOrEqual(stored, 1)
    }

    /// #49: a nap ending on the same day as the night is stored as its own session, but the day's sleep
    /// figures stay the night's (the nap is the later session and used to overwrite them).
    func testANapDoesNotReplaceTheNightsDayTotals() throws {
        let row = GarminSyncedFileRow(fileIndex: 1, dataType: 255, subType: 49, fileNumber: 1, ts: 0, size: 0)
        let batch = GarminWatchIngest.fold(GarminWellnessParser.parse(data: WellnessFixtures.sleepNap()), file: row,
                                           fallbackUtcOffsetS: 3_600)
        XCTAssertEqual(batch.sleeps.count, 2)
        let nights = batch.days.filter { $0.totalSleepMin != nil }
        XCTAssertEqual(nights.count, 1)
        let d = try XCTUnwrap(nights.first)
        XCTAssertEqual(d.totalSleepMin, 390)
        XCTAssertEqual(d.deepMin, 60)
        XCTAssertEqual(d.remMin, 120)
        XCTAssertEqual(d.awakeMin, 30)
        XCTAssertEqual(d.efficiency, 0.929)
    }

    /// #49: unmeasurable (level 0) time is in neither the stages nor the efficiency, and it leaves a hole
    /// in the stored timeline, so the shared coverage gate (`HypnogramCoverage`, which raises the Sleep
    /// tab's "Partly recorded" caption and keeps Rest off SOLID) sees the night as partly recorded.
    func testUnmeasurableTimeReadsAsAPartlyRecordedNight() throws {
        let row = GarminSyncedFileRow(fileIndex: 1, dataType: 255, subType: 49, fileNumber: 1, ts: 0, size: 0)
        let batch = GarminWatchIngest.fold(GarminWellnessParser.parse(data: WellnessFixtures.sleepNap()), file: row,
                                           fallbackUtcOffsetS: 3_600)
        let night = try XCTUnwrap(batch.sleeps.first)
        let coverage = try XCTUnwrap(HypnogramCoverage.groupFraction([night]))
        XCTAssertEqual(coverage, 25_200.0 / 27_000.0, accuracy: 1e-12)
        XCTAssertTrue(HypnogramCoverage.isHoledGroup([night]))
        let full = try XCTUnwrap(GarminWatchIngest.fold(GarminWellnessParser.parse(data: WellnessFixtures.sleepWindow()),
                                                        file: row, fallbackUtcOffsetS: 0).sleeps.first)
        XCTAssertFalse(HypnogramCoverage.isHoledGroup([full]), "a fully measured night is not flagged")
    }

    /// #49 verification item: the fold against Garmin's own per-night totals. `sleepData` below has the
    /// shape of a Garmin Connect GDPR export entry (`*_sleepData.json`; synthetic numbers, no real
    /// export is committed) for the `sleepWindow` night, and goes through the export importer. The fold's
    /// rule, each SLEEP_LEVEL row closes the span before it, must give the same stage seconds and window.
    /// The opposite rule (each row opens its level until the next row) is computed alongside and must NOT
    /// match, so the test tells the two apart when real totals are pasted in.
    func testTheFoldMatchesGarminsExportTotals() throws {
        let fitStart = WellnessFixtures.t + GarminWellnessParser.fitEpochOffset
        let sleepData = """
            [{"calendarDate":"2024-11-09","sleepStartTimestampGMT":\(fitStart * 1000),
              "sleepEndTimestampGMT":\((fitStart + 29_623) * 1000),"deepSleepSeconds":6420,
              "lightSleepSeconds":15900,"remSleepSeconds":5923,"awakeSleepSeconds":1380,"unmeasurableSeconds":0}]
            """
        let export = try XCTUnwrap(GarminExportParser.parse(["x_sleepData.json": Data(sleepData.utf8)]).sleeps.first)
        let samples = GarminWellnessParser.parse(data: WellnessFixtures.sleepWindow())
        let night = try XCTUnwrap(samples.sleepSessions.first)
        XCTAssertEqual(Int64(export.start.timeIntervalSince1970), night.start)
        XCTAssertEqual(Int64(export.end.timeIntervalSince1970), night.end)
        XCTAssertEqual(export.deepMin, Double(night.deepS) / 60)
        XCTAssertEqual(export.lightMin, Double(night.lightS) / 60)
        XCTAssertEqual(export.remMin, Double(night.remS) / 60)
        XCTAssertEqual(export.awakeMin, Double(night.awakeS) / 60)
        XCTAssertEqual(night.unmeasurableS, 0)

        // Opposite rule: a row's level runs from its own time to the next row (the last to the stop).
        var opens = [Int64](repeating: 0, count: 5)
        let rows = samples.sleepLevels
        for (i, r) in rows.enumerated() {
            let next = i + 1 < rows.count ? rows[i + 1].ts : night.end
            opens[r.value] += next - r.ts
        }
        XCTAssertNotEqual(Double(opens[3]) / 60, export.deepMin)
    }

    func testCandidateMessagesAreNotMapped() {
        let row = GarminSyncedFileRow(fileIndex: 1, dataType: 128, subType: 73, fileNumber: 1, ts: 0, size: 0)
        let batch = GarminWatchIngest.fold(GarminWellnessParser.parse(data: WellnessFixtures.candidates()), file: row,
                                           fallbackUtcOffsetS: 0)
        XCTAssertTrue(batch.hr.isEmpty)
        XCTAssertTrue(batch.deviceMetric.isEmpty, "beat intervals and skin temperature stay unmapped candidates")
    }

    /// Oracle stdout, pasted verbatim. Also pinned in the Kotlin test.
    static let expected = """
        == monitoring
        file idx=1 dt=128 st=32 num=100 ts=1100000000 size=1000
        hr 1731087296 61
        hr 1731087392 90
        hr 1731087424 70
        hr 1731090896 58
        metric 1731087392 intensity_moderate_min 5.0000 fit55
        metric 1731087392 intensity_vigorous_min 2.0000 fit55
        day 2024-11-08 1731002400..1731088800 steps=1600 dist=800.0000 act=120.0000 tot=3510.0000 rhr=- hrv=- sleep=- deep=- light=- rem=- awake=- eff=- score=-
        peak 2024-11-08 type=1 steps=400 dist=- tot=1750.0000 act=-
        peak 2024-11-08 type=6 steps=1200 dist=800.0000 tot=1760.0000 act=120.0000
        day 2024-11-09 1731088800..1731175200 steps=50 dist=- act=- tot=10.0000 rhr=- hrv=- sleep=- deep=- light=- rem=- awake=- eff=- score=-
        peak 2024-11-09 type=6 steps=50 dist=- tot=10.0000 act=-
        refresh 2024-11-10 1731175200..1731261600
        == midnight
        file idx=2 dt=128 st=32 num=101 ts=1100000001 size=1001
        day 2024-11-08 1731002400..1731088800 steps=16100 dist=12100.0000 act=790.0000 tot=- rhr=- hrv=- sleep=- deep=- light=- rem=- awake=- eff=- score=-
        peak 2024-11-08 type=1 steps=12500 dist=9600.0000 tot=- act=640.0000
        peak 2024-11-08 type=6 steps=3600 dist=2500.0000 tot=- act=150.0000
        day 2024-11-09 1731088800..1731175200 steps=31 dist=24.0000 act=1.0000 tot=- rhr=- hrv=- sleep=- deep=- light=- rem=- awake=- eff=- score=-
        peak 2024-11-09 type=6 steps=31 dist=24.0000 tot=- act=1.0000
        refresh 2024-11-10 1731175200..1731261600
        == multiSport
        file idx=3 dt=128 st=32 num=102 ts=1100000002 size=1002
        day 2024-11-08 1731002400..1731088800 steps=2000 dist=14500.0000 act=480.0000 tot=1920.0000 rhr=- hrv=- sleep=- deep=- light=- rem=- awake=- eff=- score=-
        peak 2024-11-08 type=2 steps=- dist=12000.0000 tot=300.0000 act=280.0000
        peak 2024-11-08 type=5 steps=- dist=1000.0000 tot=120.0000 act=110.0000
        peak 2024-11-08 type=6 steps=2000 dist=1500.0000 tot=1500.0000 act=90.0000
        refresh 2024-11-09 1731088800..1731175200
        == restingHeartRate
        file idx=4 dt=128 st=32 num=103 ts=1100000003 size=1003
        day 2024-11-08 1731020400..1731106800 steps=- dist=- act=- tot=- rhr=52 hrv=- sleep=- deep=- light=- rem=- awake=- eff=- score=-
        day 2024-11-09 1731106800..1731193200 steps=- dist=- act=- tot=- rhr=57 hrv=- sleep=- deep=- light=- rem=- awake=- eff=- score=-
        refresh 2024-11-10 1731193200..1731279600
        == stress
        file idx=5 dt=128 st=32 num=104 ts=1100000004 size=1004
        metric 1731087296 body_battery 80.0000 fit227
        metric 1731087296 stress 25.0000 fit227
        metric 1731087476 body_battery 79.0000 fit227
        metric 1731087836 body_battery 55.0000 fit227
        metric 1731087836 hrv_ms 32.0000 fit371
        day 2024-11-08 1731020400..1731106800 steps=- dist=- act=- tot=- rhr=- hrv=- sleep=- deep=- light=- rem=- awake=- eff=- score=-
        refresh 2024-11-09 1731106800..1731193200
        == spo2
        file idx=6 dt=128 st=32 num=105 ts=1100000005 size=1005
        metric 1731087296 spo2_pct 96.0000 fit269
        day 2024-11-08 1731020400..1731106800 steps=- dist=- act=- tot=- rhr=- hrv=- sleep=- deep=- light=- rem=- awake=- eff=- score=-
        refresh 2024-11-09 1731106800..1731193200
        == sleep
        file idx=7 dt=128 st=32 num=106 ts=1100000006 size=1006
        metric 1731107296 sleep_score 82.0000 fit346
        day 2024-11-09 1731088800..1731175200 steps=- dist=- act=- tot=- rhr=- hrv=- sleep=28.0000 deep=10.0000 light=8.0000 rem=10.0000 awake=10.0000 eff=0.7370 score=82
        sleep 1731087416-1731089696 eff=0.7370 hrv=- stages=[{"start":1731087416,"end":1731087896,"stage":"light"},{"start":1731087896,"end":1731088496,"stage":"deep"},{"start":1731088496,"end":1731089096,"stage":"rem"},{"start":1731089096,"end":1731089696,"stage":"wake"}]
        refresh 2024-11-10 1731175200..1731261600
        == sleepWindow
        file idx=8 dt=128 st=32 num=107 ts=1100000007 size=1007
        day 2024-11-09 1731106800..1731193200 steps=- dist=- act=- tot=- rhr=- hrv=- sleep=bits:407d6b7777777777 deep=107.0000 light=265.0000 rem=bits:4058adddddddddde awake=23.0000 eff=0.9530 score=-
        sleep 1731087296-1731116919 eff=0.9530 hrv=- stages=[{"start":1731087296,"end":1731088316,"stage":"light"},{"start":1731088316,"end":1731090776,"stage":"deep"},{"start":1731090776,"end":1731090836,"stage":"wake"},{"start":1731090836,"end":1731093296,"stage":"light"},{"start":1731093296,"end":1731097256,"stage":"deep"},{"start":1731097256,"end":1731097736,"stage":"light"},{"start":1731097736,"end":1731098636,"stage":"rem"},{"start":1731098636,"end":1731106916,"stage":"light"},{"start":1731106916,"end":1731106976,"stage":"wake"},{"start":1731106976,"end":1731108056,"stage":"light"},{"start":1731108056,"end":1731109316,"stage":"wake"},{"start":1731109316,"end":1731110816,"stage":"light"},{"start":1731110816,"end":1731112376,"stage":"rem"},{"start":1731112376,"end":1731113456,"stage":"light"},{"start":1731113456,"end":1731116919,"stage":"rem"}]
        refresh 2024-11-10 1731193200..1731279600
        == sleepNap
        file idx=9 dt=128 st=32 num=108 ts=1100000008 size=1008
        day 2024-11-09 1731106800..1731193200 steps=- dist=- act=- tot=- rhr=- hrv=- sleep=390.0000 deep=60.0000 light=210.0000 rem=120.0000 awake=30.0000 eff=0.9290 score=-
        sleep 1731087296-1731114296 eff=0.9290 hrv=- stages=[{"start":1731087296,"end":1731089096,"stage":"light"},{"start":1731089096,"end":1731092696,"stage":"deep"},{"start":1731094496,"end":1731101696,"stage":"rem"},{"start":1731101696,"end":1731103496,"stage":"wake"},{"start":1731103496,"end":1731114296,"stage":"light"}]
        sleep 1731137296-1731139696 eff=0.5000 hrv=- stages=[{"start":1731137296,"end":1731138496,"stage":"light"},{"start":1731138496,"end":1731139696,"stage":"wake"}]
        refresh 2024-11-10 1731193200..1731279600
        == respiration
        file idx=10 dt=128 st=32 num=109 ts=1100000009 size=1009
        metric 1731087296 respiration_bpm 14.5000 fit297
        day 2024-11-08 1731020400..1731106800 steps=- dist=- act=- tot=- rhr=- hrv=- sleep=- deep=- light=- rem=- awake=- eff=- score=-
        refresh 2024-11-09 1731106800..1731193200
        == hrv
        file idx=11 dt=128 st=32 num=110 ts=1100000010 size=1010
        metric 1731087596 hrv_ms 39.0625 fit371
        day 2024-11-08 1731020400..1731106800 steps=- dist=- act=- tot=- rhr=- hrv=37.5000 sleep=- deep=- light=- rem=- awake=- eff=- score=-
        refresh 2024-11-09 1731106800..1731193200
        == candidates
        file idx=12 dt=128 st=32 num=111 ts=1100000011 size=1011
        == framing
        file idx=13 dt=128 st=32 num=112 ts=1100000012 size=1012
        metric 1731087296 hrv_ms 40.0000 fit371
        metric 1731087296 respiration_bpm 15.0000 fit297
        metric 1731087306 respiration_bpm 15.2500 fit297
        metric 1731087331 respiration_bpm 15.5000 fit297
        day 2024-11-08 1731020400..1731106800 steps=- dist=- act=- tot=- rhr=50 hrv=- sleep=- deep=- light=- rem=- awake=- eff=- score=-
        refresh 2024-11-09 1731106800..1731193200
        == bounds
        file idx=14 dt=128 st=32 num=113 ts=1100000013 size=1013
        metric 1731087316 spo2_pct 70.0000 fit269
        metric 1731087326 spo2_pct 100.0000 fit269
        metric 1731087346 respiration_bpm 4.0000 fit297
        metric 1731087356 respiration_bpm 60.0000 fit297
        metric 1731087386 hrv_ms 5.0000 fit371
        metric 1731087396 hrv_ms 300.0000 fit371
        day 2024-11-08 1731002400..1731088800 steps=- dist=- act=- tot=- rhr=48 hrv=- sleep=- deep=- light=- rem=- awake=- eff=- score=-
        day 2024-11-09 1731088800..1731175200 steps=- dist=- act=- tot=- rhr=47 hrv=- sleep=- deep=- light=- rem=- awake=- eff=- score=-
        refresh 2024-11-10 1731175200..1731261600
        == dayStart
        1970-01-01@0 0
        2024-02-29@-18000 1709182800
        2026-11-01@25200 1793466000
        2000-03-01@50400 951818400
        bad@0 0
        == efficiency
        0/0 -
        3600/0 1.0000
        25200/1800 0.9330
        1/2 0.3330
        """
}
