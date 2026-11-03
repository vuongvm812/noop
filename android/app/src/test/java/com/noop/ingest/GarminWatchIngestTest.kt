package com.noop.ingest

import com.noop.data.GarminFileIngest
import com.noop.data.GarminSyncedFileRow
import com.noop.ingest.GarminWellnessParserTest.Companion.ALL
import com.noop.ingest.GarminWellnessParserTest.Companion.num
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fold from one decoded Garmin wellness file to what the store keeps. [EXPECTED] is the Swift
 * `GarminWatchIngestTests.oracleOutput()` text, pasted verbatim; this test runs the same fixtures through
 * the Kotlin fold, so the two platforms store the same rows.
 */
class GarminWatchIngestTest {

    private fun dump(b: GarminFileIngest): List<String> {
        val out = ArrayList<String>()
        val f = b.file
        out.add("file idx=${f.fileIndex} dt=${f.dataType} st=${f.subType} num=${f.fileNumber} ts=${f.ts} size=${f.size}")
        for (s in b.hr) out.add("hr ${s.ts} ${s.bpm}")
        for (m in b.deviceMetric) out.add("metric ${m.ts} ${m.kind.wire} ${num(m.value)} ${m.source}")
        for (d in b.days) {
            out.add("day ${d.day} ${d.dayStartTs}..${d.dayEndTs} steps=${num(d.steps)} dist=${num(d.distanceM)} " +
                "act=${num(d.activeKcal)} tot=${num(d.totalKcal)} rhr=${num(d.restingHr)} hrv=${num(d.avgHrvMs)} " +
                "sleep=${num(d.totalSleepMin)} deep=${num(d.deepMin)} light=${num(d.lightMin)} rem=${num(d.remMin)} " +
                "awake=${num(d.awakeMin)} eff=${num(d.efficiency)} score=${num(d.sleepScore)}")
            for (pk in d.activityPeaks) {
                out.add("peak ${d.day} type=${pk.activityType} steps=${num(pk.steps)} dist=${num(pk.distanceM)} " +
                    "tot=${num(pk.totalKcal)} act=${num(pk.activeKcal)}")
            }
        }
        for (s in b.sleeps) out.add("sleep ${s.startTs}-${s.endTs} eff=${num(s.efficiency)} hrv=${num(s.avgHrv)} stages=${s.stagesJSON ?: "-"}")
        for (d in b.nightRefreshDays) out.add("refresh ${d.day} ${d.dayStartTs}..${d.dayEndTs}")
        return out
    }

    private fun oracleOutput(): String {
        val out = ArrayList<String>()
        ALL.forEachIndexed { i, (name, build) ->
            out.add("== $name")
            val row = GarminSyncedFileRow(i + 1, 128, 32, 100 + i, 1_100_000_000L + i, 1_000L + i)
            out.addAll(dump(GarminWatchIngest.fold(GarminWellnessParser.parse(build()), row, 3_600)))
        }
        out.add("== dayStart")
        for ((day, off) in listOf("1970-01-01" to 0, "2024-02-29" to -18_000, "2026-11-01" to 25_200, "2000-03-01" to 50_400, "bad" to 0)) {
            out.add("$day@$off ${GarminWatchIngest.dayStartUnix(day, off)}")
        }
        out.add("== efficiency")
        for ((asleep, awake) in listOf(0L to 0L, 3_600L to 0L, 25_200L to 1_800L, 1L to 2L)) {
            out.add("$asleep/$awake ${num(GarminWatchIngest.efficiency(asleep, awake))}")
        }
        return out.joinToString("\n")
    }

    @Test
    fun oracleOutputMatchesSwift() {
        assertEquals(EXPECTED, oracleOutput())
    }

    @Test
    fun dayStartRoundTripsWithLocalDay() {
        for (ts in listOf(0L, 86_399L, 951_782_400L, 1_793_538_660L, 4_102_444_799L)) {
            for (off in listOf(-43_200, -18_000, 0, 25_200, 50_400)) {
                val day = GarminWellnessParser.localDay(ts, off)
                val start = GarminWatchIngest.dayStartUnix(day, off)
                assertTrue(start <= ts && ts < start + 86_400)
                assertEquals(day, GarminWellnessParser.localDay(start, off))
            }
        }
    }

