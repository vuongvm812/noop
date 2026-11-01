import Foundation

// MARK: - FIT parser (Garmin/ANT FIT binary)
//
// The FIT file format (Flexible and Interoperable Data Transfer), as documented publicly by Garmin in
// the FIT SDK. Structure:
//
//   ┌ File header (12 or 14 bytes) ────────────────────────────────────────────────┐
//   │ u8  headerSize (12 or 14)                                                      │
//   │ u8  protocolVersion                                                            │
//   │ u16 profileVersion (little-endian)                                             │
//   │ u32 dataSize (little-endian) — bytes of records that follow the header         │
//   │ 4×u8 ".FIT" signature                                                          │
//   │ [u16 headerCRC] — present only when headerSize == 14                           │
//   └───────────────────────────────────────────────────────────────────────────────┘
//   Then `dataSize` bytes of RECORDS, each led by a 1-byte record header:
//     • Normal header:  bit7=0. bit6=1 → DEFINITION message, 0 → DATA message.
//                       bit5 → has developer fields (definition). bits0-3 → local message type.
//     • Compressed-timestamp header: bit7=1. bits5-6 → local message type, bits0-4 → time offset.
//   A DEFINITION record describes a local message type: reserved(u8), architecture(u8: 0=LE,1=BE),
//     globalMessageNumber(u16), numFields(u8), then numFields × (fieldDefNum u8, size u8, baseType u8).
//     If the header's dev-fields bit is set: numDevFields(u8) then that many × 3 bytes (we skip them).
//   A DATA record carries the fields for its local type, in definition order, each `size` bytes.
//   Trailing 2-byte file CRC after the data section.
//
// We decode the messages that matter for a workout summary + HR/route:
//   • record (20)   — the per-sample stream: timestamp(253), position_lat(0)/long(1) in semicircles,
//                     heart_rate(3), altitude(2), distance(5).
//   • lap (19)      — per-lap summary: total_distance(9), total_calories(11), total_ascent(21),
//                     avg/max_heart_rate(15/16).
//   • session (18)  — whole-activity summary: sport(5), start_time(2), total_distance(9),
//                     total_calories(11), total_ascent(22), avg/max_heart_rate(16/17),
//                     total_elapsed_time(7).
//
// The record walk lives in `FitRecordStream`, shared with the Garmin wellness decoder
// (`GarminWellness.swift`), which reads the monitoring, sleep, HRV, stress and other health messages
// from the same files. Here developer-defined fields are skipped, compressed-timestamp headers carry
// no time into the track, and every other message type is skipped gracefully, never fatal.
//
// Conceptually adapted from the public FIT spec and the structure of roznet/FitFileParser,
// FitnessKit/FitDataProtocol (MIT) and muktihari/fit (BSD); NOOP's own clean implementation — no
// upstream code vendored.
//
// SECURITY: every read is bounds-checked against the buffer. Field/record counts are capped. A
// declared dataSize larger than the buffer is clamped. Semicircle→degree and FIT-epoch→Date conversions
// are range-checked. A malformed file yields `activity == nil`, never a crash.

enum FitParser {

    // FIT timestamps are seconds since the FIT epoch 1989-12-31T00:00:00Z (631065600 unix seconds).
    static let fitEpochOffset: Double = 631_065_600
    // Semicircles → degrees: value * (180 / 2^31).
    static let semicircleToDegrees = 180.0 / 2_147_483_648.0

    static func parse(data: Data) -> ActivityFileImportResult {
        // Work on a contiguous byte array for cheap random access (the picker handed us a local copy).
        guard let stream = FitRecordStream.open(bytes: [UInt8](BOM.stripUTF8(data))) else {
            return ActivityFileImportResult(activity: nil, kind: .fit, skipped: 0)
        }
        var decoder = FitDecoder(stream: stream)
        decoder.run()

        var result = decoder.buildResult()
        if result.activity == nil {
            // No workout: a Garmin wellness file (monitoring, sleep, HRV...) decodes here instead.
            let wellness = GarminWellnessParser.parse(data: data)
            result.wellness = wellness.isEmpty ? nil : wellness
        }
        return result
    }
}

// MARK: - Decoder

