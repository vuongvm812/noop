import Foundation

// MARK: - Protobuf wire codec + the FileAccess subset carried in 5043 / 5044
//
// Wire facts (Gadgetbridge's .proto descriptions, see ATTRIBUTION.md). A 5043/5044 payload is a `Smart`
// protobuf message; field 43 is the FileAccess `Service`, a one-of style wrapper whose fields NOOP uses:
//   1 PullItemDataRequest   2 PullItemDataResponse   9 ItemListRequest   10 ItemListResponse
//   12 ItemAddedNotification   22 SyncButtonNotification
// Delete (13/14) and ModifyFlags (15/16) are deliberately unrepresentable here: NOOP never changes or
// removes anything on the watch, so the request types that would do it have no encoder.
//
// The codec is hand-written (no protobuf runtime on either platform) and emits fields in ascending
// number order, so Swift and Kotlin produce identical bytes for identical values.

// MARK: Wire primitives

struct GarminProtoWriter {
    private(set) var bytes: [UInt8] = []

    mutating func varint(_ value: UInt64) {
        var v = value
        while v >= 0x80 {
            bytes.append(UInt8(v & 0x7F) | 0x80)
            v >>= 7
        }
        bytes.append(UInt8(v))
    }

    mutating func tag(_ field: Int, _ wireType: Int) { varint(UInt64(field << 3 | wireType)) }

    mutating func uint(_ field: Int, _ value: UInt64) {
        tag(field, 0)
        varint(value)
    }

    mutating func fixed64(_ field: Int, _ value: UInt64) {
        tag(field, 1)
        for i in 0..<8 { bytes.append(UInt8(truncatingIfNeeded: value >> (8 * UInt64(i)))) }
    }

    mutating func lengthDelimited(_ field: Int, _ data: [UInt8]) {
        tag(field, 2)
        varint(UInt64(data.count))
        bytes.append(contentsOf: data)
    }

    mutating func string(_ field: Int, _ value: String) { lengthDelimited(field, Array(value.utf8)) }
}

/// One decoded protobuf field. Groups (wire types 3/4) are rejected.
enum GarminProtoValue {
    case varint(UInt64)
    case fixed64(UInt64)
    case bytes([UInt8])
    case fixed32(UInt32)
}

enum GarminProtoReader {
    /// Split a message into (field, value) pairs in wire order, or nil if malformed.
    static func fields(_ data: [UInt8]) -> [(Int, GarminProtoValue)]? {
        var out: [(Int, GarminProtoValue)] = []
        var i = 0
        func varint() -> UInt64? {
            var result: UInt64 = 0
            var shift: UInt64 = 0
            while i < data.count && shift < 64 {
                let b = data[i]
                i += 1
                result |= UInt64(b & 0x7F) << shift
                if b & 0x80 == 0 { return result }
                shift += 7
            }
            return nil
        }
        while i < data.count {
            guard let key = varint() else { return nil }
            guard key >> 3 > 0, key >> 3 <= UInt64(Int32.max) else { return nil }
            let field = Int(key >> 3)
            switch key & 0x07 {
            case 0:
                guard let v = varint() else { return nil }
                out.append((field, .varint(v)))
            case 1:
                guard i + 8 <= data.count else { return nil }
                var v: UInt64 = 0
                for k in 0..<8 { v |= UInt64(data[i + k]) << (8 * UInt64(k)) }
                i += 8
                out.append((field, .fixed64(v)))
            case 2:
                guard let n = varint(), n <= UInt64(data.count - i) else { return nil }
                out.append((field, .bytes(Array(data[i..<(i + Int(n))]))))
                i += Int(n)
            case 5:
                guard i + 4 <= data.count else { return nil }
                var v: UInt32 = 0
                for k in 0..<4 { v |= UInt32(data[i + k]) << (8 * UInt32(k)) }
                i += 4
                out.append((field, .fixed32(v)))
            default:
                return nil
            }
        }
        return out
    }
}

private extension GarminProtoValue {
    var uint: UInt64? { if case let .varint(v) = self { return v } else { return nil } }
    var u32: UInt32? { uint.map { UInt32(truncatingIfNeeded: $0) } }
    var fixed: UInt64? { if case let .fixed64(v) = self { return v } else { return nil } }
    var data: [UInt8]? { if case let .bytes(v) = self { return v } else { return nil } }
    var text: String? { data.map { String(decoding: $0, as: UTF8.self) } }
    /// A zigzag-encoded sint32.
    var sint32: Int32? {
        guard let v = uint else { return nil }
        let n = UInt32(truncatingIfNeeded: v)
        return Int32(bitPattern: (n >> 1) ^ (0 &- (n & 1)))
    }
}

// MARK: FileAccess messages

