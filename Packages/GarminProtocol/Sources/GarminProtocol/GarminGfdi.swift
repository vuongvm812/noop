import Foundation

// MARK: - GFDI message frame
//
// Wire facts (Gadgetbridge, see ATTRIBUTION.md). Inside the COBS layer every GFDI message is
//   [len u16][type u16][payload…][crc u16]          (little-endian)
// where `len` counts the whole message including itself and the CRC, and the CRC is CRC-16/ARC over
// everything before it. A type with bit 15 set is "compact": the real id is `(type & 0xFF) + 5000` and
// bits 8…14 are a sequence number. Status replies use type 5000 with `[origType u16][status u8]` first.

public enum GarminGfdi {

    /// Smallest legal message: length, type, CRC.
    public static let minimumLength = 6

    /// Seconds between the Unix epoch and the Garmin epoch (1989-12-31T00:00:00Z).
    public static let garminEpochOffset: Int64 = 631_065_600

    public static func garminTime(fromUnix unix: Int64) -> UInt32 {
        UInt32(truncatingIfNeeded: unix - garminEpochOffset)
    }

    public static func unixTime(fromGarmin garmin: UInt32) -> Int64 {
        Int64(garmin) + garminEpochOffset
    }

    /// One verified GFDI message.
    public struct Frame: Equatable, Sendable {
        /// The type as it appeared on the wire.
        public let rawType: UInt16
        /// The message id: `rawType`, or the expanded id for a compact type.
        public let type: UInt16
        /// Bits 8…14 of a compact type, nil for a full type.
        public let compactSequence: UInt8?
        public let payload: [UInt8]
    }

    public enum DecodeError: Error, Equatable, Sendable {
        case tooShort
        case lengthMismatch(declared: Int, actual: Int)
        case crcMismatch(expected: UInt16, actual: UInt16)
    }

    /// Verify and split one de-COBSed message. The declared length must equal the byte count exactly.
    public static func decode(_ message: [UInt8]) -> Result<Frame, DecodeError> {
        guard message.count >= minimumLength else { return .failure(.tooShort) }
        let declared = Int(message[0]) | Int(message[1]) << 8
        guard declared == message.count else {
            return .failure(.lengthMismatch(declared: declared, actual: message.count))
        }
        let body = Array(message[0..<(message.count - 2)])
        let carried = UInt16(message[message.count - 2]) | UInt16(message[message.count - 1]) << 8
        let computed = GarminCRC.crc16(body)
        guard carried == computed else { return .failure(.crcMismatch(expected: computed, actual: carried)) }
        let rawType = UInt16(message[2]) | UInt16(message[3]) << 8
        let type: UInt16
        let sequence: UInt8?
        if rawType & 0x8000 != 0 {
            type = (rawType & 0xFF) + 5000
            sequence = UInt8((rawType >> 8) & 0x7F)
        } else {
            type = rawType
            sequence = nil
        }
        return .success(Frame(rawType: rawType, type: type, compactSequence: sequence,
                              payload: Array(message[4..<(message.count - 2)])))
    }

    /// Build a framed message. Internal: outbound traffic is built only through `GarminOutbound`.
    static func encode(type: UInt16, payload: [UInt8]) -> [UInt8] {
        var w = GarminByteWriter()
        w.u16(UInt16(truncatingIfNeeded: payload.count + minimumLength))
        w.u16(type)
        w.append(payload)
        let crc = GarminCRC.crc16(w.bytes)
        w.u16(crc)
        return w.bytes
    }
}

/// GFDI message ids NOOP names. Ids outside this list still decode, as `GarminInbound.unknown`.
public enum GarminMessageType: UInt16, CaseIterable, Sendable {
    case response = 5000
    case downloadRequest = 5002
    case uploadRequest = 5003
    case fileTransferData = 5004
    case createFile = 5005
    case deleteFile = 5006
    case filter = 5007
    case setFileFlag = 5008
    case fileAvailable = 5009
    case batteryStatus = 5023
    case deviceInformation = 5024
    case systemEvent = 5030
    case supportedFileTypesRequest = 5031
    case synchronization = 5037
    case protobufRequest = 5043
    case protobufResponse = 5044
    case configuration = 5050
    case compressedFileData = 5054
    case currentTimeRequest = 5052
    case authNegotiation = 5101
}

/// The status byte of a 5000 reply.
public enum GarminStatus: UInt8, CaseIterable, Sendable {
    case ack = 0
    case nak = 1
    case unsupported = 2
    case decodeError = 3
    case crcError = 4
    case lengthError = 5
}
