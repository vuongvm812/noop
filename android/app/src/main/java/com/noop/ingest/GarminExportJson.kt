package com.noop.ingest

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Every health JSON a Garmin Connect "Export Your Data" ZIP carries beyond the daily rollups
 * [WearableExportImporter.parseGarmin] already reads: the rest of the daily summaries (UDSFile), the sleep
 * scores, the health-status baselines, the body / fitness / race-prediction / personal-record files, the
 * hydration log and the per-activity summaries (`summarizedActivities`). Pure and database-free; the
 * importer persists the result. Swift twin: `GarminExportJson` (StrandImport/GarminExportJson.swift) —
 * both emit the same canonical [dump], pinned by a shared golden in each platform's tests.
 *
 * Units are normalised here: grams to kg, centimetres to metres, milliseconds to seconds, kJ to kcal.
 * Every figure is UNTRUSTED input: numbers are read as finite doubles, and a value outside a plausible range
 * is dropped rather than stored. A field the export does not carry stays absent (honest data).
 */
internal object GarminExportJson {

    /** Import-report category ids (the UI localises them). Swift twin: `GarminExportJson.Category`. */
    object Category {
        const val DAILY = "daily_summary"
        const val SLEEP = "sleep"
        const val HEALTH_STATUS = "health_status"
        const val BODY = "body"
        const val FITNESS = "fitness"
        const val RACE = "race_predictions"
        const val RECORDS = "personal_records"
        const val HYDRATION = "hydration"
        const val ACTIVITIES = "activities"
        const val SETTINGS = "settings"
        val CATEGORY_ORDER: List<String> get() = listOf(DAILY, SLEEP, HEALTH_STATUS, BODY, FITNESS, RACE, RECORDS, HYDRATION, ACTIVITIES, SETTINGS)
    }

    /** Skip-reason ids of the import report. Swift twin: `GarminExportJson.Skip`. */
    object Skip {
        const val PRIVATE_FILE = "private_undecodable"
        const val ACCOUNT = "account_data"
        const val NO_VALUES = "no_values"
        const val UNRECOGNISED = "unrecognised"
        val REASON_ORDER: List<String> get() = listOf(PRIVATE_FILE, ACCOUNT, NO_VALUES, UNRECOGNISED)
    }

    data class Point(val day: String, val key: String, val value: Double)

    /** One `summarizedActivities` entry, units normalised. Swift twin: `GarminExportJson.Activity`. */
    data class Activity(
        val activityId: Long,
        val startTs: Long,
        val durationS: Double,
        val sport: String,
        val distanceM: Double?,
        val energyKcal: Double?,
        val avgHr: Int?,
        val maxHr: Int?,
        val steps: Int?,
        /** Time in HR zones 1-5 as percentages (sum 100), or null. */
        val zonePercents: List<Double>?,
        /** Time in power zones 1-5 as percentages (sum 100), or null. */
        val powerZonePercents: List<Double>?,
        val avgPowerW: Double?,
        val maxPowerW: Double?,
        val normPowerW: Double?,
        val cadenceSpm: Double?,
        val elevationGainM: Double?,
        val elevationLossM: Double?,
        val minElevationM: Double?,
        val maxElevationM: Double?,
        val aerobicTe: Double?,
        val anaerobicTe: Double?,
        val rpe: Int?,
        val feel: Int?,
        val bodyBatteryChange: Int?,
        val vo2max: Double?,
        val strokes: Int?,
        val swolf: Double?,
        val strideM: Double?,
        val groundContactMs: Double?,
        val verticalOscillationCm: Double?,
        val sweatLossMl: Double?,
        /** `{"z1":..,"z5":..}` HR-zone percentages in the flat shape the workout screens parse, or null. */
        val zonesJson: String? = null,
        /** The extras that have no column of their own, as a short English note (no leading label). */
        val noteExtras: String = "",
    ) {
        val endTs: Long get() = startTs + durationS.toLong()
    }

