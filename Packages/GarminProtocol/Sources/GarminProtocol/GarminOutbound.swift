import Foundation

// MARK: - The send allowlist
//
// `GarminOutbound` is the ONLY way to produce GFDI bytes for the watch: `GarminGfdi.encode` is internal,
// and the transport (`GarminMultiLink.plainPackets`, `GarminMlrSession.send`) accepts only a
// `GarminOutboundFrame`, whose initializer is internal. So a message that has no case here cannot be sent.
//
// Deliberately ABSENT, and not to be added (see the BLE safety contract in docs/CONTRIBUTING.md):
//   - system events FACTORY_RESET (2), DEVICE_SOFTWARE_UPDATE (10), DEVICE_DISCONNECT (11),
//     TRIGGER_REBOOT (17): `GarminSafeSystemEvent` has no case for them;
//   - DELETE_FILE 5006, SET_FILE_FLAG 5008 (archive/erase), UPLOAD_REQUEST 5003, CREATE_FILE 5005;
//   - FileAccess Delete / ModifyFlags (`GarminFileAccessRequest` lists and pulls only);
//   - the key-exchange ids 5102…5112;
//   - any case taking a caller-supplied type id or raw payload.
// `GarminOutboundAllowlistTests` (and the Kotlin twin) enumerate every case and hold this list.
//
// Payload layouts are Gadgetbridge wire facts (ATTRIBUTION.md); field names are NOOP's.

/// A COBS-delimited GFDI message ready for the multi-link transport. Only `GarminOutbound` makes one.
public struct GarminOutboundFrame: Equatable, Sendable {
    public let bytes: [UInt8]
    init(bytes: [UInt8]) { self.bytes = bytes }
}

/// The system events NOOP may send: session and pairing state notices only. Raw values are the wire
/// ordinals; the gaps (2, 10, 11, 17) are the destructive events, which have no case.
public enum GarminSafeSystemEvent: UInt8, CaseIterable, Sendable {
    case syncComplete = 0
    case syncFail = 1
    case pairStart = 3
    case pairComplete = 4
    case pairFail = 5
    case hostDidEnterForeground = 6
    case hostDidEnterBackground = 7
    case syncReady = 8
    case newDownloadAvailable = 9
    case tutorialComplete = 12
    case setupWizardStart = 13
    case setupWizardComplete = 14
    case setupWizardSkipped = 15
    case timeUpdated = 16
}

/// 5007 filter selector sent before a legacy directory download.
public enum GarminFilterType: UInt8, CaseIterable, Sendable {
    case allFiles = 0
    case `default` = 1
    case onlyNew = 3
}

/// 5002 request type.
public enum GarminDownloadKind: UInt8, CaseIterable, Sendable {
    case `continue` = 0
    case new = 1
}

/// Capabilities NOOP may advertise in 5050, by bit index in Garmin's capability bitmap. The set is kept
/// small on purpose: a capability the watch thinks the phone has (notifications, weather, music…) makes
/// it send traffic the phone must then answer. Add one back only with the model that needs it named.
public enum GarminCapability: Int, CaseIterable, Sendable {
    case connectMobileFitLink = 0
    case sync = 3
    case deviceInitiatesSync = 4
    case hostInitiatedSyncRequests = 5
    case garminDeviceInfoFileType = 55
    case currentTimeRequestSupport = 71
    case multiLinkService = 76
    case sync2 = 90

    /// Bitmap length Garmin Connect-era watches expect: 129 known capability bits → 17 bytes.
    public static let bitmapByteCount = 17

    public static func bitmap(_ capabilities: Set<GarminCapability>) -> [UInt8] {
        var out = [UInt8](repeating: 0, count: bitmapByteCount)
        for c in capabilities { out[c.rawValue / 8] |= 1 << (c.rawValue % 8) }
        return out
    }
}

