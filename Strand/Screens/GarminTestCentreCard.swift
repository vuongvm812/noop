import SwiftUI
import StrandDesign
import GarminProtocol

// MARK: - Test Centre: Garmin section (only when a Garmin watch is paired over GFDI)
//
// The current connect's facts, the radio counters, the realtime capture toggle for channels 8 / 10 / 13
// and the sync-file list: what the watch listed at the last sync against what NOOP has ingested. Values
// are shown as the source reported them; nothing here writes to the watch. Kotlin twin:
// TestCentreScreen.kt `GarminTestCentreCard`.

struct GarminTestCentreCard: View {
    @EnvironmentObject private var model: AppModel
    @AppStorage(AppModel.garminRealtimeCaptureKey) private var garminRealtimeCapture = false

    var body: some View {
        NoopCard {
            VStack(alignment: .leading, spacing: NoopMetrics.space3) {
                Text("GARMIN")
                    .font(StrandFont.overline).tracking(StrandFont.overlineTracking)
                    .foregroundStyle(StrandPalette.textSecondary)

                if let source = model.garminSource {
                    GarminTestCentreReadouts(source: source, deviceId: model.deviceRegistry?.activeDeviceId)
                } else {
                    Text("The Garmin watch is not the active device, so there is no live session to show.")
                        .font(StrandFont.caption).foregroundStyle(StrandPalette.textTertiary)
                        .fixedSize(horizontal: false, vertical: true)
                }

                Divider().overlay(StrandPalette.hairline)
                // Garmin 6/10: capture mode for channels 8 / 10 / 13. The readings are candidates only.
                Toggle(isOn: $garminRealtimeCapture) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Garmin realtime capture").font(StrandFont.body)
                        Text("While NOOP is open, listens to the watch's live calories, intensity-minutes and stress channels and writes every message to the strap log with an unverified reading. Nothing else uses these values. Uses extra watch battery while on.")
                            .font(StrandFont.caption).foregroundStyle(StrandPalette.textTertiary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .tint(StrandPalette.accent)

                Text("Per-message detail goes to the strap log while the Connection or Sources test mode is on.")
                    .font(StrandFont.footnote).foregroundStyle(StrandPalette.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}

private struct GarminTestCentreReadouts: View {
    @ObservedObject var source: GarminSource
    let deviceId: String?
    @EnvironmentObject private var model: AppModel
    @State private var ingested: Set<GarminSyncFileKey> = []

    /// The sync-file list shows at most this many of the watch's files, newest first.
    static let maxListedFiles = 40

    var body: some View {
        VStack(alignment: .leading, spacing: NoopMetrics.space2) {
            Text("Connection").font(StrandFont.headline).foregroundStyle(StrandPalette.textPrimary)
            row("Link", source.linkPhase.rawValue)
            if let watch = source.watch {
                row("Watch", "\(GarminWatchFacts.modelName(watch)) · product \(watch.productNumber) · unit \(watch.unitNumber)")
                row("Firmware", GarminWatchFacts.firmware(watch.softwareVersion))
                row("Protocol", "\(GarminWatchFacts.protocolLine(transport: source.transport, watch: watch)) · max packet \(watch.maxPacketSize)")
            } else {
                row("Protocol", GarminWatchFacts.protocolLine(transport: source.transport, watch: nil))
            }
            row("MTU", source.connectReadout.mtu.map(String.init) ?? "-")
            row("Handshake", source.connectReadout.handshakeMs.map { "\($0) ms" } ?? "-")
            row("Sessions", "\(source.connectReadout.sessions)")
            row("Last failure", source.connectReadout.lastFailure ?? String(localized: "none"))
            if let reason = source.unsupportedReason { row("Unsupported", reason) }

            Text("Radio counters").font(StrandFont.headline).foregroundStyle(StrandPalette.textPrimary)
                .padding(.top, NoopMetrics.space2)
            TimelineView(.periodic(from: .now, by: 1)) { _ in
                Text(source.telemetrySummary())
                    .font(StrandFont.caption.monospaced())
                    .foregroundStyle(StrandPalette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .textSelection(.enabled)
            }
            row("Open channels", source.openLiveChannels.isEmpty ? String(localized: "none")
                : source.openLiveChannels.sorted().map { String($0.rawValue) }.joined(separator: ", "))

            Text("Sync files").font(StrandFont.headline).foregroundStyle(StrandPalette.textPrimary)
                .padding(.top, NoopMetrics.space2)
            if let summary = source.lastSyncSummary {
                let onWatch = summary.onWatch
                Text("On the watch: \(onWatch.count) FIT files. In NOOP: \(onWatch.filter { ingested.contains($0) }.count).")
                    .font(StrandFont.caption).foregroundStyle(StrandPalette.textSecondary)
                Text(summary.line)
                    .font(StrandFont.caption.monospaced()).foregroundStyle(StrandPalette.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
                ForEach(Array(onWatch.reversed().prefix(Self.maxListedFiles)), id: \.self) { key in
                    HStack {
                        Text(key.label).font(StrandFont.caption.monospaced())
                            .foregroundStyle(StrandPalette.textSecondary)
                        Spacer()
                        Text(ingested.contains(key) ? "ingested" : "not yet")
                            .font(StrandFont.caption)
                            .foregroundStyle(ingested.contains(key) ? StrandPalette.statusPositive : StrandPalette.textTertiary)
                    }
                }
            } else {
                Text("No sync has finished in this session yet.")
                    .font(StrandFont.caption).foregroundStyle(StrandPalette.textTertiary)
            }
            Text("Ingested in NOOP: \(ingested.count) files")
                .font(StrandFont.caption).foregroundStyle(StrandPalette.textSecondary)
        }
        // Reload the ledger whenever a sync finishes (and on first show).
        .task(id: source.lastSyncSummary?.line) { await loadLedger() }
    }

    private func loadLedger() async {
        guard let deviceId, let store = await model.repo.storeHandle() else { return }
        ingested = await GarminHistoryIngest.syncedKeys(store: store, deviceId: deviceId)
    }

    private func row(_ label: LocalizedStringKey, _ value: String) -> some View {
        row(Text(label), value)
    }

    private func row(_ label: Text, _ value: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: NoopMetrics.space2) {
            label.font(StrandFont.caption).foregroundStyle(StrandPalette.textTertiary)
                .frame(width: 110, alignment: .leading)
            Text(value).font(StrandFont.caption).foregroundStyle(StrandPalette.textPrimary)
                .fixedSize(horizontal: false, vertical: true)
                .textSelection(.enabled)
            Spacer(minLength: 0)
        }
    }
}
