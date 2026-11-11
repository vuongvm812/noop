import Foundation
import UserNotifications

/// Foreground presentation delegate for the app's local notifications (wind-down nudge, smart-alarm
/// backup, battery/illness alerts).
///
/// Without a `UNUserNotificationCenterDelegate`, iOS/macOS suppress a notification's banner while the
/// app is in the FOREGROUND (the default). A user testing a reminder with the app open would see
/// nothing and conclude notifications are broken. Returning banner + sound + list here makes them
/// visible whether the app is open or not — matching what the user expects from a reminder.
///
/// Cross-platform (iOS + macOS). Register once at launch:
/// `UNUserNotificationCenter.current().delegate = NotificationPresenter.shared`.
final class NotificationPresenter: NSObject, UNUserNotificationCenterDelegate {

    static let shared = NotificationPresenter()

    private override init() { super.init() }

    /// K5: wired by the app root (`StrandApp` on macOS, `StrandiOSApp` on iOS) at launch to route a
    /// tapped scheduled morning-brief notification to the Coach screen via `NavRouter.openCoach()`. nil
    /// is a safe no-op (the tap is simply not routed) rather than a crash if this ever fires before the
    /// root has wired it.
    var onCoachBriefTapped: (() -> Void)?
    /// Tapping the morning recap or the Coach brief opens the Daily summary for the notification's day
    /// ("yyyy-MM-dd", nil = today). Set by each app shell to its NavRouter.
    var onDailySummaryTapped: ((String?) -> Void)?
    /// #91: tapping the weekly summary opens Trends (set by each app shell to its NavRouter).
    var onWeeklySummaryTapped: (() -> Void)?

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .sound, .list])
    }

    /// Handle a tap on a delivered notification. Only the scheduled morning-brief category (K5) routes
    /// anywhere; every other notification (wind-down, smart-alarm, battery/illness) just opens the app
    /// to wherever it was, matching the pre-K5 behaviour.
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        let category = response.notification.request.content.categoryIdentifier
        let day = response.notification.request.content.userInfo[DailySummaryNotification.dayKey] as? String
        if category == DailySummaryNotification.categoryId {
            onDailySummaryTapped?(day)
        } else if category == CoachBriefScheduler.notificationCategoryId {
            // A brief carries its day and opens the Daily summary it was built from. The "unavailable, tap
            // to retry" notification carries none, and without that route wired it also falls back to Coach.
            if let day, let open = onDailySummaryTapped { open(day) } else { onCoachBriefTapped?() }
        } else if category == WeeklySummaryNotifier.categoryId {
            onWeeklySummaryTapped?()
        }
        completionHandler()
    }
}

/// Shared identifiers for notifications that open the Daily summary. The Android equivalent is `NotificationRoute`.
enum DailySummaryNotification {
    /// The category of the one-line morning recap.
    static let categoryId = "daily-summary"
    /// The `userInfo` key carrying the "yyyy-MM-dd" day to open.
    static let dayKey = "day"
}
