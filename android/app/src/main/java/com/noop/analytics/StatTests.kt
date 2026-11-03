package com.noop.analytics

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * StatTests — the inferential primitives the correlation and behaviour-effect feeds share. Pure and
 * deterministic; the line-for-line twin of Swift `StatTests` (StrandAnalytics), pinned by oracle in
 * `StatTestsTest`.
 *
 *  • Student-t two-sided tail via the regularized incomplete beta, p = I_{df/(df+t²)}(df/2, 1/2). The
 *    old normal tail understated p at small n (r = 0.8, n = 5 read p ≈ 0.02; t(3) gives ≈ 0.10).
 *  • Welch–Satterthwaite degrees of freedom.
 *  • Benjamini–Hochberg step-up adjustment across a feed's family of tests.
 *  • Average-tie ranks (Spearman), lag-1 autocorrelation, and the effective sample size for two
 *    autocorrelated daily series (Bretherton et al. 1999), which only ever shrinks n.
 */
object StatTests {

    /** Two-sided p for a t statistic on [df] (possibly fractional) degrees of freedom. */
    fun studentTTwoSidedP(t: Double, df: Double): Double {
        if (!(df > 0) || t.isNaN()) return 1.0
        if (t.isInfinite()) return 0.0
        val x = df / (df + t * t)
        val p = regularizedIncompleteBeta(df / 2.0, 0.5, x)
        return min(1.0, max(0.0, p))
    }

    /** Two-sided p for H0: ρ = 0 given r over an (effective) sample of [n]; n − 2 df. */
    fun correlationP(r: Double, n: Double): Double {
        if (!(n > 2) || r.isNaN()) return 1.0
        val oneMinusR2 = 1.0 - r * r
        if (oneMinusR2 <= 0) return 0.0
        val df = n - 2.0
        val t = r * sqrt(df / oneMinusR2)
        return studentTTwoSidedP(t, df)
    }

    /** Two-sided Welch t-test p (Welch–Satterthwaite df). No spread: equal means → 1, else 0. */
    fun welchP(m1: Double, v1: Double, n1: Int, m2: Double, v2: Double, n2: Int): Double {
        if (n1 <= 0 || n2 <= 0) return 1.0
        val a = v1 / n1.toDouble()
        val b = v2 / n2.toDouble()
        val se2 = a + b
        if (!(se2 > 0)) return if (m1 == m2) 1.0 else 0.0
        val t = (m1 - m2) / sqrt(se2)
        val da = if (n1 > 1) a * a / (n1 - 1).toDouble() else 0.0
        val db = if (n2 > 1) b * b / (n2 - 1).toDouble() else 0.0
        if (!(da + db > 0)) return 1.0
        val df = se2 * se2 / (da + db)
        return studentTTwoSidedP(t, df)
    }

    private val lanczos = doubleArrayOf(
        0.99999999999980993, 676.5203681218851, -1259.1392167224028,
        771.32342877765313, -176.61502916214059, 12.507343278686905,
        -0.13857109526572012, 9.9843695780195716e-6, 1.5056327351493116e-7,
    )

    /** ln Γ(x) for x > 0 — Lanczos (g = 7, n = 9). */
    internal fun lnGamma(x: Double): Double {
        if (x < 0.5) return ln(PI / abs(sin(PI * x))) - lnGamma(1.0 - x)
        val z = x - 1.0
        var acc = lanczos[0]
        for (i in 1 until lanczos.size) acc += lanczos[i] / (z + i.toDouble())
        val t = z + 7.5
        return 0.5 * ln(2.0 * PI) + (z + 0.5) * ln(t) - t + ln(acc)
    }

    /** Regularized incomplete beta I_x(a, b). */
    fun regularizedIncompleteBeta(a: Double, b: Double, x: Double): Double {
        if (x <= 0) return 0.0
        if (x >= 1) return 1.0
        val lnFront = lnGamma(a + b) - lnGamma(a) - lnGamma(b) + a * ln(x) + b * ln(1.0 - x)
        val front = exp(lnFront)
        if (x < (a + 1.0) / (a + b + 2.0)) return front * betaContinuedFraction(a, b, x) / a
        return 1.0 - front * betaContinuedFraction(b, a, 1.0 - x) / b
    }

