package com.noop.ingest

import com.noop.data.DeviceMetricKind
import com.noop.data.DeviceMetricRow
import com.noop.data.GarminDayRollup
import com.noop.data.GarminSleepRow
import com.noop.data.GarminSyncStore
import com.noop.data.HrRow
import com.noop.huawei.decode.HuaweiDictionaryDecoder
import com.noop.huawei.decode.HuaweiFitnessDecoder
import com.noop.huawei.decode.HuaweiSleepFileDecoder
import com.noop.huawei.decode.HuaweiStressDecoder
import com.noop.huawei.decode.HuaweiWorkoutDecoder

/**
 * Decoded Huawei band history → the same row shapes the Garmin watch fold produces, so the existing tables
 * and cards take Huawei data unchanged. Pure: no database, no clock (the caller passes [Batch.nowTs]).
 * Kotlin-only; Swift twin deferred (fork). Brand-neutral Garmin helpers are reused as they are
 * ([GarminWatchIngest.efficiency], [GarminWatchIngest.stagesJSON], [GarminWatchIngest.dayStartUnix],
 * [GarminWellnessParser.localDay]); nothing in the Garmin fold changes.
 *
 * Per-minute heart rate → hrSample; SpO2, HRV, stress, the band's sleep score, Training Effect and
 * training load → deviceMetricSample; TruSleep nights (or, without any, the legacy light / deep records)
 * → sleepSession with a stage timeline, and `sleep_state.bin` frames no other record covers → an
 * unstaged sleepSession (bounds only); day totals (summed per-minute steps, distance and energy, the
 * latest resting HR, the main night) → [GarminDayRollup]; workouts → [WorkoutDraft]. Workout pace blocks
 * (17/0C) are decoded but not stored: no workout column or series holds per-unit pace, and none is added. A timestamp that
 * cannot be placed (before [EARLIEST_TS] or more than a day past `nowTs`) is dropped, never shifted.
 */
object HuaweiWatchIngest {

    const val SOURCE_MINUTE = "huawei_minute"
    const val SOURCE_DICTIONARY = "huawei_dict"
    const val SOURCE_STRESS = "huawei_stress"
    const val SOURCE_SLEEP = "huawei_sleep"
    const val SOURCE_WORKOUT = "huawei_workout"

    /** `deviceMetricSample.source` of per-sample workout cadence / altitude / power read from the band's 17/0A data pages. */
    const val SOURCE_WORKOUT_SAMPLES = "huawei-17-0a"

    /** The same series read from a Gadgetbridge export's `HUAWEI_WORKOUT_DATA_SAMPLE` rows. */
    const val SOURCE_WORKOUT_SAMPLES_GADGETBRIDGE = "gadgetbridge"

    /** Band workout type codes of walking (Gadgetbridge `HuaweiWorkoutGbParser`): there the step rate is the cadence. */
    private val WALKING_TYPES = setOf(2, 13)

    /**
     * The `deviceMetricSample.kind` wire string of wrist skin temperature. The enum entry lands with T2;
     * until then these rows travel as [PendingMetric] and wave 2 maps them onto the entry.
     */
    const val SKIN_TEMP_WIRE = "skin_temp_c"

    /** 2010-01-01T00:00Z: a band timestamp before this is an unset clock, not a time. */
    const val EARLIEST_TS = 1_262_304_000L

    /** Legacy sleep records further apart than this belong to different sleeps. */
    const val SLEEP_SPAN_JOIN_GAP_S = 3_600L

    private val HR_RANGE = 25..240
    private val SPO2_RANGE = 1..100

    /** Everything one history sync decoded. */
    data class Batch(
        val minutes: List<HuaweiFitnessDecoder.MinuteSample> = emptyList(),
        val sleepSpans: List<HuaweiFitnessDecoder.SleepSpan> = emptyList(),
        val nights: List<HuaweiSleepFileDecoder.Night> = emptyList(),
        /** TruSleep `sleep_state.bin` frames: sleep bounds with no stages. */
        val sleepFrames: List<HuaweiSleepFileDecoder.StateFrame> = emptyList(),
        val stress: List<HuaweiStressDecoder.Entry> = emptyList(),
        val dictionary: List<HuaweiDictionaryDecoder.Sample> = emptyList(),
        val workouts: List<Workout> = emptyList(),
        val utcOffsetS: Int,
        val nowTs: Long,
        /**
         * When set, a minute before this time adds no steps, distance or energy to its day (its heart rate and
         * SpO2 are still stored): earlier syncs read those counters into [dayBase]. Null counts every minute.
         */
        val countFrom: Long? = null,
        /** Per local day, the counters of that day's minutes before [countFrom], as earlier syncs summed them. */
        val dayBase: Map<String, DayCounters> = emptyMap(),
    )

