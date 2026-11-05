package com.noop.ui

import androidx.annotation.StringRes
import com.noop.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.analytics.HrZones
import com.noop.analytics.NapCandidate
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.noop.analytics.ClockFormat

/**
 * Automations — turn the strap's physical inputs (double-tap, wrist on/off) and live
 * biometrics into on-device actions and haptic coaching. HR-zone coaching, the smart alarm
 * and the illness watch are real + persisted (ViewModel-backed).
 */
@Composable
fun AutomationsScreen(viewModel: AppViewModel) {
    val live by viewModel.live.collectAsStateWithLifecycle()

    // Double-tap action (parity since 4.2.8) — real + persisted via the ViewModel (NoopPrefs). The
    // dispatch runs in the ViewModel on a fresh strap DOUBLE_TAP event; this card just edits the choice.
    val doubleTapAction by viewModel.doubleTapAction.collectAsStateWithLifecycle()

    // (#766) The strap firmware wake-alarm state used to be read here; it moved to SmartAlarmScreen with
    // the rest of the alarm UI.
    // Illness watch is real + persisted (opt-OUT — the watch has always run on Android).
    val illnessWatch by viewModel.illnessWatchEnabled.collectAsStateWithLifecycle()
    // Battery alerts are real + persisted (opt-OUT, default ON; #368, thanks @ujix).
    val batteryAlerts by viewModel.batteryAlertsEnabled.collectAsStateWithLifecycle()
    val predictiveBatteryAlerts by viewModel.predictiveBatteryAlertsEnabled.collectAsStateWithLifecycle()
    val ctx = LocalContext.current

    // HR-zone coaching is real + persisted (zone-based, mirrors macOS): the ViewModel owns the toggle +
    // recovery option and buzzes the strap on entering the top zone (and Zone 1 if recovery is on).
    val profile = remember { ProfileStore.from(ctx.applicationContext) }
    val zoneCoaching by viewModel.zoneCoaching.collectAsStateWithLifecycle()
    val zoneCoachRecovery by viewModel.zoneCoachRecovery.collectAsStateWithLifecycle()
    // The Zone 5 entry threshold (≥ 90% of HR-max), from the same HrZones model used everywhere.
    val zone5Bpm = remember(profile.hrMax) {
        profile.hrZoneSet.zones.firstOrNull { it.number == 5 }?.lower?.roundToInt() ?: 0
    }

    // Inactivity reminder (#419) — real + persisted via InactivityPrefs (opt-in, default OFF). Seeded
    // once, written through on change (SharedPreferences isn't reactive). The buzz itself fires from the
    // BLE offload path (WhoopBleClient.maybeBuzzInactivity → the shipped SedentaryDetector engine); this
    // screen only edits the prefs the engine reads.
    var inactivityEnabled by remember { mutableStateOf(InactivityPrefs.enabled(ctx)) }
    var inactivityThreshold by remember { mutableStateOf(InactivityPrefs.thresholdMinutes(ctx)) }
    var inactivityReNudge by remember { mutableStateOf(InactivityPrefs.reNudgeMinutes(ctx)) }
    var inactivityBuzzLoops by remember { mutableStateOf(InactivityPrefs.buzzLoops(ctx)) }
    var inactivityActiveHours by remember { mutableStateOf(InactivityPrefs.activeHoursEnabled(ctx)) }
    var inactivityActiveStart by remember { mutableStateOf(InactivityPrefs.activeStartMinutes(ctx)) }
    var inactivityActiveEnd by remember { mutableStateOf(InactivityPrefs.activeEndMinutes(ctx)) }
    // The engine also requires the global notification master (default OFF); surface that dependency so
    // enabling the reminder while master is off isn't silently inert.
    val notifMasterOn = NotifPrefs.getBool(ctx, NotifPrefs.MASTER, false)

    // #haptics (#1115): per-event toggles for NOOP's IN-SESSION strap buzzes. Default ON (opt-out) — these
    // are feedback to a feature you started, so a fresh install buzzes as before and a user turns off any
    // cue they find noisy. Ambient cues (inactivity / calls / notifications) keep their own opt-in sections;
    // double-tap is gated by its action picker, not here. SharedPreferences isn't reactive → read into state.
    var breathingHaptic by remember { mutableStateOf(HapticPrefs.enabled(ctx, HapticPrefs.BREATHING)) }
    var intervalsHaptic by remember { mutableStateOf(HapticPrefs.enabled(ctx, HapticPrefs.INTERVALS)) }
    var liveSessionHaptic by remember { mutableStateOf(HapticPrefs.enabled(ctx, HapticPrefs.LIVE_SESSION)) }
    var workoutHaptic by remember { mutableStateOf(HapticPrefs.enabled(ctx, HapticPrefs.WORKOUT)) }

    // PERF (#707): lazy scaffold — each settings section is an unconditional top-level child, so each
    // becomes one `item { }` in the same order. No standalone Spacers (the eager `spacedBy(20.dp)` is
    // reproduced by the LazyColumn), so spacing is byte-identical; only on-screen sections compose + get
    // accessibility-walked on scroll.
    LazyScreenScaffold(
        title = uiString(R.string.l10n_automations_screen_automations_82542d6d),
        subtitle = uiString(R.string.l10n_automations_screen_make_the_strap_do_things_tap_f0de13d0),
    ) {
        // #haptics (#1115): per-event in-session haptic toggles, default ON (opt-out) — turn off any cue you
        // find noisy; the feature keeps working, just without that wrist buzz.
        item {
        SettingsSection(
            icon = Icons.Filled.Vibration,
            title = uiString(R.string.haptics_section_title),
            blurb = uiString(R.string.l10n_automations_screen_choose_which_in_session_cues_buzz_4f0f0ec8),
            active = breathingHaptic || intervalsHaptic || liveSessionHaptic || workoutHaptic,
        ) {
            ToggleRow(
                label = uiString(R.string.haptics_breathing_label),
                help = uiString(R.string.l10n_automations_screen_buzz_each_inhale_and_exhale_during_8453ca95),
                checked = breathingHaptic,
                onChange = { breathingHaptic = it; HapticPrefs.setEnabled(ctx, HapticPrefs.BREATHING, it) },
            )
            RowDivider()
            ToggleRow(
                label = uiString(R.string.haptics_intervals_label),
                help = uiString(R.string.l10n_automations_screen_buzz_on_each_interval_change_20f3be61),
                checked = intervalsHaptic,
                onChange = { intervalsHaptic = it; HapticPrefs.setEnabled(ctx, HapticPrefs.INTERVALS, it) },
            )
            RowDivider()
            ToggleRow(
                label = uiString(R.string.haptics_live_session_label),
                help = uiString(R.string.l10n_automations_screen_coaching_buzzes_during_a_live_workout_4642e54b),
                checked = liveSessionHaptic,
                onChange = { liveSessionHaptic = it; HapticPrefs.setEnabled(ctx, HapticPrefs.LIVE_SESSION, it) },
            )
            RowDivider()
            ToggleRow(
                label = uiString(R.string.haptics_workout_label),
                help = uiString(R.string.l10n_automations_screen_a_buzz_confirms_a_workout_starting_0263ba2e),
                checked = workoutHaptic,
                onChange = { workoutHaptic = it; HapticPrefs.setEnabled(ctx, HapticPrefs.WORKOUT, it) },
            )
        }
        }
        // Double-tap (parity since 4.2.8): a real, persisted action picker bound to the ViewModel, with a
        // Test action button. Mirrors AutomationsView.swift's Picker (Apple-applicable subset only; no
        // lockScreen / runShortcut on Android).
        item {
        SettingsSection(
            icon = Icons.Filled.TouchApp,
            title = uiString(R.string.l10n_automations_screen_double_tap_8d2f1646),
            blurb = uiString(R.string.l10n_automations_screen_double_tap_the_strap_to_trigger_f1273b28),
            active = doubleTapAction != DoubleTapAction.NONE,
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(uiString(R.string.l10n_automations_screen_when_i_double_tap_a1f1e04d), style = NoopType.body, color = Palette.textPrimary)
                Spacer(Modifier.weight(1f))
                DoubleTapActionPicker(
                    selected = doubleTapAction,
                    onSelect = { viewModel.setDoubleTapAction(it) },
                )
            }
            RowDivider()
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = { viewModel.testDoubleTapAction() },
                    enabled = doubleTapAction != DoubleTapAction.NONE,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.accent),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(uiString(R.string.l10n_automations_screen_test_action_266df314), style = NoopType.body)
                }
                Spacer(Modifier.weight(1f))
                StatePill(
                    if (live.bonded) uiString(R.string.l10n_automations_screen_strap_bonded_cbf39bda) else uiString(R.string.today_not_connected),
                    tone = if (live.bonded) StrandTone.Positive else StrandTone.Warning,
                )
            }
        }
        }

        // Haptic coaching.
        item {
        SettingsSection(
            icon = Icons.Filled.Bolt,
            title = uiString(R.string.l10n_automations_screen_haptic_coaching_e2fab286),
            blurb = uiString(R.string.l10n_automations_screen_train_by_feel_the_strap_buzzes_463f60c2),
            active = zoneCoaching,
        ) {
            ToggleRow(
                label = uiString(R.string.l10n_automations_screen_hr_zone_coaching_9306e6e1),
                help = uiString(R.string.l10n_automations_screen_a_triple_buzz_when_you_climb_7c3c634b, zone5Bpm),
                checked = zoneCoaching,
                onChange = { viewModel.setZoneCoaching(it) },
            )
            if (zoneCoaching) {
                RowDivider()
                ToggleRow(
                    label = uiString(R.string.l10n_automations_screen_recovery_buzz_1abc9a51),
                    help = uiString(R.string.l10n_automations_screen_also_buzz_once_when_your_heart_b96aee2b),
                    checked = zoneCoachRecovery,
                    onChange = { viewModel.setZoneCoachRecovery(it) },
                )
            }
        }
        }

        // #766: the strap's silent wake-alarm card used to sit here, which let users conflate it with the
        // Wake Window + Wind-Down reminder over on the Alarms screen. It's moved to SmartAlarmScreen so
        // every wake/alarm control lives in one place. Automations is just inputs-to-actions now.

        // Inactivity reminder (#419) — real + persisted via InactivityPrefs; opt-in, default OFF.
        item {
        SettingsSection(
            icon = Icons.Filled.Timer,
            title = uiString(R.string.l10n_automations_screen_inactivity_reminder_ca49b1ba),
            blurb = uiString(R.string.l10n_automations_screen_a_gentle_wrist_buzz_when_you_022719d3),
            active = inactivityEnabled,
        ) {
            ToggleRow(
                label = uiString(R.string.l10n_automations_screen_enable_inactivity_reminder_468c3017),
                help = uiString(R.string.l10n_automations_screen_buzzes_after_you_ve_been_sitting_1597d772),
                checked = inactivityEnabled,
                onChange = {
                    inactivityEnabled = it
                    InactivityPrefs.setBool(ctx, InactivityPrefs.ENABLED, it)
                },
            )
            if (inactivityEnabled) {
                if (!notifMasterOn) {
                    RowDivider()
                    Text(
                        uiString(R.string.l10n_automations_screen_notifications_are_off_so_this_can_b3dba7ee),
                        style = NoopType.footnote, color = Palette.statusWarning,
                    )
                }
                RowDivider()
                StepperRow(
                    label = uiString(R.string.l10n_automations_screen_sitting_for_e464c472),
                    help = uiString(R.string.l10n_automations_screen_minutes_seated_before_the_first_nudge_7d30de20),
                    value = inactivityThreshold, suffix = "min", range = 15..120, step = 15,
                    onChange = {
                        inactivityThreshold = it
                        InactivityPrefs.setInt(ctx, InactivityPrefs.THRESHOLD_MIN, it)
                    },
                )
                RowDivider()
                StepperRow(
                    label = uiString(R.string.l10n_automations_screen_re_nudge_every_646023cd),
                    help = uiString(R.string.l10n_automations_screen_if_you_re_still_seated_buzz_e248b041),
                    value = inactivityReNudge, suffix = "min", range = 15..120, step = 15,
                    onChange = {
                        inactivityReNudge = it
                        InactivityPrefs.setInt(ctx, InactivityPrefs.RENUDGE_MIN, it)
                    },
                )
                RowDivider()
                StepperRow(
                    label = uiString(R.string.l10n_automations_screen_buzz_strength_e895f99e),
                    help = uiString(R.string.l10n_automations_screen_how_strong_the_buzz_is_6ccd8625),
                    value = inactivityBuzzLoops, suffix = "×", range = 1..4, step = 1,
                    onChange = {
                        inactivityBuzzLoops = it
                        InactivityPrefs.setInt(ctx, InactivityPrefs.BUZZ_LOOPS, it)
                    },
                )
                RowDivider()
                ToggleRow(
                    label = uiString(R.string.l10n_automations_screen_only_during_active_hours_29c53fc9),
                    help = uiString(R.string.l10n_automations_screen_only_nudge_during_your_active_hours_f308e0a9),
                    checked = inactivityActiveHours,
                    onChange = {
                        inactivityActiveHours = it
                        InactivityPrefs.setBool(ctx, InactivityPrefs.ACTIVE_HOURS_ENABLED, it)
                    },
                )
                if (inactivityActiveHours) {
                    RowDivider()
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(uiString(R.string.l10n_automations_screen_from_3f66052a), style = NoopType.body, color = Palette.textPrimary)
                        Spacer(Modifier.weight(1f))
                        TimeChip(
                            minutes = inactivityActiveStart,
                            accessibilityLabel = uiString(R.string.l10n_automations_screen_active_hours_start_0e4bea34),
                            onPicked = {
                                inactivityActiveStart = it
                                InactivityPrefs.setInt(ctx, InactivityPrefs.ACTIVE_START_MIN, it)
                            },
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(uiString(R.string.l10n_automations_screen_to_4374aaee), style = NoopType.body, color = Palette.textSecondary)
                        Spacer(Modifier.width(8.dp))
                        TimeChip(
                            minutes = inactivityActiveEnd,
                            accessibilityLabel = uiString(R.string.l10n_automations_screen_active_hours_end_9c483811),
                            onPicked = {
                                inactivityActiveEnd = it
                                InactivityPrefs.setInt(ctx, InactivityPrefs.ACTIVE_END_MIN, it)
                            },
                        )
                    }
                }
            }
        }
        }

        // On-device short-nap detection (PR #569 reimpl) — opt-in, default OFF. Detected on the offload
        // hook; a confident nap is offered as a review card you accept (it becomes a nap session) or
        // dismiss. NEVER auto-written.
        item { NapDetectionSection(viewModel) }

        // Illness early-warning (real + persisted; opt-OUT — the watch has always run on Android).
        item {
        SettingsSection(
            icon = Icons.Filled.MonitorHeart,
            title = uiString(R.string.l10n_automations_screen_illness_early_warning_453ab477),
            blurb = uiString(R.string.l10n_automations_screen_watches_your_resting_hr_hrv_skin_1cac89f1),
            active = illnessWatch,
        ) {
            ToggleRow(
                label = uiString(R.string.l10n_automations_screen_watch_for_early_illness_signs_4c22e127),
                help = uiString(R.string.l10n_automations_screen_needs_at_least_14_days_of_8db35695),
                checked = illnessWatch,
                onChange = { viewModel.setIllnessWatchEnabled(it) },
            )
        }
        }

        // Battery alerts (real + persisted; opt-OUT, default ON — #368, thanks @ujix).
        item {
        SettingsSection(
            icon = Icons.Filled.BatteryStd,
            title = uiString(R.string.l10n_automations_screen_battery_alerts_f3679d60),
            blurb = uiString(R.string.l10n_automations_screen_a_heads_up_when_the_strap_ab7d84fe),
            active = batteryAlerts,
        ) {
            ToggleRow(
                label = uiString(R.string.l10n_automations_screen_notify_on_low_and_full_battery_d1903bb8),
                help = uiString(R.string.l10n_automations_screen_sends_a_notification_when_the_strap_28d36e37),
                checked = batteryAlerts,
                onChange = { viewModel.setBatteryAlertsEnabled(it) },
            )
            if (batteryAlerts) {
                ToggleRow(
                    label = uiString(R.string.l10n_automations_screen_predictive_runtime_warning_4d85f5a6),
                    help = uiString(R.string.l10n_automations_screen_an_early_recharge_tonight_heads_up_c470e1d0),
                    checked = predictiveBatteryAlerts,
                    onChange = { viewModel.setPredictiveBatteryAlertsEnabled(it) },
                )
            }
        }
        }
    }
}