    /** Garmin's profile settings, offered (never applied silently). Swift twin: `GarminExportJson.Settings`. */
    data class Settings(val maxHr: Int?, val restingHr: Int?, val ftpW: Int?, val zoneFloors: List<Int>) {
        val isEmpty: Boolean get() = maxHr == null && restingHr == null && ftpW == null
    }

    class Result(
        val points: List<Point>,
        val activities: List<Activity>,
        val settings: Settings,
        /** category id -> records read, in [Category.CATEGORY_ORDER]. */
        val imported: List<Pair<String, Int>>,
        /** skip-reason id -> files/entries skipped, in [Skip.REASON_ORDER]. */
        val skipped: List<Pair<String, Int>>,
    )

    private const val MAX_POINTS = 2_000_000

    /** `source` of the per-activity summary rows (Training Effect, average power, cadence). */
    const val SOURCE_SUMMARY = "garmin-summary"

    /** File-role by lowercase base name; null = not one of ours. Swift twin: `GarminExportJson.role`. */
    private fun role(base: String): String? = when {
        base.contains("udsfile") -> "uds"
        base.contains("hydrationlogfile") -> "hydration"
        base.contains("sleepdata") -> "sleep"
        base.contains("healthstatusdata") -> "health"
        base.endsWith("userbiometrics.json") -> "bio"
        base.contains("fitnessagedata") -> "fitnessage"
        base.contains("racepredictions") -> "race"
        base.contains("personalrecord") -> "records"
        base.contains("summarizedactivities") -> "activities"
        base.contains("heartratezones") -> "hrzones"
        base.contains("powerzones") -> "powerzones"
        base.contains("biometrics_latest") || base.contains("userbiometricprofiledata") -> "profile"
        base.contains("maxmetdata") -> "maxmet"
        else -> null
    }

    /** True when [path] (lowercase) is a Garmin health file this parser or the daily parser reads. Swift twin: `GarminExportJson.isHealthFile`. */
    fun isHealthFile(path: String): Boolean = role(path.substringAfterLast('/')) != null

    /**
     * Why a file under the export is not imported: Garmin-private backups NOOP cannot decode, else account /
     * commerce / device data that is not health data. Swift twin: `GarminExportJson.skipReason`.
     */
    fun skipReason(path: String): String {
        val p = path.lowercase()
        return if (p.contains("lhabackup") || p.contains("primarytrainingbackup") || p.contains("devicebackups")) Skip.PRIVATE_FILE
        else Skip.ACCOUNT
    }

