import Foundation

// MARK: - CRC-16/ARC and Garmin COBS framing
//
// Wire facts (Gadgetbridge, see ATTRIBUTION.md): every GFDI message ends in a CRC-16/ARC over everything
// before it, and the GFDI byte stream is COBS-encoded with a leading AND a trailing 0x00 delimiter. File
// downloads chain the same CRC across chunks: each FILE_TRANSFER_DATA chunk carries the CRC of all file
// bytes received so far, seeded with the previous chunk's value.

public enum GarminCRC {

    /// CRC-16/ARC: reflected polynomial 0xA001, seed 0 (or the running value), no final XOR.
    /// `crc16(Array("123456789".utf8)) == 0xBB3D`, the catalogued check value.
    public static func crc16(_ data: [UInt8], seed: UInt16 = 0) -> UInt16 {
        var crc = seed
        for byte in data {
            crc ^= UInt16(byte)
            for _ in 0..<8 {
                crc = (crc & 1) != 0 ? (crc >> 1) ^ 0xA001 : crc >> 1
            }
        }
        return crc
    }
}

public enum GarminCOBS {

    /// Encode `data` as one delimited frame: `0x00`, the COBS body, `0x00`. The body never contains 0x00.
    public static func encode(_ data: [UInt8]) -> [UInt8] {
        var out: [UInt8] = [0x00]
        out.reserveCapacity(data.count + data.count / 254 + 3)
        var codeIndex = out.count
        out.append(0)
        var code: UInt8 = 1
        for byte in data {
            if byte == 0 {
                out[codeIndex] = code
                codeIndex = out.count
                out.append(0)
                code = 1
            } else {
                out.append(byte)
                code += 1
                if code == 0xFF {
                    out[codeIndex] = code
                    codeIndex = out.count
                    out.append(0)
                    code = 1
                }
            }
        }
        out[codeIndex] = code
        out.append(0x00)
        return out
    }

    /// Decode one COBS body (the bytes BETWEEN the delimiters, none of them 0x00). Returns nil when a code
    /// byte claims more bytes than the body holds, or when the body contains a 0x00, which is a truncated
    /// or corrupt frame, never a partial result.
    public static func decodeBody(_ body: [UInt8]) -> [UInt8]? {
        var out: [UInt8] = []
        out.reserveCapacity(body.count)
        var i = 0
        while i < body.count {
            let code = Int(body[i])
            if code == 0 { return nil }
            i += 1
            let end = i + code - 1
            if end > body.count { return nil }
            while i < end {
                if body[i] == 0 { return nil }
                out.append(body[i])
                i += 1
            }
            if code != 0xFF && i < body.count { out.append(0) }
        }
        return out
    }

    /// Decode one complete delimited frame (`0x00`, body, `0x00`), or nil if it is not exactly that shape.
    public static func decode(_ frame: [UInt8]) -> [UInt8]? {
        guard frame.count >= 3, frame.first == 0, frame.last == 0 else { return nil }
        return decodeBody(Array(frame[1..<(frame.count - 1)]))
    }
}

/// Reassembles COBS frames from an arbitrarily fragmented byte stream.
///
/// A frame starts after a 0x00 and ends at the next 0x00. Bytes seen before any 0x00 belong to a frame
/// whose start was lost and are dropped. A body that fails to decode (a lost notification truncated it) is
/// dropped and the decoder is immediately ready for the next frame: the delimiter that exposed the damage
/// is treated as the next frame's opener, so one lost packet costs one message, not the session.
public struct GarminCOBSStreamDecoder {

    /// Bodies longer than this are discarded unread, bounding memory against a stream with no delimiter.
    public let maxBodyBytes: Int

    private var body: [UInt8] = []
    private var synced = false

    /// Count of bodies dropped because they were malformed or oversize (diagnostic only).
    public private(set) var droppedFrames = 0

    public init(maxBodyBytes: Int = 16_384) {
        self.maxBodyBytes = maxBodyBytes
    }

    /// Feed newly received bytes; returns every frame they completed, in order.
    public mutating func feed(_ bytes: [UInt8]) -> [[UInt8]] {
        var frames: [[UInt8]] = []
        for byte in bytes {
            if byte == 0 {
                if synced && !body.isEmpty {
                    if let decoded = GarminCOBS.decodeBody(body) {
                        frames.append(decoded)
                    } else {
                        droppedFrames += 1
                    }
                }
                body.removeAll(keepingCapacity: true)
                synced = true
            } else if synced {
                if body.count >= maxBodyBytes {
                    droppedFrames += 1
                    body.removeAll(keepingCapacity: true)
                    synced = false
                } else {
                    body.append(byte)
                }
            }
        }
        return frames
    }

    /// Forget any partial frame (call on disconnect).
    public mutating func reset() {
        body.removeAll()
        synced = false
    }
}
