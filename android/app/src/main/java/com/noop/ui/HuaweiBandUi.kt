package com.noop.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.ble.HuaweiConnectReadout
import com.noop.ble.HuaweiSource
import com.noop.huawei.protocol.HuaweiHex

/** Pure helpers behind the Huawei band panel, the wizard and the Test Centre card. */
internal object HuaweiBandUi {

    /** The registry capability tokens a Band 10 row is stored with (what its history sync can fill). */
    const val CAPABILITIES = "hr,sleep,steps,spo2,skinTemp"

    /** How long the band shows its confirm prompt during a first pairing (the auth machine's 30 s window). */
    const val CONFIRM_SECONDS = 30

    /** The wizard's Confirm step gives up after this long with no READY or failure from the source. */
    const val PAIRING_GIVE_UP_MS = 45_000L

    @StringRes
    fun statusRes(phase: HuaweiSource.LinkPhase, syncing: Boolean): Int = when {
        syncing && phase == HuaweiSource.LinkPhase.READY -> R.string.huawei_panel_status_syncing
        else -> when (phase) {
            HuaweiSource.LinkPhase.DISCONNECTED -> R.string.huawei_panel_status_disconnected
            HuaweiSource.LinkPhase.CONNECTING -> R.string.huawei_panel_status_connecting
            HuaweiSource.LinkPhase.AUTHENTICATING -> R.string.huawei_panel_status_authenticating
            HuaweiSource.LinkPhase.READY -> R.string.huawei_panel_status_ready
            HuaweiSource.LinkPhase.NEEDS_PAIRING -> R.string.huawei_panel_status_needs_pairing
            HuaweiSource.LinkPhase.FAILED -> R.string.huawei_panel_status_failed
        }
    }

    /** "Sync now" is offered whenever it does something: a sync when READY, a reconnect after a stop. */
    fun canSyncNow(phase: HuaweiSource.LinkPhase, syncing: Boolean): Boolean = when (phase) {
        HuaweiSource.LinkPhase.READY -> !syncing
        HuaweiSource.LinkPhase.DISCONNECTED, HuaweiSource.LinkPhase.FAILED -> true
        else -> false
    }

    /** Seconds left on the band's confirm prompt, counted from when the prompt was raised. */
    fun secondsLeft(startedAtMs: Long, nowMs: Long): Int =
        (CONFIRM_SECONDS - ((nowMs - startedAtMs).coerceAtLeast(0) / 1000).toInt()).coerceIn(0, CONFIRM_SECONDS)

    /** The wizard's Pick filter accepts an optional account id only when empty or 17 digits. */
    fun accountIdAcceptable(draft: String): Boolean =
        draft.isBlank() || com.noop.ble.HuaweiKeyStore.isValidAccountId(draft.trim())

    /** Capability bits for the Test Centre: the expand bitmap in hex plus the file-sync settings bits. */
    fun capabilityLine(readout: HuaweiConnectReadout): String? {
        val caps = readout.capabilities ?: return null
        val expand = caps.expand?.raw?.let { HuaweiHex.encode(it) } ?: "-"
        val s = caps.settings
        val settings = if (s == null) "-" else listOfNotNull(
            "sleep".takeIf { s.truSleepFileSync }, "rri".takeIf { s.rriFileSync }, "gps".takeIf { s.gpsFileSync },
        ).joinToString("+").ifEmpty { "0" }
        return "37=$expand 31=$settings"
    }

    /** Supported services and how many commands each confirmed, e.g. "01(7) 07(4)". */
    fun servicesLine(readout: HuaweiConnectReadout): String? {
        val caps = readout.capabilities ?: return null
        if (caps.services.isEmpty()) return null
        return caps.services.sorted().joinToString(" ") { sid ->
            val n = caps.commands[sid]?.size
            "%02X".format(sid) + (n?.let { "($it)" } ?: "")
        }
    }
}

/**
 * The Huawei band panel under the active band's device card: model, firmware, battery, link phase, last
 * sync, "Sync now" and "Pair again" (which opens the pairing wizard; pairing never starts from here).
 */
