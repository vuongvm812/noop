import XCTest
@testable import GarminProtocol

/// Realtime channels 8 / 10 / 13 (Garmin 6/10): the capture gate in the live-channel table, the capture line
/// and the CANDIDATE decoders. `garminCaptureOracleLines()` must reproduce, line for line, the literal pasted
/// into android/.../GarminCaptureTest.kt, which is that function's standalone stdout.
final class GarminCaptureTests: XCTestCase {

    private func kotlinOracle() throws -> [String] {
        let relative = "android/app/src/test/java/com/noop/garmin/GarminCaptureTest.kt"
        var directory = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        for _ in 0..<8 {
            let candidate = directory.appendingPathComponent(relative)
            if FileManager.default.fileExists(atPath: candidate.path) {
                let source = try String(contentsOf: candidate, encoding: .utf8)
                let begin = try XCTUnwrap(source.range(of: "ORACLE = \"\"\"\n"))
                let end = try XCTUnwrap(source.range(of: "\n\"\"\"", range: begin.upperBound..<source.endIndex))
                return source[begin.upperBound..<end.lowerBound].components(separatedBy: "\n")
            }
            directory = directory.deletingLastPathComponent()
        }
        throw XCTSkip("Kotlin oracle not found (package checked out without the android tree)")
    }

    func testSwiftMatchesTheKotlinCaptureOracleLiteral() throws {
        let expected = try kotlinOracle()
        let actual = garminCaptureOracleLines()
        XCTAssertEqual(actual.count, expected.count)
        for (index, (a, e)) in zip(actual, expected).enumerated() where a != e {
            XCTFail("capture oracle line \(index + 1)\n  swift:  \(a)\n  kotlin: \(e)")
        }
    }

    func testCandidatesAcceptOnlyTheObservedLengths() {
        XCTAssertNil(GarminCaloriesCandidate.decode([UInt8](repeating: 0, count: 7)))
        XCTAssertNil(GarminCaloriesCandidate.decode([UInt8](repeating: 0, count: 9)))
        XCTAssertNil(GarminIntensityCandidate.decode([UInt8](repeating: 0, count: 12)))
        XCTAssertNil(GarminStressCandidate.decode([0, 0]))
        XCTAssertEqual(GarminStressCandidate.decode([0xFC, 0xFF, 0, 0])?.level, -4)
        XCTAssertNil(GarminStressCandidate.decode([0xFC, 0xFF, 0, 0])?.score)
        XCTAssertEqual(GarminStressCandidate.decode([100, 0, 0, 0])?.score, 100)
    }

    /// Without the capture flag the undecoded channels never reach the wire, whoever asks for them.
    func testUndecodedChannelsNeedTheCaptureFlag() {
        var s = GarminSession(config: GarminSessionConfig(host: oracleSessionHost(), firstPairing: false,
                                                          sendsHostLifecycleEvents: false),
                              transport: .multiLinkV2, mtu: 150)
        let c = GarminClock(nowMs: 0, unixSeconds: 0, utcOffsetSeconds: 0)
        for (_, step) in oracleCaptureReadySteps() {
            if case let .rx(b) = step { _ = s.receive(b, c) } else if case .start = step { _ = s.start(c) }
        }
        let writes = s.setLiveChannels(Set(GarminRealtimeCapture.undecodedChannels), c).filter {
            if case .write = $0 { return true } else { return false }
        }
        XCTAssertTrue(writes.isEmpty)
    }

    func testIntensityStructuralCheck() throws {
        let ok = try XCTUnwrap(GarminIntensityCandidate.decode(oracleLE16(70) + oracleLE16(30) + oracleLE16(20) + [UInt8](repeating: 0, count: 8)))
        XCTAssertTrue(ok.totalMatchesParts)
        let bad = try XCTUnwrap(GarminIntensityCandidate.decode(oracleLE16(50) + oracleLE16(30) + oracleLE16(20) + [UInt8](repeating: 0, count: 8)))
        XCTAssertFalse(bad.totalMatchesParts)
    }

    /// Every byte capture mode writes is an ML control frame NOOP's allowlist knows: REGISTER or
    /// CLOSE_HANDLE for a realtime service, never SpO2 or the accelerometer from the capture set.
    func testCaptureWritesOnlyRealtimeRegisterAndCloseFrames() {
        XCTAssertEqual(GarminRealtimeCapture.captureChannels.map(\.rawValue), [6, 8, 10, 13])
        var s = GarminSession(config: GarminSessionConfig(host: oracleSessionHost(), firstPairing: false,
                                                          sendsHostLifecycleEvents: false),
                              transport: .multiLinkV2, mtu: 150)
        var writes: [[UInt8]] = []
        func collect(_ o: [GarminSessionOutput]) { for case let .write(b) in o { writes.append(b) } }
        let c = GarminClock(nowMs: 0, unixSeconds: 0, utcOffsetSeconds: 0)
        for (_, step) in oracleCaptureReadySteps() {
            switch step {
            case .start: _ = s.start(c)
            case let .rx(b): _ = s.receive(b, c)
            default: break
            }
        }
        XCTAssertEqual(s.phase, .ready)
        collect(s.setLiveChannels(Set(GarminRealtimeCapture.captureChannels), c))
        collect(s.setUndecodedCapture(true, c))
        for (i, service) in GarminRealtimeCapture.captureChannels.enumerated() {
            _ = s.receive(oracleControl(1, oracleLE16(Int(service.rawValue)) + [0, UInt8(0x70 + i), 0]), c)
        }
        XCTAssertEqual(s.openLiveChannels, Set(GarminRealtimeCapture.captureChannels))
        collect(s.setUndecodedCapture(false, c))
        collect(s.setLiveChannels([], c))
        XCTAssertEqual(writes.count, 8)
        for w in writes {
            XCTAssertEqual(w.count, 13)
            XCTAssertEqual(w[0], 0)
            XCTAssertTrue([0, 2].contains(w[1]), "only REGISTER (0) or CLOSE_HANDLE (2)")
            let service = UInt16(w[10]) | UInt16(w[11]) << 8
            XCTAssertTrue(GarminService(rawValue: service)?.isRealtime ?? false)
            XCTAssertFalse([16, 19].contains(service))
        }
    }
}
