package com.noop.ingest

/**
 * Garmin wellness FIT decoding: the Kotlin twin of Swift `GarminWellness.swift`, held byte-identical
 * by an oracle test (`GarminWellnessParserTest`).
 *
 * A Garmin watch records its own 24/7 data as FIT files (monitoring, sleep, HRV status, metrics, skin
 * temperature); the same files sit inside a Garmin Connect GDPR export. This decoder turns their
 * health messages into [GarminWellnessSamples], a pure model with no store dependency.
 *
 * Message and field numbers, scales and units are protocol facts re-derived (no code, literals or
 * fixtures copied) from Gadgetbridge's FIT profile and importer (AGPLv3, at edbea98c), per the
 * facts-vs-code rule in docs/CONTRIBUTING.md. The per-message field table is in the Swift file's
 * header. In short: 55 MONITORING, 103 MONITORING_INFO, 211 MONITORING_HR_DATA, 227 STRESS_LEVEL
 * (+ Body Battery), 269 SPO2_DATA, 273/274/275 sleep info / raw rows / levels, 297 RESPIRATION_RATE,
 * 346 SLEEP_ASSESSMENT, 370 HRV_STATUS_SUMMARY, 371 HRV_VALUE.
 *
 * CANDIDATES: 290 BEAT_INTERVALS, 397 SKIN_TEMP_RAW and 398 SKIN_TEMP_OVERNIGHT are declared but not
 * imported by Gadgetbridge, so their scales and units are unconfirmed. They decode raw into the
 * `*Candidate` fields and must not back a shipped metric until checked against the watch.
 *
 * Plausible ranges (#52): SpO2 70-100 %, respiration 4-60 breaths/min (raw 400-6000), HRV values 5-300 ms
 * (raw 640-38400); a value outside is dropped. Same bounds as `GarminSyncStore.GARMIN_SPO2_MIN` etc.
 *
 * Timestamps: only field 253 and compressed-timestamp headers move the running timestamp. MONITORING
 * may carry `timestamp_16`, resolved forward from the previous monitoring time across a 0xFFFF
 * rollover. Cumulative MONITORING counters reset at local midnight per activity type; a day's total is
 * the sum over types of each type's largest value that day ([GarminWellnessParser.dailyTotals]), steps
 * over the step types only ([GarminWellnessParser.STEP_ACTIVITY_TYPES]); a record stamped exactly at local
 * midnight still carries the day that just ended. `calories` (field 1) is the day's TOTAL energy, resting
 * metabolic rate included; `active_calories` (field 19) is the part above it.
 */