    /** Swift twin: `GarminExportJson.parse`. */
    fun parse(files: Map<String, ByteArray>, skippedPaths: List<String> = emptyList()): Result {
        val points = HashMap<String, Double>()           // "day|key" -> value (last write of a sorted file order wins)
        val activities = ArrayList<Activity>()
        val sweatByActivity = HashMap<Long, Double>()
        val counts = HashMap<String, Int>()
        var noValues = 0
        var unrecognised = 0
        var maxHr: Int? = null; var restingHr: Int? = null
        var ftp: Int? = null; var floors: List<Int> = emptyList()
        var bioFtp: Pair<String, Int>? = null

        val put = { day: String?, key: String, v: Double? ->
            if (day != null && v != null && v.isFinite() && points.size < MAX_POINTS) points["$day|$key"] = v
        }
        val count = { cat: String, n: Int -> counts[cat] = (counts[cat] ?: 0) + n }

        val hydrationDay = HashMap<String, Double>()
        val sweatDay = HashMap<String, Double>()
        val sleepRecords = ArrayList<JSONObject>()
        // Garmin splits files by date range; an entry repeated across two files counts once.
        val seenHydration = HashSet<String>(); val seenSleep = HashSet<String>(); val seenActivity = HashSet<Long>()

        for (name in files.keys.sortedWith(compareBy<String> { it.length }.thenBy { it })) {
            val data = files.getValue(name)
            val base = name.substringAfterLast('/')
            val r = role(base)
            if (r == null) {
                if (!name.endsWith(".csv")) unrecognised++
                continue
            }
            val records = records(data, r)
            when (r) {
                "uds" -> for (rec in records) {
                    val day = rec.day("calendarDate") ?: continue
                    count(Category.DAILY, 1)
                    put(day, "bmr_kcal", rec.pos("bmrKilocalories"))
                    put(day, "floors_ascended_m", rec.pos("floorsAscendedInMeters"))
                    put(day, "moderate_min", rec.pos("moderateIntensityMinutes"))
                    put(day, "vigorous_min", rec.pos("vigorousIntensityMinutes"))
                    put(day, "hr_min", rec.pos("minHeartRate")?.takeIf { it in 25.0..240.0 })
                    put(day, "max_hr", rec.pos("maxHeartRate")?.takeIf { it in 25.0..240.0 })
                    rec.obj("allDayStress")?.arr("aggregatorList")?.let(objects)?.firstOrNull { it.str("type") == "TOTAL" }?.let { t ->
                        put(day, "stress_max", t.pos("maxStressLevel")?.takeIf { it <= 100 })
                        put(day, "stress_low_min", t.pos("lowDuration")?.let { it / 60.0 })
                        put(day, "stress_medium_min", t.pos("mediumDuration")?.let { it / 60.0 })
                        put(day, "stress_high_min", t.pos("highDuration")?.let { it / 60.0 })
                    }
                    rec.obj("bodyBattery")?.let { bb ->
                        put(day, "body_battery_charged", bb.nonNeg("chargedValue"))
                        put(day, "body_battery_drained", bb.nonNeg("drainedValue"))
                        for (e in bb.arr("bodyBatteryStatList")?.let(objects).orEmpty()) {
                            val v = e.nonNeg("statsValue")?.takeIf { it <= 100 } ?: continue
                            when (e.str("bodyBatteryStatType")) {
                                "HIGHEST" -> put(day, "body_battery_high", v)
                                "LOWEST" -> put(day, "body_battery_low", v)
                            }
                        }
                    }
                    rec.obj("respiration")?.let { rs ->
                        put(day, "resp_rate", rs.pos("avgWakingRespirationValue")?.takeIf { it < 80 })
                        put(day, "resp_high", rs.pos("highestRespirationValue")?.takeIf { it < 80 })
                        put(day, "resp_low", rs.pos("lowestRespirationValue")?.takeIf { it < 80 })
                    }
                    (rec.obj("spo2") ?: rec.obj("pulseOx") ?: rec.obj("allDayPulseOx"))?.let { sp ->
                        put(day, "spo2", sp.firstPos("averageSpO2", "avgSpO2", "averageSpo2", "avgSpo2")?.takeIf { it <= 100 })
                        put(day, "spo2_min", sp.firstPos("lowestSpO2", "lowestSpo2", "minSpO2")?.takeIf { it <= 100 })
                    }
                }
                "hydration" -> for (rec in records) {
                    val day = rec.day("calendarDate") ?: continue
                    val hid = rec.obj("uuid")?.str("uuid") ?: "$day|${rec.str("timestampLocal")}|${rec.str("persistedTimestampGMT")}|${rec.dbl("valueInML")}|${rec.long("activityId")}"
                    if (!seenHydration.add(hid)) continue
                    count(Category.HYDRATION, 1)
                    val sweat = rec.pos("estimatedSweatLossInML")
                    if (sweat != null) {
                        sweatDay[day] = (sweatDay[day] ?: 0.0) + sweat
                        rec.long("activityId")?.takeIf { it > 0 }?.let { sweatByActivity[it] = (sweatByActivity[it] ?: 0.0) + sweat }
                    }
                    if (rec.str("hydrationSource") != "GARMIN_ACTIVITY") {
                        rec.pos("valueInML")?.let { hydrationDay[day] = (hydrationDay[day] ?: 0.0) + it }
                    }
                }
                "sleep" -> for (rec in records) {
                    if (rec.day("calendarDate") == null || listOf("deepSleepSeconds", "lightSleepSeconds", "remSleepSeconds", "awakeSleepSeconds").all { rec.dbl(it) == null }) continue
                    if (!seenSleep.add(rec.day("calendarDate") + "|" + rec.str("sleepStartTimestampGMT"))) continue
                    sleepRecords.add(rec)
                }
                "health" -> for (rec in records) {
                    val day = rec.day("calendarDate") ?: continue
                    count(Category.HEALTH_STATUS, 1)
                    for (m in rec.arr("metrics")?.let(objects).orEmpty()) {
                        // SKIN_TEMP_C is a signed deviation; every other health metric is positive.
                        val v = if (m.str("type") == "SKIN_TEMP_C") m.dbl("value") else m.pos("value")
                        if (v == null) { noValues++; continue }
                        when (m.str("type")) {
                            "HRV" -> { put(day, "hrv_baseline_low", m.pos("baselineLowerLimit")); put(day, "hrv_baseline_high", m.pos("baselineUpperLimit")) }
                            "HR" -> put(day, "hr_overnight", v)
                            "RESPIRATION" -> put(day, "resp_overnight", v)
                            "SPO2" -> put(day, "spo2", v.takeIf { it <= 100 })
                            "SKIN_TEMP_C" -> put(day, "skin_temp_dev_c", v)
                        }
                    }
                }
                "bio" -> for (rec in records) {
                    val day = rec.obj("metaData")?.day("calendarDate") ?: rec.day("calendarDate") ?: continue
                    var any = false
                    rec.pos("weight")?.let { g -> val kg = g / 1000.0; if (kg in 20.0..400.0) { put(day, "weight", kg); any = true } }
                    rec.pos("height")?.let { h ->
                        val m = if (h > 3) h / 100.0 else h
                        if (m in 0.5..2.6) { put(day, "height_m", m); any = true }
                    }
                    rec.pos("functionalThresholdPower")?.takeIf { it < 2000 }?.let { put(day, "ftp_w", it); any = true; if (bioFtp == null || day > bioFtp!!.first) bioFtp = day to it.toInt() }
                    rec.pos("vo2MaxRunning")?.takeIf { it < 100 }?.let { put(day, "vo2max", it); any = true }
                    rec.pos("lactateThresholdHeartRate")?.takeIf { it in 60.0..240.0 }?.let { put(day, "lactate_threshold_hr", it); any = true }
                    if (any) count(Category.BODY, 1)
                }
                "fitnessage" -> for (rec in records) {
                    val day = rec.day("asOfDateGmt") ?: continue
                    count(Category.FITNESS, 1)
                    put(day, "fitness_age", rec.pos("currentBioAge")?.takeIf { it < 120 })
                    put(day, "bmi", rec.pos("bmi")?.takeIf { it in 8.0..80.0 })
                    put(day, "vigorous_days", rec.nonNeg("totalVigorousDays"))
                }
                "race" -> for (rec in records) {
                    val day = rec.day("calendarDate") ?: continue
                    count(Category.RACE, 1)
                    put(day, "race_5k_s", rec.pos("raceTime5K")); put(day, "race_10k_s", rec.pos("raceTime10K"))
                    put(day, "race_half_s", rec.pos("raceTimeHalf")); put(day, "race_marathon_s", rec.pos("raceTimeMarathon"))
                }
                "records" -> for (rec in records) {
                    val type = rec.str("personalRecordType") ?: continue
                    val v = rec.dbl("value") ?: continue
                    val day = prDay(rec) ?: continue
                    count(Category.RECORDS, 1)
                    put(day, "pr_" + slug(type), v)
                }
                "activities" -> for (rec in records) {
                    val act = activity(rec) ?: continue
                    if (!seenActivity.add(act.activityId)) continue
                    count(Category.ACTIVITIES, 1)
                    activities.add(act)
                }
                "hrzones" -> {
                    val rec = records.firstOrNull { it.str("sport") == "DEFAULT" } ?: records.firstOrNull()
                    if (rec != null) {
                        maxHr = rec.pos("maxHeartRateUsed")?.takeIf { it in 100.0..240.0 }?.toInt() ?: maxHr
                        restingHr = rec.pos("restingHeartRateUsed")?.takeIf { it in 25.0..120.0 }?.toInt() ?: restingHr
                        floors = (1..5).mapNotNull { rec.pos("zone${it}Floor")?.takeIf { f -> f < 300 }?.toInt() }.let { if (it.size == 5) it else floors }
                    }
                }
                "powerzones" -> {
                    val order = listOf("DEFAULT", "CYCLING", "RUNNING")
                    val rec = records.filter { it.pos("functionalThresholdPower") != null }
                        .sortedBy { order.indexOf(it.str("sport")).let { i -> if (i < 0) order.size else i } }.firstOrNull()
                    rec?.pos("functionalThresholdPower")?.takeIf { it < 2000 }?.let { ftp = it.toInt() }
                }
                "profile" -> for (rec in records) {
                    rec.pos("functionalThresholdPower")?.takeIf { it < 2000 }?.let { if (ftp == null) ftp = it.toInt() }
                }
                "maxmet" -> for (rec in records) if (rec.day("calendarDate") != null && rec.pos("vo2MaxValue") != null) count(Category.FITNESS, 1)
            }
        }

        // Sleep: one main night per day (the longest asleep time, earliest start on a tie); further records of
        // the same day are naps. Swift twin: `GarminExportJson.parse` sleep block.
        val byDay = sleepRecords.groupBy { it.day("calendarDate")!! }
        for ((day, recs) in byDay) {
            val asleep = { r: JSONObject -> (r.dbl("deepSleepSeconds") ?: 0.0) + (r.dbl("lightSleepSeconds") ?: 0.0) + (r.dbl("remSleepSeconds") ?: 0.0) }
            val start = { r: JSONObject -> r.str("sleepStartTimestampGMT") ?: "" }
            val ordered = recs.sortedWith(compareByDescending<JSONObject> { asleep(it) }.thenBy { start(it) })
            val main = ordered.first()
            count(Category.SLEEP, recs.size)
            val scores = main.obj("sleepScores")
            put(day, "sleep_score_quality", scores?.pos("qualityScore")); put(day, "sleep_score_duration", scores?.pos("durationScore"))
            put(day, "sleep_score_recovery", scores?.pos("recoveryScore")); put(day, "sleep_score_deep", scores?.pos("deepScore"))
            put(day, "sleep_score_rem", scores?.pos("remScore")); put(day, "sleep_score_restfulness", scores?.pos("restfulnessScore"))
            put(day, "sleep_awake_count", main.nonNeg("awakeCount")); put(day, "sleep_restless_moments", main.nonNeg("restlessMomentCount"))
            put(day, "sleep_stress", main.pos("avgSleepStress")?.takeIf { it <= 100 })
            put(day, "sleep_resp_low", main.pos("lowestRespiration")?.takeIf { it < 80 }); put(day, "sleep_resp_high", main.pos("highestRespiration")?.takeIf { it < 80 })
            put(day, "sleep_awake_min", main.pos("awakeSleepSeconds")?.let { it / 60.0 })
            if (ordered.size > 1) put(day, "nap_min", ordered.drop(1).sumOf { asleep(it) } / 60.0)
        }
        for ((day, v) in hydrationDay) put(day, "hydration_ml", v)
        for ((day, v) in sweatDay) put(day, "sweat_loss_ml", v)

        val settings = Settings(maxHr, restingHr, ftp ?: bioFtp?.second, floors)
        if (!settings.isEmpty) count(Category.SETTINGS, 1)

        val acts = activities.map { a -> sweatByActivity[a.activityId]?.let { a.copy(sweatLossMl = it) } ?: a }
            .map { a -> a.copy(zonesJson = zonesJson(a.zonePercents), noteExtras = noteExtras(a)) }
            .sortedWith(compareBy<Activity> { it.startTs }.thenBy { it.activityId })
        val skipCounts = HashMap<String, Int>()
        for (p in skippedPaths) skipCounts.merge(skipReason(p), 1, Int::plus)
        if (noValues > 0) skipCounts.merge(Skip.NO_VALUES, noValues, Int::plus)
        if (unrecognised > 0) skipCounts.merge(Skip.UNRECOGNISED, unrecognised, Int::plus)

        return Result(
            points = points.map { (k, v) -> Point(k.substringBefore('|'), k.substringAfter('|'), v) }
                .sortedWith(compareBy<Point> { it.day }.thenBy { it.key }),
            activities = acts,
            settings = settings,
            imported = Category.CATEGORY_ORDER.mapNotNull { c -> counts[c]?.takeIf { it > 0 }?.let { c to it } },
            skipped = Skip.REASON_ORDER.mapNotNull { s -> skipCounts[s]?.takeIf { it > 0 }?.let { s to it } },
        )
    }

