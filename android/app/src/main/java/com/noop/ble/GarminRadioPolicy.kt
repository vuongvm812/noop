package com.noop.ble

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

// Garmin battery policy (Garmin parent issue, child "Battery policy and measurement").
//
// The watch already records 24/7 at its own cadence, so NOOP never has to stream to capture data:
//   - radio-idle is the default state,
//   - live channels are a VIEWER (open only while someone is watching),
//   - history sync is the RECORDER (short batched bursts).
//
// Twin of the Swift `GarminRadioPolicy` (Strand/BLE/GarminRadioPolicy.swift). The constants, the channel
// sets, the linger rule, the backoff curve and the telemetry line are kept identical; GarminRadioPolicyTest
// pins them against the Swift oracle's stdout. Measurement protocol and results: docs/GARMIN_BATTERY.md.

/** A Garmin realtime multi-link channel. [serviceId] is the ML service id the watch registers. */
enum class GarminLiveChannel(val serviceId: Int) {
    HEART_RATE(6),
    STEPS(7),
    CALORIES(8),
    FLOORS(9),
    INTENSITY(10),
    RR_INTERVALS(12),
    STRESS(13),
    ACCELEROMETER(16),
    SPO2(19),
    BODY_BATTERY(20),
    RESPIRATION(21),
}

/** What the app knows at one moment that decides which live channels may be open. */
data class GarminRadioInputs(
    val appInForeground: Boolean,
    /** The live screen is on screen. */
    val liveScreenVisible: Boolean = false,
    /** A NOOP workout is recording. The only demand that survives the app going to the background. */
    val workoutActive: Boolean = false,
    /** The "continuous live" toggle (off by default, shown with a battery warning). */
    val continuousLiveEnabled: Boolean = false,
    /** The user is asleep. Unattended demand (continuous live) stops; sleep comes from history files. */
    val asleep: Boolean = false,
    /** Optional channels whose UI is currently on screen (Body Battery card, respiration tile, …). */
    val onScreenChannels: Set<GarminLiveChannel> = emptySet(),
    /** Channels the user explicitly asked for (e.g. tapped "Measure SpO2"). The only way SpO2 opens. */
    val explicitChannels: Set<GarminLiveChannel> = emptySet(),
)

/** What prompted a history-sync attempt. */
enum class GarminSyncTrigger {
    /** The repeating timer (mode a) or the reconnect timer (mode b). */
    PERIODIC,

    /** A (re)connect completed its handshake. */
    CONNECT,

    /** The app became active. */
    FOREGROUND,

    /** The user tapped "Sync now". */
    MANUAL,

    /** The watch announced data (SYNCHRONIZATION 5037, FILE_AVAILABLE 5009, ItemAdded). */
    WATCH,
}

/** How the link is held between syncs. Both are measured (docs/GARMIN_BATTERY.md); the cheaper ships. */
enum class GarminConnectionMode {
    /** (a) Stay connected and idle, as Garmin Connect does. */
    PERSISTENT_IDLE,

    /** (b) Connect, drain history, disconnect; reconnect on the periodic floor. */
    CONNECT_ON_SYNC,
}

/** What the link is doing right now, for the connection-priority request. */
enum class GarminLinkActivity { IDLE, LIVE, FILE_TRANSFER }

/** Maps to `BluetoothGatt.CONNECTION_PRIORITY_*` at the call site. */
enum class GarminConnectionPriority { LOW_POWER, BALANCED, HIGH }

/** How to reach the watch. */
enum class GarminDiscoveryAction { CONNECT_BY_IDENTIFIER, SCAN, NONE }

object GarminRadioPolicy {
    /** A channel stays open this long after the last viewer leaves. Backgrounding skips the linger. */
    const val LIVE_LINGER_SECONDS = 30.0

    /** Opened whenever there is any live demand. */
    val DEFAULT_CHANNELS: Set<GarminLiveChannel> =
        setOf(GarminLiveChannel.HEART_RATE, GarminLiveChannel.RR_INTERVALS)

