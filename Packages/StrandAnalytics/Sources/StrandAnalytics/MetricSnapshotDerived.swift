import Foundation

// MetricSnapshotDerived.swift — the pure halves of the snapshot loader's derived items (workout day
// aggregation, cycle day, hydration day total, journal/lab text). The app-layer `MetricSnapshotLoader`
// reads the stores and calls these. Kotlin twin: `MetricSnapshotLoader` (workoutDay / cycleDay /
// hydrationTotal / journalText / labText).

/// One workout as the day aggregation reads it, with its device-sample aggregates already resolved by
/// the data layer. Kotlin twin: `MetricSnapshotLoader.WorkoutInput` (the Android loader's per-workout input).
public struct SnapshotWorkout: Sendable, Equatable {
    public var startTs: Int
    public var endTs: Int
    public var durationS: Double?
    public var energyKcal: Double?
    public var avgHr: Double?
    public var maxHr: Double?
    public var strain: Double?
    public var distanceM: Double?
    public var zonesJSON: String?
    public var avgPowerW: Double?
    public var avgCadence: Double?
    public var altitudeGainM: Double?
    public var maxAltitudeM: Double?
    public var aerobicTe: Double?
    public var anaerobicTe: Double?

    public init(startTs: Int, endTs: Int, durationS: Double? = nil, energyKcal: Double? = nil,
                avgHr: Double? = nil, maxHr: Double? = nil, strain: Double? = nil, distanceM: Double? = nil,
                zonesJSON: String? = nil, avgPowerW: Double? = nil, avgCadence: Double? = nil,
                altitudeGainM: Double? = nil, maxAltitudeM: Double? = nil,
                aerobicTe: Double? = nil, anaerobicTe: Double? = nil) {
        self.startTs = startTs; self.endTs = endTs; self.durationS = durationS; self.energyKcal = energyKcal
        self.avgHr = avgHr; self.maxHr = maxHr; self.strain = strain; self.distanceM = distanceM
        self.zonesJSON = zonesJSON; self.avgPowerW = avgPowerW; self.avgCadence = avgCadence
        self.altitudeGainM = altitudeGainM; self.maxAltitudeM = maxAltitudeM
        self.aerobicTe = aerobicTe; self.anaerobicTe = anaerobicTe
    }

}

public enum MetricSnapshotDerived {

    /// One local day's workout items (`workout_*` catalog ids → value), only for the figures present.
    /// Averages are duration-weighted (a plain mean when no weight is known); zone minutes are
    /// duration × percentage ÷ 100 per session; altitude gain sums per session; peaks take the max.
    /// Kotlin twin: `MetricSnapshotLoader.workoutDay`.
    public static func workoutDay(_ ws: [SnapshotWorkout]) -> [String: Double] {
        guard !ws.isEmpty else { return [:] }
        var out: [String: Double] = ["workout_count": Double(ws.count)]
        let durMin = ws.map { max($0.durationS ?? Double($0.endTs - $0.startTs), 0) / 60.0 }
        out["workout_duration_min"] = durMin.reduce(0, +)
        let sum: ((SnapshotWorkout) -> Double?) -> Double? = { pick in
            let v = ws.compactMap(pick); return v.isEmpty ? nil : v.reduce(0, +)
        }
        let peak: ((SnapshotWorkout) -> Double?) -> Double? = { pick in ws.compactMap(pick).max() }
        let weighted: ((SnapshotWorkout) -> Double?) -> Double? = { pick in
            var num = 0.0, den = 0.0, plain = 0.0, n = 0.0
            for (w, d) in zip(ws, durMin) {
                guard let v = pick(w) else { continue }
                num += v * d; den += d; plain += v; n += 1
            }
            if n == 0 { return nil }
            return den > 0 ? num / den : plain / n
        }
        if let v = sum({ $0.energyKcal }) { out["workout_energy_kcal"] = v }
        if let v = sum({ $0.distanceM }) { out["workout_distance_km"] = v / 1000.0 }
        if let v = weighted({ $0.avgHr }) { out["workout_avg_hr"] = v }
        if let v = peak({ $0.maxHr }) { out["workout_max_hr"] = v }
        if let v = peak({ $0.strain }) { out["workout_effort"] = v }
        var zones = [Double](repeating: 0, count: 5), anyZone = false
        for (w, d) in zip(ws, durMin) {
            guard d > 0, let p = zonePercents(w.zonesJSON) else { continue }
            for i in 0..<5 { zones[i] += d * p[i] / 100.0 }
            anyZone = true
        }
        if anyZone { for i in 0..<5 { out["workout_zone\(i + 1)_min"] = zones[i] } }
        if let v = weighted({ $0.avgPowerW }) { out["workout_avg_power_w"] = v }
        if let v = weighted({ $0.avgCadence }) { out["workout_avg_cadence"] = v }
        if let v = sum({ $0.altitudeGainM }) { out["workout_altitude_gain_m"] = v }
        if let v = peak({ $0.maxAltitudeM }) { out["workout_max_altitude_m"] = v }
        if let v = peak({ $0.aerobicTe }) { out["workout_aerobic_te"] = v }
        if let v = peak({ $0.anaerobicTe }) { out["workout_anaerobic_te"] = v }
        return out
    }

