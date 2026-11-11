import Foundation

// WeeklyDigestInputs.swift — the pure half of the weekly-digest input resolver (#89).
//
// The app layers read their stores (daily rows, a paired watch's daily series and intraday
// samples) and hand the results here as plain values; this file turns them into the
// day→value maps `WeeklyDigestEngine.build` consumes. Keeping the mapping pure means the
// Trends card and the Monday notification build the digest from the same function, and
// both platforms can pin it with a test. Kotlin twin: `WeeklyDigestInputs`.

/// One day's figures from the daily row, already resolved by the app layer (Rest through
/// the one Rest resolver, so the digest never disagrees with the Sleep screen).
public struct WeeklyDigestDay: Equatable, Sendable {
    public var day: String
    public var charge: Double?
    public var effort: Double?
    public var rest: Double?
    public var rhr: Double?
    public var hrv: Double?
    public var steps: Double?
    public var sleepMin: Double?
    /// As stored: a 0–1 fraction or a percent, depending on the source.
    public var efficiency: Double?
    public var deepMin: Double?
    public var remMin: Double?
    public var spo2: Double?
    public var respiration: Double?
    /// As stored: a ±°C deviation, or an absolute °C from a CSV import (dropped here).
    public var skinTemp: Double?

    public init(day: String, charge: Double? = nil, effort: Double? = nil, rest: Double? = nil,
                rhr: Double? = nil, hrv: Double? = nil, steps: Double? = nil, sleepMin: Double? = nil,
                efficiency: Double? = nil, deepMin: Double? = nil, remMin: Double? = nil,
                spo2: Double? = nil, respiration: Double? = nil, skinTemp: Double? = nil) {
        self.day = day
        self.charge = charge
        self.effort = effort
        self.rest = rest
        self.rhr = rhr
        self.hrv = hrv
        self.steps = steps
        self.sleepMin = sleepMin
        self.efficiency = efficiency
        self.deepMin = deepMin
        self.remMin = remMin
        self.spo2 = spo2
        self.respiration = respiration
        self.skinTemp = skinTemp
    }
}

/// The paired watch's per-day series, each keyed "yyyy-MM-dd". Empty for a strap-only user.
public struct WeeklyDigestWatchSeries: Equatable, Sendable {
    public var stress: [String: Double]
    public var sleepScore: [String: Double]
    public var activeKcal: [String: Double]
    public var trainingLoad: [String: Double]
    public var intensityMinutes: [String: Double]
    public var vo2max: [String: Double]
    public var bodyBatteryRecharge: [String: Double]

    public init(stress: [String: Double] = [:], sleepScore: [String: Double] = [:],
                activeKcal: [String: Double] = [:], trainingLoad: [String: Double] = [:],
                intensityMinutes: [String: Double] = [:], vo2max: [String: Double] = [:],
                bodyBatteryRecharge: [String: Double] = [:]) {
        self.stress = stress
        self.sleepScore = sleepScore
        self.activeKcal = activeKcal
        self.trainingLoad = trainingLoad
        self.intensityMinutes = intensityMinutes
        self.vo2max = vo2max
        self.bodyBatteryRecharge = bodyBatteryRecharge
    }
}

public enum WeeklyDigestInputs {

    /// The Body Battery look-around on each side of the night: the low is the minimum in
    /// the 3 h before sleep starts, the peak the maximum in the 3 h after wake.
    public static let rechargeWindowSeconds: Int = 3 * 3600

