import Foundation

// RecoveryForecast.swift — an evening estimate of TOMORROW-morning Charge.
//
// Pure, deterministic, DB-free. Given the recent Charge (recovery) history, the
// recent Effort (strain) history, today's Effort, and how much sleep is planned /
// banked tonight against the personal sleep need, this projects what tomorrow's
// Charge is LIKELY to wake at — with an honest ± error band.
//
// This is an ESTIMATE, not a measurement. WHOOP's morning recovery is computed from
// the NEXT night's HRV/RHR/respiration, none of which exist yet at the time this
// runs; so this can only lean on the levers that ARE known tonight. It is a simple,
// transparent weighting of three signed nudges around the recent Charge baseline —
// NOT a learned model — so it stays explainable and can be reasoned about line by line.
//
// Model (all adjustments are signed points ADDED to the baseline mean Charge):
//
//   center = mean(recent Charge over the last ~baselineWindow days)
//
//   1. Strain debt  — today's Effort vs the recent average Effort. A harder-than-
//      usual day suppresses tomorrow's Charge; an easier day lifts it a little.
//      adj₁ = −strainWeight × (todayEffort − meanEffort) / effortSpread     (clamped)
//
//   2. Sleep adequacy — planned/banked sleep tonight vs the personal sleep need.
//      Falling short of need suppresses Charge; meeting or beating it is neutral-to-
//      slightly-positive (sleeping far beyond need does not keep adding Charge).
//      adj₂ = sleepWeight × clamp(sleepHours/needHours − 1, −1, +0.25)
//
//   3. Mean reversion — if recent Charge has been trending, pull the projection a
//      little back toward the baseline rather than extrapolating the streak. A
//      sustained downswing is dampened, a sustained upswing is trimmed.
//      adj₃ = −reversionWeight × recentSlopePerDay
//
//   forecast = clamp(center + adj₁ + adj₂ + adj₃, 0, 100)
//
// Error band: the recent day-to-day SD of Charge, floored at minBandPoints and
// inflated when the baseline is thin (few nights) — a sparse history is less
// certain, and the ± says so honestly.
//
// Gating: returns nil unless there are at least minBaselineNights of recent Charge
// (so a cold-start user never sees a fabricated number). The UI shows the card only
// when this is non-nil.

// MARK: - Result

/// An evening projection of tomorrow-morning Charge (recovery, 0–100). APPROXIMATE.
public struct RecoveryForecast: Equatable, Sendable {
    /// The point estimate of tomorrow-morning Charge, 0–100 (rounded to a whole number).
    public let charge: Double
    /// Symmetric ± error band on `charge`, in Charge points (rounded to a whole number).
    public let band: Double
    /// Recent Charge baseline (mean) this projection is anchored to, 0–100.
    public let baseline: Double
    /// Planned/banked sleep hours tonight that the projection assumed.
    public let plannedSleepHours: Double
    /// Personal sleep need (hours) the adequacy term compared against.
    public let needHours: Double
    /// Nights of recent Charge history backing the baseline (drives confidence).
    public let nights: Int
    /// Per-score certainty tier (reuses the Charge/Effort/Rest confidence ladder).
    public let confidence: ScoreConfidence

    public init(charge: Double, band: Double, baseline: Double,
                plannedSleepHours: Double, needHours: Double,
                nights: Int, confidence: ScoreConfidence) {
        self.charge = charge
        self.band = band
        self.baseline = baseline
        self.plannedSleepHours = plannedSleepHours
        self.needHours = needHours
        self.nights = nights
        self.confidence = confidence
    }

    /// Low end of the band, clamped to [0, 100].
    public var low: Double { Swift.max(0, charge - band) }
    /// High end of the band, clamped to [0, 100].
    public var high: Double { Swift.min(100, charge + band) }
}

// MARK: - Engine

public enum RecoveryForecaster {

    // MARK: Tunables (documented, deterministic — NOT learned)

    /// Trailing Charge nights used for the baseline mean / SD / slope.
    public static let baselineWindow: Int = 14
    /// Minimum recent Charge nights before a forecast is offered (else nil — honest cold-start).
    public static let minBaselineNights: Int = 5
    /// Trailing Effort nights used for the strain-debt reference average.
    public static let effortWindow: Int = 14

