import Foundation
import WhoopProtocol
import WhoopStore

// MARK: - Garmin watch file → NOOP streams
//
// History sync (the GarminProtocol `GarminHistorySync` engine) downloads the watch's own FIT files; this
// fold turns ONE decoded wellness file (`GarminWellnessParser`) into what the store keeps, so the
// existing screens read a Garmin like any other source:
//   heart rate        MONITORING heart_rate (55/27)            → hrSample
//   stress, Body Battery, SpO2 %, respiration rate, HRV values, the watch's sleep score, intensity
//                     minutes                                  → deviceMetricSample (explicit units)
//   sleep             SLEEP_LEVEL nights (275)                 → sleepSession with a stage timeline
//   day totals        steps / distance / calories (55 counters, kept per activity type), resting HR (211), last-night HRV
//                     average (370), sleep minutes and score    → dailyMetric + metricSeries, merged
// Not mapped, on purpose: beat intervals (290) and skin temperature (397/398) are CANDIDATE decodes whose
// scale is unconfirmed (GarminWellness.swift); feeding them into rrInterval / skin temperature would give
// HRV and illness screens numbers nobody has checked. The arbitration between a Garmin and another source
// for the same day is the analytics layer's job (Garmin child 9), not this fold's.
//
// The fold is pure and platform-neutral. Kotlin twin: android/app/src/main/java/com/noop/ingest/GarminWatchIngest.kt,
// held byte-identical by the oracle in GarminWatchIngestTests.

public enum GarminWatchIngest {

    /// `deviceMetricSample.source` tags, naming the FIT message each value came from.
    static let sourceStress = "fit227"
    static let sourceSpo2 = "fit269"
    static let sourceRespiration = "fit297"
    static let sourceHrv = "fit371"
    static let sourceSleepScore = "fit346"
    static let sourceIntensity = "fit55"
    static let sourceSession = "fit18"

    /// Heart rates outside this range are sensor sentinels (0, 255), not beats.
    static let hrRange = 25...240

