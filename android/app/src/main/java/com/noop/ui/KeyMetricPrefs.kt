package com.noop.ui

import android.content.Context
import androidx.annotation.StringRes
import com.noop.R

// MARK: - Editable Key-Metrics layout (#251)
//
// The Today screen's "Key Metrics" grid was a fixed list of ten tiles in one order. This lets the user
// choose WHICH tiles show and in WHAT order, with the default being the original order so nothing changes
// for anyone who never opens the editor. Persistence is display-only — no metric is computed or stored
// differently; this just decides which of the already-computed tiles render and in what sequence.
//
// Stored as a single comma-joined string of metric keys in SharedPreferences ("today.keyMetrics"), the
// same mechanism every other Android preference uses. Mirrors the macOS KeyMetricPrefs.swift +
// @AppStorage("today.keyMetrics"). Unknown keys are dropped on read so a removed tile can't crash, and
// any known key missing from the saved list is treated as disabled (the editor re-lists it).

/**
 * One of the Today screen's Key-Metric tiles. The [raw] is the stable persisted identifier — keep it
 * byte-identical to the macOS `KeyMetric` enum so a backup/restore reads the same layout on either OS.
 */
enum class KeyMetric(val raw: String, @StringRes val titleRes: Int) {
    CHARGE("charge", R.string.today_metric_charge),
    EFFORT("effort", R.string.today_metric_effort),
    REST("rest", R.string.today_metric_rest),
    HRV("hrv", R.string.today_metric_hrv),
    RESTING_HR("restingHr", R.string.today_metric_resting_hr),
    BLOOD_OXYGEN("bloodOxygen", R.string.today_metric_blood_oxygen),
    RESPIRATORY("respiratory", R.string.today_metric_respiratory),
    STEPS("steps", R.string.today_metric_steps),
    WEIGHT("weight", R.string.today_metric_weight),
    CALORIES("calories", R.string.today_metric_calories),
    // Added 2026-08-24 (queue 11c follow-up): Skin Temp was already a "Your Cards" (DashboardCard)
    // option, but was never offered as a Key Metrics tile — not a bug, just never added. Reuses
    // DashboardCard.SKIN_TEMP's own title resource rather than adding a new one. New case, NOT added
    // to defaultOrder below, so an existing user's saved layout (and a fresh install's default) is
    // byte-identical to before; only opts in via the layout editor.
    SKIN_TEMP("skinTemp", R.string.today_card_skin_temp),

    /** Today's stress (#57): the latest level from the one [com.noop.analytics.StressToday] resolver,
     *  Garmin's own 0-100 or NOOP's 0-3. Replaced the #42 Stress card under Heart Rate; tapping it opens
     *  the Stress screen. Reuses the old section's title resource. */
    STRESS("stress", R.string.today_section_stress),

    /** The latest VO₂max (#57), the same value the "Your Cards" VO₂max card shows. Taps through to its
     *  trend detail. Reuses that card's title resource. */
    VO2MAX("vo2max", R.string.today_card_vo2max);

    companion object {
        fun fromRaw(raw: String?): KeyMetric? = entries.firstOrNull { it.raw == raw }

        /** The default grid order when the layout isn't customised. Stress and VO₂max (#57) close it; a
         *  customised layout gains them once through [KeyMetricPrefs.runMigrations]. */
        val defaultOrder: List<KeyMetric> = listOf(
            CHARGE, EFFORT, REST, HRV, RESTING_HR,
            BLOOD_OXYGEN, RESPIRATORY, STEPS, WEIGHT, CALORIES, STRESS, VO2MAX,
        )
    }
}

/**
 * Display-only persistence for the Key-Metrics layout. Holds an ORDERED list of the enabled tiles; a tile
 * not in the list is hidden. SharedPreferences isn't reactive, so the Today screen reads this once into
 * remembered state (like the other prefs) and re-reads on the recomposition the editor's write triggers.
 * Mirrors the macOS KeyMetricPrefs (@AppStorage "today.keyMetrics").
 */
object KeyMetricPrefs {
    private const val KEY_LAYOUT = "today.keyMetrics"
    private const val KEY_DETAILED = "today.keyMetricsDetailed"

    /** Whether the Key-Metrics tiles render DETAILED — taller/squarer with a trend graph under the
     *  fill bar. Display-only, default off (the Today-only compact ktile look). Set by the editor;
     *  key name is parity-ready for the macOS/iOS twin (@AppStorage "today.keyMetricsDetailed"). */
    fun detailed(context: Context): Boolean =
        NoopPrefs.of(context).getBoolean(KEY_DETAILED, false)

    fun setDetailed(context: Context, value: Boolean) {
        NoopPrefs.of(context).edit().putBoolean(KEY_DETAILED, value).apply()
    }

