import Foundation

// MARK: - Garmin wellness FIT decoding
//
// A Garmin watch records its own 24/7 data as FIT files (monitoring, sleep, HRV status, metrics,
// skin temperature). The same files sit inside a Garmin Connect GDPR export. This decoder turns
// their health messages into `GarminWellnessSamples`, a pure model with no store dependency; the
// history-sync layer persists it.
//
// Message and field numbers, scales and units are protocol facts re-derived (no code, literals or
// fixtures copied) from Gadgetbridge's FIT profile and importer (AGPLv3,
// codeberg.org/Freeyourgadget/Gadgetbridge at edbea98c), per the facts-vs-code rule in
// docs/CONTRIBUTING.md:
//
//   55  MONITORING          1 calories (kcal, cumulative), 2 distance (/100 m, cumulative),
//                           3 cycles (cumulative; the raw value is the step count for walking/running:
//                           the profile scale is /2 cycles and one cycle is two steps), 4 active_time
//                           (/1000 s), 5 activity_type, 11 local_timestamp, 19 active_calories
//                           (kcal, cumulative), 24 type+intensity (bits 0-4 type, 5-7 intensity),
//                           26 timestamp_16, 27 heart_rate, 33/34 moderate/vigorous minutes
//   103 MONITORING_INFO     0 local_timestamp, 5 resting_metabolic_rate (kcal/day)
//   211 MONITORING_HR_DATA  0 resting_heart_rate, 1 current_day_resting_heart_rate (preferred)
//   227 STRESS_LEVEL        0 stress_level_value (sint16, negative = no reading), 1 stress_level_time,
//                           3 body_energy (Body Battery, sint8)
//   269 SPO2_DATA           0 reading_spo2 (%), 1 reading_confidence, 2 mode (1 manual, 3 automatic)
//   273 SLEEP_DATA_INFO     1 sample_length (s), 2 local_timestamp
//   274 SLEEP_DATA_RAW      0 bytes: one row of ten little-endian IEEE half floats per sample slot;
//                           index 8 movement, index 9 heart rate. Rows carry no timestamp: each takes
//                           the next slot after the info message, as do awake/unmeasurable level rows.
//   275 SLEEP_LEVEL         0 sleep_level (0 unmeasurable, 1 awake, 2 light, 3 deep, 4 REM); each row
//                           closes the span it covers
//   297 RESPIRATION_RATE    0 respiration_rate (sint16, /100 breaths/min)
//   346 SLEEP_ASSESSMENT    0-5, 7-10, 14 sub-scores, 6 overall score, 11 awakenings count,
//                           15 average stress during sleep (/100)
//   370 HRV_STATUS_SUMMARY  0 weekly avg, 1 last-night avg, 2 last-night 5-min high, 3-5 baseline
//                           bounds (all u16 /128 ms), 6 status (0 none, 1 poor, 2 low, 3 unbalanced,
//                           4 balanced)
//   371 HRV_VALUE           0 value (u16 /128 ms)
//
// CANDIDATES. Gadgetbridge declares but does not import these, so their scales and units are
// unconfirmed. They are decoded raw into `*Candidate` types and must not back a shipped metric until
// they are checked against the watch or Garmin Connect:
//   290 BEAT_INTERVALS      0 timestamp_ms (u16), 1 time (u16 array, ms)
//   397 SKIN_TEMP_RAW       1 deviation (float32, presumably °C)
//   398 SKIN_TEMP_OVERNIGHT 0 local_timestamp, 1 average deviation, 2 7-day average deviation,
//                           4 nightly value (float32), 3 calibrated days (u8)
//
// Timestamps: only field 253 and compressed-timestamp headers move the file's running timestamp. A
// message without its own 253 takes the running one. MONITORING may instead carry `timestamp_16`, the
// low 16 bits of its time, resolved forward from the previous monitoring time (FIT SDK rule), which is
// what carries it across a 0xFFFF rollover.
//
// Cumulative counters: MONITORING calories, active calories, distance and cycles are running totals
// per activity type that reset at local midnight. A day's total is the sum, over activity types, of
// each type's latest value that day (`dailyActivity`), never the sum of every record.
//
// SECURITY: all reads go through `FitRecordStream`, which bounds-checks every access and stops at the
// first malformed definition or truncated record. Unknown messages and developer fields are skipped.
// The Kotlin twin is `GarminWellnessParser` in `android/.../ingest/GarminWellness.kt`; the two are
// held byte-identical by an oracle test.

