import Foundation

// Garmin battery policy (Garmin parent issue, child "Battery policy and measurement").
//
// The watch already records 24/7 at its own cadence, so NOOP never has to stream to capture data:
//   - radio-idle is the default state,
//   - live channels are a VIEWER (open only while someone is watching),
//   - history sync is the RECORDER (short batched bursts).
//
// Everything here is pure value logic with no CoreBluetooth or store dependency, so the Garmin source,
// the live-channel layer and history sync all ask this one type instead of each growing its own rule.
// Kotlin twin: android/app/src/main/java/com/noop/ble/GarminRadioPolicy.kt. The constants, the channel
// sets, the linger rule, the backoff curve and the telemetry line are kept identical across platforms.
// Measurement protocol and results: docs/GARMIN_BATTERY.md.

/// A Garmin realtime multi-link channel. The raw value is the ML service id the watch registers.
enum GarminLiveChannel: Int, CaseIterable, Comparable {
    case heartRate = 6
    case steps = 7
    case calories = 8
    case floors = 9
    case intensity = 10
    case rrIntervals = 12
    case stress = 13
    case accelerometer = 16
    case spo2 = 19
    case bodyBattery = 20
    case respiration = 21

    static func < (lhs: GarminLiveChannel, rhs: GarminLiveChannel) -> Bool { lhs.rawValue < rhs.rawValue }
}

/// What the app knows at one moment that decides which live channels may be open.
struct GarminRadioInputs: Equatable {
    var appInForeground: Bool
    /// The live screen is on screen (implies foreground on iOS/Android; checked anyway).
    var liveScreenVisible: Bool = false
    /// A NOOP workout is recording. The only demand that survives the app going to the background.
    var workoutActive: Bool = false
    /// The "continuous live" toggle (off by default, shown with a battery warning).
    var continuousLiveEnabled: Bool = false
    /// The user is asleep. Unattended demand (continuous live) stops; sleep comes from history files.
    var asleep: Bool = false
    /// Optional channels whose UI is currently on screen (Body Battery card, respiration tile, …).
    var onScreenChannels: Set<GarminLiveChannel> = []
    /// Channels the user explicitly asked for (e.g. tapped "Measure SpO2"). The only way SpO2 opens.
    var explicitChannels: Set<GarminLiveChannel> = []
}

/// What prompted a history-sync attempt.
enum GarminSyncTrigger: CaseIterable {
    case periodic    // the repeating timer (mode a) or the reconnect timer (mode b)
    case connect     // a (re)connect completed its handshake
    case foreground  // the app became active
    case manual      // the user tapped "Sync now"
    case watch       // the watch announced data (SYNCHRONIZATION 5037, FILE_AVAILABLE 5009, ItemAdded)
}

/// How the link is held between syncs. Both are measured (docs/GARMIN_BATTERY.md); the cheaper ships.
enum GarminConnectionMode: CaseIterable {
    case persistentIdle  // (a) stay connected and idle, as Garmin Connect does
    case connectOnSync   // (b) connect, drain history, disconnect; reconnect on the periodic floor
}

/// What the link is doing right now, for the Android connection-priority request.
enum GarminLinkActivity: CaseIterable {
    case idle
    case live
    case fileTransfer
}

/// Android `BluetoothGatt.CONNECTION_PRIORITY_*`. iOS has no equivalent; the value is still computed
/// on both platforms so the rule is tested in one place.
enum GarminConnectionPriority {
    case lowPower
    case balanced
    case high
}

/// How to reach the watch.
enum GarminDiscoveryAction {
    case connectByIdentifier
    case scan
    case none
}

enum GarminRadioPolicy {
    // Live channels.

    /// A channel stays open this long after the last viewer leaves, so flipping between screens does
    /// not re-register handles. Backgrounding the app skips the linger.
    static let liveLingerSeconds: TimeInterval = 30
    /// Opened whenever there is any live demand.
    static let defaultChannels: Set<GarminLiveChannel> = [.heartRate, .rrIntervals]
    /// Opened only while their UI is on screen, and only in the foreground.
    static let onScreenOnlyChannels: Set<GarminLiveChannel> = [
        .steps, .stress, .floors, .calories, .intensity, .bodyBattery, .respiration,
    ]
    /// Never opened automatically. SpO2 likely powers the pulse-ox sensor; the accelerometer streams at
    /// ~26 Hz with no consumer. They open only on an explicit foreground request, close the moment that
    /// request ends (no linger), and never ride along with a background workout.
    static let explicitOnlyChannels: Set<GarminLiveChannel> = [.accelerometer, .spo2]

    // History sync.

