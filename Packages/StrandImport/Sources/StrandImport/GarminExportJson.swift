import Foundation

// MARK: - Garmin Connect export: the rest of the health JSON
//
// Every health JSON a Garmin Connect "Export Your Data" ZIP carries beyond the daily rollups
// `GarminExportParser.parse` already reads: the rest of the daily summaries (UDSFile), the sleep scores,
// the health-status baselines, the body / fitness / race-prediction / personal-record files, the hydration
// log and the per-activity summaries (`summarizedActivities`). Pure and database-free; the app layer
// persists the result. Kotlin twin: `GarminExportJson` (ingest/GarminExportJson.kt). Both emit the same
// canonical `dump`, pinned by a shared golden (Tools/parity-fixtures/garmin-export-json) in each
// platform's tests.
//
// Units are normalised here: grams to kg, centimetres to metres, milliseconds to seconds, kJ to kcal.
// Every figure is UNTRUSTED input: numbers are read as finite doubles, and a value outside a plausible
// range is dropped rather than stored. A field the export does not carry stays absent (honest data).

public enum GarminExportJson {

    /// Import-report category ids (the UI localises them). Kotlin twin: `GarminExportJson.Category`.
    public enum Category {
        public static let daily = "daily_summary"
        public static let sleep = "sleep"
        public static let healthStatus = "health_status"
        public static let body = "body"
        public static let fitness = "fitness"
        public static let race = "race_predictions"
        public static let records = "personal_records"
        public static let hydration = "hydration"
        public static let activities = "activities"
        public static let settings = "settings"
        public static var categoryOrder: [String] { [daily, sleep, healthStatus, body, fitness, race, records, hydration, activities, settings] }
    }

    /// Skip-reason ids of the import report. Kotlin twin: `GarminExportJson.Skip`.
    public enum Skip {
        public static let privateFile = "private_undecodable"
        public static let account = "account_data"
        public static let noValues = "no_values"
        public static let unrecognised = "unrecognised"
        public static var reasonOrder: [String] { [privateFile, account, noValues, unrecognised] }
    }

    public struct Point: Equatable, Sendable {
        public let day: String
        public let key: String
        public let value: Double
    }

    /// One `summarizedActivities` entry, units normalised. Kotlin twin: `GarminExportJson.Activity`.
    public struct Activity: Equatable, Sendable {
        public let activityId: Int64
        public let startTs: Int
        public let durationS: Double
        public let sport: String
        public let distanceM: Double?
        public let energyKcal: Double?
        public let avgHr: Int?
        public let maxHr: Int?
        public let steps: Int?
        /// Time in HR zones 1-5 as percentages (sum 100), or nil.
        public let zonePercents: [Double]?
        /// Time in power zones 1-5 as percentages (sum 100), or nil.
        public let powerZonePercents: [Double]?
        public let avgPowerW: Double?
        public let maxPowerW: Double?
        public let normPowerW: Double?
        public let cadenceSpm: Double?
        public let elevationGainM: Double?
        public let elevationLossM: Double?
        public let minElevationM: Double?
        public let maxElevationM: Double?
        public let aerobicTe: Double?
        public let anaerobicTe: Double?
        public let rpe: Int?
        public let feel: Int?
        public let bodyBatteryChange: Int?
        public let vo2max: Double?
        public let strokes: Int?
        public let swolf: Double?
        public let strideM: Double?
        public let groundContactMs: Double?
        public let verticalOscillationCm: Double?
        public var sweatLossMl: Double?
        /// `{"z1":..,"z5":..}` HR-zone percentages in the flat shape the workout screens parse, or nil.
        public var zonesJson: String?
        /// The extras that have no column of their own, as a short English note (no leading label).
        public var noteExtras: String = ""

        public var endTs: Int { startTs + Int(durationS) }
    }

    /// Garmin's profile settings, offered (never applied silently). Kotlin twin: `GarminExportJson.Settings`.
    public struct Settings: Equatable, Sendable {
        public let maxHr: Int?
        public let restingHr: Int?
        public let ftpW: Int?
        public let zoneFloors: [Int]
        public init(maxHr: Int?, restingHr: Int?, ftpW: Int?, zoneFloors: [Int]) {
            self.maxHr = maxHr; self.restingHr = restingHr; self.ftpW = ftpW; self.zoneFloors = zoneFloors
        }
        public var isEmpty: Bool { maxHr == nil && restingHr == nil && ftpW == nil }
    }

