import XCTest
@testable import GarminProtocol

/// CRC-16/ARC, COBS, and the streaming COBS decoder. Kotlin twin: com.noop.garmin.GarminFramingTest.
final class GarminFramingTests: XCTestCase {

    func testCrcCatalogueCheckValue() {
        XCTAssertEqual(GarminCRC.crc16(Array("123456789".utf8)), 0xBB3D)
        XCTAssertEqual(GarminCRC.crc16([]), 0)
    }

    func testRunningCrcChainsAcrossChunks() {
        let data = oraclePattern(777, 11, 2)
        var running: UInt16 = 0
        for chunk in stride(from: 0, to: data.count, by: 100) {
            running = GarminCRC.crc16(Array(data[chunk..<min(chunk + 100, data.count)]), seed: running)
        }
        XCTAssertEqual(running, GarminCRC.crc16(data))
    }

    func testCobsRoundTripsAndNeverEmitsInnerZero() {
        let inputs: [[UInt8]] = [[], [0], [0, 0, 0], [1], oraclePattern(1_000, 1, 0), oraclePattern(509, 3, 7),
                                 [UInt8](repeating: 0xFF, count: 254), [UInt8](repeating: 0, count: 300)]
        for input in inputs {
            let encoded = GarminCOBS.encode(input)
            XCTAssertEqual(encoded.first, 0)
            XCTAssertEqual(encoded.last, 0)
            XCTAssertFalse(encoded.dropFirst().dropLast().contains(0))
            XCTAssertEqual(GarminCOBS.decode(encoded), input)
        }
    }

    func testCobsRejectsMalformedBodies() {
        XCTAssertNil(GarminCOBS.decodeBody([0x05, 0x01]))          // code claims more than present
        XCTAssertNil(GarminCOBS.decodeBody([0x02, 0x00]))          // zero inside a body
        XCTAssertNil(GarminCOBS.decode([0x02, 0x11, 0x00]))        // no leading delimiter
        XCTAssertNil(GarminCOBS.decode([0x00, 0x02, 0x11]))        // no trailing delimiter
    }

    func testStreamDecoderSurvivesALostPacket() {
        var decoder = GarminCOBSStreamDecoder()
        let first = GarminCOBS.encode([1, 2, 3, 4, 5, 6])
        let second = GarminCOBS.encode([9, 0, 9])
        // The tail of `first` (including its closing 0x00) is lost; `second` must still decode.
        XCTAssertEqual(decoder.feed(Array(first.prefix(4))), [])
        XCTAssertEqual(decoder.feed(second), [[9, 0, 9]])
        XCTAssertEqual(decoder.droppedFrames, 1)
        // And the decoder keeps working afterwards.
        XCTAssertEqual(decoder.feed(first), [[1, 2, 3, 4, 5, 6]])
    }

    func testStreamDecoderDropsBytesBeforeFirstDelimiterAndHandlesSplits() {
        var decoder = GarminCOBSStreamDecoder()
        let frame = GarminCOBS.encode(oraclePattern(600, 5, 1))
        var got: [[UInt8]] = []
        got += decoder.feed([0x42, 0x43])                     // mid-frame junk, start lost
        for byte in frame { got += decoder.feed([byte]) }     // one byte per notification
        XCTAssertEqual(got, [oraclePattern(600, 5, 1)])
        XCTAssertEqual(decoder.droppedFrames, 0)
    }

    func testStreamDecoderBoundsMemory() {
        var decoder = GarminCOBSStreamDecoder(maxBodyBytes: 8)
        XCTAssertEqual(decoder.feed([0x00] + [UInt8](repeating: 1, count: 50)), [])
        XCTAssertEqual(decoder.droppedFrames, 1)
        XCTAssertEqual(decoder.feed(GarminCOBS.encode([7])), [[7]])
    }
}
