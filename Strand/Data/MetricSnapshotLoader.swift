import Foundation
import StrandAnalytics
import StrandImport
import WhoopProtocol
import WhoopStore

/// The store half of the metric snapshot: reads every stored metric over a day range and hands it to the
/// pure `MetricSnapshotBuilder`. The Daily summary screen, the weekly digest and the AI Coach context all
/// go through these three entry points. Kotlin twin: `MetricSnapshotLoader`.
///
///   • `dayValues(repo:from:to:)` — catalog item id → local day → value (the raw per-day matrix)
///   • `notes(repo:from:to:)` — journal answers and lab readings in the range, as text rows
///   • `snapshot(repo:from:to:)` — both, rolled up into a `MetricSnapshot` (one day: from == to)
///
/// Not read on Apple, and so always "-": the watch skin temperature (`watch_skin_temp_c`; the kind
/// exists only in the Android fork).
enum MetricSnapshotLoader {

    /// The longest gap since a logged period start that still reads as a cycle day; later days show "-".
    /// Kotlin twin: `MetricSnapshotLoader.MAX_CYCLE_DAYS`.
    static let maxCycleDay = 90.0

    // MARK: - Entry points

    /// The per-day values for every catalog item over the inclusive range `from`...`to` ("yyyy-MM-dd"),
    /// keyed by `MetricSnapshotItem.id`. Series items resolve through `Repository.resolvedSeries` over the
    /// item's sources in order (the first source with a value for a day wins); the derived items (workouts,
    /// hydration, cycle, watch, SDNN/disturbances) are read here. Kotlin twin: `MetricSnapshotLoader.dayValues`.
    @MainActor
    static func dayValues(repo: Repository, from: String, to: String,
                          calendar: Calendar = .current) async -> [String: [String: Double]] {
        var out: [String: [String: Double]] = [:]
        let days = daysIn(from, to)
        guard !days.isEmpty else { return out }

        // Stored series, source by source, the first value per day winning.
        for item in MetricSnapshotCatalog.allItems() {
            guard let key = item.seriesKey else { continue }
            var byDay: [String: Double] = [:]
            for source in item.sources {
                let res = await repo.resolvedSeries(key: key, source: source, from: from, to: to)
                for p in res.points where byDay[p.day] == nil && p.day >= from && p.day <= to {
                    byDay[p.day] = p.value
                }
            }
            if !byDay.isEmpty { out[item.id] = byDay }
        }

        // Columns with no series key: SDNN and disturbances from the merged daily rows.
        for d in repo.days where d.day >= from && d.day <= to {
            if let v = d.avgSdnn { out["sdnn", default: [:]][d.day] = v }
            if let v = d.disturbances { out["disturbances", default: [:]][d.day] = Double(v) }
        }

        guard let store = await repo.storeHandle() else { return out }
        let watchId = WeeklyDigestLoader.watchId(store: store, activeDeviceId: repo.deviceId, anchorDay: to)

        // Distance: the strap/import series, then the watch's, metres → km.
        var distance: [String: Double] = [:]
        for source in ["my-whoop"] {
            let res = await repo.resolvedSeries(key: "distance_m", source: source, from: from, to: to)
            for p in res.points where p.day >= from && p.day <= to { distance[p.day] = p.value / 1000.0 }
        }
        if let watchId {
            let rows = (try? await store.metricSeries(deviceId: watchId, key: "distance_m", from: from, to: to)) ?? []
            for r in rows where distance[r.day] == nil { distance[r.day] = r.value / 1000.0 }
        }
        if !distance.isEmpty { out["distance_km"] = distance }

        // Hydration: hand-logged plus imported, per day.
        let manual = await seriesByDay(store, HydrationStore.sourceId, HydrationStore.key, from, to)
        var imported = await seriesByDay(store, HydrationStore.sourceId, HydrationStore.importedKey, from, to)
        // A Garmin export's logged water joins the day's total (its activity sweat loss is a separate item).
        for (day, v) in await seriesByDay(store, garminImport, "hydration_ml", from, to) { imported[day, default: 0] += v }
        let water = hydrationDays(manual: manual, imported: imported)
        if !water.isEmpty { out["hydration_ml"] = water }

        // Cycle day from the logged period starts.
        let starts = await repo.periodStarts()
        for day in days {
            if let c = cycleDay(starts: starts, day: day) {
                out["cycle_day", default: [:]][day] = c
            }
        }

        // Workouts.
        for (day, values) in await workoutValues(repo: repo, store: store, watchId: watchId, from: from, to: to,
                                                 calendar: calendar) {
            for (id, v) in values { out[id, default: [:]][day] = v }
        }

        // The paired watch's own series.
        if let watchId {
            for (id, byDay) in await watchValues(store: store, watchId: watchId, from: from, to: to,
                                                 calendar: calendar) where !byDay.isEmpty {
                // The watch fills days the stored series left open (VO2 max); its own ids are new.
                for (day, v) in byDay where out[id]?[day] == nil { out[id, default: [:]][day] = v }
            }
        }

        // Body Battery low / high from a Garmin export, for the days no paired watch holds them. Kotlin twin:
        // `MetricSnapshotLoader.dayValues`.
        for (id, key) in [("body_battery_min", "body_battery_low"), ("body_battery_max", "body_battery_high")] {
            for (day, v) in await seriesByDay(store, garminImport, key, from, to) where out[id]?[day] == nil {
                out[id, default: [:]][day] = v
            }
        }
        return out
    }

