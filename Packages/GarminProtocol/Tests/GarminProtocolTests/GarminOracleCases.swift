#if !GARMIN_ORACLE_STANDALONE
@testable import GarminProtocol
#endif

// The cross-platform parity oracle. `garminOracleLines()` exercises CRC, COBS, the MLR header over its whole
// input space, a scripted MLR session, ML control frames, GFDI framing, every outbound case and every
// inbound codec, and prints one line per result. The same cases are re-implemented in
// android/.../com/noop/garmin/GarminOracleTest.kt, whose expected literal is this function's output from
// the standalone build:
//
//   cd Packages/GarminProtocol && swiftc -O -D GARMIN_ORACLE_STANDALONE Sources/GarminProtocol/*.swift \
//       Tests/GarminProtocolTests/GarminOracleCases.swift Oracle/main.swift -o /tmp/garmin-oracle && /tmp/garmin-oracle
//
// `GarminOracleTests` re-checks the Swift side against that Kotlin literal, so an edit on either platform
// that changes a byte fails on both.

func oracleFnv32(_ bytes: [UInt8]) -> String {
    var h: UInt32 = 0x811C_9DC5
    for b in bytes {
        h ^= UInt32(b)
        h = h &* 0x0100_0193
    }
    return oracleHex32(h)
}

func oracleHex32(_ v: UInt32) -> String {
    let s = String(v, radix: 16)
    return String(repeating: "0", count: max(0, 8 - s.count)) + s
}

func oracleHex16(_ v: UInt16) -> String {
    let s = String(v, radix: 16)
    return String(repeating: "0", count: max(0, 4 - s.count)) + s
}

func oracleHex64(_ v: UInt64) -> String {
    let s = String(v, radix: 16)
    return String(repeating: "0", count: max(0, 16 - s.count)) + s
}

func oraclePattern(_ count: Int, _ mul: Int, _ add: Int) -> [UInt8] {
    (0..<count).map { UInt8(($0 * mul + add) % 256) }
}

func oracleOpt<T>(_ v: T?) -> String { v.map { "\($0)" } ?? "-" }

func oracleDescribe(_ item: GarminItemReference) -> String {
    let uid = item.uid.map { oracleHex64($0.mostSignificant) + ":" + oracleHex64($0.leastSignificant) } ?? "-"
    let dt = item.dataType.map {
        "\(oracleOpt($0.format))/\(oracleOpt($0.typeName))/\(oracleOpt($0.typeNameKey))/\(oracleOpt($0.fileIdentifier))"
    } ?? "-"
    return "{uid=\(uid) type=\(dt) size=\(oracleOpt(item.dataSize)) flags=\(oracleOpt(item.requestedFlags))"
        + " txn=\(oracleOpt(item.transactionId)) urg=\(oracleOpt(item.urgency)) mtime=\(oracleOpt(item.modifiedTime))}"
}

func oracleDescribe(_ m: GarminFileAccessMessage?) -> String {
    guard let m else { return "nil" }
    switch m {
    case let .itemListResponse(status, session, next, items, error):
        return "itemList status=\(oracleOpt(status)) session=\(oracleOpt(session)) next=\(oracleOpt(next))"
            + " error=\(oracleOpt(error)) items=[" + items.map(oracleDescribe).joined(separator: ",") + "]"
    case let .pullItemResponse(result, transport, handle, path, error):
        return "pull result=\(oracleOpt(result)) transport=\(oracleOpt(transport)) handle=\(oracleOpt(handle))"
            + " path=\(oracleOpt(path)) error=\(oracleOpt(error))"
    case let .itemAdded(items):
        return "itemAdded [" + items.map(oracleDescribe).joined(separator: ",") + "]"
    case .syncButton:
        return "syncButton"
    case let .other(field):
        return "other \(field)"
    }
}

func oracleDescribe(_ e: GarminDirectoryEntry) -> String {
    "{idx=\(e.fileIndex) dt=\(e.dataType) st=\(e.subType) num=\(e.fileNumber) sf=\(e.specificFlags)"
        + " ff=\(e.fileFlags) size=\(e.fileSize) ts=\(e.garminTimestamp) unix=\(oracleOpt(e.unixTime))}"
}

