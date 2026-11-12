package com.noop.huawei

import com.noop.huawei.protocol.HuaweiExpandCapabilities
import com.noop.huawei.protocol.HuaweiLink
import com.noop.huawei.protocol.HuaweiSettingRelated
import com.noop.ingest.HuaweiWatchIngest

/**
 * What the band said it supports on this connection, gathered by the source before it reports READY.
 * Every field can be empty or null: a band that refused (or never answered) one of the capability reads
 * still reaches READY, and a sync engine must treat a missing answer as "unknown", not "unsupported".
 */
data class HuaweiCapabilities(
    /** Services the band answered 1 for in SupportedServices 01/02. */
    val services: Set<Int> = emptySet(),
    /** Service -> command ids the band answered 1 for in SupportedCommands 01/03. */
    val commands: Map<Int, Set<Int>> = emptyMap(),
    /** ExpandCapabilities 01/37, or null when the band did not answer it. */
    val expand: HuaweiExpandCapabilities? = null,
    /** SettingRelated 01/31 file-sync bits, or null when the band did not answer it. */
    val settings: HuaweiSettingRelated? = null,
) {
    /** True only when the band confirmed [commandId] under [serviceId]. */
    fun supports(serviceId: Int, commandId: Int): Boolean = commands[serviceId]?.contains(commandId) == true
}

/** The part of a history sync in progress, for the device panel. */
enum class HuaweiSyncStage {
    /** The one-time measurement switches. */
    SETTINGS,
    STEPS,
    SLEEP,
    STRESS,
    DICTIONARY,
    WORKOUTS,
    /** Writing the batch to the database. */
    STORING,
}

/**
 * The seam between the BLE source and the history sync engine. The source calls [run] once the link is
 * authenticated and READY (on connect, on app foreground, on its periodic timer and on "Sync now", each
 * gated by [HuaweiRadioPolicy]), with the encrypted [link], what the band reported in [capabilities], and
 * the registry [deviceId] rows must be stored under. A normal return marks the sync complete; a throw is
 * logged and leaves the last-sync time unchanged. The source never runs two syncs at once.
 */
fun interface HuaweiSyncRunner {
    suspend fun sync(link: HuaweiLink, capabilities: HuaweiCapabilities, deviceId: String)

    /**
     * One sync that reports each [HuaweiSyncStage] it enters to [progress] and returns what it did, or null
     * when this runner keeps no summary (the default, which just calls [sync]).
     */
    suspend fun run(
        link: HuaweiLink,
        capabilities: HuaweiCapabilities,
        deviceId: String,
        progress: (HuaweiSyncStage) -> Unit = {},
    ): HuaweiSyncSummary? {
        sync(link, capabilities, deviceId)
        return null
    }

    companion object {
        /** Connects, reads device info, syncs nothing (tests, and a coordinator built without a runner). */
        val NONE: HuaweiSyncRunner = HuaweiSyncRunner { _, _, _ -> }
    }
}

/** Each history stream with its own watermark. */
enum class HuaweiDataType(val key: String) {
    STEPS("steps"),
    SLEEP("sleep"),
    SLEEP_FILE("sleep_file"),
    /** TruSleep `sleep_state.bin`, fetched only on a band without expand bit 143. */
    SLEEP_STATE("sleep_state"),
    STRESS("stress"),
    WORKOUTS("workouts"),
}

/**
 * Per-device sync state. Times are unix seconds except the P2P cursors (milliseconds, the dictionary's
 * own unit). The app's store is [com.noop.data.HuaweiSyncPrefs].
 */
interface HuaweiSyncCursor {
    fun lastSync(deviceId: String, type: HuaweiDataType): Long?
    fun setLastSync(deviceId: String, type: HuaweiDataType, ts: Long)
    fun lastWorkoutEnd(deviceId: String): Long?
    fun setLastWorkoutEnd(deviceId: String, ts: Long)
    fun dictionaryCursorMs(deviceId: String, dictClass: Int): Long?
    fun setDictionaryCursorMs(deviceId: String, dictClass: Int, ms: Long)
    fun switchesEnabled(deviceId: String): Boolean
    fun setSwitchesEnabled(deviceId: String)
    /** When the last sync attempt started (the radio policy's floor reads it). */
    fun lastSyncAt(deviceId: String): Long?
    fun emptyStreak(deviceId: String): Int
    fun recordSyncOutcome(deviceId: String, at: Long, empty: Boolean)
    /** Epoch seconds before which the band's P2P data dictionary is not asked again; null when it was served. */
    fun dictUnavailableUntil(deviceId: String): Long?
    fun setDictUnavailableUntil(deviceId: String, ts: Long)
    /** Where the next steps read may start instead of local midnight; null before the first clean read. */
    fun stepCarry(deviceId: String): HuaweiStepCarry?
    fun setStepCarry(deviceId: String, carry: HuaweiStepCarry)
    /** The end of the last finished night stored from the sleep stream [type]; null before the first. */
    fun lastNightEnd(deviceId: String, type: HuaweiDataType): Long?
    fun setLastNightEnd(deviceId: String, type: HuaweiDataType, ts: Long)
}

/**
 * A local [day]'s step counters summed over its minutes before [cutoffTs] ([base]), so the next steps read
 * can start at [cutoffTs] and still fold complete day totals. Kotlin-only; Swift twin deferred (fork).
 */
data class HuaweiStepCarry(val day: String, val cutoffTs: Long, val base: HuaweiWatchIngest.DayCounters) {
    /** One line for the cursor store: `day|cutoff|steps|distanceM|kcal`, an absent counter empty. */
    fun encode(): String = listOf(day, cutoffTs, base.steps ?: "", base.distanceM ?: "", base.kcal ?: "").joinToString("|")

    companion object {
        /** The carry [encode] wrote, or null for anything else (the read then starts at local midnight). */
        fun decode(s: String?): HuaweiStepCarry? {
            val p = s?.split("|")?.takeIf { it.size == 5 && it[0].isNotEmpty() } ?: return null
            val cutoff = p[1].toLongOrNull() ?: return null
            val steps = p[2].ifEmpty { null }?.let { it.toIntOrNull() ?: return null }
            val distance = p[3].ifEmpty { null }?.let { it.toDoubleOrNull() ?: return null }
            val kcal = p[4].ifEmpty { null }?.let { it.toDoubleOrNull() ?: return null }
            return HuaweiStepCarry(p[0], cutoff, HuaweiWatchIngest.DayCounters(steps, distance, kcal))
        }
    }
}