    /** Numerical Recipes `betacf`, modified Lentz. */
    internal fun betaContinuedFraction(a: Double, b: Double, x: Double): Double {
        val maxIterations = 300
        val eps = 1e-15
        val fpMin = 1e-300
        val qab = a + b
        val qap = a + 1.0
        val qam = a - 1.0
        var c = 1.0
        var d = 1.0 - qab * x / qap
        if (abs(d) < fpMin) d = fpMin
        d = 1.0 / d
        var h = d
        for (m in 1..maxIterations) {
            val md = m.toDouble()
            val m2 = 2.0 * md
            var aa = md * (b - md) * x / ((qam + m2) * (a + m2))
            d = 1.0 + aa * d
            if (abs(d) < fpMin) d = fpMin
            c = 1.0 + aa / c
            if (abs(c) < fpMin) c = fpMin
            d = 1.0 / d
            h *= d * c
            aa = -(a + md) * (qab + md) * x / ((a + m2) * (qap + m2))
            d = 1.0 + aa * d
            if (abs(d) < fpMin) d = fpMin
            c = 1.0 + aa / c
            if (abs(c) < fpMin) c = fpMin
            d = 1.0 / d
            val del = d * c
            h *= del
            if (abs(del - 1.0) < eps) break
        }
        return h
    }

    /** Benjamini–Hochberg q-values in INPUT order; stable on ties. */
    fun benjaminiHochberg(pValues: List<Double>): List<Double> {
        val m = pValues.size
        if (m == 0) return emptyList()
        val order = pValues.indices.sortedWith(
            Comparator { i, j -> if (pValues[i] != pValues[j]) pValues[i].compareTo(pValues[j]) else i.compareTo(j) },
        )
        val q = DoubleArray(m) { 1.0 }
        var running = 1.0
        for (rank in m - 1 downTo 0) {
            val i = order[rank]
            val adjusted = pValues[i] * m.toDouble() / (rank + 1).toDouble()
            running = min(running, adjusted)
            q[i] = min(1.0, running)
        }
        return q.toList()
    }

    /** 1-based ranks; ties share their average rank. */
    fun averageRanks(xs: List<Double>): List<Double> {
        val n = xs.size
        val order = xs.indices.sortedWith(
            Comparator { i, j -> if (xs[i] != xs[j]) xs[i].compareTo(xs[j]) else i.compareTo(j) },
        )
        val ranks = DoubleArray(n)
        var i = 0
        while (i < n) {
            var j = i
            while (j + 1 < n && xs[order[j + 1]] == xs[order[i]]) j++
            val avg = (i + j + 2).toDouble() / 2.0
            for (k in i..j) ranks[order[k]] = avg
            i = j + 1
        }
        return ranks.toList()
    }

    /** Lag-1 autocorrelation of an ordered series; 0 for n < 3 or a constant series. */
    fun lag1Autocorrelation(xs: List<Double>): Double {
        val n = xs.size
        if (n < 3) return 0.0
        var sum = 0.0
        for (v in xs) sum += v
        val mean = sum / n.toDouble()
        var den = 0.0
        for (v in xs) den += (v - mean) * (v - mean)
        if (!(den > 0)) return 0.0
        var num = 0.0
        for (t in 0 until n - 1) num += (xs[t] - mean) * (xs[t + 1] - mean)
        return num / den
    }

    /** Effective n for two autocorrelated series (never above n; anti-persistence ignored). */
    fun effectiveN(n: Int, rx: Double, ry: Double): Double {
        val product = max(0.0, rx * ry)
        if (product >= 1.0) return 2.0
        val nEff = n.toDouble() * (1.0 - product) / (1.0 + product)
        return min(n.toDouble(), nEff)
    }
}
