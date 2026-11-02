import XCTest
import Foundation
import WhoopProtocol
import WhoopStore
import StrandAnalytics
@testable import Strand

/// Garmin child 9, end to end for a Garmin-only profile: synthetic watch days go through the real
/// history-sync storage path (`WhoopStore.ingestGarminFile`), the real analytics pass
/// (`IntelligenceEngine.analyzeRecent`) and the real merged reads (`Repository`), then each check reads
/// what a Today / Sleep / Stress card reads. All data is synthetic; no device, no personal data.
@MainActor
final class GarminAnalyticsTests: XCTestCase {
    private let watch = "garmin-TEST-0001"
    private let canonical = "my-whoop"
    private let nights = 9

    private func withPreferences(_ body: () async throws -> Void) async throws {
        let defaults = UserDefaults.standard
        let keys = [
            "profile.dateOfBirth", "profile.age", "profile.sex", "profile.weightKg",
            "profile.heightCm", "profile.waistCm", "profile.hrMaxOverride", "profile.stepTicksPerStep",
            "noop.analyzeWatermark", "analyzeRecent.stepsMotionCache.v1",
            "noop.hrvBaselineEpoch", "noop.recoveryBaselineEpoch", UnitPrefs.hrvWindowKey,
            RescoreBackgroundScheduler.owedKey, RescoreBackgroundScheduler.owedTokenKey,
            RescoreBackgroundScheduler.lastPassSecondsKey, DayCycleMode.storageKey,
            PuffinExperiment.experimentalSleepV2Key, PuffinExperiment.motionAwareWakeKey,
        ]
        let saved = keys.map { ($0, defaults.object(forKey: $0)) }
        defer {
            for (key, value) in saved {
                if let value { defaults.set(value, forKey: key) } else { defaults.removeObject(forKey: key) }
            }
        }
        for key in keys { defaults.removeObject(forKey: key) }
        defaults.set(DayCycleMode.midnight.rawValue, forKey: DayCycleMode.storageKey)
        defaults.set(true, forKey: PuffinExperiment.experimentalSleepV2Key)
        defaults.set(false, forKey: PuffinExperiment.motionAwareWakeKey)
        try await body()
    }

    /// Local midnight `daysAgo` days before today, as the engine keys days.
    private func midnight(daysAgo: Int) -> Int {
        let cal = Calendar.current
        let today = cal.startOfDay(for: Date())
        return Int(cal.date(byAdding: .day, value: -daysAgo, to: today)!.timeIntervalSince1970)
    }

    private func dayKey(_ ts: Int) -> String { Repository.localDayKey(Date(timeIntervalSince1970: TimeInterval(ts))) }

    /// The watch's resting HR / last-night HRV for night `i` (0 = oldest). Varies so baselines have spread.
    private func watchRhr(_ i: Int) -> Int { 54 + (i % 3) }
    private func watchHrv(_ i: Int) -> Double { 46 + Double((i * 7) % 9) }

