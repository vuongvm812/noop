package com.noop.analytics

import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Predicted 5K / 10K / half marathon / marathon times from the user's own runs.
 *
 * Up to three estimates per target, blended by [blend] (weights [riegelWeight], [hrWeight], [vo2Weight],
 * renormalised over the estimates present), so a user with runs alone sees the Riegel numbers unchanged:
 * Riegel from the best whole run; a heart-rate effort estimate (each recent run's pace at its average heart
 * rate, scaled to a maximal effort with Swain's %HRR ≈ %VO₂R, read through Daniels' VDOT model
 * [timeForVdot]); and the newest VO₂max read as a VDOT.
 *
 * Riegel (1981), `T₂ = T₁ · (D₂ / D₁)^1.06`, over each qualifying run's WHOLE distance and duration (no
 * per-second distance is stored, so a fast segment inside a longer run is not seen). Each target takes the
 * fastest prediction among the qualifying runs of the last [windowDays] days, or of the whole history when
 * that window holds none. A run predicts a target only up to [maxExtrapolation] times its own distance, so a
 * marathon needs a run of at least 10.5 km; Riegel is least reliable for the marathon even then. The card
 * recomputes from the workout list; [dailyHistory] replays it as of each past day for the trend chart, and
 * [historyChanges] says which stored history points to write.
 *
 * Kotlin twin of Swift `RacePredictor`.
 */
object RacePredictor {

    /** One workout as the predictor reads it. [durationS] falls back to `endTs − startTs` when absent;
     *  [avgHr] (bpm) feeds the heart-rate estimate, null leaves the run to Riegel alone. */
    data class Run(
        val startTs: Long,
        val endTs: Long,
        val sport: String,
        val distanceM: Double?,
        val durationS: Double?,
        val avgHr: Double?,
    )

    /** One predicted race time and the run it came from. [targetIndex]: 0 = 5K, 1 = 10K, 2 = half, 3 = marathon. */
    data class Prediction(
        val targetIndex: Int,
        val targetM: Double,
        val seconds: Int,
        val sourceStartTs: Long,
        val sourceDistanceM: Double,
        val sourceDurationS: Double,
    )

    /** The physiology the heart-rate and VO₂max estimates read, as of one day: [hrMax] and [restingHr] in
     *  bpm, [vo2max] in ml/kg/min. Null fields leave that estimate out. */
    data class Inputs(val hrMax: Double?, val restingHr: Double?, val vo2max: Double?)

    /** The unrounded seconds each method predicts for one target; null where that method has no estimate. */
    data class Components(val riegelS: Double?, val hrS: Double?, val vo2S: Double?)

    /** The blended prediction for one target, in whole [seconds]: what the card shows. [riegel] is the
     *  Riegel pick, null when no run is long enough for this target (an estimate from heart rate and VO₂max). */
    data class Estimate(
        val targetIndex: Int,
        val targetM: Double,
        val seconds: Int,
        val components: Components,
        val riegel: Prediction?,
    )

    /** The Riegel pick per [targetDistancesM] element (null where no run reaches it), whether the picks came
     *  from the whole history because the recent window held no qualifying run, and the blended
     *  [estimates] in the same order (equal to [items] when only Riegel has an estimate). */
    data class Result(val items: List<Prediction?>, val fromAllHistory: Boolean, val estimates: List<Estimate?>)

    /** Target distances in metres: 5K, 10K, half marathon, marathon. Swift twin: `RacePredictor.targetDistancesM`. */
    fun targetDistancesM(): List<Double> = listOf(5_000.0, 10_000.0, 21_097.5, 42_195.0)

    /** Riegel's fatigue exponent. Swift twin: `RacePredictor.riegelExponent`. */
    fun riegelExponent(): Double = 1.06

    /** Shortest run that predicts anything, in metres. Swift twin: `RacePredictor.minDistanceM`. */
    fun minDistanceM(): Double = 3_000.0

    /** Shortest run duration that predicts anything, in seconds. Swift twin: `RacePredictor.minDurationS`. */
    fun minDurationS(): Double = 720.0

