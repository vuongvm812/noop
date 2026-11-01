import Foundation
import CoreBluetooth
import GarminProtocol
#if canImport(UIKit)
import UIKit
#elseif canImport(AppKit)
import AppKit
#endif

/// What the live-channel layer reports to the app: a decoded reading, a channel's handle opening or closing
/// on the watch, or the link going down (which closes every channel).
enum GarminLiveEvent: Equatable {
    case reading(GarminRealtimeReading)
    case channel(GarminService, open: Bool)
    case linkDown
}

/// EXPERIMENTAL Garmin watch source over the watch's own GFDI protocol (the `GarminProtocol` package).
///
/// Holds one stable, low-power session: connect by stored identifier, pick the transport the watch offers
/// (V2 multi-link, else V1, else V0, else stop and say "unsupported watch protocol"), subscribe, then let
/// the pure `GarminSession` run the handshake. Every byte written comes out of that session, so only the
/// allowlisted `GarminOutbound` messages and ML control frames can reach the watch.
///
/// Battery hooks (`GarminRadioPolicy`): never scans in the background, no keep-alive traffic, the minimal
/// capability set, and the host foreground/background events.
///
/// History sync (read-only): on the watch's own "new data" notices, on connect, on foreground, on the
/// periodic floor and on "Sync now", each gated by `GarminRadioPolicy.shouldSync`, one batched session
/// downloads every new FIT file. What counts as "already downloaded" comes from `loadSynced` (the
/// `garminSyncedFile` ledger); each file goes to `ingest`, which decodes, stores and records it. Nothing
/// on the watch is archived, flagged or deleted. A transfer cut short by a dropped link is continued from
/// its last checked offset on the next sync.
///
/// Live channels are a VIEWER: the app reports who is watching (`updateLiveDemand`), `GarminRadioPolicy`
/// with its 30 s linger turns that into a channel set, and the session registers / closes the handles.
/// Nothing opens without demand, nothing opens in the background except for an active workout, SpO2 opens
/// only on an explicit request, and the accelerometer never opens. Readings go out through `onLive`.
///
/// Owns its OWN `CBCentralManager` and never references `BLEManager`, so the WHOOP path cannot regress.
/// Uses only Foundation, CoreBluetooth and GarminProtocol plus `GarminRadioPolicy`, so it can also be
/// compiled into a bare command-line harness for unattended tests against a real watch.
/// Kotlin twin: android/app/src/main/java/com/noop/ble/GarminSource.kt.
@MainActor
final class GarminSource: NSObject, ObservableObject {

    enum LinkPhase: String { case disconnected, connecting, discovering, subscribing, handshaking, ready, unsupported }

    struct DiscoveredWatch: Identifiable, Equatable {
        let id: UUID
        let name: String
        let rssi: Int
    }

    @Published private(set) var linkPhase: LinkPhase = .disconnected
    @Published private(set) var batteryPct: Int?
    @Published private(set) var watch: GarminDeviceInformation?
    @Published private(set) var transport: GarminTransportKind?
    /// Set when the watch offers no transport NOOP speaks. The source stops and does not retry.
    @Published private(set) var unsupportedReason: String?
    @Published private(set) var discovered: [DiscoveredWatch] = []
    /// A history sync is running.
    @Published private(set) var syncing = false
    @Published private(set) var lastSyncSummary: GarminSyncSummary?
    /// Bytes of the file transfer in flight, nil between files (sync status line, Test Centre).
    var transferOffset: Int? { session?.syncResumePoint.map { Int($0.offset) } }
    /// Live channels whose handle is open on the watch right now (the watch confirmed the registration).
    @Published private(set) var openLiveChannels: Set<GarminLiveChannel> = []

