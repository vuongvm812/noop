package com.noop.analytics

/**
 * When the "weekly summary ready" notification fires (#91). Pure and clock-free: the caller passes
 * the LOGICAL day (the 04:00 roll). Swift twin: `WeeklySummaryPolicy`.
 */
object WeeklySummaryPolicy {

    /**
     * The Monday of the week to announce now, or null when nothing should fire. Fires once per
     * week, for LAST week, on the first evaluation after the new week's Monday logical day has
     * started and that week's first sync has landed ([hasCurrentWeekData]). A missed Monday still
     * fires later that week; never twice for the same week. Swift twin: `WeeklySummaryPolicy.weekToAnnounce`.
     */
    fun weekToAnnounce(
        enabled: Boolean,
        logicalToday: String,
        lastAnnouncedWeek: String?,
        hasCurrentWeekData: Boolean,
    ): String? {
        if (!enabled || !hasCurrentWeekData) return null
        val thisMonday = WeeklyDigestEngine.mondayOfWeek(logicalToday) ?: return null
        val lastMonday = WeeklyDigestEngine.addDays(thisMonday, -7)
        return if (lastAnnouncedWeek == lastMonday) null else lastMonday
    }

    /** Last week's Sunday, so a total spans all seven days. Swift twin: `WeeklySummaryPolicy.anchorDay`. */
    fun anchorDay(monday: String): String = WeeklyDigestEngine.addDays(monday, 6)

    /** True when the history holds a row in the week containing [logicalToday]. Swift twin: `WeeklySummaryPolicy.hasCurrentWeekData`. */
    fun hasCurrentWeekData(dayKeys: List<String>, logicalToday: String): Boolean {
        val monday = WeeklyDigestEngine.mondayOfWeek(logicalToday) ?: return false
        return dayKeys.any { it >= monday && it <= logicalToday }
    }

    /** The top focal sentence, null for an empty week. Swift twin: `WeeklySummaryPolicy.body`. */
    fun body(digest: WeeklyDigest): String? = if (digest.isEmpty) null else digest.focalPoints.firstOrNull()
}