    /** Opened only while their UI is on screen, and only in the foreground. */
    val ON_SCREEN_ONLY_CHANNELS: Set<GarminLiveChannel> = setOf(
        GarminLiveChannel.STEPS, GarminLiveChannel.STRESS, GarminLiveChannel.FLOORS,
        GarminLiveChannel.CALORIES, GarminLiveChannel.INTENSITY, GarminLiveChannel.BODY_BATTERY,
        GarminLiveChannel.RESPIRATION,
    )

    /**
     * Never opened automatically. SpO2 likely powers the pulse-ox sensor; the accelerometer streams at
     * ~26 Hz with no consumer. They open only on an explicit foreground request, close the moment that
     * request ends (no linger), and never ride along with a background workout.
     */
    val EXPLICIT_ONLY_CHANNELS: Set<GarminLiveChannel> =
        setOf(GarminLiveChannel.ACCELEROMETER, GarminLiveChannel.SPO2)

    const val PERIODIC_FLOOR_SECONDS = 900.0 // 15 min
    const val EVENT_FLOOR_SECONDS = 90.0 // absorbs reconnect flaps and event bursts
    const val EMPTY_BACKOFF_THRESHOLD = 3 // empty syncs before the floor stretches
    const val MAX_EMPTY_BACKOFF = 4.0 // 900 s x 4 = the 1 h cap

    const val REQUESTED_MTU = 515

    /** DownloadRequest flag 0x01, where the watch supports it. */
    const val REQUESTS_COMPRESSION = true

    /** PROVISIONAL until the mode (a) / mode (b) comparison in docs/GARMIN_BATTERY.md is measured. */
    val DEFAULT_CONNECTION_MODE = GarminConnectionMode.PERSISTENT_IDLE

    /** HOST_DID_ENTER_BACKGROUND / FOREGROUND. PROVISIONAL: kept only if measurement shows a benefit. */
    const val SENDS_HOST_LIFECYCLE_EVENTS = true

    /**
     * Measured watch battery cost of live HR + RR, in percent per hour. null = not measured yet, and the
     * "continuous live" toggle must say so rather than show a guess.
     */
    val MEASURED_LIVE_COST_PERCENT_PER_HOUR: Double? = null

    /** The channels that should be open right now, ignoring the linger. Pure function of the inputs. */
    fun desiredChannels(inputs: GarminRadioInputs): Set<GarminLiveChannel> {
        if (!inputs.appInForeground) {
            // Backgrounded: only an active workout keeps anything open, and only the default pair.
            return if (inputs.workoutActive) DEFAULT_CHANNELS else emptySet()
        }
        val watched = inputs.liveScreenVisible || inputs.workoutActive ||
            (inputs.continuousLiveEnabled && !inputs.asleep)
        val channels = mutableSetOf<GarminLiveChannel>()
        if (watched) {
            channels += DEFAULT_CHANNELS
            channels += inputs.onScreenChannels.intersect(ON_SCREEN_ONLY_CHANNELS)
        }
        // An explicit request is itself a viewer: the user is in the app asking for that reading.
        channels += inputs.explicitChannels.intersect(EXPLICIT_ONLY_CHANNELS)
        return channels
    }

    /**
     * Multiplier on the automatic sync floors after consecutive empty syncs: 1 below the threshold, then
     * 2, 4, capped at [MAX_EMPTY_BACKOFF]. The first non-empty sync resets the streak to 0.
     */
    fun syncBackoff(emptyStreak: Int): Double {
        if (emptyStreak < EMPTY_BACKOFF_THRESHOLD) return 1.0
        return min(2.0.pow((emptyStreak - EMPTY_BACKOFF_THRESHOLD + 1).toDouble()), MAX_EMPTY_BACKOFF)
    }

