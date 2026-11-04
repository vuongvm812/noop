package com.noop.analytics

import java.util.Locale

// SleepScoring.kt — the ONE funnel for the per-night inputs Rest is scored against (#55).
//
// Byte-identical twin of Swift `SleepScoring.swift` (StrandAnalytics): Sleep Regularity Index over the
// trailing 28 nights (Phillips et al. 2017; Windred et al. 2024) with an SD-of-midsleep fallback, base
// sleep need from the trailing 90 nights floored at the AASM/SRS adult minimum, a heuristic strain
// adjustment, and the running sleep debt. Every window ends the day before the scored day: no
// look-ahead. Oracle-pinned in SleepScoringOracleTest.

/** Calendar-day arithmetic on "yyyy-MM-dd" keys. Mirrors Swift `DayOrdinal`. */
object DayOrdinal {
    /** Days since 1970-01-01 for a "yyyy-MM-dd" key, or null when malformed. */
    fun ordinal(day: String): Int? {
        val parts = day.split("-")
        if (parts.size != 3 || parts[0].length != 4 || parts[1].length != 2 || parts[2].length != 2) return null
        val y = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        val d = parts[2].toIntOrNull() ?: return null
        if (m !in 1..12 || d !in 1..31) return null
        val yy = if (m <= 2) y - 1 else y
        val era = (if (yy >= 0) yy else yy - 399) / 400
        val yoe = yy - era * 400
        val mp = (m + 9) % 12
        val doy = (153 * mp + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }

    /** The "yyyy-MM-dd" key for a day ordinal (inverse of [ordinal]). */
    fun key(ordinal: Int): String {
        val z = ordinal + 719_468
        val era = (if (z >= 0) z else z - 146_096) / 146_097
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val y = yoe + era * 400 + (if (m <= 2) 1 else 0)
        return String.format(Locale.ROOT, "%04d-%02d-%02d", y, m, d)
    }
}

/** Sleep Regularity Index. Mirrors Swift `SleepRegularity`. */
object SleepRegularity {
    /** One asleep window with the UTC offset (seconds east) in force that night. */
    data class Window(val start: Long, val end: Long, val offsetSec: Long)

    enum class Method(val raw: String) { SRI("sri"), MIDSLEEP_SD("midsleepSD") }

    data class Result(
        /** Regularity in [0, 1]: SRI ÷ 100 clamped, or the midsleep fallback. */
        val score: Double,
        /** Raw SRI (−100…100) when that method ran; null on the fallback. */
        val sri: Double?,
        val method: Method,
        /** Consecutive-night pairs (SRI) or nights (fallback) behind the value. */
        val samples: Int,
    )

    const val epochSec: Int = 300
    const val epochsPerDay: Int = 288
    const val windowNights: Int = 28
    const val minPairs: Int = 5
    const val minCoveredEpochs: Int = 36
    const val minMidsleepNights: Int = 3
    const val midsleepSDZeroMin: Double = 120.0
    internal const val noonOffsetSec: Long = 43_200L

    internal fun floorDiv(a: Long, b: Long): Long = Math.floorDiv(a, b)

    internal class Raster {
        val grids = HashMap<Long, BooleanArray>()
        val asleepCount = HashMap<Long, Int>()
    }

    internal fun raster(windows: List<Window>): Raster {
        val r = Raster()
        val half = (epochSec / 2).toLong()
        val ep = epochSec.toLong()
        for (w in windows) {
            val ls = w.start + w.offsetSec
            val le = w.end + w.offsetSec
            if (le <= ls || le - ls > 36L * 3600L) continue
            val first = -floorDiv(-(ls - noonOffsetSec - half), ep)
            val lastExclusive = -floorDiv(-(le - noonOffsetSec - half), ep)
            if (lastExclusive <= first) continue
            var g = first
            while (g < lastExclusive) {
                val k = floorDiv(g, epochsPerDay.toLong())
                val e = (g - k * epochsPerDay).toInt()
                val grid = r.grids.getOrPut(k) { BooleanArray(epochsPerDay) }
                if (!grid[e]) {
                    grid[e] = true
                    r.asleepCount[k] = (r.asleepCount[k] ?: 0) + 1
                }
                g++
            }
        }
        return r
    }

