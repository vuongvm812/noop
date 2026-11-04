import XCTest
@testable import StrandAnalytics
import WhoopStore

/// Which nights the Charge baselines read and how they are built (#2525, #53).
///
/// The defect these pin: the imported vendor history was folded in full while the wearer's own nights
/// entered only from the scan window, so the import kept about a third of the weight however long NOOP had
/// been worn. Byte-identical twin: Kotlin `ChargeBaselinesTest` (same cases, same oracle literal).
final class ChargeBaselinesTests: XCTestCase {

    private let hrvCfg = Baselines.hrvCfg
    private let rhrCfg = Baselines.restingHRCfg

    /// `yyyy-MM-dd` for a day count from 1970-01-01 (Howard Hinnant's civil-from-days), so the cases need no
    /// calendar or time zone. The Kotlin twin uses `LocalDate.ofEpochDay`, which yields the same keys.
    private static func dayKey(_ epochDay: Int) -> String {
        let z = epochDay + 719_468
        let era = (z >= 0 ? z : z - 146_096) / 146_097
        let doe = z - era * 146_097
        let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        let doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        let mp = (5 * doy + 2) / 153
        let d = doy - (153 * mp + 2) / 5 + 1
        let m = mp < 10 ? mp + 3 : mp - 9
        let y = yoe + era * 400 + (m <= 2 ? 1 : 0)
        return String(format: "%04d-%02d-%02d", y, m, d)
    }

    /// 2026-09-30 as a day count from 1970-01-01.
    private static let anchorEpochDay = 20_726

    /// `count` consecutive nights ending `endBack` days before the anchor, all at `value`.
    private static func run(_ count: Int, endBack: Int, value: Double?) -> [(day: String, value: Double?)] {
        (0..<count).map { i in (day: dayKey(anchorEpochDay - endBack - (count - 1 - i)), value: value) }
    }

    private static let anchor = dayKey(anchorEpochDay)
    static func key(_ d: Int) -> String { dayKey(d) }

    // MARK: - The window

    func testTheAnchorIsTheDayItClaims() {
        XCTAssertEqual(Self.anchor, "2026-09-30")
        XCTAssertEqual(Baselines.isoDay(fromEpochDay: Self.anchorEpochDay), Self.anchor)
        XCTAssertEqual(ChargeBaselines.anchorDay(forScoredDay: "2026-10-01"), Self.anchor)
        XCTAssertEqual(ChargeBaselines.anchorDay(forScoredDay: "2024-03-01"), "2024-02-29")
        XCTAssertNil(ChargeBaselines.anchorDay(forScoredDay: "nope"))
    }

    func testIsoDayRoundTripsAcrossCenturies() {
        for d in stride(from: -700_000, through: 800_000, by: 997) {
            XCTAssertEqual(Baselines.isoEpochDay(Baselines.isoDay(fromEpochDay: d)), d)
        }
    }

    /// The window holds the anchor and the 59 days before it; the 60th day back and any later day are out.
    func testTheWindowKeepsSixtyCalendarDaysEndingOnTheAnchor() {
        let own: [(day: String, value: Double?)] = [
            (day: Self.dayKey(Self.anchorEpochDay - 60), value: 50),
            (day: Self.dayKey(Self.anchorEpochDay - 59), value: 51),
            (day: Self.anchor, value: 52),
            (day: Self.dayKey(Self.anchorEpochDay + 1), value: 53),
        ]
        let h = ChargeBaselines.history(imported: [], own: own, anchorDay: Self.anchor, cfg: hrvCfg, baselineEpoch: 0)
        XCTAssertEqual(h.dayKeys, ["2026-08-02", "2026-09-30"])
        XCTAssertEqual(h.values, [51, 52])
    }

    /// An import that ended before the window has nothing left to seed with, however few own nights exist.
    func testAnImportOlderThanTheWindowDoesNotSeed() {
        let imported = Self.run(300, endBack: 61, value: 40)
        let own = Self.run(3, endBack: 0, value: 60)
        let h = ChargeBaselines.history(imported: imported, own: own, anchorDay: Self.anchor, cfg: hrvCfg,
                                        baselineEpoch: 0)
        XCTAssertFalse(h.seededByImport)
        XCTAssertEqual(h.values, [60, 60, 60])
        XCTAssertEqual(h.importedNights, 0)
    }

    /// #53 seed gap: an export that ended 40 days ago used to fall outside the 21-day window and seed
    /// nothing; inside the 60-day window it seeds a new wearer.
    func testAnExportFromSixWeeksAgoSeedsANewWearer() {
        let h = ChargeBaselines.history(imported: Self.run(30, endBack: 40, value: 45),
                                        own: Self.run(2, endBack: 0, value: 60),
                                        anchorDay: Self.anchor, cfg: hrvCfg, baselineEpoch: 0)
        XCTAssertTrue(h.seededByImport)
        XCTAssertEqual(h.importedNights, 20)
        XCTAssertTrue(ChargeBaselines.state(h, metric: .hrv, anchorDay: Self.anchor, baselineEpoch: 0).usable)
    }

    // MARK: - The handoff

    /// Thirteen valid own nights are not yet a trusted baseline, so the import still seeds; the fourteenth
    /// hands over and the import drops out entirely.
    func testImportSeedsUntilTheOwnNightsAloneAreTrusted() {
        let imported = Self.run(7, endBack: 14, value: 40)
        let seeding = ChargeBaselines.history(imported: imported, own: Self.run(13, endBack: 0, value: 60),
                                              anchorDay: Self.anchor, cfg: hrvCfg, baselineEpoch: 0)
        XCTAssertTrue(seeding.seededByImport)
        XCTAssertEqual(seeding.ownValidNights, 13)
        XCTAssertEqual(seeding.importedNights, 7)
        XCTAssertEqual(seeding.dayKeys.count, 20)

        let handedOff = ChargeBaselines.history(imported: imported, own: Self.run(14, endBack: 0, value: 60),
                                                anchorDay: Self.anchor, cfg: hrvCfg, baselineEpoch: 0)
        XCTAssertFalse(handedOff.seededByImport)
        XCTAssertEqual(handedOff.ownValidNights, 14)
        XCTAssertEqual(handedOff.importedNights, 0)
        XCTAssertEqual(handedOff.values, Array(repeating: 60, count: 14))
    }

