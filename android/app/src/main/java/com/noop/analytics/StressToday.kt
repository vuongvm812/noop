package com.noop.analytics

/**
 * Today's stress reading, resolved ONCE for every surface that shows it (#42).
 *
 * The Today card and the Stress screen both draw this value, so both call this resolver and nothing else
 * decides the source, the latest level or the average. Two sources can answer:
 *
 * - [Source.GARMIN]: the watch's own 0-100 stress score. Intraday samples come from the synced monitoring
 *   files; the daily average is the watch day (`garmin_stress`) or, failing that, the day an imported
 *   Garmin export carries (`stress` under `garmin-import`, #31).
 * - [Source.NOOP]: NOOP's own 0-3 autonomic-load proxy, scored from the day's heart rate and R-R by
 *   [DaytimeStress].
 *
 * The active device's brand picks which source is asked first; the other is the fallback, so a
 * Garmin-only user and a WHOOP-only user each get the one they have. The scales are never mixed: a
 * reading carries its own [Reading.scaleMax]. Pure, so the Swift twin (`StressToday`) is pinned by the
 * same cases.
 */
object StressToday {

    /** [HUAWEI]: a Huawei Band 10's own 0-100 stress score, read the same way as [GARMIN] (Kotlin-only). */
    enum class Source(val raw: String) { GARMIN("garmin"), NOOP("noop"), HUAWEI("huawei") }

    /** One timestamped level on the reading's own scale. */
    data class Point(val ts: Long, val level: Double)

    data class Reading(
        val source: Source,
        /** The most recent level, or null when only a daily average is known (an imported day). */
        val latest: Point?,
        /** Today's average, or null when nothing scored an average yet. */
        val average: Double?,
        /** The intraday series for the sparkline, oldest first. Empty when no samples exist. */
        val points: List<Point>,
    ) {
        /** 100 for a watch (Garmin, Huawei), 3 for NOOP. */
        val scaleMax: Double get() = if (source == Source.NOOP) NOOP_MAX else GARMIN_MAX
    }

    /**
     * The one figure the Today Key Metrics Stress tile shows (#57): the latest level, or today's average
     * when only an average is known (an imported day). Null when the reading has neither. The Stress
     * screen's summary card prints the same `latest`, so the tile and the card agree.
     * Swift twin: `StressToday.tileLevel`.
     */
    fun tileLevel(reading: Reading?): Double? = reading?.latest?.level ?: reading?.average

    /** Garmin's highest stress value. Anything outside 0..100 is a watch sentinel and is ignored. */
    const val GARMIN_MAX = 100.0

    /** The stored daily-average key of a Huawei band's own stress (the twin of `garmin_stress`). */
    const val HUAWEI_STRESS_KEY = "huawei_stress"

    /** NOOP's highest stress value (the 0-3 proxy). */
    const val NOOP_MAX = 3.0

    /**
     * Resolve today's reading, or null when neither source has anything for the day.
     *
     * @param activeDeviceId the registry's active device id. A Garmin device asks Garmin first.
     * @param garminSamples today's watch stress samples, any order.
     * @param garminWatchDay today's stored watch average (`garmin_stress`), if any.
     * @param garminImportDay today's average from an imported Garmin export (`stress`), if any.
     * @param noopTimeline today's scored NOOP points (`DaytimeStress.Result.timeline` with a level).
     * @param noopDayMean today's NOOP average (`DaytimeStress.Result.dayMean`).
     * Swift twin: `StressToday.resolve`.
     */
    fun resolve(
        activeDeviceId: String,
        garminSamples: List<Point>,
        garminWatchDay: Double?,
        garminImportDay: Double?,
        noopTimeline: List<Point>,
        noopDayMean: Double?,
    ): Reading? {
        val source = watchSource(activeDeviceId)
        val garmin = garminReading(garminSamples, garminWatchDay, garminImportDay)
            ?.let { if (source == Source.GARMIN) it else it.copy(source = source) }
        val noop = noopReading(noopTimeline, noopDayMean)
        return if (prefersWatch(activeDeviceId)) garmin ?: noop else noop ?: garmin
    }

    /** The source a watch reading carries for [activeDeviceId]: [Source.HUAWEI] for a paired Huawei band,
     *  else [Source.GARMIN] (the active Garmin, or a Garmin export behind any other device). */
    fun watchSource(activeDeviceId: String): Source =
        if (FusionSource.watchBrand(activeDeviceId) == FusionSource.HUAWEI_WATCH) Source.HUAWEI else Source.GARMIN

    /** True when the active device is a watch brand (Garmin, Huawei), so its own stress answers before
     *  NOOP's. Garmin semantics are [prefersGarmin]'s. */
    fun prefersWatch(activeDeviceId: String): Boolean =
        prefersGarmin(activeDeviceId) || watchSource(activeDeviceId) == Source.HUAWEI

    /**
     * True when the active device is a Garmin, so its own stress answers before NOOP's. A loader uses it to
     * skip scoring NOOP's day when the watch already answered. Swift twin: `StressToday.prefersGarmin`.
     */
    fun prefersGarmin(activeDeviceId: String): Boolean = Baselines.brandBucket(activeDeviceId) == "garmin"

    /**
     * The Garmin half. The average is the mean of the samples drawn, so the number and the sparkline can
     * never disagree; only a day with no samples falls back to a stored daily value, the watch day before
     * the imported one. Swift twin: `StressToday.garminReading`.
     */
    internal fun garminReading(samples: List<Point>, watchDay: Double?, importDay: Double?): Reading? {
        val points = samples
            .filter { it.level.isFinite() && it.level >= 0.0 && it.level <= GARMIN_MAX }
            .sortedBy { it.ts }
        val stored = listOfNotNull(watchDay, importDay)
            .firstOrNull { it.isFinite() && it >= 0.0 && it <= GARMIN_MAX }
        val average = if (points.isEmpty()) stored else points.sumOf { it.level } / points.size
        average ?: return null
        return Reading(Source.GARMIN, points.lastOrNull(), average, points)
    }

    /** The NOOP half: the scored timeline, its last point and the day mean [DaytimeStress] computed.
     *  Swift twin: `StressToday.noopReading`. */
    internal fun noopReading(timeline: List<Point>, dayMean: Double?): Reading? {
        val points = timeline
            .filter { it.level.isFinite() && it.level >= 0.0 && it.level <= NOOP_MAX }
            .sortedBy { it.ts }
        val average = dayMean?.takeIf { it.isFinite() }
        if (points.isEmpty() && average == null) return null
        return Reading(Source.NOOP, points.lastOrNull(), average, points)
    }
}
