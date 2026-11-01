import XCTest
@testable import GarminProtocol

/// GFDI framing and every message codec: round trips plus malformed input.
/// Kotlin twin: com.noop.garmin.GarminCodecTest.
final class GarminCodecTests: XCTestCase {

    // MARK: GFDI frame

    func testGfdiRejectsShortBadCrcAndWrongLength() {
        let good = oracleFrame(5024, [1, 2, 3])
        XCTAssertEqual(try GarminGfdi.decode(good).get().payload, [1, 2, 3])
        var badCrc = good
        badCrc[4] ^= 0x01
        guard case .failure(.crcMismatch) = GarminGfdi.decode(badCrc) else { return XCTFail("bad CRC accepted") }
        var oversize = good
        oversize[0] = 0xFF
        oversize[1] = 0xFF
        XCTAssertEqual(GarminGfdi.decode(oversize), .failure(.lengthMismatch(declared: 0xFFFF, actual: good.count)))
        XCTAssertEqual(GarminGfdi.decode(Array(good.prefix(5))), .failure(.tooShort))
    }

    func testCompactTypeExpandsAndKeepsSequence() {
        let frame = try! GarminGfdi.decode(oracleFrame(0x8000 | 0x0500 | 0x18, [])).get()
        XCTAssertEqual(frame.type, 5024)
        XCTAssertEqual(frame.compactSequence, 5)
    }

    func testGarminEpoch() {
        XCTAssertEqual(GarminGfdi.garminTime(fromUnix: 631_065_600), 0)
        XCTAssertEqual(GarminGfdi.unixTime(fromGarmin: 1_000), 631_066_600)
    }

    // MARK: Outbound round trips: encode, then read every field back from the wire bytes.

    private func wire(_ m: GarminOutbound) -> (UInt16, GarminByteReader) {
        let decodedCobs = GarminCOBS.decode(m.frame().bytes)!
        XCTAssertEqual(decodedCobs, m.message())
        let frame = try! GarminGfdi.decode(decodedCobs).get()
        return (frame.type, GarminByteReader(frame.payload))
    }

    func testDeviceInformationReplyRoundTrip() {
        let host = GarminHostIdentity(protocolVersion: 150, productNumber: 7, unitNumber: 99, softwareVersion: 210,
                                      maxPacketSize: 512, bluetoothName: "Phone", manufacturer: "NOOP", model: "M")
        var (type, r) = wire(.deviceInformationReply(host, protocolFlags: 1))
        XCTAssertEqual(type, 5000)
        XCTAssertEqual(r.u16(), 5024); XCTAssertEqual(r.u8(), 0)
        XCTAssertEqual(r.u16(), 150); XCTAssertEqual(r.u16(), 7); XCTAssertEqual(r.u32(), 99)
        XCTAssertEqual(r.u16(), 210); XCTAssertEqual(r.u16(), 512)
        XCTAssertEqual(r.string8(), "Phone"); XCTAssertEqual(r.string8(), "NOOP"); XCTAssertEqual(r.string8(), "M")
        XCTAssertEqual(r.u8(), 1); XCTAssertEqual(r.remaining, 0)
    }

    func testConfigurationRoundTrip() {
        let caps: Set<GarminCapability> = [.sync, .multiLinkService, .currentTimeRequestSupport]
        var (type, r) = wire(.configuration(caps))
        XCTAssertEqual(type, 5050)
        let count = r.u8()!
        let bitmap = r.take(Int(count))!
        XCTAssertEqual(Set(GarminCapabilityBitmap.indices(bitmap)), Set(caps.map(\.rawValue)))
        // Inbound decode of the same payload.
        let inbound = GarminInbound.decode(try! GarminGfdi.decode(GarminOutbound.configuration(caps).message()).get())
        XCTAssertEqual(inbound, .configuration(capabilities: bitmap))
    }

