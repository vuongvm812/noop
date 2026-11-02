import Foundation

// MARK: - History sync: read-only FIT download
//
// The watch records its own 24/7 data as FIT files; this engine downloads the new ones. It is a pure state
// machine owned by `GarminSession`: the session feeds it every inbound message that belongs to a sync and
// performs what it returns. Like the session it has no clock or timer of its own. Kotlin twin:
// com.noop.garmin.GarminHistorySync.
//
// READ-ONLY. Nothing here can change a file on the watch: the outbound vocabulary is FILTER, DOWNLOAD,
// the per-chunk transfer reply, the read-only FileAccess list / pull requests, the pipe start request and
// SYNC_COMPLETE. Archiving (SET_FILE_FLAG), DELETE, ERASE and FileAccess ModifyFlags / Delete have no
// case in `GarminOutbound` at all. "Already downloaded" lives on the phone (`garminSyncedFile`), keyed by
// `GarminSyncFileKey`, so Garmin Connect still sees every file as new.
//
// Wire facts (Gadgetbridge, see ATTRIBUTION.md):
//
// Legacy GFDI flow (the Forerunner 165's):
//   watch 5037 SYNCHRONIZATION → phone 5007 FILTER (3, only new) → watch 5000 ack
//   phone 5002 DOWNLOAD_REQUEST index 0 (the directory) → watch 5000 ack [downloadStatus u8][size u32]
//   watch 5004 FILE_TRANSFER_DATA [flags u8][runningCrc u16][offset u32][data…] → phone 5000
//         [transferStatus u8][nextOffset u32], until `size` bytes have arrived. `runningCrc` is the
//         CRC-16/ARC of the whole file so far, seeded with the previous chunk's value.
//   the directory is 16-byte entries (`GarminDirectoryEntry`); each wanted file is fetched the same way;
//   a transfer cut short is resumed with request type CONTINUE from the last acked offset, seeded with
//   the CRC so far. Phone 5030 SYNC_COMPLETE ends the session.
//   OBSERVED on a Forerunner 165 (fw 29.05): after a reconnect the watch answers CONTINUE by sending the
//   file again from offset 0 (also for NEW at an offset). The engine then takes the file from the top;
//   the finished file is still checked end to end, so a restart costs radio time, never correctness.
//   Compression (DownloadRequest flag 0x01), OBSERVED on a Forerunner 165 (fw 29.05, 2026-11-01): the
//   watch then sends 5054 COMPRESSED_FILE_DATA instead of 5004, each carrying a sequence number, a
//   "last" flag, a checkpoint (uncompressed bytes covered so far) with the CRC-16 of the file up to it,
//   and the next piece of ONE zlib stream for the whole file (`GarminInbound` has the layout). Every
//   checkpoint CRC is checked against the inflated file, which must also pass zlib's Adler-32 and
//   come out at exactly the announced size. A compressed transfer cut short is resumed UNcompressed
//   (CONTINUE) from the bytes its stream prefix decodes to, checked against the last checkpoint.
//   A sync the phone starts (on connect, foreground, "Sync now") skips the FILTER and asks for the
//   directory straight away, as Gadgetbridge does.
// Protobuf FileAccess flow (newer watches):
//   optional legacy directory download first, which makes the watch flush partial monitor files (some
//   firmwares freeze on it, Gadgetbridge #6700, so it is per model); then 5043 ItemListRequest (paged by
//   the response's session id) → 5044 ItemListResponse; for each wanted item 5043 PullItemDataRequest →
//   5044 PullItemDataResponse [transfer handle]; the phone registers a file-transfer ML service, writes
//   the pipe start request carrying that transfer handle; the watch's first message on the new handle
//   is an acknowledgement starting `00 00 00`, every later one is the next piece of ONE zlib stream for
//   the file, and the watch closes the handle when it is done.

/// The identity of one file on the watch: everything the directory says about it. A file the watch
/// rewrites (the current day's monitor file grows) gets a new size or timestamp and so a new key, which
/// is what makes it "new" again. Stored as `garminSyncedFile` (child 3); the column order is this order.
public struct GarminSyncFileKey: Hashable, Comparable, Sendable {
    public let fileIndex: UInt16
    public let dataType: UInt8
    public let subType: UInt8
    public let fileNumber: UInt16
    /// Garmin-epoch seconds (0 = the watch gave no date).
    public let garminTimestamp: UInt32
    public let size: UInt32

    public init(fileIndex: UInt16, dataType: UInt8, subType: UInt8, fileNumber: UInt16,
                garminTimestamp: UInt32, size: UInt32) {
        self.fileIndex = fileIndex
        self.dataType = dataType
        self.subType = subType
        self.fileNumber = fileNumber
        self.garminTimestamp = garminTimestamp
        self.size = size
    }

    public init(_ entry: GarminDirectoryEntry) {
        self.init(fileIndex: entry.fileIndex, dataType: entry.dataType, subType: entry.subType,
                  fileNumber: entry.fileNumber, garminTimestamp: entry.garminTimestamp, size: entry.fileSize)
    }

    /// The `dataType` NOOP records for an item listed by the FileAccess flow. 255 is not a GFDI FIT data
    /// type (128), so the two flows can never collide in the ledger.
    public static let fileAccessDataType: UInt8 = 255

