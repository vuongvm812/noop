import Foundation

// CorrelationEngine.swift — relationships between two daily series.
//
// Pure, deterministic, DB-free. Computes the Pearson product-moment correlation r,
// a simple ordinary-least-squares regression line (slope/intercept of y on x), and
// a two-sided p-value for r.
//
//   r = Σ(x−x̄)(y−ȳ) / sqrt( Σ(x−x̄)² · Σ(y−ȳ)² )         (Pearson)
//   slope     = Σ(x−x̄)(y−ȳ) / Σ(x−x̄)²                     (OLS, y on x)
//   intercept = ȳ − slope·x̄
//
// The p-value uses the t-statistic for a correlation,
//   t = r · sqrt( (n_eff−2) / (1−r²) ),
// with a Student-t tail on n_eff − 2 degrees of freedom (StatTests). n_eff corrects n for the lag-1
// autocorrelation of both daily series (Bretherton et al. 1999), since consecutive days are not
// independent draws. Below `minPairs` pairs no inference is offered: p is 1.0, though r and the
// regression line are still returned for callers that only need a slope.
//
// `spearman` is the rank-based option: Pearson on average-tie ranks, robust to an outlier night or an
// imported step change that a raw Pearson would read as a strong relationship.
//
// `alignByDay` inner-joins two "yyyy-MM-dd"-keyed series on the day key, returning
// (x, y) pairs sorted by day. `lagged` shifts y forward by `lagDays` relative to x
// (x on day D paired with y on day D+lag) and correlates the result, which lets a
// caller probe directional / delayed effects (e.g. today's strain vs tomorrow's
// recovery).

/// The result of correlating two aligned series.
public struct Correlation: Equatable, Sendable {
    /// Pearson correlation coefficient in [-1, 1].
    public let r: Double
    /// Number of paired observations used.
    public let n: Int
    /// Two-sided p-value for H0: r = 0 (Student-t on the autocorrelation-corrected n; 1.0 below
    /// `CorrelationEngine.minPairs`).
    public let pApprox: Double
    /// OLS slope of y on x (on ranks for Spearman).
    public let slope: Double
    /// OLS intercept of y on x (on ranks for Spearman).
    public let intercept: Double
    /// Sample size after the lag-1 autocorrelation correction (≤ n).
    public let nEffective: Double
    /// Which coefficient `r` holds.
    public let method: CorrelationEngine.Method

    public init(r: Double, n: Int, pApprox: Double, slope: Double, intercept: Double,
                nEffective: Double? = nil, method: CorrelationEngine.Method = .pearson) {
        self.r = r
        self.n = n
        self.pApprox = pApprox
        self.slope = slope
        self.intercept = intercept
        self.nEffective = nEffective ?? Double(n)
        self.method = method
    }
}

public enum CorrelationEngine {

    /// Correlation coefficient to compute.
    public enum Method: String, Equatable, Sendable {
        case pearson
        case spearman
    }

    /// Minimum paired days before a correlation is shown or tested. Below this p is 1.0.
    public static let minPairs: Int = 10

    // MARK: - Pearson / Spearman

    /// `pearson` or `spearman` over the pairs, in the order given (day order for the p-value's
    /// autocorrelation correction).
    /// Kotlin twin: `CorrelationEngine.correlate`.
    public static func correlate(_ xy: [(Double, Double)], method: Method) -> Correlation? {
        switch method {
        case .pearson: return pearson(xy)
        case .spearman: return spearman(xy)
        }
    }

    /// Spearman's rho: Pearson over average-tie ranks. The autocorrelation correction is taken on the
    /// ranks too. Returns nil under the same conditions as `pearson`.
    /// Kotlin twin: `CorrelationEngine.spearman`.
    public static func spearman(_ xy: [(Double, Double)]) -> Correlation? {
        let rx = StatTests.averageRanks(xy.map(\.0))
        let ry = StatTests.averageRanks(xy.map(\.1))
        guard let c = pearson(Array(zip(rx, ry))) else { return nil }
        return Correlation(r: c.r, n: c.n, pApprox: c.pApprox, slope: c.slope,
                           intercept: c.intercept, nEffective: c.nEffective, method: .spearman)
    }