private struct FitDecoder {
    typealias Definition = FitRecordStream.Definition

    let stream: FitRecordStream

    // Accumulated activity data.
    private var samples: [ActivityFileImporter.TrackSample] = []
    private var hrSampleCount = 0
    private var gpsPointCount = 0

    private var sessionSport: String?
    private var sessionStart: Date?
    private var sessionElapsed: Double?
    private var sessionDistance: Double?
    private var sessionCalories: Double?
    private var sessionSteps: Int?
    private var sessionAscent: Double?
    private var sessionAvgHr: Int?
    private var sessionMaxHr: Int?

    private var lapDistanceSum = 0.0
    private var lapCalorieSum = 0.0
    private var lapStepSum = 0
    private var lapAscentSum = 0.0
    private var lapMaxHr: Int?

    private var skipped = 0

    init(stream: FitRecordStream) {
        self.stream = stream
    }

    /// Kotlin twin: `FitDecoder.decode`.
    mutating func run() {
        // The record walk is shared with the Garmin wellness decoder; only data messages reach here.
        let stream = self.stream
        let stop = stream.walk(maxRecords: FitRecordStream.maxRecords) { def, offset, _ in consumeData(def, at: offset) }
        // A data record whose local type was never defined cannot be sized, so the walk stopped there.
        if stop == .undefinedLocalType { skipped += 1 }
    }

    // MARK: - Data parsing

    private mutating func consumeData(_ def: Definition, at start: Int) {
        switch def.globalNum {
        // file_id (0) is read by some parsers to gate on activity-vs-course; we don't gate (a course /
        // workout file can still carry a useful track), matching the Kotlin decoder — so it's skipped.
        case 20: consumeRecord(def, at: start)
        case 19: consumeLap(def, at: start)
        case 18: consumeSession(def, at: start)
        default: break   // file_id, device_info, event, hrv, etc. — skipped gracefully (deferred)
        }
    }

    private mutating func consumeRecord(_ def: Definition, at start: Int) {
        var offset = start
        var lat: Double?
        var lon: Double?
        var ele: Double?
        var hr: Int?
        var time: Date?

        for f in def.fields {
            switch f.num {
            case 253: // timestamp (u32 seconds since FIT epoch)
                if let v = uintField(at: offset, size: f.size, bigEndian: def.bigEndian) {
                    time = fitDate(v)
                }
            case 0:   // position_lat (sint32 semicircles)
                if let v = intField(at: offset, size: f.size, bigEndian: def.bigEndian) {
                    lat = semicircles(v)
                }
            case 1:   // position_long (sint32 semicircles)
                if let v = intField(at: offset, size: f.size, bigEndian: def.bigEndian) {
                    lon = semicircles(v)
                }
            case 2:   // altitude (u16, scale 5, offset 500 → metres = v/5 - 500)
                if let v = uintField(at: offset, size: f.size, bigEndian: def.bigEndian), v != 0xFFFF {
                    ele = Double(v) / 5.0 - 500.0
                }
            case 3:   // heart_rate (u8 bpm)
                if let v = uintField(at: offset, size: f.size, bigEndian: def.bigEndian) {
                    hr = ActivityFileImporter.validHr(Double(v))
                }
            default:
                break
            }
            offset += f.size
        }

        let point = ActivityFileImporter.validCoordinate(lat: lat, lon: lon)
        if point == nil && time == nil && hr == nil {
            skipped += 1
            return
        }
        if samples.count >= ActivityFileImporter.maxPoints { return }
        if point != nil { gpsPointCount += 1 }
        if hr != nil { hrSampleCount += 1 }
        samples.append(.init(time: time, point: point, elevationM: ele, hr: hr))
    }