    /// Z1…Z5 percentages (0–100) from a `zonesJSON` object ("z1".."z5" or "zone1".."zone5"), nil without
    /// usable data. Kotlin twin: `parseZonePercents`.
    public static func zonePercents(_ json: String?) -> [Double]? {
        guard let json, let data = json.data(using: .utf8),
              let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return nil }
        let p = (1...5).map { i -> Double in
            let v = (obj["z\(i)"] ?? obj["zone\(i)"]) as? NSNumber
            return min(max(v?.doubleValue ?? 0, 0), 100)
        }
        return p.contains(where: { $0 > 0 }) ? p : nil
    }

    /// The cycle day (1 = the logged start day) of `day` given the logged period-start days, nil when no
    /// start is on or before `day`. Days are "yyyy-MM-dd". Kotlin twin: `MetricSnapshotLoader.cycleDay`.
    public static func cycleDay(starts: [String], day: String) -> Double? {
        guard let start = starts.filter({ $0 <= day }).max(),
              let a = WeeklyDigestEngine.parseYMD(start), let b = WeeklyDigestEngine.parseYMD(day)
        else { return nil }
        let dayNumber: ((Int, Int, Int)) -> Int = { ymd in
            let (y, m, d) = ymd
            let a = (14 - m) / 12, yy = y + 4800 - a, mm = m + 12 * a - 3
            return d + (153 * mm + 2) / 5 + 365 * yy + yy / 4 - yy / 100 + yy / 400
        }
        let n = dayNumber(b) - dayNumber(a)
        return n >= 0 ? Double(n + 1) : nil
    }

    /// A day's water total: hand-logged plus imported, nil when both are absent or zero.
    /// Kotlin twin: `MetricSnapshotLoader.hydrationDays` (per-day sum).
    public static func hydrationTotal(manual: Double?, imported: Double?) -> Double? {
        let t = max(manual ?? 0, 0) + max(imported ?? 0, 0)
        return t > 0 ? t : nil
    }

    /// A journal answer as text: "Yes" / "No", with a logged number appended ("Yes (2)"). Kotlin twin: `MetricSnapshotLoader.journalText`.
    public static func journalText(answeredYes: Bool, numericValue: Double?) -> String {
        let base = answeredYes ? "Yes" : "No"
        guard let n = numericValue else { return base }
        var num = MetricSnapshotFormat.number(n, decimals: 1)
        if num.hasSuffix(".0") { num.removeLast(2) }
        return "\(base) (\(num))"
    }

    /// A lab reading as text: "value unit", else its qualitative text, else "-". The caller supplies the
    /// already-rounded number. Kotlin twin: `MetricSnapshotLoader.labText`.
    public static func labText(value: String?, unit: String, valueText: String?) -> String {
        if let value { return unit.isEmpty ? value : "\(value) \(unit)" }
        return valueText ?? "-"
    }
}