    static let periodicFloorSeconds: TimeInterval = 900   // 15 min
    static let eventFloorSeconds: TimeInterval = 90       // absorbs reconnect flaps and event bursts
    static let emptyBackoffThreshold = 3                  // empty syncs before the floor stretches
    static let maxEmptyBackoff: Double = 4                // 900 s × 4 = the 1 h cap

    // Connection.

    static let requestedMTU = 515
    /// DownloadRequest flag 0x01, where the watch supports it.
    static let requestsCompression = true
    /// PROVISIONAL until the mode (a) / mode (b) comparison in docs/GARMIN_BATTERY.md is measured.
    static let defaultConnectionMode: GarminConnectionMode = .persistentIdle
    /// HOST_DID_ENTER_BACKGROUND / FOREGROUND. PROVISIONAL: kept only if measurement shows a benefit.
    static let sendsHostLifecycleEvents = true
    /// Measured watch battery cost of live HR + RR, in percent per hour. nil = not measured yet, and
    /// the "continuous live" toggle must say so rather than show a guess.
    static let measuredLiveCostPercentPerHour: Double? = nil

    /// The channels that should be open right now, ignoring the linger. Pure function of the inputs.
    static func desiredChannels(_ inputs: GarminRadioInputs) -> Set<GarminLiveChannel> {
        guard inputs.appInForeground else {
            // Backgrounded: only an active workout keeps anything open, and only the default pair.
            return inputs.workoutActive ? defaultChannels : []
        }
        let watched = inputs.liveScreenVisible || inputs.workoutActive
            || (inputs.continuousLiveEnabled && !inputs.asleep)
        var channels: Set<GarminLiveChannel> = []
        if watched {
            channels.formUnion(defaultChannels)
            channels.formUnion(inputs.onScreenChannels.intersection(onScreenOnlyChannels))
        }
        // An explicit request is itself a viewer: the user is in the app asking for that reading.
        channels.formUnion(inputs.explicitChannels.intersection(explicitOnlyChannels))
        return channels
    }

    /// Multiplier on the automatic sync floors after consecutive empty syncs: 1 below the threshold,
    /// then 2, 4, capped at `maxEmptyBackoff`. The first non-empty sync resets the streak to 0.
    static func syncBackoff(emptyStreak: Int) -> Double {
        guard emptyStreak >= emptyBackoffThreshold else { return 1 }
        return min(pow(2.0, Double(emptyStreak - emptyBackoffThreshold + 1)), maxEmptyBackoff)
    }

    /// Whether a history sync may start. `.manual` always runs; `.connect` / `.foreground` use the
    /// event floor and never back off; the automatic `.watch` / `.periodic` stretch with the empty
    /// streak. In mode (b) the reconnect itself is gated by `.periodic`.
    static func shouldSync(trigger: GarminSyncTrigger, now: TimeInterval,
                           lastSyncAt: TimeInterval?, emptyStreak: Int = 0) -> Bool {
        guard let last = lastSyncAt else { return true }
        let elapsed = now - last
        switch trigger {
        case .manual:               return true
        case .connect, .foreground: return elapsed >= eventFloorSeconds
        case .watch:                return elapsed >= eventFloorSeconds * syncBackoff(emptyStreak: emptyStreak)
        case .periodic:             return elapsed >= periodicFloorSeconds * syncBackoff(emptyStreak: emptyStreak)
        }
    }

    /// Seconds a file the watch keeps appending to (today's monitor, metrics or HRV file) waits after its
    /// last ingestion before a sync downloads it whole again (`GarminSyncRequest.grownFileFloorSeconds`).
    /// `.manual` never waits; `.foreground` waits 15 min, so an opened app is never more than that behind;
    /// `.connect`, `.watch` and `.periodic` wait 1 h, so the watch's every-few-minutes announcements stop
    /// re-sending the same day. Kotlin twin: `GarminRadioPolicy.grownFileFloorSeconds`.
    static func grownFileFloorSeconds(_ trigger: GarminSyncTrigger) -> Int64 {
        switch trigger {
        case .manual:                        return 0
        case .foreground:                    return 15 * 60
        case .connect, .watch, .periodic:    return 60 * 60
        }
    }

    /// Android connection priority: HIGH only while a file transfer runs.
    static func connectionPriority(_ activity: GarminLinkActivity) -> GarminConnectionPriority {
        switch activity {
        case .idle:         return .lowPower
        case .live:         return .balanced
        case .fileTransfer: return .high
        }
    }

    /// Never scan in the background. A known watch is reached by its stored identifier; scanning is
    /// only for first pairing, with the app open.
    static func discoveryAction(appInForeground: Bool, hasStoredIdentifier: Bool) -> GarminDiscoveryAction {
        if hasStoredIdentifier { return .connectByIdentifier }
        return appInForeground ? .scan : .none
    }

