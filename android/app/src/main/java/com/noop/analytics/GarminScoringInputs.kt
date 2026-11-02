package com.noop.analytics

/**
 * Garmin scoring inputs (Garmin child 9). Kotlin twin of
 * Packages/StrandAnalytics/Sources/StrandAnalytics/GarminScoringInputs.swift, held byte-identical by the
 * oracle in GarminScoringInputsTest.
 *
 * A paired Garmin watch owns a day the way a strap does: NOOP reads its heart rate, stages its night from
 * the watch's own hypnogram, and scores Charge / Effort / Rest with the SAME engines as WHOOP. The watch
 * computes resting HR, overnight HRV and respiration itself; NOOP's raw-stream estimates of the same facts
 * come from monitoring HR sampled about once a minute and no beat intervals. Each fact therefore goes
 * through ONE resolver, [FusionResolver], with both readings as inputs, and the arbitration table decides
 * (the watch is tier 0, NOOP's estimate tier 1). The row that feeds RecoveryScorer and the dashboard's
 * merged row then carry the same value.
 *
 * Overnight HRV: the watch's last-night average (FIT 370) when present, else the mean of its 5-minute HRV
 * values (FIT 371) inside the night's sleep blocks. Beat intervals (FIT 290) are an unconfirmed candidate
 * decode and are not used. Respiration: the mean of the watch's samples (FIT 297) inside the sleep blocks.
 */
object GarminScoringInputs {

    /** Fewest in-sleep samples a mean is formed from. Five-minute HRV values: 3 = a quarter hour of sleep. */
    const val MIN_NIGHT_SAMPLES = 3

    /** One timestamped watch sample (unix seconds, value in the kind's unit). */
    data class Sample(val ts: Long, val value: Double)

    /** A sleep block [start, end] in unix seconds. */
    data class Night(val start: Long, val end: Long)

    /** The watch's own day values: resting HR (FIT 211), last-night HRV average (FIT 370). */
    data class WatchDay(val restingHr: Int?, val lastNightHrvMs: Double?)

    /** Where the resolved overnight HRV came from. [raw] matches the Swift rawValue. */
    enum class HrvBasis(val raw: String) {
        WATCH_LAST_NIGHT("fit370"),
        WATCH_OVERNIGHT("fit371"),
        COMPUTED("noop"),
        NONE("none"),
    }

    /** The resolved scoring inputs for one day, each with the source that won it. */
    data class Resolution(
        val restingHr: Int?,
        val restingHrSource: FusionSource?,
        val avgHrv: Double?,
        val avgHrvSource: FusionSource?,
        val hrvBasis: HrvBasis,
        val respRateBpm: Double?,
        val respRateSource: FusionSource?,
    )

    /** Mean of the samples inside any of [nights] (inclusive), or null under [MIN_NIGHT_SAMPLES]. */
    fun nightMean(samples: List<Sample>, nights: List<Night>): Double? {
        var sum = 0.0
        var n = 0
        for (s in samples) {
            if (!s.value.isFinite() || s.value <= 0) continue
            if (nights.any { s.ts >= it.start && s.ts <= it.end }) {
                sum += s.value
                n += 1
            }
        }
        return if (n >= MIN_NIGHT_SAMPLES) sum / n else null
    }

    /** Swift twin: the nested `pick` in `GarminScoringInputs.resolve`. */
    private fun pick(key: String, watch: Double?, computed: Double?): FusedMetricPoint? {
        val inputs = ArrayList<FusionInput>()
        if (watch != null && watch.isFinite() && watch > 0) inputs.add(FusionInput(FusionSource.GARMIN_WATCH, watch))
        if (computed != null && computed.isFinite() && computed > 0) {
            inputs.add(FusionInput(FusionSource.NOOP_COMPUTED, computed))
        }
        return FusionResolver.resolve(key, inputs)
    }

    /** Resolve one Garmin-owned day's resting HR, overnight HRV and respiration. */
    fun resolve(
        computedRestingHr: Int?,
        computedHrv: Double?,
        computedResp: Double?,
        watch: WatchDay?,
        hrvSamples: List<Sample>,
        respSamples: List<Sample>,
        nights: List<Night>,
    ): Resolution {
        val rhr = pick("rhr", watch?.restingHr?.toDouble(), computedRestingHr?.toDouble())

        val lastNight = watch?.lastNightHrvMs?.takeIf { it.isFinite() && it > 0 }
        val overnight = if (lastNight == null) nightMean(hrvSamples, nights) else null
        val hrv = pick("hrv", lastNight ?: overnight, computedHrv)
        val basis = when (hrv?.winningSource) {
            FusionSource.GARMIN_WATCH -> if (lastNight != null) HrvBasis.WATCH_LAST_NIGHT else HrvBasis.WATCH_OVERNIGHT
            null -> HrvBasis.NONE
            else -> HrvBasis.COMPUTED
        }

        val resp = pick("resp_rate", nightMean(respSamples, nights), computedResp)

        return Resolution(
            // Swift `Double.rounded()` rounds half away from zero; so does Math.round for these positive values.
            restingHr = rhr?.let { Math.round(it.value).toInt() },
            restingHrSource = rhr?.winningSource,
            avgHrv = hrv?.value,
            avgHrvSource = hrv?.winningSource,
            hrvBasis = basis,
            respRateBpm = resp?.value,
            respRateSource = resp?.winningSource,
        )
    }

    /**
     * The baseline era cut for HRV and resting HR (#459) when a Garmin watch is part of the history, as
     * [Baselines.deviceEraEpoch] computes it; 0 when no day is a Garmin day, which keeps every non-Garmin
     * history byte-identical. Pairs are (day, sourceId).
     */
    fun baselineEraEpoch(sourceDays: List<Pair<String, String>>): Double {
        if (sourceDays.none { Baselines.brandBucket(it.second) == "garmin" }) return 0.0
        return Baselines.deviceEraEpoch(sourceDays)
    }

    /**
     * Effort from Garmin monitoring HR stays EXPERIMENTAL until a multi-day comparison shows it tracks
     * varying days (rest, easy, hard). Flip only with that comparison on the PR.
     */
    const val EFFORT_VALIDATED = false

    /** True when an Effort whose inputs came from [sourceId] (registry [brand]) carries the experimental label. */
    fun effortIsExperimental(sourceId: String, brand: String?): Boolean {
        if (EFFORT_VALIDATED) return false
        if (FusionSource.isGarminWatchId(sourceId)) return true
        return brand != null && brand.equals("Garmin", ignoreCase = true) && sourceId != "garmin-import"
    }
}
