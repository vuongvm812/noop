import Foundation

/// Which wearable-export namespaces a history read takes in, and in what order (#31).
///
/// A Garmin / Oura / Fitbit export lands under its own source id (`garmin-import`, …), apart from the
/// active device and the canonical WHOOP import. Those rows are history: they fill the days the active
/// device, the WHOOP import and NOOP's own computed rows leave open, and never win a day one of them
/// holds. This is the one place that names the namespaces, so every daily and sleep reader resolves the
/// same set in the same order.
///
/// Baselines take a narrower set. HRV and resting HR are measured differently by each brand (#459), so
/// only the imports of the ACTIVE device's own brand may seed them; a WHOOP wearer's Oura export shows as
/// history but never moves a WHOOP baseline. Pure strings only. Kotlin twin: `WearableHistory`.
public enum WearableHistory {

    /// Every wearable-export or cloud-pull namespace, in fixed fallback order. Apple Health and Health
    /// Connect are not here: their rows ride the strap source or have their own source-aware readouts.
    /// Mirrors the Kotlin `WearableHistory` object's `sourceIds` list.
    public static let sourceIds = ["garmin-import", "oura-import", "oura-api", "fitbit-import"]

    /// The brand's OWN scores an export carries (Garmin stress, Oura readiness, a sleep score). They are
    /// reference values: a history read never resolves one of them as a NOOP metric of the same key.
    /// Mirrors the Kotlin `WearableHistory` object's `referenceKeys` set.
    public static let referenceKeys: Set<String> = ["stress", "ref_readiness_score", "ref_sleep_score"]

    /// True when a metric-series history read may take `key` from an export namespace.
    /// Kotlin twin: `WearableHistory.seriesKeyAllowed`.
    public static func seriesKeyAllowed(_ key: String) -> Bool { !referenceKeys.contains(key) }

    /// `sourceIds` in read order for `activeDeviceId`: the active device's own brand first (a Garmin
    /// wearer's Garmin export beats an older Oura export on a day both cover), the rest in fixed order.
    /// Kotlin twin: `WearableHistory.readOrder`.
    public static func readOrder(activeDeviceId: String) -> [String] {
        let brand = Baselines.brandBucket(activeDeviceId)
        return sourceIds.filter { Baselines.brandBucket($0) == brand }
            + sourceIds.filter { Baselines.brandBucket($0) != brand }
    }

    /// The provider name a history row is labelled with ("Garmin"), or nil for an id outside `sourceIds`.
    /// A brand name, so it is not localized. Kotlin twin: `WearableHistory.brandName`.
    public static func brandName(for sourceId: String) -> String? {
        guard sourceIds.contains(sourceId) else { return nil }
        switch Baselines.brandBucket(sourceId) {
        case "garmin": return "Garmin"
        case "oura": return "Oura"
        case "fitbit": return "Fitbit"
        default: return nil
        }
    }

    /// The namespaces whose nights may seed the HRV, resting-HR and respiration baselines: the active
    /// device's own brand only, so a baseline never mixes two brands' scales (#459). Empty for a WHOOP or
    /// unknown device. Kotlin twin: `WearableHistory.baselineSeedIds`.
    public static func baselineSeedIds(activeDeviceId: String) -> [String] {
        let brand = Baselines.brandBucket(activeDeviceId)
        guard brand != "whoop" else { return [] }
        return sourceIds.filter { Baselines.brandBucket($0) == brand }
    }
}