func oracleDescribe(_ d: GarminStatusDetail) -> String {
    switch d {
    case .none: return "none"
    case let .downloadRequest(s, max): return "download \(s) \(max)"
    case let .fileTransferData(s, offset): return "transfer \(s) \(offset)"
    case let .supportedFileTypes(types):
        return "types [" + types.map { "\($0.dataType)/\($0.subType)/\($0.name)" }.joined(separator: ",") + "]"
    case let .filter(u): return "filter \(u)"
    case let .authNegotiation(s, u, f): return "auth \(s) \(u) \(oracleHex32(f))"
    case let .protobuf(id, offset, chunk, code): return "protobuf \(id) \(offset) \(chunk) \(code)"
    }
}

func oracleDescribe(_ m: GarminInbound) -> String {
    switch m {
    case let .status(orig, status, detail):
        return "status orig=\(orig) status=\(status) detail=\(oracleDescribe(detail))"
    case let .deviceInformation(i):
        return "devinfo proto=\(i.protocolVersion) product=\(i.productNumber) unit=\(i.unitNumber)"
            + " sw=\(i.softwareVersion) maxpkt=\(i.maxPacketSize) bt=\(i.bluetoothName) name=\(i.deviceName)"
            + " model=\(i.deviceModel) classic=\(i.classicAddress.map(garminHex) ?? "-")"
    case let .configuration(bitmap):
        return "config bits=" + GarminCapabilityBitmap.indices(bitmap).map(String.init).joined(separator: ",")
    case let .authNegotiation(u, f):
        return "auth unk=\(u) flags=\(oracleHex32(f))"
    case let .currentTimeRequest(ref):
        return "time ref=\(ref)"
    case let .synchronization(type, mask):
        return "sync type=\(type) mask=\(oracleHex64(mask))"
    case let .fileTransferData(flags, crc, offset, data):
        return "chunk flags=\(flags) crc=\(oracleHex16(crc)) offset=\(offset) data=\(garminHex(data))"
    case let .compressedFileData(seq, flags, checkpoint, crc, data):
        return "compressed seq=\(seq) flags=\(flags) checkpoint=\(checkpoint) crc=\(oracleHex16(crc)) data=\(garminHex(data))"
    case let .fileAvailable(entry):
        return "available " + oracleDescribe(entry)
    case let .protobufRequest(c):
        return "pbreq id=\(c.requestId) offset=\(c.dataOffset) total=\(c.totalLength) complete=\(c.isComplete)"
            + " data=\(garminHex(c.data))"
    case let .protobufResponse(c):
        return "pbresp id=\(c.requestId) offset=\(c.dataOffset) total=\(c.totalLength) complete=\(c.isComplete)"
            + " data=\(garminHex(c.data))"
    case let .batteryStatus(status, volts, percent):
        return "battery status=\(status) centivolts=\(volts) percent=\(percent)"
    case let .unknown(type, payload):
        return "unknown type=\(type) payload=\(garminHex(payload))"
    }
}

func oracleDescribe(_ r: Result<GarminGfdi.Frame, GarminGfdi.DecodeError>) -> String {
    switch r {
    case let .success(f):
        return "ok raw=\(oracleHex16(f.rawType)) type=\(f.type) seq=\(oracleOpt(f.compactSequence)) payload=\(garminHex(f.payload))"
    case .failure(.tooShort):
        return "err short"
    case let .failure(.lengthMismatch(declared, actual)):
        return "err length \(declared) \(actual)"
    case let .failure(.crcMismatch(expected, actual)):
        return "err crc \(oracleHex16(expected)) \(oracleHex16(actual))"
    }
}

func oracleDescribe(_ c: GarminMultiLink.ControlResponse?) -> String {
    guard let c else { return "nil" }
    switch c {
    case let .registered(service, status, handle, reliable):
        return "registered service=\(service) status=\(status) handle=\(handle) reliable=\(reliable)"
    case let .handleClosed(service, handle, status):
        return "closed service=\(service) handle=\(handle) status=\(status)"
    case .allClosed:
        return "allClosed"
    }
}

func oracleDescribe(_ r: GarminMultiLink.Route) -> String {
    switch r {
    case let .control(p): return "control \(garminHex(p))"
    case let .reliable(h, p): return "reliable \(h) \(garminHex(p))"
    case let .plain(h, p): return "plain \(h) \(garminHex(p))"
    case .empty: return "empty"
    }
}

