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
