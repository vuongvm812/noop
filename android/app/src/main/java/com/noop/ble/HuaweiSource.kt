package com.noop.ble

import com.noop.huawei.HuaweiCapabilities
import com.noop.huawei.HuaweiRadioPolicy
import com.noop.huawei.HuaweiSyncRunner
import com.noop.huawei.HuaweiSyncStage
import com.noop.huawei.HuaweiSyncTrigger
import com.noop.huawei.auth.HuaweiAuthError
import com.noop.huawei.auth.HuaweiAuthMachine
import com.noop.huawei.auth.HuaweiAuthMode
import com.noop.huawei.auth.HuaweiAuthOutcome
import com.noop.huawei.auth.HuaweiAuthResult
import com.noop.huawei.auth.HuaweiAuthenticator
import com.noop.huawei.auth.HuaweiPhoneIdentity
import com.noop.huawei.protocol.HuaweiBattery
import com.noop.huawei.protocol.HuaweiDeviceConfig
import com.noop.huawei.protocol.HuaweiFirstPairingGrant
import com.noop.huawei.protocol.HuaweiHex
import com.noop.huawei.protocol.HuaweiInbound
import com.noop.huawei.protocol.HuaweiProductInfo
import com.noop.huawei.protocol.HuaweiProtocolException
import com.noop.huawei.protocol.HuaweiRandom
import com.noop.huawei.protocol.HuaweiRequest
import com.noop.huawei.protocol.HuaweiTlv
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * One GATT connection to a Huawei band, as the source drives it. The Android implementation
 * ([AndroidHuaweiGatt]) connects over LE, requests the MTU, finds service FE86 and enables FE02
 * notifications before reporting [Events.onLinkUp]. It never bonds at the OS level. Tests substitute a fake
 * wired to a simulated band.
 */
interface HuaweiGatt {
    interface Events {
        /** Notifications are on; [mtu] is the negotiated ATT MTU. */
        fun onLinkUp(mtu: Int)

        /** One FE02 notification value. */
        fun onNotification(value: ByteArray)

        /** The connection failed or dropped. Not called after [close]. */
        fun onLinkDown(reason: String)
    }

    fun open(address: String, events: Events)

    /** Queue one FE01 write; false when there is no usable connection. */
    fun write(value: ByteArray): Boolean

    /** Disconnect and release the connection. Idempotent; reports nothing back. */
    fun close()
}

/** What the Test Centre shows for the current (or last) Huawei connection. */
data class HuaweiConnectReadout(
    val authMode: HuaweiAuthMode? = null,
    val authVersion: Int? = null,
    val deviceSupportType: Int? = null,
    val encryptMethod: Int? = null,
    /** "AES-GCM" / "AES-CBC" once a session cipher is installed. */
    val cipher: String? = null,
    val sliceSize: Int? = null,
    val mtu: Int? = null,
    val capabilities: HuaweiCapabilities? = null,
    val connects: Int = 0,
    val lastFailure: String? = null,
)

/**
 * The first-pairing permission waiting for the source of [deviceId] to be built. The wizard arms it right
 * before registering the band active; the coordinator takes it when it builds the source. Held in memory
 * only, so a process restart can never replay a pairing the user did not just ask for.
 */
object HuaweiPairingRequests {
    private val pending = ConcurrentHashMap<String, HuaweiFirstPairingGrant>()

    fun arm(deviceId: String, grant: HuaweiFirstPairingGrant) {
        pending[deviceId] = grant
    }

    fun take(deviceId: String): HuaweiFirstPairingGrant? = pending.remove(deviceId)
}