    /// lap (19): fold per-lap summary totals.
    private mutating func consumeLap(_ def: Definition, at start: Int) {
        var offset = start
        for f in def.fields {
            switch f.num {
            case 9:  // total_distance (u32, scale 100 → metres)
                if let v = uintField(at: offset, size: f.size, bigEndian: def.bigEndian), v != 0xFFFFFFFF {
                    lapDistanceSum += Double(v) / 100.0
                }
            case 11: // total_calories (u16 kcal)
                if let v = uintField(at: offset, size: f.size, bigEndian: def.bigEndian), v != 0xFFFF {
                    lapCalorieSum += Double(v)
                }
            case 10: // total_cycles; converted to steps only for foot activities in buildResult().
                if let v = uintField(at: offset, size: f.size, bigEndian: def.bigEndian),
                   let steps = Self.validSteps(v, size: f.size) {
                    lapStepSum += steps
                }
            case 21: // total_ascent (u16 metres)
                if let v = uintField(at: offset, size: f.size, bigEndian: def.bigEndian), v != 0xFFFF {
                    lapAscentSum += Double(v)
                }
            case 16: // max_heart_rate (u8 bpm)
                if let v = uintField(at: offset, size: f.size, bigEndian: def.bigEndian),
                   let hr = ActivityFileImporter.validHr(Double(v)) {
                    lapMaxHr = max(lapMaxHr ?? 0, hr)
                }
            default:
                break
            }
            offset += f.size
        }
    }

    /// session (18): the authoritative whole-activity summary; takes precedence over lap sums.
    private mutating func consumeSession(_ def: Definition, at start: Int) {
        var offset = start
        for f in def.fields {
            switch f.num {
            case 2:  // start_time (u32 FIT seconds)
                if let v = uintField(at: offset, size: f.size, bigEndian: def.bigEndian) { sessionStart = fitDate(v) }
            case 5:  // sport (enum)
                if let v = uintField(at: offset, size: f.size, bigEndian: def.bigEndian) { sessionSport = Self.sportName(v) }
            case 7:  // total_elapsed_time (u32, scale 1000 → seconds)
                if let v = uintField(at: offset, size: f.size, bigEndian: def.bigEndian), v != 0xFFFFFFFF {
                    sessionElapsed = Double(v) / 1000.0
                }
            case 9:  // total_distance (u32, scale 100 → metres)
                if let v = uintField(at: offset, size: f.size, bigEndian: def.bigEndian), v != 0xFFFFFFFF {
                    sessionDistance = Double(v) / 100.0
                }
            case 11: // total_calories (u16 kcal)
                if let v = uintField(at: offset, size: f.size, bigEndian: def.bigEndian), v != 0xFFFF {
                    sessionCalories = Double(v)
                }
            case 10: // total_cycles; converted to steps only for foot activities in buildResult().
                if let v = uintField(at: offset, size: f.size, bigEndian: def.bigEndian) {
                    sessionSteps = Self.validSteps(v, size: f.size)
                }
            case 22: // total_ascent (u16 metres)
                if let v = uintField(at: offset, size: f.size, bigEndian: def.bigEndian), v != 0xFFFF {
                    sessionAscent = Double(v)
                }
            case 16: // avg_heart_rate (u8 bpm)
                if let v = uintField(at: offset, size: f.size, bigEndian: def.bigEndian) {
                    sessionAvgHr = ActivityFileImporter.validHr(Double(v))
                }
            case 17: // max_heart_rate (u8 bpm)
                if let v = uintField(at: offset, size: f.size, bigEndian: def.bigEndian) {
                    sessionMaxHr = ActivityFileImporter.validHr(Double(v))
                }
            default:
                break
            }
            offset += f.size
        }
    }

    // MARK: - Field readers (bounds-checked)

    private func uintField(at offset: Int, size: Int, bigEndian: Bool) -> UInt64? {
        stream.uint(at: offset, size: size, bigEndian: bigEndian)
    }

    private func intField(at offset: Int, size: Int, bigEndian: Bool) -> Int64? {
        stream.int(at: offset, size: size, bigEndian: bigEndian)
    }

    private func semicircles(_ v: Int64) -> Double? {
        // The FIT "invalid" sentinel for sint32 is 0x7FFFFFFF; reject it.
        if v == 0x7FFF_FFFF { return nil }
        let deg = Double(v) * FitParser.semicircleToDegrees
        return deg.isFinite ? deg : nil
    }

