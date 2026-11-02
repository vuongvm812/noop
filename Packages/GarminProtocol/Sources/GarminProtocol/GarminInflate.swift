import Foundation

// MARK: - zlib (RFC 1950) / DEFLATE (RFC 1951) decompression
//
// A FileAccess pull (the protobuf history-sync flow) delivers the file as one zlib stream over a
// multi-link file-transfer handle (Gadgetbridge wire fact, see ATTRIBUTION.md). The package must build on
// Linux and has no Compression framework, so this is a small, bounds-checked inflater written from the
// RFCs. The Kotlin twin (GarminInflate.kt) is a port of this same algorithm, so a stream cut short decodes
// to the same prefix on both; its test also checks whole streams against java.util.zip.Inflater.
//
// SECURITY: every read is bounds-checked and the output is capped (`maxOutput`), so a hostile stream
// can neither read past its input nor expand without limit. Any malformed stream returns nil.

public enum GarminInflate {

    /// Decompress a zlib stream (2-byte header, DEFLATE body, Adler-32 trailer). Nil when the header,
    /// body or checksum is invalid, or the output would exceed `maxOutput` bytes.
    public static func zlib(_ data: [UInt8], maxOutput: Int = 64 << 20) -> [UInt8]? {
        guard data.count >= 6 else { return nil }
        let cmf = data[0], flg = data[1]
        guard cmf & 0x0F == 8, cmf >> 4 <= 7, (UInt16(cmf) << 8 | UInt16(flg)) % 31 == 0,
              flg & 0x20 == 0 else { return nil }
        var bits = BitReader(data, start: 2)
        var out: [UInt8] = []
        guard inflate(&bits, &out, maxOutput: maxOutput) else { return nil }
        let trailer = bits.alignedByteIndex
        guard trailer + 4 <= data.count else { return nil }
        let expected = UInt32(data[trailer]) << 24 | UInt32(data[trailer + 1]) << 16
            | UInt32(data[trailer + 2]) << 8 | UInt32(data[trailer + 3])
        return adler32(out) == expected ? out : nil
    }

    /// The bytes a zlib stream cut short decodes to so far: every symbol fully received, nothing guessed.
    /// Used to turn an interrupted compressed transfer into an uncompressed resume point. Empty when the
    /// header is invalid. The caller re-checks the finished file (FIT CRC), since no checksum covers a prefix.
    public static func zlibPrefix(_ data: [UInt8], maxOutput: Int = 64 << 20) -> [UInt8] {
        guard data.count >= 2, data[0] & 0x0F == 8, data[0] >> 4 <= 7,
              (UInt16(data[0]) << 8 | UInt16(data[1])) % 31 == 0, data[1] & 0x20 == 0 else { return [] }
        var bits = BitReader(data, start: 2)
        var out: [UInt8] = []
        _ = inflate(&bits, &out, maxOutput: maxOutput)
        return out
    }

    public static func adler32(_ data: [UInt8]) -> UInt32 {
        var a: UInt32 = 1, b: UInt32 = 0
        for byte in data {
            a = (a + UInt32(byte)) % 65_521
            b = (b + a) % 65_521
        }
        return b << 16 | a
    }

    // MARK: DEFLATE

    private struct BitReader {
        let data: [UInt8]
        var index: Int
        var bitBuffer: UInt32 = 0
        var bitCount = 0

        init(_ data: [UInt8], start: Int) {
            self.data = data
            self.index = start
        }

        mutating func bits(_ n: Int) -> Int? {
            while bitCount < n {
                guard index < data.count else { return nil }
                bitBuffer |= UInt32(data[index]) << UInt32(bitCount)
                index += 1
                bitCount += 8
            }
            let value = Int(bitBuffer & ((1 << UInt32(n)) - 1))
            bitBuffer >>= UInt32(n)
            bitCount -= n
            return value
        }

        mutating func alignToByte() {
            bitBuffer = 0
            bitCount = 0
        }

        /// The first byte after the bits consumed so far (whole unread bytes in the buffer are given back).
        var alignedByteIndex: Int { index - bitCount / 8 }
    }

    /// Canonical Huffman decoding table: symbol counts per length and symbols ordered by code.
    private struct Huffman {
        var counts = [Int](repeating: 0, count: 16)
        var symbols: [Int] = []

        init?(lengths: [Int]) {
            for l in lengths { counts[l] += 1 }
            counts[0] = 0
            var left = 1
            for len in 1...15 {
                left <<= 1
                left -= counts[len]
                if left < 0 { return nil }   // over-subscribed
            }
            var offsets = [Int](repeating: 0, count: 16)
            for len in 1..<15 { offsets[len + 1] = offsets[len] + counts[len] }
            symbols = [Int](repeating: 0, count: lengths.count)
            for (symbol, l) in lengths.enumerated() where l != 0 {
                symbols[offsets[l]] = symbol
                offsets[l] += 1
            }
        }