    /// One report line: a category / skip-reason id and how many records it covers.
    public struct ReportCount: Equatable, Sendable {
        public let id: String
        public let count: Int
    }

    public struct Result: Sendable, Equatable {
        public let points: [Point]
        public let activities: [Activity]
        public let settings: Settings
        /// category id → records read, in `Category.categoryOrder`.
        public let imported: [ReportCount]
        /// skip-reason id → files/entries skipped, in `Skip.reasonOrder`.
        public let skipped: [ReportCount]
    }

    private static let maxPoints = 2_000_000

    /// `source` of the per-activity summary rows (Training Effect, average power, cadence).
    public static let sourceSummary = "garmin-summary"

    /// File role by lowercase base name; nil = not one of ours. Kotlin twin: `GarminExportJson.role`.
    private static func role(_ base: String) -> String? {
        if base.contains("udsfile") { return "uds" }
        if base.contains("hydrationlogfile") { return "hydration" }
        if base.contains("sleepdata") { return "sleep" }
        if base.contains("healthstatusdata") { return "health" }
        if base.hasSuffix("userbiometrics.json") { return "bio" }
        if base.contains("fitnessagedata") { return "fitnessage" }
        if base.contains("racepredictions") { return "race" }
        if base.contains("personalrecord") { return "records" }
        if base.contains("summarizedactivities") { return "activities" }
        if base.contains("heartratezones") { return "hrzones" }
        if base.contains("powerzones") { return "powerzones" }
        if base.contains("biometrics_latest") || base.contains("userbiometricprofiledata") { return "profile" }
        if base.contains("maxmetdata") { return "maxmet" }
        return nil
    }


    /// True when `path` (lowercase) is a Garmin health file this parser or the daily parser reads.
    /// Kotlin twin: `GarminExportJson.isHealthFile`.
    public static func isHealthFile(_ path: String) -> Bool { role(path.split(separator: "/", omittingEmptySubsequences: false).last.map(String.init) ?? path) != nil }

    /// Why a file under the export is not imported: Garmin-private backups NOOP cannot decode, else
    /// account / commerce / device data that is not health data. Kotlin twin: `GarminExportJson.skipReason`.
    public static func skipReason(_ path: String) -> String {
        let p = path.lowercased()
        return p.contains("lhabackup") || p.contains("primarytrainingbackup") || p.contains("devicebackups")
            ? Skip.privateFile : Skip.account
    }