    private const val KEY_WINDOW = "today.keyMetricsWindowDays"

    /** Trailing trend window (calendar days) the DETAILED tiles graph: 7, 14 (default) or 30. Shared key
     *  with the iOS twin; an unknown stored value coerces to 14 so a bad pref can't skew the window math
     *  (this also migrates users off the retired 2-day option). */
    fun detailWindowDays(context: Context): Int =
        NoopPrefs.of(context).getInt(KEY_WINDOW, 14).let { if (it == 7 || it == 14 || it == 30) it else 14 }

    fun setDetailWindowDays(context: Context, value: Int) {
        NoopPrefs.of(context).edit().putInt(KEY_WINDOW, value).apply()
    }

    /** The enabled tiles in display order. An empty/unset string yields the full default order. */
    fun enabled(context: Context): List<KeyMetric> =
        decodeEnabled(NoopPrefs.of(context).getString(KEY_LAYOUT, null))

    /** Persist the enabled tiles in order. Disabled tiles are simply omitted from the stored string. */
    fun setEnabled(context: Context, metrics: List<KeyMetric>) {
        NoopPrefs.of(context).edit().putString(KEY_LAYOUT, encode(metrics)).apply()
    }

    /** Encode an ordered list of enabled tiles into the stored comma-joined string. */
    fun encode(metrics: List<KeyMetric>): String = metrics.joinToString(",") { it.raw }

    /**
     * Decode the stored string into an ordered list of enabled tiles. An empty/unset string yields the
     * full default order (so a fresh install shows every tile). Unknown tokens are ignored, duplicates
     * collapsed; this returns ONLY the enabled tiles in their saved order.
     */
    fun decodeEnabled(raw: String?): List<KeyMetric> {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return KeyMetric.defaultOrder
        val seen = LinkedHashSet<KeyMetric>()
        trimmed.split(",").forEach { token ->
            KeyMetric.fromRaw(token.trim())?.let { seen.add(it) }
        }
        return if (seen.isEmpty()) KeyMetric.defaultOrder else seen.toList()
    }

    // MARK: - #57 migration: the Stress card becomes a tile, VO₂max joins

    /** Set once the #57 migration has run, so a tile the user later removes is not put back. */
    private const val KEY_MIGRATED_STRESS_VO2 = "today.keyMetrics.migratedStressVo2"

    /**
     * The Key Metrics string a CUSTOMISED layout should hold after #57, or null when nothing changes.
     *
     * An empty string is the default layout, which already lists both tiles. A customised one gains the
     * Stress tile unless the user had hidden the old Stress card (they chose not to see stress on Today, so
     * that choice carries over), and gains VO₂max. Twin of the Swift `KeyMetricPrefs.migratedForStressVo2`.
     */
    fun migratedForStressVo2(keyMetricsRaw: String?, hiddenSectionsRaw: String?): String? {
        val trimmed = keyMetricsRaw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        // Not decodeEnabled: that falls back to the default order for an all-unknown string, which would
        // turn a garbled saved value into a rewrite. Only known tokens count here, like the Swift decoder.
        val enabled = LinkedHashSet<KeyMetric>()
        trimmed.split(",").forEach { token -> KeyMetric.fromRaw(token.trim())?.let { enabled.add(it) } }
        if (enabled.isEmpty()) return null
        val stressCardHidden = hiddenSectionsRaw.orEmpty().split(",").any { it.trim() == "stress" }
        if (!stressCardHidden) enabled.add(KeyMetric.STRESS)
        enabled.add(KeyMetric.VO2MAX)
        val next = encode(enabled.toList())
        return if (next == trimmed) null else next
    }

    /**
     * Run the one-time #57 layout migration: extend a customised Key Metrics layout, then strip the
     * retired `stress` section from the saved Today order and hidden set. Idempotent; Today calls it
     * before reading the layout. Twin of the Swift `KeyMetricPrefs.runMigrations`.
     */
    fun runMigrations(context: Context) {
        val prefs = NoopPrefs.of(context)
        if (prefs.getBoolean(KEY_MIGRATED_STRESS_VO2, false)) return
        val edit = prefs.edit()
        val hidden = prefs.getString(TodayLayoutPrefs.KEY_HIDDEN, null)
        migratedForStressVo2(prefs.getString(KEY_LAYOUT, null), hidden)?.let { edit.putString(KEY_LAYOUT, it) }
        for (key in listOf(TodayLayoutPrefs.KEY_ORDER, TodayLayoutPrefs.KEY_HIDDEN)) {
            val raw = prefs.getString(key, null) ?: continue
            val clean = TodayLayoutPrefs.stripRetired(raw)
            if (clean != raw) edit.putString(key, clean)
        }
        edit.putBoolean(KEY_MIGRATED_STRESS_VO2, true).apply()
    }
}
