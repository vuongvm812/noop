import Foundation

// MARK: - The connected-watch session: ML startup and the minimal GFDI handshake
//
// A pure state machine with no CoreBluetooth, no timers and no clock of its own. The platform source feeds
// it every notification from the transport's notify characteristic, calls `tick` at `nextDeadlineMs`, and
// performs the outputs in order (writes go to the transport's write characteristic). Every outbound GFDI
// message is a `GarminOutbound` case, so the session can only send what the allowlist holds; the ML
// control frames are `GarminMultiLink.ControlRequest` cases. Both platforms run the same trace through
// this type and its Kotlin twin (`com.noop.garmin.GarminSession`) and compare the printed transcript.
//
// Sequence (wire facts from Gadgetbridge, see ATTRIBUTION.md; observed on a Forerunner 165):
//   ML only:  CLOSE_ALL → CLOSE_ALL_RESP → REGISTER GFDI (plain) → REGISTER_RESP [handle, reliable].
//             The watch then sends 5024 on that handle. If it answered `reliable`, the handle runs MLR.
//   GFDI:     5024 DeviceInformation → ACK, then the 5000 reply carrying NOOP's own identity.
//             5050 Configuration     → ACK, then NOOP's minimal capability set, then SYNC_READY (and, on
//                                       the first pairing only, PAIR_COMPLETE + SETUP_WIZARD_COMPLETE),
//                                       then the host foreground/background state.
//             5101 AuthNegotiation   → the echo reply (no encryption is negotiated).
//             5052 CurrentTimeRequest→ local time, UTC offset and the next two zone transitions.
//             5043 watch requests    → declined at once (HTTP, weather, AGPS, OAuth: NOOP has no server and
//                                       never fakes a Garmin login).
//             anything else          → ACK when understood, UNSUPPORTED when not. Never silence: an
//                                       unanswered message is re-sent by the watch every ~5 s.
// 5000 replies from the watch are never answered (a reply to a reply loops).

/// The clock values a reply may need. Supplied by the platform on every call, so traces are reproducible.
public struct GarminClock: Equatable, Sendable {
    public var nowMs: Int64
    public var unixSeconds: Int64
    public var utcOffsetSeconds: Int32
    /// Unix seconds of the coming time-zone transition, nil when the zone has none.
    public var nextTransitionUnix: Int64?
    /// Unix seconds of the transition after that one, nil when none.
    public var followingTransitionUnix: Int64?

    public init(nowMs: Int64, unixSeconds: Int64, utcOffsetSeconds: Int32,
                nextTransitionUnix: Int64? = nil, followingTransitionUnix: Int64? = nil) {
        self.nowMs = nowMs
        self.unixSeconds = unixSeconds
        self.utcOffsetSeconds = utcOffsetSeconds
        self.nextTransitionUnix = nextTransitionUnix
        self.followingTransitionUnix = followingTransitionUnix
    }
}

public struct GarminSessionConfig: Equatable, Sendable {
    public var host: GarminHostIdentity
    public var capabilities: Set<GarminCapability>
    /// True only for the session that first pairs this watch with NOOP; it sends PAIR_COMPLETE and
    /// SETUP_WIZARD_COMPLETE once (both reversible state notices).
    public var firstPairing: Bool
    /// Send HOST_DID_ENTER_FOREGROUND / BACKGROUND (`GarminRadioPolicy.sendsHostLifecycleEvents`).
    public var sendsHostLifecycleEvents: Bool
    public var clientId: UInt64
    /// From the first write until the watch's capabilities arrive.
    public var handshakeTimeoutMs: Int64

    /// The capability bits NOOP advertises: file sync, device-initiated sync, time requests and the
    /// multi-link service, nothing that makes the watch send notification, weather, music or calendar
    /// traffic. Add a bit back only with a comment naming the model that refused to sync without it.
    public static let minimalCapabilities: Set<GarminCapability> = Set(GarminCapability.allCases)

