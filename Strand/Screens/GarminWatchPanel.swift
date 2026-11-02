import SwiftUI
import StrandDesign
import GarminProtocol

// MARK: - Garmin watch UI (experimental)
//
// The pieces of the Garmin watch UI shared by the Add Device wizard, Devices, Data Sources, Live and Test
// Centre. Every view observes the coordinator's live `GarminSource` (`AppModel.garminSource`) and shows
// only what that source has actually reported. Kotlin twin: android/.../ui/GarminWatchUi.kt.

/// Display strings for one watch's facts. Pure, so the wizard, Devices and Test Centre agree.
enum GarminWatchFacts {
    /// The watch's own name for itself, falling back through the 5024 fields it may leave empty.
    static func modelName(_ info: GarminDeviceInformation) -> String {
        for candidate in [info.bluetoothName, info.deviceModel, info.deviceName] where !candidate.isEmpty {
            return candidate
        }
        return String(localized: "Garmin watch")
    }

    /// Garmin's trademark, shown untranslated as on the watch itself.
    static let bodyBatteryName = "Body Battery"

    /// "Forerunner 165 · FW 29.05": a name and a version, nothing to translate.
    static func modelAndFirmware(_ info: GarminDeviceInformation) -> String {
        "\(modelName(info)) · FW \(firmware(info.softwareVersion))"
    }

    /// 5024 sends firmware × 100 (2905 is 29.05).
    static func firmware(_ softwareVersion: UInt16) -> String {
        String(format: "%d.%02d", Int(softwareVersion) / 100, Int(softwareVersion) % 100)
    }

    /// "V2 multi-link · GFDI 151", or "-" before the transport is chosen.
    static func protocolLine(transport: GarminTransportKind?, watch: GarminDeviceInformation?) -> String {
        guard let transport else { return "-" }
        guard let watch else { return transport.rawValue }
        return "\(transport.rawValue) · GFDI \(watch.protocolVersion)"
    }

    /// The measured per-hour watch battery cost of continuous live, or an honest "not measured yet".
    static func liveCostText(_ percentPerHour: Double? = GarminRadioPolicy.measuredLiveCostPercentPerHour) -> String {
        guard let percentPerHour else { return String(localized: "not measured yet") }
        return String(localized: "about \(percentPerHour, specifier: "%.1f")% per hour")
    }

    /// The panel's one-line status for a link phase.
    static func statusLine(phase: GarminSource.LinkPhase, syncing: Bool) -> String {
        if syncing { return String(localized: "Syncing history…") }
        switch phase {
        case .ready:        return String(localized: "Connected")
        case .unsupported:  return String(localized: "Protocol not supported")
        case .busy:         return String(localized: "Watch busy with another app")
        case .disconnected: return String(localized: "Not connected")
        case .connecting, .discovering, .subscribing, .handshaking: return String(localized: "Connecting…")
        }
    }

    /// "Sync now" is offered on a ready link with no sync running, and while another app holds the watch
    /// (there it asks the watch again).
    static func canSyncNow(phase: GarminSource.LinkPhase, syncing: Bool) -> Bool {
        (phase == .ready || phase == .busy) && !syncing
    }

    /// "3 new files, 31 already in NOOP, 34 on the watch" for the last sync, nil before any sync.
    static func summaryLine(_ summary: GarminSyncSummary?) -> String? {
        guard let summary else { return nil }
        return String(localized: "\(summary.downloaded) new files, \(summary.alreadySynced) already in NOOP, \(summary.wanted) on the watch")
    }
}

// MARK: - Garmin watch panel (Devices + Data Sources)

/// Battery, last sync, "Sync now", the continuous-live toggle with its measured cost, and the Broadcast
/// Heart Rate fallback note, for the active Garmin watch.
struct GarminWatchPanel: View {
    @ObservedObject var source: GarminSource
    @EnvironmentObject private var model: AppModel
    @AppStorage(AppModel.garminContinuousLiveKey) private var continuousLive = false

