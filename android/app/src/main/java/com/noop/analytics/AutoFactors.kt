package com.noop.analytics

import kotlin.math.abs
import kotlin.math.floor

/*
 * AutoFactors.kt — automatic "From your data" behaviours for the What Moves You ranker.
 *
 * Kotlin twin of StrandAnalytics/AutoFactors.swift. Keep the thresholds, the night and day keys,
 * the percentile and the group rules byte-identical to Swift; AutoFactorsOracleTest pins the
 * Swift twin's stdout.
 *
 * The journal tells EffectRanker which days a behaviour happened (Yes) and which days it did not
 * (No). This derives the same two day sets from sensor data, so a late workout or a late bedtime
 * is ranked beside the journal habits in ONE Benjamini–Hochberg family. A day in neither group
 * (between the thresholds, or missing the data the factor needs) is left out, which is the
 * journal's "no answer" rule.
 *
 * Thresholds are personal: percentiles and medians of the user's own last windowDays() days
 * (falling back to the whole history when that window holds fewer than minSamples() values).
 *
 * Day keys: a factor day is the day the behaviour happened. A night belongs to the evening it
 * started on: its key is the local day of (onset − 12 h), so a 00:30 bedtime counts for the
 * evening before. A late workout is keyed on the night it precedes, so a 00:05 workout before a
 * 00:40 bedtime counts for that same evening. Every timestamp carries its own UTC offset, so a
 * daylight-saving change cannot move a bedtime.
 */
/** Kotlin twin of Swift `AutoFactors`. */
object AutoFactors {

    /** One workout. [offsetSec] is the local UTC offset at [startTs] (seconds east of UTC). */
    data class Workout(val startTs: Long, val endTs: Long, val offsetSec: Long)

    /** One sleep block. [offsetSec] is the local UTC offset at [startTs] (the onset). */
    data class Sleep(val startTs: Long, val endTs: Long, val offsetSec: Long)

    /** One day's activity totals: the day's Effort and step count (null when unknown). */
    data class Day(val day: String, val effort: Double?, val steps: Int?)

    /** The automatic factors. [raw] is the stable cross-platform id; [key] is the ranker's behaviour name. */
    enum class Factor(val raw: String) {
        LATE_WORKOUT("lateWorkout"),
        HARD_EFFORT("hardEffort"),
        REST_DAY("restDay"),
        LATE_BEDTIME("lateBedtime"),
        IRREGULAR_BEDTIME("irregularBedtime"),
        LOW_STEPS("lowSteps"),
        HIGH_STEPS("highSteps"),
        ;

        val key: String get() = keyPrefix() + raw
    }

    /** The yes / control day sets per factor key, in the shape EffectRanker takes. */
    data class Result(val behaviors: Map<String, Set<String>>, val controls: Map<String, Set<String>>)

    /** Prefix that marks an automatic factor among journal behaviours. Swift twin: `AutoFactors.keyPrefix`. */
    fun keyPrefix(): String = "auto."

    /** Days of history the personal thresholds are taken from. Swift twin: `AutoFactors.windowDays`. */
    fun windowDays(): Int = 90

    /** Fewest values a threshold needs; below it the window falls back to the whole history.
     *  Swift twin: `AutoFactors.minSamples`. */
    fun minSamples(): Int = 14

    /** A workout ending less than this many seconds before sleep onset is "late".
     *  Swift twin: `AutoFactors.lateWorkoutGapSec`. */
    fun lateWorkoutGapSec(): Long = 3L * 3600L

    /** Shortest sleep block, in seconds, that counts as the night's main sleep.
     *  Swift twin: `AutoFactors.minMainSleepSec`. */
    fun minMainSleepSec(): Long = 3L * 3600L

    /** A bedtime this many minutes or more after the personal median is "late".
     *  Swift twin: `AutoFactors.lateBedtimeMin`. */
    fun lateBedtimeMin(): Double = 60.0

    /** Within this many minutes of the reference bedtime counts as the control group.
     *  Swift twin: `AutoFactors.bedtimeControlBandMin`. */
    fun bedtimeControlBandMin(): Double = 30.0

