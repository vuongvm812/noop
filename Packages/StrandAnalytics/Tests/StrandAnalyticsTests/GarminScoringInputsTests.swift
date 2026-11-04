import XCTest
@testable import StrandAnalytics

/// Garmin child 9: the arbitration table entries for a Garmin watch, the per-fact scoring-input resolver,
/// the baseline era cut and the Effort label. `oracleOutput()` dumps every table cell and a spread of
/// resolver cases; the Kotlin twin (`GarminScoringInputsTest`) asserts the same text, pasted verbatim, so
/// the two platforms cannot drift. All values here are synthetic.
final class GarminScoringInputsTests: XCTestCase {

    // MARK: Arbitration table

    func testGarminWatchIsPrimaryForTheWatchMeasuredVitals() {
        for key in ["rhr", "hrv", "spo2", "resp_rate", "sleep_total_min", "sleep_deep_min", "steps", "stress"] {
            let kind = MetricArbitrationPolicy.kind(forKey: key)
            XCTAssertLessThan(MetricArbitrationPolicy.tier(metric: kind, source: .garminWatch),
                              MetricArbitrationPolicy.tier(metric: kind, source: .noopComputed),
                              "\(key): the watch's own value must beat NOOP's estimate")
        }
        XCTAssertEqual(MetricArbitrationPolicy.kind(forKey: "resp_rate"), .respiration)
        XCTAssertEqual(MetricArbitrationPolicy.reason(metric: .sleep, source: .garminWatch), "watch stages")
        XCTAssertEqual(MetricArbitrationPolicy.reason(metric: .steps, source: .garminWatch), "counts directly")
    }

    func testResolverPicksTheWatchVerbatimAndFlagsDisagreement() throws {
        let point = try XCTUnwrap(FusionResolver.resolve(metricKey: "rhr", inputs: [
            FusionInput(source: .noopComputed, value: 58), FusionInput(source: .garminWatch, value: 63)]))
        XCTAssertEqual(point.winningSource, .garminWatch)
        XCTAssertEqual(point.value, 63)
        XCTAssertEqual(point.agreement, .minorDelta)
    }

    func testStoredSourceIdMapping() {
        XCTAssertEqual(FusionSource.forStoredSourceId("garmin-0A1B2C3D-0000-4000-8000-00000000F00D"), .garminWatch)
        XCTAssertNil(FusionSource.forStoredSourceId("garmin-import"), "a Garmin Connect export is not the watch")
        XCTAssertNil(FusionSource.forStoredSourceId("garmin-abc-noop"))
        XCTAssertEqual(FusionSource.forStoredSourceId("my-whoop"), .whoopImport)
        XCTAssertEqual(FusionSource.forStoredSourceId("xiaomi-band"), .xiaomiBand)
    }

    // MARK: Scoring inputs

    private let night = [GarminScoringInputs.Night(start: 1_000, end: 10_000)]

    func testWatchValuesWinEveryScoringFact() {
        let r = GarminScoringInputs.resolve(
            computedRestingHr: 55, computedHrv: 30, computedResp: 14,
            watch: .init(restingHr: 63, lastNightHrvMs: 41), hrvSamples: [], watchRespRateBpm: 20, nights: night)
        XCTAssertEqual(r.restingHr, 63)
        XCTAssertEqual(r.restingHrSource, .garminWatch)
        XCTAssertEqual(r.avgHrv, 41)
        XCTAssertEqual(r.hrvBasis, .watchLastNight)
        XCTAssertEqual(r.respRateBpm, 20)
        XCTAssertEqual(r.respRateSource, .garminWatch)
    }

    func testOvernightHrvSamplesStandInForAMissingLastNightAverage() {
        // Twelve in-night values (an hour of five-minute samples, #53) plus one before the night that is ignored.
        let samples: [GarminScoringInputs.Sample] = [.init(ts: 500, value: 99)]
            + (0..<12).map { .init(ts: 1_000 + $0 * 750, value: $0 % 2 == 0 ? 40 : 48) }
        let r = GarminScoringInputs.resolve(computedRestingHr: nil, computedHrv: nil, computedResp: nil,
                                            watch: .init(restingHr: nil, lastNightHrvMs: nil),
                                            hrvSamples: samples, watchRespRateBpm: nil, nights: night)
        XCTAssertEqual(r.avgHrv, 44)
        XCTAssertEqual(r.hrvBasis, .watchOvernight)
    }

    func testTooFewNightSamplesFallBackToNoopsEstimate() {
        let r = GarminScoringInputs.resolve(computedRestingHr: 57, computedHrv: 33, computedResp: 15,
                                            watch: nil,
                                            hrvSamples: [.init(ts: 2_000, value: 40), .init(ts: 3_000, value: 42)],
                                            watchRespRateBpm: nil, nights: night)
        XCTAssertEqual(r.restingHr, 57)
        XCTAssertEqual(r.restingHrSource, .noopComputed)
        XCTAssertEqual(r.avgHrv, 33)
        XCTAssertEqual(r.hrvBasis, .computed)
        XCTAssertEqual(r.respRateBpm, 15)
    }

    /// #53: three five-minute values (a quarter hour) no longer stand for a night; twelve (an hour) do, and
    /// so does a short night the samples cover at least half of.
    func testNightMeanNeedsAnHourOrHalfTheNight() {
        let long = [GarminScoringInputs.Night(start: 0, end: 8 * 3_600)]
        let eleven = (0..<11).map { GarminScoringInputs.Sample(ts: 600 + $0 * 300, value: 50) }
        XCTAssertNil(GarminScoringInputs.nightMean(eleven, nights: long))
        let twelve = eleven + [.init(ts: 4_000, value: 62)]
        XCTAssertEqual(GarminScoringInputs.nightMean(twelve, nights: long), 51)
        let nap = [GarminScoringInputs.Night(start: 0, end: 1_800), .init(start: 900, end: 2_400)]
        XCTAssertEqual(GarminScoringInputs.sleepSeconds(nap), 2_400)
        XCTAssertEqual(GarminScoringInputs.nightMean((0..<4).map { .init(ts: $0 * 300, value: 40) }, nights: nap), 40)
        XCTAssertNil(GarminScoringInputs.nightMean((0..<3).map { .init(ts: $0 * 300, value: 40) }, nights: nap))
    }

    func testNoNightMeansNoOvernightMeans() {
        let r = GarminScoringInputs.resolve(computedRestingHr: nil, computedHrv: nil, computedResp: nil,
                                            watch: .init(restingHr: 61, lastNightHrvMs: nil),
                                            hrvSamples: [.init(ts: 2_000, value: 40), .init(ts: 3_000, value: 42),
                                                         .init(ts: 4_000, value: 44)],
                                            watchRespRateBpm: nil, nights: [])
        XCTAssertEqual(r.restingHr, 61)
        XCTAssertNil(r.avgHrv, "HRV outside any sleep block is not an overnight value")
        XCTAssertEqual(r.hrvBasis, .none)
        XCTAssertNil(r.respRateBpm)
    }

    func testRespirationIsTheStoredOvernightValue() {
        // The row's overnight value (`WhoopStore.garminNightMean`) is the only watch respiration: scoring
        // takes it verbatim, so Charge and the `resp_rate` series show one number (#52).
        let r = GarminScoringInputs.resolve(computedRestingHr: nil, computedHrv: nil, computedResp: 13,
                                            watch: .init(restingHr: nil, lastNightHrvMs: nil),
                                            hrvSamples: [], watchRespRateBpm: 15.5, nights: [])
        XCTAssertEqual(r.respRateBpm, 15.5)
        XCTAssertEqual(r.respRateSource, .garminWatch)
    }

