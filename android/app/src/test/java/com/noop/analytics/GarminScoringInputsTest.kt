package com.noop.analytics

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Garmin child 9: Kotlin twin of GarminScoringInputsTests (StrandAnalytics). [EXPECTED] is the Swift
 * `GarminScoringInputsTests.oracleOutput()` text, pasted verbatim; this test rebuilds the same dump from the
 * Kotlin table and resolver, so the arbitration entries, the per-fact resolution, the baseline era cut and
 * the Effort label stay byte-identical across platforms. All values are synthetic.
 */
class GarminScoringInputsTest {

    private val swiftKind = mapOf(
        MetricArbitrationPolicy.MetricKind.RESTING_HR to "restingHR",
        MetricArbitrationPolicy.MetricKind.HEART_RATE to "heartRate",
        MetricArbitrationPolicy.MetricKind.HRV to "hrv",
        MetricArbitrationPolicy.MetricKind.SPO2 to "spo2",
        MetricArbitrationPolicy.MetricKind.RESPIRATION to "respiration",
        MetricArbitrationPolicy.MetricKind.SKIN_TEMP to "skinTemp",
        MetricArbitrationPolicy.MetricKind.STEPS to "steps",
        MetricArbitrationPolicy.MetricKind.SLEEP to "sleep",
        MetricArbitrationPolicy.MetricKind.CALORIES to "calories",
        MetricArbitrationPolicy.MetricKind.OTHER to "other",
    )

    /** The sources the Swift twin knows. [FusionSource.HUAWEI_WATCH] is Kotlin-only (fork), so it is not in
     *  the Swift oracle; HuaweiWatchGatesTest pins its rows against Garmin's instead. */
    private val swiftSources = FusionSource.entries.filterNot { it == FusionSource.HUAWEI_WATCH }

    private fun fmt(v: Double?): String = v?.let { String.format(Locale.ROOT, "%.4f", it) } ?: "nil"

    private fun s(ts: Long, v: Double) = GarminScoringInputs.Sample(ts, v)

    private fun oracleOutput(): String {
        val lines = ArrayList<String>()
        for (kind in MetricArbitrationPolicy.MetricKind.entries) {
            val tol = MetricArbitrationPolicy.tolerance(kind)
            lines.add("tol ${swiftKind[kind]} ${fmt(tol.agree)} ${fmt(tol.minorDelta)} ${tol.isPercent}")
            for (source in swiftSources) {
                lines.add(
                    "tier ${swiftKind[kind]} ${source.id} ${MetricArbitrationPolicy.tier(kind, source)} " +
                        MetricArbitrationPolicy.reason(kind, source),
                )
            }
        }
        for (source in swiftSources) {
            lines.add("source ${source.id} ${MetricArbitrationPolicy.sourcePriority(source)} ${source.displayName}")
        }
        for (key in listOf(
            "rhr", "resting_hr", "hrv", "spo2", "resp_rate", "respiration", "stress", "body_battery_min",
            "ref_sleep_score", "sleep_total_min", "steps", "energy_kcal",
        )) {
            lines.add("kind $key ${swiftKind[MetricArbitrationPolicy.kind(key)]}")
        }
        for (id in listOf(
            "garmin-0A1B2C3D", "garmin-AA:BB:CC:00:11:22", "garmin-import", "garmin-x-noop", "garmin",
            "my-whoop", "my-whoop-noop", "apple-health", "xiaomi-band", "oura-import",
        )) {
            lines.add("stored $id ${FusionSource.forStoredSourceId(id)?.id ?: "nil"}")
        }

        val nights = listOf(GarminScoringInputs.Night(1_000, 5_000), GarminScoringInputs.Night(4_000, 9_000))
        val hrvSets = listOf(
            emptyList(),
            listOf(s(2_000, 38.0), s(3_000, 41.0)),
            listOf(s(999, 90.0), s(2_000, 38.0), s(4_500, 41.0), s(9_000, 47.0), s(9_001, 80.0)),
            listOf(s(2_000, -1.0), s(3_000, 0.0), s(4_000, 35.0), s(5_000, 36.0), s(6_000, 37.5)),
            (0 until 12).map { s(1_200L + it * 600, 40 + it.toDouble() / 4) },
        )
        // The watch row's stored overnight respiration.
        val respValues = listOf<Double?>(null, 19.583333333333332)
        val watches = listOf(
            null, GarminScoringInputs.WatchDay(null, null), GarminScoringInputs.WatchDay(63, null),
            GarminScoringInputs.WatchDay(49, 52.5), GarminScoringInputs.WatchDay(0, 0.0),
        )
        val computeds = listOf<Triple<Int?, Double?, Double?>>(
            Triple(null, null, null), Triple(58, 31.75, 14.2), Triple(75, null, 19.0),
        )
        for ((ci, c) in computeds.withIndex()) {
            for ((wi, w) in watches.withIndex()) {
                for ((hi, h) in hrvSets.withIndex()) {
                    for ((ri, rv) in respValues.withIndex()) {
                        for ((ni, n) in listOf(emptyList(), nights, listOf(GarminScoringInputs.Night(1_000, 2_500))).withIndex()) {
                            // A stored respiration only exists with a watch row.
                            val r = GarminScoringInputs.resolve(c.first, c.second, c.third, w, h, if (w == null) null else rv, n)
                            lines.add(
                                "resolve c$ci w$wi h$hi r$ri n$ni -> rhr=${r.restingHr ?: "nil"}" +
                                    "/${r.restingHrSource?.id ?: "nil"} hrv=${fmt(r.avgHrv)}" +
                                    "/${r.avgHrvSource?.id ?: "nil"}/${r.hrvBasis.raw}" +
                                    " resp=${fmt(r.respRateBpm)}/${r.respRateSource?.id ?: "nil"}",
                            )
                        }
                    }
                }
            }
        }

        val eraCases = listOf(
            emptyList(),
            listOf("2026-10-01" to "my-whoop"),
            listOf("2026-10-01" to "my-whoop", "2026-10-02" to "garmin-A"),
            listOf("2026-10-01" to "garmin-A", "2026-10-02" to "my-whoop"),
            listOf("2026-10-01" to "garmin-A", "2026-10-02" to "garmin-B"),
            listOf("2026-10-01" to "oura-import", "2026-10-02" to "my-whoop"),
            listOf("2026-10-03" to "garmin-A", "2026-10-01" to "my-whoop", "2026-10-02" to "garmin-import"),
            listOf("2026-10-02" to "my-whoop", "2026-10-02" to "garmin-A"),
        )
        for ((i, days) in eraCases.withIndex()) {
            lines.add("era $i ${fmt(GarminScoringInputs.baselineEraEpoch(days))}")
        }
        for ((id, brand) in listOf<Pair<String, String?>>(
            "garmin-A" to "Garmin", "garmin-A" to null, "strap-1" to "Garmin", "strap-1" to "Polar",
            "garmin-import" to "Garmin", "my-whoop" to null, "garmin-A-noop" to null,
        )) {
            lines.add("effort $id ${brand ?: "nil"} ${GarminScoringInputs.effortIsExperimental(id, brand)}")
        }
        // The watch's own VO2max for a week (#51): latest on or before the key, within the age window.
        val vo2Points = listOf("2026-10-01" to 50.1, "2026-10-04" to 51.0, "2026-10-31" to 52.4,
            "2026-11-08" to 53.0, "2026-10-20" to -1.0)
        for (week in listOf("2026-10-31", "2026-10-30", "2026-11-07", "2026-11-08", "2026-12-31", "bad")) {
            lines.add("vo2 $week ${GarminScoringInputs.watchVo2max(vo2Points, week)?.toString() ?: "nil"}")
        }
        // #71: the watch's points first, the export's on the days the watch lacks; then every past week the
        // 28-day rule covers.
        val imported = listOf("2026-09-20" to 48.0, "2026-10-04" to 49.0, "2026-10-05" to 47.5,
            "2026-10-06" to -1.0, "2026-09-01" to Double.NaN, "2026-09-02" to Double.POSITIVE_INFINITY)
        val merged = GarminScoringInputs.vo2maxPoints(
            listOf("2026-10-04" to 51.0, "2026-10-01" to 50.1, "2026-10-05" to 0.0), imported)
        fun dump(points: List<Pair<String, Double>>) = points.joinToString(" ") { "${it.first}=${it.second}" }
        lines.add("merge " + dump(merged))
        lines.add("merge-watch-only " + dump(GarminScoringInputs.vo2maxPoints(vo2Points, emptyList())))
        lines.add("merge-import-only " + dump(GarminScoringInputs.vo2maxPoints(emptyList(), imported)))
        val weekSets = listOf(
            merged, emptyList(), listOf("2026-10-03" to 44.0), listOf("2026-01-01" to 40.0, "2026-03-15" to 42.0),
            listOf("2025-12-30" to 41.0, "2026-01-20" to -2.0),
        )
        for ((i, points) in weekSets.withIndex()) {
            for (before in listOf("2026-11-07", "2026-10-03", "2026-02-07", "bad")) {
                lines.add("weeks $i $before [" + dump(GarminScoringInputs.watchVo2maxWeeks(points, before)) + "]")
            }
        }
        return lines.joinToString("\n")
    }