    /// One synthetic watch file per night: the night ending on day `wake`, plus that day's monitoring HR
    /// up to `until`. Day `i` has a workout of `i % 3 * 20` minutes at 150 bpm, so Effort varies.
    private func file(night i: Int, wakeMidnight: Int, until: Int) -> GarminFileIngest {
        var ingest = GarminFileIngest(file: GarminSyncedFileRow(fileIndex: 100 + i, dataType: 128, subType: 32,
                                                               fileNumber: i, ts: wakeMidnight, size: 1))
        let sleepStart = wakeMidnight - 3_600          // 23:00 the evening before
        let sleepEnd = wakeMidnight + 7 * 3_600        // 07:00
        var hr: [HRSample] = []
        // Evening before, the night, and the day: one sample a minute, as Garmin monitoring logs at rest.
        var t = wakeMidnight - 6 * 3_600
        let workoutStart = wakeMidnight + 17 * 3_600
        let workoutEnd = workoutStart + (i % 3) * 20 * 60
        while t < min(until, wakeMidnight + 86_400) {
            let bpm: Int
            if t >= sleepStart && t < sleepEnd { bpm = 50 + (t / 60) % 5 }
            else if t >= workoutStart && t < workoutEnd { bpm = 148 + (t / 60) % 5 }
            else { bpm = 68 + (t / 60) % 9 }
            hr.append(HRSample(ts: t, bpm: bpm))
            t += 60
        }
        ingest.hr = hr
        var metrics: [DeviceMetricSample] = []
        var m = sleepStart
        while m < sleepEnd {
            metrics.append(DeviceMetricSample(ts: m, kind: .hrvMs, value: watchHrv(i) + Double(m / 300 % 3) - 1,
                                              source: "fit371"))
            metrics.append(DeviceMetricSample(ts: m + 60, kind: .respirationBpm, value: 14.5, source: "fit297"))
            metrics.append(DeviceMetricSample(ts: m + 120, kind: .spo2Pct, value: 96, source: "fit269"))
            m += 300
        }
        for h in stride(from: sleepEnd, to: min(until, wakeMidnight + 86_400), by: 3_600) {
            metrics.append(DeviceMetricSample(ts: h, kind: .stress, value: 35, source: "fit227"))
            metrics.append(DeviceMetricSample(ts: h + 30, kind: .bodyBattery, value: 60, source: "fit227"))
        }
        ingest.deviceMetric = metrics
        var day = GarminDayRollup(day: dayKey(wakeMidnight), dayStartTs: wakeMidnight, dayEndTs: wakeMidnight + 86_400)
        day.restingHr = watchRhr(i)
        day.avgHrvMs = watchHrv(i)
        day.steps = 6_000 + i * 500
        day.totalSleepMin = 450
        day.deepMin = 90
        day.lightMin = 270
        day.remMin = 90
        day.awakeMin = 30
        day.efficiencyPct = 93.8
        ingest.days = [day]
        // Stage timeline in the sleepSession vocabulary: light / deep / light / rem / wake, 8 h.
        let seg: [(Int, Int, String)] = [
            (0, 90, "light"), (90, 180, "deep"), (180, 360, "light"), (360, 450, "rem"), (450, 480, "wake")]
        let stages = "[" + seg.map {
            "{\"start\":\(sleepStart + $0.0 * 60),\"end\":\(sleepStart + $0.1 * 60),\"stage\":\"\($0.2)\"}"
        }.joined(separator: ",") + "]"
        ingest.sleeps = [CachedSleepSession(startTs: sleepStart, endTs: sleepEnd, efficiency: 93.8,
                                            restingHr: nil, avgHrv: watchHrv(i), stagesJSON: stages)]
        return ingest
    }

    private func seedGarminProfile(nights: Int? = nil) async throws -> (WhoopStore, Repository, IntelligenceEngine) {
        let nights = nights ?? self.nights
        let store = try await WhoopStore.inMemory()
        let registry = DeviceRegistryStore(dbQueue: store.registryWriter)
        try registry.add(PairedDevice(id: watch, brand: "Garmin", model: "Forerunner 165", sourceKind: .garmin,
                                      capabilities: [.hr], status: .active, addedAt: 1, lastSeenAt: 1))
        let now = Int(Date().timeIntervalSince1970)
        for i in 0..<nights {
            let wake = midnight(daysAgo: nights - 1 - i)
            _ = try await store.ingestGarminFile(file(night: i, wakeMidnight: wake, until: now),
                                                 deviceId: watch, ingestedAt: now)
        }
        let repo = Repository(deviceId: watch)
        repo.setStoreForTesting(store)
        let engine = IntelligenceEngine(repo: repo, profile: ProfileStore(), deviceId: canonical)
        await engine.analyzeRecent(maxDays: nights + 1, force: true)
        await repo.refresh(days: 30)
        return (store, repo, engine)
    }

