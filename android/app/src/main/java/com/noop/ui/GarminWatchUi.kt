package com.noop.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.ble.GarminLiveChannel
import com.noop.ble.GarminRadioPolicy
import com.noop.ble.GarminSource.LinkPhase
import com.noop.garmin.GarminDeviceInformation
import com.noop.garmin.GarminLiveSnapshot
import com.noop.garmin.GarminSyncFileKey
import com.noop.garmin.GarminSyncSummary
import com.noop.garmin.GarminTransportKind

/**
 * Pure presentation helpers for the Garmin watch UI (wizard Connect step, the Garmin watch panel, the Live
 * section, the Test Centre card). Copy resolves through string resources; these return resource ids or
 * literal data readouts so they run in a JVM test. Twin of the Swift Garmin UI helpers.
 */
internal object GarminWatchUi {

    /** The panel's status line. */
    @StringRes
    fun panelStatusRes(phase: LinkPhase, syncing: Boolean): Int = when {
        phase == LinkPhase.UNSUPPORTED -> R.string.l10n_garmin_watch_panel_protocol_not_supported_05ba63ff
        phase == LinkPhase.READY && syncing -> R.string.l10n_garmin_watch_panel_syncing_history_03ec192e
        phase == LinkPhase.READY -> R.string.l10n_garmin_watch_panel_connected_c2f9b7b4
        phase == LinkPhase.DISCONNECTED -> R.string.l10n_garmin_watch_panel_not_connected_8b02f3de
        phase == LinkPhase.BUSY -> R.string.l10n_garmin_watch_panel_watch_busy_with_another_app_d723ea45
        else -> R.string.l10n_garmin_watch_panel_connecting_fd3e7969
    }

    /** "Sync now" is offered on a ready link with no sync running, and while another app holds the watch
     *  (there it asks the watch again). */
    fun canSyncNow(phase: LinkPhase, syncing: Boolean): Boolean =
        (phase == LinkPhase.READY || phase == LinkPhase.BUSY) && !syncing

    /** The wizard Connect step's progress line, or null once the link is ready or unsupported. */
    @StringRes
    fun connectProgressRes(phase: LinkPhase): Int? = when (phase) {
        LinkPhase.DISCONNECTED, LinkPhase.CONNECTING -> R.string.l10n_add_device_wizard_connecting_to_the_watch_7acf3bc9
        LinkPhase.DISCOVERING, LinkPhase.SUBSCRIBING -> R.string.l10n_add_device_wizard_checking_which_protocol_the_watch_speaks_9a308e09
        LinkPhase.HANDSHAKING -> R.string.l10n_add_device_wizard_saying_hello_to_the_watch_4e06cb80
        LinkPhase.BUSY -> R.string.l10n_garmin_watch_panel_watch_busy_with_another_app_d723ea45
        LinkPhase.READY, LinkPhase.UNSUPPORTED -> null
    }

    /** Firmware from DeviceInformation's softwareVersion (version x 100): 2312 -> "23.12". */
    fun firmware(softwareVersion: Int): String {
        val minor = softwareVersion % 100
        return "${softwareVersion / 100}.${if (minor < 10) "0" else ""}$minor"
    }

    /** The watch's name for the Success face: bluetoothName, else deviceModel. */
    fun modelName(info: GarminDeviceInformation): String = info.bluetoothName.ifBlank { info.deviceModel }

    /** Success face "Protocol" value: "V2 multi-link · GFDI 150". */
    fun protocolLine(kind: GarminTransportKind?, info: GarminDeviceInformation?): String =
        listOfNotNull(kind?.label, info?.let { "GFDI ${it.protocolVersion}" }).joinToString(" · ").ifEmpty { "-" }

    /** Test Centre "Protocol" value: "V2 multi-link · GFDI 150 · max packet 375". */
    fun testCentreProtocolLine(kind: GarminTransportKind?, info: GarminDeviceInformation?): String =
        listOfNotNull(kind?.label, info?.let { "GFDI ${it.protocolVersion}" }, info?.let { "max packet ${it.maxPacketSize}" })
            .joinToString(" · ").ifEmpty { "-" }

    /** Test Centre "Watch" value: "Forerunner 165 · product 4432 · unit 3456789012". */
    fun watchLine(info: GarminDeviceInformation): String =
        "${info.bluetoothName} · product ${info.productNumber} · unit ${info.unitNumber}"

