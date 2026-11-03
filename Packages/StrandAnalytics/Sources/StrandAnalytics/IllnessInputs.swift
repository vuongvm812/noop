import Foundation

// IllnessInputs.swift — the ONE place the illness heads-up turns nightly history into the engine's
// z-scored inputs and journal context. Pure, deterministic, DB-free; the Kotlin twin
// (`com.noop.analytics.IllnessInputs`) is pinned against this file by oracle.
//
// Before this existed each platform built its own inputs: Apple folded an EWMA personal baseline with a
// gap and two-night persistence, Android z-scored only the newest night against a plain 30-day mean/SD
// with a 1e-6 SD floor. A stable 0.05 °C skin-temp series plus a 0.3 °C night read z ≈ 6 on Android
// (fires) and z ≈ 1 on Apple (quiet). The shape kept here is the Apple one, which is also what the
// published detectors use (Mishra 2020, Alavi 2022, Quer 2021: a personal baseline that excludes the
// most recent days, persistence over consecutive nights, a floored personal spread):
//
//   • recent window  = the last `recentNights` (2) nights;
//   • baseline       = up to `baselineNights` (28) nights ending `recentNights + gapNights` (3) nights
//                      before the newest, folded with `Baselines.foldHistory` (floored spread);
//   • z              = min(z of the recent mean, z of the newest night) in the illness-ward direction,
//                      so one night alone never counts and a two-night mean can't hide a recovered night;
//   • skin temperature: the column is bimodal (absolute wrist °C ≥ 20 for imported nights, a signed
//                      deviation for live ones), so only nights of the newest night's kind enter the
//                      baseline and the recent mean, folded with the matching config
//                      (`Baselines.metricCfg["skin_temp"]` or `VitalBands.skinTempDeviationCfg`).
//
// Journal confounders are matched on the catalog's canonical question keys (normalised the way the
// catalog normalises them), never on substrings — "pill", "still" and "chill" are not "ill", and
// "drink water" is not alcohol.
public enum IllnessInputs {

    // MARK: - Window constants (mirror the Kotlin twin)

    public static let recentNights: Int = 2
    public static let gapNights: Int = 1
    public static let baselineNights: Int = 28
    /// A day's strain at/above this many personal spreads over its baseline counts as a hard workout.
    public static let hardWorkoutZ: Double = 2.0

    // MARK: - Inputs

    /// One night's vitals, in the column units the caches store.
    public struct Night: Equatable, Sendable {
        public let day: String
        public let restingHR: Double?
        public let hrv: Double?
        public let respiration: Double?
        public let skinTemp: Double?
        public let strain: Double?
        public init(day: String, restingHR: Double? = nil, hrv: Double? = nil,
                    respiration: Double? = nil, skinTemp: Double? = nil, strain: Double? = nil) {
            self.day = day; self.restingHR = restingHR; self.hrv = hrv
            self.respiration = respiration; self.skinTemp = skinTemp; self.strain = strain
        }
    }

    /// One signal's reading plus whether its baseline is trusted.
    public struct Signal: Equatable, Sendable {
        public let reading: IllnessSignalEngine.SignalReading
        public let trusted: Bool
    }

    /// Everything the engine and the parallel distance read need.
    public struct Built: Equatable, Sendable {
        public let restingHR: Signal?
        public let hrv: Signal?
        public let respiration: Signal?
        public let skinTemp: Signal?
        /// An RHR or HRV baseline is trusted — the engine's gate for raising.
        public let baselineTrusted: Bool
        /// The strain day before a recent night sits ≥ `hardWorkoutZ` over its personal baseline.
        public let hardWorkout: Bool

        public var inputs: IllnessSignalEngine.Inputs {
            IllnessSignalEngine.Inputs(restingHR: restingHR?.reading, skinTemp: skinTemp?.reading,
                                       hrv: hrv?.reading, respiration: respiration?.reading)
        }

        /// The illness-ward z vector for `IllnessDistance` (nil where absent or not present).
        public var distanceFeatures: IllnessDistance.FeatureVector {
            let z = [restingHR, hrv, skinTemp, respiration].map { s -> Double? in
                guard let r = s?.reading, r.present else { return nil }
                return r.zIllnessward
            }
            return IllnessDistance.FeatureVector(restingHR: z[0], rmssd: z[1], skinTemp: z[2], respiration: z[3])
        }
    }

    // MARK: - Build

    /// Build the four illness-ward signals from `nights` (oldest → newest; the last one is tonight).
    /// Kotlin twin: `IllnessInputs.build`.
    public static func build(_ nights: [Night]) -> Built {
        guard let latest = nights.last else {
            return Built(restingHR: nil, hrv: nil, respiration: nil, skinTemp: nil,
                         baselineTrusted: false, hardWorkout: false)
        }
        let recent = Array(nights.suffix(recentNights))
        let base = baseline(nights)

        let rhr = signal(recent: recent.map(\.restingHR), latest: latest.restingHR,
                         base: base.map(\.restingHR), cfg: Baselines.restingHRCfg, illnessUp: true)
        let hrv = signal(recent: recent.map(\.hrv), latest: latest.hrv,
                         base: base.map(\.hrv), cfg: Baselines.hrvCfg, illnessUp: false)
        let resp = signal(recent: recent.map(\.respiration), latest: latest.respiration,
                          base: base.map(\.respiration), cfg: Baselines.respCfg, illnessUp: true)

        var skin: Signal? = nil
        if let latestSkin = latest.skinTemp {
            let absolute = VitalBands.isAbsoluteSkinTemp(latestSkin)
            let cfg = absolute ? Baselines.metricCfg["skin_temp"]! : VitalBands.skinTempDeviationCfg
            skin = signal(recent: VitalBands.skinTempHistory(matching: latestSkin, in: recent.map(\.skinTemp)),
                          latest: latestSkin,
                          base: VitalBands.skinTempHistory(matching: latestSkin, in: base.map(\.skinTemp)),
                          cfg: cfg, illnessUp: true)
        }

        return Built(restingHR: rhr, hrv: hrv, respiration: resp, skinTemp: skin,
                     baselineTrusted: (rhr?.trusted ?? false) || (hrv?.trusted ?? false),
                     hardWorkout: hardWorkout(nights, base: base))
    }

