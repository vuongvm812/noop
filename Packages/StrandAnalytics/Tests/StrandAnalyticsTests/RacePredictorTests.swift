import XCTest
@testable import StrandAnalytics

/// Race predictions (Riegel over whole runs, blended with the heart-rate and VO₂max estimates). Every expected line is the stdout of this file's fixtures run
/// through `RacePredictor.swift` compiled standalone with `swiftc -O`, and the Kotlin `RacePredictorOracleTest`
/// pins the same literals from the other side.
final class RacePredictorTests: XCTestCase {
    private typealias R = RacePredictor

    private func f(_ v: Double?) -> String { v.map { String(format: "%.9f", $0) } ?? "nil" }

    private func p(_ x: R.Prediction?) -> String {
        guard let x else { return "nil" }
        return "\(x.targetIndex):\(x.seconds)@\(x.sourceStartTs)/\(f(x.sourceDistanceM))/\(f(x.sourceDurationS))"
    }

    private func line(_ items: [R.Prediction?]) -> String { items.map { p($0) }.joined(separator: " ") }

    private func run(_ start: Int, _ sport: String, _ d: Double?, _ dur: Double?, end: Int? = nil) -> R.Run {
        let e: Int
        if let end { e = end } else if let dur, dur.isFinite { e = start + Int(dur) } else { e = start }
        return R.Run(startTs: start, endTs: e, sport: sport, distanceM: d, durationS: dur, avgHr: nil)
    }

    private let now = 1_800_000_000
    private let day = 86_400

    func testRiegelValues() {
        let line = [
            R.riegel(timeS: 1500, fromM: 5000, toM: 10000),
            R.riegel(timeS: 3000, fromM: 10000, toM: 42195),
            R.riegel(timeS: 1200, fromM: 4000, toM: 5000),
            R.riegel(timeS: 7200, fromM: 21097.5, toM: 5000),
            R.riegel(timeS: 1500, fromM: 5000, toM: 5000),
        ].map { f($0) }.joined(separator: " ")
        XCTAssertEqual("RIEGEL " + line,
                       "RIEGEL 3127.397282523 13800.597999770 1520.217962846 1565.149568183 1500.000000000")
    }

    /// Walks, a 2.9 km run, an 11 min run, an 8 m/s run, a missing / NaN distance and a slow 1.4 m/s jog are
    /// rejected; a missing, zero, infinite or negative duration falls back to the end − start span.
    func testQualifyingFilters() {
        let qual: [R.Run] = [
            run(0, "Running", 5000, 1500),
            run(0, "Walking", 5000, 1500),
            run(0, "Running", 2900, 1200),
            run(0, "Running", 3000, 660),
            run(0, "Running", 9600, 1200),
            run(0, " TREADMILL RUN\n", 5000, 1800),
            run(1000, "Running", 5000, nil, end: 2500),
            run(0, "Running", nil, 1500),
            run(1000, "running", 5000, 0, end: 2600),
            run(0, "Hiking", 8000, 3600),
            run(0, "Running", 5000, 3600),
            run(0, "Running", 4500, 3000),
            run(0, "Running", 7000, 1000),
            run(0, "Running", Double.nan, 1500),
            run(0, "Trail running", 5000, 1500),
            run(0, "Running", 5000, Double.infinity, end: 1500),
            run(0, "Running", 5000, -10, end: 1500),
        ]
        XCTAssertEqual("QUAL " + qual.map { R.qualifies($0) ? "1" : "0" }.joined(), "QUAL 10000110100110011")
        XCTAssertEqual(
            "DUR " + qual.map { f(R.effectiveDurationS($0)) }.joined(separator: " "),
            "DUR 1500.000000000 1500.000000000 1200.000000000 660.000000000 1200.000000000 1800.000000000 " +
                "1500.000000000 1500.000000000 1600.000000000 3600.000000000 3600.000000000 3000.000000000 " +
                "1000.000000000 1500.000000000 1500.000000000 1500.000000000 1500.000000000")
        XCTAssertEqual(
            "SPORT " + ["Running", "running", "  Running\t", "Treadmill run", "TREADMILL RUN", "Treadmill walk",
                        "Run", "Trail running", ""].map { R.isRunningSport($0) ? "1" : "0" }.joined(),
            "SPORT 111110000")
    }