    // MARK: Baseline era

    func testEraCutsAtTheWhoopToGarminSwitch() {
        let days: [(day: String, sourceId: String)] = [
            ("2026-10-01", "my-whoop"), ("2026-10-02", "my-whoop"),
            ("2026-10-03", "garmin-AAA"), ("2026-10-04", "garmin-AAA")]
        XCTAssertEqual(GarminScoringInputs.baselineEraEpoch(days), 1_790_985_600)   // 2026-10-03 UTC midnight
    }

    func testNoGarminDayKeepsTheOldBehaviour() {
        let days: [(day: String, sourceId: String)] = [("2026-10-01", "my-whoop"), ("2026-10-02", "oura-import")]
        XCTAssertEqual(GarminScoringInputs.baselineEraEpoch(days), 0,
                       "a WHOOP/Oura history must stay byte-identical to the pre-Garmin fold")
    }

    // MARK: Effort label

    func testGarminEffortCarriesNoExperimentalLabel() {
        XCTAssertTrue(GarminScoringInputs.effortValidated, "Garmin is official (#43)")
        XCTAssertFalse(GarminScoringInputs.effortIsExperimental(sourceId: "garmin-AAA", brand: "Garmin"))
        XCTAssertFalse(GarminScoringInputs.effortIsExperimental(sourceId: "strap-x", brand: "garmin"))
        XCTAssertFalse(GarminScoringInputs.effortIsExperimental(sourceId: "my-whoop", brand: "WHOOP"))
        XCTAssertFalse(GarminScoringInputs.effortIsExperimental(sourceId: "garmin-import", brand: nil))
    }

    // MARK: Oracle

    static func fmt(_ v: Double?) -> String { v.map { String(format: "%.4f", $0) } ?? "nil" }

    static func oracleOutput() -> String {
        var lines: [String] = []
        for kind in MetricArbitrationPolicy.MetricKind.allCases {
            let tol = MetricArbitrationPolicy.tolerance(metric: kind)
            lines.append("tol \(kind.rawValue) \(fmt(tol.agree)) \(fmt(tol.minorDelta)) \(tol.isPercent)")
            for source in FusionSource.allCases {
                lines.append("tier \(kind.rawValue) \(source.rawValue) \(MetricArbitrationPolicy.tier(metric: kind, source: source)) "
                             + "\(MetricArbitrationPolicy.reason(metric: kind, source: source))")
            }
        }
        for source in FusionSource.allCases {
            lines.append("source \(source.rawValue) \(MetricArbitrationPolicy.sourcePriority(source)) \(source.displayName)")
        }
        for key in ["rhr", "resting_hr", "hrv", "spo2", "resp_rate", "respiration", "stress", "body_battery_min",
                    "ref_sleep_score", "sleep_total_min", "steps", "energy_kcal"] {
            lines.append("kind \(key) \(MetricArbitrationPolicy.kind(forKey: key).rawValue)")
        }
        for id in ["garmin-0A1B2C3D", "garmin-AA:BB:CC:00:11:22", "garmin-import", "garmin-x-noop", "garmin",
                   "my-whoop", "my-whoop-noop", "apple-health", "xiaomi-band", "oura-import"] {
            lines.append("stored \(id) \(FusionSource.forStoredSourceId(id)?.rawValue ?? "nil")")
        }

        // Resolver cases: (computed rhr, hrv, resp) × watch × samples × nights.
        let nights = [GarminScoringInputs.Night(start: 1_000, end: 5_000), .init(start: 4_000, end: 9_000)]
        let hrvSets: [[GarminScoringInputs.Sample]] = [
            [],
            [.init(ts: 2_000, value: 38), .init(ts: 3_000, value: 41)],
            [.init(ts: 999, value: 90), .init(ts: 2_000, value: 38), .init(ts: 4_500, value: 41),
             .init(ts: 9_000, value: 47), .init(ts: 9_001, value: 80)],
            [.init(ts: 2_000, value: -1), .init(ts: 3_000, value: 0), .init(ts: 4_000, value: 35),
             .init(ts: 5_000, value: 36), .init(ts: 6_000, value: 37.5)],
            (0..<12).map { .init(ts: 1_200 + $0 * 600, value: 40 + Double($0) / 4) },
        ]
        // The watch row's stored overnight respiration.
        let respValues: [Double?] = [nil, 19.583_333_333_333_332]
        let watches: [GarminScoringInputs.WatchDay?] = [
            nil, .init(restingHr: nil, lastNightHrvMs: nil), .init(restingHr: 63, lastNightHrvMs: nil),
            .init(restingHr: 49, lastNightHrvMs: 52.5), .init(restingHr: 0, lastNightHrvMs: 0),
        ]
        let computeds: [(Int?, Double?, Double?)] = [(nil, nil, nil), (58, 31.75, 14.2), (75, nil, 19)]
        for (ci, c) in computeds.enumerated() {
            for (wi, w) in watches.enumerated() {
                for (hi, h) in hrvSets.enumerated() {
                    for (ri, rv) in respValues.enumerated() {
                        for (ni, n) in [[], nights, [.init(start: 1_000, end: 2_500)]].enumerated() {
                            // A stored respiration only exists with a watch row.
                            let r = GarminScoringInputs.resolve(computedRestingHr: c.0, computedHrv: c.1,
                                                                computedResp: c.2, watch: w, hrvSamples: h,
                                                                watchRespRateBpm: w == nil ? nil : rv, nights: n)
                            lines.append("resolve c\(ci) w\(wi) h\(hi) r\(ri) n\(ni) -> rhr=\(r.restingHr.map(String.init) ?? "nil")"
                                         + "/\(r.restingHrSource?.rawValue ?? "nil") hrv=\(fmt(r.avgHrv))"
                                         + "/\(r.avgHrvSource?.rawValue ?? "nil")/\(r.hrvBasis.rawValue)"
                                         + " resp=\(fmt(r.respRateBpm))/\(r.respRateSource?.rawValue ?? "nil")")
                        }
                    }
                }
            }
        }

        let eraCases: [[(day: String, sourceId: String)]] = [
            [],
            [("2026-10-01", "my-whoop")],
            [("2026-10-01", "my-whoop"), ("2026-10-02", "garmin-A")],
            [("2026-10-01", "garmin-A"), ("2026-10-02", "my-whoop")],
            [("2026-10-01", "garmin-A"), ("2026-10-02", "garmin-B")],
            [("2026-10-01", "oura-import"), ("2026-10-02", "my-whoop")],
            [("2026-10-03", "garmin-A"), ("2026-10-01", "my-whoop"), ("2026-10-02", "garmin-import")],
            [("2026-10-02", "my-whoop"), ("2026-10-02", "garmin-A")],
        ]
        for (i, days) in eraCases.enumerated() {
            lines.append("era \(i) \(fmt(GarminScoringInputs.baselineEraEpoch(days)))")
        }
        for (id, brand) in [("garmin-A", "Garmin"), ("garmin-A", nil), ("strap-1", "Garmin"), ("strap-1", "Polar"),
                            ("garmin-import", "Garmin"), ("my-whoop", nil), ("garmin-A-noop", nil)] as [(String, String?)] {
            lines.append("effort \(id) \(brand ?? "nil") \(GarminScoringInputs.effortIsExperimental(sourceId: id, brand: brand))")
        }
        // The watch's own VO2max for a week (#51): latest on or before the key, within the age window.
        let vo2Points: [(day: String, value: Double)] = [("2026-10-01", 50.1), ("2026-10-04", 51.0), ("2026-10-31", 52.4),
                                                         ("2026-11-08", 53.0), ("2026-10-20", -1)]
        for week in ["2026-10-31", "2026-10-30", "2026-11-07", "2026-11-08", "2026-12-31", "bad"] {
            lines.append("vo2 \(week) \(GarminScoringInputs.watchVo2max(vo2Points, weekKey: week).map { String($0) } ?? "nil")")
        }
        // #71: the watch's points first, the export's on the days the watch lacks; then every past week the
        // 28-day rule covers.
        let imported: [(day: String, value: Double)] = [("2026-09-20", 48.0), ("2026-10-04", 49.0), ("2026-10-05", 47.5),
                                                        ("2026-10-06", -1), ("2026-09-01", .nan), ("2026-09-02", .infinity)]
        let merged = GarminScoringInputs.vo2maxPoints(
            watch: [("2026-10-04", 51.0), ("2026-10-01", 50.1), ("2026-10-05", 0)], imported: imported)
        lines.append("merge " + merged.map { "\($0.day)=\($0.value)" }.joined(separator: " "))
        lines.append("merge-watch-only " + GarminScoringInputs.vo2maxPoints(watch: vo2Points, imported: [])
            .map { "\($0.day)=\($0.value)" }.joined(separator: " "))
        lines.append("merge-import-only " + GarminScoringInputs.vo2maxPoints(watch: [], imported: imported)
            .map { "\($0.day)=\($0.value)" }.joined(separator: " "))
        let weekSets: [[(day: String, value: Double)]] = [
            merged, [], [("2026-10-03", 44.0)], [("2026-01-01", 40.0), ("2026-03-15", 42.0)],
            [("2025-12-30", 41.0), ("2026-01-20", -2)],
        ]
        for (i, points) in weekSets.enumerated() {
            for before in ["2026-11-07", "2026-10-03", "2026-02-07", "bad"] {
                let weeks = GarminScoringInputs.watchVo2maxWeeks(points, beforeWeekKey: before)
                lines.append("weeks \(i) \(before) [" + weeks.map { "\($0.week)=\($0.value)" }.joined(separator: " ") + "]")
            }
        }
        return lines.joined(separator: "\n")
    }