    @Test
    fun oracleOutputMatchesSwift() {
        assertEquals(EXPECTED, oracleOutput())
    }

    /** #53: three five-minute values (a quarter hour) no longer stand for a night; twelve (an hour) do, and
     *  so does a short night the samples cover at least half of. Swift twin:
     *  `testNightMeanNeedsAnHourOrHalfTheNight`. */
    @Test
    fun nightMeanNeedsAnHourOrHalfTheNight() {
        val long = listOf(GarminScoringInputs.Night(0, 8 * 3_600))
        val eleven = (0 until 11).map { s(600L + it * 300, 50.0) }
        assertNull(GarminScoringInputs.nightMean(eleven, long))
        val twelve = eleven + s(4_000, 62.0)
        assertEquals(51.0, GarminScoringInputs.nightMean(twelve, long)!!, 0.0)
        val nap = listOf(GarminScoringInputs.Night(0, 1_800), GarminScoringInputs.Night(900, 2_400))
        assertEquals(2_400L, GarminScoringInputs.sleepSeconds(nap))
        assertEquals(40.0, GarminScoringInputs.nightMean((0 until 4).map { s(it * 300L, 40.0) }, nap)!!, 0.0)
        assertNull(GarminScoringInputs.nightMean((0 until 3).map { s(it * 300L, 40.0) }, nap))
    }

    @Test
    fun watchIsPrimaryForTheWatchMeasuredVitals() {
        for (key in listOf("rhr", "hrv", "spo2", "resp_rate", "sleep_total_min", "steps", "stress")) {
            val kind = MetricArbitrationPolicy.kind(key)
            assertTrue(
                key,
                MetricArbitrationPolicy.tier(kind, FusionSource.GARMIN_WATCH) <
                    MetricArbitrationPolicy.tier(kind, FusionSource.NOOP_COMPUTED),
            )
        }
        val point = FusionResolver.resolve(
            "rhr",
            listOf(FusionInput(FusionSource.NOOP_COMPUTED, 58.0), FusionInput(FusionSource.GARMIN_WATCH, 63.0)),
        )!!
        assertEquals(FusionSource.GARMIN_WATCH, point.winningSource)
        assertEquals(63.0, point.value, 0.0)
    }

    @Test
    fun storedIdsAndEffortLabel() {
        assertEquals(FusionSource.GARMIN_WATCH, FusionSource.forStoredSourceId("garmin-AA:BB"))
        assertNull(FusionSource.forStoredSourceId("garmin-import"))
        assertTrue("Garmin is official (#43)", GarminScoringInputs.EFFORT_VALIDATED)
        assertFalse(GarminScoringInputs.effortIsExperimental("garmin-AA:BB", "Garmin"))
        assertFalse(GarminScoringInputs.effortIsExperimental("my-whoop", "WHOOP"))
    }