// MARK: - On-device nap detection (PR #569 reimpl under NoopApp)

/**
 * The nap-detection automation: a toggle plus the REVIEW queue. Detection runs on the offload hook
 * (WhoopBleClient.maybeDetectNaps → the pure NapDetector); a confident NAP is queued in NapStore and shown
 * here as a card the user ACCEPTS (→ a manual nap session, the #508 path) or DISMISSES. The engine never
 * auto-writes a session, and an INCONCLUSIVE window queues nothing — honest by construction.
 */
@Composable
private fun NapDetectionSection(viewModel: AppViewModel) {
    val scope = rememberCoroutineScope()
    val enabled by viewModel.napDetectionEnabled.collectAsStateWithLifecycle()
    // The queue isn't a reactive flow (it's written from the BLE layer); re-read it on each toggle/action.
    var pending by remember { mutableStateOf(viewModel.pendingNaps()) }

    SettingsSection(
        icon = Icons.Filled.Bedtime,
        title = uiString(R.string.l10n_automations_screen_nap_detection_ca2dedf5),
        blurb = uiString(R.string.l10n_automations_screen_spots_a_likely_daytime_nap_from_694ebb70),
        active = enabled,
    ) {
        ToggleRow(
            label = uiString(R.string.l10n_automations_screen_detect_short_naps_bbfd136d),
            help = uiString(R.string.l10n_automations_screen_when_a_sync_shows_a_quiet_2140e9fe),
            checked = enabled,
            onChange = {
                viewModel.setNapDetectionEnabled(it)
                if (it) pending = viewModel.pendingNaps()
            },
        )
        if (enabled) {
            if (pending.isEmpty()) {
                RowDivider()
                Text(
                    uiString(R.string.l10n_automations_screen_no_naps_to_review_detected_naps_2e82e9fc),
                    style = NoopType.footnote, color = Palette.textTertiary,
                )
            } else {
                pending.forEach { nap ->
                    RowDivider()
                    NapReviewRow(
                        nap = nap,
                        onAccept = { scope.launch { pending = viewModel.acceptDetectedNap(nap) } },
                        onDismiss = { pending = viewModel.dismissDetectedNap(nap) },
                    )
                }
            }
        }
    }
}