    /// A FileAccess item has a 128-bit uid instead of a directory index. The ledger key folds it with
    /// FNV-1a (platform-neutral, the same on Kotlin) into fileIndex (low 16 bits) and fileNumber (high 16),
    /// and keeps the item's FIT sub-type (`typeName` "FIT_TYPE_<n>"), date (`itemTime`) and size. Nil when
    /// the item has no uid.
    public static func fileAccess(_ item: GarminItemReference, typeName: String? = nil) -> GarminSyncFileKey? {
        guard let uid = item.uid else { return nil }
        var hash: UInt32 = 0x811C_9DC5
        for half in [uid.mostSignificant, uid.leastSignificant] {
            for shift in stride(from: 56, through: 0, by: -8) {
                hash ^= UInt32(truncatingIfNeeded: half >> UInt64(shift)) & 0xFF
                hash = hash &* 0x0100_0193
            }
        }
        return GarminSyncFileKey(fileIndex: UInt16(truncatingIfNeeded: hash),
                                 dataType: fileAccessDataType, subType: fitSubType(typeName: typeName) ?? 0,
                                 fileNumber: UInt16(truncatingIfNeeded: hash >> 16),
                                 garminTimestamp: itemTime(item),
                                 size: UInt32(truncatingIfNeeded: item.dataSize ?? 0))
    }

    /// The FIT sub-type a FileAccess type name stands for: "FIT_TYPE_32" is 32 (MONITOR). OBSERVED on a
    /// Forerunner 165 (fw 29.05): every FIT item is named this way, so the name maps onto the legacy
    /// directory's sub-types. Nil for any other name.
    public static func fitSubType(typeName: String?) -> UInt8? {
        let prefix = "FIT_TYPE_"
        guard let name = typeName, name.hasPrefix(prefix) else { return nil }
        let digits = name.dropFirst(prefix.count)
        guard !digits.isEmpty, digits.count <= 3, digits.allSatisfy({ $0.isASCII && $0.isNumber }) else { return nil }
        return UInt8(digits)
    }

    /// An item's Garmin-epoch date: its modified time, else the top 32 bits of the uid, which are the
    /// file's creation time (Gadgetbridge fact; on a Forerunner 165 they equal the legacy directory's
    /// timestamp for the same file, and the watch sends no modified time). 0 when neither is known.
    public static func itemTime(_ item: GarminItemReference) -> UInt32 {
        if let modified = item.modifiedTime, modified != 0 { return modified }
        let created = UInt32(truncatingIfNeeded: (item.uid?.mostSignificant ?? 0) >> 32)
        return created == UInt32.max ? 0 : created
    }

    public static func < (a: GarminSyncFileKey, b: GarminSyncFileKey) -> Bool {
        (a.garminTimestamp, a.fileIndex, a.dataType, a.subType, a.fileNumber, a.size)
            < (b.garminTimestamp, b.fileIndex, b.dataType, b.subType, b.fileNumber, b.size)
    }

    /// Compact diagnostic form: `128/32 #7 idx=12 size=4096 ts=1100000000`.
    public var label: String {
        "\(dataType)/\(subType) #\(fileNumber) idx=\(fileIndex) size=\(size) ts=\(garminTimestamp)"
    }
}

/// FIT file sub-types (data type 128) NOOP downloads. Wire facts (Gadgetbridge `FileType`).
public enum GarminFitFileType: UInt8, CaseIterable, Sendable {
    case activity = 4
    case monitorA = 15
    case monitorDaily = 28
    case monitor = 32
    case metrics = 44
    case sleep = 49
    case hrvStatus = 68
    case skinTemp = 73

    public static let fitDataType: UInt8 = 128
    public static let wantedSubTypes: Set<UInt8> = Set(allCases.map(\.rawValue))

    public var name: String {
        switch self {
        case .activity: return "ACTIVITY"
        case .monitorA: return "MONITOR_A"
        case .monitorDaily: return "MONITOR_DAILY"
        case .monitor: return "MONITOR"
        case .metrics: return "METRICS"
        case .sleep: return "SLEEP"
        case .hrvStatus: return "HRV_STATUS"
        case .skinTemp: return "SKIN_TEMP"
        }
    }
}

public enum GarminSyncFlow: Equatable, Sendable {
    /// GFDI directory + DOWNLOAD_REQUEST. The default (Gadgetbridge's default for the Forerunner 165).
    case legacy
    /// Protobuf FileAccess. `legacyFlush` first downloads the legacy directory so the watch flushes its
    /// partial monitor files; false for models that freeze on that request (Gadgetbridge #6700).
    case fileAccess(legacyFlush: Bool)
}

/// Where an interrupted transfer stopped: the bytes received and checked so far. The platform keeps it
/// across a reconnect and hands it back in the next `GarminSyncRequest`; the transfer then continues
/// from `offset` (request type CONTINUE) instead of starting over. Only the legacy flow resumes.
public struct GarminSyncResume: Equatable, Sendable {
    public let key: GarminSyncFileKey
    /// The file's first bytes (uncompressed), every one of them checked.
    public let data: [UInt8]
    /// CRC-16/ARC of `data`: the seed of the CONTINUE request.
    public let runningCrc: UInt16
    public var offset: UInt32 { UInt32(data.count) }

    public init(key: GarminSyncFileKey, data: [UInt8], runningCrc: UInt16) {
        self.key = key
        self.data = data
        self.runningCrc = runningCrc
    }
}

public struct GarminSyncRequest: Equatable, Sendable {
    public var flow: GarminSyncFlow
    /// Files already ingested (the `garminSyncedFile` ledger). Never downloaded again.
    public var synced: Set<GarminSyncFileKey>
    public var resume: GarminSyncResume?
    /// DownloadRequest flag 0x01 (`GarminRadioPolicy.requestsCompression`), for wanted files only.
    public var requestCompression: Bool
    /// The watch asked (5037): answer with FILTER first, as the watch expects.
    public var watchInitiated: Bool
    /// Files dated before this Unix time are skipped (bounds the first sync's backlog). nil = no bound.
    public var notBeforeUnix: Int64?
    /// Larger files are skipped (diagnosed as such), so one odd file cannot hold the radio for minutes.
    public var maxFileBytes: UInt32

    public init(flow: GarminSyncFlow = .legacy, synced: Set<GarminSyncFileKey> = [], resume: GarminSyncResume? = nil,
                requestCompression: Bool = false, watchInitiated: Bool = false, notBeforeUnix: Int64? = nil,
                maxFileBytes: UInt32 = 16 << 20) {
        self.flow = flow
        self.synced = synced
        self.resume = resume
        self.requestCompression = requestCompression
        self.watchInitiated = watchInitiated
        self.notBeforeUnix = notBeforeUnix
        self.maxFileBytes = maxFileBytes
    }
}

