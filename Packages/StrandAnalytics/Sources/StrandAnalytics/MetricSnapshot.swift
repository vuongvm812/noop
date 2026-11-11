import Foundation

// MetricSnapshot.swift — every metric the app stores, for one day or a date range, grouped by category.
//
// Pure and database-free. A data layer (`MetricSnapshotLoader` in the app target) reads the stores and
// hands the per-day values here keyed by catalog id; this file owns the catalog (ids, labels, units,
// roll-ups), the aggregation over a range and the number formatting, so the Daily summary page, the
// weekly digest sections and the AI Coach context all print the same figure the same way.
// Kotlin twin: `MetricSnapshot` (com.noop.analytics.MetricSnapshot). Keep the two byte-identical.
//
// Entry points:
//   • `MetricSnapshotCatalog.allItems()` / `.items(in:)`
//   • `MetricSnapshotBuilder.build` — values → `MetricSnapshot` (one day: from == to)
//   • `MetricSnapshotCategory.id` — stable ids, safe to use as per-category setting keys

/// The groups of a snapshot, in display order. `id` is stable and safe to persist as a setting key
/// (the Coach's per-category sharing switches); never renumber or rename an id.
public enum MetricSnapshotCategory: String, CaseIterable, Sendable {
    case recoveryStrain = "recovery_strain"
    case sleep = "sleep"
    case heart = "heart"
    case activity = "activity"
    case workouts = "workouts"
    case body = "body_composition"
    case fitness = "fitness_scores"
    case watch = "watch"
    case nutrition = "nutrition"
    case hydration = "hydration"
    case mind = "mood_journal"
    case cycle = "cycle"
    case labs = "lab_markers"

    /// The stable id.
    public var id: String { rawValue }

    /// English label; the UI localises its own.
    public var label: String {
        switch self {
        case .recoveryStrain: return "Recovery & strain"
        case .sleep: return "Sleep"
        case .heart: return "Heart"
        case .activity: return "Activity"
        case .workouts: return "Workouts"
        case .body: return "Body composition"
        case .fitness: return "Fitness scores"
        case .watch: return "Watch metrics"
        case .nutrition: return "Nutrition"
        case .hydration: return "Hydration"
        case .mind: return "Mood & journal"
        case .cycle: return "Cycle"
        case .labs: return "Lab markers"
        }
    }

}

/// How a metric's daily values roll up over a range. Kotlin twin: `SnapshotRollup`.
public enum SnapshotRollup: Sendable { case mean, sum, latest, max, min }

/// Which way a change reads, for the weekly cards. Kotlin twin: `SnapshotDirection`.
public enum SnapshotDirection: Sendable { case higherBetter, lowerBetter, neutral }

/// One metric the snapshot reports. `seriesKey` + `sources` describe how the data layer reads a stored
/// series (sources are tried in order, the first with a value for a day wins); a nil `seriesKey` means the
/// data layer derives the value itself (workouts, hydration, cycle, watch samples) and supplies it by `id`.
/// Same fields as the Kotlin MetricSnapshotItem.
public struct MetricSnapshotItem: Sendable, Equatable {
    public let id: String
    public let category: MetricSnapshotCategory
    /// English label; the UI localises its own.
    public let label: String
    /// Display unit ("" for a unitless score). "kg" and "°C" are converted by the UI layer.
    public let unit: String
    public let decimals: Int
    public let rollup: SnapshotRollup
    public let direction: SnapshotDirection
    public let seriesKey: String?
    public let sources: [String]
}

/// A free-text line (a lab marker, a journal answer, the cycle phase). Same fields as the Kotlin MetricSnapshotNote.
public struct MetricSnapshotNote: Sendable, Equatable {
    public let category: MetricSnapshotCategory
    public let id: String
    public let label: String
    public let text: String

    public init(category: MetricSnapshotCategory, id: String, label: String, text: String) {
        self.category = category; self.id = id; self.label = label; self.text = text
    }
}

/// One resolved line: `value` is nil when the range carried no reading. Kotlin twin: `MetricSnapshotRow`.
public struct MetricSnapshotRow: Sendable, Equatable {
    public let item: MetricSnapshotItem
    public let value: Double?
    /// Days of the range that carried a reading.
    public let days: Int

