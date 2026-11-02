import Foundation

// MARK: - FIT record stream (shared by the activity and the Garmin wellness decoders)
//
// Validates the FIT file header and walks the record section, handing every DATA message to a
// consumer together with its definition. The file layout is documented in `FitParser.swift`.
//
// SECURITY: every read is bounds-checked against the clamped data window. A declared dataSize larger
// than the buffer is clamped, record counts are capped, and a definition that does not fit the
// buffer or declares a zero-size field stops the walk instead of desynchronising it. The Kotlin twin
// is `FitRecordStream` in `android/.../ingest/FitRecordStream.kt`.

struct FitRecordStream {

    struct FieldDef: Equatable { let num: Int; let size: Int; let baseType: UInt8 }

    /// A local message type's definition: its global message number, fields and byte order.
    /// `totalSize` includes developer-field bytes, which are skipped but never decoded.
    struct Definition { let globalNum: Int; let fields: [FieldDef]; let bigEndian: Bool; let totalSize: Int }

    /// Why a walk ended. Anything other than `.end` means the file was cut short or malformed.
    enum Stop: Equatable { case end, recordCap, badDefinition, truncatedData, undefinedLocalType }

    // Caps (DoS): a long activity is tens of thousands of records; these are generous ceilings that
    // still bound work against a crafted file claiming millions of records or absurd field counts.
    static let maxRecords = 2_000_000
    static let maxFieldsPerDef = 255    // a u8 count can't exceed this anyway; keep explicit.

    let bytes: [UInt8]
    let start: Int
    let end: Int

    private init(bytes: [UInt8], start: Int, end: Int) {
        self.bytes = bytes
        self.start = start
        self.end = end
    }

    /// Validates the header and the ".FIT" signature; nil for anything that is not a FIT file.
    /// Kotlin twin: `FitRecordStream.open`.
    static func open(bytes: [UInt8]) -> FitRecordStream? {
        guard bytes.count >= 12 else { return nil }
        let headerSize = Int(bytes[0])
        guard headerSize >= 12, headerSize <= bytes.count,
              bytes[8] == UInt8(ascii: "."), bytes[9] == UInt8(ascii: "F"),
              bytes[10] == UInt8(ascii: "I"), bytes[11] == UInt8(ascii: "T") else { return nil }
        var declared = 0
        for k in 0..<4 { declared |= Int(bytes[4 + k]) << (8 * k) }
        // Clamp the data window to what is actually in the buffer.
        let dataEnd = headerSize + min(declared, bytes.count - headerSize)
        guard headerSize < dataEnd else { return nil }
        return FitRecordStream(bytes: bytes, start: headerSize, end: dataEnd)
    }

    /// Calls `body(definition, offset, compressedTimeOffset)` for every DATA message, in file order.
    /// `compressedTimeOffset` is the 5-bit offset of a compressed-timestamp header, nil otherwise.
    /// Kotlin twin: `FitRecordStream.walk`.
    func walk(maxRecords: Int = FitRecordStream.maxRecords, _ body: (Definition, Int, Int?) -> Void) -> Stop {
        var defs: [Int: Definition] = [:]
        var i = start
        var records = 0
        while i < end {
            if records >= maxRecords { return .recordCap }
            records += 1
            let header = bytes[i]
            i += 1
            if header & 0x80 != 0 {
                // Compressed-timestamp header: always DATA; local type in bits 5-6, offset in bits 0-4.
                guard let def = defs[Int((header >> 5) & 0x03)] else { return .undefinedLocalType }
                guard i + def.totalSize <= end else { return .truncatedData }
                body(def, i, Int(header & 0x1F))
                i += def.totalSize
            } else if header & 0x40 != 0 {
                guard let (def, next) = readDefinition(at: i, hasDevFields: header & 0x20 != 0) else {
                    return .badDefinition
                }
                defs[Int(header & 0x0F)] = def
                i = next
            } else {
                // No definition for this local type means its length is unknown: stop safely.
                guard let def = defs[Int(header & 0x0F)] else { return .undefinedLocalType }
                guard i + def.totalSize <= end else { return .truncatedData }
                body(def, i, nil)
                i += def.totalSize
            }
        }
        return .end
    }

    /// Kotlin twin: `FitRecordStream.readDefinition`.
    private func readDefinition(at start: Int, hasDevFields: Bool) -> (Definition, Int)? {
        // reserved(1) + architecture(1) + globalNum(2) + numFields(1).
        guard start + 5 <= end else { return nil }
        var i = start + 1
        let bigEndian = bytes[i] == 1; i += 1
        let globalNum = bigEndian ? (Int(bytes[i]) << 8) | Int(bytes[i + 1]) : Int(bytes[i]) | (Int(bytes[i + 1]) << 8)
        i += 2
        let numFields = Int(bytes[i]); i += 1
        guard numFields <= FitRecordStream.maxFieldsPerDef, i + numFields * 3 <= end else { return nil }

        var fields: [FieldDef] = []
        fields.reserveCapacity(numFields)
        var total = 0
        for _ in 0..<numFields {
            let size = Int(bytes[i + 1])
            guard size > 0 else { return nil }
            fields.append(FieldDef(num: Int(bytes[i]), size: size, baseType: bytes[i + 2]))
            total += size
            i += 3
        }
        if hasDevFields {
            // Developer fields are skipped, but their bytes count toward the data record length.
            guard i < end else { return nil }
            let numDev = Int(bytes[i]); i += 1
            guard i + numDev * 3 <= end else { return nil }
            for _ in 0..<numDev {
                let size = Int(bytes[i + 1])
                guard size > 0 else { return nil }
                total += size
                i += 3
            }
        }
        return (Definition(globalNum: globalNum, fields: fields, bigEndian: bigEndian, totalSize: total), i)
    }

    // MARK: - Bounds-checked field readers

    /// An unsigned integer of `size` (1...8) bytes; nil out of bounds. Sentinels are the caller's job.
    /// Kotlin twin: `FitRecordStream.uint`.
    func uint(at offset: Int, size: Int, bigEndian: Bool) -> UInt64? {
        guard offset >= 0, size >= 1, size <= 8, offset + size <= end else { return nil }
        var v: UInt64 = 0
        if bigEndian {
            for k in 0..<size { v = (v << 8) | UInt64(bytes[offset + k]) }
        } else {
            for k in 0..<size { v |= UInt64(bytes[offset + k]) << (8 * k) }
        }
        return v
    }

    /// A sign-extended integer of `size` (1...8) bytes; nil out of bounds.
    /// Kotlin twin: `FitRecordStream.int`.
    func int(at offset: Int, size: Int, bigEndian: Bool) -> Int64? {
        guard let u = uint(at: offset, size: size, bigEndian: bigEndian) else { return nil }
        let bits = size * 8
        if bits >= 64 { return Int64(bitPattern: u) }
        let signBit: UInt64 = 1 << (bits - 1)
        if u & signBit != 0 { return Int64(bitPattern: u | ~((1 << bits) - 1)) }
        return Int64(u)
    }
}
