package com.noop.ingest

import com.noop.huawei.decode.HuaweiDictionaryDecoder
import com.noop.huawei.decode.HuaweiFitnessDecoder.MinuteSample
import com.noop.huawei.decode.HuaweiFitnessDecoder.SleepSpan
import com.noop.huawei.decode.HuaweiFitnessDecoder.SleepStage
import com.noop.huawei.decode.HuaweiSleepFileDecoder
import com.noop.huawei.decode.HuaweiSleepFileDecoder.Stage
import com.noop.huawei.decode.HuaweiStressDecoder
import com.noop.huawei.decode.HuaweiWorkoutDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** One synthetic Huawei day through the fold, against the Garmin fold's row shapes. */
class HuaweiWatchIngestTest {

    private val t0 = GarminWatchIngest.dayStartUnix("2026-03-10", 0)
    private val now = t0 + 86_400

    private fun night(fallAsleep: Long, wake: Long, score: Int?, hrv: Int?, stages: List<Stage>) =
        HuaweiSleepFileDecoder.Night(fallAsleep - 900, wake, fallAsleep, fallAsleep - 900, wake + 300, wake, 1, score, hrv, 55, stages)

    private fun stress(ts: Long, score: Int) = HuaweiStressDecoder.Entry(ts, ts + 60, 0, score, 1, 0, 0, 0, 0, List(12) { 0f })

    private fun totals(start: Long, end: Long) = HuaweiWorkoutDecoder.Totals(
        number = 1, status = 1, startTs = start, endTs = end, kcal = 200, distanceM = 3000, steps = 4000,
        totalTimeS = end - start, durationS = end - start, type = 1, minHr = 90, maxHr = 150, load = 85,
        aerobicEffect = 3.2, anaerobicEffect = 1.1, elevationGainM = 12.5,
    )

    private fun batch() = HuaweiWatchIngest.Batch(
        minutes = listOf(
            MinuteSample(t0 + 600, heartRate = 55, spo2Pct = 95),
            MinuteSample(t0 + 1200, heartRate = 56, spo2Pct = 96),
            MinuteSample(t0 + 1800, heartRate = 57, spo2Pct = 97),
            MinuteSample(t0 + 2400, heartRate = 0, spo2Pct = 0),
            MinuteSample(t0 + 30_000, restingHr = 52),
            MinuteSample(t0 + 40_000, steps = 100, distanceM = 80, calories = 5),
            MinuteSample(t0 + 40_060, steps = 50, distanceM = 40, calories = 3),
            MinuteSample(t0 + 40_060, steps = 10, heartRate = 98),
            MinuteSample(t0 + 50_000, spo2Pct = 90),
            MinuteSample(t0 + 60_000, restingHr = 54),
            // Unplaceable: an unset clock, and a time two days in the future.
            MinuteSample(1000, steps = 999, heartRate = 70),
            MinuteSample(now + 2 * 86_400, steps = 999, heartRate = 70),
        ),
        nights = listOf(
            night(t0 - 7200 + 30, t0 + 15_000, 82, 45, listOf(
                Stage(t0 - 7200, t0, "light"), Stage(t0, t0 + 3600, "deep"), Stage(t0 + 3600, t0 + 7200, "rem"),
                Stage(t0 + 7200, t0 + 7800, "wake"), Stage(t0 + 7800, t0 + 15_000, "light"),
            )),
            // A night without a timeline cannot be placed and is dropped.
            night(t0 + 70_000, t0 + 72_000, 50, 30, emptyList()),
        ),
        stress = listOf(stress(t0 + 36_000, 40), stress(t0 + 37_000, 0)),
        dictionary = listOf(
            HuaweiDictionaryDecoder.Sample(t0 + 3000, HuaweiDictionaryDecoder.Kind.HRV_RMSSD_MS, 48.5),
            HuaweiDictionaryDecoder.Sample(t0 + 3000, HuaweiDictionaryDecoder.Kind.SKIN_TEMP_C, 33.6),
            HuaweiDictionaryDecoder.Sample(500, HuaweiDictionaryDecoder.Kind.SKIN_TEMP_C, 33.6),
        ),
        workouts = listOf(
            HuaweiWatchIngest.Workout(
                totals(t0 + 43_200, t0 + 45_000),
                samples = listOf(
                    HuaweiWorkoutDecoder.Sample(t0 + 43_200, heartRate = 120),
                    HuaweiWorkoutDecoder.Sample(t0 + 43_205, heartRate = 130),
                    HuaweiWorkoutDecoder.Sample(t0 + 43_210, heartRate = 140),
                    HuaweiWorkoutDecoder.Sample(t0 + 43_215, heartRate = 255),
                    HuaweiWorkoutDecoder.Sample(t0 + 46_000, heartRate = 100),
                ),
                gps = listOf(
                    HuaweiWorkoutDecoder.GpsPoint(t0 + 43_200, 52.0, 13.0, null, false),
                    HuaweiWorkoutDecoder.GpsPoint(t0 + 43_260, 52.001, 13.001, null, true),
                    HuaweiWorkoutDecoder.GpsPoint(t0 + 43_320, 52.002, 13.002, null, false),
                ),
            ),
            HuaweiWatchIngest.Workout(totals(100, 200)),
        ),
        utcOffsetS = 0,
        nowTs = now,
    )

