import Foundation

// MARK: - Capture mode for the undecoded realtime channels 8 (calories), 10 (intensity), 13 (stress)
//
// No public source documents these payloads: Gadgetbridge lists the service numbers but closes the handles
// on registration, so their contents were never logged. This file is the instrumentation that captures
// them: one strap-log line per payload, raw hex plus every little-endian reading a layout could plausibly
// use, beside the watch's own live HR for context. Nothing here interprets a value; the candidate layouts
// live in GarminRealtimeCandidates.swift and stay Test Centre-only until compared against the watch screen.
// The handles themselves are opened and closed by the live-channel table (GarminLiveChannels.swift), which
// admits these three only while the capture flag is on and hands their payloads out raw.
// Kotlin twin: android/app/src/main/java/com/noop/garmin/GarminRealtimeCapture.kt.

public enum GarminRealtimeCapture {

    /// The channels nobody has decoded, in the order they are opened.
    public static let undecodedChannels: [GarminService] = [.realtimeCalories, .realtimeIntensity, .realtimeStress]

    /// What the capture mode opens: the undecoded channels plus HR, whose value is the context column.
    public static let captureChannels: [GarminService] = [.realtimeHeartRate] + undecodedChannels

    public static func name(_ service: UInt16) -> String {
        switch GarminService(rawValue: service) {
        case .realtimeHeartRate: return "hr"
        case .realtimeCalories: return "calories"
        case .realtimeIntensity: return "intensity"
        case .realtimeStress: return "stress"
        default: return "service\(service)"
        }
    }

    /// Context only: the watch's live HR from a decoded channel-6 reading. 0 (decoded as nil) and 0xFF
    /// (observed off-wrist) mean "no reading" and are reported as nil.
    public static func contextHeartRate(_ reading: GarminRealtimeReading) -> Int? {
        guard case let .heartRate(bpm?, _, _, _) = reading, bpm < 0xFF else { return nil }
        return bpm
    }

    /// One capture line. `unixMs` is the host receive time; `hr` the latest channel-6 HR, if any.
    ///
    ///   garmin-rt ch=13 stress t=1793549000123 len=4 hr=72 raw=19000000 u16=25,0 u32=25 i8=25
    ///       | candidate garmin-stress(0-100) level=25 unknown=0
    ///
    /// `u16` reads every even offset, `u32` every 4-aligned offset, `i8` is byte 0 signed (the stress
    /// sentinels are expected to be small negatives). A trailing odd byte is in `raw` only. The candidate
    /// reading (GarminRealtimeCandidates) follows a ` | ` when the payload fits one.
    public static func line(service: UInt16, payload: [UInt8], unixMs: Int64, hr: Int?) -> String {
        var r = GarminByteReader(payload)
        var u16: [String] = []
        while let v = r.u16() { u16.append(String(v)) }
        r = GarminByteReader(payload)
        var u32: [String] = []
        while let v = r.u32() { u32.append(String(v)) }
        let i8 = payload.first.map { String(Int8(bitPattern: $0)) } ?? "-"
        return "garmin-rt ch=\(service) \(name(service)) t=\(unixMs) len=\(payload.count) "
            + "hr=\(hr.map(String.init) ?? "-") raw=\(garminHex(payload)) "
            + "u16=\(u16.isEmpty ? "-" : u16.joined(separator: ",")) "
            + "u32=\(u32.isEmpty ? "-" : u32.joined(separator: ",")) i8=\(i8)"
            + (GarminRealtimeCandidates.describe(service: service, payload: payload).map { " | " + $0 } ?? "")
    }

    /// The per-model availability line, once every undecoded channel has answered: `opened` or the raw
    /// refusal status per channel.
    public static func availabilityLine(model: String, firmware: UInt16, answers: [UInt16: String]) -> String {
        let fw = "\(firmware / 100).\(firmware % 100 < 10 ? "0" : "")\(firmware % 100)"
        let cols = undecodedChannels.map { "ch\($0.rawValue)=\(answers[$0.rawValue] ?? "unanswered")" }
        return "garmin-rt availability model=\(model) fw=\(fw) " + cols.joined(separator: " ")
    }
}