    /** Slowest qualifying average speed, m/s. Swift twin: `RacePredictor.minSpeedMps`. */
    fun minSpeedMps(): Double = 1.5

    /** Fastest qualifying average speed, m/s. Swift twin: `RacePredictor.maxSpeedMps`. */
    fun maxSpeedMps(): Double = 7.0

    /** Longest target a run predicts, as a multiple of its own distance. Swift twin: `RacePredictor.maxExtrapolation`. */
    fun maxExtrapolation(): Double = 4.0

    /** Days of recent runs read before falling back to the whole history. Swift twin: `RacePredictor.windowDays`. */
    fun windowDays(): Int = 90

    /** Sport names that count as a run, lowercased. Swift twin: `RacePredictor.runningSports`. */
    fun runningSports(): List<String> = listOf("running", "treadmill run")

    /** True for a running sport: case-insensitive, ignoring surrounding ASCII spaces, tabs and line breaks.
     *  Swift twin: `RacePredictor.isRunningSport`. */
    fun isRunningSport(sport: String): Boolean {
        val trimmed = sport.trim { it == ' ' || it == '\t' || it == '\r' || it == '\n' }
        return trimmed.lowercase() in runningSports()
    }

    /** The stored duration when positive, else `endTs − startTs`. Swift twin: `RacePredictor.effectiveDurationS`. */
    fun effectiveDurationS(run: Run): Double {
        val d = run.durationS
        if (d != null && d.isFinite() && d > 0) return d
        return (run.endTs - run.startTs).toDouble()
    }

    /** A running sport, at least [minDistanceM] and [minDurationS], with an average speed inside
     *  [minSpeedMps]…[maxSpeedMps]. Swift twin: `RacePredictor.qualifies`. */
    fun qualifies(run: Run): Boolean {
        if (!isRunningSport(run.sport)) return false
        val d = run.distanceM ?: return false
        if (!d.isFinite() || d < minDistanceM()) return false
        val t = effectiveDurationS(run)
        if (!t.isFinite() || t < minDurationS()) return false
        val speed = d / t
        return speed >= minSpeedMps() && speed <= maxSpeedMps()
    }

    /** Riegel: the time for [toM] metres given [timeS] seconds over [fromM] metres. Swift twin: `RacePredictor.riegel`. */
    fun riegel(timeS: Double, fromM: Double, toM: Double): Double = timeS * (toM / fromM).pow(riegelExponent())

    /** The shortest run, in metres, that predicts [targetIndex]: the target over [maxExtrapolation]; 0 for an
     *  index out of range. Swift twin: `RacePredictor.minRunM`. */
    fun minRunM(targetIndex: Int): Double {
        val targets = targetDistancesM()
        if (targetIndex < 0 || targetIndex >= targets.size) return 0.0
        return targets[targetIndex] / maxExtrapolation()
    }

    /** The unrounded prediction of one run for [targetIndex], or null when the run does not qualify, the
     *  index is out of range, or the target is beyond [maxExtrapolation] times the run's distance.
     *  Swift twin: `RacePredictor.rawPrediction`. */
    fun rawPrediction(run: Run, targetIndex: Int): Double? {
        val targets = targetDistancesM()
        if (targetIndex < 0 || targetIndex >= targets.size || !qualifies(run)) return null
        val d = run.distanceM ?: return null
        val target = targets[targetIndex]
        if (target > d * maxExtrapolation()) return null
        return riegel(effectiveDurationS(run), d, target)
    }

    /** What one run predicts for each target. Swift twin: `RacePredictor.forRun`. */
    fun forRun(run: Run): List<Prediction?> =
        targetDistancesM().indices.map { i -> rawPrediction(run, i)?.let { makePrediction(run, i, it) } }

    /** Wrap a raw prediction with its target and source run, rounding to whole seconds.
     *  Swift twin: `RacePredictor.makePrediction`. */
    fun makePrediction(run: Run, targetIndex: Int, raw: Double): Prediction = Prediction(
        targetIndex = targetIndex,
        targetM = targetDistancesM()[targetIndex],
        seconds = raw.roundToInt(),
        sourceStartTs = run.startTs,
        sourceDistanceM = run.distanceM ?: 0.0,
        sourceDurationS = effectiveDurationS(run),
    )

