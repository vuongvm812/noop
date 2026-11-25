import Foundation

/// Predicted 5K / 10K / half marathon / marathon times from the user's own runs.
///
/// Method: Riegel (1981), `T₂ = T₁ · (D₂ / D₁)^1.06`, applied to each qualifying run's WHOLE distance and
/// duration. NOOP stores no per-second distance for a run, so a fast segment inside a longer run is not
/// seen: the predictions describe whole-run efforts, and they read slow for anyone whose runs are mostly
/// easy. Each target takes the fastest prediction among the qualifying runs of the last `windowDays` days,
/// or of the whole history when that window holds none. A run predicts a target only up to
/// `maxExtrapolation` times its own distance; past that Riegel overstates endurance, so a marathon needs a
/// run of at least 10.5 km. Riegel is least reliable for the marathon even within that limit.
///
/// Nothing is stored: callers recompute from the workout list, so a new, imported, edited or deleted run
/// moves the numbers on the next load. Pure, so both platforms pin it with the same fixtures.
///
/// Kotlin twin: `RacePredictor`.
public enum RacePredictor {

    /// One workout as the predictor reads it. `durationS` falls back to `endTs − startTs` when absent.
    public struct Run: Equatable, Sendable {
        public let startTs: Int
        public let endTs: Int
        public let sport: String
        public let distanceM: Double?
        public let durationS: Double?
        public init(startTs: Int, endTs: Int, sport: String, distanceM: Double?, durationS: Double?) {
            self.startTs = startTs; self.endTs = endTs; self.sport = sport
            self.distanceM = distanceM; self.durationS = durationS
        }
    }

    /// One predicted race time and the run it came from.
    public struct Prediction: Equatable, Sendable {
        /// Index into `targetDistancesM()`: 0 = 5K, 1 = 10K, 2 = half marathon, 3 = marathon.
        public let targetIndex: Int
        public let targetM: Double
        /// Predicted time in whole seconds.
        public let seconds: Int
        public let sourceStartTs: Int
        public let sourceDistanceM: Double
        public let sourceDurationS: Double
        public init(targetIndex: Int, targetM: Double, seconds: Int, sourceStartTs: Int,
                    sourceDistanceM: Double, sourceDurationS: Double) {
            self.targetIndex = targetIndex; self.targetM = targetM; self.seconds = seconds
            self.sourceStartTs = sourceStartTs; self.sourceDistanceM = sourceDistanceM
            self.sourceDurationS = sourceDurationS
        }
    }

    /// The four predictions (nil where no qualifying run reaches that target) and whether they came from
    /// the whole history because the recent window held no qualifying run.
    public struct Result: Equatable, Sendable {
        /// One entry per `targetDistancesM()` element, in that order.
        public let items: [Prediction?]
        public let fromAllHistory: Bool
        public init(items: [Prediction?], fromAllHistory: Bool) {
            self.items = items; self.fromAllHistory = fromAllHistory
        }
    }

    /// Target distances in metres: 5K, 10K, half marathon, marathon.
    ///
    /// Kotlin twin: `RacePredictor.targetDistancesM`.
    public static func targetDistancesM() -> [Double] { [5_000, 10_000, 21_097.5, 42_195] }

    /// Riegel's fatigue exponent.
    ///
    /// Kotlin twin: `RacePredictor.riegelExponent`.
    public static func riegelExponent() -> Double { 1.06 }

    /// Shortest run that predicts anything, in metres.
    ///
    /// Kotlin twin: `RacePredictor.minDistanceM`.
    public static func minDistanceM() -> Double { 3_000 }

    /// Shortest run duration that predicts anything, in seconds (12 minutes).
    ///
    /// Kotlin twin: `RacePredictor.minDurationS`.
    public static func minDurationS() -> Double { 720 }

    /// Slowest average speed a qualifying run may have, in m/s (11:07 /km). Slower is a walk.
    ///
    /// Kotlin twin: `RacePredictor.minSpeedMps`.
    public static func minSpeedMps() -> Double { 1.5 }

