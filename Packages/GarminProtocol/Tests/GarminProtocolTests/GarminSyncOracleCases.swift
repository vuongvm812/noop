#if !GARMIN_ORACLE_STANDALONE
@testable import GarminProtocol
#endif

// The history-sync parity oracle: a scripted fake watch (no hardware) answers everything `GarminSession`
// writes during a sync, for both flows and the failure paths. One printed line per session output plus
// what the watch saw. Re-implemented in android/.../com/noop/garmin/GarminSyncOracleCases.kt, and
// GarminHistorySyncTest.kt holds this function's standalone stdout as its expected literal:
//
//   cd Packages/GarminProtocol && swiftc -O -D GARMIN_ORACLE_STANDALONE Sources/GarminProtocol/*.swift \
//       Tests/GarminProtocolTests/GarminOracleCases.swift Tests/GarminProtocolTests/GarminSessionOracleCases.swift \
//       Tests/GarminProtocolTests/GarminSyncOracleCases.swift Oracle/main.swift -o /tmp/garmin-oracle \
//       && /tmp/garmin-oracle sync
//
// The fake follows the Gadgetbridge wire facts the engine is written against (ATTRIBUTION.md); the
// behaviours a real watch is not known to have (refusing CONTINUE, a corrupted chunk) are switches on it.

/// A scripted Garmin watch. It answers the ML handshake, the GFDI handshake, FILTER, DOWNLOAD_REQUEST
/// (NEW and CONTINUE) with chunked FILE_TRANSFER_DATA, the FileAccess list and pull requests, and the
/// file-transfer pipe. Every GFDI message it receives is recorded in `seen`.
final class OracleFakeWatch {
    let gfdiHandle: UInt8 = 0x65
    let pipeHandle: UInt8 = 0x66
    var directory: [GarminDirectoryEntry] = []
    var files: [UInt16: [UInt8]] = [:]
    var chunkSize = 64
    /// Chunk ordinals (counted over the watch's life) sent once with a wrong CRC.
    var corruptChunks: Set<Int> = []
    /// Stop sending after this many chunks (the link "drops"; the driver then stops delivering).
    var cutAfterChunks: Int?
    var refuseContinue = false
    /// Answer CONTINUE by sending the file again from 0 (what a Forerunner 165 does after a reconnect).
    var restartOnContinue = false
    /// zlib streams per file index, served as 5054 pieces when a download asks for compression.
    var compressedFiles: [UInt16: [UInt8]] = [:]
    var compressedPieceSize = 60
    var refuseDirectory = false
    /// Ignore DOWNLOAD_REQUEST entirely (the stall-timeout path).
    var silent = false
    /// FileAccess pages; every page but the last carries a session id.
    var itemPages: [[GarminItemReference]] = []
    var pipeFiles: [UInt16: [UInt8]] = [:]
    var pullHandles: [UInt64: UInt16] = [:]
    private(set) var seen: [String] = []
    private(set) var seenTypes: Set<UInt16> = []
    private var decoder = GarminCOBSStreamDecoder()
    private var data: [UInt8] = []
    private var crcBase: UInt16 = 0
    private var chunksSent = 0
    private var pageIndex = 0
    private var pendingPipe: UInt16?

    func directoryBytes() -> [UInt8] {
        var w = GarminByteWriter()
        for e in directory { e.write(&w) }
        return w.bytes
    }

    private func gfdi(_ type: UInt16, _ payload: [UInt8]) -> [UInt8] { [gfdiHandle] + GarminCOBS.encode(oracleFrame(type, payload)) }

    private func status(_ original: UInt16, _ rest: [UInt8] = []) -> [UInt8] {
        gfdi(5000, oracleLE16(Int(original)) + [0] + rest)
    }

    /// One write from the phone; returns the notifications the watch sends back, in order.
    func receive(_ write: [UInt8]) -> [[UInt8]] {
        guard let handle = write.first else { return [] }
        if handle == 0 { return control(write) }
        if handle == pipeHandle { return pipe(write) }
        guard handle == gfdiHandle else { return [] }
        var out: [[UInt8]] = []
        for message in decoder.feed(Array(write.dropFirst())) {
            guard case let .success(frame) = GarminGfdi.decode(message) else { seen.append("bad frame"); continue }
            seenTypes.insert(frame.type)
            out += gfdiMessage(frame)
        }
        return out
    }