    /// Own nights without a value exist but are not valid, so they do not bring the handoff forward.
    func testOwnNightsWithoutAValueDoNotCountTowardTheHandoff() {
        let own = Self.run(13, endBack: 1, value: 60) + Self.run(1, endBack: 0, value: nil)
        let h = ChargeBaselines.history(imported: Self.run(6, endBack: 14, value: 40), own: own,
                                        anchorDay: Self.anchor, cfg: hrvCfg, baselineEpoch: 0)
        XCTAssertTrue(h.seededByImport)
        XCTAssertEqual(h.ownValidNights, 13)
    }

    /// Own nights before the recalibration epoch are dropped by the fold, so they must not count here either.
    func testOwnNightsBeforeTheEpochDoNotCountTowardTheHandoff() {
        let own = Self.run(30, endBack: 0, value: 60)
        let epoch = Double(Self.anchorEpochDay - 9) * 86_400   // the last ten own nights survive
        let h = ChargeBaselines.history(imported: Self.run(3, endBack: 18, value: 40), own: own,
                                        anchorDay: Self.anchor, cfg: hrvCfg, baselineEpoch: epoch)
        XCTAssertEqual(h.ownValidNights, 10)
        XCTAssertTrue(h.seededByImport)
    }

    // MARK: - Seeding precedence (carried over from the pre-#2525 `mergeNightlyIntoHistory` pins)

    private func seed(imported: [(day: String, value: Double?)],
                      own: [(day: String, value: Double?)]) -> ChargeBaselines.History {
        ChargeBaselines.history(imported: imported, own: own, anchorDay: Self.anchor, cfg: hrvCfg, baselineEpoch: 0)
    }

    /// An imported value wins a day the own nights also cover (import users keep their seed).
    func testWhileSeedingAnImportedValueWinsASharedDay() {
        let h = seed(imported: [(day: Self.anchor, value: 62)], own: [(day: Self.anchor, value: 48)])
        XCTAssertTrue(h.seededByImport)
        XCTAssertEqual(h.values, [62])
    }

    /// An own night fills a day the import does not cover.
    func testWhileSeedingAnOwnNightFillsADayTheImportLacks() {
        let yesterday = Self.dayKey(Self.anchorEpochDay - 1)
        let h = seed(imported: [(day: yesterday, value: 62)], own: [(day: Self.anchor, value: 48)])
        XCTAssertEqual(h.dayKeys, [yesterday, Self.anchor])
        XCTAssertEqual(h.values, [62, 48])
    }

    /// A blank imported row must not shadow a night the strap measured, or an import whose rows are blank
    /// for a metric blankets every strap night and the baseline never seeds ("Needs the strap").
    func testWhileSeedingABlankImportedRowIsFilledByTheOwnNight() {
        let h = seed(imported: [(day: Self.anchor, value: nil)], own: [(day: Self.anchor, value: 48)])
        XCTAssertEqual(h.values, [48])
    }

    /// A blank imported row with no own night stays a missing night (an honest gap).
    func testWhileSeedingABlankImportedRowWithNoOwnNightStaysMissing() {
        let yesterday = Self.dayKey(Self.anchorEpochDay - 1)
        let h = seed(imported: [(day: yesterday, value: nil), (day: Self.anchor, value: 62)], own: [])
        XCTAssertTrue(h.seededByImport)
        XCTAssertEqual(h.dayKeys, [yesterday, Self.anchor])
        XCTAssertEqual(h.values, [nil, 62])
    }

    /// An own night without a value neither overwrites an imported value nor removes a blank imported day.
    func testWhileSeedingABlankOwnNightDisturbsNothing() {
        let d1 = Self.dayKey(Self.anchorEpochDay - 2), d2 = Self.dayKey(Self.anchorEpochDay - 1)
        let h = seed(imported: [(day: d1, value: 62), (day: d2, value: nil)],
                     own: [(day: d1, value: nil), (day: d2, value: nil), (day: Self.anchor, value: nil)])
        XCTAssertEqual(h.dayKeys, [d1, d2, Self.anchor])
        XCTAssertEqual(h.values, [62, nil, nil])
    }

    /// The starvation report's shape: a week of imported rows, all blank for HRV, over nights the strap
    /// scored. The seven measured nights must reach the fold so the baseline can seed.
    func testWhileSeedingAWeekOfBlankImportedRowsStillSeedsFromTheStrap() {
        let imported = Self.run(7, endBack: 0, value: nil)
        let own = (0..<7).map { i in (day: Self.dayKey(Self.anchorEpochDay - 6 + i), value: Optional(50.0 + Double(i))) }
        let h = seed(imported: imported, own: own)
        XCTAssertEqual(h.values.compactMap { $0 }.count, 7)
        let st = ChargeBaselines.state(h, metric: .hrv, anchorDay: Self.anchor, baselineEpoch: 0)
        XCTAssertGreaterThanOrEqual(st.nValid, Baselines.minNightsSeed)
    }

    /// Nothing that cannot be placed on the calendar is folded.
    func testUnparseableKeysAreDroppedAndAnUnparseableAnchorYieldsNothing() {
        let own: [(day: String, value: Double?)] = [(day: "garbage", value: 50), (day: Self.anchor, value: 51)]
        let h = ChargeBaselines.history(imported: [], own: own, anchorDay: Self.anchor, cfg: hrvCfg, baselineEpoch: 0)
        XCTAssertEqual(h.dayKeys, [Self.anchor])
        let none = ChargeBaselines.history(imported: [], own: own, anchorDay: "not-a-day", cfg: hrvCfg,
                                           baselineEpoch: 0)
        XCTAssertEqual(none.dayKeys, [])
        XCTAssertFalse(none.seededByImport)
    }

    // MARK: - State (#53)

    private func state(_ values: [Double?], metric: ChargeBaselines.Metric = .hrv,
                       epoch: Double = 0) -> BaselineState {
        let keys = (0..<values.count).map { Self.dayKey(Self.anchorEpochDay - values.count + 1 + $0) }
        return ChargeBaselines.state(values: values, dayKeys: keys, metric: metric, anchorDay: Self.anchor,
                                     baselineEpoch: epoch)
    }