    /** One day's summed step counters; a field is null when no minute carried it, as the fold leaves it. */
    data class DayCounters(val steps: Int? = null, val distanceM: Double? = null, val kcal: Double? = null)

    /** One workout's decoded parts; [gps] is its track file, empty when it had none; [pace] its 17/0C blocks. */
    data class Workout(
        val totals: HuaweiWorkoutDecoder.Totals,
        val samples: List<HuaweiWorkoutDecoder.Sample> = emptyList(),
        val gps: List<HuaweiWorkoutDecoder.GpsPoint> = emptyList(),
        val pace: List<HuaweiWorkoutDecoder.PaceBlock> = emptyList(),
        /** `deviceMetricSample.source` for this workout's per-sample cadence / altitude / power rows. */
        val sampleSource: String = SOURCE_WORKOUT_SAMPLES,
    )

    /**
     * How many usable per-sample values of each series a workout's data carried: present, not a sentinel,
     * not 0, and inside the workout. Zero means the band sent none of it, or sent nothing usable.
     */
    data class SampleCounts(val hr: Int, val cadence: Int, val altitude: Int, val power: Int)

    /** A metric whose kind enum entry has not landed yet (see [SKIN_TEMP_WIRE]). */
    data class PendingMetric(val ts: Long, val kindWire: String, val value: Double, val source: String)

    /** One workout ready for the workout table; HR series and route in the activity-file shapes. */
    data class WorkoutDraft(
        val startTs: Long,
        val endTs: Long,
        /** The band's workout type code, unmapped. */
        val huaweiType: Int?,
        val distanceM: Double?,
        val energyKcal: Double?,
        val steps: Int?,
        val avgHr: Int?,
        val maxHr: Int?,
        val ascentM: Double?,
        val hrSamples: List<ActivityFileImporter.HrPoint>,
        val route: List<ActivityFileImporter.RoutePoint>,
        val sampleCounts: SampleCounts = SampleCounts(0, 0, 0, 0),
    )

    /** What one batch contributes, in the Garmin fold's row shapes. */
    data class Ingest(
        val hr: List<HrRow>,
        val deviceMetric: List<DeviceMetricRow>,
        val pendingMetric: List<PendingMetric>,
        val days: List<GarminDayRollup>,
        val sleeps: List<GarminSleepRow>,
        /** The day after each rollup day the batch did not touch, as [com.noop.data.GarminFileIngest.nightRefreshDays]. */
        val nightRefreshDays: List<GarminDayRollup>,
        /** Per local day, the overnight SpO2 mean by the store's rule ([GarminSyncStore.nightMean]) over this batch. */
        val overnightSpo2: Map<String, Double>,
        val workouts: List<WorkoutDraft>,
    )

    /**
     * One night in the fold's own terms: bounds, its stage spans, and the band's figures for it. An
     * [unstaged] night (a `sleep_state.bin` frame) is asleep from start to end with no stage breakdown.
     */
    private data class FoldNight(
        val start: Long,
        val end: Long,
        val stages: List<HuaweiSleepFileDecoder.Stage>,
        val score: Int?,
        val avgHrv: Double?,
        val unstaged: Boolean = false,
    ) {
        fun seconds(vararg names: String) = stages.filter { it.stage in names }.sumOf { it.end - it.start }
        val asleepS get() = if (unstaged) end - start else seconds("light", "deep", "rem")
    }

    private fun placeable(b: Batch, ts: Long) = ts >= EARLIEST_TS && ts <= b.nowTs + 86_400