    /**
     * Whether a history sync may start. MANUAL always runs; CONNECT / FOREGROUND use the event floor and
     * never back off; the automatic WATCH / PERIODIC stretch with the empty streak. In mode (b) the
     * reconnect itself is gated by PERIODIC.
     */
    fun shouldSync(
        trigger: GarminSyncTrigger,
        nowSeconds: Double,
        lastSyncAtSeconds: Double?,
        emptyStreak: Int = 0,
    ): Boolean {
        val last = lastSyncAtSeconds ?: return true
        val elapsed = nowSeconds - last
        return when (trigger) {
            GarminSyncTrigger.MANUAL -> true
            GarminSyncTrigger.CONNECT, GarminSyncTrigger.FOREGROUND -> elapsed >= EVENT_FLOOR_SECONDS
            GarminSyncTrigger.WATCH -> elapsed >= EVENT_FLOOR_SECONDS * syncBackoff(emptyStreak)
            GarminSyncTrigger.PERIODIC -> elapsed >= PERIODIC_FLOOR_SECONDS * syncBackoff(emptyStreak)
        }
    }

    /**
     * Seconds a file the watch keeps appending to (today's monitor, metrics or HRV file) waits after its last
     * ingestion before a sync downloads it whole again ([com.noop.garmin.GarminSyncRequest.grownFileFloorSeconds]).
     * MANUAL never waits; FOREGROUND waits 15 min, so an opened app is never more than that behind; CONNECT,
     * WATCH and PERIODIC wait 1 h, so the watch's every-few-minutes announcements stop re-sending the same day.
     * Swift twin: `GarminRadioPolicy.grownFileFloorSeconds`.
     */
    fun grownFileFloorSeconds(trigger: GarminSyncTrigger): Long = when (trigger) {
        GarminSyncTrigger.MANUAL -> 0L
        GarminSyncTrigger.FOREGROUND -> 15L * 60
        GarminSyncTrigger.CONNECT, GarminSyncTrigger.WATCH, GarminSyncTrigger.PERIODIC -> 60L * 60
    }

    /** Connection priority: HIGH only while a file transfer runs. */
    fun connectionPriority(activity: GarminLinkActivity): GarminConnectionPriority = when (activity) {
        GarminLinkActivity.IDLE -> GarminConnectionPriority.LOW_POWER
        GarminLinkActivity.LIVE -> GarminConnectionPriority.BALANCED
        GarminLinkActivity.FILE_TRANSFER -> GarminConnectionPriority.HIGH
    }

    /**
     * Never scan in the background. A known watch is reached by its stored identifier; scanning is only
     * for first pairing, with the app open.
     */
    fun discoveryAction(appInForeground: Boolean, hasStoredIdentifier: Boolean): GarminDiscoveryAction = when {
        hasStoredIdentifier -> GarminDiscoveryAction.CONNECT_BY_IDENTIFIER
        appInForeground -> GarminDiscoveryAction.SCAN
        else -> GarminDiscoveryAction.NONE
    }

    /** Mode (b) drops the link once the history drain finishes, unless a live channel still needs it. */
    fun shouldDisconnectAfterSync(mode: GarminConnectionMode, holdingLiveChannels: Boolean): Boolean =
        mode == GarminConnectionMode.CONNECT_ON_SYNC && !holdingLiveChannels
}

/** The channels to hold open, and when to ask again ([recheckAtSeconds] null = nothing lingering). */
data class GarminLiveHold(
    val channels: Set<GarminLiveChannel>,
    val recheckAtSeconds: Double?,
)

/**
 * Applies the 30 s auto-close on top of [GarminRadioPolicy.desiredChannels]. The caller feeds every input
 * change (and each [GarminLiveHold.recheckAtSeconds]) through [update], then opens what was added and
 * closes what was dropped. No timers, no clock of its own.
 */
class GarminLiveChannelLinger {
    /**
     * When each channel was last wanted. A channel desired at the previous update stays wanted up to THIS
     * update, because demand only ends at the input change that removes it: the 30 s count starts when the
     * viewer leaves, not at the last update before that.
     */
    private val lastWantedAt = mutableMapOf<GarminLiveChannel, Double>()
    private var previouslyDesired: Set<GarminLiveChannel> = emptySet()

