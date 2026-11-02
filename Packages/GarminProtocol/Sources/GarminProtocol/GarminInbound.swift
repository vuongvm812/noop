import Foundation

// MARK: - Inbound GFDI message decoding
//
// Payload layouts (Gadgetbridge, see ATTRIBUTION.md), all little-endian, after the 4-byte len/type header:
//   5024 DeviceInformation  protocolVersion u16, productNumber u16, unitNumber u32, softwareVersion u16,
//                           maxPacketSize u16, bluetoothName str8, deviceName str8, deviceModel str8,
//                           then optionally hasAddresses u8 (1 → BLE address 6 bytes + classic 6 bytes)
//   5050 Configuration      count u8, capability bitmap (bit n of the bitmap = capability n, LSB first)
//   5101 AuthNegotiation    unknown u8, flags u32
//   5052 CurrentTimeRequest referenceId u32
//   5037 Synchronization    type u8, maskSize u8 (4 or 8), file-type bitmask (maskSize bytes)
//   5004 FileTransferData   flags u8, runningCrc u16, dataOffset u32, data…
//   5054 CompressedFileData sequence u8, flags u8 (0x02 = last), checkpoint u32, checkpointCrc u16,
//                           then the next piece of one zlib stream. OBSERVED on a Forerunner 165, not
//                           from Gadgetbridge (which names the id only): `checkpoint` is how many
//                           uncompressed bytes the stream covers and `checkpointCrc` the CRC-16/ARC of
//                           those bytes (0 / 0 until the first checkpoint; the file size and the whole
//                           file's CRC on the last piece).
//   5009 FileAvailable      one 16-byte directory entry
//   5043/5044 Protobuf      requestId u16, dataOffset u32, totalLength u32, chunkLength u32, chunk…
//   5000 Status             origType u16, status u8, then per-origType fields (see `GarminStatusDetail`)
//   5023 BatteryStatus      status u8 (bits 4…6 a coarse level), voltage u8 (hundredths of a volt), percent u8,
//                           then bytes of unknown meaning
// A string is a u8 byte count followed by UTF-8. Every decode returns nil for a payload too short for
// its fixed fields; trailing bytes beyond the known layout are ignored.

/// One entry of the file directory (file index 0) or a FileAvailable notice. Exactly 16 bytes on the wire.
public struct GarminDirectoryEntry: Equatable, Sendable {
    public static let byteCount = 16

    public let fileIndex: UInt16
    public let dataType: UInt8
    public let subType: UInt8
    public let fileNumber: UInt16
    public let specificFlags: UInt8
    public let fileFlags: UInt8
    public let fileSize: UInt32
    /// Garmin-epoch seconds; 0 means the watch gave no date.
    public let garminTimestamp: UInt32

    public init(fileIndex: UInt16, dataType: UInt8, subType: UInt8, fileNumber: UInt16,
                specificFlags: UInt8, fileFlags: UInt8, fileSize: UInt32, garminTimestamp: UInt32) {
        self.fileIndex = fileIndex
        self.dataType = dataType
        self.subType = subType
        self.fileNumber = fileNumber
        self.specificFlags = specificFlags
        self.fileFlags = fileFlags
        self.fileSize = fileSize
        self.garminTimestamp = garminTimestamp
    }

    /// Unix seconds, or nil when the watch sent the 0 "no date" sentinel.
    public var unixTime: Int64? { garminTimestamp == 0 ? nil : GarminGfdi.unixTime(fromGarmin: garminTimestamp) }

    static func read(_ r: inout GarminByteReader) -> GarminDirectoryEntry? {
        guard let index = r.u16(), let dataType = r.u8(), let subType = r.u8(), let number = r.u16(),
              let specific = r.u8(), let flags = r.u8(), let size = r.u32(), let time = r.u32() else { return nil }
        return GarminDirectoryEntry(fileIndex: index, dataType: dataType, subType: subType, fileNumber: number,
                                    specificFlags: specific, fileFlags: flags, fileSize: size, garminTimestamp: time)
    }

    func write(_ w: inout GarminByteWriter) {
        w.u16(fileIndex); w.u8(dataType); w.u8(subType); w.u16(fileNumber)
        w.u8(specificFlags); w.u8(fileFlags); w.u32(fileSize); w.u32(garminTimestamp)
    }

