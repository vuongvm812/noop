package com.noop.analytics

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Predicted 5K / 10K / half marathon / marathon times from the user's own runs.
 *
 * Riegel (1981), `T₂ = T₁ · (D₂ / D₁)^1.06`, over each qualifying run's WHOLE distance and duration (no
 * per-second distance is stored, so a fast segment inside a longer run is not seen). Each target takes the
 * fastest prediction among the qualifying runs of the last [windowDays] days, or of the whole history when
 * that window holds none. A run predicts a target only up to [maxExtrapolation] times its own distance, so a
 * marathon needs a run of at least 10.5 km; Riegel is least reliable for the marathon even then. Nothing is
 * stored: callers recompute from the workout list.
 *
 * Kotlin twin of Swift `RacePredictor`.
 */
object RacePredictor {

    /** One workout as the predictor reads it. [durationS] falls back to `endTs − startTs` when absent. */
    data class Run(
        val startTs: Long,
        val endTs: Long,
        val sport: String,
        val distanceM: Double?,
        val durationS: Double?,
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

    /** One entry per [targetDistancesM] element (null where no run reaches it), and whether the predictions
     *  came from the whole history because the recent window held no qualifying run. */
    data class Result(val items: List<Prediction?>, val fromAllHistory: Boolean)

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
     *  whole history (`fromAllHistory`) when none in that window qualifies. Swift twin: `RacePredictor.predictions`. */
    fun predictions(runs: List<Run>, nowTs: Long): Result {
        val qualifying = runs.filter { qualifies(it) }
        val cutoff = nowTs - windowDays().toLong() * 86_400L
        val recent = qualifying.filter { it.startTs >= cutoff }
        if (recent.isNotEmpty()) return Result(bestOf(recent), fromAllHistory = false)
        if (qualifying.isEmpty()) return Result(targetDistancesM().map { null }, fromAllHistory = false)
        return Result(bestOf(qualifying), fromAllHistory = true)
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
}