    /** A bedtime more than this many minutes from the 7-night median is "irregular".
     *  Swift twin: `AutoFactors.irregularBedtimeMin`. */
    fun irregularBedtimeMin(): Double = 60.0

    /** Nights looked back over for the irregular-bedtime reference median.
     *  Swift twin: `AutoFactors.irregularLookbackNights`. */
    fun irregularLookbackNights(): Int = 7

    /** Fewest of those nights needed before a night can be judged. Swift twin: `AutoFactors.irregularMinNights`. */
    fun irregularMinNights(): Int = 4

    /** Effort percentile at or above which a day is "hard". Swift twin: `AutoFactors.hardEffortPct`. */
    fun hardEffortPct(): Double = 0.75

    /** Step percentile below which a day is "low steps". Swift twin: `AutoFactors.lowStepsPct`. */
    fun lowStepsPct(): Double = 0.25

    /** Step percentile above which a day is "high steps". Swift twin: `AutoFactors.highStepsPct`. */
    fun highStepsPct(): Double = 0.75

    /** The factor a ranker behaviour name stands for, or null for a journal behaviour.
     *  Swift twin: `AutoFactors.factorForKey`. */
    fun factorForKey(key: String): Factor? {
        if (!key.startsWith(keyPrefix())) return null
        val raw = key.substring(keyPrefix().length)
        return Factor.entries.firstOrNull { it.raw == raw }
    }