    /// A 5K run stops at the 10K (4× cap); a 10.5 km run is just short of the marathon; 10.6 km reaches it.
    func testForRunAppliesTheExtrapolationCap() {
        let runs = [run(0, "Running", 5000, 1500), run(0, "Running", 10500, 3300), run(0, "Running", 10600, 3300),
                    run(0, "Running", 21097.5, 6300), run(0, "Running", 42195, 13500),
                    run(0, "Walking", 10000, 4000), run(0, "Running", 3000, 900)]
        XCTAssertEqual(runs.map { "FOR " + line(R.forRun($0)) }, [
            "FOR 0:1500@0/5000.000000000/1500.000000000 1:3127@0/5000.000000000/1500.000000000 nil nil",
            "FOR 0:1503@0/10500.000000000/3300.000000000 1:3134@0/10500.000000000/3300.000000000 " +
                "2:6914@0/10500.000000000/3300.000000000 nil",
            "FOR 0:1488@0/10600.000000000/3300.000000000 1:3102@0/10600.000000000/3300.000000000 " +
                "2:6845@0/10600.000000000/3300.000000000 3:14271@0/10600.000000000/3300.000000000",
            "FOR 0:1370@0/21097.500000000/6300.000000000 1:2855@0/21097.500000000/6300.000000000 " +
                "2:6300@0/21097.500000000/6300.000000000 3:13135@0/21097.500000000/6300.000000000",
            "FOR 0:1408@0/42195.000000000/13500.000000000 1:2935@0/42195.000000000/13500.000000000 " +
                "2:6475@0/42195.000000000/13500.000000000 3:13500@0/42195.000000000/13500.000000000",
            "FOR nil nil nil nil",
            "FOR 0:1547@0/3000.000000000/900.000000000 1:3225@0/3000.000000000/900.000000000 nil nil",
        ])
    }

    /// Fastest wins across runs inside the 90-day window (the 10K beats the slower 5K at 5K); the old fast
    /// half outside the window is ignored while a recent run qualifies, and used (flagged) when none does.
    func testWindowFallbackAndFastestWins() {
        let mixed: [R.Run] = [
            run(now - 10 * day, "Running", 5000, 1560),
            run(now - 20 * day, "Running", 10000, 3000),
            run(now - 30 * day, "Running", 15000, 4800),
            run(now - 40 * day, "Walking", 12000, 2000),
            run(now - 200 * day, "Running", 21097.5, 5400),
            run(now - 5 * day, "Running", 2000, 500),
        ]
        let res = R.predictions(runs: mixed, nowTs: now)
        XCTAssertEqual("MIX " + (res.fromAllHistory ? "all" : "recent") + " " + line(res.items),
                       "MIX recent 0:1439@1798272000/10000.000000000/3000.000000000 " +
                           "1:3000@1798272000/10000.000000000/3000.000000000 " +
                           "2:6619@1798272000/10000.000000000/3000.000000000 " +
                           "3:14367@1797408000/15000.000000000/4800.000000000")
        let old: [R.Run] = [
            run(now - 200 * day, "Running", 21097.5, 5400),
            run(now - 400 * day, "Running", 5000, 1200),
            run(now - 91 * day, "Running", 8000, 2600),
        ]
        let resOld = R.predictions(runs: old, nowTs: now)
        XCTAssertEqual("OLD " + (resOld.fromAllHistory ? "all" : "recent") + " " + line(resOld.items),
                       "OLD all 0:1174@1782720000/21097.500000000/5400.000000000 " +
                           "1:2447@1782720000/21097.500000000/5400.000000000 " +
                           "2:5400@1782720000/21097.500000000/5400.000000000 " +
                           "3:11259@1782720000/21097.500000000/5400.000000000")
        let edge = R.predictions(runs: [run(now - 90 * day, "Running", 5000, 1500)], nowTs: now)
        XCTAssertEqual("EDGE " + (edge.fromAllHistory ? "all" : "recent") + " " + line(edge.items),
                       "EDGE recent 0:1500@1792224000/5000.000000000/1500.000000000 " +
                           "1:3127@1792224000/5000.000000000/1500.000000000 nil nil")
        let none = R.predictions(runs: [run(now, "Cycling", 30000, 3600)], nowTs: now)
        XCTAssertEqual("NONE " + (none.fromAllHistory ? "all" : "recent") + " " + line(none.items),
                       "NONE recent nil nil nil nil")
        XCTAssertEqual("PACE " + res.items.map { $0.map { f(R.secPerKm($0)) } ?? "nil" }.joined(separator: " "),
                       "PACE 287.800000000 300.000000000 313.733854722 340.490579453")
    }

