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
                "awake=${num(d.awakeMin)} eff=${num(d.efficiencyPct)} score=${num(d.sleepScore)}")
        }
        for (s in b.sleeps) out.add("sleep ${s.startTs}-${s.endTs} eff=${num(s.efficiency)} hrv=${num(s.avgHrv)} stages=${s.stagesJSON ?: "-"}")
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
            out.add("$asleep/$awake ${num(GarminWatchIngest.efficiencyPct(asleep, awake))}")
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
day 2024-11-09 1731088800..1731175200 steps=50 dist=- act=- tot=10.0000 rhr=- hrv=- sleep=- deep=- light=- rem=- awake=- eff=- score=-
== midnight
file idx=2 dt=128 st=32 num=101 ts=1100000001 size=1001
day 2024-11-08 1731002400..1731088800 steps=16100 dist=12100.0000 act=790.0000 tot=- rhr=- hrv=- sleep=- deep=- light=- rem=- awake=- eff=- score=-
day 2024-11-09 1731088800..1731175200 steps=31 dist=24.0000 act=1.0000 tot=- rhr=- hrv=- sleep=- deep=- light=- rem=- awake=- eff=- score=-
== restingHeartRate
file idx=3 dt=128 st=32 num=102 ts=1100000002 size=1002
day 2024-11-08 1731020400..1731106800 steps=- dist=- act=- tot=- rhr=52 hrv=- sleep=- deep=- light=- rem=- awake=- eff=- score=-
day 2024-11-09 1731106800..1731193200 steps=- dist=- act=- tot=- rhr=57 hrv=- sleep=- deep=- light=- rem=- awake=- eff=- score=-
== stress
file idx=4 dt=128 st=32 num=103 ts=1100000003 size=1003
metric 1731087296 body_battery 80.0000 fit227
metric 1731087296 stress 25.0000 fit227
metric 1731087476 body_battery 79.0000 fit227
metric 1731087836 body_battery 55.0000 fit227
metric 1731087836 hrv_ms 32.0000 fit371
day 2024-11-08 1731020400..1731106800 steps=- dist=- act=- tot=- rhr=- hrv=- sleep=- deep=- light=- rem=- awake=- eff=- score=-
== spo2
file idx=5 dt=128 st=32 num=104 ts=1100000004 size=1004
metric 1731087296 spo2_pct 96.0000 fit269
metric 1731087416 spo2_pct 94.0000 fit269
day 2024-11-08 1731020400..1731106800 steps=- dist=- act=- tot=- rhr=- hrv=- sleep=- deep=- light=- rem=- awake=- eff=- score=-
== sleep
file idx=6 dt=128 st=32 num=105 ts=1100000005 size=1005
metric 1731107296 sleep_score 82.0000 fit346
day 2024-11-09 1731088800..1731175200 steps=- dist=- act=- tot=- rhr=- hrv=- sleep=28.0000 deep=10.0000 light=8.0000 rem=10.0000 awake=10.0000 eff=73.7000 score=82
sleep 1731087416-1731089696 eff=73.7000 hrv=- stages=[{"start":1731087416,"end":1731087896,"stage":"light"},{"start":1731087896,"end":1731088496,"stage":"deep"},{"start":1731088496,"end":1731089096,"stage":"rem"},{"start":1731089096,"end":1731089696,"stage":"wake"}]
== sleepWindow
file idx=7 dt=128 st=32 num=106 ts=1100000006 size=1006
day 2024-11-09 1731106800..1731193200 steps=- dist=- act=- tot=- rhr=- hrv=- sleep=bits:407d6b7777777777 deep=107.0000 light=265.0000 rem=bits:4058adddddddddde awake=23.0000 eff=95.3000 score=-
sleep 1731087296-1731116919 eff=95.3000 hrv=- stages=[{"start":1731087296,"end":1731088316,"stage":"light"},{"start":1731088316,"end":1731090776,"stage":"deep"},{"start":1731090776,"end":1731090836,"stage":"wake"},{"start":1731090836,"end":1731093296,"stage":"light"},{"start":1731093296,"end":1731097256,"stage":"deep"},{"start":1731097256,"end":1731097736,"stage":"light"},{"start":1731097736,"end":1731098636,"stage":"rem"},{"start":1731098636,"end":1731106916,"stage":"light"},{"start":1731106916,"end":1731106976,"stage":"wake"},{"start":1731106976,"end":1731108056,"stage":"light"},{"start":1731108056,"end":1731109316,"stage":"wake"},{"start":1731109316,"end":1731110816,"stage":"light"},{"start":1731110816,"end":1731112376,"stage":"rem"},{"start":1731112376,"end":1731113456,"stage":"light"},{"start":1731113456,"end":1731116919,"stage":"rem"}]
== respiration
file idx=8 dt=128 st=32 num=107 ts=1100000007 size=1007
metric 1731087296 respiration_bpm 14.5000 fit297
day 2024-11-08 1731020400..1731106800 steps=- dist=- act=- tot=- rhr=- hrv=- sleep=- deep=- light=- rem=- awake=- eff=- score=-
== hrv
file idx=9 dt=128 st=32 num=108 ts=1100000008 size=1008
metric 1731087596 hrv_ms 39.0625 fit371
day 2024-11-08 1731020400..1731106800 steps=- dist=- act=- tot=- rhr=- hrv=37.5000 sleep=- deep=- light=- rem=- awake=- eff=- score=-
== candidates
file idx=10 dt=128 st=32 num=109 ts=1100000009 size=1009
== framing
file idx=11 dt=128 st=32 num=110 ts=1100000010 size=1010
metric 1731087296 hrv_ms 40.0000 fit371
metric 1731087296 respiration_bpm 15.0000 fit297
metric 1731087306 respiration_bpm 15.2500 fit297
metric 1731087331 respiration_bpm 15.5000 fit297
day 2024-11-08 1731020400..1731106800 steps=- dist=- act=- tot=- rhr=50 hrv=- sleep=- deep=- light=- rem=- awake=- eff=- score=-
== dayStart
1970-01-01@0 0
2024-02-29@-18000 1709182800
2026-11-01@25200 1793466000
2000-03-01@50400 951818400
bad@0 0
== efficiency
0/0 -
3600/0 100.0000
25200/1800 93.3000
1/2 33.3000
""".trimIndent()
    }
}