/** One pending nap candidate: an honest "HH:mm–HH:mm · ~N min" line (+ mean HR when known) with Keep /
 *  Skip controls. Keep persists it as a nap session; Skip forgets it (and won't re-queue the window). */
@Composable
private fun NapReviewRow(nap: NapCandidate, onAccept: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(napWindowLabel(nap, ctx), style = NoopType.body, color = Palette.textPrimary)
            Text(napDetailLabel(nap), style = NoopType.footnote, color = Palette.textTertiary)
        }
        Spacer(Modifier.width(8.dp))
        NapActionButton(Icons.Filled.Check, uiString(R.string.l10n_automations_screen_keep_this_nap_ddc2e815), Palette.statusPositive, onAccept)
        Spacer(Modifier.width(8.dp))
        NapActionButton(Icons.Filled.Close, uiString(R.string.l10n_automations_screen_skip_this_nap_c84ac777), Palette.textTertiary, onDismiss)
    }
}

@Composable
private fun NapActionButton(icon: ImageVector, contentDescription: String, tint: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(Palette.surfaceInset)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(18.dp))
    }
}

/** "HH:mm–HH:mm · ~N min", local time. Pure-ish (reads the device clock format only). */
private fun napWindowLabel(nap: NapCandidate, ctx: android.content.Context): String {
    // #1821: the reader's chosen clock, not the raw device format.
    val fmt = java.text.SimpleDateFormat(
        ClockFormat.hourMinutePattern(ClockPrefs.uses24Hour(ctx)), java.util.Locale.getDefault(),
    )
    val start = fmt.format(java.util.Date(nap.start * 1000L))
    val end = fmt.format(java.util.Date(nap.end * 1000L))
    val mins = nap.durationS / 60
    return uiString(R.string.l10n_automations_screen_1_s_2_s_3_d_4420e580, start, end, mins)
}

