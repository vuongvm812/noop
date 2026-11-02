import XCTest
@testable import GarminProtocol

/// History sync against the scripted fake watch (`OracleFakeWatch`): both flows, resume, compression,
/// the failure paths, idempotency, and the read-only rule. Kotlin twin: com.noop.garmin.GarminHistorySyncTest,
/// which also holds `garminSyncOracleLines()` as its expected literal (checked from this side below).
final class GarminHistorySyncTests: XCTestCase {

    /// Run a scripted sync and collect the files and summaries it produced.
    private func drive(_ watch: OracleFakeWatch, _ steps: [OracleSyncStep], session: GarminSession? = nil)
        -> (files: [GarminDownloadedFile], summaries: [GarminSyncSummary], session: GarminSession) {
        var files: [GarminDownloadedFile] = []
        var summaries: [GarminSyncSummary] = []
        var s = session ?? GarminSession(config: GarminSessionConfig(host: oracleSessionHost(), firstPairing: false,
                                                                      sendsHostLifecycleEvents: false),
                                         transport: .multiLinkV2, mtu: 150)
        var now: Int64 = 0
        var linkUp = true
        var inbox: [[UInt8]] = []
        func clock() -> GarminClock { GarminClock(nowMs: now, unixSeconds: 1_793_546_986, utcOffsetSeconds: 0) }
        func absorb(_ outputs: [GarminSessionOutput]) {
            for o in outputs {
                switch o {
                case let .write(bytes) where linkUp: inbox += watch.receive(bytes)
                case let .syncFile(f): files.append(f)
                case let .syncFinished(summary): summaries.append(summary)
                default: break
                }
            }
        }
        for step in steps {
            switch step {
            case .connect: absorb(s.start(clock()))
            case let .sync(request): absorb(s.startSync(request, clock()))
            case let .watchSends(bytes): now += 1; absorb(s.receive(bytes, clock()))
            case let .tick(ms): now += ms; absorb(s.tick(clock()))
            case .dropLink: linkUp = false; inbox.removeAll()
            }
            var guardCount = 0
            while linkUp, !inbox.isEmpty, guardCount < 10_000 {
                guardCount += 1
                now += 1
                absorb(s.receive(inbox.removeFirst(), clock()))
            }
        }
        return (files, summaries, s)
    }

    private let synced: Set<GarminSyncFileKey> = [GarminSyncFileKey(oracleEntry(3, 44, number: 3, size: 40, ts: oracleRecentTs + 120))]

    func testLegacyWatchInitiatedSyncDownloadsOnlyNewWantedFiles() {
        let watch = oracleStandardWatch()
        watch.corruptChunks = [2]
        let r = drive(watch, [.connect,
                            .sync(GarminSyncRequest(synced: synced, watchInitiated: true, notBeforeUnix: oracleNotBefore))])
        XCTAssertEqual(r.files.map(\.key.fileIndex), [1, 2])
        XCTAssertEqual(r.files[0].bytes, oracleFileBody(1, 150))
        XCTAssertEqual(r.files[1].bytes, oracleFileBody(2, 70))
        XCTAssertEqual(r.files.map { $0.fitType }, [.monitor, .sleep])
        let summary = try! XCTUnwrap(r.summaries.last)
        XCTAssertEqual(summary.outcome, .completed)
        XCTAssertEqual(summary.listed, 7)
        XCTAssertEqual(summary.wanted, 5)                 // 32, 49, 44, 32 (old), 68 (empty)
        XCTAssertEqual(summary.alreadySynced, 2)          // the ledger's METRICS file and the empty HRV file
        XCTAssertEqual(summary.skippedOld, 1)
        XCTAssertEqual(summary.downloaded, 2)
        // The Test Centre list: every wanted FIT file the watch listed, sorted, whatever happened to it.
        XCTAssertEqual(summary.onWatch.count, summary.wanted)
        XCTAssertEqual(summary.onWatch, summary.onWatch.sorted())
        XCTAssertTrue(Set(r.files.map(\.key)).isSubset(of: Set(summary.onWatch)))
        XCTAssertTrue(synced.isSubset(of: Set(summary.onWatch)), "already-ingested files are still on the list")
        XCTAssertTrue(watch.seen.contains("rx filter 3"), "a watch-initiated sync opens with FILTER only-new")
        XCTAssertTrue(watch.seen.contains("rx chunk reply status=3 next=0"), "a bad chunk CRC is asked for again")
        XCTAssertTrue(watch.seen.contains("rx system event 0"), "SYNC_COMPLETE ends the session")
    }