public struct GarminWellnessSamples: Equatable, Sendable {

    public struct TimedInt: Equatable, Sendable { public let ts: Int64; public let value: Int }
    public struct TimedDouble: Equatable, Sendable { public let ts: Int64; public let value: Double }

    /// One MONITORING record. Cumulative fields are as stored (running totals per activity type).
    public struct Monitoring: Equatable, Sendable {
        public let ts: Int64
        public let activityType: Int?
        public let intensity: Int?
        /// Raw `cycles`: the cumulative step count for walking/running activity types.
        public let cycles: Int?
        public let distanceM: Double?
        public let calories: Int?
        public let activeCalories: Int?
        public let activeTimeS: Double?
        public let heartRate: Int?
        public let moderateMin: Int?
        public let vigorousMin: Int?
    }

    /// A local day's activity totals, folded from the cumulative MONITORING counters.
    public struct DailyActivity: Equatable, Sendable {
        public let day: String
        public let steps: Int?
        public let distanceM: Double?
        public let calories: Int?
        public let activeCalories: Int?
    }

    public struct Spo2: Equatable, Sendable {
        public let ts: Int64
        public let percent: Int
        public let confidence: Int?
        public let mode: Int?
    }

    /// One raw sleep sample slot (274). Heart rate and movement are nil when the row holds none.
    public struct SleepRaw: Equatable, Sendable {
        public let ts: Int64
        public let heartRate: Double?
        public let movement: Double?
    }

    /// A night folded from SLEEP_LEVEL rows. Each row closes the span since the previous row.
    public struct SleepSession: Equatable, Sendable {
        public let start: Int64
        public let end: Int64
        public let unmeasurableS: Int64
        public let awakeS: Int64
        public let lightS: Int64
        public let deepS: Int64
        public let remS: Int64
    }

    public struct SleepAssessment: Equatable, Sendable {
        public let ts: Int64
        public let overallScore: Int
        public let combinedAwakeScore: Int?
        public let awakeTimeScore: Int?
        public let awakeningsCountScore: Int?
        public let deepSleepScore: Int?
        public let sleepDurationScore: Int?
        public let lightSleepScore: Int?
        public let sleepQualityScore: Int?
        public let sleepRecoveryScore: Int?
        public let remSleepScore: Int?
        public let restlessnessScore: Int?
        public let awakeningsCount: Int?
        public let interruptionsScore: Int?
        public let averageStress: Double?
    }

    public struct HrvSummary: Equatable, Sendable {
        public let ts: Int64
        public let weeklyAverageMs: Double?
        public let lastNightAverageMs: Double?
        public let lastNight5MinHighMs: Double?
        public let baselineLowUpperMs: Double?
        public let baselineBalancedLowerMs: Double?
        public let baselineBalancedUpperMs: Double?
        public let status: Int?
    }

    /// CANDIDATE (290): scale unconfirmed; raw values only.
    public struct BeatIntervalsCandidate: Equatable, Sendable {
        public let ts: Int64
        public let timestampMs: Int?
        public let intervalsMs: [Int]
    }

    /// CANDIDATE (398): units unconfirmed; raw float values only.
    public struct SkinTempOvernightCandidate: Equatable, Sendable {
        public let ts: Int64
        public let averageDeviation: Double?
        public let average7DayDeviation: Double?
        public let calibratedDays: Int?
        public let nightlyValue: Double?
    }

