package com.noop.ingest

import com.noop.huawei.decode.HuaweiFitnessDecoder
import com.noop.huawei.decode.HuaweiSleepFileDecoder
import com.noop.huawei.decode.HuaweiStressDecoder
import com.noop.huawei.decode.HuaweiWorkoutDecoder

/**
 * Gadgetbridge (Huawei tables) rows → [HuaweiWatchIngest.Batch] parts. Pure: no Android types, no database,
 * so it runs as a plain JVM test. Table and column meanings were read from the public schema of a real
 * Gadgetbridge "Export database" file (AGPL-3.0 project; schema facts only, no code copied).
 * Kotlin-only; Swift twin deferred (Huawei is Android-only in this fork).
 *
 * Units in the export: `HUAWEI_ACTIVITY_SAMPLE` and workout tables carry unix SECONDS; the sleep and stress
 * tables carry unix MILLISECONDS. `-1` (and `0` for readings) is "not measured", never a value.
 */
object GadgetbridgeMapping {

    /** Activity rows of this `SOURCE` are the band's per-minute stream; other sources are separate series. */
    const val ACTIVITY_SOURCE_MINUTE = 11

    /** Raw stage codes: 1 light, 2 REM, 3 deep, 4 awake, 5 nap (kept as light), as the TruSleep file decoder. */
    private val STAGE_NAMES = mapOf(1 to "light", 2 to "rem", 3 to "deep", 4 to "wake", 5 to "light")

    /** Each sleep-stage sample covers one minute. */
    const val STAGE_SAMPLE_S = 60L

    data class ActivityRow(
        val ts: Long, val steps: Long, val calories: Long, val distance: Long,
        val spo: Long, val heartRate: Long, val restingHr: Long,
    )

    data class SleepStatsRow(
        val tsMs: Long, val score: Long, val bedMs: Long, val risingMs: Long, val wakeupMs: Long,
        val avgHrv: Long, val avgHr: Long,
    )

    data class StageRow(val tsMs: Long, val stage: Long)

    data class StressRow(val tsMs: Long, val stress: Long, val level: Long, val startMs: Long)

    data class WorkoutSummaryRow(
        val id: Long, val startTs: Long, val endTs: Long, val calories: Long, val distance: Long,
        val steps: Long, val totalTime: Long, val duration: Long, val type: Long, val maxHr: Long,
        val load: Long, val elevationGain: Long?,
    )

    data class WorkoutHrRow(val workoutId: Long, val ts: Long, val heartRate: Long)

    private fun positive(v: Long): Int? = if (v > 0 && v <= Int.MAX_VALUE) v.toInt() else null
    private fun nonNegative(v: Long): Int? = if (v >= 0 && v <= Int.MAX_VALUE) v.toInt() else null

    /**
     * Per-minute rows → minute samples. A counter keeps its zero (a still minute), a reading drops its zero.
     * Calories are left out: the export's figure is in small calories (a day sums to hundreds of thousands),
     * and a per-minute integer kcal cannot carry that without losing it. A row with nothing measured is dropped.
     */
    fun minutes(rows: List<ActivityRow>): List<HuaweiFitnessDecoder.MinuteSample> = rows.mapNotNull { r ->
        val m = HuaweiFitnessDecoder.MinuteSample(
            ts = r.ts, steps = nonNegative(r.steps), calories = null, distanceM = nonNegative(r.distance),
            heartRate = positive(r.heartRate), spo2Pct = positive(r.spo), restingHr = positive(r.restingHr),
        )
        if (m.steps == null && m.distanceM == null && m.heartRate == null && m.spo2Pct == null && m.restingHr == null) null else m
    }

