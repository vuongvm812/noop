import Foundation
import StrandAnalytics
import WhoopStore
import StrandImport
import WhoopProtocol   // Streams / DeviceMetricSample for the activity summaries

/// Maps a parsed Oura / Fitbit / Garmin own-data export into the on-device WhoopStore tables the UI
/// reads — `dailyMetric`, `sleepSession`, and the generic `metricSeries` — under a per-brand Data
/// Source id ("oura-import" / "fitbit-import" / "garmin-import"), so importing lights up the history
/// immediately as its own source distinct from WHOOP.
///
/// HONEST DATA: only fields the export carried are written. The brand's OWN scores (Oura readiness,
/// any sleep score) are stored under REFERENCE metric keys only — never as NOOP's Charge/Effort/Rest.
/// NOOP recomputes its own scores downstream from the imported raw RHR / HRV / sleep, exactly as it
/// does for every other imported source. Fully offline — the parse never touches the network.
enum WearableImporter {

    /// The Oura/Fitbit/Garmin export mapping revision, stamped into the Import test-mode parser line.
    static let importerVersion = 1

    @discardableResult
    static func importExport(url: URL, into store: WhoopStore,
                             trace: (@Sendable ([String]) -> Void)? = nil) async throws -> WearableImportResult {
        var result = try ImportCoordinator().importWearableExport(from: url)
        let deviceId = result.brand.sourceId

        // Day rollups → DailyMetric. HRV/recovery/respiration that the export lacks stay nil; NOOP
        // derives what it can locally. A brand's reference scores never populate `recovery`/`strain`.
        var metrics: [DailyMetric] = []
        for d in result.days {
            metrics.append(DailyMetric(
                day: d.day,
                totalSleepMin: d.totalSleepMin,
                efficiency: d.efficiencyPct ?? sleepEfficiency(total: d.totalSleepMin, awake: d.awakeMin),
                deepMin: d.deepMin,
                remMin: d.remMin,
                lightMin: d.lightMin,
                disturbances: nil,
                restingHr: d.restingHr,
                avgHrv: d.avgHrvMs,
                recovery: nil,                 // NEVER the brand's readiness score
                strain: nil,
                exerciseCount: nil,
                spo2Pct: d.spo2Pct,
                skinTempDevC: d.skinTempDevC,
                respRateBpm: d.respRateBpm,    // imported night resp now reaches the day rollup (#17)
                // Whole-day TOTAL energy, as every source stores it (#51); the brand's active calories
                // stay in the active_kcal series. Kotlin twin: WearableExportImporter.
                activeKcalEst: d.totalKcal))
        }
        // Capture the rows the store actually wrote (summed SQLite changes) for the Import test mode.
        let metricsWritten = try await store.upsertDailyMetrics(metrics, deviceId: deviceId)

        // Sleep sessions → CachedSleepSession. Oura/Garmin give duration breakdowns without a per-segment
        // hypnogram, so those sessions carry no stage JSON (we never synthesize a fake one); Fitbit's
        // optional `levels.data` hypnogram is mapped when present.
        var sessions: [CachedSleepSession] = []
        for s in result.sleeps {
            let startTs = Int(s.start.timeIntervalSince1970)
            let endTs = Int(s.end.timeIntervalSince1970)
            let segs: [[String: Any]] = s.stages.map {
                ["start": Int($0.start.timeIntervalSince1970),
                 "end": Int($0.end.timeIntervalSince1970),
                 "stage": $0.stage]
            }
            let json = segs.isEmpty ? nil : (try? JSONSerialization.data(withJSONObject: segs))
                .flatMap { String(data: $0, encoding: .utf8) }
            sessions.append(CachedSleepSession(
                startTs: startTs,
                endTs: endTs,
                efficiency: s.efficiencyPct ?? efficiency(segs: segs, start: startTs, end: endTs),
                restingHr: s.lowestHr ?? s.avgHr,   // sleeping-min HR ≈ resting; falls back to avg
                avgHrv: s.avgHrvMs,
                stagesJSON: json))
        }
        let sessionsWritten = try await store.upsertSleepSessions(sessions, deviceId: deviceId)

        // Generic metric series — every scalar keyed for the Metric Explorer + correlations. The brand's
        // own scores go under clearly-labelled reference keys (e.g. "ref_readiness_score"), so they're
        // browseable but never mistaken for a NOOP score.
        var points: [MetricPoint] = []
        func add(_ day: String, _ key: String, _ v: Double?) {
            if let v { points.append(MetricPoint(day: day, key: key, value: v)) }
        }
        for d in result.days {
            add(d.day, "steps", d.steps.map(Double.init))
            add(d.day, "distance_m", d.distanceM)
            add(d.day, "active_kcal", d.activeKcal)
            add(d.day, "total_kcal", d.totalKcal)
            add(d.day, "rhr", d.restingHr.map(Double.init))
            add(d.day, "hrv", d.avgHrvMs)
            add(d.day, "skin_temp_dev_c", d.skinTempDevC)
            add(d.day, "spo2", d.spo2Pct)
            add(d.day, "vo2max", d.vo2max)
            add(d.day, "stress", d.avgStress.map(Double.init))
            add(d.day, "sleep_total_min", d.totalSleepMin)
            add(d.day, "sleep_deep_min", d.deepMin)
            add(d.day, "sleep_light_min", d.lightMin)
            add(d.day, "sleep_rem_min", d.remMin)
            // Reference-only: the brand's own scores, never a NOOP Charge/Effort/Rest input.
            add(d.day, "ref_readiness_score", d.readinessScore.map(Double.init))
            add(d.day, "ref_sleep_score", d.sleepScore.map(Double.init))
        }
        // Garmin export extras (UDS / sleep scores / body / fitness / records ...): fill-missing only, so the
        // daily parser's own value for a (day, key) always wins. Kotlin twin: WearableExportImporter.persist.
        var workoutsWritten = 0
        if let g = result.garmin {
            var have = Set(points.map { "\($0.day)|\($0.key)" })
            for p in g.points where have.insert("\(p.day)|\(p.key)").inserted {
                points.append(MetricPoint(day: p.day, key: p.key, value: p.value))
            }
        }
        try await store.upsertMetricSeries(points, deviceId: deviceId)

        // A Garmin export's FIT files (activities, 24/7 monitoring, sleep, HRV, VO2max), stored under the
        // same device id. Kotlin twin: `WearableExportImporter.importExport`.
        if !result.fitFiles.isEmpty {
            result.fitReport = await GarminExportFitIngest.ingest(result.fitFiles, store: store)
            result.fitFiles = [:]   // the bytes are stored; release them
        }
        // The activity summaries last: the export's FIT activities are stored by now, so a summary enriches
        // its FIT workout row instead of adding a second one. Kotlin twin: `WearableExportImporter.importExport`.
        if let g = result.garmin {
            workoutsWritten = try await persistGarminActivities(g.activities, deviceId: deviceId, store: store)
        }
        // Every FIT file skipped is returned with its per-reason report rather than thrown away as "nothing".
        if result.days.isEmpty, result.sleeps.isEmpty, (result.fitReport?.storedFiles ?? 0) == 0,
           (result.fitReport?.skippedFiles ?? 0) == 0 {
            throw ImportError.emptyExport("\(result.brand.displayName) export held no sleep or daily wellness data")
        }

        // Import & Data Ingest test mode: emit the per-stage / reject / day-delta trace iff the mode is on.
        // The numbers are the import's own parsed + persisted counts, so emission changes nothing saved.
        if let trace {
            let daysMapped = Set(result.days.map { $0.day }).count
            let lines: [String] = [
                ImportTrace.parserVersionLine(sourceKind: result.brand.dataSourceKind, importerVersion: importerVersion),
                ImportTrace.stageLine(category: "days", rowsIn: result.days.count, rowsOut: metricsWritten),
                ImportTrace.stageLine(category: "sleeps", rowsIn: sessions.count, rowsOut: sessionsWritten),
                ImportTrace.stageLine(category: "workouts", rowsIn: result.garmin?.activities.count ?? 0, rowsOut: workoutsWritten),
                // The Oura/Fitbit/Garmin parser drops unusable rows upstream in StrandImport; the app map
                // keeps every day/sleep, so the reject signal at this seam is the day-delta below.
                ImportTrace.rejectLine(droppedRows: 0, skippedSpans: result.summary.skippedSpans),
                ImportTrace.dayDeltaLine(category: "days", daysMapped: daysMapped, daysPersisted: metricsWritten),
            ]
            trace(lines)
        }

        return result
    }