    /** The batch's placeable minutes by time. One minute can arrive in several records: counters add up, readings keep the last one. */
    private fun mergedMinutes(b: Batch): java.util.TreeMap<Long, HuaweiFitnessDecoder.MinuteSample> {
        val minutes = java.util.TreeMap<Long, HuaweiFitnessDecoder.MinuteSample>()
        for (m in b.minutes) {
            if (!placeable(b, m.ts)) continue
            val prev = minutes[m.ts]
            minutes[m.ts] = if (prev == null) m else HuaweiFitnessDecoder.MinuteSample(
                ts = m.ts,
                steps = sum(prev.steps, m.steps), calories = sum(prev.calories, m.calories),
                distanceM = sum(prev.distanceM, m.distanceM),
                heartRate = m.heartRate ?: prev.heartRate, spo2Pct = m.spo2Pct ?: prev.spo2Pct,
                restingHr = m.restingHr ?: prev.restingHr,
            )
        }
        return minutes
    }

    /** [c] with one minute's counters added, in the arithmetic [fold] uses for a day's totals. */
    private fun addMinute(c: DayCounters, m: HuaweiFitnessDecoder.MinuteSample) = DayCounters(
        steps = sum(c.steps, m.steps),
        distanceM = m.distanceM?.let { (c.distanceM ?: 0.0) + it } ?: c.distanceM,
        kcal = m.calories?.let { (c.kcal ?: 0.0) + it } ?: c.kcal,
    )

    /**
     * [base] plus the counters of the batch's minutes on local [day] with a time in [from, until) (from the
     * day's first minute when [from] is null), added minute by minute in time order exactly as [fold] adds
     * them, so a day folded onto a carried base matches the same day read whole from midnight.
     */
    fun dayCounters(b: Batch, day: String, from: Long?, until: Long, base: DayCounters = DayCounters()): DayCounters {
        var c = base
        for (m in mergedMinutes(b).values) {
            if ((from != null && m.ts < from) || m.ts >= until) continue
            if (GarminWellnessParser.localDay(m.ts, b.utcOffsetS) != day) continue
            c = addMinute(c, m)
        }
        return c
    }

    /** Where a TruSleep night ends (its last stage, or the wake-up time when later); null without a timeline. */
    fun nightEnd(n: HuaweiSleepFileDecoder.Night): Long? =
        if (n.stages.isEmpty()) null else maxOf(n.stages.last().end, n.wakeupTs ?: 0L)