data class GarminWellnessSamples(
    /** Local time minus UTC, in seconds, from the file's own local timestamps; null when it has none. */
    val utcOffsetS: Int? = null,
    val monitoring: List<Monitoring> = emptyList(),
    /** Folded with [utcOffsetS], or UTC when the file carries no offset. */
    val dailyActivity: List<DailyActivity> = emptyList(),
    val restingMetabolicRate: List<TimedInt> = emptyList(),
    val restingHeartRate: List<TimedInt> = emptyList(),
    val stress: List<TimedInt> = emptyList(),
    val bodyBattery: List<TimedInt> = emptyList(),
    val spo2: List<Spo2> = emptyList(),
    val respiration: List<TimedDouble> = emptyList(),
    val sleepLevels: List<TimedInt> = emptyList(),
    /** The watch's sleep window markers (EVENT 74), in file order: value 0 is a start, 1 a stop. */
    val sleepMarks: List<TimedInt> = emptyList(),
    val sleepSessions: List<SleepSession> = emptyList(),
    val sleepRaw: List<SleepRaw> = emptyList(),
    val sleepAssessments: List<SleepAssessment> = emptyList(),
    val hrvSummaries: List<HrvSummary> = emptyList(),
    val hrvValues: List<TimedDouble> = emptyList(),
    val beatIntervalsCandidate: List<BeatIntervalsCandidate> = emptyList(),
    val skinTempRawCandidate: List<TimedDouble> = emptyList(),
    val skinTempOvernightCandidate: List<SkinTempOvernightCandidate> = emptyList(),
    /** Data messages this decoder does not read (file_id, device_info, events, unknown numbers). */
    val otherMessages: Int = 0,
    /** Health messages dropped because they had no usable timestamp or value. */
    val droppedMessages: Int = 0,
    /** False when the walk stopped early on a malformed or truncated record. */
    val complete: Boolean = true,
) {
    data class TimedInt(val ts: Long, val value: Int)
    data class TimedDouble(val ts: Long, val value: Double)

    /** One MONITORING record. Cumulative fields are as stored (running totals per activity type). */
    data class Monitoring(
        val ts: Long,
        val activityType: Int?,
        val intensity: Int?,
        /** Raw `cycles`: the cumulative step count for walking/running activity types. */
        val cycles: Int?,
        val distanceM: Double?,
        val calories: Int?,
        val activeCalories: Int?,
        val activeTimeS: Double?,
        val heartRate: Int?,
        val moderateMin: Int?,
        val vigorousMin: Int?,
    )

    /** One activity type's largest cumulative MONITORING counters on one local day; type -1 = none given. */
    data class DailyTypePeak(
        val day: String,
        val activityType: Int,
        val cycles: Int?,
        val distanceM: Double?,
        val calories: Int?,
        val activeCalories: Int?,
    )
    data class DailyActivity(val day: String, val steps: Int?, val distanceM: Double?, val calories: Int?, val activeCalories: Int?)
    data class Spo2(val ts: Long, val percent: Int)
    data class SleepRaw(val ts: Long, val heartRate: Double?, val movement: Double?)

    /**
     * A night folded from SLEEP_LEVEL rows over the watch's sleep window. Each row closes the span since
     * the previous row, the first one the span since `start`.
     */
    data class SleepSession(
        val start: Long, val end: Long,
        val unmeasurableS: Long, val awakeS: Long, val lightS: Long, val deepS: Long, val remS: Long,
    )

    data class SleepAssessment(
        val ts: Long,
        val overallScore: Int,
        val combinedAwakeScore: Int?,
        val awakeTimeScore: Int?,
        val awakeningsCountScore: Int?,
        val deepSleepScore: Int?,
        val sleepDurationScore: Int?,
        val lightSleepScore: Int?,
        val sleepQualityScore: Int?,
        val sleepRecoveryScore: Int?,
        val remSleepScore: Int?,
        val restlessnessScore: Int?,
        val awakeningsCount: Int?,
        val interruptionsScore: Int?,
        val averageStress: Double?,
    )

    data class HrvSummary(
        val ts: Long,
        val weeklyAverageMs: Double?,
        val lastNightAverageMs: Double?,
        val lastNight5MinHighMs: Double?,
        val baselineLowUpperMs: Double?,
        val baselineBalancedLowerMs: Double?,
        val baselineBalancedUpperMs: Double?,
        val status: Int?,
    )

    /** CANDIDATE (290): scale unconfirmed; raw values only. */
    data class BeatIntervalsCandidate(val ts: Long, val timestampMs: Int?, val intervalsMs: List<Int>)

    /** CANDIDATE (398): units unconfirmed; raw float values only. */
    data class SkinTempOvernightCandidate(
        val ts: Long,
        val averageDeviation: Double?,
        val average7DayDeviation: Double?,
        val calibratedDays: Int?,
        val nightlyValue: Double?,
    )

    val isEmpty: Boolean
        get() = monitoring.isEmpty() && restingMetabolicRate.isEmpty() && restingHeartRate.isEmpty() &&
            stress.isEmpty() && bodyBattery.isEmpty() && spo2.isEmpty() && respiration.isEmpty() &&
            sleepLevels.isEmpty() && sleepRaw.isEmpty() && sleepAssessments.isEmpty() && hrvSummaries.isEmpty() &&
            hrvValues.isEmpty() && beatIntervalsCandidate.isEmpty() && skinTempRawCandidate.isEmpty() &&
            skinTempOvernightCandidate.isEmpty()
}

object GarminWellnessParser {

    /**
     * Global message numbers whose scale or unit is unconfirmed on hardware; they decode only into
     * the `*Candidate` fields.
     */
    val candidateMessages: Set<Int> get() = setOf(290, 397, 398)

    /**
     * FIT activity types whose MONITORING `cycles` counter is a step count: generic (0), running (1),
     * walking (6), sedentary (8), and a record with no type (-1). Cycling (2), swimming (5) and fitness
     * equipment (4) count revolutions, strokes or repetitions in the same field; transition (3) and the
     * "all" summary type (254) would double-count a sport's own counter (#51).
     * Swift twin: `GarminWellnessParser.stepActivityTypes`.
     */
    val STEP_ACTIVITY_TYPES: Set<Int> = setOf(-1, 0, 1, 6, 8)