    /**
     * Sleep statistics + per-minute stages → nights. A stage belongs to the night whose bed..wake-up window
     * holds it; consecutive minutes of the same stage merge into one span. A night with no stage is dropped.
     */
    fun nights(stats: List<SleepStatsRow>, stages: List<StageRow>): List<HuaweiSleepFileDecoder.Night> {
        val sortedStages = stages.sortedBy { it.tsMs }
        return stats.sortedBy { it.bedMs }.mapNotNull { s ->
            if (s.bedMs <= 0 || s.wakeupMs <= s.bedMs) return@mapNotNull null
            val spans = ArrayList<HuaweiSleepFileDecoder.Stage>()
            for (st in sortedStages) {
                if (st.tsMs < s.bedMs) continue
                if (st.tsMs >= s.wakeupMs) break
                val name = STAGE_NAMES[st.stage.toInt()] ?: continue
                val start = st.tsMs / 1000
                val end = start + STAGE_SAMPLE_S
                val last = spans.lastOrNull()
                if (last != null && last.stage == name && last.end == start) spans[spans.size - 1] = last.copy(end = end)
                else spans.add(HuaweiSleepFileDecoder.Stage(start, end, name))
            }
            if (spans.isEmpty()) return@mapNotNull null
            HuaweiSleepFileDecoder.Night(
                recordStart = s.bedMs / 1000, recordEnd = s.wakeupMs / 1000,
                fallAsleepTs = spans.firstOrNull { it.stage != "wake" }?.start,
                bedTs = s.bedMs / 1000, risingTs = s.risingMs.takeIf { it > 0 }?.div(1000),
                wakeupTs = s.wakeupMs / 1000, validData = null,
                sleepScore = positive(s.score), avgHrvMs = positive(s.avgHrv), avgHr = positive(s.avgHr),
                stages = spans,
            )
        }
    }

    /** Stress rows → entries carrying the score and level; every other field is neutral. */
    fun stress(rows: List<StressRow>): List<HuaweiStressDecoder.Entry> = rows.mapNotNull { r ->
        val score = positive(r.stress) ?: return@mapNotNull null
        val end = r.tsMs / 1000
        val start = if (r.startMs in 1..r.tsMs) r.startMs / 1000 else end
        HuaweiStressDecoder.Entry(
            startTs = start, endTs = end, algorithm = 0L, score = score, level = nonNegative(r.level) ?: 0,
            cFlag = 0, type = 0, accFlag = 0, ppgFlag = 0, features = emptyList(),
        )
    }

    /** Workout summaries + their heart-rate rows → workouts (totals and HR samples). */
    fun workouts(summaries: List<WorkoutSummaryRow>, hr: List<WorkoutHrRow>): List<HuaweiWatchIngest.Workout> {
        val byWorkout = hr.groupBy { it.workoutId }
        return summaries.map { w ->
            HuaweiWatchIngest.Workout(
                totals = HuaweiWorkoutDecoder.Totals(
                    number = null, status = null, startTs = w.startTs, endTs = w.endTs,
                    kcal = w.calories.takeIf { it > 0 }, distanceM = w.distance.takeIf { it > 0 },
                    steps = w.steps.takeIf { it > 0 }, totalTimeS = w.totalTime.takeIf { it > 0 },
                    durationS = w.duration.takeIf { it > 0 }, type = w.type.takeIf { it >= 0 }?.toInt(),
                    minHr = null, maxHr = positive(w.maxHr), load = w.load.takeIf { it > 0 },
                    aerobicEffect = null, anaerobicEffect = null,
                    elevationGainM = w.elevationGain?.takeIf { it > 0 }?.toDouble(),
                ),
                samples = byWorkout[w.id].orEmpty().sortedBy { it.ts }
                    .map { HuaweiWorkoutDecoder.Sample(ts = it.ts, heartRate = positive(it.heartRate)) },
            )
        }
    }

    /** One import chunk: `[from, to)` in unix seconds and the UTC offset its day boundaries use. */
    data class Chunk(val from: Long, val to: Long, val utcOffsetS: Int)

    /**
     * Chunks of [days] local days covering [minTs]..[maxTs], each starting on a local midnight so a day's rows
     * never straddle two chunks. A chunk uses the zone offset at its start (one offset per batch).
     */
    fun chunks(minTs: Long, maxTs: Long, zone: java.time.ZoneId, days: Long = 7): List<Chunk> {
        if (maxTs < minTs) return emptyList()
        val out = ArrayList<Chunk>()
        var day = java.time.Instant.ofEpochSecond(minTs).atZone(zone).toLocalDate()
        while (true) {
            val start = day.atStartOfDay(zone)
            if (start.toEpochSecond() > maxTs) break
            var endDay = day.plusDays(days)
            // A chunk ends at the first local midnight after an offset change, so the one offset it carries is right
            // for every whole day in it; only the 23 / 25-hour transition day itself keeps the pre-change offset.
            val next = zone.rules.nextTransition(start.toInstant())
            if (next != null) {
                val transitionDay = next.instant.atZone(zone).toLocalDate()
                if (transitionDay < endDay) endDay = transitionDay.plusDays(1).takeIf { it > day } ?: day.plusDays(1)
            }
            out.add(Chunk(start.toEpochSecond(), endDay.atStartOfDay(zone).toEpochSecond(), start.offset.totalSeconds))
            day = endDay
        }
        return out
    }
}