    private func control(_ w: [UInt8]) -> [[UInt8]] {
        let type = w[1]
        let service = Int(w[10]) | Int(w[11]) << 8
        switch type {
        case 5:
            seen.append("ml close-all")
            return [oracleControl(6, oracleLE16(0) + [0])]
        case 0 where service == 1:
            seen.append("ml register gfdi")
            return [oracleControl(1, oracleLE16(1) + [0, gfdiHandle, 0]), gfdi(5024, Array(oracleFr165DeviceInformation()[4..<(oracleFr165DeviceInformation().count - 2)]))]
        case 0:
            seen.append("ml register service=\(service)")
            return [oracleControl(1, oracleLE16(service) + [0, pipeHandle, 0])]
        case 2:
            seen.append("ml close handle service=\(service) handle=\(w[12])")
            return []
        default:
            seen.append("ml type \(type)")
            return []
        }
    }

    private func pipe(_ w: [UInt8]) -> [[UInt8]] {
        let transfer = UInt16(w[3]) | UInt16(w[4]) << 8
        seen.append("pipe start transfer=\(transfer) bytes=\(garminHex(w))")
        guard let stream = pipeFiles[transfer] else { return [] }
        var out: [[UInt8]] = [[pipeHandle, 0, 0, 0, UInt8(transfer & 0xFF), UInt8(transfer >> 8)]]
        var i = 0
        while i < stream.count {
            out.append([pipeHandle] + stream[i..<min(i + 40, stream.count)])
            i += 40
        }
        out.append(oracleControl(3, oracleLE16(Int(GarminService.fileTransfer2.rawValue)) + [pipeHandle, 0]))
        return out
    }

    private func gfdiMessage(_ f: GarminGfdi.Frame) -> [[UInt8]] {
        var r = GarminByteReader(f.payload)
        switch f.type {
        case 5000:
            let original = r.u16() ?? 0
            let replyStatus = r.u8()
            // The device-information reply (not the plain ACK before it) is what the watch answers with 5050.
            if original == 5024, f.payload.count > 3 { return [gfdi(5050, [8, 0xFF, 0x01, 0, 0, 0, 0, 0, 0x80])] }
            if original == 5004, replyStatus == 0 {
                let transferStatus = r.u8() ?? 0xFF
                let next = Int(r.u32() ?? 0)
                seen.append("rx chunk reply status=\(transferStatus) next=\(next)")
                return nextChunk(from: next)
            }
            return []
        case 5050:
            seen.append("rx configuration")
            return [status(5050)]
        case 5030:
            seen.append("rx system event \(f.payload[0])")
            return [status(5030)]
        case 5007:
            seen.append("rx filter \(f.payload[0])")
            return [status(5007, [0])]
        case 5002:
            let index = r.u16()!, offset = Int(r.u32()!), kind = r.u8()!, seed = r.u16()!, size = r.u32()!, flags = r.u8()!
            seen.append("rx download idx=\(index) offset=\(offset) kind=\(kind) seed=\(seed) size=\(size) flags=\(flags)")
            if silent { return [] }
            let body = index == 0 ? directoryBytes() : files[index]
            guard let body, !(index == 0 && refuseDirectory) else { return [status(5002, [1] + oracleLE32(0))] }
            if kind == 0 {
                if refuseContinue { return [status(5002, [4] + oracleLE32(0))] }
                guard offset + Int(size) <= body.count else { return [status(5002, [6] + oracleLE32(0))] }
                guard offset <= body.count, GarminCRC.crc16(Array(body[0..<offset])) == seed else {
                    return [status(5002, [5] + oracleLE32(0))]
                }
            }
            if flags & 1 != 0, kind == 1, let stream = compressedFiles[index] {
                data = []   // the phone's replies to compressed pieces do not pace anything
                return [status(5002, [0] + oracleLE32(body.count))] + compressedPieces(stream, plain: body)
            }
            let start = kind == 0 && !restartOnContinue ? offset : 0
            data = body
            return [status(5002, [0] + oracleLE32(body.count - (kind == 0 ? offset : 0)))] + nextChunk(from: start)
        case 5043:
            return fileAccess(f.payload)
        default:
            seen.append("rx \(f.type)")
            return []
        }
    }