    private fun n(v: Number?) = when (v) {
        null -> "-"
        is Double -> if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()
        else -> v.toString()
    }

    @Test fun syntheticDay() {
        val out = HuaweiWatchIngest.fold(batch())
        val r = t0.toString()
        fun at(o: Long) = (t0 + o).toString()

        assertEquals(listOf(600L, 1200L, 1800L, 40_060L).map { t0 + it }, out.hr.map { it.ts })
        assertEquals(listOf(55, 56, 57, 98), out.hr.map { it.bpm })

        assertEquals(
            listOf(
                "${at(600)} spo2_pct 95 huawei_minute", "${at(1200)} spo2_pct 96 huawei_minute",
                "${at(1800)} spo2_pct 97 huawei_minute", "${at(3000)} hrv_ms 48.5 huawei_dict",
                "${at(15_000)} sleep_score 82 huawei_sleep", "${at(36_000)} stress 40 huawei_stress",
                "${at(43_200)} aerobic_training_effect 3.2 huawei_workout",
                "${at(43_200)} anaerobic_training_effect 1.1 huawei_workout",
                "${at(43_200)} training_load 85 huawei_workout", "${at(50_000)} spo2_pct 90 huawei_minute",
            ),
            out.deviceMetric.map { "${it.ts} ${it.kind.wire} ${n(it.value)} ${it.source}" },
        )
        assertEquals(listOf("${at(3000)} skin_temp_c 33.6 huawei_dict"), out.pendingMetric.map { "${it.ts} ${it.kindWire} ${n(it.value)} ${it.source}" })

        val d = out.days.single()
        assertEquals(
            "2026-03-10 $r..${at(86_400)} steps=160 dist=120 tot=8 rhr=54 hrv=45 sleep=360 deep=60 light=240 rem=60 " +
                "awake=10 eff=0.973 score=82",
            "${d.day} ${d.dayStartTs}..${d.dayEndTs} steps=${n(d.steps)} dist=${n(d.distanceM)} tot=${n(d.totalKcal)} " +
                "rhr=${n(d.restingHr)} hrv=${n(d.avgHrvMs)} sleep=${n(d.totalSleepMin)} deep=${n(d.deepMin)} " +
                "light=${n(d.lightMin)} rem=${n(d.remMin)} awake=${n(d.awakeMin)} eff=${n(d.efficiency)} score=${n(d.sleepScore)}",
        )
        assertEquals(listOf("2026-03-11"), out.nightRefreshDays.map { it.day })

        val s = out.sleeps.single()
        assertEquals(listOf(t0 - 7200, t0 + 15_000), listOf(s.startTs, s.endTs))
        assertEquals(0.973, s.efficiency!!, 0.0)
        assertEquals(45.0, s.avgHrv!!, 0.0)
        assertEquals(
            "[{\"start\":${at(-7200)},\"end\":$r,\"stage\":\"light\"},{\"start\":$r,\"end\":${at(3600)},\"stage\":\"deep\"}," +
                "{\"start\":${at(3600)},\"end\":${at(7200)},\"stage\":\"rem\"},{\"start\":${at(7200)},\"end\":${at(7800)},\"stage\":\"wake\"}," +
                "{\"start\":${at(7800)},\"end\":${at(15_000)},\"stage\":\"light\"}]",
            s.stagesJSON,
        )

        // The three in-night samples; the 90 % daytime reading is outside every night.
        assertEquals(mapOf("2026-03-10" to 96.0), out.overnightSpo2)

        val w = out.workouts.single()
        assertEquals(listOf(t0 + 43_200, t0 + 45_000), listOf(w.startTs, w.endTs))
        assertEquals(listOf(120, 130, 140), w.hrSamples.map { it.bpm })
        assertEquals(listOf(130, 150), listOf(w.avgHr, w.maxHr))
        assertEquals(listOf(3000.0, 200.0, 12.5), listOf(w.distanceM, w.energyKcal, w.ascentM))
        assertEquals(4000, w.steps)
        assertEquals(listOf(52.0 to 13.0, 52.002 to 13.002), w.route.map { it.lat to it.lon })
    }

    @Test fun overnightSpo2NeedsThreeSamples() {
        val b = batch().copy(minutes = batch().minutes.filter { it.ts != t0 + 1800 })
        assertTrue(HuaweiWatchIngest.fold(b).overnightSpo2.isEmpty())
    }