    /** Midsleep (minutes after local noon) of the grid's longest asleep run. */
    internal fun midsleepMin(grid: BooleanArray): Double {
        var bestStart = 0; var bestLen = 0; var runStart = 0; var runLen = 0
        for (e in 0 until epochsPerDay) {
            if (grid[e]) {
                if (runLen == 0) runStart = e
                runLen += 1
                if (runLen > bestLen) { bestLen = runLen; bestStart = runStart }
            } else {
                runLen = 0
            }
        }
        return (bestStart.toDouble() + bestLen.toDouble() / 2.0) * (epochSec / 60).toDouble()
    }

    /** Regularity for each requested day from the nights that woke on the [windowNights] days before it. */
    fun results(windows: List<Window>, days: List<String>, windowNights: Int = SleepRegularity.windowNights): Map<String, Result> {
        val r = raster(windows)
        fun covered(k: Long) = (r.asleepCount[k] ?: 0) >= minCoveredEpochs
        val agreeByPair = HashMap<Long, Int>()
        for (k in r.grids.keys) {
            if (!covered(k) || !covered(k + 1)) continue
            val a = r.grids[k]!!
            val b = r.grids[k + 1]!!
            var agree = 0
            for (e in 0 until epochsPerDay) if (a[e] == b[e]) agree += 1
            agreeByPair[k] = agree
        }
        val out = LinkedHashMap<String, Result>()
        val span = maxOf(windowNights, 2)
        for (day in days) {
            val d = DayOrdinal.ordinal(day) ?: continue
            val lo = (d - 1 - span).toLong()
            val hi = (d - 2).toLong()
            var pairs = 0
            var agreeSum = 0
            var k = lo
            while (k < hi) {
                agreeByPair[k]?.let { pairs += 1; agreeSum += it }
                k++
            }
            if (pairs >= minPairs) {
                val p = agreeSum.toDouble() / (epochsPerDay * pairs).toDouble()
                val sri = 200.0 * p - 100.0
                out[day] = Result(maxOf(0.0, minOf(1.0, sri / 100.0)), sri, Method.SRI, pairs)
                continue
            }
            val mids = ArrayList<Double>()
            k = lo
            while (k <= hi) {
                if (covered(k)) mids.add(midsleepMin(r.grids[k]!!))
                k++
            }
            if (mids.size < minMidsleepNights) continue
            var sum = 0.0
            for (x in mids) sum += x
            val mean = sum / mids.size.toDouble()
            var sq = 0.0
            for (x in mids) sq += (x - mean) * (x - mean)
            val sd = Math.sqrt(sq / mids.size.toDouble())
            out[day] = Result(maxOf(0.0, minOf(1.0, 1.0 - sd / midsleepSDZeroMin)), null, Method.MIDSLEEP_SD, mids.size)
        }
        return out
    }
}

/** Sleep need funnel. Mirrors Swift `SleepNeed`. */
object SleepNeed {
    /** One recorded night keyed by its local wake day. */
    data class Night(
        val day: String,
        /** Main-night asleep minutes (null or ≤ 0 = no usable night). */
        val mainMin: Double?,
        /** Asleep minutes in separate naps on the same wake day (debt credit only). */
        val napMin: Double = 0.0,
        /** That day's Effort (0–100), which raises the NEXT night's need. */
        val strain: Double? = null,
    )

    data class Context(val baseHours: Double, val strainHours: Double, val debtMin: Double) {
        /** What the night is scored against: base + strain + debt. */
        val totalHours: Double get() = baseHours + strainHours + debtMin / 60.0
    }

    const val baseWindowDays: Int = 90
    /** HEURISTIC strain coefficients (Kitamura 2016; Van Dongen 2003 give direction, not dose). */
    const val strainNeedStart: Double = 50.0
    const val strainNeedFull: Double = 100.0
    const val strainNeedMaxHours: Double = 0.5

    fun strainHours(strain: Double?): Double {
        if (strain == null || !strain.isFinite()) return 0.0
        val f = maxOf(0.0, minOf(1.0, (strain - strainNeedStart) / (strainNeedFull - strainNeedStart)))
        return f * strainNeedMaxHours
    }

    /** One calendar day of the debt recurrence. Mirrors Swift `SleepNeed.step`. */
    internal fun step(debt: Double, needMin: Double, creditedMin: Double?): Double {
        val next = if (creditedMin != null) {
            SleepDebt.DEBT_CARRY * maxOf(0.0, needMin + debt - creditedMin)
        } else {
            SleepDebt.DEBT_CARRY * debt
        }
        return if (next < SleepDebt.ON_TARGET_BAND_MIN) 0.0 else next
    }

