import Foundation
#if !WELLNESS_ORACLE
@testable import StrandImport
#endif

// Hand-built FIT fixtures for the Garmin wellness decoder, plus the canonical text dump that the
// Kotlin twin (`GarminWellnessParserTest`) asserts byte for byte. No bytes here come from Gadgetbridge
// or a real watch: every file is written by `WellnessFit` from the field layouts in
// `GarminWellness.swift`.
//
// This file also builds standalone as the parity oracle (no XCTest, no package):
//
//   S=Packages/StrandImport/Sources/StrandImport T=Packages/StrandImport/Tests/StrandImportTests
//   echo 'print(WellnessFixtures.oracleOutput())' > /tmp/main.swift
//   swiftc -O -D WELLNESS_ORACLE $S/FitRecordStream.swift $S/GarminWellness.swift \
//       $T/GarminWellnessFixtures.swift /tmp/main.swift -o /tmp/wellness-oracle && /tmp/wellness-oracle
//
// Its stdout is pasted verbatim as `WellnessFixtures.expected` here and in the Kotlin test.

struct WellnessFit {
    private(set) var records: [UInt8] = []
    /// Byte order for the next multi-byte writes; must match the definition being filled.
    var bigEndian = false

    mutating func def(_ local: Int, _ global: Int, _ fields: [(Int, Int, UInt8)],
                      bigEndian: Bool = false, devFieldSizes: [Int] = []) {
        records.append(UInt8(0x40 | (devFieldSizes.isEmpty ? 0 : 0x20) | (local & 0x0F)))
        records.append(0)
        records.append(bigEndian ? 1 : 0)
        if bigEndian {
            records += [UInt8((global >> 8) & 0xFF), UInt8(global & 0xFF)]
        } else {
            records += [UInt8(global & 0xFF), UInt8((global >> 8) & 0xFF)]
        }
        records.append(UInt8(fields.count))
        for f in fields { records += [UInt8(f.0), UInt8(f.1), f.2] }
        if !devFieldSizes.isEmpty {
            records.append(UInt8(devFieldSizes.count))
            for (k, size) in devFieldSizes.enumerated() { records += [UInt8(k), UInt8(size), 0] }
        }
        self.bigEndian = bigEndian
    }

    mutating func data(_ local: Int) { records.append(UInt8(local & 0x0F)) }
    mutating func compressed(_ local: Int, offset: Int) { records.append(UInt8(0x80 | ((local & 0x03) << 5) | (offset & 0x1F))) }

    mutating func raw(_ bytes: [UInt8]) { records += bytes }
    mutating func u8(_ v: Int) { records.append(UInt8(truncatingIfNeeded: v)) }
    mutating func uint(_ v: Int64, size: Int) {
        let le = (0..<size).map { UInt8(truncatingIfNeeded: v >> Int64(8 * $0)) }
        records += bigEndian ? le.reversed() : le
    }
    mutating func u16(_ v: Int) { uint(Int64(v), size: 2) }
    mutating func u32(_ v: Int64) { uint(v, size: 4) }
    mutating func f32(_ v: Float) { uint(Int64(v.bitPattern), size: 4) }

    func finish() -> Data {
        let size = records.count
        var out: [UInt8] = [12, 0x20, 0x00, 0x00]
        out += (0..<4).map { UInt8(truncatingIfNeeded: size >> (8 * $0)) }
        out += Array(".FIT".utf8)
        out += records
        out += [0, 0] // file CRC, unchecked by the decoders
        return Data(out)
    }
}

enum WellnessFixtures {
    /// A FIT time whose low 16 bits sit just below 0xFFFF, so timestamp_16 rolls over (0x4190FFC0).
    static let t: Int64 = 1_100_021_696
    /// +06:00, chosen so the monitoring fixture crosses local midnight.
    static let offset: Int64 = 21_600

    static let u8: UInt8 = 0x02, s8: UInt8 = 0x01, en: UInt8 = 0x00, by: UInt8 = 0x0D
    static let u16: UInt8 = 0x84, s16: UInt8 = 0x83, u32: UInt8 = 0x86, f32: UInt8 = 0x88