    public init(item: MetricSnapshotItem, value: Double?, days: Int) {
        self.item = item; self.value = value; self.days = days
    }

    /// The line's figure, or "-" when missing.
    public var text: String { MetricSnapshotFormat.text(item, value) }
}

/// One category's rows plus its free-text notes. Kotlin twin: `MetricSnapshotSection`.
public struct MetricSnapshotSection: Sendable, Equatable {
    public let category: MetricSnapshotCategory
    public let rows: [MetricSnapshotRow]
    public let notes: [MetricSnapshotNote]

    /// True when at least one row carries a value or a note exists.
    public var hasData: Bool { !notes.isEmpty || rows.contains { $0.value != nil } }
}

/// The whole snapshot, every category present in display order. Kotlin twin: `MetricSnapshot`.
public struct MetricSnapshot: Sendable, Equatable {
    public let from: String
    public let to: String
    public let sections: [MetricSnapshotSection]

    /// The row with catalog id `id`. Kotlin twin: `MetricSnapshot.row`.
    public func row(_ id: String) -> MetricSnapshotRow? {
        for s in sections { if let r = s.rows.first(where: { $0.item.id == id }) { return r } }
        return nil
    }

    /// True when no category carries any value.
    public var isEmpty: Bool { !sections.contains { $0.hasData } }
}

public enum MetricSnapshotCatalog {
    private static let w = "my-whoop"
    private static let a = "apple-health"
    /// The Garmin export import's source id. Kotlin twin: `MetricSnapshotCatalog.G`.
    private static let g = "garmin-import"

    /// Catalog entry builder. Kotlin twin: `MetricSnapshotCatalog.m`.
    private static func m(
        _ id: String, _ cat: MetricSnapshotCategory, _ label: String, _ unit: String, _ decimals: Int,
        rollup: SnapshotRollup = .mean, direction: SnapshotDirection = .neutral,
        key: String? = nil, sources: [String] = []
    ) -> MetricSnapshotItem {
        MetricSnapshotItem(id: id, category: cat, label: label, unit: unit, decimals: decimals,
                           rollup: rollup, direction: direction, seriesKey: key, sources: sources)
    }


