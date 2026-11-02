import Foundation

// MARK: - Realtime multi-link channel payloads
//
// Wire facts (Gadgetbridge `CommunicatorV2` and its realtime callbacks, see ATTRIBUTION.md). Each realtime
// service gets its own ML handle; every notification on that handle is `[handle][payload]`, and the payload
// layouts below are little-endian. Nothing here interprets a byte that upstream has not decoded: the heart
// rate `type` byte, the bytes after the resting value, and the u32 after each beat interval are carried as
// raw candidates for the log and never feed a value.
//
//   6  heart rate   [type u8][bpm u8][resting u8][0xFF 0xFF]   bpm 0 = no reading
//   7  steps        [steps u32][goal u32]                       cumulative for the day
//   9  floors       [ascended u16][descended u16][target u16]
//   12 HRV / RR     [rr u16 ms][unknown u32]                    rr 0 = no interval
//   19 SpO2         [percent i8][garmin time u32]               percent -1 = unknown (time then invalid)
//   20 Body Battery [value u8]                                  0x7F = unknown
//   21 respiration  [breaths/min i8]                            negative = unknown
//
// Channels 8 (calories), 10 (intensity) and 13 (stress) are undecoded upstream: they open only in the Test
// Centre capture mode and reach the app raw (GarminRealtimeCapture), never through this decoder. 16
// (accelerometer) has no consumer in NOOP and never opens.

/// One decoded realtime notification. An "unknown" sentinel decodes to nil, never to a number.
public enum GarminRealtimeReading: Equatable, Sendable {
    /// `bpm` is nil when the watch sends 0 (no reading, e.g. off the wrist); `resting` is nil when 0 or
    /// absent. `type` and `trailing` are undecoded candidates, logged only.
    case heartRate(bpm: Int?, resting: Int?, type: UInt8, trailing: [UInt8])
    /// One beat-to-beat interval in milliseconds, nil for 0. `unknown` is the trailing u32 candidate, nil
    /// when the payload stops after the interval.
    case rrInterval(ms: Int?, unknown: UInt32?)
    /// Cumulative steps today and the step goal.
    case steps(total: UInt32, goal: UInt32)
    case floors(ascended: Int, descended: Int, ascendTarget: Int)
    /// `percent` is nil for the watch's "unknown" codes and anything outside 1...100; `garminTime` (seconds
    /// since 1989-12-31 UTC) is nil whenever the percent is, because upstream reports it invalid then.
    case spo2(percent: Int?, garminTime: UInt32?)
    /// 0...100, nil for 0x7F and anything above 100.
    case bodyBattery(Int?)
    /// Breaths per minute, nil for zero or negative.
    case respiration(breathsPerMinute: Int?)
}

public enum GarminRealtime {

    /// The realtime services this layer can decode and therefore may open. Anything else asked for is
    /// refused before a byte goes out (the accelerometer in particular; the undecoded 8 / 10 / 13 pass only
    /// while capture mode is on, see GarminLiveChannelTable.setCapture).
    public static let decodableServices: Set<GarminService> = [
        .realtimeHeartRate, .realtimeSteps, .realtimeFloors, .realtimeHrv,
        .realtimeSpo2, .realtimeBodyBattery, .realtimeRespiration,
    ]

    /// Decode one payload (handle byte already stripped). nil = too short for its layout, or a service
    /// this layer does not decode. Extra trailing bytes are tolerated.
    public static func decode(service: GarminService, payload: [UInt8]) -> GarminRealtimeReading? {
        var r = GarminByteReader(payload)
        switch service {
        case .realtimeHeartRate:
            guard let type = r.u8(), let bpm = r.u8() else { return nil }
            let resting = r.u8() ?? 0
            return .heartRate(bpm: bpm == 0 ? nil : Int(bpm), resting: resting == 0 ? nil : Int(resting),
                              type: type, trailing: r.rest())
        case .realtimeHrv:
            guard let rr = r.u16() else { return nil }
            return .rrInterval(ms: rr == 0 ? nil : Int(rr), unknown: r.u32())
        case .realtimeSteps:
            guard let total = r.u32(), let goal = r.u32() else { return nil }
            return .steps(total: total, goal: goal)
        case .realtimeFloors:
            guard let up = r.u16(), let down = r.u16(), let target = r.u16() else { return nil }
            return .floors(ascended: Int(up), descended: Int(down), ascendTarget: Int(target))
        case .realtimeSpo2:
            guard let raw = r.u8() else { return nil }
            let percent = Int(Int8(bitPattern: raw))
            guard (1...100).contains(percent) else { return .spo2(percent: nil, garminTime: nil) }
            return .spo2(percent: percent, garminTime: r.u32())
        case .realtimeBodyBattery:
            guard let raw = r.u8() else { return nil }
            return .bodyBattery(raw <= 100 ? Int(raw) : nil)
        case .realtimeRespiration:
            guard let raw = r.u8() else { return nil }
            let value = Int(Int8(bitPattern: raw))
            return .respiration(breathsPerMinute: value > 0 ? value : nil)
        default:
            return nil
        }
    }

    /// One log / oracle line for a reading. Same text on both platforms.
    public static func describe(_ reading: GarminRealtimeReading) -> String {
        func opt(_ v: Int?) -> String { v.map(String.init) ?? "-" }
        switch reading {
        case let .heartRate(bpm, resting, type, trailing):
            return "hr bpm=\(opt(bpm)) resting=\(opt(resting)) type=\(type) trailing=\(garminHex(trailing))"
        case let .rrInterval(ms, unknown):
            return "rr ms=\(opt(ms)) unknown=\(unknown.map { String($0) } ?? "-")"
        case let .steps(total, goal):
            return "steps total=\(total) goal=\(goal)"
        case let .floors(up, down, target):
            return "floors up=\(up) down=\(down) target=\(target)"
        case let .spo2(percent, time):
            return "spo2 pct=\(opt(percent)) time=\(time.map { String($0) } ?? "-")"
        case let .bodyBattery(value):
            return "body-battery \(opt(value))"
        case let .respiration(value):
            return "respiration \(opt(value))"
        }
    }
}