    public init(host: GarminHostIdentity, capabilities: Set<GarminCapability> = GarminSessionConfig.minimalCapabilities,
                firstPairing: Bool, sendsHostLifecycleEvents: Bool,
                clientId: UInt64 = GarminMultiLink.defaultClientId, handshakeTimeoutMs: Int64 = 30_000) {
        self.host = host
        self.capabilities = capabilities
        self.firstPairing = firstPairing
        self.sendsHostLifecycleEvents = sendsHostLifecycleEvents
        self.clientId = clientId
        self.handshakeTimeoutMs = handshakeTimeoutMs
    }

    /// NOOP's identity in the 5024 reply. The strings name NOOP and the host platform, never Garmin
    /// Connect. 0xFFFF / 0xFFFFFFFF are "not a Garmin product / no unit number".
    public static func noopHost(platform: String, softwareVersion: UInt16) -> GarminHostIdentity {
        GarminHostIdentity(protocolVersion: 150, productNumber: 0xFFFF, unitNumber: 0xFFFF_FFFF,
                           softwareVersion: softwareVersion, maxPacketSize: 0xFFFF,
                           bluetoothName: "NOOP", manufacturer: "NOOP", model: platform)
    }
}

public enum GarminSessionPhase: Equatable, Sendable {
    case idle
    case closingAll
    case registering
    case awaitingDeviceInformation
    case handshaking
    case ready
    case failed(String)

    public var label: String {
        switch self {
        case .idle: return "idle"
        case .closingAll: return "closing-all"
        case .registering: return "registering"
        case .awaitingDeviceInformation: return "awaiting-device-information"
        case .handshaking: return "handshaking"
        case .ready: return "ready"
        case let .failed(reason): return "failed(\(reason))"
        }
    }
}

/// One diagnostic line. `rare` lines are always logged (state transitions, refusals, failures); the rest
/// are per-connect detail the platform shows only while a Test Centre domain is active.
public struct GarminSessionLog: Equatable, Sendable {
    public let rare: Bool
    public let text: String
}

public enum GarminSessionOutput: Equatable, Sendable {
    /// Bytes for the transport's write characteristic, in order.
    case write([UInt8])
    case log(GarminSessionLog)
    case phase(GarminSessionPhase)
    case battery(percent: Int)
    /// The watch's model and firmware from 5024, for the registry row and the UI.
    case watch(GarminDeviceInformation)
    /// PAIR_COMPLETE / SETUP_WIZARD_COMPLETE went out; the platform records that this watch is paired.
    case pairingEventsSent
    /// The watch announced new data (5037 / 5009). The history-sync layer decides whether to act.
    case syncAnnounced
}

public struct GarminSession {

    public let config: GarminSessionConfig
    public let transport: GarminTransportKind
    public private(set) var phase: GarminSessionPhase = .idle
    public private(set) var watch: GarminDeviceInformation?
    public private(set) var gfdiHandle: UInt8?
    /// Inbound GFDI messages that passed the CRC gate, and frames dropped by it (diagnostic).
    public private(set) var messagesIn = 0
    public private(set) var framesDropped = 0

    private var mtu: Int
    private var mlr: GarminMlrSession?
    private var decoder = GarminCOBSStreamDecoder()
    private var startedAtMs: Int64?
    private var handshakeDeadline: Int64?
    private var foreground = true
    private var lifecycleSent: Bool?
    private var pairingSent = false
    private var loggedUnknownTypes: Set<UInt16> = []
    private var out: [GarminSessionOutput] = []

    public init(config: GarminSessionConfig, transport: GarminTransportKind, mtu: Int) {
        self.config = config
        self.transport = transport
        self.mtu = max(23, mtu)
    }

    /// The earliest moment `tick` has work: the handshake deadline or an MLR retransmit / ack timer.
    public var nextDeadlineMs: Int64? {
        [handshakeDeadline, mlr?.nextDeadlineMs].compactMap { $0 }.min()
    }