    // ---- activity ----------------------------------------------------------------------------------

    /** Swift twin: `GarminExportJson.activity`. */
    private fun activity(r: JSONObject): Activity? {
        val id = r.long("activityId") ?: return null
        val startMs = r.dbl("startTimeGmt") ?: r.dbl("beginTimestamp") ?: return null
        val startTs = if (startMs >= 1e11) (startMs / 1000.0).toLong() else startMs.toLong()
        val durationS = (r.pos("duration") ?: r.pos("elapsedDuration") ?: return null) / 1000.0
        if (durationS < 1 || durationS > 7 * 86_400.0 || startMs <= 0 || startMs >= 9e15) return null
        val zones = (1..5).map { r.nonNeg("hrTimeInZone_$it") ?: 0.0 }
        val pz = (1..5).map { r.nonNeg("powerTimeInZone_$it") ?: 0.0 }
        val pct = { z: List<Double> ->
            val t = z.sum()
            if (t <= 0) null else z.map { Math.round(it / t * 10_000.0) / 100.0 }
        }
        val sport = ActivityFileImporter.workoutSport(r.str("activityType") ?: r.str("sportType")?.lowercase())
        return Activity(
            activityId = id, startTs = startTs, durationS = durationS, sport = sport,
            distanceM = r.pos("distance")?.let { it / 100.0 },
            energyKcal = r.pos("calories")?.let { it / 4.184 },
            avgHr = r.pos("avgHr")?.takeIf { it in 25.0..250.0 }?.toInt(),
            maxHr = r.pos("maxHr")?.takeIf { it in 25.0..250.0 }?.toInt(),
            steps = r.pos("steps")?.takeIf { it < 1_000_000 }?.toInt(),
            zonePercents = pct(zones), powerZonePercents = pct(pz),
            avgPowerW = r.pos("avgPower")?.takeIf { it < 3000 }, maxPowerW = r.pos("maxPower")?.takeIf { it < 5000 },
            normPowerW = r.pos("normPower")?.takeIf { it < 3000 },
            cadenceSpm = (r.pos("avgDoubleCadence") ?: r.pos("avgBikeCadence"))?.takeIf { it < 300 },
            elevationGainM = r.nonNeg("elevationGain")?.let { it / 100.0 }?.takeIf { it < 20_000 }, elevationLossM = r.nonNeg("elevationLoss")?.let { it / 100.0 }?.takeIf { it < 20_000 },
            minElevationM = r.dbl("minElevation")?.let { it / 100.0 }?.takeIf { Math.abs(it) < 20_000 }, maxElevationM = r.dbl("maxElevation")?.let { it / 100.0 }?.takeIf { Math.abs(it) < 20_000 },
            aerobicTe = r.pos("aerobicTrainingEffect")?.takeIf { it <= 5.0 }, anaerobicTe = r.pos("anaerobicTrainingEffect")?.takeIf { it <= 5.0 },
            rpe = r.pos("workoutRpe")?.let { Math.round(it / 10.0).toInt() }?.takeIf { it in 1..10 },
            feel = r.nonNeg("workoutFeel")?.toInt()?.takeIf { it <= 100 },
            bodyBatteryChange = r.dbl("differenceBodyBattery")?.toInt()?.takeIf { it in -100..100 },
            vo2max = (r.pos("vO2MaxValue"))?.takeIf { it < 100 },
            strokes = r.pos("strokes")?.takeIf { it < 1_000_000 }?.toInt(), swolf = r.pos("avgSwolf")?.takeIf { it < 300 },
            strideM = r.pos("avgStrideLength")?.let { it / 100.0 }?.takeIf { it < 5 },
            groundContactMs = r.pos("avgGroundContactTime")?.takeIf { it < 2000 },
            verticalOscillationCm = r.pos("avgVerticalOscillation")?.takeIf { it < 50 },
            sweatLossMl = null,
        )
    }

