package com.noop.analytics

/**
 * When an automation posts its result notification (#92). One default-off toggle per notifier;
 * these gates keep each one from repeating itself. Swift twin: `AutomationResultPolicy`.
 */
object AutomationResultPolicy {

    /**
     * The result of a background job (an automatic backup, a self-hosted push): a success at most once
     * per [day] unless it clears an outstanding failure; a failure once until the next success.
     * Swift twin: `AutomationResultPolicy.shouldNotifyResult`.
     */
    fun shouldNotifyResult(
        enabled: Boolean,
        succeeded: Boolean,
        day: String,
        lastSuccessDay: String?,
        failureOutstanding: Boolean,
    ): Boolean {
        if (!enabled) return false
        return if (succeeded) failureOutstanding || lastSuccessDay != day else !failureOutstanding
    }

    /** A settled cycle phase that differs from the last notified. Swift twin: `AutomationResultPolicy.shouldNotifyCycleReading`. */
    fun shouldNotifyCycleReading(enabled: Boolean, phase: String?, lastNotifiedPhase: String?): Boolean =
        enabled && phase != null && phase != "learning" && phase != "unknown" && phase != lastNotifiedPhase
}