    /// The baseline nights: up to `baselineNights` ending `recentNights + gapNights` before the newest.
    /// Kotlin twin: `IllnessInputs.baseline`.
    static func baseline(_ nights: [Night]) -> [Night] {
        let end = nights.count - recentNights - gapNights
        guard end > 0 else { return [] }
        return Array(nights[Swift.max(0, end - baselineNights)..<end])
    }

    /// One signal's illness-ward reading. nil when the newest night or the recent window has no value;
    /// a not-present reading when the baseline has not yet seeded.
    /// Kotlin twin: `IllnessInputs.signal`.
    static func signal(recent: [Double?], latest: Double?, base: [Double?],
                       cfg: MetricCfg, illnessUp: Bool) -> Signal? {
        let vals = recent.compactMap { $0 }
        guard let latest, !vals.isEmpty else { return nil }
        var sum = 0.0
        for v in vals { sum += v }
        let recentMean = sum / Double(vals.count)
        let state = Baselines.foldHistory(base, cfg: cfg)
        guard state.usable else {
            return Signal(reading: IllnessSignalEngine.SignalReading(zIllnessward: 0, present: false),
                          trusted: false)
        }
        let dev = Baselines.deviation(recentMean, state: state).z
        let latestDev = Baselines.deviation(latest, state: state).z
        let z = illnessUp ? Swift.min(dev, latestDev) : Swift.min(-dev, -latestDev)
        return Signal(reading: IllnessSignalEngine.SignalReading(zIllnessward: z), trusted: state.trusted)
    }

    /// A hard day before either recent night: the strain of the day preceding each recent night (the
    /// rows just before them) against the personal strain baseline. Personal, not a fixed workout count,
    /// so a habitual two-session day is not a confounder but an unusually heavy one is.
    /// Kotlin twin: `IllnessInputs.hardWorkout`.
    static func hardWorkout(_ nights: [Night], base: [Night]) -> Bool {
        let state = Baselines.foldHistory(base.map(\.strain), cfg: Baselines.strainCfg)
        guard state.usable else { return false }
        let end = nights.count - 1
        let start = Swift.max(0, nights.count - recentNights - 1)
        guard start < end else { return false }
        for night in nights[start..<end] {
            if let s = night.strain, Baselines.deviation(s, state: state).z >= hardWorkoutZ { return true }
        }
        return false
    }

    // MARK: - Journal context

    /// Canonical journal questions (normalised) that name each confounder. These are the catalog's
    /// starter questions plus the WHOOP-export wording seen in imports. A renamed question keeps its
    /// canonical key, so a rename never breaks the match.
    public static var alcoholQuestions: Set<String> { ["did you drink any alcohol?", "have any alcoholic drinks?"] }
    public static var stressQuestions: Set<String> { ["did you feel stressed?"] }
    public static var saunaQuestions: Set<String> { ["did you use a sauna?"] }
    public static var unwellQuestions: Set<String> { ["did you feel sick or ill?"] }

    /// The engine context from the canonical questions answered YES on the recent nights' days, the
    /// built signals (for trust + the strain-based hard workout) and a detected body-clock jump.
    /// Kotlin twin: `IllnessInputs.context`.
    public static func context(yesQuestions: [String], built: Built,
                               travelPhaseJump: Bool = false) -> IllnessSignalEngine.Context {
        let keys = Set(yesQuestions.map(normalizeQuestion))
        return IllnessSignalEngine.Context(
            alcohol: !keys.isDisjoint(with: alcoholQuestions),
            stress: !keys.isDisjoint(with: stressQuestions),
            sauna: !keys.isDisjoint(with: saunaQuestions),
            hardOrLateWorkout: built.hardWorkout,
            travelPhaseJump: travelPhaseJump,
            alreadyUnwell: !keys.isDisjoint(with: unwellQuestions),
            baselineTrusted: built.baselineTrusted)
    }

    /// The days whose journal answers count: the recent nights' day keys.
    /// Kotlin twin: `IllnessInputs.recentDays`.
    public static func recentDays(_ nights: [Night]) -> Set<String> {
        Set(nights.suffix(recentNights).map(\.day))
    }

    /// Journal match key: every whitespace run collapsed to one space, trimmed, lowercased. The same
    /// normalisation as the app's journal catalog (`JournalCatalogStore.norm` / `normJournalKey`).
    /// Kotlin twin: `IllnessInputs.normalizeQuestion`.
    public static func normalizeQuestion(_ s: String) -> String {
        s.components(separatedBy: .whitespacesAndNewlines)
            .filter { !$0.isEmpty }
            .joined(separator: " ")
            .lowercased()
    }
}
