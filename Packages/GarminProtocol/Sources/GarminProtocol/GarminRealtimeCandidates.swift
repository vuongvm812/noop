import Foundation

// MARK: - CANDIDATE layouts for realtime channels 8 / 10 / 13 (UNVALIDATED)
//
// These are NOT documented anywhere upstream. They are hypotheses read off NOOP's own captures from a
// Forerunner 165 (firmware 29.05) on 2026-11-01, taken OFF-WRIST on the charger, and have NOT been compared
// against the watch screen. Until a worn session matches the screen across >= 2 sessions where the value
// moved (issue "Garmin 6/10"), every value here is instrumentation: shown only in the Test Centre capture
// readout, never stored as a metric, never fed to an analytics gate. Each decoder accepts only the exact
// payload length seen so far; anything else returns nil and stays raw in the capture log.
// Kotlin twin: android/app/src/main/java/com/noop/garmin/GarminRealtimeCandidates.kt.

/// Channel 8, 8 bytes: `[a u32][b u32]`. Observed a = 3077 → 3080 over 62 s at rest (+1 roughly every
/// 40 s, a resting burn of ~1.5 kcal/min) and b = 993 fixed. Hypothesis: a = today's TOTAL kcal (resting +
/// active), b = today's ACTIVE kcal. The steps-style `value, goal` pairing is unlikely: b < a and b did not
/// change. Not yet checked: that the screen's total and active numbers equal a and b.
public struct GarminCaloriesCandidate: Equatable, Sendable {
    public let totalKcal: UInt32
    public let activeKcal: UInt32

    public static func decode(_ payload: [UInt8]) -> GarminCaloriesCandidate? {
        guard payload.count == 8 else { return nil }
        var r = GarminByteReader(payload)
        guard let a = r.u32(), let b = r.u32() else { return nil }
        return GarminCaloriesCandidate(totalKcal: a, activeKcal: b)
    }
}

/// Channel 10, 14 bytes: seven u16. Observed `172, 26, 73, 357, 0, 150, 0`. Hypothesis: [0] = this
/// week's intensity minutes, [1] = moderate minutes, [2] = vigorous minutes, [5] = the weekly goal. The
/// structural evidence is that [0] == [1] + 2 × [2] (Garmin counts vigorous minutes double) and 150 is
/// Garmin's default weekly goal. [3], [4] and [6] are unknown and are carried raw, never interpreted.
public struct GarminIntensityCandidate: Equatable, Sendable {
    public let weeklyMinutes: UInt16
    public let moderateMinutes: UInt16
    public let vigorousMinutes: UInt16
    public let weeklyGoal: UInt16
    /// Words 3, 4 and 6, meaning unknown.
    public let unknown: [UInt16]

    /// Whether the payload satisfies the structural check behind the hypothesis. A false here is evidence
    /// AGAINST the layout and is logged as such.
    public var totalMatchesParts: Bool {
        Int(weeklyMinutes) == Int(moderateMinutes) + 2 * Int(vigorousMinutes)
    }

    public static func decode(_ payload: [UInt8]) -> GarminIntensityCandidate? {
        guard payload.count == 14 else { return nil }
        var r = GarminByteReader(payload)
        var w: [UInt16] = []
        while let v = r.u16() { w.append(v) }
        guard w.count == 7 else { return nil }
        return GarminIntensityCandidate(weeklyMinutes: w[0], moderateMinutes: w[1], vigorousMinutes: w[2],
                                        weeklyGoal: w[5], unknown: [w[3], w[4], w[6]])
    }
}

/// Channel 13, 4 bytes: `[level i16][unknown u16]`. Observed only `fc ff 00 00` (level −4) while off-wrist
/// on the charger. Hypothesis: level 0...100 is Garmin's stress score and a negative value is a "no reading"
/// sentinel whose individual meanings are unknown (−4 seen off-wrist). Whether the level is an i16 or an i8
/// plus a pad byte cannot be told until a positive reading arrives; both read −4 here.
public struct GarminStressCandidate: Equatable, Sendable {
    public let level: Int16
    public let unknown: UInt16

    /// A reading on Garmin's own 0-100 scale (NOT NOOP's stress scale), or nil for a sentinel.
    public var score: Int? { (0...100).contains(level) ? Int(level) : nil }

    public static func decode(_ payload: [UInt8]) -> GarminStressCandidate? {
        guard payload.count == 4 else { return nil }
        var r = GarminByteReader(payload)
        guard let level = r.u16(), let rest = r.u16() else { return nil }
        return GarminStressCandidate(level: Int16(bitPattern: level), unknown: rest)
    }
}

public enum GarminRealtimeCandidates {

    /// The Test Centre readout for one payload, or nil when the channel has no candidate or the payload
    /// does not fit it. Always prefixed "candidate" so it cannot be read as a shipped value.
    public static func describe(service: UInt16, payload: [UInt8]) -> String? {
        switch GarminService(rawValue: service) {
        case .realtimeCalories:
            guard let c = GarminCaloriesCandidate.decode(payload) else { return nil }
            return "candidate calories total=\(c.totalKcal) active=\(c.activeKcal) kcal"
        case .realtimeIntensity:
            guard let c = GarminIntensityCandidate.decode(payload) else { return nil }
            return "candidate intensity week=\(c.weeklyMinutes) moderate=\(c.moderateMinutes) "
                + "vigorous=\(c.vigorousMinutes) goal=\(c.weeklyGoal) "
                + "unknown=\(c.unknown.map(String.init).joined(separator: ",")) "
                + "check=\(c.totalMatchesParts ? "ok" : "MISMATCH")"
        case .realtimeStress:
            guard let c = GarminStressCandidate.decode(payload) else { return nil }
            let reading = c.score.map { "level=\($0)" }
                ?? (c.level < 0 ? "sentinel=\(c.level)" : "out-of-range=\(c.level)")
            return "candidate garmin-stress(0-100) \(reading) unknown=\(c.unknown)"
        default:
            return nil
        }
    }
}