/// One sample per `GarminOutbound.Kind`; the switch is exhaustive so a new case must be sampled.
func oracleOutboundSamples(_ kind: GarminOutbound.Kind) -> [GarminOutbound] {
    let item = GarminItemReference(
        uid: GarminUid(mostSignificant: 0x0123_4567_89AB_CDEF, leastSignificant: 0xFEDC_BA98_7654_3210),
        dataType: GarminItemDataType(format: 3, typeName: "FIT_TYPE_44", typeNameKey: 7, fileIdentifier: nil),
        dataSize: 123_456, requestedFlags: nil, transactionId: 42, urgency: 1, modifiedTime: 1_100_000_000)
    switch kind {
    case .status:
        return [.status(of: 5024, .ack), .status(of: 5999, .unsupported)]
    case .deviceInformationReply:
        return [.deviceInformationReply(GarminHostIdentity(
            protocolVersion: 150, productNumber: 0xFFFF, unitNumber: 0xFFFF_FFFF, softwareVersion: 100,
            maxPacketSize: 0xFFFF, bluetoothName: "NOOP phone", manufacturer: "NOOP", model: "Strand é"),
            protocolFlags: 1)]
    case .configuration:
        return [.configuration([]), .configuration(Set(GarminCapability.allCases))]
    case .authNegotiationReply:
        return [.authNegotiationReply(unknown: 0, flags: 0), .authNegotiationReply(unknown: 3, flags: 0x8000_0001)]
    case .currentTimeReply:
        return [.currentTimeReply(referenceId: 77, garminTime: 1_100_000_000, utcOffsetSeconds: -18_000,
                                  followingTransition: 1_120_000_000, nextTransition: 1_105_000_000)]
    case .systemEvent:
        return GarminSafeSystemEvent.allCases.map { .systemEvent($0, value: 0) }
    case .filter:
        return GarminFilterType.allCases.map { .filter($0) }
    case .downloadRequest:
        return [.downloadRequest(fileIndex: 0, dataOffset: 0, kind: .new, crcSeed: 0, dataSize: 0, flags: 0),
                .downloadRequest(fileIndex: 0x1234, dataOffset: 4096, kind: .continue, crcSeed: 0xBEEF,
                                 dataSize: 70_000, flags: 1)]
    case .fileTransferDataReply:
        return [.fileTransferDataReply(transferStatus: 0, nextOffset: 500)]
    case .supportedFileTypesRequest:
        return [.supportedFileTypesRequest]
    case .fileAccessRequest:
        return [
            .fileAccessRequest(requestId: 1, .itemList(
                sessionId: nil, transactionId: nil, maxItemCount: nil, excludeAllFlags: [], getFlags: [])),
            .fileAccessRequest(requestId: 2, .itemList(
                sessionId: 9, transactionId: 300, maxItemCount: 100,
                excludeAllFlags: [GarminUid(mostSignificant: 42_405, leastSignificant: 42_405)],
                getFlags: [GarminUid(mostSignificant: 42_405, leastSignificant: 42_405)])),
            .fileAccessRequest(requestId: 0xFFFF, .pullItem(item, priority: 24, offset: 0, compressionWindow: 15)),
        ]
    case .protobufStatus:
        return [.protobufStatus(of: .request, requestId: 5, dataOffset: 0, discard: false, code: 0),
                .protobufStatus(of: .response, requestId: 6, dataOffset: 10, discard: true, code: 100)]
    }
}

/// A test-side GFDI frame builder for inbound fixtures (production code builds frames only via GarminOutbound).
func oracleFrame(_ type: UInt16, _ payload: [UInt8]) -> [UInt8] {
    var out: [UInt8] = []
    let len = payload.count + 6
    out += [UInt8(len & 0xFF), UInt8(len >> 8), UInt8(type & 0xFF), UInt8(type >> 8)]
    out += payload
    let crc = GarminCRC.crc16(out)
    out += [UInt8(crc & 0xFF), UInt8(crc >> 8)]
    return out
}

func oracleLE16(_ v: Int) -> [UInt8] { [UInt8(v & 0xFF), UInt8((v >> 8) & 0xFF)] }
func oracleLE32(_ v: Int) -> [UInt8] { (0..<4).map { UInt8((v >> (8 * $0)) & 0xFF) } }
func oracleStr(_ s: String) -> [UInt8] { let u = Array(s.utf8); return [UInt8(u.count)] + u }