/// What the phone tells the watch about itself in the 5024 reply. NOOP identifies as itself, never as
/// Garmin Connect; the numeric fields are chosen by the source layer.
public struct GarminHostIdentity: Equatable, Sendable {
    public var protocolVersion: UInt16
    public var productNumber: UInt16
    public var unitNumber: UInt32
    public var softwareVersion: UInt16
    public var maxPacketSize: UInt16
    public var bluetoothName: String
    public var manufacturer: String
    public var model: String

    public init(protocolVersion: UInt16, productNumber: UInt16, unitNumber: UInt32, softwareVersion: UInt16,
                maxPacketSize: UInt16, bluetoothName: String, manufacturer: String, model: String) {
        self.protocolVersion = protocolVersion
        self.productNumber = productNumber
        self.unitNumber = unitNumber
        self.softwareVersion = softwareVersion
        self.maxPacketSize = maxPacketSize
        self.bluetoothName = bluetoothName
        self.manufacturer = manufacturer
        self.model = model
    }
}

public enum GarminOutbound: Equatable, Sendable {
    /// 5000 generic reply to an inbound message (ACK, or UNSUPPORTED for something NOOP does not handle).
    case status(of: UInt16, GarminStatus)
    /// 5000 reply to the watch's 5024. `protocolFlags` is 1 when the watch's protocol version is 1xx.
    case deviceInformationReply(GarminHostIdentity, protocolFlags: UInt8)
    /// 5050 NOOP's capability bitmap.
    case configuration(Set<GarminCapability>)
    /// 5000 reply to 5101, echoing the watch's values with the "guess OK" result (no encryption).
    case authNegotiationReply(unknown: UInt8, flags: UInt32)
    /// 5000 reply to 5052: Garmin time, UTC offset in seconds, then the Garmin times of the next zone
    /// transition after the coming one (`followingTransition`) and of the coming one (`nextTransition`),
    /// in that wire order; 0 when the zone has none.
    case currentTimeReply(referenceId: UInt32, garminTime: UInt32, utcOffsetSeconds: Int32,
                          followingTransition: UInt32, nextTransition: UInt32)
    /// 5030 with a one-byte value (0 for every event NOOP sends today).
    case systemEvent(GarminSafeSystemEvent, value: UInt8)
    /// 5007.
    case filter(GarminFilterType)
    /// 5002 read a file by index (index 0 is the directory).
    case downloadRequest(fileIndex: UInt16, dataOffset: UInt32, kind: GarminDownloadKind, crcSeed: UInt16,
                         dataSize: UInt32, flags: UInt8)
    /// 5000 reply to a 5004 chunk: transfer status (0 OK) and the next offset expected.
    case fileTransferDataReply(transferStatus: UInt8, nextOffset: UInt32)
    /// 5031 ask which file types the watch supports.
    case supportedFileTypesRequest
    /// 5043 a read-only FileAccess request, sent as one chunk.
    case fileAccessRequest(requestId: UInt16, GarminFileAccessRequest)
    /// 5000 reply to an inbound 5043/5044 chunk. `discard: true` with a non-zero `code` declines it
    /// (how NOOP refuses HTTP / weather / AGPS proxy requests).
    case protobufStatus(of: GarminProtobufDirection, requestId: UInt16, dataOffset: UInt32, discard: Bool, code: UInt8)

    /// One tag per case, for the allowlist test's exhaustive enumeration.
    public enum Kind: CaseIterable, Sendable {
        case status, deviceInformationReply, configuration, authNegotiationReply, currentTimeReply,
             systemEvent, filter, downloadRequest, fileTransferDataReply, supportedFileTypesRequest,
             fileAccessRequest, protobufStatus
    }

    public var kind: Kind {
        switch self {
        case .status: return .status
        case .deviceInformationReply: return .deviceInformationReply
        case .configuration: return .configuration
        case .authNegotiationReply: return .authNegotiationReply
        case .currentTimeReply: return .currentTimeReply
        case .systemEvent: return .systemEvent
        case .filter: return .filter
        case .downloadRequest: return .downloadRequest
        case .fileTransferDataReply: return .fileTransferDataReply
        case .supportedFileTypesRequest: return .supportedFileTypesRequest
        case .fileAccessRequest: return .fileAccessRequest
        case .protobufStatus: return .protobufStatus
        }
    }