    private let deviceId: String
    private let isFirstPairing: () -> Bool
    private let markPaired: () -> Void
    private let log: (String) -> Void
    /// Per-connect readouts (every message in and out) only while this returns true (a Test Centre domain).
    private let detail: () -> Bool
    private let onBattery: (Int) -> Void
    private let onWatch: (GarminDeviceInformation) -> Void
    private let onSyncAnnounced: () -> Void
    private let onLive: (GarminLiveEvent) -> Void
    private let nameMatches: (String) -> Bool
    private let loadSynced: () async -> Set<GarminSyncFileKey>
    private let ingest: (GarminDownloadedFile) async -> Bool
    private let onSyncFinished: (GarminSyncSummary) -> Void
    private let syncFlow: (GarminDeviceInformation?) -> GarminSyncFlow
    /// DownloadRequest compression; the policy's value unless a caller (a Test Centre toggle, the hardware
    /// harness) needs uncompressed transfers.
    private let requestsCompression: Bool

    private var central: CBCentralManager!
    private var peripheral: CBPeripheral?
    private var notifyChar: CBCharacteristic?
    private var writeChar: CBCharacteristic?
    private var session: GarminSession?
    private var pendingServices = 0
    private var gattServices: [GarminGattService] = []
    private var writeQueue: [Data] = []
    private var deadlineTimer: DispatchWorkItem?
    private var reconnectTimer: DispatchWorkItem?
    private var telemetry = GarminRadioTelemetry()

    /// Who is watching. `appInForeground` is always overwritten with this source's own foreground state.
    private var liveDemand = GarminRadioInputs(appInForeground: true)
    private var linger = GarminLiveChannelLinger()
    /// The channel set the policy currently holds open (demand plus linger), handed to every session.
    private var wantedLive: Set<GarminService> = []
    /// A dispatch source rather than `asyncAfter`: GCD gives `asyncAfter` a leeway that grows with the delay,
    /// which closed the 30 s linger ~1.7 s late on hardware. 100 ms is plenty for an auto-close.
    private var lingerTimer: DispatchSourceTimer?

    /// The watch to keep reaching. nil = no target (stopped, or never started).
    private var reconnectID: UUID?
    private var pendingConnectID: UUID?
    private var intentionalDisconnect = false
    private var wantsScan = false
    private var failedConnects = 0
    private var foreground = true

    // History sync state. `lastSyncAt` / `emptySyncStreak` persist per device so a relaunch keeps the floor.
    private var syncPreparing = false
    private var heldResume: GarminSyncResume?
    private var periodicTimer: DispatchWorkItem?
    /// Files are ingested one at a time, in arrival order.
    private var ingestTail: Task<Void, Never>?
    private var lastSyncAtKey: String { "noop.garmin.sync.lastAt.\(deviceId)" }
    private var emptyStreakKey: String { "noop.garmin.sync.emptyStreak.\(deviceId)" }
    /// The first sync of a watch reaches back this far (the watch keeps weeks of monitor files).
    static let historyLookbackSeconds: TimeInterval = 14 * 86_400

    /// Distinct from the WHOOP (`BLEManager`) and Oura restore ids: each restoring central needs its own.
    private static let restoreID = "com.openwhoop.ble.garmin"