    /// The engine's series from the daily rows and the watch series. A day listed twice
    /// keeps its last row, as the hand-built maps this replaces did.
    /// Kotlin twin: `WeeklyDigestInputs.series`.
    public static func series(days: [WeeklyDigestDay],
                              watch: WeeklyDigestWatchSeries) -> [WeeklyMetric: [String: Double]] {
        var out: [WeeklyMetric: [String: Double]] = [:]
        // Kotlin twin: `WeeklyDigestInputs.put` (the same local helper).
        func put(_ m: WeeklyMetric, _ day: String, _ v: Double?) {
            guard let v = v, v.isFinite else { return }
            out[m, default: [:]][day] = v
        }
        for d in days {
            put(.charge, d.day, d.charge)
            put(.effort, d.day, d.effort)
            put(.rest, d.day, d.rest)
            put(.rhr, d.day, d.rhr)
            put(.hrv, d.day, d.hrv)
            put(.steps, d.day, d.steps)
            put(.sleepDuration, d.day, d.sleepMin.map { $0 / 60.0 })
            put(.sleepEfficiency, d.day, d.efficiency.map(efficiencyPercent))
            put(.deepSleep, d.day, d.deepMin)
            put(.remSleep, d.day, d.remMin)
            put(.spo2, d.day, d.spo2)
            put(.respiration, d.day, d.respiration)
            put(.skinTempDeviation, d.day, d.skinTemp.flatMap { VitalBands.isAbsoluteSkinTemp($0) ? nil : $0 })
        }
        let watchMaps: [(WeeklyMetric, [String: Double])] = [
            (.stress, watch.stress), (.sleepScore, watch.sleepScore), (.activeKcal, watch.activeKcal),
            (.trainingLoad, watch.trainingLoad), (.intensityMinutes, watch.intensityMinutes),
            (.vo2max, watch.vo2max), (.bodyBatteryRecharge, watch.bodyBatteryRecharge),
        ]
        for (m, map) in watchMaps {
            for (day, v) in map { put(m, day, v) }
        }
        return out
    }

    /// The series of the metrics read from the snapshot stores (body composition, workouts, nutrition, ...):
    /// each `WeeklyMetric` naming a `snapshotId` takes that item's per-day values from `snapshotValues`
    /// (`MetricSnapshotItem.id` → day → value). Disjoint from `series`'s metrics, so callers merge the two.
    /// Kotlin twin: `WeeklyDigestInputs.snapshotSeries`.
    public static func snapshotSeries(_ snapshotValues: [String: [String: Double]]) -> [WeeklyMetric: [String: Double]] {
        var out: [WeeklyMetric: [String: Double]] = [:]
        for m in WeeklyMetric.allCases {
            guard let id = m.snapshotId, let vals = snapshotValues[id] else { continue }
            let finite = vals.filter { $0.value.isFinite }
            if !finite.isEmpty { out[m] = finite }
        }
        return out
    }

    /// Efficiency as a percent: a stored 0–1 fraction is scaled, a percent passes through.
    /// Kotlin twin: `WeeklyDigestInputs.efficiencyPercent`.
    public static func efficiencyPercent(_ v: Double) -> Double { v <= 1.0 ? v * 100.0 : v }

    /// Intensity minutes per day: moderate + 2 × vigorous, over the days either reports.
    /// Kotlin twin: `WeeklyDigestInputs.intensityMinutes`.
    public static func intensityMinutes(moderate: [String: Double],
                                        vigorous: [String: Double]) -> [String: Double] {
        var out: [String: Double] = [:]
        for day in Set(moderate.keys).union(vigorous.keys) {
            out[day] = (moderate[day] ?? 0) + 2 * (vigorous[day] ?? 0)
        }
        return out
    }

    /// One night's Body Battery recharge: the morning peak in the `rechargeWindowSeconds`
    /// after wake minus the lowest reading in the same window before sleep starts. nil when
    /// either side has no reading (a gap must not read as a zero).
    /// Kotlin twin: `WeeklyDigestInputs.bodyBatteryRecharge`.
    public static func bodyBatteryRecharge(samples: [(ts: Int, value: Double)],
                                           sleepStart: Int, sleepEnd: Int) -> Double? {
        let before = samples.filter { $0.ts >= sleepStart - rechargeWindowSeconds && $0.ts <= sleepStart }
        let after = samples.filter { $0.ts >= sleepEnd && $0.ts <= sleepEnd + rechargeWindowSeconds }
        guard let low = before.map(\.value).min(), let peak = after.map(\.value).max() else { return nil }
        return peak - low
    }
}
