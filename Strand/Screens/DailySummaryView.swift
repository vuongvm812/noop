import SwiftUI
import StrandDesign
import StrandAnalytics
import Foundation

// MARK: - Daily summary
//
// Every metric the app stores for one day, grouped by category, "-" where a metric has no reading. The
// numbers come from `MetricSnapshotLoader.snapshot` (the same snapshot the weekly digest and the AI Coach
// read), so this page can never disagree with them. The morning recap and Coach brief notifications open it
// for the notification's day. Informational only. Kotlin twin: `DailySummaryScreen`.

/// Localized names and value text for the snapshot, resolved at the render site only: the catalog ids and
/// English labels stay the stable identifiers. Kotlin twin: `DailySummaryScreen.snapshotDisplayText` (value text only; the Android UI localizes labels through string resources).
enum DailySummaryText {

    /// The category heading in the current locale.
    static func title(_ c: MetricSnapshotCategory) -> String {
        switch c {
        case .recoveryStrain: return String(localized: "Recovery & strain")
        case .sleep: return String(localized: "Sleep")
        case .heart: return String(localized: "Heart")
        case .activity: return String(localized: "Activity")
        case .workouts: return String(localized: "Workouts")
        case .body: return String(localized: "Body composition")
        case .fitness: return String(localized: "Fitness scores")
        case .watch: return String(localized: "Watch metrics")
        case .nutrition: return String(localized: "Nutrition")
        case .hydration: return String(localized: "Hydration")
        case .mind: return String(localized: "Mood & journal")
        case .cycle: return String(localized: "Cycle")
        case .labs: return String(localized: "Lab markers")
        }
    }