    init(deviceId: String,
         isFirstPairing: @escaping () -> Bool = { false },
         markPaired: @escaping () -> Void = {},
         log: @escaping (String) -> Void,
         detail: @escaping () -> Bool = { false },
         onBattery: @escaping (Int) -> Void = { _ in },
         onWatch: @escaping (GarminDeviceInformation) -> Void = { _ in },
         onSyncAnnounced: @escaping () -> Void = {},
         onLive: @escaping (GarminLiveEvent) -> Void = { _ in },
         nameMatches: @escaping (String) -> Bool = { _ in false },
         loadSynced: @escaping () async -> Set<GarminSyncFileKey> = { [] },
         ingest: @escaping (GarminDownloadedFile) async -> Bool = { _ in false },
         onSyncFinished: @escaping (GarminSyncSummary) -> Void = { _ in },
         syncFlow: @escaping (GarminDeviceInformation?) -> GarminSyncFlow = { GarminSyncModelPolicy.flow(productNumber: $0?.productNumber) },
         requestsCompression: Bool = GarminRadioPolicy.requestsCompression,
         restoresState: Bool = true) {
        self.deviceId = deviceId
        self.isFirstPairing = isFirstPairing
        self.markPaired = markPaired
        self.log = log
        self.detail = detail
        self.onBattery = onBattery
        self.onWatch = onWatch
        self.onSyncAnnounced = onSyncAnnounced
        self.onLive = onLive
        self.nameMatches = nameMatches
        self.loadSynced = loadSynced
        self.ingest = ingest
        self.onSyncFinished = onSyncFinished
        self.syncFlow = syncFlow
        self.requestsCompression = requestsCompression
        super.init()
        #if os(iOS)
        // iOS state restoration: CoreBluetooth relaunches the app into the background for this central
        // after a suspend-then-jettison, so the watch session resumes without the user opening NOOP.
        if restoresState {
            central = CBCentralManager(delegate: self, queue: nil,
                                       options: [CBCentralManagerOptionRestoreIdentifierKey: Self.restoreID])
        } else {
            central = CBCentralManager(delegate: self, queue: nil)
        }
        #else
        central = CBCentralManager(delegate: self, queue: nil)
        #endif
        observeAppState()
    }

    // MARK: - LiveHRSource

    /// First-pairing discovery only, and only with the app in the foreground (`GarminRadioPolicy`).
    func scan() {
        guard GarminRadioPolicy.discoveryAction(appInForeground: foreground, hasStoredIdentifier: false) == .scan else {
            say(true, "scan skipped - the app is in the background (never scans in the background)")
            return
        }
        intentionalDisconnect = false
        wantsScan = true
        guard central.state == .poweredOn else { return }
        discovered = []
        say(true, "scanning for a Garmin watch (company id 0x0087 or a Garmin name)")
        central.scanForPeripherals(withServices: nil, options: [CBCentralManagerScanOptionAllowDuplicatesKey: false])
    }

    func connect(_ id: UUID) {
        intentionalDisconnect = false
        reconnectID = id
        unsupportedReason = nil
        guard central.state == .poweredOn else {
            pendingConnectID = id
            say(true, "connect deferred until Bluetooth is on")
            return
        }
        guard let p = central.retrievePeripherals(withIdentifiers: [id]).first else {
            say(true, "watch \(id) unknown to this device; scanning")
            scan()
            return
        }
        attach(p)
    }

    func stop() {
        intentionalDisconnect = true
        reconnectID = nil
        pendingConnectID = nil
        wantsScan = false
        reconnectTimer?.cancel()
        deadlineTimer?.cancel()
        periodicTimer?.cancel()
        lingerTimer?.cancel()
        if central.isScanning { central.stopScan() }
        if let p = peripheral { central.cancelPeripheralConnection(p) }
        endSession()
        linkPhase = .disconnected
    }

    /// Drop the current link and let the ordinary reconnect path bring it back (the Live screen's
    /// "Reconnect" affordance; also how the hardware harness forces a disconnect).
    func reconnect() {
        guard let p = peripheral, reconnectID != nil else { return }
        intentionalDisconnect = false
        say(true, "reconnect requested; dropping the link")
        central.cancelPeripheralConnection(p)
    }

    /// "Sync now": a manual sync, which the floor never holds back.
    func syncNow() {
        requestSync(.manual)
    }

    // MARK: - History sync