    /** Open live channel service ids, ascending and comma-joined ("6,12"); null when none is open. */
    fun openChannelIds(channels: Set<GarminLiveChannel>): String? =
        channels.map { it.serviceId }.sorted().joinToString(",").ifEmpty { null }

    /** How many of the files listed on the watch the ingest ledger already holds. */
    fun inNoopCount(onWatch: List<GarminSyncFileKey>, ingested: Set<GarminSyncFileKey>): Int =
        onWatch.count { it in ingested }

    /** The Test Centre file list: newest first, at most [limit] rows, each with "already ingested". */
    fun syncFileRows(
        onWatch: List<GarminSyncFileKey>,
        ingested: Set<GarminSyncFileKey>,
        limit: Int = SYNC_FILE_ROWS,
    ): List<Pair<GarminSyncFileKey, Boolean>> =
        onWatch.sortedDescending().take(limit).map { it to (it in ingested) }

    /** The panel summary counts, in copy order: new files, already in NOOP, on the watch. */
    fun summaryCounts(summary: GarminSyncSummary): Triple<Int, Int, Int> =
        Triple(summary.downloaded, summary.alreadySynced, summary.wanted)

    /** The optional channels the Live screen's Garmin tiles hold open while it is visible. */
    val liveScreenChannels: Set<GarminLiveChannel> = setOf(
        GarminLiveChannel.BODY_BATTERY, GarminLiveChannel.RESPIRATION, GarminLiveChannel.STEPS, GarminLiveChannel.FLOORS,
    )

    /** Garmin's trademark, shown as Garmin names it in every language. */
    const val BODY_BATTERY = "Body Battery"
    const val SYNC_FILE_ROWS = 40
    /** No ready link within this long moves the wizard to the Slow face. */
    const val CONNECT_TIMEOUT_MS = 45_000L
}

/** The continuous-live battery cost for the toggle's description. */
@Composable
private fun liveCostText(): String = GarminRadioPolicy.MEASURED_LIVE_COST_PERCENT_PER_HOUR?.let {
    uiString(R.string.l10n_garmin_watch_panel_about_per_hour_0b305a52, it)
} ?: uiString(R.string.l10n_garmin_watch_panel_not_measured_yet_64196e90)

/**
 * The Garmin watch panel: link status, battery, last sync and its summary, "Sync now", the continuous-live
 * toggle and the Broadcast Heart Rate fallback note. Shown under the active Garmin device card and on Data
 * Sources. Renders nothing while no Garmin source is live.
 */
@Composable
internal fun GarminWatchPanel(viewModel: AppViewModel, modifier: Modifier = Modifier) {
    val source by viewModel.garminSource.collectAsStateWithLifecycle()
    val s = source ?: return
    val phase by s.linkPhase.collectAsStateWithLifecycle()
    val syncing by s.syncing.collectAsStateWithLifecycle()
    val battery by s.batteryPct.collectAsStateWithLifecycle()
    val lastSyncAt by s.lastSyncAt.collectAsStateWithLifecycle()
    val summary by s.lastSyncSummary.collectAsStateWithLifecycle()
    val continuous by viewModel.garminContinuousLive.collectAsStateWithLifecycle()

    NoopCard(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space10)) {
            Overline(uiString(R.string.l10n_add_device_wizard_garmin_watch_a866104a))
            Text(uiString(GarminWatchUi.panelStatusRes(phase, syncing)), style = NoopType.headline, color = Palette.textPrimary)
            Text(
                battery?.let { uiString(R.string.l10n_garmin_watch_panel_battery_c81930ed, it) }
                    ?: uiString(R.string.l10n_garmin_watch_panel_battery_not_reported_by_this_watch_db5206e5),
                style = NoopType.subhead,
                color = Palette.textSecondary,
            )
            Text(
                lastSyncAt?.let { uiString(R.string.l10n_garmin_watch_panel_last_sync_6dc327c7, relativeAgo(it)) }
                    ?: uiString(R.string.l10n_garmin_watch_panel_not_synced_yet_f8c91683),
                style = NoopType.subhead,
                color = Palette.textSecondary,
            )
            summary?.let { sum ->
                val (fresh, known, listed) = GarminWatchUi.summaryCounts(sum)
                Text(
                    uiString(R.string.l10n_garmin_watch_panel_new_files_already_in_noop_on_5d9e06f1, fresh, known, listed),
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                )
            }
            NoopButton(
                text = uiString(R.string.l10n_garmin_watch_panel_sync_now_2b7d938e),
                leadingIcon = Icons.Filled.Sync,
                kind = NoopButtonKind.Secondary,
                fullWidth = true,
                enabled = GarminWatchUi.canSyncNow(phase, syncing),
                onClick = { viewModel.garminSyncNow() },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Metrics.space16),
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Metrics.space4)) {
                    Text(uiString(R.string.l10n_garmin_watch_panel_continuous_live_e34ccebb), style = NoopType.subhead, color = Palette.textPrimary)
                    Text(
                        uiString(R.string.l10n_garmin_watch_panel_keeps_heart_rate_and_r_r_cb51859c, liveCostText()),
                        style = NoopType.footnote,
                        color = Palette.textTertiary,
                    )
                }
                Switch(
                    checked = continuous,
                    onCheckedChange = { viewModel.setGarminContinuousLive(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Palette.surfaceBase,
                        checkedTrackColor = Palette.accent,
                        uncheckedThumbColor = Palette.textSecondary,
                        uncheckedTrackColor = Palette.surfaceInset,
                        uncheckedBorderColor = Palette.hairline,
                    ),
                )
            }
            Text(
                uiString(R.string.l10n_garmin_watch_panel_if_noop_can_t_keep_a_4ae29b91),
                style = NoopType.footnote,
                color = Palette.textTertiary,
            )
        }
    }
}