    // MARK: - Scoring inputs and calibrating

    func testWatchOwnedNightsScoreFromTheWatchsOwnVitals() async throws {
        try await withPreferences {
            let (store, _, _) = try await seedGarminProfile()
            let computed = try await store.dailyMetrics(deviceId: canonical + "-noop",
                                                        from: "0000-01-01", to: "9999-12-31")
            let byDay = Dictionary(computed.map { ($0.day, $0) }, uniquingKeysWith: { a, _ in a })
            for i in 1..<nights {   // night 0's evening has no HR before it; it may be unscored
                let key = dayKey(midnight(daysAgo: nights - 1 - i))
                let row = try XCTUnwrap(byDay[key], "day \(key) must be scored from the watch's HR")
                XCTAssertEqual(row.restingHr, watchRhr(i), "\(key): resting HR is the watch's own (one resolver)")
                XCTAssertEqual(try XCTUnwrap(row.avgHrv), watchHrv(i), accuracy: 1e-9,
                               "\(key): overnight HRV is the watch's FIT 370 average")
                XCTAssertEqual(try XCTUnwrap(row.respRateBpm), 14.5, accuracy: 1e-9,
                               "\(key): respiration is the watch's own overnight mean")
                XCTAssertGreaterThan(row.totalSleepMin ?? 0, 400, "\(key): the watch's own night is staged")
                XCTAssertNotNil(row.strain, "\(key): Effort is computed from the watch's HR")
            }
        }
    }

    func testChargeStaysCalibratingUntilTheBaselineIsUsableThenScores() async throws {
        try await withPreferences {
            // Three watch nights: under `Baselines.minNightsSeed`, so Charge is calibrating (nil), never faked,
            // while Effort and Rest already score.
            let (fewStore, _, _) = try await seedGarminProfile(nights: 3)
            let few = try await fewStore.dailyMetrics(deviceId: canonical + "-noop",
                                                      from: "0000-01-01", to: "9999-12-31")
            XCTAssertFalse(few.isEmpty)
            XCTAssertTrue(few.allSatisfy { $0.recovery == nil }, "calibrating until the HRV baseline is usable")
            XCTAssertTrue(few.contains { $0.strain != nil })
            XCTAssertTrue(few.contains { $0.avgHrv != nil }, "the watch's HRV is already feeding the baseline")

            let (store, _, _) = try await seedGarminProfile()
            let computed = try await store.dailyMetrics(deviceId: canonical + "-noop",
                                                        from: "0000-01-01", to: "9999-12-31")
                .sorted { $0.day < $1.day }
            let scored = computed.filter { $0.recovery != nil }
            XCTAssertGreaterThanOrEqual(scored.count, Baselines.minNightsSeed,
                                        "Charge lights up once the baseline has its nights")
            for row in scored {
                let r = try XCTUnwrap(row.recovery)
                XCTAssertTrue((0...100).contains(r), "Charge in range on \(row.day)")
            }
            // Rest is the same composite as WHOOP's, from the watch's own night.
            let rest = try await store.metricSeries(deviceId: canonical + "-noop", key: "sleep_performance",
                                                    from: "0000-01-01", to: "9999-12-31")
            XCTAssertGreaterThanOrEqual(rest.count, nights - 1)
        }
    }