    /// Local time minus UTC, in seconds, from the file's own local timestamps; nil when it has none.
    public var utcOffsetS: Int?
    public var monitoring: [Monitoring] = []
    /// Folded with `utcOffsetS`, or UTC when the file carries no offset.
    public var dailyActivity: [DailyActivity] = []
    public var restingMetabolicRate: [TimedInt] = []
    public var restingHeartRate: [TimedInt] = []
    public var stress: [TimedInt] = []
    public var bodyBattery: [TimedInt] = []
    public var spo2: [Spo2] = []
    public var respiration: [TimedDouble] = []
    public var sleepLevels: [TimedInt] = []
    public var sleepSessions: [SleepSession] = []
    public var sleepRaw: [SleepRaw] = []
    public var sleepAssessments: [SleepAssessment] = []
    public var hrvSummaries: [HrvSummary] = []
    public var hrvValues: [TimedDouble] = []
    public var beatIntervalsCandidate: [BeatIntervalsCandidate] = []
    public var skinTempRawCandidate: [TimedDouble] = []
    public var skinTempOvernightCandidate: [SkinTempOvernightCandidate] = []
    /// Data messages this decoder does not read (file_id, device_info, events, unknown numbers).
    public var otherMessages = 0
    /// Health messages dropped because they had no usable timestamp or value.
    public var droppedMessages = 0
    /// False when the walk stopped early on a malformed or truncated record.
    public var complete = true

    public init() {}

    public var isEmpty: Bool {
        monitoring.isEmpty && restingMetabolicRate.isEmpty && restingHeartRate.isEmpty && stress.isEmpty
            && bodyBattery.isEmpty && spo2.isEmpty && respiration.isEmpty && sleepLevels.isEmpty
            && sleepRaw.isEmpty && sleepAssessments.isEmpty && hrvSummaries.isEmpty && hrvValues.isEmpty
            && beatIntervalsCandidate.isEmpty && skinTempRawCandidate.isEmpty
            && skinTempOvernightCandidate.isEmpty
    }
}

public enum GarminWellnessParser {

    /// Global message numbers whose scale or unit is unconfirmed on hardware; they decode only into
    /// the `*Candidate` fields.
    public static var candidateMessages: Set<Int> { [290, 397, 398] }

    /// Folds cumulative MONITORING counters into local-day totals: per day, each activity type's
    /// latest value of each counter, summed over activity types.
    /// Kotlin twin: `GarminWellnessParser.dailyTotals`.
    public static func dailyTotals(_ records: [GarminWellnessSamples.Monitoring], utcOffsetS: Int) -> [GarminWellnessSamples.DailyActivity] {
        struct Latest { var cycles: Int?; var distance: Double?; var calories: Int?; var active: Int? }
        var byDay: [String: [Int: Latest]] = [:]
        for r in records {
            let day = localDay(r.ts, utcOffsetS: utcOffsetS)
            var types = byDay[day] ?? [:]
            var latest = types[r.activityType ?? -1] ?? Latest()
            if let v = r.cycles { latest.cycles = v }
            if let v = r.distanceM { latest.distance = v }
            if let v = r.calories { latest.calories = v }
            if let v = r.activeCalories { latest.active = v }
            types[r.activityType ?? -1] = latest
            byDay[day] = types
        }
        return byDay.keys.sorted().map { day in
            // Sum in activity-type order so the floating-point total is platform-independent.
            let latest = byDay[day]!.sorted { $0.key < $1.key }.map(\.value)
            let steps = latest.compactMap(\.cycles)
            let distances = latest.compactMap(\.distance)
            let calories = latest.compactMap(\.calories)
            let active = latest.compactMap(\.active)
            return GarminWellnessSamples.DailyActivity(day: day,
                                 steps: steps.isEmpty ? nil : steps.reduce(0, +),
                                 distanceM: distances.isEmpty ? nil : distances.reduce(0, +),
                                 calories: calories.isEmpty ? nil : calories.reduce(0, +),
                                 activeCalories: active.isEmpty ? nil : active.reduce(0, +))
        }
    }