    /** Whether candidate [a] (raw [ra]) beats the current best [b] (raw [rb]): faster wins; a tie goes to the
     *  newer run, then to the longer one. Swift twin: `RacePredictor.beats`. */
    fun beats(a: Run, ra: Double, b: Run, rb: Double): Boolean {
        if (ra != rb) return ra < rb
        if (a.startTs != b.startTs) return a.startTs > b.startTs
        return (a.distanceM ?: 0.0) > (b.distanceM ?: 0.0)
    }

    /** The fastest prediction per target over [runs]. Swift twin: `RacePredictor.bestOf`. */
    fun bestOf(runs: List<Run>): List<Prediction?> = targetDistancesM().indices.map { i ->
        var bestRun: Run? = null
        var bestRaw = 0.0
        for (run in runs) {
            val raw = rawPrediction(run, i) ?: continue
            val current = bestRun
            if (current != null && !beats(run, raw, current, bestRaw)) continue
            bestRun = run
            bestRaw = raw
        }
        bestRun?.let { makePrediction(it, i, bestRaw) }
    }

    /** Predictions from the qualifying runs that started at or after `nowTs − windowDays` days, or from the
     *  whole history (`fromAllHistory`) when none in that window qualifies; Riegel alone, so each estimate
     *  is its pick. Swift twin: `RacePredictor.predictions`. */
    fun predictions(runs: List<Run>, nowTs: Long): Result {
        val qualifying = runs.filter { qualifies(it) }
        val cutoff = nowTs - windowDays().toLong() * 86_400L
        val recent = qualifying.filter { it.startTs >= cutoff }
        val items: List<Prediction?> = when {
            recent.isNotEmpty() -> bestOf(recent)
            qualifying.isEmpty() -> targetDistancesM().map { null }
            else -> bestOf(qualifying)
        }
        return Result(
            items,
            fromAllHistory = recent.isEmpty() && qualifying.isNotEmpty(),
            estimates = items.indices.map { estimate(it, items[it], null, null) },
        )
    }

    /** [predictions] blended with the heart-rate estimate (runs of the same 90-day window only) and the VO₂max
     *  estimate from [inputs]. Everything stays null when no run qualifies at all.
     *  Swift twin: `RacePredictor.blendedPredictions`. */
    fun blendedPredictions(runs: List<Run>, nowTs: Long, inputs: Inputs): Result {
        val base = predictions(runs, nowTs)
        if (base.items.none { it != null }) return base
        val cutoff = nowTs - windowDays().toLong() * 86_400L
        val hr = hrVdot(runs.filter { qualifies(it) && it.startTs >= cutoff }, inputs)
        val vo2 = inputs.vo2max?.takeIf { it.isFinite() && it > 0 }
        return base.copy(estimates = base.items.indices.map { estimate(it, base.items[it], hr, vo2) })
    }

    /** "h:mm:ss" from an hour up, else "m:ss"; negative reads as 0. Swift twin: `RacePredictor.formatTime`. */
    fun formatTime(seconds: Int): String {
        val s = maxOf(0, seconds)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "$h:${twoDigits(m)}:${twoDigits(sec)}" else "$m:${twoDigits(sec)}"
    }

    /** Zero-padded two-digit field for [formatTime]. Swift twin: `RacePredictor.twoDigits`. */
    fun twoDigits(v: Int): String = if (v < 10) "0$v" else "$v"

    /** Seconds per kilometre for a prediction, for the pace formatter. Swift twin: `RacePredictor.secPerKm`. */
    fun secPerKm(p: Prediction): Double = p.seconds.toDouble() / (p.targetM / 1000.0)

    /** Seconds per kilometre for [seconds] over target [targetIndex]; 0 for an index out of range.
     *  Swift twin: `RacePredictor.paceSecPerKm`. */
    fun paceSecPerKm(seconds: Int, targetIndex: Int): Double {
        val targets = targetDistancesM()
        if (targetIndex < 0 || targetIndex >= targets.size) return 0.0
        return seconds.toDouble() / (targets[targetIndex] / 1000.0)
    }