    /// An exact tie goes to the newer run whatever the input order.
    func testTieBreakIsOrderIndependent() {
        let ties = [run(now - 3 * day, "Running", 5000, 1500), run(now - 2 * day, "Running", 5000, 1500),
                    run(now - 4 * day, "Running", 4000, 1500)]
        let a = R.predictions(runs: ties, nowTs: now)
        let b = R.predictions(runs: ties.reversed(), nowTs: now)
        XCTAssertEqual("TIE " + line(a.items) + " | " + line(b.items),
                       "TIE 0:1500@1799827200/5000.000000000/1500.000000000 " +
                           "1:3127@1799827200/5000.000000000/1500.000000000 nil nil | " +
                           "0:1500@1799827200/5000.000000000/1500.000000000 " +
                           "1:3127@1799827200/5000.000000000/1500.000000000 nil nil")
    }

    /// Adding a faster run moves the prediction; removing it restores the previous one.
    func testAddingAndRemovingAFasterRunMovesThePrediction() {
        let base = [run(now - 10 * day, "Running", 5000, 1560)]
        let before = R.predictions(runs: base, nowTs: now).items[0]?.seconds
        let faster = base + [run(now - 1 * day, "Running", 5000, 1440)]
        XCTAssertEqual(R.predictions(runs: faster, nowTs: now).items[0]?.seconds, 1440)
        XCTAssertEqual(R.predictions(runs: base, nowTs: now).items[0]?.seconds, before)
        XCTAssertEqual(before, 1560)
    }

    func testMinRunForEachTarget() {
        XCTAssertEqual("MINRUN " + [-1, 0, 1, 2, 3, 4].map { f(R.minRunM(targetIndex: $0)) }.joined(separator: " "),
                       "MINRUN 0.000000000 1250.000000000 2500.000000000 5274.375000000 10548.750000000 0.000000000")
    }

    func testFormatTime() {
        XCTAssertEqual("FMT " + [0, 59, 600, 1499, 3599, 3600, 3661, 10_000, 36_000, -5]
                        .map { R.formatTime($0) }.joined(separator: " "),
                       "FMT 0:00 0:59 10:00 24:59 59:59 1:00:00 1:01:01 2:46:40 10:00:00 0:00")
    }

    // MARK: - v2: Daniels VDOT, heart-rate effort, VO₂max, blend, history

    private func est(_ e: R.Estimate?) -> String {
        guard let e else { return "nil" }
        return "\(e.targetIndex):\(e.seconds)[\(f(e.components.riegelS))|\(f(e.components.hrS))|\(f(e.components.vo2S))]"
            + (e.riegel == nil ? "E" : "R")
    }

    private func i(_ v: Int?) -> String { v.map { "\($0)" } ?? "nil" }