/// A 128-bit item or flag id, carried as two fixed64 halves.
public struct GarminUid: Equatable, Hashable, Sendable {
    public let mostSignificant: UInt64
    public let leastSignificant: UInt64
    public init(mostSignificant: UInt64, leastSignificant: UInt64) {
        self.mostSignificant = mostSignificant
        self.leastSignificant = leastSignificant
    }

    func encode() -> [UInt8] {
        var w = GarminProtoWriter()
        w.fixed64(1, mostSignificant)
        w.fixed64(2, leastSignificant)
        return w.bytes
    }

    static func decode(_ data: [UInt8]) -> GarminUid? {
        guard let fields = GarminProtoReader.fields(data) else { return nil }
        var most: UInt64 = 0, least: UInt64 = 0
        for (f, v) in fields {
            if f == 1, let x = v.fixed { most = x }
            if f == 2, let x = v.fixed { least = x }
        }
        return GarminUid(mostSignificant: most, leastSignificant: least)
    }
}

/// FileAccess `DataType`: how the watch names an item's kind (e.g. a GDXML type string such as a FIT
/// file category). Only the fields NOOP reads are kept.
public struct GarminItemDataType: Equatable, Sendable {
    public var format: UInt32?
    public var typeName: String?
    public var typeNameKey: UInt32?
    public var fileIdentifier: String?

    public init(format: UInt32? = nil, typeName: String? = nil, typeNameKey: UInt32? = nil,
                fileIdentifier: String? = nil) {
        self.format = format
        self.typeName = typeName
        self.typeNameKey = typeNameKey
        self.fileIdentifier = fileIdentifier
    }

    func encode() -> [UInt8] {
        var w = GarminProtoWriter()
        if let format { w.uint(1, UInt64(format)) }
        if let typeName { w.string(2, typeName) }
        if let typeNameKey { w.uint(3, UInt64(typeNameKey)) }
        if let fileIdentifier { w.string(4, fileIdentifier) }
        return w.bytes
    }

    static func decode(_ data: [UInt8]) -> GarminItemDataType? {
        guard let fields = GarminProtoReader.fields(data) else { return nil }
        var t = GarminItemDataType()
        for (f, v) in fields {
            switch f {
            case 1: t.format = v.u32 ?? t.format
            case 2: t.typeName = v.text ?? t.typeName
            case 3: t.typeNameKey = v.u32 ?? t.typeNameKey
            case 4: t.fileIdentifier = v.text ?? t.fileIdentifier
            default: break
            }
        }
        return t
    }
}

/// FileAccess `ItemReference`: one file on the watch. A pull request echoes it back.
public struct GarminItemReference: Equatable, Sendable {
    public var uid: GarminUid?
    public var dataType: GarminItemDataType?
    public var dataSize: UInt64?
    public var requestedFlags: UInt32?
    public var transactionId: UInt32?
    public var urgency: UInt32?
    public var modifiedTime: UInt32?

    public init(uid: GarminUid? = nil, dataType: GarminItemDataType? = nil, dataSize: UInt64? = nil,
                requestedFlags: UInt32? = nil, transactionId: UInt32? = nil, urgency: UInt32? = nil,
                modifiedTime: UInt32? = nil) {
        self.uid = uid
        self.dataType = dataType
        self.dataSize = dataSize
        self.requestedFlags = requestedFlags
        self.transactionId = transactionId
        self.urgency = urgency
        self.modifiedTime = modifiedTime
    }

    func encode() -> [UInt8] {
        var w = GarminProtoWriter()
        if let uid { w.lengthDelimited(1, uid.encode()) }
        if let dataType { w.lengthDelimited(2, dataType.encode()) }
        if let dataSize { w.uint(3, dataSize) }
        if let requestedFlags { w.uint(4, UInt64(requestedFlags)) }
        if let transactionId { w.uint(5, UInt64(transactionId)) }
        if let urgency { w.uint(6, UInt64(urgency)) }
        if let modifiedTime { w.uint(7, UInt64(modifiedTime)) }
        return w.bytes
    }

    static func decode(_ data: [UInt8]) -> GarminItemReference? {
        guard let fields = GarminProtoReader.fields(data) else { return nil }
        var r = GarminItemReference()
        for (f, v) in fields {
            switch f {
            case 1: if let d = v.data { guard let uid = GarminUid.decode(d) else { return nil }; r.uid = uid }
            case 2: if let d = v.data { guard let t = GarminItemDataType.decode(d) else { return nil }; r.dataType = t }
            case 3: r.dataSize = v.uint ?? r.dataSize
            case 4: r.requestedFlags = v.u32 ?? r.requestedFlags
            case 5: r.transactionId = v.u32 ?? r.transactionId
            case 6: r.urgency = v.u32 ?? r.urgency
            case 7: r.modifiedTime = v.u32 ?? r.modifiedTime
            default: break
            }
        }
        return r
    }
}