    /// Every item in category order, then display order. Ids equal the stored series key where one exists.
    /// Kotlin twin: `MetricSnapshotCatalog.allItems`.
    public static func allItems() -> [MetricSnapshotItem] { [
        // Recovery & strain
        m("recovery", .recoveryStrain, "Charge", "%", 0, direction: .higherBetter, key: "recovery", sources: [w]),
        m("strain", .recoveryStrain, "Effort", "/100", 1, key: "strain", sources: [w]),
        m("hrv", .recoveryStrain, "HRV", "ms", 0, direction: .higherBetter, key: "hrv", sources: [w]),
        m("rhr", .recoveryStrain, "Resting heart rate", "bpm", 0, direction: .lowerBetter, key: "rhr", sources: [w]),
        m("stress", .recoveryStrain, "Day stress", "/3", 1, direction: .lowerBetter, key: "stress", sources: [w]),
        // Sleep
        m("sleep_performance", .sleep, "Rest", "%", 0, direction: .higherBetter, key: "sleep_performance", sources: [w]),
        m("sleep_total_min", .sleep, "Asleep time", "min", 0, direction: .higherBetter, key: "sleep_total_min", sources: [w]),
        m("in_bed_min", .sleep, "Time in bed", "min", 0, key: "in_bed_min", sources: [w]),
        m("sleep_efficiency", .sleep, "Sleep efficiency", "%", 0, direction: .higherBetter, key: "sleep_efficiency", sources: [w]),
        m("sleep_deep_min", .sleep, "Deep sleep", "min", 0, direction: .higherBetter, key: "sleep_deep_min", sources: [w]),
        m("sleep_rem_min", .sleep, "REM sleep", "min", 0, direction: .higherBetter, key: "sleep_rem_min", sources: [w]),
        m("sleep_light_min", .sleep, "Light sleep", "min", 0, key: "sleep_light_min", sources: [w]),
        m("restorative_min", .sleep, "Restorative sleep", "min", 0, direction: .higherBetter, key: "restorative_min", sources: [w]),
        m("sleep_need_min", .sleep, "Sleep need", "min", 0, key: "sleep_need_min", sources: [w]),
        m("sleep_debt_min", .sleep, "Sleep debt", "min", 0, direction: .lowerBetter, key: "sleep_debt_min", sources: [w]),
        m("sleep_consistency", .sleep, "Sleep consistency", "%", 0, direction: .higherBetter, key: "sleep_consistency", sources: [w]),
        m("hours_vs_needed_pct", .sleep, "Hours vs needed", "%", 0, direction: .higherBetter, key: "hours_vs_needed_pct", sources: [w]),
        m("disturbances", .sleep, "Disturbances", "", 0, direction: .lowerBetter),
        m("sleep_awake_count", .sleep, "Awakenings", "", 0, direction: .lowerBetter, key: "sleep_awake_count", sources: [g]),
        m("sleep_restless_moments", .sleep, "Restless moments", "", 0, direction: .lowerBetter, key: "sleep_restless_moments", sources: [g]),
        m("sleep_stress", .sleep, "Sleep stress", "", 0, direction: .lowerBetter, key: "sleep_stress", sources: [g]),
        m("sleep_awake_min", .sleep, "Awake during sleep", "min", 0, direction: .lowerBetter, key: "sleep_awake_min", sources: [g]),
        m("nap_min", .sleep, "Naps", "min", 0, rollup: .sum, key: "nap_min", sources: [g]),
        m("sleep_score_quality", .sleep, "Sleep quality score", "", 0, direction: .higherBetter, key: "sleep_score_quality", sources: [g]),
        m("sleep_score_duration", .sleep, "Sleep duration score", "", 0, direction: .higherBetter, key: "sleep_score_duration", sources: [g]),
        m("sleep_score_recovery", .sleep, "Sleep recovery score", "", 0, direction: .higherBetter, key: "sleep_score_recovery", sources: [g]),
        m("sleep_score_deep", .sleep, "Deep sleep score", "", 0, direction: .higherBetter, key: "sleep_score_deep", sources: [g]),
        m("sleep_score_rem", .sleep, "REM sleep score", "", 0, direction: .higherBetter, key: "sleep_score_rem", sources: [g]),
        m("sleep_score_restfulness", .sleep, "Sleep restfulness score", "", 0, direction: .higherBetter, key: "sleep_score_restfulness", sources: [g]),
        // Heart
        m("avg_hr", .heart, "Average heart rate", "bpm", 0, key: "avg_hr", sources: [w]),
        m("max_hr", .heart, "Max heart rate", "bpm", 0, rollup: .max, key: "max_hr", sources: [w, g]),
        m("resp_rate", .heart, "Respiratory rate", "rpm", 1, key: "resp_rate", sources: [w, g]),
        m("spo2", .heart, "Blood oxygen", "%", 0, direction: .higherBetter, key: "spo2", sources: [w, g]),
        m("skin_temp", .heart, "Skin temperature", "°C", 1, key: "skin_temp", sources: [w]),
        m("sdnn", .heart, "HRV (SDNN)", "ms", 0, direction: .higherBetter),
        m("hr_min", .heart, "Min heart rate", "bpm", 0, rollup: .min, key: "hr_min", sources: [g]),
        m("hr_overnight", .heart, "Overnight heart rate", "bpm", 0, direction: .lowerBetter, key: "hr_overnight", sources: [g]),
        m("resp_overnight", .heart, "Overnight respiratory rate", "rpm", 1, key: "resp_overnight", sources: [g]),
        m("spo2_min", .heart, "Lowest blood oxygen", "%", 0, rollup: .min, direction: .higherBetter, key: "spo2_min", sources: [g]),
        // Activity
        m("steps", .activity, "Steps", "steps", 0, rollup: .sum, direction: .higherBetter, key: "steps", sources: [w, a]),
        m("steps_est", .activity, "Steps (estimated)", "steps", 0, rollup: .sum, key: "steps_est", sources: [w]),
        m("distance_km", .activity, "Distance", "km", 1, rollup: .sum),
        m("energy_kcal", .activity, "Total calories", "kcal", 0, rollup: .sum, key: "energy_kcal", sources: [w]),
        m("active_kcal", .activity, "Active calories", "kcal", 0, rollup: .sum, key: "active_kcal", sources: [w, a]),
        m("basal_kcal", .activity, "Basal calories", "kcal", 0, rollup: .sum, key: "basal_kcal", sources: [a]),
        m("strength_min", .activity, "Strength activity", "min", 0, rollup: .sum, key: "strength_min", sources: [w]),
        m("hr_zones13_min", .activity, "HR zones 1-3", "min", 0, rollup: .sum, key: "hr_zones13_min", sources: [w]),
        m("hr_zones45_min", .activity, "HR zones 4-5", "min", 0, rollup: .sum, key: "hr_zones45_min", sources: [w]),
        m("hr_zones_all_min", .activity, "HR zones (all)", "min", 0, rollup: .sum, key: "hr_zones_all_min", sources: [w]),
        m("bmr_kcal", .activity, "Resting calories", "kcal", 0, rollup: .sum, key: "bmr_kcal", sources: [g]),
        m("floors_ascended_m", .activity, "Floors climbed", "m", 0, rollup: .sum, key: "floors_ascended_m", sources: [g]),
        m("moderate_min", .activity, "Moderate intensity", "min", 0, rollup: .sum, direction: .higherBetter, key: "moderate_min", sources: [g]),
        m("vigorous_min", .activity, "Vigorous intensity", "min", 0, rollup: .sum, direction: .higherBetter, key: "vigorous_min", sources: [g]),
        // Workouts (derived from the workout rows and their device samples)
        m("workout_count", .workouts, "Workouts", "", 0, rollup: .sum),
        m("workout_duration_min", .workouts, "Workout time", "min", 0, rollup: .sum),
        m("workout_energy_kcal", .workouts, "Workout calories", "kcal", 0, rollup: .sum),
        m("workout_distance_km", .workouts, "Workout distance", "km", 1, rollup: .sum),
        m("workout_avg_hr", .workouts, "Workout average heart rate", "bpm", 0),
        m("workout_max_hr", .workouts, "Workout max heart rate", "bpm", 0, rollup: .max),
        m("workout_effort", .workouts, "Workout effort", "/100", 1, rollup: .max),
        m("workout_zone1_min", .workouts, "Zone 1", "min", 0, rollup: .sum),
        m("workout_zone2_min", .workouts, "Zone 2", "min", 0, rollup: .sum),
        m("workout_zone3_min", .workouts, "Zone 3", "min", 0, rollup: .sum),
        m("workout_zone4_min", .workouts, "Zone 4", "min", 0, rollup: .sum),
        m("workout_zone5_min", .workouts, "Zone 5", "min", 0, rollup: .sum),
        m("workout_avg_power_w", .workouts, "Average power", "W", 0),
        m("workout_avg_cadence", .workouts, "Average cadence", "rpm", 0),
        m("workout_altitude_gain_m", .workouts, "Elevation gain", "m", 0, rollup: .sum),
        m("workout_max_altitude_m", .workouts, "Max altitude", "m", 0, rollup: .max),
        m("workout_aerobic_te", .workouts, "Aerobic training effect", "", 1, rollup: .max),
        m("workout_anaerobic_te", .workouts, "Anaerobic training effect", "", 1, rollup: .max),
        // Body composition
        m("weight", .body, "Weight", "kg", 1, rollup: .latest, key: "weight", sources: [a, g]),
        m("body_fat", .body, "Body fat", "%", 1, rollup: .latest, direction: .lowerBetter, key: "body_fat", sources: [a]),
        m("lean_mass", .body, "Lean mass", "kg", 1, rollup: .latest, direction: .higherBetter, key: "lean_mass", sources: [a]),
        m("bmi", .body, "BMI", "", 1, rollup: .latest, key: "bmi", sources: [a, g]),
        m("height_m", .body, "Height", "m", 2, rollup: .latest, key: "height_m", sources: [g]),
        // Fitness scores
        m("fitness_age", .fitness, "Fitness Age", "yrs", 0, rollup: .latest, direction: .lowerBetter, key: "fitness_age", sources: [w, g]),
        m("vitality", .fitness, "Vitality", "", 0, rollup: .latest, direction: .higherBetter, key: "vitality", sources: [w]),
        m("body_age", .fitness, "Body Age", "yrs", 0, rollup: .latest, direction: .lowerBetter, key: "body_age", sources: [w]),
        m("vo2max", .fitness, "VO₂ max", "ml/kg/min", 1, rollup: .latest, direction: .higherBetter, key: "vo2max", sources: [a, w, g]),
        m("vo2max_est", .fitness, "VO₂ max (estimated)", "ml/kg/min", 1, rollup: .latest, direction: .higherBetter, key: "vo2max_est", sources: [w]),
        m("ftp_w", .fitness, "Functional threshold power", "W", 0, rollup: .latest, direction: .higherBetter, key: "ftp_w", sources: [g]),
        m("lactate_threshold_hr", .fitness, "Lactate threshold heart rate", "bpm", 0, rollup: .latest, key: "lactate_threshold_hr", sources: [g]),
        m("race_5k_s", .fitness, "5K race prediction", "s", 0, rollup: .latest, direction: .lowerBetter, key: "race_5k_s", sources: [g]),
        m("race_10k_s", .fitness, "10K race prediction", "s", 0, rollup: .latest, direction: .lowerBetter, key: "race_10k_s", sources: [g]),
        m("race_half_s", .fitness, "Half marathon prediction", "s", 0, rollup: .latest, direction: .lowerBetter, key: "race_half_s", sources: [g]),
        m("race_marathon_s", .fitness, "Marathon prediction", "s", 0, rollup: .latest, direction: .lowerBetter, key: "race_marathon_s", sources: [g]),
        // Watch metrics (the paired watch's own series; the data layer reads them under the watch id)
        m("watch_stress", .watch, "Watch stress", "", 0, direction: .lowerBetter),
        m("body_battery_min", .watch, "Body Battery low", "", 0, rollup: .min),
        m("body_battery_max", .watch, "Body Battery high", "", 0, rollup: .max),
        m("body_battery_recharge", .watch, "Body Battery recharge", "", 0, direction: .higherBetter),
        m("watch_sleep_score", .watch, "Watch sleep score", "", 0, direction: .higherBetter),
        m("training_load", .watch, "Training load", "", 0, rollup: .sum),
        m("intensity_min", .watch, "Intensity minutes", "min", 0, rollup: .sum, direction: .higherBetter),
        m("watch_skin_temp_c", .watch, "Watch skin temperature", "°C", 1),
        m("stress_max", .watch, "Max stress", "", 0, rollup: .max, direction: .lowerBetter, key: "stress_max", sources: [g]),
        m("stress_low_min", .watch, "Low stress time", "min", 0, rollup: .sum, direction: .higherBetter, key: "stress_low_min", sources: [g]),
        m("stress_medium_min", .watch, "Medium stress time", "min", 0, rollup: .sum, key: "stress_medium_min", sources: [g]),
        m("stress_high_min", .watch, "High stress time", "min", 0, rollup: .sum, direction: .lowerBetter, key: "stress_high_min", sources: [g]),
        m("body_battery_charged", .watch, "Body Battery charged", "", 0, direction: .higherBetter, key: "body_battery_charged", sources: [g]),
        m("body_battery_drained", .watch, "Body Battery drained", "", 0, key: "body_battery_drained", sources: [g]),
        // Nutrition
        m("calories_in", .nutrition, "Calories in", "kcal", 0, rollup: .sum, key: "calories_in", sources: ["nutrition-csv"]),
        m("protein_g", .nutrition, "Protein", "g", 0, rollup: .sum, key: "protein_g", sources: ["nutrition-csv"]),
        m("carbs_g", .nutrition, "Carbs", "g", 0, rollup: .sum, key: "carbs_g", sources: ["nutrition-csv"]),
        m("fat_g", .nutrition, "Fat", "g", 0, rollup: .sum, key: "fat_g", sources: ["nutrition-csv"]),
        // Hydration
        m("hydration_ml", .hydration, "Water", "ml", 0, rollup: .sum),
        m("sweat_loss_ml", .hydration, "Sweat loss", "ml", 0, rollup: .sum, key: "sweat_loss_ml", sources: [g]),
        // Mood & journal
        m("mood", .mind, "Mood", "/5", 0, direction: .higherBetter, key: "mood", sources: ["noop-mood"]),
        // Cycle
        m("cycle_day", .cycle, "Cycle day", "", 0, rollup: .latest),
    ] }


