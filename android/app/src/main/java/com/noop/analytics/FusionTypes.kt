package com.noop.analytics

/*
 * Fusion value types (v5 — Local Multi-Device Fusion).
 *
 * Value-for-value Kotlin twin of Packages/StrandAnalytics/.../FusionTypes.swift. The plain
 * inputs/outputs for the pure fusion engine in
 * docs/superpowers/specs/2026-06-19-v5-local-multi-device-fusion-design.md. No I/O, no model, no
 * network — the repository feeds these rows it already loads, the engine returns a resolved point.
 * Mirrors the existing DailyMetricSource / SourcedDailyMetric provenance vocabulary, generalised so a
 * future Polar/Garmin/Oura is a table entry, not a type change.
 */

/**
 * Where a fused number came from — a superset of the legacy macOS DailyMetricSource, extended to every
 * source the importers already write. [id] is the canonical source id ("my-whoop" etc.) so a
 * FusionSource round-trips to/from the stored deviceId / source string without a lookup table.
 */
enum class FusionSource(val id: String, val displayName: String) {
    /** Imported WHOOP record (CSV/zip export under the strap's deviceId, e.g. "my-whoop"). */
    WHOOP_IMPORT("my-whoop", "WHOOP"),

    /** NOOP-computed score derived on-device from raw strap streams (the "$deviceId-noop" sibling). */
    NOOP_COMPUTED("my-whoop-noop", "NOOP"),

    /** Apple Health aggregate of a declared-compatible quantity. */
    APPLE_HEALTH("apple-health", "Apple Health"),

    /** Health Connect aggregate (Android's Apple-equivalent body-metric source). */
    HEALTH_CONNECT("health-connect", "Health Connect"),

    /** Mi Band / Xiaomi import — a dedicated wrist band (counts steps directly). */
    XIAOMI_BAND("xiaomi-band", "Mi Band"),

    /**
     * A Garmin watch synced over its own protocol (Garmin child 9). Its rows live under the watch's registry
     * id ("garmin-<suffix>"), so a stored id is mapped through [forStoredSourceId]; [id] is only the
     * canonical name. The watch's OWN values (resting HR, overnight HRV, SpO2, respiration, stress, Body
     * Battery, sleep stages and score) are primary; NOOP computes Charge / Effort / Rest from them. A Garmin
     * Connect CSV export ("garmin-import") is a different source and does not map here.
     */
    GARMIN_WATCH("garmin-watch", "Garmin"),

    /**
     * A Huawei Band 10 synced over its own protocol. Kotlin-only (fork). Same role as [GARMIN_WATCH]: rows
     * live under the band's registry id ("huawei-<suffix>"), the band's own values are primary, and NOOP
     * computes Charge / Effort / Rest from them.
     */
    HUAWEI_WATCH("huawei-watch", "Huawei"),

    /** Nutrition CSV import (single-source passthrough — calories/macros). */
    NUTRITION_CSV("nutrition-csv", "Nutrition"),

    /** Locally-cached fallback row with no richer provenance. */
    LOCAL_CACHE("local-cache", "Cached"),
    ;