    fun fold(b: Batch): Ingest {
        val offset = b.utcOffsetS
        fun placeable(ts: Long) = placeable(b, ts)
        fun dayOf(ts: Long) = GarminWellnessParser.localDay(ts, offset)

        val minutes = mergedMinutes(b)
        val hr = minutes.values.mapNotNull { m -> m.heartRate?.takeIf { it in HR_RANGE }?.let { HrRow(m.ts, it) } }

        val metrics = LinkedHashMap<String, DeviceMetricRow>()
        fun add(ts: Long, kind: DeviceMetricKind, value: Double, source: String) {
            if (!value.isFinite() || !placeable(ts)) return
            metrics["${kind.wire}|$ts"] = DeviceMetricRow(ts, kind, value, source)
        }
        val pending = ArrayList<PendingMetric>()
        for (m in minutes.values) m.spo2Pct?.takeIf { it in SPO2_RANGE }?.let { add(m.ts, DeviceMetricKind.SPO2_PCT, it.toDouble(), SOURCE_MINUTE) }
        for (s in b.dictionary) when (s.kind) {
            HuaweiDictionaryDecoder.Kind.HRV_RMSSD_MS -> add(s.ts, DeviceMetricKind.HRV_MS, s.value, SOURCE_DICTIONARY)
            HuaweiDictionaryDecoder.Kind.SKIN_TEMP_C ->
                if (placeable(s.ts)) pending.add(PendingMetric(s.ts, SKIN_TEMP_WIRE, s.value, SOURCE_DICTIONARY))
        }
        for (e in b.stress) if (e.score in 1..100) add(e.startTs, DeviceMetricKind.STRESS, e.score.toDouble(), SOURCE_STRESS)

        val nights = foldNights(b, ::placeable)
        for (n in nights) n.score?.let { add(n.end, DeviceMetricKind.SLEEP_SCORE, it.toDouble(), SOURCE_SLEEP) }

        val drafts = ArrayList<WorkoutDraft>()
        for (w in b.workouts.sortedBy { it.totals.startTs }) {
            val t = w.totals
            if (!placeable(t.startTs) || t.endTs < t.startTs || !placeable(t.endTs)) continue
            t.aerobicEffect?.let { add(t.startTs, DeviceMetricKind.AEROBIC_TRAINING_EFFECT, it, SOURCE_WORKOUT) }
            t.anaerobicEffect?.let { add(t.startTs, DeviceMetricKind.ANAEROBIC_TRAINING_EFFECT, it, SOURCE_WORKOUT) }
            t.load?.let { add(t.startTs, DeviceMetricKind.TRAINING_LOAD, it.toDouble(), SOURCE_WORKOUT) }
            val series = workoutSeries(w)
            for (r in series.rows) add(r.ts, r.kind, r.value, w.sampleSource)
            drafts.add(draft(w, series.counts))
        }
        val deviceMetric = metrics.values.sortedWith(compareBy<DeviceMetricRow>({ it.ts }, { it.kind.wire }))

        val days = HashMap<String, GarminDayRollup>()
        fun day(key: String): GarminDayRollup = days[key] ?: run {
            val start = GarminWatchIngest.dayStartUnix(key, offset)
            GarminDayRollup(day = key, dayStartTs = start, dayEndTs = start + 86_400)
        }
        // A day the minutes touch starts from its carried base; a minute before countFrom is already in that base.
        for (m in minutes.values) {
            val key = dayOf(m.ts)
            val d = days[key] ?: day(key).let { r ->
                b.dayBase[key]?.let { r.copy(steps = it.steps, distanceM = it.distanceM, totalKcal = it.kcal) } ?: r
            }
            val before = DayCounters(d.steps, d.distanceM, d.totalKcal)
            val c = if (b.countFrom == null || m.ts >= b.countFrom) addMinute(before, m) else before
            days[key] = d.copy(
                steps = c.steps, distanceM = c.distanceM, totalKcal = c.kcal,
                restingHr = m.restingHr?.takeIf { it in HR_RANGE } ?: d.restingHr,
            )
        }
        for (m in deviceMetric) days[dayOf(m.ts)] = day(dayOf(m.ts))
        for (p in pending) days[dayOf(p.ts)] = day(dayOf(p.ts))

        // Every night is a session; the day's sleep figures come from its main night, the one with the most
        // time asleep (the earlier on a tie), the same rule as the Garmin fold.
        val sleeps = ArrayList<GarminSleepRow>()
        val mainNight = HashMap<String, FoldNight>()
        for (n in nights) {
            val asleep = n.asleepS
            if (asleep <= 0) continue
            val key = dayOf(n.end)
            if (asleep > (mainNight[key]?.asleepS ?: 0L)) mainNight[key] = n
            sleeps.add(if (n.unstaged) GarminSleepRow(n.start, n.end, null, null, null, null)
            else GarminSleepRow(n.start, n.end, GarminWatchIngest.efficiency(asleep, n.seconds("wake")), null,
                n.avgHrv, stagesJSON(n)))
        }
        for ((key, n) in mainNight) {
            val asleep = n.asleepS
            if (n.unstaged) {
                // Bounds only: total sleep, no stage minutes and no efficiency the band never reported.
                days[key] = day(key).copy(totalSleepMin = asleep / 60.0, deepMin = null, lightMin = null, remMin = null,
                    awakeMin = null, efficiency = null)
                continue
            }
            val awake = n.seconds("wake")
            days[key] = day(key).copy(
                totalSleepMin = asleep / 60.0, deepMin = n.seconds("deep") / 60.0, lightMin = n.seconds("light") / 60.0,
                remMin = n.seconds("rem") / 60.0, awakeMin = awake / 60.0, efficiency = GarminWatchIngest.efficiency(asleep, awake),
                sleepScore = n.score ?: day(key).sleepScore, avgHrvMs = n.avgHrv ?: day(key).avgHrvMs,
            )
        }
        val rollups = days.keys.sorted().map { days.getValue(it) }
        val refresh = rollups.mapNotNull { d -> dayOf(d.dayEndTs).let { if (days[it] == null) day(it) else null } }

        return Ingest(hr = hr, deviceMetric = deviceMetric, pendingMetric = pending.sortedBy { it.ts }, days = rollups,
            sleeps = sleeps.sortedBy { it.startTs }, nightRefreshDays = refresh,
            overnightSpo2 = overnightSpo2(rollups, deviceMetric, nights), workouts = drafts)
    }