/**
 * Huawei Band 10 source over the band's own protocol (`com.noop.huawei`). Kotlin-only; no Swift twin yet.
 *
 * Each connection: open the GATT link ([HuaweiGatt]), authenticate with the stored key ([HuaweiAuthMachine]
 * in reconnect mode), read ProductInfo 01/07, set the band's clock (01/05), read Battery 01/08, ask which
 * services and commands the band supports (01/02, 01/03, 01/37, 01/31), send the user's optional Huawei
 * account id (0x1A) when one was entered, report READY and hand the authenticated link to the
 * [HuaweiSyncRunner]. Battery pushes (01/27) update [battery] while connected. Every message is an
 * allowlisted [HuaweiRequest]. Slices are capped at the negotiated ATT MTU ([HuaweiGattLink.capSliceToMtu]).
 *
 * History sync runs on READY, on [onForeground], on a periodic timer while connected and on [syncNow], each
 * gated by [HuaweiRadioPolicy] (a manual sync always runs) and never two at once. [syncStage],
 * [lastSyncAt] and [lastSyncError] are what the device panel shows.
 *
 * Pairing is never automatic. With no stored key the source goes straight to NEEDS_PAIRING without
 * touching the radio; a band that says it needs a fresh pairing does the same. Only [beginFirstPairing],
 * which needs a [HuaweiFirstPairingGrant] from the pairing wizard, runs the key-installing handshake, once.
 *
 * Bonding: this source never asks Android to bond (the band's own handshake is the security layer), and it
 * shares nothing with the WHOOP client, so the WHOOP bond watchdog, the #982 never-bonded detector and the
 * bond-refusal give-up never see a Huawei connection.
 *
 * Reconnects after a dropped link or a timed-out handshake back off like Garmin's ([ReconnectBackoff]). A
 * band that refuses our key (a device error, a proof that does not verify) stops at FAILED: retrying cannot
 * fix it, and "Pair again" or "Sync now" is the way out.
 *
 * All state lives on [scope] (the main dispatcher in production); public calls hop onto it.
 */
