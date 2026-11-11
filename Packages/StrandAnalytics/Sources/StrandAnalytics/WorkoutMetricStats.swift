import Foundation

/// Summary figures for the workout detail's power, cadence and elevation graphs, computed over the plotted
/// (bucketed) series. Pure arithmetic, no I/O. Kotlin twin: `WorkoutMetricStats`.
public enum WorkoutMetricStats {
    /// Mean of the values above zero (a 0 W / 0 rpm bucket is the sensor idling, not a reading); nil when none.
    /// Kotlin twin: `WorkoutMetricStats.averagePositive`.
    public static func averagePositive(_ values: [Double]) -> Double? {
        var sum = 0.0
        var n = 0
        for v in values where v.isFinite && v > 0 { sum += v; n += 1 }
        return n == 0 ? nil : sum / Double(n)
    }

    /// Total climb: the sum of the positive deltas between consecutive values.
    /// Kotlin twin: `WorkoutMetricStats.elevationGain`.
    public static func elevationGain(_ values: [Double]) -> Double {
        var gain = 0.0
        if values.count > 1 {
            for i in 1..<values.count {
                let d = values[i] - values[i - 1]
                if d > 0 { gain += d }
            }
        }
        return gain
    }
}