    /// Store the export's activity summaries as workouts under `deviceId`. An activity that overlaps (same sport,
    /// more than half of the shorter session) a workout already stored under that id (a FIT-derived row) is NOT
    /// duplicated: that row is enriched with the summary's zones, energy, heart rate and notes instead. Returns the
    /// rows written (created + enriched). Kotlin twin: `WearableExportImporter.persistGarminActivities`.
    static func persistGarminActivities(_ acts: [GarminExportJson.Activity], deviceId: String,
                                        store: WhoopStore) async throws -> Int {
        guard let first = acts.first, let last = acts.last else { return 0 }
        let existing = try await store.workouts(deviceId: deviceId, from: first.startTs - 86_400,
                                                to: (acts.map(\.endTs).max() ?? last.endTs) + 86_400, limit: Int.max / 2)
        var rows: [WorkoutRow] = []
        var metrics: [DeviceMetricSample] = []
        for a in acts {
            let fresh = WorkoutRow(
                startTs: a.startTs, endTs: a.endTs, sport: a.sport, source: deviceId, durationS: a.durationS,
                energyKcal: a.energyKcal, avgHr: a.avgHr, maxHr: a.maxHr, strain: nil, distanceM: a.distanceM,
                zonesJSON: a.zonesJson, notes: ["Imported Garmin Connect activity", a.noteExtras].filter { !$0.isEmpty }.joined(separator: " · "),
                steps: a.steps)
            // An overlapping summary earlier in this same export counts as a stored row too.
            let match = existing.first(where: { WorkoutSource.sameActivity($0, fresh) })
            if match == nil, rows.contains(where: { WorkoutSource.sameActivity($0, fresh) }) { continue }
            // Training Effect is a reference value the workout detail reads back over the workout window.
            func add(_ kind: DeviceMetricKind, _ v: Double?) {
                if let v { metrics.append(DeviceMetricSample(ts: a.startTs, kind: kind, value: v, source: GarminExportJson.sourceSummary)) }
            }
            add(.aerobicTrainingEffect, a.aerobicTe); add(.anaerobicTrainingEffect, a.anaerobicTe)
            if let match {
                rows.append(WorkoutRow(
                    startTs: match.startTs, endTs: match.endTs, sport: match.sport, source: match.source,
                    durationS: match.durationS, energyKcal: match.energyKcal ?? fresh.energyKcal,
                    avgHr: match.avgHr ?? fresh.avgHr, maxHr: match.maxHr ?? fresh.maxHr, strain: match.strain,
                    distanceM: match.distanceM ?? fresh.distanceM, zonesJSON: match.zonesJSON ?? fresh.zonesJSON,
                    notes: GarminExportJson.enrichedNotes(match.notes, a.noteExtras),
                    steps: match.steps ?? fresh.steps))
            } else {
                rows.append(fresh)
                // Power and cadence only where no FIT row already carries the per-record series.
                add(.powerW, a.avgPowerW); add(.cadence, a.cadenceSpm)
            }
        }
        if !rows.isEmpty { try await store.upsertWorkouts(rows, deviceId: deviceId) }
        if !metrics.isEmpty { _ = try await store.insert(Streams(deviceMetric: metrics), deviceId: deviceId) }
        return rows.count
    }

    /// Asleep fraction of in-bed time, from the daily stage minutes (when the export gave no efficiency).
    private static func sleepEfficiency(total: Double?, awake: Double?) -> Double? {
        guard let total, total > 0 else { return nil }
        let awake = awake ?? 0
        let inBed = total + awake
        return inBed > 0 ? min(100, total / inBed * 100) : nil
    }

    /// Asleep fraction from the hypnogram segments (non-wake ÷ in-bed span).
    private static func efficiency(segs: [[String: Any]], start: Int, end: Int) -> Double? {
        guard end > start, !segs.isEmpty else { return nil }
        var asleep = 0
        for seg in segs {
            guard let s = seg["start"] as? Int, let e = seg["end"] as? Int,
                  let stage = seg["stage"] as? String, !SleepStageVocabulary.isWake(stage) else { continue }
            asleep += max(0, e - s)
        }
        return min(100, Double(asleep) / Double(end - start) * 100)
    }
}