    private func hrRun(_ start: Int, _ sport: String, _ d: Double, _ dur: Double, hr: Double?) -> R.Run {
        R.Run(startTs: start, endTs: start + Int(dur), sport: sport, distanceM: d, durationS: dur, avgHr: hr)
    }

    /// VDOT 50 lands on Daniels' published table (5K 19:57, marathon 3:10:49) within seconds.
    func testDanielsModel() {
        XCTAssertEqual("VO2COST " + [0.0, 100, 150, 200, 250, 268.2, 300, 350, 400].map { f(R.vo2Cost($0)) }
                        .joined(separator: " "),
                       "VO2COST -4.600000000 14.665800000 25.078700000 36.011600000 47.464500000 51.762444560 " +
                       "59.437400000 71.930300000 84.943200000")
        XCTAssertEqual("FRAC " + [0.0, 5, 12, 20, 30, 45, 60, 90, 120, 180, 240].map { f(R.fractionAt($0)) }
                        .joined(separator: " "),
                       "FRAC 1.288395100 1.091463441 0.991914029 0.952983125 0.930025336 0.906647316 0.888007314 " +
                       "0.859982257 0.840882768 0.818992205 0.808822883")
        let tfv = [30.0, 40, 50, 60, 70, 85].map { vdot in
            "TFV \(Int(vdot)) " + R.targetDistancesM().map { f(R.timeForVdot(vdot, distanceM: $0)) }.joined(separator: " ")
        }
        XCTAssertEqual(tfv, ["TFV 30 1840.877537363 3828.853225879 8477.218074930 17388.861762954", "TFV 40 1446.218941311 3000.771086871 6653.909186662 13777.421299376", "TFV 50 1196.020824328 2479.603649337 5491.197069311 11439.741755151",
                             "TFV 60 1022.785122427 2121.854975856 4688.594740426 9802.480501888", "TFV 70 895.523685568 1860.950249480 4102.678137969 8592.728224653", "TFV 85 757.305815165 1579.188061992 3471.442195617 7275.054904279"])
        let edge: [(Double, Double)] = [(0, 5000), (-1, 5000), (.nan, 5000), (.infinity, 5000), (50, 0), (50, -5),
                                        (50, .nan), (1, 5000), (2, 5000), (200, 5000), (400, 5000)]
        XCTAssertEqual("TFVEDGE " + edge.map { f(R.timeForVdot($0.0, distanceM: $0.1)) }.joined(separator: " "),
                       "TFVEDGE nil nil nil nil nil nil nil nil nil 367.704937948 nil")
    }

    /// The reserve fraction accepts 0.60…1.00 and rejects missing, non-positive or inverted inputs; a run's
    /// heart-rate VDOT needs a qualifying run with an accepted fraction, and the best three are averaged.
    func testHeartRateEffort() {
        let cases: [(Double?, Double?, Double?)] = [
            (150, 50, 190), (134, 50, 190), (133, 50, 190), (190, 50, 190), (191, 50, 190), (nil, 50, 190),
            (150, nil, 190), (150, 50, nil), (150, 0, 190), (150, 190, 190), (150, 200, 190), (.nan, 50, 190),
            (170, 55, 185),
        ]
        XCTAssertEqual("HRR " + cases.map { f(R.hrrFraction(avgHr: $0.0, restingHr: $0.1, hrMax: $0.2)) }
                        .joined(separator: " "),
                       "HRR 0.714285714 0.600000000 nil 1.000000000 nil nil nil nil nil nil nil nil 0.884615385")
        let runs = [
            hrRun(now - 1 * day, "Running", 10000, 3600, hr: 150), hrRun(now - 2 * day, "Running", 5000, 1500, hr: 175),
            hrRun(now - 3 * day, "Running", 8000, 2880, hr: 120), hrRun(now - 4 * day, "Running", 12000, 4200, hr: 160),
            hrRun(now - 5 * day, "Running", 6000, 2100, hr: nil), hrRun(now - 6 * day, "Walking", 6000, 3600, hr: 150),
            hrRun(now - 7 * day, "Running", 2000, 600, hr: 170), hrRun(now - 8 * day, "Running", 7000, 2400, hr: 200),
        ]
        XCTAssertEqual("VDOTHR " + runs.map { f(R.vdotFromHr($0, restingHr: 50, hrMax: 190)) }.joined(separator: " "),
                       "VDOTHR 38.731311111 39.912992000 nil 36.846161039 nil nil nil nil")
        let inputs = R.Inputs(hrMax: 190, restingHr: 50, vo2max: 52)
        XCTAssertEqual("HRVDOT \(f(R.hrVdot(runs, inputs: inputs))) \(f(R.hrVdot(Array(runs.prefix(2)), inputs: inputs))) "
                        + "\(f(R.hrVdot([runs[2]], inputs: inputs))) "
                        + "\(f(R.hrVdot(runs, inputs: R.Inputs(hrMax: nil, restingHr: 50, vo2max: nil))))",
                       "HRVDOT 38.496821383 39.322151556 nil nil")
    }