    private func requestSync(_ trigger: GarminSyncTrigger, watchInitiated: Bool = false) {
        guard linkPhase == .ready, let s = session, s.phase == .ready else { return }
        guard !s.isSyncing, !syncPreparing else {
            say(false, "sync trigger \(trigger) ignored; a sync is already running")
            return
        }
        let defaults = UserDefaults.standard
        let now = Date().timeIntervalSince1970
        let last = defaults.object(forKey: lastSyncAtKey) as? Double
        let streak = defaults.integer(forKey: emptyStreakKey)
        guard GarminRadioPolicy.shouldSync(trigger: trigger, now: now, lastSyncAt: last, emptyStreak: streak) else {
            say(false, "sync trigger \(trigger) held by the floor (last \(last.map { Int(now - $0) } ?? -1) s ago, empty streak \(streak))")
            return
        }
        syncPreparing = true
        let flow = syncFlow(watch)
        Task { @MainActor [weak self] in
            guard let self else { return }
            let synced = await self.loadSynced()
            self.syncPreparing = false
            guard var s = self.session, s.phase == .ready, !s.isSyncing else { return }
            let request = GarminSyncRequest(
                flow: flow, synced: synced, resume: self.heldResume,
                requestCompression: self.requestsCompression,
                watchInitiated: watchInitiated,
                notBeforeUnix: Int64(Date().timeIntervalSince1970 - Self.historyLookbackSeconds))
            self.say(true, "sync (\(trigger)) starting with \(synced.count) files on record")
            self.heldResume = nil
            let outputs = s.startSync(request, self.clock())
            self.session = s
            self.perform(outputs)
        }
    }

    private func syncFinished(_ summary: GarminSyncSummary) {
        lastSyncSummary = summary
        telemetry.syncSessionCompleted()
        let defaults = UserDefaults.standard
        if case .completed = summary.outcome {
            defaults.set(Date().timeIntervalSince1970, forKey: lastSyncAtKey)
            defaults.set(summary.isEmpty ? defaults.integer(forKey: emptyStreakKey) + 1 : 0, forKey: emptyStreakKey)
        }
        if let held = session?.syncResumePoint { heldResume = held }
        onSyncFinished(summary)
        schedulePeriodicSync()
    }

