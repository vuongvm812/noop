package com.noop.analytics

import com.noop.data.HrSample

/**
 * Effort for a workout stored by the Garmin history sync (#50).
 *
 * The sync stores a watch activity with no strain: the FIT file carries Garmin's own load figures, not
 * NOOP's Effort. The watch's per-second heart rate is stored with it under the watch's device id, so the
 * post-sync scoring pass scores the row from that trace with the same [ManualWorkoutRescore] recipe a
 * manual workout uses, against the same HRmax, measured resting HR and Effort method as the day it sits
 * in. Pure, so both platforms pin it with the same fixtures. Swift twin: `GarminWorkoutEffort`.
 */
object GarminWorkoutEffort {

    /**
     * True for a workout row stored by a watch's history sync: its source is the watch's registry id,
     * minted as "garmin-<uuid>" or "huawei-<uuid>". The brand's export import ("garmin-import") is a
     * different source and carries no watch trace, so it is excluded. Swift twin:
     * `GarminWorkoutEffort.isWatchSource` (Garmin only).
     */
    fun isWatchSource(source: String): Boolean = watchBrand(source) != null

    /** The watch brand ([FusionSource.GARMIN_WATCH], [FusionSource.HUAWEI_WATCH]) whose history sync stored
     *  a workout row with this [source], or null. Case-insensitive, like [isWatchSource]. */
    fun watchBrand(source: String): FusionSource? =
        com.noop.data.DeviceBrandCatalog.watchSpecForSource(source.lowercase())
            ?.let { FusionSource.forWatchKind(it.sourceKind) }

    /**
     * Row limit for the Effort pass's workout read: no cap. The read is ascending by start, so any cap
     * would leave the newest workouts of a long history unscored forever. Swift twin:
     * `GarminWorkoutEffort.allRows`, which is the same 32-bit maximum.
     */
    const val ALL_ROWS = 2_147_483_647

    /**
     * Identity of one Garmin workout row for the pass's "already tried, too thin to score" memory: the
     * watch id plus the row's primary key (start, sport) and its end, so an edited span is tried again.
     * Swift twin: `GarminWorkoutEffort.attemptKey`.
     */
    fun attemptKey(deviceId: String, startTs: Long, endTs: Long, sport: String): String =
        "$deviceId|$startTs|$endTs|$sport"

    /**
     * The Effort to store on a Garmin row, or null to leave the row as it is: it already has one, or its
     * trace is too thin to score ([StrainScorer] never returns a fabricated number).
     * Swift twin: `GarminWorkoutEffort.strainToFill`.
     */
    fun strainToFill(
        currentStrain: Double?,
        windowSamples: List<HrSample>,
        profile: UserProfile,
        hrMax: Double,
        restingHR: Double?,
        effortMethod: StrainScorer.Method = StrainScorer.Method.EDWARDS,
    ): Double? {
        if (currentStrain != null) return null
        return ManualWorkoutRescore.scored(windowSamples, profile, hrMax, restingHR, effortMethod)?.strain
    }
}