    /// The category's items in display order (empty for the note-only Labs category).
    /// Kotlin twin: `MetricSnapshotCatalog.items`.
    public static func items(in category: MetricSnapshotCategory) -> [MetricSnapshotItem] {
        allItems().filter { $0.category == category }
    }
}

public enum MetricSnapshotFormat {
    /// The figure printed with the same rounding on both platforms; "-" for a missing value.
    /// Kotlin twin: `MetricSnapshotFormat.text`.
    public static func text(_ item: MetricSnapshotItem, _ value: Double?) -> String {
        guard let value, value.isFinite else { return "-" }
        let n = number(value, decimals: item.decimals)
        return item.unit.isEmpty ? n : "\(n) \(item.unit)"
    }

    /// `value` with `decimals` places, rounded half away from zero on the scaled magnitude with plain
    /// IEEE arithmetic (never `String(format:)`, whose tie rule differs from Kotlin's); a negative zero
    /// is printed unsigned. Kotlin twin: `MetricSnapshotFormat.number`.
    public static func number(_ value: Double, decimals: Int) -> String {
        let d = max(decimals, 0)
        var p: Int64 = 1
        for _ in 0..<d { p *= 10 }
        let n = Int64((abs(value) * Double(p) + 0.5).rounded(.down))
        let sign = (value < 0 && n != 0) ? "-" : ""
        if d == 0 { return "\(sign)\(n)" }
        let frac = String(n % p)
        return "\(sign)\(n / p).\(String(repeating: "0", count: d - frac.count))\(frac)"
    }
}

