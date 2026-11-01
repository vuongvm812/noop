import Foundation

// MARK: - Little-endian byte reader / writer shared by every Garmin codec
//
// All multi-byte GFDI and multi-link integers are little-endian. Strings are a u8 byte count followed by
// that many UTF-8 bytes. Readers return nil on a short buffer instead of trapping, so a malformed frame
// is rejected as a whole rather than half-decoded.

/// Cursor over an immutable byte buffer. Every read returns nil when too few bytes remain.
struct GarminByteReader {
    let bytes: [UInt8]
    private(set) var position: Int

    init(_ bytes: [UInt8], position: Int = 0) {
        self.bytes = bytes
        self.position = position
    }

    var remaining: Int { bytes.count - position }

    mutating func u8() -> UInt8? {
        guard remaining >= 1 else { return nil }
        defer { position += 1 }
        return bytes[position]
    }

    mutating func u16() -> UInt16? {
        guard remaining >= 2 else { return nil }
        defer { position += 2 }
        return UInt16(bytes[position]) | UInt16(bytes[position + 1]) << 8
    }

    mutating func u32() -> UInt32? {
        guard remaining >= 4 else { return nil }
        var value: UInt32 = 0
        for i in 0..<4 { value |= UInt32(bytes[position + i]) << (8 * UInt32(i)) }
        position += 4
        return value
    }

    mutating func u64() -> UInt64? {
        guard remaining >= 8 else { return nil }
        var value: UInt64 = 0
        for i in 0..<8 { value |= UInt64(bytes[position + i]) << (8 * UInt64(i)) }
        position += 8
        return value
    }

    mutating func take(_ count: Int) -> [UInt8]? {
        guard count >= 0, remaining >= count else { return nil }
        defer { position += count }
        return Array(bytes[position..<(position + count)])
    }

    mutating func rest() -> [UInt8] {
        defer { position = bytes.count }
        return Array(bytes[position...])
    }

    /// A u8-length-prefixed UTF-8 string. Invalid UTF-8 is repaired with U+FFFD rather than rejected.
    mutating func string8() -> String? {
        guard let count = u8(), let raw = take(Int(count)) else { return nil }
        return String(decoding: raw, as: UTF8.self)
    }
}

/// Append-only little-endian byte builder.
struct GarminByteWriter {
    private(set) var bytes: [UInt8] = []

    mutating func u8(_ value: UInt8) { bytes.append(value) }

    mutating func u16(_ value: UInt16) {
        bytes.append(UInt8(value & 0xFF))
        bytes.append(UInt8(value >> 8))
    }

    mutating func u32(_ value: UInt32) {
        for i in 0..<4 { bytes.append(UInt8(truncatingIfNeeded: value >> (8 * UInt32(i)))) }
    }

    mutating func u64(_ value: UInt64) {
        for i in 0..<8 { bytes.append(UInt8(truncatingIfNeeded: value >> (8 * UInt64(i)))) }
    }

    mutating func append(_ data: [UInt8]) { bytes.append(contentsOf: data) }

    /// A u8-length-prefixed UTF-8 string, truncated to at most 255 bytes without splitting a code point.
    mutating func string8(_ value: String) {
        let utf8 = GarminByteWriter.truncatedUTF8(value, maxBytes: 255)
        bytes.append(UInt8(utf8.count))
        bytes.append(contentsOf: utf8)
    }

    /// The UTF-8 bytes of `value`, cut to `maxBytes`. When the cut lands inside a multi-byte sequence the
    /// cut moves back to the start of that sequence, so the result is always valid UTF-8.
    static func truncatedUTF8(_ value: String, maxBytes: Int) -> [UInt8] {
        let all = Array(value.utf8)
        guard all.count > maxBytes else { return all }
        var cut = maxBytes
        while cut > 0 && (all[cut] & 0xC0) == 0x80 { cut -= 1 }
        return Array(all[0..<cut])
    }
}

/// Lower-case hex without separators, used by diagnostics and the parity oracle.
public func garminHex(_ bytes: [UInt8]) -> String {
    let digits: [Character] = Array("0123456789abcdef")
    var out = ""
    out.reserveCapacity(bytes.count * 2)
    for b in bytes {
        out.append(digits[Int(b >> 4)])
        out.append(digits[Int(b & 0x0F)])
    }
    return out
}
