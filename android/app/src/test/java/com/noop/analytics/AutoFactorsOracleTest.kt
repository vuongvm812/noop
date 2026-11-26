package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * Swift/Kotlin parity for the automatic What Moves You factors, pinned by ORACLE: [EXPECTED] is the
 * verbatim stdout of the Swift twins (`AutoFactors`, `WhatMovesYouHighlights`, `EffectRanker`) compiled
 * standalone with `swiftc -O` over the same synthetic fixtures. Swift `AutoFactorsTests` pins the same
 * text from the other side. All data is generated here (MINSTD), nothing is read from a real export.
 */
class AutoFactorsOracleTest {

    private class Gen(var s: Long) {
        fun next(): Long { s = (s * 48271L) % 2147483647L; return s }
    }

    private fun f9(v: Double): String = String.format(Locale.US, "%.9f", v)

    private class Fixture(
        val workouts: List<AutoFactors.Workout>,
        val sleeps: List<AutoFactors.Sleep>,
        val days: List<AutoFactors.Day>,
        val today: String,
        val lateWorkoutDays: Set<String>,
        val lateBedDays: Set<String>,
    )

    private fun fixture(seed: Long, base: String, n: Int, offA: Long, offB: Long): Fixture {
        val g = Gen(seed)
        val baseIdx = AutoFactors.dayIndex(base)!!
        val ws = ArrayList<AutoFactors.Workout>()
        val ss = ArrayList<AutoFactors.Sleep>()
        val ds = ArrayList<AutoFactors.Day>()
        val lateW = HashSet<String>()
        val lateB = HashSet<String>()
        for (i in 0 until n) {
            val idx = baseIdx + i
            val off = if (i < n / 2) offA else offB
            val midnight = idx * 86_400L - off
            var bedMin = 22L * 60 + (g.next() % 90) - 30
            if (g.next() % 6 == 0L) { bedMin += 150; lateB.add(AutoFactors.dayKey(idx)) }
            // A 00:40 onset after a 00:05–00:25 workout: the workout starts on the next calendar day but
            // belongs to this night, and is late.
            val afterMidnight = i % 23 == 5
            if (afterMidnight) { bedMin = 24L * 60 + 40; lateB.add(AutoFactors.dayKey(idx)) }
            val onset = midnight + bedMin * 60
            val dur = 6L * 3600 + (g.next() % 120) * 60
            ss.add(AutoFactors.Sleep(onset, onset + dur, off))
            if (g.next() % 7 == 0L) {
                val nap = midnight + 14L * 3600
                ss.add(AutoFactors.Sleep(nap, nap + 40L * 60, off))
            }
            val w = g.next() % 4
            if (w == 0L) {
                // rest day
            } else if (w == 1L) {
                val end = onset - 600 - (g.next() % 90) * 60
                ws.add(AutoFactors.Workout(end - 2700, end, off))
                lateW.add(AutoFactors.dayKey(idx))
            } else {
                val start = midnight + 7L * 3600 + (g.next() % 300) * 60
                ws.add(AutoFactors.Workout(start, start + 2700, off))
                if (w == 3L) {
                    val s2 = midnight + 17L * 3600
                    ws.add(AutoFactors.Workout(s2, s2 + 1800, off))
                }
            }
            if (afterMidnight) {
                val s0 = midnight + (24L * 60 + 5) * 60
                ws.add(AutoFactors.Workout(s0, s0 + 20L * 60, off))
                lateW.add(AutoFactors.dayKey(idx))
            }
            // A workout still running at onset is neither late nor a control.
            if (i % 19 == 7) {
                ws.add(AutoFactors.Workout(onset - 1200, onset + 600, off))
            }
            val effort: Double? = if (g.next() % 15 == 0L) null else (20 + g.next() % 60).toDouble() + 0.25
            val steps: Int? = if (g.next() % 11 == 0L) null else (3000 + g.next() % 12000).toInt()
            ds.add(AutoFactors.Day(AutoFactors.dayKey(idx), effort, steps))
        }
        return Fixture(ws, ss, ds, AutoFactors.dayKey(baseIdx + n - 1), lateW, lateB)
    }