    static func monitoring() -> Data {
        var f = WellnessFit()
        f.def(0, 103, [(253, 4, u32), (0, 4, u32), (5, 2, u16)])
        f.data(0); f.u32(t); f.u32(t + offset); f.u16(1650)
        f.def(1, 55, [(253, 4, u32), (5, 1, en), (3, 4, u32), (2, 4, u32), (1, 2, u16), (19, 2, u16), (27, 1, u8), (24, 1, by)])
        f.data(1); f.u32(t); f.u8(6); f.u32(1000); f.u32(80_000); f.u16(1700); f.u16(120); f.u8(61); f.u8((2 << 5) | 6)
        f.def(2, 55, [(26, 2, u16), (5, 1, en), (3, 4, u32), (1, 2, u16), (27, 1, u8), (33, 2, u16), (34, 2, u16), (4, 4, u32)])
        // timestamp_16 rolls over 0xFFFF -> 0x0020 (t + 96).
        f.data(2); f.u16(Int((t + 96) & 0xFFFF)); f.u8(1); f.u32(400); f.u16(1750); f.u8(90); f.u16(5); f.u16(2); f.u32(2500)
        f.data(2); f.u16(Int((t + 128) & 0xFFFF)); f.u8(6); f.u32(1200); f.u16(1760); f.u8(70); f.u16(0xFFFF); f.u16(0xFFFF); f.u32(0xFFFF_FFFF)
        // An hour later: past local midnight, so the walking counters have reset.
        f.data(2); f.u16(Int((t + 3600) & 0xFFFF)); f.u8(6); f.u32(50); f.u16(10); f.u8(58); f.u16(0); f.u16(0); f.u32(0xFFFF_FFFF)
        // A record with nothing but invalid values is dropped.
        f.data(2); f.u16(Int((t + 3660) & 0xFFFF)); f.u8(0xFF); f.u32(0xFFFF_FFFF); f.u16(0xFFFF); f.u8(0xFF); f.u16(0xFFFF); f.u16(0xFFFF); f.u32(0xFFFF_FFFF)
        return f.finish()
    }

    /// Local midnight shaped like a Forerunner 165 day boundary: at exactly 00:00:00 the watch writes
    /// one record per activity type still carrying the ENDED day's totals, then the counters reset.
    static func midnight() -> Data {
        let midnight = t + 1504 // t + offset is 23:34:56 local
        var f = WellnessFit()
        f.def(0, 103, [(253, 4, u32), (0, 4, u32)])
        f.data(0); f.u32(t); f.u32(t + offset)
        f.def(1, 55, [(253, 4, u32), (5, 1, en), (3, 4, u32), (2, 4, u32), (19, 2, u16)])
        f.data(1); f.u32(midnight - 600); f.u8(1); f.u32(11_000); f.u32(850_000); f.u16(560)
        // Written out of order: an earlier, lower reading after a later one must not lower the total.
        f.data(1); f.u32(midnight - 300); f.u8(6); f.u32(3_400); f.u32(240_000); f.u16(140)
        f.data(1); f.u32(midnight - 900); f.u8(6); f.u32(3_100); f.u32(220_000); f.u16(130)
        // 00:00:00: the ended day's final totals.
        f.data(1); f.u32(midnight); f.u8(1); f.u32(12_500); f.u32(960_000); f.u16(640)
        f.data(1); f.u32(midnight); f.u8(6); f.u32(3_600); f.u32(250_000); f.u16(150)
        // 00:00:01 onwards: the new day's counters, starting again from zero.
        f.data(1); f.u32(midnight + 1); f.u8(6); f.u32(5); f.u32(400); f.u16(0)
        f.data(1); f.u32(midnight + 60); f.u8(6); f.u32(31); f.u32(2_400); f.u16(1)
        return f.finish()
    }

    static func restingHeartRate() -> Data {
        var f = WellnessFit()
        f.def(0, 211, [(253, 4, u32), (0, 1, u8), (1, 1, u8)])
        f.data(0); f.u32(t); f.u8(55); f.u8(52)
        f.data(0); f.u32(t + 86_400); f.u8(57); f.u8(0xFF)
        f.data(0); f.u32(t + 172_800); f.u8(0xFF); f.u8(0)
        return f.finish()
    }