    /**
     * Folds cumulative MONITORING counters into per-(local day, activity type) peaks: each counter's
     * largest value. A record stamped exactly at local midnight carries the day that just ended (the
     * watch writes its final totals at 00:00:00 before the counters reset), so it counts toward the
     * previous day. The counters only rise within a day, so the largest value is the type's day total
     * even when records arrive out of order. Sorted by day, then activity type.
     * Swift twin: `GarminWellnessParser.dailyTypePeaks`.
     */
    fun dailyTypePeaks(records: List<GarminWellnessSamples.Monitoring>, utcOffsetS: Int): List<GarminWellnessSamples.DailyTypePeak> {
        class Peak { var cycles: Int? = null; var distance: Double? = null; var calories: Int? = null; var active: Int? = null }
        val byDay = HashMap<String, HashMap<Int, Peak>>()
        for (r in records) {
            val atMidnight = (r.ts + utcOffsetS) % 86_400 == 0L
            val types = byDay.getOrPut(localDay(if (atMidnight) r.ts - 1 else r.ts, utcOffsetS)) { HashMap() }
            val peak = types.getOrPut(r.activityType ?: -1) { Peak() }
            r.cycles?.let { peak.cycles = maxOf(peak.cycles ?: it, it) }
            r.distanceM?.let { peak.distance = maxOf(peak.distance ?: it, it) }
            r.calories?.let { peak.calories = maxOf(peak.calories ?: it, it) }
            r.activeCalories?.let { peak.active = maxOf(peak.active ?: it, it) }
        }
        return byDay.keys.sorted().flatMap { day ->
            byDay.getValue(day).entries.sortedBy { it.key }.map { (type, p) ->
                GarminWellnessSamples.DailyTypePeak(day, type, p.cycles, p.distance, p.calories, p.active)
            }
        }
    }

    /**
     * Local-day totals: the sum over activity types of each type's peak ([dailyTypePeaks]). Steps sum
     * only [STEP_ACTIVITY_TYPES]. Swift twin: `GarminWellnessParser.dailyTotals`.
     */
    fun dailyTotals(records: List<GarminWellnessSamples.Monitoring>, utcOffsetS: Int): List<GarminWellnessSamples.DailyActivity> {
        val byDay = dailyTypePeaks(records, utcOffsetS).groupBy { it.day }
        return byDay.keys.sorted().map { day ->
            // Sum in activity-type order so the floating-point total is platform-independent.
            val peaks = byDay.getValue(day)
            val steps = peaks.filter { it.activityType in STEP_ACTIVITY_TYPES }.mapNotNull { it.cycles }
            val distances = peaks.mapNotNull { it.distanceM }
            val calories = peaks.mapNotNull { it.calories }
            val active = peaks.mapNotNull { it.activeCalories }
            GarminWellnessSamples.DailyActivity(
                day = day,
                steps = if (steps.isEmpty()) null else steps.sum(),
                distanceM = if (distances.isEmpty()) null else distances.fold(0.0) { a, b -> a + b },
                calories = if (calories.isEmpty()) null else calories.sum(),
                activeCalories = if (active.isEmpty()) null else active.sum(),
            )
        }
    }

    /** Rows further apart than this, with no sleep window around them, are two nights. */
    const val FALLBACK_NIGHT_GAP_S = 4 * 3600L

    /**
     * The lowest SPO2_DATA mode kept: 0 (off wrist) and 1 (a manual spot check) are not background
     * readings (#52). Swift twin: `GarminWellnessParser.spo2MinBackgroundMode`.
     */
    const val SPO2_MIN_BACKGROUND_MODE = 2