    /// HRV is the mean and sample SD of ln RMSSD; the baseline reads back as the geometric mean in ms.
    func testHrvIsAGeometricMeanWithALogSD() {
        let s = state([40, 50, 80, nil, 300])   // nil missing, 300 out of bounds
        XCTAssertEqual(s.nValid, 3)
        XCTAssertTrue(s.logDomain)
        let ln = [log(40.0), log(50.0), log(80.0)]
        let m = ln.reduce(0, +) / 3
        let sd = (ln.map { ($0 - m) * ($0 - m) }.reduce(0, +) / 2).squareRoot()
        XCTAssertEqual(s.baseline, exp(m), accuracy: 1e-9)
        XCTAssertEqual(1.253 * s.spread, sd, accuracy: 1e-12)
        XCTAssertEqual(s.status, .calibrating)
        XCTAssertEqual(s.nightsSinceUpdate, 2, "newest valid night is two days before the anchor")
    }

    /// Every night weighs the same: reversing the order of the window changes nothing (the EWMA put 42% of
    /// the weight on the oldest seven of 21 nights and 29% on the newest seven).
    func testEveryNightWeighsTheSame() {
        let vals: [Double?] = (0..<21).map { 40 + Double(($0 * 11) % 23) }
        let a = state(vals), b = state(vals.reversed())
        XCTAssertEqual(a.baseline, b.baseline, accuracy: 1e-9)
        XCTAssertEqual(a.spread, b.spread, accuracy: 1e-12)
    }

    /// The SD is the window's own: a wide wearer reads wide and a flat one is floored at 0.05 ln.
    func testSpreadIsTheSampleSDFlooredPerMetric() {
        let flat = state(Array(repeating: 60.0, count: 20))
        XCTAssertEqual(1.253 * flat.spread, ChargeBaselines.hrvSigmaFloorLn, accuracy: 1e-12)
        XCTAssertEqual(flat.status, .trusted)
        let rhr = state(Array(repeating: 55.0, count: 20), metric: .restingHR)
        XCTAssertFalse(rhr.logDomain)
        XCTAssertEqual(1.253 * rhr.spread, Baselines.restingHRCfg.floorSpread, accuracy: 1e-12)
        let wide = state((0..<30).map { $0 % 2 == 0 ? 30.0 : 120.0 })
        XCTAssertEqual(1.253 * wide.spread, log(4.0) / 2 * (30.0 / 29.0).squareRoot(), accuracy: 1e-9)
    }

    /// Nights before the epoch are dropped; an empty window is calibrating and never usable.
    func testEpochDropsEarlierNightsAndEmptyIsCalibrating() {
        let epoch = Double(Self.anchorEpochDay - 2) * 86_400
        XCTAssertEqual(state(Array(repeating: 50.0, count: 10), epoch: epoch).nValid, 3)
        let empty = state([nil, nil])
        XCTAssertEqual(empty.nValid, 0)
        XCTAssertFalse(empty.usable)
    }

    /// A wearer back after more than `staleDays` reads stale (unusable) until a new night lands.
    func testALongGapReadsStale() {
        let s = state(Array(repeating: 50.0, count: 10) + Array(repeating: nil, count: 15))
        XCTAssertEqual(s.nightsSinceUpdate, 15)
        XCTAssertEqual(s.status, .stale)
    }

    // MARK: - Eras and per-day resolution (#53)

    private static func src(_ back: Int, _ id: String) -> ChargeBaselines.SourceDay {
        ChargeBaselines.SourceDay(day: dayKey(anchorEpochDay - back), sourceId: id)
    }

    /// A WHOOP -> Garmin switch: a WHOOP-era day keeps its full baseline; the first Garmin day has nothing
    /// before it in its era, so it cannot score; HRV/RHR cut, respiration cuts too, skin only on recalibration.
    func testEraCutIsDecidedPerScoredDay() {
        let sources = (0..<30).map { b in Self.src(b, b < 5 ? "garmin-AAA" : "my-whoop") }
        let whoopDay = Self.dayKey(Self.anchorEpochDay - 6)
        XCTAssertEqual(ChargeBaselines.epochs(sourceDays: sources, scoredDay: whoopDay, hrvRecalibration: 0,
                                              recoveryRecalibration: 0), .none)
        let firstGarmin = Self.dayKey(Self.anchorEpochDay - 4)
        let e = ChargeBaselines.epochs(sourceDays: sources, scoredDay: firstGarmin, hrvRecalibration: 0,
                                       recoveryRecalibration: 0)
        let cut = Double(Self.anchorEpochDay - 4) * 86_400
        XCTAssertEqual(e, ChargeBaselines.Epochs(hrv: cut, restingHR: cut, resp: cut, skin: 0))
        // An Oura switch cuts respiration only (#459's HRV/RHR scope is Garmin).
        let oura = (0..<30).map { b in Self.src(b, b < 5 ? "oura-import" : "my-whoop") }
        let eo = ChargeBaselines.epochs(sourceDays: oura, scoredDay: Self.anchor, hrvRecalibration: 7,
                                        recoveryRecalibration: 9)
        XCTAssertEqual(eo, ChargeBaselines.Epochs(hrv: 7, restingHR: 9, resp: cut, skin: 9))
    }

    func testSourceDaysPrecedenceOwnThenImportThenSeed() {
        let d = { (b: Int) in Self.dayKey(Self.anchorEpochDay - b) }
        let out = ChargeBaselines.sourceDays(
            own: [(day: d(0), sourceId: "garmin-AAA"), (day: d(1), sourceId: nil)], ownFallbackId: "my-whoop",
            importedDays: [d(1), d(2)], importedSourceId: "imp",
            seed: [Self.src(2, "garmin-import"), Self.src(3, "garmin-import"), Self.src(3, "garmin-other")])
        XCTAssertEqual(out.map(\.sourceId), ["garmin-import", "imp", "my-whoop", "garmin-AAA"])
    }

    func testRecordedSourcesPreferTheBaselineKey() {
        let rows: [(day: String, key: String, sourceId: String)] = [
            ("a", "strain", "s"), ("a", "recovery", "r"), ("b", "strain", "s"),
            ("b", ChargeBaselines.baselineSourceKey, "x"), ("b", "recovery", "r"), ("c", "vo2max_est", "nes")]
        XCTAssertEqual(ChargeBaselines.recordedSources(rows), ["a": "r", "b": "x"])
    }