    static func stress() -> Data {
        var f = WellnessFit()
        f.def(0, 227, [(0, 2, s16), (1, 4, u32), (3, 1, s8)])
        f.data(0); f.u16(25); f.u32(t); f.u8(80)
        f.data(0); f.u16(-1); f.u32(t + 180); f.u8(79)
        f.data(0); f.u16(-2); f.u32(t + 360); f.u8(0x7F)
        // No stress_level_time and no running timestamp yet: dropped.
        f.data(0); f.u16(40); f.u32(0xFFFF_FFFF); f.u8(60)
        f.def(1, 371, [(253, 4, u32), (0, 2, u16)])
        f.data(1); f.u32(t + 540); f.u16(4096)
        // Now the running timestamp (t + 540) stands in.
        f.data(0); f.u16(101); f.u32(0xFFFF_FFFF); f.u8(55)
        return f.finish()
    }

    static func spo2() -> Data {
        var f = WellnessFit()
        f.def(0, 269, [(253, 4, u32), (0, 1, u8), (1, 1, u8), (2, 1, en)])
        f.data(0); f.u32(t); f.u8(96); f.u8(3); f.u8(3)
        f.data(0); f.u32(t + 60); f.u8(0); f.u8(1); f.u8(3)
        f.data(0); f.u32(t + 120); f.u8(94); f.u8(0xFF); f.u8(1)
        return f.finish()
    }

    static func halfRow(movement: Int, heartRate: Int) -> [UInt8] {
        var row = [UInt8](repeating: 0, count: 20)
        row[16] = UInt8(movement & 0xFF); row[17] = UInt8(movement >> 8)
        row[18] = UInt8(heartRate & 0xFF); row[19] = UInt8(heartRate >> 8)
        return row
    }

    static func sleep() -> Data {
        var f = WellnessFit()
        f.def(0, 273, [(253, 4, u32), (1, 2, u16), (2, 4, u32)])
        f.data(0); f.u32(t); f.u16(60); f.u32(t + offset)
        f.def(1, 274, [(0, 20, by)])
        f.def(2, 275, [(253, 4, u32), (0, 1, en)])
        // movement 0.5, heart rate 62.0
        f.data(1); f.raw(halfRow(movement: 0x3800, heartRate: 0x53C0))
        // movement 2^-24 (subnormal), heart rate +infinity -> none
        f.data(1); f.raw(halfRow(movement: 0x0001, heartRate: 0x7C00))
        // An awake row takes the third slot.
        f.data(2); f.u32(t + 120); f.u8(1)
        // movement -1.0 -> none, heart rate 300 -> out of range
        f.data(1); f.raw(halfRow(movement: 0xBC00, heartRate: 0x5CB0))
        f.data(2); f.u32(t + 600); f.u8(2)
        f.data(2); f.u32(t + 1200); f.u8(3)
        f.data(2); f.u32(t + 1800); f.u8(4)
        f.data(2); f.u32(t + 2400); f.u8(1)
        f.data(2); f.u32(t + 2400); f.u8(9)          // not a sleep level: dropped
        f.data(2); f.u32(t + 20_000); f.u8(2)        // over four hours later, no sleep window: a new night
        f.def(3, 346, [(6, 1, u8), (0, 1, u8), (1, 1, u8), (2, 1, u8), (3, 1, u8), (4, 1, u8), (5, 1, u8),
                       (7, 1, u8), (8, 1, u8), (9, 1, u8), (10, 1, u8), (11, 1, u8), (14, 1, u8), (15, 2, u16)])
        f.data(3); f.raw([82, 70, 71, 72, 73, 74, 75, 77, 78, 79, 80, 3, 0xFF]); f.u16(2250)
        return f.finish()
    }