    @Test
    fun sleepBecomesASessionWithStagesAndCandidatesStayUnmapped() {
        val row = GarminSyncedFileRow(1, 128, 49, 1, 0, 0)
        val sleep = GarminWatchIngest.fold(GarminWellnessParser.parse(ALL.first { it.first == "sleep" }.second()), row, 0)
        assertNotNull(sleep.sleeps.firstOrNull()?.stagesJSON)
        assertTrue(sleep.days.any { it.totalSleepMin != null })
        val candidates = GarminWatchIngest.fold(GarminWellnessParser.parse(ALL.first { it.first == "candidates" }.second()), row, 0)
        assertTrue(candidates.hr.isEmpty() && candidates.deviceMetric.isEmpty())
    }

    /** #47: a real-shaped night is ONE session over the watch's window. Swift twin: `testARealShapedNightIsOneSessionOverTheWatchWindow`. */
    @Test
    fun aRealShapedNightIsOneSessionOverTheWatchWindow() {
        val row = GarminSyncedFileRow(1, 255, 49, 1, 0, 0)
        val b = GarminWatchIngest.fold(GarminWellnessParser.parse(GarminWellnessParserTest.sleepWindow()), row, 25_200)
        assertEquals(1, b.sleeps.size)
        val onset = GarminWellnessParserTest.T + 631_065_600L // FIT epoch to Unix
        assertEquals(onset, b.sleeps[0].startTs)
        assertEquals(onset + 29_623, b.sleeps[0].endTs)
        assertEquals(470.7, b.days.first().totalSleepMin!!, 0.1)
        assertEquals(23.0, b.days.first().awakeMin!!, 0.0)
    }

    /** #49: efficiency is a 0-1 fraction. Swift twin: `testEfficiencyIsAFraction`. */
    @Test
    fun efficiencyIsAFraction() {
        val row = GarminSyncedFileRow(1, 255, 49, 1, 0, 0)
        val b = GarminWatchIngest.fold(GarminWellnessParser.parse(GarminWellnessParserTest.sleepWindow()), row, 25_200)
        assertEquals(0.953, b.sleeps[0].efficiency!!, 0.0)
        assertEquals(0.953, b.days.first().efficiency!!, 0.0)
    }

    /** #49: a nap ending the same day keeps the night's day totals. Swift twin: `testANapDoesNotReplaceTheNightsDayTotals`. */
    @Test
    fun aNapDoesNotReplaceTheNightsDayTotals() {
        val row = GarminSyncedFileRow(1, 255, 49, 1, 0, 0)
        val b = GarminWatchIngest.fold(GarminWellnessParser.parse(GarminWellnessParserTest.sleepNap()), row, 3_600)
        assertEquals(2, b.sleeps.size)
        val d = b.days.single { it.totalSleepMin != null }
        assertEquals(390.0, d.totalSleepMin!!, 0.0)
        assertEquals(60.0, d.deepMin!!, 0.0)
        assertEquals(120.0, d.remMin!!, 0.0)
        assertEquals(30.0, d.awakeMin!!, 0.0)
        assertEquals(0.929, d.efficiency!!, 0.0)
    }

    /** #49: unmeasurable time leaves a hole the coverage gate sees. Swift twin: `testUnmeasurableTimeReadsAsAPartlyRecordedNight`. */
    @Test
    fun unmeasurableTimeReadsAsAPartlyRecordedNight() {
        val row = GarminSyncedFileRow(1, 255, 49, 1, 0, 0)
        val night = GarminWatchIngest.fold(GarminWellnessParser.parse(GarminWellnessParserTest.sleepNap()), row, 3_600).sleeps[0]
        val coverage = com.noop.analytics.HypnogramCoverage.groupFraction(
            listOf(com.noop.analytics.HypnogramCoverage.Fragment(night.stagesJSON, (night.endTs - night.startTs).toDouble())))!!
        assertEquals(25_200.0 / 27_000.0, coverage, 1e-12)
        assertTrue(coverage < com.noop.analytics.HypnogramCoverage.minCoverage)
    }