    /// Kotlin twin: `GarminExportJson.parse`.
    public static func parse(_ files: [String: Data], skippedPaths: [String] = []) -> Result {
        var points: [String: Double] = [:]               // "day|key" → value (last write of the sorted file order wins)
        var activities: [Activity] = []
        var sweatByActivity: [Int64: Double] = [:]
        var counts: [String: Int] = [:]
        var noValues = 0
        var unrecognised = 0
        var maxHr: Int?, restingHr: Int?, ftp: Int?
        var floors: [Int] = []
        var bioFtp: (day: String, v: Int)?

        let put = { (_ day: String?, _ key: String, _ v: Double?) -> Void in
            guard let day, let v, v.isFinite, points.count < maxPoints else { return }
            points["\(day)|\(key)"] = v
        }
        let count = { (_ cat: String, _ n: Int) -> Void in counts[cat, default: 0] += n }

        var hydrationDay: [String: Double] = [:]
        var sweatDay: [String: Double] = [:]
        var sleepRecords: [[String: Any]] = []
        // Garmin splits files by date range; an entry repeated across two files counts once.
        var seenHydration = Set<String>(), seenSleep = Set<String>(), seenActivity = Set<Int64>()

        let names = files.keys.sorted { a, b in
            a.utf16.count != b.utf16.count ? a.utf16.count < b.utf16.count : a.utf16.lexicographicallyPrecedes(b.utf16)
        }
        for name in names {
            guard let data = files[name] else { continue }
            let base = name.split(separator: "/", omittingEmptySubsequences: false).last.map(String.init) ?? name
            guard let r = role(base) else {
                if !name.hasSuffix(".csv") { unrecognised += 1 }
                continue
            }
            let records = Self.records(data, role: r)
            switch r {
            case "uds":
                for rec in records {
                    guard let day = rec.day("calendarDate") else { continue }
                    count(Category.daily, 1)
                    put(day, "bmr_kcal", rec.pos("bmrKilocalories"))
                    put(day, "floors_ascended_m", rec.pos("floorsAscendedInMeters"))
                    put(day, "moderate_min", rec.pos("moderateIntensityMinutes"))
                    put(day, "vigorous_min", rec.pos("vigorousIntensityMinutes"))
                    put(day, "hr_min", rec.pos("minHeartRate").flatMap { (25.0...240.0).contains($0) ? $0 : nil })
                    put(day, "max_hr", rec.pos("maxHeartRate").flatMap { (25.0...240.0).contains($0) ? $0 : nil })
                    if let t = rec.obj("allDayStress")?.arr("aggregatorList")?.first(where: { $0.str("type") == "TOTAL" }) {
                        put(day, "stress_max", t.pos("maxStressLevel").flatMap { $0 <= 100 ? $0 : nil })
                        put(day, "stress_low_min", t.pos("lowDuration").map { $0 / 60.0 })
                        put(day, "stress_medium_min", t.pos("mediumDuration").map { $0 / 60.0 })
                        put(day, "stress_high_min", t.pos("highDuration").map { $0 / 60.0 })
                    }
                    if let bb = rec.obj("bodyBattery") {
                        put(day, "body_battery_charged", bb.nonNeg("chargedValue"))
                        put(day, "body_battery_drained", bb.nonNeg("drainedValue"))
                        for e in bb.arr("bodyBatteryStatList") ?? [] {
                            guard let v = e.nonNeg("statsValue").flatMap({ $0 <= 100 ? $0 : nil }) else { continue }
                            switch e.str("bodyBatteryStatType") {
                            case "HIGHEST": put(day, "body_battery_high", v)
                            case "LOWEST": put(day, "body_battery_low", v)
                            default: break
                            }
                        }
                    }
                    if let rs = rec.obj("respiration") {
                        put(day, "resp_rate", rs.pos("avgWakingRespirationValue").flatMap { $0 < 80 ? $0 : nil })
                        put(day, "resp_high", rs.pos("highestRespirationValue").flatMap { $0 < 80 ? $0 : nil })
                        put(day, "resp_low", rs.pos("lowestRespirationValue").flatMap { $0 < 80 ? $0 : nil })
                    }
                    if let sp = rec.obj("spo2") ?? rec.obj("pulseOx") ?? rec.obj("allDayPulseOx") {
                        put(day, "spo2", sp.firstPos("averageSpO2", "avgSpO2", "averageSpo2", "avgSpo2").flatMap { $0 <= 100 ? $0 : nil })
                        put(day, "spo2_min", sp.firstPos("lowestSpO2", "lowestSpo2", "minSpO2").flatMap { $0 <= 100 ? $0 : nil })
                    }
                }
            case "hydration":
                for rec in records {
                    guard let day = rec.day("calendarDate") else { continue }
                    let hid = rec.obj("uuid")?.str("uuid") ?? "\(day)|\(rec.str("timestampLocal") ?? "null")|\(rec.str("persistedTimestampGMT") ?? "null")|\(rec.dbl("valueInML").map { String($0) } ?? "null")|\(rec.int64("activityId").map { String($0) } ?? "null")"
                    if !seenHydration.insert(hid).inserted { continue }
                    count(Category.hydration, 1)
                    if let sweat = rec.pos("estimatedSweatLossInML") {
                        sweatDay[day, default: 0] += sweat
                        if let id = rec.int64("activityId"), id > 0 { sweatByActivity[id, default: 0] += sweat }
                    }
                    if rec.str("hydrationSource") != "GARMIN_ACTIVITY", let v = rec.pos("valueInML") {
                        hydrationDay[day, default: 0] += v
                    }
                }
            case "sleep":
                for rec in records {
                    if rec.day("calendarDate") == nil || ["deepSleepSeconds", "lightSleepSeconds", "remSleepSeconds", "awakeSleepSeconds"].allSatisfy({ rec.dbl($0) == nil }) { continue }
                    if !seenSleep.insert("\(rec.day("calendarDate") ?? "")|\(rec.str("sleepStartTimestampGMT") ?? "null")").inserted { continue }
                    sleepRecords.append(rec)
                }
            case "health":
                for rec in records {
                    guard let day = rec.day("calendarDate") else { continue }
                    count(Category.healthStatus, 1)
                    for m in rec.arr("metrics") ?? [] {
                        // SKIN_TEMP_C is a signed deviation; every other health metric is positive.
                        guard let v = m.str("type") == "SKIN_TEMP_C" ? m.dbl("value") : m.pos("value") else { noValues += 1; continue }
                        switch m.str("type") {
                        case "HRV":
                            put(day, "hrv_baseline_low", m.pos("baselineLowerLimit"))
                            put(day, "hrv_baseline_high", m.pos("baselineUpperLimit"))
                        case "HR": put(day, "hr_overnight", v)
                        case "RESPIRATION": put(day, "resp_overnight", v)
                        case "SPO2": put(day, "spo2", v <= 100 ? v : nil)
                        case "SKIN_TEMP_C": put(day, "skin_temp_dev_c", v)
                        default: break
                        }
                    }
                }
            case "bio":
                for rec in records {
                    guard let day = rec.obj("metaData")?.day("calendarDate") ?? rec.day("calendarDate") else { continue }
                    var any = false
                    if let g = rec.pos("weight") { let kg = g / 1000.0; if (20.0...400.0).contains(kg) { put(day, "weight", kg); any = true } }
                    if let h = rec.pos("height") {
                        let m = h > 3 ? h / 100.0 : h
                        if (0.5...2.6).contains(m) { put(day, "height_m", m); any = true }
                    }
                    if let f = rec.pos("functionalThresholdPower"), f < 2000 {
                        put(day, "ftp_w", f); any = true
                        if bioFtp == nil || day > bioFtp!.day { bioFtp = (day, Int(f)) }
                    }
                    if let v = rec.pos("vo2MaxRunning"), v < 100 { put(day, "vo2max", v); any = true }
                    if let v = rec.pos("lactateThresholdHeartRate"), (60.0...240.0).contains(v) { put(day, "lactate_threshold_hr", v); any = true }
                    if any { count(Category.body, 1) }
                }
            case "fitnessage":
                for rec in records {
                    guard let day = rec.day("asOfDateGmt") else { continue }
                    count(Category.fitness, 1)
                    put(day, "fitness_age", rec.pos("currentBioAge").flatMap { $0 < 120 ? $0 : nil })
                    put(day, "bmi", rec.pos("bmi").flatMap { (8.0...80.0).contains($0) ? $0 : nil })
                    put(day, "vigorous_days", rec.nonNeg("totalVigorousDays"))
                }
            case "race":
                for rec in records {
                    guard let day = rec.day("calendarDate") else { continue }
                    count(Category.race, 1)
                    put(day, "race_5k_s", rec.pos("raceTime5K")); put(day, "race_10k_s", rec.pos("raceTime10K"))
                    put(day, "race_half_s", rec.pos("raceTimeHalf")); put(day, "race_marathon_s", rec.pos("raceTimeMarathon"))
                }
            case "records":
                for rec in records {
                    guard let type = rec.str("personalRecordType"), let v = rec.dbl("value"), let day = prDay(rec) else { continue }
                    count(Category.records, 1)
                    put(day, "pr_" + slug(type), v)
                }
            case "activities":
                for rec in records {
                    guard let a = activity(rec) else { continue }
                    if !seenActivity.insert(a.activityId).inserted { continue }
                    count(Category.activities, 1)
                    activities.append(a)
                }
            case "hrzones":
                if let rec = records.first(where: { $0.str("sport") == "DEFAULT" }) ?? records.first {
                    maxHr = rec.pos("maxHeartRateUsed").flatMap { (100.0...240.0).contains($0) ? Int($0) : nil } ?? maxHr
                    restingHr = rec.pos("restingHeartRateUsed").flatMap { (25.0...120.0).contains($0) ? Int($0) : nil } ?? restingHr
                    let f = (1...5).compactMap { rec.pos("zone\($0)Floor").flatMap { $0 < 300 ? Int($0) : nil } }
                    if f.count == 5 { floors = f }
                }
            case "powerzones":
                let rank = { (_ r: [String: Any]) -> Int in ["DEFAULT", "CYCLING", "RUNNING"].firstIndex(of: r.str("sport") ?? "") ?? 3 }
                let cands = records.enumerated().filter { $0.element.pos("functionalThresholdPower") != nil }
                    .sorted { rank($0.element) != rank($1.element) ? rank($0.element) < rank($1.element) : $0.offset < $1.offset }
                if let f = cands.first?.element.pos("functionalThresholdPower"), f < 2000 { ftp = Int(f) }
            case "profile":
                for rec in records {
                    if let f = rec.pos("functionalThresholdPower"), f < 2000, ftp == nil { ftp = Int(f) }
                }
            case "maxmet":
                for rec in records where rec.day("calendarDate") != nil && rec.pos("vo2MaxValue") != nil { count(Category.fitness, 1) }
            default: break
            }
        }

        // Sleep: one main night per day (the longest asleep time, earliest start on a tie); further records of
        // the same day are naps. Kotlin twin: `GarminExportJson.parse` sleep block.
        let asleep = { (_ r: [String: Any]) -> Double in (r.dbl("deepSleepSeconds") ?? 0) + (r.dbl("lightSleepSeconds") ?? 0) + (r.dbl("remSleepSeconds") ?? 0) }
        let start = { (_ r: [String: Any]) -> String in r.str("sleepStartTimestampGMT") ?? "" }
        var byDay: [String: [[String: Any]]] = [:]
        for r in sleepRecords { byDay[r.day("calendarDate")!, default: []].append(r) }
        for (day, recs) in byDay {
            let ordered = recs.enumerated().sorted { a, b in
                let x = asleep(a.element), y = asleep(b.element)
                if x != y { return x > y }
                let sa = start(a.element), sb = start(b.element)
                if sa != sb { return sa.utf16.lexicographicallyPrecedes(sb.utf16) }
                return a.offset < b.offset
            }.map(\.element)
            let main = ordered[0]
            count(Category.sleep, recs.count)
            let scores = main.obj("sleepScores")
            put(day, "sleep_score_quality", scores?.pos("qualityScore")); put(day, "sleep_score_duration", scores?.pos("durationScore"))
            put(day, "sleep_score_recovery", scores?.pos("recoveryScore")); put(day, "sleep_score_deep", scores?.pos("deepScore"))
            put(day, "sleep_score_rem", scores?.pos("remScore")); put(day, "sleep_score_restfulness", scores?.pos("restfulnessScore"))
            put(day, "sleep_awake_count", main.nonNeg("awakeCount")); put(day, "sleep_restless_moments", main.nonNeg("restlessMomentCount"))
            put(day, "sleep_stress", main.pos("avgSleepStress").flatMap { $0 <= 100 ? $0 : nil })
            put(day, "sleep_resp_low", main.pos("lowestRespiration").flatMap { $0 < 80 ? $0 : nil })
            put(day, "sleep_resp_high", main.pos("highestRespiration").flatMap { $0 < 80 ? $0 : nil })
            put(day, "sleep_awake_min", main.pos("awakeSleepSeconds").map { $0 / 60.0 })
            if ordered.count > 1 { put(day, "nap_min", ordered.dropFirst().reduce(0.0) { $0 + asleep($1) } / 60.0) }
        }
        for (day, v) in hydrationDay { put(day, "hydration_ml", v) }
        for (day, v) in sweatDay { put(day, "sweat_loss_ml", v) }

        let settings = Settings(maxHr: maxHr, restingHr: restingHr, ftpW: ftp ?? bioFtp?.v, zoneFloors: floors)
        if !settings.isEmpty { count(Category.settings, 1) }

        let acts = activities.map { a -> Activity in
            var b = a; if let s = sweatByActivity[a.activityId] { b.sweatLossMl = s }
            b.zonesJson = zonesJson(b.zonePercents); b.noteExtras = noteExtras(b)
            return b
        }.sorted { $0.startTs != $1.startTs ? $0.startTs < $1.startTs : $0.activityId < $1.activityId }
        var skipCounts: [String: Int] = [:]
        for p in skippedPaths { skipCounts[skipReason(p), default: 0] += 1 }
        if noValues > 0 { skipCounts[Skip.noValues, default: 0] += noValues }
        if unrecognised > 0 { skipCounts[Skip.unrecognised, default: 0] += unrecognised }

        let pts = points.map { (k, v) -> Point in
            let i = k.firstIndex(of: "|")!
            return Point(day: String(k[..<i]), key: String(k[k.index(after: i)...]), value: v)
        }.sorted { a, b in a.day != b.day ? a.day.utf16.lexicographicallyPrecedes(b.day.utf16) : a.key.utf16.lexicographicallyPrecedes(b.key.utf16) }
        return Result(
            points: pts, activities: acts, settings: settings,
            imported: Category.categoryOrder.compactMap { c in counts[c].flatMap { $0 > 0 ? ReportCount(id: c, count: $0) : nil } },
            skipped: Skip.reasonOrder.compactMap { s in skipCounts[s].flatMap { $0 > 0 ? ReportCount(id: s, count: $0) : nil } })
    }