    /// The whole compressed transfer at once (the phone's replies do not pace it, as on a Forerunner 165).
    /// The checkpoint after piece i is the plain length covered by the stream so far, rounded down to
    /// 64 bytes, with the CRC of the plain file up to it; the last piece carries the file size and CRC.
    private func compressedPieces(_ stream: [UInt8], plain: [UInt8]) -> [[UInt8]] {
        var out: [[UInt8]] = []
        var i = 0, seq = 0, checkpoint = 0
        while i < stream.count {
            if let cut = cutAfterChunks, chunksSent >= cut { seen.append("link cut at piece \(seq)"); break }
            chunksSent += 1
            let end = min(i + compressedPieceSize, stream.count)
            let last = end == stream.count
            if last { checkpoint = plain.count }
            var header: [UInt8] = [UInt8(seq), last ? 0x02 : 0]
            header += oracleLE32(checkpoint) + oracleLE16(Int(GarminCRC.crc16(Array(plain[0..<checkpoint]))))
            out.append(gfdi(5054, header + Array(stream[i..<end])))
            if !last { checkpoint = (GarminInflate.zlibPrefix(Array(stream[0..<end])).count / 64) * 64 }
            i = end
            seq += 1
        }
        return out
    }

    private func nextChunk(from offset: Int) -> [[UInt8]] {
        guard offset < data.count else { return [] }
        if let cut = cutAfterChunks, chunksSent >= cut { seen.append("link cut at offset \(offset)"); return [] }
        let end = min(offset + chunkSize, data.count)
        let chunk = Array(data[offset..<end])
        var crc = GarminCRC.crc16(chunk, seed: GarminCRC.crc16(Array(data[0..<offset])))
        if corruptChunks.remove(chunksSent) != nil { crc ^= 0xFFFF }
        chunksSent += 1
        return [gfdi(5004, [0] + oracleLE16(Int(crc)) + oracleLE32(offset) + chunk)]
    }

    private func fileAccess(_ payload: [UInt8]) -> [[UInt8]] {
        var r = GarminByteReader(payload)
        let id = r.u16()!
        _ = r.u32(); _ = r.u32(); _ = r.u32()
        let smart = r.rest()
        let ack = status(5043, oracleLE16(Int(id)) + oracleLE32(0) + [0, 0])
        guard let fields = GarminProtoReader.fields(smart), let (_, value) = fields.first,
              case let .bytes(service) = value, let inner = GarminProtoReader.fields(service),
              let (field, _) = inner.first else { return [ack] }
        var body = GarminProtoWriter()
        if field == 9 {
            let page = pageIndex < itemPages.count ? itemPages[pageIndex] : []
            seen.append("rx item list page=\(pageIndex)")
            pageIndex += 1
            body.uint(1, 0)
            if pageIndex < itemPages.count { body.uint(2, 7) }
            for item in page { body.lengthDelimited(4, item.encode()) }
            var wrap = GarminProtoWriter(); wrap.lengthDelimited(10, body.bytes)
            var smartOut = GarminProtoWriter(); smartOut.lengthDelimited(43, wrap.bytes)
            // Split the response in two 5044 chunks to exercise reassembly.
            let all = smartOut.bytes
            let half = all.count / 2
            let first = Array(all[0..<half]), second = Array(all[half...])
            return [ack,
                    gfdi(5044, oracleLE16(Int(id)) + oracleLE32(0) + oracleLE32(all.count) + oracleLE32(first.count) + first),
                    gfdi(5044, oracleLE16(Int(id)) + oracleLE32(half) + oracleLE32(all.count) + oracleLE32(second.count) + second)]
        }
        if field == 1, case let .bytes(pull)? = inner.first?.1, let pf = GarminProtoReader.fields(pull),
           case let .bytes(itemBytes)? = pf.first(where: { $0.0 == 1 })?.1,
           let item = GarminItemReference.decode(itemBytes), let uid = item.uid {
            let handle = pullHandles[uid.leastSignificant]
            seen.append("rx pull item uid=\(oracleHex64(uid.leastSignificant)) -> \(handle.map(String.init) ?? "refused")")
            if let handle {
                body.uint(1, 0); body.uint(2, 0); body.uint(3, UInt64(handle))
            } else {
                body.uint(1, 2)
            }
            var wrap = GarminProtoWriter(); wrap.lengthDelimited(2, body.bytes)
            var smartOut = GarminProtoWriter(); smartOut.lengthDelimited(43, wrap.bytes)
            let all = smartOut.bytes
            return [ack, gfdi(5044, oracleLE16(Int(id)) + oracleLE32(0) + oracleLE32(all.count) + oracleLE32(all.count) + all)]
        }
        seen.append("rx file access field \(field)")
        return [ack]
    }
}

