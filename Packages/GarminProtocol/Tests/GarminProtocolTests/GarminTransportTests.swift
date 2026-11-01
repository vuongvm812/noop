import XCTest
@testable import GarminProtocol

/// Multi-link control frames, plain framing, routing and the MLR state machine.
/// Kotlin twin: com.noop.garmin.GarminTransportTest.
final class GarminTransportTests: XCTestCase {

    private let client: [UInt8] = [2, 0, 0, 0, 0, 0, 0, 0]

    func testControlFramesAreThirteenBytes() {
        XCTAssertEqual(GarminMultiLink.ControlRequest.closeAll.encode(), [0, 5] + client + [0, 0, 0])
        XCTAssertEqual(GarminMultiLink.ControlRequest.register(.gfdi, reliable: false).encode(), [0, 0] + client + [1, 0, 0])
        XCTAssertEqual(GarminMultiLink.ControlRequest.register(.gfdi, reliable: true).encode(), [0, 0] + client + [1, 0, 2])
        XCTAssertEqual(GarminMultiLink.ControlRequest.closeHandle(.realtimeSpo2, handle: 7).encode(), [0, 2] + client + [19, 0, 7])
        for request in [GarminMultiLink.ControlRequest.closeAll, .register(.fileTransferE, reliable: true)] {
            XCTAssertEqual(request.encode().count, 13)
        }
    }

    func testRegisterResponseParsesAndForeignClientIsIgnored() {
        XCTAssertEqual(GarminMultiLink.parseControl([0, 1] + client + [1, 0, 0, 5, 1]),
                       .registered(service: 1, status: 0, handle: 5, reliable: true))
        XCTAssertNil(GarminMultiLink.parseControl([0, 1, 3, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 5, 1]))
        XCTAssertEqual(GarminMultiLink.parseControl([0, 1, 3, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 5, 1], clientId: 3),
                       .registered(service: 1, status: 0, handle: 5, reliable: true))
        XCTAssertNil(GarminMultiLink.parseControl([0, 1] + client))           // short
        XCTAssertEqual(GarminMultiLink.parseControl([0, 3] + client + [6, 0, 9, 0]),
                       .handleClosed(service: 6, handle: 9, status: 0))
        XCTAssertEqual(GarminMultiLink.parseControl([0, 6] + client), .allClosed)
    }

    func testPlainPacketsFragmentToMtuMinusFour() {
        let frame = GarminOutbound.supportedFileTypesRequest.frame()   // 9 bytes after COBS
        let packets = GarminMultiLink.plainPackets(frame, handle: 4, mtu: 7)
        XCTAssertEqual(packets.map(\.count), [4, 4, 4])
        XCTAssertTrue(packets.allSatisfy { $0.first == 4 })
        XCTAssertEqual(packets.flatMap { $0.dropFirst() }, frame.bytes)
    }

    func testRouteOnlySendsRegisteredReliableHandlesToMlr() {
        // Gadgetbridge #5476: a plain handle may have bit 7 set.
        XCTAssertEqual(GarminMultiLink.route([0x85, 1, 2], reliableHandles: []), .plain(handle: 0x85, payload: [1, 2]))
        XCTAssertEqual(GarminMultiLink.route([0xD0, 1, 2], reliableHandles: [0x85]), .reliable(handle: 0x85, packet: [0xD0, 1, 2]))
        XCTAssertEqual(GarminMultiLink.route([0x00, 1], reliableHandles: [0x85]), .control([0x00, 1]))
        XCTAssertEqual(GarminMultiLink.route([], reliableHandles: []), .empty)
    }

    func testMlrHeaderRoundTripsOverWholeSpace() {
        for h in 0..<8 {
            for r in 0..<64 {
                for s in 0..<64 {
                    let header = GarminMlrHeader(handle: UInt8(h), reqNum: UInt8(r), seq: UInt8(s))
                    let bytes = header.bytes
                    XCTAssertEqual(bytes[0], 0x80 | UInt8(h) << 4 | UInt8(r >> 2))
                    XCTAssertEqual(bytes[1], UInt8((r & 3) << 6 | s))
                    XCTAssertEqual(GarminMlrHeader.parse(bytes), header)
                }
            }
        }
        XCTAssertNil(GarminMlrHeader.parse([0x7F, 0]))
    }