    /// Charge points a one-spread excess of today's Effort over average removes.
    public static let strainWeight: Double = 9.0
    /// Effort spread (points) that defines "one unit" of strain excess. A day this far
    /// above your average Effort costs the full `strainWeight`. Deliberately a fixed,
    /// explainable spread (not a personal SD) so the nudge is stable and legible.
    public static let effortSpread: Double = 12.0
    /// Max |strain-debt| nudge (points), so one freak max-Effort day can't dominate.
    public static let strainAdjCap: Double = 12.0

    /// Charge points a full night short / over of sleep-need moves the estimate.
    public static let sleepWeight: Double = 14.0
    /// Sleep beyond need keeps helping only up to this fraction (diminishing returns).
    public static let sleepOverCap: Double = 0.25

    /// Charge points removed per point/day of recent up-slope (and added back per
    /// point/day of down-slope) — the mean-reversion damping.
    public static let reversionWeight: Double = 1.0
    /// Max |mean-reversion| nudge (points).
    public static let reversionAdjCap: Double = 8.0

    /// Floor on the ± band (points) — even a steady sleeper isn't perfectly predictable.
    public static let minBandPoints: Double = 8.0
    /// Extra ± points added while the baseline is below `trustedNights` (thin history).
    public static let thinBandPoints: Double = 6.0
    /// Recent Charge nights at/above which the band is no longer inflated for thinness.
    public static let trustedNights: Int = 10
    /// Nights informing the personal sleep need at/above which the need is "solid"
    /// (matches the Charge/Effort/Rest building-vs-solid threshold of 7).
    public static let solidNeedNights: Int = 7

    /// Default personal sleep need (hours) when the caller has none to refine it.
    public static let defaultNeedHours: Double = AnalyticsEngine.Rest.defaultNeedHours

    // MARK: - Forecast

    /// Project tomorrow-morning Charge from tonight's known levers. APPROXIMATE; nil
    /// until there are at least `minBaselineNights` of recent Charge to anchor to.
    ///
    /// - Parameters:
    ///   - recentCharge: recent daily Charge values, OLDEST→NEWEST (0–100). Only the
    ///     trailing `baselineWindow` are used for the baseline mean/SD/slope.
    ///   - recentEffort: recent daily Effort values, OLDEST→NEWEST (0–100); the
    ///     trailing `effortWindow` set the strain-debt reference average. May be
    ///     empty — the strain term then drops.
    ///   - todayEffort: today's Effort (0–100), or nil to drop the strain term.
    ///   - plannedSleepHours: sleep hours planned / already banked tonight. Negative
    ///     is treated as 0.
    ///   - needHours: personal sleep need (hours); nil → `defaultNeedHours`.
    ///   - needNights: recent nights that informed `needHours` (0 = still the default);
    ///     drives the Rest-style confidence tier.
    public static func forecast(recentCharge: [Double],
                                recentEffort: [Double] = [],
                                todayEffort: Double?,
                                plannedSleepHours: Double,
                                needHours: Double? = nil,
                                needNights: Int = 0) -> RecoveryForecast? {
        let chargeWindow = Array(recentCharge.suffix(baselineWindow))
        let nights = chargeWindow.count
        guard nights >= minBaselineNights else { return nil }

        let center = mean(chargeWindow)
        let sd = sampleSD(chargeWindow)
        let slope = leastSquaresSlope(chargeWindow)

        // 1. Strain debt: today vs the recent average Effort (both 0–100).
        var strainAdj = 0.0
        if let today = todayEffort, !recentEffort.isEmpty {
            let meanEffort = mean(Array(recentEffort.suffix(effortWindow)))
            let excess = (today - meanEffort) / effortSpread
            strainAdj = clamp(-strainWeight * excess, -strainAdjCap, strainAdjCap)
        }

        // 2. Sleep adequacy: planned sleep vs personal need.
        let need = Swift.max(needHours ?? defaultNeedHours, 0.1)
        let sleep = Swift.max(plannedSleepHours, 0.0)
        let sleepRatio = clamp(sleep / need - 1.0, -1.0, sleepOverCap)
        let sleepAdj = sleepWeight * sleepRatio

        // 3. Mean reversion: dampen a recent streak back toward the baseline.
        let reversionAdj = clamp(-reversionWeight * slope, -reversionAdjCap, reversionAdjCap)

        let raw = center + strainAdj + sleepAdj + reversionAdj
        let charge = (clamp(raw, 0.0, 100.0)).rounded()

        // ± band: recent SD, floored, inflated while the baseline is thin.
        var band = Swift.max(sd, minBandPoints)
        if nights < trustedNights { band += thinBandPoints }
        band = band.rounded()

        // Confidence rides the SAME calibrating/building/solid ladder as the daily
        // scores. The forecast always clears `minBaselineNights` to reach here (so it
        // is never .calibrating), then it is .building on a thin baseline OR an
        // unrefined sleep-need default, and .solid only when both the baseline is full
        // (≥ trustedNights) and the personal need is informed.
        let confidence: ScoreConfidence =
            (nights >= trustedNights && needNights >= solidNeedNights) ? .solid : .building

        return RecoveryForecast(charge: charge, band: band, baseline: center,
                                plannedSleepHours: sleep, needHours: need,
                                nights: nights, confidence: confidence)
    }