/// Deterministic file bodies: `count` bytes seeded by `seed`, the last two a FIT-style CRC trailer.
func oracleFileBody(_ seed: Int, _ count: Int) -> [UInt8] {
    oracleWithCrc((0..<(count - 2)).map { UInt8(truncatingIfNeeded: ($0 &* 31) &+ seed &* 7 &+ ($0 >> 4)) })
}

func oracleEntry(_ index: UInt16, _ subType: UInt8, number: UInt16, size: Int, ts: UInt32, dataType: UInt8 = 128) -> GarminDirectoryEntry {
    GarminDirectoryEntry(fileIndex: index, dataType: dataType, subType: subType, fileNumber: number, specificFlags: 0,
                         fileFlags: 0, fileSize: UInt32(size), garminTimestamp: ts)
}

/// A zlib stream (Python `zlib.compress(data, 9)`) of `oracleZlibPlain()`, the compressed / pipe body.
func oracleZlibStream() -> [UInt8] {
    oracleHexBytes("78da6350f5ca9fb2f31eb37144eda2e36f04cdd2ba375cfd25ef923973df630edda0f239673e8a5927b4ae3aff5fc5336ff28ebb4c46e1350b8fbd16304ded5a7fe5a79c73c68cbd8fd87502cb669ffe206a15dfb2f2dc3f658fdc49dbef301a86552f38fa8adf24a573dde51fb24ee9d3f73c64d30e289d75eabd88655cf38ab37f95dc73266ebbcd60105a35ffc84b3ee3e48eb597becb38a64ddbfd8055cbbf64e6c977c216b14dcbcffc5174cb9eb0f5d67ffd90ca79875ff01a25b5afb9f84dda2175eaaefb2c9a7ec5334ebc15328f695c76fab7826b56ff969bfff4822be61e7ace6398d8b6fac25729fb1490df347c8ba683fc16ddb0f414c86f7d9b6ffc05f9ede0336e0390dfbe48da2583fca6ee53380de4b7a8fa2527417eebdd74fd0fc86f079e72e983fca6e7e619a2509e9a9393975a5cac9059929aab4063a1177e00bc77bf0b")
}

/// The 416 bytes `oracleZlibStream()` inflates to: 414 bytes then their CRC-16 (a FIT-style trailer).
func oracleZlibPlain() -> [UInt8] {
    oracleWithCrc((0..<300).map { UInt8(truncatingIfNeeded: $0 * 37 + ($0 >> 3) * 11) }
                  + Array(String(repeating: ".FIT wellness item ", count: 6).utf8))
}

/// `body` followed by its CRC-16/ARC, little-endian: the whole then checks to 0, like a FIT file.
func oracleWithCrc(_ body: [UInt8]) -> [UInt8] {
    let crc = GarminCRC.crc16(body)
    return body + [UInt8(crc & 0xFF), UInt8(crc >> 8)]
}

func oracleHexBytes(_ hex: String) -> [UInt8] {
    let chars = Array(hex.utf8)
    func nibble(_ c: UInt8) -> UInt8 { c >= 97 ? c - 87 : c - 48 }
    return stride(from: 0, to: chars.count, by: 2).map { nibble(chars[$0]) << 4 | nibble(chars[$0 + 1]) }
}

func oracleSyncDescribe(_ o: GarminSessionOutput) -> String? {
    switch o {
    case let .write(bytes):
        guard bytes.first != 0 else { return "w ml \(garminHex(bytes))" }
        guard let body = GarminCOBS.decode(Array(bytes.dropFirst())), case let .success(frame) = GarminGfdi.decode(body) else {
            return "w raw \(garminHex(bytes))"
        }
        let p = frame.payload.count <= 40 ? garminHex(frame.payload) : "len=\(frame.payload.count) fnv=\(oracleFnv32(frame.payload))"
        return "w \(frame.type) \(p)"
    case let .log(line): return line.rare ? "log \(line.text)" : nil
    case let .syncFile(file):
        return "file \(file.key.label) n=\(file.bytes.count) fnv=\(oracleFnv32(file.bytes)) resumed=\(oracleOpt(file.resumedFrom)) "
            + "type=\(oracleOpt(file.typeName)) fit=\(oracleOpt(file.fitType?.name))"
    case let .syncFinished(summary): return "finished \(summary.line) empty=\(summary.isEmpty)"
    case let .syncAnnounced(a): return "announced \(a.label) filter=\(a.expectsFilter)"
    case let .transferActive(active): return "transfer \(active)"
    case let .phase(p): return "phase \(p.label)"
    case .battery, .watch, .pairingEventsSent: return nil
    }
}

