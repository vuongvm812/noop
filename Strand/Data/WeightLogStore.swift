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
