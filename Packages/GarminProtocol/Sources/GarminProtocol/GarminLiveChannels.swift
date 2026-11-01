import Foundation

// MARK: - Realtime channel handles: register to start, close the handle to stop
//
// The platform decides WHICH channels should be open (`GarminRadioPolicy` + its 30 s linger) and hands the
// set to `GarminSession.setLiveChannels`. This table turns that set into ML control frames and keeps one
// state per service, so a channel is never registered twice and every open handle is closed by handle:
//
//   (none) --register--> registering --REGISTER_RESP ok--> open(h) --CLOSE_HANDLE--> closing(h) --RESP--> (none)
//
// A registration that comes back after the demand ended is closed at once; one the watch refuses is not
// retried until the demand for it goes away and comes back (no retry loop against a watch that says no).
// A handle the watch closes by itself is forgotten; the next demand change opens it again. Only services
// in `GarminRealtime.decodableServices` can be registered, so the accelerometer and the undecoded channels
// cannot be opened from here whatever the caller asks for.

enum GarminLiveChannelEvent: Equatable {
    case control(GarminMultiLink.ControlRequest)
    case log(rare: Bool, String)
    case opened(GarminService, handle: UInt8)
    case closed(GarminService)
    case reading(GarminService, GarminRealtimeReading)
}

struct GarminLiveChannelTable {

    enum State: Equatable {
        case registering
        case open(UInt8)
        case closing(UInt8)
    }

    private(set) var desired: Set<GarminService> = []
    private(set) var states: [GarminService: State] = [:]
    private var refused: Set<GarminService> = []
    private var loggedUnsupported: Set<GarminService> = []
    /// Distinct candidate values already logged this session, per field. Capped so a counter-like field
    /// cannot flood the always-on log.
    private var candidates: [String: Set<String>] = [:]
    static let candidateLogCap = 4

    var openServices: Set<GarminService> {
        Set(states.compactMap { service, state in if case .open = state { return service } else { return nil } })
    }

    /// Record the wanted set. Nothing is sent unless `active` (the session is ready on multi-link).
    mutating func setDesired(_ services: Set<GarminService>, active: Bool) -> [GarminLiveChannelEvent] {
        var events: [GarminLiveChannelEvent] = []
        var wanted: Set<GarminService> = []
        for service in services.sorted(by: { $0.rawValue < $1.rawValue }) {
            if GarminRealtime.decodableServices.contains(service) {
                wanted.insert(service)
            } else if loggedUnsupported.insert(service).inserted {
                events.append(.log(rare: true, "live channel \(service.rawValue) is not supported; not opened"))
            }
        }
        // Demand that went away clears a refusal, so the next viewer may try again.
        refused.subtract(desired.subtracting(wanted))
        desired = wanted
        if active { events += apply() }
        return events
    }

    /// Bring the handles in line with `desired`. Closes in service order, then registers in service order,
    /// so both platforms emit the same frames.
    mutating func apply() -> [GarminLiveChannelEvent] {
        var events: [GarminLiveChannelEvent] = []
        for service in states.keys.sorted(by: { $0.rawValue < $1.rawValue }) where !desired.contains(service) {
            if case let .open(handle) = states[service] {
                states[service] = .closing(handle)
                events.append(.log(rare: false, "live channel \(service.rawValue) closing handle \(hex8(handle))"))
                events.append(.control(.closeHandle(service, handle: handle)))
            }
            // .registering: closed when its response lands. .closing: already on its way.
        }
        for service in desired.sorted(by: { $0.rawValue < $1.rawValue })
        where states[service] == nil && !refused.contains(service) {
            states[service] = .registering
            events.append(.log(rare: false, "live channel \(service.rawValue) registering"))
            events.append(.control(.register(service, reliable: false)))
        }
        return events
    }