/// One file fully downloaded and checked (every chunk CRC, or the zlib checksum), ready to decode.
public struct GarminDownloadedFile: Equatable, Sendable {
    public let key: GarminSyncFileKey
    /// The FIT bytes. Inflated already when the watch sent the file compressed.
    public let bytes: [UInt8]
    /// The offset this transfer resumed from, nil when it started at 0.
    public let resumedFrom: UInt32?
    /// FileAccess type name when the watch gave one (diagnostics), nil for the legacy flow.
    public let typeName: String?

    /// The FIT sub-type, from the directory entry or the FileAccess type name.
    public var fitType: GarminFitFileType? { GarminFitFileType(rawValue: key.subType) }
}

public struct GarminSyncSummary: Equatable, Sendable {
    public enum Outcome: Equatable, Sendable {
        case completed
        /// The link or the GFDI handle went away mid-sync. A resume point may be held.
        case interrupted(String)
        case failed(String)

        public var label: String {
            switch self {
            case .completed: return "completed"
            case let .interrupted(why): return "interrupted(\(why))"
            case let .failed(why): return "failed(\(why))"
            }
        }
    }

    public var outcome: Outcome = .completed
    /// Entries the watch listed (directory or item list).
    public var listed = 0
    /// Of those, FIT files of a wanted type.
    public var wanted = 0
    public var alreadySynced = 0
    public var skippedOld = 0
    public var skippedLarge = 0
    public var downloaded = 0
    public var failed = 0
    public var resumed = 0
    /// File bytes received over the air (after a resume, only the new part).
    public var bytes = 0
    /// Every listed file of a wanted FIT type (the entries `wanted` counts), sorted. The Test Centre's
    /// "on the watch vs ingested" list. Not part of `line`.
    public var onWatch: [GarminSyncFileKey] = []

    public init() {}

    /// True when nothing new arrived: the empty-sync backoff counts these.
    public var isEmpty: Bool { downloaded == 0 }

    public var line: String {
        "sync \(outcome.label): listed=\(listed) wanted=\(wanted) synced=\(alreadySynced) old=\(skippedOld) "
            + "large=\(skippedLarge) downloaded=\(downloaded) failed=\(failed) resumed=\(resumed) bytes=\(bytes)"
    }
}

/// Which history flow a watch model syncs with.
public enum GarminSyncModelPolicy {
    /// Products verified to list and pull their files through FileAccess. The Forerunner 165 (product
    /// 4432, fw 29.05, 2026-11-01) lists only MONITOR files in its legacy directory but every FIT file,
    /// activities included, through FileAccess, and its legacy directory request (the flush) is safe.
    public static let fileAccessProducts: Set<UInt16> = [4432]
    /// Products whose firmware freezes on the legacy directory request that precedes a FileAccess list
    /// (Gadgetbridge #6700). None is known by product number yet; add one with the report that named it.
    public static let noLegacyFlushProducts: Set<UInt16> = []

    /// Legacy for every model not verified otherwise (Gadgetbridge's default).
    public static func flow(productNumber: UInt16?) -> GarminSyncFlow {
        guard let product = productNumber, fileAccessProducts.contains(product) else { return .legacy }
        return .fileAccess(legacyFlush: !noLegacyFlushProducts.contains(product))
    }
}

/// The start packet a phone writes on a FileAccess file-transfer handle: `[0][0][transferHandle u16][0][0]`.
/// Wire fact (Gadgetbridge `downloadFileFromServiceV2`). The only data NOOP writes on a non-GFDI handle.
public struct GarminPipeStartRequest: Equatable, Sendable {
    public let transferHandle: UInt16

    public func packet(mlHandle: UInt8) -> [UInt8] {
        [mlHandle, 0, 0, UInt8(transferHandle & 0xFF), UInt8(transferHandle >> 8), 0, 0]
    }
}

/// What the engine asks the session to do.
enum GarminSyncAction: Equatable {
    case send(GarminOutbound)
    case control(GarminMultiLink.ControlRequest)
    case pipe(GarminPipeStartRequest, mlHandle: UInt8)
    case log(rare: Bool, String)
    case file(GarminDownloadedFile)
    case finished(GarminSyncSummary)
    /// A file transfer started (true) or the sync ended (false); Android raises the connection priority.
    case transferActive(Bool)
}

struct GarminHistorySync {

    /// No progress for this long ends the sync (the resume point is kept).
    static let stallTimeoutMs: Int64 = 20_000
    /// Consecutive bad chunks (CRC or offset) before a file is given up for this sync.
    static let maxBadChunks = 3
    /// Status bytes of the phone's chunk reply (Gadgetbridge `TransferStatus` ordinals).
    static let transferOk: UInt8 = 0
    static let transferAbort: UInt8 = 2
    static let transferCrcMismatch: UInt8 = 3
    static let transferOffsetMismatch: UInt8 = 4
    /// The FileAccess "synced" flag uid (both halves 42405). Only ever ASKED for (getFlags), so the list
    /// shows whether Garmin Connect has synced an item; never set and never used to exclude items.
    static let syncedFlag = GarminUid(mostSignificant: 42_405, leastSignificant: 42_405)
    /// PullItemDataRequest priority and zlib window, as Gadgetbridge sends them.
    static let pullPriority: UInt32 = 24
    static let pullCompressionWindow: UInt32 = 15
    /// The ML service registered for a FileAccess pull.
    static let pipeService: GarminService = .fileTransfer2

    private enum Target: Equatable {
        case directory(flushOnly: Bool)
        case file(GarminSyncFileKey)
    }