    /// The free-text rows for `from`...`to`: each journal answer (Yes / No / the logged number) under
    /// Mood & journal and each lab reading ("2.9 mmol/L") under Lab markers, oldest first. A one-day range
    /// carries no date in the label; a longer range appends the day. Kotlin twin: `MetricSnapshotLoader.notes`.
    @MainActor
    static func notes(repo: Repository, from: String, to: String) async -> [MetricSnapshotNote] {
        var out: [MetricSnapshotNote] = []
        let multi = from != to
        func label(_ base: String, _ day: String) -> String { multi ? "\(base) (\(day))" : base }

        let entries = (await repo.journalEntries()).filter { $0.day >= from && $0.day <= to }
            .sorted { ($0.day, $0.question) < ($1.day, $1.question) }
        for e in entries {
            out.append(MetricSnapshotNote(
                category: .mind, id: "journal:\(e.day):\(e.question)", label: label(e.question, e.day),
                text: journalText(answeredYes: e.answeredYes, numeric: e.numericValue)))
        }

        if let store = await repo.storeHandle() {
            var rows: [LabMarkerRow] = []
            for category in LabMarkerCategory.allCases {
                rows += ((try? await store.labMarkers(deviceId: repo.deviceId, category: category.rawValue)) ?? [])
                    .filter { $0.day >= from && $0.day <= to }
            }
            for r in rows.sorted(by: { ($0.takenAt, $0.markerKey) < ($1.takenAt, $1.markerKey) }) {
                let name = MarkerCatalog.definition(for: r.markerKey)?.displayName ?? r.markerKey
                out.append(MetricSnapshotNote(
                    category: .labs, id: "lab:\(r.markerKey):\(r.takenAt)", label: label(name, r.day),
                    text: labText(value: r.value.map { LabBookFormat.value($0, key: r.markerKey) },
                                  valueText: r.valueText, unit: r.unit)))
            }
        }
        return out
    }

    /// The snapshot for `from`...`to` (one day: both the same), every category present. Kotlin twin:
    /// `MetricSnapshotLoader.snapshot`.
    @MainActor
    static func snapshot(repo: Repository, from: String, to: String,
                         calendar: Calendar = .current) async -> MetricSnapshot {
        let values = await dayValues(repo: repo, from: from, to: to, calendar: calendar)
        let notes = await notes(repo: repo, from: from, to: to)
        return MetricSnapshotBuilder.build(from: from, to: to, values: values, notes: notes)
    }

    /// One local day's workout items from its sessions. Kotlin twin: `MetricSnapshotLoader.workoutDay`.
    static func workoutDay(_ ws: [SnapshotWorkout]) -> [String: Double] {
        MetricSnapshotDerived.workoutDay(ws)
    }

    /// Per local day, the workout items of `workoutDay`. Kotlin twin: `MetricSnapshotLoader.workoutDays`.
    static func workoutDays(_ byDay: [String: [SnapshotWorkout]]) -> [String: [String: Double]] {
        byDay.mapValues { workoutDay($0) }
    }

