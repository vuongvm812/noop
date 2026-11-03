import Foundation
import StrandAnalytics
import WhoopProtocol
import WhoopStore

/// Reads today's stress inputs and hands them to `StressToday.resolve` (#42).
///
/// The Today stress card and the Stress screen's "Today" summary both call `load`, so they resolve the
/// same source, latest level and average from the same reads. NOOP's half comes from `StressDayCurve`,
/// the gated, memoised scorer the hosted stress card and the iOS widget already use, so opening the
/// Stress screen after Today costs one fingerprint query rather than a second day of scoring.
/// Kotlin twin: `StressTodayLoader`.
enum StressTodayLoader {

    @MainActor
    static func load(repo: Repository, now: Date = Date(),
                     calendar: Calendar = .current) async -> StressToday.Reading? {
        let active = repo.deviceId
        let startOfDay = calendar.startOfDay(for: now)
        let from = Int(startOfDay.timeIntervalSince1970)
        let to = Int(now.timeIntervalSince1970)
        let day = Repository.dayString(now)

        var samples: [StressToday.Point] = []
        var watchDay: Double?
        var importDay: Double?
        if let store = await repo.storeHandle() {
            // The ACTIVE device's own watch samples. A WHOOP id simply has none, so no brand check here.
            samples = ((try? await store.deviceMetricSamples(deviceId: active, kind: .stress, from: from, to: to))
                ?? []).map { StressToday.Point(ts: $0.ts, level: $0.value) }
            watchDay = (try? await store.metricSeries(deviceId: active, key: WhoopStore.garminStressKey,
                                                      from: day, to: day))?.last?.value
            importDay = (try? await store.metricSeries(deviceId: "garmin-import", key: "stress",
                                                       from: day, to: day))?.last?.value
        }

        let garminOnly = StressToday.resolve(activeDeviceId: active, garminSamples: samples,
                                             garminWatchDay: watchDay, garminImportDay: importDay,
                                             noopTimeline: [], noopDayMean: nil)
        // The watch answered for a Garmin wearer: NOOP's own score would lose anyway, so do not pay for it.
        if garminOnly != nil, StressToday.prefersGarmin(activeDeviceId: active) { return garminOnly }

        let noop = await StressDayCurve.today(
            repo: repo, now: now, calendar: calendar,
            personalBaseline: PuffinExperiment.stressPersonalBaselineEnabled
        )?.result
        let timeline = (noop?.timeline ?? []).compactMap { point in
            point.level.map { StressToday.Point(ts: point.startTs, level: $0) }
        }
        return StressToday.resolve(activeDeviceId: active, garminSamples: samples,
                                   garminWatchDay: watchDay, garminImportDay: importDay,
                                   noopTimeline: timeline, noopDayMean: noop?.dayMean)
    }
}