    /**
     * Derive every factor's yes and control day sets. [today] ("yyyy-MM-dd") anchors the threshold
     * window. A factor whose yes or control set comes out empty is omitted. Swift twin: `AutoFactors.factors`.
     */
    fun factors(workouts: List<Workout>, sleeps: List<Sleep>, days: List<Day>, today: String): Result {
        val todayIdx = dayIndex(today) ?: return Result(emptyMap(), emptyMap())
        val windowStart = todayIdx - (windowDays() - 1)
        val groups = ArrayList<Triple<Factor, Set<String>, Set<String>>>()

        // Main sleep per night: the longest block of at least minMainSleepSec; ties go to the
        // earlier onset.
        val mainSleep = HashMap<Long, Sleep>()
        for (s in sleeps) {
            val dur = s.endTs - s.startTs
            if (dur < minMainSleepSec()) continue
            val night = nightIndex(s)
            val cur = mainSleep[night]
            if (cur == null) {
                mainSleep[night] = s
            } else {
                val curDur = cur.endTs - cur.startTs
                if (dur > curDur || (dur == curDur && s.startTs < cur.startTs)) mainSleep[night] = s
            }
        }
        val onsetMin = HashMap<Long, Double>()
        for ((night, s) in mainSleep) onsetMin[night] = minutesAfterNoon(s).toDouble()

        // Workouts per local start day (the rest-day factor's calendar day).
        val workoutsByDay = HashMap<Long, MutableList<Workout>>()
        for (w in workouts) {
            workoutsByDay.getOrPut(floorDiv(w.startTs + w.offsetSec, 86_400L)) { mutableListOf() }.add(w)
        }

        // Late workout vs an earlier one, judged against the onset of the night the workout
        // precedes. A workout starting on local day d belongs to night d − 1 when that night's main
        // sleep exists and the workout starts before its onset (an onset after midnight); otherwise
        // to night d. Only a workout that ends by the onset is judged: one still running at onset is
        // neither late nor a control. A night is late when any judged workout ends less than
        // lateWorkoutGapSec() before onset, and a control when it has judged workouts and none is late.
        run {
            val lateByNight = HashMap<Long, Boolean>()
            for (w in workouts) {
                val day = floorDiv(w.startTs + w.offsetSec, 86_400L)
                val prev = mainSleep[day - 1]
                val night = if (prev != null && w.startTs < prev.startTs) day - 1 else day
                val s = mainSleep[night] ?: continue
                if (w.endTs > s.startTs) continue
                val late = s.startTs - w.endTs < lateWorkoutGapSec()
                lateByNight[night] = (lateByNight[night] ?: false) || late
            }
            val y = HashSet<String>()
            val n = HashSet<String>()
            for ((idx, late) in lateByNight) if (late) y.add(dayKey(idx)) else n.add(dayKey(idx))
            groups.add(Triple(Factor.LATE_WORKOUT, y, n))
        }

        // Effort and steps (only positive values).
        val effortByIdx = HashMap<Long, Double>()
        val stepsByIdx = HashMap<Long, Double>()
        for (d in days) {
            val idx = dayIndex(d.day) ?: continue
            d.effort?.let { if (it > 0) effortByIdx[idx] = it }
            d.steps?.let { if (it > 0) stepsByIdx[idx] = it.toDouble() }
        }

        // Hard Effort day (≥ personal 75th percentile) vs an ordinary one (≤ median).
        thresholdValues(effortByIdx, windowStart, todayIdx)?.let { vals ->
            val p75 = percentile(vals, hardEffortPct())
            val med = percentile(vals, 0.5)
            val y = HashSet<String>()
            val n = HashSet<String>()
            for ((idx, e) in effortByIdx) {
                if (e >= p75) y.add(dayKey(idx)) else if (e <= med) n.add(dayKey(idx))
            }
            groups.add(Triple(Factor.HARD_EFFORT, y, n))
        }

        // Rest day (no workout) vs workout day. Only days with data, from the first workout on.
        workoutsByDay.keys.minOrNull()?.let { firstWorkout ->
            val observed = HashSet<Long>(effortByIdx.keys)
            observed.addAll(stepsByIdx.keys)
            observed.addAll(mainSleep.keys)
            val y = HashSet<String>()
            val n = HashSet<String>()
            for (idx in observed) if (idx >= firstWorkout && workoutsByDay[idx] == null) y.add(dayKey(idx))
            for (idx in workoutsByDay.keys) n.add(dayKey(idx))
            groups.add(Triple(Factor.REST_DAY, y, n))
        }

        // Late bedtime (≥ 60 min after the personal median) vs a usual one (within ±30 min).
        thresholdValues(onsetMin, windowStart, todayIdx)?.let { vals ->
            val med = percentile(vals, 0.5)
            val y = HashSet<String>()
            val n = HashSet<String>()
            for ((idx, m) in onsetMin) {
                if (m >= med + lateBedtimeMin()) y.add(dayKey(idx))
                else if (abs(m - med) <= bedtimeControlBandMin()) n.add(dayKey(idx))
            }
            groups.add(Triple(Factor.LATE_BEDTIME, y, n))
        }

        // Irregular bedtime: |onset − median of the previous 7 nights| > 60 min vs ≤ 30 min.
        run {
            val y = HashSet<String>()
            val n = HashSet<String>()
            for ((idx, m) in onsetMin) {
                val prior = ArrayList<Double>()
                for (k in 1..irregularLookbackNights()) onsetMin[idx - k]?.let { prior.add(it) }
                if (prior.size < irregularMinNights()) continue
                val dev = abs(m - percentile(prior.sorted(), 0.5))
                if (dev > irregularBedtimeMin()) y.add(dayKey(idx))
                else if (dev <= bedtimeControlBandMin()) n.add(dayKey(idx))
            }
            groups.add(Triple(Factor.IRREGULAR_BEDTIME, y, n))
        }

        // Low steps (< 25th pct) and high steps (> 75th pct), each vs the middle band.
        thresholdValues(stepsByIdx, windowStart, todayIdx)?.let { vals ->
            val p25 = percentile(vals, lowStepsPct())
            val p75 = percentile(vals, highStepsPct())
            val low = HashSet<String>()
            val high = HashSet<String>()
            val mid = HashSet<String>()
            for ((idx, s) in stepsByIdx) {
                when {
                    s < p25 -> low.add(dayKey(idx))
                    s > p75 -> high.add(dayKey(idx))
                    else -> mid.add(dayKey(idx))
                }
            }
            groups.add(Triple(Factor.LOW_STEPS, low, mid))
            groups.add(Triple(Factor.HIGH_STEPS, high, mid))
        }

        val yes = HashMap<String, Set<String>>()
        val no = HashMap<String, Set<String>>()
        for ((f, y, n) in groups) {
            if (y.isEmpty() || n.isEmpty()) continue
            yes[f.key] = y
            no[f.key] = n
        }
        return Result(yes, no)
    }