    /// Fastest average speed a qualifying run may have, in m/s (2:23 /km). Faster is a GPS glitch or a ride
    /// labelled as a run.
    ///
    /// Kotlin twin: `RacePredictor.maxSpeedMps`.
    public static func maxSpeedMps() -> Double { 7.0 }

    /// Longest target a run predicts, as a multiple of its own distance.
    ///
    /// Kotlin twin: `RacePredictor.maxExtrapolation`.
    public static func maxExtrapolation() -> Double { 4.0 }

    /// Days of recent runs the predictions read before falling back to the whole history.
    ///
    /// Kotlin twin: `RacePredictor.windowDays`.
    public static func windowDays() -> Int { 90 }

    /// Sport names that count as a run, lowercased. Walking and hiking are on foot but are not race efforts.
    ///
    /// Kotlin twin: `RacePredictor.runningSports`.
    public static func runningSports() -> [String] { ["running", "treadmill run"] }

    /// True for a running sport: case-insensitive, ignoring surrounding ASCII spaces, tabs and line breaks.
    ///
    /// Kotlin twin: `RacePredictor.isRunningSport`.
    public static func isRunningSport(_ sport: String) -> Bool {
        let trimmed = sport.trimmingCharacters(in: CharacterSet(charactersIn: " \t\r\n"))
        return runningSports().contains(trimmed.lowercased())
    }

    /// The run's duration in seconds: the stored duration when positive, else `endTs − startTs`.
    ///
    /// Kotlin twin: `RacePredictor.effectiveDurationS`.
    public static func effectiveDurationS(_ run: Run) -> Double {
        if let d = run.durationS, d.isFinite, d > 0 { return d }
        return Double(run.endTs - run.startTs)
    }

    /// Whether a run is a usable race effort: a running sport, at least `minDistanceM()` and
    /// `minDurationS()`, with an average speed inside `minSpeedMps()`…`maxSpeedMps()`.
    ///
    /// Kotlin twin: `RacePredictor.qualifies`.
    public static func qualifies(_ run: Run) -> Bool {
        guard isRunningSport(run.sport), let d = run.distanceM, d.isFinite, d >= minDistanceM() else {
            return false
        }
        let t = effectiveDurationS(run)
        guard t.isFinite, t >= minDurationS() else { return false }
        let speed = d / t
        return speed >= minSpeedMps() && speed <= maxSpeedMps()
    }

    /// Riegel: the time for `toM` metres given `timeS` seconds over `fromM` metres.
    ///
    /// Kotlin twin: `RacePredictor.riegel`.
    public static func riegel(timeS: Double, fromM: Double, toM: Double) -> Double {
        timeS * pow(toM / fromM, riegelExponent())
    }

    /// The shortest run, in metres, that predicts target `targetIndex`: the target over `maxExtrapolation()`.
    /// 0 for an index out of range.
    ///
    /// Kotlin twin: `RacePredictor.minRunM`.
    public static func minRunM(targetIndex: Int) -> Double {
        let targets = targetDistancesM()
        guard targetIndex >= 0, targetIndex < targets.count else { return 0 }
        return targets[targetIndex] / maxExtrapolation()
    }

    /// The unrounded prediction of one run for target `targetIndex`, or nil when the run does not qualify,
    /// the index is out of range, or the target is beyond `maxExtrapolation()` times the run's distance.
    ///
    /// Kotlin twin: `RacePredictor.rawPrediction`.
    public static func rawPrediction(_ run: Run, targetIndex: Int) -> Double? {
        let targets = targetDistancesM()
        guard targetIndex >= 0, targetIndex < targets.count, qualifies(run), let d = run.distanceM else {
            return nil
        }
        let target = targets[targetIndex]
        guard target <= d * maxExtrapolation() else { return nil }
        return riegel(timeS: effectiveDurationS(run), fromM: d, toM: target)
    }