    /// One legacy transfer in progress.
    private struct Transfer {
        let target: Target
        var expected: UInt32 = 0
        var data: [UInt8] = []
        var crc: UInt16 = 0
        var compressed: Bool
        /// The zlib stream received so far (each 5054 piece after its 8-byte header).
        var stream: [UInt8] = []
        /// The checkpoints the watch sent (uncompressed offset, CRC of the file up to it).
        var checkpoints: [(UInt32, UInt16)] = []
        let resumedFrom: UInt32?
        var badChunks = 0
        var sizeKnown = false
        /// The size the watch announced for this request (for a CONTINUE, the bytes left).
        var reported: UInt32 = 0
        /// Payload bytes of the compressed pieces, the offset the phone acknowledges.
        var received = 0
        /// Compressed pieces received (their sequence numbers count up from 0).
        var pieces = 0
    }

    private enum Stage: Equatable {
        case idle
        case awaitingFilter
        case awaitingDownloadStatus
        case receiving
        case awaitingList(requestId: UInt16)
        case awaitingPull(requestId: UInt16)
        case registeringPipe
        case receivingPipe(mlHandle: UInt8)
    }

    private(set) var request: GarminSyncRequest?
    private var stage: Stage = .idle
    private var transfer: Transfer?
    private var queue: [GarminSyncFileKey] = []
    private var items: [GarminSyncFileKey: GarminItemReference] = [:]
    /// typeNameKey → name, for the list page being read. The watch names a type on its first item only
    /// and REUSES key numbers across pages (a Forerunner 165 does), so the map is per page.
    private var itemNames: [UInt32: String] = [:]
    /// Each wanted item's name, resolved while its page was read.
    private var itemTypeNames: [GarminSyncFileKey: String] = [:]
    private var currentItem: GarminSyncFileKey?
    private var pullTransferHandle: UInt16 = 0
    private var pipeData: [UInt8] = []
    /// The pipe's first message (the watch's acknowledgement) has arrived.
    private var pipeStarted = false
    private var listSession: UInt32?
    private var fragments: [UInt16: [UInt8]] = [:]
    private var nextRequestId: UInt16 = 1
    private var summary = GarminSyncSummary()
    private var resumeFailed: Set<GarminSyncFileKey> = []
    private(set) var deadlineMs: Int64?
    /// The clock of the call being handled.
    private var now: Int64 = 0
    private var actions: [GarminSyncAction] = []

    var isActive: Bool { stage != .idle }

    /// What an interrupted sync left behind, kept until the next `start` takes it.
    private(set) var heldResume: GarminSyncResume?

    /// The point an interrupted legacy file transfer could continue from: a wanted file with at least
    /// one checked chunk and bytes still to come. While idle, the point the last interruption held.
    var resumePoint: GarminSyncResume? {
        guard isActive else { return heldResume }
        if let t = transfer, case let .file(key) = t.target {
            let have = t.compressed ? Self.checkedPrefix(t) : t.data
            if !have.isEmpty, !t.sizeKnown || have.count < Int(t.expected) {
                return GarminSyncResume(key: key, data: have,
                                        runningCrc: t.compressed ? GarminCRC.crc16(have) : t.crc)
            }
        }
        return request?.resume
    }

    // MARK: Entry points

    mutating func start(_ request: GarminSyncRequest, nowMs: Int64) -> [GarminSyncAction] {
        guard stage == .idle else {
            note(false, "sync already running; trigger ignored")
            return flush()
        }
        self.request = request
        heldResume = nil
        now = nowMs
        summary = GarminSyncSummary()
        queue = []
        items = [:]
        itemNames = [:]
        itemTypeNames = [:]
        listSession = nil
        fragments = [:]
        resumeFailed = []
        let flowName: String
        switch request.flow {
        case .legacy: flowName = "legacy"
        case let .fileAccess(flush): flowName = "file-access flush=\(flush)"
        }
        note(true, "sync start flow=\(flowName) synced=\(request.synced.count) "
             + "resume=\(request.resume.map { "\($0.key.label)@\($0.offset)" } ?? "none") "
             + "compression=\(request.requestCompression) watchInitiated=\(request.watchInitiated)")
        actions.append(.transferActive(true))
        switch request.flow {
        case .legacy, .fileAccess(legacyFlush: true):
            if request.watchInitiated {
                send(.filter(.onlyNew))
                stage = .awaitingFilter
                touch(nowMs)
            } else {
                requestDirectory(flushOnly: request.flow != .legacy, nowMs)
            }
        case .fileAccess(legacyFlush: false):
            requestList(nowMs)
        }
        return flush()
    }

    mutating func tick(nowMs: Int64) -> [GarminSyncAction] {
        now = nowMs
        if let deadline = deadlineMs, nowMs >= deadline {
            finish(.failed("stalled in \(stageLabel) for \(Self.stallTimeoutMs) ms"))
        }
        return flush()
    }

    /// The GFDI link went away. Ends the sync without sending anything; `resumePoint` keeps what could
    /// be continued.
    mutating func interrupt(_ reason: String) -> [GarminSyncAction] {
        guard isActive else { return flush() }
        let held = resumePoint
        finish(.interrupted(reason), sendComplete: false)
        heldResume = held
        if let held { note(true, "holding resume point \(held.key.label)@\(held.offset)") }
        return flush()
    }

    // MARK: Inbound (all return true when the message belonged to the sync)

    /// A 5000 reply from the watch to one of the sync's requests.
    mutating func handleStatus(original: UInt16, status: UInt8, detail: GarminStatusDetail,
                               nowMs: Int64) -> (Bool, [GarminSyncAction]) {
        now = nowMs
        switch (GarminMessageType(rawValue: original), stage) {
        case (.filter?, .awaitingFilter):
            note(false, "filter acknowledged status=\(status)")
            requestDirectory(flushOnly: request?.flow != .legacy, nowMs)
            return (true, flush())
        case (.downloadRequest?, .awaitingDownloadStatus):
            downloadStatus(status: status, detail: detail, nowMs)
            return (true, flush())
        case (.protobufRequest?, .awaitingList), (.protobufRequest?, .awaitingPull):
            if status != GarminStatus.ack.rawValue {
                note(true, "watch refused FileAccess request status=\(status)")
                finish(.failed("FileAccess request refused status=\(status)"))
            }
            return (true, flush())
        default:
            return (false, [])
        }
    }