    /// Folds SLEEP_LEVEL rows into nights. A gap over an hour (or time running backwards) starts a
    /// new night; each row's level is credited with the time since the previous row.
    /// Kotlin twin: `GarminWellnessParser.sleepSessions`.
    public static func sleepSessions(_ levels: [GarminWellnessSamples.TimedInt]) -> [GarminWellnessSamples.SleepSession] {
        // Each night is [start row index, end row index], folded after the split points are known.
        var nights: [(first: Int, last: Int)] = []
        for (i, row) in levels.enumerated() {
            if let open = nights.last, row.ts >= levels[open.last].ts, row.ts - levels[open.last].ts <= 3600 {
                nights[nights.count - 1].last = i
            } else {
                nights.append((i, i))
            }
        }
        return nights.map { night in
            var spans = [Int64](repeating: 0, count: 5)
            if night.first < night.last {
                for i in (night.first + 1)...night.last {
                    spans[levels[i].value] += levels[i].ts - levels[i - 1].ts
                }
            }
            return GarminWellnessSamples.SleepSession(start: levels[night.first].ts, end: levels[night.last].ts,
                                unmeasurableS: spans[0], awakeS: spans[1], lightS: spans[2],
                                deepS: spans[3], remS: spans[4])
        }
    }

    /// Seconds between the FIT epoch (1989-12-31T00:00:00Z) and the Unix epoch.
    static let fitEpochOffset: Int64 = 631_065_600
    /// Upper sanity bound for a decoded time (2100-01-01T00:00:00Z).
    static let maxUnix: Int64 = 4_102_444_800
    /// The widest real UTC offset is +14:00; anything beyond is garbage.
    static let maxUtcOffsetS: Int64 = 14 * 3600

    /// Decodes every health message of a FIT file. Never throws: a malformed file yields what was read
    /// before the fault, with `complete == false`. Kotlin twin: `GarminWellnessParser.parse`.
    public static func parse(data: Data) -> GarminWellnessSamples {
        guard let stream = FitRecordStream.open(bytes: [UInt8](data)) else {
            var empty = GarminWellnessSamples()
            empty.complete = false
            return empty
        }
        var decoder = Decoder(stream: stream)
        let stop = stream.walk(maxRecords: FitRecordStream.maxRecords) { def, offset, compressed in
            decoder.consume(def, at: offset, compressed: compressed)
        }
        var out = decoder.out
        out.complete = stop == .end
        out.dailyActivity = dailyTotals(out.monitoring, utcOffsetS: out.utcOffsetS ?? 0)
        out.sleepSessions = sleepSessions(out.sleepLevels)
        return out
    }

    /// The local calendar day ("yyyy-MM-dd") of a Unix time at a fixed UTC offset. Pure arithmetic
    /// (civil-from-days) so both platforms agree without a time-zone database.
    /// Kotlin twin: `GarminWellnessParser.localDay`.
    static func localDay(_ ts: Int64, utcOffsetS: Int) -> String {
        let t = ts + Int64(utcOffsetS)
        let days = t >= 0 ? t / 86_400 : (t - 86_399) / 86_400
        let z = days + 719_468
        let era = (z >= 0 ? z : z - 146_096) / 146_097
        let doe = z - era * 146_097
        let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        let doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        let mp = (5 * doy + 2) / 153
        let d = doy - (153 * mp + 2) / 5 + 1
        let m = mp < 10 ? mp + 3 : mp - 9
        let y = yoe + era * 400 + (m <= 2 ? 1 : 0)
        return String(format: "%04lld-%02lld-%02lld", y, m, d)
    }