    /// Persistent-idle mode: one sync per periodic floor (stretched by the empty streak) while connected.
    private func schedulePeriodicSync() {
        periodicTimer?.cancel()
        guard GarminRadioPolicy.defaultConnectionMode == .persistentIdle else { return }
        let streak = UserDefaults.standard.integer(forKey: emptyStreakKey)
        let delay = GarminRadioPolicy.periodicFloorSeconds * GarminRadioPolicy.syncBackoff(emptyStreak: streak)
        let work = DispatchWorkItem { [weak self] in
            MainActor.assumeIsolated { self?.requestSync(.periodic) }
        }
        periodicTimer = work
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: work)
    }

    private func enqueueIngest(_ file: GarminDownloadedFile) {
        let previous = ingestTail
        ingestTail = Task { @MainActor [weak self] in
            await previous?.value
            guard let self else { return }
            let stored = await self.ingest(file)
            self.say(!stored, stored ? "ingested \(file.key.label)" : "ingest failed for \(file.key.label); it stays new")
        }
    }

    // MARK: - Live demand

    /// Report who is watching: the live screen, an active workout, the continuous-live toggle, the
    /// optional channels whose UI is on screen, or an explicit request (the only way SpO2 opens). The
    /// policy decides the channels; the 30 s linger keeps them across a quick screen change.
    func updateLiveDemand(_ change: (inout GarminRadioInputs) -> Void) {
        change(&liveDemand)
        applyLiveDemand()
    }

    private func applyLiveDemand() {
        liveDemand.appInForeground = foreground
        let hold = linger.update(liveDemand, now: Self.uptimeSeconds())
        let wanted = Set(hold.channels.compactMap { GarminService(rawValue: UInt16($0.rawValue)) })
        if wanted != wantedLive {
            say(true, "live channels wanted: \(Self.list(wanted))"
                + (hold.recheckAt != nil ? " (lingering \(Int(GarminRadioPolicy.liveLingerSeconds)) s)" : ""))
        }
        wantedLive = wanted
        if var s = session {
            let outputs = s.setLiveChannels(wanted, clock())
            session = s
            perform(outputs)
        }
        lingerTimer?.cancel()
        lingerTimer = nil
        if let at = hold.recheckAt {
            let timer = DispatchSource.makeTimerSource(queue: .main)
            timer.setEventHandler { [weak self] in MainActor.assumeIsolated { self?.applyLiveDemand() } }
            timer.schedule(deadline: .now() + max(0, at - Self.uptimeSeconds()) + 0.05, leeway: .milliseconds(100))
            timer.resume()
            lingerTimer = timer
        }
    }

    nonisolated static func uptimeSeconds() -> TimeInterval {
        Double(DispatchTime.now().uptimeNanoseconds) / 1_000_000_000
    }

    private static func list(_ services: Set<GarminService>) -> String {
        services.isEmpty ? "none" : services.map(\.rawValue).sorted().map(String.init).joined(separator: ",")
    }

    // MARK: - Session plumbing

    private func attach(_ p: CBPeripheral) {
        peripheral = p
        p.delegate = self
        linkPhase = .connecting
        say(false, "connecting to \(p.identifier)")
        // No timeout: the connect stays outstanding until the watch is reachable (also while suspended).
        central.connect(p, options: nil)
    }

    private func clock() -> GarminClock {
        let now = Date()
        let zone = TimeZone.current
        let next = zone.nextDaylightSavingTimeTransition(after: now)
        let following = next.flatMap { zone.nextDaylightSavingTimeTransition(after: $0) }
        return GarminClock(nowMs: Int64(DispatchTime.now().uptimeNanoseconds / 1_000_000),
                           unixSeconds: Int64(now.timeIntervalSince1970),
                           utcOffsetSeconds: Int32(zone.secondsFromGMT(for: now)),
                           nextTransitionUnix: next.map { Int64($0.timeIntervalSince1970) },
                           followingTransitionUnix: following.map { Int64($0.timeIntervalSince1970) })
    }

    private func perform(_ outputs: [GarminSessionOutput]) {
        for output in outputs {
            switch output {
            case let .write(bytes):
                telemetry.recordBytes(outbound: bytes.count)
                enqueueWrite(Data(bytes))
            case let .log(line):
                say(line.rare, line.text)
            case let .phase(phase):
                switch phase {
                case .ready:
                    linkPhase = .ready
                    failedConnects = 0
                    requestSync(.connect)
                case let .failed(reason):
                    // Drop the link; the reconnect path tries again with backoff.
                    say(true, "dropping the link after a session failure (\(reason))")
                    if let p = peripheral { central.cancelPeripheralConnection(p) }
                case .closingAll, .registering, .awaitingDeviceInformation, .handshaking:
                    linkPhase = .handshaking
                case .idle:
                    break
                }
            case let .battery(percent):
                batteryPct = percent
                onBattery(percent)
            case let .watch(info):
                watch = info
                onWatch(info)
            case .pairingEventsSent:
                markPaired()
            case let .syncAnnounced(announcement):
                onSyncAnnounced()
                requestSync(.watch, watchInitiated: announcement.expectsFilter)
            case let .syncFile(file):
                enqueueIngest(file)
            case let .syncFinished(summary):
                syncFinished(summary)
            case let .transferActive(active):
                syncing = active
            case let .live(reading):
                onLive(.reading(reading))
            case let .liveChannel(service, open):
                if let channel = GarminLiveChannel(rawValue: Int(service.rawValue)) {
                    let now = Date().timeIntervalSince1970
                    if open {
                        telemetry.channelOpened(channel, at: now)
                        openLiveChannels.insert(channel)
                    } else {
                        telemetry.channelClosed(channel, at: now)
                        openLiveChannels.remove(channel)
                    }
                }
                onLive(.channel(service, open: open))
            }
        }
        scheduleDeadline()
    }

    private func enqueueWrite(_ data: Data) {
        writeQueue.append(data)
        drainWrites()
    }

    private func drainWrites() {
        guard let p = peripheral, let c = writeChar else { writeQueue.removeAll(); return }
        let withoutResponse = c.properties.contains(.writeWithoutResponse)
        while !writeQueue.isEmpty {
            if withoutResponse {
                guard p.canSendWriteWithoutResponse else { return }   // resumes in peripheralIsReady
                p.writeValue(writeQueue.removeFirst(), for: c, type: .withoutResponse)
            } else {
                p.writeValue(writeQueue.removeFirst(), for: c, type: .withResponse)
            }
        }
    }

    private func scheduleDeadline() {
        deadlineTimer?.cancel()
        guard let deadline = session?.nextDeadlineMs else { return }
        let delayMs = max(0, deadline - clock().nowMs)
        let work = DispatchWorkItem { [weak self] in
            guard let self, var s = self.session else { return }
            let outputs = s.tick(self.clock())
            self.session = s
            self.perform(outputs)
        }
        deadlineTimer = work
        DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(Int(delayMs)), execute: work)
    }

    private func endSession() {
        if let held = session?.syncResumePoint {
            heldResume = held
            say(true, "link down mid-transfer; holding \(held.key.label) at offset \(held.offset) to resume")
        }
        if session?.isSyncing == true {
            syncing = false
            telemetry.syncSessionCompleted()
        }
        periodicTimer?.cancel()
        syncPreparing = false
        if session != nil {
            let now = Date().timeIntervalSince1970
            telemetry.linkDown(at: now)
            say(false, telemetry.summary(now: now))
            openLiveChannels = []
            onLive(.linkDown)
        }
        session = nil
        notifyChar = nil
        writeChar = nil
        writeQueue.removeAll()
        deadlineTimer?.cancel()
        gattServices = []
        pendingServices = 0
    }

    private func scheduleReconnect() {
        guard !intentionalDisconnect, let id = reconnectID, unsupportedReason == nil else { return }
        failedConnects += 1
        let delay = Self.reconnectDelaySeconds(attempt: failedConnects)
        say(true, "reconnect attempt \(failedConnects) in \(Int(delay)) s")
        reconnectTimer?.cancel()
        let work = DispatchWorkItem { [weak self] in
            guard let self, !self.intentionalDisconnect, self.reconnectID == id else { return }
            self.connect(id)
        }
        reconnectTimer = work
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: work)
    }

    /// The same capped exponential schedule as the WHOOP path (BLEManager, #414) and Android's
    /// `ReconnectBackoff`: 3, 6, 12, 24, 48, then 60 s.
    nonisolated static func reconnectDelaySeconds(attempt: Int) -> TimeInterval {
        min(60, 3 * pow(2, Double(max(0, attempt - 1))))
    }

    private func say(_ rare: Bool, _ text: String) {
        guard rare || detail() else { return }
        log("Garmin: \(text)")
    }

    // MARK: - App state

    private func observeAppState() {
        let center = NotificationCenter.default
        #if canImport(UIKit)
        let active = UIApplication.didBecomeActiveNotification
        let inactive = UIApplication.didEnterBackgroundNotification
        #elseif canImport(AppKit)
        let active = NSApplication.didBecomeActiveNotification
        let inactive = NSApplication.didResignActiveNotification
        #endif
        center.addObserver(forName: active, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.appStateChanged(foreground: true) }
        }
        center.addObserver(forName: inactive, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.appStateChanged(foreground: false) }
        }
    }

    private func appStateChanged(foreground isForeground: Bool) {
        foreground = isForeground
        if !isForeground, central.isScanning {
            central.stopScan()
            say(true, "scan stopped - the app went to the background")
        }
        if var s = session {
            let outputs = s.setForeground(isForeground, clock())
            session = s
            perform(outputs)
        }
        if isForeground { requestSync(.foreground) }
        // Backgrounding closes every live channel at once (no linger), except an active workout's HR + RR.
        applyLiveDemand()
    }

    private static func hostIdentity() -> GarminHostIdentity {
        #if os(iOS)
        let platform = "iOS"
        #else
        let platform = "macOS"
        #endif
        let build = (Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String).flatMap { UInt16($0) } ?? 1
        return GarminSessionConfig.noopHost(platform: platform, softwareVersion: build)
    }
}