    companion object {
        /** Swift oracle stdout (`GarminScoringInputsTests.oracleOutput()`), verbatim. */
        private val EXPECTED = """
            tol restingHR 3.0000 8.0000 false
            tier restingHR my-whoop 0 direct sensor
            tier restingHR my-whoop-noop 1 computed on device
            tier restingHR apple-health 2 phone aggregate
            tier restingHR health-connect 2 phone aggregate
            tier restingHR xiaomi-band 0 direct sensor
            tier restingHR garmin-watch 0 direct sensor
            tier restingHR nutrition-csv 3 estimate
            tier restingHR local-cache 3 estimate
            tol heartRate 5.0000 12.0000 false
            tier heartRate my-whoop 0 direct sensor
            tier heartRate my-whoop-noop 1 computed on device
            tier heartRate apple-health 2 phone aggregate
            tier heartRate health-connect 2 phone aggregate
            tier heartRate xiaomi-band 0 direct sensor
            tier heartRate garmin-watch 0 direct sensor
            tier heartRate nutrition-csv 3 estimate
            tier heartRate local-cache 3 estimate
            tol hrv 8.0000 20.0000 false
            tier hrv my-whoop 0 direct sensor
            tier hrv my-whoop-noop 1 computed on device
            tier hrv apple-health 2 phone aggregate
            tier hrv health-connect 2 phone aggregate
            tier hrv xiaomi-band 0 direct sensor
            tier hrv garmin-watch 0 direct sensor
            tier hrv nutrition-csv 3 estimate
            tier hrv local-cache 3 estimate
            tol spo2 2.0000 4.0000 false
            tier spo2 my-whoop 0 direct sensor
            tier spo2 my-whoop-noop 1 computed on device
            tier spo2 apple-health 2 phone aggregate
            tier spo2 health-connect 2 phone aggregate
            tier spo2 xiaomi-band 0 direct sensor
            tier spo2 garmin-watch 0 direct sensor
            tier spo2 nutrition-csv 3 estimate
            tier spo2 local-cache 3 estimate
            tol respiration 1.0000 3.0000 false
            tier respiration my-whoop 0 direct sensor
            tier respiration my-whoop-noop 1 computed on device
            tier respiration apple-health 2 phone aggregate
            tier respiration health-connect 2 phone aggregate
            tier respiration xiaomi-band 0 direct sensor
            tier respiration garmin-watch 0 direct sensor
            tier respiration nutrition-csv 3 estimate
            tier respiration local-cache 3 estimate
            tol skinTemp 0.5000 1.5000 false
            tier skinTemp my-whoop 0 worn sensor
            tier skinTemp my-whoop-noop 1 worn sensor
            tier skinTemp apple-health 2 worn sensor
            tier skinTemp health-connect 2 worn sensor
            tier skinTemp xiaomi-band 0 worn sensor
            tier skinTemp garmin-watch 0 worn sensor
            tier skinTemp nutrition-csv 3 worn sensor
            tier skinTemp local-cache 3 worn sensor
            tol steps 0.1000 0.3000 true
            tier steps my-whoop 3 step estimate
            tier steps my-whoop-noop 3 step estimate
            tier steps apple-health 0 counts directly
            tier steps health-connect 0 counts directly
            tier steps xiaomi-band 0 counts directly
            tier steps garmin-watch 0 counts directly
            tier steps nutrition-csv 3 estimate
            tier steps local-cache 3 estimate
            tol sleep 20.0000 60.0000 false
            tier sleep my-whoop 0 best stager
            tier sleep my-whoop-noop 1 computed stages
            tier sleep apple-health 2 phone sleep buckets
            tier sleep health-connect 2 phone sleep buckets
            tier sleep xiaomi-band 1 computed on device
            tier sleep garmin-watch 0 watch stages
            tier sleep nutrition-csv 3 estimate
            tier sleep local-cache 3 estimate
            tol calories 0.1500 0.4000 true
            tier calories my-whoop 3 estimate
            tier calories my-whoop-noop 3 estimate
            tier calories apple-health 2 phone aggregate
            tier calories health-connect 2 phone aggregate
            tier calories xiaomi-band 3 estimate
            tier calories garmin-watch 3 estimate
            tier calories nutrition-csv 3 estimate
            tier calories local-cache 3 estimate
            tol other 0.1000 0.3000 true
            tier other my-whoop 0 direct sensor
            tier other my-whoop-noop 1 computed on device
            tier other apple-health 2 phone aggregate
            tier other health-connect 2 phone aggregate
            tier other xiaomi-band 0 direct sensor
            tier other garmin-watch 0 direct sensor
            tier other nutrition-csv 0 direct sensor
            tier other local-cache 3 estimate
            source my-whoop 0 WHOOP
            source my-whoop-noop 1 NOOP
            source apple-health 2 Apple Health
            source health-connect 3 Health Connect
            source xiaomi-band 4 Mi Band
            source garmin-watch 5 Garmin
            source nutrition-csv 6 Nutrition
            source local-cache 7 Cached
            kind rhr restingHR
            kind resting_hr restingHR
            kind hrv hrv
            kind spo2 spo2
            kind resp_rate respiration
            kind respiration respiration
            kind stress other
            kind body_battery_min other
            kind ref_sleep_score other
            kind sleep_total_min sleep
            kind steps steps
            kind energy_kcal calories
            stored garmin-0A1B2C3D garmin-watch
            stored garmin-AA:BB:CC:00:11:22 garmin-watch
            stored garmin-import nil
            stored garmin-x-noop nil
            stored garmin nil
            stored my-whoop my-whoop
            stored my-whoop-noop my-whoop-noop
            stored apple-health apple-health
            stored xiaomi-band xiaomi-band
            stored oura-import nil
            resolve c0 w0 h0 r0 n0 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
            resolve c0 w0 h0 r0 n1 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
            resolve c0 w0 h0 r0 n2 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
            resolve c0 w0 h0 r1 n0 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
            resolve c0 w0 h0 r1 n1 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
            resolve c0 w0 h0 r1 n2 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
            resolve c0 w0 h1 r0 n0 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
            resolve c0 w0 h1 r0 n1 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
            resolve c0 w0 h1 r0 n2 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
            resolve c0 w0 h1 r1 n0 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
            resolve c0 w0 h1 r1 n1 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
            resolve c0 w0 h1 r1 n2 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
            resolve c0 w0 h2 r0 n0 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
            resolve c0 w0 h2 r0 n1 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
            resolve c0 w0 h2 r0 n2 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
            resolve c0 w0 h2 r1 n0 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
            resolve c0 w0 h2 r1 n1 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
            resolve c0 w0 h2 r1 n2 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
            resolve c0 w0 h3 r0 n0 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
            resolve c0 w0 h3 r0 n1 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
            resolve c0 w0 h3 r0 n2 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
            resolve c0 w0 h3 r1 n0 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
            resolve c0 w0 h3 r1 n1 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
            resolve c0 w0 h3 r1 n2 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
            resolve c0 w0 h4 r0 n0 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
            resolve c0 w0 h4 r0 n1 -> rhr=nil/nil hrv=41.3750/garmin-watch/fit371 resp=nil/nil
            resolve c0 w0 h4 r0 n2 -> rhr=nil/nil hrv=40.2500/garmin-watch/fit371 resp=nil/nil
            resolve c0 w0 h4 r1 n0 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
            resolve c0 w0 h4 r1 n1 -> rhr=nil/nil hrv=41.3750/garmin-watch/fit371 resp=nil/nil
            resolve c0 w0 h4 r1 n2 -> rhr=nil/nil hrv=40.2500/garmin-watch/fit371 resp=nil/nil
            resolve c0 w1 h0 r0 n0 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
            resolve c0 w1 h0 r0 n1 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
            resolve c0 w1 h0 r0 n2 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
            resolve c0 w1 h0 r1 n0 -> rhr=nil/nil hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c0 w1 h0 r1 n1 -> rhr=nil/nil hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c0 w1 h0 r1 n2 -> rhr=nil/nil hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c0 w1 h1 r0 n0 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
            resolve c0 w1 h1 r0 n1 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
            resolve c0 w1 h1 r0 n2 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
            resolve c0 w1 h1 r1 n0 -> rhr=nil/nil hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c0 w1 h1 r1 n1 -> rhr=nil/nil hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c0 w1 h1 r1 n2 -> rhr=nil/nil hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c0 w1 h2 r0 n0 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
            resolve c0 w1 h2 r0 n1 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
            resolve c0 w1 h2 r0 n2 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
            resolve c0 w1 h2 r1 n0 -> rhr=nil/nil hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c0 w1 h2 r1 n1 -> rhr=nil/nil hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c0 w1 h2 r1 n2 -> rhr=nil/nil hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c0 w1 h3 r0 n0 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
            resolve c0 w1 h3 r0 n1 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
            resolve c0 w1 h3 r0 n2 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
            resolve c0 w1 h3 r1 n0 -> rhr=nil/nil hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c0 w1 h3 r1 n1 -> rhr=nil/nil hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c0 w1 h3 r1 n2 -> rhr=nil/nil hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c0 w1 h4 r0 n0 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
            resolve c0 w1 h4 r0 n1 -> rhr=nil/nil hrv=41.3750/garmin-watch/fit371 resp=nil/nil
            resolve c0 w1 h4 r0 n2 -> rhr=nil/nil hrv=40.2500/garmin-watch/fit371 resp=nil/nil
            resolve c0 w1 h4 r1 n0 -> rhr=nil/nil hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c0 w1 h4 r1 n1 -> rhr=nil/nil hrv=41.3750/garmin-watch/fit371 resp=19.5833/garmin-watch
            resolve c0 w1 h4 r1 n2 -> rhr=nil/nil hrv=40.2500/garmin-watch/fit371 resp=19.5833/garmin-watch
            resolve c0 w2 h0 r0 n0 -> rhr=63/garmin-watch hrv=nil/nil/none resp=nil/nil
            resolve c0 w2 h0 r0 n1 -> rhr=63/garmin-watch hrv=nil/nil/none resp=nil/nil
            resolve c0 w2 h0 r0 n2 -> rhr=63/garmin-watch hrv=nil/nil/none resp=nil/nil
            resolve c0 w2 h0 r1 n0 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c0 w2 h0 r1 n1 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c0 w2 h0 r1 n2 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c0 w2 h1 r0 n0 -> rhr=63/garmin-watch hrv=nil/nil/none resp=nil/nil
            resolve c0 w2 h1 r0 n1 -> rhr=63/garmin-watch hrv=nil/nil/none resp=nil/nil
            resolve c0 w2 h1 r0 n2 -> rhr=63/garmin-watch hrv=nil/nil/none resp=nil/nil
            resolve c0 w2 h1 r1 n0 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c0 w2 h1 r1 n1 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c0 w2 h1 r1 n2 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c0 w2 h2 r0 n0 -> rhr=63/garmin-watch hrv=nil/nil/none resp=nil/nil
            resolve c0 w2 h2 r0 n1 -> rhr=63/garmin-watch hrv=nil/nil/none resp=nil/nil
            resolve c0 w2 h2 r0 n2 -> rhr=63/garmin-watch hrv=nil/nil/none resp=nil/nil
            resolve c0 w2 h2 r1 n0 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c0 w2 h2 r1 n1 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c0 w2 h2 r1 n2 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c0 w2 h3 r0 n0 -> rhr=63/garmin-watch hrv=nil/nil/none resp=nil/nil
            resolve c0 w2 h3 r0 n1 -> rhr=63/garmin-watch hrv=nil/nil/none resp=nil/nil
            resolve c0 w2 h3 r0 n2 -> rhr=63/garmin-watch hrv=nil/nil/none resp=nil/nil
            resolve c0 w2 h3 r1 n0 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c0 w2 h3 r1 n1 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c0 w2 h3 r1 n2 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c0 w2 h4 r0 n0 -> rhr=63/garmin-watch hrv=nil/nil/none resp=nil/nil
            resolve c0 w2 h4 r0 n1 -> rhr=63/garmin-watch hrv=41.3750/garmin-watch/fit371 resp=nil/nil
            resolve c0 w2 h4 r0 n2 -> rhr=63/garmin-watch hrv=40.2500/garmin-watch/fit371 resp=nil/nil
            resolve c0 w2 h4 r1 n0 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c0 w2 h4 r1 n1 -> rhr=63/garmin-watch hrv=41.3750/garmin-watch/fit371 resp=19.5833/garmin-watch
            resolve c0 w2 h4 r1 n2 -> rhr=63/garmin-watch hrv=40.2500/garmin-watch/fit371 resp=19.5833/garmin-watch
            resolve c0 w3 h0 r0 n0 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=nil/nil
            resolve c0 w3 h0 r0 n1 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=nil/nil
            resolve c0 w3 h0 r0 n2 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=nil/nil
            resolve c0 w3 h0 r1 n0 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
            resolve c0 w3 h0 r1 n1 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
            resolve c0 w3 h0 r1 n2 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
            resolve c0 w3 h1 r0 n0 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=nil/nil
            resolve c0 w3 h1 r0 n1 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=nil/nil
            resolve c0 w3 h1 r0 n2 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=nil/nil
            resolve c0 w3 h1 r1 n0 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
            resolve c0 w3 h1 r1 n1 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
            resolve c0 w3 h1 r1 n2 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
            resolve c0 w3 h2 r0 n0 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=nil/nil
            resolve c0 w3 h2 r0 n1 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=nil/nil
            resolve c0 w3 h2 r0 n2 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=nil/nil
            resolve c0 w3 h2 r1 n0 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
            resolve c0 w3 h2 r1 n1 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
            resolve c0 w3 h2 r1 n2 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
            resolve c0 w3 h3 r0 n0 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=nil/nil
            resolve c0 w3 h3 r0 n1 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=nil/nil
            resolve c0 w3 h3 r0 n2 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=nil/nil
            resolve c0 w3 h3 r1 n0 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
            resolve c0 w3 h3 r1 n1 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
            resolve c0 w3 h3 r1 n2 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
            resolve c0 w3 h4 r0 n0 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=nil/nil
            resolve c0 w3 h4 r0 n1 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=nil/nil
            resolve c0 w3 h4 r0 n2 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=nil/nil
            resolve c0 w3 h4 r1 n0 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
            resolve c0 w3 h4 r1 n1 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
            resolve c0 w3 h4 r1 n2 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
            resolve c0 w4 h0 r0 n0 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
            resolve c0 w4 h0 r0 n1 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
            resolve c0 w4 h0 r0 n2 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
            resolve c0 w4 h0 r1 n0 -> rhr=nil/nil hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c0 w4 h0 r1 n1 -> rhr=nil/nil hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c0 w4 h0 r1 n2 -> rhr=nil/nil hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c0 w4 h1 r0 n0 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
            resolve c0 w4 h1 r0 n1 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
            resolve c0 w4 h1 r0 n2 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
            resolve c0 w4 h1 r1 n0 -> rhr=nil/nil hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c0 w4 h1 r1 n1 -> rhr=nil/nil hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c0 w4 h1 r1 n2 -> rhr=nil/nil hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c0 w4 h2 r0 n0 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
            resolve c0 w4 h2 r0 n1 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
            resolve c0 w4 h2 r0 n2 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
            resolve c0 w4 h2 r1 n0 -> rhr=nil/nil hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c0 w4 h2 r1 n1 -> rhr=nil/nil hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c0 w4 h2 r1 n2 -> rhr=nil/nil hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c0 w4 h3 r0 n0 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
            resolve c0 w4 h3 r0 n1 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
            resolve c0 w4 h3 r0 n2 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
            resolve c0 w4 h3 r1 n0 -> rhr=nil/nil hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c0 w4 h3 r1 n1 -> rhr=nil/nil hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c0 w4 h3 r1 n2 -> rhr=nil/nil hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c0 w4 h4 r0 n0 -> rhr=nil/nil hrv=nil/nil/none resp=nil/nil
            resolve c0 w4 h4 r0 n1 -> rhr=nil/nil hrv=41.3750/garmin-watch/fit371 resp=nil/nil
            resolve c0 w4 h4 r0 n2 -> rhr=nil/nil hrv=40.2500/garmin-watch/fit371 resp=nil/nil
            resolve c0 w4 h4 r1 n0 -> rhr=nil/nil hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c0 w4 h4 r1 n1 -> rhr=nil/nil hrv=41.3750/garmin-watch/fit371 resp=19.5833/garmin-watch
            resolve c0 w4 h4 r1 n2 -> rhr=nil/nil hrv=40.2500/garmin-watch/fit371 resp=19.5833/garmin-watch
            resolve c1 w0 h0 r0 n0 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
            resolve c1 w0 h0 r0 n1 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
            resolve c1 w0 h0 r0 n2 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
            resolve c1 w0 h0 r1 n0 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
            resolve c1 w0 h0 r1 n1 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
            resolve c1 w0 h0 r1 n2 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
            resolve c1 w0 h1 r0 n0 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
            resolve c1 w0 h1 r0 n1 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
            resolve c1 w0 h1 r0 n2 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
            resolve c1 w0 h1 r1 n0 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
            resolve c1 w0 h1 r1 n1 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
            resolve c1 w0 h1 r1 n2 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
            resolve c1 w0 h2 r0 n0 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
            resolve c1 w0 h2 r0 n1 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
            resolve c1 w0 h2 r0 n2 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
            resolve c1 w0 h2 r1 n0 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
            resolve c1 w0 h2 r1 n1 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
            resolve c1 w0 h2 r1 n2 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
            resolve c1 w0 h3 r0 n0 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
            resolve c1 w0 h3 r0 n1 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
            resolve c1 w0 h3 r0 n2 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
            resolve c1 w0 h3 r1 n0 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
            resolve c1 w0 h3 r1 n1 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
            resolve c1 w0 h3 r1 n2 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
            resolve c1 w0 h4 r0 n0 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
            resolve c1 w0 h4 r0 n1 -> rhr=58/my-whoop-noop hrv=41.3750/garmin-watch/fit371 resp=14.2000/my-whoop-noop
            resolve c1 w0 h4 r0 n2 -> rhr=58/my-whoop-noop hrv=40.2500/garmin-watch/fit371 resp=14.2000/my-whoop-noop
            resolve c1 w0 h4 r1 n0 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
            resolve c1 w0 h4 r1 n1 -> rhr=58/my-whoop-noop hrv=41.3750/garmin-watch/fit371 resp=14.2000/my-whoop-noop
            resolve c1 w0 h4 r1 n2 -> rhr=58/my-whoop-noop hrv=40.2500/garmin-watch/fit371 resp=14.2000/my-whoop-noop
            resolve c1 w1 h0 r0 n0 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
            resolve c1 w1 h0 r0 n1 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
            resolve c1 w1 h0 r0 n2 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
            resolve c1 w1 h0 r1 n0 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
            resolve c1 w1 h0 r1 n1 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
            resolve c1 w1 h0 r1 n2 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
            resolve c1 w1 h1 r0 n0 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
            resolve c1 w1 h1 r0 n1 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
            resolve c1 w1 h1 r0 n2 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
            resolve c1 w1 h1 r1 n0 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
            resolve c1 w1 h1 r1 n1 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
            resolve c1 w1 h1 r1 n2 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
            resolve c1 w1 h2 r0 n0 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
            resolve c1 w1 h2 r0 n1 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
            resolve c1 w1 h2 r0 n2 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
            resolve c1 w1 h2 r1 n0 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
            resolve c1 w1 h2 r1 n1 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
            resolve c1 w1 h2 r1 n2 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
            resolve c1 w1 h3 r0 n0 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
            resolve c1 w1 h3 r0 n1 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
            resolve c1 w1 h3 r0 n2 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
            resolve c1 w1 h3 r1 n0 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
            resolve c1 w1 h3 r1 n1 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
            resolve c1 w1 h3 r1 n2 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
            resolve c1 w1 h4 r0 n0 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
            resolve c1 w1 h4 r0 n1 -> rhr=58/my-whoop-noop hrv=41.3750/garmin-watch/fit371 resp=14.2000/my-whoop-noop
            resolve c1 w1 h4 r0 n2 -> rhr=58/my-whoop-noop hrv=40.2500/garmin-watch/fit371 resp=14.2000/my-whoop-noop
            resolve c1 w1 h4 r1 n0 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
            resolve c1 w1 h4 r1 n1 -> rhr=58/my-whoop-noop hrv=41.3750/garmin-watch/fit371 resp=19.5833/garmin-watch
            resolve c1 w1 h4 r1 n2 -> rhr=58/my-whoop-noop hrv=40.2500/garmin-watch/fit371 resp=19.5833/garmin-watch
            resolve c1 w2 h0 r0 n0 -> rhr=63/garmin-watch hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
            resolve c1 w2 h0 r0 n1 -> rhr=63/garmin-watch hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
            resolve c1 w2 h0 r0 n2 -> rhr=63/garmin-watch hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
            resolve c1 w2 h0 r1 n0 -> rhr=63/garmin-watch hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
            resolve c1 w2 h0 r1 n1 -> rhr=63/garmin-watch hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
            resolve c1 w2 h0 r1 n2 -> rhr=63/garmin-watch hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
            resolve c1 w2 h1 r0 n0 -> rhr=63/garmin-watch hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
            resolve c1 w2 h1 r0 n1 -> rhr=63/garmin-watch hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
            resolve c1 w2 h1 r0 n2 -> rhr=63/garmin-watch hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
            resolve c1 w2 h1 r1 n0 -> rhr=63/garmin-watch hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
            resolve c1 w2 h1 r1 n1 -> rhr=63/garmin-watch hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
            resolve c1 w2 h1 r1 n2 -> rhr=63/garmin-watch hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
            resolve c1 w2 h2 r0 n0 -> rhr=63/garmin-watch hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
            resolve c1 w2 h2 r0 n1 -> rhr=63/garmin-watch hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
            resolve c1 w2 h2 r0 n2 -> rhr=63/garmin-watch hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
            resolve c1 w2 h2 r1 n0 -> rhr=63/garmin-watch hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
            resolve c1 w2 h2 r1 n1 -> rhr=63/garmin-watch hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
            resolve c1 w2 h2 r1 n2 -> rhr=63/garmin-watch hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
            resolve c1 w2 h3 r0 n0 -> rhr=63/garmin-watch hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
            resolve c1 w2 h3 r0 n1 -> rhr=63/garmin-watch hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
            resolve c1 w2 h3 r0 n2 -> rhr=63/garmin-watch hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
            resolve c1 w2 h3 r1 n0 -> rhr=63/garmin-watch hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
            resolve c1 w2 h3 r1 n1 -> rhr=63/garmin-watch hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
            resolve c1 w2 h3 r1 n2 -> rhr=63/garmin-watch hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
            resolve c1 w2 h4 r0 n0 -> rhr=63/garmin-watch hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
            resolve c1 w2 h4 r0 n1 -> rhr=63/garmin-watch hrv=41.3750/garmin-watch/fit371 resp=14.2000/my-whoop-noop
            resolve c1 w2 h4 r0 n2 -> rhr=63/garmin-watch hrv=40.2500/garmin-watch/fit371 resp=14.2000/my-whoop-noop
            resolve c1 w2 h4 r1 n0 -> rhr=63/garmin-watch hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
            resolve c1 w2 h4 r1 n1 -> rhr=63/garmin-watch hrv=41.3750/garmin-watch/fit371 resp=19.5833/garmin-watch
            resolve c1 w2 h4 r1 n2 -> rhr=63/garmin-watch hrv=40.2500/garmin-watch/fit371 resp=19.5833/garmin-watch
            resolve c1 w3 h0 r0 n0 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=14.2000/my-whoop-noop
            resolve c1 w3 h0 r0 n1 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=14.2000/my-whoop-noop
            resolve c1 w3 h0 r0 n2 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=14.2000/my-whoop-noop
            resolve c1 w3 h0 r1 n0 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
            resolve c1 w3 h0 r1 n1 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
            resolve c1 w3 h0 r1 n2 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
            resolve c1 w3 h1 r0 n0 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=14.2000/my-whoop-noop
            resolve c1 w3 h1 r0 n1 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=14.2000/my-whoop-noop
            resolve c1 w3 h1 r0 n2 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=14.2000/my-whoop-noop
            resolve c1 w3 h1 r1 n0 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
            resolve c1 w3 h1 r1 n1 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
            resolve c1 w3 h1 r1 n2 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
            resolve c1 w3 h2 r0 n0 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=14.2000/my-whoop-noop
            resolve c1 w3 h2 r0 n1 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=14.2000/my-whoop-noop
            resolve c1 w3 h2 r0 n2 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=14.2000/my-whoop-noop
            resolve c1 w3 h2 r1 n0 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
            resolve c1 w3 h2 r1 n1 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
            resolve c1 w3 h2 r1 n2 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
            resolve c1 w3 h3 r0 n0 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=14.2000/my-whoop-noop
            resolve c1 w3 h3 r0 n1 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=14.2000/my-whoop-noop
            resolve c1 w3 h3 r0 n2 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=14.2000/my-whoop-noop
            resolve c1 w3 h3 r1 n0 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
            resolve c1 w3 h3 r1 n1 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
            resolve c1 w3 h3 r1 n2 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
            resolve c1 w3 h4 r0 n0 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=14.2000/my-whoop-noop
            resolve c1 w3 h4 r0 n1 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=14.2000/my-whoop-noop
            resolve c1 w3 h4 r0 n2 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=14.2000/my-whoop-noop
            resolve c1 w3 h4 r1 n0 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
            resolve c1 w3 h4 r1 n1 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
            resolve c1 w3 h4 r1 n2 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
            resolve c1 w4 h0 r0 n0 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
            resolve c1 w4 h0 r0 n1 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
            resolve c1 w4 h0 r0 n2 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
            resolve c1 w4 h0 r1 n0 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
            resolve c1 w4 h0 r1 n1 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
            resolve c1 w4 h0 r1 n2 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
            resolve c1 w4 h1 r0 n0 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
            resolve c1 w4 h1 r0 n1 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
            resolve c1 w4 h1 r0 n2 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
            resolve c1 w4 h1 r1 n0 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
            resolve c1 w4 h1 r1 n1 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
            resolve c1 w4 h1 r1 n2 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
            resolve c1 w4 h2 r0 n0 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
            resolve c1 w4 h2 r0 n1 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
            resolve c1 w4 h2 r0 n2 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
            resolve c1 w4 h2 r1 n0 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
            resolve c1 w4 h2 r1 n1 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
            resolve c1 w4 h2 r1 n2 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
            resolve c1 w4 h3 r0 n0 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
            resolve c1 w4 h3 r0 n1 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
            resolve c1 w4 h3 r0 n2 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
            resolve c1 w4 h3 r1 n0 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
            resolve c1 w4 h3 r1 n1 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
            resolve c1 w4 h3 r1 n2 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
            resolve c1 w4 h4 r0 n0 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=14.2000/my-whoop-noop
            resolve c1 w4 h4 r0 n1 -> rhr=58/my-whoop-noop hrv=41.3750/garmin-watch/fit371 resp=14.2000/my-whoop-noop
            resolve c1 w4 h4 r0 n2 -> rhr=58/my-whoop-noop hrv=40.2500/garmin-watch/fit371 resp=14.2000/my-whoop-noop
            resolve c1 w4 h4 r1 n0 -> rhr=58/my-whoop-noop hrv=31.7500/my-whoop-noop/noop resp=19.5833/garmin-watch
            resolve c1 w4 h4 r1 n1 -> rhr=58/my-whoop-noop hrv=41.3750/garmin-watch/fit371 resp=19.5833/garmin-watch
            resolve c1 w4 h4 r1 n2 -> rhr=58/my-whoop-noop hrv=40.2500/garmin-watch/fit371 resp=19.5833/garmin-watch
            resolve c2 w0 h0 r0 n0 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
            resolve c2 w0 h0 r0 n1 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
            resolve c2 w0 h0 r0 n2 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
            resolve c2 w0 h0 r1 n0 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
            resolve c2 w0 h0 r1 n1 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
            resolve c2 w0 h0 r1 n2 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
            resolve c2 w0 h1 r0 n0 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
            resolve c2 w0 h1 r0 n1 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
            resolve c2 w0 h1 r0 n2 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
            resolve c2 w0 h1 r1 n0 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
            resolve c2 w0 h1 r1 n1 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
            resolve c2 w0 h1 r1 n2 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
            resolve c2 w0 h2 r0 n0 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
            resolve c2 w0 h2 r0 n1 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
            resolve c2 w0 h2 r0 n2 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
            resolve c2 w0 h2 r1 n0 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
            resolve c2 w0 h2 r1 n1 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
            resolve c2 w0 h2 r1 n2 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
            resolve c2 w0 h3 r0 n0 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
            resolve c2 w0 h3 r0 n1 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
            resolve c2 w0 h3 r0 n2 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
            resolve c2 w0 h3 r1 n0 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
            resolve c2 w0 h3 r1 n1 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
            resolve c2 w0 h3 r1 n2 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
            resolve c2 w0 h4 r0 n0 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
            resolve c2 w0 h4 r0 n1 -> rhr=75/my-whoop-noop hrv=41.3750/garmin-watch/fit371 resp=19.0000/my-whoop-noop
            resolve c2 w0 h4 r0 n2 -> rhr=75/my-whoop-noop hrv=40.2500/garmin-watch/fit371 resp=19.0000/my-whoop-noop
            resolve c2 w0 h4 r1 n0 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
            resolve c2 w0 h4 r1 n1 -> rhr=75/my-whoop-noop hrv=41.3750/garmin-watch/fit371 resp=19.0000/my-whoop-noop
            resolve c2 w0 h4 r1 n2 -> rhr=75/my-whoop-noop hrv=40.2500/garmin-watch/fit371 resp=19.0000/my-whoop-noop
            resolve c2 w1 h0 r0 n0 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
            resolve c2 w1 h0 r0 n1 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
            resolve c2 w1 h0 r0 n2 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
            resolve c2 w1 h0 r1 n0 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c2 w1 h0 r1 n1 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c2 w1 h0 r1 n2 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c2 w1 h1 r0 n0 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
            resolve c2 w1 h1 r0 n1 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
            resolve c2 w1 h1 r0 n2 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
            resolve c2 w1 h1 r1 n0 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c2 w1 h1 r1 n1 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c2 w1 h1 r1 n2 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c2 w1 h2 r0 n0 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
            resolve c2 w1 h2 r0 n1 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
            resolve c2 w1 h2 r0 n2 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
            resolve c2 w1 h2 r1 n0 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c2 w1 h2 r1 n1 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c2 w1 h2 r1 n2 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c2 w1 h3 r0 n0 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
            resolve c2 w1 h3 r0 n1 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
            resolve c2 w1 h3 r0 n2 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
            resolve c2 w1 h3 r1 n0 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c2 w1 h3 r1 n1 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c2 w1 h3 r1 n2 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c2 w1 h4 r0 n0 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
            resolve c2 w1 h4 r0 n1 -> rhr=75/my-whoop-noop hrv=41.3750/garmin-watch/fit371 resp=19.0000/my-whoop-noop
            resolve c2 w1 h4 r0 n2 -> rhr=75/my-whoop-noop hrv=40.2500/garmin-watch/fit371 resp=19.0000/my-whoop-noop
            resolve c2 w1 h4 r1 n0 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c2 w1 h4 r1 n1 -> rhr=75/my-whoop-noop hrv=41.3750/garmin-watch/fit371 resp=19.5833/garmin-watch
            resolve c2 w1 h4 r1 n2 -> rhr=75/my-whoop-noop hrv=40.2500/garmin-watch/fit371 resp=19.5833/garmin-watch
            resolve c2 w2 h0 r0 n0 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.0000/my-whoop-noop
            resolve c2 w2 h0 r0 n1 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.0000/my-whoop-noop
            resolve c2 w2 h0 r0 n2 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.0000/my-whoop-noop
            resolve c2 w2 h0 r1 n0 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c2 w2 h0 r1 n1 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c2 w2 h0 r1 n2 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c2 w2 h1 r0 n0 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.0000/my-whoop-noop
            resolve c2 w2 h1 r0 n1 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.0000/my-whoop-noop
            resolve c2 w2 h1 r0 n2 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.0000/my-whoop-noop
            resolve c2 w2 h1 r1 n0 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c2 w2 h1 r1 n1 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c2 w2 h1 r1 n2 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c2 w2 h2 r0 n0 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.0000/my-whoop-noop
            resolve c2 w2 h2 r0 n1 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.0000/my-whoop-noop
            resolve c2 w2 h2 r0 n2 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.0000/my-whoop-noop
            resolve c2 w2 h2 r1 n0 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c2 w2 h2 r1 n1 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c2 w2 h2 r1 n2 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c2 w2 h3 r0 n0 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.0000/my-whoop-noop
            resolve c2 w2 h3 r0 n1 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.0000/my-whoop-noop
            resolve c2 w2 h3 r0 n2 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.0000/my-whoop-noop
            resolve c2 w2 h3 r1 n0 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c2 w2 h3 r1 n1 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c2 w2 h3 r1 n2 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c2 w2 h4 r0 n0 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.0000/my-whoop-noop
            resolve c2 w2 h4 r0 n1 -> rhr=63/garmin-watch hrv=41.3750/garmin-watch/fit371 resp=19.0000/my-whoop-noop
            resolve c2 w2 h4 r0 n2 -> rhr=63/garmin-watch hrv=40.2500/garmin-watch/fit371 resp=19.0000/my-whoop-noop
            resolve c2 w2 h4 r1 n0 -> rhr=63/garmin-watch hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c2 w2 h4 r1 n1 -> rhr=63/garmin-watch hrv=41.3750/garmin-watch/fit371 resp=19.5833/garmin-watch
            resolve c2 w2 h4 r1 n2 -> rhr=63/garmin-watch hrv=40.2500/garmin-watch/fit371 resp=19.5833/garmin-watch
            resolve c2 w3 h0 r0 n0 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.0000/my-whoop-noop
            resolve c2 w3 h0 r0 n1 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.0000/my-whoop-noop
            resolve c2 w3 h0 r0 n2 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.0000/my-whoop-noop
            resolve c2 w3 h0 r1 n0 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
            resolve c2 w3 h0 r1 n1 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
            resolve c2 w3 h0 r1 n2 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
            resolve c2 w3 h1 r0 n0 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.0000/my-whoop-noop
            resolve c2 w3 h1 r0 n1 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.0000/my-whoop-noop
            resolve c2 w3 h1 r0 n2 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.0000/my-whoop-noop
            resolve c2 w3 h1 r1 n0 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
            resolve c2 w3 h1 r1 n1 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
            resolve c2 w3 h1 r1 n2 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
            resolve c2 w3 h2 r0 n0 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.0000/my-whoop-noop
            resolve c2 w3 h2 r0 n1 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.0000/my-whoop-noop
            resolve c2 w3 h2 r0 n2 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.0000/my-whoop-noop
            resolve c2 w3 h2 r1 n0 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
            resolve c2 w3 h2 r1 n1 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
            resolve c2 w3 h2 r1 n2 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
            resolve c2 w3 h3 r0 n0 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.0000/my-whoop-noop
            resolve c2 w3 h3 r0 n1 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.0000/my-whoop-noop
            resolve c2 w3 h3 r0 n2 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.0000/my-whoop-noop
            resolve c2 w3 h3 r1 n0 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
            resolve c2 w3 h3 r1 n1 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
            resolve c2 w3 h3 r1 n2 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
            resolve c2 w3 h4 r0 n0 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.0000/my-whoop-noop
            resolve c2 w3 h4 r0 n1 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.0000/my-whoop-noop
            resolve c2 w3 h4 r0 n2 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.0000/my-whoop-noop
            resolve c2 w3 h4 r1 n0 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
            resolve c2 w3 h4 r1 n1 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
            resolve c2 w3 h4 r1 n2 -> rhr=49/garmin-watch hrv=52.5000/garmin-watch/fit370 resp=19.5833/garmin-watch
            resolve c2 w4 h0 r0 n0 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
            resolve c2 w4 h0 r0 n1 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
            resolve c2 w4 h0 r0 n2 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
            resolve c2 w4 h0 r1 n0 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c2 w4 h0 r1 n1 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c2 w4 h0 r1 n2 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c2 w4 h1 r0 n0 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
            resolve c2 w4 h1 r0 n1 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
            resolve c2 w4 h1 r0 n2 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
            resolve c2 w4 h1 r1 n0 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c2 w4 h1 r1 n1 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c2 w4 h1 r1 n2 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c2 w4 h2 r0 n0 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
            resolve c2 w4 h2 r0 n1 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
            resolve c2 w4 h2 r0 n2 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
            resolve c2 w4 h2 r1 n0 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c2 w4 h2 r1 n1 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c2 w4 h2 r1 n2 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c2 w4 h3 r0 n0 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
            resolve c2 w4 h3 r0 n1 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
            resolve c2 w4 h3 r0 n2 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
            resolve c2 w4 h3 r1 n0 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c2 w4 h3 r1 n1 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c2 w4 h3 r1 n2 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c2 w4 h4 r0 n0 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.0000/my-whoop-noop
            resolve c2 w4 h4 r0 n1 -> rhr=75/my-whoop-noop hrv=41.3750/garmin-watch/fit371 resp=19.0000/my-whoop-noop
            resolve c2 w4 h4 r0 n2 -> rhr=75/my-whoop-noop hrv=40.2500/garmin-watch/fit371 resp=19.0000/my-whoop-noop
            resolve c2 w4 h4 r1 n0 -> rhr=75/my-whoop-noop hrv=nil/nil/none resp=19.5833/garmin-watch
            resolve c2 w4 h4 r1 n1 -> rhr=75/my-whoop-noop hrv=41.3750/garmin-watch/fit371 resp=19.5833/garmin-watch
            resolve c2 w4 h4 r1 n2 -> rhr=75/my-whoop-noop hrv=40.2500/garmin-watch/fit371 resp=19.5833/garmin-watch
            era 0 0.0000
            era 1 0.0000
            era 2 1790899200.0000
            era 3 1790899200.0000
            era 4 0.0000
            era 5 0.0000
            era 6 1790899200.0000
            era 7 1790899200.0000
            effort garmin-A Garmin false
            effort garmin-A nil false
            effort strap-1 Garmin false
            effort strap-1 Polar false
            effort garmin-import Garmin false
            effort my-whoop nil false
            effort garmin-A-noop nil false
            vo2 2026-10-31 52.4
            vo2 2026-10-30 51.0
            vo2 2026-11-07 52.4
            vo2 2026-11-08 53.0
            vo2 2026-12-31 nil
            vo2 bad nil
            merge 2026-09-20=48.0 2026-10-01=50.1 2026-10-04=51.0 2026-10-05=47.5
            merge-watch-only 2026-10-01=50.1 2026-10-04=51.0 2026-10-31=52.4 2026-11-08=53.0
            merge-import-only 2026-09-20=48.0 2026-10-04=49.0 2026-10-05=47.5
            weeks 0 2026-11-07 [2026-09-26=48.0 2026-10-03=50.1 2026-10-10=47.5 2026-10-17=47.5 2026-10-24=47.5 2026-10-31=47.5]
            weeks 0 2026-10-03 [2026-09-26=48.0]
            weeks 0 2026-02-07 []
            weeks 0 bad []
            weeks 1 2026-11-07 []
            weeks 1 2026-10-03 []
            weeks 1 2026-02-07 []
            weeks 1 bad []
            weeks 2 2026-11-07 [2026-10-03=44.0 2026-10-10=44.0 2026-10-17=44.0 2026-10-24=44.0]
            weeks 2 2026-10-03 []
            weeks 2 2026-02-07 []
            weeks 2 bad []
            weeks 3 2026-11-07 [2026-01-03=40.0 2026-01-10=40.0 2026-01-17=40.0 2026-01-24=40.0 2026-03-21=42.0 2026-03-28=42.0 2026-04-04=42.0 2026-04-11=42.0]
            weeks 3 2026-10-03 [2026-01-03=40.0 2026-01-10=40.0 2026-01-17=40.0 2026-01-24=40.0 2026-03-21=42.0 2026-03-28=42.0 2026-04-04=42.0 2026-04-11=42.0]
            weeks 3 2026-02-07 [2026-01-03=40.0 2026-01-10=40.0 2026-01-17=40.0 2026-01-24=40.0]
            weeks 3 bad []
            weeks 4 2026-11-07 [2026-01-03=41.0 2026-01-10=41.0 2026-01-17=41.0 2026-01-24=41.0]
            weeks 4 2026-10-03 [2026-01-03=41.0 2026-01-10=41.0 2026-01-17=41.0 2026-01-24=41.0]
            weeks 4 2026-02-07 [2026-01-03=41.0 2026-01-10=41.0 2026-01-17=41.0 2026-01-24=41.0]
            weeks 4 bad []
        """.trimIndent()
    }
}