@Composable
internal fun HuaweiBandPanel(viewModel: AppViewModel, onPairAgain: () -> Unit, modifier: Modifier = Modifier) {
    val source by viewModel.huaweiSource.collectAsStateWithLifecycle()
    val s = source ?: return
    val phase by s.linkPhase.collectAsStateWithLifecycle()
    val syncing by s.syncing.collectAsStateWithLifecycle()
    val battery by s.battery.collectAsStateWithLifecycle()
    val product by s.product.collectAsStateWithLifecycle()
    val lastSyncAt by s.lastSyncAt.collectAsStateWithLifecycle()

    NoopCard(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space10)) {
            Overline(uiString(R.string.huawei_band_title))
            Text(uiString(HuaweiBandUi.statusRes(phase, syncing)), style = NoopType.headline, color = Palette.textPrimary)
            product?.let { p ->
                Text(uiString(R.string.huawei_panel_model, p.productModel), style = NoopType.subhead, color = Palette.textSecondary)
                Text(uiString(R.string.huawei_panel_firmware, p.softwareVersion), style = NoopType.subhead, color = Palette.textSecondary)
            }
            Text(
                battery?.let { uiString(R.string.huawei_panel_battery, it) } ?: uiString(R.string.huawei_panel_battery_unknown),
                style = NoopType.subhead,
                color = Palette.textSecondary,
            )
            Text(
                lastSyncAt?.let { uiString(R.string.huawei_panel_last_sync, relativeAgo(it)) } ?: uiString(R.string.huawei_panel_not_synced),
                style = NoopType.subhead,
                color = Palette.textSecondary,
            )
            NoopButton(
                text = uiString(R.string.huawei_panel_sync_now),
                leadingIcon = Icons.Filled.Sync,
                kind = NoopButtonKind.Secondary,
                fullWidth = true,
                enabled = HuaweiBandUi.canSyncNow(phase, syncing),
                onClick = { viewModel.huaweiSyncNow() },
            )
            NoopButton(
                text = uiString(R.string.huawei_panel_pair_again),
                leadingIcon = Icons.Filled.Link,
                kind = NoopButtonKind.Secondary,
                fullWidth = true,
                onClick = onPairAgain,
            )
        }
    }
}

/** The Live screen's note for a Huawei band: it has no realtime heart-rate channel. */
@Composable
internal fun HuaweiLiveNote() {
    NoopCard {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
            Overline(uiString(R.string.huawei_band_title))
            Text(uiString(R.string.huawei_live_no_hr), style = NoopType.subhead, color = Palette.textSecondary)
        }
    }
}

/** The Test Centre's Huawei connection readout, shown while a Huawei band is the active source. */
@Composable
internal fun HuaweiTestCentreCard(viewModel: AppViewModel) {
    val source by viewModel.huaweiSource.collectAsStateWithLifecycle()
    val s = source ?: return
    val phase by s.linkPhase.collectAsStateWithLifecycle()
    val readout by s.readout.collectAsStateWithLifecycle()
    val none = uiString(R.string.l10n_test_centre_screen_none_71f8e797)
    NoopCard(tint = Palette.accent) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space4)) {
            Overline(uiString(R.string.huawei_band_title))
            HuaweiReadoutRow(uiString(R.string.l10n_test_centre_screen_link_d0517071), phase.name.lowercase())
            HuaweiReadoutRow(uiString(R.string.huawei_tc_auth_mode), readout.authMode?.name?.lowercase() ?: none)
            HuaweiReadoutRow(uiString(R.string.huawei_tc_auth_version), readout.authVersion?.toString() ?: none)
            HuaweiReadoutRow(uiString(R.string.huawei_tc_cipher), readout.cipher ?: none)
            HuaweiReadoutRow(uiString(R.string.huawei_tc_slice_size), readout.sliceSize?.toString() ?: none)
            HuaweiReadoutRow("MTU", readout.mtu?.toString() ?: none)
            HuaweiReadoutRow(uiString(R.string.huawei_tc_capability_bits), HuaweiBandUi.capabilityLine(readout) ?: none)
            HuaweiReadoutRow(uiString(R.string.huawei_tc_services), HuaweiBandUi.servicesLine(readout) ?: none)
            HuaweiReadoutRow(uiString(R.string.l10n_test_centre_screen_sessions_e11e37a9), readout.connects.toString())
            HuaweiReadoutRow(uiString(R.string.l10n_test_centre_screen_last_failure_d11afcf4), readout.lastFailure ?: none)
        }
    }
}

@Composable
private fun HuaweiReadoutRow(name: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Metrics.space12)) {
        Text(name, style = NoopType.caption, color = Palette.textTertiary)
        Spacer(Modifier.weight(1f))
        Text(value, style = NoopType.caption, color = Palette.textPrimary)
    }
}
