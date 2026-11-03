import Foundation
import WhoopProtocol

/// Effort for a workout stored by the Garmin history sync (#50).
///
/// The sync stores a watch activity with no strain: the FIT file carries Garmin's own load figures, not
/// NOOP's Effort. The watch's per-second heart rate is stored with it under the watch's device id, so the
/// post-sync scoring pass scores the row from that trace with the same `ManualWorkoutRescore` recipe a
/// manual workout uses, against the same HRmax, measured resting HR and Effort method as the day it sits
/// in. Pure, so both platforms pin it with the same fixtures. Kotlin twin: `GarminWorkoutEffort`.
public enum GarminWorkoutEffort {

    /// True for a workout row stored by the Garmin history sync: its source is the watch's registry id,
    /// minted as "garmin-<uuid>". The Garmin export import ("garmin-import") is a different source and
    /// carries no watch trace, so it is excluded. Kotlin twin: `GarminWorkoutEffort.isWatchSource`.
    public static func isWatchSource(_ source: String) -> Bool {
        let s = source.lowercased()
        return s.hasPrefix("garmin-") && s != "garmin-import"
    }

    /// The Effort to store on a Garmin row, or nil to leave the row as it is: it already has one, or its
    /// trace is too thin to score (`StrainScorer` never returns a fabricated number).
    /// Kotlin twin: `GarminWorkoutEffort.strainToFill`.
    public static func strainToFill(currentStrain: Double?, windowSamples: [HRSample], profile: UserProfile,
                                    hrMax: Double, restingHR: Double?,
                                    effortMethod: StrainScorer.Method = .edwards) -> Double? {
        guard currentStrain == nil else { return nil }
        return ManualWorkoutRescore.scored(windowSamples: windowSamples, profile: profile, hrMax: hrMax,
                                           restingHR: restingHR, effortMethod: effortMethod)?.strain
    }
}