    /// What one run predicts for each target, nil where it does not qualify or the target is too far.
    ///
    /// Kotlin twin: `RacePredictor.forRun`.
    public static func forRun(_ run: Run) -> [Prediction?] {
        targetDistancesM().indices.map { i in
            rawPrediction(run, targetIndex: i).map { makePrediction(run, targetIndex: i, raw: $0) }
        }
    }

    /// Wrap a raw prediction with its target and source run, rounding to whole seconds.
    ///
    /// Kotlin twin: `RacePredictor.makePrediction`.
    public static func makePrediction(_ run: Run, targetIndex: Int, raw: Double) -> Prediction {
        Prediction(targetIndex: targetIndex, targetM: targetDistancesM()[targetIndex],
                   seconds: Int(raw.rounded()), sourceStartTs: run.startTs,
                   sourceDistanceM: run.distanceM ?? 0, sourceDurationS: effectiveDurationS(run))
    }

    /// Whether candidate `a` (raw prediction `ra`) beats the current best `b` (raw `rb`): faster wins; a
    /// tie goes to the newer run, then to the longer one, so the result never depends on input order.
    ///
    /// Kotlin twin: `RacePredictor.beats`.
    public static func beats(_ a: Run, _ ra: Double, _ b: Run, _ rb: Double) -> Bool {
        if ra != rb { return ra < rb }
        if a.startTs != b.startTs { return a.startTs > b.startTs }
        return (a.distanceM ?? 0) > (b.distanceM ?? 0)
    }

    /// The fastest prediction per target over `runs` (already filtered to a window).
    ///
    /// Kotlin twin: `RacePredictor.bestOf`.
    public static func bestOf(_ runs: [Run]) -> [Prediction?] {
        targetDistancesM().indices.map { i in
            var best: (run: Run, raw: Double)?
            for run in runs {
                guard let raw = rawPrediction(run, targetIndex: i) else { continue }
                if let b = best, !beats(run, raw, b.run, b.raw) { continue }
                best = (run, raw)
            }
            return best.map { makePrediction($0.run, targetIndex: i, raw: $0.raw) }
        }
    }

    /// Predictions from every qualifying run that started at or after `nowTs − windowDays()` days, or from
    /// the whole history (`fromAllHistory`) when none in that window qualifies. All nil when no run
    /// qualifies at all.
    ///
    /// Kotlin twin: `RacePredictor.predictions`.
    public static func predictions(runs: [Run], nowTs: Int) -> Result {
        let qualifying = runs.filter { qualifies($0) }
        let cutoff = nowTs - windowDays() * 86_400
        let recent = qualifying.filter { $0.startTs >= cutoff }
        if !recent.isEmpty { return Result(items: bestOf(recent), fromAllHistory: false) }
        if qualifying.isEmpty {
            return Result(items: targetDistancesM().map { _ in nil }, fromAllHistory: false)
        }
        return Result(items: bestOf(qualifying), fromAllHistory: true)
    }

    /// A race time for display: "h:mm:ss" from an hour up, else "m:ss". Negative input reads as 0.
    ///
    /// Kotlin twin: `RacePredictor.formatTime`.
    public static func formatTime(_ seconds: Int) -> String {
        let s = max(0, seconds)
        let h = s / 3600, m = (s % 3600) / 60, sec = s % 60
        return h > 0 ? "\(h):\(twoDigits(m)):\(twoDigits(sec))" : "\(m):\(twoDigits(sec))"
    }

    /// Zero-padded two-digit field for `formatTime`.
    ///
    /// Kotlin twin: `RacePredictor.twoDigits`.
    public static func twoDigits(_ v: Int) -> String { v < 10 ? "0\(v)" : "\(v)" }

    /// Seconds per kilometre for a prediction, for the pace formatter.
    ///
    /// Kotlin twin: `RacePredictor.secPerKm`.
    public static func secPerKm(_ p: Prediction) -> Double { Double(p.seconds) / (p.targetM / 1000.0) }
}
