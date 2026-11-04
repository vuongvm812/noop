import Foundation

/// The ONE definition of a day's "Calories" figure on every surface (#51): whole-day TOTAL energy,
/// resting metabolic rate included.
///
/// Two stores hold a day's energy. The phone's import (Apple Health / Health Connect, `AppleDaily`) splits
/// it into active and basal energy; every other source stores the total in `DailyMetric.activeKcalEst`
/// (NOOP's own estimate, and each brand import's watch total). A phone day counts only when it carries
/// BOTH halves: its active energy alone is a different quantity, and charting it beside a total reads
/// ~500 kcal next to ~2,400. Resolved imported-first, the precedence the Today surfaces already used
/// (#616), so a day the phone covers shows the phone's figure.
///
/// Kotlin twin: `com.noop.analytics.DayCalories`.
public enum DayCalories {
    /// The ring scale of the Today Calories tile: a day's total energy as a fraction of this.
    public static let tileScaleKcal: Double = 3_000

    /// A phone day's total energy: active + basal when both are positive, else nil.
    /// Kotlin twin: `DayCalories.importedTotal`.
    public static func importedTotal(activeKcal: Double?, basalKcal: Double?) -> Double? {
        guard let active = activeKcal, active > 0, let basal = basalKcal, basal > 0 else { return nil }
        return active + basal
    }

    /// One phone row of a day: its active and basal energy.
    public struct ImportedDay: Equatable, Sendable {
        public let day: String
        public let activeKcal: Double?
        public let basalKcal: Double?

        public init(day: String, activeKcal: Double?, basalKcal: Double?) {
            self.day = day
            self.activeKcal = activeKcal
            self.basalKcal = basalKcal
        }
    }

    /// Every day either store covers, resolved: the phone's total when it has one, else the stored total.
    /// Several phone rows on one day (Apple Health and Health Connect) keep the largest total. Sorted by
    /// day. Kotlin twin: `DayCalories.byDay`.
    public static func byDay(imported: [ImportedDay], stored: [String: Double]) -> [(day: String, kcal: Double)] {
        var phone: [String: Double] = [:]
        for row in imported {
            guard let total = importedTotal(activeKcal: row.activeKcal, basalKcal: row.basalKcal) else { continue }
            phone[row.day] = max(phone[row.day] ?? 0, total)
        }
        return Set(phone.keys).union(stored.keys).sorted().compactMap { day in
            if let total = phone[day] { return (day, total) }
            guard let total = stored[day], total > 0 else { return nil }
            return (day, total)
        }
    }
}
