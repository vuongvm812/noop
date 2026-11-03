import Foundation
import GarminProtocol
import StrandImport
import WhoopProtocol
import WhoopStore

/// Stores what Garmin history sync downloads (`GarminSource` hands each checked file here) and answers
/// which files are already stored. Nothing here talks to the watch: the `garminSyncedFile` ledger is the
/// only record of "already downloaded", which is what lets NOOP leave every file on the watch untouched.
///
/// - Wellness files (monitor, sleep, HRV status, metrics, skin temperature) are decoded by
///   `GarminWellnessParser`, folded by `GarminWatchIngest`, and written with their ledger row in ONE
///   transaction (`WhoopStore.ingestGarminFile`).
/// - ACTIVITY files go through the existing workout path (`ActivityFileImporter`, the same parse the
///   manual workout-file import uses), stored under the watch's own device id, then recorded. A GPS
///   route goes to `RouteStore` under the row's natural key, so the workout detail draws its map; the
///   watch's Training Effect and training load go to `deviceMetricSample` for the same detail.
/// Kotlin twin: `GarminHistoryIngest` (android/.../data/GarminHistoryIngest.kt).
enum GarminHistoryIngest {

    static func syncedKeys(store: WhoopStore, deviceId: String) async -> Set<GarminSyncFileKey> {
        let rows = (try? await store.garminSyncedFiles(deviceId: deviceId)) ?? []
        return Set(rows.map {
            GarminSyncFileKey(fileIndex: UInt16(truncatingIfNeeded: $0.fileIndex), dataType: UInt8(truncatingIfNeeded: $0.dataType),
                              subType: UInt8(truncatingIfNeeded: $0.subType), fileNumber: UInt16(truncatingIfNeeded: $0.fileNumber),
                              garminTimestamp: UInt32(truncatingIfNeeded: $0.ts), size: UInt32(truncatingIfNeeded: $0.size))
        })
    }

    /// Once per watch, repair the nights an older fold stored. Two faults are corrected:
    ///  - #47: a night split at every single-stage stretch over an hour and started at its first stage
    ///    change. The stored SLEEP files are forgotten, so the next sync downloads them again and re-folds
    ///    them over the watch's own sleep window; the store replaces the overlapping split rows.
    ///  - #49: efficiency stored as a percent (93.3) where every reader expects a fraction (0.933), and a
    ///    nap's totals written over the night's in the day rollup. Every stored efficiency of this watch
    ///    above 1 is divided by 100, which also covers nights older than the sync lookback; the re-fold
    ///    above rewrites the recent day rollups from their main night.
    /// Nothing on the watch changes. Version 2 of the key, so a watch repaired for #47 alone runs it once
    /// more. Kotlin twin: `GarminHistoryIngest.refetchSleepFilesOnce`.
    static func refetchSleepFilesOnce(store: WhoopStore, deviceId: String, defaults: UserDefaults = .standard,
                                      log: (String) -> Void) async {
        let key = "noop.garmin.sleepRefold.v2.\(deviceId)"
        guard !defaults.bool(forKey: key) else { return }
        do {
            let rescaled = try await store.repairGarminEfficiencyUnits(deviceId: deviceId)
            let forgotten = try await store.forgetGarminSyncedFiles(deviceId: deviceId, dataType: 255,
                                                                    subType: Int(GarminFitFileType.sleep.rawValue))
            defaults.set(true, forKey: key)
            log("Garmin: re-folding stored nights; \(forgotten) sleep files will download again, "
                + "\(rescaled) efficiency values rescaled from percent")
        } catch {
            log("Garmin: re-fold of stored nights postponed: \(error.localizedDescription)")
        }
    }

    /// Once per watch, re-derive every stored day's SpO2 and respiration as the overnight mean over its
    /// stored night (#52). Older ingests stored a midnight-to-midnight mean of every sample. The day spans
    /// use the phone's current UTC offset, as the fold does for a file without one. Kotlin twin:
    /// `GarminHistoryIngest.rederiveNightVitalsOnce`.
    static func rederiveNightVitalsOnce(store: WhoopStore, deviceId: String, defaults: UserDefaults = .standard,
                                        utcOffsetS: Int = TimeZone.current.secondsFromGMT(),
                                        log: (String) -> Void) async {
        let key = "noop.garmin.nightVitals.v1.\(deviceId)"
        guard !defaults.bool(forKey: key) else { return }
        do {
            let days = try await store.garminDays(deviceId: deviceId).map { day -> GarminDayRollup in
                let start = GarminWatchIngest.dayStartUnix(day, utcOffsetS: utcOffsetS)
                return GarminDayRollup(day: day, dayStartTs: start, dayEndTs: start + 86_400)
            }
            let changed = try await store.rederiveGarminNightVitals(deviceId: deviceId, days: days)
            defaults.set(true, forKey: key)
            log("Garmin: re-derived overnight SpO2 / respiration for \(days.count) days (\(changed) points)")
        } catch {
            log("Garmin: overnight vitals re-derive postponed: \(error.localizedDescription)")
        }
    }

