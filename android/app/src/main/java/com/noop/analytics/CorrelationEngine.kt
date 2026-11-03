package com.noop.analytics

import kotlin.math.sqrt

/** The result of correlating two aligned series. Twin of Swift `Correlation`. */
data class Correlation(
    /** Pearson r (or Spearman rho) in [-1, 1]. */
    val r: Double,
    /** Paired observations used. */
    val n: Int,
    /** Two-sided p for H0: r = 0 (Student-t on [nEffective]; 1.0 below [CorrelationEngine.minPairs]). */
    val pApprox: Double,
    /** OLS slope of y on x (on ranks for Spearman). */
    val slope: Double,
    /** OLS intercept of y on x (on ranks for Spearman). */
    val intercept: Double,
    /** Sample size after the lag-1 autocorrelation correction (≤ n). */
    val nEffective: Double = n.toDouble(),
    val method: CorrelationEngine.Method = CorrelationEngine.Method.PEARSON,
)

/**
 * CorrelationEngine — the shared correlation primitive for every Android screen that shows one, the
 * twin of Swift `CorrelationEngine` (StrandAnalytics). Before it existed, Compare, Mind, Lab Book and
 * Insights each carried a private Pearson copy, and Insights judged significance with a 2/√n rule of
 * thumb while Apple ran a t-test.
 *
 * p uses t = r·sqrt((n_eff−2)/(1−r²)) with a Student-t tail on n_eff − 2 df, n_eff correcting for the
 * lag-1 autocorrelation of both daily series. Below [minPairs] p is 1.0 (r and the line are still
 * returned for slope-only callers). [spearman] is the rank-based option.
 */
object CorrelationEngine {

    enum class Method { PEARSON, SPEARMAN }

    /** Minimum paired days before a correlation is shown or tested. */
    const val minPairs: Int = 10

    fun correlate(xy: List<Pair<Double, Double>>, method: Method): Correlation? = when (method) {
        Method.PEARSON -> pearson(xy)
        Method.SPEARMAN -> spearman(xy)
    }

    /** Spearman's rho: Pearson over average-tie ranks. */
    fun spearman(xy: List<Pair<Double, Double>>): Correlation? {
        val rx = StatTests.averageRanks(xy.map { it.first })
        val ry = StatTests.averageRanks(xy.map { it.second })
        val c = pearson(rx.zip(ry)) ?: return null
        return c.copy(method = Method.SPEARMAN)
    }

    /** Pearson r + OLS line + p. Null with < 3 pairs or zero variance in either variable. Swift twin: `CorrelationEngine.pearson`. */
    fun pearson(xy: List<Pair<Double, Double>>): Correlation? {
        val n = xy.size
        if (n < 3) return null
        val nD = n.toDouble()
        var sumX = 0.0
        var sumY = 0.0
        for (p in xy) { sumX += p.first; sumY += p.second }
        val meanX = sumX / nD
        val meanY = sumY / nD
        var sxx = 0.0
        var syy = 0.0
        var sxy = 0.0
        for (p in xy) {
            val dx = p.first - meanX
            val dy = p.second - meanY
            sxx += dx * dx
            syy += dy * dy
            sxy += dx * dy
        }
        if (!(sxx > 0.0 && syy > 0.0)) return null
        var r = sxy / (sqrt(sxx) * sqrt(syy))
        if (r > 1.0) r = 1.0
        if (r < -1.0) r = -1.0
        val slope = sxy / sxx
        val intercept = meanY - slope * meanX
        val nEff = StatTests.effectiveN(
            n,
            StatTests.lag1Autocorrelation(xy.map { it.first }),
            StatTests.lag1Autocorrelation(xy.map { it.second }),
        )
        val p = if (n < minPairs) 1.0 else StatTests.correlationP(r, nEff)
        return Correlation(r, n, p, slope, intercept, nEff)
    }

    /** Inner-join two day-keyed series on the day key → (x, y) pairs sorted by day; last write wins. Swift twin: `CorrelationEngine.alignByDay`. */
    fun alignByDay(
        a: List<Pair<String, Double>>,
        b: List<Pair<String, Double>>,
    ): List<Pair<Double, Double>> {
        val mapA = HashMap<String, Double>()
        for ((day, v) in a) mapA[day] = v
        val mapB = HashMap<String, Double>()
        for ((day, v) in b) mapB[day] = v
        val common = mapA.keys.filter { mapB.containsKey(it) }.sorted()
        return common.map { mapA.getValue(it) to mapB.getValue(it) }
    }

    /** Correlate x[day] against y[day + lagDays] on the calendar (fixed-UTC day arithmetic). */
    fun lagged(
        x: List<Pair<String, Double>>,
        y: List<Pair<String, Double>>,
        lagDays: Int,
        method: Method = Method.PEARSON,
    ): Correlation? {
        val mapY = HashMap<String, Double>()
        for ((day, v) in y) mapY[day] = v
        val pairs = ArrayList<Pair<Double, Double>>()
        for ((day, v) in x.sortedBy { it.first }) {
            val shifted = ActivityCostEngine.shiftDay(day, lagDays) ?: continue
            mapY[shifted]?.let { pairs.add(v to it) }
        }
        return correlate(pairs, method)
    }
}
