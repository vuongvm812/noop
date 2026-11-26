import Foundation

/// Predicted 5K / 10K / half marathon / marathon times from the user's own runs.
///
/// Up to three estimates per target, blended by `blend` (weights `riegelWeight()`, `hrWeight()`,
/// `vo2Weight()`, renormalised over the estimates present), so a user with runs alone sees the Riegel
/// numbers unchanged:
///
/// - Riegel (below), from the best whole run.
/// - Heart-rate effort: each recent run's pace at its average heart rate, scaled to a maximal effort with
///   Swain's %HRR ≈ %VO₂R, gives a VDOT; the mean of the best `hrTopCount()` runs is converted to a race
///   time with Daniels' VDOT model (`timeForVdot`). An easy run therefore counts at its true fitness.
/// - VO₂max: the newest VO₂max (watch value, else NOOP's estimate) read as a VDOT.
///
/// Riegel (1981), `T₂ = T₁ · (D₂ / D₁)^1.06`, applied to each qualifying run's WHOLE distance and
/// duration. NOOP stores no per-second distance for a run, so a fast segment inside a longer run is not
/// seen: the predictions describe whole-run efforts, and they read slow for anyone whose runs are mostly
/// easy. Each target takes the fastest prediction among the qualifying runs of the last `windowDays` days,
/// or of the whole history when that window holds none. A run predicts a target only up to
/// `maxExtrapolation` times its own distance; past that Riegel overstates endurance, so a marathon needs a
/// run of at least 10.5 km. Riegel is least reliable for the marathon even within that limit.
///
/// The card recomputes from the workout list, so a new, imported, edited or deleted run moves the numbers
/// on the next load. `dailyHistory` replays the same computation as of each past day for the trend chart,
/// and `historyChanges` says which stored history points to write. Pure, so both platforms pin it with the
/// same fixtures.
///
/// Kotlin twin: `RacePredictor`.
public enum RacePredictor {