    func testEffortVariesWithTheDayAndIsLabelledExperimental() async throws {
        try await withPreferences {
            let (store, repo, _) = try await seedGarminProfile()
            let strains = try await store.dailyMetrics(deviceId: canonical + "-noop",
                                                       from: "0000-01-01", to: "9999-12-31")
                .compactMap { $0.strain }
            XCTAssertGreaterThan(Set(strains.map { ($0 * 10).rounded() }).count, 1,
                                 "rest / easy / hard days must not all score the same Effort")
            let key = dayKey(midnight(daysAgo: 1))
            let maybeProvider = await repo.scoreInputProvider(resolvedSource: canonical + "-noop",
                                                              day: key, metricKey: "strain")
            let provider = try XCTUnwrap(maybeProvider)
            XCTAssertEqual(provider.sourceId, watch, "Effort's inputs are attributed to the watch")
            XCTAssertEqual(provider.brand, "Garmin")
            let label = try XCTUnwrap(LiquidTodayView.heroSourceLabel(providers: [provider], effortProvider: provider))
            XCTAssertEqual(label, "Garmin · \(String(localized: "Effort experimental"))")
            XCTAssertEqual(TodayView.todayScoreProviderLabel(sourceId: watch, brand: nil), "Garmin")
            XCTAssertEqual(TodayView.provenanceDisplayLabel(rawSource: watch, deviceId: watch), "Garmin",
                           "the watch's own rows never read as Whoop, even when it is the active device")
        }
    }

    // MARK: - What each screen reads

    func testTodayAndSleepCardsHaveTheirInputsForAGarminOnlyProfile() async throws {
        try await withPreferences {
            let (_, repo, _) = try await seedGarminProfile()
            let key = dayKey(midnight(daysAgo: 1))
            let day = try XCTUnwrap(repo.days.first { $0.day == key }, "merged day for \(key)")
            // Key metrics / vitals cards: the watch's own values, through the one merged row.
            XCTAssertEqual(day.restingHr, watchRhr(nights - 2))
            XCTAssertEqual(try XCTUnwrap(day.avgHrv), watchHrv(nights - 2), accuracy: 1e-9)
            XCTAssertNotNil(day.spo2Pct)
            XCTAssertNotNil(day.respRateBpm)
            XCTAssertNotNil(day.steps)
            // Hero: Charge / Effort / Rest through the same resolver the hero reads.
            for metric in ["recovery", "strain", "sleep_performance"] {
                let res = await repo.resolvedSeries(key: metric, source: canonical)
                XCTAssertTrue(res.points.contains { $0.day == key }, "\(metric) resolves for \(key)")
            }
            // Heart-rate card: today's HR samples.
            let from = midnight(daysAgo: 0)
            let hr = await repo.hrSamples(from: from, to: Int(Date().timeIntervalSince1970), limit: 10_000)
            XCTAssertFalse(hr.isEmpty, "the HR card reads the watch's samples")
            // Sleep tab: the watch's night, with stages.
            let model = try XCTUnwrap(SleepModel.build(SleepModelInputs(
                days: repo.days, sleeps: repo.sleeps, allSessions: [], importedSleep: repo.importedSleep,
                habitualMidsleepSec: nil, motionByStart: [:])))
            XCTAssertFalse(model.isStubNight)
            XCTAssertFalse(model.intervals.isEmpty, "the hypnogram has the watch's stages")
            // Stress: the 0-3 model reads the watch's vitals, never Garmin's 0-100 score as 0-3.
            let stored = await repo.series(key: "stress", source: canonical)
            XCTAssertTrue(stored.isEmpty, "Garmin stress must not land in the 0-3 stress series")
            let garminStress = await repo.series(key: WhoopStore.garminStressKey, source: canonical)
            XCTAssertFalse(garminStress.isEmpty, "the watch's own 0-100 stress is kept under its own key")
            XCTAssertTrue(garminStress.allSatisfy { $0.value == 35 })
            XCTAssertNotNil(StressModel(days: repo.days, stored: stored), "the Stress screen has a model")
        }
    }

    func testSwitchingFromWhoopToGarminStartsANewBaselineEra() {
        let days: [(day: String, sourceId: String)] = [
            ("2026-10-01", "my-whoop"), ("2026-10-02", "my-whoop"), ("2026-10-03", watch), ("2026-10-04", watch)]
        let era = GarminScoringInputs.baselineEraEpoch(days)
        XCTAssertEqual(AnalyticsEngine.dayString(Int(era)), "2026-10-03")
    }
}