    static func ledgerRow(_ key: GarminSyncFileKey) -> GarminSyncedFileRow {
        GarminSyncedFileRow(fileIndex: Int(key.fileIndex), dataType: Int(key.dataType), subType: Int(key.subType),
                            fileNumber: Int(key.fileNumber), ts: Int(key.garminTimestamp), size: Int(key.size))
    }

    /// Decode and store one file. True when it is stored and recorded; false leaves it "new", so the
    /// next sync fetches it again.
    static func ingest(_ file: GarminDownloadedFile, deviceId: String, store: WhoopStore,
                       routeDefaults: UserDefaults = .standard, log: (String) -> Void) async -> Bool {
        let now = Int(Date().timeIntervalSince1970)
        let row = ledgerRow(file.key)
        do {
            if file.fitType == .activity {
                if let kept = try await ingestActivity(file, deviceId: deviceId, store: store, log: log),
                   let route = kept.route {
                    RouteStore.store(route, startTs: kept.row.startTs, sport: kept.row.sport, into: routeDefaults)
                }
                try await store.recordGarminSyncedFile(row, deviceId: deviceId, ingestedAt: now)
                return true
            }
            let samples = GarminWellnessParser.parse(data: Data(file.bytes))
            let batch = GarminWatchIngest.fold(samples, file: row,
                                               fallbackUtcOffsetS: TimeZone.current.secondsFromGMT())
            let counts = try await store.ingestGarminFile(batch, deviceId: deviceId, ingestedAt: now)
            log("Garmin: stored \(file.fitType?.name ?? "file") \(file.key.label): hr=\(counts.hr) metrics=\(counts.deviceMetric) "
                + "days=\(counts.days) sleeps=\(counts.sleeps) points=\(counts.metricPoints)"
                + (samples.complete ? "" : " (file ended early; kept what decoded)"))
            return true
        } catch {
            log("Garmin: storing \(file.key.label) failed: \(error.localizedDescription)")
            return false
        }
    }

    /// The manual workout-file import's mapping (DataSourcesView `importActivityFile`), under the watch's
    /// device id: one workout row plus its per-sample heart rate. A file with no usable activity is
    /// recorded anyway, so it is not fetched on every sync. Returns the stored row and its route (nil when
    /// the file had fewer than two GPS points); `ingest` keeps the route in `RouteStore`.
    private static func ingestActivity(_ file: GarminDownloadedFile, deviceId: String, store: WhoopStore,
                                       log: (String) -> Void) async throws -> (row: WorkoutRow, route: WorkoutRoute?)? {
        let result = ActivityFileImporter.parse(data: Data(file.bytes), filename: "garmin-activity.fit")
        guard let activity = result.activity, let duration = activity.durationS, duration > 0 else {
            log("Garmin: activity \(file.key.label) has no usable workout; recorded as synced")
            return nil
        }
        let row = WorkoutRow(
            startTs: Int(activity.start.timeIntervalSince1970),
            endTs: Int(activity.end.timeIntervalSince1970),
            sport: ActivityFileImporter.workoutSport(from: activity.sport),
            source: deviceId,
            durationS: activity.durationS,
            energyKcal: activity.energyKcal,
            avgHr: activity.avgHr,
            maxHr: activity.maxHr,
            strain: nil,                       // never a fabricated cardiovascular strain
            distanceM: activity.distanceM,
            zonesJSON: nil,
            notes: activity.importNote(),
            steps: activity.steps)
        try await store.upsertWorkouts([row], deviceId: deviceId)
        // The per-sample HR, plus the watch's Training Effect and training load for the workout detail.
        let streams = Streams(hr: activity.hrSamples.map { HRSample(ts: $0.ts, bpm: $0.bpm) },
                              deviceMetric: GarminWatchIngest.activityReference(activity))
        if !streams.isEmpty {
            _ = try await store.insert(streams, deviceId: deviceId)
        }
        let route = workoutRoute(from: activity)
        log("Garmin: stored workout \(row.sport) \(row.startTs)-\(row.endTs) hr=\(activity.hrSamples.count) "
            + "route=\(route == nil ? 0 : activity.route.count) from \(file.key.label)")
        return (row, route)
    }

    /// The route to keep for a decoded activity, or nil when it has fewer than two GPS points. The
    /// polyline is the same precision-5 encoding Android stores in `WorkoutRow.routePolyline`. No
    /// per-point measurements: the FIT route carries no horizontal accuracy, and a guessed one would make
    /// the route look exportable as a measured series.
    static func workoutRoute(from activity: ActivityFile) -> WorkoutRoute? {
        guard activity.route.count >= 2 else { return nil }
        let points = activity.route.map { RouteMath.LatLng($0.lat, $0.lon) }
        return WorkoutRoute(polyline: RouteMath.encode(points), distanceM: RouteMath.totalMeters(points))
    }
}