    /// Build the ingest for one decoded file. `fallbackUtcOffsetS` (the phone's current offset) is used
    /// only when the file carries no local timestamp of its own. Kotlin twin: `GarminWatchIngest.fold`.
    public static func fold(_ s: GarminWellnessSamples, file: GarminSyncedFileRow,
                            fallbackUtcOffsetS: Int) -> GarminFileIngest {
        let offset = s.utcOffsetS ?? fallbackUtcOffsetS
        var out = GarminFileIngest(file: file)

        // Heart rate: one sample per second, the last record of a second wins.
        var hrBySecond: [Int64: Int] = [:]
        for m in s.monitoring {
            if let bpm = m.heartRate, hrRange.contains(bpm) { hrBySecond[m.ts] = bpm }
        }
        out.hr = hrBySecond.keys.sorted().map { HRSample(ts: Int($0), bpm: hrBySecond[$0]!) }

        // Watch-computed series. Keyed (kind, second); a later duplicate in the same file wins.
        var metrics: [String: DeviceMetricSample] = [:]
        // Kotlin twin: `GarminWatchIngest.add`.
        func add(_ ts: Int64, _ kind: DeviceMetricKind, _ value: Double, _ source: String) {
            guard value.isFinite else { return }
            metrics["\(kind.rawValue)|\(ts)"] = DeviceMetricSample(ts: Int(ts), kind: kind, value: value, source: source)
        }
        for v in s.stress { add(v.ts, .stress, Double(v.value), sourceStress) }
        for v in s.bodyBattery { add(v.ts, .bodyBattery, Double(v.value), sourceStress) }
        for v in s.spo2 { add(v.ts, .spo2Pct, Double(v.percent), sourceSpo2) }
        for v in s.respiration where v.value > 0 { add(v.ts, .respirationBpm, v.value, sourceRespiration) }
        for v in s.hrvValues where v.value > 0 { add(v.ts, .hrvMs, v.value, sourceHrv) }
        for a in s.sleepAssessments { add(a.ts, .sleepScore, Double(a.overallScore), sourceSleepScore) }
        for m in s.monitoring {
            if let v = m.moderateMin, v > 0 { add(m.ts, .intensityModerateMin, Double(v), sourceIntensity) }
            if let v = m.vigorousMin, v > 0 { add(m.ts, .intensityVigorousMin, Double(v), sourceIntensity) }
        }
        out.deviceMetric = metrics.values.sorted { ($0.ts, $0.kind.rawValue) < ($1.ts, $1.kind.rawValue) }

        // Day rollups, keyed by the file's local day.
        var days: [String: GarminDayRollup] = [:]
        // Kotlin twin: `GarminWatchIngest.day`.
        func day(_ key: String) -> GarminDayRollup {
            if let d = days[key] { return d }
            let start = dayStartUnix(key, utcOffsetS: offset)
            return GarminDayRollup(day: key, dayStartTs: start, dayEndTs: start + 86_400)
        }
        // Kotlin twin: `GarminWatchIngest.dayOf`.
        func dayOf(_ ts: Int64) -> String { GarminWellnessParser.localDay(ts, utcOffsetS: offset) }

        for a in GarminWellnessParser.dailyTotals(s.monitoring, utcOffsetS: offset) {
            var d = day(a.day)
            d.steps = a.steps
            d.distanceM = a.distanceM
            d.activeKcal = a.activeCalories.map(Double.init)
            d.totalKcal = a.calories.map(Double.init)
            days[a.day] = d
        }
        // The per-type peaks behind those totals: the store merges them per type across files.
        for p in GarminWellnessParser.dailyTypePeaks(s.monitoring, utcOffsetS: offset) {
            var d = day(p.day)
            let steps = GarminWellnessParser.stepActivityTypes.contains(p.activityType) ? p.cycles : nil
            d.activityPeaks.append(GarminActivityPeak(activityType: p.activityType, steps: steps,
                                                      distanceM: p.distanceM, totalKcal: p.calories.map(Double.init),
                                                      activeKcal: p.activeCalories.map(Double.init)))
            days[p.day] = d
        }
        // A resting HR stamped exactly at local midnight describes the day that just ended: the new day has
        // no data yet to rest on. Same rule as the 00:00:00 MONITORING totals (`dailyTotals`).
        for v in s.restingHeartRate.sorted(by: { $0.ts < $1.ts }) {
            let key = dayOf((v.ts + Int64(offset)) % 86_400 == 0 ? v.ts - 1 : v.ts)
            var d = day(key); d.restingHr = v.value; days[key] = d
        }
        for h in s.hrvSummaries.sorted(by: { $0.ts < $1.ts }) {
            guard let avg = h.lastNightAverageMs, avg > 0 else { continue }
            let key = dayOf(h.ts)
            var d = day(key); d.avgHrvMs = avg; days[key] = d
        }
        for a in s.sleepAssessments.sorted(by: { $0.ts < $1.ts }) {
            let key = dayOf(a.ts)
            var d = day(key); d.sleepScore = a.overallScore; days[key] = d
        }
        for v in s.vo2max.sorted(by: { $0.ts < $1.ts }) {
            let key = dayOf(v.ts)
            var d = day(key); d.vo2max = v.value; days[key] = d
        }
        // Every day a sample landed in gets a rollup, so the store re-derives that day's aggregates.
        for m in out.deviceMetric {
            let key = dayOf(Int64(m.ts))
            days[key] = day(key)
        }

        // Nights. A night belongs to the day it ends on (the wake day), as every NOOP sleep reader keys it.
        // Every night is stored as a session; the day's sleep totals come from its MAIN night only, the one
        // with the most time asleep (the earlier one on a tie), so a nap or a fallback fragment ending the
        // same day never replaces the overnight figures (#49). The store applies the same rule across files.
        var mainNight: [String: GarminWellnessSamples.SleepSession] = [:]
        for night in s.sleepSessions where night.end > night.start {
            let asleep = night.lightS + night.deepS + night.remS
            guard asleep > 0 else { continue }
            let key = dayOf(night.end)
            let bestAsleep = mainNight[key].map { $0.lightS + $0.deepS + $0.remS } ?? 0
            if asleep > bestAsleep { mainNight[key] = night }
            out.sleeps.append(CachedSleepSession(
                startTs: Int(night.start), endTs: Int(night.end),
                efficiency: efficiency(asleep: asleep, awake: night.awakeS),
                restingHr: nil, avgHrv: day(key).avgHrvMs,
                stagesJSON: stagesJSON(s.sleepLevels, from: night.start, to: night.end)))
        }
        for (key, night) in mainNight {
            let asleep = night.lightS + night.deepS + night.remS
            var d = day(key)
            d.totalSleepMin = Double(asleep) / 60
            d.deepMin = Double(night.deepS) / 60
            d.lightMin = Double(night.lightS) / 60
            d.remMin = Double(night.remS) / 60
            d.awakeMin = Double(night.awakeS) / 60
            d.efficiency = efficiency(asleep: asleep, awake: night.awakeS)
            days[key] = d
        }
        out.days = days.keys.sorted().map { days[$0]! }
        // A night that began before midnight ends on the next day; re-derive that day's overnight vitals too.
        out.nightRefreshDays = out.days.compactMap { d in
            let next = dayOf(Int64(d.dayEndTs))
            return days[next] == nil ? day(next) : nil
        }
        return out
    }

