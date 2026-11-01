import XCTest
@testable import GarminProtocol

/// The connected-watch session: handshake order, the bytes it writes, refusals, re-registration and the
/// transport choice. Kotlin twin: com.noop.garmin.GarminSessionTest (holds the shared oracle literal).
final class GarminSessionTests: XCTestCase {

    private func clock(_ ms: Int64) -> GarminClock {
        GarminClock(nowMs: ms, unixSeconds: 1_793_546_986, utcOffsetSeconds: 25_200)
    }

    private func config(firstPairing: Bool = false) -> GarminSessionConfig {
        GarminSessionConfig(host: oracleSessionHost(), firstPairing: firstPairing, sendsHostLifecycleEvents: true)
    }

    /// Run the Forerunner 165 handshake and return every output.
    private func handshake(firstPairing: Bool) -> [GarminSessionOutput] {
        var s = GarminSession(config: config(firstPairing: firstPairing), transport: .multiLinkV2, mtu: 150)
        var out = s.start(clock(0))
        out += s.receive(oracleControl(6, oracleLE16(0) + [0]), clock(1))
        out += s.receive(oracleControl(1, oracleLE16(1) + [0, 0x65, 0]), clock(2))
        out += s.receive([0x65] + oracleCobs(oracleFr165DeviceInformation()), clock(3))
        out += s.receive([0x65] + oracleCobs(oracleFrame(5050, [1, 0x09])), clock(4))
        out += s.receive([0x65] + oracleCobs(oracleFrame(5052, oracleLE32(1))), clock(5))
        XCTAssertEqual(s.phase, .ready)
        return out
    }

    /// The handshake log assertion: the outbound messages, in order, exactly as the watch must see them.
    func testHandshakeSendsExactlyTheMinimalSequence() {
        let sent = handshake(firstPairing: false).compactMap { o -> String? in
            guard case let .log(line) = o, line.text.hasPrefix("-> ") else { return nil }
            return line.text
        }
        XCTAssertEqual(sent, [
            "-> ML close all",
            "-> ML register service=1 reliable=false",
            "-> status 5024 ack",
            "-> device information reply",
            "-> status 5050 ack",
            "-> configuration 8 bits",
            "-> system event 8",       // SYNC_READY
            "-> system event 6",       // HOST_DID_ENTER_FOREGROUND
            "-> current time reply",
        ])
        let firstPair = handshake(firstPairing: true)
        XCTAssertTrue(firstPair.contains(.pairingEventsSent))
        let events = firstPair.compactMap { o -> String? in
            guard case let .log(line) = o, line.text.hasPrefix("-> system event") else { return nil }
            return line.text
        }
        XCTAssertEqual(events, ["-> system event 8", "-> system event 4", "-> system event 14", "-> system event 6"])
    }

    /// Decode every byte the session wrote back into GFDI messages and check them against the allowlist:
    /// only status replies, NOOP's configuration and the safe system events, never a destructive event.
    func testEveryWrittenMessageIsAllowlisted() {
        var decoder = GarminCOBSStreamDecoder()
        var types: [UInt16] = []
        for case let .write(bytes) in handshake(firstPairing: true) where bytes.first != 0 {
            for message in decoder.feed(Array(bytes.dropFirst())) {
                guard case let .success(frame) = GarminGfdi.decode(message) else { return XCTFail("bad frame") }
                types.append(frame.type)
                if frame.type == 5030 {
                    XCTAssertFalse([2, 10, 11, 17].contains(frame.payload[0]), "destructive system event")
                }
            }
        }
        XCTAssertEqual(Set(types), [5000, 5050, 5030])
    }

    func testMinimalCapabilitiesAdvertiseNoPhoneFeatures() {
        XCTAssertEqual(GarminSessionConfig.minimalCapabilities.map(\.rawValue).sorted(), [0, 3, 4, 5, 55, 71, 76, 90])
    }

    func testWatchProxyRequestsAreDiscardedNotKept() {
        var s = GarminSession(config: config(), transport: .v1, mtu: 150)
        _ = s.start(clock(0))
        let request = oracleFrame(5043, oracleLE16(9) + oracleLE32(0) + oracleLE32(2) + oracleLE32(2) + [0x6A, 0x00])
        let out = s.receive(oracleCobs(request), clock(1))
        let reply = out.compactMap { o -> [UInt8]? in if case let .write(b) = o { return b }; return nil }.joined()
        let message = GarminCOBS.decode(Array(reply))!
        let frame = try! GarminGfdi.decode(message).get()
        XCTAssertEqual(frame.type, 5000)
        // [orig 5043][ACK][id 9][offset 0][DISCARDED][UNKNOWN_REQUEST_ID]
        XCTAssertEqual(frame.payload, oracleLE16(5043) + [0] + oracleLE16(9) + oracleLE32(0) + [1, 100])
        XCTAssertTrue(out.contains { if case let .log(l) = $0 { return l.rare && l.text.contains("core (HTTP proxy") }; return false })
    }

    func testRefusedRegistrationFailsWithoutSendingMore() {
        var s = GarminSession(config: config(), transport: .multiLinkV2, mtu: 23)
        _ = s.start(clock(0))
        _ = s.receive(oracleControl(6, oracleLE16(0) + [0]), clock(1))
        let out = s.receive(oracleControl(1, oracleLE16(1) + [3, 0, 0]), clock(2))
        XCTAssertEqual(s.phase, .failed("registration refused status=3"))
        XCTAssertFalse(out.contains { if case .write = $0 { return true }; return false })
        XCTAssertTrue(s.receive([0x65] + oracleCobs(oracleFr165DeviceInformation()), clock(3)).isEmpty)
    }

    func testTransportPrefersMultiLinkAndRejectsWatchAppsService() {
        let ml = GarminGattService(uuid: GarminTransportSelection.multiLinkService,
                                   characteristics: [GarminTransportSelection.multiLinkNotify[0], GarminTransportSelection.multiLinkWrite[0]])
        let v1 = GarminGattService(uuid: GarminTransportSelection.v1Service,
                                   characteristics: [GarminTransportSelection.v1Notify, GarminTransportSelection.v1Write])
        XCTAssertEqual(GarminTransportSelection.select([v1, ml])?.kind, .multiLinkV2)
        XCTAssertEqual(GarminTransportSelection.select([v1])?.kind, .v1)
        let watchApps = GarminGattService(uuid: "6a4e8022-667b-11e3-949a-0800200c9a66", characteristics: v1.characteristics)
        XCTAssertNil(GarminTransportSelection.select([watchApps]))
    }

    // MARK: - Parity

    private func kotlinOracle() throws -> [String] {
        let relative = "android/app/src/test/java/com/noop/garmin/GarminSessionTest.kt"
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
        throw XCTSkip("Kotlin session oracle not found (package checked out without the android tree)")
    }

    func testSwiftMatchesTheKotlinSessionOracleLiteral() throws {
        let expected = try kotlinOracle()
        let actual = garminSessionOracleLines()
        XCTAssertEqual(actual.count, expected.count)
        for (index, (a, e)) in zip(actual, expected).enumerated() where a != e {
            XCTFail("session oracle line \(index + 1)\n  swift:  \(a)\n  kotlin: \(e)")
        }
    }
}