    mutating func handleChunk(flags: UInt8, runningCrc: UInt16, offset: UInt32, data: [UInt8],
                              nowMs: Int64) -> (Bool, [GarminSyncAction]) {
        now = nowMs
        guard stage == .receiving, var t = transfer else { return (false, []) }
        touch(nowMs)
        if t.compressed {
            guard t.stream.isEmpty else {
                finishBadStream("5004 chunk inside a compressed transfer")
                return (true, flush())
            }
            // The watch ignored the compression flag: an ordinary transfer.
            note(true, "watch sent \(describe(t.target)) uncompressed")
            t.compressed = false
        }
        if let from = t.resumedFrom, offset == 0, UInt32(t.data.count) == from {
            // The watch did not continue: it started the file over. Take it from the top.
            note(true, "watch restarted \(describe(t.target)) from 0 instead of continuing at \(from)")
            var size = t.reported
            if case let .file(key) = t.target { size = max(size, key.size) }
            t = Transfer(target: t.target, expected: size, compressed: false, resumedFrom: nil)
            t.reported = size
            t.sizeKnown = true
            summary.resumed -= 1
        }
        let have = UInt32(t.data.count)
        if offset != have {
            t.badChunks += 1
            note(true, "chunk at offset \(offset), expected \(have); asking for \(have)")
            transfer = t
            if t.badChunks >= Self.maxBadChunks { abortTransfer("offset mismatch") } else {
                send(.fileTransferDataReply(transferStatus: Self.transferOffsetMismatch, nextOffset: have))
            }
            return (true, flush())
        }
        let computed = GarminCRC.crc16(data, seed: t.crc)
        guard computed == runningCrc else {
            t.badChunks += 1
            note(true, "chunk crc \(runningCrc) != \(computed) at offset \(offset); asking again")
            transfer = t
            if t.badChunks >= Self.maxBadChunks { abortTransfer("crc mismatch") } else {
                send(.fileTransferDataReply(transferStatus: Self.transferCrcMismatch, nextOffset: have))
            }
            return (true, flush())
        }
        t.badChunks = 0
        t.crc = computed
        t.data += data
        summary.bytes += data.count
        if have == (t.resumedFrom ?? 0) {
            note(false, "first chunk \(data.count) bytes flags=\(flags) head=\(garminHex(Array(data.prefix(8))))")
        }
        transfer = t
        send(.fileTransferDataReply(transferStatus: Self.transferOk, nextOffset: UInt32(t.data.count)))
        if t.data.count >= Int(t.expected) { completeTransfer(nowMs) }
        return (true, flush())
    }

    /// The 5054 flag on a transfer's last piece.
    static let compressedLastFlag: UInt8 = 0x02

    /// A 5054 piece of a compressed transfer. The phone acknowledges each piece like a 5004 chunk, with
    /// the compressed bytes received so far as the next offset (HARDWARE-GATED: the Forerunner 165 keeps
    /// streaming whatever that value is, so its meaning to the watch is unconfirmed).
    mutating func handleCompressedChunk(sequence: UInt8, flags: UInt8, checkpoint: UInt32, checkpointCrc: UInt16,
                                        data: [UInt8], nowMs: Int64) -> (Bool, [GarminSyncAction]) {
        now = nowMs
        guard stage == .receiving, var t = transfer, t.compressed else { return (false, []) }
        touch(nowMs)
        guard sequence == UInt8(truncatingIfNeeded: t.pieces) else {
            finishBadStream("compressed piece \(sequence) where \(UInt8(truncatingIfNeeded: t.pieces)) was due")
            return (true, flush())
        }
        t.pieces += 1
        t.stream += data
        t.received += data.count
        if checkpoint > 0 { t.checkpoints.append((checkpoint, checkpointCrc)) }
        summary.bytes += data.count
        transfer = t
        send(.fileTransferDataReply(transferStatus: Self.transferOk, nextOffset: UInt32(t.received)))
        guard flags & Self.compressedLastFlag != 0 else { return (true, flush()) }
        guard let inflated = GarminInflate.zlib(t.stream, maxOutput: Int(t.expected)) else {
            finishBadStream("last piece arrived but the stream does not inflate")
            return (true, flush())
        }
        guard inflated.count == Int(t.expected), checkpoint == t.expected else {
            finishBadStream("stream inflated to \(inflated.count) bytes, watch announced \(t.expected) (last checkpoint \(checkpoint))")
            return (true, flush())
        }
        for (offset, crc) in t.checkpoints where GarminCRC.crc16(Array(inflated[0..<Int(offset)])) != crc {
            finishBadStream("checkpoint \(offset) CRC does not match the inflated file")
            return (true, flush())
        }
        t.data = inflated
        transfer = t
        completeTransfer(nowMs)
        return (true, flush())
    }

    /// The uncompressed bytes an unfinished compressed transfer has delivered, trimmed to the last
    /// checkpoint whose CRC they match (the stream's own checksum covers only a finished stream).
    private static func checkedPrefix(_ t: Transfer) -> [UInt8] {
        let prefix = GarminInflate.zlibPrefix(t.stream, maxOutput: Int(t.expected))
        for (offset, crc) in t.checkpoints.reversed() where Int(offset) <= prefix.count {
            let checked = Array(prefix[0..<Int(offset)])
            return GarminCRC.crc16(checked) == crc ? checked : []
        }
        return []
    }

    private mutating func finishBadStream(_ why: String) {
        guard let t = transfer else { return }
        note(true, "\(describe(t.target)): \(why); giving it up for this sync")
        send(.fileTransferDataReply(transferStatus: Self.transferAbort, nextOffset: UInt32(t.received)))
        transfer = nil
        summary.failed += 1
        nextFile(now)
    }