    fun update(inputs: GarminRadioInputs, nowSeconds: Double): GarminLiveHold {
        val desired = GarminRadioPolicy.desiredChannels(inputs)
        for (channel in desired + previouslyDesired) lastWantedAt[channel] = nowSeconds
        previouslyDesired = desired
        val hold = desired.toMutableSet()
        var recheckAt: Double? = null
        val iterator = lastWantedAt.entries.iterator()
        while (iterator.hasNext()) {
            val (channel, wantedAt) = iterator.next()
            if (channel in desired) continue
            val expiry = wantedAt + GarminRadioPolicy.LIVE_LINGER_SECONDS
            val lingers = inputs.appInForeground &&
                channel !in GarminRadioPolicy.EXPLICIT_ONLY_CHANNELS &&
                nowSeconds < expiry
            if (lingers) {
                hold += channel
                recheckAt = min(recheckAt ?: expiry, expiry)
            } else {
                iterator.remove()
            }
        }
        return GarminLiveHold(hold, recheckAt)
    }
}

/** Local per-session radio counters for the Test Centre log. Nothing here leaves the device. */
class GarminRadioTelemetry {
    var connectedSeconds = 0.0
        private set
    var bytesIn = 0L
        private set
    var bytesOut = 0L
        private set
    var syncSessions = 0
        private set
    private val handleOpens = mutableMapOf<GarminLiveChannel, Int>()
    private val handleOpenSeconds = mutableMapOf<GarminLiveChannel, Double>()
    private var connectedSince: Double? = null
    private val openSince = mutableMapOf<GarminLiveChannel, Double>()

    fun opens(channel: GarminLiveChannel): Int = handleOpens[channel] ?: 0

    fun openSeconds(channel: GarminLiveChannel): Double = handleOpenSeconds[channel] ?: 0.0

    fun linkUp(nowSeconds: Double) {
        if (connectedSince == null) connectedSince = nowSeconds
    }

    /** Also closes every open handle: a dropped link closes them on the watch too. */
    fun linkDown(nowSeconds: Double) {
        connectedSince?.let { connectedSeconds += max(0.0, nowSeconds - it) }
        connectedSince = null
        for (channel in openSince.keys.sortedBy { it.serviceId }) channelClosed(channel, nowSeconds)
    }

    fun channelOpened(channel: GarminLiveChannel, nowSeconds: Double) {
        if (openSince.containsKey(channel)) return
        openSince[channel] = nowSeconds
        handleOpens[channel] = opens(channel) + 1
    }

    fun channelClosed(channel: GarminLiveChannel, nowSeconds: Double) {
        val since = openSince.remove(channel) ?: return
        handleOpenSeconds[channel] = openSeconds(channel) + max(0.0, nowSeconds - since)
    }

    fun recordBytes(inbound: Int = 0, outbound: Int = 0) {
        bytesIn += inbound
        bytesOut += outbound
    }

    fun syncSessionCompleted() {
        syncSessions += 1
    }

    /**
     * One Test Centre line, with in-progress spans counted up to [nowSeconds]. Seconds are truncated to
     * whole numbers so both platforms print the same text.
     */
    fun summary(nowSeconds: Double): String {
        val connected = connectedSeconds + (connectedSince?.let { max(0.0, nowSeconds - it) } ?: 0.0)
        val channels = handleOpens.keys.sortedBy { it.serviceId }.map { channel ->
            val open = openSeconds(channel) + (openSince[channel]?.let { max(0.0, nowSeconds - it) } ?: 0.0)
            "${channel.serviceId}:${opens(channel)}x/${open.toLong()}s"
        }
        val handles = if (channels.isEmpty()) "none" else channels.joinToString(",")
        return "garmin radio: connected=${connected.toLong()}s bytes_in=$bytesIn bytes_out=$bytesOut " +
            "syncs=$syncSessions handles=$handles"
    }
}
