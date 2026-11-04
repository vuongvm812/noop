import Foundation
import StrandAnalytics
import UserNotifications
import WhoopStore

/// The automation result notifications on Apple (#92): the morning recap and post-workout summary
/// (the Swift port of Android's ScheduledReportPolicy), an automatic backup finishing or failing, and
/// a new cycle-awareness reading. Each is default off (`BehaviorStore`) with a pure gate in
/// `ScheduledReportPolicy` / `AutomationResultPolicy`. Nap detection and the self-hosted push are
/// Android-only features, so they have no Apple notifier. Rhythm instrumentation never posts here
/// (docs/SCOPE.md). Kotlin twins: `ScheduledReportNotifier`, `AutomationResultNotifier`.
@MainActor
enum AutomationResultNotifier {
    private enum K {
        static let morningDay = "behavior.reportMorningDay"
        static let lastWorkoutTs = "behavior.reportLastWorkoutTs"
        static let backupDay = "behavior.resultBackupDay"
        static let backupFailed = "behavior.resultBackupFailed"
        static let cyclePhase = "behavior.resultCyclePhase"
    }

    static func requestAuthorization() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
    }

    /// The morning recap for the banked night `reportDay`, once per night and not before 05:00.
    static func onMorning(reportDay: String, chargePct: Int?, restPct: Int?, enabled: Bool, now: Date = Date()) {
        let c = Calendar.current.dateComponents([.hour, .minute], from: now)
        guard ScheduledReportPolicy.shouldNotifyMorning(
                enabled: enabled, chargeOrRestPresent: chargePct != nil || restPct != nil,
                lastNotifiedDay: UserDefaults.standard.string(forKey: K.morningDay), reportDay: reportDay,
                nowMinuteOfDay: (c.hour ?? 0) * 60 + (c.minute ?? 0)),
              let copy = ScheduledReportPolicy.morningCopy(chargePct: chargePct, restPct: restPct)
        else { return }
        post(id: "report-morning", title: copy.title, body: copy.body) {
            UserDefaults.standard.set(reportDay, forKey: K.morningDay)
        }
    }

    /// The post-workout summary for the newest workout, only when strictly newer than the last.
    static func onWorkout(_ row: WorkoutRow?, enabled: Bool) {
        guard let row, ScheduledReportPolicy.shouldNotifyWorkout(
            enabled: enabled, newestWorkoutTs: row.startTs,
            lastWorkoutTs: UserDefaults.standard.integer(forKey: K.lastWorkoutTs)) else { return }
        let minutes = Int((((row.durationS ?? Double(row.endTs - row.startTs))) / 60.0).rounded())
        let sport = WorkoutSource.displaySport(row.sport)
        let copy: (title: String, body: String)
        if let strain = row.strain {
            let scale = UnitPrefs.resolveEffortScale(UserDefaults.standard.string(forKey: UnitPrefs.effortScaleKey) ?? "")
            copy = ScheduledReportPolicy.workoutCopy(
                sportLabel: sport, effortDisplay: UnitFormatter.effortDisplay(strain, scale: scale),
                effortMaxLabel: UnitFormatter.effortScaleMax(scale),
                durationLabel: ScheduledReportPolicy.durationLabel(minutes: minutes), avgHr: row.avgHr)
        } else {
            var pieces = [ScheduledReportPolicy.durationLabel(minutes: minutes)]
            if let hr = row.avgHr { pieces.append("avg \(hr) bpm") }
            copy = ("Workout logged: \(sport)", pieces.joined(separator: " · ") + ". Summarised after your strap synced.")
        }
        post(id: "report-workout", title: copy.title, body: copy.body) {
            UserDefaults.standard.set(row.startTs, forKey: K.lastWorkoutTs)
        }
    }

    /// Move the post-workout frontier to `newestTs` without notifying (when the toggle turns on), so
    /// an old session already in history does not fire.
    static func seedWorkoutFrontier(_ newestTs: Int?) {
        if let ts = newestTs, ts > UserDefaults.standard.integer(forKey: K.lastWorkoutTs) {
            UserDefaults.standard.set(ts, forKey: K.lastWorkoutTs)
        }
    }

    /// An automatic backup finished or failed (manual backups report on screen).
    static func onBackupResult(succeeded: Bool, enabled: Bool) {
        let day = Repository.logicalDayKey(Date())
        let outstanding = UserDefaults.standard.bool(forKey: K.backupFailed)
        if succeeded { UserDefaults.standard.set(false, forKey: K.backupFailed) }
        guard AutomationResultPolicy.shouldNotifyResult(
            enabled: enabled, succeeded: succeeded, day: day,
            lastSuccessDay: UserDefaults.standard.string(forKey: K.backupDay),
            failureOutstanding: outstanding) else { return }
        let title = succeeded ? String(localized: "Backup finished") : String(localized: "Backup failed")
        let body = succeeded
            ? String(localized: "Your automatic backup was saved to your chosen folder.")
            : String(localized: "The automatic backup could not be written. It will try again; check the folder in Backup & Sync.")
        post(id: "result-backup", title: title, body: body) {
            if succeeded { UserDefaults.standard.set(day, forKey: K.backupDay) }
            else { UserDefaults.standard.set(true, forKey: K.backupFailed) }
        }
    }

    /// The cycle-awareness engine produced `phase` (its raw key). Neutral copy, never the phase.
    static func onCycleReading(phase: String?, enabled: Bool) {
        guard AutomationResultPolicy.shouldNotifyCycleReading(
            enabled: enabled, phase: phase,
            lastNotifiedPhase: UserDefaults.standard.string(forKey: K.cyclePhase)), let phase else { return }
        post(id: "result-cycle", title: String(localized: "Cycle awareness"),
             body: String(localized: "A new cycle-awareness reading is available.")) {
            UserDefaults.standard.set(phase, forKey: K.cyclePhase)
        }
    }

    /// Post when authorized; `onPosted` records the gate's state only after the system accepted it.
    private static func post(id: String, title: String, body: String, onPosted: @escaping @MainActor () -> Void) {
        let center = UNUserNotificationCenter.current()
        center.getNotificationSettings { settings in
            guard settings.authorizationStatus == .authorized else { return }
            let content = UNMutableNotificationContent()
            content.title = title
            content.body = body
            content.sound = .default
            center.add(UNNotificationRequest(identifier: id, content: content, trigger: nil)) { error in
                guard error == nil else { return }
                Task { @MainActor in onPosted() }
            }
        }
    }
}