    /// Shaped like a real Forerunner 165 night (#47; relative times only): the watch's sleep window from
    /// EVENT 74, rows only at stage changes (66 and 138 minute single-stage stretches), the first row 17
    /// minutes after onset, the last row at the stop. Plus a stray stop before the start, a non-sleep
    /// event and an unknown event type.
    static func sleepWindow() -> Data {
        var f = WellnessFit()
        f.def(0, 21, [(253, 4, u32), (0, 1, en), (1, 1, en)])
        f.data(0); f.u32(t - 600); f.u8(74); f.u8(1)    // a stop with no start: ignored
        f.data(0); f.u32(t); f.u8(0); f.u8(0)           // timer event: not sleep
        f.data(0); f.u32(t); f.u8(74); f.u8(0)          // sleep onset
        f.data(0); f.u32(t + 60); f.u8(74); f.u8(4)     // unknown event type: dropped
        f.def(1, 275, [(253, 4, u32), (0, 1, en)])
        f.data(1); f.u32(t + 1020); f.u8(2)
        f.data(1); f.u32(t + 3480); f.u8(3)
        f.data(1); f.u32(t + 3540); f.u8(1)
        f.data(1); f.u32(t + 6000); f.u8(2)
        f.data(1); f.u32(t + 9960); f.u8(3)
        f.data(1); f.u32(t + 10440); f.u8(2)
        f.data(1); f.u32(t + 11340); f.u8(4)
        f.data(1); f.u32(t + 19620); f.u8(2)
        f.data(1); f.u32(t + 19680); f.u8(1)
        f.data(1); f.u32(t + 20760); f.u8(2)
        f.data(1); f.u32(t + 22020); f.u8(1)
        f.data(1); f.u32(t + 23520); f.u8(2)
        f.data(1); f.u32(t + 25080); f.u8(4)
        f.data(1); f.u32(t + 26160); f.u8(2)
        f.data(1); f.u32(t + 29623); f.u8(4)
        f.data(0); f.u32(t + 29623); f.u8(74); f.u8(1)  // wake
        return f.finish()
    }

    /// #49: a night and a later nap ending on the same local day, each in its own sleep window. The night
    /// holds a 30 minute unmeasurable span (level 0), so its timeline covers 93% of its window; the nap is
    /// the later session, which is what used to overwrite the night's day totals.
    static func sleepNap() -> Data {
        var f = WellnessFit()
        f.def(0, 21, [(253, 4, u32), (0, 1, en), (1, 1, en)])
        f.def(1, 275, [(253, 4, u32), (0, 1, en)])
        f.data(0); f.u32(t); f.u8(74); f.u8(0)
        f.data(1); f.u32(t + 1800); f.u8(2)
        f.data(1); f.u32(t + 5400); f.u8(3)
        f.data(1); f.u32(t + 7200); f.u8(0)
        f.data(1); f.u32(t + 14400); f.u8(4)
        f.data(1); f.u32(t + 16200); f.u8(1)
        f.data(1); f.u32(t + 27000); f.u8(2)
        f.data(0); f.u32(t + 27000); f.u8(74); f.u8(1)
        f.data(0); f.u32(t + 50000); f.u8(74); f.u8(0)
        f.data(1); f.u32(t + 51200); f.u8(2)
        f.data(1); f.u32(t + 52400); f.u8(1)
        f.data(0); f.u32(t + 52400); f.u8(74); f.u8(1)
        return f.finish()
    }

    static func respiration() -> Data {
        var f = WellnessFit()
        f.def(0, 297, [(253, 4, u32), (0, 2, s16)])
        f.data(0); f.u32(t); f.u16(1450)
        f.data(0); f.u32(t + 60); f.u16(-1)
        f.data(0); f.u32(t + 120); f.u16(0x7FFF)
        return f.finish()
    }

    static func hrv() -> Data {
        var f = WellnessFit()
        f.def(0, 370, [(253, 4, u32), (0, 2, u16), (1, 2, u16), (2, 2, u16), (3, 2, u16), (4, 2, u16), (5, 2, u16), (6, 1, en)])
        f.data(0); f.u32(t); f.u16(5120); f.u16(4800); f.u16(7000); f.u16(0xFFFF); f.u16(4352); f.u16(6400); f.u8(4)
        f.def(1, 371, [(253, 4, u32), (0, 2, u16)])
        f.data(1); f.u32(t + 300); f.u16(5000)
        f.data(1); f.u32(t + 600); f.u16(0xFFFF)
        return f.finish()
    }