    /// The per-day baseline never reads the scored night or any later one, so adding nights after a day
    /// leaves that day's baseline (and so its stored Charge) exactly as it was.
    func testADaysBaselineIgnoresTonightAndLaterNights() {
        let day = Self.dayKey(Self.anchorEpochDay - 10)
        func resolve(_ ownBacks: ClosedRange<Int>, tonight: Double) -> ChargeBaselines.Resolved {
            let own = ownBacks.map { b in (day: Self.dayKey(Self.anchorEpochDay - b),
                                           value: Optional(b == 10 ? tonight : 50 + Double(b % 5))) }
            return ChargeBaselines.resolveDay(scoredDay: day, imported: .init(), own: .init(hrv: own),
                                              seed: .init(), sourceDays: [], hrvRecalibration: 0,
                                              recoveryRecalibration: 0)!
        }
        let a = resolve(10...40, tonight: 20), b = resolve(0...40, tonight: 200)
        XCTAssertEqual(a.anchorDay, Self.dayKey(Self.anchorEpochDay - 11))
        XCTAssertEqual(a.hrv, b.hrv)
        XCTAssertEqual(a.hrv.nValid, 30)
    }

    /// The dashboard inputs resolve exactly what the engine's day-by-day call resolves.
    func testInputsResolveMatchesResolveDay() {
        func row(_ back: Int) -> DailyMetric {
            DailyMetric(day: Self.dayKey(Self.anchorEpochDay - back), totalSleepMin: nil, efficiency: nil,
                        deepMin: nil, remMin: nil, lightMin: nil, disturbances: nil, restingHr: 50 + back % 7,
                        avgHrv: 40 + Double(back % 11), recovery: nil, strain: nil, exerciseCount: nil,
                        respRateBpm: 14 + Double(back % 3) / 2, skinTempC: 33 + Double(back % 4) / 10)
        }
        let own = (0..<25).map(row), imported = (20..<70).map(row)
        let sources = (0..<70).map { Self.src($0, $0 < 3 ? "garmin-AAA" : "my-whoop") }
        let inputs = ChargeBaselines.Inputs(imported: imported, own: own, seed: [], sourceDays: sources,
                                            hrvRecalibration: 0, recoveryRecalibration: 0)
        for back in [0, 2, 5, 12] {
            let day = Self.dayKey(Self.anchorEpochDay - back)
            var imp = ChargeBaselines.Nights(rows: imported); imp.skin = []
            let direct = ChargeBaselines.resolveDay(scoredDay: day, imported: imp,
                                                    own: .init(rows: own), seed: .init(), sourceDays: sources,
                                                    hrvRecalibration: 0, recoveryRecalibration: 0)
            XCTAssertEqual(inputs.resolve(scoredDay: day), direct)
        }
        // Garmin era starts at day -2: day -2 has no night before it in its era, day -1 has one.
        XCTAssertEqual(inputs.resolve(scoredDay: Self.dayKey(Self.anchorEpochDay - 1))?.hrv.nValid, 1)
        XCTAssertEqual(inputs.resolve(scoredDay: Self.dayKey(Self.anchorEpochDay - 2))?.hrv.nValid, 0)
        // The last WHOOP day keeps its whole WHOOP baseline.
        XCTAssertGreaterThanOrEqual(inputs.resolve(scoredDay: Self.dayKey(Self.anchorEpochDay - 3))!.hrv.nValid, 14)
    }

    // MARK: - Diagnostic line

    func testLogLineNamesEachMetricsComposition() {
        let seeded = ChargeBaselines.History(dayKeys: [], values: [], seededByImport: true,
                                             ownValidNights: 9, importedNights: 40)
        let own = ChargeBaselines.History(dayKeys: [], values: [], seededByImport: false,
                                          ownValidNights: 45, importedNights: 0)
        XCTAssertEqual(ChargeBaselines.logLine(scoredDay: "2026-09-30", hrv: own, restingHR: seeded,
                                               resp: own, skin: own),
                       "charge baseline day=2026-09-30 window=60d hrv=own/45 rhr=seed/9+40 resp=own/45 skin=own/45")
    }

    // MARK: - Oracle (shared with Kotlin)