    /// Every subset of components renormalises over its own weights; a lone component comes back as itself.
    func testBlendWeights() {
        var blends: [String] = []
        for r in [Double?.some(1500), nil] { for h in [Double?.some(1400), nil] { for v in [Double?.some(1300), nil] {
            blends.append(f(R.blend(R.Components(riegelS: r, hrS: h, vo2S: v))))
        }}}
        XCTAssertEqual("BLEND " + blends.joined(separator: " "), "BLEND 1430.000000000 1462.500000000 1442.857142857 1500.000000000 1360.000000000 " +
                       "1400.000000000 1300.000000000 nil")
        XCTAssertEqual("WEIGHTS \(f(R.riegelWeight())) \(f(R.hrWeight())) \(f(R.vo2Weight()))", "WEIGHTS 0.500000000 0.300000000 0.200000000")
    }

    /// Riegel alone reads exactly as before; heart rate and VO₂max each pull the times and fill the
    /// marathon as an estimate ("E") when no run is long enough; a non-positive VO₂max is ignored; with no
    /// recent run only VO₂max joins the old Riegel picks; nobody who does not run gets a prediction.
    func testBlendedPredictions() {
        let mixed = [
            hrRun(now - 10 * day, "Running", 5000, 1560, hr: 168), hrRun(now - 20 * day, "Running", 8000, 2900, hr: 140),
            hrRun(now - 30 * day, "Running", 6000, 2100, hr: 150), hrRun(now - 200 * day, "Running", 21097.5, 5400, hr: 165),
        ]
        let cases: [(String, R.Inputs)] = [
            ("NONE", R.Inputs(hrMax: nil, restingHr: nil, vo2max: nil)), ("HR", R.Inputs(hrMax: 190, restingHr: 50, vo2max: nil)),
            ("VO2", R.Inputs(hrMax: nil, restingHr: nil, vo2max: 50)), ("ALL", R.Inputs(hrMax: 190, restingHr: 50, vo2max: 50)),
            ("BADVO2", R.Inputs(hrMax: 190, restingHr: 50, vo2max: -3)),
        ]
        let lines = cases.map { name, inputs -> String in
            let r = R.blendedPredictions(runs: mixed, nowTs: now, inputs: inputs)
            return "PRED \(name) \(r.fromAllHistory ? "all" : "recent") " + r.estimates.map(est).joined(separator: " ")
        }
        XCTAssertEqual(lines, [
            "PRED NONE recent 0:1560[1560.000000000|nil|nil]R 1:3252[3252.000000000|nil|nil]R " +
            "2:7963[7963.000000000|nil|nil]R nil",
            "PRED HR recent 0:1508[1560.000000000|1421.412694314|nil]R " +
            "1:3138[3252.000000000|2948.912182022|nil]R 2:7429[7963.000000000|6538.680895379|nil]R " +
            "3:13547[nil|13547.257362012|nil]E",
            "PRED VO2 recent 0:1456[1560.000000000|nil|1196.020824328]R " +
            "1:3031[3252.000000000|nil|2479.603649337]R 2:7257[7963.000000000|nil|5491.197069311]R " +
            "3:11440[nil|nil|11439.741755151]E",
            "PRED ALL recent 0:1446[1560.000000000|1421.412694314|1196.020824328]R " +
            "1:3007[3252.000000000|2948.912182022|2479.603649337]R " +
            "2:7041[7963.000000000|6538.680895379|5491.197069311]R " +
            "3:12704[nil|13547.257362012|11439.741755151]E",
            "PRED BADVO2 recent 0:1508[1560.000000000|1421.412694314|nil]R " +
            "1:3138[3252.000000000|2948.912182022|nil]R 2:7429[7963.000000000|6538.680895379|nil]R " +
            "3:13547[nil|13547.257362012|nil]E",
        ])
        let riegelOnly = R.predictions(runs: mixed, nowTs: now)
        XCTAssertEqual(riegelOnly.estimates.map { $0?.seconds }, riegelOnly.items.map { $0?.seconds })
        let old = R.blendedPredictions(runs: [hrRun(now - 200 * day, "Running", 10000, 3000, hr: 160)], nowTs: now,
                                inputs: R.Inputs(hrMax: 190, restingHr: 50, vo2max: 48))
        XCTAssertEqual("PREDOLD \(old.fromAllHistory ? "all" : "recent") " + old.estimates.map(est).joined(separator: " "),
                       "PREDOLD all 0:1382[1439.000000000|nil|1238.491615985]R " +
                       "1:2876[3000.000000000|nil|2567.737140031]R 2:6353[6619.000000000|nil|5688.482961099]R " +
                       "3:11839[nil|nil|11838.993075231]E")
        let noRuns = R.blendedPredictions(runs: [hrRun(now, "Cycling", 30000, 3600, hr: 150)], nowTs: now,
                                   inputs: R.Inputs(hrMax: 190, restingHr: 50, vo2max: 55))
        XCTAssertEqual("PREDNORUN " + noRuns.estimates.map(est).joined(separator: " "), "PREDNORUN nil nil nil nil")
        let paces: [(Int, Int)] = [(1500, 0), (3127, 1), (6914, 2), (14271, 3), (100, 4), (100, -1)]
        XCTAssertEqual("PACE " + paces.map { f(R.paceSecPerKm(seconds: $0.0, targetIndex: $0.1)) }.joined(separator: " "),
                       "PACE 300.000000000 312.700000000 327.716554094 338.215428368 0.000000000 0.000000000")
    }