// MARK: - CBCentralManagerDelegate

extension GarminSource: CBCentralManagerDelegate {

    nonisolated func centralManagerDidUpdateState(_ central: CBCentralManager) {
        MainActor.assumeIsolated {
            switch central.state {
            case .poweredOn:
                if let id = pendingConnectID ?? (session == nil ? reconnectID : nil) {
                    pendingConnectID = nil
                    connect(id)
                } else if wantsScan, reconnectID == nil {
                    scan()
                }
            case .poweredOff, .resetting, .unauthorized, .unsupported:
                say(true, "Bluetooth state \(central.state.rawValue); link down")
                endSession()
                if linkPhase != .unsupported { linkPhase = .disconnected }
            default:
                break
            }
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) {
        MainActor.assumeIsolated {
            guard let p = (dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral])?.first else { return }
            say(true, "restored by the system; resuming \(p.identifier)")
            reconnectID = p.identifier
            intentionalDisconnect = false
            peripheral = p
            p.delegate = self
            if p.state == .connected {
                self.centralManager(central, didConnect: p)
            } else {
                pendingConnectID = p.identifier
            }
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                                    advertisementData: [String: Any], rssi RSSI: NSNumber) {
        let manufacturer = (advertisementData[CBAdvertisementDataManufacturerDataKey] as? Data).map { [UInt8]($0) }
        let advertised = advertisementData[CBAdvertisementDataLocalNameKey] as? String
        MainActor.assumeIsolated {
            let name = advertised ?? peripheral.name ?? ""
            guard GarminAdvertisement.isGarmin(manufacturerData: manufacturer) || nameMatches(name) else { return }
            if !discovered.contains(where: { $0.id == peripheral.identifier }) {
                discovered.append(DiscoveredWatch(id: peripheral.identifier, name: name.isEmpty ? "Garmin watch" : name,
                                                  rssi: RSSI.intValue))
                say(true, "found \(name.isEmpty ? "a Garmin watch" : name) \(peripheral.identifier) rssi=\(RSSI.intValue)")
            }
            // The coordinator's scan fallback (no stored identifier): take the first watch found.
            if reconnectID == nil, wantsScan {
                central.stopScan()
                wantsScan = false
                reconnectID = peripheral.identifier
                attach(peripheral)
            }
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        MainActor.assumeIsolated {
            reconnectTimer?.cancel()
            linkPhase = .discovering
            telemetry.linkUp(at: Date().timeIntervalSince1970)
            say(false, "connected; discovering services")
            peripheral.delegate = self
            peripheral.discoverServices(GarminTransportSelection.servicesOfInterest.map { CBUUID(string: $0) })
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        let message = error?.localizedDescription ?? "unknown"
        MainActor.assumeIsolated {
            say(true, "connect failed: \(message)")
            linkPhase = .disconnected
            scheduleReconnect()
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral,
                                    error: Error?) {
        let message = error?.localizedDescription
        MainActor.assumeIsolated {
            let wasReady = linkPhase == .ready
            endSession()
            if linkPhase != .unsupported { linkPhase = .disconnected }
            guard !intentionalDisconnect, reconnectID == peripheral.identifier, unsupportedReason == nil else {
                say(false, "disconnected")
                return
            }
            say(true, "disconnected (\(message ?? "no error")); leaving a standing reconnect")
            if wasReady {
                // A drop from a working session: leave a standing connect, which CoreBluetooth completes
                // whenever the watch is reachable again. No scan, no timer.
                attach(peripheral)
            } else {
                scheduleReconnect()
            }
        }
    }
}

// MARK: - CBPeripheralDelegate

extension GarminSource: CBPeripheralDelegate {

    nonisolated func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        MainActor.assumeIsolated {
            let services = peripheral.services ?? []
            gattServices = []
            pendingServices = services.count
            if services.isEmpty { chooseTransport(peripheral) }
            for s in services { peripheral.discoverCharacteristics(nil, for: s) }
        }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        MainActor.assumeIsolated {
            gattServices.append(GarminGattService(uuid: service.uuid.uuidString,
                                                  characteristics: (service.characteristics ?? []).map(\.uuid.uuidString)))
            pendingServices -= 1
            if pendingServices <= 0 { chooseTransport(peripheral) }
        }
    }

    private func chooseTransport(_ peripheral: CBPeripheral) {
        guard let choice = GarminTransportSelection.select(gattServices) else {
            let found = GarminTransportSelection.describe(gattServices)
            unsupportedReason = "unsupported watch protocol"
            linkPhase = .unsupported
            say(true, "unsupported watch protocol (no V2/V1/V0 GFDI characteristics; found \(found.isEmpty ? "none" : found)); "
                + "disconnecting - Broadcast Heart Rate remains available for this watch")
            intentionalDisconnect = true
            central.cancelPeripheralConnection(peripheral)
            return
        }
        let chars = (peripheral.services ?? []).flatMap { $0.characteristics ?? [] }
        notifyChar = chars.first { GarminTransportSelection.normalize($0.uuid.uuidString) == choice.notify }
        writeChar = chars.first { GarminTransportSelection.normalize($0.uuid.uuidString) == choice.write }
        transport = choice.kind
        say(true, "protocol variant \(choice.kind.rawValue) (notify \(choice.notify.prefix(8)), write \(choice.write.prefix(8)))")
        linkPhase = .subscribing
        if let n = notifyChar { peripheral.setNotifyValue(true, for: n) }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic,
                                error: Error?) {
        let message = error?.localizedDescription
        MainActor.assumeIsolated {
            guard characteristic.uuid == notifyChar?.uuid else { return }
            if let message {
                // The GFDI channel needs an encrypted, bonded link; the OS shows its pairing prompt here.
                say(true, "subscribe failed (\(message)); the watch must be bonded (OS pairing with the watch's PIN)")
                central.cancelPeripheralConnection(peripheral)
                return
            }
            guard let kind = transport else { return }
            let mtu = peripheral.maximumWriteValueLength(for: .withoutResponse) + 3
            let config = GarminSessionConfig(host: Self.hostIdentity(), firstPairing: isFirstPairing(),
                                             sendsHostLifecycleEvents: GarminRadioPolicy.sendsHostLifecycleEvents)
            var s = GarminSession(config: config, transport: kind, mtu: mtu)
            let c = clock()
            var outputs = s.start(c)
            if !foreground { outputs += s.setForeground(false, c) }
            // Remembered by the session and registered once its handshake completes.
            outputs += s.setLiveChannels(wantedLive, c)
            session = s
            linkPhase = .handshaking
            perform(outputs)
        }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        let value = characteristic.value.map { [UInt8]($0) }
        MainActor.assumeIsolated {
            guard characteristic.uuid == notifyChar?.uuid, let value, var s = session else { return }
            telemetry.recordBytes(inbound: value.count)
            let outputs = s.receive(value, clock())
            session = s
            perform(outputs)
        }
    }

    nonisolated func peripheralIsReady(toSendWriteWithoutResponse peripheral: CBPeripheral) {
        MainActor.assumeIsolated { drainWrites() }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        guard let message = error?.localizedDescription else { return }
        MainActor.assumeIsolated { say(true, "write failed: \(message)") }
    }
}