    /// A deterministic spread of imported / own / seed histories with device switches and recalibration
    /// epochs, resolved for one scored day each. The same generator runs in the Kotlin twin
    /// (`ChargeBaselinesTest.oracleLines`) and both assert the same literal.
    static func oracleLines() -> [String] {
        var s: UInt64 = 53
        func next() -> Int {
            s = s &* 6_364_136_223_846_793_005 &+ 1_442_695_040_888_963_407
            return Int(s >> 33)
        }
        func f6(_ x: Double) -> String { String(format: "%.6f", x) }
        var lines: [String] = []
        for c in 0..<60 {
            let scored = 20_089 + next() % 400
            func nights(_ count: Int, _ endBack: Int, nilEvery: Int,
                        _ gen: () -> Double) -> [(day: String, value: Double?)] {
                (0..<count).map { i in
                    let v: Double? = next() % nilEvery == 0 ? nil : gen()
                    return (day: dayKey(scored - endBack - (count - 1 - i)), value: v)
                }
            }
            let nImp = next() % 90, impEnd = next() % 70
            let nOwn = next() % 70, ownEnd = next() % 5
            let nSeed = next() % 60, seedEnd = next() % 80
            var imported = ChargeBaselines.Nights(), own = ChargeBaselines.Nights(), seed = ChargeBaselines.Nights()
            imported.hrv = nights(nImp, impEnd, nilEvery: 9) { 30 + Double(next() % 90) }
            imported.restingHR = nights(nImp, impEnd, nilEvery: 9) { Double(45 + next() % 20) }
            imported.resp = nights(nImp, impEnd, nilEvery: 9) { 12 + Double(next() % 60) / 10 }
            own.hrv = nights(nOwn, ownEnd, nilEvery: 7) { 20 + Double(next() % 1200) / 10 }
            own.restingHR = nights(nOwn, ownEnd, nilEvery: 7) { Double(42 + next() % 25) }
            own.resp = nights(nOwn, ownEnd, nilEvery: 7) { 11 + Double(next() % 80) / 10 }
            own.skin = nights(nOwn, ownEnd, nilEvery: 5) { 32 + Double(next() % 40) / 10 }
            seed.hrv = nights(nSeed, seedEnd, nilEvery: 6) { 25 + Double(next() % 100) }
            seed.restingHR = nights(nSeed, seedEnd, nilEvery: 6) { Double(44 + next() % 22) }
            seed.resp = nights(nSeed, seedEnd, nilEvery: 6) { 12 + Double(next() % 50) / 10 }
            // Own nights switch brand `switchBack` days before the scored day (0 = never).
            let brands = ["my-whoop", "garmin-AAA", "oura-import"]
            let newBrand = brands[next() % 3], switchBack = next() % 30
            let ownSources = own.hrv.map { n -> (day: String, sourceId: String?) in
                let back = scored - Baselines.isoEpochDay(n.day)!
                return (day: n.day, sourceId: switchBack > 0 && back < switchBack ? newBrand
                                                : (next() % 6 == 0 ? nil : "my-whoop"))
            }
            let seedSources = seed.hrv.map { ChargeBaselines.SourceDay(day: $0.day, sourceId: "garmin-import") }
            let sources = ChargeBaselines.sourceDays(own: ownSources, ownFallbackId: "my-whoop",
                                                     importedDays: imported.hrv.map(\.day), importedSourceId: "my-whoop",
                                                     seed: seedSources)
            let hrvRecal = next() % 6 == 0 ? Double(scored - next() % 70) * 86_400 : 0
            let recRecal = next() % 6 == 0 ? Double(scored - next() % 70) * 86_400 : 0
            guard let r = ChargeBaselines.resolveDay(scoredDay: dayKey(scored), imported: imported, own: own,
                                                     seed: seed, sourceDays: sources, hrvRecalibration: hrvRecal,
                                                     recoveryRecalibration: recRecal) else {
                lines.append("c=\(c) nil"); continue
            }
            func ep(_ e: Double) -> String { e > 0 ? dayKey(Int(e / 86_400)) : "-" }
            func st(_ name: String, _ h: ChargeBaselines.History, _ b: BaselineState) -> String {
                "\(name)=\(h.seededByImport ? "s" : "o")\(h.ownValidNights)+\(h.importedNights)/" +
                "n\(b.nValid)/\(b.status.rawValue)/\(f6(b.baseline))/\(f6(b.spread))/\(b.nightsSinceUpdate)"
            }
            lines.append("c=\(c) day=\(r.scoredDay) ep=\(ep(r.epochs.hrv)),\(ep(r.epochs.restingHR)),"
                         + "\(ep(r.epochs.resp)),\(ep(r.epochs.skin)) "
                         + st("hrv", r.hrvHistory, r.hrv) + " " + st("rhr", r.restingHRHistory, r.restingHR) + " "
                         + st("resp", r.respHistory, r.resp) + " " + st("skin", r.skinHistory, r.skin))
        }
        return lines
    }

    func testOracleSpread() {
        let got = Self.oracleLines().joined(separator: "\n")
        if ProcessInfo.processInfo.environment["PRINT_ORACLE"] != nil { print("ORACLE-BEGIN\n\(got)\nORACLE-END") }
        XCTAssertEqual(got, Self.oracleLiteral)
    }