    /// Resting HR is the median of the seven days ending on the day (zeros ignored); VO₂max is the newest
    /// positive value up to 30 days old, the later point winning a shared day.
    func testInputsAsOfDay() {
        let rhr = [R.DayValue(epochDay: 100, value: 60), R.DayValue(epochDay: 101, value: 58), R.DayValue(epochDay: 103, value: 55),
                   R.DayValue(epochDay: 104, value: 0), R.DayValue(epochDay: 106, value: 57), R.DayValue(epochDay: 108, value: 52)]
        XCTAssertEqual("RHR " + [99, 100, 101, 103, 106, 107, 108, 112, 115, 116].map { f(R.restingHrAsOf(rhr, epochDay: $0)) }
                        .joined(separator: " "),
                       "RHR nil 60.000000000 59.000000000 58.000000000 57.500000000 57.000000000 55.000000000 " +
                       "54.500000000 nil nil")
        let vo2 = [R.DayValue(epochDay: 100, value: 48), R.DayValue(epochDay: 110, value: 49.5),
                   R.DayValue(epochDay: 110, value: 50.5), R.DayValue(epochDay: 120, value: -1),
                   R.DayValue(epochDay: 150, value: 51)]
        XCTAssertEqual("VO2AT " + [99, 100, 109, 110, 120, 130, 140, 141, 150, 180, 181].map { f(R.vo2maxAsOf(vo2, epochDay: $0)) }
                        .joined(separator: " "),
                       "VO2AT nil 48.000000000 48.000000000 50.500000000 50.500000000 50.500000000 50.500000000 " +
                       "nil 51.000000000 51.000000000 nil")
        XCTAssertEqual("KEYS " + R.historyKeys().joined(separator: " ")
                        + " \(R.historyDays()) \(R.restingHrDays()) \(R.vo2maxMaxAgeDays()) \(R.hrTopCount()) \(R.bisectionSteps())",
                       "KEYS race_pred_5k_s race_pred_10k_s race_pred_half_s race_pred_marathon_s 365 7 30 3 100")
    }

