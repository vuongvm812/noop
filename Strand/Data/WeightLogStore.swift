import Foundation
import StrandAnalytics
import WhoopStore

// MARK: - Weight diary persistence (#57)
//
// One hand-logged weight per local day, stored in the generic `metricSeries` table under
// `WeightLog.sourceId` / `WeightLog.key` (kilograms). No schema change, and the rows ride inside the
// `.noopbak` SQLite like every other series, so a restore on either platform brings the diary back.
// Every write also points the profile weight (which calorie / BMR math reads) at the newest logged
// weight, through `WeightLog.profileWeight`. Kotlin twin: `com.noop.ui.WeightLogStore`.

extension Repository {

    /// Every logged weight, oldest day first. Empty when nothing is logged or the store is unavailable.
    func weightLogEntries() async -> [WeightLog.Entry] {
        guard let store = await storeHandle() else { return [] }
        let pts = (try? await store.metricSeries(deviceId: WeightLog.sourceId, key: WeightLog.key,
                                                 from: "0000-01-01", to: "9999-12-31")) ?? []
        return pts.map { WeightLog.Entry(day: $0.day, kg: $0.value) }
    }

    /// The newest body fat (0-100 %) and lean mass (kg) across Apple Health, Health Connect and the Lab Book
    /// projection (which carries body fat only), each nil when no source holds one. The strictly newest day wins; a same-day tie keeps the
    /// earlier source in that order. One LIMIT-1 read per source and key.
    /// Kotlin twin: `WhoopRepository.latestBodyComposition`.
    func latestBodyComposition() async -> WeightLog.BodyComposition {
        guard let store = await storeHandle() else { return WeightLog.BodyComposition(bodyFatPct: nil, leanMassKg: nil) }
        let sources = ["apple-health", "health-connect", WhoopStore.labBookSourceId]
        func newest(_ key: String, maxValue: Double?) async -> WeightLog.BodyReading? {
            var rows: [WeightLog.BodyReading?] = []
            for src in sources {
                let p = try? await store.latestMetricPoint(deviceId: src, key: key)
                rows.append(p.map { WeightLog.BodyReading(day: $0.day, value: $0.value) })
            }
            return WeightLog.newestBodyReading(rows, maxValue: maxValue)
        }
        return WeightLog.BodyComposition(bodyFatPct: await newest("body_fat", maxValue: 100),
                                         leanMassKg: await newest("lean_mass", maxValue: nil))
    }

    /// Log (or replace) `day`'s weight. An invalid weight is ignored. Returns true when it was stored.
    @discardableResult
    func setLoggedWeight(day: String, kg: Double, profile: ProfileStore) async -> Bool {
        guard WeightLog.isValid(kg: kg), let store = await storeHandle() else { return false }
        do {
            _ = try await store.upsertMetricSeries([MetricPoint(day: day, key: WeightLog.key, value: kg)],
                                                   deviceId: WeightLog.sourceId)
        } catch {
            return false
        }
        await syncProfileWeightFromLog(profile)
        noteWeightLogChanged()
        return true
    }

    /// Remove `day`'s logged weight. The profile then follows the newest remaining entry, if any.
    func deleteLoggedWeight(day: String, profile: ProfileStore) async {
        guard let store = await storeHandle() else { return }
        _ = try? await store.deleteMetricSeriesPoint(deviceId: WeightLog.sourceId, day: day, key: WeightLog.key)
        await syncProfileWeightFromLog(profile)
        noteWeightLogChanged()
    }

    /// Point the profile weight at the newest logged weight. An empty diary leaves the profile as it is.
    private func syncProfileWeightFromLog(_ profile: ProfileStore) async {
        guard let kg = WeightLog.profileWeight(logged: await weightLogEntries()) else { return }
        if profile.weightKg != kg { profile.weightKg = kg }
    }
}