    public mutating func setMtu(_ mtu: Int) {
        self.mtu = max(23, mtu)
        mlr?.setMtu(self.mtu)
    }

    /// Begin once the notify characteristic is subscribed. ML closes every stale handle first; V1 / V0
    /// have nothing to open, the watch speaks first.
    public mutating func start(_ clock: GarminClock) -> [GarminSessionOutput] {
        startedAtMs = clock.nowMs
        handshakeDeadline = clock.nowMs + config.handshakeTimeoutMs
        note(true, "session start transport=\(transport.rawValue) mtu=\(mtu) firstPairing=\(config.firstPairing)")
        if transport == .multiLinkV2 {
            setPhase(.closingAll)
            control(.closeAll)
        } else {
            setPhase(.awaitingDeviceInformation)
        }
        return flush()
    }

    /// The app moved between foreground and background. Sent to the watch only once the handshake is done,
    /// only when the state changed, and only if the policy keeps the lifecycle events.
    public mutating func setForeground(_ isForeground: Bool, _ clock: GarminClock) -> [GarminSessionOutput] {
        foreground = isForeground
        sendLifecycleIfNeeded(clock)
        return flush()
    }

    public mutating func tick(_ clock: GarminClock) -> [GarminSessionOutput] {
        if let deadline = handshakeDeadline, clock.nowMs >= deadline {
            handshakeDeadline = nil
            fail("handshake timed out in \(phase.label) after \(clock.nowMs - (startedAtMs ?? clock.nowMs)) ms", clock)
        }
        if var m = mlr {
            for packet in m.tick(nowMs: clock.nowMs) { out.append(.write(packet)) }
            mlr = m
        }
        return flush()
    }

    /// One notification from the notify characteristic.
    public mutating func receive(_ notification: [UInt8], _ clock: GarminClock) -> [GarminSessionOutput] {
        if case .failed = phase { return flush() }
        guard transport == .multiLinkV2 else {
            feed(notification, clock)
            return flush()
        }
        let reliable: Set<UInt8> = (mlr != nil && gfdiHandle != nil) ? [gfdiHandle!] : []
        switch GarminMultiLink.route(notification, reliableHandles: reliable) {
        case .empty:
            break
        case let .control(packet):
            handleControl(packet, clock)
        case let .reliable(_, packet):
            if var m = mlr {
                let received = m.receive(packet, nowMs: clock.nowMs)
                mlr = m
                for p in received.outbound { out.append(.write(p)) }
                if let data = received.data { feed(data, clock) }
            }
        case let .plain(handle, payload):
            if handle == gfdiHandle {
                feed(payload, clock)
            } else {
                note(false, "ignored \(payload.count) bytes on unregistered handle \(hex8(handle))")
            }
        }
        return flush()
    }

    // MARK: - ML control

    private mutating func handleControl(_ packet: [UInt8], _ clock: GarminClock) {
        guard let response = GarminMultiLink.parseControl(packet, clientId: config.clientId) else {
            note(false, "ignored control frame \(garminHex(packet))")
            return
        }
        switch response {
        case .allClosed:
            if phase == .closingAll {
                note(false, "all handles closed")
            } else {
                note(true, "watch closed all handles; re-registering GFDI")
                dropGfdi(clock)
            }
            setPhase(.registering)
            control(.register(.gfdi, reliable: false))
        case let .handleClosed(service, handle, status):
            if handle == gfdiHandle, service == GarminService.gfdi.rawValue {
                note(true, "watch closed GFDI handle \(hex8(handle)) status=\(status); re-registering")
                dropGfdi(clock)
                setPhase(.registering)
                control(.register(.gfdi, reliable: false))
            } else {
                note(false, "handle \(hex8(handle)) service=\(service) closed status=\(status)")
            }
        case let .registered(service, status, handle, reliable):
            guard service == GarminService.gfdi.rawValue, phase == .registering else {
                note(false, "registration for service \(service) status=\(status) ignored")
                return
            }
            guard status == 0 else {
                // The watch refused: another app holds GFDI, or the service is unavailable. One status is
                // reportedly ALREADY_IN_USE; its number is unconfirmed, so the raw value is what is logged.
                note(true, "GFDI registration refused status=\(status)")
                fail("registration refused status=\(status)", clock)
                return
            }
            gfdiHandle = handle
            if reliable { mlr = GarminMlrSession(handle: handle, mtu: mtu) }
            note(true, "GFDI registered handle=\(hex8(handle)) reliable=\(reliable) status=0")
            setPhase(.awaitingDeviceInformation)
        }
    }