    // MARK: - activity

    /// Kotlin twin: `GarminExportJson.activity`.
    private static func activity(_ r: [String: Any]) -> Activity? {
        guard let id = r.int64("activityId") else { return nil }
        guard let startMs = r.dbl("startTimeGmt") ?? r.dbl("beginTimestamp") else { return nil }
        let startSec = startMs >= 1e11 ? (startMs / 1000.0) : startMs
        guard let durMs = r.pos("duration") ?? r.pos("elapsedDuration") else { return nil }
        let durationS = durMs / 1000.0
        guard durationS >= 1, durationS <= 7 * 86_400.0, startMs > 0, startMs < 9e15 else { return nil }
        let startTs = Int(startSec)
        let zones = (1...5).map { r.nonNeg("hrTimeInZone_\($0)") ?? 0 }
        let pz = (1...5).map { r.nonNeg("powerTimeInZone_\($0)") ?? 0 }
        let pct = { (_ z: [Double]) -> [Double]? in
            let t = z.reduce(0, +)
            return t <= 0 ? nil : z.map { ($0 / t * 10_000.0 + 0.5).rounded(.down) / 100.0 }
        }
        let raw = r.str("activityType") ?? r.str("sportType")?.lowercased()
        return Activity(
            activityId: id, startTs: startTs, durationS: durationS, sport: ActivityFileImporter.workoutSport(from: raw),
            distanceM: r.pos("distance").map { $0 / 100.0 },
            energyKcal: r.pos("calories").map { $0 / 4.184 },
            avgHr: r.pos("avgHr").flatMap { (25.0...250.0).contains($0) ? Int($0) : nil },
            maxHr: r.pos("maxHr").flatMap { (25.0...250.0).contains($0) ? Int($0) : nil },
            steps: r.pos("steps").flatMap { $0 < 1_000_000 ? Int($0) : nil },
            zonePercents: pct(zones), powerZonePercents: pct(pz),
            avgPowerW: r.pos("avgPower").flatMap { $0 < 3000 ? $0 : nil },
            maxPowerW: r.pos("maxPower").flatMap { $0 < 5000 ? $0 : nil },
            normPowerW: r.pos("normPower").flatMap { $0 < 3000 ? $0 : nil },
            cadenceSpm: (r.pos("avgDoubleCadence") ?? r.pos("avgBikeCadence")).flatMap { $0 < 300 ? $0 : nil },
            elevationGainM: r.nonNeg("elevationGain").map { $0 / 100.0 }.flatMap { $0 < 20_000 ? $0 : nil },
            elevationLossM: r.nonNeg("elevationLoss").map { $0 / 100.0 }.flatMap { $0 < 20_000 ? $0 : nil },
            minElevationM: r.dbl("minElevation").map { $0 / 100.0 }.flatMap { abs($0) < 20_000 ? $0 : nil },
            maxElevationM: r.dbl("maxElevation").map { $0 / 100.0 }.flatMap { abs($0) < 20_000 ? $0 : nil },
            aerobicTe: r.pos("aerobicTrainingEffect").flatMap { $0 <= 5.0 ? $0 : nil },
            anaerobicTe: r.pos("anaerobicTrainingEffect").flatMap { $0 <= 5.0 ? $0 : nil },
            rpe: r.pos("workoutRpe").flatMap { v -> Int? in let n = Int((v / 10.0 + 0.5).rounded(.down)); return (1...10).contains(n) ? n : nil },
            feel: r.nonNeg("workoutFeel").flatMap { $0 <= 100 ? Int($0) : nil },
            bodyBatteryChange: r.dbl("differenceBodyBattery").flatMap { (-100.0...100.0).contains($0) ? Int($0) : nil },
            vo2max: r.pos("vO2MaxValue").flatMap { $0 < 100 ? $0 : nil },
            strokes: r.pos("strokes").flatMap { $0 < 1_000_000 ? Int($0) : nil },
            swolf: r.pos("avgSwolf").flatMap { $0 < 300 ? $0 : nil },
            strideM: r.pos("avgStrideLength").map { $0 / 100.0 }.flatMap { $0 < 5 ? $0 : nil },
            groundContactMs: r.pos("avgGroundContactTime").flatMap { $0 < 2000 ? $0 : nil },
            verticalOscillationCm: r.pos("avgVerticalOscillation").flatMap { $0 < 50 ? $0 : nil },
            sweatLossMl: nil)
    }