    /// Total climb: the sum of the positive steps of a time-ordered altitude series; nil with fewer than two
    /// points. Kotlin twin: `MetricSnapshotLoader.altitudeGain`.
    static func altitudeGain(_ altitude: [Double]) -> Double? {
        guard altitude.count >= 2 else { return nil }
        return zip(altitude, altitude.dropFirst()).reduce(0) { $0 + max($1.1 - $1.0, 0) }
    }

    /// The 1-based cycle day of `day`, nil with no start on or before it or past `maxCycleDay`.
    /// Kotlin twin: `MetricSnapshotLoader.cycleDay`.
    static func cycleDay(starts: [String], day: String) -> Double? {
        MetricSnapshotDerived.cycleDay(starts: starts, day: day).flatMap { $0 <= maxCycleDay ? $0 : nil }
    }

    /// "Yes" / "No", with a numeric reading appended ("Yes (2)"). Kotlin twin: `MetricSnapshotLoader.journalText`.
    static func journalText(answeredYes: Bool, numeric: Double?) -> String {
        MetricSnapshotDerived.journalText(answeredYes: answeredYes, numericValue: numeric)
    }

    /// "2.9 mmol/L"; the value text when the entry is qualitative. Kotlin twin: `MetricSnapshotLoader.labText`.
    static func labText(value: String?, valueText: String?, unit: String) -> String {
        MetricSnapshotDerived.labText(value: value, unit: unit, valueText: valueText)
    }

    /// Hand-logged plus imported water per day; non-positive days dropped. Kotlin twin: `MetricSnapshotLoader.hydrationDays`.
    static func hydrationDays(manual: [String: Double], imported: [String: Double]) -> [String: Double] {
        var out: [String: Double] = [:]
        for day in Set(manual.keys).union(imported.keys) {
            if let t = MetricSnapshotDerived.hydrationTotal(manual: manual[day], imported: imported[day]) { out[day] = t }
        }
        return out
    }

    // MARK: - Workouts

    @MainActor
    private static func workoutValues(repo: Repository, store: WhoopStore, watchId: String?, from: String,
                                      to: String, calendar: Calendar) async -> [String: [String: Double]] {
        guard let fromTs = WeeklyDigestLoader.dayStart(from, calendar),
              let toTs = WeeklyDigestLoader.dayStart(WeeklyDigestEngine.addDays(to, 1), calendar) else { return [:] }
        // The same union Android reads: imported ids, the detected ("-noop") computed ids, and the watch.
        var ids = repo.importedReadIds
        for id in repo.computedReadIds where !ids.contains(id) { ids.append(id) }
        if let watchId, !ids.contains(watchId) { ids.append(watchId) }
        var rows: [WorkoutRow] = []
        // WorkoutRow carries no device id, so remember which id first held each row (ids are in
        // precedence order) to try that id's device samples first.
        var owner: [String: String] = [:]
        func rowKey(_ r: WorkoutRow) -> String { "\(r.startTs)|\(r.endTs)|\(r.sport)|\(r.source)" }
        for id in ids {
            let got = (try? await store.workouts(deviceId: id, from: fromTs, to: toTs - 1, limit: 5_000)) ?? []
            for r in got where owner[rowKey(r)] == nil { owner[rowKey(r)] = id }
            rows += got
        }
        rows = Repository.dedupWorkoutsByNaturalKey(rows)

        var byDay: [String: [SnapshotWorkout]] = [:]
        for r in rows {
            var w = SnapshotWorkout(
                startTs: r.startTs, endTs: r.endTs, durationS: r.durationS, energyKcal: r.energyKcal,
                avgHr: r.avgHr.map(Double.init), maxHr: r.maxHr.map(Double.init), strain: r.strain,
                distanceM: r.distanceM, zonesJSON: r.zonesJSON)
            // The workout's own device first, then the watch, then the imported ids.
            var sampleIds = owner[rowKey(r)].map { [$0] } ?? []
            for id in (watchId.map { [$0] } ?? []) + repo.importedReadIds where !sampleIds.contains(id) { sampleIds.append(id) }
            func samples(_ kind: DeviceMetricKind) async -> [Double] {
                for id in sampleIds {
                    let s = (try? await store.deviceMetricSamples(deviceId: id, kind: kind, from: r.startTs,
                                                                  to: r.endTs, limit: 20_000)) ?? []
                    if !s.isEmpty { return s.sorted { $0.ts < $1.ts }.map(\.value) }
                }
                return []
            }
            let power = await samples(.powerW)
            if !power.isEmpty { w.avgPowerW = power.reduce(0, +) / Double(power.count) }
            let cadence = await samples(.cadence)
            if !cadence.isEmpty { w.avgCadence = cadence.reduce(0, +) / Double(cadence.count) }
            let altitude = await samples(.altitudeM)
            if !altitude.isEmpty {
                w.maxAltitudeM = altitude.max()
                w.altitudeGainM = altitudeGain(altitude)
            }
            w.aerobicTe = await samples(.aerobicTrainingEffect).max()
            w.anaerobicTe = await samples(.anaerobicTrainingEffect).max()
            byDay[WeeklyDigestLoader.dayKey(r.startTs, calendar), default: []].append(w)
        }
        return workoutDays(byDay)
    }

