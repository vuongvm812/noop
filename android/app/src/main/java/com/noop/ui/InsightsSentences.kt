package com.noop.ui

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import com.noop.R
import com.noop.analytics.ActivityCost
import com.noop.analytics.ActivityCostEngine
import com.noop.analytics.BehaviorEffect
import com.noop.analytics.DoseResponse
import com.noop.analytics.EffectRanker
import com.noop.analytics.RankedEffect
import kotlin.math.abs

// InsightsSentences.kt - view-layer renderer for the Insights engines' plain-English sentences.
//
// `EffectRanker.sentence`, `ActivityCost.sentence()`, `RankedEffect.sentence()` and
// `DoseResponse.sentence()` return fixed English that parity/golden tests pin, so the engines stay as
// they are. The screens render these sentences from the engines' structured fields instead, through
// localized whole-sentence templates (one resource per sentence shape, so word order can change per
// language). With the English resources each renderer returns exactly what its engine returns
// (InsightsSentencesTest asserts it). Swift twin: `InsightsSentences.swift`.

/** Where the renderer resolves its text; the production source is the app resources, tests supply English. */
internal interface InsightsText {
    fun string(@StringRes id: Int, vararg args: Any): String
    fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): String
}

internal object UiInsightsText : InsightsText {
    override fun string(@StringRes id: Int, vararg args: Any): String = uiString(id, *args)
    override fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): String = uiPlural(id, count, *args)
}

internal object InsightsSentences {

    /** An engine outcome label ("Charge" / "Rest" / "Resting HR") as the user's language names it; any other
     *  label (HRV, or one already localized by the caller) passes through unchanged. */
    fun displayOutcome(name: String, t: InsightsText = UiInsightsText): String = when (name) {
        "Charge" -> t.string(R.string.trends_charge)
        "Rest" -> t.string(R.string.trends_rest)
        "Resting HR" -> t.string(R.string.l10n_health_screen_resting_hr_26677094)
        else -> name
    }

    /** Cohen's d to its conventional magnitude word. */
    fun magnitudeWord(d: Double, t: InsightsText = UiInsightsText): String {
        val m = abs(d)
        return when {
            m < 0.2 -> t.string(R.string.l10n_insights_sentences_negligible_89891430)
            m < 0.5 -> t.string(R.string.l10n_insights_sentences_small_89f6229a)
            m < 0.8 -> t.string(R.string.l10n_insights_sentences_moderate_78cd1257)
            else -> t.string(R.string.l10n_insights_sentences_large_5296d5cc)
        }
    }

    /** `EffectRanker.sentence`. */
    fun effect(e: BehaviorEffect, t: InsightsText = UiInsightsText): String {
        val avgWith = EffectRanker.roundedInt(e.meanWith)
        val avgWithout = EffectRanker.roundedInt(e.meanWithout)
        if (e.delta == 0.0 || e.delta.isNaN()) {
            return t.string(R.string.l10n_insights_sentences_on_days_you_logged_1_s_f44ec478, e.behavior, e.outcome, avgWith, avgWithout, e.nWith, e.nWithout)
        }
        val higher = e.delta > 0
        val pct = e.pctChange
        if (pct != null) {
            val p = EffectRanker.roundedInt(abs(pct))
            return t.string(
                if (higher) R.string.l10n_insights_sentences_on_days_you_logged_1_s_94099d2a else R.string.l10n_insights_sentences_on_days_you_logged_1_s_f301678a,
                e.behavior, e.outcome, p, avgWith, avgWithout, e.nWith, e.nWithout,
            )
        }
        val mag = EffectRanker.round1(abs(e.delta)).toString()
        return t.string(
            if (higher) R.string.l10n_insights_sentences_on_days_you_logged_1_s_2a7f00be else R.string.l10n_insights_sentences_on_days_you_logged_1_s_6ffcd121,
            e.behavior, e.outcome, mag, avgWith, avgWithout, e.nWith, e.nWithout,
        )
    }

    /** `ActivityCost.sentence()`. */
    fun activityCost(c: ActivityCost, t: InsightsText = UiInsightsText): String {
        val mag = abs(c.delta)
        val points = ActivityCostEngine.roundToIntHalfUp(mag)
        if (mag < ActivityCostEngine.barelyMovesPoints) {
            return t.string(R.string.l10n_insights_sentences_sessions_like_this_barely_move_your_e3416b3d, c.n)
        }
        val pointsPhrase = t.plural(R.plurals.l10n_insights_sentences_1_d_charge_points_d0308b78, points, points)
        val costs = c.delta >= 0
        val days = c.daysToBaseline
        if (days != null) {
            val daysPhrase = t.plural(R.plurals.l10n_insights_sentences_1_d_days_8808cc6b, days, days)
            return t.string(if (costs) R.string.l10n_insights_sentences_sessions_like_this_usually_cost_you_3716a75b else R.string.l10n_insights_sentences_sessions_like_this_usually_lift_about_31dee263, pointsPhrase, daysPhrase, c.n)
        }
        return t.string(if (costs) R.string.l10n_insights_sentences_sessions_like_this_usually_cost_you_b8166ca8 else R.string.l10n_insights_sentences_sessions_like_this_usually_lift_about_a6a1eb7a, pointsPhrase, c.n)
    }

    /** The lead/lag chip text ("same day" / "next morning" / "N mornings later"). */
    fun leadLag(lag: Int, t: InsightsText = UiInsightsText): String = when (lag) {
        0 -> t.string(R.string.l10n_insights_sentences_same_day_9632d983)
        1 -> t.string(R.string.l10n_insights_sentences_next_morning_c080b71b)
        else -> t.string(R.string.l10n_insights_sentences_1_d_mornings_later_77089720, lag)
    }

    /** `RankedEffect.sentence()`. */
    fun ranked(r: RankedEffect, t: InsightsText = UiInsightsText): String {
        val base = effect(r.effect, t)
        val trimmed = if (base.endsWith(".")) base.dropLast(1) else base
        return t.string(R.string.l10n_insights_sentences_1_s_2_s_d5dfba96, trimmed, leadLag(r.lag, t))
    }

    /** `DoseResponse.sentence()`. */
    fun dose(r: DoseResponse, t: InsightsText = UiInsightsText): String {
        val mag = EffectRanker.round1(abs(r.perUnit)).toString()
        val lower = r.perUnit <= 0
        val outcome = displayOutcome(r.outcome, t)
        return when {
            r.priorDominated ->
                t.string(if (lower) R.string.l10n_insights_sentences_each_extra_unit_typically_lines_up_3bb2707a else R.string.l10n_insights_sentences_each_extra_unit_typically_lines_up_12bb55d1, mag, outcome, r.nUser)
            r.contradictsPrior -> t.string(R.string.l10n_insights_sentences_in_your_data_so_far_this_12b51244, outcome, r.nUser)
            else -> t.string(if (lower) R.string.l10n_insights_sentences_each_extra_unit_tends_to_line_ebce1154 else R.string.l10n_insights_sentences_each_extra_unit_tends_to_line_5e59158b, mag, outcome, r.nUser)
        }
    }
}
