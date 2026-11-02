#if !GARMIN_ORACLE_STANDALONE
@testable import GarminProtocol
#endif

// The capture parity oracle (Garmin 6/10): the undecoded channels 8 / 10 / 13 through the live-channel table
// (gated by the capture flag, raw payloads out, refusals reported), the capture log line and the CANDIDATE
// decoders, one printed line per result. The Kotlin re-run is android/.../com/noop/garmin/GarminCaptureOracleCases.kt,
// and GarminCaptureTest.kt holds this function's standalone stdout as its expected literal:
//
//   cd Packages/GarminProtocol && swiftc -O -D GARMIN_ORACLE_STANDALONE Sources/GarminProtocol/*.swift \
//       Tests/GarminProtocolTests/GarminOracleCases.swift Tests/GarminProtocolTests/GarminSessionOracleCases.swift \
//       Tests/GarminProtocolTests/GarminSyncOracleCases.swift Tests/GarminProtocolTests/GarminRealtimeOracleCases.swift \
//       Tests/GarminProtocolTests/GarminCaptureOracleCases.swift Oracle/main.swift -o /tmp/garmin-oracle \
//       && /tmp/garmin-oracle capture
//
// Every payload here is synthesized for the test (our own values in the observed shapes), not a personal log.

enum OracleCaptureStep {
    case start
    case rx([UInt8])
    case want([GarminService])
    case capture(Bool)
}

func oracleRunCapture(_ name: String, _ session: inout GarminSession,
                      _ steps: [(Int64, OracleCaptureStep)]) -> [String] {
    var out: [String] = ["trace \(name)"]
    for (ms, step) in steps {
        let clock = GarminClock(nowMs: ms, unixSeconds: 1_793_546_986 + ms / 1000, utcOffsetSeconds: 25_200)
        let outputs: [GarminSessionOutput]
        switch step {
        case .start: outputs = session.start(clock)
        case let .rx(bytes): outputs = session.receive(bytes, clock)
        case let .want(services): outputs = session.setLiveChannels(Set(services), clock)
        case let .capture(on): outputs = session.setUndecodedCapture(on, clock)
        }
        out += outputs.map { "@\(ms) " + oracleDescribe($0) }
    }
    let open = session.openLiveChannels.map(\.rawValue).sorted().map(String.init)
    out.append("end phase=\(session.phase.label) open=\(open.isEmpty ? "-" : open.joined(separator: ","))")
    return out
}

/// A V2 session driven to `ready` on GFDI handle 0x65 (no first pairing, no lifecycle events).
func oracleCaptureReadySteps() -> [(Int64, OracleCaptureStep)] {
    let handle: UInt8 = 0x65
    func ml(_ message: [UInt8]) -> [UInt8] { [handle] + oracleCobs(message) }
    return [
        (0, .start),
        (1, .rx(oracleControl(6, oracleLE16(0) + [0]))),
        (2, .rx(oracleControl(1, oracleLE16(1) + [0, handle, 0]))),
        (3, .rx(ml(oracleFr165DeviceInformation()))),
        (4, .rx(ml(oracleFrame(5050, [8] + [0xFF, 0x01, 0, 0, 0, 0, 0, 0x80])))),
    ]
}