    @Test fun legacySleepRecordsJoinIntoNightsWithWakeGaps() {
        val b = HuaweiWatchIngest.Batch(
            sleepSpans = listOf(
                SleepSpan(t0 - 3600, t0 + 3600, SleepStage.LIGHT),
                SleepSpan(t0 + 3600, t0 + 7200, SleepStage.DEEP),
                SleepSpan(t0 + 8400, t0 + 14_400, SleepStage.LIGHT),
                // Over an hour later: a separate sleep (a nap).
                SleepSpan(t0 + 50_000, t0 + 51_800, SleepStage.LIGHT),
            ),
            utcOffsetS = 0, nowTs = now,
        )
        val out = HuaweiWatchIngest.fold(b)
        assertEquals(listOf(t0 - 3600 to t0 + 14_400, t0 + 50_000 to t0 + 51_800), out.sleeps.map { it.startTs to it.endTs })
        // The main night is the longer one: 3 h 40 light, 1 h deep, 20 min awake.
        val d = out.days.single()
        assertEquals(listOf(280.0, 60.0, 220.0, 20.0), listOf(d.totalSleepMin, d.deepMin, d.lightMin, d.awakeMin))
        assertTrue(out.sleeps[0].stagesJSON!!.contains("{\"start\":${t0 + 7200},\"end\":${t0 + 8400},\"stage\":\"wake\"}"))
    }

    @Test fun legacyRecordsUnderATruSleepNightAreIgnored() {
        val b = batch().copy(sleepSpans = listOf(SleepSpan(t0, t0 + 3600, SleepStage.DEEP)))
        assertEquals(1, HuaweiWatchIngest.fold(b).sleeps.size)
    }

    @Test fun localDayFollowsTheOffset() {
        // 23:30 UTC on the 10th is the 11th at UTC+7.
        val b = HuaweiWatchIngest.Batch(minutes = listOf(MinuteSample(t0 + 84_600, steps = 12)), utcOffsetS = 25_200, nowTs = now)
        val d = HuaweiWatchIngest.fold(b).days.single()
        assertEquals("2026-03-11", d.day)
        assertEquals(GarminWatchIngest.dayStartUnix("2026-03-11", 25_200), d.dayStartTs)
    }

    @Test fun aDayFoldedOntoItsCarriedBaseMatchesTheWholeDay() {
        val day = "2026-03-10"
        val minutes = (0 until 300).map { i ->
            MinuteSample(t0 + 240L * i, steps = i % 13, calories = i % 3 + 1, distanceM = if (i % 4 == 0) null else i % 9, heartRate = 60 + i % 40)
        } + MinuteSample(t0 + 2_400, steps = 3) // a minute in two records adds up
        val whole = HuaweiWatchIngest.Batch(minutes = minutes, utcOffsetS = 0, nowTs = now)
        val cutoff = t0 + 9 * 3_600 + 30
        val base = HuaweiWatchIngest.dayCounters(whole, day, null, cutoff)
        // The next read starts half an hour before the cutoff: those minutes are in the base already.
        val tail = HuaweiWatchIngest.Batch(minutes = minutes.filter { it.ts >= cutoff - 1_800 }, utcOffsetS = 0, nowTs = now,
            countFrom = cutoff, dayBase = mapOf(day to base))
        val w = HuaweiWatchIngest.fold(whole).days.single()
        val t = HuaweiWatchIngest.fold(tail).days.single()
        assertEquals(listOf(w.steps, w.distanceM, w.totalKcal), listOf(t.steps, t.distanceM, t.totalKcal))
        assertEquals("every minute's heart rate is still stored", HuaweiWatchIngest.fold(whole).hr.filter { it.ts >= cutoff - 1_800 },
            HuaweiWatchIngest.fold(tail).hr)
        // Carried in two steps, the base is the same as in one.
        val mid = t0 + 4 * 3_600
        val twoSteps = HuaweiWatchIngest.dayCounters(whole, day, mid, cutoff, HuaweiWatchIngest.dayCounters(whole, day, null, mid))
        assertEquals(base, twoSteps)
    }

    @Test fun aCarriedBaseWithoutCountersLeavesThemNull() {
        val b = HuaweiWatchIngest.Batch(minutes = listOf(MinuteSample(t0 + 36_000, heartRate = 70)), utcOffsetS = 0, nowTs = now,
            countFrom = t0 + 30_000, dayBase = mapOf("2026-03-10" to HuaweiWatchIngest.DayCounters()))
        val d = HuaweiWatchIngest.fold(b).days.single()
        assertEquals(listOf<Any?>(null, null, null), listOf(d.steps, d.distanceM, d.totalKcal))
    }

    @Test fun emptyBatchYieldsNothing() {
        val out = HuaweiWatchIngest.fold(HuaweiWatchIngest.Batch(utcOffsetS = 0, nowTs = now))
        assertTrue(out.hr.isEmpty() && out.deviceMetric.isEmpty() && out.days.isEmpty() && out.sleeps.isEmpty() && out.workouts.isEmpty())
    }
}
