package com.noop.analytics

// CoachMetricContext.kt — the AI Coach's "all metrics" context block and its per-category sharing set.
//
// Pure and database-free. The data layer ([com.noop.ai.AiCoach]) reads the metric snapshot values for the
// last 30 days ([com.noop.ui.MetricSnapshotLoader.dayValues]) and hands them here; this file decides which
// categories may leave the device, rolls them up with [MetricSnapshotBuilder] and prints a compact block:
// per category one 30-day line plus per-day lines for the last 14 days, trimmed to a character budget so a
// small local model's context window is not crowded out. Summary text only, never raw samples.
// Swift twin: `CoachMetricContext` (StrandAnalytics/CoachMetricContext.swift). Keep the two byte-identical.
object CoachMetricContext {

    /** Days that get their own per-day line (newest first). Swift twin: `CoachMetricContext.perDayWindow`. */
    const val PER_DAY_WINDOW = 14

    /**
     * Character budget of one block (about 1,800 tokens at ~4 chars/token). Per-day lines are dropped
     * whole, category by category, once the budget is spent; the 30-day lines and notes always stay.
     * Swift twin: `CoachMetricContext.maxChars`.
     */
    const val MAX_CHARS = 7000

    /** Notes (journal answers, lab readings) kept per category, newest. Swift twin: `CoachMetricContext.maxNotesPerCategory`. */
    const val MAX_NOTES_PER_CATEGORY = 12

    /**
     * The categories shared when the user has never touched the switches: exactly what the Coach has
     * always sent. Everything newer defaults OFF. Swift twin: `CoachMetricContext.defaultCategoryIds`.
     */
    fun defaultCategoryIds(): List<String> = listOf(
        MetricSnapshotCategory.RECOVERY_STRAIN.id,
        MetricSnapshotCategory.SLEEP.id,
        MetricSnapshotCategory.HEART.id,
        MetricSnapshotCategory.ACTIVITY.id,
        MetricSnapshotCategory.WORKOUTS.id,
    )

    /**
     * Metric ids the Coach's long-standing day table and 30-day averages already print, so the block
     * does not repeat them. Swift twin: `CoachMetricContext.coveredByDayTable`.
     */
    fun coveredByDayTable(): Set<String> = setOf(
        "recovery", "strain", "hrv", "rhr", "sleep_total_min", "sleep_deep_min", "sleep_rem_min",
        "sleep_light_min", "sleep_efficiency", "spo2", "resp_rate", "steps",
    )

    /**
     * The enabled category ids from the stored string: null/absent means the defaults, "" means none, and
     * unknown ids are dropped. Swift twin: `CoachMetricContext.parseCategories`.
     */
    fun parseCategories(stored: String?): Set<String> {
        if (stored == null) return defaultCategoryIds().toSet()
        val known = MetricSnapshotCategory.entries.map { it.id }.toSet()
        return stored.split(",").map { it.trim() }.filter { it in known }.toSet()
    }

    /** The stored string: enabled ids in category order, comma-joined. Swift twin: `CoachMetricContext.encodeCategories`. */
    fun encodeCategories(enabled: Set<String>): String =
        MetricSnapshotCategory.entries.map { it.id }.filter { it in enabled }.joinToString(",")

    /**
     * The block for [enabled] categories, or "" when none of them carries a reading.
     * [days] are the ascending "yyyy-MM-dd" days of the window (30 for the Coach); the last
     * [PER_DAY_WINDOW] get per-day lines. Swift twin: `CoachMetricContext.block`.
     */
    fun block(
        days: List<String>,
        values: Map<String, Map<String, Double>>,
        notes: List<MetricSnapshotNote>,
        enabled: Set<String>,
        maxChars: Int = MAX_CHARS,
    ): String {
        if (days.isEmpty() || enabled.isEmpty()) return ""
        val snapshot = MetricSnapshotBuilder.build(days.first(), days.last(), values, notes)
        val recent = days.takeLast(PER_DAY_WINDOW).reversed()

        class Part(val head: List<String>, val perDay: List<String>)
        val parts = ArrayList<Part>()
        for (section in snapshot.sections) {
            if (section.category.id !in enabled) continue
            val rows = section.rows.filter { it.value != null && it.item.id !in coveredByDayTable() }
            val sectionNotes = section.notes.takeLast(MAX_NOTES_PER_CATEGORY)
            if (rows.isEmpty() && sectionNotes.isEmpty()) continue
            val head = ArrayList<String>()
            head.add("[${section.category.label}]")
            if (rows.isNotEmpty()) {
                head.add("  30d: " + rows.joinToString(", ") { "${it.item.id}=${it.text}${tag(it.item.rollup)}" })
            }
            for (n in sectionNotes) head.add("  ${n.label}: ${n.text}")
            val perDay = ArrayList<String>()
            for (day in recent) {
                val cells = rows.mapNotNull { r ->
                    values[r.item.id]?.get(day)?.takeIf { it.isFinite() }
                        ?.let { "${r.item.id}=${MetricSnapshotFormat.number(it, r.item.decimals)}" }
                }
                if (cells.isNotEmpty()) perDay.add("  $day: " + cells.joinToString(", "))
            }
            parts.add(Part(head, perDay))
        }
        if (parts.isEmpty()) return ""

        val header = "ALL METRICS (ids are metric names; 30d = the last 30 days, averaged unless tagged; " +
            "per-day lines are newest first, bare numbers in the units shown on the 30d line; " +
            "a metric that is not listed was not recorded, it is not zero):"
        var used = header.length + parts.sumOf { p -> p.head.sumOf { it.length + 1 } + 1 }
        val out = ArrayList<String>()
        out.add(header)
        for (p in parts) {
            out.addAll(p.head)
            val cost = p.perDay.sumOf { it.length + 1 }
            if (p.perDay.isNotEmpty() && used + cost <= maxChars) {
                out.addAll(p.perDay)
                used += cost
            }
        }
        return out.joinToString("\n")
    }

    /** The roll-up tag printed after a 30-day figure. Swift twin: `CoachMetricContext.tag`. */
    private fun tag(rollup: SnapshotRollup): String = when (rollup) {
        SnapshotRollup.MEAN -> ""
        SnapshotRollup.SUM -> " total"
        SnapshotRollup.LATEST -> " latest"
        SnapshotRollup.MAX -> " max"
        SnapshotRollup.MIN -> " min"
    }
}
