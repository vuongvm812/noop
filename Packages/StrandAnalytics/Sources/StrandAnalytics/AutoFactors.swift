import Foundation

// AutoFactors.swift — automatic "From your data" behaviours for the What Moves You ranker.
//
// Pure, deterministic, DB-free, and self-contained (integer civil-day arithmetic, no Foundation
// calendars), so it compiles standalone for the Swift↔Kotlin oracle. Kotlin twin: `AutoFactors.kt`.
//
// The journal tells EffectRanker which days a behaviour happened (Yes) and which days it did not
// (No). This file derives the same two day sets from sensor data, so a late workout or a late
// bedtime is ranked beside the journal habits in ONE Benjamini–Hochberg family. Each factor
// compares a clear "yes" group against a clear control group; a day that is in neither (between
// the thresholds, or missing the data the factor needs) is left out, which is the journal's
// "no answer" rule.
//
// Thresholds are personal: percentiles and medians of the user's own last `windowDays()` days
// (falling back to the whole history when that window holds fewer than `minSamples()` values).
//
// Day keys: a factor day is the day the behaviour happened. A night belongs to the evening it
// started on: its key is the local day of (onset − 12 h), so a 00:30 bedtime counts for the
// evening before. A late workout is keyed on the night it precedes, so a 00:05 workout before a
// 00:40 bedtime counts for that same evening. EffectRanker's lag then pairs that day with the
// following mornings' outcomes.
// Every timestamp carries its own UTC offset, so a daylight-saving change cannot move a bedtime.

/// Kotlin twin: `AutoFactors`.
public enum AutoFactors {

    // MARK: - Inputs

    /// One workout. `offsetSec` is the local UTC offset at `startTs` (seconds east of UTC).
    public struct Workout: Equatable, Sendable {
        public let startTs: Int
        public let endTs: Int
        public let offsetSec: Int
        public init(startTs: Int, endTs: Int, offsetSec: Int) {
            self.startTs = startTs
            self.endTs = endTs
            self.offsetSec = offsetSec
        }
    }

    /// One sleep block. `offsetSec` is the local UTC offset at `startTs` (the onset).
    public struct Sleep: Equatable, Sendable {
        public let startTs: Int
        public let endTs: Int
        public let offsetSec: Int
        public init(startTs: Int, endTs: Int, offsetSec: Int) {
            self.startTs = startTs
            self.endTs = endTs
            self.offsetSec = offsetSec
        }
    }

    /// One day's activity totals: the day's Effort and step count (nil when unknown).
    public struct Day: Equatable, Sendable {
        public let day: String
        public let effort: Double?
        public let steps: Int?
        public init(day: String, effort: Double?, steps: Int?) {
            self.day = day
            self.effort = effort
            self.steps = steps
        }
    }

    // MARK: - Factors

    /// The automatic factors. The raw value is the stable cross-platform id; `key` is what the
    /// ranker sees as the behaviour name ("auto.lateWorkout").
    public enum Factor: String, CaseIterable, Sendable {
        case lateWorkout
        case hardEffort
        case restDay
        case lateBedtime
        case irregularBedtime
        case lowSteps
        case highSteps

        /// The behaviour name handed to EffectRanker.
        public var key: String { AutoFactors.keyPrefix() + rawValue }
    }

    /// The yes / control day sets per factor key, in the shape EffectRanker takes.
    public struct Result: Equatable, Sendable {
        public let behaviors: [String: Set<String>]
        public let controls: [String: Set<String>]
    }

    // MARK: - Constants

    /// Prefix that marks an automatic factor among journal behaviours.
    ///
    /// Kotlin twin: `AutoFactors.keyPrefix`.
    public static func keyPrefix() -> String { "auto." }

    /// Days of history the personal thresholds are taken from.
    ///
    /// Kotlin twin: `AutoFactors.windowDays`.
    public static func windowDays() -> Int { 90 }

    /// Fewest values a threshold needs; below it the window falls back to the whole history.
    ///
    /// Kotlin twin: `AutoFactors.minSamples`.
    public static func minSamples() -> Int { 14 }

    /// A workout ending less than this many seconds before sleep onset is "late".
    ///
    /// Kotlin twin: `AutoFactors.lateWorkoutGapSec`.
    public static func lateWorkoutGapSec() -> Int { 3 * 3600 }

    /// Shortest sleep block, in seconds, that counts as the night's main sleep.
    ///
    /// Kotlin twin: `AutoFactors.minMainSleepSec`.
    public static func minMainSleepSec() -> Int { 3 * 3600 }