    /**
     * Folds a file's SLEEP_LEVEL rows ([GarminWellnessSamples.sleepLevels]) into nights. A night is the
     * watch's own sleep window (a start marker, then the next stop in [GarminWellnessSamples.sleepMarks]; a start with no stop ends at its last row), holding the rows after its start up to
     * its stop: the first row closes the span from sleep onset, each later row the span since the row
     * before. Rows outside every window fall back to splitting on a gap over [FALLBACK_NIGHT_GAP_S] or
     * time running backwards, each such night starting at its first row.
     * Swift twin: `GarminWellnessParser.sleepSessions`.
     */
    fun sleepSessions(samples: GarminWellnessSamples): List<GarminWellnessSamples.SleepSession> {
        val levels = samples.sleepLevels
        val marks = samples.sleepMarks
        val windows = ArrayList<Pair<Long, Long?>>()
        var open: Long? = null
        for (m in marks) {
            val start = open
            if (m.value == 0) {
                if (start == null) open = m.ts
            } else if (m.value == 1 && start != null && m.ts > start) {
                windows.add(start to m.ts)
                open = null
            }
        }
        open?.let { windows.add(it to null) }

        val nights = ArrayList<GarminWellnessSamples.SleepSession>()
        val used = BooleanArray(levels.size)
        for ((start, stop) in windows) {
            val rows = levels.indices.filter { i ->
                !used[i] && levels[i].ts > start && (stop == null || levels[i].ts <= stop)
            }
            if (rows.isEmpty()) continue
            for (i in rows) used[i] = true
            nights.add(session(levels, rows, start, stop ?: levels[rows.last()].ts))
        }

        val runs = ArrayList<ArrayList<Int>>()
        for (i in levels.indices) {
            if (used[i]) continue
            val prev = runs.lastOrNull()?.last()
            if (prev != null && levels[i].ts >= levels[prev].ts && levels[i].ts - levels[prev].ts <= FALLBACK_NIGHT_GAP_S) {
                runs.last().add(i)
            } else {
                runs.add(arrayListOf(i))
            }
        }
        for (run in runs) nights.add(session(levels, run, levels[run.first()].ts, levels[run.last()].ts))
        return nights.sortedWith(compareBy({ it.start }, { it.end }))
    }

    /** One night's stage totals: each row credits its level with the time since the previous boundary.
     *  Swift twin: `GarminWellnessParser.session`. */
    private fun session(
        levels: List<GarminWellnessSamples.TimedInt>,
        rows: List<Int>,
        start: Long,
        end: Long,
    ): GarminWellnessSamples.SleepSession {
        val spans = LongArray(5)
        var prev = start
        for (i in rows) {
            if (levels[i].ts <= prev) continue
            spans[levels[i].value] += levels[i].ts - prev
            prev = levels[i].ts
        }
        return GarminWellnessSamples.SleepSession(start, end, spans[0], spans[1], spans[2], spans[3], spans[4])
    }

    /** Seconds between the FIT epoch (1989-12-31T00:00:00Z) and the Unix epoch. */
    private const val FIT_EPOCH_OFFSET = 631_065_600L
    /** Upper sanity bound for a decoded time (2100-01-01T00:00:00Z). */
    private const val MAX_UNIX = 4_102_444_800L
    /** The widest real UTC offset is +14:00; anything beyond is garbage. */
    private const val MAX_UTC_OFFSET_S = 14 * 3600L

    /**
     * Decodes every health message of a FIT file. Never throws: a malformed file yields what was read
     * before the fault, with `complete == false`.
     */
    fun parse(data: ByteArray): GarminWellnessSamples {
        val stream = FitRecordStream.open(data) ?: return GarminWellnessSamples(complete = false)
        val d = Decoder(stream)
        val stop = stream.walk(FitRecordStream.MAX_RECORDS) { def, offset, compressed -> d.consume(def, offset, compressed) }
        val samples = GarminWellnessSamples(
            utcOffsetS = d.utcOffsetS,
            monitoring = d.monitoring,
            dailyActivity = dailyTotals(d.monitoring, d.utcOffsetS ?: 0),
            restingMetabolicRate = d.restingMetabolicRate,
            restingHeartRate = d.restingHeartRate,
            stress = d.stress,
            bodyBattery = d.bodyBattery,
            spo2 = d.spo2,
            respiration = d.respiration,
            sleepLevels = d.sleepLevels,
            sleepMarks = d.sleepMarks,
            sleepRaw = d.sleepRaw,
            sleepAssessments = d.sleepAssessments,
            hrvSummaries = d.hrvSummaries,
            hrvValues = d.hrvValues,
            beatIntervalsCandidate = d.beatIntervals,
            skinTempRawCandidate = d.skinTempRaw,
            skinTempOvernightCandidate = d.skinTempOvernight,
            otherMessages = d.otherMessages,
            droppedMessages = d.droppedMessages,
            complete = stop == FitRecordStream.Stop.END,
        )
        return samples.copy(sleepSessions = sleepSessions(samples))
    }