        func decode(_ r: inout BitReader) -> Int? {
            var code = 0, first = 0, index = 0
            for len in 1...15 {
                guard let bit = r.bits(1) else { return nil }
                code |= bit
                let count = counts[len]
                if code - count < first { return symbols[index + (code - first)] }
                index += count
                first += count
                first <<= 1
                code <<= 1
            }
            return nil
        }
    }

    private static let lengthBase = [3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31,
                                     35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258]
    private static let lengthExtra = [0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2,
                                      3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0]
    private static let distanceBase = [1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193,
                                       257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145,
                                       8193, 12289, 16385, 24577]
    private static let distanceExtra = [0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6,
                                        7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13]
    private static let codeLengthOrder = [16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15]

    private static let fixedTables: (Huffman, Huffman) = {
        var lengths = [Int](repeating: 8, count: 288)
        for i in 144..<256 { lengths[i] = 9 }
        for i in 256..<280 { lengths[i] = 7 }
        return (Huffman(lengths: lengths)!, Huffman(lengths: [Int](repeating: 5, count: 30))!)
    }()

    /// Inflate into `out`; false on a malformed or truncated stream (`out` then holds what decoded).
    private static func inflate(_ r: inout BitReader, _ out: inout [UInt8], maxOutput: Int) -> Bool {
        var last = 0
        repeat {
            guard let final = r.bits(1), let type = r.bits(2) else { return false }
            last = final
            switch type {
            case 0:
                r.alignToByte()
                guard r.index + 4 <= r.data.count else { return false }
                let len = Int(r.data[r.index]) | Int(r.data[r.index + 1]) << 8
                let nlen = Int(r.data[r.index + 2]) | Int(r.data[r.index + 3]) << 8
                guard len == (~nlen & 0xFFFF), out.count + len <= maxOutput else { return false }
                let available = min(len, r.data.count - r.index - 4)
                out.append(contentsOf: r.data[(r.index + 4)..<(r.index + 4 + available)])
                guard available == len else { return false }
                r.index += 4 + len
            case 1:
                guard codes(&r, &out, fixedTables.0, fixedTables.1, maxOutput) else { return false }
            case 2:
                guard let (lit, dist) = dynamicTables(&r),
                      codes(&r, &out, lit, dist, maxOutput) else { return false }
            default:
                return false
            }
        } while last == 0
        return true
    }

    private static func dynamicTables(_ r: inout BitReader) -> (Huffman, Huffman)? {
        guard let hlit = r.bits(5), let hdist = r.bits(5), let hclen = r.bits(4) else { return nil }
        let nlen = hlit + 257, ndist = hdist + 1
        guard nlen <= 286, ndist <= 30 else { return nil }
        var codeLengths = [Int](repeating: 0, count: 19)
        for i in 0..<(hclen + 4) {
            guard let v = r.bits(3) else { return nil }
            codeLengths[codeLengthOrder[i]] = v
        }
        guard let lencode = Huffman(lengths: codeLengths) else { return nil }
        var lengths: [Int] = []
        while lengths.count < nlen + ndist {
            guard let symbol = lencode.decode(&r) else { return nil }
            switch symbol {
            case 0...15:
                lengths.append(symbol)
            case 16:
                guard let previous = lengths.last, let n = r.bits(2) else { return nil }
                lengths += [Int](repeating: previous, count: 3 + n)
            case 17:
                guard let n = r.bits(3) else { return nil }
                lengths += [Int](repeating: 0, count: 3 + n)
            default:
                guard let n = r.bits(7) else { return nil }
                lengths += [Int](repeating: 0, count: 11 + n)
            }
        }
        guard lengths.count == nlen + ndist, lengths[256] != 0,
              let lit = Huffman(lengths: Array(lengths[0..<nlen])),
              let dist = Huffman(lengths: Array(lengths[nlen...])) else { return nil }
        return (lit, dist)
    }

    private static func codes(_ r: inout BitReader, _ out: inout [UInt8], _ lit: Huffman, _ dist: Huffman,
                              _ maxOutput: Int) -> Bool {
        while true {
            guard let symbol = lit.decode(&r) else { return false }
            if symbol < 256 {
                guard out.count < maxOutput else { return false }
                out.append(UInt8(symbol))
            } else if symbol == 256 {
                return true
            } else {
                let li = symbol - 257
                guard li < lengthBase.count, let le = r.bits(lengthExtra[li]) else { return false }
                let length = lengthBase[li] + le
                guard let ds = dist.decode(&r), ds < distanceBase.count,
                      let de = r.bits(distanceExtra[ds]) else { return false }
                let distance = distanceBase[ds] + de
                guard distance <= out.count, out.count + length <= maxOutput else { return false }
                let start = out.count - distance
                for k in 0..<length { out.append(out[start + k]) }
            }
        }
    }
}
