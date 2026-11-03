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
     * True for a workout row stored by the Garmin history sync: its source is the watch's registry id,
     * minted as "garmin-<uuid>". The Garmin export import ("garmin-import") is a different source and
     * carries no watch trace, so it is excluded. Swift twin: `GarminWorkoutEffort.isWatchSource`.
     */
    fun isWatchSource(source: String): Boolean {
        val s = source.lowercase()
        return s.startsWith("garmin-") && s != "garmin-import"
    }

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