    /// The GFDI message (len, type, payload, CRC), before COBS.
    public func message() -> [UInt8] {
        let (type, payload) = typeAndPayload()
        return GarminGfdi.encode(type: type.rawValue, payload: payload)
    }

    /// The COBS-delimited frame for the transport.
    public func frame() -> GarminOutboundFrame {
        GarminOutboundFrame(bytes: GarminCOBS.encode(message()))
    }

    private func typeAndPayload() -> (GarminMessageType, [UInt8]) {
        var w = GarminByteWriter()
        switch self {
        case let .status(original, status):
            w.u16(original); w.u8(status.rawValue)
            return (.response, w.bytes)
        case let .deviceInformationReply(host, flags):
            w.u16(GarminMessageType.deviceInformation.rawValue); w.u8(GarminStatus.ack.rawValue)
            w.u16(host.protocolVersion); w.u16(host.productNumber); w.u32(host.unitNumber)
            w.u16(host.softwareVersion); w.u16(host.maxPacketSize)
            w.string8(host.bluetoothName); w.string8(host.manufacturer); w.string8(host.model)
            w.u8(flags)
            return (.response, w.bytes)
        case let .configuration(capabilities):
            let bitmap = GarminCapability.bitmap(capabilities)
            w.u8(UInt8(bitmap.count)); w.append(bitmap)
            return (.configuration, w.bytes)
        case let .authNegotiationReply(unknown, flags):
            w.u16(GarminMessageType.authNegotiation.rawValue); w.u8(GarminStatus.ack.rawValue)
            w.u8(0) // auth result: "guess OK"
            w.u8(unknown); w.u32(flags)
            return (.response, w.bytes)
        case let .currentTimeReply(reference, time, offset, following, next):
            w.u16(GarminMessageType.currentTimeRequest.rawValue); w.u8(GarminStatus.ack.rawValue)
            w.u32(reference); w.u32(time); w.u32(UInt32(bitPattern: offset)); w.u32(following); w.u32(next)
            return (.response, w.bytes)
        case let .systemEvent(event, value):
            w.u8(event.rawValue); w.u8(value)
            return (.systemEvent, w.bytes)
        case let .filter(type):
            w.u8(type.rawValue)
            return (.filter, w.bytes)
        case let .downloadRequest(index, offset, kind, seed, size, flags):
            w.u16(index); w.u32(offset); w.u8(kind.rawValue); w.u16(seed); w.u32(size); w.u8(flags)
            return (.downloadRequest, w.bytes)
        case let .fileTransferDataReply(status, next):
            w.u16(GarminMessageType.fileTransferData.rawValue); w.u8(GarminStatus.ack.rawValue)
            w.u8(status); w.u32(next)
            return (.response, w.bytes)
        case .supportedFileTypesRequest:
            return (.supportedFileTypesRequest, [])
        case let .fileAccessRequest(id, request):
            let smart = request.encodeSmart()
            w.u16(id); w.u32(0); w.u32(UInt32(smart.count)); w.u32(UInt32(smart.count)); w.append(smart)
            return (.protobufRequest, w.bytes)
        case let .protobufStatus(direction, id, offset, discard, code):
            w.u16(direction.messageType.rawValue); w.u8(GarminStatus.ack.rawValue)
            w.u16(id); w.u32(offset); w.u8(discard ? 1 : 0); w.u8(code)
            return (.response, w.bytes)
        }
    }
}

/// Which protobuf message a `protobufStatus` answers.
public enum GarminProtobufDirection: CaseIterable, Sendable {
    case request, response
    var messageType: GarminMessageType { self == .request ? .protobufRequest : .protobufResponse }
}