    /// IEEE 754 half precision to Double; nil for infinities and NaN.
    /// Kotlin twin: `GarminWellnessParser.halfToDouble`.
    static func halfToDouble(_ bits: Int) -> Double? {
        let sign: Double = bits & 0x8000 != 0 ? -1 : 1
        let exponent = (bits >> 10) & 0x1F
        let mantissa = Double(bits & 0x3FF)
        switch exponent {
        case 0: return sign * mantissa * 0x1p-24
        case 31: return nil
        default: return sign * (1 + mantissa / 1024) * Double(sign: .plus, exponent: exponent - 15, significand: 1)
        }
    }

    // MARK: - Decoder

    private struct Decoder {
        let stream: FitRecordStream
        var out = GarminWellnessSamples()
        /// The file's running timestamp in FIT seconds (field 253 and compressed headers).
        var running: Int64?
        var lastMonitoring: Int64?
        var rawStart: Int64?
        var rawStep: Int64 = 60
        var rawSlot: Int64 = 0

        init(stream: FitRecordStream) { self.stream = stream }

        /// One data message's fields, read by field number with FIT invalid sentinels rejected.
        struct Message {
            let stream: FitRecordStream
            let bigEndian: Bool
            let fields: [Int: (offset: Int, size: Int)]

            /// Kotlin twin: `Message.u`.
            func u(_ num: Int) -> Int64? {
                guard let f = fields[num], f.size <= 4,
                      let v = stream.uint(at: f.offset, size: f.size, bigEndian: bigEndian) else { return nil }
                let invalid: UInt64 = (1 << UInt64(f.size * 8)) - 1
                return v == invalid ? nil : Int64(v)
            }

            /// Kotlin twin: `Message.s`.
            func s(_ num: Int) -> Int64? {
                guard let f = fields[num], f.size <= 4,
                      let v = stream.int(at: f.offset, size: f.size, bigEndian: bigEndian) else { return nil }
                let invalid: Int64 = (1 << Int64(f.size * 8 - 1)) - 1
                return v == invalid ? nil : v
            }

            /// Kotlin twin: `Message.f32`.
            func f32(_ num: Int) -> Double? {
                guard let f = fields[num], f.size == 4,
                      let v = stream.uint(at: f.offset, size: 4, bigEndian: bigEndian), v != 0xFFFF_FFFF else { return nil }
                let d = Double(Float(bitPattern: UInt32(v)))
                return d.isFinite ? d : nil
            }

            /// An unsigned field as Int, or nil. Kotlin twin: `Message.value`.
            func value(_ num: Int) -> Int? { u(num).map { Int($0) } }

            /// An unsigned field divided by its FIT scale, or nil. Kotlin twin: `Message.scaled`.
            func scaled(_ num: Int, _ scale: Double) -> Double? { u(num).map { Double($0) / scale } }

            /// Kotlin twin: `Message.bytes`.
            func bytes(_ num: Int) -> ArraySlice<UInt8>? {
                guard let f = fields[num], f.offset + f.size <= stream.end else { return nil }
                return stream.bytes[f.offset..<(f.offset + f.size)]
            }
        }

        /// Kotlin twin: `Decoder.unix`.
        static func unix(_ fit: Int64?) -> Int64? {
            guard let fit, fit > 0 else { return nil }
            let unix = fit + GarminWellnessParser.fitEpochOffset
            return unix < GarminWellnessParser.maxUnix ? unix : nil
        }

        /// Kotlin twin: `Decoder.range`.
        static func range(_ v: Int64?, _ lo: Int64, _ hi: Int64) -> Int? {
            guard let v, v >= lo, v <= hi else { return nil }
            return Int(v)
        }

        /// Kotlin twin: `Decoder.noteOffset`.
        mutating func noteOffset(local: Int64?, fitTs: Int64?) {
            guard out.utcOffsetS == nil, let local, let fitTs else { return }
            let offset = local - fitTs
            if abs(offset) <= GarminWellnessParser.maxUtcOffsetS { out.utcOffsetS = Int(offset) }
        }