    /// A complete 5044 FileAccess message (already reassembled by the caller) during a FileAccess stage.
    mutating func handleChunkedResponse(_ chunk: GarminProtobufChunk, nowMs: Int64) -> (Bool, [GarminSyncAction]) {
        now = nowMs
        let ours: Bool
        switch stage {
        case let .awaitingList(id), let .awaitingPull(id): ours = chunk.requestId == id
        default: ours = false
        }
        guard ours else { return (false, []) }
        touch(nowMs)
        send(.protobufStatus(of: .response, requestId: chunk.requestId, dataOffset: chunk.dataOffset,
                             discard: false, code: 0))
        var buffer = chunk.dataOffset == 0 ? [] : fragments[chunk.requestId] ?? []
        guard buffer.count == Int(chunk.dataOffset) else {
            finish(.failed("FileAccess response chunk out of order"))
            return (true, flush())
        }
        buffer += chunk.data
        if buffer.count < Int(chunk.totalLength) {
            fragments[chunk.requestId] = buffer
            return (true, flush())
        }
        fragments[chunk.requestId] = nil
        guard let message = GarminFileAccessMessage.decodeSmart(buffer) else {
            finish(.failed("undecodable FileAccess response"))
            return (true, flush())
        }
        fileAccessResponse(message, nowMs)
        return (true, flush())
    }

    mutating func handleRegistered(service: UInt16, status: UInt8, handle: UInt8, nowMs: Int64) -> (Bool, [GarminSyncAction]) {
        now = nowMs
        guard stage == .registeringPipe, service == Self.pipeService.rawValue else { return (false, []) }
        touch(nowMs)
        guard status == 0 else {
            note(true, "file-transfer service registration refused status=\(status)")
            itemFailed("pipe refused")
            return (true, flush())
        }
        stage = .receivingPipe(mlHandle: handle)
        pipeData = []
        pipeStarted = false
        actions.append(.pipe(GarminPipeStartRequest(transferHandle: pullTransferHandle), mlHandle: handle))
        note(false, "-> pipe start transfer=\(pullTransferHandle) on handle \(handle)")
        return (true, flush())
    }

    mutating func handlePipeData(handle: UInt8, payload: [UInt8], nowMs: Int64) -> (Bool, [GarminSyncAction]) {
        now = nowMs
        guard case let .receivingPipe(h) = stage, h == handle else { return (false, []) }
        touch(nowMs)
        guard pipeStarted else {
            guard payload.count >= 3, payload[0] == 0, payload[1] == 0, payload[2] == 0 else {
                note(true, "pipe opened with \(garminHex(Array(payload.prefix(8)))), not an acknowledgement")
                actions.append(.control(.closeHandle(Self.pipeService, handle: handle)))
                itemFailed("pipe start refused")
                return (true, flush())
            }
            note(false, "pipe acknowledged \(garminHex(payload))")
            pipeStarted = true
            return (true, flush())
        }
        pipeData += payload
        summary.bytes += payload.count
        return (true, flush())
    }

    mutating func handleClosed(service: UInt16, handle: UInt8, nowMs: Int64) -> (Bool, [GarminSyncAction]) {
        now = nowMs
        guard case let .receivingPipe(h) = stage, h == handle, service == Self.pipeService.rawValue else {
            return (false, [])
        }
        touch(nowMs)
        guard let key = currentItem else { return (true, flush()) }
        guard let inflated = GarminInflate.zlib(pipeData) else {
            note(true, "FileAccess item \(key.label): \(pipeData.count) bytes did not inflate")
            itemFailed("inflate")
            return (true, flush())
        }
        let name = itemTypeNames[key]
        summary.downloaded += 1
        note(true, "downloaded \(name ?? "item") \(key.label) (\(pipeData.count) -> \(inflated.count) bytes)")
        actions.append(.file(GarminDownloadedFile(key: key, bytes: inflated, resumedFrom: nil, typeName: name)))
        currentItem = nil
        pullNext(nowMs)
        return (true, flush())
    }

    // MARK: Legacy flow

    private mutating func requestDirectory(flushOnly: Bool, _ nowMs: Int64) {
        transfer = Transfer(target: .directory(flushOnly: flushOnly), compressed: false, resumedFrom: nil)
        send(.downloadRequest(fileIndex: 0, dataOffset: 0, kind: .new, crcSeed: 0, dataSize: 0, flags: 0))
        stage = .awaitingDownloadStatus
        touch(nowMs)
    }

    private mutating func requestFile(_ key: GarminSyncFileKey, _ nowMs: Int64) {
        let compress = request?.requestCompression ?? false
        if let resume = request?.resume, resume.key == key, !resumeFailed.contains(key), resume.offset > 0 {
            transfer = Transfer(target: .file(key), data: resume.data, crc: resume.runningCrc,
                                compressed: false, resumedFrom: resume.offset)
            note(true, "resuming \(key.label) from offset \(resume.offset)")
            // The size is the bytes left: the watch range-checks offset + size (a Forerunner 165 refuses a
            // CONTINUE asking for the whole file again with download status 6).
            send(.downloadRequest(fileIndex: key.fileIndex, dataOffset: resume.offset, kind: .continue,
                                  crcSeed: resume.runningCrc, dataSize: key.size - min(key.size, resume.offset), flags: 0))
        } else {
            transfer = Transfer(target: .file(key), compressed: compress, resumedFrom: nil)
            send(.downloadRequest(fileIndex: key.fileIndex, dataOffset: 0, kind: .new, crcSeed: 0, dataSize: 0,
                                  flags: compress ? 1 : 0))
        }
        stage = .awaitingDownloadStatus
        touch(nowMs)
    }