    /** The day a personal record was set: `prStartTimeGMT` ("Sat Oct 24 17:00:00 GMT 2026") else `createdDate`. Swift twin: `GarminExportJson.prDay`. */
    private fun prDay(r: JSONObject): String? {
        r.str("prStartTimeGMT")?.let { s ->
            val m = Regex("""^\w{3} (\w{3}) (\d{1,2}) \d{2}:\d{2}:\d{2} \w+ (\d{4})$""").find(s.trim())
            if (m != null) {
                val mon = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec").indexOf(m.groupValues[1].lowercase())
                if (mon >= 0 && m.groupValues[2].toInt() in 1..31) return String.format(Locale.US, "%04d-%02d-%02d", m.groupValues[3].toInt(), mon + 1, m.groupValues[2].toInt())
            }
        }
        return r.day("createdDate")
    }

    /** Lowercase `[a-z0-9]` words joined by `_` ("Most Steps in a Day" -> "most_steps_in_a_day"). Swift twin: `GarminExportJson.slug`. */
    internal fun slug(s: String): String {
        val out = StringBuilder(); var gap = false
        for (c in s.lowercase()) {
            if (c in 'a'..'z' || c in '0'..'9') { if (gap && out.isNotEmpty()) out.append('_'); gap = false; out.append(c) } else gap = true
        }
        return out.toString().take(48)
    }