    /** The sorted values a threshold is taken from: the window's values (day index within
     *  [windowStart]…[todayIdx]), or the whole history's when the window holds fewer than [minSamples];
     *  null when even that is too few. Swift twin: `AutoFactors.thresholdValues`. */
    internal fun thresholdValues(byIdx: Map<Long, Double>, windowStart: Long, todayIdx: Long): List<Double>? {
        val window = byIdx.filterKeys { it in windowStart..todayIdx }.values.toList()
        if (window.size >= minSamples()) return window.sorted()
        if (byIdx.size >= minSamples()) return byIdx.values.sorted()
        return null
    }

    /** Linear-interpolation percentile (the common "type 7") of an ascending, non-empty list.
     *  Swift twin: `AutoFactors.percentile`. */
    internal fun percentile(sorted: List<Double>, p: Double): Double {
        if (sorted.size == 1) return sorted[0]
        val h = (sorted.size - 1).toDouble() * p
        val lo = floor(h).toInt()
        if (lo >= sorted.size - 1) return sorted[sorted.size - 1]
        return sorted[lo] + (h - lo.toDouble()) * (sorted[lo + 1] - sorted[lo])
    }

    /** The night a sleep block belongs to: the local day of (onset − 12 h), as a day index.
     *  Swift twin: `AutoFactors.nightIndex`. */
    internal fun nightIndex(s: Sleep): Long = floorDiv(s.startTs + s.offsetSec - 43_200L, 86_400L)

    /** Local onset as minutes after the previous local noon (0 until 1440), so bedtimes either side of
     *  midnight sit on one continuous scale. Swift twin: `AutoFactors.minutesAfterNoon`. */
    internal fun minutesAfterNoon(s: Sleep): Long = floorMod(s.startTs + s.offsetSec - 43_200L, 86_400L) / 60L

    /** Integer division rounded toward negative infinity. Swift twin: `AutoFactors.floorDiv`. */
    internal fun floorDiv(a: Long, b: Long): Long = Math.floorDiv(a, b)

    /** The remainder matching [floorDiv], always in 0 until [b] for a positive [b]. Swift twin: `AutoFactors.floorMod`. */
    internal fun floorMod(a: Long, b: Long): Long = a - floorDiv(a, b) * b

    /** Days since 1970-01-01 for a "yyyy-MM-dd" key, or null when it does not parse.
     *  Swift twin: `AutoFactors.dayIndex`. */
    internal fun dayIndex(day: String): Long? {
        val parts = day.split("-")
        if (parts.size != 3 || parts[0].length != 4 || parts[1].length != 2 || parts[2].length != 2) return null
        if (!parts.all { p -> p.all { it in '0'..'9' } }) return null
        val y = parts[0].toLong()
        val m = parts[1].toLong()
        val d = parts[2].toLong()
        if (m !in 1L..12L || d !in 1L..31L) return null
        return daysFromCivil(y, m, d)
    }

    /** The "yyyy-MM-dd" key for a day index (days since 1970-01-01). Swift twin: `AutoFactors.dayKey`. */
    internal fun dayKey(idx: Long): String {
        val (y, m, d) = civilFromDays(idx)
        return y.toString().padStart(4, '0') + "-" + m.toString().padStart(2, '0') + "-" + d.toString().padStart(2, '0')
    }

    /** Howard Hinnant's days_from_civil (proleptic Gregorian). Swift twin: `AutoFactors.daysFromCivil`. */
    internal fun daysFromCivil(y0: Long, m: Long, d: Long): Long {
        val y = if (m <= 2) y0 - 1 else y0
        val era = floorDiv(y, 400L)
        val yoe = y - era * 400
        val mp = (m + 9) % 12
        val doy = (153 * mp + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }

    /** Howard Hinnant's civil_from_days. Swift twin: `AutoFactors.civilFromDays`. */
    internal fun civilFromDays(z0: Long): Triple<Long, Long, Long> {
        val z = z0 + 719_468
        val era = floorDiv(z, 146_097L)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val y = yoe + era * 400 + (if (m <= 2) 1 else 0)
        return Triple(y, m, d)
    }
}
