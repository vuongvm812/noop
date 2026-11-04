package com.noop.ui

import com.noop.analytics.StressToday
import com.noop.data.DeviceMetricKind
import com.noop.data.GarminSyncStore
import com.noop.data.WhoopRepository
import com.noop.widget.StressWidgetProducer
import java.time.ZoneId

/**
 * Reads today's stress inputs and hands them to [StressToday.resolve] (#42).
 *
 * The Today stress card and the Stress screen's summary both call [load], so they resolve the same
 * source, latest level and average from the same reads. NOOP's half comes from
 * [StressWidgetProducer.todayCurve], the gated, memoised scorer the hosted stress card and the widget
 * already use, so opening the Stress screen after Today costs one fingerprint query rather than a second
 * day of scoring. Swift twin: `StressTodayLoader`.
 */
internal object StressTodayLoader {

    suspend fun load(
        repo: WhoopRepository,
        activeDeviceId: String,
        personalBaseline: Boolean,
        nowSeconds: Long = System.currentTimeMillis() / 1000L,
        zone: ZoneId = ZoneId.systemDefault(),
    ): StressToday.Reading? {
        val window = stressLocalDayWindowContaining(nowSeconds, zone)
        val day = window.day.toString()

        // The ACTIVE device's own watch samples. A WHOOP id simply has none, so no brand check here.
        val samples = runCatching {
            repo.deviceMetricSamples(activeDeviceId, DeviceMetricKind.STRESS, window.fromEpochSecond, nowSeconds)
        }.getOrDefault(emptyList()).map { StressToday.Point(it.ts, it.value) }
        val watchDay = runCatching {
            repo.metricSeries(activeDeviceId, watchStressKey(activeDeviceId), day, day)
        }.getOrNull()?.lastOrNull()?.value
        val importDay = runCatching {
            repo.metricSeries(watchStressImportId(activeDeviceId), "stress", day, day)
        }.getOrNull()?.lastOrNull()?.value

        val garminOnly = StressToday.resolve(activeDeviceId, samples, watchDay, importDay, emptyList(), null)
        // The watch answered for a watch wearer: NOOP's own score would lose anyway, so do not pay for it.
        if (garminOnly != null && StressToday.prefersWatch(activeDeviceId)) return garminOnly

        val curve = StressWidgetProducer.todayCurve(repo, activeDeviceId, personalBaseline, nowSeconds, zone)
        val timeline = curve?.points.orEmpty().mapNotNull { p -> p.level?.let { StressToday.Point(p.ts, it) } }
        return StressToday.resolve(activeDeviceId, samples, watchDay, importDay, timeline, curve?.dayMean)
    }

    /** The stored daily-average stress key for [activeDeviceId]'s brand: `huawei_stress` for a Huawei band,
     *  `garmin_stress` otherwise (a WHOOP id simply has no row under it). */
    fun watchStressKey(activeDeviceId: String): String = when (StressToday.watchSource(activeDeviceId)) {
        StressToday.Source.HUAWEI -> StressToday.HUAWEI_STRESS_KEY
        else -> GarminSyncStore.GARMIN_STRESS_KEY
    }

    /** The export import whose stored stress fills a day with no samples: the active watch brand's own
     *  export ("<prefix>-import"), else the Garmin export. A brand with no export importer reads nothing. */
    fun watchStressImportId(activeDeviceId: String): String =
        (com.noop.analytics.FusionSource.watchBrand(activeDeviceId)?.watchIdPrefix ?: "garmin") + "-import"
}