    /// The day a personal record was set: `prStartTimeGMT` ("Sat Oct 24 17:00:00 GMT 2026") else `createdDate`.
    /// Kotlin twin: `GarminExportJson.prDay`.
    private static func prDay(_ r: [String: Any]) -> String? {
        let monthNames = ["jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"]
        if let s = r.str("prStartTimeGMT")?.trimmingCharacters(in: .whitespaces),
           let prPattern = try? NSRegularExpression(pattern: #"^\w{3} (\w{3}) (\d{1,2}) \d{2}:\d{2}:\d{2} \w+ (\d{4})$"#) {
            let ns = s as NSString
            if let m = prPattern.firstMatch(in: s, range: NSRange(location: 0, length: ns.length)),
               let mon = monthNames.firstIndex(of: ns.substring(with: m.range(at: 1)).lowercased()),
               let d = Int(ns.substring(with: m.range(at: 2))), (1...31).contains(d), let y = Int(ns.substring(with: m.range(at: 3))) {
                return String(format: "%04d-%02d-%02d", y, mon + 1, d)
            }
        }
        return r.day("createdDate")
    }

    /// Lowercase `[a-z0-9]` words joined by `_` ("Most Steps in a Day" → "most_steps_in_a_day").
    /// Kotlin twin: `GarminExportJson.slug`.
    static func slug(_ s: String) -> String {
        var out = ""; var gap = false
        for c in s.lowercased().unicodeScalars {
            if (c >= "a" && c <= "z") || (c >= "0" && c <= "9") {
                if gap && !out.isEmpty { out.append("_") }
                gap = false; out.unicodeScalars.append(c)
            } else { gap = true }
        }
        return String(out.prefix(48))
    }