    static func candidates() -> Data {
        var f = WellnessFit()
        f.def(0, 290, [(253, 4, u32), (0, 2, u16), (1, 8, u16)])
        f.data(0); f.u32(t); f.u16(512); f.u16(812); f.u16(0xFFFF); f.u16(790); f.u16(801)
        f.def(1, 397, [(253, 4, u32), (1, 4, f32)])
        f.data(1); f.u32(t + 60); f.f32(-0.25)
        f.data(1); f.u32(t + 120); f.u32(0xFFFF_FFFF)
        f.def(2, 398, [(253, 4, u32), (0, 4, u32), (1, 4, f32), (2, 4, f32), (3, 1, u8), (4, 4, f32)])
        f.data(2); f.u32(t + 3600); f.u32(t + 3600 - 18_000); f.f32(0.1); f.f32(-0.3); f.u8(14); f.f32(33.5)
        return f.finish()
    }

    /// Big-endian definitions, developer fields, compressed timestamps, unknown messages and a
    /// redefined local type, all in one file.
    static func framing() -> Data {
        var f = WellnessFit()
        f.def(0, 0, [(4, 4, u32), (0, 1, en)])
        f.data(0); f.u32(t); f.u8(32)
        f.def(1, 371, [(253, 4, u32), (0, 2, u16)], bigEndian: true)
        f.data(1); f.u32(t); f.u16(5120)
        f.def(2, 297, [(0, 2, s16)], devFieldSizes: [3])
        f.data(2); f.u16(1500); f.raw([0xAA, 0xBB, 0xCC])
        f.compressed(2, offset: 10); f.u16(1525); f.raw([1, 2, 3])
        // Offset 3 is behind the running timestamp's low 5 bits (10): it wraps forward to t + 35.
        f.compressed(2, offset: 3); f.u16(1550); f.raw([4, 5, 6])
        f.def(3, 65_280, [(0, 1, u8)])
        f.data(3); f.u8(7)
        f.def(1, 211, [(253, 4, u32), (1, 1, u8)])
        f.data(1); f.u32(t + 40); f.u8(50)
        return f.finish()
    }

    static let all: [(String, () -> Data)] = [
        ("monitoring", monitoring), ("midnight", midnight), ("restingHeartRate", restingHeartRate), ("stress", stress),
        ("spo2", spo2), ("sleep", sleep), ("sleepWindow", sleepWindow), ("sleepNap", sleepNap), ("respiration", respiration), ("hrv", hrv),
        ("candidates", candidates), ("framing", framing),
    ]

    // MARK: - Canonical dump

    /// A double as exact decimal when it has at most four places, else its IEEE bit pattern, so the
    /// text cannot differ between Swift and Kotlin formatting.
    static func num(_ v: Double?) -> String {
        guard let v else { return "-" }
        let scaled = v * 10_000
        if scaled == scaled.rounded(), abs(scaled) < 1e15 {
            let n = Int64(scaled)
            let sign = n < 0 ? "-" : ""
            let a = abs(n)
            let frac = String(a % 10_000)
            return "\(sign)\(a / 10_000).\(String(repeating: "0", count: 4 - frac.count))\(frac)"
        }
        return "bits:" + String(v.bitPattern, radix: 16)
    }

    static func num(_ v: Int?) -> String { v.map { String($0) } ?? "-" }