    /// A bedtime this many minutes or more after the personal median is "late".
    ///
    /// Kotlin twin: `AutoFactors.lateBedtimeMin`.
    public static func lateBedtimeMin() -> Double { 60 }

    /// Within this many minutes of the reference bedtime counts as the control group.
    ///
    /// Kotlin twin: `AutoFactors.bedtimeControlBandMin`.
    public static func bedtimeControlBandMin() -> Double { 30 }

    /// A bedtime more than this many minutes from the 7-night median is "irregular".
    ///
    /// Kotlin twin: `AutoFactors.irregularBedtimeMin`.
    public static func irregularBedtimeMin() -> Double { 60 }

    /// Nights looked back over for the irregular-bedtime reference median.
    ///
    /// Kotlin twin: `AutoFactors.irregularLookbackNights`.
    public static func irregularLookbackNights() -> Int { 7 }

    /// Fewest of those nights needed before a night can be judged.
    ///
    /// Kotlin twin: `AutoFactors.irregularMinNights`.
    public static func irregularMinNights() -> Int { 4 }

    /// Effort percentile at or above which a day is "hard".
    ///
    /// Kotlin twin: `AutoFactors.hardEffortPct`.
    public static func hardEffortPct() -> Double { 0.75 }

    /// Step percentile below which a day is "low steps".
    ///
    /// Kotlin twin: `AutoFactors.lowStepsPct`.
    public static func lowStepsPct() -> Double { 0.25 }

    /// Step percentile above which a day is "high steps".
    ///
    /// Kotlin twin: `AutoFactors.highStepsPct`.
    public static func highStepsPct() -> Double { 0.75 }

    // MARK: - Engine

    /// The factor a ranker behaviour name stands for, or nil for a journal behaviour.
    ///
    /// Kotlin twin: `AutoFactors.factorForKey`.
    public static func factorForKey(_ key: String) -> Factor? {
        guard key.hasPrefix(keyPrefix()) else { return nil }
        return Factor(rawValue: String(key.dropFirst(keyPrefix().count)))
    }