    // MARK: - workout mapping (shared by the app layer and the tests)

    /// `{"z1":..,"z5":..}` zone percentages in the flat shape the workout screens parse, or nil.
    /// Kotlin twin: `GarminExportJson.zonesJson`.
    static func zonesJson(_ p: [Double]?) -> String? {
        guard let p, p.count == 5 else { return nil }
        return "{" + p.enumerated().map { "\"z\($0.offset + 1)\":" + num($0.element) }.joined(separator: ",") + "}"
    }

    /// `existing` plus the summary's `extra` note, unchanged when it is already there (a re-import adds nothing).
    /// Kotlin twin: `WearableExportImporter.enrichedNotes`.
    public static func enrichedNotes(_ existing: String?, _ extra: String) -> String? {
        if extra.isEmpty || existing?.contains(extra) == true { return existing }
        return [existing.flatMap { $0.isEmpty ? nil : $0 }, extra].compactMap { $0 }.joined(separator: " · ")
    }

    /// The summary's extras that have no column of their own, as a short English note (like the FIT import note).
    /// Kotlin twin: `GarminExportJson.noteExtras`.
    static func noteExtras(_ a: Activity) -> String {
        var parts: [String] = []
        if let v = a.normPowerW { parts.append("normalized power \(Int(v)) W") }
        if let v = a.maxPowerW { parts.append("max power \(Int(v)) W") }
        if a.elevationGainM != nil || a.elevationLossM != nil {
            parts.append("elevation +\(Int(a.elevationGainM ?? 0)) m / -\(Int(a.elevationLossM ?? 0)) m")
        }
        if let v = a.rpe { parts.append("RPE \(v)") }
        if let v = a.feel { parts.append("feel \(v)/100") }
        if let v = a.bodyBatteryChange { parts.append("Body Battery \(v)") }
        if let v = a.vo2max { parts.append("VO2 max " + num(v)) }
        if let v = a.strokes { parts.append("\(v) strokes") }
        if let v = a.swolf { parts.append("SWOLF \(Int(v))") }
        if let v = a.strideM { parts.append("stride " + num(v) + " m") }
        if let v = a.groundContactMs { parts.append("ground contact \(Int(v)) ms") }
        if let v = a.verticalOscillationCm { parts.append("vertical oscillation " + num(v) + " cm") }
        if let v = a.sweatLossMl { parts.append("sweat loss \(Int(v)) ml") }
        return parts.joined(separator: " · ")
    }