    private mutating func downloadStatus(status: UInt8, detail: GarminStatusDetail, _ nowMs: Int64) {
        guard var t = transfer else { return }
        var downloadStatus: UInt8 = 0xFF
        var size: UInt32 = 0
        if case let .downloadRequest(s, max) = detail { downloadStatus = s; size = max }
        guard status == GarminStatus.ack.rawValue, downloadStatus == 0 else {
            note(true, "download of \(describe(t.target)) refused status=\(status) download=\(downloadStatus)")
            switch t.target {
            case .directory:
                finish(.failed("directory refused status=\(status) download=\(downloadStatus)"))
            case let .file(key):
                if t.resumedFrom != nil {
                    // The watch would not continue: start the file over once.
                    resumeFailed.insert(key)
                    note(true, "resume refused; restarting \(key.label) from 0")
                    requestFile(key, nowMs)
                } else {
                    summary.failed += 1
                    nextFile(nowMs)
                }
            }
            return
        }
        // On CONTINUE the watch reports the remaining size; on NEW the whole size.
        t.expected = size + (t.resumedFrom ?? 0)
        t.reported = size
        t.sizeKnown = true
        if case let .file(key) = t.target, t.resumedFrom == nil, !t.compressed, size != key.size {
            note(true, "\(key.label): watch reports \(size) bytes")
        }
        if let from = t.resumedFrom {
            summary.resumed += 1
            note(true, "watch continues \(describe(t.target)) at \(from): \(size) bytes left")
        } else {
            note(false, "download \(describe(t.target)) size=\(size)")
        }
        transfer = t
        stage = .receiving
        touch(nowMs)
        if t.data.count >= Int(t.expected) { completeTransfer(nowMs) }
    }

    private mutating func completeTransfer(_ nowMs: Int64) {
        guard let t = transfer else { return }
        transfer = nil
        switch t.target {
        case let .directory(flushOnly):
            guard let entries = GarminDirectoryEntry.parseDirectory(t.data) else {
                finish(.failed("directory of \(t.data.count) bytes is not whole 16-byte entries"))
                return
            }
            if flushOnly {
                note(false, "legacy directory flushed (\(entries.count) entries); listing items")
                requestList(nowMs)
                return
            }
            planLegacy(entries)
            nextFile(nowMs)
        case let .file(key):
            let bytes = t.data
            // A FIT file ends in a CRC-16/ARC over everything before it, so the whole file checks to 0.
            // The one check that also covers a resumed file's two halves; a file whose trailer is the
            // 0x0000 "no CRC" value is let through and named.
            let fitCrcOk = bytes.count < 14 || GarminCRC.crc16(bytes) == 0
            let noCrc = bytes.count >= 2 && bytes[bytes.count - 2] == 0 && bytes[bytes.count - 1] == 0
            guard fitCrcOk || noCrc else {
                note(true, "\(key.label): FIT CRC does not check (\(bytes.count) bytes); dropped")
                summary.failed += 1
                if request?.resume?.key == key { request?.resume = nil }
                nextFile(nowMs)
                return
            }
            summary.downloaded += 1
            note(true, "downloaded \(GarminFitFileType(rawValue: key.subType)?.name ?? "file") \(key.label) "
                 + "(\(bytes.count) bytes\(t.compressed ? " from \(t.received) compressed" : "")"
                 + "\(t.resumedFrom.map { ", resumed at \($0)" } ?? "")\(fitCrcOk ? "" : ", no FIT CRC"))")
            actions.append(.file(GarminDownloadedFile(key: key, bytes: bytes, resumedFrom: t.resumedFrom, typeName: nil)))
            if request?.resume?.key == key { request?.resume = nil }
            nextFile(nowMs)
        }
    }

    private mutating func planLegacy(_ entries: [GarminDirectoryEntry]) {
        guard let request else { return }
        summary.listed = entries.count
        var wanted: [GarminSyncFileKey] = []
        var counts: [String: Int] = [:]
        for entry in entries {
            counts["\(entry.dataType)/\(entry.subType)", default: 0] += 1
            guard entry.dataType == GarminFitFileType.fitDataType,
                  GarminFitFileType.wantedSubTypes.contains(entry.subType) else { continue }
            summary.wanted += 1
            let key = GarminSyncFileKey(entry)
            summary.onWatch.append(key)
            if request.synced.contains(key) { summary.alreadySynced += 1; continue }
            if entry.fileSize == 0 { summary.alreadySynced += 1; continue }
            if let floor = request.notBeforeUnix, let unix = entry.unixTime, unix < floor, request.resume?.key != key {
                summary.skippedOld += 1; continue
            }
            if entry.fileSize > request.maxFileBytes { summary.skippedLarge += 1; continue }
            wanted.append(key)
        }
        let types = counts.keys.sorted().map { "\($0)x\(counts[$0]!)" }.joined(separator: " ")
        note(true, "directory: \(entries.count) entries [\(types)]; \(wanted.count) to download")
        // A held resume goes first, then oldest first so a day's files arrive in order.
        wanted.sort()
        if let resume = request.resume, let i = wanted.firstIndex(of: resume.key) {
            wanted.insert(wanted.remove(at: i), at: 0)
        } else if let resume = request.resume {
            note(true, "resume point \(resume.key.label) is no longer listed; dropped")
            self.request?.resume = nil
        }
        queue = wanted
    }

    private mutating func nextFile(_ nowMs: Int64) {
        guard !queue.isEmpty else {
            finish(.completed)
            return
        }
        requestFile(queue.removeFirst(), nowMs)
    }

    private mutating func abortTransfer(_ why: String) {
        guard let t = transfer else { return }
        send(.fileTransferDataReply(transferStatus: Self.transferAbort, nextOffset: UInt32(t.data.count)))
        note(true, "gave up \(describe(t.target)) after \(Self.maxBadChunks) bad chunks (\(why))")
        transfer = nil
        switch t.target {
        case .directory: finish(.failed("directory transfer: \(why)"))
        case .file:
            summary.failed += 1
            nextFile(now)
        }
    }