    /// The watch dropped GFDI: forget the handle and run the handshake again on the next one, under a
    /// fresh deadline. The pairing events are not repeated; the lifecycle state is re-sent once ready.
    private mutating func dropGfdi(_ clock: GarminClock) {
        gfdiHandle = nil
        mlr = nil
        decoder.reset()
        lifecycleSent = nil
        startedAtMs = clock.nowMs
        handshakeDeadline = clock.nowMs + config.handshakeTimeoutMs
    }

    // MARK: - GFDI

    private mutating func feed(_ bytes: [UInt8], _ clock: GarminClock) {
        let droppedBefore = decoder.droppedFrames
        let messages = decoder.feed(bytes)
        if decoder.droppedFrames > droppedBefore {
            framesDropped += decoder.droppedFrames - droppedBefore
            note(true, "dropped \(decoder.droppedFrames - droppedBefore) malformed COBS frame(s)")
        }
        for message in messages {
            switch GarminGfdi.decode(message) {
            case let .failure(error):
                framesDropped += 1
                note(true, "dropped GFDI frame: \(Self.describe(error))")
            case let .success(frame):
                messagesIn += 1
                handle(GarminInbound.decode(frame), type: frame.type, clock)
            }
        }
    }

    private mutating func handle(_ message: GarminInbound, type: UInt16, _ clock: GarminClock) {
        switch message {
        case let .deviceInformation(info):
            if watch == nil {
                note(true, "watch \(info.bluetoothName) model=\(info.deviceModel) product=\(info.productNumber) "
                     + "fw=\(firmware(info.softwareVersion)) protocol=\(info.protocolVersion) maxPacket=\(info.maxPacketSize)")
                out.append(.watch(info))
            } else {
                note(false, "5024 again (the watch re-sends until answered)")
            }
            watch = info
            send(.status(of: type, .ack), clock)
            send(.deviceInformationReply(config.host, protocolFlags: info.protocolVersion / 100 == 1 ? 1 : 0), clock)
            if phase == .awaitingDeviceInformation { setPhase(.handshaking) }

        case let .configuration(bitmap):
            let bits = GarminCapabilityBitmap.indices(bitmap)
            note(true, "watch capabilities: \(bits.count) bits; NOOP advertises "
                 + config.capabilities.map(\.rawValue).sorted().map(String.init).joined(separator: ","))
            send(.status(of: type, .ack), clock)
            send(.configuration(config.capabilities), clock)
            if phase != .ready { completeHandshake(clock) }

        case let .authNegotiation(unknown, flags):
            note(false, "5101 auth negotiation echoed unknown=\(unknown) flags=\(flags)")
            send(.authNegotiationReply(unknown: unknown, flags: flags), clock)

        case let .currentTimeRequest(reference):
            note(false, "5052 time request ref=\(reference) -> unix=\(clock.unixSeconds) offset=\(clock.utcOffsetSeconds)")
            send(.currentTimeReply(referenceId: reference,
                                   garminTime: GarminGfdi.garminTime(fromUnix: clock.unixSeconds),
                                   utcOffsetSeconds: clock.utcOffsetSeconds,
                                   followingTransition: clock.followingTransitionUnix.map(GarminGfdi.garminTime(fromUnix:)) ?? 0,
                                   nextTransition: clock.nextTransitionUnix.map(GarminGfdi.garminTime(fromUnix:)) ?? 0), clock)

        case let .batteryStatus(_, _, percent):
            note(false, "5023 battery \(percent)%")
            send(.status(of: type, .ack), clock)
            if percent <= 100 { out.append(.battery(percent: Int(percent))) }

        case .synchronization, .fileAvailable:
            note(false, "\(type) watch has new data")
            send(.status(of: type, .ack), clock)
            out.append(.syncAnnounced)

        case let .protobufRequest(chunk):
            note(true, "refused watch request \(Self.smartServiceName(chunk)) id=\(chunk.requestId)")
            send(.protobufStatus(of: .request, requestId: chunk.requestId, dataOffset: chunk.dataOffset,
                                 discard: true, code: Self.unknownRequestId), clock)

        case let .protobufResponse(chunk):
            // NOOP has no protobuf request outstanding in this layer, so any response is unsolicited.
            note(false, "discarded unsolicited protobuf response id=\(chunk.requestId)")
            send(.protobufStatus(of: .response, requestId: chunk.requestId, dataOffset: chunk.dataOffset,
                                 discard: true, code: Self.unknownRequestId), clock)

        case let .status(original, status, _):
            if status == GarminStatus.ack.rawValue {
                note(false, "watch acked \(original)")
            } else {
                note(true, "watch answered \(original) with status \(status)")
            }

        case .fileTransferData, .unknown:
            if loggedUnknownTypes.insert(type).inserted {
                note(true, Self.refusedTypeNames[type].map { "refused \($0) (\(type))" } ?? "unsupported message \(type)")
            }
            send(.status(of: type, .unsupported), clock)
        }
    }