public enum MetricSnapshotBuilder {
    /// Roll `values` (item id → day → value) up over the inclusive range `from`...`to` into a snapshot.
    /// Days outside the range and non-finite values are ignored; a one-day range is the day's snapshot.
    /// `notes` are grouped under their category. Kotlin twin: `MetricSnapshotBuilder.build`.
    public static func build(
        from: String, to: String,
        values: [String: [String: Double]],
        notes: [MetricSnapshotNote] = []
    ) -> MetricSnapshot {
        let sections = MetricSnapshotCategory.allCases.map { cat -> MetricSnapshotSection in
            let rows = MetricSnapshotCatalog.items(in: cat).map { item -> MetricSnapshotRow in
                let inRange = (values[item.id] ?? [:])
                    .filter { $0.key >= from && $0.key <= to && $0.value.isFinite }
                    .sorted { $0.key < $1.key }
                return MetricSnapshotRow(item: item, value: roll(item.rollup, inRange.map { $0.value }), days: inRange.count)
            }
            return MetricSnapshotSection(category: cat, rows: rows, notes: notes.filter { $0.category == cat })
        }
        return MetricSnapshot(from: from, to: to, sections: sections)
    }

    /// Roll a day-ordered list of values up per `rollup`; nil when empty. Kotlin twin: `MetricSnapshotBuilder.roll`.
    public static func roll(_ rollup: SnapshotRollup, _ ordered: [Double]) -> Double? {
        guard let last = ordered.last else { return nil }
        switch rollup {
        case .mean: return ordered.reduce(0, +) / Double(ordered.count)
        case .sum: return ordered.reduce(0, +)
        case .latest: return last
        case .max: return ordered.max()
        case .min: return ordered.min()
        }
    }
}
