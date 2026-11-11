import Foundation

// CoachMetricContext.swift — the AI Coach's "all metrics" context block and its per-category sharing set.
//
// Pure and database-free. The data layer (`AICoachEngine`) reads the metric snapshot values for the last
// 30 days (`MetricSnapshotLoader.dayValues`) and hands them here; this file decides which categories may
// leave the device, rolls them up with `MetricSnapshotBuilder` and prints a compact block: per category one
// 30-day line plus per-day lines for the last 14 days, trimmed to a character budget so a small local
// model's context window is not crowded out. Summary text only, never raw samples.
// Kotlin twin: `CoachMetricContext` (com.noop.analytics.CoachMetricContext). Keep the two byte-identical.
public enum CoachMetricContext {

    /// Days that get their own per-day line (newest first). Kotlin twin: `CoachMetricContext.PER_DAY_WINDOW`.
    public static let perDayWindow = 14

    /// Character budget of one block (about 1,800 tokens at ~4 chars/token). Per-day lines are dropped
    /// whole, category by category, once the budget is spent; the 30-day lines and notes always stay.
    /// Kotlin twin: `CoachMetricContext.MAX_CHARS`.
    public static let maxChars = 7000

    /// Notes (journal answers, lab readings) kept per category, newest. Kotlin twin: `CoachMetricContext.MAX_NOTES_PER_CATEGORY`.
    public static let maxNotesPerCategory = 12

    /// The categories shared when the user has never touched the switches: exactly what the Coach has
    /// always sent. Everything newer defaults OFF. Kotlin twin: `CoachMetricContext.defaultCategoryIds`.
    public static func defaultCategoryIds() -> [String] { [
        MetricSnapshotCategory.recoveryStrain.id,
        MetricSnapshotCategory.sleep.id,
        MetricSnapshotCategory.heart.id,
        MetricSnapshotCategory.activity.id,
        MetricSnapshotCategory.workouts.id,
    ] }

    /// Metric ids the Coach's long-standing day table and 30-day averages already print, so the block
    /// does not repeat them. Kotlin twin: `CoachMetricContext.coveredByDayTable`.
    public static func coveredByDayTable() -> Set<String> { [
        "recovery", "strain", "hrv", "rhr", "sleep_total_min", "sleep_deep_min", "sleep_rem_min",
        "sleep_light_min", "sleep_efficiency", "spo2", "resp_rate", "steps",
    ] }

    /// The enabled category ids from the stored string: nil/absent means the defaults, "" means none, and
    /// unknown ids are dropped. Kotlin twin: `CoachMetricContext.parseCategories`.
    public static func parseCategories(_ stored: String?) -> Set<String> {
        guard let stored else { return Set(defaultCategoryIds()) }
        let known = Set(MetricSnapshotCategory.allCases.map { $0.id })
        return Set(stored.split(separator: ",", omittingEmptySubsequences: false)
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { known.contains($0) })
    }

    /// The stored string: enabled ids in category order, comma-joined. Kotlin twin: `CoachMetricContext.encodeCategories`.
    public static func encodeCategories(_ enabled: Set<String>) -> String {
        MetricSnapshotCategory.allCases.map { $0.id }.filter { enabled.contains($0) }.joined(separator: ",")
    }

    /// The block for `enabled` categories, or "" when none of them carries a reading.
    /// `days` are the ascending "yyyy-MM-dd" days of the window (30 for the Coach); the last
    /// `perDayWindow` get per-day lines. Kotlin twin: `CoachMetricContext.block`.
    public static func block(
        days: [String],
        values: [String: [String: Double]],
        notes: [MetricSnapshotNote],
        enabled: Set<String>,
        maxChars: Int = CoachMetricContext.maxChars
    ) -> String {
        guard let first = days.first, let last = days.last, !enabled.isEmpty else { return "" }
        let snapshot = MetricSnapshotBuilder.build(from: first, to: last, values: values, notes: notes)
        let recent = Array(days.suffix(perDayWindow).reversed())

        struct Part { let head: [String]; let perDay: [String] }
        var parts: [Part] = []
        for section in snapshot.sections where enabled.contains(section.category.id) {
            let rows = section.rows.filter { $0.value != nil && !coveredByDayTable().contains($0.item.id) }
            let sectionNotes = Array(section.notes.suffix(maxNotesPerCategory))
            if rows.isEmpty && sectionNotes.isEmpty { continue }
            var head: [String] = ["[\(section.category.label)]"]
            if !rows.isEmpty {
                head.append("  30d: " + rows.map { "\($0.item.id)=\($0.text)\(tag($0.item.rollup))" }.joined(separator: ", "))
            }
            for n in sectionNotes { head.append("  \(n.label): \(n.text)") }
            var perDay: [String] = []
            for day in recent {
                let cells: [String] = rows.compactMap { r in
                    guard let v = values[r.item.id]?[day], v.isFinite else { return nil }
                    return "\(r.item.id)=\(MetricSnapshotFormat.number(v, decimals: r.item.decimals))"
                }
                if !cells.isEmpty { perDay.append("  \(day): " + cells.joined(separator: ", ")) }
            }
            parts.append(Part(head: head, perDay: perDay))
        }
        if parts.isEmpty { return "" }

        let header = "ALL METRICS (ids are metric names; 30d = the last 30 days, averaged unless tagged; "
            + "per-day lines are newest first, bare numbers in the units shown on the 30d line; "
            + "a metric that is not listed was not recorded, it is not zero):"
        // UTF-16 lengths, so the budget arithmetic matches Kotlin's String.length.
        let len: (String) -> Int = { $0.utf16.count }
        var used = len(header) + parts.reduce(0) { acc, p in acc + p.head.reduce(0) { $0 + len($1) + 1 } + 1 }
        var out: [String] = [header]
        for p in parts {
            out.append(contentsOf: p.head)
            let cost = p.perDay.reduce(0) { $0 + len($1) + 1 }
            if !p.perDay.isEmpty && used + cost <= maxChars {
                out.append(contentsOf: p.perDay)
                used += cost
            }
        }
        return out.joined(separator: "\n")
    }

    /// The roll-up tag printed after a 30-day figure. Kotlin twin: `CoachMetricContext.tag`.
    private static func tag(_ rollup: SnapshotRollup) -> String {
        switch rollup {
        case .mean: return ""
        case .sum: return " total"
        case .latest: return " latest"
        case .max: return " max"
        case .min: return " min"
        }
    }
}
