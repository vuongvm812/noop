import XCTest
@testable import GarminProtocol

/// Realtime channels: the payload decoders, the handle table driven through `GarminSession`, and the live
/// intake. Kotlin twin: com.noop.garmin.GarminRealtimeTest (holds the shared realtime oracle literal).
final class GarminRealtimeTests: XCTestCase {

    private func kotlinLiteral(_ file: String) throws -> [String] {
        let relative = "android/app/src/test/java/com/noop/garmin/\(file)"
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

    func testSwiftMatchesTheKotlinRealtimeOracleLiteral() throws {
        let expected = try kotlinLiteral("GarminRealtimeTest.kt")
        let actual = garminRealtimeOracleLines()
        XCTAssertEqual(actual.count, expected.count)
        for (index, (a, e)) in zip(actual, expected).enumerated() where a != e {
            XCTFail("realtime oracle line \(index + 1)\n  swift:  \(a)\n  kotlin: \(e)")
        }
    }

    // MARK: - Session + table

    private func clock(_ ms: Int64) -> GarminClock {
        GarminClock(nowMs: ms, unixSeconds: 1_793_546_986, utcOffsetSeconds: 0)
    }

    private func readySession() -> GarminSession {
        var s = GarminSession(config: GarminSessionConfig(host: oracleSessionHost(), firstPairing: false,
                                                          sendsHostLifecycleEvents: false),
                              transport: .multiLinkV2, mtu: 150)
        _ = s.start(clock(0))
        _ = s.receive(oracleControl(6, oracleLE16(0) + [0]), clock(1))
        _ = s.receive(oracleControl(1, oracleLE16(1) + [0, 0x65, 0]), clock(2))
        _ = s.receive([0x65] + oracleCobs(oracleFr165DeviceInformation()), clock(3))
        _ = s.receive([0x65] + oracleCobs(oracleFrame(5050, [1, 0x09])), clock(4))
        XCTAssertEqual(s.phase, .ready)
        return s
    }

    private func writes(_ outputs: [GarminSessionOutput]) -> [[UInt8]] {
        outputs.compactMap { if case let .write(b) = $0 { return b } else { return nil } }
    }

    func testRegisterAndCloseAreTheExactControlFrames() {
        var s = readySession()
        let open = writes(s.setLiveChannels([.realtimeHeartRate, .realtimeHrv], clock(10)))
        XCTAssertEqual(open, [GarminMultiLink.ControlRequest.register(.realtimeHeartRate, reliable: false).encode(),
                              GarminMultiLink.ControlRequest.register(.realtimeHrv, reliable: false).encode()])
        XCTAssertEqual(open.first, [0x00, 0x00, 2, 0, 0, 0, 0, 0, 0, 0, 0x06, 0x00, 0x00])
        _ = s.receive(oracleControl(1, oracleLE16(6) + [0, 0x66, 0]), clock(11))
        _ = s.receive(oracleControl(1, oracleLE16(12) + [0, 0x67, 0]), clock(12))
        XCTAssertEqual(s.openLiveChannels, [.realtimeHeartRate, .realtimeHrv])
        // Asking again for the same set sends nothing (no duplicate registration).
        XCTAssertEqual(writes(s.setLiveChannels([.realtimeHeartRate, .realtimeHrv], clock(13))), [])
        let close = writes(s.setLiveChannels([], clock(20)))
        XCTAssertEqual(close, [[0x00, 0x02, 2, 0, 0, 0, 0, 0, 0, 0, 0x06, 0x00, 0x66],
                               [0x00, 0x02, 2, 0, 0, 0, 0, 0, 0, 0, 0x0C, 0x00, 0x67]])
        let closed = s.receive(oracleControl(3, oracleLE16(6) + [0x66, 0]), clock(21))
        XCTAssertTrue(closed.contains(.liveChannel(.realtimeHeartRate, open: false)))
        XCTAssertEqual(s.openLiveChannels, [])
    }

    func testNothingIsSentBeforeTheHandshakeCompletes() {
        var s = GarminSession(config: GarminSessionConfig(host: oracleSessionHost(), firstPairing: false,
                                                          sendsHostLifecycleEvents: false),
                              transport: .multiLinkV2, mtu: 150)
        _ = s.start(clock(0))
        XCTAssertEqual(writes(s.setLiveChannels([.realtimeHeartRate], clock(1))), [])
    }

    func testTheAccelerometerAndUndecodedChannelsAreNeverRegistered() {
        var s = readySession()
        let out = s.setLiveChannels([.realtimeAccelerometer, .realtimeStress, .realtimeCalories, .realtimeIntensity], clock(10))
        XCTAssertEqual(writes(out), [])
        XCTAssertFalse(GarminRealtime.decodableServices.contains(.realtimeAccelerometer))
    }

    func testReadingsReachTheOutputOnlyFromAnOpenHandle() {
        var s = readySession()
        _ = s.setLiveChannels([.realtimeHeartRate], clock(10))
        // Before the handle is granted, data on it is stray.
        XCTAssertFalse(s.receive([0x66, 3, 62, 55, 0xFF, 0xFF], clock(11)).contains { if case .live = $0 { return true }; return false })
        _ = s.receive(oracleControl(1, oracleLE16(6) + [0, 0x66, 0]), clock(12))
        let out = s.receive([0x66, 3, 62, 55, 0xFF, 0xFF], clock(13))
        XCTAssertTrue(out.contains(.live(.heartRate(bpm: 62, resting: 55, type: 3, trailing: [0xFF, 0xFF]))))
    }

    // MARK: - Intake

    func testMetricWritesAreThrottledToOnePerKindPerMinute() {
        var intake = GarminLiveIntake()
        var written: [GarminLiveMetricWrite] = []
        for second in 0..<180 {
            written += intake.ingest(.bodyBattery(60), unixSeconds: 1_793_546_940 + second).writes
            written += intake.ingest(.respiration(breathsPerMinute: 14), unixSeconds: 1_793_546_940 + second).writes
        }
        XCTAssertEqual(written.filter { $0.kind == GarminLiveIntake.kindBodyBattery }.count, 3)
        XCTAssertEqual(written.filter { $0.kind == GarminLiveIntake.kindRespiration }.count, 3)
    }

    func testImplausibleHeartRateIsNeitherShownNorStored() {
        var intake = GarminLiveIntake()
        XCTAssertNil(intake.ingest(.heartRate(bpm: 250, resting: nil, type: 3, trailing: []), unixSeconds: 0).heartRate)
        XCTAssertEqual(intake.ingest(.heartRate(bpm: 61, resting: nil, type: 3, trailing: []), unixSeconds: 0).heartRate, 61)
    }
}
