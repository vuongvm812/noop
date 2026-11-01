import Foundation

// MARK: - Which GATT transport a watch offers
//
// Wire facts (Gadgetbridge, see ATTRIBUTION.md):
//   - V2 multi-link: service 6a4e2800-667b-11e3-949a-0800200c9a66, notify characteristics 6a4e2810…2814 and
//     write characteristics 6a4e2820…2824. The usable pair is the first index n with BOTH 281n and 282n.
//   - V1: service 6a4e2401-667b-11e3-949a-0800200c9a66, write 6a4e4c80-…, notify 6a4ecd28-….
//   - V0: service 9b012401-bc30-ce9a-e111-0f67e491abde, write df334c80-e6a7-d082-274d-78fc66f85e16,
//     notify 4acbcd28-7425-868e-f447-915c8f00d0cb.
// V1 and V0 carry the same COBS GFDI stream as V2, but directly on the characteristic (no handle byte, no
// control frames). The V1 characteristic ids also appear under the watch-apps service 6a4e8022 on V2
// watches (the Forerunner 165 has both), so a pair only counts inside its own service.
//
// Anything else, including the newer single-characteristic variant Gadgetbridge does not support either,
// is reported as unsupported: the source then stops instead of holding a half-working link.

public enum GarminTransportKind: String, CaseIterable, Sendable {
    case multiLinkV2 = "V2 multi-link"
    case v1 = "V1"
    case v0 = "V0"
}

/// One discovered GATT service and its characteristic UUIDs, in the platform's discovery order.
public struct GarminGattService: Equatable, Sendable {
    public let uuid: String
    public let characteristics: [String]

    public init(uuid: String, characteristics: [String]) {
        self.uuid = uuid
        self.characteristics = characteristics
    }
}

/// The characteristic pair a session runs on. UUIDs are lowercase, full 128-bit form.
public struct GarminTransport: Equatable, Sendable {
    public let kind: GarminTransportKind
    public let service: String
    public let notify: String
    public let write: String
}

public enum GarminTransportSelection {

    public static let multiLinkService = "6a4e2800-667b-11e3-949a-0800200c9a66"
    public static let v1Service = "6a4e2401-667b-11e3-949a-0800200c9a66"
    public static let v1Write = "6a4e4c80-667b-11e3-949a-0800200c9a66"
    public static let v1Notify = "6a4ecd28-667b-11e3-949a-0800200c9a66"
    public static let v0Service = "9b012401-bc30-ce9a-e111-0f67e491abde"
    public static let v0Write = "df334c80-e6a7-d082-274d-78fc66f85e16"
    public static let v0Notify = "4acbcd28-7425-868e-f447-915c8f00d0cb"

    /// Notify characteristics of the V2 service, in preference order; `multiLinkWrite[n]` pairs with `[n]`.
    public static let multiLinkNotify = (0...4).map { "6a4e281\($0)-667b-11e3-949a-0800200c9a66" }
    public static let multiLinkWrite = (0...4).map { "6a4e282\($0)-667b-11e3-949a-0800200c9a66" }

    /// Services to ask the platform to discover (nothing else is needed).
    public static let servicesOfInterest = [multiLinkService, v1Service, v0Service]

    /// Expand a 16-bit or 32-bit short UUID string to the Bluetooth base form and lowercase everything,
    /// so CoreBluetooth's short "2A37"-style strings and Android's full strings compare equal.
    public static func normalize(_ uuid: String) -> String {
        let lower = uuid.lowercased()
        switch lower.count {
        case 4: return "0000\(lower)-0000-1000-8000-00805f9b34fb"
        case 8: return "\(lower)-0000-1000-8000-00805f9b34fb"
        default: return lower
        }
    }

    /// V2 if any pair is present, else V1, else V0, else nil (unsupported).
    public static func select(_ services: [GarminGattService]) -> GarminTransport? {
        func chars(of service: String) -> Set<String>? {
            services.first { normalize($0.uuid) == service }.map { Set($0.characteristics.map(normalize)) }
        }
        if let ml = chars(of: multiLinkService) {
            for (notify, write) in zip(multiLinkNotify, multiLinkWrite) where ml.contains(notify) && ml.contains(write) {
                return GarminTransport(kind: .multiLinkV2, service: multiLinkService, notify: notify, write: write)
            }
        }
        if let v1 = chars(of: v1Service), v1.contains(v1Notify), v1.contains(v1Write) {
            return GarminTransport(kind: .v1, service: v1Service, notify: v1Notify, write: v1Write)
        }
        if let v0 = chars(of: v0Service), v0.contains(v0Notify), v0.contains(v0Write) {
            return GarminTransport(kind: .v0, service: v0Service, notify: v0Notify, write: v0Write)
        }
        return nil
    }

    /// One line describing what was found, for the always-on "unsupported watch protocol" diagnostic.
    public static func describe(_ services: [GarminGattService]) -> String {
        services.map { s in
            "\(normalize(s.uuid).prefix(8))[\(s.characteristics.map { normalize($0).prefix(8) }.joined(separator: ","))]"
        }.joined(separator: " ")
    }
}

// MARK: - Garmin advertisement recognition

public enum GarminAdvertisement {
    /// Bluetooth SIG company identifier assigned to Garmin (0x0087), little-endian at the start of the
    /// advertised manufacturer data. Observed on the Forerunner 165, which advertises no name.
    public static let companyId: UInt16 = 0x0087

    public static func isGarmin(manufacturerData: [UInt8]?) -> Bool {
        guard let d = manufacturerData, d.count >= 2 else { return false }
        return UInt16(d[0]) | UInt16(d[1]) << 8 == companyId
    }
}