func garminCaptureOracleLines() -> [String] {
    var out: [String] = []
    func config() -> GarminSessionConfig {
        GarminSessionConfig(host: oracleSessionHost(), firstPairing: false, sendsHostLifecycleEvents: false)
    }
    func registered(_ service: UInt16, status: UInt8, handle: UInt8) -> [UInt8] {
        oracleControl(1, oracleLE16(Int(service)) + [status, handle, 0])
    }
    func closed(_ service: UInt16, handle: UInt8) -> [UInt8] {
        oracleControl(3, oracleLE16(Int(service)) + [handle, 0])
    }
    let all: [GarminService] = [.realtimeHeartRate, .realtimeCalories, .realtimeIntensity, .realtimeStress]

    // A: asked for without capture (only HR opens), then capture on: data routed raw, one refused, capture off.
    var a = GarminSession(config: config(), transport: .multiLinkV2, mtu: 150)
    let intensity: [UInt8] = oracleLE16(95) + oracleLE16(15) + oracleLE16(40) + oracleLE16(7) + oracleLE16(0)
        + oracleLE16(150) + oracleLE16(0)
    let calories: [UInt8] = oracleLE32(2150) + oracleLE32(410)
    out += oracleRunCapture("capture", &a, oracleCaptureReadySteps() + [
        (10, .want(all + [.realtimeAccelerometer])),
        (11, .capture(true)),
        (20, .rx(registered(6, status: 0, handle: 0x66))),
        (21, .rx(registered(8, status: 0, handle: 0x67))),
        (22, .rx(registered(10, status: 0, handle: 0x68))),
        (23, .rx(registered(13, status: 3, handle: 0))),
        (30, .rx([0x66, 0x00, 0x48, 0x3B, 0xFF, 0xFF])),
        (31, .rx([0x67] + calories)),
        (32, .rx([0x68] + intensity)),
        (33, .rx([0x69, 0x01, 0x02])),
        (34, .capture(true)),
        (40, .capture(false)),
        (41, .rx(closed(8, handle: 0x67))),
        (42, .rx([0x68, 0x01])),
        (43, .rx(closed(10, handle: 0x68))),
        (44, .want([.realtimeHeartRate])),
        (45, .capture(true)),
    ])

    // B: capture switched off before the registration answers; the late handle is given back.
    var b = GarminSession(config: config(), transport: .multiLinkV2, mtu: 150)
    out += oracleRunCapture("late-answer", &b, oracleCaptureReadySteps() + [
        (10, .capture(true)),
        (11, .want([.realtimeStress])),
        (12, .capture(false)),
        (13, .rx(registered(13, status: 0, handle: 0x70))),
        (14, .rx(closed(13, handle: 0x70))),
    ])

    // C: the watch closes one channel itself, then closes everything.
    var c = GarminSession(config: config(), transport: .multiLinkV2, mtu: 150)
    out += oracleRunCapture("watch-closes", &c, oracleCaptureReadySteps() + [
        (10, .capture(true)),
        (11, .want([.realtimeStress, .realtimeCalories])),
        (12, .rx(registered(8, status: 0, handle: 0x67))),
        (13, .rx(registered(13, status: 0, handle: 0x66))),
        (14, .rx(closed(13, handle: 0x66))),
        (15, .rx(oracleControl(6, oracleLE16(0) + [0]))),
        (16, .rx([0x67, 0x01])),
    ])

    // D: capture and demand set before the handshake are applied once it completes.
    var d = GarminSession(config: config(), transport: .multiLinkV2, mtu: 150)
    var early: [(Int64, OracleCaptureStep)] = [(0, .start), (1, .capture(true)), (1, .want([.realtimeIntensity]))]
    for step in oracleCaptureReadySteps().dropFirst() { early.append((step.0 + 1, step.1)) }
    out += oracleRunCapture("before-ready", &d, early)

    // E: V1 has no multi-link handles.
    var e = GarminSession(config: config(), transport: .v1, mtu: 150)
    out += oracleRunCapture("v1", &e, [(0, .start), (1, .capture(true)), (2, .want([.realtimeStress]))])

    // Capture lines and candidates over synthesized payloads in the observed shapes, plus edge lengths.
    let payloads: [(UInt16, [UInt8])] = [
        (6, [0x00, 0x48, 0x3B, 0xFF, 0xFF]),
        (6, [0x00, 0xFF, 0x3B, 0xFF, 0xFF]),
        (6, [0x00, 0x00]),
        (6, [0x00]),
        (8, oracleLE32(2150) + oracleLE32(410)),
        (8, oracleLE32(0xFFFF_FFFF) + oracleLE32(0)),
        (8, oracleLE32(2150)),
        (10, oracleLE16(95) + oracleLE16(15) + oracleLE16(40) + oracleLE16(7) + oracleLE16(0) + oracleLE16(150) + oracleLE16(0)),
        (10, oracleLE16(96) + oracleLE16(15) + oracleLE16(40) + oracleLE16(7) + oracleLE16(0) + oracleLE16(150) + oracleLE16(0)),
        (10, oracleLE16(0xFFFF) + oracleLE16(0xFFFF) + oracleLE16(0xFFFF) + oracleLE16(1) + oracleLE16(2) + oracleLE16(3) + oracleLE16(4)),
        (10, oracleLE16(95) + oracleLE16(15) + [0x28]),
        (13, oracleLE16(42) + oracleLE16(0)),
        (13, oracleLE16(0xFFFE) + oracleLE16(0)),
        (13, oracleLE16(101) + oracleLE16(7)),
        (13, oracleLE16(0) + oracleLE16(0xFFFF)),
        (13, [0x2A]),
        (13, []),
        (21, [0x10]),
    ]
    for (i, (service, payload)) in payloads.enumerated() {
        let reading = GarminService(rawValue: service).flatMap { GarminRealtime.decode(service: $0, payload: payload) }
        out.append("hr \(service) \(oracleOpt(reading.flatMap(GarminRealtimeCapture.contextHeartRate)))")
        out.append(GarminRealtimeCapture.line(service: service, payload: payload,
                                              unixMs: 1_793_549_639_000 + Int64(i), hr: i % 2 == 0 ? 72 : nil))
    }
    out.append(GarminRealtimeCapture.availabilityLine(model: "Forerunner 165", firmware: 2905,
                                                      answers: [8: "opened", 13: "refused(3)"]))
    out.append(GarminRealtimeCapture.availabilityLine(model: "Venu", firmware: 1001,
                                                      answers: [8: "opened", 10: "opened", 13: "opened"]))
    for raw in GarminService.allCases.map(\.rawValue) {
        out.append("realtime \(raw) \(GarminService(rawValue: raw)!.isRealtime)")
    }
    return out
}