    // MARK: - Stats (self-contained so the Kotlin mirror is line-for-line)

    static func mean(_ values: [Double]) -> Double {
        guard !values.isEmpty else { return 0 }
        return values.reduce(0, +) / Double(values.count)
    }

    /// Sample standard deviation (ddof = 1); 0 for fewer than 2 values.
    static func sampleSD(_ values: [Double]) -> Double {
        let n = values.count
        guard n >= 2 else { return 0 }
        let m = mean(values)
        var ss = 0.0
        for v in values { let d = v - m; ss += d * d }
        return (ss / Double(n - 1)).squareRoot()
    }

    /// OLS slope of value vs the 0-based index (per-day trend); 0 for < 2 points.
    static func leastSquaresSlope(_ values: [Double]) -> Double {
        let n = values.count
        guard n >= 2 else { return 0 }
        let meanX = Double(n - 1) / 2.0
        let meanY = mean(values)
        var num = 0.0, den = 0.0
        for (i, v) in values.enumerated() {
            let dx = Double(i) - meanX
            num += dx * (v - meanY)
            den += dx * dx
        }
        return den == 0 ? 0 : num / den
    }

    static func clamp(_ x: Double, _ lo: Double, _ hi: Double) -> Double {
        // Inclusive-bound equality is already in range: return x itself so Swift
        // and Kotlin preserve the same IEEE signed-zero bit (#56).
        if x < lo { return lo }
        if x > hi { return hi }
        return x
    }
}

// MARK: - Tonight's plan: bedtime → planned sleep → forecast
//
// The forecast above takes planned sleep hours as a given. This layer derives them from a BEDTIME, so the
// evening card can answer "if I'm in bed by 22:45, where does tomorrow's Charge land?":
//
//   plannedSleep  = (wake − bedtime) × efficiency                  (hours asleep, not hours in bed)
//   suggested     = wake − needHours ÷ efficiency, floored to the 15-min grid
//   options       = the anchor bedtime, one hour earlier, one hour later
//
// `needHours` is the SAME SleepNeed context the night is later scored against (base + strain + debt), and
// `needNights` counts the recorded nights behind its base, so the confidence tier can reach "solid". The
// wake time, sleep efficiency and every timestamp are resolved by the caller (they need the calendar and
// the alarm settings); everything here is plain arithmetic over them.

extension RecoveryForecaster {

    /// Trailing nights the typical sleep efficiency (and the caller's typical wake time) are taken over.
    public static let planHistoryNights: Int = 14
    /// Sleep efficiency (asleep ÷ in bed) assumed when no recent night carries one.
    public static let defaultEfficiency: Double = 0.88
    /// Lowest efficiency a plan accepts, so one fragmented night can't demand a 16-hour bedtime.
    public static let minEfficiency: Double = 0.5
    /// Bedtime grid in seconds: the slider step and the suggestion's rounding.
    public static let bedtimeStepSec: Double = 900
    /// Offset of the two alternative bedtimes from the anchor, in seconds.
    public static let alternativeOffsetSec: Double = 3600
    /// Shortest sleep span (seconds) that counts as the night's sleep starting. A shorter nap after the
    /// window opens does not freeze tonight's forecast.
    public static let mainSleepMinSec: Double = 3 * 3600
    /// Wake minute-of-day assumed when there is neither an alarm nor any recorded wake (07:00).
    public static let defaultWakeMinute: Double = 7 * 60
    /// Local hour on the evening day from which tonight's forecast is live (the afternoon).
    public static let openHour: Int = 12
    /// Local hour on the target day at which it closes at the latest (the logical-day rollover).
    public static let closeHour: Int = 4
    /// The bedtime slider's range: from this local hour on the evening day…
    public static let sliderStartHour: Int = 21
    /// …to this local hour on the target day.
    public static let sliderEndHour: Int = 2