        /// Kotlin twin: `Decoder.consume`.
        mutating func consume(_ def: FitRecordStream.Definition, at start: Int, compressed: Int?) {
            var fields: [Int: (offset: Int, size: Int)] = [:]
            var offset = start
            for f in def.fields {
                if fields[f.num] == nil { fields[f.num] = (offset, f.size) }
                offset += f.size
            }
            let m = Message(stream: stream, bigEndian: def.bigEndian, fields: fields)

            if let c = compressed, let last = running {
                running = last + ((Int64(c) - (last & 0x1F)) & 0x1F)
            }
            if let ts = m.u(253) { running = ts }

            switch def.globalNum {
            case 55: monitoring(m)
            case 103:
                noteOffset(local: m.u(0), fitTs: running)
                guard m.u(5) != nil else { return }
                guard let ts = Self.unix(running), let rmr = Self.range(m.u(5), 1, 20_000) else { return drop() }
                out.restingMetabolicRate.append(.init(ts: ts, value: rmr))
            case 211:
                guard let ts = Self.unix(running),
                      let rhr = Self.range(m.u(1), 1, 300) ?? Self.range(m.u(0), 1, 300) else { return drop() }
                out.restingHeartRate.append(.init(ts: ts, value: rhr))
            case 227:
                guard let ts = Self.unix(m.u(1)) ?? Self.unix(running) else { return drop() }
                if let v = Self.range(m.s(0), 0, 100) { out.stress.append(.init(ts: ts, value: v)) }
                if let v = Self.range(m.s(3), 0, 100) { out.bodyBattery.append(.init(ts: ts, value: v)) }
            case 269:
                guard let ts = Self.unix(running), let pct = Self.range(m.u(0), 1, 100) else { return drop() }
                out.spo2.append(.init(ts: ts, percent: pct, confidence: m.value(1), mode: m.value(2)))
            case 273:
                noteOffset(local: m.u(2), fitTs: running)
                rawStart = Self.unix(running)
                rawStep = Int64(Self.range(m.u(1), 1, 3600) ?? 60)
                rawSlot = 0
            case 274: sleepRawRow(m)
            case 275:
                guard let ts = Self.unix(running), let level = Self.range(m.u(0), 0, 4) else { return drop() }
                out.sleepLevels.append(.init(ts: ts, value: level))
                // Awake and unmeasurable rows occupy a raw sample slot of their own.
                if rawStart != nil, level <= 1 { rawSlot += 1 }
            case 297:
                guard let ts = Self.unix(running), let raw = m.s(0), raw > 0 else { return drop() }
                out.respiration.append(.init(ts: ts, value: Double(raw) / 100))
            case 346: sleepAssessment(m)
            case 370:
                guard let ts = Self.unix(running) else { return drop() }
                out.hrvSummaries.append(.init(ts: ts, weeklyAverageMs: m.scaled(0, 128), lastNightAverageMs: m.scaled(1, 128),
                                              lastNight5MinHighMs: m.scaled(2, 128), baselineLowUpperMs: m.scaled(3, 128),
                                              baselineBalancedLowerMs: m.scaled(4, 128),
                                              baselineBalancedUpperMs: m.scaled(5, 128),
                                              status: Self.range(m.u(6), 0, 4)))
            case 371:
                guard let ts = Self.unix(running), let v = m.u(0) else { return drop() }
                out.hrvValues.append(.init(ts: ts, value: Double(v) / 128))
            case 290: beatIntervalsRow(m)
            case 397:
                guard let ts = Self.unix(running), let v = m.f32(1) else { return drop() }
                out.skinTempRawCandidate.append(.init(ts: ts, value: v))
            case 398:
                noteOffset(local: m.u(0), fitTs: running)
                guard let ts = Self.unix(running) else { return drop() }
                out.skinTempOvernightCandidate.append(.init(ts: ts, averageDeviation: m.f32(1),
                                                            average7DayDeviation: m.f32(2),
                                                            calibratedDays: m.value(3),
                                                            nightlyValue: m.f32(4)))
            default:
                out.otherMessages += 1
            }
        }