    // MARK: FileAccess flow

    private mutating func requestList(_ nowMs: Int64) {
        let id = takeRequestId()
        send(.fileAccessRequest(requestId: id, .itemList(sessionId: listSession, transactionId: nil, maxItemCount: nil,
                                                          excludeAllFlags: [], getFlags: [Self.syncedFlag])))
        stage = .awaitingList(requestId: id)
        touch(nowMs)
    }

    private mutating func fileAccessResponse(_ message: GarminFileAccessMessage, _ nowMs: Int64) {
        switch (message, stage) {
        case let (.itemListResponse(status, session, _, list, error), .awaitingList):
            if let status, status != 0 {
                note(true, "item list status=\(status) error=\(error.map(String.init) ?? "none")")
            }
            planItems(list)
            listSession = session
            if session != nil {
                requestList(nowMs)
            } else {
                note(true, "item list: \(summary.listed) items; \(queue.count) to download")
                pullNext(nowMs)
            }
        case let (.pullItemResponse(result, _, handle, _, error), .awaitingPull):
            guard result == nil || result == 0, let handle else {
                note(true, "pull refused result=\(result.map(String.init) ?? "none") error=\(error.map(String.init) ?? "none")")
                itemFailed("pull refused")
                return
            }
            pullTransferHandle = UInt16(truncatingIfNeeded: handle)
            stage = .registeringPipe
            actions.append(.control(.register(Self.pipeService, reliable: false)))
            note(false, "-> ML register file transfer for transfer handle \(handle)")
        default:
            note(true, "unexpected FileAccess message in \(stageLabel)")
        }
    }

    private mutating func planItems(_ list: [GarminItemReference]) {
        guard let request else { return }
        itemNames = [:]
        for item in list {
            summary.listed += 1
            if let key = item.dataType?.typeNameKey, let name = item.dataType?.typeName { itemNames[key] = name }
            let name = typeName(item)
            note(false, "item \(name ?? "untyped") uid=\(item.uid.map { garminHex64($0.mostSignificant) + ":" + garminHex64($0.leastSignificant) } ?? "-") "
                 + "size=\(item.dataSize.map(String.init) ?? "-") mtime=\(item.modifiedTime.map(String.init) ?? "-") "
                 + "flags=\(item.requestedFlags.map(String.init) ?? "-") format=\(item.dataType?.format.map(String.init) ?? "-") "
                 + "key=\(item.dataType?.typeNameKey.map(String.init) ?? "-") id=\(item.dataType?.fileIdentifier ?? "-")")
            guard let sub = GarminSyncFileKey.fitSubType(typeName: name), GarminFitFileType.wantedSubTypes.contains(sub),
                  let key = GarminSyncFileKey.fileAccess(item, typeName: name) else {
                continue
            }
            summary.wanted += 1
            summary.onWatch.append(key)
            if request.synced.contains(key) { summary.alreadySynced += 1; continue }
            if let floor = request.notBeforeUnix, key.garminTimestamp != 0,
               GarminGfdi.unixTime(fromGarmin: key.garminTimestamp) < floor { summary.skippedOld += 1; continue }
            if key.size > request.maxFileBytes { summary.skippedLarge += 1; continue }
            items[key] = item
            itemTypeNames[key] = name
            queue.append(key)
        }
        queue.sort()
    }

    private func typeName(_ item: GarminItemReference) -> String? {
        if let name = item.dataType?.typeName, !name.isEmpty { return name }
        if let key = item.dataType?.typeNameKey { return itemNames[key] }
        return nil
    }

    private mutating func pullNext(_ nowMs: Int64) {
        guard !queue.isEmpty else {
            finish(.completed)
            return
        }
        let key = queue.removeFirst()
        guard let item = items[key] else { pullNext(nowMs); return }
        currentItem = key
        let id = takeRequestId()
        send(.fileAccessRequest(requestId: id, .pullItem(item, priority: Self.pullPriority, offset: 0,
                                                          compressionWindow: Self.pullCompressionWindow)))
        stage = .awaitingPull(requestId: id)
        touch(nowMs)
    }

    private mutating func itemFailed(_ why: String) {
        summary.failed += 1
        currentItem = nil
        pullNext(now)
    }

    private mutating func takeRequestId() -> UInt16 {
        defer { nextRequestId = nextRequestId == UInt16.max ? 1 : nextRequestId + 1 }
        return nextRequestId
    }

    // MARK: Plumbing

    private mutating func finish(_ outcome: GarminSyncSummary.Outcome, sendComplete: Bool = true) {
        summary.outcome = outcome
        summary.onWatch.sort()
        if sendComplete { send(.systemEvent(.syncComplete, value: 0)) }
        note(true, summary.line)
        actions.append(.finished(summary))
        actions.append(.transferActive(false))
        stage = .idle
        transfer = nil
        queue = []
        currentItem = nil
        pipeData = []
        deadlineMs = nil
        request = nil
    }

    private mutating func touch(_ nowMs: Int64) { deadlineMs = nowMs + Self.stallTimeoutMs }

    private mutating func send(_ message: GarminOutbound) { actions.append(.send(message)) }

    private mutating func note(_ rare: Bool, _ text: String) { actions.append(.log(rare: rare, text)) }

    private mutating func flush() -> [GarminSyncAction] {
        defer { actions.removeAll() }
        return actions
    }

    private func describe(_ target: Target) -> String {
        switch target {
        case .directory: return "directory"
        case let .file(key): return key.label
        }
    }

    private var stageLabel: String {
        switch stage {
        case .idle: return "idle"
        case .awaitingFilter: return "awaiting-filter"
        case .awaitingDownloadStatus: return "awaiting-download-status"
        case .receiving: return "receiving"
        case .awaitingList: return "awaiting-list"
        case .awaitingPull: return "awaiting-pull"
        case .registeringPipe: return "registering-pipe"
        case .receivingPipe: return "receiving-pipe"
        }
    }
}