    /// What the forecast knows tonight apart from the bedtime.
    public struct Plan: Equatable, Sendable {
        /// Recent daily Charge, OLDEST→NEWEST, up to and including this morning.
        public let recentCharge: [Double]
        /// Recent daily Effort, OLDEST→NEWEST.
        public let recentEffort: [Double]
        /// Today's Effort, or nil to drop the strain term.
        public let todayEffort: Double?
        /// Tonight's sleep need in hours (SleepNeed context total).
        public let needHours: Double
        /// Recorded nights behind the need's base.
        public let needNights: Int
        /// Tomorrow's wake time, unix seconds.
        public let wakeTs: Double
        /// Typical sleep efficiency, a fraction in [minEfficiency, 1].
        public let efficiency: Double

        public init(recentCharge: [Double], recentEffort: [Double], todayEffort: Double?, needHours: Double,
                    needNights: Int, wakeTs: Double, efficiency: Double) {
            self.recentCharge = recentCharge
            self.recentEffort = recentEffort
            self.todayEffort = todayEffort
            self.needHours = needHours
            self.needNights = needNights
            self.wakeTs = wakeTs
            self.efficiency = efficiency
        }
    }

    /// One bedtime and the forecast it gives.
    public struct BedtimeOption: Equatable, Sendable {
        public let bedtimeTs: Double
        public let forecast: RecoveryForecast

        public init(bedtimeTs: Double, forecast: RecoveryForecast) {
            self.bedtimeTs = bedtimeTs
            self.forecast = forecast
        }
    }

    /// Sleep need for `day` from the nights before it: the SleepNeed context total (base + strain + debt)
    /// and the number of recorded nights in the base window. Kotlin twin: `RecoveryForecaster.need`.
    public static func need(nights: [SleepNeed.Night], day: String, age: Int?) -> (hours: Double, nights: Int) {
        let hours = SleepNeed.contexts(nights: nights, days: [day], age: age)[day]?.totalHours
            ?? AnalyticsEngine.Rest.personalizedNeedHours(nightlyHours: [], age: age)
        guard let d = DayOrdinal.ordinal(day) else { return (hours, 0) }
        var seen = Set<Int>()
        for n in nights {
            guard let o = DayOrdinal.ordinal(n.day), o >= d - SleepNeed.baseWindowDays, o < d,
                  let m = n.mainMin, m > 0 else { continue }
            seen.insert(o)
        }
        return (hours, seen.count)
    }

    /// A stored efficiency as a fraction: values above 1 are percentages. nil for a missing, non-finite or
    /// non-positive value. Kotlin twin: `RecoveryForecaster.efficiencyFraction`.
    public static func efficiencyFraction(_ value: Double?) -> Double? {
        guard let v = value, v.isFinite, v > 0 else { return nil }
        let f = v > 1 ? v / 100 : v
        return f > 1 ? nil : f
    }

    /// Typical efficiency: the median of the trailing `planHistoryNights` usable values (OLDEST→NEWEST,
    /// any scale `efficiencyFraction` accepts), floored at `minEfficiency`; `defaultEfficiency` when none.
    /// Kotlin twin: `RecoveryForecaster.typicalEfficiency`.
    public static func typicalEfficiency(_ values: [Double?]) -> Double {
        let usable = values.compactMap { efficiencyFraction($0) }
        guard let m = median(Array(usable.suffix(planHistoryNights))) else { return defaultEfficiency }
        return Swift.max(m, minEfficiency)
    }

    /// Typical wake minute-of-day: the median of the trailing `planHistoryNights` values (OLDEST→NEWEST),
    /// or nil when there are none. Kotlin twin: `RecoveryForecaster.typicalWakeMinute`.
    public static func typicalWakeMinute(_ minutes: [Double]) -> Double? {
        median(Array(minutes.filter { $0.isFinite }.suffix(planHistoryNights)))
    }

    /// Median (mean of the middle two for an even count); nil when empty. Kotlin twin: `RecoveryForecaster.median`.
    public static func median(_ values: [Double]) -> Double? {
        guard !values.isEmpty else { return nil }
        let s = values.sorted()
        let mid = s.count / 2
        return s.count % 2 == 0 ? (s[mid - 1] + s[mid]) / 2 : s[mid]
    }

    /// `efficiency` clamped to [minEfficiency, 1]; `defaultEfficiency` when it is not finite.
    /// Kotlin twin: `RecoveryForecaster.planEfficiency`.
    static func planEfficiency(_ efficiency: Double) -> Double {
        efficiency.isFinite ? clamp(efficiency, minEfficiency, 1.0) : defaultEfficiency
    }