private fun napDetailLabel(nap: NapCandidate): String =
    if (nap.meanHr != null) uiString(R.string.l10n_automations_screen_quiet_and_settled_mean_hr_1_a752b883, nap.meanHr) else uiString(R.string.l10n_automations_screen_quiet_and_settled_d11e7d4a)

// MARK: - Per-weekday wake-time overrides (PR #554 reimpl under NoopApp)

/**
 * Per-weekday wake-time OVERRIDES for the smart alarm (#554). For each day the alarm fires on, shows the
 * effective wake time (the day's override, else the default) as a [TimeChip]; picking a time sets that
 * day's override, and a "Reset" affordance clears it back to the default. Days the alarm doesn't fire on
 * aren't shown (no point overriding a day it won't ring). Empty enabledDays = every day, so all seven show.
 */
// internal (not private) so the consolidated Alarms screen (SmartAlarmScreen, #766) can reuse the
// exact same picker. The strap wake-alarm card moved there but its weekday/override UI is unchanged.
@Composable
internal fun AlarmDayOverridePicker(
    defaultMinutes: Int,
    enabledDays: Set<Int>,
    overrides: Map<Int, Int>,
    onSetOverride: (Int, Int?) -> Unit,
) {
    val fireDays = SMART_ALARM_WEEKDAY_ORDER.filter { smartAlarmWeekdayIsSelected(it, enabledDays) }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(uiString(R.string.l10n_automations_screen_per_day_wake_time_873c81e1), style = NoopType.caption, color = Palette.textTertiary)
        fireDays.forEach { dow ->
            val effective = overrides[dow] ?: defaultMinutes
            val hasOverride = overrides.containsKey(dow)
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(smartAlarmWeekdayName(dow), style = NoopType.body, color = Palette.textPrimary)
                Spacer(Modifier.weight(1f))
                if (hasOverride) {
                    Text(
                        uiString(R.string.l10n_automations_screen_reset_44c57abd),
                        style = NoopType.caption,
                        color = Palette.accent,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { onSetOverride(dow, null) }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                TimeChip(
                    minutes = effective,
                    accessibilityLabel = uiString(R.string.l10n_automations_screen_1_s_wake_time_20e25a1c, smartAlarmWeekdayName(dow)),
                    onPicked = { onSetOverride(dow, it) },
                )
            }
        }
        Text(
            uiString(R.string.l10n_automations_screen_each_day_uses_the_time_above_f9bc9ce3),
            style = NoopType.footnote, color = Palette.textTertiary,
        )
    }
}