    // Daniels VDOT model

    /** Oxygen cost of running at [vMPerMin] metres per minute, ml/kg/min (Daniels & Gilbert, 1979).
     *  Swift twin: `RacePredictor.vo2Cost`. */
    fun vo2Cost(vMPerMin: Double): Double = -4.60 + 0.182258 * vMPerMin + 0.000104 * vMPerMin * vMPerMin

    /** The fraction of VO₂max sustainable for a race lasting [tMin] minutes (Daniels & Gilbert, 1979).
     *  Swift twin: `RacePredictor.fractionAt`. */
    fun fractionAt(tMin: Double): Double =
        0.8 + 0.1894393 * exp(-0.012778 * tMin) + 0.2989558 * exp(-0.1932605 * tMin)

    /** Bisection steps in [timeForVdot]. Swift twin: `RacePredictor.bisectionSteps`. */
    fun bisectionSteps(): Int = 100

    /** Fastest speed [timeForVdot] searches, m/min. Swift twin: `RacePredictor.searchMaxMPerMin`. */
    fun searchMaxMPerMin(): Double = 1000.0

    /** Slowest speed [timeForVdot] searches, m/min. Swift twin: `RacePredictor.searchMinMPerMin`. */
    fun searchMinMPerMin(): Double = 50.0

    /** The race time in seconds over [distanceM] metres for a runner of [vdot]: the time t (minutes) where
     *  `vo2Cost(distanceM / t) = vdot · fractionAt(t)`, found by [bisectionSteps] halvings between
     *  [searchMaxMPerMin] and [searchMinMPerMin]. Null for a non-finite or non-positive input, or a [vdot]
     *  whose time falls outside that speed range. Swift twin: `RacePredictor.timeForVdot`. */
    fun timeForVdot(vdot: Double, distanceM: Double): Double? {
        if (!vdot.isFinite() || vdot <= 0 || !distanceM.isFinite() || distanceM <= 0) return null
        var lo = distanceM / searchMaxMPerMin()
        var hi = distanceM / searchMinMPerMin()
        if (!(vdotGap(lo, vdot, distanceM) > 0) || !(vdotGap(hi, vdot, distanceM) < 0)) return null
        repeat(bisectionSteps()) {
            val mid = (lo + hi) / 2
            if (vdotGap(mid, vdot, distanceM) > 0) lo = mid else hi = mid
        }
        return (lo + hi) / 2 * 60
    }

    /** How far the oxygen cost of covering [distanceM] in [tMin] minutes exceeds what a runner of [vdot] can
     *  sustain for that long: positive while the pace is too fast. Swift twin: `RacePredictor.vdotGap`. */
    fun vdotGap(tMin: Double, vdot: Double, distanceM: Double): Double = vo2Cost(distanceM / tMin) - vdot * fractionAt(tMin)

    // Heart-rate effort estimate

    /** Lowest heart-rate-reserve fraction a run may hold to estimate fitness.
     *  Swift twin: `RacePredictor.minHrrFraction`. */
    fun minHrrFraction(): Double = 0.60

    /** Highest heart-rate-reserve fraction a run may hold. Swift twin: `RacePredictor.maxHrrFraction`. */
    fun maxHrrFraction(): Double = 1.00

    /** Resting oxygen cost, ml/kg/min. Swift twin: `RacePredictor.restingVo2`. */
    fun restingVo2(): Double = 3.5

    /** How many of the best heart-rate VDOTs are averaged. Swift twin: `RacePredictor.hrTopCount`. */
    fun hrTopCount(): Int = 3

