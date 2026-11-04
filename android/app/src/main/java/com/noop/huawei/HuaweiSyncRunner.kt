package com.noop.huawei

import com.noop.huawei.protocol.HuaweiExpandCapabilities
import com.noop.huawei.protocol.HuaweiLink
import com.noop.huawei.protocol.HuaweiSettingRelated

// The seam between the Huawei BLE source (#81) and history sync (#82). Named in both briefs as
// `HuaweiSyncRunner { suspend fun sync(link, capabilities, deviceId) }`; #81 calls it once the link is
// authenticated and the capability exchange is done. Kotlin-only; Swift twin deferred (fork).

/**
 * What the band said it can do, from the capability exchange: [commands] is service → supported command
 * ids (01/03), [expand] the 01/37 bitmap, [fileSync] the 01/31 file-sync bits. A missing part reads as
 * "not supported".
 */
data class HuaweiCapabilities(
    val commands: Map<Int, Set<Int>> = emptyMap(),
    val expand: HuaweiExpandCapabilities? = null,
    val fileSync: HuaweiSettingRelated? = null,
) {
    fun supports(serviceId: Int, commandId: Int): Boolean = commands[serviceId]?.contains(commandId) == true

    fun hasExpand(bit: Int): Boolean = expand?.has(bit) == true

    companion object {
        /**
         * The commands history sync gates on, per service, for the 01/03 request: the same lists
         * Gadgetbridge asks for (`DeviceConfig.java`), so a band answers them as it answers it.
         */
        val PROBE_COMMANDS: Map<Int, ByteArray> = linkedMapOf(
            0x07 to bytes(0x01, 0x03, 0x05, 0x07, 0x08, 0x09, 0x0A, 0x0E, 0x10, 0x13, 0x16, 0x15, 0x17, 0x18, 0x1B, 0x1C,
                0x1D, 0x1E, 0x21, 0x22, 0x23, 0x24, 0x25, 0x28, 0x29, 0x06, 0x1F),
            0x0A to bytes(0x01, 0x09, 0x0A),
            0x17 to bytes(0x01, 0x04, 0x06, 0x07, 0x0B, 0x0C, 0x10, 0x12, 0x15, 0x17),
            0x20 to bytes(0x01, 0x02, 0x03, 0x04, 0x09, 0x0A),
            0x34 to bytes(0x01),
        )

        private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    }
}

/** Runs one history sync over an authenticated link. */
interface HuaweiSyncRunner {
    suspend fun sync(link: HuaweiLink, capabilities: HuaweiCapabilities, deviceId: String)
}

/** Syncs nothing: the default until the real runner is wired in. */
object NoopHuaweiSyncRunner : HuaweiSyncRunner {
    override suspend fun sync(link: HuaweiLink, capabilities: HuaweiCapabilities, deviceId: String) = Unit
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