    static let oracleLiteral = """
    c=0 day=2025-06-25 ep=2025-06-22,2025-06-22,2025-06-22,- hrv=s3+24/n3/calibrating/64.710175/0.610071/0 rhr=s3+25/n3/calibrating/52.000000/4.788508/0 resp=s3+26/n3/calibrating/13.566667/1.132418/0 skin=o29+0/n29/trusted/34.103448/0.867331/0
    c=1 day=2025-05-12 ep=2025-04-22,2025-04-22,2025-04-22,- hrv=s13+23/n13/provisional/64.517798/0.448642/3 rhr=o15+0/n15/trusted/52.466667/5.495178/3 resp=s13+22/n13/provisional/14.284615/1.783038/3 skin=o26+0/n26/trusted/34.146154/0.933515/3
    c=2 day=2025-02-13 ep=2025-02-10,2025-02-10,2025-02-10,- hrv=s0+7/n1/calibrating/65.000000/0.039904/2 rhr=s0+4/n0/calibrating/75.000000/1.596169/0 resp=s0+7/n1/calibrating/16.900000/0.399042/2 skin=o34+0/n34/trusted/33.941176/0.768545/3
    c=3 day=2026-01-10 ep=2026-01-02,2026-01-02,2026-01-02,- hrv=s4+34/n5/provisional/63.661994/0.482593/3 rhr=s5+36/n5/provisional/51.600000/4.570733/3 resp=s5+37/n5/provisional/14.720000/2.180831/3 skin=o4+0/n4/provisional/33.725000/0.717907/4
    c=4 day=2025-10-16 ep=2025-09-20,2025-09-20,2025-09-20,- hrv=o23+0/n23/trusted/63.799867/0.443995/1 rhr=o19+0/n19/trusted/52.842105/5.201264/2 resp=o23+0/n23/trusted/15.552174/2.099001/1 skin=o50+0/n50/trusted/33.942000/0.964072/1
    c=5 day=2025-10-27 ep=2025-10-23,2025-10-23,2025-10-23,2025-09-05 hrv=s3+38/n3/calibrating/100.579262/0.077611/1 rhr=s4+39/n4/provisional/48.250000/3.983767/0 resp=s4+39/n4/provisional/13.925000/2.373212/0 skin=o3+0/n3/calibrating/35.166667/0.945430/0
    c=6 day=2025-11-10 ep=-,-,2025-11-01,- hrv=o25+0/n25/trusted/82.738563/0.323457/0 rhr=o27+0/n27/trusted/54.777778/5.819973/0 resp=s8+2/n8/provisional/15.112500/1.423468/1 skin=o30+0/n30/trusted/33.686667/1.004213/0
    c=7 day=2025-01-18 ep=-,-,2024-12-28,- hrv=o25+0/n25/trusted/54.168697/0.438866/0 rhr=o23+0/n23/trusted/53.826087/4.657649/0 resp=o21+0/n21/trusted/14.076190/1.754680/0 skin=o23+0/n23/trusted/34.021739/0.934430/0
    c=8 day=2025-10-29 ep=2025-08-04,2025-08-04,2025-08-04,- hrv=s11+39/n47/trusted/67.102613/0.375481/4 rhr=s11+39/n48/trusted/55.354167/5.120804/3 resp=s13+39/n50/trusted/14.990000/1.516939/3 skin=o11+0/n11/provisional/33.609091/0.947978/3
    c=9 day=2025-06-10 ep=2025-06-02,2025-06-02,2025-06-02,- hrv=s8+36/n8/provisional/65.948399/0.532264/0 rhr=s5+36/n5/provisional/54.800000/6.897778/0 resp=s8+36/n8/provisional/14.375000/1.854458/0 skin=o16+0/n16/trusted/34.025000/1.085121/1
    c=10 day=2025-08-19 ep=2025-07-27,2025-07-27,2025-07-27,- hrv=o19+0/n19/trusted/61.528465/0.363861/0 rhr=o17+0/n17/trusted/54.705882/4.933983/0 resp=o19+0/n19/trusted/15.763158/2.167470/0 skin=o18+0/n18/trusted/34.150000/1.021772/1
    c=11 day=2026-01-24 ep=2026-01-21,2026-01-21,2026-01-21,2026-01-15 hrv=s0+60/n3/calibrating/54.761502/0.443509/0 rhr=s0+60/n3/calibrating/53.000000/4.146969/0 resp=s0+60/n3/calibrating/13.433333/0.799414/0 skin=o5+0/n5/provisional/33.460000/1.098055/3
    c=12 day=2025-12-24 ep=2025-11-17,2025-10-28,2025-10-28,- hrv=o28+0/n28/trusted/90.592075/0.292399/3 rhr=o46+0/n46/trusted/53.173913/5.689035/2 resp=o42+0/n42/trusted/14.323810/1.665686/2 skin=o50+0/n50/trusted/33.742000/0.801188/2
    c=13 day=2025-12-19 ep=2025-12-08,2025-12-08,2025-12-08,2025-10-20 hrv=s10+13/n10/provisional/59.927374/0.520296/1 rhr=s9+13/n9/provisional/53.888889/4.861843/1 resp=s9+13/n9/provisional/15.744444/1.717788/1 skin=o6+0/n6/provisional/34.233333/1.113512/1
    c=14 day=2025-05-03 ep=2025-04-20,2025-04-20,2025-04-20,- hrv=s11+42/n11/provisional/60.650612/0.423067/1 rhr=s10+43/n10/provisional/56.000000/3.686195/1 resp=s11+44/n12/provisional/13.908333/1.871816/1 skin=o8+0/n8/provisional/33.800000/1.075830/1
    c=15 day=2025-10-16 ep=2025-08-31,2025-08-19,2025-08-19,- hrv=s3+54/n39/trusted/65.874196/0.354552/0 rhr=s2+54/n54/trusted/54.648148/5.273782/1 resp=s4+53/n54/trusted/15.205556/1.239243/0 skin=o4+0/n4/provisional/34.225000/0.801734/0
    c=16 day=2025-10-27 ep=2025-10-23,2025-10-23,2025-10-23,- hrv=s3+25/n3/calibrating/35.491138/0.649749/1 rhr=s2+25/n2/calibrating/51.000000/7.900634/1 resp=s3+25/n3/calibrating/14.033333/1.341004/1 skin=o3+0/n3/calibrating/34.033333/0.719753/1
    c=17 day=2025-02-01 ep=2025-01-13,2025-01-13,2025-01-13,2024-11-27 hrv=o16+0/n16/trusted/76.685549/0.333527/0 rhr=o19+0/n19/trusted/53.578947/5.181173/0 resp=o17+0/n17/trusted/15.070588/1.699275/0 skin=o29+0/n29/trusted/33.793103/0.932411/0
    c=18 day=2025-03-07 ep=2025-01-01,2024-12-16,2024-12-16,- hrv=o0+0/n0/calibrating/127.500000/0.039904/0 rhr=o1+0/n1/calibrating/54.000000/1.596169/0 resp=o1+0/n1/calibrating/17.600000/0.399042/0 skin=o1+0/n1/calibrating/35.300000/0.239425/0
    c=19 day=2025-11-27 ep=2025-11-02,2025-11-02,2025-11-02,- hrv=o20+0/n20/trusted/68.149220/0.432925/0 rhr=o21+0/n21/trusted/51.095238/6.009025/0 resp=o23+0/n23/trusted/14.195652/1.935922/0 skin=o19+0/n19/trusted/34.336842/0.813981/1
    c=20 day=2025-01-02 ep=2025-01-01,2025-01-01,2025-01-01,- hrv=s0+17/n1/calibrating/70.000000/0.039904/0 rhr=s0+17/n1/calibrating/45.000000/1.596169/0 resp=s0+17/n1/calibrating/17.700000/0.399042/0 skin=o33+0/n33/trusted/33.412121/0.980044/1
    c=21 day=2025-06-01 ep=2025-05-17,2025-05-17,2025-05-17,- hrv=s12+15/n12/provisional/76.120921/0.463305/3 rhr=s13+15/n13/provisional/56.000000/5.333849/2 resp=s12+15/n12/provisional/14.091667/1.489375/2 skin=o30+0/n30/trusted/34.080000/0.878868/2
    c=22 day=2025-03-15 ep=-,-,-,- hrv=s9+20/n28/trusted/64.123739/0.346792/2 rhr=s10+20/n30/trusted/55.933333/4.360893/2 resp=s10+20/n28/trusted/14.932143/1.437382/2 skin=o8+0/n8/provisional/34.675000/0.942136/2
    c=23 day=2025-04-29 ep=2025-04-05,2025-04-26,2025-04-26,2025-04-26 hrv=o20+0/n20/trusted/67.756766/0.433569/0 rhr=s3+29/n3/calibrating/48.333333/2.303872/0 resp=s3+23/n3/calibrating/14.066667/1.948918/0 skin=o3+0/n3/calibrating/34.800000/1.055767/0
    c=24 day=2025-03-28 ep=2025-03-07,-,2025-03-19,- hrv=o17+0/n17/trusted/65.391949/0.402066/1 rhr=o28+0/n28/trusted/53.250000/6.263914/0 resp=s9+23/n9/provisional/14.177778/1.773732/0 skin=o25+0/n25/trusted/33.716000/0.711475/1
    c=25 day=2025-05-29 ep=2025-05-05,-,-,- hrv=o18+0/n18/trusted/67.635775/0.408726/0 rhr=o45+0/n45/trusted/53.800000/5.590737/0 resp=o43+0/n43/trusted/15.123256/1.944854/2 skin=o33+0/n33/trusted/34.069697/0.718687/0
    c=26 day=2025-03-03 ep=2025-02-27,2025-02-27,2025-02-27,- hrv=s4+32/n4/provisional/76.260765/0.457770/0 rhr=s3+32/n3/calibrating/59.333333/2.438189/0 resp=s4+32/n4/provisional/16.925000/2.679136/0 skin=o3+0/n3/calibrating/34.200000/0.761324/0
    c=27 day=2025-01-28 ep=2025-01-13,2025-01-13,2025-01-13,- hrv=s11+19/n11/provisional/88.352941/0.304763/2 rhr=s11+18/n11/provisional/55.545455/4.924429/2 resp=s12+22/n12/provisional/15.225000/2.213058/2 skin=o49+0/n49/trusted/33.967347/0.838525/2
    c=28 day=2025-05-03 ep=2025-04-17,2025-04-22,2025-04-22,2025-04-22 hrv=s13+44/n13/provisional/71.976955/0.384524/1 rhr=s9+44/n9/provisional/54.000000/4.371289/1 resp=s8+44/n8/provisional/14.862500/1.829854/0 skin=o9+0/n9/provisional/34.500000/0.920396/1
    c=29 day=2025-03-13 ep=-,-,2025-02-23,- hrv=o20+0/n20/trusted/62.968824/0.442137/1 rhr=o18+0/n18/trusted/49.000000/4.004482/2 resp=s12+43/n12/provisional/15.566667/1.282967/1 skin=o15+0/n15/trusted/34.326667/0.781650/1
    c=30 day=2025-11-21 ep=-,-,-,- hrv=o49+0/n49/trusted/61.813697/0.492695/3 rhr=o50+0/n50/trusted/54.660000/5.854962/4 resp=o50+0/n50/trusted/15.254000/1.946852/3 skin=o41+0/n41/trusted/33.992683/0.837493/3
    c=31 day=2025-02-23 ep=2025-01-19,2025-01-19,2025-01-19,- hrv=o30+0/n30/trusted/65.637938/0.460819/1 rhr=o33+0/n33/trusted/54.333333/5.920995/0 resp=o35+0/n35/trusted/14.720000/1.775816/0 skin=o33+0/n33/trusted/33.715152/0.923445/0
    c=32 day=2025-07-09 ep=2025-05-24,2025-05-24,2025-05-24,- hrv=o20+0/n20/trusted/79.757351/0.323384/1 rhr=o26+0/n26/trusted/54.230769/5.676206/0 resp=o23+0/n23/trusted/15.139130/1.982655/0 skin=o21+0/n21/trusted/33.980952/0.785462/0
    c=33 day=2025-08-20 ep=2025-08-08,2025-08-08,2025-08-08,- hrv=s9+8/n9/provisional/75.014152/0.358872/0 rhr=s11+8/n11/provisional/55.727273/6.894000/0 resp=s12+8/n12/provisional/14.950000/2.037710/0 skin=o15+0/n15/trusted/34.420000/0.731581/0
    c=34 day=2025-03-20 ep=2025-03-13,2025-03-13,2025-03-13,- hrv=o4+0/n4/provisional/67.452347/0.492438/3 rhr=o4+0/n4/provisional/51.500000/8.203854/3 resp=o3+0/n3/calibrating/16.066667/2.008469/3 skin=o4+0/n4/provisional/35.125000/0.301270/3
    c=35 day=2025-03-15 ep=2025-02-03,-,-,- hrv=o35+0/n35/trusted/73.982853/0.367779/2 rhr=o44+0/n44/trusted/54.068182/6.390237/2 resp=o47+0/n47/trusted/15.176596/1.926734/2 skin=o46+0/n46/trusted/34.080435/0.880327/2
    c=36 day=2025-01-04 ep=-,-,2024-12-09,- hrv=o24+0/n24/trusted/81.914811/0.369669/0 rhr=o20+0/n20/trusted/56.250000/5.374806/0 resp=o22+0/n22/trusted/14.018182/1.910748/0 skin=o22+0/n22/trusted/33.563636/0.907802/0
    c=37 day=2025-08-19 ep=-,-,2025-08-10,- hrv=o52+0/n52/trusted/71.126549/0.417679/2 rhr=o51+0/n51/trusted/55.450980/5.718016/2 resp=s7+24/n7/provisional/14.528571/1.941667/2 skin=o47+0/n47/trusted/33.678723/0.908881/2
    c=38 day=2025-10-27 ep=-,-,-,- hrv=o47+0/n47/trusted/72.211121/0.419972/1 rhr=o49+0/n49/trusted/52.816327/5.850368/1 resp=o46+0/n46/trusted/15.058696/1.706365/2 skin=o38+0/n38/trusted/34.305263/0.951561/1
    c=39 day=2025-10-11 ep=2025-09-29,2025-09-29,2025-09-29,- hrv=s9+11/n9/provisional/84.305327/0.281489/2 rhr=s8+10/n8/provisional/56.875000/4.338643/3 resp=s8+12/n8/provisional/14.875000/2.097590/2 skin=o14+0/n14/trusted/33.814286/0.873056/2
    c=40 day=2025-01-07 ep=-,-,-,- hrv=o49+0/n49/trusted/77.238528/0.353540/0 rhr=o55+0/n55/trusted/55.745455/5.327694/0 resp=o53+0/n53/trusted/15.111321/1.820967/0 skin=o50+0/n50/trusted/33.752000/0.986220/1
    c=41 day=2025-01-16 ep=2024-12-24,2024-12-24,2024-12-24,- hrv=o18+0/n18/trusted/76.830262/0.329820/1 rhr=o20+0/n20/trusted/53.000000/5.955455/1 resp=o22+0/n22/trusted/14.309091/1.843082/1 skin=o19+0/n19/trusted/33.894737/0.819727/1
    c=42 day=2025-11-30 ep=-,-,-,- hrv=s8+51/n48/trusted/63.857371/0.315722/2 rhr=s6+51/n50/trusted/53.140000/4.204589/2 resp=s7+51/n46/trusted/15.067391/1.421702/2 skin=o7+0/n7/provisional/34.114286/1.130944/2
    c=43 day=2025-03-04 ep=2025-01-15,2025-01-15,2025-01-15,- hrv=o40+0/n40/trusted/70.211762/0.426627/0 rhr=o36+0/n36/trusted/54.611111/5.657088/0 resp=o40+0/n40/trusted/15.182500/1.565891/0 skin=o36+0/n36/trusted/33.683333/0.868096/2
    c=44 day=2025-05-06 ep=2025-04-01,2025-04-01,2025-04-01,- hrv=s0+48/n23/trusted/66.439064/0.403454/9 rhr=s0+47/n22/trusted/54.590909/5.527422/8 resp=s0+46/n21/trusted/14.442857/1.080092/9 skin=o0+0/n0/calibrating/31.000000/0.239425/0
    c=45 day=2025-02-12 ep=2025-01-18,2025-01-18,2025-01-18,- hrv=o19+0/n19/trusted/72.959927/0.473858/1 rhr=o21+0/n21/trusted/55.333333/5.645191/1 resp=o22+0/n22/trusted/15.063636/2.011117/1 skin=o34+0/n34/trusted/34.211765/0.943849/1
    c=46 day=2025-04-13 ep=2025-04-06,2025-04-06,2025-04-06,- hrv=s6+34/n6/provisional/59.624617/0.511912/1 rhr=s7+32/n7/provisional/55.142857/5.962153/0 resp=s6+31/n6/provisional/14.266667/1.540669/0 skin=o7+0/n7/provisional/34.257143/1.082435/0
    c=47 day=2025-01-20 ep=2024-12-31,2024-12-31,2024-12-31,- hrv=o18+0/n18/trusted/71.871883/0.422464/0 rhr=o18+0/n18/trusted/57.000000/4.858416/0 resp=o19+0/n19/trusted/14.089474/1.526338/0 skin=o46+0/n46/trusted/34.165217/1.034466/1
    c=48 day=2025-06-21 ep=2025-06-01,2025-06-01,2025-06-01,- hrv=o17+0/n17/trusted/85.824842/0.419174/0 rhr=o17+0/n17/trusted/55.352941/5.607511/0 resp=o17+0/n17/trusted/14.758824/1.908753/0 skin=o34+0/n34/trusted/33.755882/0.769818/0
    c=49 day=2025-10-06 ep=2025-09-23,2025-09-23,2025-09-23,- hrv=s12+58/n13/provisional/68.894606/0.217642/0 rhr=s11+58/n12/provisional/55.416667/6.361584/0 resp=s11+58/n12/provisional/15.525000/1.410981/0 skin=o22+0/n22/trusted/33.954545/0.915184/0
    c=50 day=2026-01-17 ep=-,-,-,- hrv=o14+0/n14/trusted/91.200431/0.310582/3 rhr=s12+11/n20/trusted/53.550000/5.532171/2 resp=o16+0/n16/trusted/15.950000/1.645423/2 skin=o15+0/n15/trusted/33.693333/0.892524/2
    c=51 day=2025-08-08 ep=2025-07-28,2025-07-28,2025-07-28,2025-07-02 hrv=s8+25/n10/provisional/76.757948/0.264994/1 rhr=s9+24/n9/provisional/51.666667/4.316302/1 resp=s10+25/n10/provisional/14.950000/1.651626/1 skin=o8+0/n8/provisional/33.725000/0.972550/1
    c=52 day=2025-09-24 ep=2025-09-16,2025-08-27,2025-08-27,- hrv=s4+20/n4/provisional/65.856400/0.604877/3 rhr=o22+0/n22/trusted/52.090909/5.429149/1 resp=o23+0/n23/trusted/15.365217/2.048283/2 skin=o24+0/n24/trusted/33.895833/0.832553/3
    c=53 day=2025-04-16 ep=-,-,2025-04-07,- hrv=o25+0/n25/trusted/67.892979/0.467671/0 rhr=o23+0/n23/trusted/57.173913/5.511735/0 resp=s8+7/n8/provisional/14.675000/2.489187/1 skin=o21+0/n21/trusted/34.180952/0.885701/0
    c=54 day=2025-10-10 ep=-,-,-,- hrv=o25+0/n25/trusted/70.542391/0.358816/0 rhr=o28+0/n28/trusted/55.892857/5.898149/0 resp=o23+0/n23/trusted/15.430435/1.944570/0 skin=o23+0/n23/trusted/34.095652/1.042654/1
    c=55 day=2025-06-07 ep=2025-03-26,2025-03-26,2025-03-26,- hrv=o25+0/n25/trusted/63.029677/0.516939/4 rhr=o25+0/n25/trusted/51.320000/5.935410/3 resp=o21+0/n21/trusted/15.500000/2.025154/4 skin=o30+0/n30/trusted/33.570000/0.801147/3
    c=56 day=2025-03-23 ep=2025-01-20,2025-01-20,2025-01-20,- hrv=s13+24/n34/trusted/77.982521/0.314244/0 rhr=s10+24/n32/trusted/54.937500/4.813917/1 resp=s13+24/n37/trusted/15.162162/1.497617/0 skin=o10+0/n10/provisional/33.370000/0.913101/0
    c=57 day=2025-08-16 ep=2025-06-09,2025-06-09,2025-06-09,- hrv=s3+35/n37/trusted/66.810601/0.344030/0 rhr=s3+35/n33/trusted/55.303030/4.681248/0 resp=s3+35/n34/trusted/14.432353/1.297288/0 skin=o3+0/n3/calibrating/33.833333/0.599007/0
    c=58 day=2025-11-26 ep=2025-11-15,-,2025-10-31,- hrv=s8+26/n8/provisional/52.544329/0.322643/2 rhr=o26+0/n26/trusted/54.576923/5.260936/2 resp=o20+0/n20/trusted/14.745000/1.838145/2 skin=o24+0/n24/trusted/33.691667/0.914485/2
    c=59 day=2026-01-20 ep=2026-01-19,2026-01-19,2026-01-19,- hrv=s1+4/n1/calibrating/51.100000/0.039904/0 rhr=s1+6/n1/calibrating/65.000000/1.596169/0 resp=s1+7/n1/calibrating/12.600000/0.399042/0 skin=o7+0/n7/provisional/33.857143/1.052602/0
    """
}
