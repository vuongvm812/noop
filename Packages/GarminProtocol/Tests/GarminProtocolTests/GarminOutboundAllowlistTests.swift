import XCTest
@testable import GarminProtocol

/// The send allowlist (BLE safety contract). Every `GarminOutbound` case is enumerated through the
/// exhaustive `oracleOutboundSamples(_:)` switch; each encoded frame's GFDI type, and each system event's
/// ordinal, must be on the explicit lists below. Kotlin twin: com.noop.garmin.GarminOutboundAllowlistTest.
final class GarminOutboundAllowlistTests: XCTestCase {

    /// The only GFDI message types NOOP may emit.
    static let allowedTypes: Set<UInt16> = [5000, 5002, 5007, 5030, 5031, 5043, 5050]

    /// The only system-event ordinals NOOP may emit.
    static let allowedSystemEvents: Set<UInt8> = [0, 1, 3, 4, 5, 6, 7, 8, 9, 12, 13, 14, 15, 16]

    /// Destructive or out-of-scope types that must never appear, whatever the allowlist says.
    static let forbiddenTypes: Set<UInt16> = Set([5003, 5005, 5006, 5008]).union(5102...5112)
    static let forbiddenSystemEvents: Set<UInt8> = [2, 10, 11, 17]

    func testEveryOutboundCaseIsOnTheAllowlist() {
        var seenKinds = 0
        for kind in GarminOutbound.Kind.allCases {
            let samples = oracleOutboundSamples(kind)
            XCTAssertFalse(samples.isEmpty, "\(kind) has no sample")
            seenKinds += 1
            for sample in samples {
                XCTAssertEqual(sample.kind, kind)
                let message = GarminCOBS.decode(sample.frame().bytes)!
                let frame = try! GarminGfdi.decode(message).get()
                XCTAssertTrue(Self.allowedTypes.contains(frame.type), "\(kind) emits type \(frame.type)")
                XCTAssertFalse(Self.forbiddenTypes.contains(frame.type))
                if frame.type == 5030 {
                    let ordinal = frame.payload[0]
                    XCTAssertTrue(Self.allowedSystemEvents.contains(ordinal), "system event \(ordinal)")
                    XCTAssertFalse(Self.forbiddenSystemEvents.contains(ordinal))
                }
                if frame.type == 5043, case let .protobufRequest(chunk) = GarminInbound.decode(frame) {
                    // FileAccess: only ItemList (9) and PullItem (1) requests, never Delete (13) / ModifyFlags (15).
                    let smart = GarminProtoReader.fields(chunk.data)!
                    XCTAssertEqual(smart.map(\.0), [43])
                    guard case let .bytes(service) = smart[0].1 else { return XCTFail() }
                    let fields = GarminProtoReader.fields(service)!.map(\.0)
                    XCTAssertTrue(fields.allSatisfy { [1, 9].contains($0) }, "FileAccess fields \(fields)")
                }
            }
        }
        XCTAssertEqual(seenKinds, GarminOutbound.Kind.allCases.count)
    }

    func testSafeSystemEventsExcludeDestructiveOrdinals() {
        XCTAssertEqual(Set(GarminSafeSystemEvent.allCases.map(\.rawValue)), Self.allowedSystemEvents)
        XCTAssertTrue(Self.forbiddenSystemEvents.isDisjoint(with: GarminSafeSystemEvent.allCases.map(\.rawValue)))
    }

    func testFilterAndCapabilityEnumsAreExplicit() {
        XCTAssertEqual(GarminFilterType.allCases.map(\.rawValue), [0, 1, 3])
        XCTAssertEqual(GarminCapability.allCases.map(\.rawValue), [0, 3, 4, 5, 55, 71, 76, 90])
    }
}