    /// Parse a downloaded directory file (file index 0): a whole number of 16-byte entries, else nil.
    public static func parseDirectory(_ data: [UInt8]) -> [GarminDirectoryEntry]? {
        guard data.count % byteCount == 0 else { return nil }
        var r = GarminByteReader(data)
        var entries: [GarminDirectoryEntry] = []
        while r.remaining > 0 {
            guard let entry = read(&r) else { return nil }
            entries.append(entry)
        }
        return entries
    }
}

/// The watch's 5024 DeviceInformation.
public struct GarminDeviceInformation: Equatable, Sendable {
    public let protocolVersion: UInt16
    public let productNumber: UInt16
    public let unitNumber: UInt32
    /// Firmware version × 100 (e.g. 1234 is 12.34).
    public let softwareVersion: UInt16
    public let maxPacketSize: UInt16
    public let bluetoothName: String
    public let deviceName: String
    public let deviceModel: String
    /// The classic-Bluetooth address bytes as sent (little-endian), when the watch includes them.
    public let classicAddress: [UInt8]?
}

/// Per-origType fields that follow `[origType][status]` in a 5000 reply. Read only when status is ACK.
public enum GarminStatusDetail: Equatable, Sendable {
    case none
    case downloadRequest(downloadStatus: UInt8, maxFileSize: UInt32)
    case fileTransferData(transferStatus: UInt8, dataOffset: UInt32)
    case supportedFileTypes([GarminSupportedFileType])
    case filter(unknown: UInt8)
    case authNegotiation(authStatus: UInt8, unknown: UInt8, flags: UInt32)
    case protobuf(requestId: UInt16, dataOffset: UInt32, chunkStatus: UInt8, statusCode: UInt8)
}

public struct GarminSupportedFileType: Equatable, Sendable {
    public let dataType: UInt8
    public let subType: UInt8
    public let name: String
}

/// One chunk of a 5043 / 5044 protobuf message.
public struct GarminProtobufChunk: Equatable, Sendable {
    public let requestId: UInt16
    public let dataOffset: UInt32
    public let totalLength: UInt32
    public let data: [UInt8]
    /// True when this chunk is the whole message (offset 0 and length equals total).
    public var isComplete: Bool { dataOffset == 0 && UInt32(data.count) == totalLength }
}

public enum GarminInbound: Equatable, Sendable {
    /// 5000. `status` stays raw so an out-of-range value is still reported.
    case status(originalType: UInt16, status: UInt8, detail: GarminStatusDetail)
    case deviceInformation(GarminDeviceInformation)
    case configuration(capabilities: [UInt8])
    case authNegotiation(unknown: UInt8, flags: UInt32)
    case currentTimeRequest(referenceId: UInt32)
    case synchronization(type: UInt8, fileTypeMask: UInt64)
    case fileTransferData(flags: UInt8, runningCrc: UInt16, dataOffset: UInt32, data: [UInt8])
    /// 5054, sent instead of 5004 when the download asked for compression (layout above).
    case compressedFileData(sequence: UInt8, flags: UInt8, checkpoint: UInt32, checkpointCrc: UInt16, data: [UInt8])
    case fileAvailable(GarminDirectoryEntry)
    case protobufRequest(GarminProtobufChunk)
    case protobufResponse(GarminProtobufChunk)
    /// 5023. `percent` is the watch's charge; `statusBits` and `centivolts` are kept raw (candidates).
    case batteryStatus(statusBits: UInt8, centivolts: UInt8, percent: UInt8)
    /// A valid frame of a type NOOP does not decode, or a known type whose payload was too short.
    case unknown(type: UInt16, payload: [UInt8])

    /// Decode a verified GFDI frame. Never fails: an undecodable payload becomes `.unknown`, which the
    /// session still acknowledges (as unsupported) so the watch does not stall.
    public static func decode(_ frame: GarminGfdi.Frame) -> GarminInbound {
        decodeKnown(frame) ?? .unknown(type: frame.type, payload: frame.payload)
    }