    /**
     * The batch's nights: TruSleep nights anchored by a fall-asleep time, then legacy light / deep records
     * joined into sleeps where no TruSleep night covers them. A night must start and end at a placeable time.
     */
    private fun foldNights(b: Batch, placeable: (Long) -> Boolean): List<FoldNight> {
        val out = ArrayList<FoldNight>()
        for (n in b.nights) {
            val end = nightEnd(n) ?: continue
            val start = n.stages.first().start
            if (!placeable(start) || !placeable(end)) continue
            out.add(FoldNight(start, end, n.stages, n.sleepScore, n.avgHrvMs?.takeIf { it > 0 }?.toDouble()))
        }
        val spans = b.sleepSpans
            .filter { placeable(it.startTs) && placeable(it.endTs) && out.none { n -> it.startTs < n.end && it.endTs > n.start } }
            .sortedBy { it.startTs }
        var group = ArrayList<HuaweiSleepFileDecoder.Stage>()
        fun flush() {
            if (group.isNotEmpty()) out.add(FoldNight(group.first().start, group.last().end, group, null, null))
            group = ArrayList()
        }
        for (s in spans) {
            val name = if (s.stage == HuaweiFitnessDecoder.SleepStage.DEEP) "deep" else "light"
            val last = group.lastOrNull()
            if (last != null && s.startTs - last.end > SLEEP_SPAN_JOIN_GAP_S) flush()
            val prev = group.lastOrNull()
            val from = maxOf(s.startTs, prev?.end ?: s.startTs)
            if (s.endTs <= from) continue
            if (prev != null && from > prev.end) group.add(HuaweiSleepFileDecoder.Stage(prev.end, from, "wake"))
            group.add(HuaweiSleepFileDecoder.Stage(from, s.endTs, name))
        }
        flush()
        // Last, the band's sleep_state frames, only where no TruSleep night or legacy record covers them.
        for (f in b.sleepFrames.sortedBy { it.startTs }) {
            if (f.endTs <= f.startTs || !placeable(f.startTs) || !placeable(f.endTs)) continue
            if (out.any { f.startTs < it.end && f.endTs > it.start }) continue
            out.add(FoldNight(f.startTs, f.endTs, emptyList(), null, null, unstaged = true))
        }
        return out.sortedBy { it.start }
    }

    /** The night's timeline in the Garmin fold's `stagesJSON` text, through its own writer. */
    private fun stagesJSON(n: FoldNight): String? {
        val codes = mapOf("wake" to 1, "light" to 2, "deep" to 3, "rem" to 4)
        val levels = ArrayList<GarminWellnessSamples.TimedInt>()
        var prev = n.start
        for (s in n.stages) {
            // A hole in the timeline closes as an unnamed span, so the next stage keeps its own start.
            if (s.start > prev) levels.add(GarminWellnessSamples.TimedInt(s.start, 0))
            levels.add(GarminWellnessSamples.TimedInt(s.end, codes.getValue(s.stage)))
            prev = s.end
        }
        return GarminWatchIngest.stagesJSON(levels, n.start, n.end)
    }

    /**
     * Per local day, the mean of this batch's SpO2 samples (70-100) inside any night ending that day, night
     * bounds inclusive and each sample counted once, or no entry under the store's minimum sample count.
     */
    private fun overnightSpo2(days: List<GarminDayRollup>, metrics: List<DeviceMetricRow>, nights: List<FoldNight>): Map<String, Double> {
        val out = LinkedHashMap<String, Double>()
        val spo2 = metrics.filter {
            it.kind == DeviceMetricKind.SPO2_PCT && it.value >= GarminSyncStore.GARMIN_SPO2_MIN && it.value <= GarminSyncStore.GARMIN_SPO2_MAX
        }
        for (d in days) {
            val ending = nights.filter { it.end >= d.dayStartTs && it.end < d.dayEndTs }
            val inside = spo2.filter { m -> m.ts >= d.dayStartTs - 86_400 && m.ts < d.dayEndTs && ending.any { m.ts >= it.start && m.ts <= it.end } }
            if (inside.size >= GarminSyncStore.GARMIN_MIN_NIGHT_SAMPLES) out[d.day] = inside.sumOf { it.value } / inside.size
        }
        return out
    }

