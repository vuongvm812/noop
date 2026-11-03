import Foundation

// StatTests.swift — the small set of inferential primitives the correlation and behaviour-effect
// feeds share. Pure, deterministic, DB-free, and kept line-for-line with the Kotlin twin
// (`com.noop.analytics.StatTests`), which pins this file's output as an oracle.
//
//   • Student-t two-sided tail via the regularized incomplete beta function:
//       p = I_{df/(df+t²)}(df/2, 1/2)
//     The previous normal tail understated p at small n (r = 0.8, n = 5 read p ≈ 0.02; the t(3) tail is
//     ≈ 0.10). The incomplete beta uses the Numerical Recipes continued fraction (modified Lentz) and a
//     Lanczos ln Γ, both written out here so Swift and Kotlin evaluate the same arithmetic.
//   • Welch–Satterthwaite degrees of freedom for the unequal-variance two-sample test.
//   • Benjamini–Hochberg step-up adjustment (false-discovery rate) across a feed's family of tests.
//   • Average-tie ranks for Spearman's rho.
//   • Lag-1 autocorrelation and the effective sample size for two autocorrelated daily series
//     (Bretherton et al. 1999): n_eff = n · (1 − ρx·ρy) / (1 + ρx·ρy). Only ever shrinks n.
public enum StatTests {

    // MARK: - Student-t

    /// Two-sided p-value for a t statistic with `df` degrees of freedom (df may be fractional, as
    /// Welch and effective-n produce). Returns 1.0 when df ≤ 0 or t is NaN, 0.0 for an infinite t.
    /// Kotlin twin: `StatTests.studentTTwoSidedP`.
    public static func studentTTwoSidedP(t: Double, df: Double) -> Double {
        guard df > 0, !t.isNaN else { return 1.0 }
        if t.isInfinite { return 0.0 }
        let x = df / (df + t * t)
        let p = regularizedIncompleteBeta(a: df / 2.0, b: 0.5, x: x)
        return Swift.min(1.0, Swift.max(0.0, p))
    }

    /// Two-sided p-value for H0: ρ = 0 given a correlation `r` over an (effective) sample of `n`,
    /// via t = r·sqrt((n−2)/(1−r²)) on n − 2 degrees of freedom. n ≤ 2 → 1.0; |r| = 1 → 0.0.
    /// Kotlin twin: `StatTests.correlationP`.
    public static func correlationP(r: Double, n: Double) -> Double {
        guard n > 2, !r.isNaN else { return 1.0 }
        let oneMinusR2 = 1.0 - r * r
        if oneMinusR2 <= 0 { return 0.0 }
        let df = n - 2.0
        let t = r * (df / oneMinusR2).squareRoot()
        return studentTTwoSidedP(t: t, df: df)
    }

    /// Two-sided Welch t-test p-value. Degrees of freedom by Welch–Satterthwaite; a group of one
    /// contributes no variance term. No spread anywhere: identical means → 1.0, differing → 0.0.
    /// Kotlin twin: `StatTests.welchP`.
    public static func welchP(m1: Double, v1: Double, n1: Int,
                              m2: Double, v2: Double, n2: Int) -> Double {
        guard n1 > 0, n2 > 0 else { return 1.0 }
        let a = v1 / Double(n1)
        let b = v2 / Double(n2)
        let se2 = a + b
        guard se2 > 0 else { return m1 == m2 ? 1.0 : 0.0 }
        let t = (m1 - m2) / se2.squareRoot()
        let da = n1 > 1 ? a * a / Double(n1 - 1) : 0.0
        let db = n2 > 1 ? b * b / Double(n2 - 1) : 0.0
        guard da + db > 0 else { return 1.0 }
        let df = se2 * se2 / (da + db)
        return studentTTwoSidedP(t: t, df: df)
    }

    // MARK: - Special functions

    /// ln Γ(x) for x > 0 — Lanczos (g = 7, n = 9), |relative error| < 1e-15 over the range used here.
    /// Kotlin twin: `StatTests.lnGamma`.
    static func lnGamma(_ x: Double) -> Double {
        if x < 0.5 {
            // Reflection: Γ(x)Γ(1−x) = π / sin(πx).
            return log(Double.pi / abs(sin(Double.pi * x))) - lnGamma(1.0 - x)
        }
        let lanczos: [Double] = [
            0.99999999999980993, 676.5203681218851, -1259.1392167224028,
            771.32342877765313, -176.61502916214059, 12.507343278686905,
            -0.13857109526572012, 9.9843695780195716e-6, 1.5056327351493116e-7,
        ]
        let z = x - 1.0
        var acc = lanczos[0]
        for i in 1..<lanczos.count { acc += lanczos[i] / (z + Double(i)) }
        let t = z + 7.5
        return 0.5 * log(2.0 * Double.pi) + (z + 0.5) * log(t) - t + log(acc)
    }

