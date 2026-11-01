import Foundation

// MARK: - Multi-link (ML) transport: control frames, plain data framing, inbound routing
//
// Wire facts (Gadgetbridge, see ATTRIBUTION.md). The V2 ML service multiplexes several logical services
// over one notify/write characteristic pair. Byte 0 of every packet is a handle:
//   - handle 0x00 carries 13-byte control frames
//       [0x00][type u8][clientId u64][service u16][param u8]
//     REGISTER 0 / 1 (request / response), CLOSE_HANDLE 2 / 3, CLOSE_ALL 5 / 6. The REGISTER param is
//     0 for plain ML or 2 to ask for the reliable (MLR) variant; the CLOSE_HANDLE param is the handle.
//   - any other handle carries data for the service the watch assigned it: `[handle][payload…]`.
// A handle with bit 7 set MAY be an MLR packet, but plain handles can also have bit 7 set (Gadgetbridge
// #5476), so only handles registered as reliable are routed to MLR.

/// The logical ML services NOOP knows by number. Realtime channel payloads are decoded elsewhere.
public enum GarminService: UInt16, CaseIterable, Sendable {
    case gfdi = 1
    case registration = 4
    case realtimeHeartRate = 6
    case realtimeSteps = 7
    case realtimeCalories = 8
    case realtimeFloors = 9
    case realtimeIntensity = 10
    case realtimeHrv = 12
    case realtimeStress = 13
    case realtimeAccelerometer = 16
    case realtimeSpo2 = 19
    case realtimeBodyBattery = 20
    case realtimeRespiration = 21
    case fileTransfer2 = 0x2018
    case fileTransfer4 = 0x4018
    case fileTransfer6 = 0x6018
    case fileTransferA = 0xA018
    case fileTransferC = 0xC018
    case fileTransferE = 0xE018
}

public enum GarminMultiLink {

    /// The client id NOOP puts in every control frame; responses carrying any other id belong to another
    /// app on the phone and are ignored. Gadgetbridge uses 2 and that is the only value observed working
    /// with a watch, so NOOP starts from it. HARDWARE-GATED: whether the id matters for coexisting with
    /// Garmin Connect is an open question for the GarminSource issue.
    public static let defaultClientId: UInt64 = 2

    /// The handle reserved for control frames.
    public static let controlHandle: UInt8 = 0x00

    /// Control frame type byte.
    public enum ControlType: UInt8, Sendable {
        case registerRequest = 0
        case registerResponse = 1
        case closeHandleRequest = 2
        case closeHandleResponse = 3
        case closeAllRequest = 5
        case closeAllResponse = 6
    }

    /// The ML control requests NOOP may send. There is no raw-bytes case.
    public enum ControlRequest: Equatable, Sendable {
        case register(GarminService, reliable: Bool)
        case closeHandle(GarminService, handle: UInt8)
        case closeAll

        /// The 13-byte control frame, written as-is to the ML write characteristic.
        public func encode(clientId: UInt64 = GarminMultiLink.defaultClientId) -> [UInt8] {
            var w = GarminByteWriter()
            w.u8(GarminMultiLink.controlHandle)
            switch self {
            case let .register(service, reliable):
                w.u8(ControlType.registerRequest.rawValue)
                w.u64(clientId)
                w.u16(service.rawValue)
                w.u8(reliable ? 2 : 0)
            case let .closeHandle(service, handle):
                w.u8(ControlType.closeHandleRequest.rawValue)
                w.u64(clientId)
                w.u16(service.rawValue)
                w.u8(handle)
            case .closeAll:
                w.u8(ControlType.closeAllRequest.rawValue)
                w.u64(clientId)
                w.u16(0)
                w.u8(0)
            }
            return w.bytes
        }
    }

    /// A control response from the watch. `service` stays raw so an unknown number is still reported.
    public enum ControlResponse: Equatable, Sendable {
        /// `status == 0` is success; any other value is a refusal (one is reportedly ALREADY_IN_USE, number
        /// unconfirmed). `handle` and `reliable` are meaningful only on success.
        case registered(service: UInt16, status: UInt8, handle: UInt8, reliable: Bool)
        case handleClosed(service: UInt16, handle: UInt8, status: UInt8)
        case allClosed
    }

    /// Parse a control packet (byte 0 is the 0x00 control handle). Returns nil for a short packet, a
    /// request type echoed back, an unknown type, or a response addressed to another client id.
    public static func parseControl(_ packet: [UInt8],
                                    clientId: UInt64 = GarminMultiLink.defaultClientId) -> ControlResponse? {
        var r = GarminByteReader(packet)
        guard r.u8() == controlHandle,
              let rawType = r.u8(),
              let incomingClient = r.u64(),
              incomingClient == clientId,
              let type = ControlType(rawValue: rawType) else { return nil }
        switch type {
        case .registerResponse:
            guard let service = r.u16(), let status = r.u8() else { return nil }
            let handle = r.u8() ?? 0
            let reliable = (r.u8() ?? 0) != 0
            return .registered(service: service, status: status, handle: handle, reliable: reliable)
        case .closeHandleResponse:
            guard let service = r.u16(), let handle = r.u8(), let status = r.u8() else { return nil }
            return .handleClosed(service: service, handle: handle, status: status)
        case .closeAllResponse:
            return .allClosed
        case .registerRequest, .closeHandleRequest, .closeAllRequest:
            return nil
        }
    }

    /// Payload bytes per plain-ML write for a given ATT MTU: MTU − 3 (ATT header) − 1 (handle byte).
    public static func plainPayloadSize(mtu: Int) -> Int { max(1, mtu - 4) }

    /// Split an outbound frame into plain-ML writes `[handle][≤ MTU−4 bytes]`.
    public static func plainPackets(_ frame: GarminOutboundFrame, handle: UInt8, mtu: Int) -> [[UInt8]] {
        let chunk = plainPayloadSize(mtu: mtu)
        var packets: [[UInt8]] = []
        var start = 0
        let bytes = frame.bytes
        while start < bytes.count {
            let end = min(start + chunk, bytes.count)
            packets.append([handle] + bytes[start..<end])
            start = end
        }
        return packets
    }

    /// Where an inbound ML notification goes.
    public enum Route: Equatable, Sendable {
        case control([UInt8])
        /// An MLR packet for the reliable handle `handle` (the value the watch registered). Pass the whole
        /// packet to that handle's `GarminMlrSession`.
        case reliable(handle: UInt8, packet: [UInt8])
        /// Plain data for `handle`, with the handle byte stripped.
        case plain(handle: UInt8, payload: [UInt8])
        case empty
    }

    /// Route a notification. `reliableHandles` are the handles the watch registered with `reliable = true`.
    /// A bit-7 packet whose 3-bit handle matches none of them falls through to plain routing (#5476).
    public static func route(_ notification: [UInt8], reliableHandles: Set<UInt8>) -> Route {
        guard let first = notification.first else { return .empty }
        if first & 0x80 != 0 {
            let packed = (first & 0x70) >> 4
            if let handle = reliableHandles.sorted().first(where: { $0 & 0x07 == packed }) {
                return .reliable(handle: handle, packet: notification)
            }
        }
        if first == controlHandle { return .control(notification) }
        return .plain(handle: first, payload: Array(notification.dropFirst()))
    }
}