    private mutating func completeHandshake(_ clock: GarminClock) {
        send(.systemEvent(.syncReady, value: 0), clock)
        if config.firstPairing && !pairingSent {
            send(.systemEvent(.pairComplete, value: 0), clock)
            send(.systemEvent(.setupWizardComplete, value: 0), clock)
            pairingSent = true
            out.append(.pairingEventsSent)
        }
        handshakeDeadline = nil
        setPhase(.ready)
        note(true, "handshake complete in \(clock.nowMs - (startedAtMs ?? clock.nowMs)) ms")
        sendLifecycleIfNeeded(clock)
    }

    private mutating func sendLifecycleIfNeeded(_ clock: GarminClock) {
        guard config.sendsHostLifecycleEvents, phase == .ready, lifecycleSent != foreground else { return }
        lifecycleSent = foreground
        send(.systemEvent(foreground ? .hostDidEnterForeground : .hostDidEnterBackground, value: 0), clock)
    }

    // MARK: - Output plumbing

    private mutating func send(_ message: GarminOutbound, _ clock: GarminClock) {
        let frame = message.frame()
        note(false, "-> \(Self.describe(message))")
        switch transport {
        case .multiLinkV2:
            guard let handle = gfdiHandle else {
                note(true, "no GFDI handle; \(Self.describe(message)) not sent")
                return
            }
            if var m = mlr {
                for p in m.send(frame, nowMs: clock.nowMs) { out.append(.write(p)) }
                mlr = m
            } else {
                for p in GarminMultiLink.plainPackets(frame, handle: handle, mtu: mtu) { out.append(.write(p)) }
            }
        case .v1, .v0:
            let size = GarminMultiLink.plainPayloadSize(mtu: mtu)
            var start = 0
            while start < frame.bytes.count {
                let end = min(start + size, frame.bytes.count)
                out.append(.write(Array(frame.bytes[start..<end])))
                start = end
            }
        }
    }

    private mutating func control(_ request: GarminMultiLink.ControlRequest) {
        note(false, "-> ML \(Self.describe(request))")
        out.append(.write(request.encode(clientId: config.clientId)))
    }

    private mutating func fail(_ reason: String, _ clock: GarminClock) {
        handshakeDeadline = nil
        note(true, "session failed: \(reason)")
        setPhase(.failed(reason))
    }

