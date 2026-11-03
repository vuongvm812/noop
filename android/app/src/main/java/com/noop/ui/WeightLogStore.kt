package com.noop.ui

import android.content.Context
import com.noop.analytics.ProfileWeightSync
import com.noop.analytics.WeightLog
import com.noop.data.MetricSeriesRow
import com.noop.data.WhoopRepository
import com.noop.ingest.HealthConnectImporter
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Weight diary persistence (#57).
 *
 * One hand-logged weight per local day, stored in the generic `metricSeries` table under
 * [WeightLog.SOURCE_ID] / [WeightLog.KEY] (kilograms). No schema change, and the rows ride inside the
 * `.noopbak` SQLite like every other series, so a restore on either platform brings the diary back. Every
 * write also points the profile weight (which calorie / BMR math reads) at the newest logged weight.
 * Swift twin: `Repository.setLoggedWeight` / `deleteLoggedWeight` (Strand/Data/WeightLogStore.swift).
 */
object WeightLogStore {

    /** Bumped by every diary write, so the Today Weight tile re-reads at once (Swift `weightLogSeq`). */
    val mutationSeq = MutableStateFlow(0)

    /** Every logged weight, oldest day first. */
    suspend fun entries(repo: WhoopRepository): List<WeightLog.Entry> =
        repo.metricSeries(WeightLog.SOURCE_ID, WeightLog.KEY, "0000-01-01", "9999-12-31")
            .map { WeightLog.Entry(it.day, it.value) }

    /** Log (or replace) [day]'s weight. An invalid weight is ignored. Returns true when it was stored. */
    suspend fun set(context: Context, repo: WhoopRepository, day: String, kg: Double): Boolean {
        if (!WeightLog.isValid(kg)) return false
        repo.upsertMetricSeries(listOf(MetricSeriesRow(WeightLog.SOURCE_ID, day, WeightLog.KEY, kg)))
        syncProfile(context, repo)
        mutationSeq.value += 1
        return true
    }

    /** Remove [day]'s logged weight. The profile then follows the newest remaining entry, if any. */
    suspend fun delete(context: Context, repo: WhoopRepository, day: String) {
        repo.deleteMetricSeriesPoint(WeightLog.SOURCE_ID, day, WeightLog.KEY)
        syncProfile(context, repo)
        mutationSeq.value += 1
    }

    private suspend fun syncProfile(context: Context, repo: WhoopRepository) {
        val profile = ProfileStore.from(context)
        val healthConnect = if (profile.useHealthConnectWeight) {
            HealthConnectWeightSync.weightReadings(
                repo.appleDaily(HealthConnectImporter.HC_DEVICE, "0000-01-01", "9999-12-31"),
            )
        } else emptyList()
        applyToProfile(profile, entries(repo), healthConnect)
    }

    /**
     * Point the profile at the newest logged weight. Pure apart from the profile write, so it runs as a JVM
     * test. An empty diary leaves the profile alone, and so does a NEWER Health Connect weigh-in while
     * "Use weight from Health Connect" is on: the profile follows whichever is newest, the diary on a tie.
     * Returns the weight applied, or null.
     */
    internal fun applyToProfile(
        profile: ProfileStore,
        logged: List<WeightLog.Entry>,
        healthConnect: List<com.noop.analytics.WeightReading>,
    ): Double? {
        val kg = WeightLog.profileWeight(logged) ?: return null
        val newestDay = WeightLog.newest(logged)?.day ?: return null
        val hc = ProfileWeightSync.newest(healthConnect)
        if (hc != null && hc.day > newestDay) return null
        profile.weightKg = kg
        return kg
    }
}
