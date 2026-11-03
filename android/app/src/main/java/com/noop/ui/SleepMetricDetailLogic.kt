package com.noop.ui

import androidx.compose.ui.graphics.Color
import com.noop.analytics.RestScorer
import com.noop.analytics.SleepDebt
import com.noop.data.DailyMetric
import java.time.LocalDate
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

internal enum class SleepMetricRange(val label: String, val days: Long?) {
    WEEK("W", 7), MONTH("M", 30), THREE_MONTH("3M", 90),
    SIX_MONTH("6M", 180), YEAR("1Y", 365), ALL("ALL", null),
}

internal data class SleepMetricSpec(
    val title: String,
    val unit: String,
    val color: Color,
    val format: (Double) -> String,
)

internal fun sleepMetricSpec(key: String): SleepMetricSpec = when (key) {
    "performance"     -> SleepMetricSpec("Rest", "%", Palette.restColor) { "${it.roundToInt()}" }
    "efficiency"      -> SleepMetricSpec("Sleep Efficiency", "%", Palette.statusPositive) { "${it.roundToInt()}" }
    "consistency"     -> SleepMetricSpec("Consistency", "%", Palette.metricCyan) { "${it.roundToInt()}" }
    "hours_vs_needed" -> SleepMetricSpec("Hours vs Needed", "%", Palette.restColor) { "${it.roundToInt()}" }
    "restorative"     -> SleepMetricSpec("Restorative", "%", Palette.sleepREM) { "${it.roundToInt()}" }
    "respiratory"     -> SleepMetricSpec("Respiratory Rate", "rpm", Palette.metricPurple) { String.format(Locale.US, "%.1f", it) }
    "sleep_debt"      -> SleepMetricSpec("Sleep Debt", "min", Palette.metricRose) { "${it.roundToInt()}" }   // #691: minutes, not decimal hours
    else              -> SleepMetricSpec(key, "", Palette.accent) { "${it.roundToInt()}" }
}

internal fun buildSleepMetricPoints(
    days: List<DailyMetric>,
    key: String,
    imported: ImportedSleepSeries = ImportedSleepSeries(),
    napSleepMinByDay: Map<String, Double> = emptyMap(),
    // #55: the engine's persisted per-night figures; resolved exactly as the Sleep tab tiles do.
    computed: ImportedSleepSeries = ImportedSleepSeries(),
): List<Pair<String, Double>> {
    if (key == "sleep_debt") {
        val debtNeedMin = RestScorer.personalizedNeedHours(
            days.mapNotNull { it.totalSleepMin?.let { minutes -> minutes / 60.0 } }, null,
        ) * 60.0
        return SleepDebt.debtSeries(
            series = days.map { day ->
                day.day to SleepDebt.creditedSleepMin(
                    day.totalSleepMin,
                    napSleepMinByDay[day.day] ?: 0.0,
                )
            },
            needHours = debtNeedMin / 60.0,
            importedDebtMin = imported.debtMin + computed.debtMin.filterKeys { it !in imported.debtMin },
        )
    }
    return days.mapNotNull { d ->
        val v: Double? = when (key) {
            // #55: every series resolves per day exactly as its Sleep tab tile does — the imported WHOOP
            // figure, else the engine's persisted value (scored against the night's sleep context), else
            // (an unscored night) the cold-start Rest / default need. Consistency is the Sleep Regularity
            // Index; there is no local re-derivation of it any more.
            "performance" -> (imported.performance[d.day] ?: computed.performance[d.day]
                ?: RestScorer.restFromDaily(d))?.takeIf { it in 0.0..100.0 }
            "efficiency"  -> d.efficiency?.let { if (it <= 1.0) it * 100.0 else it }
            "consistency" -> imported.consistency[d.day] ?: computed.consistency[d.day]
            "hours_vs_needed" -> {
                val need = imported.needMin[d.day] ?: computed.needMin[d.day] ?: RestScorer.defaultSleepNeedHours * 60.0
                d.totalSleepMin?.takeIf { it > 0.0 && need > 0.0 }?.let { it / need * 100.0 }
            }
            "restorative" -> {
                val dp = d.deepMin ?: return@mapNotNull null
                val rm = d.remMin ?: return@mapNotNull null
                val sl = d.totalSleepMin ?: return@mapNotNull null
                if (sl > 0.0) (dp + rm) / sl * 100.0 else null
            }
            "respiratory" -> d.respRateBpm
            else          -> null
        }
        v?.takeIf { it.isFinite() }?.let { d.day to it }
    }
}

internal fun filterSleepMetricPoints(
    points: List<Pair<String, Double>>,
    range: SleepMetricRange,
): List<Pair<String, Double>> {
    val windowDays = range.days ?: return points
    val latestDate = points.lastOrNull()?.first?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        ?: return points.takeLast(windowDays.toInt())
    val cutoff = latestDate.minusDays(windowDays - 1)
    val filtered = points.filter { (day, _) ->
        runCatching { LocalDate.parse(day) }.getOrNull()?.let { !it.isBefore(cutoff) } ?: false
    }
    return filtered.ifEmpty { points.takeLast(windowDays.toInt()) }
}
