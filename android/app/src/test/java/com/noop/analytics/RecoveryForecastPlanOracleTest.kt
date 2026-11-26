package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * Swift/Kotlin parity for tonight's plan (bedtime → planned sleep → forecast), the one resolver behind the
 * Today and Intelligence cards, and the forecast-accuracy summary, pinned by ORACLE: every expected literal
 * below is the verbatim stdout of `RecoveryForecast.swift` + `ForecastAccuracy.swift` compiled standalone
 * with `swiftc -O` over the same fixtures. Swift `RecoveryForecastPlanTests` pins the same lines.
 */
class RecoveryForecastPlanOracleTest {
    private val rf = RecoveryForecaster

    private fun f(v: Double?): String = v?.let { String.format(Locale.US, "%.9f", it) } ?: "nil"

    private fun fc(x: RecoveryForecast?): String {
        if (x == null) return "nil"
        return "${f(x.charge)}/${f(x.band)}/${f(x.baseline)}/${f(x.plannedSleepHours)}/${f(x.needHours)}/" +
            "${x.nights}/${x.confidence.name.lowercase(Locale.US)}"
    }

    private val e = 1_773_403_200.0
    private val h = 3600.0

    private fun rows(): List<RecoveryForecaster.DayRow> {
        val out = ArrayList<RecoveryForecaster.DayRow>()
        for (i in 0 until 20) {
            val day = DayOrdinal.key(DayOrdinal.ordinal("2026-03-01")!! + i)
            val rec: Double? = if (i % 6 == 5) null else (50 + (i * 7) % 23).toDouble()
            val strain: Double? = if (i % 9 == 4) null else (40 + (i * 5) % 30).toDouble() + 0.5
            val sleep: Double? = if (i % 8 == 6) null else (380 + (i * 13) % 90).toDouble()
            val eff: Double? = if (i % 4 == 3) null else if (i % 2 == 0) (85 + i % 7).toDouble() else 0.8 + (i % 5) * 0.03
            out.add(RecoveryForecaster.DayRow(day, rec, strain, sleep, eff))
        }
        out.add(RecoveryForecaster.DayRow("2026-03-21", 99.0, 99.0, 600.0, 0.99))
        out.add(RecoveryForecaster.DayRow("2026-03-25", 1.0, 1.0, 60.0, 0.5))
        return out.reversed()
    }

    private val window = RecoveryForecaster.Window(
        eveningDay = "2026-03-20", targetDay = "2026-03-21", openTs = e, closeTs = e + 16 * h,
        earliestTs = e + 9 * h, latestTs = e + 14 * h, wakeTs = e + 18.5 * h,
    )

    private fun tn(t: RecoveryForecaster.Tonight?): String {
        if (t == null) return "nil"
        val p = t.plan
        val head = "${f(t.suggestedTs - e)} ${f(t.anchorTs - e)} ${f(t.sliderTs - e)} ${f(t.sliderStartTs - e)} ${if (t.isOpen) 1 else 0} " +
            "${f(p.needHours)} ${p.needNights} ${f(p.efficiency)} ${f(p.todayEffort)} ${p.recentCharge.size} ${p.recentEffort.size}"
        val opts = t.options.joinToString(" ") { "${f(it.bedtimeTs - e)}=${fc(it.forecast)}" }
        return "$head | $opts"
    }

    private fun opts(list: List<RecoveryForecaster.BedtimeOption>): String =
        list.joinToString(" ") { "${f(it.bedtimeTs - e)}=${fc(it.forecast)}" }