    /// Derive every factor's yes and control day sets. `today` ("yyyy-MM-dd") anchors the
    /// threshold window. A factor whose yes or control set comes out empty is omitted.
    ///
    /// Kotlin twin: `AutoFactors.factors`.
    public static func factors(workouts: [Workout], sleeps: [Sleep], days: [Day], today: String) -> Result {
        guard let todayIdx = dayIndex(today) else { return Result(behaviors: [:], controls: [:]) }
        let windowStart = todayIdx - (windowDays() - 1)
        var groups: [(Factor, Set<String>, Set<String>)] = []

        // Main sleep per night: the longest block of at least minMainSleepSec; ties go to the
        // earlier onset.
        var mainSleep: [Int: Sleep] = [:]
        for s in sleeps {
            let dur = s.endTs - s.startTs
            guard dur >= minMainSleepSec() else { continue }
            let night = nightIndex(s)
            if let cur = mainSleep[night] {
                let curDur = cur.endTs - cur.startTs
                if dur > curDur || (dur == curDur && s.startTs < cur.startTs) { mainSleep[night] = s }
            } else {
                mainSleep[night] = s
            }
        }
        var onsetMin: [Int: Double] = [:]
        for (night, s) in mainSleep { onsetMin[night] = Double(minutesAfterNoon(s)) }

        // Workouts per local start day (the rest-day factor's calendar day).
        var workoutsByDay: [Int: [Workout]] = [:]
        for w in workouts {
            workoutsByDay[floorDiv(w.startTs + w.offsetSec, 86_400), default: []].append(w)
        }

        // Late workout vs an earlier one, judged against the onset of the night the workout
        // precedes. A workout starting on local day d belongs to night d − 1 when that night's main
        // sleep exists and the workout starts before its onset (an onset after midnight); otherwise
        // to night d. Only a workout that ends by the onset is judged: one still running at onset is
        // neither late nor a control. A night is late when any judged workout ends less than
        // lateWorkoutGapSec() before onset, and a control when it has judged workouts and none is late.
        do {
            var lateByNight: [Int: Bool] = [:]
            for w in workouts {
                let day = floorDiv(w.startTs + w.offsetSec, 86_400)
                var night = day
                if let prev = mainSleep[day - 1], w.startTs < prev.startTs { night = day - 1 }
                guard let s = mainSleep[night], w.endTs <= s.startTs else { continue }
                let late = s.startTs - w.endTs < lateWorkoutGapSec()
                lateByNight[night] = (lateByNight[night] ?? false) || late
            }
            var y = Set<String>(), n = Set<String>()
            for (idx, late) in lateByNight {
                if late { y.insert(dayKey(idx)) } else { n.insert(dayKey(idx)) }
            }
            groups.append((.lateWorkout, y, n))
        }

        // Effort and steps (only positive values).
        var effortByIdx: [Int: Double] = [:]
        var stepsByIdx: [Int: Double] = [:]
        for d in days {
            guard let idx = dayIndex(d.day) else { continue }
            if let e = d.effort, e > 0 { effortByIdx[idx] = e }
            if let st = d.steps, st > 0 { stepsByIdx[idx] = Double(st) }
        }

        // Hard Effort day (≥ personal 75th percentile) vs an ordinary one (≤ median).
        if let vals = thresholdValues(effortByIdx, windowStart: windowStart, todayIdx: todayIdx) {
            let p75 = percentile(vals, hardEffortPct())
            let med = percentile(vals, 0.5)
            var y = Set<String>(), n = Set<String>()
            for (idx, e) in effortByIdx {
                if e >= p75 { y.insert(dayKey(idx)) }
                else if e <= med { n.insert(dayKey(idx)) }
            }
            groups.append((.hardEffort, y, n))
        }

        // Rest day (no workout) vs workout day. Only days with data, from the first workout on.
        if let firstWorkout = workoutsByDay.keys.min() {
            var observed = Set<Int>(effortByIdx.keys)
            observed.formUnion(stepsByIdx.keys)
            observed.formUnion(mainSleep.keys)
            var y = Set<String>(), n = Set<String>()
            for idx in observed where idx >= firstWorkout && workoutsByDay[idx] == nil {
                y.insert(dayKey(idx))
            }
            for idx in workoutsByDay.keys { n.insert(dayKey(idx)) }
            groups.append((.restDay, y, n))
        }

        // Late bedtime (≥ 60 min after the personal median) vs a usual one (within ±30 min).
        if let vals = thresholdValues(onsetMin, windowStart: windowStart, todayIdx: todayIdx) {
            let med = percentile(vals, 0.5)
            var y = Set<String>(), n = Set<String>()
            for (idx, m) in onsetMin {
                if m >= med + lateBedtimeMin() { y.insert(dayKey(idx)) }
                else if abs(m - med) <= bedtimeControlBandMin() { n.insert(dayKey(idx)) }
            }
            groups.append((.lateBedtime, y, n))
        }

        // Irregular bedtime: |onset − median of the previous 7 nights| > 60 min vs ≤ 30 min.
        do {
            var y = Set<String>(), n = Set<String>()
            for (idx, m) in onsetMin {
                var prior: [Double] = []
                for k in 1...irregularLookbackNights() {
                    if let v = onsetMin[idx - k] { prior.append(v) }
                }
                guard prior.count >= irregularMinNights() else { continue }
                let dev = abs(m - percentile(prior.sorted(), 0.5))
                if dev > irregularBedtimeMin() { y.insert(dayKey(idx)) }
                else if dev <= bedtimeControlBandMin() { n.insert(dayKey(idx)) }
            }
            groups.append((.irregularBedtime, y, n))
        }

        // Low steps (< 25th pct) and high steps (> 75th pct), each vs the middle band.
        if let vals = thresholdValues(stepsByIdx, windowStart: windowStart, todayIdx: todayIdx) {
            let p25 = percentile(vals, lowStepsPct())
            let p75 = percentile(vals, highStepsPct())
            var low = Set<String>(), high = Set<String>(), mid = Set<String>()
            for (idx, s) in stepsByIdx {
                if s < p25 { low.insert(dayKey(idx)) }
                else if s > p75 { high.insert(dayKey(idx)) }
                else { mid.insert(dayKey(idx)) }
            }
            groups.append((.lowSteps, low, mid))
            groups.append((.highSteps, high, mid))
        }

        var yes: [String: Set<String>] = [:]
        var no: [String: Set<String>] = [:]
        for (f, y, n) in groups where !y.isEmpty && !n.isEmpty {
            yes[f.key] = y
            no[f.key] = n
        }
        return Result(behaviors: yes, controls: no)
    }

    // MARK: - Helpers (internal for tests)