class HuaweiSource(
    val deviceId: String,
    private val gatt: HuaweiGatt,
    private val keys: HuaweiKeyAccess,
    private val scope: CoroutineScope,
    private val syncRunner: HuaweiSyncRunner = HuaweiSyncRunner.NONE,
    private val log: (String) -> Unit = {},
    /** Per-connect readouts only while this returns true (a Test Centre domain). Rare events always log. */
    private val detail: () -> Boolean = { false },
    private val onBattery: (Int) -> Unit = {},
    private val onProduct: (HuaweiProductInfo) -> Unit = {},
    /** A sync finished normally at this Unix second (persist it). */
    private val onSynced: (Long) -> Unit = {},
    initialLastSyncAt: Long? = null,
    /** When the last sync attempt started, as the sync runner persisted it (the policy floor). */
    private val lastSyncAttemptAt: () -> Long? = { null },
    /** Consecutive syncs that found nothing (stretches the periodic floor). */
    private val emptyStreak: () -> Int = { 0 },
    private val utcOffsetSeconds: (Long) -> Int = { ts -> java.util.TimeZone.getDefault().getOffset(ts * 1000) / 1000 },
    private val random: HuaweiRandom = HuaweiRandom.secure,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val backoffMs: (Int) -> Long = ReconnectBackoff::nextDelayMs,
    /** Per-read timeout after authentication. */
    private val readTimeoutMs: Long = HuaweiAuthMachine.DEFAULT_TIMEOUT_MS,
) : LiveHrSource {

    enum class LinkPhase { DISCONNECTED, CONNECTING, AUTHENTICATING, READY, NEEDS_PAIRING, FAILED }

    private val _linkPhase = MutableStateFlow(LinkPhase.DISCONNECTED)
    val linkPhase: StateFlow<LinkPhase> = _linkPhase.asStateFlow()
    private val _product = MutableStateFlow<HuaweiProductInfo?>(null)
    val product: StateFlow<HuaweiProductInfo?> = _product.asStateFlow()
    private val _battery = MutableStateFlow<Int?>(null)
    val battery: StateFlow<Int?> = _battery.asStateFlow()
    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()
    private val _lastSyncAt = MutableStateFlow(initialLastSyncAt)
    val lastSyncAt: StateFlow<Long?> = _lastSyncAt.asStateFlow()
    /** The stage of the sync in flight; null when none is running. */
    private val _syncStage = MutableStateFlow<HuaweiSyncStage?>(null)
    val syncStage: StateFlow<HuaweiSyncStage?> = _syncStage.asStateFlow()
    /** Why the last sync did not complete fully, worded from what the runner reported; null after a clean one. */
    private val _lastSyncError = MutableStateFlow<String?>(null)
    val lastSyncError: StateFlow<String?> = _lastSyncError.asStateFlow()

    /** True while a first pairing waits for the user to confirm on the band. */
    private val _confirmingOnBand = MutableStateFlow(false)
    val confirmingOnBand: StateFlow<Boolean> = _confirmingOnBand.asStateFlow()
    private val _readout = MutableStateFlow(HuaweiConnectReadout())
    val readout: StateFlow<HuaweiConnectReadout> = _readout.asStateFlow()

    /** The authenticated link while READY, for a caller that needs it outside a sync run. */
    var link: HuaweiGattLink? = null
        private set
    var capabilities: HuaweiCapabilities = HuaweiCapabilities()
        private set

    private var target: String? = null
    private var intentional = false
    private var pendingGrant: HuaweiFirstPairingGrant? = null
    private var pairingInFlight = false
    private var failedConnects = 0
    /** Bumped on every open and teardown; callbacks from an older connection are ignored. */
    private var epoch = 0
    private var sessionJob: Job? = null
    private var pushJob: Job? = null
    private var syncJob: Job? = null
    private var reconnectJob: Job? = null
    private var periodicJob: Job? = null
    /** Start of the last attempt this source made (seconds), alongside the runner's persisted one. */
    private var attemptedAt: Long? = null
    /** The band's own LinkParams slice size (before the MTU cap): the SupportedCommands request budget. */
    private var bandSliceSize: Int = com.noop.huawei.protocol.HuaweiFraming.DEFAULT_SLICE_SIZE

    // LiveHrSource

    /** No background discovery: the band is always reached by its stored address. */
    override fun scan() = post {
        say(true, "no stored address for $deviceId; pair the band from Add a device")
        _linkPhase.value = LinkPhase.NEEDS_PAIRING
    }

    override fun connect(address: String) = post {
        intentional = false
        if (target == address && _linkPhase.value in ACTIVE_PHASES) return@post
        target = address
        failedConnects = 0
        open()
    }

    override fun stop() = post {
        intentional = true
        target = null
        pendingGrant = null
        teardown("stopped")
        _linkPhase.value = LinkPhase.DISCONNECTED
    }

    /**
     * Run the key-installing handshake on the next connection (now, when an address is known). The grant is
     * used once, whatever the outcome.
     */
    fun beginFirstPairing(grant: HuaweiFirstPairingGrant) = post {
        pendingGrant = grant
        intentional = false
        failedConnects = 0
        if (target == null) return@post
        teardown("first pairing requested")
        open()
    }

    /** "Sync now": sync when READY; otherwise reconnect now (after FAILED or while backing off). */
    fun syncNow() = post {
        when (_linkPhase.value) {
            LinkPhase.READY -> requestSync(HuaweiSyncTrigger.MANUAL)
            LinkPhase.DISCONNECTED, LinkPhase.FAILED -> if (target != null) {
                failedConnects = 0
                reconnectJob?.cancel()
                open()
            }
            else -> say(false, "sync request ignored while ${_linkPhase.value}")
        }
    }

    /** The app came to the foreground: a sync, if READY and the event floor allows it. */
    fun onForeground() = post {
        if (_linkPhase.value == LinkPhase.READY) requestSync(HuaweiSyncTrigger.FOREGROUND)
    }

    // Connection

    private fun open() {
        val address = target ?: return
        teardown("reopening")
        if (pendingGrant == null && keys.load(deviceId)?.bond == null) {
            needsPairing("no stored key for this band")
            return
        }
        val e = ++epoch
        val l = HuaweiGattLink(write = { gatt.write(it) }, log = { say(true, it.removePrefix("huawei: ")) })
        link = l
        _linkPhase.value = LinkPhase.CONNECTING
        say(false, "connecting to $address")
        gatt.open(address, object : HuaweiGatt.Events {
            override fun onLinkUp(mtu: Int) = post { if (e == epoch) linkUp(l, address, mtu) }
            override fun onNotification(value: ByteArray) = l.onNotification(value)
            override fun onLinkDown(reason: String) = post { if (e == epoch) linkLost(reason) }
        })
    }

    private fun linkUp(l: HuaweiGattLink, address: String, mtu: Int) {
        l.capSliceToMtu(mtu)
        _linkPhase.value = LinkPhase.AUTHENTICATING
        _readout.value = _readout.value.copy(mtu = mtu, connects = _readout.value.connects + 1)
        say(false, "link up (MTU $mtu); authenticating")
        sessionJob = scope.launch { session(l, address) }
    }

    private suspend fun session(l: HuaweiGattLink, address: String) {
        val grant = pendingGrant
        pendingGrant = null
        val stored = keys.load(deviceId)
        val machine = when {
            grant != null -> {
                val key = stored ?: HuaweiStoredKey(HuaweiPhoneIdentity.generate(random, HuaweiKeyStore.PHONE_MODEL), null, null)
                if (stored == null) keys.save(deviceId, key)
                HuaweiAuthMachine.firstPairing(grant, key.identity, address, key.bond, random, nowMs)
            }
            stored?.bond != null -> HuaweiAuthMachine.reconnect(stored.identity, address, stored.bond, random, nowMs)
            else -> {
                needsPairing("no stored key for this band")
                return
            }
        }
        pairingInFlight = grant != null
        _confirmingOnBand.value = grant != null
        if (grant != null) say(true, "first pairing started; confirm on the band")
        val outcome = HuaweiAuthenticator.run(l, machine)
        pairingInFlight = false
        _confirmingOnBand.value = false
        when (outcome) {
            is HuaweiAuthOutcome.Failure -> authFailed(outcome.error, firstPairing = grant != null)
            is HuaweiAuthOutcome.Success -> {
                val result = outcome.result
                val previous = stored?.bond
                if (previous != null && previous.mode != result.mode) {
                    say(true, "auth mode changed from ${previous.mode} to ${result.mode}")
                }
                if (grant != null || previous != result.bond) {
                    val base = keys.load(deviceId) ?: stored ?: return
                    keys.save(deviceId, base.copy(bond = result.bond))
                }
                if (grant != null) say(true, "first pairing complete (${result.mode})")
                recordAuth(result, l)
                afterAuth(l)
            }
        }
    }

    private suspend fun afterAuth(l: HuaweiGattLink) {
        read(l, HuaweiDeviceConfig.ProductInfo.request())?.let { tlv ->
            runCatching { HuaweiDeviceConfig.ProductInfo.decode(tlv) }
                .onSuccess { info ->
                    _product.value = info
                    onProduct(info)
                    say(false, "product ${info.productModel} firmware ${info.softwareVersion}")
                }
                .onFailure { say(true, "product info unreadable (${it.message})") }
        }
        setTime(l)
        read(l, HuaweiDeviceConfig.Battery.request())?.let { tlv ->
            runCatching { HuaweiDeviceConfig.Battery.decode(tlv) }.onSuccess { battery(it) }
        }
        val caps = readCapabilities(l)
        capabilities = caps
        _readout.value = _readout.value.copy(capabilities = caps)
        say(false, "capabilities: services ${caps.services.sorted().joinToString(",") { "%02X".format(it) }}, " +
            "expand ${caps.expand?.raw?.let { HuaweiHex.encode(it) } ?: "none"}, settings ${caps.settings ?: "none"}")
        sendAccount(l, caps)
        pushJob = scope.launch {
            l.pushes.collect { push ->
                if (push.serviceId == HuaweiInbound.SERVICE_DEVICE_CONFIG && push.commandId == HuaweiInbound.BATTERY_LEVEL_CHANGED) {
                    runCatching { HuaweiDeviceConfig.Battery.decode(push.tlv) }.onSuccess { battery(it) }
                }
            }
        }
        failedConnects = 0
        _readout.value = _readout.value.copy(lastFailure = null)
        _linkPhase.value = LinkPhase.READY
        say(true, "ready")
        requestSync(HuaweiSyncTrigger.CONNECT)
        startPeriodic()
    }

    /** The phone's time and UTC offset onto the band (01/05). A refusal is logged and the session goes on. */
    private suspend fun setTime(l: HuaweiGattLink) {
        val now = nowMs() / 1000
        if (read(l, HuaweiDeviceConfig.SetTime.request(now, utcOffsetSeconds(now))) != null) say(false, "band clock set")
    }

    /**
     * The 17-digit account id the user typed into the wizard, if any: 1A/05 when the band lists 1A/05 and
     * 1A/06, else 1A/01 when it lists that. Nothing is sent without an id, and an id is never cleared.
     */
    private suspend fun sendAccount(l: HuaweiGattLink, caps: HuaweiCapabilities) {
        val account = keys.load(deviceId)?.accountId?.takeIf { HuaweiKeyStore.isValidAccountId(it) } ?: return
        val extended = caps.supports(0x1A, 0x05) && caps.supports(0x1A, 0x06)
        if (!extended && !caps.supports(0x1A, 0x01)) {
            say(true, "account id not sent: the band lists neither 1A/05 with 1A/06 nor 1A/01")
            return
        }
        val optimisation = caps.expand?.has(HuaweiDeviceConfig.Account.EXPAND_DIFF_ACCOUNT_PAIRING) == true
        if (read(l, HuaweiDeviceConfig.Account.request(account, extended, optimisation)) != null) {
            say(false, "account id sent (${if (extended) "1A/05" else "1A/01"})")
        }
    }

    private suspend fun readCapabilities(l: HuaweiGattLink): HuaweiCapabilities {
        val asked = CAPABILITY_SERVICES
        val services = read(l, HuaweiDeviceConfig.SupportedServices.request(asked))
            ?.let { runCatching { HuaweiDeviceConfig.SupportedServices.decode(it, asked) }.getOrNull() }
            .orEmpty()
        val plan = CAPABILITY_COMMANDS.filterKeys { it in services }
        val commands = linkedMapOf<Int, Set<Int>>()
        if (plan.isNotEmpty()) {
            val requests = runCatching { HuaweiDeviceConfig.SupportedCommands.requests(plan, bandSliceSize) }.getOrDefault(emptyList())
            for (request in requests) {
                read(l, request)?.let { runCatching { HuaweiDeviceConfig.SupportedCommands.decode(it, plan) }.getOrNull() }
                    ?.let { commands.putAll(it) }
            }
        }
        val expand = read(l, HuaweiDeviceConfig.ExpandCapabilities.request())
            ?.let { runCatching { HuaweiDeviceConfig.ExpandCapabilities.decode(it) }.getOrNull() }
        val settings = read(l, HuaweiDeviceConfig.SettingRelated.request())
            ?.let { runCatching { HuaweiDeviceConfig.SettingRelated.decode(it) }.getOrNull() }
        return HuaweiCapabilities(services, commands, expand, settings)
    }

    /** One post-auth read: its TLV, or null when the band refused it, timed out or the link failed. */
    private suspend fun read(l: HuaweiGattLink, request: HuaweiRequest): HuaweiTlv? {
        val reply = try {
            withTimeoutOrNull(readTimeoutMs) { l.request(request) }
        } catch (e: HuaweiProtocolException) {
            say(false, "${request.command} failed (${e.message})")
            return null
        }
        if (reply == null) {
            say(false, "${request.command} timed out")
            return null
        }
        val code = reply.resultCode()
        if (code != null && code != HuaweiTlv.RESULT_OK) {
            say(false, "${request.command} refused (0x%08X)".format(code))
            return null
        }
        return reply
    }

    private fun battery(b: HuaweiBattery) {
        if (b.level !in 0..100) return
        _battery.value = b.level
        onBattery(b.level)
    }

    private fun recordAuth(result: HuaweiAuthResult, l: HuaweiGattLink) {
        val p = result.linkParams
        bandSliceSize = result.linkConfig.sliceSize
        _readout.value = _readout.value.copy(
            authMode = result.mode,
            authVersion = p.authVersion,
            deviceSupportType = p.deviceSupportType,
            encryptMethod = p.encryptMethod,
            cipher = result.linkConfig.cipher?.let { if (it.useGcm) "AES-GCM" else "AES-CBC" },
            // The slice actually used: the band's, capped at the negotiated MTU.
            sliceSize = l.config.sliceSize,
        )
        say(false, "authenticated: mode ${result.mode}, auth version ${p.authVersion}, support type ${p.deviceSupportType}, " +
            "encrypt method ${p.encryptMethod}, slice ${result.linkConfig.sliceSize} (used ${l.config.sliceSize}), installed key ${result.installedKey}")
    }

    private fun authFailed(error: HuaweiAuthError, firstPairing: Boolean) {
        val text = describe(error)
        _readout.value = _readout.value.copy(lastFailure = text)
        say(true, "authentication failed: $text")
        when {
            error is HuaweiAuthError.NeedsFirstPairing -> needsPairing(text)
            firstPairing -> terminal("first pairing failed ($text)")
            error is HuaweiAuthError.Timeout || error is HuaweiAuthError.Protocol -> {
                teardown("auth failed")
                _linkPhase.value = LinkPhase.DISCONNECTED
                scheduleReconnect()
            }
            else -> terminal("the band refused the stored key ($text)")
        }
    }

    private fun linkLost(reason: String) {
        val phase = _linkPhase.value
        when {
            pairingInFlight -> {
                pairingInFlight = false
                _confirmingOnBand.value = false
                _readout.value = _readout.value.copy(lastFailure = "link lost during first pairing ($reason)")
                terminal("link lost during first pairing ($reason)")
            }
            else -> {
                say(true, if (phase == LinkPhase.AUTHENTICATING) "link lost during authentication ($reason)" else "link lost ($reason)")
                teardown(reason)
                _linkPhase.value = LinkPhase.DISCONNECTED
                scheduleReconnect()
            }
        }
    }

    private fun needsPairing(reason: String) {
        teardown(reason)
        _linkPhase.value = LinkPhase.NEEDS_PAIRING
        say(true, "needs pairing ($reason); pair again from Add a device")
    }

    private fun terminal(reason: String) {
        teardown(reason)
        _linkPhase.value = LinkPhase.FAILED
        say(true, "stopped: $reason")
    }

    /** Drop the current connection and everything riding on it. Leaves [linkPhase] to the caller. */
    private fun teardown(reason: String) {
        epoch++
        reconnectJob?.cancel(); reconnectJob = null
        sessionJob?.cancel(); sessionJob = null
        pushJob?.cancel(); pushJob = null
        syncJob?.cancel(); syncJob = null
        periodicJob?.cancel(); periodicJob = null
        _syncing.value = false
        _syncStage.value = null
        _confirmingOnBand.value = false
        pairingInFlight = false
        link?.close(reason)
        link = null
        gatt.close()
    }

    private fun scheduleReconnect() {
        if (intentional || target == null) return
        failedConnects += 1
        val wait = backoffMs(failedConnects)
        say(true, "reconnect attempt $failedConnects in ${wait / 1000} s")
        reconnectJob = scope.launch {
            delay(wait)
            reconnectJob = null
            if (!intentional && target != null) open()
        }
    }

    /** A sync for [trigger] when READY, none is running and [HuaweiRadioPolicy] allows it. */
    private fun requestSync(trigger: HuaweiSyncTrigger) {
        val l = link ?: return
        if (_linkPhase.value != LinkPhase.READY) return
        if (syncJob?.isActive == true) {
            say(false, "sync ($trigger) skipped; one is running")
            return
        }
        val now = nowMs() / 1000
        val last = listOfNotNull(lastSyncAttemptAt(), attemptedAt).maxOrNull()
        val streak = emptyStreak()
        if (!HuaweiRadioPolicy.shouldSync(trigger, now, last, streak)) {
            say(false, "sync ($trigger) held by the floor (last ${last?.let { now - it } ?: -1} s ago, empty streak $streak)")
            return
        }
        attemptedAt = now
        val caps = capabilities
        syncJob = scope.launch {
            _syncing.value = true
            try {
                say(false, "sync ($trigger) starting")
                val summary = syncRunner.run(l, caps, deviceId) { stage -> _syncStage.value = stage }
                if (summary != null && !summary.stored) {
                    _lastSyncError.value = "not stored" + summary.failures.joinToString("; ", prefix = ": ").takeIf { summary.failures.isNotEmpty() }.orEmpty()
                    say(true, "sync ($trigger) finished without storing")
                } else {
                    val at = nowMs() / 1000
                    _lastSyncAt.value = at
                    onSynced(at)
                    _lastSyncError.value = summary?.failures?.takeIf { it.isNotEmpty() }?.joinToString("; ")
                    say(false, "sync ($trigger) finished")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _lastSyncError.value = "${e.javaClass.simpleName}: ${e.message}"
                say(true, "sync ($trigger) failed (${e.javaClass.simpleName}: ${e.message})")
            } finally {
                _syncing.value = false
                _syncStage.value = null
            }
        }
    }

    /** While READY: one PERIODIC request per floor, stretched by the empty streak, the policy deciding each. */
    private fun startPeriodic() {
        periodicJob?.cancel()
        periodicJob = scope.launch {
            while (true) {
                val wait = HuaweiRadioPolicy.PERIODIC_FLOOR_SECONDS * HuaweiRadioPolicy.syncBackoff(emptyStreak())
                delay((wait * 1000).toLong())
                requestSync(HuaweiSyncTrigger.PERIODIC)
            }
        }
    }

    private fun say(rare: Boolean, text: String) {
        if (rare || detail()) log("Huawei: $text")
    }

    private fun post(block: () -> Unit) {
        scope.launch { runCatching(block).onFailure { log("Huawei: callback error (${it.javaClass.simpleName}: ${it.message})") } }
    }

    companion object {
        private val ACTIVE_PHASES = setOf(LinkPhase.CONNECTING, LinkPhase.AUTHENTICATING, LinkPhase.READY)

        /**
         * Services asked about in SupportedServices: device config, fitness data, workout, the two file
         * download services, stress (0x20) and P2P (0x34), the last two gating history sync's stress file
         * and data-dictionary streams (#82), and AccountRelated (0x1A), which gates sending the account id. Ids re-derived from Gadgetbridge's packet classes (AGPL-3.0, read for facts
         * only); unvalidated on a Band 10.
         */
        val CAPABILITY_SERVICES: List<Int> = listOf(0x01, 0x07, 0x0A, 0x17, 0x2C, 0x20, 0x34, 0x1A)

        /**
         * Commands asked about per supported service (SupportedCommands 01/03), from the same source. History
         * sync (#82) gates on 07/0A-0D (step and legacy sleep history), 17/01 (workouts), 20/09 (automatic
         * stress), 34/01 (P2P) and 17/0C (pace), so those are asked too, as are 1A/01, 1A/05 and 1A/06 (account).
         */
        val CAPABILITY_COMMANDS: Map<Int, ByteArray> = linkedMapOf(
            0x01 to bytes(0x02, 0x03, 0x07, 0x08, 0x27, 0x31, 0x37),
            0x07 to bytes(0x03, 0x07, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x13, 0x16, 0x17, 0x1C, 0x1D, 0x21, 0x22, 0x23, 0x24,
                0x25, 0x28, 0x29, 0x2A),
            0x0A to bytes(0x01, 0x02, 0x03, 0x04, 0x05, 0x06),
            0x17 to bytes(0x01, 0x07, 0x08, 0x0A, 0x0C, 0x0E, 0x14, 0x15, 0x16, 0x17),
            0x2C to bytes(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07),
            0x20 to bytes(0x09),
            0x34 to bytes(0x01),
            0x1A to bytes(0x01, 0x05, 0x06),
        )

        private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

        /** One line naming what failed, worded only from what the auth machine reported. */
        fun describe(error: HuaweiAuthError): String = when (error) {
            is HuaweiAuthError.DeviceError -> "band returned 0x%08X at %s".format(error.code, error.stage)
            is HuaweiAuthError.MacMismatch -> "band proof did not verify at ${error.stage}"
            is HuaweiAuthError.Timeout -> "no reply at ${error.stage}"
            is HuaweiAuthError.UnknownMode -> "unknown auth mode (support type ${error.deviceSupportType}, auth type ${error.authType})"
            is HuaweiAuthError.NeedsFirstPairing -> "needs a first pairing (${error.reason})"
            is HuaweiAuthError.HiChainError -> "HiChain error ${error.errorCode} at ${error.stage}"
            is HuaweiAuthError.Protocol -> "protocol error${error.stage?.let { " at $it" } ?: ""} (${error.message})"
        }
    }
}