// MARK: - Section + rows (mirror the settings idiom from AutomationsView.swift)

@Composable
private fun SettingsSection(
    icon: ImageVector,
    title: String,
    blurb: String,
    active: Boolean = false,
    content: @Composable () -> Unit,
) {
    NoopCard(padding = 20.dp, tint = Palette.accent) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Overline(uiString(R.string.l10n_automations_screen_automation_a15fde51))
                    if (active) Overline(uiString(R.string.l10n_automations_screen_on_387d7a59), color = Palette.accent)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = if (active) Palette.accent else Palette.textSecondary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(title, style = NoopType.title2, color = Palette.textPrimary)
                }
            }
            Text(blurb, style = NoopType.subhead, color = Palette.textSecondary)
            content()
        }
    }
}

/** A compact dropdown that mirrors the iOS double-tap Picker: a tappable label + chevron that opens a
 *  menu of [DoubleTapAction]s. Labels come from [DoubleTapAction.label] so both clients read the same. */
@Composable
private fun DoubleTapActionPicker(
    selected: DoubleTapAction,
    onSelect: (DoubleTapAction) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .clickable { expanded = true }
                .background(Palette.surfaceInset)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(selected.label, style = NoopType.body, color = Palette.textPrimary)
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = uiString(R.string.l10n_automations_screen_choose_double_tap_action_6ac9a313),
                tint = Palette.textSecondary,
                modifier = Modifier.size(18.dp),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (action in DoubleTapAction.entries) {
                DropdownMenuItem(
                    text = {
                        Text(
                            action.label,
                            style = NoopType.body,
                            color = if (action == selected) Palette.accent else Palette.textPrimary,
                        )
                    },
                    onClick = { onSelect(action); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    help: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = NoopType.body, color = Palette.textPrimary)
            Text(help, style = NoopType.footnote, color = Palette.textTertiary)
        }
        Spacer(Modifier.width(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Palette.surfaceBase,
                checkedTrackColor = Palette.accent,
                uncheckedThumbColor = Palette.textSecondary,
                uncheckedTrackColor = Palette.surfaceInset,
                uncheckedBorderColor = Palette.hairline,
            ),
        )
    }
}

