import Foundation
import WhoopProtocol

/// What the watch itself reported about one workout, read back from `deviceMetricSample` over the
/// workout window: its Training Effect and training load (from the activity file's session summary) and
/// the intensity minutes its monitoring credited during the window.
///
/// Reference values only. NOOP shows them beside its own numbers and never scores from them: there is no
/// EPOC model here, and the watch's figures are not checked against anything.
///
/// Kotlin twin: `WorkoutWatchReference`.
public struct WorkoutWatchReference: Equatable, Sendable {
    /// Aerobic Training Effect, 0.0-5.0.
    public let aerobicTrainingEffect: Double?
    /// Anaerobic Training Effect, 0.0-5.0.
    public let anaerobicTrainingEffect: Double?
    /// The watch's EPOC-based training load for the activity.
    public let trainingLoad: Double?
    /// Moderate-intensity minutes credited inside the window.
    public let moderateMinutes: Double?
    /// Vigorous-intensity minutes credited inside the window.
    public let vigorousMinutes: Double?

    public init(aerobicTrainingEffect: Double?, anaerobicTrainingEffect: Double?, trainingLoad: Double?,
                moderateMinutes: Double?, vigorousMinutes: Double?) {
        self.aerobicTrainingEffect = aerobicTrainingEffect
        self.anaerobicTrainingEffect = anaerobicTrainingEffect
        self.trainingLoad = trainingLoad
        self.moderateMinutes = moderateMinutes
        self.vigorousMinutes = vigorousMinutes
    }

    /// Intensity minutes as the watch counts them toward its weekly goal: a vigorous minute counts twice.
    /// nil when the window credited neither kind.
    public var intensityMinutes: Double? {
        guard moderateMinutes != nil || vigorousMinutes != nil else { return nil }
        return (moderateMinutes ?? 0) + 2 * (vigorousMinutes ?? 0)
    }

    /// The stored kinds this summary reads. Kotlin twin: `WorkoutWatchReference.kinds`.
    public static let kinds: [DeviceMetricKind] = [
        .aerobicTrainingEffect, .anaerobicTrainingEffect, .trainingLoad,
        .intensityModerateMin, .intensityVigorousMin,
    ]

    /// Fold the samples already limited to one workout window. A per-workout value (Training Effect,
    /// training load) takes the latest sample of its kind, so a re-imported file cannot double it; intensity
    /// minutes are per-interval credits and add up. nil when the window holds none of the kinds.
    ///
    /// Kotlin twin: `WorkoutWatchReference.summarize`.
    public static func summarize(_ samples: [DeviceMetricSample]) -> WorkoutWatchReference? {
        var latest: [DeviceMetricKind: (ts: Int, value: Double)] = [:]
        var sums: [DeviceMetricKind: Double] = [:]
        for s in samples where s.value.isFinite {
            switch s.kind {
            case .intensityModerateMin, .intensityVigorousMin:
                sums[s.kind, default: 0] += s.value
            case .aerobicTrainingEffect, .anaerobicTrainingEffect, .trainingLoad:
                if let prev = latest[s.kind], prev.ts > s.ts { continue }
                latest[s.kind] = (s.ts, s.value)
            default:
                continue
            }
        }
        guard !latest.isEmpty || !sums.isEmpty else { return nil }
        return WorkoutWatchReference(
            aerobicTrainingEffect: latest[.aerobicTrainingEffect]?.value,
            anaerobicTrainingEffect: latest[.anaerobicTrainingEffect]?.value,
            trainingLoad: latest[.trainingLoad]?.value,
            moderateMinutes: sums[.intensityModerateMin],
            vigorousMinutes: sums[.intensityVigorousMin])
    }
}
