import Foundation

// AutomationResultPolicy.swift — when an automation posts its result notification (#92).
//
// One default-off toggle per notifier; these gates keep each one from repeating itself. Pure, so
// both platforms pin the same table. (The nap gate is Android-only, as nap detection is, and lives
// in Android's AutomationResultNotifier.) Kotlin twin: `AutomationResultPolicy`.
public enum AutomationResultPolicy {

    /// The result of a background job (an automatic backup, a self-hosted push): a success at most
    /// once per `day` unless it clears an outstanding failure; a failure once until the next success.
    /// Kotlin twin: `AutomationResultPolicy.shouldNotifyResult`.
    public static func shouldNotifyResult(enabled: Bool, succeeded: Bool, day: String,
                                          lastSuccessDay: String?, failureOutstanding: Bool) -> Bool {
        guard enabled else { return false }
        if succeeded { return failureOutstanding || lastSuccessDay != day }
        return !failureOutstanding
    }

    /// A new cycle-awareness reading: a settled phase (not learning / unknown) that differs from
    /// the last one notified. The copy stays neutral ("a new reading is available").
    /// Kotlin twin: `AutomationResultPolicy.shouldNotifyCycleReading`.
    public static func shouldNotifyCycleReading(enabled: Bool, phase: String?, lastNotifiedPhase: String?) -> Bool {
        guard enabled, let p = phase, p != "learning", p != "unknown" else { return false }
        return p != lastNotifiedPhase
    }
}