    func testPhoneStartedSyncSkipsTheFilter() {
        let watch = oracleStandardWatch()
        _ = drive(watch, [.connect, .sync(GarminSyncRequest(synced: synced, notBeforeUnix: oracleNotBefore))])
        XCTAssertFalse(watch.seen.contains { $0.hasPrefix("rx filter") })
    }

    func testResyncIsIdempotent() {
        let watch = oracleStandardWatch()
        let first = drive(watch, [.connect, .sync(GarminSyncRequest(synced: synced, notBeforeUnix: oracleNotBefore))])
        let ledger = synced.union(first.files.map(\.key))
        let second = drive(watch, [.sync(GarminSyncRequest(synced: ledger, notBeforeUnix: oracleNotBefore))], session: first.session)
        XCTAssertTrue(second.files.isEmpty)
        XCTAssertEqual(second.summaries.last?.isEmpty, true)
        XCTAssertEqual(second.summaries.last?.outcome, .completed)
    }

    func testInterruptedTransferResumesWithContinueWithoutCorruptionOrDuplicates() {
        let watch = oracleStandardWatch()
        watch.cutAfterChunks = 4
        let cut = drive(watch, [.connect, .sync(GarminSyncRequest(synced: synced, notBeforeUnix: oracleNotBefore)), .dropLink])
        XCTAssertTrue(cut.files.isEmpty)
        let held = try! XCTUnwrap(cut.session.syncResumePoint)
        XCTAssertEqual(held.offset, 128)
        XCTAssertEqual(held.data, Array(oracleFileBody(1, 150)[0..<128]))
        watch.cutAfterChunks = nil
        let resumed = drive(watch, [.connect, .sync(GarminSyncRequest(synced: synced, resume: held, notBeforeUnix: oracleNotBefore))])
        XCTAssertEqual(resumed.files.map(\.key.fileIndex), [1, 2], "the held file first, each file once")
        XCTAssertEqual(resumed.files[0].bytes, oracleFileBody(1, 150))
        XCTAssertEqual(resumed.files[0].resumedFrom, 128)
        XCTAssertTrue(watch.seen.contains { $0.hasPrefix("rx download idx=1 offset=128 kind=0") && $0.contains("size=22") },
                      "CONTINUE from the held offset, asking for the bytes left")
    }

    func testAWatchThatRestartsOnContinueStillYieldsTheExactFile() {
        let watch = oracleStandardWatch()
        watch.restartOnContinue = true
        let partial = GarminSyncResume(key: GarminSyncFileKey(watch.directory[0]), data: Array(oracleFileBody(1, 150)[0..<64]),
                                       runningCrc: GarminCRC.crc16(Array(oracleFileBody(1, 150)[0..<64])))
        let r = drive(watch, [.connect, .sync(GarminSyncRequest(synced: synced, resume: partial, notBeforeUnix: oracleNotBefore))])
        XCTAssertEqual(r.files.filter { $0.key.fileIndex == 1 }.count, 1)
        XCTAssertEqual(r.files.first { $0.key.fileIndex == 1 }?.bytes, oracleFileBody(1, 150))
        XCTAssertEqual(r.summaries.last?.failed, 0)
    }

    func testRefusedContinueRestartsFromZeroOnce() {
        let watch = oracleStandardWatch()
        watch.refuseContinue = true
        let partial = GarminSyncResume(key: GarminSyncFileKey(watch.directory[1]), data: [1, 2, 3], runningCrc: GarminCRC.crc16([1, 2, 3]))
        let r = drive(watch, [.connect, .sync(GarminSyncRequest(synced: synced, resume: partial, notBeforeUnix: oracleNotBefore))])
        XCTAssertEqual(r.files.first?.key.fileIndex, 2)
        XCTAssertEqual(r.files.first?.bytes, oracleFileBody(2, 70))
    }

