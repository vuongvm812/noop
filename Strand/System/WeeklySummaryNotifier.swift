import Foundation
import StrandAnalytics
import UserNotifications

/// The "weekly summary ready" notification (#91): once a week, after the new week's Monday logical
/// day has started and its first sync has landed, the top focal line of LAST week's digest. The
/// digest is built through `WeeklyDigestLoader`, the same reads the Trends card makes, and a tap
/// opens Trends. Opt-in via `BehaviorStore.weeklySummaryNudge`. The policy is the pure
/// `WeeklySummaryPolicy`, shared with Android. Kotlin twin: `ScheduledReportNotifier.onWeeklySummary`.
@MainActor
enum WeeklySummaryNotifier {
    /// The Monday of the last week announced: the once-a-week gate.
    static let lastWeekKey = "behavior.weeklySummaryLastWeek"
    /// Category the tap handler routes to Trends on.
    static let categoryId = "weekly-summary"

    static func requestAuthorization() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
    }

    /// Post last week's summary if it is due. Safe to call on every days republish: the persisted
    /// gate turns every call after the first into a defaults read.
    static func evaluate(repo: Repository, enabled: Bool, now: Date = Date()) async {
        let logicalToday = Repository.logicalDayKey(now)
        guard let week = WeeklySummaryPolicy.weekToAnnounce(
            enabled: enabled, logicalToday: logicalToday,
            lastAnnouncedWeek: UserDefaults.standard.string(forKey: lastWeekKey),
            hasCurrentWeekData: WeeklySummaryPolicy.hasCurrentWeekData(dayKeys: repo.days.map(\.day),
                                                                        logicalToday: logicalToday))
        else { return }
        let center = UNUserNotificationCenter.current()
        // Checked before the digest is built, so a denied permission does not rebuild it every time.
        guard await center.notificationSettings().authorizationStatus == .authorized else { return }
        let digest = await WeeklyDigestLoader.digest(repo: repo, anchorDay: WeeklySummaryPolicy.anchorDay(forWeek: week))
        guard let body = WeeklySummaryPolicy.body(digest) else {
            // Nothing to say about an empty week: mark it, so it is not rebuilt on every republish.
            UserDefaults.standard.set(week, forKey: lastWeekKey)
            return
        }
        let content = UNMutableNotificationContent()
        content.title = String(localized: "Your weekly summary is ready")
        content.body = body
        content.sound = .default
        content.categoryIdentifier = categoryId
        do {
            try await center.add(UNNotificationRequest(identifier: "weekly-summary", content: content, trigger: nil))
            UserDefaults.standard.set(week, forKey: lastWeekKey)
        } catch {
            // Not marked, so the next evaluation retries.
        }
    }
}