    companion object {
        /**
         * Map a STORED source id (a dailyMetric deviceId / resolver source) onto its fusion source. A paired
         * watch is stored under its registry id, so it cannot round-trip through [id]; every other source
         * does. Swift twin: `FusionSource.forStoredSourceId` (Garmin only; Huawei is Kotlin-only).
         */
        fun forStoredSourceId(id: String): FusionSource? {
            watchBrand(id)?.let { return it }
            return entries.firstOrNull { it.id == id }
        }

        /** True for a paired Garmin watch's registry id. Swift twin: `FusionSource.isGarminWatchId`. */
        fun isGarminWatchId(id: String): Boolean = watchBrand(id) == GARMIN_WATCH

        /** True for any paired watch's registry id (Garmin or Huawei). */
        fun isWatchId(id: String): Boolean = watchBrand(id) != null

        /**
         * The ONE resolver for "is this device id a paired watch, and which brand": [GARMIN_WATCH] for
         * "garmin-<suffix>", [HUAWEI_WATCH] for "huawei-<suffix>", null for anything else (a brand export
         * import, a "-noop" computed sibling, a strap, a ring). Backed by [com.noop.data.DeviceBrandCatalog].
         */
        fun watchBrand(id: String): FusionSource? =
            com.noop.data.DeviceBrandCatalog.watchSpecForDeviceId(id)?.let { forWatchKind(it.sourceKind) }

        /** The fusion source of a watch [kind] from [com.noop.data.DeviceBrandCatalog.WATCH_SOURCE_KINDS]. */
        internal fun forWatchKind(kind: com.noop.data.SourceKind): FusionSource? = when (kind) {
            com.noop.data.SourceKind.garmin -> GARMIN_WATCH
            com.noop.data.SourceKind.huawei -> HUAWEI_WATCH
            else -> null
        }
    }

    /** True for a paired-watch source ([GARMIN_WATCH], [HUAWEI_WATCH]). */
    val isWatch: Boolean get() = this == GARMIN_WATCH || this == HUAWEI_WATCH

    /** The catalog id prefix of a watch source ("garmin", "huawei"); null for every other source. */
    val watchIdPrefix: String?
        get() = com.noop.data.DeviceBrandCatalog.all.firstOrNull { forWatchKind(it.sourceKind) == this }?.idPrefix
}

/**
 * How well a metric's value agrees across the sources that reported it on the same day. agree → quiet
 * parenthetical; minorDelta → show both, neutral; conflict → flag, never merge. Deterministic
 * threshold output (no statistics beyond a clamp + a percentage); see
 * [MetricArbitrationPolicy.tolerance].
 */
enum class AgreementState {
    /** Only one source reported the metric — nothing to cross-check, no chip shown. */
    SINGLE,

    /** Within the metric's tolerance — sources agree. */
    AGREE,

    /** Outside tolerance but inside the plausible-spread band — show both, no alarm. */
    MINOR_DELTA,

    /** Large divergence — flag prominently, keep both, never silently average. */
    CONFLICT,
}

/**
 * One source's value for a (metric, day), with the trust tier the policy assigned it. The winner is
 * the lowest [tier] (most trusted), ties broken by [sourcePriority] (stable). [reason] is the
 * published, plain-English justification ("counts directly", "best stager") — the honesty contract.
 */
data class ContributingSource(
    val source: FusionSource,
    val value: Double,
    /** Trust tier (lower = more trusted); from [MetricArbitrationPolicy.tier]. */
    val tier: Int,
    /** Stable tiebreak within a tier (lower wins); from the policy's source ordering. */
    val sourcePriority: Int,
    /** The visible "best signal" reason for this source on this metric. Never "accurate"/"correct". */
    val reason: String,
)

/**
 * The fused result for one (metric, day): the winning value, the source that supplied it, every
 * contributor (for the compare sheet), and the agreement classification. Pure data — existing
 * consumers that ignore [agreement] are unaffected.
 */
data class FusedMetricPoint(
    /** The metric key this point resolves (matches the resolver's series keys, e.g. "rhr", "steps"). */
    val metric: String,
    /** The chosen value (verbatim from [winningSource]'s row — never an average). */
    val value: Double,
    /** The source that supplied [value] (highest trust, stable tiebreak). */
    val winningSource: FusionSource,
    /** Every source that reported this metric for the day, winner first, then by tier/priority. */
    val contributors: List<ContributingSource>,
    /** Cross-validation outcome across [contributors]. */
    val agreement: AgreementState,
)

/**
 * A single source's raw input to the fusion engine: a value for a metric on a day. The repository
 * builds these from rows it already reads (it does the I/O); the engine stays pure.
 */
data class FusionInput(
    val source: FusionSource,
    val value: Double,
)