    private fun oracleLines(): List<String> {
        val out = ArrayList<String>()
        out.add("PLANNED " + listOf(
            rf.plannedSleep(0.0, 28_800.0, 0.9),
            rf.plannedSleep(0.0, 28_800.0, 0.3),
            rf.plannedSleep(0.0, 28_800.0, 1.2),
            rf.plannedSleep(30_000.0, 28_800.0, 0.9),
            rf.plannedSleep(1000.5, 30_000.25, 0.875),
            rf.plannedSleep(0.0, 28_800.0, Double.NaN),
        ).joinToString(" ") { f(it) })
        out.add("SUGGEST " + listOf(
            rf.suggestedBedtime(e + 18.5 * h, 8.0, 0.9),
            rf.suggestedBedtime(e + 18.5 * h, 8.75, 0.88),
            rf.suggestedBedtime(e + 18.5 * h, 0.0, 0.9),
            rf.suggestedBedtime(e + 18.5 * h, -1.0, 0.9),
            rf.suggestedBedtime(e + 18.5 * h, 7.5, 0.2),
            rf.suggestedBedtime(e + 18.5 * h + 123, 9.333333, 0.93),
            rf.suggestedBedtime(e + 18.5 * h, 8.0, Double.POSITIVE_INFINITY),
        ).joinToString(" ") { f(it - e) })
        out.add("SNAP " + listOf(
            rf.snapBedtime(e + 10 * h + 7 * 60, e + 9 * h, e + 14 * h),
            rf.snapBedtime(e + 10 * h + 8 * 60, e + 9 * h, e + 14 * h),
            rf.snapBedtime(e + 10 * h + 450, e + 9 * h, e + 14 * h),
            rf.snapBedtime(e + 5 * h, e + 9 * h, e + 14 * h),
            rf.snapBedtime(e + 20 * h, e + 9 * h, e + 14 * h),
        ).joinToString(" ") { f(it - e) })
        out.add("MEDIAN " + listOf(
            rf.median(emptyList()), rf.median(listOf(3.0)), rf.median(listOf(4.0, 1.0)),
            rf.median(listOf(5.0, 1.0, 3.0)), rf.median(listOf(2.0, 8.0, 4.0, 6.0)),
        ).joinToString(" ") { f(it) })
        out.add("EFFFRAC " + listOf(null, Double.NaN, 0.0, -0.5, 0.42, 1.0, 1.0001, 88.0, 100.0, 100.5)
            .joinToString(" ") { f(rf.efficiencyFraction(it)) })
        out.add("EFFTYP " + listOf(
            rf.typicalEfficiency(emptyList()),
            rf.typicalEfficiency(listOf(null, Double.NaN)),
            rf.typicalEfficiency(listOf(90.0, 0.8, null, 85.0)),
            rf.typicalEfficiency(listOf(0.2, 0.3, 0.4)),
            rf.typicalEfficiency((0 until 20).map { (70 + it).toDouble() }),
        ).joinToString(" ") { f(it) })
        out.add("WAKE " + listOf(
            rf.typicalWakeMinute(emptyList()),
            rf.typicalWakeMinute(listOf(420.0)),
            rf.typicalWakeMinute(listOf(400.0, Double.POSITIVE_INFINITY, 430.0)),
            rf.typicalWakeMinute((0 until 20).map { (360 + it * 5).toDouble() }),
        ).joinToString(" ") { f(it) })

        val needNights = listOf(
            SleepNeed.Night("2026-03-01", 420.0, strain = 55.0),
            SleepNeed.Night("2026-03-02", 0.0, strain = 40.0),
            SleepNeed.Night("2026-03-03", 465.0, strain = 72.0),
            SleepNeed.Night("2026-03-03", 470.0, strain = 80.0),
            SleepNeed.Night("2025-11-01", 500.0),
            SleepNeed.Night("2026-03-04", null, strain = 90.0),
            SleepNeed.Night("2026-03-05", 395.0, strain = 61.0),
            SleepNeed.Night("2026-03-06", 510.0, strain = 35.0),
            SleepNeed.Night("2026-03-07", 430.0, strain = 66.0),
            SleepNeed.Night("2026-03-08", 445.0, strain = 81.0),
            SleepNeed.Night("2026-03-09", 410.0, strain = 58.0),
            SleepNeed.Night("2026-03-10", 400.0, strain = 77.0),
            SleepNeed.Night("bad-day", 400.0),
        )
        out.add("NEED " + listOf(
            rf.need(needNights, "2026-03-11", null),
            rf.need(needNights, "2026-03-11", 16),
            rf.need(needNights, "2026-03-04", 30),
            rf.need(emptyList(), "2026-03-11", null),
            rf.need(needNights, "nope", null),
        ).joinToString(" ") { "${f(it.first)}/${it.second}" })

        val plan = RecoveryForecaster.Plan(
            listOf(62.0, 58.0, 71.0, 66.0, 60.0, 55.0, 68.0, 72.0, 64.0, 59.0, 61.0),
            listOf(45.0, 52.0, 60.0, 38.0, 70.0), 66.0, 8.4, 9, e + 18.5 * h, 0.9,
        )
        val thinPlan = RecoveryForecaster.Plan(
            listOf(62.0, 58.0, 71.0, 66.0, 60.0), emptyList(), null, 8.0, 2, e + 18.5 * h, 0.85,
        )
        val coldPlan = RecoveryForecaster.Plan(
            listOf(62.0, 58.0, 71.0, 66.0), emptyList(), null, 8.0, 0, e + 18.5 * h, 0.85,
        )
        out.add("OPTIONS " + opts(rf.options(plan, e + 10.75 * h)))
        out.add("OPTIONS2 " + opts(rf.options(thinPlan, e + 18 * h)))
        out.add("OPTIONS3 ${rf.options(coldPlan, e + 10 * h).size}")
        out.add("PLANFC " + fc(rf.forecastAt(plan, e + 9 * h)) + " " + fc(rf.forecastAt(coldPlan, e)))

        val lastNight = listOf((e - 14 * h) to (e - 6 * h))
        val nap = listOf((e + 2 * h) to (e + 2.75 * h))
        val night = listOf((e + 11 * h) to (e + 18 * h))
        out.add("SLEEPSTART " + listOf(
            rf.sleepStart(emptyList(), e),
            rf.sleepStart(lastNight, e),
            rf.sleepStart(nap, e),
            rf.sleepStart(lastNight + nap + night, e),
            rf.sleepStart(listOf((e + 12 * h) to (e + 15 * h), (e + 11.5 * h) to (e + 14 * h)), e),
        ).joinToString(" ") { f(it?.let { v -> v - e }) })
        out.add("GATE " + listOf(
            rf.isOpen(e - 1, e, e + 16 * h, null),
            rf.isOpen(e, e, e + 16 * h, null),
            rf.isOpen(e + 8 * h, e, e + 16 * h, null),
            rf.isOpen(e + 16 * h, e, e + 16 * h, null),
            rf.isOpen(e + 10 * h, e, e + 16 * h, e + 11 * h),
            rf.isOpen(e + 11 * h, e, e + 16 * h, e + 11 * h),
            rf.isOpen(e + 12 * h, e, e + 16 * h, e + 11 * h),
        ).joinToString("") { if (it) "1" else "0" })

        val r = rows()
        out.add("TONIGHT1 " + tn(rf.tonight(r, window, null, e + 8 * h, lastNight, null)))
        out.add("TONIGHT2 " + tn(rf.tonight(r, window, 35, e + 8 * h, lastNight + nap, e + 12 * h + 8 * 60)))
        out.add("TONIGHT3 " + tn(rf.tonight(r, window, null, e + 8 * h, emptyList(), e + 20 * h)))
        out.add("TONIGHT4 " + tn(rf.tonight(r, window, null, e + 11.5 * h, lastNight + night, e + 13.5 * h)))
        out.add("TONIGHT5 " + tn(rf.tonight(r, window, null, e + 10 * h, night, null)))
        out.add("TONIGHT6 " + tn(rf.tonight(r.takeLast(4), window, null, e + 8 * h, emptyList(), null)))
        out.add("TONIGHT7 " + tn(rf.tonight(r, window, null, e + 16 * h, emptyList(), null)))
        val earlyWindow = RecoveryForecaster.Window(
            eveningDay = "2026-03-20", targetDay = "2026-03-21", openTs = e, closeTs = e + 16 * h,
            earliestTs = e + 9 * h, latestTs = e + 14 * h, wakeTs = e + 16.5 * h,
        )
        out.add("TONIGHT8 " + tn(rf.tonight(r, earlyWindow, null, e + 6 * h, emptyList(), null)))
        out.add("TONIGHT9 " + tn(rf.tonight(r, window, null, e + 11.4 * h, lastNight, e + 10 * h)))

        val fcast = HashMap<String, Double>()
        val lo = HashMap<String, Double>()
        val hi = HashMap<String, Double>()
        val act = HashMap<String, Double>()
        for (i in 0 until 18) {
            val day = DayOrdinal.key(DayOrdinal.ordinal("2026-04-01")!! + i)
            val c = (55 + (i * 11) % 25).toDouble()
            val b = (8 + i % 4).toDouble()
            fcast[day] = c
            if (i != 3) lo[day] = c - b
            if (i != 4) hi[day] = c + b
            if (i != 6) act[day] = if (i == 7) Double.POSITIVE_INFINITY else c + ((i * 17) % 23).toDouble() - 11
        }
        act["2026-04-17"] = fcast["2026-04-17"]!! - (8 + 16 % 4).toDouble()
        act["2026-04-18"] = fcast["2026-04-18"]!! + (8 + 17 % 4).toDouble()
        act["2026-05-01"] = 70.0
        val pairs = ForecastAccuracy.pairs(fcast, lo, hi, act)
        out.add("PAIRS ${pairs.size} " + pairs.joinToString(" ") {
            "${it.day.takeLast(2)}:${f(it.forecast)},${f(it.low)},${f(it.high)},${f(it.actual)}"
        })
        fun sm(s: ForecastAccuracy.Summary?): String = s?.let { "${it.scored}/${f(it.meanAbsError)}/${it.inside}" } ?: "nil"
        out.add("SUMMARY " + listOf(
            sm(ForecastAccuracy.summary(pairs)), sm(ForecastAccuracy.summary(pairs.take(4))),
            sm(ForecastAccuracy.summary(pairs.take(5).reversed())), sm(ForecastAccuracy.summary(emptyList())),
        ).joinToString(" "))
        return out
    }

