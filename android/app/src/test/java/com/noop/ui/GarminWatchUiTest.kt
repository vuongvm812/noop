package com.noop.ui

import com.noop.R
import com.noop.ble.GarminLiveChannel
import com.noop.ble.GarminSource.LinkPhase
import com.noop.ble.SourceCoordinator
import com.noop.data.PairedDeviceRow
import com.noop.data.SourceKind
import com.noop.garmin.GarminDeviceInformation
import com.noop.garmin.GarminSyncFileKey
import com.noop.garmin.GarminSyncSummary
import com.noop.garmin.GarminTransportKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The Garmin watch UI's pure helpers, and its English copy against the shared spec (identical on Apple). */
class GarminWatchUiTest {

    private fun info(name: String = "Forerunner 165", model: String = "006-B4432-00") = GarminDeviceInformation(
        protocolVersion = 150, productNumber = 4432, unitNumber = 3_456_789_012L, softwareVersion = 2305,
        maxPacketSize = 375, bluetoothName = name, deviceName = name, deviceModel = model, classicAddress = null,
    )

    private fun key(ts: Long, index: Int = 1) = GarminSyncFileKey(index, 128, 32, index, ts, 100L)

    @Test
    fun panelStatusFollowsThePhaseAndTheSync() {
        assertEquals(R.string.l10n_garmin_watch_panel_connected_c2f9b7b4, GarminWatchUi.panelStatusRes(LinkPhase.READY, false))
        assertEquals(R.string.l10n_garmin_watch_panel_syncing_history_03ec192e, GarminWatchUi.panelStatusRes(LinkPhase.READY, true))
        for (p in listOf(LinkPhase.CONNECTING, LinkPhase.DISCOVERING, LinkPhase.SUBSCRIBING, LinkPhase.HANDSHAKING)) {
            assertEquals(R.string.l10n_garmin_watch_panel_connecting_fd3e7969, GarminWatchUi.panelStatusRes(p, false))
        }
        assertEquals(R.string.l10n_garmin_watch_panel_protocol_not_supported_05ba63ff, GarminWatchUi.panelStatusRes(LinkPhase.UNSUPPORTED, false))
        assertEquals(R.string.l10n_garmin_watch_panel_not_connected_8b02f3de, GarminWatchUi.panelStatusRes(LinkPhase.DISCONNECTED, false))
        assertEquals(R.string.l10n_garmin_watch_panel_watch_busy_with_another_app_d723ea45,
            GarminWatchUi.panelStatusRes(LinkPhase.BUSY, false))
        assertTrue(GarminWatchUi.canSyncNow(LinkPhase.READY, false))
        assertTrue(GarminWatchUi.canSyncNow(LinkPhase.BUSY, false))
        assertFalse(GarminWatchUi.canSyncNow(LinkPhase.READY, true))
        assertFalse(GarminWatchUi.canSyncNow(LinkPhase.HANDSHAKING, false))
        assertFalse(GarminWatchUi.canSyncNow(LinkPhase.DISCONNECTED, false))
    }

    @Test
    fun connectProgressLines() {
        assertEquals(R.string.l10n_add_device_wizard_connecting_to_the_watch_7acf3bc9, GarminWatchUi.connectProgressRes(LinkPhase.CONNECTING))
        assertEquals(R.string.l10n_add_device_wizard_connecting_to_the_watch_7acf3bc9, GarminWatchUi.connectProgressRes(LinkPhase.DISCONNECTED))
        assertEquals(R.string.l10n_add_device_wizard_checking_which_protocol_the_watch_speaks_9a308e09, GarminWatchUi.connectProgressRes(LinkPhase.DISCOVERING))
        assertEquals(R.string.l10n_add_device_wizard_checking_which_protocol_the_watch_speaks_9a308e09, GarminWatchUi.connectProgressRes(LinkPhase.SUBSCRIBING))
        assertEquals(R.string.l10n_add_device_wizard_saying_hello_to_the_watch_4e06cb80, GarminWatchUi.connectProgressRes(LinkPhase.HANDSHAKING))
        assertEquals(R.string.l10n_garmin_watch_panel_watch_busy_with_another_app_d723ea45,
            GarminWatchUi.connectProgressRes(LinkPhase.BUSY))
        assertNull(GarminWatchUi.connectProgressRes(LinkPhase.READY))
        assertNull(GarminWatchUi.connectProgressRes(LinkPhase.UNSUPPORTED))
    }

    @Test
    fun readoutStrings() {
        assertEquals("23.05", GarminWatchUi.firmware(2305))
        assertEquals("23.12", GarminWatchUi.firmware(2312))
        assertEquals("4.00", GarminWatchUi.firmware(400))
        assertEquals("Forerunner 165", GarminWatchUi.modelName(info()))
        assertEquals("006-B4432-00", GarminWatchUi.modelName(info(name = "")))
        assertEquals("V2 multi-link · GFDI 150", GarminWatchUi.protocolLine(GarminTransportKind.MULTI_LINK_V2, info()))
        assertEquals("V1", GarminWatchUi.protocolLine(GarminTransportKind.V1, null))
        assertEquals("-", GarminWatchUi.protocolLine(null, null))
        assertEquals("V0 · GFDI 150 · max packet 375", GarminWatchUi.testCentreProtocolLine(GarminTransportKind.V0, info()))
        assertEquals("Forerunner 165 · product 4432 · unit 3456789012", GarminWatchUi.watchLine(info()))
        assertEquals("6,12,19", GarminWatchUi.openChannelIds(setOf(GarminLiveChannel.SPO2, GarminLiveChannel.HEART_RATE, GarminLiveChannel.RR_INTERVALS)))
        assertNull(GarminWatchUi.openChannelIds(emptySet()))
    }