    func testAuthNegotiationEchoRoundTrip() {
        let inbound = GarminInbound.decode(try! GarminGfdi.decode(oracleFrame(5101, [4] + oracleLE32(0x0102_0304))).get())
        guard case let .authNegotiation(unknown, flags) = inbound else { return XCTFail() }
        var (type, r) = wire(.authNegotiationReply(unknown: unknown, flags: flags))
        XCTAssertEqual(type, 5000)
        XCTAssertEqual(r.u16(), 5101); XCTAssertEqual(r.u8(), 0); XCTAssertEqual(r.u8(), 0)
        XCTAssertEqual(r.u8(), 4); XCTAssertEqual(r.u32(), 0x0102_0304)
    }

    func testCurrentTimeRoundTrip() {
        let inbound = GarminInbound.decode(try! GarminGfdi.decode(oracleFrame(5052, oracleLE32(31))).get())
        XCTAssertEqual(inbound, .currentTimeRequest(referenceId: 31))
        var (_, r) = wire(.currentTimeReply(referenceId: 31, garminTime: 5, utcOffsetSeconds: -3_600,
                                            followingTransition: 9, nextTransition: 8))
        XCTAssertEqual(r.u16(), 5052); XCTAssertEqual(r.u8(), 0)
        XCTAssertEqual(r.u32(), 31); XCTAssertEqual(r.u32(), 5)
        XCTAssertEqual(r.u32().map { Int32(bitPattern: $0) }, -3_600)
        XCTAssertEqual(r.u32(), 9); XCTAssertEqual(r.u32(), 8)
    }

    func testSystemEventFilterDownloadSupportedTypesRoundTrip() {
        var (t1, r1) = wire(.systemEvent(.syncReady, value: 0))
        XCTAssertEqual(t1, 5030); XCTAssertEqual(r1.u8(), 8); XCTAssertEqual(r1.u8(), 0)
        var (t2, r2) = wire(.filter(.onlyNew))
        XCTAssertEqual(t2, 5007); XCTAssertEqual(r2.u8(), 3)
        var (t3, r3) = wire(.downloadRequest(fileIndex: 0x0203, dataOffset: 77, kind: .new, crcSeed: 0x1111,
                                             dataSize: 88, flags: 0))
        XCTAssertEqual(t3, 5002)
        XCTAssertEqual(r3.u16(), 0x0203); XCTAssertEqual(r3.u32(), 77); XCTAssertEqual(r3.u8(), 1)
        XCTAssertEqual(r3.u16(), 0x1111); XCTAssertEqual(r3.u32(), 88); XCTAssertEqual(r3.u8(), 0)
        let (t4, r4) = wire(.supportedFileTypesRequest)
        XCTAssertEqual(t4, 5031); XCTAssertEqual(r4.remaining, 0)
    }

    func testFileTransferChunkAndAckRoundTrip() {
        let data: [UInt8] = [1, 2, 3, 0, 5]
        let crc = GarminCRC.crc16(data, seed: 0x4242)
        let inbound = GarminInbound.decode(try! GarminGfdi.decode(
            oracleFrame(5004, [0] + oracleLE16(Int(crc)) + oracleLE32(1_000) + data)).get())
        XCTAssertEqual(inbound, .fileTransferData(flags: 0, runningCrc: crc, dataOffset: 1_000, data: data))
        var (_, r) = wire(.fileTransferDataReply(transferStatus: 0, nextOffset: 1_005))
        XCTAssertEqual(r.u16(), 5004); XCTAssertEqual(r.u8(), 0); XCTAssertEqual(r.u8(), 0); XCTAssertEqual(r.u32(), 1_005)
    }

