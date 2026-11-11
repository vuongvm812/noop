import Foundation
import WhoopStore
import StrandImport

/// Stores the FIT files of a Garmin Connect export under `garmin-import`: ACTIVITY files through the same
/// workout ingest the watch sync uses (row, HR, Training Effect / load, per-record power / cadence / altitude,
/// route), every other health type through the wellness fold and the watch store. A file that holds nothing
/// storable is counted under its `skipped.*` reason. Routing and decoding are the pure
/// `GarminExportFit.decode` (StrandImport). Kotlin twin: `GarminExportFit.ingest`.
enum GarminExportFitIngest {

    /// Idempotent: workouts upsert by natural key, HR / metrics / days / sleeps by their keys, and a file's
    /// ledger row is keyed on its type, creation time and size. Never throws for a bad file.
    static func ingest(_ files: [String: Data], store: WhoopStore,
                       routeDefaults: UserDefaults = .standard,
                       utcOffsetS: Int = TimeZone.current.secondsFromGMT(),
                       log: (String) -> Void = { _ in }) async -> GarminExportFit.Report {
        var report = GarminExportFit.Report()
        let deviceId = GarminExportFit.deviceId
        let now = Int(Date().timeIntervalSince1970)
        // Sorted so the stored result and the log read the same on every run.
        for path in files.keys.sorted() {
            guard let data = files[path] else { continue }
            do {
                switch GarminExportFit.decode(data, utcOffsetS: utcOffsetS) {
                case .skipped(let reason):
                    report.counts[reason, default: 0] += 1
                case .activity:
                    if let kept = try await GarminHistoryIngest.storeActivity(data, deviceId: deviceId, store: store,
                                                                              log: log, label: path) {
                        if let route = kept.route {
                            RouteStore.store(route, startTs: kept.row.startTs, sport: kept.row.sport, into: routeDefaults)
                        }
                        report.counts[GarminExportFit.keyActivity, default: 0] += 1
                        report.counts[GarminExportFit.keyWorkouts, default: 0] += 1
                    } else {
                        report.counts[GarminExportFit.skipNoUsableActivity, default: 0] += 1
                    }
                case .wellness(let key, let batch):
                    _ = try await store.ingestGarminFile(batch, deviceId: deviceId, ingestedAt: now)
                    report.counts[key, default: 0] += 1
                }
            } catch is CancellationError {
                break
            } catch {
                log("Garmin export: \(path) failed: \(error.localizedDescription)")
                report.counts[GarminExportFit.skipFailed, default: 0] += 1
            }
        }
        return report
    }
}