    /** One workout's cadence / altitude / power rows plus the counts of each series, HR included. */
    internal data class WorkoutSeries(val rows: List<DeviceMetricRow>, val counts: SampleCounts)

    /**
     * Per-sample cadence, altitude and power of one workout, as Gadgetbridge's own reader takes them: speed is
     * not stored here. A value that is absent, a sentinel (`0xFF` step rate, `0xFFFF` cadence / power, raw
     * `-1` altitude) or 0 is skipped, as is a sample outside the workout. Cadence is steps per minute: for
     * walking the step rate (field 2), otherwise the inner block's cadence (inner bit 0), each falling back to
     * the other, because Gadgetbridge's chart code reads them in that order (`buildActivityPoint`).
     * The rows' source is left empty; [fold] stamps [Workout.sampleSource].
     */
    internal fun workoutSeries(w: Workout): WorkoutSeries {
        val t = w.totals
        val walking = t.type in WALKING_TYPES
        val rows = ArrayList<DeviceMetricRow>()
        var hr = 0; var cadence = 0; var altitude = 0; var power = 0
        val seen = HashSet<Long>()
        for (s in w.samples.sortedBy { it.ts }) {
            if (s.ts !in t.startTs..t.endTs || !seen.add(s.ts)) continue
            if (s.heartRate?.let { it in HR_RANGE } == true) hr++
            val stepRate = s.stepRate?.takeIf { it in 1..254 }
            val inner = s.inner[0]?.takeIf { it in 1..0xFFFE }
            val c = if (walking) stepRate ?: inner else inner ?: stepRate
            if (c != null) { rows.add(DeviceMetricRow(s.ts, DeviceMetricKind.CADENCE, c.toDouble(), "")); cadence++ }
            // 0.0 m is skipped with the other zeros, as the brief asks, although sea level is a real altitude.
            val alt = s.altitudeM?.takeIf { Math.round(it * 10) != -1L && it != 0.0 }
            if (alt != null) { rows.add(DeviceMetricRow(s.ts, DeviceMetricKind.ALTITUDE_M, alt, "")); altitude++ }
            val p = s.powerW?.takeIf { it in 1..0xFFFE }
            if (p != null) { rows.add(DeviceMetricRow(s.ts, DeviceMetricKind.POWER_W, p.toDouble(), "")); power++ }
        }
        return WorkoutSeries(rows, SampleCounts(hr, cadence, altitude, power))
    }

    private fun draft(w: Workout, counts: SampleCounts): WorkoutDraft {
        val t = w.totals
        val hr = w.samples.mapNotNull { s ->
            s.heartRate?.takeIf { it in HR_RANGE && s.ts in t.startTs..t.endTs }?.let { ActivityFileImporter.HrPoint(s.ts, it) }
        }.distinctBy { it.ts }.sortedBy { it.ts }
        val route = w.gps.filter { !it.paused }.map { ActivityFileImporter.RoutePoint(it.lat, it.lon) }
        val maxHr = t.maxHr?.takeIf { it in HR_RANGE } ?: hr.maxOfOrNull { it.bpm }
        return WorkoutDraft(
            startTs = t.startTs, endTs = t.endTs, huaweiType = t.type,
            distanceM = t.distanceM?.takeIf { it > 0 }?.toDouble(), energyKcal = t.kcal?.takeIf { it > 0 }?.toDouble(),
            steps = t.steps?.takeIf { it in 1..Int.MAX_VALUE }?.toInt(),
            avgHr = if (hr.isEmpty()) null else Math.round(hr.sumOf { it.bpm }.toDouble() / hr.size).toInt(),
            maxHr = maxHr, ascentM = t.elevationGainM?.takeIf { it > 0 }, hrSamples = hr, route = route,
            sampleCounts = counts.copy(hr = hr.size),
        )
    }

    private fun sum(a: Int?, b: Int?): Int? = if (a == null) b else if (b == null) a else a + b
}