/**
 * The Live screen's "From your Garmin" section: Body Battery, respiration, steps, floors and an on-request
 * SpO2 reading from the watch's live channels. HR and R-R ride the screen's existing live HR UI.
 */
@Composable
internal fun GarminLiveSection(snapshot: GarminLiveSnapshot?, spo2Requested: Boolean, onMeasureSpo2: () -> Unit) {
    val snap = snapshot ?: GarminLiveSnapshot()
    NoopCard {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
            Overline(uiString(R.string.l10n_live_screen_from_your_garmin_9f41152c))
            Row(horizontalArrangement = Arrangement.spacedBy(Metrics.space12), modifier = Modifier.fillMaxWidth()) {
                GarminTile(
                    GarminWatchUi.BODY_BATTERY,
                    snap.bodyBattery?.toString() ?: "-",
                    Modifier.weight(1f),
                )
                GarminTile(
                    uiString(R.string.l10n_live_screen_respiration_59da5ee8),
                    snap.respirationBpm?.let { uiString(R.string.l10n_live_screen_brpm_8ef5fdaf, it) } ?: "-",
                    Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Metrics.space12), modifier = Modifier.fillMaxWidth()) {
                GarminTile(
                    uiString(R.string.l10n_live_screen_steps_cdde4f20),
                    snap.steps?.let { steps ->
                        snap.stepGoal?.let { goal -> uiString(R.string.l10n_live_screen_of_6f00f423, steps, goal) } ?: steps.toString()
                    } ?: "-",
                    Modifier.weight(1f),
                )
                GarminTile(
                    uiString(R.string.l10n_live_screen_floors_e623d4fb),
                    snap.floorsAscended?.let { uiString(R.string.l10n_live_screen_up_14927903, it) } ?: "-",
                    Modifier.weight(1f),
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(Metrics.space12),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                GarminTile("SpO₂", snap.spo2Pct?.let { "$it%" } ?: "-", Modifier.weight(1f))
                NoopButton(
                    text = if (spo2Requested) uiString(R.string.l10n_live_screen_measuring_8945ea3a)
                    else uiString(R.string.l10n_live_screen_measure_spo_488fcb91),
                    kind = NoopButtonKind.Secondary,
                    enabled = !spo2Requested,
                    onClick = onMeasureSpo2,
                )
            }
            Text(
                uiString(R.string.l10n_live_screen_live_from_the_watch_while_this_d33cba9f),
                style = NoopType.footnote,
                color = Palette.textTertiary,
            )
        }
    }
}

@Composable
private fun GarminTile(name: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Metrics.space4)) {
        Text(name, style = NoopType.caption, color = Palette.textSecondary)
        Text(value, style = NoopType.title2, color = Palette.textPrimary)
    }
}
