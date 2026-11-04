package com.noop.analytics

import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Faithful Kotlin port of
 * Packages/StrandAnalytics/Tests/StrandAnalyticsTests/RestWiringImpactTests.swift (Wave 0 · SL1/T1,
 * PR #456).
 *
 * Behavioral validation of SL1/T1: score the SAME night for different sleeper HISTORIES and show how
 * Rest moves once real regularity + personalized need replace neutral-0.5 / fixed-8h. Prints an
 * old→new table for eyeballing magnitude/direction, and pins the load-bearing directions. Same
 * inputs and assertions as the Swift test.
 */
class RestWiringImpactTest {

    // One fixed scored night so only (need, consistency) vary: 7h TST, 90% eff, 1h deep, 1.5h REM.
    private val tst = 7.0 * 3600
    private val eff = 0.90
    private val deep = 1.0 * 3600
    private val rem = 1.5 * 3600

    private fun restOld(): Double =
        RestScorer.rest(
            asleepSeconds = tst, efficiency = eff, deepSeconds = deep, remSeconds = rem,
            sleepNeedHours = RestScorer.defaultSleepNeedHours, consistency = null,   // null → neutral 0.5
        )!!

    private data class NewRest(val rest: Double, val need: Double, val cons: Double)

    /** Score the fixed night on 2026-06-15 against a 14-night history ending the day before. Each history
     *  night starts at `onsetHour` (UTC) of the previous evening and lasts `hours`. Mirrors Swift. */
    private fun restNew(history: List<Double>, onsets: List<Double>? = null, age: Int?): NewRest {
        val scored = "2026-06-15"
        val d = DayOrdinal.ordinal(scored)!!
        val nights = ArrayList<SleepNeed.Night>()
        val windows = ArrayList<SleepRegularity.Window>()
        for ((i, h) in history.withIndex()) {
            val wakeOrd = d - history.size + i
            val onsetHour = onsets?.get(i) ?: 23.0
            val start = (wakeOrd - 1).toLong() * 86_400L + (onsetHour * 3600).toLong()
            windows.add(SleepRegularity.Window(start, start + (h * 3600).toLong(), 0L))
            nights.add(SleepNeed.Night(DayOrdinal.key(wakeOrd), h * 60))
        }
        val ctx = SleepScoring.contexts(nights, windows, listOf(scored), age)[scored]!!
        val rest = RestScorer.rest(
            asleepSeconds = tst, efficiency = eff, deepSeconds = deep, remSeconds = rem,
            sleepNeedHours = ctx.rest.needHours, consistency = ctx.rest.consistency, age = age,
        )!!
        return NewRest(rest, ctx.rest.needHours, ctx.rest.consistency ?: -1.0)
    }

    @Test
    fun archetypeImpactTable() {
        val regular = List(14) { 8.0 }
        val irregular = List(14) { 8.0 }
        val irregularOnsets = (0 until 14).map { if (it % 2 == 0) 21.0 else 26.0 }   // same length, onset swings 5 h
        val chronicShort = List(14) { 5.5 }
        val longSleeper = List(14) { 9.2 }

        val old = restOld()
        val reg = restNew(regular, age = 30)
        val irr = restNew(irregular, irregularOnsets, age = 30)
        val shortS = restNew(chronicShort, age = 30)
        val longS = restNew(longSleeper, age = 30)

        println(String.format(Locale.US, "cold start (neutral 0.5, need 8h):     Rest %5.1f", old))
        println(String.format(Locale.US, "regular good sleeper:  need %.1f cons %.2f  Rest %5.1f", reg.need, reg.cons, reg.rest))
        println(String.format(Locale.US, "irregular sleeper:     need %.1f cons %.2f  Rest %5.1f", irr.need, irr.cons, irr.rest))
        println(String.format(Locale.US, "chronic short (5.5h):  need %.1f cons %.2f  Rest %5.1f", shortS.need, shortS.cons, shortS.rest))
        println(String.format(Locale.US, "long sleeper (9.2h):   need %.1f cons %.2f  Rest %5.1f", longS.need, longS.cons, longS.rest))

        // Load-bearing directions (SL1): a REGULAR history lifts Rest above an IRREGULAR one for the
        // identical night — regularity now actually matters.
        assertTrue(reg.rest > irr.rest)
        // T1: chronic-short base need is floored at the 7 h adult minimum, and the carried debt raises the
        // night's total need above it.
        assertTrue(shortS.need > 7.0)
        // T1: a genuine long sleeper's need exceeds the old fixed 8h (more demanding duration term).
        assertTrue(longS.need > 8.0)
    }
}
