package com.noop.garmin

// Sharing the watch with Garmin Connect (#35). Twin of
// Packages/GarminProtocol/Sources/GarminProtocol/GarminCoexistence.swift, where the hardware evidence and the
// rules are written out: the watch grants GFDI to one client at a time (status 3 to the other), NOOP's
// CLOSE_ALL does not release another app's GFDI, and a registration appears to outlive its app while the
// link stays up. NOOP
// never fights for GFDI: it parks in `busy`, re-asks on a backoff, and yields to an app that was refused.
// NOOP never sends a frame under another client's id.

object GarminCoexistence {

    /** The REGISTER status the watch answered while Garmin Connect held GFDI. */
    const val IN_USE_STATUS: Int = 3

    /** First re-ask after a refusal; doubles per refusal up to [RETRY_CAP_MS]. */
    const val RETRY_BASE_MS: Long = 30_000
    const val RETRY_CAP_MS: Long = 600_000

    /** After the other app released GFDI, or the watch closed NOOP's own GFDI handle unasked. */
    const val RELEASE_GRACE_MS: Long = 5_000

    /** After NOOP yields GFDI to another app: time for that app to sync before NOOP asks again. */
    const val YIELD_COOLDOWN_MS: Long = 120_000

    /** A foreground or "Sync now" re-ask is skipped when NOOP asked this recently. */
    const val MANUAL_RETRY_MIN_GAP_MS: Long = 5_000

    /** The delay before re-ask number [refusals] (1-based): 30 s, 60 s, 120 s, 240 s, 480 s, then 600 s. */
    fun retryDelayMs(refusals: Int): Long {
        val shift = minOf(maxOf(0, refusals - 1), 10)
        return minOf(RETRY_CAP_MS, RETRY_BASE_MS shl shift)
    }

    /** Foreign data packets are logged at these counts per handle: first sight, then sparsely. */
    fun logsForeignPacket(count: Int): Boolean =
        count == 1 || count == 10 || count == 100 || count == 1_000 || count == 10_000
}
