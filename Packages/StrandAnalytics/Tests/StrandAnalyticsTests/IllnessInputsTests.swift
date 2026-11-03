import XCTest
@testable import StrandAnalytics

final class IllnessInputsTests: XCTestCase {

    private func night(_ i: Int, rhr: Double? = 52, hrv: Double? = 60, resp: Double? = 14.5,
                       skin: Double? = 0, strain: Double? = 40) -> IllnessInputs.Night {
        IllnessInputs.Night(day: String(format: "2026-01-%02d", i + 1), restingHR: rhr, hrv: hrv,
                            respiration: resp, skinTemp: skin, strain: strain)
    }

    private func stableHistory() -> [IllnessInputs.Night] {
        (0..<30).map { i in
            night(i, rhr: 52 + Double(i % 3), hrv: 60 + Double(i % 4) * 2, resp: 14.5 + Double(i % 2) * 0.2,
                  skin: [0.05, -0.05, 0.0, 0.04, -0.04][i % 5], strain: 40 + Double(i % 5) * 3)
        }
    }

    // The ticket's divergence: a stable ±0.05 °C series plus a +0.3 °C night. The floored personal spread
    // keeps it well under the firing threshold (Android's plain SD read z ≈ 6 here).
    func testStableSkinTempPlusSmallBumpStaysQuiet() throws {
        var n = stableHistory()
        n[28] = night(28, rhr: 53, hrv: 62, resp: 14.6, skin: 0.3, strain: 42)
        n[29] = night(29, rhr: 53, hrv: 62, resp: 14.6, skin: 0.3, strain: 42)
        let b = IllnessInputs.build(n)
        let skin = try XCTUnwrap(b.skinTemp)
        XCTAssertLessThan(skin.reading.zIllnessward, IllnessSignalEngine.signalZThreshold)
        XCTAssertTrue(b.baselineTrusted)
    }

    func testTwoIllNightsFireAllFour() throws {
        var n = stableHistory()
        n[28] = night(28, rhr: 60, hrv: 44, resp: 16.0, skin: 0.8, strain: 30)
        n[29] = night(29, rhr: 61, hrv: 42, resp: 16.2, skin: 0.9, strain: 30)
        let b = IllnessInputs.build(n)
        for s in [b.restingHR, b.hrv, b.respiration, b.skinTemp] {
            XCTAssertGreaterThan(try XCTUnwrap(s).reading.zIllnessward, IllnessSignalEngine.signalZThreshold)
        }
        let r = IllnessSignalEngine.evaluate(b.inputs, context: IllnessInputs.context(yesQuestions: [], built: b))
        XCTAssertEqual(r.level, .raised)
    }

    // Persistence: one bad night after a normal one does not count (min of mean and latest).
    func testSingleNightDoesNotCount() throws {
        var n = stableHistory()
        n[28] = night(28, rhr: 53, hrv: 62, resp: 14.6, skin: 0.0, strain: 42)
        n[29] = night(29, rhr: 64, hrv: 40, resp: 16.5, skin: 0.9, strain: 42)
        let b = IllnessInputs.build(n)
        XCTAssertLessThan(try XCTUnwrap(b.restingHR).reading.zIllnessward,
                          Baselines.deviation(64, state: Baselines.foldHistory(
                              IllnessInputs.baseline(n).map(\.restingHR), cfg: Baselines.restingHRCfg)).z)
    }

    // The gap: the two recent nights and the one before them never enter the baseline.
    func testBaselineExcludesRecentAndGap() {
        let n = stableHistory()
        let base = IllnessInputs.baseline(n)
        XCTAssertEqual(base.count, 27)
        XCTAssertEqual(base.last?.day, n[26].day)
        XCTAssertEqual(IllnessInputs.baseline(Array(n.prefix(3))), [])
    }

    // Imported absolute °C rows must not be divided by a deviation spread (z ≈ 110 → false alert).
    func testAbsoluteSkinTempIsComparedToItsOwnKind() throws {
        var n = stableHistory()
        for i in 0..<20 { n[i] = night(i, skin: 33.0 + Double(i % 3) * 0.1, strain: 45) }
        let dev = try XCTUnwrap(IllnessInputs.build(n).skinTemp)
        XCTAssertLessThan(dev.reading.zIllnessward, IllnessSignalEngine.signalZThreshold)

        var abs = n
        abs[28] = night(28, skin: 33.1)
        abs[29] = night(29, skin: 33.1)
        let a = try XCTUnwrap(IllnessInputs.build(abs).skinTemp)
        XCTAssertLessThan(a.reading.zIllnessward, IllnessSignalEngine.signalZThreshold)
    }