    /// The watch's own reference values for one decoded activity (Training Effect and training load from the
    /// FIT session), as `deviceMetricSample` rows stamped at the workout start. The workout detail reads
    /// them back over the workout window; nothing scores from them. Empty for a file that carries none.
    /// Kotlin twin: `GarminWatchIngest.activityReference`.
    public static func activityReference(_ a: ActivityFile) -> [DeviceMetricSample] {
        let ts = Int(a.start.timeIntervalSince1970)
        let values: [(DeviceMetricKind, Double?)] = [
            (.aerobicTrainingEffect, a.aerobicTrainingEffect),
            (.anaerobicTrainingEffect, a.anaerobicTrainingEffect),
            (.trainingLoad, a.trainingLoad),
        ]
        return values.compactMap { kind, value in
            guard let value, value.isFinite else { return nil }
            return DeviceMetricSample(ts: ts, kind: kind, value: value, source: sourceSession)
        }
    }

    /// Asleep share of the time in bed (asleep + awake) as a 0-1 FRACTION, the unit every other writer of
    /// `sleepSession.efficiency` / `dailyMetric.efficiency` uses (WHOOP import, Oura, on-device staging).
    /// Unmeasurable time (level 0) is in neither term. Storing the percent read as 100% efficient
    /// everywhere the value is clamped to [0, 1] (#49). Kotlin twin: `GarminWatchIngest.efficiency`.
    /// Derived from `efficiencyPct` (one decimal of percent is three of fraction) by re-rounding the
    /// tenths, not by dividing: 33.3 / 100 is 0.33299999999999996, while 333 / 1000 is the shortest 0.333.
    static func efficiency(asleep: Int64, awake: Int64) -> Double? {
        efficiencyPct(asleep: asleep, awake: awake).map { ($0 * 10).rounded() / 1000 }
    }

    /// Asleep share of the time in bed (asleep + awake), percent rounded to one decimal, the unit the
    /// watch shows. Not a stored unit: storage goes through `efficiency`. Kotlin twin: `GarminWatchIngest.efficiencyPct`.
    static func efficiencyPct(asleep: Int64, awake: Int64) -> Double? {
        let inBed = asleep + awake
        guard inBed > 0 else { return nil }
        return (Double(asleep) * 1000 / Double(inBed)).rounded() / 10
    }

    /// The night's stage timeline in the `sleepSession.stagesJSON` vocabulary, written by hand with a
    /// fixed key order so both platforms store the same text. Each SLEEP_LEVEL row after `start` closes the
    /// span since the previous boundary, the first one the span from `start` (the watch's sleep onset, the
    /// same rule as `GarminWellnessParser.sleepSessions`); unmeasurable spans (level 0) are left out,
    /// consecutive equal stages are joined. Kotlin twin: `GarminWatchIngest.stagesJSON`.
    static func stagesJSON(_ levels: [GarminWellnessSamples.TimedInt], from start: Int64, to end: Int64) -> String? {
        let names = [1: "wake", 2: "light", 3: "deep", 4: "rem"]
        var segments: [(Int64, Int64, String)] = []
        var prev = start
        for row in levels where row.ts > prev && row.ts <= end {
            defer { prev = row.ts }
            guard let name = names[row.value] else { continue }
            if let last = segments.last, last.2 == name, last.1 == prev {
                segments[segments.count - 1].1 = row.ts
            } else {
                segments.append((prev, row.ts, name))
            }
        }
        guard !segments.isEmpty else { return nil }
        return "[" + segments.map { "{\"start\":\($0.0),\"end\":\($0.1),\"stage\":\"\($0.2)\"}" }.joined(separator: ",") + "]"
    }

    /// Unix seconds of local midnight starting `day` ("yyyy-MM-dd") at a fixed UTC offset.
    /// Kotlin twin: `GarminWatchIngest.dayStartUnix`.
    public static func dayStartUnix(_ day: String, utcOffsetS: Int) -> Int {
        let parts = day.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3 else { return 0 }
        let (y0, m, d) = (parts[0], parts[1], parts[2])
        let y = m <= 2 ? y0 - 1 : y0
        let era = (y >= 0 ? y : y - 399) / 400
        let yoe = y - era * 400
        let doy = (153 * (m > 2 ? m - 3 : m + 9) + 2) / 5 + d - 1
        let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        let days = era * 146_097 + doe - 719_468
        return days * 86_400 - utcOffsetS
    }
}