enum OracleSyncStep {
    case connect
    case sync(GarminSyncRequest)
    case watchSends([UInt8])
    case tick(Int64)
    /// Stop delivering the watch's notifications (the link dropped).
    case dropLink
}

/// Run a scripted sync. Every phone write goes to the fake; its replies are delivered one per ms until
/// the exchange goes quiet. Returns the transcript and the session (for its resume point).
func oracleRunSync(_ name: String, _ watch: OracleFakeWatch, _ steps: [OracleSyncStep],
                   session existing: GarminSession? = nil) -> ([String], GarminSession) {
    var session = existing ?? GarminSession(config: GarminSessionConfig(host: oracleSessionHost(), firstPairing: false,
                                                                         sendsHostLifecycleEvents: false),
                                            transport: .multiLinkV2, mtu: 150)
    var out = ["trace \(name)"]
    var now: Int64 = 0
    var linkUp = true
    var inbox: [[UInt8]] = []
    func clock() -> GarminClock { GarminClock(nowMs: now, unixSeconds: 1_793_546_986 + now / 1000, utcOffsetSeconds: 25_200) }
    func absorb(_ outputs: [GarminSessionOutput]) {
        for o in outputs {
            if let line = oracleSyncDescribe(o) { out.append("@\(now) " + line) }
            if case let .write(bytes) = o, linkUp { inbox += watch.receive(bytes) }
        }
    }
    func pump() {
        var guardCount = 0
        while linkUp, !inbox.isEmpty, guardCount < 10_000 {
            guardCount += 1
            now += 1
            let n = inbox.removeFirst()
            absorb(session.receive(n, clock()))
        }
    }
    for step in steps {
        switch step {
        case .connect:
            absorb(session.start(clock()))
        case let .sync(request):
            absorb(session.startSync(request, clock()))
        case let .watchSends(bytes):
            now += 1
            absorb(session.receive(bytes, clock()))
        case let .tick(ms):
            now += ms
            absorb(session.tick(clock()))
        case .dropLink:
            linkUp = false
            inbox.removeAll()
        }
        pump()
    }
    out.append("watch saw " + watch.seen.joined(separator: " | "))
    out.append("watch types " + watch.seenTypes.sorted().map(String.init).joined(separator: ","))
    out.append("end syncing=\(session.isSyncing) resume=\(session.syncResumePoint.map { "\($0.key.label)@\($0.offset) crc=\($0.runningCrc)" } ?? "-") "
               + "deadline=\(oracleOpt(session.nextDeadlineMs))")
    return (out, session)
}

/// The ts every "recent" fixture file carries (Garmin epoch; 2026-10-31) and the lookback floor.
let oracleRecentTs: UInt32 = 1_161_900_000
let oracleNotBefore: Int64 = 1_792_000_000

func oracleStandardWatch() -> OracleFakeWatch {
    let w = OracleFakeWatch()
    w.directory = [
        oracleEntry(1, 32, number: 1, size: 150, ts: oracleRecentTs),        // MONITOR, new
        oracleEntry(2, 49, number: 2, size: 70, ts: oracleRecentTs + 60),    // SLEEP, new
        oracleEntry(3, 44, number: 3, size: 40, ts: oracleRecentTs + 120),   // METRICS, already synced
        oracleEntry(4, 3, number: 4, size: 90, ts: oracleRecentTs),          // SPORTS: not a wanted type
        oracleEntry(5, 32, number: 5, size: 64, ts: 1_000_000),              // MONITOR, older than the floor
        oracleEntry(6, 255, number: 6, size: 30, ts: oracleRecentTs, dataType: 8), // DEVICE_XML-like: not FIT
        oracleEntry(7, 68, number: 7, size: 0, ts: oracleRecentTs),          // HRV_STATUS, empty
    ]
    w.files = [1: oracleFileBody(1, 150), 2: oracleFileBody(2, 70), 3: oracleFileBody(3, 40),
               4: oracleFileBody(4, 90), 5: oracleFileBody(5, 64)]
    return w
}