    func testDirectoryEntryRoundTripAndFileAvailable() {
        let entry = GarminDirectoryEntry(fileIndex: 12, dataType: 128, subType: 44, fileNumber: 3, specificFlags: 1,
                                         fileFlags: 0x20, fileSize: 9_999, garminTimestamp: 0)
        var w = GarminByteWriter()
        entry.write(&w)
        XCTAssertEqual(w.bytes.count, 16)
        XCTAssertEqual(GarminDirectoryEntry.parseDirectory(w.bytes + w.bytes), [entry, entry])
        XCTAssertNil(entry.unixTime)
        XCTAssertNil(GarminDirectoryEntry.parseDirectory(Array(w.bytes.dropLast())))
        XCTAssertEqual(GarminInbound.decode(try! GarminGfdi.decode(oracleFrame(5009, w.bytes)).get()), .fileAvailable(entry))
        XCTAssertEqual(GarminInbound.decode(try! GarminGfdi.decode(oracleFrame(5009, Array(w.bytes.prefix(15)))).get()),
                       .unknown(type: 5009, payload: Array(w.bytes.prefix(15))))
    }

    func testSynchronizationAndDeviceInformationDecode() {
        XCTAssertEqual(GarminInbound.decode(try! GarminGfdi.decode(oracleFrame(5037, [1, 4, 0xFF, 0xFF, 0xFF, 0xFF])).get()),
                       .synchronization(type: 1, fileTypeMask: 0xFFFF_FFFF))
        let payload = oracleLE16(150) + oracleLE16(1) + oracleLE32(2) + oracleLE16(3) + oracleLE16(4)
            + oracleStr("a") + oracleStr("bé") + oracleStr("")
        guard case let .deviceInformation(info) = GarminInbound.decode(try! GarminGfdi.decode(oracleFrame(5024, payload)).get())
        else { return XCTFail() }
        XCTAssertEqual(info.deviceName, "bé")
        XCTAssertEqual(info.deviceModel, "")
        XCTAssertNil(info.classicAddress)
    }

    func testStringsTruncateOnCodePointBoundary() {
        XCTAssertEqual(GarminByteWriter.truncatedUTF8("aé", maxBytes: 2), [0x61])
        XCTAssertEqual(GarminByteWriter.truncatedUTF8("aé", maxBytes: 3), [0x61, 0xC3, 0xA9])
    }

    // MARK: FileAccess protobuf

    func testFileAccessRequestsRoundTripThroughTheWireReader() {
        let item = GarminItemReference(uid: GarminUid(mostSignificant: 1, leastSignificant: 2),
                                       dataType: GarminItemDataType(format: 3, typeName: "T"), dataSize: 1 << 40,
                                       transactionId: 6)
        let smart = GarminFileAccessRequest.pullItem(item, priority: 24, offset: 0, compressionWindow: 15).encodeSmart()
        let service = GarminProtoReader.fields(smart)!
        XCTAssertEqual(service.map(\.0), [43])
        guard case let .bytes(serviceBytes) = service[0].1, let pull = GarminProtoReader.fields(serviceBytes),
              case let .bytes(pullBytes) = pull[0].1, let pullFields = GarminProtoReader.fields(pullBytes),
              case let .bytes(itemBytes) = pullFields[0].1 else { return XCTFail() }
        XCTAssertEqual(pull.map(\.0), [1])
        XCTAssertEqual(pullFields.map(\.0), [1, 2, 3, 4, 5])
        XCTAssertEqual(GarminItemReference.decode(itemBytes), item)

        // The watch's ItemAdded carrying the same reference decodes back to it.
        let added = oracleSmart(12, oracleLd(1, item.encode()))
        XCTAssertEqual(GarminFileAccessMessage.decodeSmart(added), .itemAdded([item]))
    }

    func testFileAccessRejectsMalformedProtobuf() {
        XCTAssertNil(GarminProtoReader.fields([0x0A, 0x05, 0x01]))   // length past end
        XCTAssertNil(GarminProtoReader.fields([0x08, 0x80]))         // unterminated varint
        XCTAssertNil(GarminProtoReader.fields([0x0B]))               // group
        XCTAssertNil(GarminFileAccessMessage.decodeSmart([0x08, 0x01]))
    }
}