    /**
     * The local calendar day ("yyyy-MM-dd") of a Unix time at a fixed UTC offset. Pure arithmetic
     * (civil-from-days) so both platforms agree without a time-zone database.
     */
    fun localDay(ts: Long, utcOffsetS: Int): String {
        val t = ts + utcOffsetS
        val days = if (t >= 0) t / 86_400 else (t - 86_399) / 86_400
        val z = days + 719_468
        val era = (if (z >= 0) z else z - 146_096) / 146_097
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val y = yoe + era * 400 + if (m <= 2) 1 else 0
        return "${y.toString().padStart(4, '0')}-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}"
    }

    /** IEEE 754 half precision to Double; null for infinities and NaN. */
    fun halfToDouble(bits: Int): Double? {
        val sign = if (bits and 0x8000 != 0) -1.0 else 1.0
        val exponent = (bits shr 10) and 0x1F
        val mantissa = (bits and 0x3FF).toDouble()
        return when (exponent) {
            0 -> sign * mantissa * Math.scalb(1.0, -24)
            31 -> null
            else -> sign * (1 + mantissa / 1024) * Math.scalb(1.0, exponent - 15)
        }
    }

    /** One data message's fields, read by field number with FIT invalid sentinels rejected. */
    private class Message(val stream: FitRecordStream, val bigEndian: Boolean, val fields: Map<Int, Pair<Int, Int>>) {
        fun u(num: Int): Long? {
            val (off, size) = fields[num] ?: return null
            if (size > 4) return null
            val v = stream.uint(off, size, bigEndian) ?: return null
            return if (v == (1L shl (size * 8)) - 1) null else v
        }

        fun s(num: Int): Long? {
            val (off, size) = fields[num] ?: return null
            if (size > 4) return null
            val v = stream.int(off, size, bigEndian) ?: return null
            return if (v == (1L shl (size * 8 - 1)) - 1) null else v
        }

        fun f32(num: Int): Double? {
            val (off, size) = fields[num] ?: return null
            if (size != 4) return null
            val v = stream.uint(off, 4, bigEndian) ?: return null
            if (v == 0xFFFF_FFFFL) return null
            val d = java.lang.Float.intBitsToFloat(v.toInt()).toDouble()
            return if (d.isFinite()) d else null
        }

        /** An unsigned field as Int, or null. */
        fun value(num: Int): Int? = u(num)?.toInt()

        /** An unsigned field divided by its FIT scale, or null. */
        fun scaled(num: Int, scale: Double): Double? = u(num)?.let { it.toDouble() / scale }

        fun bytes(num: Int): ByteArray? {
            val (off, size) = fields[num] ?: return null
            if (off + size > stream.end) return null
            return stream.bytes.copyOfRange(off, off + size)
        }
    }

    private class Decoder(val stream: FitRecordStream) {
        /** The file's running timestamp in FIT seconds (field 253 and compressed headers). */
        var running: Long? = null
        var lastMonitoring: Long? = null
        var rawStart: Long? = null
        var rawStep = 60L
        var rawSlot = 0L

        var utcOffsetS: Int? = null
        val monitoring = ArrayList<GarminWellnessSamples.Monitoring>()
        val restingMetabolicRate = ArrayList<GarminWellnessSamples.TimedInt>()
        val restingHeartRate = ArrayList<GarminWellnessSamples.TimedInt>()
        val stress = ArrayList<GarminWellnessSamples.TimedInt>()
        val bodyBattery = ArrayList<GarminWellnessSamples.TimedInt>()
        val spo2 = ArrayList<GarminWellnessSamples.Spo2>()
        val respiration = ArrayList<GarminWellnessSamples.TimedDouble>()
        val sleepLevels = ArrayList<GarminWellnessSamples.TimedInt>()
        val sleepMarks = ArrayList<GarminWellnessSamples.TimedInt>()
        val sleepRaw = ArrayList<GarminWellnessSamples.SleepRaw>()
        val sleepAssessments = ArrayList<GarminWellnessSamples.SleepAssessment>()
        val hrvSummaries = ArrayList<GarminWellnessSamples.HrvSummary>()
        val hrvValues = ArrayList<GarminWellnessSamples.TimedDouble>()
        val beatIntervals = ArrayList<GarminWellnessSamples.BeatIntervalsCandidate>()
        val skinTempRaw = ArrayList<GarminWellnessSamples.TimedDouble>()
        val skinTempOvernight = ArrayList<GarminWellnessSamples.SkinTempOvernightCandidate>()
        var otherMessages = 0
        var droppedMessages = 0