    private fun factorLines(tag: String, r: AutoFactors.Result, out: MutableList<String>) {
        for (f in AutoFactors.Factor.entries) {
            val y = r.behaviors[f.key].orEmpty().sorted()
            val n = r.controls[f.key].orEmpty().sorted()
            out.add("$tag ${f.raw} yes=${y.size} no=${n.size} yesHead=${y.take(3).joinToString(",")} noHead=${n.take(3).joinToString(",")}")
            out.add("$tag ${f.raw} yesAll=${y.joinToString(",")}")
        }
    }

    private fun oracle(): String {
        val out = ArrayList<String>()

        // 1. Day arithmetic.
        val dayLine = ArrayList<String>()
        for (d in listOf("1970-01-01", "1969-12-31", "2000-02-29", "2024-03-01", "2026-11-26", "2100-03-01", "0001-01-01")) {
            val i = AutoFactors.dayIndex(d)!!
            dayLine.add("$d=$i>${AutoFactors.dayKey(i)}")
        }
        for (bad in listOf("2026-1-01", "2026-13-01", "abcd-ef-gh", "2026-+1-01", "")) {
            dayLine.add("$bad=${AutoFactors.dayIndex(bad)?.toString() ?: "nil"}")
        }
        out.add("DAYS " + dayLine.joinToString(" "))

        // 2. Night keys and onset minutes across midnight and offsets.
        val nightLine = ArrayList<String>()
        val base = AutoFactors.dayIndex("2026-03-28")!! * 86_400L
        for ((local, off) in listOf(
            23L * 3600 to 3600L, 30L * 3600 + 1800 to 3600L, 21L * 3600 + 900 to -18_000L,
            25L * 3600 to -18_000L, 12L * 3600 to 0L, 11L * 3600 + 3540 to 0L, 35L * 3600 + 3599 to 19_800L,
        )) {
            val s = AutoFactors.Sleep(base + local - off, base + local - off + 7L * 3600, off)
            nightLine.add("${AutoFactors.dayKey(AutoFactors.nightIndex(s))}@${AutoFactors.minutesAfterNoon(s)}")
        }
        out.add("NIGHTS " + nightLine.joinToString(" "))

        // 3. Percentiles.
        val pv = listOf(3.0, 1.0, 4.0, 1.0, 5.0, 9.0, 2.0, 6.0, 5.0, 3.0, 5.0).sorted()
        out.add("PCT " + listOf(0.0, 0.25, 0.5, 0.75, 1.0, 0.1).joinToString(" ") { f9(AutoFactors.percentile(pv, it)) } +
            " one=" + f9(AutoFactors.percentile(listOf(7.5), 0.75)))

        // 4. Full factor sets: a DST switch (+1h → +2h) and a west-of-UTC user.
        val a = fixture(12345, "2026-01-15", 140, 3600, 7200)
        factorLines("EU", AutoFactors.factors(a.workouts, a.sleeps, a.days, a.today), out)
        val b = fixture(777, "2026-02-20", 120, -18_000, -14_400)
        factorLines("US", AutoFactors.factors(b.workouts, b.sleeps, b.days, b.today), out)
        val c = fixture(4242, "2026-05-01", 10, 0, 0)
        factorLines("THIN", AutoFactors.factors(c.workouts, c.sleeps, c.days, c.today), out)

        // 5. Auto + journal factors ranked in ONE family.
        val fa = AutoFactors.factors(a.workouts, a.sleeps, a.days, a.today)
        val g = Gen(99)
        val hrv = HashMap<String, Double>()
        val rest = HashMap<String, Double>()
        val alcYes = HashSet<String>()
        val alcNo = HashSet<String>()
        val baseIdx = AutoFactors.dayIndex("2026-01-15")!!
        for (i in 0 until 141) {
            val key = AutoFactors.dayKey(baseIdx + i)
            val prev = AutoFactors.dayKey(baseIdx + i - 1)
            var h = 60.0 + (g.next() % 100).toDouble() / 10.0
            if (prev in a.lateWorkoutDays) h -= 6
            var r = 80.0 + (g.next() % 80).toDouble() / 10.0
            if (prev in a.lateBedDays) r -= 7
            if (prev in alcYes) h -= 3
            hrv[key] = h
            rest[key] = r
            val j = g.next() % 5
            if (j == 0L) alcYes.add(key) else if (j < 4) alcNo.add(key)
        }
        val behaviors = HashMap(fa.behaviors)
        val controls = HashMap(fa.controls)
        behaviors["Alcohol"] = alcYes
        controls["Alcohol"] = alcNo
        val family = EffectRanker.rankFamilyExcludingSameDay(behaviors, controls, mapOf("HRV" to hrv, "Rest" to rest),
            noSameDay = fa.behaviors.keys)
        for (o in listOf("HRV", "Rest")) {
            for (r in family[o].orEmpty()) {
                out.add("RANK $o ${r.behavior} lag=${r.lag} d=${f9(r.effect.cohensD)} p=${f9(r.effect.pApprox)} q=${f9(r.effect.pAdjusted)} sig=${r.effect.significant} n=${r.effect.nWith}/${r.effect.nWithout} conf=${r.confidence.name.lowercase(Locale.US)}")
            }
        }
        val alone = EffectRanker.rankFamily(mapOf("Alcohol" to alcYes), mapOf("Alcohol" to alcNo), mapOf("HRV" to hrv, "Rest" to rest))
        for (o in listOf("HRV", "Rest")) {
            for (r in alone[o].orEmpty()) out.add("ALONE $o ${r.behavior} lag=${r.lag} q=${f9(r.effect.pAdjusted)} sig=${r.effect.significant}")
        }
        val top = WhatMovesYouHighlights.top(family, listOf("HRV", "Rest"))
        out.add("TOP " + top.joinToString(" ") { "${it.outcome}:${it.behavior}:${it.lag}" })
        out.add("TOP1 " + WhatMovesYouHighlights.top(family, listOf("Rest", "HRV"), limit = 1).joinToString(" ") { "${it.outcome}:${it.behavior}" })
        return out.joinToString("\n")
    }

