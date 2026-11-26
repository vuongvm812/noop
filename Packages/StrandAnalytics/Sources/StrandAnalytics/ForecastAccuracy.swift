import Foundation

// ForecastAccuracy.swift — how close the evening Charge forecasts came.
//
// Each evening the forecast for the next morning is stored (`charge_forecast`, `_low`, `_high` under the
// computed source, keyed by the day it targets) and frozen once that night's sleep starts. When the morning's
// real Charge is scored, the pair is scored too. Over the last `window` scored days this reports the mean
// absolute error and how many real values landed inside the forecast's band. Nothing is reported until
// `minPairs` days are scored, so two lucky nights never read as a track record.

public enum ForecastAccuracy {

    /// Stored metric keys (computed source, keyed by the forecast's target day).
    public static let forecastKey = "charge_forecast"
    public static let lowKey = "charge_forecast_low"
    public static let highKey = "charge_forecast_high"

    /// Most recent scored days the summary covers.
    public static let window: Int = 14
    /// Scored days needed before any summary is shown.
    public static let minPairs: Int = 5

    /// One forecast and the Charge that was then scored for its day.
    public struct Pair: Equatable, Sendable {
        public let day: String
        public let forecast: Double
        public let low: Double
        public let high: Double
        public let actual: Double

        public init(day: String, forecast: Double, low: Double, high: Double, actual: Double) {
            self.day = day
            self.forecast = forecast
            self.low = low
            self.high = high
            self.actual = actual
        }
    }

    public struct Summary: Equatable, Sendable {
        /// Scored days the summary covers (minPairs…window).
        public let scored: Int
        /// Mean |actual − forecast| in Charge points.
        public let meanAbsError: Double
        /// Days whose actual Charge fell inside [low, high].
        public let inside: Int

        public init(scored: Int, meanAbsError: Double, inside: Int) {
            self.scored = scored
            self.meanAbsError = meanAbsError
            self.inside = inside
        }
    }

    /// Join stored forecasts with scored Charge by day. A day needs all three forecast values and a real
    /// Charge, all finite; the result is sorted by day. Kotlin twin: `ForecastAccuracy.pairs`.
    public static func pairs(forecast: [String: Double], low: [String: Double], high: [String: Double],
                             actual: [String: Double]) -> [Pair] {
        var out: [Pair] = []
        for day in forecast.keys.sorted() {
            guard let f = forecast[day], let l = low[day], let h = high[day], let a = actual[day],
                  f.isFinite, l.isFinite, h.isFinite, a.isFinite else { continue }
            out.append(Pair(day: day, forecast: f, low: l, high: h, actual: a))
        }
        return out
    }

    /// Mean absolute error and inside-band count over the last `window` pairs (by day); nil below
    /// `minPairs`. Kotlin twin: `ForecastAccuracy.summary`.
    public static func summary(_ pairs: [Pair]) -> Summary? {
        let recent = Array(pairs.sorted { $0.day < $1.day }.suffix(window))
        guard recent.count >= minPairs else { return nil }
        var errSum = 0.0
        var inside = 0
        for p in recent {
            errSum += abs(p.actual - p.forecast)
            if p.actual >= p.low && p.actual <= p.high { inside += 1 }
        }
        return Summary(scored: recent.count, meanAbsError: errSum / Double(recent.count), inside: inside)
    }
}