    private static func decodeKnown(_ frame: GarminGfdi.Frame) -> GarminInbound? {
        var r = GarminByteReader(frame.payload)
        switch GarminMessageType(rawValue: frame.type) {
        case .response:
            guard let orig = r.u16(), let status = r.u8() else { return nil }
            let detail = status == GarminStatus.ack.rawValue ? statusDetail(orig, &r) : GarminStatusDetail.none
            return .status(originalType: orig, status: status, detail: detail)
        case .deviceInformation:
            guard let protocolVersion = r.u16(), let product = r.u16(), let unit = r.u32(),
                  let software = r.u16(), let maxPacket = r.u16(), let btName = r.string8(),
                  let name = r.string8(), let model = r.string8() else { return nil }
            var classic: [UInt8]?
            if r.u8() == 1, r.take(6) != nil, let address = r.take(6) { classic = address }
            return .deviceInformation(GarminDeviceInformation(
                protocolVersion: protocolVersion, productNumber: product, unitNumber: unit,
                softwareVersion: software, maxPacketSize: maxPacket, bluetoothName: btName,
                deviceName: name, deviceModel: model, classicAddress: classic))
        case .configuration:
            guard let count = r.u8(), let bitmap = r.take(Int(count)) else { return nil }
            return .configuration(capabilities: bitmap)
        case .authNegotiation:
            guard let unknown = r.u8(), let flags = r.u32() else { return nil }
            return .authNegotiation(unknown: unknown, flags: flags)
        case .currentTimeRequest:
            guard let reference = r.u32() else { return nil }
            return .currentTimeRequest(referenceId: reference)
        case .synchronization:
            guard let type = r.u8(), let size = r.u8() else { return nil }
            switch size {
            case 4: guard let mask = r.u32() else { return nil }; return .synchronization(type: type, fileTypeMask: UInt64(mask))
            case 8: guard let mask = r.u64() else { return nil }; return .synchronization(type: type, fileTypeMask: mask)
            default: return nil
            }
        case .fileTransferData:
            guard let flags = r.u8(), let crc = r.u16(), let offset = r.u32() else { return nil }
            return .fileTransferData(flags: flags, runningCrc: crc, dataOffset: offset, data: r.rest())
        case .compressedFileData:
            guard let sequence = r.u8(), let flags = r.u8(), let checkpoint = r.u32(), let crc = r.u16() else { return nil }
            return .compressedFileData(sequence: sequence, flags: flags, checkpoint: checkpoint, checkpointCrc: crc,
                                       data: r.rest())
        case .batteryStatus:
            guard let status = r.u8(), let volts = r.u8(), let percent = r.u8() else { return nil }
            return .batteryStatus(statusBits: status, centivolts: volts, percent: percent)
        case .fileAvailable:
            guard let entry = GarminDirectoryEntry.read(&r) else { return nil }
            return .fileAvailable(entry)
        case .protobufRequest, .protobufResponse:
            guard let id = r.u16(), let offset = r.u32(), let total = r.u32(), let length = r.u32(),
                  let data = r.take(Int(length)) else { return nil }
            let chunk = GarminProtobufChunk(requestId: id, dataOffset: offset, totalLength: total, data: data)
            return frame.type == GarminMessageType.protobufRequest.rawValue
                ? .protobufRequest(chunk) : .protobufResponse(chunk)
        default:
            return nil
        }
    }

    private static func statusDetail(_ orig: UInt16, _ r: inout GarminByteReader) -> GarminStatusDetail {
        switch GarminMessageType(rawValue: orig) {
        case .downloadRequest:
            guard let s = r.u8(), let max = r.u32() else { return .none }
            return .downloadRequest(downloadStatus: s, maxFileSize: max)
        case .fileTransferData:
            guard let s = r.u8(), let offset = r.u32() else { return .none }
            return .fileTransferData(transferStatus: s, dataOffset: offset)
        case .supportedFileTypesRequest:
            guard let count = r.u8() else { return .none }
            var types: [GarminSupportedFileType] = []
            for _ in 0..<count {
                guard let dt = r.u8(), let st = r.u8(), let name = r.string8() else { return .none }
                types.append(GarminSupportedFileType(dataType: dt, subType: st, name: name))
            }
            return .supportedFileTypes(types)
        case .filter:
            guard let unknown = r.u8() else { return .none }
            return .filter(unknown: unknown)
        case .authNegotiation:
            guard let s = r.u8(), let unknown = r.u8(), let flags = r.u32() else { return .none }
            return .authNegotiation(authStatus: s, unknown: unknown, flags: flags)
        case .protobufRequest, .protobufResponse:
            guard let id = r.u16(), let offset = r.u32(), let chunk = r.u8(), let code = r.u8() else { return .none }
            return .protobuf(requestId: id, dataOffset: offset, chunkStatus: chunk, statusCode: code)
        default:
            return .none
        }
    }
}

/// Decodes a capability bitmap from 5050 into the set of bit indices.
public enum GarminCapabilityBitmap {
    public static func indices(_ bitmap: [UInt8]) -> [Int] {
        var out: [Int] = []
        for (byteIndex, byte) in bitmap.enumerated() {
            for bit in 0..<8 where byte & (1 << bit) != 0 { out.append(byteIndex * 8 + bit) }
        }
        return out
    }
}