    /** A decimal with up to 4 places, half away from zero, trailing zeros trimmed. Swift twin: `GarminExportJson.num`. */
    internal fun num(v: Double): String {
        val n = Math.floor(Math.abs(v) * 10_000.0 + 0.5).toLong()
        val sign = if (v < 0 && n != 0L) "-" else ""
        val frac = (n % 10_000).toString().padStart(4, '0').trimEnd('0')
        return sign + (n / 10_000) + if (frac.isEmpty()) "" else ".$frac"
    }

    /** `{"z1":..,"z5":..}` zone percentages in the flat shape the workout screens parse, or null. Swift twin: `GarminExportJson.zonesJson`. */
    internal fun zonesJson(p: List<Double>?): String? =
        p?.takeIf { it.size == 5 }?.let { z -> z.mapIndexed { i, v -> "\"z${i + 1}\":${num(v)}" }.joinToString(",", "{", "}") }

    /** The summary's extras that have no column of their own, as a short English note (like the FIT import note). Swift twin: `GarminExportJson.noteExtras`. */
    internal fun noteExtras(a: Activity): String {
        val parts = ArrayList<String>()
        a.normPowerW?.let { parts.add("normalized power ${it.toInt()} W") }
        a.maxPowerW?.let { parts.add("max power ${it.toInt()} W") }
        if (a.elevationGainM != null || a.elevationLossM != null)
            parts.add("elevation +${(a.elevationGainM ?: 0.0).toInt()} m / -${(a.elevationLossM ?: 0.0).toInt()} m")
        a.rpe?.let { parts.add("RPE $it") }
        a.feel?.let { parts.add("feel $it/100") }
        a.bodyBatteryChange?.let { parts.add("Body Battery $it") }
        a.vo2max?.let { parts.add("VO2 max ${num(it)}") }
        a.strokes?.let { parts.add("$it strokes") }
        a.swolf?.let { parts.add("SWOLF ${it.toInt()}") }
        a.strideM?.let { parts.add("stride ${num(it)} m") }
        a.groundContactMs?.let { parts.add("ground contact ${it.toInt()} ms") }
        a.verticalOscillationCm?.let { parts.add("vertical oscillation ${num(it)} cm") }
        a.sweatLossMl?.let { parts.add("sweat loss ${it.toInt()} ml") }
        return parts.joinToString(" · ")
    }


