import Foundation
import GarminProtocol
import StrandAnalytics
import WhoopProtocol

/// Feeds an EXPERIMENTAL Garmin watch's live channels into NOOP's shared live state and store, through the
/// same funnels every other source uses, so nothing downstream needs to know the beats came from a watch:
///   - heart rate -> `live.heartRate` + the silence timer, and an `hrSample` row per reading;
///   - beat intervals -> `live.setRRIntervals` (the R-R intake the live HRV, strain and stress math reads)
///     and `rrInterval` rows, unfiltered: the existing artifact filtering runs downstream as for any strap;
///   - Body Battery / respiration / SpO2 / steps / floors -> `live.garminLive`, plus at most one
///     `deviceMetricSample` row per kind per minute for the first three (`GarminLiveIntake`).
/// Rows are buffered and flushed in batches like `StandardHRSource`. Kotlin twin: `com.noop.ble.GarminLiveFeed`.
@MainActor
final class GarminLiveFeed {

    private let live: LiveState
    private let persist: (Streams) -> Void
    private let log: (String) -> Void
    /// The RR-quality readout is per-session detail: logged only while this returns true.
    private let detail: () -> Bool
    private let now: () -> Int

    private var intake = GarminLiveIntake()
    private var hr: [HRSample] = []
    private var rr: [RRInterval] = []
    private var metrics: [DeviceMetricSample] = []
    private var lastFlush: Int
    /// Beats received since the R-R channel opened, for the rejected-share readout.
    private var sessionBeats: [Double] = []
    private var loggedFirstBeat = false
    private var loggedFirstHR = false

    static let flushCount = 30
    static let flushIntervalSeconds = 30
    static let qualityWindowCap = 4_000

    init(live: LiveState, persist: @escaping (Streams) -> Void, log: @escaping (String) -> Void,
         detail: @escaping () -> Bool = { false }, now: @escaping () -> Int = { Int(Date().timeIntervalSince1970) }) {
        self.live = live
        self.persist = persist
        self.log = log
        self.detail = detail
        self.now = now
        self.lastFlush = now()
    }

    func handle(_ event: GarminLiveEvent) {
        let ts = now()
        switch event {
        case let .reading(reading):
            apply(intake.ingest(reading, unixSeconds: ts), ts: ts)
        case let .channel(service, open):
            if open {
                if service == .realtimeHrv { sessionBeats = []; loggedFirstBeat = false }
                if service == .realtimeHeartRate { loggedFirstHR = false }
                return
            }
            if service == .realtimeHrv { logQuality(reason: "channel closed") }
            apply(intake.channelClosed(service), ts: ts)
            flush()
        case .linkDown:
            if !sessionBeats.isEmpty { logQuality(reason: "link down") }
            apply(intake.linkDown(), ts: ts)
            live.garminLive = nil
            live.connected = false
            flush()
        }
    }

    private func apply(_ effect: GarminLiveIntakeEffect, ts: Int) {
        if let bpm = effect.heartRate {
            live.heartRate = bpm
            live.noteReadableHeartRate()
            live.connected = true
            live.streamingLiveHR = true
            hr.append(HRSample(ts: ts, bpm: bpm))
            if !loggedFirstHR {
                loggedFirstHR = true
                log("Garmin: receiving live heart rate - first sample \(bpm) bpm")
            }
        }
        if !effect.rr.isEmpty {
            live.setRRIntervals(effect.rr)
            rr += effect.rr.map { RRInterval(ts: ts, rrMs: $0) }
            sessionBeats += effect.rr.map(Double.init)
            if sessionBeats.count > Self.qualityWindowCap { sessionBeats.removeFirst(sessionBeats.count - Self.qualityWindowCap) }
            if !loggedFirstBeat {
                loggedFirstBeat = true
                log("Garmin: receiving beat intervals - first \(effect.rr[0]) ms")
            }
        }
        for w in effect.writes {
            guard let kind = DeviceMetricKind(rawValue: w.kind) else { continue }
            metrics.append(DeviceMetricSample(ts: w.ts, kind: kind, value: w.value, source: GarminLiveIntake.metricSource))
        }
        if effect.clearHeartRate {
            live.clearLiveHeartRate()
            live.streamingLiveHR = false
        }
        if effect.snapshotChanged { live.garminLive = intake.snapshot }
        if hr.count + rr.count + metrics.count >= Self.flushCount || ts - lastFlush >= Self.flushIntervalSeconds { flush() }
    }

    private func flush() {
        lastFlush = now()
        guard !hr.isEmpty || !rr.isEmpty || !metrics.isEmpty else { return }
        persist(Streams(hr: hr, rr: rr, deviceMetric: metrics))
        hr.removeAll()
        rr.removeAll()
        metrics.removeAll()
    }

    /// The share of beats NOOP's own artifact filter (`HRVAnalyzer.cleanRR`: range filter, then ectopic
    /// rejection) drops over this channel session. Diagnostic only; scoring runs its own filtering.
    private func logQuality(reason: String) {
        defer { sessionBeats = [] }
        guard detail(), !sessionBeats.isEmpty else { return }
        log("Garmin: " + Self.qualityLine(sessionBeats, reason: reason))
    }

    nonisolated static func qualityLine(_ beats: [Double], reason: String) -> String {
        let kept = HRVAnalyzer.cleanRR(beats).count
        let rejected = beats.count - kept
        let pct = beats.isEmpty ? 0 : Double(rejected) * 100 / Double(beats.count)
        return "rr quality (\(reason)): beats=\(beats.count) kept=\(kept) rejected=\(rejected) "
            + "(\(String(format: "%.1f", pct))%)"
    }
}
