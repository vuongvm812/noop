package com.noop.analytics

import kotlin.math.abs

/*
 * ForecastAccuracy.kt — how close the evening Charge forecasts came.
 *
 * Kotlin mirror of StrandAnalytics/ForecastAccuracy.swift; keep the keys, window, gate and arithmetic
 * byte-identical. Each evening the forecast for the next morning is stored (charge_forecast, _low, _high
 * under the computed source, keyed by the day it targets) and frozen once that night's sleep starts. When
 * the morning's real Charge is scored, the pair is scored too. Over the last [ForecastAccuracy.window]
 * scored days this reports the mean absolute error and how many real values landed inside the band.
 * Nothing is reported until [ForecastAccuracy.minPairs] days are scored.
 */
object ForecastAccuracy {

    /** Stored metric keys (computed source, keyed by the forecast's target day). */
    const val forecastKey = "charge_forecast"
    const val lowKey = "charge_forecast_low"
    const val highKey = "charge_forecast_high"

    /** Most recent scored days the summary covers. */
    const val window: Int = 14

    /** Scored days needed before any summary is shown. */
    const val minPairs: Int = 5

    /** One forecast and the Charge that was then scored for its day. Swift twin: `ForecastAccuracy.Pair`. */
    data class Pair(
        val day: String,
        val forecast: Double,
        val low: Double,
        val high: Double,
        val actual: Double,
    )

    /** Swift twin: `ForecastAccuracy.Summary`. */
    data class Summary(
        /** Scored days the summary covers (minPairs…window). */
        val scored: Int,
        /** Mean |actual − forecast| in Charge points. */
        val meanAbsError: Double,
        /** Days whose actual Charge fell inside [low, high]. */
        val inside: Int,
    )

    /**
     * Join stored forecasts with scored Charge by day. A day needs all three forecast values and a real
     * Charge, all finite; the result is sorted by day. Swift twin: `ForecastAccuracy.pairs`.
     */
    fun pairs(
        forecast: Map<String, Double>,
        low: Map<String, Double>,
        high: Map<String, Double>,
        actual: Map<String, Double>,
    ): List<Pair> {
        val out = ArrayList<Pair>()
        for (day in forecast.keys.sorted()) {
            val f = forecast[day] ?: continue
            val l = low[day] ?: continue
            val h = high[day] ?: continue
            val a = actual[day] ?: continue
            if (!f.isFinite() || !l.isFinite() || !h.isFinite() || !a.isFinite()) continue
            out.add(Pair(day, f, l, h, a))
        }
        return out
    }

    /** Mean absolute error and inside-band count over the last [window] pairs (by day); null below
     *  [minPairs]. Swift twin: `ForecastAccuracy.summary`. */
    fun summary(pairs: List<Pair>): Summary? {
        val recent = pairs.sortedBy { it.day }.takeLast(window)
        if (recent.size < minPairs) return null
        var errSum = 0.0
        var inside = 0
        for (p in recent) {
            errSum += abs(p.actual - p.forecast)
            if (p.actual >= p.low && p.actual <= p.high) inside += 1
        }
        return Summary(recent.size, errSum / recent.size, inside)
    }
}