    // ---- JSON helpers (finite doubles only; org.json saturates hostile values) -----------------------

    /** The file's records: a bare array, a one-element wrapper array / object holding the real list, or a single object. Swift twin: `GarminExportJson.records`. */
    private fun records(data: ByteArray, role: String): List<JSONObject> {
        val text = String(Bom.stripUtf8(data), Charsets.UTF_8)
        val arr = runCatching { JSONArray(text) }.getOrNull()
        val top: List<JSONObject> = if (arr != null) arr.let(objects) else runCatching { JSONObject(text) }.getOrNull()?.let { listOf(it) } ?: return emptyList()
        // Wrapper shapes: [{"summarizedActivitiesExport":[...]}], [{"personalRecords":[...]}], {"key":[...]}.
        val wrapperKey = when (role) { "activities" -> "summarizedActivitiesExport"; "records" -> "personalRecords"; else -> null }
        if (wrapperKey != null) return top.flatMap { it.arr(wrapperKey)?.let(objects).orEmpty() }
        return top
    }

    private val objects = { a: JSONArray -> (0 until minOf(a.length(), 1_000_000)).mapNotNull { a.optJSONObject(it) } }
    /** Swift twin: `Dictionary.arr`. */
    private fun JSONObject.arr(k: String): JSONArray? = if (has(k)) optJSONArray(k) else null
    /** Swift twin: `Dictionary.obj`. */
    private fun JSONObject.obj(k: String): JSONObject? = if (has(k)) optJSONObject(k) else null
    /** Swift twin: `Dictionary.str`. */
    private fun JSONObject.str(k: String): String? = (opt(k) as? String)?.ifEmpty { null }

