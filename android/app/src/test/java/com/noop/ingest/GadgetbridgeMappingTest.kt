package com.noop.ingest

import com.noop.ingest.GadgetbridgeMapping.ActivityRow
import com.noop.ingest.GadgetbridgeMapping.SleepStatsRow
import com.noop.ingest.GadgetbridgeMapping.StageRow
import com.noop.ingest.GadgetbridgeMapping.StressRow
import com.noop.ingest.GadgetbridgeMapping.WorkoutHrRow
import com.noop.ingest.GadgetbridgeMapping.WorkoutSummaryRow
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Literal Gadgetbridge rows → decoder shapes: sentinels, second / millisecond units, stage spans, night assignment. */
class GadgetbridgeMappingTest {

    private val t0 = 1_761_955_200L // 2025-11-01T00:00:00Z

    @Test
    fun minuteSentinelsBecomeNullAndEmptyRowsAreDropped() {
        val out = GadgetbridgeMapping.minutes(listOf(
            ActivityRow(t0, steps = 0, calories = 1426, distance = 0, spo = -1, heartRate = 54, restingHr = 0),
            ActivityRow(t0 + 60, -1, -1, -1, -1, -1, -1),
            ActivityRow(t0 + 120, steps = 12, calories = 0, distance = 9, spo = 97, heartRate = 0, restingHr = 58),
        ))
        assertEquals(2, out.size)
        assertEquals(t0, out[0].ts); assertEquals(0, out[0].steps); assertEquals(54, out[0].heartRate)
        assertNull(out[0].spo2Pct); assertNull(out[0].restingHr); assertNull(out[0].calories)
        assertEquals(12, out[1].steps); assertEquals(9, out[1].distanceM); assertEquals(97, out[1].spo2Pct)
        assertNull(out[1].heartRate); assertEquals(58, out[1].restingHr)
    }

    @Test
    fun stagesMergeIntoSpansAndFollowTheirNight() {
        val bed = t0 * 1000 - 3_600_000
        val wake = bed + 7 * 60_000
        // light, light, deep, REM, awake, nap(light), then one minute after wake-up (belongs to no night).
        val codes = listOf(1L, 1L, 3L, 2L, 4L, 5L, 1L)
        val stages = codes.mapIndexed { i, c -> StageRow(bed + i * 60_000L, c) } +
            StageRow(wake + 120_000, 3) + StageRow(bed - 60_000, 3)
        val stats = listOf(SleepStatsRow(bed + 1000, score = 80, bedMs = bed, risingMs = wake, wakeupMs = wake, avgHrv = 61, avgHr = 60))
        val n = GadgetbridgeMapping.nights(stats, stages).single()
        assertEquals(listOf("light", "deep", "rem", "wake", "light"), n.stages.map { it.stage })
        assertEquals(listOf(120L, 60L, 60L, 60L, 120L), n.stages.map { it.end - it.start })
        assertEquals(bed / 1000, n.stages.first().start)
        assertEquals(bed / 1000, n.bedTs); assertEquals(wake / 1000, n.wakeupTs)
        assertEquals(80, n.sleepScore); assertEquals(61, n.avgHrvMs); assertEquals(60, n.avgHr)
    }

    @Test
    fun twoNightsGetOnlyTheirOwnStagesAndSentinelsAreNull() {
        val bed1 = t0 * 1000
        val bed2 = bed1 + 86_400_000
        val stats = listOf(
            SleepStatsRow(bed2, -1, bed2, bed2 + 120_000, bed2 + 120_000, 0, -1),
            SleepStatsRow(bed1, 79, bed1, bed1 + 120_000, bed1 + 120_000, 64, 57),
        )
        val stages = listOf(StageRow(bed1, 3), StageRow(bed2, 2))
        val nights = GadgetbridgeMapping.nights(stats, stages)
        assertEquals(listOf("deep"), nights[0].stages.map { it.stage })
        assertEquals(listOf("rem"), nights[1].stages.map { it.stage })
        assertNull(nights[1].sleepScore); assertNull(nights[1].avgHrvMs); assertNull(nights[1].avgHr)
    }

    @Test
    fun nightWithoutStagesIsDropped() {
        val bed = t0 * 1000
        assertEquals(0, GadgetbridgeMapping.nights(listOf(SleepStatsRow(bed, 80, bed, bed + 60_000, bed + 60_000, 1, 1)), emptyList()).size)
    }