    /// The row label in the current locale, by catalog id.
    static func label(_ id: String) -> String {
        switch id {
        case "recovery": return String(localized: "Charge")
        case "strain": return String(localized: "Effort")
        case "hrv": return String(localized: "HRV")
        case "rhr": return String(localized: "Resting heart rate")
        case "stress": return String(localized: "Day stress")
        case "sleep_performance": return String(localized: "Rest")
        case "sleep_total_min": return String(localized: "Asleep time")
        case "in_bed_min": return String(localized: "Time in bed")
        case "sleep_efficiency": return String(localized: "Sleep efficiency")
        case "sleep_deep_min": return String(localized: "Deep sleep")
        case "sleep_rem_min": return String(localized: "REM sleep")
        case "sleep_light_min": return String(localized: "Light sleep")
        case "restorative_min": return String(localized: "Restorative sleep")
        case "sleep_need_min": return String(localized: "Sleep need")
        case "sleep_debt_min": return String(localized: "Sleep debt")
        case "sleep_consistency": return String(localized: "Sleep consistency")
        case "hours_vs_needed_pct": return String(localized: "Hours vs needed")
        case "disturbances": return String(localized: "Disturbances")
        case "avg_hr": return String(localized: "Average heart rate")
        case "max_hr": return String(localized: "Max heart rate")
        case "resp_rate": return String(localized: "Respiratory rate")
        case "spo2": return String(localized: "Blood oxygen")
        case "skin_temp": return String(localized: "Skin temperature")
        case "sdnn": return String(localized: "HRV (SDNN)")
        case "steps": return String(localized: "Steps")
        case "steps_est": return String(localized: "Steps (estimated)")
        case "distance_km": return String(localized: "Distance")
        case "energy_kcal": return String(localized: "Total calories")
        case "active_kcal": return String(localized: "Active calories")
        case "basal_kcal": return String(localized: "Basal calories")
        case "strength_min": return String(localized: "Strength activity")
        case "hr_zones13_min": return String(localized: "HR zones 1-3")
        case "hr_zones45_min": return String(localized: "HR zones 4-5")
        case "hr_zones_all_min": return String(localized: "HR zones (all)")
        case "workout_count": return String(localized: "Workouts")
        case "workout_duration_min": return String(localized: "Workout time")
        case "workout_energy_kcal": return String(localized: "Workout calories")
        case "workout_distance_km": return String(localized: "Workout distance")
        case "workout_avg_hr": return String(localized: "Workout average heart rate")
        case "workout_max_hr": return String(localized: "Workout max heart rate")
        case "workout_effort": return String(localized: "Workout effort")
        case "workout_zone1_min": return String(localized: "Zone 1")
        case "workout_zone2_min": return String(localized: "Zone 2")
        case "workout_zone3_min": return String(localized: "Zone 3")
        case "workout_zone4_min": return String(localized: "Zone 4")
        case "workout_zone5_min": return String(localized: "Zone 5")
        case "workout_avg_power_w": return String(localized: "Average power")
        case "workout_avg_cadence": return String(localized: "Average cadence")
        case "workout_altitude_gain_m": return String(localized: "Elevation gain")
        case "workout_max_altitude_m": return String(localized: "Max altitude")
        case "workout_aerobic_te": return String(localized: "Aerobic training effect")
        case "workout_anaerobic_te": return String(localized: "Anaerobic training effect")
        case "weight": return String(localized: "Weight")
        case "body_fat": return String(localized: "Body fat")
        case "lean_mass": return String(localized: "Lean mass")
        case "bmi": return String(localized: "BMI")
        case "fitness_age": return String(localized: "Fitness Age")
        case "vitality": return String(localized: "Vitality")
        case "body_age": return String(localized: "Body Age")
        case "vo2max": return String(localized: "VO₂ max")
        case "vo2max_est": return String(localized: "VO₂ max (estimated)")
        case "watch_stress": return String(localized: "Watch stress")
        case "body_battery_min": return String(localized: "Body Battery low")
        case "body_battery_max": return String(localized: "Body Battery high")
        case "body_battery_recharge": return String(localized: "Body Battery recharge")
        case "watch_sleep_score": return String(localized: "Watch sleep score")
        case "training_load": return String(localized: "Training load")
        case "intensity_min": return String(localized: "Intensity minutes")
        case "watch_skin_temp_c": return String(localized: "Watch skin temperature")
        case "calories_in": return String(localized: "Calories in")
        case "protein_g": return String(localized: "Protein")
        case "carbs_g": return String(localized: "Carbs")
        case "fat_g": return String(localized: "Fat")
        case "hydration_ml": return String(localized: "Water")
        case "mood": return String(localized: "Mood")
        case "cycle_day": return String(localized: "Cycle day")
        case "sleep_awake_count": return String(localized: "Awakenings")
        case "sleep_restless_moments": return String(localized: "Restless moments")
        case "sleep_stress": return String(localized: "Sleep stress")
        case "sleep_awake_min": return String(localized: "Awake during sleep")
        case "nap_min": return String(localized: "Naps")
        case "sleep_score_quality": return String(localized: "Sleep quality score")
        case "sleep_score_duration": return String(localized: "Sleep duration score")
        case "sleep_score_recovery": return String(localized: "Sleep recovery score")
        case "sleep_score_deep": return String(localized: "Deep sleep score")
        case "sleep_score_rem": return String(localized: "REM sleep score")
        case "sleep_score_restfulness": return String(localized: "Sleep restfulness score")
        case "hr_min": return String(localized: "Min heart rate")
        case "hr_overnight": return String(localized: "Overnight heart rate")
        case "resp_overnight": return String(localized: "Overnight respiratory rate")
        case "spo2_min": return String(localized: "Lowest blood oxygen")
        case "bmr_kcal": return String(localized: "Resting calories")
        case "floors_ascended_m": return String(localized: "Floors climbed")
        case "moderate_min": return String(localized: "Moderate intensity")
        case "vigorous_min": return String(localized: "Vigorous intensity")
        case "height_m": return String(localized: "Height")
        case "ftp_w": return String(localized: "Functional threshold power")
        case "lactate_threshold_hr": return String(localized: "Lactate threshold heart rate")
        case "race_5k_s": return String(localized: "5K race prediction")
        case "race_10k_s": return String(localized: "10K race prediction")
        case "race_half_s": return String(localized: "Half marathon prediction")
        case "race_marathon_s": return String(localized: "Marathon prediction")
        case "stress_max": return String(localized: "Max stress")
        case "stress_low_min": return String(localized: "Low stress time")
        case "stress_medium_min": return String(localized: "Medium stress time")
        case "stress_high_min": return String(localized: "High stress time")
        case "body_battery_charged": return String(localized: "Body Battery charged")
        case "body_battery_drained": return String(localized: "Body Battery drained")
        case "sweat_loss_ml": return String(localized: "Sweat loss")
        default: return MetricSnapshotCatalog.allItems().first { $0.id == id }?.label ?? id
        }
    }

    /// A row's figure for display: "-" when missing; mass, temperature and Effort follow the user's unit
    /// and scale settings, the other units are localized. Kotlin twin: `snapshotDisplayText`.
    static func value(_ row: MetricSnapshotRow, system: UnitSystem, temperature: TemperatureUnit,
                      effortScale: EffortScale) -> String {
        guard let v = row.value, v.isFinite else { return "-" }
        let item = row.item
        let n = MetricSnapshotFormat.number(v, decimals: item.decimals)
        switch item.unit {
        case "kg": return UnitFormatter.massFromKilograms(v, system: system)
        case "°C":
            if item.id == "skin_temp" {
                return SkinTempDisplay.format(v, fahrenheit: temperature == .fahrenheit, decimals: item.decimals)
            }
            return UnitFormatter.temperatureFromCelsius(v, unit: temperature, decimals: item.decimals)
        case "/100" where item.id == "strain" || item.id == "workout_effort":
            return "\(UnitFormatter.effortDisplay(v, scale: effortScale)) /\(UnitFormatter.effortScaleMax(effortScale))"
        case "min": return String(localized: "\(n) min")
        case "yrs": return String(localized: "\(n) yrs")
        case "steps": return String(localized: "\(n) steps")
        case "": return n
        default: return "\(n) \(item.unit)"
        }
    }
}

struct DailySummaryView: View {
    @EnvironmentObject var repo: Repository
    @EnvironmentObject var router: NavRouter

