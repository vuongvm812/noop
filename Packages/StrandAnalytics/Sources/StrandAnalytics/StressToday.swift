import Foundation

/// Today's stress reading, resolved ONCE for every surface that shows it (#42).
///
/// The Today card and the Stress screen both draw this value, so both call this resolver and nothing else
/// decides the source, the latest level or the average. Two sources can answer:
///
/// - `garmin`: the watch's own 0-100 stress score. Intraday samples come from the synced monitoring
///   files; the daily average is the watch day (`garmin_stress`) or, failing that, the day an imported
///   Garmin export carries (`stress` under `garmin-import`, #31).
/// - `noop`: NOOP's own 0-3 autonomic-load proxy, scored from the day's heart rate and R-R by
///   `DaytimeStress`.
///
/// The active device's brand picks which source is asked first; the other is the fallback, so a
/// Garmin-only user and a WHOOP-only user each get the one they have. The scales are never mixed:
/// a reading carries its own `scaleMax`. Pure, so the Kotlin twin (`StressToday`) is pinned by the same
/// cases.
public enum StressToday {

    public enum Source: String, Equatable, Sendable {
        case garmin
        case noop
    }

    /// One timestamped level on the reading's own scale.
    public struct Point: Equatable, Sendable {
        public let ts: Int
        public let level: Double
        public init(ts: Int, level: Double) {
            self.ts = ts
            self.level = level
        }
    }

    public struct Reading: Equatable, Sendable {
        public let source: Source
        /// The most recent level, or nil when only a daily average is known (an imported day).
        public let latest: Point?
        /// Today's average, or nil when nothing scored an average yet.
        public let average: Double?
        /// The intraday series for the sparkline, oldest first. Empty when no samples exist.
        public let points: [Point]
        /// 100 for Garmin, 3 for NOOP.
        public var scaleMax: Double { source == .garmin ? 100 : 3 }
    }

    /// Garmin's highest stress value. Anything outside 0...100 is a watch sentinel and is ignored.
    public static let garminMax: Double = 100
    /// NOOP's highest stress value (the 0-3 proxy).
    public static let noopMax: Double = 3

    /// Resolve today's reading, or nil when neither source has anything for the day.
    ///
    /// - Parameters:
    ///   - activeDeviceId: the registry's active device id. A Garmin device asks Garmin first.
    ///   - garminSamples: today's watch stress samples, any order.
    ///   - garminWatchDay: today's stored watch average (`garmin_stress`), if any.
    ///   - garminImportDay: today's average from an imported Garmin export (`stress`), if any.
    ///   - noopTimeline: today's scored NOOP points (`DaytimeStress.Result.timeline` with a level).
    ///   - noopDayMean: today's NOOP average (`DaytimeStress.Result.dayMean`).
    /// Kotlin twin: `StressToday.resolve`.
    public static func resolve(activeDeviceId: String,
                               garminSamples: [Point],
                               garminWatchDay: Double?,
                               garminImportDay: Double?,
                               noopTimeline: [Point],
                               noopDayMean: Double?) -> Reading? {
        let garmin = garminReading(samples: garminSamples, watchDay: garminWatchDay, importDay: garminImportDay)
        let noop = noopReading(timeline: noopTimeline, dayMean: noopDayMean)
        return prefersGarmin(activeDeviceId: activeDeviceId) ? (garmin ?? noop) : (noop ?? garmin)
    }

    /// True when the active device is a Garmin, so its own stress answers before NOOP's. A loader uses it
    /// to skip scoring NOOP's day when the watch already answered. Kotlin twin: `StressToday.prefersGarmin`.
    public static func prefersGarmin(activeDeviceId: String) -> Bool {
        Baselines.brandBucket(activeDeviceId) == "garmin"
    }

    /// The Garmin half. The average is the mean of the samples drawn, so the number and the sparkline
    /// can never disagree; only a day with no samples falls back to a stored daily value, the watch day
    /// before the imported one. Kotlin twin: `StressToday.garminReading`.
    static func garminReading(samples: [Point], watchDay: Double?, importDay: Double?) -> Reading? {
        let points = samples
            .filter { $0.level.isFinite && $0.level >= 0 && $0.level <= garminMax }
            .sorted { $0.ts < $1.ts }
        let stored = [watchDay, importDay].compactMap { $0 }
            .first { $0.isFinite && $0 >= 0 && $0 <= garminMax }
        let average = points.isEmpty ? stored : points.map(\.level).reduce(0, +) / Double(points.count)
        guard let average else { return nil }
        return Reading(source: .garmin, latest: points.last, average: average, points: points)
    }

    /// The NOOP half: the scored timeline, its last point and the day mean `DaytimeStress` computed.
    /// Kotlin twin: `StressToday.noopReading`.
    static func noopReading(timeline: [Point], dayMean: Double?) -> Reading? {
        let points = timeline
            .filter { $0.level.isFinite && $0.level >= 0 && $0.level <= noopMax }
            .sorted { $0.ts < $1.ts }
        let average = dayMean.flatMap { $0.isFinite ? $0 : nil }
        guard !points.isEmpty || average != nil else { return nil }
        return Reading(source: .noop, latest: points.last, average: average, points: points)
    }
}