    @Test
    fun matchesSwiftOracle() {
        val expected = EXPECTED.lines()
        val actual = oracle().lines()
        for (i in 0 until minOf(expected.size, actual.size)) assertEquals("line ${i + 1}", expected[i], actual[i])
        assertEquals(expected.size, actual.size)
    }

    @Test
    fun factorKeysRoundTrip() {
        for (f in AutoFactors.Factor.entries) assertEquals(f, AutoFactors.factorForKey(f.key))
        assertEquals(null, AutoFactors.factorForKey("Alcohol"))
        assertEquals(null, AutoFactors.factorForKey("auto.unknown"))
    }

    private companion object {
        val EXPECTED = """
DAYS 1970-01-01=0>1970-01-01 1969-12-31=-1>1969-12-31 2000-02-29=11016>2000-02-29 2024-03-01=19783>2024-03-01 2026-11-26=20783>2026-11-26 2100-03-01=47541>2100-03-01 0001-01-01=-719162>0001-01-01 2026-1-01=nil 2026-13-01=nil abcd-ef-gh=nil 2026-+1-01=nil =nil
NIGHTS 2026-03-28@660 2026-03-28@1110 2026-03-28@555 2026-03-28@780 2026-03-28@0 2026-03-27@1439 2026-03-28@1439
PCT 1.000000000 2.500000000 4.000000000 5.000000000 9.000000000 1.000000000 one=7.500000000
EU lateWorkout yes=27 no=66 yesHead=2026-01-20,2026-02-12,2026-02-22 noHead=2026-01-15,2026-01-18,2026-01-22
EU lateWorkout yesAll=2026-01-20,2026-02-12,2026-02-22,2026-03-01,2026-03-04,2026-03-05,2026-03-07,2026-03-12,2026-03-18,2026-03-21,2026-03-22,2026-03-29,2026-03-30,2026-04-12,2026-04-15,2026-04-22,2026-05-03,2026-05-04,2026-05-06,2026-05-10,2026-05-14,2026-05-15,2026-05-17,2026-05-18,2026-05-26,2026-05-29,2026-05-31
EU hardEffort yes=38 no=63 yesHead=2026-01-18,2026-01-19,2026-01-23 noHead=2026-01-16,2026-01-17,2026-01-20
EU hardEffort yesAll=2026-01-18,2026-01-19,2026-01-23,2026-01-26,2026-01-31,2026-02-02,2026-02-03,2026-02-04,2026-02-05,2026-02-07,2026-02-13,2026-02-18,2026-02-19,2026-02-24,2026-02-26,2026-03-03,2026-03-08,2026-03-09,2026-03-10,2026-03-15,2026-03-26,2026-03-27,2026-03-31,2026-04-04,2026-04-06,2026-04-07,2026-04-08,2026-04-11,2026-04-12,2026-04-17,2026-04-23,2026-04-24,2026-05-05,2026-05-07,2026-05-09,2026-05-12,2026-05-14,2026-05-20
EU restDay yes=43 no=97 yesHead=2026-01-16,2026-01-17,2026-01-19 noHead=2026-01-15,2026-01-18,2026-01-20
EU restDay yesAll=2026-01-16,2026-01-17,2026-01-19,2026-01-27,2026-01-30,2026-01-31,2026-02-03,2026-02-05,2026-02-08,2026-02-09,2026-02-14,2026-02-15,2026-02-16,2026-02-18,2026-02-19,2026-02-20,2026-02-23,2026-02-24,2026-02-25,2026-02-26,2026-03-10,2026-03-15,2026-04-03,2026-04-04,2026-04-06,2026-04-09,2026-04-13,2026-04-16,2026-04-18,2026-04-19,2026-04-20,2026-04-24,2026-04-26,2026-04-28,2026-04-30,2026-05-05,2026-05-21,2026-05-23,2026-05-24,2026-05-25,2026-05-27,2026-05-30,2026-06-03
EU lateBedtime yes=28 no=69 yesHead=2026-01-19,2026-01-20,2026-01-25 noHead=2026-01-16,2026-01-18,2026-01-21
EU lateBedtime yesAll=2026-01-19,2026-01-20,2026-01-25,2026-02-03,2026-02-05,2026-02-12,2026-02-13,2026-02-15,2026-02-20,2026-03-01,2026-03-07,2026-03-11,2026-03-12,2026-03-21,2026-03-22,2026-03-25,2026-03-27,2026-03-30,2026-04-06,2026-04-22,2026-04-30,2026-05-01,2026-05-03,2026-05-07,2026-05-09,2026-05-15,2026-05-20,2026-05-31
EU irregularBedtime yes=37 no=63 yesHead=2026-01-19,2026-01-20,2026-01-25 noHead=2026-01-23,2026-01-24,2026-01-26
EU irregularBedtime yesAll=2026-01-19,2026-01-20,2026-01-25,2026-02-01,2026-02-02,2026-02-03,2026-02-05,2026-02-10,2026-02-12,2026-02-13,2026-02-15,2026-02-20,2026-03-01,2026-03-07,2026-03-11,2026-03-12,2026-03-15,2026-03-16,2026-03-19,2026-03-21,2026-03-22,2026-03-25,2026-03-27,2026-03-28,2026-03-29,2026-03-30,2026-03-31,2026-04-06,2026-04-22,2026-04-30,2026-05-01,2026-05-03,2026-05-07,2026-05-09,2026-05-15,2026-05-20,2026-05-31
EU lowSteps yes=32 no=61 yesHead=2026-01-19,2026-01-21,2026-01-23 noHead=2026-01-16,2026-01-20,2026-01-25
EU lowSteps yesAll=2026-01-19,2026-01-21,2026-01-23,2026-01-29,2026-02-05,2026-02-06,2026-02-10,2026-02-11,2026-02-14,2026-02-24,2026-02-28,2026-03-06,2026-03-09,2026-03-10,2026-03-11,2026-03-22,2026-03-23,2026-03-24,2026-03-27,2026-04-02,2026-04-08,2026-04-12,2026-04-17,2026-04-25,2026-04-30,2026-05-05,2026-05-08,2026-05-12,2026-05-20,2026-05-27,2026-05-29,2026-05-31
EU highSteps yes=38 no=61 yesHead=2026-01-15,2026-01-17,2026-01-18 noHead=2026-01-16,2026-01-20,2026-01-25
EU highSteps yesAll=2026-01-15,2026-01-17,2026-01-18,2026-01-24,2026-01-26,2026-01-30,2026-02-01,2026-02-02,2026-02-07,2026-02-08,2026-02-15,2026-02-16,2026-02-21,2026-02-25,2026-02-26,2026-03-01,2026-03-05,2026-03-07,2026-03-08,2026-03-16,2026-03-26,2026-03-29,2026-04-04,2026-04-05,2026-04-07,2026-04-16,2026-04-18,2026-04-20,2026-04-21,2026-05-02,2026-05-03,2026-05-09,2026-05-11,2026-05-17,2026-05-19,2026-05-24,2026-06-01,2026-06-02
US lateWorkout yes=35 no=58 yesHead=2026-02-20,2026-02-24,2026-02-25 noHead=2026-02-22,2026-02-23,2026-02-27
US lateWorkout yesAll=2026-02-20,2026-02-24,2026-02-25,2026-02-26,2026-02-28,2026-03-05,2026-03-06,2026-03-07,2026-03-10,2026-03-12,2026-03-16,2026-03-20,2026-03-21,2026-03-28,2026-04-02,2026-04-12,2026-04-15,2026-04-20,2026-04-21,2026-04-25,2026-04-28,2026-04-29,2026-05-05,2026-05-07,2026-05-08,2026-05-13,2026-05-18,2026-05-19,2026-05-28,2026-05-31,2026-06-02,2026-06-06,2026-06-08,2026-06-12,2026-06-17
US hardEffort yes=29 no=55 yesHead=2026-02-23,2026-02-25,2026-03-01 noHead=2026-02-21,2026-02-24,2026-02-26
US hardEffort yesAll=2026-02-23,2026-02-25,2026-03-01,2026-03-05,2026-03-12,2026-03-16,2026-03-20,2026-03-26,2026-04-02,2026-04-08,2026-04-11,2026-04-12,2026-04-13,2026-04-14,2026-04-24,2026-04-25,2026-04-28,2026-05-04,2026-05-07,2026-05-09,2026-05-14,2026-05-17,2026-06-03,2026-06-04,2026-06-10,2026-06-12,2026-06-15,2026-06-16,2026-06-17
US restDay yes=24 no=96 yesHead=2026-02-21,2026-02-25,2026-03-02 noHead=2026-02-20,2026-02-22,2026-02-23
US restDay yesAll=2026-02-21,2026-02-25,2026-03-02,2026-03-03,2026-03-14,2026-03-15,2026-03-24,2026-03-26,2026-03-27,2026-03-30,2026-04-04,2026-04-08,2026-04-11,2026-04-17,2026-04-18,2026-04-26,2026-04-27,2026-05-10,2026-05-11,2026-05-16,2026-05-21,2026-05-22,2026-05-30,2026-06-07
US lateBedtime yes=26 no=68 yesHead=2026-02-20,2026-02-21,2026-02-25 noHead=2026-02-22,2026-02-24,2026-03-01
US lateBedtime yesAll=2026-02-20,2026-02-21,2026-02-25,2026-02-26,2026-03-09,2026-03-10,2026-03-19,2026-03-20,2026-03-25,2026-03-28,2026-03-29,2026-04-01,2026-04-09,2026-04-12,2026-04-15,2026-04-24,2026-04-30,2026-05-02,2026-05-05,2026-05-09,2026-05-19,2026-05-21,2026-05-28,2026-06-06,2026-06-09,2026-06-10
US irregularBedtime yes=30 no=55 yesHead=2026-02-24,2026-02-25,2026-02-27 noHead=2026-03-01,2026-03-02,2026-03-03
US irregularBedtime yesAll=2026-02-24,2026-02-25,2026-02-27,2026-03-09,2026-03-10,2026-03-14,2026-03-19,2026-03-20,2026-03-23,2026-03-25,2026-03-26,2026-03-28,2026-03-29,2026-04-01,2026-04-09,2026-04-12,2026-04-15,2026-04-23,2026-04-24,2026-04-30,2026-05-02,2026-05-05,2026-05-09,2026-05-13,2026-05-19,2026-05-21,2026-05-28,2026-06-06,2026-06-09,2026-06-10
US lowSteps yes=29 no=58 yesHead=2026-02-21,2026-02-22,2026-02-23 noHead=2026-02-20,2026-02-24,2026-02-26
US lowSteps yesAll=2026-02-21,2026-02-22,2026-02-23,2026-02-25,2026-03-01,2026-03-07,2026-03-13,2026-03-16,2026-03-22,2026-03-24,2026-04-06,2026-04-09,2026-04-10,2026-04-14,2026-04-15,2026-04-19,2026-04-23,2026-04-25,2026-04-26,2026-04-27,2026-04-28,2026-05-04,2026-05-20,2026-05-23,2026-05-30,2026-06-03,2026-06-11,2026-06-12,2026-06-18
US highSteps yes=25 no=58 yesHead=2026-02-28,2026-03-05,2026-03-19 noHead=2026-02-20,2026-02-24,2026-02-26
US highSteps yesAll=2026-02-28,2026-03-05,2026-03-19,2026-03-21,2026-03-26,2026-03-29,2026-03-30,2026-03-31,2026-04-03,2026-04-04,2026-04-07,2026-04-12,2026-04-18,2026-04-20,2026-04-29,2026-05-03,2026-05-07,2026-05-12,2026-05-18,2026-05-21,2026-05-24,2026-05-25,2026-06-04,2026-06-13,2026-06-16
THIN lateWorkout yes=3 no=4 yesHead=2026-05-05,2026-05-06,2026-05-07 noHead=2026-05-03,2026-05-04,2026-05-09
THIN lateWorkout yesAll=2026-05-05,2026-05-06,2026-05-07
THIN hardEffort yes=0 no=0 yesHead= noHead=
THIN hardEffort yesAll=
THIN restDay yes=0 no=0 yesHead= noHead=
THIN restDay yesAll=
THIN lateBedtime yes=0 no=0 yesHead= noHead=
THIN lateBedtime yesAll=
THIN irregularBedtime yes=1 no=3 yesHead=2026-05-06 noHead=2026-05-05,2026-05-07,2026-05-09
THIN irregularBedtime yesAll=2026-05-06
THIN lowSteps yes=0 no=0 yesHead= noHead=
THIN lowSteps yesAll=
THIN highSteps yes=0 no=0 yesHead= noHead=
THIN highSteps yesAll=
RANK HRV auto.lateWorkout lag=1 d=-1.732617581 p=0.000000000 q=0.000000005 sig=true n=27/66 conf=solid
RANK HRV Alcohol lag=1 d=-0.797612665 p=0.000349855 q=0.002973764 sig=true n=29/90 conf=solid
RANK HRV auto.restDay lag=1 d=0.396179234 p=0.022721816 q=0.113027583 sig=false n=43/97 conf=solid
RANK HRV auto.lateBedtime lag=1 d=-0.365042644 p=0.133678380 q=0.454506493 sig=false n=28/69 conf=solid
RANK HRV auto.hardEffort lag=2 d=-0.264008265 p=0.195576201 q=0.554132568 sig=false n=38/63 conf=solid
RANK HRV auto.lowSteps lag=2 d=0.202355251 p=0.339382319 q=0.655204249 sig=false n=32/60 conf=solid
RANK HRV auto.irregularBedtime lag=1 d=-0.089421360 p=0.677581028 q=0.942287828 sig=false n=37/63 conf=solid
RANK HRV auto.highSteps lag=1 d=-0.061876649 p=0.760505480 q=0.942287828 sig=false n=38/61 conf=solid
RANK Rest auto.lateBedtime lag=1 d=-2.791760242 p=0.000000000 q=0.000000000 sig=true n=28/69 conf=solid
RANK Rest auto.irregularBedtime lag=1 d=-1.698483656 p=0.000000000 q=0.000000005 sig=true n=37/63 conf=solid
RANK Rest auto.lateWorkout lag=1 d=-0.544237107 p=0.023270385 q=0.113027583 sig=false n=27/66 conf=solid
RANK Rest auto.lowSteps lag=2 d=-0.541766126 p=0.012651433 q=0.086029747 sig=false n=32/60 conf=solid
RANK Rest auto.hardEffort lag=2 d=-0.423739509 p=0.048391600 q=0.205664302 sig=false n=38/63 conf=solid
RANK Rest Alcohol lag=1 d=0.376411922 p=0.058818984 q=0.222205052 sig=false n=29/90 conf=solid
RANK Rest auto.restDay lag=2 d=0.232801119 p=0.165686283 q=0.512121239 sig=false n=42/97 conf=solid
RANK Rest auto.highSteps lag=1 d=0.227458884 p=0.276683372 q=0.655204249 sig=false n=38/61 conf=solid
ALONE HRV Alcohol lag=1 q=0.002099128 sig=true
ALONE Rest Alcohol lag=1 q=0.176456953 sig=false
TOP Rest:auto.lateBedtime:1 HRV:auto.lateWorkout:1 Rest:auto.irregularBedtime:1
TOP1 Rest:auto.lateBedtime
""".trimIndent()
    }
}