        /// Kotlin twin: `Decoder.drop`.
        mutating func drop() { out.droppedMessages += 1 }

        /// Kotlin twin: `Decoder.monitoring`.
        mutating func monitoring(_ m: Message) {
            var fitTs: Int64?
            if let full = m.u(253) {
                fitTs = full
            } else if let t16 = m.u(26) {
                if let ref = lastMonitoring ?? running { fitTs = ref + ((t16 - (ref & 0xFFFF)) & 0xFFFF) }
            } else {
                fitTs = running
            }
            guard let fitTs, let ts = Self.unix(fitTs) else { return drop() }
            lastMonitoring = fitTs
            noteOffset(local: m.u(11), fitTs: fitTs)

            let typeIntensity = m.u(24)
            let sample = GarminWellnessSamples.Monitoring(
                ts: ts,
                activityType: m.value(5) ?? typeIntensity.map { Int($0 & 0x1F) },
                intensity: typeIntensity.map { Int(($0 >> 5) & 0x07) },
                cycles: m.value(3),
                distanceM: m.scaled(2, 100),
                calories: m.value(1),
                activeCalories: m.value(19),
                activeTimeS: m.scaled(4, 1000),
                heartRate: Self.range(m.u(27), 1, 300),
                moderateMin: m.value(33),
                vigorousMin: m.value(34))
            if sample.cycles == nil, sample.distanceM == nil, sample.calories == nil, sample.activeCalories == nil,
               sample.activeTimeS == nil, sample.heartRate == nil, sample.intensity == nil,
               sample.moderateMin == nil, sample.vigorousMin == nil {
                return drop()
            }
            out.monitoring.append(sample)
        }

        /// Kotlin twin: `Decoder.sleepRawRow`.
        mutating func sleepRawRow(_ m: Message) {
            guard let start = rawStart, let row = m.bytes(0), row.count == 20 else { return drop() }
            // Ten little-endian half floats: index 8 is movement, index 9 heart rate.
            let b = Array(row)
            let hr = GarminWellnessParser.halfToDouble(Int(b[18]) | (Int(b[19]) << 8)).flatMap { $0 >= 25 && $0 <= 250 ? $0 : nil }
            let movement = GarminWellnessParser.halfToDouble(Int(b[16]) | (Int(b[17]) << 8)).flatMap { $0 >= 0 ? $0 : nil }
            out.sleepRaw.append(.init(ts: start + rawSlot * rawStep, heartRate: hr, movement: movement))
            rawSlot += 1
        }

        /// Kotlin twin: `Decoder.sleepAssessment`.
        mutating func sleepAssessment(_ m: Message) {
            guard let ts = Self.unix(running), let overall = Self.range(m.u(6), 0, 100) else { return drop() }
            out.sleepAssessments.append(.init(
                ts: ts, overallScore: overall, combinedAwakeScore: m.value(0), awakeTimeScore: m.value(1),
                awakeningsCountScore: m.value(2), deepSleepScore: m.value(3), sleepDurationScore: m.value(4),
                lightSleepScore: m.value(5), sleepQualityScore: m.value(7), sleepRecoveryScore: m.value(8),
                remSleepScore: m.value(9), restlessnessScore: m.value(10), awakeningsCount: m.value(11),
                interruptionsScore: m.value(14), averageStress: m.scaled(15, 100)))
        }

        /// Kotlin twin: `Decoder.beatIntervalsRow`.
        mutating func beatIntervalsRow(_ m: Message) {
            guard let ts = Self.unix(running) else { return drop() }
            var intervals: [Int] = []
            if let f = m.fields[1] {
                var o = f.offset
                while o + 2 <= f.offset + f.size {
                    if let v = stream.uint(at: o, size: 2, bigEndian: m.bigEndian), v != 0xFFFF { intervals.append(Int(v)) }
                    o += 2
                }
            }
            out.beatIntervalsCandidate.append(.init(ts: ts, timestampMs: m.value(0), intervalsMs: intervals))
        }
    }
}