    // MARK: - Watch

    @MainActor
    private static func watchValues(store: WhoopStore, watchId: String, from: String, to: String,
                                    calendar: Calendar) async -> [String: [String: Double]] {
        guard let fromTs = WeeklyDigestLoader.dayStart(from, calendar),
              let toTs = WeeklyDigestLoader.dayStart(WeeklyDigestEngine.addDays(to, 1), calendar) else { return [:] }
        let endTs = toTs - 1
        func daily(_ key: String) async -> [String: Double] { await seriesByDay(store, watchId, key, from, to) }
        func summed(_ kind: DeviceMetricKind) async -> [String: Double] {
            let rows = (try? await store.deviceMetricSamples(deviceId: watchId, kind: kind, from: fromTs, to: endTs)) ?? []
            var out: [String: Double] = [:]
            for s in rows { out[WeeklyDigestLoader.dayKey(s.ts, calendar), default: 0] += s.value }
            return out
        }
        var vo2: [String: Double] = [:]
        for p in await IntelligenceEngine.garminVo2maxPoints(store: store, watchId: watchId, to: to)
        where p.day >= from && p.day <= to { vo2[p.day] = p.value }
        return [
            "watch_stress": await daily(WhoopStore.garminStressKey),
            "watch_sleep_score": await daily("ref_sleep_score"),
            "body_battery_min": await daily("body_battery_min"),
            "body_battery_max": await daily("body_battery_max"),
            "body_battery_recharge": await WeeklyDigestLoader.bodyBatteryRecharge(
                store: store, watchId: watchId, fromTs: fromTs, toTs: endTs, calendar: calendar),
            "training_load": await summed(.trainingLoad),
            "intensity_min": WeeklyDigestInputs.intensityMinutes(
                moderate: await summed(.intensityModerateMin), vigorous: await summed(.intensityVigorousMin)),
            "vo2max": vo2,
        ]
    }

    // MARK: - Helpers

    /// The Garmin export import's source id (`WearableExportImporter.Brand.garmin.sourceId`).
    private static let garminImport = "garmin-import"

    private static func seriesByDay(_ store: WhoopStore, _ deviceId: String, _ key: String,
                                    _ from: String, _ to: String) async -> [String: Double] {
        let rows = (try? await store.metricSeries(deviceId: deviceId, key: key, from: from, to: to)) ?? []
        return Dictionary(rows.map { ($0.day, $0.value) }, uniquingKeysWith: { _, b in b })
    }

    /// Every "yyyy-MM-dd" from `from` to `to` inclusive (empty when `from` is after `to`, capped at 400 days).
    static func daysIn(_ from: String, _ to: String) -> [String] {
        guard from <= to, WeeklyDigestEngine.parseYMD(from) != nil, WeeklyDigestEngine.parseYMD(to) != nil else { return [] }
        var out: [String] = [], d = from
        while d <= to, out.count < 400 { out.append(d); d = WeeklyDigestEngine.addDays(d, 1) }
        return out
    }
}