    @Test
    fun stressUsesMillisecondTimestampsAndNeutralFields() {
        val out = GadgetbridgeMapping.stress(listOf(
            StressRow(tsMs = 1_781_123_494_000, stress = 38, level = 2, startMs = 1_781_123_438_000),
            StressRow(tsMs = 1_781_125_294_000, stress = 36, level = 2, startMs = 0),
            StressRow(tsMs = 1_781_127_093_000, stress = -1, level = 0, startMs = 0),
        ))
        assertEquals(2, out.size)
        assertEquals(1_781_123_438L, out[0].startTs); assertEquals(1_781_123_494L, out[0].endTs)
        assertEquals(38, out[0].score); assertEquals(2, out[0].level)
        assertEquals(1_781_125_294L, out[1].startTs)
    }

    @Test
    fun workoutTotalsAndHeartRateSamples() {
        val s = listOf(
            WorkoutSummaryRow(3, 1_781_259_319, 1_781_261_602, 379, 0, 0, 2262, 2262, -1, -89, -1, null),
            WorkoutSummaryRow(4, 1_781_261_962, 1_781_263_969, 357, 5200, 4100, 2004, 2004, 7, 171, 90, 12),
        )
        val hr = listOf(
            WorkoutHrRow(4, 1_781_261_972, 130), WorkoutHrRow(4, 1_781_261_967, 0), WorkoutHrRow(3, 1_781_259_329, 60),
        )
        val out = GadgetbridgeMapping.workouts(s, hr)
        assertNull(out[0].totals.type); assertNull(out[0].totals.distanceM); assertNull(out[0].totals.maxHr)
        assertEquals(379L, out[0].totals.kcal); assertEquals(listOf(60), out[0].samples.map { it.heartRate })
        assertEquals(7, out[1].totals.type); assertEquals(5200L, out[1].totals.distanceM)
        assertEquals(171, out[1].totals.maxHr); assertEquals(90L, out[1].totals.load)
        assertEquals(12.0, out[1].totals.elevationGainM!!, 0.0)
        // Rows come back time-ordered; a zero reading is null (the fold's HR range then drops it).
        assertEquals(listOf(null, 130), out[1].samples.map { it.heartRate })
    }

    @Test
    fun chunksStartOnLocalMidnightAndCoverTheRange() {
        val chunks = GadgetbridgeMapping.chunks(t0 + 3_600, t0 + 15 * 86_400, ZoneId.of("UTC"))
        assertEquals(3, chunks.size)
        assertEquals(t0, chunks[0].from); assertEquals(t0 + 7 * 86_400, chunks[0].to)
        assertEquals(chunks[0].to, chunks[1].from)
        assertEquals(0, chunks[0].utcOffsetS)
        assertEquals(emptyList<Any>(), GadgetbridgeMapping.chunks(10, 5, ZoneId.of("UTC")))
        // UTC+7: a UTC instant just after 17:00Z is already the next local day.
        assertEquals(t0 - 7 * 3600, GadgetbridgeMapping.chunks(t0 - 3600, t0, ZoneId.of("Asia/Ho_Chi_Minh"))[0].from)
    }

    @Test
    fun chunkNeverSpansAnOffsetChange() {
        // Europe/Berlin moves to winter time on 2025-10-26 (03:00 local).
        val zone = ZoneId.of("Europe/Berlin")
        val from = java.time.LocalDate.of(2025, 10, 22).atStartOfDay(zone).toEpochSecond()
        val chunks = GadgetbridgeMapping.chunks(from, from + 12 * 86_400, zone)
        for (c in chunks) {
            val a = zone.rules.getOffset(java.time.Instant.ofEpochSecond(c.from)).totalSeconds
            // Only the transition day itself (the chunk's last) may straddle the change; every day before it is whole.
            val b = zone.rules.getOffset(java.time.Instant.ofEpochSecond(c.to - 86_400 - 1)).totalSeconds
            assertEquals(a, b); assertEquals(a, c.utcOffsetS)
        }
        assertEquals(setOf(7200, 3600), chunks.map { it.utcOffsetS }.toSet())
        for (i in 1 until chunks.size) assertEquals(chunks[i - 1].to, chunks[i].from)
    }

    @Test
    fun stageAtWakeupMinuteBelongsToNoNight() {
        val bed = t0 * 1000
        val stats = listOf(SleepStatsRow(bed, 80, bed, bed + 120_000, bed + 120_000, 1, 1))
        val stages = listOf(StageRow(bed, 3), StageRow(bed + 60_000, 3), StageRow(bed + 120_000, 3))
        assertEquals(120L, GadgetbridgeMapping.nights(stats, stages).single().stages.sumOf { it.end - it.start })
    }
}