    /// Each day replays only the runs started by its end; today's point equals the live card; the changes
    /// write new or moved values, delete a stored day that no longer predicts, and skip unchanged days.
    func testDailyHistoryAndChanges() {
        let e0 = now / day
        let runs = [
            hrRun((e0 - 5) * day + 3600, "Running", 5000, 1500, hr: 165),
            hrRun((e0 - 3) * day + 3600, "Running", 10000, 3300, hr: 150),
            hrRun((e0 - 1) * day + 3600, "Running", 3000, 1000, hr: 140),
        ]
        let days = (0...6).map { k -> R.HistoryDay in
            let ed = e0 - 6 + k
            return R.HistoryDay(epochDay: ed, asOfTs: k == 6 ? now : (ed + 1) * day - 1,
                                inputs: R.Inputs(hrMax: 190, restingHr: 50, vo2max: k >= 4 ? 51 : nil))
        }
        let points = R.dailyHistory(runs: runs, days: days)
        XCTAssertEqual("HIST " + points.map { "\($0.epochDay)=" + $0.seconds.map(i).joined(separator: ",") }
                        .joined(separator: " "),
                       "HIST 20827=nil,nil,nil,nil 20828=1447,3011,6244,12958 20829=1447,3011,6244,12958 " +
                       "20830=1445,3008,6886,12922 20831=1391,2894,6588,12253 20832=1381,2872,6540,12062 " +
                       "20833=1381,2872,6540,12062")
        let live = R.blendedPredictions(runs: runs, nowTs: now, inputs: R.Inputs(hrMax: 190, restingHr: 50, vo2max: 51))
        XCTAssertEqual("LIVE " + live.estimates.map { i($0?.seconds) }.joined(separator: ","), "LIVE 1381,2872,6540,12062")
        let stored: [[R.DayValue]] = [
            [R.DayValue(epochDay: e0 - 6, value: 999), R.DayValue(epochDay: e0 - 4, value: Double(points[2].seconds[0] ?? 0)),
             R.DayValue(epochDay: e0 - 30, value: 1)],
            [R.DayValue(epochDay: e0 - 3, value: Double(points[3].seconds[1] ?? 0))],
            [],
            [R.DayValue(epochDay: e0 - 5, value: 12345)],
        ]
        XCTAssertEqual("CHANGES " + R.historyChanges(points: points, stored: stored)
                        .map { "\($0.epochDay - e0)/\($0.targetIndex)=\(i($0.seconds))" }.joined(separator: " "),
                       "CHANGES -6/0=nil -5/0=1447 -5/1=3011 -5/2=6244 -5/3=12958 -4/1=3011 -4/2=6244 -4/3=12958 " +
                       "-3/0=1445 -3/2=6886 -3/3=12922 -2/0=1391 -2/1=2894 -2/2=6588 -2/3=12253 -1/0=1381 " +
                       "-1/1=2872 -1/2=6540 -1/3=12062 0/0=1381 0/1=2872 0/2=6540 0/3=12062")
    }
}