    /// Regularized incomplete beta I_x(a, b) for a, b > 0, x ∈ [0, 1].
    /// Kotlin twin: `StatTests.regularizedIncompleteBeta`.
    public static func regularizedIncompleteBeta(a: Double, b: Double, x: Double) -> Double {
        if x <= 0 { return 0.0 }
        if x >= 1 { return 1.0 }
        let lnFront = lnGamma(a + b) - lnGamma(a) - lnGamma(b) + a * log(x) + b * log(1.0 - x)
        let front = exp(lnFront)
        if x < (a + 1.0) / (a + b + 2.0) {
            return front * betaContinuedFraction(a: a, b: b, x: x) / a
        }
        return 1.0 - front * betaContinuedFraction(a: b, b: a, x: 1.0 - x) / b
    }

    /// Continued fraction for I_x(a, b) (Numerical Recipes `betacf`, modified Lentz).
    /// Kotlin twin: `StatTests.betaContinuedFraction`.
    static func betaContinuedFraction(a: Double, b: Double, x: Double) -> Double {
        let maxIterations = 300
        let eps = 1e-15
        let fpMin = 1e-300
        let qab = a + b, qap = a + 1.0, qam = a - 1.0
        var c = 1.0
        var d = 1.0 - qab * x / qap
        if abs(d) < fpMin { d = fpMin }
        d = 1.0 / d
        var h = d
        for m in 1...maxIterations {
            let md = Double(m)
            let m2 = 2.0 * md
            var aa = md * (b - md) * x / ((qam + m2) * (a + m2))
            d = 1.0 + aa * d
            if abs(d) < fpMin { d = fpMin }
            c = 1.0 + aa / c
            if abs(c) < fpMin { c = fpMin }
            d = 1.0 / d
            h *= d * c
            aa = -(a + md) * (qab + md) * x / ((a + m2) * (qap + m2))
            d = 1.0 + aa * d
            if abs(d) < fpMin { d = fpMin }
            c = 1.0 + aa / c
            if abs(c) < fpMin { c = fpMin }
            d = 1.0 / d
            let del = d * c
            h *= del
            if abs(del - 1.0) < eps { break }
        }
        return h
    }

    // MARK: - Multiple comparisons

    /// Benjamini–Hochberg adjusted p-values (q-values), returned in the INPUT order. A test is a
    /// discovery at FDR α iff its q < α. Ties keep input order (stable sort), so the result is
    /// deterministic across platforms.
    /// Kotlin twin: `StatTests.benjaminiHochberg`.
    public static func benjaminiHochberg(_ pValues: [Double]) -> [Double] {
        let m = pValues.count
        guard m > 0 else { return [] }
        let order = pValues.indices.sorted { i, j in
            pValues[i] != pValues[j] ? pValues[i] < pValues[j] : i < j
        }
        var q = [Double](repeating: 1.0, count: m)
        var running = 1.0
        for rank in stride(from: m - 1, through: 0, by: -1) {
            let i = order[rank]
            let adjusted = pValues[i] * Double(m) / Double(rank + 1)
            running = Swift.min(running, adjusted)
            q[i] = Swift.min(1.0, running)
        }
        return q
    }

    // MARK: - Ranks / autocorrelation

    /// 1-based ranks with ties sharing their average rank (the Spearman convention).
    /// Kotlin twin: `StatTests.averageRanks`.
    public static func averageRanks(_ xs: [Double]) -> [Double] {
        let n = xs.count
        let order = xs.indices.sorted { i, j in xs[i] != xs[j] ? xs[i] < xs[j] : i < j }
        var ranks = [Double](repeating: 0, count: n)
        var i = 0
        while i < n {
            var j = i
            while j + 1 < n && xs[order[j + 1]] == xs[order[i]] { j += 1 }
            let avg = Double(i + j + 2) / 2.0
            for k in i...j { ranks[order[k]] = avg }
            i = j + 1
        }
        return ranks
    }

    /// Lag-1 autocorrelation of an ordered series: Σ(x_t−x̄)(x_{t+1}−x̄) / Σ(x_t−x̄)². 0 for fewer than
    /// three values or a constant series.
    /// Kotlin twin: `StatTests.lag1Autocorrelation`.
    public static func lag1Autocorrelation(_ xs: [Double]) -> Double {
        let n = xs.count
        guard n >= 3 else { return 0.0 }
        var sum = 0.0
        for v in xs { sum += v }
        let mean = sum / Double(n)
        var den = 0.0
        for v in xs { den += (v - mean) * (v - mean) }
        guard den > 0 else { return 0.0 }
        var num = 0.0
        for t in 0..<(n - 1) { num += (xs[t] - mean) * (xs[t + 1] - mean) }
        return num / den
    }

    /// Effective sample size for correlating two autocorrelated series of length `n` with lag-1
    /// autocorrelations `rx` and `ry` (Bretherton et al. 1999). A negative product (anti-persistent
    /// pair) is treated as independent, so the correction never inflates n.
    /// Kotlin twin: `StatTests.effectiveN`.
    public static func effectiveN(n: Int, rx: Double, ry: Double) -> Double {
        let product = Swift.max(0.0, rx * ry)
        guard product < 1.0 else { return 2.0 }
        let nEff = Double(n) * (1.0 - product) / (1.0 + product)
        return Swift.min(Double(n), nEff)
    }
}