    private companion object {
        /** Swift oracle stdout, verbatim. */
        val EXPECTED = """
== monitoring
file idx=1 dt=128 st=32 num=100 ts=1100000000 size=1000
hr 1731087296 61
hr 1731087392 90
hr 1731087424 70
hr 1731090896 58
metric 1731087392 intensity_moderate_min 5.0000 fit55
metric 1731087392 intensity_vigorous_min 2.0000 fit55
day 2024-11-08 1731002400..1731088800 steps=1600 dist=800.0000 act=120.0000 tot=3510.0000 rhr=- hrv=- sleep=- deep=- light=- rem=- awake=- eff=- score=-
peak 2024-11-08 type=1 steps=400 dist=- tot=1750.0000 act=-
peak 2024-11-08 type=6 steps=1200 dist=800.0000 tot=1760.0000 act=120.0000
day 2024-11-09 1731088800..1731175200 steps=50 dist=- act=- tot=10.0000 rhr=- hrv=- sleep=- deep=- light=- rem=- awake=- eff=- score=-
peak 2024-11-09 type=6 steps=50 dist=- tot=10.0000 act=-
refresh 2024-11-10 1731175200..1731261600
== midnight
file idx=2 dt=128 st=32 num=101 ts=1100000001 size=1001
day 2024-11-08 1731002400..1731088800 steps=16100 dist=12100.0000 act=790.0000 tot=- rhr=- hrv=- sleep=- deep=- light=- rem=- awake=- eff=- score=-
peak 2024-11-08 type=1 steps=12500 dist=9600.0000 tot=- act=640.0000
peak 2024-11-08 type=6 steps=3600 dist=2500.0000 tot=- act=150.0000
day 2024-11-09 1731088800..1731175200 steps=31 dist=24.0000 act=1.0000 tot=- rhr=- hrv=- sleep=- deep=- light=- rem=- awake=- eff=- score=-
peak 2024-11-09 type=6 steps=31 dist=24.0000 tot=- act=1.0000
refresh 2024-11-10 1731175200..1731261600
== multiSport
file idx=3 dt=128 st=32 num=102 ts=1100000002 size=1002
day 2024-11-08 1731002400..1731088800 steps=2000 dist=14500.0000 act=480.0000 tot=1920.0000 rhr=- hrv=- sleep=- deep=- light=- rem=- awake=- eff=- score=-
peak 2024-11-08 type=2 steps=- dist=12000.0000 tot=300.0000 act=280.0000
peak 2024-11-08 type=5 steps=- dist=1000.0000 tot=120.0000 act=110.0000
peak 2024-11-08 type=6 steps=2000 dist=1500.0000 tot=1500.0000 act=90.0000
refresh 2024-11-09 1731088800..1731175200
== restingHeartRate
file idx=4 dt=128 st=32 num=103 ts=1100000003 size=1003
day 2024-11-08 1731020400..1731106800 steps=- dist=- act=- tot=- rhr=52 hrv=- sleep=- deep=- light=- rem=- awake=- eff=- score=-
day 2024-11-09 1731106800..1731193200 steps=- dist=- act=- tot=- rhr=57 hrv=- sleep=- deep=- light=- rem=- awake=- eff=- score=-
refresh 2024-11-10 1731193200..1731279600
== stress
file idx=5 dt=128 st=32 num=104 ts=1100000004 size=1004
metric 1731087296 body_battery 80.0000 fit227
metric 1731087296 stress 25.0000 fit227
metric 1731087476 body_battery 79.0000 fit227
metric 1731087836 body_battery 55.0000 fit227
metric 1731087836 hrv_ms 32.0000 fit371
day 2024-11-08 1731020400..1731106800 steps=- dist=- act=- tot=- rhr=- hrv=- sleep=- deep=- light=- rem=- awake=- eff=- score=-
refresh 2024-11-09 1731106800..1731193200
== spo2
file idx=6 dt=128 st=32 num=105 ts=1100000005 size=1005
metric 1731087296 spo2_pct 96.0000 fit269
day 2024-11-08 1731020400..1731106800 steps=- dist=- act=- tot=- rhr=- hrv=- sleep=- deep=- light=- rem=- awake=- eff=- score=-
refresh 2024-11-09 1731106800..1731193200
== sleep
file idx=7 dt=128 st=32 num=106 ts=1100000006 size=1006
metric 1731107296 sleep_score 82.0000 fit346
day 2024-11-09 1731088800..1731175200 steps=- dist=- act=- tot=- rhr=- hrv=- sleep=28.0000 deep=10.0000 light=8.0000 rem=10.0000 awake=10.0000 eff=0.7370 score=82
sleep 1731087416-1731089696 eff=0.7370 hrv=- stages=[{"start":1731087416,"end":1731087896,"stage":"light"},{"start":1731087896,"end":1731088496,"stage":"deep"},{"start":1731088496,"end":1731089096,"stage":"rem"},{"start":1731089096,"end":1731089696,"stage":"wake"}]
refresh 2024-11-10 1731175200..1731261600
== sleepWindow
file idx=8 dt=128 st=32 num=107 ts=1100000007 size=1007
day 2024-11-09 1731106800..1731193200 steps=- dist=- act=- tot=- rhr=- hrv=- sleep=bits:407d6b7777777777 deep=107.0000 light=265.0000 rem=bits:4058adddddddddde awake=23.0000 eff=0.9530 score=-
sleep 1731087296-1731116919 eff=0.9530 hrv=- stages=[{"start":1731087296,"end":1731088316,"stage":"light"},{"start":1731088316,"end":1731090776,"stage":"deep"},{"start":1731090776,"end":1731090836,"stage":"wake"},{"start":1731090836,"end":1731093296,"stage":"light"},{"start":1731093296,"end":1731097256,"stage":"deep"},{"start":1731097256,"end":1731097736,"stage":"light"},{"start":1731097736,"end":1731098636,"stage":"rem"},{"start":1731098636,"end":1731106916,"stage":"light"},{"start":1731106916,"end":1731106976,"stage":"wake"},{"start":1731106976,"end":1731108056,"stage":"light"},{"start":1731108056,"end":1731109316,"stage":"wake"},{"start":1731109316,"end":1731110816,"stage":"light"},{"start":1731110816,"end":1731112376,"stage":"rem"},{"start":1731112376,"end":1731113456,"stage":"light"},{"start":1731113456,"end":1731116919,"stage":"rem"}]
refresh 2024-11-10 1731193200..1731279600
== sleepNap
file idx=9 dt=128 st=32 num=108 ts=1100000008 size=1008
day 2024-11-09 1731106800..1731193200 steps=- dist=- act=- tot=- rhr=- hrv=- sleep=390.0000 deep=60.0000 light=210.0000 rem=120.0000 awake=30.0000 eff=0.9290 score=-
sleep 1731087296-1731114296 eff=0.9290 hrv=- stages=[{"start":1731087296,"end":1731089096,"stage":"light"},{"start":1731089096,"end":1731092696,"stage":"deep"},{"start":1731094496,"end":1731101696,"stage":"rem"},{"start":1731101696,"end":1731103496,"stage":"wake"},{"start":1731103496,"end":1731114296,"stage":"light"}]
sleep 1731137296-1731139696 eff=0.5000 hrv=- stages=[{"start":1731137296,"end":1731138496,"stage":"light"},{"start":1731138496,"end":1731139696,"stage":"wake"}]
refresh 2024-11-10 1731193200..1731279600
== respiration
file idx=10 dt=128 st=32 num=109 ts=1100000009 size=1009
metric 1731087296 respiration_bpm 14.5000 fit297
day 2024-11-08 1731020400..1731106800 steps=- dist=- act=- tot=- rhr=- hrv=- sleep=- deep=- light=- rem=- awake=- eff=- score=-
refresh 2024-11-09 1731106800..1731193200
== hrv
file idx=11 dt=128 st=32 num=110 ts=1100000010 size=1010
metric 1731087596 hrv_ms 39.0625 fit371
day 2024-11-08 1731020400..1731106800 steps=- dist=- act=- tot=- rhr=- hrv=37.5000 sleep=- deep=- light=- rem=- awake=- eff=- score=-
refresh 2024-11-09 1731106800..1731193200
== candidates
file idx=12 dt=128 st=32 num=111 ts=1100000011 size=1011
== framing
file idx=13 dt=128 st=32 num=112 ts=1100000012 size=1012
metric 1731087296 hrv_ms 40.0000 fit371
metric 1731087296 respiration_bpm 15.0000 fit297
metric 1731087306 respiration_bpm 15.2500 fit297
metric 1731087331 respiration_bpm 15.5000 fit297
day 2024-11-08 1731020400..1731106800 steps=- dist=- act=- tot=- rhr=50 hrv=- sleep=- deep=- light=- rem=- awake=- eff=- score=-
refresh 2024-11-09 1731106800..1731193200
== bounds
file idx=14 dt=128 st=32 num=113 ts=1100000013 size=1013
metric 1731087316 spo2_pct 70.0000 fit269
metric 1731087326 spo2_pct 100.0000 fit269
metric 1731087346 respiration_bpm 4.0000 fit297
metric 1731087356 respiration_bpm 60.0000 fit297
metric 1731087386 hrv_ms 5.0000 fit371
metric 1731087396 hrv_ms 300.0000 fit371
day 2024-11-08 1731002400..1731088800 steps=- dist=- act=- tot=- rhr=48 hrv=- sleep=- deep=- light=- rem=- awake=- eff=- score=-
day 2024-11-09 1731088800..1731175200 steps=- dist=- act=- tot=- rhr=47 hrv=- sleep=- deep=- light=- rem=- awake=- eff=- score=-
refresh 2024-11-10 1731175200..1731261600
== dayStart
1970-01-01@0 0
2024-02-29@-18000 1709182800
2026-11-01@25200 1793466000
2000-03-01@50400 951818400
bad@0 0
== efficiency
0/0 -
3600/0 1.0000
25200/1800 0.9330
1/2 0.3330
""".trimIndent()
    }
}