    private mutating func setPhase(_ p: GarminSessionPhase) {
        guard p != phase else { return }
        phase = p
        out.append(.phase(p))
    }

    private mutating func note(_ rare: Bool, _ text: String) {
        out.append(.log(GarminSessionLog(rare: rare, text: text)))
    }

    private mutating func flush() -> [GarminSessionOutput] {
        defer { out.removeAll() }
        return out
    }

    // MARK: - Names (diagnostics only)

    /// Protobuf status code for a declined request: the watch drops it and does not wait (Gadgetbridge's
    /// UNKNOWN_REQUEST_ID with chunk status DISCARDED).
    static let unknownRequestId: UInt8 = 100

    /// GFDI ids that are internet-proxy or phone-feature requests NOOP declines, named for the log.
    static let refusedTypeNames: [UInt16: String] = [
        5014: "weather request",
        5016: "live-track request",
        5019: "ephemeris (AGPS) request",
        5028: "ephemeris EPO (AGPS) request",
        5034: "notification control",
        5039: "find-my-phone request",
        5041: "music control",
    ]

    /// The `Smart` service a watch-initiated 5043 targets, from its first field tag. Field numbers are
    /// Gadgetbridge facts; only the ones a watch is known to initiate are named.
    static func smartServiceName(_ chunk: GarminProtobufChunk) -> String {
        guard chunk.dataOffset == 0, let field = firstFieldNumber(chunk.data) else { return "(continuation chunk)" }
        let names: [Int: String] = [
            2: "Connect IQ HTTP", 7: "data transfer", 8: "device status",
            13: "core (HTTP proxy: weather / AGPS / OAuth)", 43: "file access",
        ]
        return "\(names[field] ?? "service") (Smart field \(field))"
    }

    private static func firstFieldNumber(_ data: [UInt8]) -> Int? {
        var value = 0
        var shift = 0
        for byte in data.prefix(5) {
            value |= Int(byte & 0x7F) << shift
            if byte & 0x80 == 0 { return value >> 3 }
            shift += 7
        }
        return nil
    }

    static func describe(_ m: GarminOutbound) -> String {
        switch m {
        case let .status(original, status): return "status \(original) \(status)"
        case .deviceInformationReply: return "device information reply"
        case let .configuration(c): return "configuration \(c.count) bits"
        case .authNegotiationReply: return "auth negotiation reply"
        case .currentTimeReply: return "current time reply"
        case let .systemEvent(e, _): return "system event \(e.rawValue)"
        case .filter: return "filter"
        case .downloadRequest: return "download request"
        case .fileTransferDataReply: return "file transfer reply"
        case .supportedFileTypesRequest: return "supported file types request"
        case .fileAccessRequest: return "file access request"
        case let .protobufStatus(direction, id, _, discard, code):
            return "protobuf status \(direction == .request ? "request" : "response") id=\(id) discard=\(discard) code=\(code)"
        }
    }

    static func describe(_ r: GarminMultiLink.ControlRequest) -> String {
        switch r {
        case let .register(service, reliable): return "register service=\(service.rawValue) reliable=\(reliable)"
        case let .closeHandle(service, handle): return "close handle service=\(service.rawValue) handle=\(handle)"
        case .closeAll: return "close all"
        }
    }

    static func describe(_ e: GarminGfdi.DecodeError) -> String {
        switch e {
        case .tooShort: return "too short"
        case let .lengthMismatch(declared, actual): return "length \(declared) != \(actual)"
        case let .crcMismatch(expected, actual): return "crc \(expected) != \(actual)"
        }
    }

    private func firmware(_ v: UInt16) -> String {
        let minor = v % 100
        return "\(v / 100).\(minor < 10 ? "0" : "")\(minor)"
    }

    private func hex8(_ v: UInt8) -> String {
        let s = String(v, radix: 16)
        return "0x" + (s.count < 2 ? "0" : "") + s
    }
}