    private func frame(_ count: Int) -> GarminOutboundFrame { GarminOutboundFrame(bytes: oraclePattern(count, 1, 1)) }

    func testMlrCapsInFlightAtThirtyTwo() {
        var s = GarminMlrSession(handle: 0x81, mtu: 23)
        let sent = s.send(frame(18 * 50), nowMs: 0)
        XCTAssertEqual(sent.count, 32)
        XCTAssertEqual(s.inFlight, 32)
        XCTAssertEqual(s.queued, 18)
        XCTAssertTrue(sent.allSatisfy { $0.count == 20 })
        // Sequence numbers run 0…31 with reqNum 0.
        XCTAssertEqual(sent.map { GarminMlrHeader.parse($0)!.seq }, (0..<32).map(UInt8.init))
    }

    func testMlrBatchesAcksAtFivePacketsOrTwentyMs() {
        var s = GarminMlrSession(handle: 0x81, mtu: 23)
        func data(_ seq: Int) -> [UInt8] { GarminMlrHeader(handle: 1, reqNum: 0, seq: UInt8(seq)).bytes + [UInt8(seq)] }
        var acks = 0
        for seq in 0..<4 {
            let r = s.receive(data(seq), nowMs: Int64(seq))
            XCTAssertEqual(r.data, [UInt8(seq)])
            acks += r.outbound.count
        }
        XCTAssertEqual(acks, 0, "no ack per packet")
        XCTAssertEqual(s.nextDeadlineMs, 3 + 20)
        let fifth = s.receive(data(4), nowMs: 4)
        XCTAssertEqual(fifth.outbound, [GarminMlrHeader(handle: 1, reqNum: 5, seq: 0).bytes])
        XCTAssertNil(s.nextDeadlineMs)
        _ = s.receive(data(5), nowMs: 10)
        XCTAssertEqual(s.tick(nowMs: 29), [])
        XCTAssertEqual(s.tick(nowMs: 30), [GarminMlrHeader(handle: 1, reqNum: 6, seq: 0).bytes])
    }

    func testMlrRetransmitBacksOffToTwentySecondsAndAckRestores() {
        var s = GarminMlrSession(handle: 0x82, mtu: 23)
        _ = s.send(frame(10), nowMs: 0)
        var now: Int64 = 0
        var timeouts: [Int64] = []
        for _ in 0..<8 {
            now = s.nextDeadlineMs!
            let resent = s.tick(nowMs: now)
            XCTAssertEqual(resent.count, 1)
            timeouts.append(s.retransmitTimeoutMs)
        }
        XCTAssertEqual(timeouts, [2_000, 4_000, 8_000, 16_000, 20_000, 20_000, 20_000, 20_000])
        XCTAssertEqual(s.windowSize, 1)
        _ = s.receive(GarminMlrHeader(handle: 2, reqNum: 1, seq: 0).bytes, nowMs: now + 1)
        XCTAssertEqual(s.inFlight, 0)
        XCTAssertEqual(s.retransmitTimeoutMs, 1_000)
        XCTAssertEqual(s.windowSize, 2)
        XCTAssertNil(s.nextDeadlineMs)
    }

    func testMlrDataPacketCarriesTheAck() {
        var s = GarminMlrSession(handle: 0x81, mtu: 23)
        _ = s.receive(GarminMlrHeader(handle: 1, reqNum: 0, seq: 0).bytes + [0xAA], nowMs: 0)
        XCTAssertNotNil(s.nextDeadlineMs)
        let out = s.send(frame(3), nowMs: 5)
        XCTAssertEqual(GarminMlrHeader.parse(out[0])?.reqNum, 1)
        // The pending pure ack was discharged by the data packet; only the retransmit timer remains.
        XCTAssertEqual(s.nextDeadlineMs, 1_005)
    }
}