func garminSyncOracleLines() -> [String] {
    var out: [String] = []
    let synced: Set<GarminSyncFileKey> = [GarminSyncFileKey(oracleEntry(3, 44, number: 3, size: 40, ts: oracleRecentTs + 120))]
    let handle: UInt8 = 0x65
    func ml(_ message: [UInt8]) -> [UInt8] { [handle] + GarminCOBS.encode(message) }

    // A: the watch announces data (5037); FILTER, directory, two downloads (one chunk corrupted once).
    let a = oracleStandardWatch()
    a.corruptChunks = [2]
    out += oracleRunSync("legacy-watch-initiated", a, [
        .connect,
        .watchSends(ml(oracleFrame(5037, [1, 4] + oracleLE32(0x0400_0028)))),
        .sync(GarminSyncRequest(synced: synced, requestCompression: false, watchInitiated: true, notBeforeUnix: oracleNotBefore)),
    ]).0

    // B: a phone-started sync is cut mid-file; a new session resumes it with CONTINUE.
    let b = oracleStandardWatch()
    b.cutAfterChunks = 4   // directory (2 chunks: 112 bytes) + 2 chunks of the first file
    let (bLines, bSession) = oracleRunSync("legacy-cut", b, [
        .connect,
        .sync(GarminSyncRequest(synced: synced, notBeforeUnix: oracleNotBefore)),
        .dropLink,
    ])
    out += bLines
    let held = bSession.syncResumePoint
    b.cutAfterChunks = nil
    out += oracleRunSync("legacy-resume", b, [
        .connect,
        .sync(GarminSyncRequest(synced: synced, resume: held, notBeforeUnix: oracleNotBefore)),
    ]).0

    // C: the watch will not CONTINUE; the file starts over from 0 once.
    let c = oracleStandardWatch()
    c.refuseContinue = true
    let partial = GarminSyncResume(key: GarminSyncFileKey(c.directory[1]), data: Array(oracleFileBody(2, 70)[0..<20]),
                                   runningCrc: GarminCRC.crc16(Array(oracleFileBody(2, 70)[0..<20])))
    out += oracleRunSync("legacy-resume-refused", c, [
        .connect,
        .sync(GarminSyncRequest(synced: synced, resume: partial, notBeforeUnix: oracleNotBefore)),
    ]).0

    // D: a resume point for a file the watch no longer lists is dropped; nothing new is an empty sync.
    let d = oracleStandardWatch()
    let gone = GarminSyncResume(key: GarminSyncFileKey(oracleEntry(9, 32, number: 9, size: 100, ts: oracleRecentTs)),
                                data: [1, 2, 3], runningCrc: GarminCRC.crc16([1, 2, 3]))
    let everything = Set(d.directory.map(GarminSyncFileKey.init))
    out += oracleRunSync("legacy-nothing-new", d, [
        .connect,
        .sync(GarminSyncRequest(synced: everything, resume: gone, notBeforeUnix: oracleNotBefore)),
    ]).0

    // E: directory refused, then a stall (the watch goes silent), then the GFDI handle closes mid-file.
    let e = oracleStandardWatch()
    e.refuseDirectory = true
    out += oracleRunSync("legacy-directory-refused", e, [.connect, .sync(GarminSyncRequest())]).0
    let f = oracleStandardWatch()
    f.silent = true
    out += oracleRunSync("legacy-stall", f, [.connect, .sync(GarminSyncRequest()), .tick(19_999), .tick(1)]).0
    let g = oracleStandardWatch()
    g.cutAfterChunks = 3
    out += oracleRunSync("legacy-gfdi-closed", g, [
        .connect,
        .sync(GarminSyncRequest(synced: synced, notBeforeUnix: oracleNotBefore)),
        .watchSends(oracleControl(3, oracleLE16(1) + [handle, 0])),
    ]).0

    // F: FileAccess flow with the legacy flush: paged item list (two pages, chunked responses), one
    // non-FIT name skipped, one already synced, one refused pull, one zlib file over the pipe.
    let h = oracleStandardWatch()
    let monitor = GarminItemReference(uid: GarminUid(mostSignificant: 0x4567_0000_0000_0001, leastSignificant: 0xA1),
                                      dataType: GarminItemDataType(format: 3, typeName: "FIT_TYPE_32", typeNameKey: 32),
                                      dataSize: 416, modifiedTime: oracleRecentTs)
    let sleep = GarminItemReference(uid: GarminUid(mostSignificant: 0x4567_0000_0000_0002, leastSignificant: 0xA2),
                                    dataType: GarminItemDataType(typeNameKey: 32), dataSize: 99, modifiedTime: oracleRecentTs + 5)
    let app = GarminItemReference(uid: GarminUid(mostSignificant: 1, leastSignificant: 0xA3),
                                  dataType: GarminItemDataType(typeName: "IQWatchApps"), dataSize: 5_000, modifiedTime: oracleRecentTs)
    let known = GarminItemReference(uid: GarminUid(mostSignificant: 2, leastSignificant: 0xA4),
                                    dataType: GarminItemDataType(typeName: "FIT_TYPE_49", typeNameKey: 32), dataSize: 77,
                                    modifiedTime: oracleRecentTs)
    let untyped = GarminItemReference(uid: GarminUid(mostSignificant: 3, leastSignificant: 0xA5), dataSize: 10)
    // Page 2 REUSES name key 32 for another type (as a Forerunner 165 does): `sleep` resolves to it.
    h.itemPages = [[monitor, app], [known, sleep, untyped]]
    h.pullHandles = [0xA1: 0x1234]
    h.pipeFiles = [0x1234: oracleZlibStream()]
    out += oracleRunSync("file-access-flush", h, [
        .connect,
        .sync(GarminSyncRequest(flow: .fileAccess(legacyFlush: true), synced: [GarminSyncFileKey.fileAccess(known, typeName: "FIT_TYPE_49")!],
                                notBeforeUnix: oracleNotBefore)),
    ]).0

    // G: FileAccess without the flush (a model that freezes on it), started by an ItemAdded notice.
    let i = oracleStandardWatch()
    i.itemPages = [[monitor]]
    i.pullHandles = [0xA1: 0x0042]
    i.pipeFiles = [0x0042: Array(oracleZlibStream().dropLast())]   // a truncated stream: checksum missing
    var added = GarminProtoWriter(); added.lengthDelimited(1, monitor.encode())
    var addedWrap = GarminProtoWriter(); addedWrap.lengthDelimited(12, added.bytes)
    var addedSmart = GarminProtoWriter(); addedSmart.lengthDelimited(43, addedWrap.bytes)
    let notice = addedSmart.bytes
    var button = GarminProtoWriter(); button.lengthDelimited(22, [])
    var buttonSmart = GarminProtoWriter(); buttonSmart.lengthDelimited(43, button.bytes)
    out += oracleRunSync("file-access-no-flush", i, [
        .connect,
        .watchSends(ml(oracleFrame(5043, oracleLE16(900) + oracleLE32(0) + oracleLE32(notice.count) + oracleLE32(notice.count) + notice))),
        .watchSends(ml(oracleFrame(5043, oracleLE16(901) + oracleLE32(0) + oracleLE32(buttonSmart.bytes.count)
                                   + oracleLE32(buttonSmart.bytes.count) + buttonSmart.bytes))),
        .watchSends(ml(oracleFrame(5009, Array(GarminSyncOracleEntryBytes(oracleEntry(1, 32, number: 1, size: 150, ts: oracleRecentTs)))))),
        .sync(GarminSyncRequest(flow: .fileAccess(legacyFlush: false))),
    ]).0

    // I: compression (the Forerunner 165's 5054 pieces): one file whole, one whose checkpoint CRC is
    // wrong (dropped, nothing stored), then a cut mid-stream that resumes uncompressed from the last
    // checkpoint, against a watch that restarts the file from 0 (as the Forerunner 165 does).
    func compressedWatch() -> OracleFakeWatch {
        let w = OracleFakeWatch()
        w.directory = [oracleEntry(8, 32, number: 8, size: 416, ts: oracleRecentTs),
                       oracleEntry(9, 49, number: 9, size: 416, ts: oracleRecentTs + 10)]
        w.files = [8: oracleZlibPlain(), 9: oracleZlibPlain()]
        w.compressedFiles = [8: oracleZlibStream(), 9: oracleZlibStream()]
        return w
    }
    let k = compressedWatch()
    var bad = oracleZlibStream()
    bad[bad.count - 1] ^= 0x01   // breaks the Adler-32: the last piece does not inflate
    k.compressedFiles[9] = bad
    out += oracleRunSync("compressed", k, [
        .connect,
        .sync(GarminSyncRequest(requestCompression: true, notBeforeUnix: oracleNotBefore)),
    ]).0
    let l = compressedWatch()
    l.compressedPieceSize = 120
    l.cutAfterChunks = 3   // the directory, then two pieces of the first file
    let (lLines, lSession) = oracleRunSync("compressed-cut", l, [
        .connect,
        .sync(GarminSyncRequest(requestCompression: true, notBeforeUnix: oracleNotBefore)),
        .dropLink,
    ])
    out += lLines
    // The link dropped after the first piece; deliver nothing more and read what can be resumed.
    l.restartOnContinue = true
    l.cutAfterChunks = nil
    out += oracleRunSync("compressed-resume-restarted", l, [
        .connect,
        .sync(GarminSyncRequest(resume: lSession.syncResumePoint, requestCompression: true, notBeforeUnix: oracleNotBefore)),
    ]).0
    let m = oracleStandardWatch()
    m.restartOnContinue = true
    let partialM = GarminSyncResume(key: GarminSyncFileKey(m.directory[0]), data: Array(oracleFileBody(1, 150)[0..<64]),
                                    runningCrc: GarminCRC.crc16(Array(oracleFileBody(1, 150)[0..<64])))
    out += oracleRunSync("legacy-continue-restarted", m, [
        .connect,
        .sync(GarminSyncRequest(synced: synced, resume: partialM, notBeforeUnix: oracleNotBefore)),
    ]).0

    // H: a sync before the handshake is refused.
    let j = oracleStandardWatch()
    out += oracleRunSync("not-ready", j, [.sync(GarminSyncRequest())]).0

    // Inflate vectors: stored, fixed and dynamic blocks, plus corrupt inputs.
    let vectors: [(String, String)] = [
        ("stored", "7801011100eeff4e4f4f502073746f72656420626c6f636b34440619"),
        ("fixed", "78014b4c4a4e8421053f7fff0000389005f5"),
        ("dynamic", "78dacbc9cc4b553050c84f5328c94855484f2ccacdcc53c8cdcfcb2cc92f5248cbcc49e5ca01a93024a8c288a00a63822a4c08aa3025a8c28ca08a51df8efa76d4b7a3bea5896f01ecb1db33"),
        ("bad-header", "7901011100eeff4e4f4f502073746f72656420626c6f636b34440619"),
        ("bad-adler", "7801011100eeff4e4f4f502073746f72656420626c6f636b34440618"),
        ("truncated", "78dacbc9cc4b553050c84f5328c94855"),
        ("empty", ""),
    ]
    for (name, hex) in vectors {
        let result = GarminInflate.zlib(oracleHexBytes(hex))
        out.append("inflate \(name) " + (result.map { "n=\($0.count) fnv=\(oracleFnv32($0))" } ?? "nil"))
    }
    let pipePlain = GarminInflate.zlib(oracleZlibStream())
    out.append("inflate pipe matches=\(pipePlain == oracleZlibPlain())")

    // Ledger keys.
    out.append("key fa \(GarminSyncFileKey.fileAccess(monitor, typeName: "FIT_TYPE_32")!.label)")
    out.append("key fa-none \(oracleOpt(GarminSyncFileKey.fileAccess(untyped)?.label))")
    out.append("key legacy \(GarminSyncFileKey(oracleEntry(0x1234, 49, number: 0xBEEF, size: 70_000, ts: 0xFEDC_BA98)).label)")
    out.append("wanted " + GarminFitFileType.allCases.map { "\($0.rawValue)=\($0.name)" }.joined(separator: ","))
    for name in ["FIT_TYPE_32", "FIT_TYPE_4", "FIT_TYPE_255", "FIT_TYPE_256", "FIT_TYPE_", "FIT_TYPE_-1", "FIT_TYPE_0032", "IQWatchApps", ""] {
        out.append("fit-subtype \(name.isEmpty ? "(empty)" : name)=\(oracleOpt(GarminSyncFileKey.fitSubType(typeName: name)))")
    }
    out.append("item-time modified=\(GarminSyncFileKey.itemTime(monitor)) uid=\(GarminSyncFileKey.itemTime(app)) "
               + "none=\(GarminSyncFileKey.itemTime(untyped)) "
               + "created=\(GarminSyncFileKey.itemTime(GarminItemReference(uid: GarminUid(mostSignificant: 0x4549_F016_0000_0001, leastSignificant: 1)))) "
               + "max=\(GarminSyncFileKey.itemTime(GarminItemReference(uid: GarminUid(mostSignificant: UInt64.max, leastSignificant: 1))))")
    for product in [UInt16(4432), 3990, 0] {
        out.append("flow product=\(product) \(GarminSyncModelPolicy.flow(productNumber: product))")
    }
    out.append("flow product=nil \(GarminSyncModelPolicy.flow(productNumber: nil))")
    return out
}

/// The 16 wire bytes of a directory entry (test side).
func GarminSyncOracleEntryBytes(_ e: GarminDirectoryEntry) -> [UInt8] {
    var w = GarminByteWriter()
    e.write(&w)
    return w.bytes
}