    /** Context for each requested day from the nights BEFORE it. A repeated day keeps its last row. */
    fun contexts(nights: List<Night>, days: List<String>, age: Int?): Map<String, Context> {
        val byOrd = HashMap<Int, Night>()
        for (n in nights) DayOrdinal.ordinal(n.day)?.let { byOrd[it] = n }
        val firstOrd = byOrd.keys.minOrNull()
        if (firstOrd == null) {
            val out = LinkedHashMap<String, Context>()
            val base = RestScorer.personalizedNeedHours(emptyList(), age)
            for (day in days) if (DayOrdinal.ordinal(day) != null) out[day] = Context(base, 0.0, 0.0)
            return out
        }
        val baseMemo = HashMap<Int, Double>()
        fun base(d: Int): Double {
            baseMemo[d]?.let { return it }
            val hours = ArrayList<Double>()
            for (o in (d - baseWindowDays) until d) {
                val m = byOrd[o]?.mainMin
                if (m != null && m > 0) hours.add(m / 60.0)
            }
            val b = RestScorer.personalizedNeedHours(hours, age)
            baseMemo[d] = b
            return b
        }
        fun credited(o: Int): Double? {
            val n = byOrd[o] ?: return null
            return SleepDebt.creditedSleepMin(n.mainMin, n.napMin)
        }
        fun needMin(o: Int): Double = (base(o) + strainHours(byOrd[o - 1]?.strain)) * 60.0

        val targets = days.mapNotNull { day -> DayOrdinal.ordinal(day)?.let { day to it } }
        val lastTarget = targets.maxOfOrNull { it.second } ?: return emptyMap()
        val debtInto = HashMap<Int, Double>()
        var debt = 0.0
        var o = firstOrd
        debtInto[o] = 0.0
        while (o < lastTarget) {
            debt = step(debt, needMin(o), credited(o))
            o += 1
            debtInto[o] = debt
        }
        val out = LinkedHashMap<String, Context>()
        for ((day, d) in targets) {
            out[day] = Context(base(d), strainHours(byOrd[d - 1]?.strain), debtInto[d] ?: 0.0)
        }
        return out
    }

    /** Debt after the scored night: one step from the context it was scored with. */
    fun debtAfter(context: Context, mainMin: Double?, napMin: Double): Double {
        val needMin = (context.baseHours + context.strainHours) * 60.0
        return step(context.debtMin, needMin, SleepDebt.creditedSleepMin(mainMin, napMin))
    }
}

/** Joined Rest context. Mirrors Swift `SleepScoring`. */
object SleepScoring {
    data class DayContext(
        val need: SleepNeed.Context,
        val regularity: SleepRegularity.Result?,
        val rest: RestScorer.Context,
    )

    /** A sleep block for nap credit: (start, end, efficiency). */
    data class Block(val start: Long, val end: Long, val efficiency: Double?)

    fun contexts(
        nights: List<SleepNeed.Night>,
        windows: List<SleepRegularity.Window>,
        days: List<String>,
        age: Int?,
    ): Map<String, DayContext> {
        val need = SleepNeed.contexts(nights, days, age)
        val reg = SleepRegularity.results(windows, days)
        val out = LinkedHashMap<String, DayContext>()
        for (day in days) {
            val n = need[day] ?: continue
            val r = reg[day]
            out[day] = DayContext(n, r, RestScorer.Context(needHours = n.totalHours, consistency = r?.score, age = age))
        }
        return out
    }

    /** Asleep minutes outside the longest block of one wake day (missing/out-of-range efficiency → 0.9). */
    fun napMinutes(blocks: List<Block>): Double {
        val valid = blocks.filter { it.end > it.start }
        if (valid.isEmpty()) return 0.0
        var longest = 0
        for (i in 1 until valid.size) {
            if ((valid[longest].end - valid[longest].start) < (valid[i].end - valid[i].start)) longest = i
        }
        var total = 0.0
        for ((i, b) in valid.withIndex()) {
            if (i == longest) continue
            val eff = b.efficiency?.takeIf { it > 0.0 && it <= 1.0 } ?: 0.9
            total += (b.end - b.start).toDouble() / 60.0 * eff
        }
        return total
    }
}
