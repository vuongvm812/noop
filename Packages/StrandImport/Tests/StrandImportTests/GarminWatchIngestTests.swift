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
                       + "awake=\(num(d.awakeMin)) eff=\(num(d.efficiencyPct)) score=\(num(d.sleepScore))")
        }
        for s in b.sleeps {
            out.append("sleep \(s.startTs)-\(s.endTs) eff=\(num(s.efficiency)) hrv=\(num(s.avgHrv)) stages=\(s.stagesJSON ?? "-")")
        }
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
            out.append("\(asleep)/\(awake) \(num(GarminWatchIngest.efficiencyPct(asleep: asleep, awake: awake)))")
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
        day 2024-11-09 1731088800..1731175200 steps=50 dist=- act=- tot=10.0000 rhr=- hrv=- sleep=- deep=- light=- rem=- awake=- eff=- score=-
        == midnight
        file idx=2 dt=128 st=32 num=101 ts=1100000001 size=1001
        day 2024-11-08 1731002400..1731088800 steps=16100 dist=12100.0000 act=790.0000 tot=- rhr=- hrv=- sleep=- deep=- light=- rem=- awake=- eff=- score=-
        day 2024-11-09 1731088800..1731175200 steps=31 dist=24.0000 act=1.0000 tot=- rhr=- hrv=- sleep=- deep=- light=- rem=- awake=- eff=- score=-
        == restingHeartRate
        file idx=3 dt=128 st=32 num=102 ts=1100000002 size=1002
        day 2024-11-08 1731020400..1731106800 steps=- dist=- act=- tot=- rhr=52 hrv=- sleep=- deep=- light=- rem=- awake=- eff=- score=-
        day 2024-11-09 1731106800..1731193200 steps=- dist=- act=- tot=- rhr=57 hrv=- sleep=- deep=- light=- rem=- awake=- eff=- score=-
        == stress
        file idx=4 dt=128 st=32 num=103 ts=1100000003 size=1003
        metric 1731087296 body_battery 80.0000 fit227
        metric 1731087296 stress 25.0000 fit227
        metric 1731087476 body_battery 79.0000 fit227
        metric 1731087836 body_battery 55.0000 fit227
        metric 1731087836 hrv_ms 32.0000 fit371
        day 2024-11-08 1731020400..1731106800 steps=- dist=- act=- tot=- rhr=- hrv=- sleep=- deep=- light=- rem=- awake=- eff=- score=-
        == spo2
        file idx=5 dt=128 st=32 num=104 ts=1100000004 size=1004
        metric 1731087296 spo2_pct 96.0000 fit269
        metric 1731087416 spo2_pct 94.0000 fit269
        day 2024-11-08 1731020400..1731106800 steps=- dist=- act=- tot=- rhr=- hrv=- sleep=- deep=- light=- rem=- awake=- eff=- score=-
        == sleep
        file idx=6 dt=128 st=32 num=105 ts=1100000005 size=1005
        metric 1731107296 sleep_score 82.0000 fit346
        day 2024-11-09 1731088800..1731175200 steps=- dist=- act=- tot=- rhr=- hrv=- sleep=28.0000 deep=10.0000 light=8.0000 rem=10.0000 awake=10.0000 eff=73.7000 score=82
        sleep 1731087416-1731089696 eff=73.7000 hrv=- stages=[{"start":1731087416,"end":1731087896,"stage":"light"},{"start":1731087896,"end":1731088496,"stage":"deep"},{"start":1731088496,"end":1731089096,"stage":"rem"},{"start":1731089096,"end":1731089696,"stage":"wake"}]
        == sleepWindow
        file idx=7 dt=128 st=32 num=106 ts=1100000006 size=1006
        day 2024-11-09 1731106800..1731193200 steps=- dist=- act=- tot=- rhr=- hrv=- sleep=bits:407d6b7777777777 deep=107.0000 light=265.0000 rem=bits:4058adddddddddde awake=23.0000 eff=95.3000 score=-
        sleep 1731087296-1731116919 eff=95.3000 hrv=- stages=[{"start":1731087296,"end":1731088316,"stage":"light"},{"start":1731088316,"end":1731090776,"stage":"deep"},{"start":1731090776,"end":1731090836,"stage":"wake"},{"start":1731090836,"end":1731093296,"stage":"light"},{"start":1731093296,"end":1731097256,"stage":"deep"},{"start":1731097256,"end":1731097736,"stage":"light"},{"start":1731097736,"end":1731098636,"stage":"rem"},{"start":1731098636,"end":1731106916,"stage":"light"},{"start":1731106916,"end":1731106976,"stage":"wake"},{"start":1731106976,"end":1731108056,"stage":"light"},{"start":1731108056,"end":1731109316,"stage":"wake"},{"start":1731109316,"end":1731110816,"stage":"light"},{"start":1731110816,"end":1731112376,"stage":"rem"},{"start":1731112376,"end":1731113456,"stage":"light"},{"start":1731113456,"end":1731116919,"stage":"rem"}]
        == respiration
        file idx=8 dt=128 st=32 num=107 ts=1100000007 size=1007
        metric 1731087296 respiration_bpm 14.5000 fit297
        day 2024-11-08 1731020400..1731106800 steps=- dist=- act=- tot=- rhr=- hrv=- sleep=- deep=- light=- rem=- awake=- eff=- score=-
        == hrv
        file idx=9 dt=128 st=32 num=108 ts=1100000008 size=1008
        metric 1731087596 hrv_ms 39.0625 fit371
        day 2024-11-08 1731020400..1731106800 steps=- dist=- act=- tot=- rhr=- hrv=37.5000 sleep=- deep=- light=- rem=- awake=- eff=- score=-
        == candidates
        file idx=10 dt=128 st=32 num=109 ts=1100000009 size=1009
        == framing
        file idx=11 dt=128 st=32 num=110 ts=1100000010 size=1010
        metric 1731087296 hrv_ms 40.0000 fit371
        metric 1731087296 respiration_bpm 15.0000 fit297
        metric 1731087306 respiration_bpm 15.2500 fit297
        metric 1731087331 respiration_bpm 15.5000 fit297
        day 2024-11-08 1731020400..1731106800 steps=- dist=- act=- tot=- rhr=50 hrv=- sleep=- deep=- light=- rem=- awake=- eff=- score=-
        == dayStart
        1970-01-01@0 0
        2024-02-29@-18000 1709182800
        2026-11-01@25200 1793466000
        2000-03-01@50400 951818400
        bad@0 0
        == efficiency
        0/0 -
        3600/0 100.0000
        25200/1800 93.3000
        1/2 33.3000
        """
}
