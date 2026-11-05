import Foundation

// WeeklySummaryPolicy.swift — when the "weekly summary ready" notification fires (#91).
//
// Pure and clock-free: the caller passes the LOGICAL day (the 04:00 roll), so a late night
// still counts as Sunday. Kotlin twin: `WeeklySummaryPolicy`.
public enum WeeklySummaryPolicy {

    /// The Monday ("yyyy-MM-dd") of the week to announce now, or nil when nothing should fire.
    ///
    /// Fires once per week, for LAST week, on the first evaluation after the new week's
    /// Monday logical day has started AND that week's first sync has landed
    /// (`hasCurrentWeekData`), so the summary is built after the night that closes last week
    /// was banked. A missed Monday still fires later that week; it never fires twice for the
    /// same week.
    /// Kotlin twin: `WeeklySummaryPolicy.weekToAnnounce`.
    public static func weekToAnnounce(enabled: Bool, logicalToday: String,
                                      lastAnnouncedWeek: String?,
                                      hasCurrentWeekData: Bool) -> String? {
        guard enabled, hasCurrentWeekData,
              let thisMonday = WeeklyDigestEngine.mondayOfWeek(containing: logicalToday) else { return nil }
        let lastMonday = WeeklyDigestEngine.addDays(thisMonday, -7)
        return lastAnnouncedWeek == lastMonday ? nil : lastMonday
    }

    /// The day to build last week's digest from: its Sunday, so a total spans all seven days.
    /// Kotlin twin: `WeeklySummaryPolicy.anchorDay`.
    public static func anchorDay(forWeek monday: String) -> String {
        WeeklyDigestEngine.addDays(monday, 6)
    }

    /// True when the history holds a row in the week containing `logicalToday`.
    /// Kotlin twin: `WeeklySummaryPolicy.hasCurrentWeekData`.
    public static func hasCurrentWeekData(dayKeys: [String], logicalToday: String) -> Bool {
        guard let monday = WeeklyDigestEngine.mondayOfWeek(containing: logicalToday) else { return false }
        return dayKeys.contains { $0 >= monday && $0 <= logicalToday }
    }

    /// The notification body: the digest's top focal sentence, nil for an empty week (no
    /// notification then).
    /// Kotlin twin: `WeeklySummaryPolicy.body`.
    public static func body(_ digest: WeeklyDigest) -> String? {
        digest.isEmpty ? nil : digest.focalPoints.first
    }
}
