package com.noop.ble

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.noop.garmin.GarminAdvertisement
import com.noop.garmin.GarminClock
import com.noop.garmin.GarminDeviceInformation
import com.noop.garmin.GarminDownloadedFile
import com.noop.garmin.GarminSyncFileKey
import com.noop.garmin.GarminSyncFlow
import com.noop.garmin.GarminSyncModelPolicy
import com.noop.garmin.GarminSyncRequest
import com.noop.garmin.GarminSyncResume
import com.noop.garmin.GarminSyncSummary
import com.noop.garmin.GarminGattService
import com.noop.garmin.GarminRealtimeCapture
import com.noop.garmin.GarminRealtimeReading
import com.noop.garmin.GarminSession
import com.noop.garmin.GarminSessionConfig
import com.noop.garmin.GarminSessionOutput
import com.noop.garmin.GarminService
import com.noop.garmin.GarminSessionPhase
import com.noop.garmin.GarminTransport
import com.noop.garmin.GarminTransportKind
import com.noop.garmin.GarminTransportSelection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone
import java.util.UUID

/**
 * Garmin watch source over the watch's own GFDI protocol (`com.noop.garmin`). Twin of
 * Strand/BLE/GarminSource.swift.
 *
 * Holds one stable, low-power session: connect by stored address, request MTU 515, pick the transport the
 * watch offers (V2 multi-link, else V1, else V0, else stop and say "unsupported watch protocol"), subscribe,
 * then let the pure [GarminSession] run the handshake. Every byte written comes out of that session, so
 * only allowlisted [com.noop.garmin.GarminOutbound] messages and ML control frames can reach the watch.
 *
 * Battery hooks ([GarminRadioPolicy]): never scans in the background, no keep-alive traffic, the minimal
 * capability set, connection priority LOW_POWER once idle (HIGH only while a history transfer runs), and
 * the host foreground/background events.
 *
 * History sync (read-only): on the watch's own "new data" notices, on connect, on foreground, on the
 * periodic floor and on [syncNow], each gated by [GarminRadioPolicy.shouldSync], one batched session
 * downloads every new FIT file. [loadSynced] (the `garminSyncedFile` ledger) says what is already stored;
 * each file goes to [ingest]. Nothing on the watch is archived, flagged or deleted. A transfer cut short by
 * a dropped link is continued (or, where the watch restarts it, re-fetched) on the next sync.
 * The Test Centre-only capture of the undecoded channels 8 / 10 / 13 ([undecodedCapture]) counts as one
 * more live viewer and writes their payloads raw to the strap log.
 * Runs on the app-scoped [SourceCoordinator], so the existing foreground connection service keeps the
 * process (and this link) alive. Every GATT callback is posted to the main looper, which keeps the
 * session single-threaded. Owns its OWN GATT and never references [WhoopBleClient].
 *
 * Live channels are a VIEWER: the app reports who is watching ([updateLiveDemand]), [GarminRadioPolicy] with
 * its 30 s linger turns that into a channel set, and the session registers / closes the handles. Nothing opens
 * without demand, nothing opens in the background except for an active workout, SpO2 opens only on an explicit
 * request, and the accelerometer never opens. Readings go out through [onLive].
 */
