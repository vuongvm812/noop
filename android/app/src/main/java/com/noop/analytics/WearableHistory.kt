package com.noop.analytics

/**
 * Which wearable-export namespaces a history read takes in, and in what order (#31).
 *
 * A Garmin / Oura / Fitbit export lands under its own source id (`garmin-import`, …), apart from the
 * active device and the canonical WHOOP import. Those rows are history: they fill the days the active
 * device, the WHOOP import and NOOP's own computed rows leave open, and never win a day one of them
 * holds. This is the one place that names the namespaces, so every daily and sleep reader resolves the
 * same set in the same order.
 *
 * Baselines take a narrower set. HRV and resting HR are measured differently by each brand (#459), so
 * only the imports of the ACTIVE device's own brand may seed them; a WHOOP wearer's Oura export shows as
 * history but never moves a WHOOP baseline. Pure strings only. Swift twin: `WearableHistory`.
 */
object WearableHistory {

    /** Every wearable-export or cloud-pull namespace, in fixed fallback order.
     *  Mirrors the Swift `WearableHistory.sourceIds` list. */
    val sourceIds: List<String> = listOf("garmin-import", "oura-import", "oura-api", "fitbit-import")

    /** The brand's OWN scores an export carries: reference values a history read never resolves as a
     *  NOOP metric of the same key. Mirrors the Swift `WearableHistory.referenceKeys` set. */
    val referenceKeys: Set<String> = setOf("stress", "ref_readiness_score", "ref_sleep_score")

    /** True when a metric-series history read may take [key] from an export namespace.
     *  Swift twin: `WearableHistory.seriesKeyAllowed`. */
    fun seriesKeyAllowed(key: String): Boolean = key !in referenceKeys

    /** [sourceIds] in read order: the active device's own brand first, the rest in fixed order.
     *  Swift twin: `WearableHistory.readOrder`. */
    fun readOrder(activeDeviceId: String): List<String> {
        val brand = Baselines.brandBucket(activeDeviceId)
        return sourceIds.filter { Baselines.brandBucket(it) == brand } +
            sourceIds.filter { Baselines.brandBucket(it) != brand }
    }

    /** The provider name a history row is labelled with ("Garmin"), or null outside [sourceIds].
     *  Swift twin: `WearableHistory.brandName`. */
    fun brandName(sourceId: String): String? {
        if (sourceId !in sourceIds) return null
        return when (Baselines.brandBucket(sourceId)) {
            "garmin" -> "Garmin"
            "oura" -> "Oura"
            "fitbit" -> "Fitbit"
            else -> null
        }
    }

    /** The namespaces whose nights may seed the HRV, resting-HR and respiration baselines: the active
     *  device's own brand only (#459). Empty for a WHOOP or unknown device.
     *  Swift twin: `WearableHistory.baselineSeedIds`. */
    fun baselineSeedIds(activeDeviceId: String): List<String> {
        val brand = Baselines.brandBucket(activeDeviceId)
        if (brand == "whoop") return emptyList()
        return sourceIds.filter { Baselines.brandBucket(it) == brand }
    }
}