    func testCompressedTransferInflatesAndChecksEveryCheckpoint() {
        let watch = OracleFakeWatch()
        watch.directory = [oracleEntry(8, 32, number: 8, size: 416, ts: oracleRecentTs)]
        watch.files = [8: oracleZlibPlain()]
        watch.compressedFiles = [8: oracleZlibStream()]
        let r = drive(watch, [.connect, .sync(GarminSyncRequest(requestCompression: true))])
        XCTAssertEqual(r.files.map(\.bytes), [oracleZlibPlain()])
        XCTAssertTrue(watch.seen.contains { $0.contains("idx=8") && $0.contains("flags=1") })
        // A checkpoint CRC that does not match the inflated file drops the file.
        let bad = OracleFakeWatch()
        bad.directory = watch.directory
        bad.files = [8: oracleZlibPlain().map { $0 }]
        bad.files[8]![10] ^= 0xFF   // the watch's checkpoint CRCs now describe different bytes
        bad.compressedFiles = [8: oracleZlibStream()]
        let rb = drive(bad, [.connect, .sync(GarminSyncRequest(requestCompression: true))])
        XCTAssertTrue(rb.files.isEmpty)
        XCTAssertEqual(rb.summaries.last?.failed, 1)
    }

    func testFileAccessFlowListsPagesPullsAndInflates() {
        let watch = oracleStandardWatch()
        let item = GarminItemReference(uid: GarminUid(mostSignificant: 0x4567_0000_0000_0001, leastSignificant: 0xA1),
                                       dataType: GarminItemDataType(format: 3, typeName: "FIT_TYPE_32", typeNameKey: 32),
                                       dataSize: 416)
        let app = GarminItemReference(uid: GarminUid(mostSignificant: 1, leastSignificant: 0xA3),
                                      dataType: GarminItemDataType(typeName: "IQWatchApps"), dataSize: 5_000)
        watch.itemPages = [[app], [item]]
        watch.pullHandles = [0xA1: 7]
        watch.pipeFiles = [7: oracleZlibStream()]
        let r = drive(watch, [.connect, .sync(GarminSyncRequest(flow: .fileAccess(legacyFlush: false)))])
        XCTAssertEqual(r.files.map(\.bytes), [oracleZlibPlain()])
        XCTAssertEqual(r.files.first?.fitType, .monitor)
        XCTAssertEqual(r.files.first?.key.garminTimestamp, 0x4567_0000, "no modified time: the uid's creation time")
        XCTAssertEqual(r.summaries.last?.onWatch, r.files.map(\.key), "only the FIT item is listed, not the watch app")
        XCTAssertEqual(watch.seen.filter { $0.hasPrefix("rx item list") }.count, 2)
        XCTAssertFalse(watch.seen.contains { $0.hasPrefix("rx download") }, "no legacy flush when the model skips it")
        XCTAssertTrue(watch.seen.contains("ml register service=8216"))
    }

    func testLegacyFlushPrecedesTheItemList() {
        let watch = oracleStandardWatch()
        watch.itemPages = [[]]
        _ = drive(watch, [.connect, .sync(GarminSyncRequest(flow: .fileAccess(legacyFlush: true)))])
        let download = watch.seen.firstIndex { $0.hasPrefix("rx download idx=0") }
        let list = watch.seen.firstIndex { $0.hasPrefix("rx item list") }
        XCTAssertNotNil(download)
        XCTAssertLessThan(download!, list!)
    }

    func testStallTimesOutAndASecondStartWhileRunningIsIgnored() {
        let watch = oracleStandardWatch()
        watch.silent = true
        var s = GarminSession(config: GarminSessionConfig(host: oracleSessionHost(), firstPairing: false,
                                                           sendsHostLifecycleEvents: false), transport: .multiLinkV2, mtu: 150)
        let r = drive(watch, [.connect, .sync(GarminSyncRequest())], session: s)
        s = r.session
        XCTAssertTrue(s.isSyncing)
        let again = s.startSync(GarminSyncRequest(), GarminClock(nowMs: 100, unixSeconds: 0, utcOffsetSeconds: 0))
        XCTAssertFalse(again.contains { if case .write = $0 { return true }; return false })
        XCTAssertEqual(s.nextDeadlineMs, r.session.nextDeadlineMs)
        let timedOut = s.tick(GarminClock(nowMs: s.nextDeadlineMs!, unixSeconds: 0, utcOffsetSeconds: 0))
        XCTAssertTrue(timedOut.contains { if case let .syncFinished(sum) = $0, case .failed = sum.outcome { return true }; return false })
        XCTAssertFalse(s.isSyncing)
    }