    @AppStorage(UnitPrefs.systemKey) private var unitSystemRaw = UnitSystem.metric.rawValue
    @AppStorage(UnitPrefs.temperatureKey) private var temperatureRaw = ""
    @AppStorage(UnitPrefs.effortScaleKey) private var effortScaleRaw = EffortScale.hundred.rawValue

    @State private var day = Date()
    @State private var snapshot: MetricSnapshot?
    @State private var loading = false

    private var unitSystem: UnitSystem { UnitSystem(rawValue: unitSystemRaw) ?? .metric }
    private var temperature: TemperatureUnit { UnitPrefs.resolveTemperature(system: unitSystem, override: temperatureRaw) }
    private var effortScale: EffortScale { UnitPrefs.resolveEffortScale(effortScaleRaw) }
    private var dayKey: String { Repository.localDayKey(day) }
    private var isToday: Bool { Calendar.current.isDateInToday(day) }

    var body: some View {
        ScreenScaffold(title: "Daily summary",
                       subtitle: "Every metric for one day, by category.",
                       lazy: true) {
            VStack(alignment: .leading, spacing: NoopMetrics.sectionGap) {
                dayPicker
                if let snapshot {
                    if snapshot.isEmpty {
                        DataPendingNote(title: "No readings for this day",
                                        message: "Pick another day, or sync your strap to fill this one in.")
                    }
                    ForEach(snapshot.sections, id: \.category.id) { section in
                        sectionCard(section)
                    }
                    Text("Informational only, not medical advice.")
                        .font(StrandFont.footnote)
                        .foregroundStyle(StrandPalette.textTertiary)
                } else {
                    ProgressView().frame(maxWidth: .infinity).padding(.top, 40)
                }
            }
        }
        .onAppear { consumePending() }
        .onChange(of: router.pendingDailySummaryDay) { _ in consumePending() }
        .task(id: "\(dayKey)|\(repo.days.count)") {
            let requested = dayKey
            loading = true
            let loaded = await MetricSnapshotLoader.snapshot(repo: repo, from: requested, to: requested)
            // A newer day (or a cancelled task) supersedes this load: never overwrite its snapshot.
            guard !Task.isCancelled, requested == dayKey else { return }
            snapshot = loaded
            loading = false
        }
    }

    /// Moves to the notification's day and clears the pending value; runs on appear and whenever a tap
    /// arrives while the screen is already showing.
    private func consumePending() {
        guard let pending = router.pendingDailySummaryDay else { return }
        if let d = Self.date(fromKey: pending) { day = min(d, Date()) }
        router.pendingDailySummaryDay = nil
    }

    // MARK: Day picker

    private var dayPicker: some View {
        NoopCard(padding: NoopMetrics.space2) {
            HStack(spacing: 8) {
                Button { shift(-1) } label: { Image(systemName: "chevron.left") }
                    .accessibilityLabel(Text("Previous day"))
                Spacer(minLength: 4)
                DatePicker("Day", selection: $day, in: ...Date(), displayedComponents: .date)
                    .labelsHidden()
                Spacer(minLength: 4)
                Button { shift(1) } label: { Image(systemName: "chevron.right") }
                    .disabled(isToday)
                    .accessibilityLabel(Text("Next day"))
            }
            .foregroundStyle(StrandPalette.accent)
            .buttonStyle(.plain)
        }
    }

    private func shift(_ delta: Int) {
        guard let d = Calendar.current.date(byAdding: .day, value: delta, to: day) else { return }
        day = min(d, Date())
    }

    // MARK: Sections

    private func sectionCard(_ section: MetricSnapshotSection) -> some View {
        NoopCard {
            VStack(alignment: .leading, spacing: 10) {
                Text(DailySummaryText.title(section.category))
                    .font(StrandFont.caption)
                    .foregroundStyle(StrandPalette.textTertiary)
                    .accessibilityAddTraits(.isHeader)
                ForEach(section.rows, id: \.item.id) { row in
                    line(DailySummaryText.label(row.item.id),
                         DailySummaryText.value(row, system: unitSystem, temperature: temperature,
                                                effortScale: effortScale))
                }
                ForEach(section.notes, id: \.id) { note in
                    line(note.label, note.text)
                }
                if section.category == .labs && section.notes.isEmpty {
                    line(String(localized: "Readings"), "-")
                }
                if section.category == .mind && !section.notes.contains(where: { $0.category == .mind }) {
                    line(String(localized: "Journal"), "-")
                }
            }
        }
    }

    private func line(_ label: String, _ value: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            Text(label)
                .font(StrandFont.subhead)
                .foregroundStyle(StrandPalette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 8)
            Text(value)
                .font(StrandFont.bodyNumber)
                .foregroundStyle(value == "-" ? StrandPalette.textTertiary : StrandPalette.textPrimary)
                .multilineTextAlignment(.trailing)
        }
        .accessibilityElement(children: .combine)
    }

    private static func date(fromKey key: String) -> Date? {
        guard let (y, m, d) = WeeklyDigestEngine.parseYMD(key) else { return nil }
        return Calendar.current.date(from: DateComponents(year: y, month: m, day: d, hour: 12))
    }
}

