package com.noop.huawei

import kotlin.math.min
import kotlin.math.pow

/** What prompted a Huawei history-sync attempt. */
enum class HuaweiSyncTrigger {
    /** The repeating timer. */
    PERIODIC,

    /** A (re)connect finished authenticating. */
    CONNECT,

    /** The app became active. */
    FOREGROUND,

    /** The user tapped "Sync now". */
    MANUAL,
}

/**
 * When to pull history from a Huawei band, modelled on [com.noop.ble.GarminRadioPolicy]: the band records
 * on its own, so NOOP syncs in short bursts no more often than a 15-minute floor, and stretches the floor
 * after consecutive syncs that found nothing. Pure; the caller passes the clock and the stored streak.
 * Kotlin-only; Swift twin deferred (fork).
 */
object HuaweiRadioPolicy {
    const val PERIODIC_FLOOR_SECONDS = 900L
    const val EVENT_FLOOR_SECONDS = 90L
    const val EMPTY_BACKOFF_THRESHOLD = 3
    const val MAX_EMPTY_BACKOFF = 4.0

    /** First sync of a band reaches this far back. */
    const val FIRST_BACKFILL_SECONDS = 14L * 86_400

    /** 1 below the threshold, then 2, 4, capped at [MAX_EMPTY_BACKOFF] (900 s x 4 = 1 h). */
    fun syncBackoff(emptyStreak: Int): Double {
        if (emptyStreak < EMPTY_BACKOFF_THRESHOLD) return 1.0
        return min(2.0.pow((emptyStreak - EMPTY_BACKOFF_THRESHOLD + 1).toDouble()), MAX_EMPTY_BACKOFF)
    }

    /** MANUAL always runs; CONNECT / FOREGROUND use the event floor; PERIODIC the backed-off 15-minute floor. */
    fun shouldSync(trigger: HuaweiSyncTrigger, nowSeconds: Long, lastSyncAtSeconds: Long?, emptyStreak: Int = 0): Boolean {
        val last = lastSyncAtSeconds ?: return true
        val elapsed = nowSeconds - last
        return when (trigger) {
            HuaweiSyncTrigger.MANUAL -> true
            HuaweiSyncTrigger.CONNECT, HuaweiSyncTrigger.FOREGROUND -> elapsed >= EVENT_FLOOR_SECONDS
            HuaweiSyncTrigger.PERIODIC -> elapsed >= PERIODIC_FLOOR_SECONDS * syncBackoff(emptyStreak)
        }
    }
}