    /// Pearson r plus an OLS regression line and p-value for the pairs.
    /// Returns nil when fewer than 3 pairs, or when either variable has zero
    /// variance (r undefined).
    public static func pearson(_ xy: [(Double, Double)]) -> Correlation? {
        let n = xy.count
        guard n >= 3 else { return nil }
        let nD = Double(n)

        var sumX = 0.0, sumY = 0.0
        for p in xy { sumX += p.0; sumY += p.1 }
        let meanX = sumX / nD
        let meanY = sumY / nD

        var sxx = 0.0, syy = 0.0, sxy = 0.0
        for p in xy {
            let dx = p.0 - meanX
            let dy = p.1 - meanY
            sxx += dx * dx
            syy += dy * dy
            sxy += dx * dy
        }

        // Zero variance in either variable → correlation undefined.
        guard sxx > 0 && syy > 0 else { return nil }

        var r = sxy / (sxx.squareRoot() * syy.squareRoot())
        // Clamp tiny floating-point overshoot so |r| ≤ 1 exactly.
        if r > 1.0 { r = 1.0 }
        if r < -1.0 { r = -1.0 }

        let slope = sxy / sxx
        let intercept = meanY - slope * meanX
        let nEff = StatTests.effectiveN(n: n,
                                        rx: StatTests.lag1Autocorrelation(xy.map(\.0)),
                                        ry: StatTests.lag1Autocorrelation(xy.map(\.1)))
        let p = n < minPairs ? 1.0 : StatTests.correlationP(r: r, n: nEff)

        return Correlation(r: r, n: n, pApprox: p, slope: slope, intercept: intercept,
                           nEffective: nEff)
    }

    // MARK: - Day alignment

    /// Inner-join two "yyyy-MM-dd"-keyed series on the day key, returning the (x, y)
    /// value pairs for days present in BOTH, sorted ascending by day. Later entries
    /// for a duplicated day in either series win (last-write).
    public static func alignByDay(_ a: [(day: String, value: Double)],
                                  _ b: [(day: String, value: Double)]) -> [(Double, Double)] {
        var mapA: [String: Double] = [:]
        for row in a { mapA[row.day] = row.value }
        var mapB: [String: Double] = [:]
        for row in b { mapB[row.day] = row.value }

        let commonDays = mapA.keys.filter { mapB[$0] != nil }.sorted()
        return commonDays.map { (mapA[$0]!, mapB[$0]!) }
    }

    // MARK: - Lagged correlation

    /// Correlate x[day] against y[day + lagDays]. A positive lag asks "does x today
    /// predict y `lagDays` later?"; a negative lag looks backward. Days are matched
    /// on the calendar by offsetting x's day key by `lagDays` and joining to y.
    ///
    /// Returns nil when fewer than 3 lag-matched pairs survive or when `pearson`
    /// rejects them (zero variance).
    /// Kotlin twin: `CorrelationEngine.lagged`.
    public static func lagged(x: [(day: String, value: Double)],
                              y: [(day: String, value: Double)],
                              lagDays: Int,
                              method: Method = .pearson) -> Correlation? {
        var mapY: [String: Double] = [:]
        for row in y { mapY[row.day] = row.value }

        var pairs: [(Double, Double)] = []
        // Sort x by day for deterministic ordering of the pair list.
        let sortedX = x.sorted { $0.day < $1.day }
        for row in sortedX {
            guard let shifted = shiftDay(row.day, by: lagDays) else { continue }
            if let yv = mapY[shifted] {
                pairs.append((row.value, yv))
            }
        }
        return correlate(pairs, method: method)
    }

    // MARK: - p-value

    /// Standard-normal CDF Φ(z) using the A&S 7.1.26 erf approximation.
    static func normalCDF(_ z: Double) -> Double {
        0.5 * (1.0 + erfApprox(z / 2.0.squareRoot()))
    }

    /// erf(x) — Abramowitz & Stegun 7.1.26, |error| ≤ 1.5e-7.
    static func erfApprox(_ x: Double) -> Double {
        let sign = x < 0 ? -1.0 : 1.0
        let ax = abs(x)
        let t = 1.0 / (1.0 + 0.3275911 * ax)
        let y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t
                        - 0.284496736) * t + 0.254829592) * t * exp(-ax * ax)
        return sign * y
    }

    // MARK: - Day arithmetic

    /// Shift a "yyyy-MM-dd" day string by `delta` days (can be negative), returning
    /// a normalised "yyyy-MM-dd" string. Uses a fixed UTC calendar so it is
    /// deterministic and timezone-free. Returns nil if the input can't be parsed.
    static func shiftDay(_ day: String, by delta: Int) -> String? {
        if delta == 0 { return day }
        let parts = day.split(separator: "-", omittingEmptySubsequences: false)
        guard parts.count == 3,
              let y = Int(parts[0]), let m = Int(parts[1]), let d = Int(parts[2]),
              (1...12).contains(m), d >= 1 else { return nil }

        var comps = DateComponents()
        comps.year = y; comps.month = m; comps.day = d
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "UTC")!
        guard let base = cal.date(from: comps),
              let shifted = cal.date(byAdding: .day, value: delta, to: base) else { return nil }
        let out = cal.dateComponents([.year, .month, .day], from: shifted)
        guard let oy = out.year, let om = out.month, let od = out.day else { return nil }
        return String(format: "%04d-%02d-%02d", oy, om, od)
    }
}
