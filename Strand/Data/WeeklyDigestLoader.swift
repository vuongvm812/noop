import Foundation
import StrandAnalytics
import WhoopProtocol
import WhoopStore

/// The store half of the weekly-digest input resolver (#89). Not tied to a view, so the Trends
/// card and the Monday "weekly summary" notification read the digest's inputs through the same
/// calls; the pure mapping is `WeeklyDigestInputs`. Garmin only: Swift has no Huawei ingest.
/// Kotlin twin: `WeeklyDigestLoader`.
enum WeeklyDigestLoader {

    /// Weeks of history the engine reads around the anchor: this, last, and the baseline weeks.
    static let historyDays = 7 * (WeeklyDigestEngine.baselineWeeks + 1)

    /// The watch whose series feed the digest: the active device when it is a paired Garmin
    /// watch, else the registry's owner of `anchorDay` when a Garmin watch owns that day.
    /// Kotlin twin: `WeeklyDigestLoader.watchId`.
    static func watchId(store: WhoopStore, activeDeviceId: String, anchorDay: String) -> String? {
        let registry = DeviceRegistryStore(dbQueue: store.registryWriter)
        let active = (try? registry.activeDeviceId()) ?? activeDeviceId
        if FusionSource.isGarminWatchId(active) { return active }
        guard let owner = (try? registry.dayOwner(anchorDay))?.deviceId else { return nil }
        return FusionSource.isGarminWatchId(owner) ? owner : nil
    }

    /// The watch series for the week containing `anchorDay`; empty for a strap-only user.
    @MainActor
    static func load(repo: Repository, anchorDay: String,
                     calendar: Calendar = .current) async -> WeeklyDigestWatchSeries {
        guard let store = await repo.storeHandle(),
              let watchId = watchId(store: store, activeDeviceId: repo.deviceId, anchorDay: anchorDay)
        else { return WeeklyDigestWatchSeries() }
        return await watchSeries(store: store, watchId: watchId, anchorDay: anchorDay, calendar: calendar)
    }

    /// The snapshot values (catalog id → day → value) over the digest's six weeks around `anchorDay`, for the
    /// metrics read from the snapshot stores (body composition, workouts, nutrition, ...). Same window as
    /// the watch series. Kotlin twin: `WeeklyDigestLoader.snapshotValues`.
    @MainActor
    static func snapshotValues(repo: Repository, anchorDay: String,
                               calendar: Calendar = .current) async -> [String: [String: Double]] {
        guard let monday = WeeklyDigestEngine.mondayOfWeek(containing: anchorDay) else { return [:] }
        return await MetricSnapshotLoader.dayValues(
            repo: repo, from: WeeklyDigestEngine.addDays(monday, -historyDays),
            to: WeeklyDigestEngine.addDays(monday, 6), calendar: calendar)
    }

    /// The digest a non-UI caller (the weekly-summary notification) builds from the stores.
    @MainActor
    static func digest(repo: Repository, anchorDay: String,
                       effortDisplayFactor: Double = UnitPrefs.currentEffortDisplayFactor()) async -> WeeklyDigest {
        let watch = await load(repo: repo, anchorDay: anchorDay)
        let values = await snapshotValues(repo: repo, anchorDay: anchorDay)
        return WeeklyDigestSource.digest(from: repo.days, anchorDay: anchorDay,
                                         importedSleep: repo.importedSleep, computedSleep: repo.computedSleep,
                                         watch: watch, snapshotValues: values,
                                         effortDisplayFactor: effortDisplayFactor)
    }