        private fun unix(fit: Long?): Long? {
            if (fit == null || fit <= 0) return null
            val unix = fit + FIT_EPOCH_OFFSET
            return if (unix < MAX_UNIX) unix else null
        }

        private fun range(v: Long?, lo: Long, hi: Long): Int? = if (v != null && v in lo..hi) v.toInt() else null

        private fun noteOffset(local: Long?, fitTs: Long?) {
            if (utcOffsetS != null || local == null || fitTs == null) return
            val offset = local - fitTs
            if (kotlin.math.abs(offset) <= MAX_UTC_OFFSET_S) utcOffsetS = offset.toInt()
        }

        private fun drop() { droppedMessages++ }

        fun consume(def: FitRecordStream.Definition, start: Int, compressed: Int?) {
            val fields = HashMap<Int, Pair<Int, Int>>()
            var offset = start
            for (f in def.fields) {
                if (!fields.containsKey(f.num)) fields[f.num] = offset to f.size
                offset += f.size
            }
            val m = Message(stream, def.bigEndian, fields)

            val last = running
            if (compressed != null && last != null) running = last + ((compressed - (last and 0x1F)) and 0x1F)
            m.u(253)?.let { running = it }

            when (def.globalNum) {
                21 -> {
                    if (m.u(0) != 74L) {
                        otherMessages++
                        return
                    }
                    val ts = unix(running)
                    val type = range(m.u(1), 0, 1)
                    if (ts == null || type == null) return drop()
                    sleepMarks.add(GarminWellnessSamples.TimedInt(ts, type))
                }
                55 -> monitoring(m)
                103 -> {
                    noteOffset(m.u(0), running)
                    if (m.u(5) == null) return
                    val ts = unix(running)
                    val rmr = range(m.u(5), 1, 20_000)
                    if (ts == null || rmr == null) return drop()
                    restingMetabolicRate.add(GarminWellnessSamples.TimedInt(ts, rmr))
                }
                211 -> {
                    val ts = unix(running)
                    val rhr = range(m.u(1), 1, 300) ?: range(m.u(0), 1, 300)
                    if (ts == null || rhr == null) return drop()
                    restingHeartRate.add(GarminWellnessSamples.TimedInt(ts, rhr))
                }
                227 -> {
                    val ts = unix(m.u(1)) ?: unix(running) ?: return drop()
                    range(m.s(0), 0, 100)?.let { stress.add(GarminWellnessSamples.TimedInt(ts, it)) }
                    range(m.s(3), 0, 100)?.let { bodyBattery.add(GarminWellnessSamples.TimedInt(ts, it)) }
                }
                269 -> {
                    val ts = unix(running)
                    val pct = range(m.u(0), 70, 100)
                    if (ts == null || pct == null) return drop()
                    val mode = m.value(2)
                    if (mode == null || mode >= SPO2_MIN_BACKGROUND_MODE) spo2.add(GarminWellnessSamples.Spo2(ts, pct))
                }
                273 -> {
                    noteOffset(m.u(2), running)
                    rawStart = unix(running)
                    rawStep = (range(m.u(1), 1, 3600) ?: 60).toLong()
                    rawSlot = 0
                }
                274 -> sleepRawRow(m)
                275 -> {
                    val ts = unix(running)
                    val level = range(m.u(0), 0, 4)
                    if (ts == null || level == null) return drop()
                    sleepLevels.add(GarminWellnessSamples.TimedInt(ts, level))
                    // Awake and unmeasurable rows occupy a raw sample slot of their own.
                    if (rawStart != null && level <= 1) rawSlot++
                }
                297 -> {
                    val ts = unix(running)
                    val raw = range(m.s(0), 400, 6000)
                    if (ts == null || raw == null) return drop()
                    respiration.add(GarminWellnessSamples.TimedDouble(ts, raw.toDouble() / 100))
                }
                346 -> sleepAssessment(m)
                370 -> {
                    val ts = unix(running) ?: return drop()
                    hrvSummaries.add(
                        GarminWellnessSamples.HrvSummary(
                            ts, m.scaled(0, 128.0), m.scaled(1, 128.0), m.scaled(2, 128.0), m.scaled(3, 128.0),
                            m.scaled(4, 128.0), m.scaled(5, 128.0), range(m.u(6), 0, 4),
                        ),
                    )
                }
                371 -> {
                    val ts = unix(running)
                    val v = range(m.u(0), 640, 38_400)
                    if (ts == null || v == null) return drop()
                    hrvValues.add(GarminWellnessSamples.TimedDouble(ts, v.toDouble() / 128))
                }
                290 -> beatIntervalsRow(m)
                397 -> {
                    val ts = unix(running)
                    val v = m.f32(1)
                    if (ts == null || v == null) return drop()
                    skinTempRaw.add(GarminWellnessSamples.TimedDouble(ts, v))
                }
                398 -> {
                    noteOffset(m.u(0), running)
                    val ts = unix(running) ?: return drop()
                    skinTempOvernight.add(
                        GarminWellnessSamples.SkinTempOvernightCandidate(ts, m.f32(1), m.f32(2), m.value(3), m.f32(4)),
                    )
                }
                else -> otherMessages++
            }
        }