    mutating func registered(service raw: UInt16, status: UInt8, handle: UInt8, reliable: Bool) -> [GarminLiveChannelEvent] {
        guard let service = GarminService(rawValue: raw), states[service] == .registering else {
            // Not asked for. A success means a handle is open on the watch that nothing will close.
            guard status == 0, let service = GarminService(rawValue: raw) else {
                return [.log(rare: false, "registration for service \(raw) status=\(status) ignored")]
            }
            if states[service] == nil { states[service] = .closing(handle) }
            return [.log(rare: true, "unrequested handle \(hex8(handle)) for service \(raw); closing it"),
                    .control(.closeHandle(service, handle: handle))]
        }
        guard status == 0 else {
            states[service] = nil
            refused.insert(service)
            return [.log(rare: true, "live channel \(raw) refused status=\(status); not retried until asked again")]
        }
        guard !reliable, desired.contains(service) else {
            // Asked plain and got reliable (payloads would be MLR-framed), or the demand ended meanwhile.
            states[service] = .closing(handle)
            if reliable { refused.insert(service) }
            return [.log(rare: reliable, reliable
                         ? "live channel \(raw) came back reliable; closing handle \(hex8(handle))"
                         : "live channel \(raw) no longer wanted; closing handle \(hex8(handle))"),
                    .control(.closeHandle(service, handle: handle))]
        }
        states[service] = .open(handle)
        return [.log(rare: true, "live channel \(raw) open handle=\(hex8(handle))"), .opened(service, handle: handle)]
    }

    /// The watch answered CLOSE_HANDLE (ours or its own). Returns nil when the handle is not a live channel.
    mutating func handleClosed(handle: UInt8, status: UInt8) -> [GarminLiveChannelEvent]? {
        guard let (service, state) = states.first(where: { $0.value == .open(handle) || $0.value == .closing(handle) }) else {
            return nil
        }
        states[service] = nil
        let byWatch = state == .open(handle)
        var events: [GarminLiveChannelEvent] = [
            .log(rare: true, byWatch
                 ? "watch closed live channel \(service.rawValue) handle \(hex8(handle)) status=\(status)"
                 : "live channel \(service.rawValue) closed handle=\(hex8(handle)) status=\(status)"),
            .closed(service),
        ]
        if !byWatch, desired.contains(service) {
            // Wanted again while it was closing: open a fresh handle.
            states[service] = .registering
            events.append(.control(.register(service, reliable: false)))
        }
        return events
    }

    /// CLOSE_ALL_RESP or a lost GFDI registration: every handle is gone on the watch.
    mutating func allClosed() -> [GarminLiveChannelEvent] {
        var events: [GarminLiveChannelEvent] = []
        for service in states.keys.sorted(by: { $0.rawValue < $1.rawValue }) {
            if case .open = states[service] { events.append(.closed(service)) }
        }
        states.removeAll()
        return events
    }

    /// Data on a handle. nil when the handle is not a live channel (the session then logs it as stray).
    mutating func receive(handle: UInt8, payload: [UInt8]) -> [GarminLiveChannelEvent]? {
        guard let service = states.first(where: { $0.value == .open(handle) || $0.value == .closing(handle) })?.key else {
            return nil
        }
        guard let reading = GarminRealtime.decode(service: service, payload: payload) else {
            return [.log(rare: true, "live channel \(service.rawValue) payload too short: \(garminHex(payload))")]
        }
        var events: [GarminLiveChannelEvent] = [
            .log(rare: false, "live \(service.rawValue) [\(garminHex(payload))] \(GarminRealtime.describe(reading))"),
        ]
        switch reading {
        case let .heartRate(_, _, type, trailing):
            events += candidate("hr.type", "\(type)", service)
            events += candidate("hr.trailing", garminHex(trailing), service)
        case let .rrInterval(_, unknown):
            if let unknown { events += candidate("rr.unknown", "\(unknown)", service) }
        default:
            break
        }
        events.append(.reading(service, reading))
        return events
    }

    private mutating func candidate(_ field: String, _ value: String, _ service: GarminService) -> [GarminLiveChannelEvent] {
        var seen = candidates[field, default: []]
        guard seen.count < Self.candidateLogCap, seen.insert(value).inserted else { return [] }
        candidates[field] = seen
        return [.log(rare: true, "candidate \(field)=\(value) (service \(service.rawValue); undecoded, not used)")]
    }

    private func hex8(_ v: UInt8) -> String {
        let s = String(v, radix: 16)
        return "0x" + (s.count < 2 ? "0" : "") + s
    }
}