    /// One workout as the predictor reads it. `durationS` falls back to `endTs − startTs` when absent.
    /// `avgHr` (bpm) feeds the heart-rate estimate; nil leaves the run to Riegel alone.
    public struct Run: Equatable, Sendable {
        public let startTs: Int
        public let endTs: Int
        public let sport: String
        public let distanceM: Double?
        public let durationS: Double?
        public let avgHr: Double?
        public init(startTs: Int, endTs: Int, sport: String, distanceM: Double?, durationS: Double?,
                    avgHr: Double?) {
            self.startTs = startTs; self.endTs = endTs; self.sport = sport
            self.distanceM = distanceM; self.durationS = durationS; self.avgHr = avgHr
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

    /// The physiology the heart-rate and VO₂max estimates read, as of one day. nil fields leave that
    /// estimate out.
    public struct Inputs: Equatable, Sendable {
        /// Maximum heart rate, bpm.
        public let hrMax: Double?
        /// Resting heart rate, bpm (`restingHrAsOf`).
        public let restingHr: Double?
        /// VO₂max, ml/kg/min (`vo2maxAsOf`).
        public let vo2max: Double?
        public init(hrMax: Double?, restingHr: Double?, vo2max: Double?) {
            self.hrMax = hrMax; self.restingHr = restingHr; self.vo2max = vo2max
        }
    }

    /// The unrounded seconds each method predicts for one target; nil where that method has no estimate.
    public struct Components: Equatable, Sendable {
        public let riegelS: Double?
        public let hrS: Double?
        public let vo2S: Double?
        public init(riegelS: Double?, hrS: Double?, vo2S: Double?) {
            self.riegelS = riegelS; self.hrS = hrS; self.vo2S = vo2S
        }
    }

    /// The blended prediction for one target: what the card shows.
    public struct Estimate: Equatable, Sendable {
        /// Index into `targetDistancesM()`.
        public let targetIndex: Int
        public let targetM: Double
        /// Blended time in whole seconds.
        public let seconds: Int
        public let components: Components
        /// The Riegel pick behind `components.riegelS`. nil when no run is long enough for this target, so
        /// the time is an estimate from heart rate and VO₂max alone.
        public let riegel: Prediction?
        public init(targetIndex: Int, targetM: Double, seconds: Int, components: Components, riegel: Prediction?) {
            self.targetIndex = targetIndex; self.targetM = targetM; self.seconds = seconds
            self.components = components; self.riegel = riegel
        }
    }

    /// The four predictions (nil where no qualifying run reaches that target) and whether they came from
    /// the whole history because the recent window held no qualifying run.
    public struct Result: Equatable, Sendable {
        /// The Riegel pick per `targetDistancesM()` element, in that order.
        public let items: [Prediction?]
        public let fromAllHistory: Bool
        /// The blended prediction per target, in the same order. Equal to `items` when only Riegel has an
        /// estimate; all nil when no run qualifies.
        public let estimates: [Estimate?]
        public init(items: [Prediction?], fromAllHistory: Bool, estimates: [Estimate?]) {
            self.items = items; self.fromAllHistory = fromAllHistory; self.estimates = estimates
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
    /// qualifies at all. Riegel alone: each estimate is its pick.
    ///
    /// Kotlin twin: `RacePredictor.predictions`.
    public static func predictions(runs: [Run], nowTs: Int) -> Result {
        let qualifying = runs.filter { qualifies($0) }
        let cutoff = nowTs - windowDays() * 86_400
        let recent = qualifying.filter { $0.startTs >= cutoff }
        let items: [Prediction?]
        if !recent.isEmpty {
            items = bestOf(recent)
        } else if qualifying.isEmpty {
            items = targetDistancesM().map { _ in nil }
        } else {
            items = bestOf(qualifying)
        }
        return Result(items: items, fromAllHistory: recent.isEmpty && !qualifying.isEmpty,
                      estimates: items.indices.map { estimate(targetIndex: $0, riegel: items[$0], hrVdot: nil, vo2max: nil) })
    }

    /// `predictions(runs:nowTs:)` blended with the heart-rate estimate (runs of the same 90-day window only)
    /// and the VO₂max estimate from `inputs`. Everything stays nil when no run qualifies at all: the
    /// physiology refines run predictions, it does not invent them for someone who does not run.
    ///
    /// Kotlin twin: `RacePredictor.blendedPredictions`.
    public static func blendedPredictions(runs: [Run], nowTs: Int, inputs: Inputs) -> Result {
        let base = predictions(runs: runs, nowTs: nowTs)
        guard base.items.contains(where: { $0 != nil }) else { return base }
        let cutoff = nowTs - windowDays() * 86_400
        let hr = hrVdot(runs.filter { qualifies($0) && $0.startTs >= cutoff }, inputs: inputs)
        let vo2 = inputs.vo2max.flatMap { $0.isFinite && $0 > 0 ? $0 : nil }
        return Result(items: base.items, fromAllHistory: base.fromAllHistory,
                      estimates: base.items.indices.map { estimate(targetIndex: $0, riegel: base.items[$0], hrVdot: hr, vo2max: vo2) })
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

    /// Seconds per kilometre for `seconds` over target `targetIndex`; 0 for an index out of range.
    ///
    /// Kotlin twin: `RacePredictor.paceSecPerKm`.
    public static func paceSecPerKm(seconds: Int, targetIndex: Int) -> Double {
        let targets = targetDistancesM()
        guard targetIndex >= 0, targetIndex < targets.count else { return 0 }
        return Double(seconds) / (targets[targetIndex] / 1000.0)
    }

    // MARK: - Daniels VDOT model

    /// Oxygen cost of running at `vMPerMin` metres per minute, ml/kg/min (Daniels & Gilbert, 1979).
    ///
    /// Kotlin twin: `RacePredictor.vo2Cost`.
    public static func vo2Cost(_ vMPerMin: Double) -> Double {
        -4.60 + 0.182258 * vMPerMin + 0.000104 * vMPerMin * vMPerMin
    }

    /// The fraction of VO₂max sustainable for a race lasting `tMin` minutes (Daniels & Gilbert, 1979).
    ///
    /// Kotlin twin: `RacePredictor.fractionAt`.
    public static func fractionAt(_ tMin: Double) -> Double {
        0.8 + 0.1894393 * exp(-0.012778 * tMin) + 0.2989558 * exp(-0.1932605 * tMin)
    }

    /// Bisection steps in `timeForVdot`. Fixed, so both platforms walk the same intervals.
    ///
    /// Kotlin twin: `RacePredictor.bisectionSteps`.
    public static func bisectionSteps() -> Int { 100 }

    /// Fastest speed `timeForVdot` searches, metres per minute (60 km/h).
    ///
    /// Kotlin twin: `RacePredictor.searchMaxMPerMin`.
    public static func searchMaxMPerMin() -> Double { 1000 }

    /// Slowest speed `timeForVdot` searches, metres per minute (3 km/h).
    ///
    /// Kotlin twin: `RacePredictor.searchMinMPerMin`.
    public static func searchMinMPerMin() -> Double { 50 }

    /// The race time in seconds over `distanceM` metres for a runner of `vdot`: the time t (minutes) where
    /// `vo2Cost(distanceM / t) = vdot · fractionAt(t)`, found by `bisectionSteps()` halvings between
    /// `searchMaxMPerMin()` and `searchMinMPerMin()`. nil for a non-finite or non-positive input, or a
    /// `vdot` whose time falls outside that speed range.
    ///
    /// Kotlin twin: `RacePredictor.timeForVdot`.
    public static func timeForVdot(_ vdot: Double, distanceM: Double) -> Double? {
        guard vdot.isFinite, vdot > 0, distanceM.isFinite, distanceM > 0 else { return nil }
        var lo = distanceM / searchMaxMPerMin(), hi = distanceM / searchMinMPerMin()
        guard vdotGap(lo, vdot: vdot, distanceM: distanceM) > 0,
              vdotGap(hi, vdot: vdot, distanceM: distanceM) < 0 else { return nil }
        for _ in 0..<bisectionSteps() {
            let mid = (lo + hi) / 2
            if vdotGap(mid, vdot: vdot, distanceM: distanceM) > 0 { lo = mid } else { hi = mid }
        }
        return (lo + hi) / 2 * 60
    }

    /// How far the oxygen cost of covering `distanceM` in `tMin` minutes exceeds what a runner of `vdot`
    /// can sustain for that long: positive while the pace is too fast, negative once it is sustainable.
    ///
    /// Kotlin twin: `RacePredictor.vdotGap`.
    public static func vdotGap(_ tMin: Double, vdot: Double, distanceM: Double) -> Double {
        vo2Cost(distanceM / tMin) - vdot * fractionAt(tMin)
    }

    // MARK: - Heart-rate effort estimate

    /// Lowest heart-rate-reserve fraction a run may hold to estimate fitness. Below it the run is too easy
    /// for Swain's %HRR ≈ %VO₂R to scale reliably.
    ///
    /// Kotlin twin: `RacePredictor.minHrrFraction`.
    public static func minHrrFraction() -> Double { 0.60 }

    /// Highest heart-rate-reserve fraction a run may hold. Above it the average heart rate exceeds the
    /// maximum, so the maximum is stale or the reading is wrong.
    ///
    /// Kotlin twin: `RacePredictor.maxHrrFraction`.
    public static func maxHrrFraction() -> Double { 1.00 }

    /// Resting oxygen cost, ml/kg/min: the floor of the VO₂ reserve.
    ///
    /// Kotlin twin: `RacePredictor.restingVo2`.
    public static func restingVo2() -> Double { 3.5 }

    /// How many of the best heart-rate VDOTs are averaged.
    ///
    /// Kotlin twin: `RacePredictor.hrTopCount`.
    public static func hrTopCount() -> Int { 3 }

    /// The heart-rate-reserve fraction `(avgHr − restingHr) / (hrMax − restingHr)`, or nil when an input is
    /// missing, non-finite or non-positive, `hrMax` is not above `restingHr`, or the fraction falls outside
    /// `minHrrFraction()`…`maxHrrFraction()`.
    ///
    /// Kotlin twin: `RacePredictor.hrrFraction`.
    public static func hrrFraction(avgHr: Double?, restingHr: Double?, hrMax: Double?) -> Double? {
        guard let avgHr, let restingHr, let hrMax, avgHr.isFinite, restingHr.isFinite, hrMax.isFinite,
              restingHr > 0, hrMax > restingHr else { return nil }
        let f = (avgHr - restingHr) / (hrMax - restingHr)
        return f >= minHrrFraction() && f <= maxHrrFraction() ? f : nil
    }

    /// One run's VDOT from its heart rate: `restingVo2() + (vo2Cost(v) − restingVo2()) / f`, where v is its
    /// average speed and f its `hrrFraction`. nil when the run does not qualify or f is rejected.
    ///
    /// Kotlin twin: `RacePredictor.vdotFromHr`.
    public static func vdotFromHr(_ run: Run, restingHr: Double?, hrMax: Double?) -> Double? {
        guard qualifies(run), let d = run.distanceM,
              let f = hrrFraction(avgHr: run.avgHr, restingHr: restingHr, hrMax: hrMax) else { return nil }
        let v = d / (effectiveDurationS(run) / 60)
        return restingVo2() + (vo2Cost(v) - restingVo2()) / f
    }

    /// The mean of the best `hrTopCount()` heart-rate VDOTs among `runs` (already filtered to the window),
    /// or nil when none has one.
    ///
    /// Kotlin twin: `RacePredictor.hrVdot`.
    public static func hrVdot(_ runs: [Run], inputs: Inputs) -> Double? {
        let top = runs.compactMap { vdotFromHr($0, restingHr: inputs.restingHr, hrMax: inputs.hrMax) }
            .sorted(by: >).prefix(hrTopCount())
        guard !top.isEmpty else { return nil }
        return top.reduce(0, +) / Double(top.count)
    }

    // MARK: - Blend

    /// Weight of the Riegel estimate in `blend`.
    ///
    /// Kotlin twin: `RacePredictor.riegelWeight`.
    public static func riegelWeight() -> Double { 0.5 }

    /// Weight of the heart-rate estimate in `blend`.
    ///
    /// Kotlin twin: `RacePredictor.hrWeight`.
    public static func hrWeight() -> Double { 0.3 }

    /// Weight of the VO₂max estimate in `blend`.
    ///
    /// Kotlin twin: `RacePredictor.vo2Weight`.
    public static func vo2Weight() -> Double { 0.2 }

    /// The weighted mean of the components present, renormalised over their weights; nil when none is.
    /// A lone component comes back as itself, so Riegel alone keeps its exact value.
    ///
    /// Kotlin twin: `RacePredictor.blend`.
    public static func blend(_ c: Components) -> Double? {
        var sum = 0.0, weight = 0.0
        for (v, w) in [(c.riegelS, riegelWeight()), (c.hrS, hrWeight()), (c.vo2S, vo2Weight())] {
            guard let v else { continue }
            sum += v * w; weight += w
        }
        return weight > 0 ? sum / weight : nil
    }

    /// The blended estimate for target `targetIndex` from its Riegel pick (its whole seconds, so Riegel
    /// alone reads exactly as the pick) and the heart-rate and VO₂max VDOTs; nil when no component has a
    /// value or the index is out of range.
    ///
    /// Kotlin twin: `RacePredictor.estimate`.
    public static func estimate(targetIndex: Int, riegel: Prediction?, hrVdot: Double?, vo2max: Double?) -> Estimate? {
        let targets = targetDistancesM()
        guard targetIndex >= 0, targetIndex < targets.count else { return nil }
        let target = targets[targetIndex]
        let riegelS = riegel.map { Double($0.seconds) }
        let c = Components(riegelS: riegelS,
                           hrS: hrVdot.flatMap { timeForVdot($0, distanceM: target) },
                           vo2S: vo2max.flatMap { timeForVdot($0, distanceM: target) })
        guard let blended = blend(c) else { return nil }
        return Estimate(targetIndex: targetIndex, targetM: target, seconds: Int(blended.rounded()), components: c,
                        riegel: riegel)
    }

    // MARK: - Inputs as of a day

    /// One stored daily value, keyed by days since 1970-01-01.
    public struct DayValue: Equatable, Sendable {
        public let epochDay: Int
        public let value: Double
        public init(epochDay: Int, value: Double) { self.epochDay = epochDay; self.value = value }
    }

    /// Days of resting heart rate `restingHrAsOf` takes the median of, ending on the day itself.
    ///
    /// Kotlin twin: `RacePredictor.restingHrDays`.
    public static func restingHrDays() -> Int { 7 }

    /// Oldest VO₂max `vo2maxAsOf` accepts, in days before the day itself.
    ///
    /// Kotlin twin: `RacePredictor.vo2maxMaxAgeDays`.
    public static func vo2maxMaxAgeDays() -> Int { 30 }

    /// The median resting heart rate of the `restingHrDays()` days ending on `epochDay`; nil when none of
    /// those days has a positive, finite value.
    ///
    /// Kotlin twin: `RacePredictor.restingHrAsOf`.
    public static func restingHrAsOf(_ points: [DayValue], epochDay: Int) -> Double? {
        let lo = epochDay - (restingHrDays() - 1)
        let v = points.filter { $0.epochDay >= lo && $0.epochDay <= epochDay && $0.value.isFinite && $0.value > 0 }
            .map(\.value).sorted()
        guard !v.isEmpty else { return nil }
        let mid = v.count / 2
        return v.count % 2 == 1 ? v[mid] : (v[mid - 1] + v[mid]) / 2
    }

    /// The newest positive, finite VO₂max dated within `vo2maxMaxAgeDays()` days up to `epochDay`; nil when
    /// there is none. On a shared day the later point in `points` wins.
    ///
    /// Kotlin twin: `RacePredictor.vo2maxAsOf`.
    public static func vo2maxAsOf(_ points: [DayValue], epochDay: Int) -> Double? {
        let lo = epochDay - vo2maxMaxAgeDays()
        var best: DayValue?
        for p in points where p.epochDay >= lo && p.epochDay <= epochDay && p.value.isFinite && p.value > 0 {
            if let b = best, p.epochDay < b.epochDay { continue }
            best = p
        }
        return best?.value
    }

    // MARK: - History

    /// Days of prediction history kept and rebuilt, ending today.
    ///
    /// Kotlin twin: `RacePredictor.historyDays`.
    public static func historyDays() -> Int { 365 }

    /// The `metricSeries` key of each target's stored prediction, in `targetDistancesM()` order. Written
    /// under the computed ("-noop") id beside Garmin's own `race_*_s`.
    ///
    /// Kotlin twin: `RacePredictor.historyKeys`.
    public static func historyKeys() -> [String] {
        ["race_pred_5k_s", "race_pred_10k_s", "race_pred_half_s", "race_pred_marathon_s"]
    }

    /// One day to replay: its key, the instant to predict as of (the day's last second, or now for today)
    /// and the physiology as of that day.
    public struct HistoryDay: Equatable, Sendable {
        public let epochDay: Int
        public let asOfTs: Int
        public let inputs: Inputs
        public init(epochDay: Int, asOfTs: Int, inputs: Inputs) {
            self.epochDay = epochDay; self.asOfTs = asOfTs; self.inputs = inputs
        }
    }

    /// One day's blended prediction per target, nil where it had none.
    public struct HistoryPoint: Equatable, Sendable {
        public let epochDay: Int
        public let seconds: [Int?]
        public init(epochDay: Int, seconds: [Int?]) { self.epochDay = epochDay; self.seconds = seconds }
    }

    /// The prediction each of `days` showed: `blendedPredictions` over the runs that started at or before its
    /// `asOfTs`, with its own inputs. Today's point, replayed as of now, equals the live card.
    ///
    /// Kotlin twin: `RacePredictor.dailyHistory`.
    public static func dailyHistory(runs: [Run], days: [HistoryDay]) -> [HistoryPoint] {
        let qualifying = runs.filter { qualifies($0) }
        return days.map { d in
            let r = blendedPredictions(runs: qualifying.filter { $0.startTs <= d.asOfTs }, nowTs: d.asOfTs, inputs: d.inputs)
            return HistoryPoint(epochDay: d.epochDay, seconds: r.estimates.map { $0?.seconds })
        }
    }

    /// One stored-history edit: write `seconds` for (`epochDay`, `targetIndex`), or delete it when nil.
    public struct HistoryChange: Equatable, Sendable {
        public let epochDay: Int
        public let targetIndex: Int
        public let seconds: Int?
        public init(epochDay: Int, targetIndex: Int, seconds: Int?) {
            self.epochDay = epochDay; self.targetIndex = targetIndex; self.seconds = seconds
        }
    }

    /// The edits that bring `stored` (per target, in `targetDistancesM()` order) to `points`: a write where
    /// the value is new or differs, a delete where a stored day now has no prediction. Days outside
    /// `points` are left alone. Ordered by day, then target.
    ///
    /// Kotlin twin: `RacePredictor.historyChanges`.
    public static func historyChanges(points: [HistoryPoint], stored: [[DayValue]]) -> [HistoryChange] {
        var out: [HistoryChange] = []
        let byTarget: [[Int: Double]] = targetDistancesM().indices.map { i in
            var m: [Int: Double] = [:]
            for p in i < stored.count ? stored[i] : [] { m[p.epochDay] = p.value }
            return m
        }
        for p in points.sorted(by: { $0.epochDay < $1.epochDay }) {
            for i in targetDistancesM().indices {
                let now = i < p.seconds.count ? p.seconds[i] : nil
                let was = byTarget[i][p.epochDay]
                if let now {
                    if was != Double(now) { out.append(HistoryChange(epochDay: p.epochDay, targetIndex: i, seconds: now)) }
                } else if was != nil {
                    out.append(HistoryChange(epochDay: p.epochDay, targetIndex: i, seconds: nil))
                }
            }
        }
        return out
    }
}