    /** A strict `yyyy-MM-dd` day (a longer timestamp is cut to its date), else null. Swift twin: `Dictionary.day`. */
    private fun JSONObject.day(k: String): String? =
        str(k)?.take(10)?.takeIf { Regex("""\d{4}-(0[1-9]|1[0-2])-(0[1-9]|[12]\d|3[01])""").matches(it) }
    /** Swift twin: `Dictionary.dbl`. */
    private fun JSONObject.dbl(k: String): Double? = if (has(k) && !isNull(k)) optDouble(k).takeIf { it.isFinite() } else null
    /** Swift twin: `Dictionary.pos`. */
    private fun JSONObject.pos(k: String): Double? = dbl(k)?.takeIf { it > 0 }
    /** Swift twin: `Dictionary.nonNeg`. */
    private fun JSONObject.nonNeg(k: String): Double? = dbl(k)?.takeIf { it >= 0 }
    /** Swift twin: `Dictionary.int64`. */
    private fun JSONObject.long(k: String): Long? = dbl(k)?.takeIf { it >= -9e18 && it <= 9e18 }?.toLong()
    /** Swift twin: `Dictionary.firstPos`. */
    private fun JSONObject.firstPos(vararg ks: String): Double? = ks.firstNotNullOfOrNull { pos(it) }


}