    func testHardWorkoutIsPersonal() {
        var n = stableHistory()
        n[28] = night(28, strain: 95)
        XCTAssertTrue(IllnessInputs.build(n).hardWorkout)
        XCTAssertFalse(IllnessInputs.build(stableHistory()).hardWorkout)
        // Today's own strain comes after the night: it is not a confounder for it.
        var late = stableHistory()
        late[29] = night(29, strain: 95)
        XCTAssertFalse(IllnessInputs.build(late).hardWorkout)
    }

    // Catalog keys, not substrings: "pill", "still", "drink water" match nothing.
    func testContextMatchesCanonicalQuestions() {
        let b = IllnessInputs.build(stableHistory())
        let c = IllnessInputs.context(yesQuestions: ["  Did you drink   any alcohol?\n", "Did you feel stressed?",
                                                     "Did you use a sauna?"], built: b)
        XCTAssertTrue(c.alcohol); XCTAssertTrue(c.stress); XCTAssertTrue(c.sauna)
        XCTAssertFalse(c.alreadyUnwell)
        let none = IllnessInputs.context(yesQuestions: ["Did you take a pill?", "Still tired?", "Did you drink water?",
                                                        "Feeling chill?"], built: b)
        XCTAssertEqual(none, IllnessSignalEngine.Context(baselineTrusted: true))
        XCTAssertTrue(IllnessInputs.context(yesQuestions: ["Did you feel sick or ill?"], built: b).alreadyUnwell)
        XCTAssertTrue(IllnessInputs.context(yesQuestions: ["Have any alcoholic drinks?"], built: b).alcohol)
    }

    private func withMidpoints(_ n: [IllnessInputs.Night], _ mid: (Int) -> Double?) -> [IllnessInputs.Night] {
        n.enumerated().map { i, x in
            IllnessInputs.Night(day: x.day, restingHR: x.restingHR, hrv: x.hrv, respiration: x.respiration,
                                skinTemp: x.skinTemp, strain: x.strain, sleepMidpointUtcMin: mid(i))
        }
    }

    // A 6 h time-zone crossing moves the UTC sleep midpoint at once; jitter within an hour does not.
    // The baseline straddles UTC midnight to exercise the circular mean.
    func testSleepTimingJumpIsTravel() {
        let jitter: [Double] = [1410, 1430, 10, 30, 1420, 20, 0]
        let home = withMidpoints(stableHistory()) { jitter[$0 % 7] }
        XCTAssertFalse(IllnessInputs.build(home).travelPhaseJump)
        let east = withMidpoints(stableHistory()) { $0 >= 29 ? 1080 : jitter[$0 % 7] }
        let b = IllnessInputs.build(east)
        XCTAssertTrue(b.travelPhaseJump)
        XCTAssertTrue(IllnessInputs.context(yesQuestions: [], built: b).travelPhaseJump)
        let nearly = withMidpoints(stableHistory()) { $0 >= 28 ? 170 : jitter[$0 % 7] }
        XCTAssertFalse(IllnessInputs.build(nearly).travelPhaseJump)
    }

    // No reference without enough baseline midpoints, or with an irregular (rotating-shift) baseline.
    func testIrregularOrThinSleepTimingIsNoReference() {
        let thin = withMidpoints(stableHistory()) { $0 < 21 ? nil : ($0 >= 28 ? 600 : 180) }
        XCTAssertFalse(IllnessInputs.build(thin).travelPhaseJump)
        let rotating = withMidpoints(stableHistory()) { $0 >= 28 ? 600 : Double(($0 % 4) * 360) }
        XCTAssertFalse(IllnessInputs.build(rotating).travelPhaseJump)
    }

    func testSleepMidpointsKeepTheMainBlock() {
        let m = IllnessInputs.sleepMidpoints([
            .init(day: "2026-01-02", start: 1_767_312_000, end: 1_767_340_800),   // 00:00–08:00 UTC
            .init(day: "2026-01-02", start: 1_767_358_800, end: 1_767_362_400),   // a nap
            .init(day: "2026-01-03", start: 1_767_391_200, end: 1_767_391_200),   // empty block
            .init(day: "2026-01-04", start: 1_767_481_200, end: 1_767_510_000),   // 23:00–07:00 UTC
        ])
        XCTAssertEqual(m["2026-01-02"], 240)
        XCTAssertNil(m["2026-01-03"])
        XCTAssertEqual(m["2026-01-04"], 180)
    }

    func testShortHistoryIsNotPresent() {
        let b = IllnessInputs.build(Array(stableHistory().prefix(5)))
        XCTAssertEqual(b.restingHR?.reading.present, false)
        XCTAssertFalse(b.baselineTrusted)
        XCTAssertNil(IllnessInputs.build([]).restingHR)
    }
}
