import Foundation

// MARK: - Live readings -> NOOP's live state and stored rows
//
// Pure mapping from decoded realtime readings to what the platform does with them, so the rule (which
// values count, what is stored, how often) is one tested type with a Kotlin twin and an oracle:
//   - heart rate -> the live heart rate and one `hrSample` per reading, only when it is a plausible
//     30...220 bpm (the same window the Android live sink applies to every external source);
//   - beat interval -> the SAME R-R intake every other source feeds (live R-R + `rrInterval`), unfiltered
//     here: NOOP's own artifact filtering runs downstream exactly as it does for a WHOOP or a chest strap;
//   - Body Battery, respiration (4...60 breaths/min only) and SpO2 -> the live snapshot, plus at most ONE `deviceMetricSample` row
//     per kind per wall-clock minute (the first reading in that minute), so an open channel cannot write
//     a row a second;
//   - steps and floors -> the live snapshot only. They are cumulative day totals with no stored kind;
//     the watch's own history files carry them (history sync).
// Unknown sentinels never reach any of these: the decoder already turned them into nil.

/// One `deviceMetricSample` row to write. `kind` is the stored text (`DeviceMetricKind` raw value).
public struct GarminLiveMetricWrite: Equatable, Sendable {
    public let kind: String
    public let ts: Int
    public let value: Double
}

/// The latest live values a Garmin watch reported, for the live screen. nil = not reported (or the
/// channel is closed).
public struct GarminLiveSnapshot: Equatable, Sendable {
    public var restingHeartRate: Int?
    public var bodyBattery: Int?
    public var respirationBpm: Int?
    public var spo2Pct: Int?
    public var steps: Int?
    public var stepGoal: Int?
    public var floorsAscended: Int?
    public var floorsDescended: Int?
    public var floorsTarget: Int?

    public init() {}
}

public struct GarminLiveIntakeEffect: Equatable, Sendable {
    /// A readable heart rate to publish and store at `ts`.
    public var heartRate: Int?
    /// Beat intervals (ms) to publish and store at `ts`.
    public var rr: [Int] = []
    public var writes: [GarminLiveMetricWrite] = []
    /// The snapshot changed; republish it.
    public var snapshotChanged = false
    /// The live heart rate must be blanked (its channel closed).
    public var clearHeartRate = false
}

public struct GarminLiveIntake: Sendable {

    public static let metricSource = "garmin_live"
    public static let metricWriteIntervalSeconds = 60
    public static let plausibleHeartRate = 30...220
    /// Breaths per minute a person can have. A value outside it (127 is the i8 maximum) is not shown or stored.
    public static let plausibleRespiration = 4...60

    public static let kindBodyBattery = "body_battery"
    public static let kindRespiration = "respiration_bpm"
    public static let kindSpo2 = "spo2_pct"

    public private(set) var snapshot = GarminLiveSnapshot()
    private var lastWrittenMinute: [String: Int] = [:]

    public init() {}

    public mutating func ingest(_ reading: GarminRealtimeReading, unixSeconds ts: Int) -> GarminLiveIntakeEffect {
        var effect = GarminLiveIntakeEffect()
        let before = snapshot
        switch reading {
        case let .heartRate(bpm, resting, _, _):
            if let bpm, Self.plausibleHeartRate.contains(bpm) { effect.heartRate = bpm }
            snapshot.restingHeartRate = resting.flatMap { Self.plausibleHeartRate.contains($0) ? $0 : nil }
        case let .rrInterval(ms, _):
            if let ms { effect.rr = [ms] }
        case let .steps(total, goal):
            snapshot.steps = Int(total)
            snapshot.stepGoal = Int(goal)
        case let .floors(up, down, target):
            snapshot.floorsAscended = up
            snapshot.floorsDescended = down
            snapshot.floorsTarget = target
        case let .bodyBattery(value):
            snapshot.bodyBattery = value
            if let value { write(Self.kindBodyBattery, value, ts, &effect) }
        case let .respiration(value):
            let plausible = value.flatMap { Self.plausibleRespiration.contains($0) ? $0 : nil }
            snapshot.respirationBpm = plausible
            if let plausible { write(Self.kindRespiration, plausible, ts, &effect) }
        case let .spo2(percent, _):
            snapshot.spo2Pct = percent
            if let percent { write(Self.kindSpo2, percent, ts, &effect) }
        }
        effect.snapshotChanged = snapshot != before
        return effect
    }

    /// A channel closed: its values stop being current, so they leave the snapshot. The throttle keeps its
    /// memory, so reopening inside the same minute does not write that minute twice.
    public mutating func channelClosed(_ service: GarminService) -> GarminLiveIntakeEffect {
        var effect = GarminLiveIntakeEffect()
        let before = snapshot
        switch service {
        case .realtimeHeartRate:
            snapshot.restingHeartRate = nil
            effect.clearHeartRate = true
        case .realtimeSteps:
            snapshot.steps = nil
            snapshot.stepGoal = nil
        case .realtimeFloors:
            snapshot.floorsAscended = nil
            snapshot.floorsDescended = nil
            snapshot.floorsTarget = nil
        case .realtimeBodyBattery: snapshot.bodyBattery = nil
        case .realtimeRespiration: snapshot.respirationBpm = nil
        case .realtimeSpo2: snapshot.spo2Pct = nil
        default: break
        }
        effect.snapshotChanged = snapshot != before
        return effect
    }

    /// The link went down: every channel is closed.
    public mutating func linkDown() -> GarminLiveIntakeEffect {
        let changed = snapshot != GarminLiveSnapshot()
        snapshot = GarminLiveSnapshot()
        var effect = GarminLiveIntakeEffect()
        effect.snapshotChanged = changed
        effect.clearHeartRate = true
        return effect
    }

    private mutating func write(_ kind: String, _ value: Int, _ ts: Int, _ effect: inout GarminLiveIntakeEffect) {
        let minute = Self.floorDiv(ts, Self.metricWriteIntervalSeconds)
        guard lastWrittenMinute[kind] != minute else { return }
        lastWrittenMinute[kind] = minute
        effect.writes.append(GarminLiveMetricWrite(kind: kind, ts: ts, value: Double(value)))
    }

    static func floorDiv(_ a: Int, _ b: Int) -> Int {
        let q = a / b
        return (a % b != 0 && (a < 0) != (b < 0)) ? q - 1 : q
    }
}