        private fun monitoring(m: Message) {
            val full = m.u(253)
            val t16 = m.u(26)
            val fitTs: Long? = when {
                full != null -> full
                t16 != null -> (lastMonitoring ?: running)?.let { ref -> ref + ((t16 - (ref and 0xFFFF)) and 0xFFFF) }
                else -> running
            }
            val ts = unix(fitTs)
            if (fitTs == null || ts == null) return drop()
            lastMonitoring = fitTs
            noteOffset(m.u(11), fitTs)

            val typeIntensity = m.u(24)
            val sample = GarminWellnessSamples.Monitoring(
                ts = ts,
                activityType = m.value(5) ?: typeIntensity?.let { (it and 0x1F).toInt() },
                intensity = typeIntensity?.let { ((it shr 5) and 0x07).toInt() },
                cycles = m.value(3),
                distanceM = m.scaled(2, 100.0),
                calories = m.value(1),
                activeCalories = m.value(19),
                activeTimeS = m.scaled(4, 1000.0),
                heartRate = range(m.u(27), 1, 300),
                moderateMin = m.value(33),
                vigorousMin = m.value(34),
            )
            if (sample.cycles == null && sample.distanceM == null && sample.calories == null &&
                sample.activeCalories == null && sample.activeTimeS == null && sample.heartRate == null &&
                sample.intensity == null && sample.moderateMin == null && sample.vigorousMin == null
            ) return drop()
            monitoring.add(sample)
        }

        private fun sleepRawRow(m: Message) {
            val start = rawStart
            val b = m.bytes(0)
            if (start == null || b == null || b.size != 20) return drop()
            // Ten little-endian half floats: index 8 is movement, index 9 heart rate.
            val hr = halfToDouble((b[18].toInt() and 0xFF) or ((b[19].toInt() and 0xFF) shl 8))?.takeIf { it >= 25 && it <= 250 }
            val movement = halfToDouble((b[16].toInt() and 0xFF) or ((b[17].toInt() and 0xFF) shl 8))?.takeIf { it >= 0 }
            sleepRaw.add(GarminWellnessSamples.SleepRaw(start + rawSlot * rawStep, hr, movement))
            rawSlot++
        }

        private fun sleepAssessment(m: Message) {
            val ts = unix(running)
            val overall = range(m.u(6), 0, 100)
            if (ts == null || overall == null) return drop()
            sleepAssessments.add(
                GarminWellnessSamples.SleepAssessment(
                    ts = ts, overallScore = overall, combinedAwakeScore = m.value(0), awakeTimeScore = m.value(1),
                    awakeningsCountScore = m.value(2), deepSleepScore = m.value(3), sleepDurationScore = m.value(4),
                    lightSleepScore = m.value(5), sleepQualityScore = m.value(7), sleepRecoveryScore = m.value(8),
                    remSleepScore = m.value(9), restlessnessScore = m.value(10), awakeningsCount = m.value(11),
                    interruptionsScore = m.value(14), averageStress = m.scaled(15, 100.0),
                ),
            )
        }

        private fun beatIntervalsRow(m: Message) {
            val ts = unix(running) ?: return drop()
            val intervals = ArrayList<Int>()
            m.fields[1]?.let { (off, size) ->
                var o = off
                while (o + 2 <= off + size) {
                    stream.uint(o, 2, m.bigEndian)?.let { if (it != 0xFFFFL) intervals.add(it.toInt()) }
                    o += 2
                }
            }
            beatIntervals.add(GarminWellnessSamples.BeatIntervalsCandidate(ts, m.value(0), intervals))
        }
    }
}