/// The FileAccess requests NOOP may send. Read-only by construction: listing and pulling only.
public enum GarminFileAccessRequest: Equatable, Sendable {
    /// `ItemListRequest`. `excludeAllFlags` / `getFlags` filter by item flag ids.
    case itemList(sessionId: UInt32?, transactionId: UInt32?, maxItemCount: UInt32?,
                  excludeAllFlags: [GarminUid], getFlags: [GarminUid])
    /// `PullItemDataRequest` over the multi-link pipe transport (0).
    case pullItem(GarminItemReference, priority: UInt32?, offset: UInt64?, compressionWindow: UInt32?)

    /// Field numbers in `Smart` and in the FileAccess `Service` wrapper.
    static let smartFileAccessField = 43
    static let multilinkTransport: UInt64 = 0

    /// The complete `Smart` protobuf bytes for a 5043 payload.
    func encodeSmart() -> [UInt8] {
        var inner = GarminProtoWriter()
        var service = GarminProtoWriter()
        switch self {
        case let .itemList(sessionId, transactionId, maxItemCount, excludeAllFlags, getFlags):
            if let sessionId { inner.uint(1, UInt64(sessionId)) }
            if let transactionId { inner.uint(2, UInt64(transactionId)) }
            if let maxItemCount { inner.uint(3, UInt64(maxItemCount)) }
            for flag in excludeAllFlags { inner.lengthDelimited(4, flag.encode()) }
            for flag in getFlags { inner.lengthDelimited(5, flag.encode()) }
            service.lengthDelimited(9, inner.bytes)
        case let .pullItem(item, priority, offset, compressionWindow):
            inner.lengthDelimited(1, item.encode())
            if let priority { inner.uint(2, UInt64(priority)) }
            inner.uint(3, GarminFileAccessRequest.multilinkTransport)
            if let offset { inner.uint(4, offset) }
            if let compressionWindow { inner.uint(5, UInt64(compressionWindow)) }
            service.lengthDelimited(1, inner.bytes)
        }
        var smart = GarminProtoWriter()
        smart.lengthDelimited(GarminFileAccessRequest.smartFileAccessField, service.bytes)
        return smart.bytes
    }
}

/// The FileAccess messages NOOP reads from the watch.
public enum GarminFileAccessMessage: Equatable, Sendable {
    case itemListResponse(status: UInt32?, sessionId: UInt32?, nextTransactionId: UInt32?,
                          items: [GarminItemReference], errorCode: UInt32?)
    case pullItemResponse(result: UInt32?, transport: UInt32?, transferHandle: UInt32?,
                          filePath: String?, errorCode: Int32?)
    case itemAdded([GarminItemReference])
    case syncButton
    /// A FileAccess `Service` field NOOP does not read (number kept for the log).
    case other(field: Int)

    /// Decode a `Smart` protobuf. Returns nil when the bytes are malformed or carry no FileAccess field
    /// (some other Smart service, e.g. an HTTP proxy request, which the caller answers as unsupported).
    public static func decodeSmart(_ smart: [UInt8]) -> GarminFileAccessMessage? {
        guard let fields = GarminProtoReader.fields(smart) else { return nil }
        guard let service = fields.last(where: { $0.0 == GarminFileAccessRequest.smartFileAccessField })?.1.data,
              let serviceFields = GarminProtoReader.fields(service),
              let (field, value) = serviceFields.first else { return nil }
        guard let body = value.data else { return nil }
        guard let inner = GarminProtoReader.fields(body) else { return nil }
        switch field {
        case 10:
            var status: UInt32?, session: UInt32?, next: UInt32?, error: UInt32?
            var items: [GarminItemReference] = []
            for (f, v) in inner {
                switch f {
                case 1: status = v.u32 ?? status
                case 2: session = v.u32 ?? session
                case 3: next = v.u32 ?? next
                case 4:
                    guard let d = v.data, let item = GarminItemReference.decode(d) else { return nil }
                    items.append(item)
                case 5: error = v.u32 ?? error
                default: break
                }
            }
            return .itemListResponse(status: status, sessionId: session, nextTransactionId: next,
                                     items: items, errorCode: error)
        case 2:
            var result: UInt32?, transport: UInt32?, handle: UInt32?, path: String?, error: Int32?
            for (f, v) in inner {
                switch f {
                case 1: result = v.u32 ?? result
                case 2: transport = v.u32 ?? transport
                case 3: handle = v.u32 ?? handle
                case 4: path = v.text ?? path
                case 5: error = v.sint32 ?? error
                default: break
                }
            }
            return .pullItemResponse(result: result, transport: transport, transferHandle: handle,
                                     filePath: path, errorCode: error)
        case 12:
            var items: [GarminItemReference] = []
            for (f, v) in inner where f == 1 {
                guard let d = v.data, let item = GarminItemReference.decode(d) else { return nil }
                items.append(item)
            }
            return .itemAdded(items)
        case 22:
            return .syncButton
        default:
            return .other(field: field)
        }
    }
}