    /// A decimal with up to 4 places, half away from zero, trailing zeros trimmed. Kotlin twin: `GarminExportJson.num`.
    public static func num(_ v: Double) -> String {
        let n = Int64((abs(v) * 10_000.0 + 0.5).rounded(.down))
        let sign = v < 0 && n != 0 ? "-" : ""
        var frac = String(n % 10_000)
        frac = String(repeating: "0", count: 4 - frac.count) + frac
        while frac.hasSuffix("0") { frac.removeLast() }
        return sign + String(n / 10_000) + (frac.isEmpty ? "" : "." + frac)
    }

    // MARK: - JSON helpers (finite doubles only)

    /// The file's records: a bare array, a one-element wrapper array / object holding the real list, or a single object.
    /// Kotlin twin: `GarminExportJson.records`.
    private static func records(_ data: Data, role: String) -> [[String: Any]] {
        guard let json = try? JSONSerialization.jsonObject(with: BOM.stripUTF8(data)) else { return [] }
        let top: [[String: Any]]
        if let arr = json as? [Any] { top = Array(arr.prefix(1_000_000)).compactMap { $0 as? [String: Any] } }
        else if let obj = json as? [String: Any] { top = [obj] }
        else { return [] }
        let wrapperKey: String? = role == "activities" ? "summarizedActivitiesExport" : role == "records" ? "personalRecords" : nil
        if let wrapperKey { return top.flatMap { $0.arr(wrapperKey) ?? [] } }
        return top
    }
}