    private func fitDate(_ v: UInt64) -> Date? {
        // 0xFFFFFFFF is the invalid timestamp sentinel.
        if v == 0xFFFF_FFFF { return nil }
        let unix = Double(v) + FitParser.fitEpochOffset
        // Sanity window: 1990-01-01 .. 2100-01-01. Anything outside is garbage.
        guard unix > FitParser.fitEpochOffset, unix < 4_102_444_800 else { return nil }
        return Date(timeIntervalSince1970: unix)
    }

    /// Map the FIT `sport` enum to a readable label (the common subset). Unknown → nil (neutral).
    static func sportName(_ v: UInt64) -> String? {
        switch v {
        case 0: return "Generic"
        case 1: return "Running"
        case 2: return "Cycling"
        case 5: return "Swimming"
        case 11: return "Walking"
        case 13: return "Strength Training"
        case 14: return "Cardio"
        case 15: return "Hiking"
        case 17: return "Hiking"
        case 4: return "Fitness Equipment"
        case 10: return "Training"
        default: return nil
        }
    }

    static func validSteps(_ v: UInt64, size: Int) -> Int? {
        let invalid = size >= 8 ? UInt64.max : ((UInt64(1) << UInt64(size * 8)) - 1)
        guard v != invalid, v > 0, v <= 1_000_000 else { return nil }
        return Int(v)
    }

    static func isFootSport(_ sport: String?) -> Bool {
        switch sport?.lowercased() {
        case "running", "walking", "hiking": return true
        default: return false
        }
    }

    // MARK: - Build

    func buildResult() -> ActivityFileImportResult {
        guard !samples.isEmpty || sessionStart != nil else {
            return ActivityFileImportResult(activity: nil, kind: .fit, skipped: skipped)
        }

        let route = samples.compactMap { $0.point }
        let times = samples.compactMap { $0.time }

        // Prefer the session summary; fall back to lap sums, then the sampled track.
        let distance = sessionDistance
            ?? (lapDistanceSum > 0 ? lapDistanceSum : (route.count >= 2 ? ActivityFileImporter.routeDistanceM(route) : nil))
        let calories = sessionCalories ?? (lapCalorieSum > 0 ? lapCalorieSum : nil)
        // FIT field 10 is total_cycles — for foot sports that's STRIDES (one stride = two steps), so
        // double it to the real step count. The "converted to steps" note above was never applied, so a
        // walk logged as 1150 strides read as 1150 steps instead of 2300 (#568).
        let steps = Self.isFootSport(sessionSport)
            ? (sessionSteps ?? (lapStepSum > 0 ? lapStepSum : nil)).map { $0 * 2 }
            : nil
        let ascent = sessionAscent
            ?? (lapAscentSum > 0 ? lapAscentSum : ActivityFileImporter.ascentM(from: samples))

        let sampledHrs = samples.compactMap { $0.hr }
        let avgHr = sessionAvgHr
            ?? (sampledHrs.isEmpty ? nil : Int((Double(sampledHrs.reduce(0, +)) / Double(sampledHrs.count)).rounded()))
        let maxHr = sessionMaxHr ?? lapMaxHr ?? sampledHrs.max()

        // Time window: the session start + elapsed when present, else the sampled span.
        let start = times.min() ?? sessionStart ?? Date(timeIntervalSince1970: 0)
        let computedEnd = times.max() ?? start
        let end: Date
        if let s = sessionStart, let el = sessionElapsed, el > 0 {
            end = max(s.addingTimeInterval(el), computedEnd)
        } else {
            end = computedEnd
        }

        let activity = ActivityFile(
            kind: .fit,
            start: min(start, sessionStart ?? start),
            end: max(end, start),
            sport: sessionSport,
            distanceM: distance,
            energyKcal: calories,
            steps: steps,
            avgHr: avgHr,
            maxHr: maxHr,
            ascentM: ascent,
            gpsPointCount: gpsPointCount,
            hrSampleCount: hrSampleCount,
            route: ActivityFileImporter.cappedRoute(route),
            // #137: the real per-record HR series, via the SHARED extractor so FIT persists a
            // byte-identical HR stream to the GPX/TCX paths (and the Kotlin twin).
            hrSamples: ActivityFileImporter.hrSamples(from: samples)
        )
        return ActivityFileImportResult(activity: activity, kind: .fit, skipped: skipped)
    }
}