/// Protobuf fixture helpers (test side).
func oracleVarint(_ v: UInt64) -> [UInt8] {
    var out: [UInt8] = []
    var x = v
    while x >= 0x80 { out.append(UInt8(x & 0x7F) | 0x80); x >>= 7 }
    out.append(UInt8(x))
    return out
}
func oracleLd(_ field: Int, _ data: [UInt8]) -> [UInt8] { oracleVarint(UInt64(field << 3 | 2)) + oracleVarint(UInt64(data.count)) + data }
func oracleVi(_ field: Int, _ v: UInt64) -> [UInt8] { oracleVarint(UInt64(field << 3)) + oracleVarint(v) }
func oracleSmart(_ serviceField: Int, _ body: [UInt8]) -> [UInt8] { oracleLd(43, oracleLd(serviceField, body)) }

func oracleInboundFixtures() -> [(String, [UInt8])] {
    let devinfo: [UInt8] = oracleLE16(150) + oracleLE16(3_869) + oracleLE32(0x1234_5678) + oracleLE16(1_234) + oracleLE16(375)
        + oracleStr("Forerunner 165") + oracleStr("Forerunner 165") + oracleStr("FR165")
    var entry: [UInt8] = oracleLE16(0x0102)
    entry += [128, 4]
    entry += oracleLE16(7)
    entry += [0, 0x10]
    entry += oracleLE32(2_048)
    entry += oracleLE32(1_100_000_000)
    var uidBody: [UInt8] = [0x09]
    uidBody += oracleLE32(1) + oracleLE32(0)
    uidBody += [0x11]
    uidBody += oracleLE32(2) + oracleLE32(0)
    let itemRef: [UInt8] = oracleLd(1, uidBody) + oracleLd(2, oracleVi(1, 3) + oracleLd(2, Array("FIT_TYPE_44".utf8)) + oracleVi(3, 7))
        + oracleVi(3, 2_048) + oracleVi(5, 11) + oracleVi(7, 1_100_000_000)
    let itemList = oracleSmart(10, oracleVi(1, 0) + oracleVi(2, 9) + oracleVi(3, 12) + oracleLd(4, itemRef) + oracleLd(4, oracleVi(3, 1)))
    let pullResp = oracleSmart(2, oracleVi(1, 0) + oracleVi(2, 0) + oracleVi(3, 0x2018) + oracleLd(4, Array("/f.fit".utf8)) + oracleVi(5, 3))
    let added = oracleSmart(12, oracleLd(1, itemRef))
    let sync = oracleSmart(22, [])
    func pb(_ id: Int, _ smart: [UInt8]) -> [UInt8] {
        oracleLE16(id) + oracleLE32(0) + oracleLE32(smart.count) + oracleLE32(smart.count) + smart
    }
    return [
        ("devinfo", oracleFrame(5024, devinfo)),
        ("devinfo.addr", oracleFrame(5024, devinfo + [1] + [1, 2, 3, 4, 5, 6] + [0xA1, 0xA2, 0xA3, 0xA4, 0xA5, 0xA6] + [0])),
        ("devinfo.short", oracleFrame(5024, Array(devinfo.prefix(20)))),
        ("config", oracleFrame(5050, [3, 0x39, 0x00, 0x81])),
        ("config.short", oracleFrame(5050, [5, 1, 2])),
        ("auth", oracleFrame(5101, [2] + oracleLE32(0x11))),
        ("time", oracleFrame(5052, oracleLE32(0xDEAD_BEEF))),
        ("sync4", oracleFrame(5037, [1, 4] + oracleLE32(0x8000_0021))),
        ("sync8", oracleFrame(5037, [0, 8] + oracleLE32(0x0400_0020) + oracleLE32(0x10))),
        ("sync.bad", oracleFrame(5037, [0, 2, 1, 1])),
        ("chunk", oracleFrame(5004, [0] + oracleLE16(0x1F2E) + oracleLE32(512) + [0xDE, 0xAD, 0x00, 0xEF])),
        // 5054 as a Forerunner 165 sends it: the last piece of a 915-byte file (checkpoint = size, CRC 0).
        ("compressed", oracleFrame(5054, [1, 2] + oracleLE32(915) + oracleLE16(0) + [0x10, 0x4F, 0x21, 0xD4])),
        ("compressed-short", oracleFrame(5054, [0, 0, 0, 0, 0, 0, 0])),
        ("available", oracleFrame(5009, entry)),
        ("available.extra", oracleFrame(5009, entry + [1])),
        ("compact.available", oracleFrame(0x8000 | 0x2A00 | 9, entry)),
        ("status.generic", oracleFrame(5000, oracleLE16(5030) + [0])),
        ("status.nak", oracleFrame(5000, oracleLE16(5002) + [1, 9, 9])),
        ("status.download", oracleFrame(5000, oracleLE16(5002) + [0, 0] + oracleLE32(4_096))),
        ("status.download.short", oracleFrame(5000, oracleLE16(5002) + [0, 0])),
        ("status.transfer", oracleFrame(5000, oracleLE16(5004) + [0, 1] + oracleLE32(256))),
        ("status.types", oracleFrame(5000, oracleLE16(5031) + [0, 2, 128, 4] + oracleStr("FIT_TYPE_4") + [255, 0] + oracleStr("Ü"))),
        ("status.filter", oracleFrame(5000, oracleLE16(5007) + [0, 7])),
        ("status.auth", oracleFrame(5000, oracleLE16(5101) + [0, 0, 2] + oracleLE32(1))),
        ("status.protobuf", oracleFrame(5000, oracleLE16(5043) + [0] + oracleLE16(3) + oracleLE32(0) + [0, 0])),
        ("status.short", oracleFrame(5000, [0x88])),
        ("pb.itemlist", oracleFrame(5044, pb(3, itemList))),
        ("pb.pull", oracleFrame(5044, pb(4, pullResp))),
        ("pb.added", oracleFrame(5043, pb(5, added))),
        ("pb.sync", oracleFrame(5043, pb(6, sync))),
        ("pb.chunked", oracleFrame(5043, oracleLE16(7) + oracleLE32(10) + oracleLE32(40) + oracleLE32(3) + [1, 2, 3])),
        ("pb.overlong", oracleFrame(5043, oracleLE16(7) + oracleLE32(0) + oracleLE32(40) + oracleLE32(30) + [1, 2, 3])),
        ("battery", oracleFrame(5023, [1, 2, 3])),
        ("battery.full", oracleFrame(5023, [0x10, 0xC8, 87, 0, 0, 0, 0])),
        ("battery.short", oracleFrame(5023, [1, 2])),
        ("unnamed", oracleFrame(5777, [9])),
    ]
}