    /// Hours asleep for a bedtime: time in bed until the wake, times the efficiency. 0 when the bedtime is
    /// at or after the wake. Kotlin twin: `RecoveryForecaster.plannedSleep`.
    public static func plannedSleep(bedtimeTs: Double, wakeTs: Double, efficiency: Double) -> Double {
        Swift.max(wakeTs - bedtimeTs, 0) / 3600 * planEfficiency(efficiency)
    }

    /// The latest bedtime on the 15-minute grid that still meets `needHours` asleep by the wake.
    /// Kotlin twin: `RecoveryForecaster.suggestedBedtime`.
    public static func suggestedBedtime(wakeTs: Double, needHours: Double, efficiency: Double) -> Double {
        let inBed = Swift.max(needHours, 0) * 3600 / planEfficiency(efficiency)
        return ((wakeTs - inBed) / bedtimeStepSec).rounded(.down) * bedtimeStepSec
    }

    /// `ts` rounded to the nearest grid step, then clamped to [earliest, latest] (the slider's range).
    /// Kotlin twin: `RecoveryForecaster.snapBedtime`.
    public static func snapBedtime(_ ts: Double, earliest: Double, latest: Double) -> Double {
        let snapped = (ts / bedtimeStepSec + 0.5).rounded(.down) * bedtimeStepSec
        return clamp(snapped, earliest, latest)
    }

    /// The forecast for one bedtime under `plan`; nil while the Charge baseline is too thin.
    /// Kotlin twin: `RecoveryForecaster.forecastAt`.
    public static func forecastAt(plan: Plan, bedtimeTs: Double) -> RecoveryForecast? {
        forecast(recentCharge: plan.recentCharge,
                 recentEffort: plan.recentEffort,
                 todayEffort: plan.todayEffort,
                 plannedSleepHours: plannedSleep(bedtimeTs: bedtimeTs, wakeTs: plan.wakeTs,
                                                 efficiency: plan.efficiency),
                 needHours: plan.needHours,
                 needNights: plan.needNights)
    }

    /// The anchor bedtime first, then one hour earlier and one hour later; empty while the Charge baseline
    /// is too thin. Kotlin twin: `RecoveryForecaster.options`.
    public static func options(plan: Plan, anchorTs: Double) -> [BedtimeOption] {
        var out: [BedtimeOption] = []
        for ts in [anchorTs, anchorTs - alternativeOffsetSec, anchorTs + alternativeOffsetSec] {
            guard let f = forecastAt(plan: plan, bedtimeTs: ts) else { return [] }
            out.append(BedtimeOption(bedtimeTs: ts, forecast: f))
        }
        return out
    }

    /// When the night's sleep started: the earliest start among spans that begin at or after `openTs` and
    /// last at least `mainSleepMinSec`; nil when none has. Kotlin twin: `RecoveryForecaster.sleepStart`.
    public static func sleepStart(spans: [(startTs: Double, endTs: Double)], openTs: Double) -> Double? {
        var first: Double?
        for s in spans where s.startTs >= openTs && s.endTs - s.startTs >= mainSleepMinSec {
            if first == nil || s.startTs < first! { first = s.startTs }
        }
        return first
    }

    /// Whether tonight's forecast is live: inside [openTs, closeTs) and before the night's sleep started.
    /// The one gate for both showing the Today card and overwriting the stored forecast; once it closes the
    /// stored forecast is frozen. Kotlin twin: `RecoveryForecaster.isOpen`.
    public static func isOpen(nowTs: Double, openTs: Double, closeTs: Double, sleepStartTs: Double?) -> Bool {
        guard nowTs >= openTs, nowTs < closeTs else { return false }
        if let s = sleepStartTs, nowTs >= s { return false }
        return true
    }
}

// MARK: - Tonight: one resolver for every surface that shows tomorrow's Charge

extension RecoveryForecaster {

    /// One stored day as the evening plan reads it.
    public struct DayRow: Equatable, Sendable {
        public let day: String
        public let recovery: Double?
        public let strain: Double?
        public let sleepMin: Double?
        public let efficiency: Double?

        public init(day: String, recovery: Double?, strain: Double?, sleepMin: Double?, efficiency: Double?) {
            self.day = day
            self.recovery = recovery
            self.strain = strain
            self.sleepMin = sleepMin
            self.efficiency = efficiency
        }
    }

