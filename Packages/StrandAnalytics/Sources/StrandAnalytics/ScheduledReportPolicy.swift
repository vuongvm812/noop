import Foundation

// ScheduledReportPolicy.swift — the morning recap and post-workout summary gates and copy (#92).
//
// Swift port of the Kotlin `ScheduledReportPolicy` (android/.../notif/ScheduledReportNotifier.kt),
// so the Apple apps post the same two opt-in reports with the same once-per-night / strictly-newer
// gates and the same text. Pure, so the package tests pin it.
public enum ScheduledReportPolicy {

    /// Fire the morning recap at most once per reported night: enabled, a recap value exists, not
    /// already posted for `reportDay` (the banked night's day, not the calendar day, #567), and the
    /// clock is past the floor (05:00 by default; a floor, not a window, since the recap is about
    /// last night, #2289). Kotlin twin: `ScheduledReportPolicy.shouldNotifyMorning`.
    public static func shouldNotifyMorning(enabled: Bool, chargeOrRestPresent: Bool,
                                           lastNotifiedDay: String?, reportDay: String,
                                           nowMinuteOfDay: Int,
                                           earliestMinuteOfDay: Int = 5 * 60) -> Bool {
        enabled && chargeOrRestPresent && lastNotifiedDay != reportDay && nowMinuteOfDay >= earliestMinuteOfDay
    }

    /// Fire the post-workout summary only for a workout strictly newer than the last summarised.
    /// Kotlin twin: `ScheduledReportPolicy.shouldNotifyWorkout`.
    public static func shouldNotifyWorkout(enabled: Bool, newestWorkoutTs: Int?, lastWorkoutTs: Int) -> Bool {
        guard enabled, let ts = newestWorkoutTs else { return false }
        return ts > lastWorkoutTs
    }

    /// Title + body for the morning recap; an absent Charge or Rest is omitted, nil when neither.
    /// Kotlin twin: `ScheduledReportPolicy.morningCopy`.
    public static func morningCopy(chargePct: Int?, restPct: Int?) -> (title: String, body: String)? {
        var parts: [String] = []
        if let c = chargePct { parts.append("Charge \(c)") }
        if let r = restPct { parts.append("Rest \(r)") }
        guard !parts.isEmpty else { return nil }
        return ("Good morning: last night's recap",
                parts.joined(separator: " · ") + ". Recovery from your strap, scored after it synced this morning.")
    }

    /// Title + body for the post-workout summary. Kotlin twin: `ScheduledReportPolicy.workoutCopy`.
    public static func workoutCopy(sportLabel: String, effortDisplay: String, effortMaxLabel: String,
                                   durationLabel: String, avgHr: Int?) -> (title: String, body: String) {
        var pieces = ["Effort \(effortDisplay)/\(effortMaxLabel)", durationLabel]
        if let hr = avgHr { pieces.append("avg \(hr) bpm") }
        return ("Workout logged: \(sportLabel)", pieces.joined(separator: " · ") + ". Summarised after your strap synced.")
    }

    /// "42 min" / "1 h 8 min"; a 0/negative span reads "under a minute".
    /// Kotlin twin: `ScheduledReportPolicy.durationLabel`.
    public static func durationLabel(minutes: Int) -> String {
        if minutes <= 0 { return "under a minute" }
        if minutes < 60 { return "\(minutes) min" }
        if minutes % 60 == 0 { return "\(minutes / 60) h" }
        return "\(minutes / 60) h \(minutes % 60) min"
    }
}