    /// The sorted values a threshold is taken from: the window's values (day index within
    /// `windowStart`…`todayIdx`), or the whole history's when the window holds fewer than
    /// `minSamples()`; nil when even that is too few.
    ///
    /// Kotlin twin: `AutoFactors.thresholdValues`.
    static func thresholdValues(_ byIdx: [Int: Double], windowStart: Int, todayIdx: Int) -> [Double]? {
        let window = byIdx.filter { $0.key >= windowStart && $0.key <= todayIdx }.map(\.value)
        if window.count >= minSamples() { return window.sorted() }
        if byIdx.count >= minSamples() { return byIdx.values.sorted() }
        return nil
    }

    /// Linear-interpolation percentile (the common "type 7") of an ascending, non-empty array.
    ///
    /// Kotlin twin: `AutoFactors.percentile`.
    static func percentile(_ sorted: [Double], _ p: Double) -> Double {
        if sorted.count == 1 { return sorted[0] }
        let h = Double(sorted.count - 1) * p
        let lo = Int(h.rounded(.down))
        if lo >= sorted.count - 1 { return sorted[sorted.count - 1] }
        return sorted[lo] + (h - Double(lo)) * (sorted[lo + 1] - sorted[lo])
    }

    /// The night a sleep block belongs to: the local day of (onset − 12 h), as a day index.
    ///
    /// Kotlin twin: `AutoFactors.nightIndex`.
    static func nightIndex(_ s: Sleep) -> Int {
        floorDiv(s.startTs + s.offsetSec - 43_200, 86_400)
    }

    /// Local onset as minutes after the previous local noon (0..<1440), so bedtimes either
    /// side of midnight sit on one continuous scale (23:00 → 660, 00:30 → 750).
    ///
    /// Kotlin twin: `AutoFactors.minutesAfterNoon`.
    static func minutesAfterNoon(_ s: Sleep) -> Int {
        floorMod(s.startTs + s.offsetSec - 43_200, 86_400) / 60
    }

    /// Integer division rounded toward negative infinity.
    ///
    /// Kotlin twin: `AutoFactors.floorDiv`.
    static func floorDiv(_ a: Int, _ b: Int) -> Int {
        let q = a / b
        return (a % b != 0 && (a < 0) != (b < 0)) ? q - 1 : q
    }

    /// The remainder matching `floorDiv`, always in `0..<b` for a positive `b`.
    ///
    /// Kotlin twin: `AutoFactors.floorMod`.
    static func floorMod(_ a: Int, _ b: Int) -> Int { a - floorDiv(a, b) * b }

    /// Days since 1970-01-01 for a "yyyy-MM-dd" key, or nil when it does not parse.
    ///
    /// Kotlin twin: `AutoFactors.dayIndex`.
    static func dayIndex(_ day: String) -> Int? {
        let parts = day.split(separator: "-", omittingEmptySubsequences: false)
        guard parts.count == 3, parts[0].count == 4, parts[1].count == 2, parts[2].count == 2,
              parts.allSatisfy({ $0.allSatisfy { ("0"..."9").contains($0) } }),
              let y = Int(parts[0]), let m = Int(parts[1]), let d = Int(parts[2]),
              (1...12).contains(m), (1...31).contains(d) else { return nil }
        return daysFromCivil(y, m, d)
    }

    /// The "yyyy-MM-dd" key for a day index (days since 1970-01-01).
    ///
    /// Kotlin twin: `AutoFactors.dayKey`.
    static func dayKey(_ idx: Int) -> String {
        let (y, m, d) = civilFromDays(idx)
        return String(format: "%04d-%02d-%02d", y, m, d)
    }

    /// Howard Hinnant's days_from_civil (proleptic Gregorian).
    ///
    /// Kotlin twin: `AutoFactors.daysFromCivil`.
    static func daysFromCivil(_ y0: Int, _ m: Int, _ d: Int) -> Int {
        let y = m <= 2 ? y0 - 1 : y0
        let era = floorDiv(y, 400)
        let yoe = y - era * 400
        let mp = (m + 9) % 12
        let doy = (153 * mp + 2) / 5 + d - 1
        let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }

    /// Howard Hinnant's civil_from_days.
    ///
    /// Kotlin twin: `AutoFactors.civilFromDays`.
    static func civilFromDays(_ z0: Int) -> (Int, Int, Int) {
        let z = z0 + 719_468
        let era = floorDiv(z, 146_097)
        let doe = z - era * 146_097
        let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        let doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        let mp = (5 * doy + 2) / 153
        let d = doy - (153 * mp + 2) / 5 + 1
        let m = mp < 10 ? mp + 3 : mp - 9
        let y = yoe + era * 400 + (m <= 2 ? 1 : 0)
        return (y, m, d)
    }
}
