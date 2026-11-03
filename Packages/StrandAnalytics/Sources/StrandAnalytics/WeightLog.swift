import Foundation

/// The hand-logged body-weight diary and the one resolver every weight readout uses (#57).
///
/// The user logs at most one weight per local day from the Today Weight tile. Entries live in the generic
/// `metricSeries` table under their own source (`sourceId`) and key (`key`), so they need no schema change
/// and travel inside the `.noopbak` SQLite like every other series. A health-store weight (Apple Health /
/// Health Connect) can sit beside them; this type decides which one a screen shows, so the Today tile, the
/// weight chart and the profile weight that calorie / BMR math reads cannot name different weights.
///
/// Pure. Kotlin twin: `com.noop.analytics.WeightLog`, pinned by the same cases.
public enum WeightLog {

    /// The `metricSeries` device id the diary is written under. Keep equal to the Kotlin `SOURCE_ID`.
    public static let sourceId = "weight-log"
    /// The `metricSeries` key, kilograms. Keep equal to the Kotlin `KEY`.
    public static let key = "weight_kg"

    /// The accepted range, the same clamp the profile weight already applies on both platforms, so a
    /// logged value always lands in the profile unchanged.
    public static let minKg = 30.0
    public static let maxKg = 250.0
    /// The app's pound factor (`UnitFormatter.poundsPerKilogram`), repeated here because this package
    /// cannot see the app target.
    public static let poundsPerKilogram = 2.20462

    public enum Source: String, Equatable, Sendable {
        case logged
        case health
    }

    /// One day's weight. `day` is ISO `yyyy-MM-dd`, which sorts chronologically.
    public struct Entry: Equatable, Sendable {
        public let day: String
        public let kg: Double
        public init(day: String, kg: Double) {
            self.day = day
            self.kg = kg
        }
    }

    public struct Resolved: Equatable, Sendable {
        public let entry: Entry
        public let source: Source
    }

    /// True for a finite weight inside `minKg...maxKg`.
    /// Kotlin twin: `WeightLog.isValid`.
    public static func isValid(kg: Double) -> Bool {
        kg.isFinite && kg >= minKg && kg <= maxKg
    }

    /// Parse what the user typed into kilograms, or nil when it is not a valid weight.
    ///
    /// Accepts a comma or a dot as the decimal separator (the field is free text in every locale),
    /// converts pounds when `imperial`, and rounds to 0.01 kg so the stored figure is not a float tail.
    /// Kotlin twin: `WeightLog.parseKg`.
    public static func parseKg(_ text: String, imperial: Bool) -> Double? {
        let cleaned = text.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: ",", with: ".")
        guard !cleaned.isEmpty, let value = Double(cleaned), value.isFinite else { return nil }
        let kg = imperial ? value / poundsPerKilogram : value
        let rounded = (kg * 100).rounded() / 100
        return isValid(kg: rounded) ? rounded : nil
    }

    /// The most recent valid entry, or nil. On a same-day tie the FIRST one wins, so callers put the
    /// source they trust first.
    /// Kotlin twin: `WeightLog.newest`.
    public static func newest(_ entries: [Entry]) -> Entry? {
        var best: Entry?
        for e in entries where isValid(kg: e.kg) {
            if best == nil || e.day > best!.day { best = e }
        }
        return best
    }

    /// The weight to show for `day`: the newest reading on or before it. A hand-logged weight wins a
    /// same-day tie with the health store, since the user typed it on purpose. Nil when neither has one.
    /// Kotlin twin: `WeightLog.resolve`.
    public static func resolve(logged: [Entry], health: [Entry], onOrBefore day: String) -> Resolved? {
        let l = newest(logged.filter { $0.day <= day })
        let h = newest(health.filter { $0.day <= day })
        switch (l, h) {
        case let (l?, h?): return h.day > l.day ? Resolved(entry: h, source: .health) : Resolved(entry: l, source: .logged)
        case let (l?, nil): return Resolved(entry: l, source: .logged)
        case let (nil, h?): return Resolved(entry: h, source: .health)
        default: return nil
        }
    }

    /// The daily series the weight chart draws: one value per day, oldest first, a logged day replacing
    /// the health store's reading for that day. Invalid values are dropped. Within one source the first
    /// entry for a day wins, the same tie rule as `newest`, so the chart and the tile agree.
    /// Kotlin twin: `WeightLog.dailySeries`.
    public static func dailySeries(logged: [Entry], health: [Entry]) -> [Entry] {
        var byDay: [String: Double] = [:]
        var loggedDays = Set<String>()
        var healthDays = Set<String>()
        for e in health where isValid(kg: e.kg) && healthDays.insert(e.day).inserted { byDay[e.day] = e.kg }
        for e in logged where isValid(kg: e.kg) && loggedDays.insert(e.day).inserted { byDay[e.day] = e.kg }
        return byDay.keys.sorted().map { Entry(day: $0, kg: byDay[$0]!) }
    }

    /// The profile weight after the diary changed: the newest logged weight, or nil when the diary is
    /// empty (the profile then keeps whatever it held).
    /// Kotlin twin: `WeightLog.profileWeight`.
    public static func profileWeight(logged: [Entry]) -> Double? {
        newest(logged)?.kg
    }
}