    /// Mode (b) drops the link once the history drain finishes, unless a live channel still needs it.
    static func shouldDisconnectAfterSync(mode: GarminConnectionMode, holdingLiveChannels: Bool) -> Bool {
        mode == .connectOnSync && !holdingLiveChannels
    }
}

/// The channels to hold open, and when to ask again.
struct GarminLiveHold: Equatable {
    var channels: Set<GarminLiveChannel>
    /// The earliest moment a lingering channel expires. nil = nothing is lingering; no timer needed.
    var recheckAt: TimeInterval?
}

/// Applies the 30 s auto-close on top of `GarminRadioPolicy.desiredChannels`. The caller feeds every
/// input change (and each `recheckAt`) through `update`, then opens what was added and closes what was
/// dropped. Pure value type: no timers, no clock of its own.
struct GarminLiveChannelLinger {
    /// When each channel was last wanted. A channel desired at the previous update stays wanted up to
    /// THIS update, because demand only ends at the input change that removes it: the 30 s count
    /// starts when the viewer leaves, not at the last update before that.
    private(set) var lastWantedAt: [GarminLiveChannel: TimeInterval] = [:]
    private var previouslyDesired: Set<GarminLiveChannel> = []

    mutating func update(_ inputs: GarminRadioInputs, now: TimeInterval) -> GarminLiveHold {
        let desired = GarminRadioPolicy.desiredChannels(inputs)
        for channel in desired.union(previouslyDesired) { lastWantedAt[channel] = now }
        previouslyDesired = desired
        var hold = desired
        var recheckAt: TimeInterval?
        for (channel, wantedAt) in lastWantedAt where !desired.contains(channel) {
            let expiry = wantedAt + GarminRadioPolicy.liveLingerSeconds
            let lingers = inputs.appInForeground
                && !GarminRadioPolicy.explicitOnlyChannels.contains(channel)
                && now < expiry
            if lingers {
                hold.insert(channel)
                recheckAt = min(recheckAt ?? expiry, expiry)
            } else {
                lastWantedAt[channel] = nil
            }
        }
        return GarminLiveHold(channels: hold, recheckAt: recheckAt)
    }
}

/// Local per-session radio counters for the Test Centre log. Nothing here leaves the device.
struct GarminRadioTelemetry {
    private(set) var connectedSeconds: TimeInterval = 0
    private(set) var bytesIn = 0
    private(set) var bytesOut = 0
    private(set) var syncSessions = 0
    private(set) var handleOpens: [GarminLiveChannel: Int] = [:]
    private(set) var handleOpenSeconds: [GarminLiveChannel: TimeInterval] = [:]
    private var connectedSince: TimeInterval?
    private var openSince: [GarminLiveChannel: TimeInterval] = [:]

    mutating func linkUp(at now: TimeInterval) {
        if connectedSince == nil { connectedSince = now }
    }

    /// Also closes every open handle: a dropped link closes them on the watch too.
    mutating func linkDown(at now: TimeInterval) {
        if let since = connectedSince { connectedSeconds += max(0, now - since) }
        connectedSince = nil
        for channel in openSince.keys.sorted() { channelClosed(channel, at: now) }
    }

    mutating func channelOpened(_ channel: GarminLiveChannel, at now: TimeInterval) {
        guard openSince[channel] == nil else { return }
        openSince[channel] = now
        handleOpens[channel, default: 0] += 1
    }

    mutating func channelClosed(_ channel: GarminLiveChannel, at now: TimeInterval) {
        guard let since = openSince.removeValue(forKey: channel) else { return }
        handleOpenSeconds[channel, default: 0] += max(0, now - since)
    }

    mutating func recordBytes(inbound: Int = 0, outbound: Int = 0) {
        bytesIn += inbound
        bytesOut += outbound
    }

    mutating func syncSessionCompleted() { syncSessions += 1 }

    /// One Test Centre line, with in-progress spans counted up to `now`. Seconds are truncated to
    /// whole numbers so both platforms print the same text.
    func summary(now: TimeInterval) -> String {
        let connected = connectedSeconds + (connectedSince.map { max(0, now - $0) } ?? 0)
        let channels = Set(handleOpens.keys).sorted().map { channel -> String in
            let open = (handleOpenSeconds[channel] ?? 0) + (openSince[channel].map { max(0, now - $0) } ?? 0)
            return "\(channel.rawValue):\(handleOpens[channel] ?? 0)x/\(Int(open))s"
        }
        let handles = channels.isEmpty ? "none" : channels.joined(separator: ",")
        return "garmin radio: connected=\(Int(connected))s bytes_in=\(bytesIn) bytes_out=\(bytesOut) "
            + "syncs=\(syncSessions) handles=\(handles)"
    }
}
