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
 * decode and are not used. Respiration: the watch day's stored overnight value ([com.noop.data.GarminSyncStore.nightMean]),
 * the same number the row and the `resp_rate` series carry (#52).
 */
object GarminScoringInputs {

    /** Fewest in-sleep samples a mean is formed from on its own: twelve five-minute HRV values is an hour of
     *  sleep (#53; three, a quarter hour, let a sliver of the night stand for all of it). */
    const val MIN_NIGHT_SAMPLES = 12

    /** Alternatively, the samples may cover at least this fraction of the sleep window (each sample standing
     *  for [SAMPLE_INTERVAL_SEC]), so a short but fully sampled night still resolves. */
    const val MIN_NIGHT_COVERAGE = 0.5

    /** The watch's sampling interval for the coverage rule: its overnight HRV values are five-minute values. */
    const val SAMPLE_INTERVAL_SEC = 300

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

    /**
     * Mean of the samples inside any of [nights] (inclusive), or null unless there are at least
     * [MIN_NIGHT_SAMPLES] of them or they cover [MIN_NIGHT_COVERAGE] of the sleep window (#53). Each sample
     * counts once even when blocks overlap. Swift twin: `GarminScoringInputs.nightMean`.
     */
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
        if (n == 0) return null
        if (n >= MIN_NIGHT_SAMPLES) return sum / n
        val window = sleepSeconds(nights)
        if (window <= 0 || (n.toLong() * SAMPLE_INTERVAL_SEC).toDouble() < MIN_NIGHT_COVERAGE * window.toDouble()) {
            return null
        }
        return sum / n
    }

    /** Seconds covered by the union of [nights] (overlapping blocks counted once). Swift twin:
     *  `GarminScoringInputs.sleepSeconds`. */
    internal fun sleepSeconds(nights: List<Night>): Long {
        val sorted = nights.filter { it.end > it.start }.sortedBy { it.start }
        var total = 0L
        var curStart = 0L
        var curEnd = 0L
        var open = false
        for (b in sorted) {
            if (open && b.start <= curEnd) {
                curEnd = maxOf(curEnd, b.end)
            } else {
                if (open) total += curEnd - curStart
                curStart = b.start
                curEnd = b.end
                open = true
            }
        }
        if (open) total += curEnd - curStart
        return total
    }

    /** Swift twin: the nested `pick` in `GarminScoringInputs.resolve`. */
    private fun pick(key: String, watch: Double?, computed: Double?, watchSource: FusionSource): FusedMetricPoint? {
        val inputs = ArrayList<FusionInput>()
        if (watch != null && watch.isFinite() && watch > 0) inputs.add(FusionInput(watchSource, watch))
        if (computed != null && computed.isFinite() && computed > 0) {
            inputs.add(FusionInput(FusionSource.NOOP_COMPUTED, computed))
        }
        return FusionResolver.resolve(key, inputs)
    }

    /** Resolve one watch-owned day's resting HR, overnight HRV and respiration. [watchSource] is the owning
     *  watch's brand ([FusionSource.watchBrand]); Garmin by default, the only brand the Swift twin knows. */
    fun resolve(
        computedRestingHr: Int?,
        computedHrv: Double?,
        computedResp: Double?,
        watch: WatchDay?,
        hrvSamples: List<Sample>,
        watchRespRateBpm: Double?,
        nights: List<Night>,
        watchSource: FusionSource = FusionSource.GARMIN_WATCH,
    ): Resolution {
        val rhr = pick("rhr", watch?.restingHr?.toDouble(), computedRestingHr?.toDouble(), watchSource)

        val lastNight = watch?.lastNightHrvMs?.takeIf { it.isFinite() && it > 0 }
        val overnight = if (lastNight == null) nightMean(hrvSamples, nights) else null
        val hrv = pick("hrv", lastNight ?: overnight, computedHrv, watchSource)
        val basis = when {
            hrv == null -> HrvBasis.NONE
            hrv.winningSource.isWatch -> if (lastNight != null) HrvBasis.WATCH_LAST_NIGHT else HrvBasis.WATCH_OVERNIGHT
            else -> HrvBasis.COMPUTED
        }

        val resp = pick("resp_rate", watchRespRateBpm, computedResp, watchSource)

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
     * The baseline era cut for HRV and resting HR (#459) when a watch brand (Garmin, Huawei) is part of the
     * history, as [Baselines.deviceEraEpoch] computes it; 0 when no day is a watch-brand day, which keeps
     * every other history byte-identical. Pairs are (day, sourceId).
     */
    fun baselineEraEpoch(sourceDays: List<Pair<String, String>>): Double {
        if (sourceDays.none { Baselines.brandBucket(it.second) in Baselines.WATCH_BRAND_BUCKETS }) return 0.0
        return Baselines.deviceEraEpoch(sourceDays)
    }

    /**
     * Garmin support is official in this fork (#43), so a Garmin-derived Effort carries no experimental
     * label: it shows "calibrating" while its baselines are not usable, the same as WHOOP. Setting this
     * back to false restores the label on both platforms.
     */
    const val EFFORT_VALIDATED = true

    /** How long a watch VO2max still stands for a week (#51). The watch re-estimates it only after an
     *  activity that qualifies, so its latest value is its current one for weeks at a time. */
    const val WATCH_VO2MAX_MAX_AGE_DAYS = 28

    /**
     * The watch's own VO2max for the week keyed [weekKey] ("yyyy-MM-dd"): its latest positive point on or
     * before that day and within [WATCH_VO2MAX_MAX_AGE_DAYS] of it, or null. The VO2max card prefers it
     * over NOOP's estimate ([IntelligenceEngine.fitnessAgeRows]). Swift twin: `GarminScoringInputs.watchVo2max`.
     */
    fun watchVo2max(points: List<Pair<String, Double>>, weekKey: String): Double? {
        val week = runCatching { java.time.LocalDate.parse(weekKey) }.getOrNull() ?: return null
        val from = week.minusDays((WATCH_VO2MAX_MAX_AGE_DAYS - 1).toLong()).toString()
        return points.filter { (day, v) -> day >= from && day <= weekKey && v > 0.0 && v.isFinite() }
            .maxByOrNull { it.first }?.second
    }

    /**
     * The VO2max points the week resolver reads (#71): every live watch point, plus the Garmin export's
     * imported points on days the watch has no usable value, oldest first. The watch's own sync wins
     * wherever it has a day. Swift twin: `GarminScoringInputs.vo2maxPoints`.
     */
    fun vo2maxPoints(
        watch: List<Pair<String, Double>>, imported: List<Pair<String, Double>>,
    ): List<Pair<String, Double>> {
        val watchPoints = watch.filter { it.second > 0.0 && it.second.isFinite() }
        val watchDays = watchPoints.mapTo(HashSet()) { it.first }
        return (watchPoints + imported.filter { it.second > 0.0 && it.second.isFinite() && it.first !in watchDays })
            .sortedBy { it.first }
    }

    /**
     * Every Saturday week key strictly before [beforeWeekKey] that [watchVo2max] gives a value from [points],
     * with that value, oldest first (#71). The recompute pass writes these past weeks so the VO2max history
     * shows the watch's numbers, not only for the current week. Empty when [beforeWeekKey] does not parse.
     * Swift twin: `GarminScoringInputs.watchVo2maxWeeks`.
     */
    fun watchVo2maxWeeks(points: List<Pair<String, Double>>, beforeWeekKey: String): List<Pair<String, Double>> {
        if (runCatching { java.time.LocalDate.parse(beforeWeekKey) }.isFailure) return emptyList()
        val first = points.filter { it.second > 0.0 && it.second.isFinite() }.minOfOrNull { it.first } ?: return emptyList()
        val firstDate = runCatching { java.time.LocalDate.parse(first) }.getOrNull() ?: return emptyList()
        // Monday = 1 … Saturday = 6: step forward to the first Saturday on or after the oldest point.
        var week = firstDate.plusDays(((6 - firstDate.dayOfWeek.value + 7) % 7).toLong())
        val out = ArrayList<Pair<String, Double>>()
        while (true) {
            val key = week.toString()
            if (key >= beforeWeekKey) break
            watchVo2max(points, key)?.let { out.add(key to it) }
            week = week.plusDays(7)
        }
        return out
    }

    /** True when an Effort whose inputs came from [sourceId] (registry [brand]) carries the experimental label. */
    fun effortIsExperimental(sourceId: String, brand: String?): Boolean {
        if (EFFORT_VALIDATED) return false
        if (FusionSource.isWatchId(sourceId)) return true
        return brand != null && brand.equals("Garmin", ignoreCase = true) && sourceId != "garmin-import"
    }
}