@SuppressLint("MissingPermission")
class GarminSource(
    context: Context,
    private val deviceId: String,
    private val isFirstPairing: () -> Boolean = { false },
    private val markPaired: () -> Unit = {},
    private val log: (String) -> Unit = {},
    /** Per-connect readouts (every message in and out) only while this returns true (a Test Centre domain). */
    private val detail: () -> Boolean = { false },
    private val onBattery: (Int) -> Unit = {},
    private val onWatch: (GarminDeviceInformation) -> Unit = {},
    private val onSyncAnnounced: () -> Unit = {},
    private val loadSynced: suspend () -> Set<GarminSyncFileKey> = { emptySet() },
    private val ingest: suspend (GarminDownloadedFile) -> Boolean = { false },
    private val onSyncFinished: (GarminSyncSummary) -> Unit = {},
    private val syncFlow: (GarminDeviceInformation?) -> GarminSyncFlow = { GarminSyncModelPolicy.flow(it?.productNumber) },
    /** DownloadRequest compression; the policy's value unless a caller needs uncompressed transfers. */
    private val requestsCompression: Boolean = GarminRadioPolicy.REQUESTS_COMPRESSION,
    private val onLive: (GarminLiveEvent) -> Unit = {},
    /**
     * The Test Centre "Garmin realtime capture" toggle. While it returns true and the app is in the foreground,
     * the policy opens HR + R-R + calories / intensity / stress and every 8 / 10 / 13 payload is logged raw.
     */
    private val undecodedCapture: () -> Boolean = { false },
    /** The preferences holding that toggle and its key, so a change applies to the open session at once. */
    private val capturePrefs: SharedPreferences? = null,
    private val captureKey: String? = null,
    /** Wizard scanner: [scan] only lists watches into [discovered] and never attaches. */
    private val discoveryOnly: Boolean = false,
) : LiveHrSource {

    /** BUSY: the link is up but another app (Garmin Connect) holds the watch's GFDI service; the session asks
     *  again on its backoff, on foreground and on "Sync now" (GarminCoexistence). */
    enum class LinkPhase { DISCONNECTED, CONNECTING, DISCOVERING, SUBSCRIBING, HANDSHAKING, BUSY, READY, UNSUPPORTED }

    /** [connected]: listed from the OS's bonded / connected devices rather than an advertisement. */
    data class DiscoveredWatch(val address: String, val name: String, val rssi: Int, val connected: Boolean = false)

    private val _linkPhase = MutableStateFlow(LinkPhase.DISCONNECTED)
    val linkPhase: StateFlow<LinkPhase> = _linkPhase.asStateFlow()
    private val _batteryPct = MutableStateFlow<Int?>(null)
    val batteryPct: StateFlow<Int?> = _batteryPct.asStateFlow()
    /** Set when the watch offers no transport NOOP speaks. The source stops and does not retry. */
    private val _unsupportedReason = MutableStateFlow<String?>(null)
    val unsupportedReason: StateFlow<String?> = _unsupportedReason.asStateFlow()
    private val _discovered = MutableStateFlow<List<DiscoveredWatch>>(emptyList())
    val discovered: StateFlow<List<DiscoveredWatch>> = _discovered.asStateFlow()
    /** A history sync is running. */
    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()
    private val _lastSyncSummary = MutableStateFlow<GarminSyncSummary?>(null)
    val lastSyncSummary: StateFlow<GarminSyncSummary?> = _lastSyncSummary.asStateFlow()
    /** Bytes of the file transfer in flight, null between files (sync status line, Test Centre). */
    val transferOffset: Long? get() = session?.syncResumePoint?.offset
    /** Live channels whose handle is open on the watch right now (the watch confirmed the registration). */
    private val _openLiveChannels = MutableStateFlow<Set<GarminLiveChannel>>(emptySet())
    val openLiveChannels: StateFlow<Set<GarminLiveChannel>> = _openLiveChannels.asStateFlow()
    /** The watch's DeviceInformation once the handshake delivered it. */
    private val _watch = MutableStateFlow<GarminDeviceInformation?>(null)
    val watch: StateFlow<GarminDeviceInformation?> = _watch.asStateFlow()
    /** The GFDI transport chosen for the current link. */
    private val _transportKind = MutableStateFlow<GarminTransportKind?>(null)
    val transportKind: StateFlow<GarminTransportKind?> = _transportKind.asStateFlow()
    /** Unix seconds of the last completed sync (persisted per device). */
    private val _lastSyncAt = MutableStateFlow<Long?>(null)
    val lastSyncAt: StateFlow<Long?> = _lastSyncAt.asStateFlow()
    private val _connectReadout = MutableStateFlow(GarminConnectReadout())
    val connectReadout: StateFlow<GarminConnectReadout> = _connectReadout.asStateFlow()

    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private val handler = Handler(Looper.getMainLooper())

    private var gatt: BluetoothGatt? = null
    private var transport: GarminTransport? = null
    private var notifyChar: BluetoothGattCharacteristic? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var session: GarminSession? = null
    private var mtu = 23
    private val writeQueue = ArrayDeque<ByteArray>()
    private var writeInFlight = false
    private val telemetry = GarminRadioTelemetry()
    /**
     * Capture mode (Test Centre): whether it is on, how each undecoded channel answered on this session (for the
     * one availability line per connect), and the latest live HR for the capture lines' context.
     */
    private var capturing = false
    private val channelAnswers = HashMap<Int, String>()
    private var availabilityLogged = false
    private var contextHr: Int? = null
    private val captureListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == captureKey) onMain { if (undecodedCapture() != capturing) applyLiveDemand() }
    }

    /** Who is watching. `appInForeground` is always overwritten with this source's own foreground state. */
    private var liveDemand = GarminRadioInputs(appInForeground = true)
    private val linger = GarminLiveChannelLinger()
    /** The channel set the policy currently holds open (demand plus linger), handed to every session. */
    private var wantedLive: Set<GarminService> = emptySet()
    private val lingerRecheck = Runnable { applyLiveDemand() }

    private var targetAddress: String? = null
    private var intentionalDisconnect = false
    private var wantsScan = false
    private var failedConnects = 0
    private var foreground = initialForeground()
    private var startedActivities = 0

    // History sync state. lastSyncAt / emptyStreak persist per device so a relaunch keeps the floor.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val ingestLock = Mutex()
    /** The last queued ingest; the lock is fair, so it finishes after every earlier one. */
    private var ingestTail: Job? = null
    private val prefs = appContext.getSharedPreferences("noop_garmin_sync", Context.MODE_PRIVATE)
    private val lastSyncAtKey = "lastAt.$deviceId"
    private val emptyStreakKey = "emptyStreak.$deviceId"
    private val watchInfo: GarminDeviceInformation? get() = _watch.value
    private var handshakeStartedMs: Long? = null
    private var syncPreparing = false
    private var heldResume: GarminSyncResume? = null
    private val periodicSync = Runnable { onMain { requestSync(GarminSyncTrigger.PERIODIC) } }

    private val deadline = Runnable { onMain { session?.let { perform(it.tick(clock())) } } }
    private val reconnectRunnable = Runnable {
        val address = targetAddress
        if (!intentionalDisconnect && address != null) connect(address)
    }

    // LiveHrSource

    /** First-pairing discovery only, and only with the app in the foreground ([GarminRadioPolicy]). */
    override fun scan() {
        if (GarminRadioPolicy.discoveryAction(foreground, hasStoredIdentifier = false) != GarminDiscoveryAction.SCAN) {
            say(true, "scan skipped - the app is in the background (never scans in the background)")
            return
        }
        intentionalDisconnect = false
        wantsScan = true
        val sc = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: run {
            say(true, "scan deferred until Bluetooth is on")
            return
        }
        _discovered.value = if (discoveryOnly) systemWatches() else emptyList()
        say(true, "scanning for a Garmin watch (company id 0x0087 or a Garmin name)")
        runCatching { sc.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback) }
    }

    override fun connect(address: String) {
        handler.removeCallbacks(reconnectRunnable)
        intentionalDisconnect = false
        targetAddress = address
        _unsupportedReason.value = null
        val a = adapter
        if (a == null || !a.isEnabled) {
            say(true, "connect deferred until Bluetooth is on")
            return
        }
        val device = runCatching { a.getRemoteDevice(address) }.getOrNull() ?: run {
            say(true, "invalid watch address; scanning")
            scan()
            return
        }
        attach(device)
    }

    override fun stop() {
        intentionalDisconnect = true
        targetAddress = null
        wantsScan = false
        handler.removeCallbacks(reconnectRunnable)
        handler.removeCallbacks(deadline)
        handler.removeCallbacks(periodicSync)
        handler.removeCallbacks(lingerRecheck)
        stopScan()
        gatt?.let { runCatching { it.disconnect(); it.close() } }
        gatt = null
        endSession()
        // Children only: the same source may be connected again, and its scope must still launch.
        scope.coroutineContext.cancelChildren()
        _linkPhase.value = LinkPhase.DISCONNECTED
        (appContext as? Application)?.unregisterActivityLifecycleCallbacks(lifecycleCallbacks)
        runCatching { appContext.unregisterReceiver(adapterReceiver) }
        capturePrefs?.unregisterOnSharedPreferenceChangeListener(captureListener)
    }

    /** Drop the link and let the ordinary reconnect path bring it back (a "Reconnect" affordance). */
    fun reconnect() {
        if (targetAddress == null) return
        say(true, "reconnect requested; dropping the link")
        gatt?.disconnect()
    }

    /** "Sync now": a manual sync, which the floor never holds back. While another app holds GFDI it asks the
     *  watch for GFDI again instead; the sync runs on connect once the handshake completes. */
    fun syncNow() = onMain {
        retryGfdi()
        requestSync(GarminSyncTrigger.MANUAL)
    }

    /** A BUSY session asks for GFDI again now (the session skips it when it asked moments ago). */
    private fun retryGfdi() {
        val s = session ?: return
        if (s.phase != GarminSessionPhase.Busy) return
        perform(s.retryGfdi(clock()))
    }

    // History sync

    private fun requestSync(trigger: GarminSyncTrigger, watchInitiated: Boolean = false) {
        val s = session ?: return
        if (_linkPhase.value != LinkPhase.READY || s.phase !is GarminSessionPhase.Ready) return
        if (s.isSyncing || syncPreparing) {
            say(false, "sync trigger $trigger ignored; a sync is already running")
            return
        }
        val now = System.currentTimeMillis() / 1000.0
        val last = if (prefs.contains(lastSyncAtKey)) prefs.getLong(lastSyncAtKey, 0).toDouble() else null
        val streak = prefs.getInt(emptyStreakKey, 0)
        if (!GarminRadioPolicy.shouldSync(trigger, now, last, streak)) {
            say(false, "sync trigger $trigger held by the floor (last ${last?.let { (now - it).toInt() } ?: -1} s ago, empty streak $streak)")
            return
        }
        syncPreparing = true
        val flow = syncFlow(watchInfo)
        scope.launch {
            val synced = runCatching { loadSynced() }.getOrDefault(emptySet())
            syncPreparing = false
            val current = session ?: return@launch
            if (current !== s || current.phase !is GarminSessionPhase.Ready || current.isSyncing) return@launch
            val request = GarminSyncRequest(
                flow = flow, synced = synced, resume = heldResume,
                requestCompression = requestsCompression,
                watchInitiated = watchInitiated,
                notBeforeUnix = System.currentTimeMillis() / 1000 - HISTORY_LOOKBACK_SECONDS,
            )
            say(true, "sync ($trigger) starting with ${synced.size} files on record")
            heldResume = null
            perform(current.startSync(request, clock()))
        }
    }

    private fun syncFinished(summary: GarminSyncSummary) {
        _lastSyncSummary.value = summary
        telemetry.syncSessionCompleted()
        if (summary.outcome is GarminSyncSummary.Outcome.Completed) {
            val streak = if (summary.isEmpty) prefs.getInt(emptyStreakKey, 0) + 1 else 0
            val at = System.currentTimeMillis() / 1000
            prefs.edit().putLong(lastSyncAtKey, at).putInt(emptyStreakKey, streak).apply()
            _lastSyncAt.value = at
        }
        session?.syncResumePoint?.let { heldResume = it }
        // Reported once every file of this sync is stored, so a re-analysis sees all of them.
        val tail = ingestTail
        scope.launch {
            tail?.join()
            onSyncFinished(summary)
        }
        schedulePeriodicSync()
    }

    /** Persistent-idle mode: one sync per periodic floor (stretched by the empty streak) while connected. */
    private fun schedulePeriodicSync() {
        handler.removeCallbacks(periodicSync)
        if (GarminRadioPolicy.DEFAULT_CONNECTION_MODE != GarminConnectionMode.PERSISTENT_IDLE) return
        val delay = GarminRadioPolicy.PERIODIC_FLOOR_SECONDS * GarminRadioPolicy.syncBackoff(prefs.getInt(emptyStreakKey, 0))
        handler.postDelayed(periodicSync, (delay * 1000).toLong())
    }

    /** Files are stored one at a time, in arrival order. */
    private fun enqueueIngest(file: GarminDownloadedFile) {
        ingestTail = scope.launch {
            ingestLock.withLock {
                val stored = runCatching { ingest(file) }.getOrDefault(false)
                say(!stored, if (stored) "ingested ${file.key.label}" else "ingest failed for ${file.key.label}; it stays new")
            }
        }
    }

    // Live demand

    /**
     * Report who is watching: the live screen, an active workout, the continuous-live toggle, optional channels
     * on screen, or an explicit request (the only way SpO2 opens). The policy decides the channels; the 30 s
     * linger keeps them across a quick screen change. Call on the main thread.
     */
    fun updateLiveDemand(change: (GarminRadioInputs) -> GarminRadioInputs) {
        liveDemand = change(liveDemand)
        applyLiveDemand()
    }

    private fun applyLiveDemand() {
        liveDemand = liveDemand.copy(appInForeground = foreground)
        // Capture mode is one more viewer: a live screen with the calories / intensity / stress tiles on it.
        // Turning it off drops 8 / 10 / 13 at once (no linger); the session gate closes their handles.
        val captureChanged = undecodedCapture() != capturing
        capturing = undecodedCapture()
        if (captureChanged) say(true, "realtime capture ${if (capturing) "on" else "off"}")
        val inputs = if (!capturing) liveDemand else liveDemand.copy(
            liveScreenVisible = true,
            onScreenChannels = liveDemand.onScreenChannels +
                setOf(GarminLiveChannel.CALORIES, GarminLiveChannel.INTENSITY, GarminLiveChannel.STRESS),
        )
        val hold = linger.update(inputs, SystemClock.elapsedRealtime() / 1000.0)
        var wanted = hold.channels.mapNotNull { GarminService.fromRaw(it.serviceId) }.toSet()
        if (!capturing) wanted = wanted - GarminRealtimeCapture.undecodedChannels.toSet()
        if (wanted != wantedLive) {
            say(true, "live channels wanted: ${list(wanted)}" +
                if (hold.recheckAtSeconds != null) " (lingering ${GarminRadioPolicy.LIVE_LINGER_SECONDS.toInt()} s)" else "")
        }
        wantedLive = wanted
        session?.let { s ->
            val c = clock()
            // On: allow 8 / 10 / 13 before asking for them. Off: stop asking first, so they close as ordinary
            // demand changes, then drop the flag.
            val outputs = if (captureChanged && capturing) s.setUndecodedCapture(true, c).toMutableList() else mutableListOf()
            outputs += s.setLiveChannels(wanted, c)
            if (captureChanged && !capturing) outputs += s.setUndecodedCapture(false, c)
            perform(outputs)
        }
        handler.removeCallbacks(lingerRecheck)
        hold.recheckAtSeconds?.let { at ->
            val delayMs = ((at - SystemClock.elapsedRealtime() / 1000.0) * 1000).toLong().coerceAtLeast(0) + 50
            handler.postDelayed(lingerRecheck, delayMs)
        }
    }

    private fun list(services: Set<GarminService>): String =
        if (services.isEmpty()) "none" else services.map { it.raw }.sorted().joinToString(",")

    // Session plumbing

    private fun attach(device: BluetoothDevice) {
        gatt?.let { runCatching { it.disconnect(); it.close() } }
        _linkPhase.value = LinkPhase.CONNECTING
        say(false, "connecting to ${device.address}")
        gatt = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                @Suppress("DEPRECATION")
                device.connectGatt(appContext, false, gattCallback)
            }
        }.getOrElse {
            say(true, "connectGatt failed (${it.javaClass.simpleName})")
            noteFailure("connectGatt failed (${it.javaClass.simpleName})")
            scheduleReconnect()
            null
        }
    }

    private fun clock(): GarminClock {
        val now = Instant.now()
        val rules = ZoneId.systemDefault().rules
        val next = runCatching { rules.nextTransition(now) }.getOrNull()
        val following = next?.let { runCatching { rules.nextTransition(it.instant) }.getOrNull() }
        return GarminClock(
            nowMs = SystemClock.elapsedRealtime(),
            unixSeconds = now.epochSecond,
            utcOffsetSeconds = TimeZone.getDefault().getOffset(now.toEpochMilli()) / 1000,
            nextTransitionUnix = next?.toEpochSecond(),
            followingTransitionUnix = following?.toEpochSecond(),
        )
    }

    private fun perform(outputs: List<GarminSessionOutput>) {
        for (o in outputs) {
            when (o) {
                is GarminSessionOutput.Write -> {
                    telemetry.recordBytes(outbound = o.bytes.size)
                    writeQueue.addLast(o.bytes)
                }
                is GarminSessionOutput.Log -> say(o.line.rare, o.line.text)
                is GarminSessionOutput.Phase -> when (val p = o.phase) {
                    is GarminSessionPhase.Ready -> {
                        _linkPhase.value = LinkPhase.READY
                        handshakeStartedMs?.let { started ->
                            _connectReadout.value = _connectReadout.value.copy(handshakeMs = (SystemClock.elapsedRealtime() - started).toInt())
                        }
                        handshakeStartedMs = null
                        failedConnects = 0
                        setPriority(GarminRadioPolicy.connectionPriority(GarminLinkActivity.IDLE))
                        requestSync(GarminSyncTrigger.CONNECT)
                    }
                    is GarminSessionPhase.Busy -> {
                        // The link works; only GFDI is held elsewhere. No drop, no reconnect.
                        _linkPhase.value = LinkPhase.BUSY
                        failedConnects = 0
                    }
                    is GarminSessionPhase.Failed -> {
                        noteFailure(p.reason)
                        say(true, "dropping the link after a session failure (${p.reason})")
                        gatt?.disconnect()
                    }
                    is GarminSessionPhase.Idle -> Unit
                    else -> _linkPhase.value = LinkPhase.HANDSHAKING
                }
                is GarminSessionOutput.Battery -> {
                    _batteryPct.value = o.percent
                    onBattery(o.percent)
                }
                is GarminSessionOutput.Watch -> {
                    _watch.value = o.info
                    onWatch(o.info)
                }
                is GarminSessionOutput.PairingEventsSent -> markPaired()
                is GarminSessionOutput.SyncAnnounced -> {
                    onSyncAnnounced()
                    requestSync(GarminSyncTrigger.WATCH, watchInitiated = o.announcement.expectsFilter)
                }
                is GarminSessionOutput.SyncFile -> enqueueIngest(o.file)
                is GarminSessionOutput.SyncFinished -> syncFinished(o.summary)
                is GarminSessionOutput.TransferActive -> {
                    _syncing.value = o.active
                    // CONNECTION_PRIORITY_HIGH only while files move, then back to low power.
                    setPriority(GarminRadioPolicy.connectionPriority(
                        if (o.active) GarminLinkActivity.FILE_TRANSFER else GarminLinkActivity.IDLE))
                }
                is GarminSessionOutput.Live -> {
                    if (o.reading is GarminRealtimeReading.HeartRate) contextHr = GarminRealtimeCapture.contextHeartRate(o.reading)
                    onLive(GarminLiveEvent.Reading(o.reading))
                }
                is GarminSessionOutput.LiveChannel -> {
                    GarminLiveChannel.entries.firstOrNull { it.serviceId == o.service.raw }?.let { channel ->
                        val now = System.currentTimeMillis() / 1000.0
                        if (o.open) {
                            telemetry.channelOpened(channel, now)
                            _openLiveChannels.value = _openLiveChannels.value + channel
                        } else {
                            telemetry.channelClosed(channel, now)
                            _openLiveChannels.value = _openLiveChannels.value - channel
                        }
                    }
                    if (o.open) channelAnswered(o.service, "opened")
                    onLive(GarminLiveEvent.Channel(o.service, o.open))
                }
                is GarminSessionOutput.LiveRefused -> channelAnswered(o.service, "refused(${o.status})")
                // The capture IS the Test Centre readout, so its lines are always written while it runs.
                is GarminSessionOutput.LiveRaw -> say(
                    true,
                    GarminRealtimeCapture.line(o.service.raw, o.payload, System.currentTimeMillis(), contextHr),
                )
            }
        }
        drainWrites()
        scheduleDeadline()
    }

    // Realtime capture (Test Centre only)

    /** Record how an undecoded channel answered; once all three have, log the per-model availability line. */
    private fun channelAnswered(service: GarminService, answer: String) {
        if (service !in GarminRealtimeCapture.undecodedChannels) return
        channelAnswers[service.raw] = answer
        val info = watchInfo ?: return
        if (availabilityLogged || GarminRealtimeCapture.undecodedChannels.any { channelAnswers[it.raw] == null }) return
        availabilityLogged = true
        say(true, GarminRealtimeCapture.availabilityLine(info.bluetoothName, info.softwareVersion, channelAnswers))
    }

    private fun drainWrites() {
        if (writeInFlight) return
        val g = gatt ?: return
        val c = writeChar ?: return
        val next = writeQueue.removeFirstOrNull() ?: return
        val type = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        writeInFlight = true
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(c, next, type) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run { c.writeType = type; c.value = next; g.writeCharacteristic(c) }
        }
        if (!started) {
            // The stack is busy; put it back and retry shortly rather than drop a GFDI fragment.
            writeInFlight = false
            writeQueue.addFirst(next)
            handler.postDelayed({ drainWrites() }, 20)
        }
    }

    private fun scheduleDeadline() {
        handler.removeCallbacks(deadline)
        val at = session?.nextDeadlineMs ?: return
        handler.postDelayed(deadline, maxOf(0L, at - SystemClock.elapsedRealtime()))
    }

    private fun setPriority(p: GarminConnectionPriority) {
        val value = when (p) {
            GarminConnectionPriority.LOW_POWER -> BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER
            GarminConnectionPriority.BALANCED -> BluetoothGatt.CONNECTION_PRIORITY_BALANCED
            GarminConnectionPriority.HIGH -> BluetoothGatt.CONNECTION_PRIORITY_HIGH
        }
        runCatching { gatt?.requestConnectionPriority(value) }
        say(false, "connection priority $p")
    }

    private fun endSession() {
        session?.syncResumePoint?.let {
            heldResume = it
            say(true, "link down mid-transfer; holding ${it.key.label} at offset ${it.offset} to resume")
        }
        if (session?.isSyncing == true) {
            _syncing.value = false
            telemetry.syncSessionCompleted()
        }
        handler.removeCallbacks(periodicSync)
        syncPreparing = false
        if (session != null) {
            val now = System.currentTimeMillis() / 1000.0
            telemetry.linkDown(now)
            say(false, telemetry.summary(now))
            _openLiveChannels.value = emptySet()
            onLive(GarminLiveEvent.LinkDown)
        }
        session = null
        channelAnswers.clear()
        availabilityLogged = false
        contextHr = null
        notifyChar = null
        writeChar = null
        writeQueue.clear()
        writeInFlight = false
        handler.removeCallbacks(deadline)
    }

    private fun scheduleReconnect() {
        if (intentionalDisconnect || targetAddress == null || _unsupportedReason.value != null) return
        failedConnects += 1
        val delay = ReconnectBackoff.nextDelayMs(failedConnects)
        say(true, "reconnect attempt $failedConnects in ${delay / 1000} s")
        handler.removeCallbacks(reconnectRunnable)
        handler.postDelayed(reconnectRunnable, delay)
    }

    /** Stop a running scan (the wizard leaving its pick list). The link, if any, is untouched. */
    fun stopScan() {
        wantsScan = false
        haltScan()
    }

    private fun haltScan() {
        if (adapter?.isEnabled == true) runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    /**
     * Garmin watches the OS already knows (bonded, or connected over GATT by any app). A bonded watch may not
     * advertise, so the wizard lists these too. Discovery only: nothing here connects.
     */
    private fun systemWatches(): List<DiscoveredWatch> {
        val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val connected = runCatching { manager?.getConnectedDevices(BluetoothProfile.GATT).orEmpty() }.getOrDefault(emptyList())
        val bonded = runCatching { adapter?.bondedDevices.orEmpty().toList() }.getOrDefault(emptyList())
        val out = LinkedHashMap<String, DiscoveredWatch>()
        for (device in connected + bonded) {
            val name = runCatching { device.name }.getOrNull() ?: continue
            if (ExperimentalBrand.recognise(name) != ExperimentalBrand.GARMIN) continue
            if (runCatching { device.type }.getOrNull() == BluetoothDevice.DEVICE_TYPE_CLASSIC) continue
            out.getOrPut(device.address) { DiscoveredWatch(device.address, name, SYSTEM_WATCH_RSSI, connected = true) }
        }
        if (out.isNotEmpty()) say(true, "${out.size} Garmin watch(es) already known to this device")
        return out.values.toList()
    }

    /** Radio counters for the Test Centre (one line, refreshed by the caller). */
    fun telemetrySummary(): String = telemetry.summary(System.currentTimeMillis() / 1000.0)

    private fun noteFailure(text: String) {
        _connectReadout.value = _connectReadout.value.copy(lastFailure = text)
    }

    private fun say(rare: Boolean, text: String) {
        if (rare || detail()) log("Garmin: $text")
    }

    private fun onMain(block: () -> Unit) {
        handler.post { runCatching(block).onFailure { log("Garmin: callback error (${it.javaClass.simpleName}: ${it.message})") } }
    }

    // App state (no lifecycle-process dependency: count started activities)

    private fun initialForeground(): Boolean = runCatching {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }.getOrDefault(true)

    private fun appStateChanged(isForeground: Boolean) {
        if (isForeground == foreground) return
        foreground = isForeground
        if (!isForeground && wantsScan) {
            haltScan()
            say(true, "scan stopped - the app went to the background")
        }
        session?.let { perform(it.setForeground(isForeground, clock())) }
        if (isForeground) {
            retryGfdi()
            requestSync(GarminSyncTrigger.FOREGROUND)
        }
        // Backgrounding closes every live channel at once (no linger), except an active workout's HR + RR.
        applyLiveDemand()
    }

    private val lifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            startedActivities += 1
            if (startedActivities == 1) appStateChanged(true)
        }
        override fun onActivityStopped(activity: Activity) {
            startedActivities = maxOf(0, startedActivities - 1)
            if (startedActivities == 0) appStateChanged(false)
        }
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    private val adapterReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> onMain {
                    val address = targetAddress
                    if (!intentionalDisconnect && address != null && session == null) {
                        say(true, "Bluetooth on; reconnecting")
                        connect(address)
                    }
                }
                BluetoothAdapter.STATE_OFF -> onMain {
                    say(true, "Bluetooth off; link down")
                    handler.removeCallbacks(reconnectRunnable)
                    gatt?.let { runCatching { it.close() } }
                    gatt = null
                    endSession()
                    if (_linkPhase.value != LinkPhase.UNSUPPORTED) _linkPhase.value = LinkPhase.DISCONNECTED
                }
            }
        }
    }

    // Registered after both callbacks above exist (Kotlin initialises properties in declaration order).
    init {
        if (prefs.contains(lastSyncAtKey)) _lastSyncAt.value = prefs.getLong(lastSyncAtKey, 0)
        (appContext as? Application)?.registerActivityLifecycleCallbacks(lifecycleCallbacks)
        runCatching { appContext.registerReceiver(adapterReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)) }
        capturePrefs?.registerOnSharedPreferenceChangeListener(captureListener)
    }

    // Scan callback

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val record = result.scanRecord
            val manufacturer = record?.getManufacturerSpecificData(GarminAdvertisement.COMPANY_ID)
            val name = record?.deviceName ?: runCatching { device.name }.getOrNull() ?: ""
            val isGarmin = manufacturer != null || ExperimentalBrand.recognise(name) == ExperimentalBrand.GARMIN
            if (!isGarmin) return
            onMain {
                val address = device.address
                if (_discovered.value.none { it.address == address }) {
                    _discovered.value = _discovered.value + DiscoveredWatch(address, name.ifBlank { "Garmin watch" }, result.rssi)
                    say(true, "found ${if (name.isBlank()) "a Garmin watch" else logSafeDeviceName(name)} rssi=${result.rssi}")
                }
                // The coordinator's scan fallback (no stored address): take the first watch found.
                if (!discoveryOnly && targetAddress == null && wantsScan) {
                    wantsScan = false
                    stopScan()
                    targetAddress = address
                    attach(device)
                }
            }
        }
    }

    // GATT callback

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) = onMain {
            if (g !== gatt) return@onMain
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    handler.removeCallbacks(reconnectRunnable)
                    telemetry.linkUp(System.currentTimeMillis() / 1000.0)
                    _linkPhase.value = LinkPhase.DISCOVERING
                    say(false, "connected; requesting MTU ${GarminRadioPolicy.REQUESTED_MTU}")
                    setPriority(GarminConnectionPriority.BALANCED)
                    if (!g.requestMtu(GarminRadioPolicy.REQUESTED_MTU)) g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    endSession()
                    runCatching { g.close() }
                    gatt = null
                    if (_linkPhase.value != LinkPhase.UNSUPPORTED) _linkPhase.value = LinkPhase.DISCONNECTED
                    if (intentionalDisconnect || targetAddress == null || _unsupportedReason.value != null) {
                        say(false, "disconnected (status=$status)")
                    } else {
                        say(true, "disconnected (status=$status); reconnecting")
                        scheduleReconnect()
                    }
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) = onMain {
            if (g !== gatt) return@onMain
            mtu = if (status == BluetoothGatt.GATT_SUCCESS) newMtu else 23
            _connectReadout.value = _connectReadout.value.copy(mtu = mtu)
            say(false, "MTU $mtu (status=$status); discovering services")
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = onMain {
            if (g !== gatt) return@onMain
            val services = g.services.orEmpty().map { s -> GarminGattService(s.uuid.toString(), s.characteristics.map { it.uuid.toString() }) }
            val choice = GarminTransportSelection.select(services)
            if (choice == null) {
                val found = GarminTransportSelection.describe(services)
                _unsupportedReason.value = "unsupported watch protocol"
                noteFailure("unsupported watch protocol")
                _linkPhase.value = LinkPhase.UNSUPPORTED
                say(true, "unsupported watch protocol (no V2/V1/V0 GFDI characteristics; found ${found.ifEmpty { "none" }}); " +
                    "disconnecting - Broadcast Heart Rate remains available for this watch")
                intentionalDisconnect = true
                g.disconnect()
                return@onMain
            }
            val service = g.getService(UUID.fromString(choice.service))
            notifyChar = service?.getCharacteristic(UUID.fromString(choice.notify))
            writeChar = service?.getCharacteristic(UUID.fromString(choice.write))
            transport = choice
            _transportKind.value = choice.kind
            say(true, "protocol variant ${choice.kind.label} (notify ${choice.notify.take(8)}, write ${choice.write.take(8)})")
            val n = notifyChar ?: return@onMain
            _linkPhase.value = LinkPhase.SUBSCRIBING
            g.setCharacteristicNotification(n, true)
            val cccd = n.getDescriptor(CCCD) ?: run {
                say(true, "notify characteristic has no CCCD; disconnecting")
                noteFailure("notify characteristic has no CCCD")
                g.disconnect()
                return@onMain
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                run { cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(cccd) }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) = onMain {
            if (g !== gatt || descriptor.uuid != CCCD) return@onMain
            if (status != BluetoothGatt.GATT_SUCCESS) {
                // The GFDI channel needs an encrypted, bonded link; Android raises its pairing prompt here.
                say(true, "subscribe failed (status=$status); the watch must be bonded (OS pairing with the watch's PIN)")
                noteFailure("subscribe failed (status=$status)")
                g.disconnect()
                return@onMain
            }
            val kind = transport?.kind ?: return@onMain
            val config = GarminSessionConfig(
                host = GarminSessionConfig.noopHost("Android", 1),
                firstPairing = isFirstPairing(),
                sendsHostLifecycleEvents = GarminRadioPolicy.SENDS_HOST_LIFECYCLE_EVENTS,
            )
            val s = GarminSession(config, kind, mtu)
            session = s
            handshakeStartedMs = SystemClock.elapsedRealtime()
            _connectReadout.value = _connectReadout.value.copy(connects = _connectReadout.value.connects + 1)
            _linkPhase.value = LinkPhase.HANDSHAKING
            val c = clock()
            val outputs = s.start(c).toMutableList()
            if (!foreground) outputs += s.setForeground(false, c)
            // Remembered by the session and registered once its handshake completes.
            outputs += s.setUndecodedCapture(capturing, c)
            outputs += s.setLiveChannels(wantedLive, c)
            perform(outputs)
        }

        @Deprecated("API < 33 path")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: return
            received(g, characteristic, value.copyOf())
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            received(g, characteristic, value.copyOf())
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) = onMain {
            if (g !== gatt) return@onMain
            if (status != BluetoothGatt.GATT_SUCCESS) say(true, "write failed (status=$status)")
            writeInFlight = false
            drainWrites()
        }
    }

    private fun received(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) = onMain {
        if (g !== gatt || characteristic.uuid != notifyChar?.uuid) return@onMain
        val s = session ?: return@onMain
        telemetry.recordBytes(inbound = value.size)
        perform(s.receive(value, clock()))
    }

    private companion object {
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        /** The first sync of a watch reaches back this far (Swift: `historyLookbackSeconds`). */
        const val HISTORY_LOOKBACK_SECONDS = 14L * 86_400
        /** Signal level shown for a watch listed from the OS rather than a scan (no RSSI available). */
        const val SYSTEM_WATCH_RSSI = -60
    }
}