    func testSyncBeforeTheHandshakeIsRefused() {
        var s = GarminSession(config: GarminSessionConfig(host: oracleSessionHost(), firstPairing: false,
                                                           sendsHostLifecycleEvents: false), transport: .multiLinkV2, mtu: 150)
        let out = s.startSync(GarminSyncRequest(), GarminClock(nowMs: 0, unixSeconds: 0, utcOffsetSeconds: 0))
        XCTAssertFalse(s.isSyncing)
        XCTAssertFalse(out.contains { if case .write = $0 { return true }; return false })
    }

    /// READ-ONLY: across every oracle trace the fake watch received only allowlisted message types, and
    /// never a file mutation (5003 upload, 5005 create, 5006 delete, 5008 set flag) or a FileAccess delete
    /// / modify-flags request.
    func testEverySyncTraceIsReadOnly() {
        let lines = garminSyncOracleLines()
        let typeLines = lines.filter { $0.hasPrefix("watch types") }
        XCTAssertGreaterThan(typeLines.count, 10)
        for line in typeLines {
            let types = line.dropFirst("watch types ".count).split(separator: ",").compactMap { UInt16($0) }
            for t in types {
                XCTAssertTrue(GarminOutboundAllowlistTests.allowedTypes.contains(t), "\(line)")
                XCTAssertFalse(GarminOutboundAllowlistTests.forbiddenTypes.contains(t), "\(line)")
            }
        }
        XCTAssertFalse(lines.contains { $0.contains("rx file access field 13") || $0.contains("rx file access field 15") })
    }

    func testInflateMatchesZlibVectorsAndRejectsCorruptStreams() {
        XCTAssertEqual(GarminInflate.zlib(oracleZlibStream()), oracleZlibPlain())
        XCTAssertEqual(GarminInflate.zlib(oracleHexBytes("7801011100eeff4e4f4f502073746f72656420626c6f636b34440619")),
                       Array("NOOP stored block".utf8))
        XCTAssertEqual(GarminInflate.zlib(oracleHexBytes("78014b4c4a4e8421053f7fff0000389005f5")), Array("abcabcabcabc NOOP".utf8))
        XCTAssertNil(GarminInflate.zlib(Array(oracleZlibStream().dropLast())))
        XCTAssertNil(GarminInflate.zlib(oracleZlibStream(), maxOutput: 100), "the output cap holds")
        let prefix = GarminInflate.zlibPrefix(Array(oracleZlibStream().prefix(150)))
        XCTAssertFalse(prefix.isEmpty)
        XCTAssertEqual(prefix, Array(oracleZlibPlain().prefix(prefix.count)), "a prefix decodes to the file's first bytes")
    }

    func testFileAccessNamesAndModelPolicy() {
        XCTAssertEqual(GarminSyncFileKey.fitSubType(typeName: "FIT_TYPE_49"), 49)
        XCTAssertNil(GarminSyncFileKey.fitSubType(typeName: "FIT_TYPE_256"))
        XCTAssertNil(GarminSyncFileKey.fitSubType(typeName: "BACKUP_PRIMARY"))
        XCTAssertEqual(GarminSyncModelPolicy.flow(productNumber: 4432), .fileAccess(legacyFlush: true))
        XCTAssertEqual(GarminSyncModelPolicy.flow(productNumber: 1), .legacy)
    }

    // MARK: Parity

    func testSwiftMatchesTheKotlinSyncOracleLiteral() throws {
        let relative = "android/app/src/test/java/com/noop/garmin/GarminHistorySyncTest.kt"
        var directory = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        var source: String?
        for _ in 0..<8 {
            let candidate = directory.appendingPathComponent(relative)
            if FileManager.default.fileExists(atPath: candidate.path) {
                source = try String(contentsOf: candidate, encoding: .utf8)
                break
            }
            directory = directory.deletingLastPathComponent()
        }
        guard let source else { throw XCTSkip("Kotlin oracle not found (package checked out without the android tree)") }
        let begin = try XCTUnwrap(source.range(of: "SYNC_ORACLE = \"\"\"\n"))
        let end = try XCTUnwrap(source.range(of: "\n\"\"\"", range: begin.upperBound..<source.endIndex))
        let expected = source[begin.upperBound..<end.lowerBound].components(separatedBy: "\n")
        let actual = garminSyncOracleLines()
        XCTAssertEqual(actual.count, expected.count)
        for (index, (a, e)) in zip(actual, expected).enumerated() where a != e {
            XCTFail("sync oracle line \(index + 1)\n  swift:  \(a)\n  kotlin: \(e)")
        }
    }
}
