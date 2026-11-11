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

    /// The watch-export import ids whose workouts carry the watch's own per-second heart rate under the id.
    /// Kotlin twin: `GarminWorkoutEffort.watchImportSources`.
    public static func watchImportSources() -> [String] { ["garmin-import", "huawei-import"] }

    /// True for an id whose workouts the Effort pass scores from the trace stored under that same id: a
    /// watch's history sync, or a watch export import (the Garmin Connect export's FIT activities; the
    /// Gadgetbridge Huawei import exists on Android only). Kotlin twin: `GarminWorkoutEffort.scoresOwnTrace`.
    public static func scoresOwnTrace(_ source: String) -> Bool {
        isWatchSource(source) || watchImportSources().contains(source.lowercased())
    }

    /// Row limit for the Effort pass's workout read: no cap. The read is ascending by start, so any cap
    /// would leave the newest workouts of a long history unscored forever. The 32-bit Int maximum, matching
    /// the Kotlin LIMIT. Kotlin twin: `GarminWorkoutEffort.ALL_ROWS`.
    public static let allRows = 2_147_483_647

    /// Identity of one Garmin workout row for the pass's "already tried, too thin to score" memory: the
    /// watch id plus the row's primary key (start, sport) and its end, so an edited span is tried again.
    /// Kotlin twin: `GarminWorkoutEffort.attemptKey`.
    public static func attemptKey(deviceId: String, startTs: Int, endTs: Int, sport: String) -> String {
        "\(deviceId)|\(startTs)|\(endTs)|\(sport)"
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