    /// The watch's per-day series over the digest's six weeks: daily rollups from `metricSeries`,
    /// totals and Body Battery from the intraday samples bucketed by local day.
    /// Kotlin twin: `WeeklyDigestLoader.watchSeries`.
    @MainActor
    static func watchSeries(store: WhoopStore, watchId: String, anchorDay: String,
                            calendar: Calendar = .current) async -> WeeklyDigestWatchSeries {
        guard let monday = WeeklyDigestEngine.mondayOfWeek(containing: anchorDay),
              let fromTs = dayStart(WeeklyDigestEngine.addDays(monday, -historyDays), calendar),
              let endTs = dayStart(WeeklyDigestEngine.addDays(monday, 7), calendar)
        else { return WeeklyDigestWatchSeries() }
        let fromDay = WeeklyDigestEngine.addDays(monday, -historyDays)
        let toDay = WeeklyDigestEngine.addDays(monday, 6)
        let toTs = endTs - 1

        func daily(_ key: String) async -> [String: Double] {
            let rows = (try? await store.metricSeries(deviceId: watchId, key: key, from: fromDay, to: toDay)) ?? []
            return Dictionary(rows.map { ($0.day, $0.value) }, uniquingKeysWith: { _, b in b })
        }
        func summedByDay(_ kind: DeviceMetricKind) async -> [String: Double] {
            let rows = (try? await store.deviceMetricSamples(deviceId: watchId, kind: kind, from: fromTs, to: toTs)) ?? []
            var out: [String: Double] = [:]
            for s in rows { out[dayKey(s.ts, calendar), default: 0] += s.value }
            return out
        }

        let vo2Points = await IntelligenceEngine.garminVo2maxPoints(store: store, watchId: watchId, to: toDay)
        var vo2: [String: Double] = [:]
        for p in vo2Points where p.day >= fromDay { vo2[p.day] = p.value }

        // The keys the watch ingest writes (GarminSyncStore mergeGarminDay).
        return WeeklyDigestWatchSeries(
            stress: await daily(WhoopStore.garminStressKey),
            sleepScore: await daily("ref_sleep_score"),
            activeKcal: await daily("active_kcal"),
            trainingLoad: await summedByDay(.trainingLoad),
            intensityMinutes: WeeklyDigestInputs.intensityMinutes(
                moderate: await summedByDay(.intensityModerateMin),
                vigorous: await summedByDay(.intensityVigorousMin)),
            vo2max: vo2,
            bodyBatteryRecharge: await bodyBatteryRecharge(store: store, watchId: watchId,
                                                           fromTs: fromTs, toTs: toTs, calendar: calendar))
    }

    /// Each wake day's Body Battery recharge from the watch's main sleep that day (the longest
    /// session ending on it). Kotlin twin: `WeeklyDigestLoader.bodyBatteryRecharge`.
    @MainActor
    static func bodyBatteryRecharge(store: WhoopStore, watchId: String, fromTs: Int, toTs: Int,
                                            calendar: Calendar) async -> [String: Double] {
        let window = WeeklyDigestInputs.rechargeWindowSeconds
        let sessions = ((try? await store.sleepSessions(deviceId: watchId, from: fromTs - 86_400, to: toTs,
                                                        limit: 1_000)) ?? [])
            .filter { $0.endTs >= fromTs && $0.endTs <= toTs }
        guard !sessions.isEmpty else { return [:] }
        var mainByDay: [String: CachedSleepSession] = [:]
        for s in sessions {
            let day = dayKey(s.endTs, calendar)
            if let cur = mainByDay[day], cur.endTs - cur.effectiveStartTs >= s.endTs - s.effectiveStartTs { continue }
            mainByDay[day] = s
        }
        let samples = ((try? await store.deviceMetricSamples(deviceId: watchId, kind: .bodyBattery,
                                                             from: fromTs - 86_400 - window, to: toTs + window)) ?? [])
            .map { (ts: $0.ts, value: $0.value) }
        var out: [String: Double] = [:]
        for (day, s) in mainByDay {
            if let r = WeeklyDigestInputs.bodyBatteryRecharge(samples: samples, sleepStart: s.effectiveStartTs,
                                                              sleepEnd: s.endTs) {
                out[day] = r
            }
        }
        return out
    }

    /// One daily row as the pure mapping reads it, Rest through the one Rest resolver (#55).
    /// Kotlin twin: `WeeklyDigestLoader.digestDay`.
    static func digestDay(_ d: DailyMetric, importedSleep: [String: ImportedSleepFigures],
                          computedSleep: [String: ImportedSleepFigures]) -> WeeklyDigestDay {
        WeeklyDigestDay(
            day: d.day, charge: d.recovery, effort: d.strain,
            rest: Repository.restScore(d, imported: importedSleep[d.day], computed: computedSleep[d.day]),
            rhr: d.restingHr.map(Double.init), hrv: d.avgHrv, steps: d.steps.map(Double.init),
            sleepMin: d.totalSleepMin, efficiency: d.efficiency, deepMin: d.deepMin, remMin: d.remMin,
            spo2: d.spo2Pct, respiration: d.respRateBpm, skinTemp: d.skinTempDevC)
    }

    /// Unix start of the local day "yyyy-MM-dd".
    static func dayStart(_ day: String, _ calendar: Calendar) -> Int? {
        guard let (y, m, d) = WeeklyDigestEngine.parseYMD(day),
              let date = calendar.date(from: DateComponents(year: y, month: m, day: d)) else { return nil }
        return Int(calendar.startOfDay(for: date).timeIntervalSince1970)
    }

    /// The local "yyyy-MM-dd" a unix timestamp falls on.
    static func dayKey(_ ts: Int, _ calendar: Calendar) -> String {
        let c = calendar.dateComponents([.year, .month, .day], from: Date(timeIntervalSince1970: TimeInterval(ts)))
        return String(format: "%04d-%02d-%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0)
    }
}
