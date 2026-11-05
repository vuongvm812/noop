package com.noop.ui

import com.noop.R
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the Devices card's state-pill priority (#221): "Connected · not paired" must beat "Active · Live"
 * but yield to a reboot's "Reconnecting…". Mirrors the Swift `DevicePillStateTests` exactly — a silent
 * reorder on either platform would otherwise only be caught by eyeballing a screenshot.
 */
class DevicePillStateTest {

    @Test
    fun bondRefused_beatsActiveLive_butYieldsToReconnecting() {
        assertEquals(
            R.string.l10n_devices_screen_connected_not_paired_5a149d26,
            devicePillState(
                isArchived = false, isActive = true, isReconnecting = false,
                bondRefused = true, isLiveConnected = true,
            ).labelRes,
        )
        assertEquals(
            R.string.l10n_devices_screen_reconnecting_8a8b9561,
            devicePillState(
                isArchived = false, isActive = true, isReconnecting = true,
                bondRefused = true, isLiveConnected = true,
            ).labelRes,
        )
    }

    @Test
    fun normalConnect_isUnaffected() {
        assertEquals(
            R.string.l10n_devices_screen_active_live_4dc58f41,
            devicePillState(
                isArchived = false, isActive = true, isReconnecting = false,
                bondRefused = false, isLiveConnected = true,
            ).labelRes,
        )
        assertEquals(
            R.string.l10n_devices_screen_active_a733b809,
            devicePillState(
                isArchived = false, isActive = true, isReconnecting = false,
                bondRefused = false, isLiveConnected = false,
            ).labelRes,
        )
    }

    @Test
    fun nonActiveAndArchived() {
        assertEquals(
            R.string.l10n_devices_screen_paired_3cd1b3d4,
            devicePillState(
                isArchived = false, isActive = false, isReconnecting = false,
                bondRefused = false, isLiveConnected = false,
            ).labelRes,
        )
        assertEquals(
            R.string.l10n_devices_screen_removed_b5e77c5c,
            devicePillState(
                isArchived = true, isActive = false, isReconnecting = false,
                bondRefused = false, isLiveConnected = false,
            ).labelRes,
        )
    }

}
