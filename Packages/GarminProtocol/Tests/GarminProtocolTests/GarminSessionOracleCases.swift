#if !GARMIN_ORACLE_STANDALONE
@testable import GarminProtocol
#endif

// The session parity oracle: scripted watch traces run through `GarminSession`, one printed line per output.
// The same traces are re-implemented in android/.../com/noop/garmin/GarminSessionOracleCases.kt, and
// GarminSessionTest.kt holds this function's standalone stdout as its expected literal:
//
//   cd Packages/GarminProtocol && swiftc -O -D GARMIN_ORACLE_STANDALONE Sources/GarminProtocol/*.swift \
//       Tests/GarminProtocolTests/GarminOracleCases.swift Tests/GarminProtocolTests/GarminSessionOracleCases.swift \
//       Oracle/main.swift -o /tmp/garmin-oracle && /tmp/garmin-oracle session
//
// The transcript doubles as the handshake log assertion: every write is printed in hex next to the log line
// that names it, so a reordered, extra or missing message fails on both platforms.

enum OracleSessionStep {
    case start
    case rx([UInt8])
    case tick
    case foreground(Bool)
    case live([GarminService])
}

/// The Forerunner 165's 5024 as observed on 2026-11-01 (protocol 151, product 4432, firmware 29.05).
func oracleFr165DeviceInformation() -> [UInt8] {
    oracleFrame(5024, oracleLE16(151) + oracleLE16(4432) + oracleLE32(3_632_104_734) + oracleLE16(2905)
                + oracleLE16(512) + oracleStr("Forerunner 165") + oracleStr("Forerunner") + oracleStr("165"))
}

func oracleCobs(_ message: [UInt8]) -> [UInt8] { GarminCOBS.encode(message) }

func oracleControl(_ type: UInt8, _ rest: [UInt8]) -> [UInt8] { [0, type, 2, 0, 0, 0, 0, 0, 0, 0] + rest }

func oracleSessionHost() -> GarminHostIdentity {
    GarminSessionConfig.noopHost(platform: "oracle", softwareVersion: 1)
}

func oracleDescribe(_ o: GarminSessionOutput) -> String {
    switch o {
    case let .write(bytes): return "w \(garminHex(bytes))"
    case let .log(line): return "log \(line.rare ? "R" : "D") \(line.text)"
    case let .phase(p): return "phase \(p.label)"
    case let .battery(percent): return "battery \(percent)"
    case let .watch(info): return "watch \(info.bluetoothName) sw=\(info.softwareVersion)"
    case .pairingEventsSent: return "paired"
    case let .syncAnnounced(a): return "sync \(a.label)"
    case let .syncFile(f): return "sync-file \(f.key.label)"
    case let .syncFinished(summary): return "sync-finished \(summary.line)"
    case let .transferActive(active): return "transfer \(active)"
    case let .live(reading): return "live \(GarminRealtime.describe(reading))"
    case let .liveChannel(service, open): return "channel \(service.rawValue) \(open ? "open" : "closed")"
    case let .liveRefused(service, status): return "channel \(service.rawValue) refused status=\(status)"
    case let .liveRaw(service, payload): return "raw \(service.rawValue) \(garminHex(payload))"
    }
}

func oracleRunSession(_ name: String, _ session: inout GarminSession, _ steps: [(Int64, OracleSessionStep)],
                      next: Int64? = nil, following: Int64? = nil) -> [String] {
    var out: [String] = ["trace \(name)"]
    for (ms, step) in steps {
        let clock = GarminClock(nowMs: ms, unixSeconds: 1_793_546_986 + ms / 1000, utcOffsetSeconds: 25_200,
                                nextTransitionUnix: next, followingTransitionUnix: following)
        let outputs: [GarminSessionOutput]
        switch step {
        case .start: outputs = session.start(clock)
        case let .rx(bytes): outputs = session.receive(bytes, clock)
        case .tick: outputs = session.tick(clock)
        case let .foreground(fg): outputs = session.setForeground(fg, clock)
        case let .live(services): outputs = session.setLiveChannels(Set(services), clock)
        }
        out += outputs.map { "@\(ms) " + oracleDescribe($0) }
    }
    out.append("end phase=\(session.phase.label) in=\(session.messagesIn) dropped=\(session.framesDropped) "
               + "deadline=\(oracleOpt(session.nextDeadlineMs))")
    return out
}

