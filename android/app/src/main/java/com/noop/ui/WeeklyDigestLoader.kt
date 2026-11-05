package com.noop.ui

import com.noop.analytics.FusionSource
import com.noop.analytics.IntelligenceEngine
import com.noop.analytics.RestScorer
import com.noop.analytics.WeeklyDigest
import com.noop.analytics.WeeklyDigestDay
import com.noop.analytics.WeeklyDigestEngine
import com.noop.analytics.WeeklyDigestInputs
import com.noop.analytics.WeeklyDigestWatchSeries
import com.noop.data.DailyMetric
import com.noop.data.DeviceMetricKind
import com.noop.data.DeviceRegistry
import com.noop.data.WhoopRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The store half of the weekly-digest input resolver (#89). Not a Composable, so the Trends card
 * and the Monday "weekly summary" notification read the digest's inputs through the same calls.
 * The pure mapping is [WeeklyDigestInputs]. Swift twin: `WeeklyDigestLoader`.
 */
object WeeklyDigestLoader {

    /** Everything the digest needs beyond the daily rows. */
    data class Inputs(
        val restByDay: Map<String, Double> = emptyMap(),
        val watch: WeeklyDigestWatchSeries = WeeklyDigestWatchSeries.EMPTY,
    )

    /** Weeks of history the engine reads around the anchor: this, last, and the four baseline weeks. */
    private const val HISTORY_DAYS = 7 * (WeeklyDigestEngine.BASELINE_WEEKS + 1)

    /**
     * #55: per-day Rest as every Rest surface resolves it — the imported WHOOP figure, else the
     * engine's persisted `sleep_performance`. Moved out of the former `rememberRestByDay`.
     */
    suspend fun restByDay(repo: WhoopRepository, activeDeviceId: String): Map<String, Double> = runCatching {
        val computed = loadComputedSleepSeries(repo, activeDeviceId).performance
        val imported = repo.metricSeries(WhoopRepository.WHOOP_SOURCE, "sleep_performance", "0000-00-00", "9999-99-99")
            .associate { it.day to it.value }
        computed + imported
    }.getOrDefault(emptyMap())

    /**
     * The watch whose series feed the digest: the active device when it is a paired watch (the same
     * resolver the VO₂max read uses), else the registry's owner of [anchorDay] when that day is
     * owned by a watch. null for a strap-only user. Swift twin: `WeeklyDigestLoader.watchId`.
     */
    suspend fun watchId(
        repo: WhoopRepository,
        registry: DeviceRegistry?,
        activeDeviceId: String,
        anchorDay: String,
    ): String? {
        IntelligenceEngine.activeGarminWatchId(repo, activeDeviceId)?.let { return it }
        val owner = registry?.let { runCatching { it.dayOwner(anchorDay) }.getOrNull() }?.deviceId
        return owner?.takeIf { FusionSource.isWatchId(it) }
    }

    /** Both halves for the week containing [anchorDay]. */
    suspend fun load(
        repo: WhoopRepository,
        registry: DeviceRegistry?,
        activeDeviceId: String,
        anchorDay: String,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Inputs {
        val rest = restByDay(repo, activeDeviceId)
        val watchId = watchId(repo, registry, activeDeviceId, anchorDay) ?: return Inputs(rest)
        return Inputs(rest, watchSeries(repo, watchId, anchorDay, zone))
    }

    /** The digest a non-UI caller (the weekly-summary notification) builds from the stores. */
    suspend fun digest(
        repo: WhoopRepository,
        registry: DeviceRegistry?,
        activeDeviceId: String,
        days: List<DailyMetric>,
        anchorDay: String,
        effortDisplayFactor: Double = 1.0,
        zone: ZoneId = ZoneId.systemDefault(),
    ): WeeklyDigest {
        val inputs = load(repo, registry, activeDeviceId, anchorDay, zone)
        return buildWeeklyDigest(days, anchorDay, effortDisplayFactor, inputs.restByDay, inputs.watch)
    }

    /**
     * The watch's per-day series over the digest's six weeks. Daily rollups come from
     * `metricSeries`; totals and Body Battery from the intraday samples, bucketed by local day.
     * Swift twin: `WeeklyDigestLoader.watchSeries`.
     */
    suspend fun watchSeries(
        repo: WhoopRepository,
        watchId: String,
        anchorDay: String,
        zone: ZoneId = ZoneId.systemDefault(),
    ): WeeklyDigestWatchSeries {
        val monday = WeeklyDigestEngine.mondayOfWeek(anchorDay) ?: return WeeklyDigestWatchSeries.EMPTY
        val fromDay = WeeklyDigestEngine.addDays(monday, -HISTORY_DAYS)
        val toDay = WeeklyDigestEngine.addDays(monday, 6)
        val fromTs = LocalDate.parse(fromDay).atStartOfDay(zone).toEpochSecond()
        val toTs = LocalDate.parse(toDay).plusDays(1).atStartOfDay(zone).toEpochSecond() - 1

        suspend fun daily(key: String): Map<String, Double> = runCatching {
            repo.metricSeries(watchId, key, fromDay, toDay).associate { it.day to it.value }
        }.getOrDefault(emptyMap())

        suspend fun samples(kind: DeviceMetricKind, from: Long, to: Long) = runCatching {
            repo.deviceMetricSamples(watchId, kind, from, to)
        }.getOrDefault(emptyList())

        fun dayOf(ts: Long): String = Instant.ofEpochSecond(ts).atZone(zone).toLocalDate().toString()

        suspend fun summedByDay(kind: DeviceMetricKind): Map<String, Double> {
            val out = HashMap<String, Double>()
            for (s in samples(kind, fromTs, toTs)) out.merge(dayOf(s.ts), s.value, Double::plus)
            return out
        }

        val vo2 = runCatching { IntelligenceEngine.garminVo2maxPoints(repo, watchId, toDay) }
            .getOrDefault(emptyList())
            .filter { it.first >= fromDay }
            .toMap()

        return WeeklyDigestWatchSeries(
            // The keys the watch ingest writes (GarminSyncStore / HuaweiHistoryIngest mergeDay).
            stress = daily(StressTodayLoader.watchStressKey(watchId)),
            sleepScore = daily("ref_sleep_score"),
            activeKcal = daily("active_kcal"),
            trainingLoad = summedByDay(DeviceMetricKind.TRAINING_LOAD),
            intensityMinutes = WeeklyDigestInputs.intensityMinutes(
                summedByDay(DeviceMetricKind.INTENSITY_MODERATE_MIN),
                summedByDay(DeviceMetricKind.INTENSITY_VIGOROUS_MIN),
            ),
            vo2max = vo2,
            bodyBatteryRecharge = bodyBatteryRecharge(repo, watchId, fromTs, toTs, zone),
        )
    }

    /**
     * Each wake day's Body Battery recharge from the watch's main sleep that day (the longest
     * session ending on it). Swift twin: `WeeklyDigestLoader.bodyBatteryRecharge`.
     */
    private suspend fun bodyBatteryRecharge(
        repo: WhoopRepository,
        watchId: String,
        fromTs: Long,
        toTs: Long,
        zone: ZoneId,
    ): Map<String, Double> {
        val window = WeeklyDigestInputs.RECHARGE_WINDOW_SECONDS
        val sessions = runCatching { repo.sleepSessionsForDevice(watchId, fromTs - 86_400L, toTs) }
            .getOrDefault(emptyList())
            .filter { it.endTs in fromTs..toTs }
        if (sessions.isEmpty()) return emptyMap()
        val mainByDay = sessions
            .groupBy { Instant.ofEpochSecond(it.endTs).atZone(zone).toLocalDate().toString() }
            .mapValues { (_, s) -> s.maxByOrNull { it.endTs - it.effectiveStartTs }!! }
        val samples = runCatching {
            repo.deviceMetricSamples(watchId, DeviceMetricKind.BODY_BATTERY, fromTs - 86_400L - window, toTs + window)
        }.getOrDefault(emptyList()).map { it.ts to it.value }
        val out = HashMap<String, Double>()
        for ((day, s) in mainByDay) {
            WeeklyDigestInputs.bodyBatteryRecharge(samples, s.effectiveStartTs, s.endTs)?.let { out[day] = it }
        }
        return out
    }

    /** One daily row as the pure mapping reads it, Rest resolved the way every Rest surface does. */
    fun digestDay(d: DailyMetric, restByDay: Map<String, Double>): WeeklyDigestDay = WeeklyDigestDay(
        day = d.day,
        charge = d.recovery,
        effort = d.strain,
        // Rest = the score the day was scored with (#55), else the cold-start composite.
        rest = restByDay[d.day] ?: RestScorer.restFromDaily(d),
        rhr = d.restingHr?.toDouble(),
        hrv = d.avgHrv,
        steps = d.steps?.toDouble(),
        sleepMin = d.totalSleepMin,
        efficiency = d.efficiency,
        deepMin = d.deepMin,
        remMin = d.remMin,
        spo2 = d.spo2Pct,
        respiration = d.respRateBpm,
        skinTemp = d.skinTempDevC,
    )
}