    private val expected = listOf(
        "PLANNED 7.200000000 4.000000000 8.000000000 0.000000000 7.048550347 7.040000000",
        "SUGGEST 34200.000000000 30600.000000000 66600.000000000 66600.000000000 12600.000000000 29700.000000000 33300.000000000",
        "SNAP 36000.000000000 36900.000000000 36900.000000000 32400.000000000 50400.000000000",
        "MEDIAN nil 3.000000000 2.500000000 3.000000000 5.000000000",
        "EFFFRAC nil nil nil nil 0.420000000 1.000000000 0.010001000 0.880000000 1.000000000 nil",
        "EFFTYP 0.880000000 0.880000000 0.850000000 0.500000000 0.825000000",
        "WAKE nil 420.000000000 415.000000000 422.500000000",
        "NEED 9.039194032/8 9.724610698/8 8.558041667/2 8.000000000/0 8.000000000/0",
        "OPTIONS 38700.000000000=51.000000000/8.000000000/63.272727273/6.975000000/8.400000000/11/solid 35100.000000000=53.000000000/8.000000000/63.272727273/7.875000000/8.400000000/11/solid 42300.000000000=50.000000000/8.000000000/63.272727273/6.075000000/8.400000000/11/solid",
        "OPTIONS2 64800.000000000=50.000000000/14.000000000/63.400000000/0.425000000/8.000000000/5/building 61200.000000000=51.000000000/14.000000000/63.400000000/1.275000000/8.000000000/5/building 68400.000000000=49.000000000/14.000000000/63.400000000/0.000000000/8.000000000/5/building",
        "OPTIONS3 0",
        "PLANFC 54.000000000/8.000000000/63.272727273/8.550000000/8.400000000/11/solid nil",
        "SLEEPSTART nil nil nil 39600.000000000 43200.000000000",
        "GATE 0110100",
        "TONIGHT1 36000.000000000 36000.000000000 36000.000000000 32400.000000000 1 7.229166667 18 0.875000000 45.500000000 17 18 | 36000.000000000=69.000000000/8.000000000/62.928571429/7.437500000/7.229166667/14/solid 32400.000000000=70.000000000/8.000000000/62.928571429/8.312500000/7.229166667/14/solid 39600.000000000=67.000000000/8.000000000/62.928571429/6.562500000/7.229166667/14/solid",
        "TONIGHT2 36000.000000000 44100.000000000 44100.000000000 32400.000000000 1 7.229166667 18 0.875000000 45.500000000 17 18 | 44100.000000000=65.000000000/8.000000000/62.928571429/5.468750000/7.229166667/14/solid 40500.000000000=67.000000000/8.000000000/62.928571429/6.343750000/7.229166667/14/solid 47700.000000000=63.000000000/8.000000000/62.928571429/4.593750000/7.229166667/14/solid",
        "TONIGHT3 36000.000000000 36000.000000000 36000.000000000 32400.000000000 1 7.229166667 18 0.875000000 45.500000000 17 18 | 36000.000000000=69.000000000/8.000000000/62.928571429/7.437500000/7.229166667/14/solid 32400.000000000=70.000000000/8.000000000/62.928571429/8.312500000/7.229166667/14/solid 39600.000000000=67.000000000/8.000000000/62.928571429/6.562500000/7.229166667/14/solid",
        "TONIGHT4 36000.000000000 48600.000000000 48600.000000000 41400.000000000 0 7.229166667 18 0.875000000 45.500000000 17 18 | 48600.000000000=63.000000000/8.000000000/62.928571429/4.375000000/7.229166667/14/solid 45000.000000000=64.000000000/8.000000000/62.928571429/5.250000000/7.229166667/14/solid 52200.000000000=61.000000000/8.000000000/62.928571429/3.500000000/7.229166667/14/solid",
        "TONIGHT5 36000.000000000 36000.000000000 36000.000000000 36000.000000000 1 7.229166667 18 0.875000000 45.500000000 17 18 | 36000.000000000=69.000000000/8.000000000/62.928571429/7.437500000/7.229166667/14/solid 32400.000000000=70.000000000/8.000000000/62.928571429/8.312500000/7.229166667/14/solid 39600.000000000=67.000000000/8.000000000/62.928571429/6.562500000/7.229166667/14/solid",
        "TONIGHT6 nil",
        "TONIGHT7 36000.000000000 50400.000000000 50400.000000000 50400.000000000 0 7.229166667 18 0.875000000 45.500000000 17 18 | 50400.000000000=62.000000000/8.000000000/62.928571429/3.937500000/7.229166667/14/solid 46800.000000000=64.000000000/8.000000000/62.928571429/4.812500000/7.229166667/14/solid 54000.000000000=60.000000000/8.000000000/62.928571429/3.062500000/7.229166667/14/solid",
        "TONIGHT8 28800.000000000 32400.000000000 32400.000000000 32400.000000000 1 7.229166667 18 0.875000000 45.500000000 17 18 | 32400.000000000=67.000000000/8.000000000/62.928571429/6.562500000/7.229166667/14/solid 28800.000000000=69.000000000/8.000000000/62.928571429/7.437500000/7.229166667/14/solid 36000.000000000=65.000000000/8.000000000/62.928571429/5.687500000/7.229166667/14/solid",
        "TONIGHT9 36000.000000000 41400.000000000 41400.000000000 41400.000000000 1 7.229166667 18 0.875000000 45.500000000 17 18 | 41400.000000000=66.000000000/8.000000000/62.928571429/6.125000000/7.229166667/14/solid 37800.000000000=68.000000000/8.000000000/62.928571429/7.000000000/7.229166667/14/solid 45000.000000000=64.000000000/8.000000000/62.928571429/5.250000000/7.229166667/14/solid",
        "PAIRS 14 01:55.000000000,47.000000000,63.000000000,44.000000000 02:66.000000000,57.000000000,75.000000000,72.000000000 03:77.000000000,67.000000000,87.000000000,77.000000000 06:60.000000000,51.000000000,69.000000000,65.000000000 09:68.000000000,60.000000000,76.000000000,78.000000000 10:79.000000000,70.000000000,88.000000000,83.000000000 11:65.000000000,55.000000000,75.000000000,63.000000000 12:76.000000000,65.000000000,87.000000000,68.000000000 13:62.000000000,54.000000000,70.000000000,71.000000000 14:73.000000000,64.000000000,82.000000000,76.000000000 15:59.000000000,49.000000000,69.000000000,56.000000000 16:70.000000000,59.000000000,81.000000000,61.000000000 17:56.000000000,48.000000000,64.000000000,48.000000000 18:67.000000000,58.000000000,76.000000000,76.000000000",
        "SUMMARY 14/6.214285714/11 nil 5/6.400000000/3 nil",
    )

    @Test
    fun everyOracleLineMatchesSwift() {
        val got = oracleLines()
        assertEquals(expected.size, got.size)
        for (i in expected.indices) assertEquals(expected[i], got[i])
    }

    /** The stored forecast freezes once the night's sleep starts (twin of the Swift freeze test). The sleep
     *  starts at the suggested bedtime (22:00), so the past-bedtime clamp leaves the anchor where it was. */
    @Test
    fun forecastFreezesOnceTheNightsSleepStarts() {
        val r = rows()
        val night = listOf((e + 10 * h) to (e + 18 * h))
        val before = rf.tonight(r, window, null, e + 9.5 * h, night, null)
        val after = rf.tonight(r, window, null, e + 10 * h, night, null)
        val napOnly = rf.tonight(r, window, null, e + 10 * h, listOf((e + 2 * h) to (e + 2.75 * h)), null)
        assertEquals(true, before?.isOpen)
        assertEquals(false, after?.isOpen)
        assertEquals(true, napOnly?.isOpen)
        assertEquals(before?.headline, after?.headline)
    }
}