    @Test
    fun syncFileRowsAreNewestFirstCappedAndMarked() {
        val onWatch = (1..50).map { key(1_000L + it, it) }
        val ingested = setOf(key(1_050L, 50), key(1_001L, 1))
        assertEquals(2, GarminWatchUi.inNoopCount(onWatch, ingested))
        val rows = GarminWatchUi.syncFileRows(onWatch, ingested)
        assertEquals(40, rows.size)
        assertEquals(key(1_050L, 50), rows.first().first)
        assertTrue(rows.first().second)
        assertEquals(key(1_011L, 11), rows.last().first)
        assertFalse(rows.last().second)
        val summary = GarminSyncSummary(wanted = 7, alreadySynced = 2, downloaded = 4)
        assertEquals(Triple(4, 2, 7), GarminWatchUi.summaryCounts(summary))
    }

    @Test
    fun liveScreenChannelsAndTheGarminProfileRoute() {
        assertEquals(
            setOf(GarminLiveChannel.BODY_BATTERY, GarminLiveChannel.RESPIRATION, GarminLiveChannel.STEPS, GarminLiveChannel.FLOORS),
            GarminWatchUi.liveScreenChannels,
        )
        val row = PairedDeviceRow(id = "garmin-AA", brand = "Garmin", model = "Forerunner 165", nickname = null,
            peripheralId = "AA", sourceKind = SourceKind.garmin.name, capabilities = "hr", status = "paired", addedAt = 0, lastSeenAt = 0)
        assertTrue(isGarminWatch(row))
        assertFalse(isGarminWatch(row.copy(sourceKind = SourceKind.liveBLE.name)))
    }

    @Test
    fun onlyASyncThatStoredFilesTriggersReanalysis() {
        assertFalse(SourceCoordinator.garminSyncStoredHistory(GarminSyncSummary(wanted = 5, alreadySynced = 5)))
        assertTrue(SourceCoordinator.garminSyncStoredHistory(GarminSyncSummary(wanted = 5, downloaded = 1)))
    }

    private fun repoRoot(): File {
        val userDir = File(System.getProperty("user.dir") ?: ".")
        val found = listOf(userDir, File(userDir, ".."), File(userDir, "../.."))
            .firstOrNull { File(it, "android/app/src/main/res/values/strings.xml").isFile }
        assertNotNull("repo root not found from user.dir=$userDir", found)
        return found!!
    }

    /** The English source copy, word for word as the shared spec gives it (Apple uses the same text). */
    @Test
    fun englishCopyMatchesTheSpec() {
        val xml = File(repoRoot(), "android/app/src/main/res/values/strings.xml").readText()
        fun text(name: String): String {
            val m = Regex("<string name=\"$name\">(.*?)</string>").find(xml)
            assertNotNull("missing $name", m)
            return m!!.groupValues[1].replace("\\'", "'").replace("\\\"", "\"").replace("&amp;", "&")
        }
        val expected = mapOf(
            "l10n_add_device_wizard_pairs_over_the_watch_s_own_2064d8fb" to
                "Pairs over the watch's own protocol: history sync and live data, read-only.",
            "l10n_add_device_wizard_on_the_watch_open_settings_then_a2c94606" to
                "On the watch, open Settings, then Phone (or Connectivity), and choose Pair Phone. You can ignore any QR code the watch shows.",
            "l10n_add_device_wizard_noop_was_tested_with_garmin_connect_c365d5af" to
                "NOOP was tested with Garmin Connect switched off. If the watch refuses NOOP, close Garmin Connect or turn off " +
                "Bluetooth on that phone, then try again. If Garmin Connect syncs first, it can take today's files off the watch " +
                "before NOOP reads them.",
            "l10n_add_device_wizard_connecting_to_the_watch_7acf3bc9" to "Connecting to the watch…",
            "l10n_add_device_wizard_last_error_ee28d44b" to "Last error: %1\$s",
            "l10n_devices_screen_heart_rate_r_r_sleep_steps_d1a26aee" to
                "Heart rate · R-R · Sleep · Steps · SpO₂ · Respiration · Stress · Body Battery · HRV",
            "l10n_devices_screen_powers_charge_effort_and_rest_from_6c8040c2" to
                "Powers Charge, Effort and Rest from the watch's own data.",
            "l10n_devices_screen_read_only_noop_downloads_the_watch_49e3c0cd" to
                "Read-only: NOOP downloads the watch's files and never deletes or changes them. Garmin's stress " +
                "and Body Battery are shown as Garmin reports them.",
            "l10n_garmin_watch_panel_new_files_already_in_noop_on_5d9e06f1" to
                "%1\$d new files, %2\$d already in NOOP, %3\$d on the watch",
            "l10n_garmin_watch_panel_last_sync_6dc327c7" to "Last sync %1\$s",
            "l10n_garmin_watch_panel_keeps_heart_rate_and_r_r_cb51859c" to
                "Keeps heart rate and R-R streaming while NOOP is open, even off the Live screen. Watch battery cost: %1\$s.",
            "l10n_garmin_watch_panel_about_per_hour_0b305a52" to "about %1\$.1f%% per hour",
            "l10n_test_centre_screen_on_the_watch_fit_files_in_20f67e86" to "On the watch: %1\$d FIT files. In NOOP: %2\$d.",
            "l10n_test_centre_screen_per_message_detail_goes_to_the_d305d4b1" to
                "Per-message detail goes to the strap log while the Connection or Sources test mode is on.",
        )
        for ((name, english) in expected) assertEquals(name, english, text(name))
        // No em-dash anywhere in the Garmin copy.
        val garminLines = xml.lines().filter { it.contains("garmin_watch_panel") || it.contains("add_device_wizard_garmin") }
        assertTrue(garminLines.none { it.contains('—') })
    }
}
