package com.noop.ui

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import com.noop.R
import com.noop.analytics.BalanceRead
import com.noop.analytics.WeeklyDigest
import com.noop.analytics.WeeklyFocal
import com.noop.analytics.WeeklyRollup

/** Resource lookup the digest renderer needs; production resolves through the app locale, tests swap in English. */
interface WeeklyDigestStrings {
    fun string(@StringRes id: Int, vararg args: Any): String
    fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): String
}

/** Production lookup: the process's localized resources. */
object UiWeeklyDigestStrings : WeeklyDigestStrings {
    override fun string(id: Int, vararg args: Any): String = uiString(id, *args)
    override fun plural(id: Int, count: Int, vararg args: Any): String = uiPlural(id, count, *args)
}

/**
 * Rebuilds the digest's user-visible sentences from structured data ([WeeklyFocal],
 * [BalanceRead]) with localized templates. The engine's English strings stay untouched (they are
 * pinned by golden tests); in English this renderer produces the same sentences, except that metric names use
 * the card's display names (e.g. "SpO₂", "Skin temperature", "VO₂max" where the engine says "Overnight SpO2",
 * "Skin temp deviation", "VO2 max"). Swift twin:
 * `WeeklyDigestText`.
 */
object WeeklyDigestText {

    /** The digest's focal sentences in the user's language, same order as `digest.focalPoints`. */
    fun focalLines(digest: WeeklyDigest, strings: WeeklyDigestStrings = UiWeeklyDigestStrings): List<String> =
        if (digest.focalDetails.isEmpty()) digest.focalPoints
        else digest.focalDetails.map { focal(it, strings) }

    /** The notification body: the top focal sentence, null for an empty week. */
    fun notificationBody(digest: WeeklyDigest, strings: WeeklyDigestStrings = UiWeeklyDigestStrings): String? =
        if (digest.isEmpty) null else focalLines(digest, strings).firstOrNull()

    fun balance(read: BalanceRead, strings: WeeklyDigestStrings = UiWeeklyDigestStrings): String =
        strings.string(
            when (read) {
                BalanceRead.OVERREACHING -> R.string.l10n_weekly_digest_text_your_effort_outpaced_your_charge_this_11c8d229
                BalanceRead.BALANCED -> R.string.l10n_weekly_digest_text_effort_and_charge_tracked_together_this_02ac03be
                BalanceRead.UNDERLOADED -> R.string.l10n_weekly_digest_text_you_carried_more_charge_than_you_08848cfb
                BalanceRead.INSUFFICIENT -> R.string.l10n_weekly_digest_text_not_enough_effort_and_charge_days_6e55b2ca
            },
        )

    fun focal(f: WeeklyFocal, strings: WeeklyDigestStrings = UiWeeklyDigestStrings): String = when (f) {
        is WeeklyFocal.Mover -> mover(f, strings)
        is WeeklyFocal.Balance -> balance(f.read, strings)
        is WeeklyFocal.EarlyWeek ->
            strings.plural(R.plurals.l10n_weekly_digest_text_only_1_d_day_into_this_6e0a1f13, f.days, f.days)
        is WeeklyFocal.SparseLastWeek ->
            strings.plural(R.plurals.l10n_weekly_digest_text_last_week_only_had_1_d_dec3760e, f.days, f.days)
        is WeeklyFocal.SteadyRest ->
            strings.string(R.string.l10n_weekly_digest_text_a_steady_week_rest_held_even_f23438c2, f.sd)
        WeeklyFocal.Steady ->
            strings.string(R.string.l10n_weekly_digest_text_a_steady_week_no_metric_moved_8d343fef)
    }

    private fun mover(f: WeeklyFocal.Mover, strings: WeeklyDigestStrings): String {
        val dir = strings.string(
            when {
                f.delta > 0 -> R.string.l10n_weekly_digest_text_up_7c0a25c0
                f.delta < 0 -> R.string.l10n_weekly_digest_text_down_77346d04
                else -> R.string.l10n_weekly_digest_text_flat_b9c7b42d
            },
        )
        val magnitude = when (val m = f.magnitude) {
            is WeeklyFocal.Magnitude.Percent -> strings.string(R.string.l10n_weekly_digest_text_1_d_c6b1826f, m.value)
            is WeeklyFocal.Magnitude.Points -> strings.string(R.string.l10n_today_screen_signed_pts_5ea85678, m.value)
            is WeeklyFocal.Magnitude.Unit -> strings.string(R.string.l10n_weekly_digest_text_1_s_2_s_78c505fe, m.value, m.unit)
        }
        val word = strings.string(
            when (f.rollup) {
                WeeklyRollup.MEAN -> R.string.l10n_weekly_digest_text_avg_97e77871
                WeeklyRollup.SUM -> R.string.l10n_weekly_digest_text_total_5a537e20
                WeeklyRollup.LATEST -> R.string.l10n_weekly_digest_text_latest_1481d0a0
            },
        )
        val frame = when (f.goodness) {
            1 -> strings.string(R.string.l10n_weekly_digest_text_a_good_sign_c810c570)
            -1 -> strings.string(R.string.l10n_weekly_digest_text_worth_a_look_4073e6e5)
            else -> ""
        }
        return strings.string(
            R.string.l10n_weekly_digest_text_1_s_is_2_s_3_1ef91d1b,
            strings.string(f.metric.labelRes()), dir, magnitude, word, f.thisValue, f.lastValue, frame,
        )
    }
}