func oracleProtobufFixtures() -> [(String, [UInt8])] {
    let fixtures = oracleInboundFixtures()
    var out: [(String, [UInt8])] = []
    for (name, frame) in fixtures where name.hasPrefix("pb.") {
        if case let .success(f) = GarminGfdi.decode(frame) {
            switch GarminInbound.decode(f) {
            case let .protobufRequest(c), let .protobufResponse(c): out.append((name, c.data))
            default: break
            }
        }
    }
    out.append(("pb.notfileaccess", oracleLd(2, oracleVi(1, 1))))
    out.append(("pb.otherservice", oracleSmart(13, oracleVi(1, 1))))
    out.append(("pb.truncated", Array(oracleSmart(10, oracleVi(1, 0)).dropLast())))
    out.append(("pb.group", [0x0B, 0x0C]))
    out.append(("pb.negerror", oracleSmart(2, oracleVi(1, 4) + oracleVi(5, 0xFFFF_FFFF))))
    return out
}

func garminOracleLines() -> [String] {
    var out: [String] = []

    // CRC-16/ARC and the running chain.
    let crcInputs: [(String, [UInt8])] = [
        ("empty", []), ("zero", [0]), ("check", Array("123456789".utf8)), ("ff4", [0xFF, 0xFF, 0xFF, 0xFF]),
        ("seq256", oraclePattern(256, 1, 0)), ("pat1000", oraclePattern(1000, 7, 3)),
    ]
    for (name, data) in crcInputs { out.append("crc \(name) \(oracleHex16(GarminCRC.crc16(data)))") }
    let whole = oraclePattern(1000, 13, 5)
    var running: UInt16 = 0
    var chain: [String] = []
    var start = 0
    for size in [1, 7, 64, 128, 300, 500] {
        running = GarminCRC.crc16(Array(whole[start..<(start + size)]), seed: running)
        chain.append(oracleHex16(running))
        start += size
    }
    out.append("crc.chain \(chain.joined(separator: ",")) whole=\(oracleHex16(GarminCRC.crc16(whole)))")

    // COBS.
    let cobsInputs: [(String, [UInt8])] = [
        ("empty", []), ("z1", [0]), ("z2", [0, 0]), ("mix", [0x11, 0x22, 0x00, 0x33]), ("tailzero", [0x11, 0x00]),
        ("ones253", [UInt8](repeating: 1, count: 253)), ("ones254", [UInt8](repeating: 1, count: 254)),
        ("ones255", [UInt8](repeating: 1, count: 255)), ("seq256", oraclePattern(256, 1, 0)),
        ("pat600", oraclePattern(600, 7, 3)),
    ]
    for (name, data) in cobsInputs {
        let enc = GarminCOBS.encode(data)
        let rt = GarminCOBS.decode(enc) == data
        let shown = enc.count <= 40 ? garminHex(enc) : "len=\(enc.count) fnv=\(oracleFnv32(enc))"
        out.append("cobs \(name) \(shown) rt=\(rt)")
    }
    let badBodies: [(String, [UInt8])] = [
        ("overrun", [0x05, 0x11, 0x22]), ("innerzero", [0x03, 0x11, 0x00]), ("codezero", [0x00]),
        ("ff-short", [0xFF, 0x01]),
    ]
    for (name, body) in badBodies {
        out.append("cobs.bad \(name) \(GarminCOBS.decodeBody(body).map(garminHex) ?? "nil")")
    }
    for (name, frame) in [("nolead", [0x02, 0x11, 0x00]), ("notail", [0x00, 0x02, 0x11]), ("tiny", [0x00, 0x00])] as [(String, [UInt8])] {
        out.append("cobs.frame \(name) \(GarminCOBS.decode(frame).map(garminHex) ?? "nil")")
    }
    // Stream: junk before sync, a frame truncated by a lost packet, then good frames; fed in several splits.
    let stream: [UInt8] = [0x11, 0x22] + [0x00, 0x05, 0x01, 0x02] + GarminCOBS.encode([0xAA, 0x00, 0xBB])
        + GarminCOBS.encode(oraclePattern(300, 3, 1)) + [0x00, 0x00] + GarminCOBS.encode([])
    for split in [1, 3, 20, 1000] {
        var dec = GarminCOBSStreamDecoder()
        var frames: [String] = []
        var i = 0
        while i < stream.count {
            for f in dec.feed(Array(stream[i..<min(i + split, stream.count)])) {
                frames.append(f.count <= 8 ? garminHex(f) : "len\(f.count):\(oracleFnv32(f))")
            }
            i += split
        }
        out.append("cobs.stream split=\(split) frames=[\(frames.joined(separator: ","))] dropped=\(dec.droppedFrames)")
    }
    var small = GarminCOBSStreamDecoder(maxBodyBytes: 4)
    let oversize = small.feed([0x00, 0x06, 1, 2, 3, 4, 5, 0x00] + GarminCOBS.encode([7, 8]))
    out.append("cobs.oversize frames=[\(oversize.map(garminHex).joined(separator: ","))] dropped=\(small.droppedFrames)")

    // MLR header over every handle × reqNum × seq.
    var unpacked = 0
    for h in 0..<8 {
        var all: [UInt8] = []
        for r in 0..<64 {
            for s in 0..<64 {
                let header = GarminMlrHeader(handle: UInt8(h), reqNum: UInt8(r), seq: UInt8(s))
                all += header.bytes
                if GarminMlrHeader.parse(header.bytes) == header { unpacked += 1 }
            }
        }
        out.append("mlr.pack h=\(h) fnv=\(oracleFnv32(all)) crc=\(oracleHex16(GarminCRC.crc16(all))) head=\(garminHex(Array(all.prefix(12)))) tail=\(garminHex(Array(all.suffix(4))))")
    }
    out.append("mlr.unpack ok=\(unpacked) plain=\(GarminMlrHeader.parse([0x7F, 0x00]) == nil) short=\(GarminMlrHeader.parse([0x80]) == nil)")

    // A scripted MLR session: 70 frames' worth of data through a 32-packet window, acks, a loss,
    // retransmission backoff, inbound in-order / duplicate / gap data, and the 5-packet ack threshold.
    var mlr = GarminMlrSession(handle: 0x83, mtu: 23)
    func show(_ step: String, _ packets: [[UInt8]], _ s: GarminMlrSession) -> String {
        "mlr.step \(step) out=[\(packets.map { $0.count <= 6 ? garminHex($0) : garminHex(Array($0.prefix(2))) + "+\($0.count - 2)" }.joined(separator: ","))]"
            + " inflight=\(s.inFlight) queued=\(s.queued) window=\(s.windowSize) rto=\(s.retransmitTimeoutMs) next=\(oracleOpt(s.nextDeadlineMs))"
    }
    let big = GarminOutbound.fileTransferDataReply(transferStatus: 0, nextOffset: 1).frame()
    var sentPackets = mlr.send(GarminOutboundFrame(bytes: oraclePattern(18 * 40, 5, 1)), nowMs: 0)
    out.append(show("send40", sentPackets, mlr))
    func peer(_ reqNum: Int, _ seq: Int, _ data: [UInt8]) -> [UInt8] {
        GarminMlrHeader(handle: 3, reqNum: UInt8(reqNum), seq: UInt8(seq)).bytes + data
    }
    var rx = mlr.receive(peer(10, 0, []), nowMs: 100)
    out.append(show("ack10", rx.outbound, mlr) + " data=\(oracleOpt(rx.data.map(garminHex)))")
    sentPackets = mlr.tick(nowMs: 1_099)
    out.append(show("tick1099", sentPackets, mlr))
    sentPackets = mlr.tick(nowMs: 1_100)
    out.append(show("rto1", sentPackets, mlr))
    sentPackets = mlr.tick(nowMs: 3_100)
    out.append(show("rto2", sentPackets, mlr))
    rx = mlr.receive(peer(40, 0, [0xA0]), nowMs: 3_200)
    out.append(show("ack40+data0", rx.outbound, mlr) + " data=\(oracleOpt(rx.data.map(garminHex)))")
    sentPackets = mlr.tick(nowMs: 3_220)
    out.append(show("acktimer", sentPackets, mlr))
    rx = mlr.receive(peer(40, 0, [0xA0]), nowMs: 3_300)
    out.append(show("dup0", rx.outbound, mlr) + " data=\(oracleOpt(rx.data.map(garminHex)))")
    rx = mlr.receive(peer(40, 2, [0xA2]), nowMs: 3_310)
    out.append(show("gap2", rx.outbound, mlr) + " data=\(oracleOpt(rx.data.map(garminHex)))")
    for seq in 1...5 {
        rx = mlr.receive(peer(40, seq, [UInt8(0xA0 + seq)]), nowMs: 3_320 + Int64(seq))
        out.append(show("in\(seq)", rx.outbound, mlr) + " data=\(oracleOpt(rx.data.map(garminHex)))")
    }
    rx = mlr.receive(peer(63, 6, []), nowMs: 3_400)
    out.append(show("bogusack", rx.outbound, mlr))
    rx = mlr.receive(peer(0, 6, []), nowMs: 3_500)
    out.append(show("ack0", rx.outbound, mlr))
    sentPackets = mlr.send(big, nowMs: 3_600)
    out.append(show("sendbig", sentPackets, mlr))
    rx = mlr.receive(peer(41, 6, [0xA6]), nowMs: 3_610)
    out.append(show("ack41+data6", rx.outbound, mlr) + " data=\(oracleOpt(rx.data.map(garminHex)))")
    out.append("mlr.wrong-handle \(mlr.receive(GarminMlrHeader(handle: 2, reqNum: 0, seq: 7).bytes + [1], nowMs: 3_700) == GarminMlrSession.Received(data: nil, outbound: []))")

    // ML control frames.
    for service in GarminService.allCases {
        out.append("ml.register \(service.rawValue) \(garminHex(GarminMultiLink.ControlRequest.register(service, reliable: false).encode())) \(garminHex(GarminMultiLink.ControlRequest.register(service, reliable: true).encode()))")
    }
    out.append("ml.close \(garminHex(GarminMultiLink.ControlRequest.closeHandle(.realtimeHeartRate, handle: 5).encode()))")
    out.append("ml.closeall \(garminHex(GarminMultiLink.ControlRequest.closeAll.encode())) \(garminHex(GarminMultiLink.ControlRequest.closeAll.encode(clientId: 0x0102_0304_0506_0708)))")
    let client: [UInt8] = [2, 0, 0, 0, 0, 0, 0, 0]
    let controlFixtures: [(String, [UInt8])] = [
        ("reg.ok", [0, 1] + client + [1, 0, 0, 1, 0]),
        ("reg.mlr", [0, 1] + client + [0x18, 0x20, 0, 0x82, 2]),
        ("reg.fail", [0, 1] + client + [6, 0, 3]),
        ("reg.short", [0, 1] + client + [6, 0]),
        ("close", [0, 3] + client + [6, 0, 4, 0]),
        ("closeall", [0, 6] + client + [0, 0, 0]),
        ("foreign", [0, 1, 9, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 1, 0]),
        ("echo", [0, 0] + client + [1, 0, 0]),
        ("unk", [0, 4] + client + [1, 0, 0]),
        ("nothandle0", [1, 1] + client + [1, 0, 0, 1, 0]),
    ]
    for (name, packet) in controlFixtures {
        out.append("ml.parse \(name) \(oracleDescribe(GarminMultiLink.parseControl(packet)))")
    }
    let routes: [[UInt8]] = [[], [0x00, 0x01], [0x01, 0xAA, 0xBB], [0xB2, 0x41, 0xCC], [0xA1, 0x00], [0x85, 0x10]]
    for n in routes {
        out.append("ml.route \(garminHex(n)) \(oracleDescribe(GarminMultiLink.route(n, reliableHandles: [0x82, 0x83])))")
    }
    out.append("ml.plain \(GarminMultiLink.plainPackets(big, handle: 3, mtu: 10).map(garminHex).joined(separator: ","))")
    out.append("ml.plain.mtu \(GarminMultiLink.plainPayloadSize(mtu: 23)) \(GarminMultiLink.plainPayloadSize(mtu: 515)) \(GarminMultiLink.plainPayloadSize(mtu: 2)) mlr=\(GarminMlrSession.payloadSize(mtu: 23))")

    // GFDI framing.
    let good = oracleFrame(5024, [1, 2, 3])
    var badCrc = good; badCrc[badCrc.count - 1] ^= 0xFF
    var badLen = good; badLen[0] = 200
    let gfdiCases: [(String, [UInt8])] = [
        ("good", good), ("compact", oracleFrame(0x8000 | 0x7F00 | 0x32, [9])), ("empty", oracleFrame(5031, [])),
        ("badcrc", badCrc), ("oversize", badLen), ("undersize", Array(good.dropLast())), ("short", [6, 0, 0x9F, 0x13, 0]),
    ]
    for (name, bytes) in gfdiCases { out.append("gfdi \(name) \(oracleDescribe(GarminGfdi.decode(bytes)))") }
    out.append("epoch \(GarminGfdi.garminTime(fromUnix: 1_767_225_600)) \(GarminGfdi.unixTime(fromGarmin: 0)) \(GarminGfdi.garminTime(fromUnix: 631_065_599))")

    // Every outbound case.
    for kind in GarminOutbound.Kind.allCases {
        for sample in oracleOutboundSamples(kind) {
            let frame = sample.frame().bytes
            let shown = frame.count <= 80 ? garminHex(frame) : "len=\(frame.count) fnv=\(oracleFnv32(frame))"
            out.append("out \(kind) \(shown)")
        }
    }

    // Every inbound codec.
    for (name, bytes) in oracleInboundFixtures() {
        switch GarminGfdi.decode(bytes) {
        case let .success(frame): out.append("in \(name) \(oracleDescribe(GarminInbound.decode(frame)))")
        case .failure: out.append("in \(name) frame-error \(oracleDescribe(GarminGfdi.decode(bytes)))")
        }
    }
    for (name, data) in oracleProtobufFixtures() {
        out.append("fa \(name) \(oracleDescribe(GarminFileAccessMessage.decodeSmart(data)))")
    }

    // Directory.
    var dir: [UInt8] = []
    for i in 0..<3 {
        dir += oracleLE16(i + 1)
        dir += [128, UInt8(4 + i)]
        dir += oracleLE16(i)
        dir += [0, 0]
        dir += oracleLE32(100 * i)
        dir += oracleLE32(i == 1 ? 0 : 1_000_000 + i)
    }
    out.append("dir \(GarminDirectoryEntry.parseDirectory(dir).map { $0.map(oracleDescribe).joined(separator: ",") } ?? "nil")")
    out.append("dir.bad \(GarminDirectoryEntry.parseDirectory(Array(dir.dropLast())).map { "\($0.count)" } ?? "nil") \(GarminDirectoryEntry.parseDirectory([])?.count ?? -1)")

    // String truncation at 255 UTF-8 bytes without splitting a code point.
    let longName = String(repeating: "a", count: 254) + "é" + "z"
    let host = GarminHostIdentity(protocolVersion: 1, productNumber: 2, unitNumber: 3, softwareVersion: 4,
                                  maxPacketSize: 5, bluetoothName: longName, manufacturer: "", model: "")
    let devReply = GarminOutbound.deviceInformationReply(host, protocolFlags: 0).message()
    out.append("str.truncate len=\(devReply.count) namelen=\(devReply[19]) fnv=\(oracleFnv32(devReply))")
    return out
}
