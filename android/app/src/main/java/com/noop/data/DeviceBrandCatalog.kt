package com.noop.data

import java.text.Normalizer

/**
 * Single source of truth for recognising a wearable BRAND from its advertised BLE name, and the stored
 * facts that follow from the brand. Pure (no android.bluetooth) so it's JVM-unit-tested — the recognition
 * + capability facts are byte-parity-critical and must be asserted headlessly on both platforms. Faithful
 * twin of Packages/WhoopStore/Sources/WhoopStore/DeviceBrandCatalog.swift.
 *
 * Adding a recognised brand is ONE row in [all]: the wizard's brand labelling ([brandGuess]), the
 * custom-protocol brand view ([com.noop.ble.ExperimentalBrand]), and the source routing all derive from here
 * instead of re-listing the advertised-name tokens per call site.
 */
data class DeviceBrandSpec(
    /** Display + stored `PairedDeviceRow.brand` string ("Polar", "Amazfit", "Oura", …). */
    val brand: String,
    /** Lowercased, diacritic-folded advertised-name substrings that identify this brand. Checked in [all]
     *  order (most specific first) so a Huami sub-brand like Mi Band wins over the broader Amazfit tokens. */
    val nameTokens: List<String>,
    /** The routing kind stored on the device (drives `SourceCoordinator.makeSource`). [SourceKind.liveBLE]
     *  for a brand whose live HR is the standard 0x180D broadcast (generic straps); [SourceKind.huami]
     *  / [SourceKind.oura] / [SourceKind.garmin] / [SourceKind.huawei] for the custom-protocol sources. */
    val sourceKind: SourceKind,
    /** The registry id prefix for a device of this brand ("huami", "oura", "garmin", "huawei", "strap"). */
    val idPrefix: String,
    /** Whether this brand can stream LIVE heart rate at all in NOOP. false for Oura (no open live stream) —
     *  the wizard routes those to import instead of pretending to connect. */
    val canStreamLiveHR: Boolean,
    /** True for the opt-in EXPERIMENTAL tier (Amazfit / Mi Band / Oura); false for the supported brands
     *  (Garmin, and the generic HR straps Polar / Wahoo / …). Garmin left this tier in #43. */
    val isExperimentalTier: Boolean,
)

object DeviceBrandCatalog {
    /** The brand table, checked most-specific-first. Order matters ONLY where one name could match two rows:
     *  Mi Band (a Huami sub-brand) precedes Amazfit so a "Mi Smart Band" is not mis-labelled Amazfit. Tokens
     *  are otherwise disjoint across rows, so the remaining order is cosmetic. */
    val all: List<DeviceBrandSpec> = listOf(
        // Custom-protocol live sources. Mi Band / Amazfit / Oura are the EXPERIMENTAL tier (opt-in and
        // best-effort); Garmin is supported.
        DeviceBrandSpec("Mi Band", listOf("mi band", "miband", "smart band", "xiaomi"),
            SourceKind.huami, "huami", canStreamLiveHR = true, isExperimentalTier = true),
        DeviceBrandSpec("Amazfit", listOf("amazfit", "zepp", "helio", "huami"),
            SourceKind.huami, "huami", canStreamLiveHR = true, isExperimentalTier = true),
        DeviceBrandSpec("Garmin",
            listOf("garmin", "forerunner", "fenix", "vivoactive", "venu", "instinct", "epix", "vivosmart", "hrm"),
            SourceKind.garmin, "garmin", canStreamLiveHR = true, isExperimentalTier = false),
        // Huawei Band 10: a supported watch source like Garmin (listed beside it, not under Experimental).
        // No live HR stream in NOOP yet.
        DeviceBrandSpec("Huawei", listOf("huawei band 10"),
            SourceKind.huawei, "huawei", canStreamLiveHR = false, isExperimentalTier = false),
        DeviceBrandSpec("Oura", listOf("oura"),
            SourceKind.oura, "oura", canStreamLiveHR = false, isExperimentalTier = true),
        // Shipped generic HR straps — standard 0x180D broadcast, labelled for display only.
        DeviceBrandSpec("Polar", listOf("polar"),
            SourceKind.liveBLE, "strap", canStreamLiveHR = true, isExperimentalTier = false),
        DeviceBrandSpec("Wahoo", listOf("wahoo", "tickr"),
            SourceKind.liveBLE, "strap", canStreamLiveHR = true, isExperimentalTier = false),
        DeviceBrandSpec("Coospo", listOf("coospo"),
            SourceKind.liveBLE, "strap", canStreamLiveHR = true, isExperimentalTier = false),
        DeviceBrandSpec("Scosche", listOf("scosche", "rhythm"),
            SourceKind.liveBLE, "strap", canStreamLiveHR = true, isExperimentalTier = false),
        DeviceBrandSpec("Magene", listOf("magene"),
            SourceKind.liveBLE, "strap", canStreamLiveHR = true, isExperimentalTier = false),
    )

    /** The brand whose advertised name matches, or null if unrecognised. Diacritic-folded (NFD + strip
     *  combining marks, mirroring Swift's `.diacriticInsensitive`) + lowercased; substring match in [all]
     *  order. Twin of Swift `DeviceBrandCatalog.spec(forAdvertisedName:)`. */
    fun specForAdvertisedName(name: String): DeviceBrandSpec? {
        val n = Normalizer.normalize(name, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
        return all.firstOrNull { spec -> spec.nameTokens.any { n.contains(it) } }
    }

    /** The row for a known brand string ("Amazfit", …), or null. Lets the typed `ExperimentalBrand` derive
     *  its facts (capability/routing) from this table rather than re-declaring them. */
    fun specForBrand(brand: String): DeviceBrandSpec? = all.firstOrNull { it.brand == brand }

    /** True when a registry deviceId belongs to an Oura ring, resolved from its "<idPrefix>-" prefix against
     *  this canonical table (never an ad-hoc "oura" literal — the registry mints every non-WHOOP id as
     *  "<idPrefix>-<uuid>", so the prefix IS the brand key). Read/UI side only: it lets the sleep surfaces
     *  name an Oura night's provenance "Oura". Twin of Swift `DeviceBrandCatalog.isOura`. */
    fun isOura(deviceId: String): Boolean =
        all.firstOrNull { deviceId.startsWith(it.idPrefix + "-") }?.sourceKind == SourceKind.oura

    /** The source kinds of a paired WATCH: a device whose own vitals, sleep stages, stress and workouts are
     *  primary on the days it owns, with NOOP scoring Charge / Effort / Rest from them. */
    val WATCH_SOURCE_KINDS: Set<SourceKind> = setOf(SourceKind.garmin, SourceKind.huawei)

    /** The watch brand row whose "<idPrefix>-" a stored source id carries, or null. The brand's own export
     *  import ("<idPrefix>-import") is a different source and never matches. A "-noop" computed sibling
     *  does match here; [watchSpecForDeviceId] is the stricter device check. */
    fun watchSpecForSource(sourceId: String): DeviceBrandSpec? {
        val spec = all.firstOrNull { it.sourceKind in WATCH_SOURCE_KINDS && sourceId.startsWith(it.idPrefix + "-") }
            ?: return null
        return spec.takeIf { sourceId != it.idPrefix + "-import" }
    }

    /** The watch brand row for a paired watch's registry id ("garmin-<suffix>", "huawei-<suffix>"), or null
     *  for any other id, a brand export import, or a "-noop" computed sibling. */
    fun watchSpecForDeviceId(deviceId: String): DeviceBrandSpec? =
        if (deviceId.endsWith("-noop")) null else watchSpecForSource(deviceId)
}