    var body: some View {
        NoopCard {
            VStack(alignment: .leading, spacing: NoopMetrics.space3) {
                HStack {
                    Text("GARMIN WATCH")
                        .font(StrandFont.overline).tracking(StrandFont.overlineTracking)
                        .foregroundStyle(StrandPalette.textSecondary)
                    Spacer()
                    StatePill(LocalizedStringKey(GarminWatchFacts.statusLine(phase: source.linkPhase, syncing: source.syncing)),
                              tone: source.linkPhase == .ready ? .positive
                                  : source.linkPhase == .unsupported ? .warning : .neutral,
                              pulsing: source.syncing)
                }
                if let watch = source.watch {
                    Text(verbatim: GarminWatchFacts.modelAndFirmware(watch))
                        .font(StrandFont.subhead)
                        .foregroundStyle(StrandPalette.textPrimary)
                }
                Text(source.batteryPct.map { String(localized: "Battery \($0)%") }
                     ?? String(localized: "Battery: not reported by this watch"))
                    .font(StrandFont.footnote)
                    .foregroundStyle(StrandPalette.textSecondary)
                // Re-render the "ago" text every 30 s.
                TimelineView(.periodic(from: .now, by: 30)) { _ in
                    Text(source.lastSyncAt.map { String(localized: "Last sync \(relativeAgo($0))") }
                         ?? String(localized: "Not synced yet"))
                        .font(StrandFont.footnote)
                        .foregroundStyle(StrandPalette.textSecondary)
                }
                if let line = GarminWatchFacts.summaryLine(source.lastSyncSummary) {
                    Text(line)
                        .font(StrandFont.footnote)
                        .foregroundStyle(StrandPalette.textTertiary)
                }
                NoopButton("Sync now", systemImage: "arrow.triangle.2.circlepath", kind: .secondary) {
                    model.garminSyncNow()
                }
                .disabled(!GarminWatchFacts.canSyncNow(phase: source.linkPhase, syncing: source.syncing))

                Divider().overlay(StrandPalette.hairline)
                Toggle(isOn: Binding(get: { continuousLive },
                                     set: { continuousLive = $0; model.setGarminContinuousLive($0) })) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Continuous live").font(StrandFont.body)
                        Text("Keeps heart rate and R-R streaming while NOOP is open, even off the Live screen. Watch battery cost: \(GarminWatchFacts.liveCostText()).")
                            .font(StrandFont.caption).foregroundStyle(StrandPalette.textTertiary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .toggleStyle(.switch)
                .tint(StrandPalette.accent)
                Text("If NOOP can't keep a connection with this watch, you can still add it with Broadcast Heart Rate from Add a device.")
                    .font(StrandFont.footnote)
                    .foregroundStyle(StrandPalette.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}

// MARK: - Live screen section

/// Body Battery, respiration, steps, floors and an on-request SpO2 from the watch's live channels. On
/// appear it reports those cards as on screen (`GarminRadioPolicy` opens their channels while the app is
/// in the foreground); on disappear it withdraws them and the 30 s linger closes them.
struct GarminLiveSection: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var live: LiveState

    static let cardChannels: Set<GarminLiveChannel> = [.bodyBattery, .respiration, .steps, .floors]

    var body: some View {
        VStack(alignment: .leading, spacing: NoopMetrics.space3) {
            Text("FROM YOUR GARMIN")
                .font(StrandFont.overline).tracking(StrandFont.overlineTracking)
                .foregroundStyle(StrandPalette.textSecondary)
            let snap = live.garminLive
            LazyVGrid(columns: [GridItem(.flexible(), spacing: NoopMetrics.gap),
                                GridItem(.flexible(), spacing: NoopMetrics.gap)],
                      spacing: NoopMetrics.gap) {
                tile(Text(verbatim: GarminWatchFacts.bodyBatteryName), snap?.bodyBattery.map(String.init))
                tile("Respiration", snap?.respirationBpm.map { String(localized: "\($0) brpm") })
                tile("Steps", snap?.steps.map { steps in
                    snap?.stepGoal.map { String(localized: "\(steps) of \($0)") } ?? "\(steps)"
                })
                tile("Floors", snap?.floorsAscended.map { String(localized: "\($0) up") })
            }
            NoopCard(padding: 14) {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(verbatim: "SpO₂").font(StrandFont.subhead).foregroundStyle(StrandPalette.textSecondary)
                        Text(snap?.spo2Pct.map { "\($0)%" } ?? "-")
                            .font(StrandFont.title2).foregroundStyle(StrandPalette.textPrimary)
                    }
                    Spacer()
                    NoopButton(model.garminSpO2Requested ? "Measuring…" : "Measure SpO₂",
                               systemImage: "drop", kind: .secondary) {
                        model.requestGarminSpO2()
                    }
                    .disabled(model.garminSpO2Requested)
                }
            }
            Text("Live from the watch while this screen is open. The watch's live channels close 30 seconds after you leave.")
                .font(StrandFont.footnote)
                .foregroundStyle(StrandPalette.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .onAppear { model.setGarminOnScreenChannels(Self.cardChannels) }
        .onDisappear {
            model.setGarminOnScreenChannels([])
            model.endGarminSpO2Request()
        }
    }

    private func tile(_ title: LocalizedStringKey, _ value: String?) -> some View {
        tile(Text(title), value)
    }

    private func tile(_ title: Text, _ value: String?) -> some View {
        NoopCard(padding: 14) {
            VStack(alignment: .leading, spacing: 2) {
                title.font(StrandFont.subhead).foregroundStyle(StrandPalette.textSecondary)
                Text(value ?? "-").font(StrandFont.title2).foregroundStyle(StrandPalette.textPrimary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}