    func testOracleMatchesThePinnedOutput() throws {
        if let path = ProcessInfo.processInfo.environment["GARMIN_SCORING_ORACLE_DUMP"] {
            try Self.oracleOutput().write(toFile: path, atomically: true, encoding: .utf8)
        }
        XCTAssertEqual(Self.oracleOutput(), Self.expected)
    }

    /// Oracle stdout, pasted verbatim. Also pinned in the Kotlin test (`GarminScoringInputsTest.EXPECTED`).
    static let expected = """
    tol restingHR 3.0000 8.0000 false
    tier restingHR my-whoop 0 direct sensor
    tier restingHR my-whoop-noop 1 computed on device
    tier restingHR apple-health 2 phone aggregate
    tier restingHR health-connect 2 phone aggregate
    tier restingHR xiaomi-band 0 direct sensor
    tier restingHR garmin-watch 0 direct sensor
    tier restingHR nutrition-csv 3 estimate
    tier restingHR local-cache 3 estimate
    tol heartRate 5.0000 12.0000 false
    tier heartRate my-whoop 0 direct sensor
    tier heartRate my-whoop-noop 1 computed on device
    tier heartRate apple-health 2 phone aggregate
    tier heartRate health-connect 2 phone aggregate
    tier heartRate xiaomi-band 0 direct sensor
    tier heartRate garmin-watch 0 direct sensor
    tier heartRate nutrition-csv 3 estimate
    tier heartRate local-cache 3 estimate
    tol hrv 8.0000 20.0000 false
    tier hrv my-whoop 0 direct sensor
    tier hrv my-whoop-noop 1 computed on device
    tier hrv apple-health 2 phone aggregate
    tier hrv health-connect 2 phone aggregate
    tier hrv xiaomi-band 0 direct sensor
    tier hrv garmin-watch 0 direct sensor
    tier hrv nutrition-csv 3 estimate
    tier hrv local-cache 3 estimate
    tol spo2 2.0000 4.0000 false
    tier spo2 my-whoop 0 direct sensor
    tier spo2 my-whoop-noop 1 computed on device
    tier spo2 apple-health 2 phone aggregate
    tier spo2 health-connect 2 phone aggregate
    tier spo2 xiaomi-band 0 direct sensor
    tier spo2 garmin-watch 0 direct sensor
    tier spo2 nutrition-csv 3 estimate
    tier spo2 local-cache 3 estimate
    tol respiration 1.0000 3.0000 false
    tier respiration my-whoop 0 direct sensor
    tier respiration my-whoop-noop 1 computed on device
    tier respiration apple-health 2 phone aggregate
    tier respiration health-connect 2 phone aggregate
    tier respiration xiaomi-band 0 direct sensor
    tier respiration garmin-watch 0 direct sensor
    tier respiration nutrition-csv 3 estimate
    tier respiration local-cache 3 estimate
    tol skinTemp 0.5000 1.5000 false
    tier skinTemp my-whoop 0 worn sensor
    tier skinTemp my-whoop-noop 1 worn sensor
    tier skinTemp apple-health 2 worn sensor
    tier skinTemp health-connect 2 worn sensor
    tier skinTemp xiaomi-band 0 worn sensor
    tier skinTemp garmin-watch 0 worn sensor
    tier skinTemp nutrition-csv 3 worn sensor
    tier skinTemp local-cache 3 worn sensor
    tol steps 0.1000 0.3000 true
    tier steps my-whoop 3 step estimate
    tier steps my-whoop-noop 3 step estimate
    tier steps apple-health 0 counts directly
    tier steps health-connect 0 counts directly
    tier steps xiaomi-band 0 counts directly
    tier steps garmin-watch 0 counts directly
    tier steps nutrition-csv 3 estimate
    tier steps local-cache 3 estimate
    tol sleep 20.0000 60.0000 false
    tier sleep my-whoop 0 best stager
    tier sleep my-whoop-noop 1 computed stages
    tier sleep apple-health 2 phone sleep buckets
    tier sleep health-connect 2 phone sleep buckets
    tier sleep xiaomi-band 1 computed on device
    tier sleep garmin-watch 0 watch stages
    tier sleep nutrition-csv 3 estimate
    tier sleep local-cache 3 estimate
    tol calories 0.1500 0.4000 true
    tier calories my-whoop 3 estimate
    tier calories my-whoop-noop 3 estimate
    tier calories apple-health 2 phone aggregate
    tier calories health-connect 2 phone aggregate
    tier calories xiaomi-band 3 estimate
    tier calories garmin-watch 3 estimate
    tier calories nutrition-csv 3 estimate
    tier calories local-cache 3 estimate
    tol other 0.1000 0.3000 true
    tier other my-whoop 0 direct sensor
    tier other my-whoop-noop 1 computed on device
    tier other apple-health 2 phone aggregate
    tier other health-connect 2 phone aggregate
    tier other xiaomi-band 0 direct sensor
    tier other garmin-watch 0 direct sensor
    tier other nutrition-csv 0 direct sensor
    tier other local-cache 3 estimate
    source my-whoop 0 WHOOP
    source my-whoop-noop 1 NOOP
    source apple-health 2 Apple Health
    source health-connect 3 Health Connect
    source xiaomi-band 4 Mi Band
    source garmin-watch 5 Garmin
    source nutrition-csv 6 Nutrition
    source local-cache 7 Cached
    kind rhr restingHR
    kind resting_hr restingHR
    kind hrv hrv
    kind spo2 spo2
    kind resp_rate respiration
    kind respiration respiration
    kind stress other
    kind body_battery_min other
    kind ref_sleep_score other
    kind sleep_total_min sleep
    kind steps steps
    kind energy_kcal calories
    stored garmin-0A1B2C3D garmin-watch
    stored garmin-AA:BB:CC:00:11:22 garmin-watch
    stored garmin-import nil
    stored garmin-x-noop nil
    stored garmin nil
    stored my-whoop my-whoop
    stored my-whoop-noop my-whoop-noop
    stored apple-health apple-health
    stored xiaomi-band xiaomi-band
    stored oura-import nil
    resolve c0 w0 h0 r0 n0 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
    resolve c0 w0 h0 r0 n1 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
    resolve c0 w0 h0 r0 n2 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
    resolve c0 w0 h0 r1 n0 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
    resolve c0 w0 h0 r1 n1 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
    resolve c0 w0 h0 r1 n2 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
    resolve c0 w0 h1 r0 n0 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
    resolve c0 w0 h1 r0 n1 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
    resolve c0 w0 h1 r0 n2 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
    resolve c0 w0 h1 r1 n0 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
    resolve c0 w0 h1 r1 n1 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
    resolve c0 w0 h1 r1 n2 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
    resolve c0 w0 h2 r0 n0 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
    resolve c0 w0 h2 r0 n1 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
    resolve c0 w0 h2 r0 n2 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
    resolve c0 w0 h2 r1 n0 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
    resolve c0 w0 h2 r1 n1 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
    resolve c0 w0 h2 r1 n2 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
    resolve c0 w0 h3 r0 n0 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
    resolve c0 w0 h3 r0 n1 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
    resolve c0 w0 h3 r0 n2 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
    resolve c0 w0 h3 r1 n0 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
    resolve c0 w0 h3 r1 n1 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
    resolve c0 w0 h3 r1 n2 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
    resolve c0 w0 h4 r0 n0 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
    resolve c0 w0 h4 r0 n1 -> rhr=nil/nil hrv=41.3750/garmin-watch/fit371 resp=nil/nil
    resolve c0 w0 h4 r0 n2 -> rhr=nil/nil hrv=40.2500/garmin-watch/fit371 resp=nil/nil
    resolve c0 w0 h4 r1 n0 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
    resolve c0 w0 h4 r1 n1 -> rhr=nil/nil hrv=41.3750/garmin-watch/fit371 resp=nil/nil
    resolve c0 w0 h4 r1 n2 -> rhr=nil/nil hrv=40.2500/garmin-watch/fit371 resp=nil/nil
    resolve c0 w1 h0 r0 n0 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
    resolve c0 w1 h0 r0 n1 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
    resolve c0 w1 h0 r0 n2 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
    resolve c0 w1 h0 r1 n0 -> rhr=nil/nil hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c0 w1 h0 r1 n1 -> rhr=nil/nil hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c0 w1 h0 r1 n2 -> rhr=nil/nil hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c0 w1 h1 r0 n0 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
    resolve c0 w1 h1 r0 n1 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
    resolve c0 w1 h1 r0 n2 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
    resolve c0 w1 h1 r1 n0 -> rhr=nil/nil hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c0 w1 h1 r1 n1 -> rhr=nil/nil hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c0 w1 h1 r1 n2 -> rhr=nil/nil hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c0 w1 h2 r0 n0 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
    resolve c0 w1 h2 r0 n1 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
    resolve c0 w1 h2 r0 n2 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
    resolve c0 w1 h2 r1 n0 -> rhr=nil/nil hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c0 w1 h2 r1 n1 -> rhr=nil/nil hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c0 w1 h2 r1 n2 -> rhr=nil/nil hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c0 w1 h3 r0 n0 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
    resolve c0 w1 h3 r0 n1 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
    resolve c0 w1 h3 r0 n2 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
    resolve c0 w1 h3 r1 n0 -> rhr=nil/nil hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c0 w1 h3 r1 n1 -> rhr=nil/nil hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c0 w1 h3 r1 n2 -> rhr=nil/nil hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c0 w1 h4 r0 n0 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
    resolve c0 w1 h4 r0 n1 -> rhr=nil/nil hrv=41.3750/garmin-watch/fit371 resp=nil/nil
    resolve c0 w1 h4 r0 n2 -> rhr=nil/nil hrv=40.2500/garmin-watch/fit371 resp=nil/nil
    resolve c0 w1 h4 r1 n0 -> rhr=nil/nil hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c0 w1 h4 r1 n1 -> rhr=nil/nil hrv=41.3750/garmin-watch/fit371 resp=19.5833/garmin-watch
    resolve c0 w1 h4 r1 n2 -> rhr=nil/nil hrv=40.2500/garmin-watch/fit371 resp=19.5833/garmin-watch
    resolve c0 w2 h0 r0 n0 -> rhr=63/garmin-watch hrv=nil/nil/none resp=nil/nil
    resolve c0 w2 h0 r0 n1 -> rhr=63/garmin-watch hrv=nil/nil/none resp=nil/nil
    resolve c0 w2 h0 r0 n2 -> rhr=63/garmin-watch hrv=nil/nil/none resp=nil/nil
    resolve c0 w2 h0 r1 n0 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c0 w2 h0 r1 n1 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c0 w2 h0 r1 n2 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c0 w2 h1 r0 n0 -> rhr=63/garmin-watch hrv=nil/nil/none resp=nil/nil
    resolve c0 w2 h1 r0 n1 -> rhr=63/garmin-watch hrv=nil/nil/none resp=nil/nil
    resolve c0 w2 h1 r0 n2 -> rhr=63/garmin-watch hrv=nil/nil/none resp=nil/nil
    resolve c0 w2 h1 r1 n0 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c0 w2 h1 r1 n1 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c0 w2 h1 r1 n2 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c0 w2 h2 r0 n0 -> rhr=63/garmin-watch hrv=nil/nil/none resp=nil/nil
    resolve c0 w2 h2 r0 n1 -> rhr=63/garmin-watch hrv=nil/nil/none resp=nil/nil
    resolve c0 w2 h2 r0 n2 -> rhr=63/garmin-watch hrv=nil/nil/none resp=nil/nil
    resolve c0 w2 h2 r1 n0 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c0 w2 h2 r1 n1 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c0 w2 h2 r1 n2 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c0 w2 h3 r0 n0 -> rhr=63/garmin-watch hrv=nil/nil/none resp=nil/nil
    resolve c0 w2 h3 r0 n1 -> rhr=63/garmin-watch hrv=nil/nil/none resp=nil/nil
    resolve c0 w2 h3 r0 n2 -> rhr=63/garmin-watch hrv=nil/nil/none resp=nil/nil
    resolve c0 w2 h3 r1 n0 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c0 w2 h3 r1 n1 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c0 w2 h3 r1 n2 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c0 w2 h4 r0 n0 -> rhr=63/garmin-watch hrv=nil/nil/none resp=nil/nil
    resolve c0 w2 h4 r0 n1 -> rhr=63/garmin-watch hrv=41.3750/garmin-watch/fit371 resp=nil/nil
    resolve c0 w2 h4 r0 n2 -> rhr=63/garmin-watch hrv=40.2500/garmin-watch/fit371 resp=nil/nil
    resolve c0 w2 h4 r1 n0 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c0 w2 h4 r1 n1 -> rhr=63/garmin-watch hrv=41.3750/garmin-watch/fit371 resp=19.5833/garmin-watch
    resolve c0 w2 h4 r1 n2 -> rhr=63/garmin-watch hrv=40.2500/garmin-watch/fit371 resp=19.5833/garmin-watch
    resolve c0 w3 h0 r0 n0 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=nil/nil
    resolve c0 w3 h0 r0 n1 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=nil/nil
    resolve c0 w3 h0 r0 n2 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=nil/nil
    resolve c0 w3 h0 r1 n0 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
    resolve c0 w3 h0 r1 n1 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
    resolve c0 w3 h0 r1 n2 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
    resolve c0 w3 h1 r0 n0 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=nil/nil
    resolve c0 w3 h1 r0 n1 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=nil/nil
    resolve c0 w3 h1 r0 n2 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=nil/nil
    resolve c0 w3 h1 r1 n0 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
    resolve c0 w3 h1 r1 n1 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
    resolve c0 w3 h1 r1 n2 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
    resolve c0 w3 h2 r0 n0 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=nil/nil
    resolve c0 w3 h2 r0 n1 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=nil/nil
    resolve c0 w3 h2 r0 n2 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=nil/nil
    resolve c0 w3 h2 r1 n0 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
    resolve c0 w3 h2 r1 n1 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
    resolve c0 w3 h2 r1 n2 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
    resolve c0 w3 h3 r0 n0 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=nil/nil
    resolve c0 w3 h3 r0 n1 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=nil/nil
    resolve c0 w3 h3 r0 n2 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=nil/nil
    resolve c0 w3 h3 r1 n0 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
    resolve c0 w3 h3 r1 n1 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
    resolve c0 w3 h3 r1 n2 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
    resolve c0 w3 h4 r0 n0 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=nil/nil
    resolve c0 w3 h4 r0 n1 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=nil/nil
    resolve c0 w3 h4 r0 n2 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=nil/nil
    resolve c0 w3 h4 r1 n0 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
    resolve c0 w3 h4 r1 n1 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
    resolve c0 w3 h4 r1 n2 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
    resolve c0 w4 h0 r0 n0 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
    resolve c0 w4 h0 r0 n1 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
    resolve c0 w4 h0 r0 n2 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
    resolve c0 w4 h0 r1 n0 -> rhr=nil/nil hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c0 w4 h0 r1 n1 -> rhr=nil/nil hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c0 w4 h0 r1 n2 -> rhr=nil/nil hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c0 w4 h1 r0 n0 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
    resolve c0 w4 h1 r0 n1 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
    resolve c0 w4 h1 r0 n2 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
    resolve c0 w4 h1 r1 n0 -> rhr=nil/nil hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c0 w4 h1 r1 n1 -> rhr=nil/nil hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c0 w4 h1 r1 n2 -> rhr=nil/nil hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c0 w4 h2 r0 n0 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
    resolve c0 w4 h2 r0 n1 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
    resolve c0 w4 h2 r0 n2 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
    resolve c0 w4 h2 r1 n0 -> rhr=nil/nil hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c0 w4 h2 r1 n1 -> rhr=nil/nil hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c0 w4 h2 r1 n2 -> rhr=nil/nil hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c0 w4 h3 r0 n0 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
    resolve c0 w4 h3 r0 n1 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
    resolve c0 w4 h3 r0 n2 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
    resolve c0 w4 h3 r1 n0 -> rhr=nil/nil hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c0 w4 h3 r1 n1 -> rhr=nil/nil hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c0 w4 h3 r1 n2 -> rhr=nil/nil hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c0 w4 h4 r0 n0 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
    resolve c0 w4 h4 r0 n1 -> rhr=nil/nil hrv=41.3750/garmin-watch/fit371 resp=nil/nil
    resolve c0 w4 h4 r0 n2 -> rhr=nil/nil hrv=40.2500/garmin-watch/fit371 resp=nil/nil
    resolve c0 w4 h4 r1 n0 -> rhr=nil/nil hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c0 w4 h4 r1 n1 -> rhr=nil/nil hrv=41.3750/garmin-watch/fit371 resp=19.5833/garmin-watch
    resolve c0 w4 h4 r1 n2 -> rhr=nil/nil hrv=40.2500/garmin-watch/fit371 resp=19.5833/garmin-watch
    resolve c1 w0 h0 r0 n0 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
    resolve c1 w0 h0 r0 n1 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
    resolve c1 w0 h0 r0 n2 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
    resolve c1 w0 h0 r1 n0 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
    resolve c1 w0 h0 r1 n1 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
    resolve c1 w0 h0 r1 n2 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
    resolve c1 w0 h1 r0 n0 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
    resolve c1 w0 h1 r0 n1 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
    resolve c1 w0 h1 r0 n2 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
    resolve c1 w0 h1 r1 n0 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
    resolve c1 w0 h1 r1 n1 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
    resolve c1 w0 h1 r1 n2 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
    resolve c1 w0 h2 r0 n0 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
    resolve c1 w0 h2 r0 n1 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
    resolve c1 w0 h2 r0 n2 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
    resolve c1 w0 h2 r1 n0 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
    resolve c1 w0 h2 r1 n1 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
    resolve c1 w0 h2 r1 n2 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
    resolve c1 w0 h3 r0 n0 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
    resolve c1 w0 h3 r0 n1 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
    resolve c1 w0 h3 r0 n2 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
    resolve c1 w0 h3 r1 n0 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
    resolve c1 w0 h3 r1 n1 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
    resolve c1 w0 h3 r1 n2 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
    resolve c1 w0 h4 r0 n0 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
    resolve c1 w0 h4 r0 n1 -> rhr=58/my-whoop-noop hrv=41.3750/garmin-watch/fit371 resp=14.2000/my-whoop-noop
    resolve c1 w0 h4 r0 n2 -> rhr=58/my-whoop-noop hrv=40.2500/garmin-watch/fit371 resp=14.2000/my-whoop-noop
    resolve c1 w0 h4 r1 n0 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
    resolve c1 w0 h4 r1 n1 -> rhr=58/my-whoop-noop hrv=41.3750/garmin-watch/fit371 resp=14.2000/my-whoop-noop
    resolve c1 w0 h4 r1 n2 -> rhr=58/my-whoop-noop hrv=40.2500/garmin-watch/fit371 resp=14.2000/my-whoop-noop
    resolve c1 w1 h0 r0 n0 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
    resolve c1 w1 h0 r0 n1 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
    resolve c1 w1 h0 r0 n2 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
    resolve c1 w1 h0 r1 n0 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
    resolve c1 w1 h0 r1 n1 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
    resolve c1 w1 h0 r1 n2 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
    resolve c1 w1 h1 r0 n0 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
    resolve c1 w1 h1 r0 n1 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
    resolve c1 w1 h1 r0 n2 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
    resolve c1 w1 h1 r1 n0 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
    resolve c1 w1 h1 r1 n1 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
    resolve c1 w1 h1 r1 n2 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
    resolve c1 w1 h2 r0 n0 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
    resolve c1 w1 h2 r0 n1 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
    resolve c1 w1 h2 r0 n2 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
    resolve c1 w1 h2 r1 n0 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
    resolve c1 w1 h2 r1 n1 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
    resolve c1 w1 h2 r1 n2 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
    resolve c1 w1 h3 r0 n0 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
    resolve c1 w1 h3 r0 n1 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
    resolve c1 w1 h3 r0 n2 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
    resolve c1 w1 h3 r1 n0 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
    resolve c1 w1 h3 r1 n1 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
    resolve c1 w1 h3 r1 n2 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
    resolve c1 w1 h4 r0 n0 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
    resolve c1 w1 h4 r0 n1 -> rhr=58/my-whoop-noop hrv=41.3750/garmin-watch/fit371 resp=14.2000/my-whoop-noop
    resolve c1 w1 h4 r0 n2 -> rhr=58/my-whoop-noop hrv=40.2500/garmin-watch/fit371 resp=14.2000/my-whoop-noop
    resolve c1 w1 h4 r1 n0 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
    resolve c1 w1 h4 r1 n1 -> rhr=58/my-whoop-noop hrv=41.3750/garmin-watch/fit371 resp=19.5833/garmin-watch
    resolve c1 w1 h4 r1 n2 -> rhr=58/my-whoop-noop hrv=40.2500/garmin-watch/fit371 resp=19.5833/garmin-watch
    resolve c1 w2 h0 r0 n0 -> rhr=63/garmin-watch hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
    resolve c1 w2 h0 r0 n1 -> rhr=63/garmin-watch hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
    resolve c1 w2 h0 r0 n2 -> rhr=63/garmin-watch hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
    resolve c1 w2 h0 r1 n0 -> rhr=63/garmin-watch hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
    resolve c1 w2 h0 r1 n1 -> rhr=63/garmin-watch hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
    resolve c1 w2 h0 r1 n2 -> rhr=63/garmin-watch hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
    resolve c1 w2 h1 r0 n0 -> rhr=63/garmin-watch hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
    resolve c1 w2 h1 r0 n1 -> rhr=63/garmin-watch hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
    resolve c1 w2 h1 r0 n2 -> rhr=63/garmin-watch hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
    resolve c1 w2 h1 r1 n0 -> rhr=63/garmin-watch hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
    resolve c1 w2 h1 r1 n1 -> rhr=63/garmin-watch hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
    resolve c1 w2 h1 r1 n2 -> rhr=63/garmin-watch hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
    resolve c1 w2 h2 r0 n0 -> rhr=63/garmin-watch hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
    resolve c1 w2 h2 r0 n1 -> rhr=63/garmin-watch hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
    resolve c1 w2 h2 r0 n2 -> rhr=63/garmin-watch hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
    resolve c1 w2 h2 r1 n0 -> rhr=63/garmin-watch hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
    resolve c1 w2 h2 r1 n1 -> rhr=63/garmin-watch hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
    resolve c1 w2 h2 r1 n2 -> rhr=63/garmin-watch hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
    resolve c1 w2 h3 r0 n0 -> rhr=63/garmin-watch hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
    resolve c1 w2 h3 r0 n1 -> rhr=63/garmin-watch hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
    resolve c1 w2 h3 r0 n2 -> rhr=63/garmin-watch hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
    resolve c1 w2 h3 r1 n0 -> rhr=63/garmin-watch hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
    resolve c1 w2 h3 r1 n1 -> rhr=63/garmin-watch hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
    resolve c1 w2 h3 r1 n2 -> rhr=63/garmin-watch hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
    resolve c1 w2 h4 r0 n0 -> rhr=63/garmin-watch hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
    resolve c1 w2 h4 r0 n1 -> rhr=63/garmin-watch hrv=41.3750/garmin-watch/fit371 resp=14.2000/my-whoop-noop
    resolve c1 w2 h4 r0 n2 -> rhr=63/garmin-watch hrv=40.2500/garmin-watch/fit371 resp=14.2000/my-whoop-noop
    resolve c1 w2 h4 r1 n0 -> rhr=63/garmin-watch hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
    resolve c1 w2 h4 r1 n1 -> rhr=63/garmin-watch hrv=41.3750/garmin-watch/fit371 resp=19.5833/garmin-watch
    resolve c1 w2 h4 r1 n2 -> rhr=63/garmin-watch hrv=40.2500/garmin-watch/fit371 resp=19.5833/garmin-watch
    resolve c1 w3 h0 r0 n0 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=14.2000/my-whoop-noop
    resolve c1 w3 h0 r0 n1 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=14.2000/my-whoop-noop
    resolve c1 w3 h0 r0 n2 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=14.2000/my-whoop-noop
    resolve c1 w3 h0 r1 n0 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
    resolve c1 w3 h0 r1 n1 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
    resolve c1 w3 h0 r1 n2 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
    resolve c1 w3 h1 r0 n0 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=14.2000/my-whoop-noop
    resolve c1 w3 h1 r0 n1 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=14.2000/my-whoop-noop
    resolve c1 w3 h1 r0 n2 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=14.2000/my-whoop-noop
    resolve c1 w3 h1 r1 n0 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
    resolve c1 w3 h1 r1 n1 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
    resolve c1 w3 h1 r1 n2 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
    resolve c1 w3 h2 r0 n0 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=14.2000/my-whoop-noop
    resolve c1 w3 h2 r0 n1 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=14.2000/my-whoop-noop
    resolve c1 w3 h2 r0 n2 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=14.2000/my-whoop-noop
    resolve c1 w3 h2 r1 n0 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
    resolve c1 w3 h2 r1 n1 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
    resolve c1 w3 h2 r1 n2 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
    resolve c1 w3 h3 r0 n0 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=14.2000/my-whoop-noop
    resolve c1 w3 h3 r0 n1 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=14.2000/my-whoop-noop
    resolve c1 w3 h3 r0 n2 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=14.2000/my-whoop-noop
    resolve c1 w3 h3 r1 n0 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
    resolve c1 w3 h3 r1 n1 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
    resolve c1 w3 h3 r1 n2 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
    resolve c1 w3 h4 r0 n0 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=14.2000/my-whoop-noop
    resolve c1 w3 h4 r0 n1 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=14.2000/my-whoop-noop
    resolve c1 w3 h4 r0 n2 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=14.2000/my-whoop-noop
    resolve c1 w3 h4 r1 n0 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
    resolve c1 w3 h4 r1 n1 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
    resolve c1 w3 h4 r1 n2 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
    resolve c1 w4 h0 r0 n0 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
    resolve c1 w4 h0 r0 n1 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
    resolve c1 w4 h0 r0 n2 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
    resolve c1 w4 h0 r1 n0 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
    resolve c1 w4 h0 r1 n1 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
    resolve c1 w4 h0 r1 n2 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
    resolve c1 w4 h1 r0 n0 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
    resolve c1 w4 h1 r0 n1 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
    resolve c1 w4 h1 r0 n2 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
    resolve c1 w4 h1 r1 n0 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
    resolve c1 w4 h1 r1 n1 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
    resolve c1 w4 h1 r1 n2 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
    resolve c1 w4 h2 r0 n0 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
    resolve c1 w4 h2 r0 n1 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
    resolve c1 w4 h2 r0 n2 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
    resolve c1 w4 h2 r1 n0 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
    resolve c1 w4 h2 r1 n1 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
    resolve c1 w4 h2 r1 n2 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
    resolve c1 w4 h3 r0 n0 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
    resolve c1 w4 h3 r0 n1 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
    resolve c1 w4 h3 r0 n2 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
    resolve c1 w4 h3 r1 n0 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
    resolve c1 w4 h3 r1 n1 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
    resolve c1 w4 h3 r1 n2 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
    resolve c1 w4 h4 r0 n0 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
    resolve c1 w4 h4 r0 n1 -> rhr=58/my-whoop-noop hrv=41.3750/garmin-watch/fit371 resp=14.2000/my-whoop-noop
    resolve c1 w4 h4 r0 n2 -> rhr=58/my-whoop-noop hrv=40.2500/garmin-watch/fit371 resp=14.2000/my-whoop-noop
    resolve c1 w4 h4 r1 n0 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
    resolve c1 w4 h4 r1 n1 -> rhr=58/my-whoop-noop hrv=41.3750/garmin-watch/fit371 resp=19.5833/garmin-watch
    resolve c1 w4 h4 r1 n2 -> rhr=58/my-whoop-noop hrv=40.2500/garmin-watch/fit371 resp=19.5833/garmin-watch
    resolve c2 w0 h0 r0 n0 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
    resolve c2 w0 h0 r0 n1 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
    resolve c2 w0 h0 r0 n2 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
    resolve c2 w0 h0 r1 n0 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
    resolve c2 w0 h0 r1 n1 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
    resolve c2 w0 h0 r1 n2 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
    resolve c2 w0 h1 r0 n0 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
    resolve c2 w0 h1 r0 n1 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
    resolve c2 w0 h1 r0 n2 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
    resolve c2 w0 h1 r1 n0 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
    resolve c2 w0 h1 r1 n1 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
    resolve c2 w0 h1 r1 n2 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
    resolve c2 w0 h2 r0 n0 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
    resolve c2 w0 h2 r0 n1 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
    resolve c2 w0 h2 r0 n2 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
    resolve c2 w0 h2 r1 n0 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
    resolve c2 w0 h2 r1 n1 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
    resolve c2 w0 h2 r1 n2 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
    resolve c2 w0 h3 r0 n0 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
    resolve c2 w0 h3 r0 n1 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
    resolve c2 w0 h3 r0 n2 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
    resolve c2 w0 h3 r1 n0 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
    resolve c2 w0 h3 r1 n1 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
    resolve c2 w0 h3 r1 n2 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
    resolve c2 w0 h4 r0 n0 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
    resolve c2 w0 h4 r0 n1 -> rhr=75/my-whoop-noop hrv=41.3750/garmin-watch/fit371 resp=19.0000/my-whoop-noop
    resolve c2 w0 h4 r0 n2 -> rhr=75/my-whoop-noop hrv=40.2500/garmin-watch/fit371 resp=19.0000/my-whoop-noop
    resolve c2 w0 h4 r1 n0 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
    resolve c2 w0 h4 r1 n1 -> rhr=75/my-whoop-noop hrv=41.3750/garmin-watch/fit371 resp=19.0000/my-whoop-noop
    resolve c2 w0 h4 r1 n2 -> rhr=75/my-whoop-noop hrv=40.2500/garmin-watch/fit371 resp=19.0000/my-whoop-noop
    resolve c2 w1 h0 r0 n0 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
    resolve c2 w1 h0 r0 n1 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
    resolve c2 w1 h0 r0 n2 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
    resolve c2 w1 h0 r1 n0 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c2 w1 h0 r1 n1 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c2 w1 h0 r1 n2 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c2 w1 h1 r0 n0 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
    resolve c2 w1 h1 r0 n1 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
    resolve c2 w1 h1 r0 n2 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
    resolve c2 w1 h1 r1 n0 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c2 w1 h1 r1 n1 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c2 w1 h1 r1 n2 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c2 w1 h2 r0 n0 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
    resolve c2 w1 h2 r0 n1 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
    resolve c2 w1 h2 r0 n2 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
    resolve c2 w1 h2 r1 n0 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c2 w1 h2 r1 n1 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c2 w1 h2 r1 n2 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c2 w1 h3 r0 n0 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
    resolve c2 w1 h3 r0 n1 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
    resolve c2 w1 h3 r0 n2 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
    resolve c2 w1 h3 r1 n0 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c2 w1 h3 r1 n1 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c2 w1 h3 r1 n2 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c2 w1 h4 r0 n0 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
    resolve c2 w1 h4 r0 n1 -> rhr=75/my-whoop-noop hrv=41.3750/garmin-watch/fit371 resp=19.0000/my-whoop-noop
    resolve c2 w1 h4 r0 n2 -> rhr=75/my-whoop-noop hrv=40.2500/garmin-watch/fit371 resp=19.0000/my-whoop-noop
    resolve c2 w1 h4 r1 n0 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c2 w1 h4 r1 n1 -> rhr=75/my-whoop-noop hrv=41.3750/garmin-watch/fit371 resp=19.5833/garmin-watch
    resolve c2 w1 h4 r1 n2 -> rhr=75/my-whoop-noop hrv=40.2500/garmin-watch/fit371 resp=19.5833/garmin-watch
    resolve c2 w2 h0 r0 n0 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.0000/my-whoop-noop
    resolve c2 w2 h0 r0 n1 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.0000/my-whoop-noop
    resolve c2 w2 h0 r0 n2 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.0000/my-whoop-noop
    resolve c2 w2 h0 r1 n0 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c2 w2 h0 r1 n1 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c2 w2 h0 r1 n2 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c2 w2 h1 r0 n0 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.0000/my-whoop-noop
    resolve c2 w2 h1 r0 n1 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.0000/my-whoop-noop
    resolve c2 w2 h1 r0 n2 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.0000/my-whoop-noop
    resolve c2 w2 h1 r1 n0 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c2 w2 h1 r1 n1 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c2 w2 h1 r1 n2 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c2 w2 h2 r0 n0 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.0000/my-whoop-noop
    resolve c2 w2 h2 r0 n1 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.0000/my-whoop-noop
    resolve c2 w2 h2 r0 n2 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.0000/my-whoop-noop
    resolve c2 w2 h2 r1 n0 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c2 w2 h2 r1 n1 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c2 w2 h2 r1 n2 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c2 w2 h3 r0 n0 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.0000/my-whoop-noop
    resolve c2 w2 h3 r0 n1 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.0000/my-whoop-noop
    resolve c2 w2 h3 r0 n2 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.0000/my-whoop-noop
    resolve c2 w2 h3 r1 n0 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c2 w2 h3 r1 n1 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c2 w2 h3 r1 n2 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c2 w2 h4 r0 n0 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.0000/my-whoop-noop
    resolve c2 w2 h4 r0 n1 -> rhr=63/garmin-watch hrv=41.3750/garmin-watch/fit371 resp=19.0000/my-whoop-noop
    resolve c2 w2 h4 r0 n2 -> rhr=63/garmin-watch hrv=40.2500/garmin-watch/fit371 resp=19.0000/my-whoop-noop
    resolve c2 w2 h4 r1 n0 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c2 w2 h4 r1 n1 -> rhr=63/garmin-watch hrv=41.3750/garmin-watch/fit371 resp=19.5833/garmin-watch
    resolve c2 w2 h4 r1 n2 -> rhr=63/garmin-watch hrv=40.2500/garmin-watch/fit371 resp=19.5833/garmin-watch
    resolve c2 w3 h0 r0 n0 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.0000/my-whoop-noop
    resolve c2 w3 h0 r0 n1 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.0000/my-whoop-noop
    resolve c2 w3 h0 r0 n2 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.0000/my-whoop-noop
    resolve c2 w3 h0 r1 n0 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
    resolve c2 w3 h0 r1 n1 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
    resolve c2 w3 h0 r1 n2 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
    resolve c2 w3 h1 r0 n0 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.0000/my-whoop-noop
    resolve c2 w3 h1 r0 n1 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.0000/my-whoop-noop
    resolve c2 w3 h1 r0 n2 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.0000/my-whoop-noop
    resolve c2 w3 h1 r1 n0 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
    resolve c2 w3 h1 r1 n1 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
    resolve c2 w3 h1 r1 n2 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
    resolve c2 w3 h2 r0 n0 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.0000/my-whoop-noop
    resolve c2 w3 h2 r0 n1 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.0000/my-whoop-noop
    resolve c2 w3 h2 r0 n2 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.0000/my-whoop-noop
    resolve c2 w3 h2 r1 n0 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
    resolve c2 w3 h2 r1 n1 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
    resolve c2 w3 h2 r1 n2 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
    resolve c2 w3 h3 r0 n0 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.0000/my-whoop-noop
    resolve c2 w3 h3 r0 n1 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.0000/my-whoop-noop
    resolve c2 w3 h3 r0 n2 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.0000/my-whoop-noop
    resolve c2 w3 h3 r1 n0 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
    resolve c2 w3 h3 r1 n1 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
    resolve c2 w3 h3 r1 n2 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
    resolve c2 w3 h4 r0 n0 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.0000/my-whoop-noop
    resolve c2 w3 h4 r0 n1 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.0000/my-whoop-noop
    resolve c2 w3 h4 r0 n2 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.0000/my-whoop-noop
    resolve c2 w3 h4 r1 n0 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
    resolve c2 w3 h4 r1 n1 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
    resolve c2 w3 h4 r1 n2 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
    resolve c2 w4 h0 r0 n0 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
    resolve c2 w4 h0 r0 n1 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
    resolve c2 w4 h0 r0 n2 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
    resolve c2 w4 h0 r1 n0 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c2 w4 h0 r1 n1 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c2 w4 h0 r1 n2 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c2 w4 h1 r0 n0 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
    resolve c2 w4 h1 r0 n1 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
    resolve c2 w4 h1 r0 n2 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
    resolve c2 w4 h1 r1 n0 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c2 w4 h1 r1 n1 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c2 w4 h1 r1 n2 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c2 w4 h2 r0 n0 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
    resolve c2 w4 h2 r0 n1 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
    resolve c2 w4 h2 r0 n2 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
    resolve c2 w4 h2 r1 n0 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c2 w4 h2 r1 n1 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c2 w4 h2 r1 n2 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c2 w4 h3 r0 n0 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
    resolve c2 w4 h3 r0 n1 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
    resolve c2 w4 h3 r0 n2 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
    resolve c2 w4 h3 r1 n0 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c2 w4 h3 r1 n1 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c2 w4 h3 r1 n2 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c2 w4 h4 r0 n0 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
    resolve c2 w4 h4 r0 n1 -> rhr=75/my-whoop-noop hrv=41.3750/garmin-watch/fit371 resp=19.0000/my-whoop-noop
    resolve c2 w4 h4 r0 n2 -> rhr=75/my-whoop-noop hrv=40.2500/garmin-watch/fit371 resp=19.0000/my-whoop-noop
    resolve c2 w4 h4 r1 n0 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.5833/garmin-watch
    resolve c2 w4 h4 r1 n1 -> rhr=75/my-whoop-noop hrv=41.3750/garmin-watch/fit371 resp=19.5833/garmin-watch
    resolve c2 w4 h4 r1 n2 -> rhr=75/my-whoop-noop hrv=40.2500/garmin-watch/fit371 resp=19.5833/garmin-watch
    era 0 0.0000
    era 1 0.0000
    era 2 1790899200.0000
    era 3 1790899200.0000
    era 4 0.0000
    era 5 0.0000
    era 6 1790899200.0000
    era 7 1790899200.0000
    effort garmin-A Garmin false
    effort garmin-A nil false
    effort strap-1 Garmin false
    effort strap-1 Polar false
    effort garmin-import Garmin false
    effort my-whoop nil false
    effort garmin-A-noop nil false
    vo2 2026-10-31 52.4
    vo2 2026-10-30 51.0
    vo2 2026-11-07 52.4
    vo2 2026-11-08 53.0
    vo2 2026-12-31 nil
    vo2 bad nil
    merge 2026-09-20=48.0 2026-10-01=50.1 2026-10-04=51.0 2026-10-05=47.5
    merge-watch-only 2026-10-01=50.1 2026-10-04=51.0 2026-10-31=52.4 2026-11-08=53.0
    merge-import-only 2026-09-20=48.0 2026-10-04=49.0 2026-10-05=47.5
    weeks 0 2026-11-07 [2026-09-26=48.0 2026-10-03=50.1 2026-10-10=47.5 2026-10-17=47.5 2026-10-24=47.5 2026-10-31=47.5]
    weeks 0 2026-10-03 [2026-09-26=48.0]
    weeks 0 2026-02-07 []
    weeks 0 bad []
    weeks 1 2026-11-07 []
    weeks 1 2026-10-03 []
    weeks 1 2026-02-07 []
    weeks 1 bad []
    weeks 2 2026-11-07 [2026-10-03=44.0 2026-10-10=44.0 2026-10-17=44.0 2026-10-24=44.0]
    weeks 2 2026-10-03 []
    weeks 2 2026-02-07 []
    weeks 2 bad []
    weeks 3 2026-11-07 [2026-01-03=40.0 2026-01-10=40.0 2026-01-17=40.0 2026-01-24=40.0 2026-03-21=42.0 2026-03-28=42.0 2026-04-04=42.0 2026-04-11=42.0]
    weeks 3 2026-10-03 [2026-01-03=40.0 2026-01-10=40.0 2026-01-17=40.0 2026-01-24=40.0 2026-03-21=42.0 2026-03-28=42.0 2026-04-04=42.0 2026-04-11=42.0]
    weeks 3 2026-02-07 [2026-01-03=40.0 2026-01-10=40.0 2026-01-17=40.0 2026-01-24=40.0]
    weeks 3 bad []
    weeks 4 2026-11-07 [2026-01-03=41.0 2026-01-10=41.0 2026-01-17=41.0 2026-01-24=41.0]
    weeks 4 2026-10-03 [2026-01-03=41.0 2026-01-10=41.0 2026-01-17=41.0 2026-01-24=41.0]
    weeks 4 2026-02-07 [2026-01-03=41.0 2026-01-10=41.0 2026-01-17=41.0 2026-01-24=41.0]
    weeks 4 bad []
    """
}