    static func dump(_ s: GarminWellnessSamples) -> String {
        var out: [String] = []
        out.append("offset \(num(s.utcOffsetS)) complete \(s.complete) other \(s.otherMessages) dropped \(s.droppedMessages)")
        for m in s.monitoring {
            out.append("mon \(m.ts) type=\(num(m.activityType)) int=\(num(m.intensity)) cyc=\(num(m.cycles)) dist=\(num(m.distanceM)) cal=\(num(m.calories)) act=\(num(m.activeCalories)) time=\(num(m.activeTimeS)) hr=\(num(m.heartRate)) mod=\(num(m.moderateMin)) vig=\(num(m.vigorousMin))")
        }
        for d in s.dailyActivity {
            out.append("day \(d.day) steps=\(num(d.steps)) dist=\(num(d.distanceM)) cal=\(num(d.calories)) act=\(num(d.activeCalories))")
        }
        for r in s.restingMetabolicRate { out.append("rmr \(r.ts) \(r.value)") }
        for r in s.restingHeartRate { out.append("rhr \(r.ts) \(r.value)") }
        for r in s.stress { out.append("stress \(r.ts) \(r.value)") }
        for r in s.bodyBattery { out.append("bb \(r.ts) \(r.value)") }
        for r in s.spo2 { out.append("spo2 \(r.ts) \(r.percent) conf=\(num(r.confidence)) mode=\(num(r.mode))") }
        for r in s.respiration { out.append("resp \(r.ts) \(num(r.value))") }
        for r in s.sleepLevels { out.append("level \(r.ts) \(r.value)") }
        for r in s.sleepMarks { out.append("mark \(r.ts) \(r.value)") }
        for r in s.sleepSessions {
            out.append("night \(r.start)-\(r.end) unmeasurable=\(r.unmeasurableS) awake=\(r.awakeS) light=\(r.lightS) deep=\(r.deepS) rem=\(r.remS)")
        }
        for r in s.sleepRaw { out.append("raw \(r.ts) hr=\(num(r.heartRate)) move=\(num(r.movement))") }
        for a in s.sleepAssessments {
            let subs = [a.combinedAwakeScore, a.awakeTimeScore, a.awakeningsCountScore, a.deepSleepScore,
                        a.sleepDurationScore, a.lightSleepScore, a.sleepQualityScore, a.sleepRecoveryScore,
                        a.remSleepScore, a.restlessnessScore, a.interruptionsScore].map { num($0) }
            out.append("assess \(a.ts) overall=\(a.overallScore) subs=\(subs.joined(separator: ",")) awakenings=\(num(a.awakeningsCount)) stress=\(num(a.averageStress))")
        }
        for h in s.hrvSummaries {
            let vals = [h.weeklyAverageMs, h.lastNightAverageMs, h.lastNight5MinHighMs, h.baselineLowUpperMs,
                        h.baselineBalancedLowerMs, h.baselineBalancedUpperMs].map { num($0) }
            out.append("hrvsum \(h.ts) \(vals.joined(separator: ",")) status=\(num(h.status))")
        }
        for r in s.hrvValues { out.append("hrv \(r.ts) \(num(r.value))") }
        for b in s.beatIntervalsCandidate {
            out.append("beats? \(b.ts) at=\(num(b.timestampMs)) \(b.intervalsMs.map { String($0) }.joined(separator: ","))")
        }
        for r in s.skinTempRawCandidate { out.append("skinraw? \(r.ts) \(num(r.value))") }
        for r in s.skinTempOvernightCandidate {
            out.append("skinnight? \(r.ts) avg=\(num(r.averageDeviation)) avg7=\(num(r.average7DayDeviation)) days=\(num(r.calibratedDays)) nightly=\(num(r.nightlyValue))")
        }
        return out.joined(separator: "\n")
    }

    /// Every fixture decoded and dumped, plus the local-day and half-float helpers at their edges.
    static func oracleOutput() -> String {
        var out: [String] = []
        for (name, build) in all {
            out.append("== \(name)")
            out.append(dump(GarminWellnessParser.parse(data: build())))
        }
        out.append("== localDay")
        for (ts, off) in [(Int64(0), 0), (-1, 0), (951_782_400, 0), (1_709_164_800, -1), (4_102_444_799, 50_400)] {
            out.append("\(ts)@\(off) \(GarminWellnessParser.localDay(ts, utcOffsetS: off))")
        }
        out.append("== half")
        for bits in [0x0000, 0x8000, 0x0001, 0x03FF, 0x0400, 0x3C00, 0x7BFF, 0xFBFF, 0x7C00, 0x7E00, 0x5640] {
            out.append("\(String(bits, radix: 16)) \(num(GarminWellnessParser.halfToDouble(bits)))")
        }
        return out.joined(separator: "\n")
    }
}