    /** `(avgHr − restingHr) / (hrMax − restingHr)`, or null when an input is missing, non-finite or
     *  non-positive, [hrMax] is not above [restingHr], or the fraction falls outside
     *  [minHrrFraction]…[maxHrrFraction]. Swift twin: `RacePredictor.hrrFraction`. */
    fun hrrFraction(avgHr: Double?, restingHr: Double?, hrMax: Double?): Double? {
        if (avgHr == null || restingHr == null || hrMax == null) return null
        if (!avgHr.isFinite() || !restingHr.isFinite() || !hrMax.isFinite()) return null
        if (restingHr <= 0 || hrMax <= restingHr) return null
        val f = (avgHr - restingHr) / (hrMax - restingHr)
        return if (f >= minHrrFraction() && f <= maxHrrFraction()) f else null
    }

    /** One run's VDOT from its heart rate: `restingVo2 + (vo2Cost(v) − restingVo2) / f`. Null when the run
     *  does not qualify or f is rejected. Swift twin: `RacePredictor.vdotFromHr`. */
    fun vdotFromHr(run: Run, restingHr: Double?, hrMax: Double?): Double? {
        if (!qualifies(run)) return null
        val d = run.distanceM ?: return null
        val f = hrrFraction(run.avgHr, restingHr, hrMax) ?: return null
        val v = d / (effectiveDurationS(run) / 60)
        return restingVo2() + (vo2Cost(v) - restingVo2()) / f
    }

    /** The mean of the best [hrTopCount] heart-rate VDOTs among [runs], or null when none has one.
     *  Swift twin: `RacePredictor.hrVdot`. */
    fun hrVdot(runs: List<Run>, inputs: Inputs): Double? {
        val top = runs.mapNotNull { vdotFromHr(it, inputs.restingHr, inputs.hrMax) }
            .sortedDescending().take(hrTopCount())
        if (top.isEmpty()) return null
        return top.fold(0.0) { a, b -> a + b } / top.size
    }

    // Blend

    /** Weight of the Riegel estimate in [blend]. Swift twin: `RacePredictor.riegelWeight`. */
    fun riegelWeight(): Double = 0.5

    /** Weight of the heart-rate estimate in [blend]. Swift twin: `RacePredictor.hrWeight`. */
    fun hrWeight(): Double = 0.3

    /** Weight of the VO₂max estimate in [blend]. Swift twin: `RacePredictor.vo2Weight`. */
    fun vo2Weight(): Double = 0.2

    /** The weighted mean of the components present, renormalised over their weights; null when none is.
     *  Swift twin: `RacePredictor.blend`. */
    fun blend(c: Components): Double? {
        var sum = 0.0
        var weight = 0.0
        for ((v, w) in listOf(c.riegelS to riegelWeight(), c.hrS to hrWeight(), c.vo2S to vo2Weight())) {
            if (v == null) continue
            sum += v * w
            weight += w
        }
        return if (weight > 0) sum / weight else null
    }

    /** The blended estimate for [targetIndex] from its Riegel pick (its whole seconds) and the heart-rate and
     *  VO₂max VDOTs; null when no component has a value or the index is out of range.
     *  Swift twin: `RacePredictor.estimate`. */
    fun estimate(targetIndex: Int, riegel: Prediction?, hrVdot: Double?, vo2max: Double?): Estimate? {
        val targets = targetDistancesM()
        if (targetIndex < 0 || targetIndex >= targets.size) return null
        val target = targets[targetIndex]
        val c = Components(
            riegelS = riegel?.seconds?.toDouble(),
            hrS = hrVdot?.let { timeForVdot(it, target) },
            vo2S = vo2max?.let { timeForVdot(it, target) },
        )
        val blended = blend(c) ?: return null
        return Estimate(targetIndex, target, blended.roundToInt(), c, riegel)
    }

    // Inputs as of a day

    /** One stored daily value, keyed by days since 1970-01-01. */
    data class DayValue(val epochDay: Int, val value: Double)

    /** Days of resting heart rate [restingHrAsOf] takes the median of. Swift twin: `RacePredictor.restingHrDays`. */
    fun restingHrDays(): Int = 7

    /** Oldest VO₂max [vo2maxAsOf] accepts, in days. Swift twin: `RacePredictor.vo2maxMaxAgeDays`. */
    fun vo2maxMaxAgeDays(): Int = 30