func garminSessionOracleLines() -> [String] {
    var out: [String] = []
    let handle: UInt8 = 0x65
    func ml(_ message: [UInt8]) -> [UInt8] { [handle] + oracleCobs(message) }

    // A: the full multi-link handshake as the Forerunner 165 ran it, plus every inbound branch.
    var a = GarminSession(config: GarminSessionConfig(host: oracleSessionHost(), firstPairing: true,
                                                      sendsHostLifecycleEvents: true),
                          transport: .multiLinkV2, mtu: 150)
    let deviceInfo = ml(oracleFr165DeviceInformation())
    let corrupt: [UInt8] = { var f = oracleFr165DeviceInformation(); f[6] ^= 0xFF; return ml(f) }()
    let config = ml(oracleFrame(5050, [8] + [0xFF, 0x01, 0, 0, 0, 0, 0, 0x80]))
    out += oracleRunSession("ml-full", &a, [
        (0, .start),
        (5, .rx(oracleControl(3, oracleLE16(1) + [0x60, 0]))),       // stale handle closed by CLOSE_ALL
        (10, .rx(oracleControl(6, oracleLE16(0) + [0]))),             // CLOSE_ALL_RESP
        (20, .rx(oracleControl(1, oracleLE16(1) + [0, handle, 0]))),  // REGISTER_RESP plain
        (30, .rx(Array(deviceInfo.prefix(20)))),                      // 5024 split across two notifications
        (31, .rx([handle] + Array(deviceInfo.dropFirst(20)))),
        (40, .rx(deviceInfo)),                                        // re-sent 5024
        (50, .rx(config)),
        (60, .rx(ml(oracleFrame(5000, oracleLE16(5050) + [0])))),
        (70, .rx(ml(oracleFrame(5052, oracleLE32(90_094_860))))),
        (80, .rx(ml(oracleFrame(5101, [4] + oracleLE32(0x0102_0304))))),
        (90, .rx(ml(oracleFrame(5043, oracleLE16(795) + oracleLE32(0) + oracleLE32(4) + oracleLE32(4) + [0x6A, 0x02, 0x08, 0x01])))),
        (100, .rx(ml(oracleFrame(5023, [0x10, 0xC8, 87, 0, 0, 0, 0])))),
        (110, .rx(ml(oracleFrame(5037, [0, 4] + oracleLE32(0x0F))))),
        (120, .rx(ml(oracleFrame(5014, [1, 2])))),
        (121, .rx(ml(oracleFrame(5014, [1, 2])))),
        (130, .rx(ml(oracleFrame(5000, oracleLE16(5030) + [2])))),
        (140, .rx(corrupt)),
        (150, .rx([0x22, 1, 2, 3])),                                  // unregistered handle
        (160, .rx(oracleControl(1, oracleLE16(6) + [0, 0x22, 0]))),   // foreign-service registration
        (170, .foreground(false)),
        (171, .foreground(false)),
        (180, .rx(oracleControl(3, oracleLE16(1) + [handle, 0]))),    // watch closed GFDI
        (190, .rx(oracleControl(1, oracleLE16(1) + [0, 0x66, 0]))),
        (195, .rx([0x66] + oracleCobs(oracleFr165DeviceInformation()))),
        (196, .rx([0x66] + oracleCobs(oracleFrame(5050, [1, 0x09])))),
        (200, .foreground(true)),
        (210, .tick),
    ])

    // B: registration refused; nothing further is sent and later traffic is ignored.
    var b = GarminSession(config: GarminSessionConfig(host: oracleSessionHost(), firstPairing: false,
                                                      sendsHostLifecycleEvents: false),
                          transport: .multiLinkV2, mtu: 23)
    out += oracleRunSession("ml-refused", &b, [
        (0, .start),
        (10, .rx(oracleControl(6, oracleLE16(0) + [0]))),
        (20, .rx(oracleControl(1, oracleLE16(1) + [3, 0, 0]))),
        (30, .rx(ml(oracleFr165DeviceInformation()))),
    ])

    // C: the watch never answers CLOSE_ALL; the handshake deadline fails the session.
    var c = GarminSession(config: GarminSessionConfig(host: oracleSessionHost(), firstPairing: false,
                                                      sendsHostLifecycleEvents: true, handshakeTimeoutMs: 1_000),
                          transport: .multiLinkV2, mtu: 23)
    out += oracleRunSession("ml-timeout", &c, [(0, .start), (999, .tick), (1_000, .tick), (1_001, .tick)])

    // D: V1 direct transport at the minimum MTU, background from the start, with a DST zone.
    var d = GarminSession(config: GarminSessionConfig(host: oracleSessionHost(), firstPairing: false,
                                                      sendsHostLifecycleEvents: true),
                          transport: .v1, mtu: 23)
    let direct = oracleCobs(oracleFr165DeviceInformation())
    out += oracleRunSession("v1-direct", &d, [
        (0, .start),
        (1, .foreground(false)),
        (10, .rx(Array(direct.prefix(9)))),
        (11, .rx(Array(direct.dropFirst(9)))),
        (20, .rx(oracleCobs(oracleFrame(5050, [2, 0x09, 0x00])))),
        (30, .rx(oracleCobs(oracleFrame(5052, oracleLE32(7))))),
    ], next: 1_801_386_000, following: 1_793_523_600 + 31_536_000)

    // E: the watch asks for reliable mode; GFDI rides MLR (acks, then retransmit on the timer).
    var e = GarminSession(config: GarminSessionConfig(host: oracleSessionHost(), firstPairing: false,
                                                      sendsHostLifecycleEvents: false),
                          transport: .multiLinkV2, mtu: 64)
    let reliableHandle: UInt8 = 0x81
    let mlrInfo = GarminMlrHeader(handle: reliableHandle, reqNum: 0, seq: 0).bytes + oracleCobs(oracleFr165DeviceInformation())
    out += oracleRunSession("mlr", &e, [
        (0, .start),
        (10, .rx(oracleControl(6, oracleLE16(0) + [0]))),
        (20, .rx(oracleControl(1, oracleLE16(1) + [0, reliableHandle, 1]))),
        (30, .rx(mlrInfo)),
        (50, .tick),
        (1_040, .tick),
    ])

    // F: live channels. Asked for before the handshake (sent once ready), data on each channel including
    // the unknown sentinels, a refusal, a reliable answer, an unsupported ask, the demand shrinking, a
    // re-ask while closing, an unrequested registration and the watch closing everything.
    var f = GarminSession(config: GarminSessionConfig(host: oracleSessionHost(), firstPairing: false,
                                                      sendsHostLifecycleEvents: false),
                          transport: .multiLinkV2, mtu: 150)
    let all: [GarminService] = [.realtimeHeartRate, .realtimeHrv, .realtimeSteps, .realtimeFloors, .realtimeSpo2,
                                .realtimeBodyBattery, .realtimeRespiration, .realtimeStress, .realtimeAccelerometer]
    out += oracleRunSession("ml-live", &f, [
        (0, .start),
        (1, .live([.realtimeHeartRate, .realtimeHrv])),
        (10, .rx(oracleControl(6, oracleLE16(0) + [0]))),
        (20, .rx(oracleControl(1, oracleLE16(1) + [0, handle, 0]))),
        (30, .rx(ml(oracleFr165DeviceInformation()))),
        (40, .rx(ml(oracleFrame(5050, [1, 0x09])))),
        (50, .rx(oracleControl(1, oracleLE16(6) + [0, 0x66, 0]))),
        (51, .rx(oracleControl(1, oracleLE16(12) + [0, 0x67, 0]))),
        (60, .rx([0x66, 3, 62, 55, 0xFF, 0xFF])),
        (61, .rx([0x66, 3, 0, 0, 0xFF, 0xFF])),
        (62, .rx([0x66, 2, 250, 0])),
        (63, .rx([0x66, 3])),
        (70, .rx([0x67, 0xE8, 0x03] + oracleLE32(0x0102_0304))),
        (71, .rx([0x67, 0x00, 0x00] + oracleLE32(7))),
        (72, .rx([0x67, 0x52, 0x03])),
        (73, .rx([0x67, 0x05])),
        (80, .live(all)),
        (90, .rx(oracleControl(1, oracleLE16(7) + [0, 0x68, 0]))),
        (91, .rx(oracleControl(1, oracleLE16(9) + [0, 0x69, 0]))),
        (92, .rx(oracleControl(1, oracleLE16(19) + [1, 0, 0]))),
        (93, .rx(oracleControl(1, oracleLE16(20) + [0, 0x6A, 1]))),
        (94, .rx(oracleControl(1, oracleLE16(21) + [0, 0x6B, 0]))),
        (100, .rx([0x68] + oracleLE32(4_321) + oracleLE32(7_500))),
        (101, .rx([0x69] + oracleLE16(3) + oracleLE16(1) + oracleLE16(10))),
        (102, .rx([0x6B, 14])),
        (103, .rx([0x6B, 0xFE])),
        (104, .rx([0x6B, 0x00])),
        (110, .live(all)),
        (120, .rx(oracleControl(3, oracleLE16(20) + [0x6A, 0]))),
        (130, .live([.realtimeHeartRate, .realtimeHrv])),
        (140, .rx(oracleControl(3, oracleLE16(7) + [0x68, 0]))),
        (141, .rx(oracleControl(3, oracleLE16(9) + [0x69, 0]))),
        (142, .rx(oracleControl(3, oracleLE16(21) + [0x6B, 0]))),
        (150, .live([.realtimeSpo2])),
        (160, .rx(oracleControl(1, oracleLE16(19) + [0, 0x6C, 0]))),
        (161, .rx([0x6C, 0xFF] + oracleLE32(0))),
        (162, .rx([0x6C, 97] + oracleLE32(1_162_481_386))),
        (163, .rx([0x6C, 101] + oracleLE32(5))),
        (170, .live([])),
        (171, .live([.realtimeHeartRate])),
        (180, .rx(oracleControl(3, oracleLE16(6) + [0x66, 0]))),
        (181, .rx(oracleControl(3, oracleLE16(12) + [0x67, 0]))),
        (182, .rx(oracleControl(3, oracleLE16(19) + [0x6C, 0]))),
        (190, .rx(oracleControl(1, oracleLE16(6) + [0, 0x6D, 0]))),
        (191, .rx(oracleControl(1, oracleLE16(21) + [0, 0x6E, 0]))),
        (192, .rx(oracleControl(3, oracleLE16(21) + [0x6E, 0]))),
        (193, .rx(oracleControl(1, oracleLE16(77) + [0, 0x6F, 0]))),
        (200, .rx([0x6D, 3, 70, 0, 0xFF, 0xFF])),
        (210, .rx(oracleControl(6, oracleLE16(0) + [0]))),
        (211, .rx([0x6D, 3, 71, 0, 0xFF, 0xFF])),
        (220, .rx(oracleControl(1, oracleLE16(1) + [0, 0x70, 0]))),
        (221, .rx([0x70] + oracleCobs(oracleFr165DeviceInformation()))),
        (222, .rx([0x70] + oracleCobs(oracleFrame(5050, [1, 0x09])))),
        (230, .rx(oracleControl(1, oracleLE16(6) + [0, 0x71, 0]))),
        (240, .rx(oracleControl(2, oracleLE16(6) + [0x71, 0]))),
    ])

    // G: a direct (V1) transport has no live channels; asked once, said once.
    var g = GarminSession(config: GarminSessionConfig(host: oracleSessionHost(), firstPairing: false,
                                                      sendsHostLifecycleEvents: false),
                          transport: .v1, mtu: 23)
    out += oracleRunSession("v1-live", &g, [
        (0, .start), (1, .live([.realtimeHeartRate])), (2, .live([.realtimeHeartRate])), (3, .live([])),
    ])

    // Transport selection.
    let fr165: [GarminGattService] = [
        GarminGattService(uuid: "6A4E2800-667B-11E3-949A-0800200C9A66", characteristics: [
            "6A4E2810-667B-11E3-949A-0800200C9A66", "6A4E2811-667B-11E3-949A-0800200C9A66",
            "6A4E2820-667B-11E3-949A-0800200C9A66", "6A4E2821-667B-11E3-949A-0800200C9A66", "2803"]),
        GarminGattService(uuid: "6A4E8022-667B-11E3-949A-0800200C9A66", characteristics: [
            "6A4E4C80-667B-11E3-949A-0800200C9A66", "6A4ECD28-667B-11E3-949A-0800200C9A66"]),
        GarminGattService(uuid: "180D", characteristics: ["2A37"]),
    ]
    let selections: [(String, [GarminGattService])] = [
        ("fr165", fr165),
        ("ml-second-pair", [GarminGattService(uuid: GarminTransportSelection.multiLinkService, characteristics: [
            GarminTransportSelection.multiLinkNotify[0], GarminTransportSelection.multiLinkNotify[2],
            GarminTransportSelection.multiLinkWrite[1], GarminTransportSelection.multiLinkWrite[2]])]),
        ("watch-apps-only", [fr165[1], fr165[2]]),
        ("v1", [GarminGattService(uuid: GarminTransportSelection.v1Service, characteristics: [
            GarminTransportSelection.v1Write, GarminTransportSelection.v1Notify])]),
        ("v0", [GarminGattService(uuid: GarminTransportSelection.v0Service.uppercased(), characteristics: [
            GarminTransportSelection.v0Notify, GarminTransportSelection.v0Write])]),
        ("v1-half", [GarminGattService(uuid: GarminTransportSelection.v1Service, characteristics: [
            GarminTransportSelection.v1Write])]),
        ("empty", []),
    ]
    for (name, services) in selections {
        let pick = GarminTransportSelection.select(services)
        out.append("select \(name) \(pick.map { "\($0.kind.rawValue) \($0.notify.prefix(8)) \($0.write.prefix(8))" } ?? "unsupported") "
                   + "found=\(GarminTransportSelection.describe(services))")
    }
    for (name, data) in [("garmin", [0x87, 0x00, 1] as [UInt8]?), ("other", [0x4C, 0x00]), ("short", [0x87]), ("none", nil)] {
        out.append("advert \(name) \(GarminAdvertisement.isGarmin(manufacturerData: data))")
    }
    return out
}
