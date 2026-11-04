package com.noop.huawei

import com.noop.huawei.protocol.HuaweiExpandCapabilities
import com.noop.huawei.protocol.HuaweiLink
import com.noop.huawei.protocol.HuaweiSettingRelated

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

/**
 * The seam between the BLE source and the history sync engine. The source calls [sync] once the link is
 * authenticated and READY (on connect and on "Sync now"), with the encrypted [link], what the band
 * reported in [capabilities], and the registry [deviceId] rows must be stored under. A normal return marks
 * the sync complete; a throw is logged and leaves the last-sync time unchanged. The source never runs two
 * syncs at once.
 */
fun interface HuaweiSyncRunner {
    suspend fun sync(link: HuaweiLink, capabilities: HuaweiCapabilities, deviceId: String)

    companion object {
        /** Until the sync engine is wired: connects, reads device info, syncs nothing. */
        val NONE: HuaweiSyncRunner = HuaweiSyncRunner { _, _, _ -> }
    }
}

/** Each history stream with its own watermark. */
enum class HuaweiDataType(val key: String) {
    STEPS("steps"),
    SLEEP("sleep"),
    SLEEP_FILE("sleep_file"),
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
    fun lastSyncAt(deviceId: String): Long?
    fun emptyStreak(deviceId: String): Int
    fun recordSyncOutcome(deviceId: String, at: Long, empty: Boolean)
}