    /// Tonight's clock, resolved by the caller from the local calendar and the alarm settings.
    public struct Window: Equatable, Sendable {
        /// The day whose evening this is (its Effort is "today's").
        public let eveningDay: String
        /// The day whose morning Charge is forecast.
        public let targetDay: String
        /// From when the forecast is live (the evening day's afternoon).
        public let openTs: Double
        /// When it closes at the latest (the target day's early morning).
        public let closeTs: Double
        /// The slider's earliest and latest bedtime.
        public let earliestTs: Double
        public let latestTs: Double
        /// Tomorrow's wake time.
        public let wakeTs: Double

        public init(eveningDay: String, targetDay: String, openTs: Double, closeTs: Double,
                    earliestTs: Double, latestTs: Double, wakeTs: Double) {
            self.eveningDay = eveningDay
            self.targetDay = targetDay
            self.openTs = openTs
            self.closeTs = closeTs
            self.earliestTs = earliestTs
            self.latestTs = latestTs
            self.wakeTs = wakeTs
        }
    }

    /// Tonight's resolved forecast.
    public struct Tonight: Equatable, Sendable {
        public let window: Window
        public let plan: Plan
        /// The bedtime that meets the need by the wake (not snapped to the slider range).
        public let suggestedTs: Double
        /// The bedtime the headline uses: a selection inside [sliderStartTs, latestTs], else the
        /// suggestion, either one snapped to the grid and clamped into that range.
        public let anchorTs: Double
        /// Where the slider thumb sits: always `anchorTs`, so the thumb and the headline cannot disagree.
        public let sliderTs: Double
        /// The slider's lowest selectable bedtime: the window's earliest bedtime, or now rounded up to
        /// the 15-minute grid once that is later (never past latestTs).
        public let sliderStartTs: Double
        /// The anchor first, then one hour earlier and one hour later.
        public let options: [BedtimeOption]
        /// True while the forecast may be shown on Today and overwritten in storage.
        public let isOpen: Bool

        /// The headline forecast (the anchor's).
        public var headline: BedtimeOption { options[0] }
    }

    /// Resolve tonight from the stored days (any order; days on or after the target are ignored), the
    /// caller's clock, the recorded sleep spans and an optional slider selection. The anchor is the
    /// selection when it lies in [sliderStartTs, latestTs], else the suggestion, clamped into that range,
    /// so the headline never names a bedtime outside the slider or already in the past. nil while the
    /// Charge baseline is too thin. Kotlin twin: `RecoveryForecaster.tonight`.
    public static func tonight(rows: [DayRow], window: Window, age: Int?, nowTs: Double,
                               spans: [(startTs: Double, endTs: Double)], selectedTs: Double?) -> Tonight? {
        let past = rows.filter { $0.day < window.targetDay }.sorted { $0.day < $1.day }
        let nights = past.map { SleepNeed.Night(day: $0.day, mainMin: $0.sleepMin, strain: $0.strain) }
        let need = need(nights: nights, day: window.targetDay, age: age)
        let plan = Plan(recentCharge: past.compactMap(\.recovery),
                        recentEffort: past.compactMap(\.strain),
                        todayEffort: past.last(where: { $0.day == window.eveningDay })?.strain,
                        needHours: need.hours,
                        needNights: need.nights,
                        wakeTs: window.wakeTs,
                        efficiency: typicalEfficiency(past.map(\.efficiency)))
        let suggested = suggestedBedtime(wakeTs: plan.wakeTs, needHours: plan.needHours, efficiency: plan.efficiency)
        let floorTs = Swift.min(Swift.max(window.earliestTs,
                                          (nowTs / bedtimeStepSec).rounded(.up) * bedtimeStepSec),
                                window.latestTs)
        let anchor: Double
        if let s = selectedTs, s.isFinite, s >= floorTs, s <= window.latestTs {
            anchor = snapBedtime(s, earliest: floorTs, latest: window.latestTs)
        } else {
            anchor = snapBedtime(suggested, earliest: floorTs, latest: window.latestTs)
        }
        let opts = options(plan: plan, anchorTs: anchor)
        guard !opts.isEmpty else { return nil }
        let open = isOpen(nowTs: nowTs, openTs: window.openTs, closeTs: window.closeTs,
                          sleepStartTs: sleepStart(spans: spans, openTs: window.openTs))
        return Tonight(window: window, plan: plan, suggestedTs: suggested, anchorTs: anchor,
                       sliderTs: anchor, sliderStartTs: floorTs, options: opts, isOpen: open)
    }
}