private extension Dictionary where Key == String, Value == Any {
    /// Kotlin twin: `JSONObject.arr`.
    func arr(_ k: String) -> [[String: Any]]? { (self[k] as? [Any])?.prefix(1_000_000).compactMap { $0 as? [String: Any] } }
    /// Kotlin twin: `JSONObject.obj`.
    func obj(_ k: String) -> [String: Any]? { self[k] as? [String: Any] }
    /// Kotlin twin: `JSONObject.str`.
    func str(_ k: String) -> String? { (self[k] as? String).flatMap { $0.isEmpty ? nil : $0 } }
    /// A strict `yyyy-MM-dd` day (a longer timestamp is cut to its date), else nil. Kotlin twin: `JSONObject.day`.
    func day(_ k: String) -> String? {
        guard let d = str(k).map({ String($0.prefix(10)) }),
              d.range(of: #"^\d{4}-(0[1-9]|1[0-2])-(0[1-9]|[12]\d|3[01])$"#, options: .regularExpression) != nil else { return nil }
        return d
    }
    /// Kotlin twin: `JSONObject.dbl`.
    func dbl(_ k: String) -> Double? {
        if let n = self[k] as? NSNumber {
            if CFGetTypeID(n) == CFBooleanGetTypeID() { return nil }
            let d = n.doubleValue; return d.isFinite ? d : nil
        }
        if let s = self[k] as? String, let d = Double(s), d.isFinite { return d }
        return nil
    }
    /// Kotlin twin: `JSONObject.pos`.
    func pos(_ k: String) -> Double? { dbl(k).flatMap { $0 > 0 ? $0 : nil } }
    /// Kotlin twin: `JSONObject.nonNeg`.
    func nonNeg(_ k: String) -> Double? { dbl(k).flatMap { $0 >= 0 ? $0 : nil } }
    /// Kotlin twin: `JSONObject.long`.
    func int64(_ k: String) -> Int64? { dbl(k).flatMap { $0 >= -9e18 && $0 <= 9e18 ? Int64($0) : nil } }
    /// Kotlin twin: `JSONObject.firstPos`.
    func firstPos(_ ks: String...) -> Double? { ks.lazy.compactMap { pos($0) }.first }
}