    /** The median resting heart rate of the [restingHrDays] days ending on [epochDay]; null when none has a
     *  positive, finite value. Swift twin: `RacePredictor.restingHrAsOf`. */
    fun restingHrAsOf(points: List<DayValue>, epochDay: Int): Double? {
        val lo = epochDay - (restingHrDays() - 1)
        val v = points.filter { it.epochDay in lo..epochDay && it.value.isFinite() && it.value > 0 }
            .map { it.value }.sorted()
        if (v.isEmpty()) return null
        val mid = v.size / 2
        return if (v.size % 2 == 1) v[mid] else (v[mid - 1] + v[mid]) / 2
    }

    /** The newest positive, finite VO₂max dated within [vo2maxMaxAgeDays] days up to [epochDay]; on a shared
     *  day the later point wins. Swift twin: `RacePredictor.vo2maxAsOf`. */
    fun vo2maxAsOf(points: List<DayValue>, epochDay: Int): Double? {
        val lo = epochDay - vo2maxMaxAgeDays()
        var best: DayValue? = null
        for (p in points) {
            if (p.epochDay < lo || p.epochDay > epochDay || !p.value.isFinite() || p.value <= 0) continue
            val b = best
            if (b != null && p.epochDay < b.epochDay) continue
            best = p
        }
        return best?.value
    }

    // History

    /** Days of prediction history kept and rebuilt, ending today. Swift twin: `RacePredictor.historyDays`. */
    fun historyDays(): Int = 365

    /** The `metricSeries` key of each target's stored prediction, in [targetDistancesM] order, written under
     *  the computed ("-noop") id. Swift twin: `RacePredictor.historyKeys`. */
    fun historyKeys(): List<String> = listOf("race_pred_5k_s", "race_pred_10k_s", "race_pred_half_s", "race_pred_marathon_s")

    /** One day to replay: its key, the instant to predict as of, and the physiology as of that day. */
    data class HistoryDay(val epochDay: Int, val asOfTs: Long, val inputs: Inputs)

    /** One day's blended prediction per target, null where it had none. */
    data class HistoryPoint(val epochDay: Int, val seconds: List<Int?>)

    /** The prediction each of [days] showed: [blendedPredictions] over the runs that started at or before its
     *  `asOfTs`, with its own inputs. Swift twin: `RacePredictor.dailyHistory`. */
    fun dailyHistory(runs: List<Run>, days: List<HistoryDay>): List<HistoryPoint> {
        val qualifying = runs.filter { qualifies(it) }
        return days.map { d ->
            val r = blendedPredictions(qualifying.filter { it.startTs <= d.asOfTs }, d.asOfTs, d.inputs)
            HistoryPoint(d.epochDay, r.estimates.map { it?.seconds })
        }
    }

    /** One stored-history edit: write [seconds] for ([epochDay], [targetIndex]), or delete it when null. */
    data class HistoryChange(val epochDay: Int, val targetIndex: Int, val seconds: Int?)

    /** The edits that bring [stored] (per target) to [points]: a write where the value is new or differs, a
     *  delete where a stored day now has no prediction. Days outside [points] are left alone. Ordered by
     *  day, then target. Swift twin: `RacePredictor.historyChanges`. */
    fun historyChanges(points: List<HistoryPoint>, stored: List<List<DayValue>>): List<HistoryChange> {
        val out = ArrayList<HistoryChange>()
        val byTarget = targetDistancesM().indices.map { i ->
            val m = HashMap<Int, Double>()
            for (p in stored.getOrNull(i).orEmpty()) m[p.epochDay] = p.value
            m
        }
        for (p in points.sortedBy { it.epochDay }) {
            for (i in targetDistancesM().indices) {
                val now = p.seconds.getOrNull(i)
                val was = byTarget[i][p.epochDay]
                if (now != null) {
                    if (was != now.toDouble()) out.add(HistoryChange(p.epochDay, i, now))
                } else if (was != null) {
                    out.add(HistoryChange(p.epochDay, i, null))
                }
            }
        }
        return out
    }
}
