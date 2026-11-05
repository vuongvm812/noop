#if os(iOS)
import BackgroundTasks
import Foundation

/// The Monday wake behind the weekly summary (#91), so it can land without the app being opened.
/// Best-effort like every `BGAppRefreshTask`: iOS picks the actual time, no earlier than just after
/// the Monday logical day starts (04:00). The notifier's own gate decides whether anything is posted.
@MainActor
enum WeeklySummaryBackgroundScheduler {
    static let taskIdentifier = (Bundle.main.bundleIdentifier ?? "com.noopapp.noop") + ".weeklysummary"

    /// Register at launch, before the first scene connects.
    static func register(perform operation: @escaping @MainActor () async -> Void) {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: taskIdentifier, using: nil) { task in
            let completion = TaskCompletionGuard(task: task)
            let worker = Task { @MainActor in
                // Re-arm first: a refresh request is single-shot.
                schedule()
                await operation()
                guard !Task.isCancelled else { return }
                completion.finish(success: true)
            }
            task.expirationHandler = {
                worker.cancel()
                completion.finish(success: false)
            }
        }
    }

    /// Keep exactly one pending request, for the next Monday 04:05 local.
    static func schedule(now: Date = Date(), calendar: Calendar = .current) {
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: taskIdentifier)
        let request = BGAppRefreshTaskRequest(identifier: taskIdentifier)
        request.earliestBeginDate = nextMondayWake(after: now, calendar: calendar)
        try? BGTaskScheduler.shared.submit(request)
    }

    /// The next Monday 04:05 strictly after `now` (just past the logical-day roll).
    static func nextMondayWake(after now: Date, calendar: Calendar) -> Date? {
        calendar.nextDate(after: now, matching: DateComponents(hour: 4, minute: 5, weekday: 2),
                          matchingPolicy: .nextTime)
    }

    /// Single-shot `setTaskCompleted`, as every scheduler here keeps its own copy.
    private final class TaskCompletionGuard: @unchecked Sendable {
        private let task: BGTask
        private let lock = NSLock()
        private var finished = false

        init(task: BGTask) { self.task = task }

        func finish(success: Bool) {
            lock.lock()
            defer { lock.unlock() }
            guard !finished else { return }
            finished = true
            task.setTaskCompleted(success: success)
            task.expirationHandler = nil
        }
    }
}
#endif
