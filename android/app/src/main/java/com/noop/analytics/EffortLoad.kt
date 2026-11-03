package com.noop.analytics

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Daily training load on a LINEAR axis, and the descriptive load ratios built on it (#54).
 *
 * Day Effort is `100 × ln(TRIMP + 1) / ln(D)`, a log compression for a 0–100 gauge; smoothing it
 * under-reacts. Load models (ACWR, monotony, CTL/ATL) read TRIMP, recovered by inverting the log map
 * (exact up to the 2 dp Effort rounding, at most 0.045 % of `TRIMP + 1`), with Edwards' denominator.
 *
 * Kotlin twin of Swift `EffortLoad`.
 */
object EffortLoad {
    const val acuteSpanDays: Int = 7
    const val chronicSpanDays: Int = 28
    const val minChronicObservedDays: Int = 14
    const val runInDays: Int = 84
    const val minMonotonyDays: Int = 4

    /** TRIMP recovered from a day's Effort (0–100). Null for a non-finite or negative Effort.
     *  Swift twin: `EffortLoad.trimp`. */
    fun trimpFromEffort(effort: Double): Double? {
        if (!effort.isFinite() || effort < 0) return null
        return exp(effort / StrainScorer.maxStrain * ln(StrainScorer.strainDenominator)) - 1.0
    }

    /** Williams et al. (2017) EWMA decay: λ = 2 / (N + 1). Swift twin: `EffortLoad.ewmaLambda`. */
    fun ewmaLambda(spanDays: Int): Double = 2.0 / (spanDays + 1).toDouble()

    data class AcuteChronic(val acute: Double, val chronic: Double, val observedDays: Int) {
        val ratio: Double? get() = if (chronic > 0) acute / chronic else null
    }

    /**
     * EWMA acute:chronic load over CALENDAR days ending on [endOrdinal]. A day missing from [loads] was not
     * observed and the states HOLD across it (an unworn day is not a rest day). Seeded with the first
     * observed load in the [runInDays]-day run; null until [minObserved] of the last [chronicSpan] days are
     * observed. The spans default to [acuteSpanDays] / [chronicSpanDays] / [minChronicObservedDays]; a
     * caller with its own named tunables passes them. Descriptive only, not an injury predictor
     * (Impellizzeri 2020).
     *
     * Kotlin twin of Swift `EffortLoad.acuteChronic`.
     */
    fun acuteChronic(
        loads: Map<Int, Double>,
        endOrdinal: Int,
        acuteSpan: Int = acuteSpanDays,
        chronicSpan: Int = chronicSpanDays,
        minObserved: Int = minChronicObservedDays,
    ): AcuteChronic? {
        var observed = 0
        for (d in (endOrdinal - chronicSpan + 1)..endOrdinal) if (loads[d] != null) observed++
        if (observed < minObserved) return null
        val lambdaAcute = ewmaLambda(acuteSpan)
        val lambdaChronic = ewmaLambda(chronicSpan)
        var acute: Double? = null
        var chronic = 0.0
        for (d in (endOrdinal - runInDays + 1)..endOrdinal) {
            val x = loads[d] ?: continue
            val a = acute
            if (a != null) {
                acute = a + lambdaAcute * (x - a)
                chronic += lambdaChronic * (x - chronic)
            } else {
                acute = x
                chronic = x
            }
        }
        val a = acute ?: return null
        return AcuteChronic(a, chronic, observed)
    }

    /**
     * Foster (1998) monotony, mean / sample SD, over the observed loads in the [spanDays] (default
     * [acuteSpanDays]) calendar days ending on [endOrdinal]. Kotlin twin of Swift `EffortLoad.monotony`.
     */
    fun monotony(loads: Map<Int, Double>, endOrdinal: Int, spanDays: Int = acuteSpanDays): Double? {
        val week = ArrayList<Double>()
        for (d in (endOrdinal - spanDays + 1)..endOrdinal) loads[d]?.let { week.add(it) }
        if (week.size < minMonotonyDays) return null
        var sum = 0.0
        for (x in week) sum += x
        val m = sum / week.size.toDouble()
        var ss = 0.0
        for (x in week) ss += (x - m) * (x - m)
        val sd = sqrt(ss / (week.size - 1).toDouble())
        // Relative: identical loads sum with rounding noise, which must read as "no spread". Mirrors Swift.
        if (sd <= m * 1e-9) return null
        return m / sd
    }

    /**
     * Day ordinal → TRIMP for every row with a usable Effort; duplicate days keep the larger load.
     * Swift twin: `EffortLoad.loadsByOrdinal`.
     */
    fun loadsByOrdinal(rows: List<Pair<String, Double?>>): Map<Int, Double> {
        val out = HashMap<Int, Double>()
        for ((day, effort) in rows) {
            val t = effort?.let { trimpFromEffort(it) } ?: continue
            val ordinal = TrainingLoadEngine.dayOrdinal(day) ?: continue
            out[ordinal] = maxOf(out[ordinal] ?: t, t)
        }
        return out
    }
}