@Composable
private fun RowDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .padding(vertical = 4.dp)
            .background(Palette.hairline),
    )
}

/**
 * Weekday selector for the smart alarm (#539). One tappable circle per weekday, Monday-first. An empty
 * [selected] set means "every day" (all circles read as on). Mirrors the macOS AutomationsView picker.
 */
// internal (not private) so SmartAlarmScreen (the consolidated Alarms surface, #766) can reuse it.
@Composable
internal fun AlarmWeekdayPicker(selected: Set<Int>, onToggle: (Int) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (dow in SMART_ALARM_WEEKDAY_ORDER) {
                val on = smartAlarmWeekdayIsSelected(dow, selected)
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(if (on) Palette.accent else Palette.surfaceInset)
                        .clickable { onToggle(dow) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        smartAlarmWeekdayInitial(dow),
                        style = NoopType.caption,
                        color = if (on) Palette.surfaceBase else Palette.textSecondary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        Text(smartAlarmWeekdaySummary(selected), style = NoopType.caption, color = Palette.textTertiary)
    }
}

/** Calendar.DAY_OF_WEEK numbers laid out Monday-first (Mon…Sun → 2,3,4,5,6,7,1). */
private val SMART_ALARM_WEEKDAY_ORDER = intArrayOf(2, 3, 4, 5, 6, 7, 1)

/** A day reads as "on" when the set is empty (= every day) or explicitly contains it. Pure for tests. */
internal fun smartAlarmWeekdayIsSelected(dow: Int, days: Set<Int>): Boolean =
    days.isEmpty() || days.contains(dow)

/**
 * Toggle one weekday, normalising "every day" at both ends so the empty set always means every day.
 * Pure + side-effect-free for unit tests. Pulling a day out of the implicit "every day" expands to the
 * explicit other six; selecting the seventh collapses back to the empty "every day" set. Mirrors macOS
 * `AutomationsView.toggledWeekday`.
 */
internal fun toggledSmartAlarmWeekday(dow: Int, days: Set<Int>): Set<Int> {
    val next: MutableSet<Int> = when {
        days.isEmpty() -> (1..7).toMutableSet().also { it.remove(dow) }
        days.contains(dow) -> days.toMutableSet().also { it.remove(dow) }
        else -> days.toMutableSet().also { it.add(dow) }
    }
    return if (next.size == 7) emptySet() else next
}

/** Resolves a localized label, falling back to [english] where no Application exists (JVM unit tests). */
private fun weekdayString(@StringRes id: Int, english: String): String =
    runCatching { uiString(id) }.getOrDefault(english)

/** Human-readable summary of the selection. Pure for tests. Mirrors macOS `weekdaySummary`. */
internal fun smartAlarmWeekdaySummary(days: Set<Int>): String = when {
    days.isEmpty() || days.size == 7 -> weekdayString(R.string.l10n_automations_screen_every_day_3b2eb513, "Every day")
    days == setOf(2, 3, 4, 5, 6) -> weekdayString(R.string.l10n_automations_screen_weekdays_4ffc67b6, "Weekdays")
    days == setOf(1, 7) -> weekdayString(R.string.l10n_automations_screen_weekends_2f92a0aa, "Weekends")
    else -> SMART_ALARM_WEEKDAY_ORDER.filter { days.contains(it) }
        .joinToString(", ") { smartAlarmWeekdayName(it) }
}

private fun smartAlarmWeekdayInitial(dow: Int): String = when (dow) {
    1 -> "S"; 2 -> "M"; 3 -> "T"; 4 -> "W"; 5 -> "T"; 6 -> "F"; 7 -> "S"; else -> "?"
}

private fun smartAlarmWeekdayName(dow: Int): String = when (dow) {
    1 -> weekdayString(R.string.l10n_automations_screen_sun_48c98cab, "Sun"); 2 -> weekdayString(R.string.l10n_automations_screen_mon_24b2a099, "Mon"); 3 -> weekdayString(R.string.l10n_automations_screen_tue_529541bb, "Tue"); 4 -> weekdayString(R.string.l10n_automations_screen_wed_23408b19, "Wed"); 5 -> weekdayString(R.string.l10n_automations_screen_thu_3593ccd9, "Thu"); 6 -> weekdayString(R.string.l10n_automations_screen_fri_bbd6e32e, "Fri"); 7 -> weekdayString(R.string.l10n_automations_screen_sat_6b782d41, "Sat"); else -> "?"
}

/** A label/help row with a −[value]+ stepper, clamped to [range] and moved by [step]. */
@Composable
private fun StepperRow(
    label: String,
    help: String,
    value: Int,
    suffix: String,
    range: IntRange,
    step: Int,
    onChange: (Int) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = NoopType.body, color = Palette.textPrimary)
            Text(help, style = NoopType.footnote, color = Palette.textTertiary)
        }
        Spacer(Modifier.width(12.dp))
        StepButton(Icons.Filled.Remove, uiString(R.string.l10n_components_decrease_accessibility_df5f1511, label), enabled = value > range.first) {
            onChange((value - step).coerceAtLeast(range.first))
        }
        Text(
            uiString(R.string.l10n_automations_screen_value_suffix_26985180, value, suffix),
            style = NoopType.body,
            color = Palette.textPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp).widthIn(min = 56.dp),
        )
        StepButton(Icons.Filled.Add, uiString(R.string.l10n_components_increase_accessibility_0949c0e9, label), enabled = value < range.last) {
            onChange((value + step).coerceAtMost(range.last))
        }
    }
}

@Composable
private fun StepButton(icon: ImageVector, contentDescription: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(Palette.surfaceInset)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (enabled) Palette.accent else Palette.textTertiary,
        )
    }
}
